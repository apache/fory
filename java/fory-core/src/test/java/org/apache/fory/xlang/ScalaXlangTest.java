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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.annotation.ForyStruct;
import org.apache.fory.annotation.Nullable;
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.serializer.UnionSerializer;
import org.apache.fory.test.TestUtils;
import org.apache.fory.type.union.Union;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/** Executes Java-driven Scala 3 macro xlang serializer tests. */
@Test
public class ScalaXlangTest extends XlangTestBase {
  private static final String DERIVED_CASE = "derived_struct_round_trip";
  private static final String KNOWN_UNION_CASE = "known_union_case_round_trip";
  private static final String UNKNOWN_UNION_CASE = "unknown_union_case_round_trip";
  private static final String NAMESPACE = "scala_peer";
  private static final File SCALA_DIR = new File("../../scala");
  private static final File SCALA_CLASSPATH_FILE =
      new File(SCALA_DIR, "target/scala-xlang-test-classpath");
  private static final String JAVA_EXECUTABLE =
      new File(new File(System.getProperty("java.home"), "bin"), "java").getAbsolutePath();
  private static volatile String scalaPeerClasspath;

  @DataProvider(name = "enableCodegenParallel")
  public static Object[][] enableCodegenParallel() {
    return enableCodegen();
  }

  @Override
  protected void ensurePeerReady() {
    String enabled = System.getenv("FORY_SCALA_JAVA_CI");
    if (!"1".equals(enabled)) {
      throw new SkipException("Skipping ScalaXlangTest: FORY_SCALA_JAVA_CI not set to 1");
    }
    boolean buildSuccess =
        TestUtils.executeCommand(
            Arrays.asList("sbt", "--batch", "++3.3.1", "writeTestClasspath"),
            240,
            Collections.emptyMap(),
            SCALA_DIR);
    if (!buildSuccess) {
      throw new AssertionError("Failed to compile Scala xlang peer");
    }
    try {
      scalaPeerClasspath =
          new String(Files.readAllBytes(SCALA_CLASSPATH_FILE.toPath()), StandardCharsets.UTF_8)
              .trim();
    } catch (IOException e) {
      throw new AssertionError("Failed to read Scala xlang peer classpath", e);
    }
  }

  @Override
  protected CommandContext buildCommandContext(String caseName, Path dataFile) {
    if (scalaPeerClasspath == null || scalaPeerClasspath.isEmpty()) {
      throw new IllegalStateException("Scala xlang peer classpath is not initialized");
    }
    return new CommandContext(
        Arrays.asList(
            JAVA_EXECUTABLE,
            "-cp",
            scalaPeerClasspath,
            "org.apache.fory.serializer.scala.ScalaXlangPeer",
            caseName,
            dataFile.toAbsolutePath().toString()),
        envBuilder(dataFile),
        SCALA_DIR);
  }

  @Test(groups = "xlang")
  public void testDerivedStructRoundTrip() throws IOException {
    Fory fory = newFory();
    registerScalaPeerTypes(fory);

    ScalaPeerUserMirror request = new ScalaPeerUserMirror();
    request.id = 41;
    request.name = "java";
    request.email = "java@example.com";

    ExecutionContext context = executePeer(DERIVED_CASE, fory, request);
    ScalaPeerUserMirror response =
        (ScalaPeerUserMirror) fory.deserialize(readBuffer(context.dataFile()));
    Assert.assertEquals(response.id, 42);
    Assert.assertEquals(response.name, "scala-java");
    Assert.assertNull(response.email);
  }

  @Test(groups = "xlang")
  public void testKnownUnionCaseRoundTrip() throws IOException {
    Fory fory = newFory();
    registerScalaPeerTypes(fory);

    ScalaPeerUserMirror user = new ScalaPeerUserMirror();
    user.id = 41;
    user.name = "java";
    user.email = "java@example.com";

    ExecutionContext context =
        executePeer(KNOWN_UNION_CASE, fory, new ScalaPeerTargetMirror(0, user));
    ScalaPeerTargetMirror response =
        (ScalaPeerTargetMirror) fory.deserialize(readBuffer(context.dataFile()));
    Assert.assertEquals(response.getIndex(), 0);
    Assert.assertTrue(response.getValue() instanceof ScalaPeerUserMirror);
    ScalaPeerUserMirror responseUser = (ScalaPeerUserMirror) response.getValue();
    Assert.assertEquals(responseUser.id, 42);
    Assert.assertEquals(responseUser.name, "scala-java");
    Assert.assertNull(responseUser.email);
  }

