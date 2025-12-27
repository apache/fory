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
import java.util.Collections;
import java.util.List;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.test.TestUtils;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

/** Executes cross-language tests against the Go implementation. */
@Test
public class GoXlangTest extends XlangTestBase {
  private static final boolean IS_WINDOWS =
      System.getProperty("os.name").toLowerCase().contains("windows");
  private static final String GO_BINARY = IS_WINDOWS ? "xlang_test_main.exe" : "xlang_test_main";

  @Override
  protected void ensurePeerReady() {
    String enabled = System.getenv("FORY_GO_JAVA_CI");
    if (!"1".equals(enabled)) {
      throw new SkipException("Skipping GoXlangTest: FORY_GO_JAVA_CI not set to 1");
    }
    boolean goInstalled = true;
    try {
      Process process = new ProcessBuilder("go", "version").start();
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        goInstalled = false;
      }
    } catch (IOException | InterruptedException e) {
      goInstalled = false;
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }
    if (!goInstalled) {
      throw new SkipException("Skipping GoXlangTest: go not installed");
    }
    // Build Go xlang_test_main binary
    List<String> buildCommand =
        Arrays.asList("go", "build", "-o", "tests/" + GO_BINARY, "tests/xlang/xlang_test_main.go");
    boolean buildSuccess =
        TestUtils.executeCommand(
            buildCommand, 60, Collections.emptyMap(), new File("../../go/fory"));
    if (!buildSuccess) {
      throw new SkipException("Skipping GoXlangTest: failed to build " + GO_BINARY);
    }
    // Check if binary exists
    File binaryFile = new File("../../go/fory/tests/" + GO_BINARY);
    if (!binaryFile.exists()) {
      throw new SkipException(
          "Skipping GoXlangTest: "
              + GO_BINARY
              + " not found after build. Please check build output.");
    }
  }

  @Override
  protected CommandContext buildCommandContext(String caseName, Path dataFile) {
    List<String> command = new ArrayList<>();
    // On Windows, use the binary name directly; on Unix, use ./ prefix
    command.add(IS_WINDOWS ? GO_BINARY : "./" + GO_BINARY);
    command.add("--case");
    command.add(caseName);
    ImmutableMap<String, String> env = envBuilder(dataFile).build();
    return new CommandContext(command, env, new File("../../go/fory/tests"));
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
  @Override
  public void testEnumSchemaEvolutionCompatible() throws java.io.IOException {
    // Go-specific override: Go writes null for nil pointers (nullable=true by default)
    String caseName = "test_enum_schema_evolution_compatible";
    // Fory for TwoEnumFieldStruct
    Fory fory2 =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory2.register(TestEnum.class, 210);
    fory2.register(TwoEnumFieldStruct.class, 211);

    // Fory for EmptyStruct and OneEnumFieldStruct with same type ID
    Fory foryEmpty =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    foryEmpty.register(TestEnum.class, 210);
    foryEmpty.register(EmptyStruct.class, 211);

    Fory fory1 =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory1.register(TestEnum.class, 210);
    fory1.register(OneEnumFieldStruct.class, 211);

    // Test 1: Serialize TwoEnumFieldStruct, deserialize as Empty
    TwoEnumFieldStruct obj2 = new TwoEnumFieldStruct();
    obj2.f1 = TestEnum.VALUE_A;
    obj2.f2 = TestEnum.VALUE_B;

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(128);
    fory2.serialize(buffer, obj2);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());

    // Deserialize as EmptyStruct (should skip all fields)
    EmptyStruct emptyResult = (EmptyStruct) foryEmpty.deserialize(buffer2);
    Assert.assertNotNull(emptyResult);

    // Test 2: Serialize OneEnumFieldStruct, deserialize as TwoEnumFieldStruct
    OneEnumFieldStruct obj1 = new OneEnumFieldStruct();
    obj1.f1 = TestEnum.VALUE_C;

    buffer = MemoryBuffer.newHeapBuffer(64);
    fory1.serialize(buffer, obj1);

    String caseName2 = "test_enum_schema_evolution_compatible_reverse";
    ExecutionContext ctx2 = prepareExecution(caseName2, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx2);

    MemoryBuffer buffer3 = readBuffer(ctx2.dataFile());
    TwoEnumFieldStruct result2 = (TwoEnumFieldStruct) fory2.deserialize(buffer3);
    Assert.assertEquals(result2.f1, TestEnum.VALUE_C);
    // Go writes null for nil pointers (nullable=true by default for pointer types)
    Assert.assertNull(result2.f2);
  }

  @Test
  @Override
  public void testUnionXlang() throws java.io.IOException {
    // Skip: Go doesn't have Union xlang support yet
    throw new SkipException("Skipping testUnionXlang: Go Union xlang support not implemented");
  }
}
