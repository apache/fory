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
import java.util.concurrent.TimeUnit;
import org.apache.fory.Fory;
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
      throw new SkipException("Skipping CPPXlangTest: FORY_CPP_JAVA_CI not set to 1");
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
    Map<String, String> env = envBuilder(dataFile);
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

  @Test(groups = "xlang")
  public void testBuffer() throws java.io.IOException {
    super.testBuffer();
  }

  @Test(groups = "xlang")
  public void testBufferVar() throws java.io.IOException {
    super.testBufferVar();
  }

  @Test(groups = "xlang")
  public void testMurmurHash3() throws java.io.IOException {
    super.testMurmurHash3();
  }

  @Test(groups = "xlang")
  public void testStringSerializer() throws Exception {
    super.testStringSerializer();
  }

  @Test(groups = "xlang")
  public void testCrossLanguageSerializer() throws Exception {
    super.testCrossLanguageSerializer();
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testSimpleStruct(boolean enableCodegen) throws java.io.IOException {
    super.testSimpleStruct(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testSimpleNamedStruct(boolean enableCodegen) throws java.io.IOException {
    super.testSimpleNamedStruct(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testList(boolean enableCodegen) throws java.io.IOException {
    super.testList(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testMap(boolean enableCodegen) throws java.io.IOException {
    super.testMap(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testInteger(boolean enableCodegen) throws java.io.IOException {
    super.testInteger(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testItem(boolean enableCodegen) throws java.io.IOException {
    super.testItem(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testColor(boolean enableCodegen) throws java.io.IOException {
    super.testColor(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testStructWithList(boolean enableCodegen) throws java.io.IOException {
    super.testStructWithList(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testStructWithMap(boolean enableCodegen) throws java.io.IOException {
    super.testStructWithMap(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testCollectionElementRefOverride(boolean enableCodegen) throws java.io.IOException {
    super.testCollectionElementRefOverride(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testSkipIdCustom(boolean enableCodegen) throws java.io.IOException {
    super.testSkipIdCustom(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testSkipNameCustom(boolean enableCodegen) throws java.io.IOException {
    super.testSkipNameCustom(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testConsistentNamed(boolean enableCodegen) throws java.io.IOException {
    super.testConsistentNamed(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testStructVersionCheck(boolean enableCodegen) throws java.io.IOException {
    super.testStructVersionCheck(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testPolymorphicList(boolean enableCodegen) throws java.io.IOException {
    super.testPolymorphicList(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testPolymorphicMap(boolean enableCodegen) throws java.io.IOException {
    super.testPolymorphicMap(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testOneStringFieldSchemaConsistent(boolean enableCodegen) throws java.io.IOException {
    super.testOneStringFieldSchemaConsistent(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testOneStringFieldCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testOneStringFieldCompatible(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testTwoStringFieldCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testTwoStringFieldCompatible(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testSchemaEvolutionCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testSchemaEvolutionCompatible(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testOneEnumFieldSchemaConsistent(boolean enableCodegen) throws java.io.IOException {
    super.testOneEnumFieldSchemaConsistent(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testOneEnumFieldCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testOneEnumFieldCompatible(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testTwoEnumFieldCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testTwoEnumFieldCompatible(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testEnumSchemaEvolutionCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testEnumSchemaEvolutionCompatible(enableCodegen);
  }

  @Override
  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testNullableFieldSchemaConsistentNotNull(boolean enableCodegen)
      throws java.io.IOException {
    super.testNullableFieldSchemaConsistentNotNull(enableCodegen);
  }

  @Override
  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testNullableFieldSchemaConsistentNull(boolean enableCodegen)
      throws java.io.IOException {
    super.testNullableFieldSchemaConsistentNull(enableCodegen);
  }

  @Override
  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testNullableFieldCompatibleNotNull(boolean enableCodegen) throws java.io.IOException {
    super.testNullableFieldCompatibleNotNull(enableCodegen);
  }

  @Override
  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testNullableFieldCompatibleNull(boolean enableCodegen) throws java.io.IOException {
    // C++ has proper std::optional support and sends actual null values,
    // unlike Rust which sends default values. Override with C++-specific expectations.
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

    // C++ properly supports std::optional and sends actual null values
    // (unlike Rust which sends default values)
    Assert.assertEquals(result, obj);
  }

  @Override
  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testUnionXlang(boolean enableCodegen) throws java.io.IOException {
    // Skip: C++ doesn't have Union xlang support yet
    throw new SkipException("Skipping testUnionXlang: C++ Union xlang support not implemented");
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testRefSchemaConsistent(boolean enableCodegen) throws java.io.IOException {
    super.testRefSchemaConsistent(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testRefCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testRefCompatible(enableCodegen);
  }

  @Override
  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testCircularRefSchemaConsistent(boolean enableCodegen) throws java.io.IOException {
    super.testCircularRefSchemaConsistent(enableCodegen);
  }

  @Override
  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testCircularRefCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testCircularRefCompatible(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testUnsignedSchemaConsistent(boolean enableCodegen) throws java.io.IOException {
    super.testUnsignedSchemaConsistent(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testUnsignedSchemaConsistentSimple(boolean enableCodegen) throws java.io.IOException {
    super.testUnsignedSchemaConsistentSimple(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testUnsignedSchemaCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testUnsignedSchemaCompatible(enableCodegen);
  }
}