  @Test(groups = "xlang")
  public void testUnknownUnionCaseRoundTrip() throws IOException {
    Fory fory = newFory();
    registerScalaPeerTypes(fory);

    ScalaPeerUserMirror unknownPayload = new ScalaPeerUserMirror();
    unknownPayload.id = 99;
    unknownPayload.name = "future";
    unknownPayload.email = "future@example.com";

    ExecutionContext context =
        executePeer(UNKNOWN_UNION_CASE, fory, new ScalaPeerTargetMirror(99, unknownPayload));
    ScalaPeerTargetMirror response =
        (ScalaPeerTargetMirror) fory.deserialize(readBuffer(context.dataFile()));
    Assert.assertEquals(response.getIndex(), 99);
    Assert.assertTrue(response.getValue() instanceof ScalaPeerUserMirror);
    ScalaPeerUserMirror responsePayload = (ScalaPeerUserMirror) response.getValue();
    Assert.assertEquals(responsePayload.id, 100);
    Assert.assertEquals(responsePayload.name, "scala-future");
    Assert.assertNull(responsePayload.email);
  }

  private ExecutionContext executePeer(String caseName, Fory fory, Object request)
      throws IOException {
    MemoryBuffer buffer = MemoryUtils.buffer(128);
    fory.serialize(buffer, request);
    ExecutionContext context = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(context, 180);
    return context;
  }

  private static Fory newFory() {
    return Fory.builder()
        .withLanguage(Language.XLANG)
        .withCompatible(true)
        .requireClassRegistration(true)
        .build();
  }

  private static void registerScalaPeerTypes(Fory fory) {
    fory.register(ScalaPeerUserMirror.class, NAMESPACE, "ScalaPeerUser");
    fory.registerUnion(
        ScalaPeerTargetMirror.class,
        NAMESPACE,
        "ScalaPeerTarget",
        new UnionSerializer(fory.getTypeResolver(), ScalaPeerTargetMirror.class));
  }

  @Data
  @ForyStruct
  public static class ScalaPeerUserMirror {
    @ForyField(id = 1)
    public int id;

    @ForyField(id = 2)
    public String name;

    @Nullable
    @ForyField(id = 3)
    public String email;
  }

  public static final class ScalaPeerTargetMirror extends Union {
    public ScalaPeerTargetMirror(int index, Object value) {
      super(index, value);
    }
  }

  // ============================================================================
  // Test methods - duplicated from XlangTestBase for Maven Surefire discovery
  // ============================================================================

  @Test(groups = "xlang")
  public void testBuffer() throws IOException {
    super.testBuffer();
  }

  @Test(groups = "xlang")
  public void testBufferVar() throws IOException {
    super.testBufferVar();
  }

