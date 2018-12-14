/*
 * Copyright 2018 The GraphicsFuzz Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.graphicsfuzz.reducer;

import com.graphicsfuzz.common.transformreduce.GlslShaderJob;
import com.graphicsfuzz.common.transformreduce.ShaderJob;
import com.graphicsfuzz.common.util.ShaderJobFileOperations;
import com.graphicsfuzz.reducer.glslreducers.IReductionPlan;
import com.graphicsfuzz.reducer.glslreducers.MasterPlan;
import com.graphicsfuzz.reducer.glslreducers.NoMoreToReduceException;
import com.graphicsfuzz.reducer.reductionopportunities.FailedReductionException;
import com.graphicsfuzz.reducer.reductionopportunities.ReducerContext;
import com.graphicsfuzz.reducer.util.Simplify;
import com.graphicsfuzz.util.Constants;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReductionDriver {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReductionDriver.class);

  public static final boolean DEBUG_REDUCER = false;

  private static final int NUM_INITIAL_TRIES = 5;

  private final ReducerContext context;

  private final IReductionPlan plan;

  private final ShaderJobFileOperations fileOps;

  private int numSuccessfulReductions = 0;

  private final Set<String> failHashes;
  private final Set<String> passHashes;

  public ReductionDriver(ReducerContext context,
                         boolean verbose,
                         ShaderJobFileOperations fileOps) {
    this.context = context;
    this.fileOps = fileOps;
    this.plan = new MasterPlan(context, verbose);
    this.failHashes = new HashSet<>();
    this.passHashes = new HashSet<>();
  }

  public String doReduction(
        ShaderJob initialState,
        String shaderJobShortName,
        int fileCountOffset, // Used when continuing a reduction - added on to the number associated
        // with each reduction step during the current reduction.
        IFileJudge judge,
        File workDir,
        int stepLimit) throws IOException {

    // This is used for Vulkan compatibility.
    final boolean requiresUniformBindings = initialState.hasUniformBindings();
    if (initialState.hasUniformBindings()) {
      // We eliminate uniform bindings while applying reduction steps, and re-introduce them
      // each time we emit shaders.
      initialState.removeUniformBindings();
    }

    try {
      if (fileCountOffset > 0) {
        LOGGER.info("Continuing reduction for {}", shaderJobShortName);
      } else {
        LOGGER.info("Starting reduction for {}", shaderJobShortName);
        for (int i = 1; ; i++) {
          if (isInterestingNoCache(judge, initialState, requiresUniformBindings, shaderJobShortName,
              workDir)) {
            break;
          }
          LOGGER.info("Result from initial state is not interesting (attempt " + i + ")");
          if (i >= NUM_INITIAL_TRIES) {
            LOGGER.info("Tried " + NUM_INITIAL_TRIES + " times; stopping.");
            fileOps.createFile(new File(workDir, "NOT_INTERESTING"));
            return null;
          }
        }
        LOGGER.info("Result from initial state is interesting - proceeding with reduction.");
      }

      ShaderJob currentState = initialState;

      int stepCount = 0;
      boolean stoppedEarly = false;

      while (true) {
        LOGGER.info("Trying reduction attempt " + stepCount + " (" + numSuccessfulReductions
            + " successful so far).");
        ShaderJob newState;
        try {
          newState = applyReduction(currentState);
          stepCount++;
        } catch (NoMoreToReduceException exception) {
          LOGGER.info("No more to reduce; stopping.");
          break;
        }
        final int currentReductionAttempt = stepCount + fileCountOffset;
        String currentShaderJobShortName =
            getReductionStepShaderJobShortName(
                shaderJobShortName,
                currentReductionAttempt);
        if (isInterestingWithCache(judge, newState, requiresUniformBindings,
            currentShaderJobShortName,
            workDir)) {
          LOGGER.info("Successful reduction.");
          String currentStepShaderJobShortNameWithOutcome =
              getReductionStepShaderJobShortName(
                  shaderJobShortName,
                  currentReductionAttempt,
                  Optional.of("success"));
          fileOps.moveShaderJobFileTo(
              new File(workDir, currentShaderJobShortName + ".json"),
              new File(workDir, currentStepShaderJobShortNameWithOutcome + ".json"),
              true
          );
          numSuccessfulReductions++;
          currentState = newState;
          plan.update(true);
        } else {
          LOGGER.info("Failed reduction.");
          String currentStepShaderJobShortNameWithOutcome =
              getReductionStepShaderJobShortName(
                  shaderJobShortName,
                  currentReductionAttempt,
                  Optional.of("fail"));
          fileOps.moveShaderJobFileTo(
              new File(workDir, currentShaderJobShortName + ".json"),
              new File(workDir, currentStepShaderJobShortNameWithOutcome + ".json"),
              true
          );
          plan.update(false);
        }

        if (stepLimit > -1 && stepCount >= stepLimit) {
          LOGGER.info("Stopping reduction due to hitting step limit {}.", stepLimit);
          stoppedEarly = true;
          break;
        }
      }

      ShaderJob finalState = finaliseReduction(currentState);

      String finalOutputFilePrefix = shaderJobShortName + "_reduced_final";

      if (!isInterestingNoCache(judge, finalState, requiresUniformBindings, finalOutputFilePrefix,
          workDir)) {
        LOGGER.info(
            "Failed to simplify final reduction state! Reverting to the non-simplified state.");
        writeState(currentState, new File(workDir, finalOutputFilePrefix + ".json"),
            requiresUniformBindings);
      }

      if (stoppedEarly) {
        // Place a marker file to indicate that the reduction was not complete.
        fileOps.createFile(new File(workDir, Constants.REDUCTION_INCOMPLETE));
      }

      return finalOutputFilePrefix;
    } catch (FileNotFoundException | FileJudgeException exception) {
      throw new RuntimeException(exception);
    }
  }

  private boolean isInteresting(IFileJudge judge,
                                ShaderJob state,
                                boolean requiresUniformBindings,
                                String shaderJobShortName,
                                File workDir,
                                boolean useCache) throws IOException, FileJudgeException {
    final File shaderJobFile = new File(workDir, shaderJobShortName + ".json");
    final File resultFile = new File(workDir, shaderJobShortName + ".info.json");
    writeState(state, shaderJobFile, requiresUniformBindings);

    String hash = null;
    if (useCache) {
      hash = fileOps.getShaderJobFileHash(shaderJobFile);
      if (failHashes.contains(hash)) {
        return false;
      }
      if (passHashes.contains(hash)) {
        throw new RuntimeException("Reduction loop detected!");
      }
    }

    if (judge.isInteresting(
        shaderJobFile,
        resultFile)) {
      if (useCache) {
        passHashes.add(hash);
      }
      return true;
    }
    if (useCache) {
      failHashes.add(hash);
    }
    return false;
  }

  private boolean isInterestingWithCache(IFileJudge judge,
                                ShaderJob state,
                                boolean requiresUniformBindings,
                                String shaderJobShortName,
                                File workDir) throws IOException, FileJudgeException {

    return isInteresting(judge, state, requiresUniformBindings, shaderJobShortName, workDir, true);
  }

  private boolean isInterestingNoCache(IFileJudge judge,
                                ShaderJob state,
                                boolean requiresUniformBindings,
                                String shaderJobShortName,
                                File workDir) throws IOException, FileJudgeException {
    return isInteresting(judge, state, requiresUniformBindings, shaderJobShortName, workDir, false);
  }

  private void writeState(ShaderJob state, File shaderJobFileOutput,
                          boolean requiresUniformBindings) throws FileNotFoundException {
    if (requiresUniformBindings) {
      assert !state.hasUniformBindings();
      state.makeUniformBindings();
    }
    fileOps.writeShaderJobFile(
        state,
        shaderJobFileOutput,
        context.getEmitGraphicsFuzzDefines()
    );
    if (requiresUniformBindings) {
      assert state.hasUniformBindings();
      state.removeUniformBindings();
    }
  }

  public static String getReductionStepShaderJobShortName(String variantPrefix,
                                                          int currentReductionAttempt,
                                                          Optional<String> successIndicator) {
    return variantPrefix + "_reduced_" + String.format("%04d", currentReductionAttempt)
          + successIndicator
          .flatMap(item -> Optional.of("_" + item))
          .orElse("");
  }

  private String getReductionStepShaderJobShortName(String variantPrefix,
                                                    int currentReductionAttempt) {
    return getReductionStepShaderJobShortName(variantPrefix, currentReductionAttempt,
        Optional.empty());
  }

  private ShaderJob applyReduction(ShaderJob state) throws NoMoreToReduceException {
    int attempts = 0;
    final int maxAttempts = 3;
    while (true) {
      try {
        return plan.applyReduction(state);
      } catch (FailedReductionException exception) {
        attempts++;
        if (attempts == maxAttempts) {
          throw exception;
        }
      }
    }
  }

  public final ShaderJob finaliseReduction(ShaderJob state) {
    // Do final cleanup pass to get rid of macros
    return new GlslShaderJob(
        state.getLicense(),
        state.getUniformsInfo(),
        state.getShaders().stream().map(Simplify::simplify).collect(Collectors.toList()));
  }


}
