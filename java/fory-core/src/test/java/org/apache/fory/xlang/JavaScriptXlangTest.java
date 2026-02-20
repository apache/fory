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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
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
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/** Executes cross-language tests against the JavaScript implementation. */
@Test
public class JavaScriptXlangTest extends XlangTestBase {
  private static final String NODE_EXECUTABLE = "node";
  private static final String PROTOCOL_READY = "__FORY_XLANG_READY__";
  private static final String PROTOCOL_RESULT = "__FORY_XLANG_RESULT__";
  private static final String PROTOCOL_SHUTDOWN = "__FORY_XLANG_SHUTDOWN__";
  private static final int DEFAULT_PEER_TIMEOUT_SECONDS = 60;
  private static final int STARTUP_TIMEOUT_SECONDS = 30;
  private static final int SHUTDOWN_TIMEOUT_SECONDS = 10;
  private static final File JAVASCRIPT_WORK_DIR = new File("../../javascript");
  private static final List<String> PEER_COMMAND =
      Arrays.asList(NODE_EXECUTABLE, "test/xlang_peer_runner.js");

  private final Object peerLock = new Object();
  private Process peerProcess;
  private BufferedWriter peerInput;
  private BufferedReader peerOutput;

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
    try {
      startPeerProcessIfNeeded();
    } catch (IOException e) {
      throw new SkipException("Skipping JavaScriptXlangTest: failed to start javascript peer", e);
    }
  }

  @Override
  protected CommandContext buildCommandContext(String caseName, Path dataFile) {
    Map<String, String> env = envBuilder(dataFile);
    return new CommandContext(Collections.emptyList(), env, JAVASCRIPT_WORK_DIR);
  }

  @Override
  protected ExecutionContext prepareExecution(String caseName, byte[] payload) throws IOException {
    byte[] bytes = payload == null ? new byte[0] : payload;
    Path dataFile = Files.createTempFile(caseName, "data");
    writeData(dataFile, bytes);
    dataFile.toFile().deleteOnExit();
    return new ExecutionContext(
        caseName,
        dataFile,
        new CommandContext(Collections.emptyList(), Collections.emptyMap(), JAVASCRIPT_WORK_DIR));
  }

  @Override
  protected void runPeer(ExecutionContext ctx) {
    runPeer(ctx, DEFAULT_PEER_TIMEOUT_SECONDS);
  }

  @Override
  protected void runPeer(ExecutionContext ctx, int timeoutSeconds) {
    synchronized (peerLock) {
      try {
        startPeerProcessIfNeeded();
        peerInput.write(ctx.caseName());
        peerInput.write('\t');
        peerInput.write(ctx.dataFile().toAbsolutePath().toString());
        peerInput.newLine();
        peerInput.flush();

        String response = waitForProtocolLine(PROTOCOL_RESULT, timeoutSeconds);
        Assert.assertNotNull(
            response,
            "Timed out waiting for javascript xlang peer response for case " + ctx.caseName());
        String ok = PROTOCOL_RESULT + "\tOK";
        if (!ok.equals(response)) {
          String prefix = PROTOCOL_RESULT + "\tERR\t";
          if (response.startsWith(prefix)) {
            Assert.fail(
                "Failed to execute peer test "
                    + ctx.caseName()
                    + ": "
                    + response.substring(prefix.length()));
          } else {
            Assert.fail(
                "Failed to execute peer test "
                    + ctx.caseName()
                    + ": unexpected peer response "
                    + response);
          }
        }
      } catch (IOException e) {
        throw new RuntimeException("Failed to execute peer test " + ctx.caseName(), e);
      }
    }
  }

  @AfterClass(alwaysRun = true)
  public void shutdownPeer() {
    synchronized (peerLock) {
      if (peerProcess == null) {
        return;
      }
      try {
        if (peerProcess.isAlive()) {
          peerInput.write(PROTOCOL_SHUTDOWN);
          peerInput.newLine();
          peerInput.flush();
          waitForProtocolLine(PROTOCOL_RESULT, SHUTDOWN_TIMEOUT_SECONDS);
          peerProcess.waitFor(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
      } catch (Exception e) {
        if ("1".equals(System.getenv("ENABLE_FORY_DEBUG_OUTPUT"))) {
          e.printStackTrace(System.out);
        }
      } finally {
        if (peerProcess.isAlive()) {
          peerProcess.destroyForcibly();
        }
        closePeerResources();
      }
    }
  }

  private void startPeerProcessIfNeeded() throws IOException {
    synchronized (peerLock) {
      if (peerProcess != null && peerProcess.isAlive()) {
        return;
      }
      closePeerResources();
      ProcessBuilder processBuilder = new ProcessBuilder(PEER_COMMAND);
      processBuilder.directory(JAVASCRIPT_WORK_DIR);
      processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
      peerProcess = processBuilder.start();
      peerInput =
          new BufferedWriter(
              new OutputStreamWriter(peerProcess.getOutputStream(), StandardCharsets.UTF_8));
      peerOutput =
          new BufferedReader(
              new InputStreamReader(peerProcess.getInputStream(), StandardCharsets.UTF_8));
      String line = waitForProtocolLine(PROTOCOL_READY, STARTUP_TIMEOUT_SECONDS);
      if (line == null) {
        throw new IOException("Timed out waiting for javascript xlang peer startup");
      }
    }
  }

  private String waitForProtocolLine(String prefix, int timeoutSeconds) throws IOException {
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    while (System.nanoTime() < deadlineNanos) {
      if (peerOutput.ready()) {
        String line = peerOutput.readLine();
        if (line == null) {
          if (peerProcess != null && !peerProcess.isAlive()) {
            throw new IOException(
                "javascript xlang peer process exited with code " + peerProcess.exitValue());
          }
          throw new IOException("javascript xlang peer process closed stdout unexpectedly");
        }
        if (line.startsWith(prefix)) {
          return line;
        }
        if ("1".equals(System.getenv("ENABLE_FORY_DEBUG_OUTPUT"))) {
          System.out.println("[javascript-peer] " + line);
        }
      } else {
        if (peerProcess != null && !peerProcess.isAlive()) {
          throw new IOException(
              "javascript xlang peer process exited with code " + peerProcess.exitValue());
        }
        try {
          TimeUnit.MILLISECONDS.sleep(10);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while waiting for javascript xlang peer response", e);
        }
      }
    }
    return null;
  }

  private void closePeerResources() {
    if (peerInput != null) {
      try {
        peerInput.close();
      } catch (IOException ignored) {
      }
      peerInput = null;
    }
    if (peerOutput != null) {
      try {
        peerOutput.close();
      } catch (IOException ignored) {
      }
      peerOutput = null;
    }
    peerProcess = null;
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

  @Override
  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testCollectionElementRefOverride(boolean enableCodegen) throws java.io.IOException {
    super.testCollectionElementRefOverride(enableCodegen);
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
    // JavaScript properly supports Optional and sends actual null values,
    // unlike Rust which sends default values. Override with JavaScript-specific expectations.
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

    // JavaScript properly supports Optional and sends actual null values
    // (unlike Rust which sends default values)
    Assert.assertEquals(result, obj);
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
