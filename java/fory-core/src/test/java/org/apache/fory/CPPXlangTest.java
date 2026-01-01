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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

/** Executes cross-language tests against the C++ implementation. */
@Test
public class CPPXlangTest extends XlangTestBase {
  private static final String BAZEL_TARGET = "//cpp/fory/serialization:xlang_test_main";
  private volatile boolean binaryBuilt;

  @Override
  protected void ensurePeerReady() {
    String enabled = System.getenv("FORY_CPP_JAVA_CI");
    if (!"1".equals(enabled)) {
      //      throw new SkipException("Skipping CPPXlangTest: FORY_CPP_JAVA_CI not set to 1");
    }
    boolean bazelAvailable = true;
    try {
      List<String> command = new ArrayList<>();
      addBazelBootstrapFlags(command);
      command.add("version");
      Process process = new ProcessBuilder(command).directory(repoRoot()).start();
      if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
        bazelAvailable = false;
      }
    } catch (IOException | InterruptedException e) {
      bazelAvailable = false;
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }
    if (!bazelAvailable) {
      throw new SkipException("Skipping CPPXlangTest: bazel is not available");
    }
    try {
      ensureBinaryBuilt();
    } catch (IOException e) {
      throw new RuntimeException("Failed to build C++ peer binary: " + BAZEL_TARGET, e);
    }
  }

  @Override
  protected CommandContext buildCommandContext(String caseName, Path dataFile) throws IOException {
    ensureBinaryBuilt();
    String binaryPath =
        new File(repoRoot(), "bazel-bin/cpp/fory/serialization/xlang_test_main").getAbsolutePath();
    List<String> command = new ArrayList<>();
    command.add(binaryPath);
    command.add("--case");
    command.add(caseName);
    ImmutableMap<String, String> env = envBuilder(dataFile).build();
    return new CommandContext(command, env, repoRoot());
  }

  private void addBazelBootstrapFlags(List<String> command) {
    File root = repoRoot();
    String userRoot = new File(root, ".bazel_user_root").getAbsolutePath();
    command.add("bazel");
    command.add("--batch");
    command.add("--output_user_root=" + userRoot);
  }

  private File repoRoot() {
    return new File("../..").getAbsoluteFile();
  }

  private void ensureBinaryBuilt() throws IOException {
    if (binaryBuilt) {
      return;
    }
    synchronized (this) {
      if (binaryBuilt) {
        return;
      }
      runBazelCommand("build", BAZEL_TARGET);
      binaryBuilt = true;
    }
  }

  private void runBazelCommand(String... args) throws IOException {
    List<String> command = new ArrayList<>();
    addBazelBootstrapFlags(command);
    for (String arg : args) {
      command.add(arg);
    }
    Process process = new ProcessBuilder(command).directory(repoRoot()).inheritIO().start();
    try {
      if (!process.waitFor(15, TimeUnit.MINUTES)) {
        process.destroyForcibly();
        throw new IOException("Timed out while running bazel " + String.join(" ", args));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while running bazel " + args[0], e);
    }
    if (process.exitValue() != 0) {
      throw new IOException("bazel " + args[0] + " failed with exit code " + process.exitValue());
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

  @Override
  @Test
  public void testNullableFieldSchemaConsistentNotNull() throws java.io.IOException {
    super.testNullableFieldSchemaConsistentNotNull();
  }

  @Override
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
    // C++ has proper std::optional support and sends actual null values,
    // unlike Rust which sends default values. Override with C++-specific expectations.
    String caseName = "test_nullable_field_compatible_null";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(false)
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

    // C++ properly supports std::optional and sends actual null values
    // (unlike Rust which sends default values)
    Assert.assertEquals(result, obj);
  }

  @Test
  @Override
  public void testUnionXlang() throws java.io.IOException {
    // Skip: C++ doesn't have Union xlang support yet
    throw new SkipException("Skipping testUnionXlang: C++ Union xlang support not implemented");
  }
}
