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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyEnumId;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.annotation.ForyStruct;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.test.TestUtils;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

/** Executes cross-language tests against the C# implementation. */
@Test
public class CSharpXlangTest extends XlangTestBase {
  private static final String CSHARP_DLL = "Fory.XlangPeer.dll";
  private static final File CSHARP_DIR = new File("../../csharp");
  private static final File CSHARP_BINARY_DIR =
      new File(CSHARP_DIR, "tests/Fory.XlangPeer/bin/Debug/net8.0");
  private volatile boolean peerBuilt;

  @Override
  protected void ensurePeerReady() {
    String enabled = System.getenv("FORY_CSHARP_JAVA_CI");
    if (!"1".equals(enabled)) {
      throw new SkipException("Skipping CSharpXlangTest: FORY_CSHARP_JAVA_CI not set to 1");
    }

    if (!isDotnetAvailable()) {
      throw new SkipException("Skipping CSharpXlangTest: dotnet is not available");
    }

    try {
      ensurePeerBuilt();
    } catch (IOException e) {
      throw new RuntimeException("Failed to build C# peer", e);
    }
  }

  @Override
  protected CommandContext buildCommandContext(String caseName, Path dataFile) throws IOException {
    ensurePeerBuilt();

    List<String> command = new ArrayList<>();
    command.add("dotnet");
    command.add(new File(CSHARP_BINARY_DIR, CSHARP_DLL).getAbsolutePath());
    command.add("--case");
    command.add(caseName);

    Map<String, String> env = envBuilder(dataFile);
    return new CommandContext(command, env, CSHARP_BINARY_DIR);
  }

