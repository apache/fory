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
import org.testng.SkipException;
import org.testng.annotations.Test;

/** Executes cross-language tests against the Dart implementation. */
@Test
public class DartXlangTest extends XlangTestBase {
  private static final String DART_EXECUTABLE = "dart";
  private static final int DART_SETUP_TIMEOUT_SECONDS = 300;
  private static final File DART_WORK_DIR = new File("../../dart");
  private static final File DART_FORY_TEST_WORK_DIR = new File("../../dart/packages/fory-test");
  private static final File DART_XLANG_SOURCE_FILE =
      new File(DART_FORY_TEST_WORK_DIR, "lib/entity/xlang_test_models.dart");
  private static final File DART_XLANG_GENERATED_FILE =
      new File(DART_FORY_TEST_WORK_DIR, "lib/generated/xlang_test_models.g.dart");
  private static final String DART_MODULE =
      "packages/fory-test/test/cross_lang_test/xlang_test_main.dart";
  private static final List<String> DART_CODEGEN_COMMAND =
      Arrays.asList(
          DART_EXECUTABLE, "run", "build_runner", "build", "--delete-conflicting-outputs");

  private static final List<String> DART_BASE_COMMAND =
      Arrays.asList(DART_EXECUTABLE, "run", DART_MODULE, "<DART_TESTCASE>");

  private static final int DART_TESTCASE_INDEX = 3;

  @Override
  protected void ensurePeerReady() {
    String enabled = System.getenv("FORY_DART_JAVA_CI");
    if (!"1".equals(enabled)) {
      throw new SkipException("Skipping DartXlangTest: FORY_DART_JAVA_CI not set to 1");
    }
    boolean dartInstalled = true;
    try {
      Process process = new ProcessBuilder(DART_EXECUTABLE, "--version").start();
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        dartInstalled = false;
      }
    } catch (IOException | InterruptedException e) {
      dartInstalled = false;
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }
    if (!dartInstalled) {
      throw new SkipException("Skipping DartXlangTest: dart not installed");
    }
    ensureGeneratedXlangSpecs();
  }

  @Override
  protected CommandContext buildCommandContext(String caseName, Path dataFile) {
    List<String> command = new ArrayList<>(DART_BASE_COMMAND);
    command.set(DART_TESTCASE_INDEX, caseName);
    Map<String, String> env = envBuilder(dataFile);
    env.put("ENABLE_FORY_DEBUG_OUTPUT", "1");
    return new CommandContext(command, env, DART_WORK_DIR);
  }

  private static synchronized void ensureGeneratedXlangSpecs() {
    if (DART_XLANG_GENERATED_FILE.isFile()
        && DART_XLANG_GENERATED_FILE.lastModified() >= DART_XLANG_SOURCE_FILE.lastModified()) {
      return;
    }
    if (!TestUtils.executeCommand(
        DART_CODEGEN_COMMAND,
        DART_SETUP_TIMEOUT_SECONDS,
        Collections.emptyMap(),
        DART_FORY_TEST_WORK_DIR)) {
      throw new IllegalStateException(
          "Failed to generate Dart xlang specs by command: "
              + String.join(" ", DART_CODEGEN_COMMAND));
    }
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

  @Test(dataProvider = "enableCodegenParallel")
  public void testSimpleStruct(boolean enableCodegen) throws java.io.IOException {
    super.testSimpleStruct(enableCodegen);
  }

  @Test
  public void testSimpleNamedStructCodegenEnabled() throws java.io.IOException {
    super.testSimpleNamedStruct(false);
  }

  @Test
  public void testSimpleNamedStructCodegenDisabled() throws java.io.IOException {
    super.testSimpleNamedStruct(false);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testList(boolean enableCodegen) throws java.io.IOException {
    super.testList(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testMap(boolean enableCodegen) throws java.io.IOException {
    super.testMap(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testInteger(boolean enableCodegen) throws java.io.IOException {
    super.testInteger(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testItem(boolean enableCodegen) throws java.io.IOException {
    super.testItem(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testColor(boolean enableCodegen) throws java.io.IOException {
    super.testColor(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testStructWithList(boolean enableCodegen) throws java.io.IOException {
    super.testStructWithList(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testStructWithMap(boolean enableCodegen) throws java.io.IOException {
    super.testStructWithMap(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testCollectionElementRefOverride(boolean enableCodegen) throws java.io.IOException {
    super.testCollectionElementRefOverride(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testSkipIdCustom(boolean enableCodegen) throws java.io.IOException {
    super.testSkipIdCustom(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testSkipNameCustom(boolean enableCodegen) throws java.io.IOException {
    super.testSkipNameCustom(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testConsistentNamed(boolean enableCodegen) throws java.io.IOException {
    super.testConsistentNamed(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testStructVersionCheck(boolean enableCodegen) throws java.io.IOException {
    super.testStructVersionCheck(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testPolymorphicList(boolean enableCodegen) throws java.io.IOException {
    super.testPolymorphicList(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testPolymorphicMap(boolean enableCodegen) throws java.io.IOException {
    super.testPolymorphicMap(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testOneStringFieldSchemaConsistent(boolean enableCodegen) throws java.io.IOException {
    super.testOneStringFieldSchemaConsistent(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testOneStringFieldCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testOneStringFieldCompatible(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testTwoStringFieldCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testTwoStringFieldCompatible(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testSchemaEvolutionCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testSchemaEvolutionCompatible(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testOneEnumFieldSchemaConsistent(boolean enableCodegen) throws java.io.IOException {
    super.testOneEnumFieldSchemaConsistent(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testOneEnumFieldCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testOneEnumFieldCompatible(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testTwoEnumFieldCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testTwoEnumFieldCompatible(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testEnumSchemaEvolutionCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testEnumSchemaEvolutionCompatible(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testNullableFieldSchemaConsistentNotNull(boolean enableCodegen)
      throws java.io.IOException {
    super.testNullableFieldSchemaConsistentNotNull(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testNullableFieldSchemaConsistentNull(boolean enableCodegen)
      throws java.io.IOException {
    super.testNullableFieldSchemaConsistentNull(enableCodegen);
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testNullableFieldCompatibleNotNull(boolean enableCodegen) throws java.io.IOException {
    super.testNullableFieldCompatibleNotNull(enableCodegen);
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testNullableFieldCompatibleNull(boolean enableCodegen) throws java.io.IOException {
    super.testNullableFieldCompatibleNull(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testUnionXlang(boolean enableCodegen) throws java.io.IOException {
    super.testUnionXlang(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testRefSchemaConsistent(boolean enableCodegen) throws java.io.IOException {
    super.testRefSchemaConsistent(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testRefCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testRefCompatible(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testCircularRefSchemaConsistent(boolean enableCodegen) throws java.io.IOException {
    super.testCircularRefSchemaConsistent(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testCircularRefCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testCircularRefCompatible(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testUnsignedSchemaConsistent(boolean enableCodegen) throws java.io.IOException {
    super.testUnsignedSchemaConsistent(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testUnsignedSchemaConsistentSimple(boolean enableCodegen) throws java.io.IOException {
    super.testUnsignedSchemaConsistentSimple(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testUnsignedSchemaCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testUnsignedSchemaCompatible(enableCodegen);
  }
}
