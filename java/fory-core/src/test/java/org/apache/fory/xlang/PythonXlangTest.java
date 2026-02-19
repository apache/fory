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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.apache.fory.Fory;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.test.TestUtils;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

/** Executes cross-language tests against the Python implementation. */
@Test
public class PythonXlangTest extends XlangTestBase {
  private static final String PYTHON_EXECUTABLE = "python";
  private static final String PYTHON_MODULE = "pyfory.tests.xlang_test_main";

  private static final List<String> PYTHON_BASE_COMMAND =
      Arrays.asList(PYTHON_EXECUTABLE, "-m", PYTHON_MODULE, "<PYTHON_TESTCASE>");

  private static final int PYTHON_TESTCASE_INDEX = 3;

  @Override
  protected void ensurePeerReady() {
    String enabled = System.getenv("FORY_PYTHON_JAVA_CI");
    if (!"1".equals(enabled)) {
      throw new SkipException("Skipping PythonXlangTest: FORY_PYTHON_JAVA_CI not set to 1");
    }
    TestUtils.verifyPyforyInstalled();
  }

  @Override
  protected CommandContext buildCommandContext(String caseName, Path dataFile) {
    List<String> command = new ArrayList<>(PYTHON_BASE_COMMAND);
    command.set(PYTHON_TESTCASE_INDEX, caseName);
    Map<String, String> env = envBuilder(dataFile);
    return new CommandContext(command, env, new File("../../python"));
  }

  // ============================================================================
  // Skip tests that are similar to CrossLanguageTest.java
  // These tests are already covered in CrossLanguageTest which tests Java-Python
  // ============================================================================

  @Override
  @Test
  public void testBuffer() throws IOException {
    throw new SkipException("Skipping: similar test already covered in CrossLanguageTest");
  }

  @Override
  @Test
  public void testMurmurHash3() throws IOException {
    throw new SkipException("Skipping: similar test already covered in CrossLanguageTest");
  }