  private boolean isDotnetAvailable() {
    try {
      Process process = new ProcessBuilder("dotnet", "--version").start();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return false;
      }
      return process.exitValue() == 0;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return false;
    }
  }

  private void ensurePeerBuilt() throws IOException {
    if (peerBuilt) {
      return;
    }

    synchronized (this) {
      if (peerBuilt) {
        return;
      }

      List<String> buildCommand =
          Arrays.asList(
              "dotnet", "build", "tests/Fory.XlangPeer/Fory.XlangPeer.csproj", "-c", "Debug");
      boolean built =
          TestUtils.executeCommand(buildCommand, 180, Collections.emptyMap(), CSHARP_DIR);
      if (!built) {
        throw new IOException("dotnet build failed for csharp/tests/Fory.XlangPeer");
      }

      File dll = new File(CSHARP_BINARY_DIR, CSHARP_DLL);
      if (!dll.exists()) {
        throw new IOException("C# peer assembly not found: " + dll.getAbsolutePath());
      }

      peerBuilt = true;
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

  @Override
  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testExactFieldTags(boolean enableCodegen) throws java.io.IOException {
    super.testExactFieldTags(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testSimpleStruct(boolean enableCodegen) throws java.io.IOException {
    super.testSimpleStruct(enableCodegen);
  }

  @Test(groups = "xlang")
  public void testSimpleNamedStructCodegenEnabled() throws java.io.IOException {
    super.testSimpleNamedStruct(false);
  }

  @Test(groups = "xlang")
  public void testSimpleNamedStructCodegenDisabled() throws java.io.IOException {
    super.testSimpleNamedStruct(false);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testStructEvolvingOverride(boolean enableCodegen) throws java.io.IOException {
    super.testStructEvolvingOverride(enableCodegen);
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
  public void testDecimal(boolean enableCodegen) throws java.io.IOException {
    super.testDecimal(enableCodegen);
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
  public void testCollectionElementRefRemoteTracking(boolean enableCodegen)
      throws java.io.IOException {
    super.testCollectionElementRefRemoteTracking(enableCodegen);
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
  public void testReducedPrecisionFloatStruct(boolean enableCodegen) throws java.io.IOException {
    super.testReducedPrecisionFloatStruct(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testReducedPrecisionFloatStructCompatibleFieldSkip(boolean enableCodegen)
      throws java.io.IOException {
    super.testReducedPrecisionFloatStructCompatibleFieldSkip(enableCodegen);
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

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testNullableFieldSchemaConsistentNotNull(boolean enableCodegen)
      throws java.io.IOException {
    super.testNullableFieldSchemaConsistentNotNull(enableCodegen);
  }

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
    super.testNullableFieldCompatibleNull(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testUnionXlang(boolean enableCodegen) throws java.io.IOException {
    super.testUnionXlang(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testRefSchemaConsistent(boolean enableCodegen) throws java.io.IOException {
    super.testRefSchemaConsistent(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testRefCompatible(boolean enableCodegen) throws java.io.IOException {
    super.testRefCompatible(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testCircularRefSchemaConsistent(boolean enableCodegen) throws java.io.IOException {
    super.testCircularRefSchemaConsistent(enableCodegen);
  }

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

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testNestedAnnotatedContainerSchemaConsistent(boolean enableCodegen)
      throws java.io.IOException {
    super.testNestedAnnotatedContainerSchemaConsistent(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testNestedAnnotatedContainerCompatible(boolean enableCodegen)
      throws java.io.IOException {
    super.testNestedAnnotatedContainerCompatible(enableCodegen);
  }

  @Override
  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testManualSchemaKindStruct(boolean enableCodegen) throws java.io.IOException {
    super.testManualSchemaKindStruct(enableCodegen);
  }

  @Override
  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testListArrayCompatibleRead(boolean enableCodegen) throws java.io.IOException {
    super.testListArrayCompatibleRead(enableCodegen);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testCSharpExternalId(boolean enableCodegen) throws IOException {
    Fory fory =
        Fory.builder().withXlang(true).withCompatible(true).withCodegen(enableCodegen).build();
    registerExternalById(fory);
    assertExternalRoundTrip(fory, "test_csharp_external_id");
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testCSharpExternalName(boolean enableCodegen) throws IOException {
    Fory fory =
        Fory.builder().withXlang(true).withCompatible(false).withCodegen(enableCodegen).build();
    registerExternalByName(fory);
    assertExternalRoundTrip(fory, "test_csharp_external_name");
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testCSharpOrdinaryInheritanceId(boolean enableCodegen) throws IOException {
    Fory fory =
        Fory.builder().withXlang(true).withCompatible(true).withCodegen(enableCodegen).build();
    fory.register(CSharpOrdinaryInheritance.class, 1311);
    CSharpOrdinaryInheritance value = new CSharpOrdinaryInheritance();
    value.identifier = 17;
    value.name = "ordinary";
    value.score = 19;

    MemoryBuffer buffer = MemoryUtils.buffer(128);
    fory.serialize(buffer, value);
    ExecutionContext ctx =
        prepareExecution(
            "test_csharp_ordinary_inheritance_id", buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    Assert.assertEquals(
        (CSharpOrdinaryInheritance) fory.deserialize(readBuffer(ctx.dataFile())), value);
  }

  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testCSharpExternalInheritanceName(boolean enableCodegen) throws IOException {
    Fory fory =
        Fory.builder().withXlang(true).withCompatible(false).withCodegen(enableCodegen).build();
    fory.register(CSharpExternalInheritance.class, "csharp.inheritance.ExternalLeaf");
    CSharpExternalInheritance value = new CSharpExternalInheritance();
    value.identifier = 23;
    value.secret = "external";
    value.publicValue = 31;
    value.leafName = "leaf";

    MemoryBuffer buffer = MemoryUtils.buffer(128);
    fory.serialize(buffer, value);
    ExecutionContext ctx =
        prepareExecution(
            "test_csharp_external_inheritance_name", buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    Assert.assertEquals(
        (CSharpExternalInheritance) fory.deserialize(readBuffer(ctx.dataFile())), value);
  }

  @SuppressWarnings("unchecked")
  private void assertExternalRoundTrip(Fory fory, String caseName) throws IOException {
    CSharpExternalUser user = newExternalUser(7, "root");

    CSharpExternalPoint point = new CSharpExternalPoint();
    point.x = 3;
    point.y = -4;

    CSharpExternalStatus status = CSharpExternalStatus.DONE;

    CSharpExternalHolder holder = new CSharpExternalHolder();
    holder.users = Arrays.asList(newExternalUser(11, "holder-a"), newExternalUser(12, "holder-b"));
    holder.usersByName = new LinkedHashMap<>();
    holder.usersByName.put("first", newExternalUser(13, "map-a"));
    holder.usersByName.put("second", newExternalUser(14, "map-b"));

    List<CSharpExternalUser> users =
        Arrays.asList(newExternalUser(21, "list-a"), newExternalUser(22, "list-b"));
    Map<String, CSharpExternalUser> usersByName = new LinkedHashMap<>();
    usersByName.put("left", newExternalUser(31, "root-map-a"));
    usersByName.put("right", newExternalUser(32, "root-map-b"));

    MemoryBuffer buffer = MemoryUtils.buffer(256);
    fory.serialize(buffer, user);
    fory.serialize(buffer, point);
    fory.serialize(buffer, status);
    fory.serialize(buffer, holder);
    fory.serialize(buffer, users);
    fory.serialize(buffer, usersByName);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer result = readBuffer(ctx.dataFile());
    Assert.assertEquals((CSharpExternalUser) fory.deserialize(result), user);
    Assert.assertEquals((CSharpExternalPoint) fory.deserialize(result), point);
    Assert.assertEquals((CSharpExternalStatus) fory.deserialize(result), status);
    Assert.assertEquals((CSharpExternalHolder) fory.deserialize(result), holder);
    Assert.assertEquals((List<CSharpExternalUser>) fory.deserialize(result), users);
    Assert.assertEquals((Map<String, CSharpExternalUser>) fory.deserialize(result), usersByName);
    Assert.assertEquals(result.remaining(), 0);
  }

  private static CSharpExternalUser newExternalUser(int id, String name) {
    CSharpExternalUser user = new CSharpExternalUser();
    user.id = id;
    user.name = name;
    return user;
  }

  private static void registerExternalById(Fory fory) {
    fory.register(CSharpExternalUser.class, 1301);
    fory.register(CSharpExternalPoint.class, 1302);
    fory.register(CSharpExternalStatus.class, 1303);
    fory.register(CSharpExternalHolder.class, 1304);
  }

  private static void registerExternalByName(Fory fory) {
    fory.register(CSharpExternalUser.class, "csharp.external.User");
    fory.register(CSharpExternalPoint.class, "csharp.external.Point");
    fory.register(CSharpExternalStatus.class, "csharp.external.Status");
    fory.register(CSharpExternalHolder.class, "csharp.external.Holder");
  }

  @Data
  @ForyStruct
  static class CSharpExternalUser {
    @ForyField(id = 1)
    int id;

    @ForyField(id = 2)
    String name;
  }

  @Data
  @ForyStruct
  static class CSharpExternalPoint {
    @ForyField(id = 1)
    int x;

    @ForyField(id = 2)
    int y;
  }

  enum CSharpExternalStatus {
    @ForyEnumId(0)
    UNKNOWN,

    @ForyEnumId(7)
    READY,

    @ForyEnumId(23)
    DONE,
  }

  @Data
  @ForyStruct
  static class CSharpExternalHolder {
    @ForyField(id = 1)
    List<CSharpExternalUser> users;

    @ForyField(id = 2)
    Map<String, CSharpExternalUser> usersByName;
  }

  @Data
  @ForyStruct
  static class CSharpOrdinaryInheritance {
    @ForyField(id = 1)
    int identifier;

    @ForyField(id = 2)
    String name;

    @ForyField(id = 3)
    long score;
  }

  @Data
  @ForyStruct
  static class CSharpExternalInheritance {
    @ForyField(id = 1)
    long identifier;

    @ForyField(id = 2)
    String secret;

    @ForyField(id = 3)
    int publicValue;

    @ForyField(id = 4)
    String leafName;
  }
}
