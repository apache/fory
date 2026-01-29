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
import java.util.List;
import java.util.Map;
import org.testng.SkipException;
import org.testng.annotations.Test;

/** Executes cross-language tests against the Rust implementation. */
@Test
public class JavaScriptXlangTest extends XlangTestBase {
  private static final String NODE_EXECUTABLE = "npx";
  private static final String NODE_MODULE = "crossLanguage.test.ts";

  private static final List<String> RUST_BASE_COMMAND =
      Arrays.asList(NODE_EXECUTABLE, "jest", NODE_MODULE, "-t", "caseName");

  private static final int NODE_TESTCASE_INDEX = 4;

  @Override
  protected void ensurePeerReady() {
    String enabled = System.getenv("FORY_JAVASCRIPT_JAVA_CI");
    if (!"1".equals(enabled)) {
      throw new SkipException("Skipping JavaScriptXlangTest: FORY_JAVASCRIPT_JAVA_CI not set to 1");
    }
    boolean nodeInstalled = true;
    try {
      Process process = new ProcessBuilder("node", "--version").start();
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        nodeInstalled = false;
      }
    } catch (IOException | InterruptedException e) {
      nodeInstalled = false;
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }
    if (!nodeInstalled) {
      throw new SkipException("Skipping JavaScriptXlangTest: nodejs not installed");
    }
  }

  @Override
  protected CommandContext buildCommandContext(String caseName, Path dataFile) {
    List<String> command = new ArrayList<>(RUST_BASE_COMMAND);
    // jest use regexp to match the castName. And '$' at end to ignore matching error.
    command.set(NODE_TESTCASE_INDEX, caseName + "$");
    Map<String, String> env = envBuilder(dataFile);
    return new CommandContext(command, env, new File("../../javascript"));
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

  @Test(dataProvider = "enableCodegen")
  public void testSimpleStruct(boolean enableCodegen) throws java.io.IOException {
    super.testSimpleStruct(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testSimpleNamedStruct(boolean enableCodegen) throws java.io.IOException {
    super.testSimpleNamedStruct(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testList(boolean enableCodegen) throws java.io.IOException {
    super.testList(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testMap(boolean enableCodegen) throws java.io.IOException {
    super.testMap(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testInteger(boolean enableCodegen) throws java.io.IOException {
    super.testInteger(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testItem(boolean enableCodegen) throws java.io.IOException {
    super.testItem(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testColor(boolean enableCodegen) throws java.io.IOException {
    super.testColor(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testStructWithList(boolean enableCodegen) throws java.io.IOException {
    super.testStructWithList(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testStructWithMap(boolean enableCodegen) throws java.io.IOException {
    super.testStructWithMap(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testSkipIdCustom(boolean enableCodegen) throws java.io.IOException {
    super.testSkipIdCustom(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testSkipNameCustom(boolean enableCodegen) throws java.io.IOException {
    super.testSkipNameCustom(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testConsistentNamed(boolean enableCodegen) throws java.io.IOException {
    super.testConsistentNamed(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testStructVersionCheck(boolean enableCodegen) throws java.io.IOException {
    super.testStructVersionCheck(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testPolymorphicList(boolean enableCodegen) throws java.io.IOException {
    super.testPolymorphicList(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testPolymorphicMap(boolean enableCodegen) throws java.io.IOException {
    super.testPolymorphicMap(enableCodegen);
  }

  @Override
  @Test(dataProvider = "enableCodegen")
  public void testCollectionElementRefOverride(boolean enableCodegen) throws java.io.IOException {
    throw new SkipException("Skipping: JavaScript xlang test not implemented for this case");
  }

  @Test(dataProvider = "enableCodegen")
  public void testOneStringFieldSchemaConsistent(boolean enableCodegen) throws java.io.IOException {
    super.testOneStringFieldSchemaConsistent(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testOneStringFieldCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testOneStringFieldCompatible(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testTwoStringFieldCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testTwoStringFieldCompatible(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testSchemaEvolutionCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testSchemaEvolutionCompatible(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testOneEnumFieldSchemaConsistent(boolean enableCodegen) throws java.io.IOException {
    super.testOneEnumFieldSchemaConsistent(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testOneEnumFieldCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testOneEnumFieldCompatible(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testTwoEnumFieldCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testTwoEnumFieldCompatible(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testEnumSchemaEvolutionCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testEnumSchemaEvolutionCompatible(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testNullableFieldSchemaConsistentNotNull(boolean enableCodegen)
      throws java.io.IOException {
    super.testNullableFieldSchemaConsistentNotNull(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testNullableFieldSchemaConsistentNull(boolean enableCodegen)
      throws java.io.IOException {
    super.testNullableFieldSchemaConsistentNull(enableCodegen);
  }

  @Override
  @Test(dataProvider = "enableCodegen")
  public void testNullableFieldCompatibleNotNull(boolean enableCodegen) throws java.io.IOException {
    super.testNullableFieldCompatibleNotNull(enableCodegen);
  }

  @Override
  @Test(dataProvider = "enableCodegen")
  public void testNullableFieldCompatibleNull(boolean enableCodegen) throws java.io.IOException {
    super.testNullableFieldCompatibleNull(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testUnionXlang(boolean enableCodegen) throws java.io.IOException {
    super.testUnionXlang(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testRefSchemaConsistent(boolean enableCodegen) throws java.io.IOException {
    super.testRefSchemaConsistent(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testRefCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testRefCompatible(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testCircularRefSchemaConsistent(boolean enableCodegen) throws java.io.IOException {
    super.testCircularRefSchemaConsistent(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testCircularRefCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testCircularRefCompatible(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testUnsignedSchemaConsistent(boolean enableCodegen) throws java.io.IOException {
    super.testUnsignedSchemaConsistent(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testUnsignedSchemaConsistentSimple(boolean enableCodegen) throws java.io.IOException {
    super.testUnsignedSchemaConsistentSimple(enableCodegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void testUnsignedSchemaCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testUnsignedSchemaCompatible(enableCodegen);
  }
}