  @Override
  @Test
  public void testCrossLanguageSerializer() throws Exception {
    throw new SkipException("Skipping: similar test already covered in CrossLanguageTest");
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testList(boolean enableCodegen) throws IOException {
    throw new SkipException("Skipping: similar test already covered in CrossLanguageTest");
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testMap(boolean enableCodegen) throws IOException {
    throw new SkipException("Skipping: similar test already covered in CrossLanguageTest");
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testItem(boolean enableCodegen) throws IOException {
    throw new SkipException("Skipping: simple struct tests covered in CrossLanguageTest");
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testColor(boolean enableCodegen) throws IOException {
    throw new SkipException("Skipping: enum tests covered in CrossLanguageTest");
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testStructWithList(boolean enableCodegen) throws IOException {
    throw new SkipException("Skipping: struct with list covered in CrossLanguageTest");
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testStructWithMap(boolean enableCodegen) throws IOException {
    throw new SkipException("Skipping: struct with map covered in CrossLanguageTest");
  }

  @Test
  public void testBufferVar() throws IOException {
    throw new SkipException("Skipping: similar test already covered in CrossLanguageTest");
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testInteger(boolean enableCodegen) throws IOException {
    throw new SkipException("Skipping: similar test already covered in CrossLanguageTest");
  }

  // ============================================================================
  // Explicitly re-declare inherited test methods to enable running individual
  // tests via Maven: mvn test -Dtest=org.apache.fory.xlang.PythonXlangTest#testXxx
  //
  // Maven Surefire cannot find inherited test methods when using the #methodName
  // syntax for test selection. By overriding and forwarding to the parent class,
  // we make each test directly addressable while preserving the shared test logic.
  // ============================================================================

  @Override
  @Test
  public void testStringSerializer() throws Exception {
    super.testStringSerializer();
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testSimpleStruct(boolean enableCodegen) throws IOException {
    super.testSimpleStruct(enableCodegen);
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testSimpleNamedStruct(boolean enableCodegen) throws IOException {
    super.testSimpleNamedStruct(enableCodegen);
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testSkipIdCustom(boolean enableCodegen) throws IOException {
    super.testSkipIdCustom(enableCodegen);
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testSkipNameCustom(boolean enableCodegen) throws IOException {
    super.testSkipNameCustom(enableCodegen);
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testConsistentNamed(boolean enableCodegen) throws IOException {
    super.testConsistentNamed(enableCodegen);
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testStructVersionCheck(boolean enableCodegen) throws IOException {
    super.testStructVersionCheck(enableCodegen);
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testPolymorphicList(boolean enableCodegen) throws IOException {
    super.testPolymorphicList(enableCodegen);
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testPolymorphicMap(boolean enableCodegen) throws IOException {
    super.testPolymorphicMap(enableCodegen);
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testCollectionElementRefOverride(boolean enableCodegen) throws IOException {
    super.testCollectionElementRefOverride(enableCodegen);
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testNullableFieldSchemaConsistentNotNull(boolean enableCodegen) throws IOException {
    super.testNullableFieldSchemaConsistentNotNull(enableCodegen);
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testNullableFieldSchemaConsistentNull(boolean enableCodegen) throws IOException {
    super.testNullableFieldSchemaConsistentNull(enableCodegen);
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testNullableFieldCompatibleNotNull(boolean enableCodegen) throws IOException {
    super.testNullableFieldCompatibleNotNull(enableCodegen);
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testNullableFieldCompatibleNull(boolean enableCodegen) throws IOException {
    // Python properly supports Optional and sends actual null values,
    // unlike Rust which sends default values. Override with Python-specific expectations.
    String caseName = "test_nullable_field_compatible_null";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
            .withMetaCompressor(new NoOpMetaCompressor())
            .build();
    fory.register(NullableComprehensiveCompatible.class, 402);

    NullableComprehensiveCompatible obj = new NullableComprehensiveCompatible();
    // Base non-nullable primitive fields - must have values
    obj.byteField = 1;
    obj.shortField = 2;
    obj.intField = 42;
    obj.longField = 123456789L;
    obj.floatField = 1.5f;
    obj.doubleField = 2.5;
    obj.boolField = true;

    // Base non-nullable boxed fields - must have values
    obj.boxedInt = 10;
    obj.boxedLong = 20L;
    obj.boxedFloat = 1.1f;
    obj.boxedDouble = 2.2;
    obj.boxedBool = true;

    // Base non-nullable reference fields - must have values
    obj.stringField = "hello";
    obj.listField = Arrays.asList("a", "b", "c");
    obj.setField = new HashSet<>(Arrays.asList("x", "y"));
    obj.mapField = new HashMap<>();
    obj.mapField.put("key1", "value1");
    obj.mapField.put("key2", "value2");

    // Nullable group 1 - all null
    obj.nullableInt1 = null;
    obj.nullableLong1 = null;
    obj.nullableFloat1 = null;
    obj.nullableDouble1 = null;
    obj.nullableBool1 = null;

    // Nullable group 2 - all null
    obj.nullableString2 = null;
    obj.nullableList2 = null;
    obj.nullableSet2 = null;
    obj.nullableMap2 = null;

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(1024);
    fory.serialize(buffer, obj);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    NullableComprehensiveCompatible result =
        (NullableComprehensiveCompatible) fory.deserialize(buffer2);

    // Python properly supports Optional and sends actual null values
    // (unlike Rust which sends default values)
    Assert.assertEquals(result, obj);
  }

  @Override
  @Test(dataProvider = "enableCodegenParallel")
  public void testUnionXlang(boolean enableCodegen) throws IOException {
    // Skip: Python doesn't have Union xlang support yet
    throw new SkipException("Skipping testUnionXlang: Python Union xlang support not implemented");
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testRefSchemaConsistent(boolean enableCodegen) throws IOException {
    super.testRefSchemaConsistent(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testRefCompatible(boolean enableCodegen) throws IOException {
    super.testRefCompatible(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testCircularRefSchemaConsistent(boolean enableCodegen) throws IOException {
    super.testCircularRefSchemaConsistent(enableCodegen);
  }

  @Test(dataProvider = "enableCodegenParallel")
  public void testCircularRefCompatible(boolean enableCodegen) throws IOException {
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
