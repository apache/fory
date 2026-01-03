/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fory.test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.testng.SkipException;

public class TestUtils {
  public static String random(int size, int rand) {
    return random(size, new Random(rand));
  }

  public static String random(int size, Random random) {
    char[] chars = new char[size];
    char start = ' ';
    char end = 'z' + 1;
    int gap = end - start;
    for (int i = 0; i < size; i++) {
      chars[i] = (char) (start + random.nextInt(gap));
    }
    return new String(chars);
  }

  private static ProcessBuilder buildProcess(
      List<String> command, Map<String, String> env, File workDir) {
    // redirectOutput doesn't work for forked jvm such as in maven sure.
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    if (workDir != null) {
      processBuilder.directory(workDir);
    }
    for (Map.Entry<String, String> entry : env.entrySet()) {
      processBuilder.environment().put(entry.getKey(), entry.getValue());
    }
    return processBuilder;
  }

  private static boolean executeCommand(
      ProcessBuilder processBuilder, List<String> command, int waitTimeoutSeconds) {
    try {
      processBuilder.inheritIO();
      Process process = processBuilder.start();
      boolean finished = process.waitFor(waitTimeoutSeconds, TimeUnit.SECONDS);
      if (finished) {
        return process.exitValue() == 0;
      } else {
        process.destroy();
        return false;
      }
    } catch (Exception e) {
      throw new RuntimeException(
          "Error executing command " + String.join(" ", command) + ": " + e.getMessage(), e);
    }
  }

  public static boolean executeCommand(
      List<String> command, int waitTimeoutSeconds, Map<String, String> env, File workDir) {
    String envStr =
        env.entrySet().stream()
            .filter(e -> !"DATA_FILE".equals(e.getKey()))
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining(" "));
    System.out.println("Executing command: " + envStr + " " + String.join(" ", command));
    ProcessBuilder processBuilder = buildProcess(command, env, workDir);
    return executeCommand(processBuilder, command, waitTimeoutSeconds);
  }

  public static boolean executeCommand(
      List<String> command, int waitTimeoutSeconds, Map<String, String> env) {
    return executeCommand(command, waitTimeoutSeconds, env, null);
  }

  public static void verifyPyforyInstalled() {
    // Don't skip in CI, fail if something goes wrong instead
    if (System.getenv("FORY_CI") != null) {
      return;
    }
    try {
      if (executeCommand(
          Arrays.asList(
              "python",
              "-c",
              "import importlib.util, sys; sys.exit(0 if importlib.util.find_spec('pyfory') is None else 1)"),
          10,
          Collections.emptyMap())) {
        throw new SkipException("pyfory not installed");
      }
    } catch (RuntimeException e) {
      if (e.getCause() instanceof IOException
          && e.getMessage().contains("No such file or directory")) {
        throw new SkipException("Python not installed or not found in PATH");
      }
      throw e;
    }
  }
}
