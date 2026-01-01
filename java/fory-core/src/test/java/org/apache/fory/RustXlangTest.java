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

  // ============================================================================
  // Test methods - duplicated from XlangTestBase for Maven Surefire discovery
  // ============================================================================

  @Test
  public void testBuffer() throws java.io.IOException {
    super.testBuffer();
  }

  @Test
  public void testBufferVar() throws java.io.IOException {
    super.testBufferVar();
  }

  @Test
  public void testMurmurHash3() throws java.io.IOException {
    super.testMurmurHash3();
  }

  @Test
  public void testStringSerializer() throws Exception {
    super.testStringSerializer();
  }

  @Test
  public void testCrossLanguageSerializer() throws Exception {
    super.testCrossLanguageSerializer();
  }

  @Test
  public void testSimpleStruct() throws java.io.IOException {
    super.testSimpleStruct();
  }

  @Test
  public void testSimpleNamedStruct() throws java.io.IOException {
    super.testSimpleNamedStruct();
  }

  @Test
  public void testList() throws java.io.IOException {
    super.testList();
  }

  @Test
  public void testMap() throws java.io.IOException {
    super.testMap();
  }

  @Test
  public void testInteger() throws java.io.IOException {
    super.testInteger();
  }

  @Test
  public void testItem() throws java.io.IOException {
    super.testItem();
  }

  @Test
  public void testColor() throws java.io.IOException {
    super.testColor();
  }

  @Test
  public void testStructWithList() throws java.io.IOException {
    super.testStructWithList();
  }

  @Test
  public void testStructWithMap() throws java.io.IOException {
    super.testStructWithMap();
  }

  @Test
  public void testSkipIdCustom() throws java.io.IOException {
    super.testSkipIdCustom();
  }

  @Test
  public void testSkipNameCustom() throws java.io.IOException {
    super.testSkipNameCustom();
  }

  @Test
  public void testConsistentNamed() throws java.io.IOException {
    super.testConsistentNamed();
  }

  @Test
  public void testStructVersionCheck() throws java.io.IOException {
    super.testStructVersionCheck();
  }

  @Test
  public void testPolymorphicList() throws java.io.IOException {
    super.testPolymorphicList();
  }

  @Test
  public void testPolymorphicMap() throws java.io.IOException {
    super.testPolymorphicMap();
  }

  @Test
  public void testOneStringFieldSchemaConsistent() throws java.io.IOException {
    super.testOneStringFieldSchemaConsistent();
  }

  @Test
  public void testOneStringFieldCompatible() throws java.io.IOException {
    super.testOneStringFieldCompatible();
  }

  @Test
  public void testTwoStringFieldCompatible() throws java.io.IOException {
    super.testTwoStringFieldCompatible();
  }

  @Test
  public void testSchemaEvolutionCompatible() throws java.io.IOException {
    super.testSchemaEvolutionCompatible();
  }

  @Test
  public void testOneEnumFieldSchemaConsistent() throws java.io.IOException {
    super.testOneEnumFieldSchemaConsistent();
  }

  @Test
  public void testOneEnumFieldCompatible() throws java.io.IOException {
    super.testOneEnumFieldCompatible();
  }

  @Test
  public void testTwoEnumFieldCompatible() throws java.io.IOException {
    super.testTwoEnumFieldCompatible();
  }

  @Test
  public void testEnumSchemaEvolutionCompatible() throws java.io.IOException {
    super.testEnumSchemaEvolutionCompatible();
  }

  @Test
  public void testNullableFieldSchemaConsistentNotNull() throws java.io.IOException {
    super.testNullableFieldSchemaConsistentNotNull();
  }

  @Test
  public void testNullableFieldSchemaConsistentNull() throws java.io.IOException {
    super.testNullableFieldSchemaConsistentNull();
  }

  @Override
  @Test
  public void testNullableFieldCompatibleNotNull() throws java.io.IOException {
    super.testNullableFieldCompatibleNotNull();
  }

  @Override
  @Test
  public void testNullableFieldCompatibleNull() throws java.io.IOException {
    super.testNullableFieldCompatibleNull();
  }

  @Test
  public void testUnionXlang() throws java.io.IOException {
    super.testUnionXlang();
  }
}
