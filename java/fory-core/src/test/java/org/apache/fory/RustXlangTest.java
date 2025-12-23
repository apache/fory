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

package org.apache.fory;

import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.testng.SkipException;
import org.testng.annotations.Test;

/** Executes cross-language tests against the Rust implementation. */
@Test
public class RustXlangTest extends XlangTestBase {
  private static final String RUST_EXECUTABLE = "cargo";
  private static final String RUST_MODULE = "test_cross_language";

  private static final List<String> RUST_BASE_COMMAND =
      Arrays.asList(
          RUST_EXECUTABLE,
          "test",
          "--test",
          RUST_MODULE,
          "<RUST_TESTCASE>",
          "--",
          "--nocapture",
          "--ignored",
          "--exact");

  private static final int RUST_TESTCASE_INDEX = 4;

  @Override
  protected void ensurePeerReady() {
    String enabled = System.getenv("FORY_RUST_JAVA_CI");
    if (!"1".equals(enabled)) {
      throw new SkipException("Skipping RustXlangTest: FORY_RUST_JAVA_CI not set to 1");
    }
    boolean rustInstalled = true;
    try {
      Process process = new ProcessBuilder("rustc", "--version").start();
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        rustInstalled = false;
      }
    } catch (IOException | InterruptedException e) {
      rustInstalled = false;
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }
    if (!rustInstalled) {
      throw new SkipException("Skipping RustXlangTest: rust not installed");
    }
  }

  @Override
  protected CommandContext buildCommandContext(String caseName, Path dataFile) {
    List<String> command = new ArrayList<>(RUST_BASE_COMMAND);
    command.set(RUST_TESTCASE_INDEX, caseName);
    ImmutableMap<String, String> env =
        envBuilder(dataFile)
            .put("RUSTFLAGS", "-Awarnings")
            .put("RUST_BACKTRACE", "1")
            .put("ENABLE_FORY_DEBUG_OUTPUT", "1")
            .put("FORY_PANIC_ON_ERROR", "1")
            .build();
    return new CommandContext(command, env, new File("../../rust"));
  }

  @Test
  @Override
  public void testStructWithUnionFields() throws java.io.IOException {
    throw new SkipException("Union types not yet supported in Rust");
  }

  @Test
  @Override
  public void testTopLevelUnion() throws java.io.IOException {
    throw new SkipException("Union types not yet supported in Rust");
  }
}
