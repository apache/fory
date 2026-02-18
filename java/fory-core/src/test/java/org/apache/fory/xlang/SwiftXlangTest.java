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

package org.apache.fory.xlang;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.fory.test.TestUtils;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

/** Executes cross-language tests against the Swift implementation. */
@Test
public class SwiftXlangTest extends XlangTestBase {
  private static final String SWIFT_EXECUTABLE = "swift";
  private static final String SWIFT_PEER_TARGET = "ForySwiftXlangPeer";

  @Override
  protected void ensurePeerReady() {
    String enabled = System.getenv("FORY_SWIFT_JAVA_CI");
    if (!"1".equals(enabled)) {
      throw new SkipException("Skipping SwiftXlangTest: FORY_SWIFT_JAVA_CI not set to 1");
    }
    boolean swiftInstalled = true;
    try {
      Process process = new ProcessBuilder(SWIFT_EXECUTABLE, "--version").start();
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        swiftInstalled = false;
      }
    } catch (IOException | InterruptedException e) {
      swiftInstalled = false;
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }
    Assert.assertTrue(swiftInstalled, "swift is required for SwiftXlangTest");

    List<String> buildCommand =
        Arrays.asList(SWIFT_EXECUTABLE, "build", "-c", "release", "--product", SWIFT_PEER_TARGET);
    boolean built =
        TestUtils.executeCommand(
            buildCommand,
            600,
            Collections.singletonMap("ENABLE_FORY_DEBUG_OUTPUT", "1"),
            new File("../../swift"));
    Assert.assertTrue(built, "failed to build Swift xlang peer target " + SWIFT_PEER_TARGET);
  }

  @Override
  protected CommandContext buildCommandContext(String caseName, Path dataFile) {
    List<String> command = new ArrayList<>();
    command.add(SWIFT_EXECUTABLE);
    command.add("run");
    command.add("--skip-build");
    command.add("-c");
    command.add("release");
    command.add(SWIFT_PEER_TARGET);
    command.add("--case");
    command.add(caseName);

    Map<String, String> env = envBuilder(dataFile);
    env.put("ENABLE_FORY_DEBUG_OUTPUT", "1");
    return new CommandContext(command, env, new File("../../swift"));
  }
}