  @Test(groups = "xlang")
  public void testMurmurHash3() throws IOException {
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
  public void testSimpleStruct(boolean enableCodegen) throws IOException {
    super.testSimpleStruct(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testSimpleNamedStruct(boolean enableCodegen) throws IOException {
    super.testSimpleNamedStruct(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testStructEvolvingOverride(boolean enableCodegen) throws IOException {
    super.testStructEvolvingOverride(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testList(boolean enableCodegen) throws IOException {
    super.testList(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testMap(boolean enableCodegen) throws IOException {
    super.testMap(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testInteger(boolean enableCodegen) throws IOException {
    super.testInteger(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testDecimal(boolean enableCodegen) throws IOException {
    super.testDecimal(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testItem(boolean enableCodegen) throws IOException {
    super.testItem(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testColor(boolean enableCodegen) throws IOException {
    super.testColor(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testStructWithList(boolean enableCodegen) throws IOException {
    super.testStructWithList(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testStructWithMap(boolean enableCodegen) throws IOException {
    super.testStructWithMap(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testNestedAnnotatedContainerSchemaConsistent(boolean enableCodegen)
      throws IOException {
    super.testNestedAnnotatedContainerSchemaConsistent(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testNestedAnnotatedContainerCompatible(boolean enableCodegen) throws IOException {
    super.testNestedAnnotatedContainerCompatible(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testCollectionElementRefOverride(boolean enableCodegen) throws IOException {
    super.testCollectionElementRefOverride(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testCollectionElementRefRemoteTracking(boolean enableCodegen) throws IOException {
    super.testCollectionElementRefRemoteTracking(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testSkipIdCustom(boolean enableCodegen) throws IOException {
    super.testSkipIdCustom(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testSkipNameCustom(boolean enableCodegen) throws IOException {
    super.testSkipNameCustom(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testConsistentNamed(boolean enableCodegen) throws IOException {
    super.testConsistentNamed(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testStructVersionCheck(boolean enableCodegen) throws IOException {
    super.testStructVersionCheck(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testReducedPrecisionFloatStruct(boolean enableCodegen) throws IOException {
    super.testReducedPrecisionFloatStruct(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testReducedPrecisionFloatStructCompatibleFieldSkip(boolean enableCodegen)
      throws IOException {
    super.testReducedPrecisionFloatStructCompatibleFieldSkip(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testPolymorphicList(boolean enableCodegen) throws IOException {
    super.testPolymorphicList(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testPolymorphicMap(boolean enableCodegen) throws IOException {
    super.testPolymorphicMap(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testOneStringFieldSchemaConsistent(boolean enableCodegen) throws IOException {
    super.testOneStringFieldSchemaConsistent(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testOneStringFieldCompatible(boolean enableCodegen) throws IOException {
    super.testOneStringFieldCompatible(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testTwoStringFieldCompatible(boolean enableCodegen) throws IOException {
    super.testTwoStringFieldCompatible(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testSchemaEvolutionCompatible(boolean enableCodegen) throws IOException {
    super.testSchemaEvolutionCompatible(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testOneEnumFieldSchemaConsistent(boolean enableCodegen) throws IOException {
    super.testOneEnumFieldSchemaConsistent(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testOneEnumFieldCompatible(boolean enableCodegen) throws IOException {
    super.testOneEnumFieldCompatible(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testTwoEnumFieldCompatible(boolean enableCodegen) throws IOException {
    super.testTwoEnumFieldCompatible(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testEnumSchemaEvolutionCompatible(boolean enableCodegen) throws IOException {
    super.testEnumSchemaEvolutionCompatible(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testNullableFieldSchemaConsistentNotNull(boolean enableCodegen) throws IOException {
    super.testNullableFieldSchemaConsistentNotNull(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testNullableFieldSchemaConsistentNull(boolean enableCodegen) throws IOException {
    super.testNullableFieldSchemaConsistentNull(enableCodegen);
  }

  @Override
  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testNullableFieldCompatibleNotNull(boolean enableCodegen) throws IOException {
    super.testNullableFieldCompatibleNotNull(enableCodegen);
  }

  @Override
  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testNullableFieldCompatibleNull(boolean enableCodegen) throws IOException {
    super.testNullableFieldCompatibleNull(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testUnionXlang(boolean enableCodegen) throws IOException {
    super.testUnionXlang(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testRefSchemaConsistent(boolean enableCodegen) throws IOException {
    super.testRefSchemaConsistent(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testRefCompatible(boolean enableCodegen) throws IOException {
    super.testRefCompatible(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testCircularRefSchemaConsistent(boolean enableCodegen) throws IOException {
    super.testCircularRefSchemaConsistent(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testCircularRefCompatible(boolean enableCodegen) throws IOException {
    super.testCircularRefCompatible(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testUnsignedSchemaConsistent(boolean enableCodegen) throws IOException {
    super.testUnsignedSchemaConsistent(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testUnsignedSchemaConsistentSimple(boolean enableCodegen) throws IOException {
    super.testUnsignedSchemaConsistentSimple(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testUnsignedSchemaCompatible(boolean enableCodegen) throws IOException {
    super.testUnsignedSchemaCompatible(enableCodegen);
  }

  @Override
  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testManualSchemaKindStruct(boolean enableCodegen) throws IOException {
    super.testManualSchemaKindStruct(enableCodegen);
  }

  @Override
  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testListArrayCompatibleRead(boolean enableCodegen) throws IOException {
    super.testListArrayCompatibleRead(enableCodegen);
  }
}
