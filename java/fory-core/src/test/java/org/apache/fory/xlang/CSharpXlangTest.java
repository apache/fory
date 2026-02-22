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
import java.util.concurrent.TimeUnit;
import org.apache.fory.test.TestUtils;
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
}
