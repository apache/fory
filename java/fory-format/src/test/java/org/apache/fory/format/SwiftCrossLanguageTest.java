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

package org.apache.fory.format;

import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.fory.format.GoCrossLanguageTest.Blob;
import org.apache.fory.format.encoder.Encoders;
import org.apache.fory.format.encoder.RowEncoder;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.test.TestUtils;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Row format cross-language tests against the Swift peer built from {@code
 * swift/Tests/ForyRowXlangPeer}. Data shapes are shared with {@link CrossLanguageTest} and {@link
 * GoCrossLanguageTest}. Setting {@code FORY_SWIFT_JAVA_CI=1} opts into the suite; once opted in, a
 * missing Swift toolchain or a peer build failure fails the tests instead of skipping them. Setting
 * {@code FORY_SWIFT_PEER_PREBUILT=1} uses an already built release peer.
 */
@Test
public class SwiftCrossLanguageTest {
  private static final String SWIFT_EXECUTABLE = "swift";
  private static final String SWIFT_PEER_TARGET = "ForyRowXlangPeer";
  private static final File SWIFT_WORK_DIR = new File("../../swift");
  private static final Path SWIFT_PEER_BINARY_PATH =
      SWIFT_WORK_DIR.toPath().resolve(".build/release").resolve(SWIFT_PEER_TARGET);
  // Fixed dictionary order in the peer, so a failure reproduces with the same bytes.
  private static final Map<String, String> SWIFT_PEER_ENV =
      ImmutableMap.of("SWIFT_DETERMINISTIC_HASHING", "1", "ENABLE_FORY_DEBUG_OUTPUT", "1");
  private String swiftPeerBinaryPath;

  @BeforeClass
  public void ensureSwiftPeerReady() {
    if (!"1".equals(System.getenv("FORY_SWIFT_JAVA_CI"))) {
      throw new SkipException("Skipping SwiftCrossLanguageTest: FORY_SWIFT_JAVA_CI not set to 1");
    }
    try {
      Process process = new ProcessBuilder(SWIFT_EXECUTABLE, "--version").start();
      Assert.assertEquals(process.waitFor(), 0, "swift is required when FORY_SWIFT_JAVA_CI=1");
    } catch (IOException e) {
      throw new AssertionError("swift is required when FORY_SWIFT_JAVA_CI=1", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while probing the swift toolchain", e);
    }
    if (!"1".equals(System.getenv("FORY_SWIFT_PEER_PREBUILT"))) {
      Assert.assertTrue(buildSwiftPeer(), "failed to build the Swift row format peer");
    }
    Assert.assertTrue(
        Files.isRegularFile(SWIFT_PEER_BINARY_PATH),
        "Swift row format peer not found at " + SWIFT_PEER_BINARY_PATH.toAbsolutePath());
    Assert.assertTrue(
        Files.isExecutable(SWIFT_PEER_BINARY_PATH),
        "Swift row format peer is not executable: " + SWIFT_PEER_BINARY_PATH.toAbsolutePath());
    swiftPeerBinaryPath = SWIFT_PEER_BINARY_PATH.toAbsolutePath().toString();
  }

  public void testByteArrayCarrier() throws IOException {
    Blob blob = Blob.create();
    RowEncoder<Blob> encoder = Encoders.bean(Blob.class);
    Path dataFile = createTempFile("row_swift_blob");
    Files.write(dataFile, encoder.encode(blob));
    Assert.assertTrue(runSwiftPeer("test_byte_array_carrier", dataFile));
    byte[] swiftBytes = Files.readAllBytes(dataFile);
    Assert.assertEquals(encoder.decode(swiftBytes), blob);
    // Blob holds no map, so the Swift encoding has one possible layout and must equal Java's.
    Assert.assertEquals(swiftBytes, encoder.encode(blob));
  }

  public void testMapEncoder() throws IOException {
    CrossLanguageTest.A a = CrossLanguageTest.A.create();
    RowEncoder<CrossLanguageTest.A> encoder = Encoders.bean(CrossLanguageTest.A.class);
    Path dataFile = createTempFile("row_swift_map");
    Files.write(dataFile, encoder.encode(a));
    Assert.assertTrue(runSwiftPeer("test_map_encoder", dataFile));
    Assert.assertEquals(encoder.decode(Files.readAllBytes(dataFile)), a);
  }

  public void testSerializationWithoutSchema() throws IOException {
    CrossLanguageTest.Foo foo = CrossLanguageTest.Foo.create();
    RowEncoder<CrossLanguageTest.Foo> encoder = Encoders.bean(CrossLanguageTest.Foo.class);
    Path dataFile = createTempFile("row_swift_foo");
    Files.write(dataFile, encoder.toRow(foo).toBytes());
    Assert.assertTrue(runSwiftPeer("test_serialization_without_schema", dataFile));
    Assert.assertEquals(readFoo(encoder, dataFile), foo);
  }

  public void testSerializationWithSchema() throws IOException {
    CrossLanguageTest.Foo foo = CrossLanguageTest.Foo.create();
    RowEncoder<CrossLanguageTest.Foo> encoder = Encoders.bean(CrossLanguageTest.Foo.class);
    Path dataFile = createTempFile("row_swift_foo");
    Path schemaFile = createTempFile("row_swift_foo_schema");
    BinaryRow row = encoder.toRow(foo);
    Files.write(dataFile, row.toBytes());
    Files.write(schemaFile, DataTypes.serializeSchema(row.getSchema()));
    Assert.assertTrue(runSwiftPeer("test_serialization_with_schema", schemaFile, dataFile));
    Assert.assertEquals(readFoo(encoder, dataFile), foo);
  }

  private static boolean buildSwiftPeer() {
    Map<String, String> env = ImmutableMap.of("ENABLE_FORY_DEBUG_OUTPUT", "1");
    List<String> fastBuildCommand =
        Arrays.asList(
            SWIFT_EXECUTABLE,
            "build",
            "-c",
            "release",
            "--disable-automatic-resolution",
            "--product",
            SWIFT_PEER_TARGET);
    if (TestUtils.executeCommand(fastBuildCommand, 600, env, SWIFT_WORK_DIR)) {
      return true;
    }
    List<String> fallbackBuildCommand =
        Arrays.asList(SWIFT_EXECUTABLE, "build", "-c", "release", "--product", SWIFT_PEER_TARGET);
    return TestUtils.executeCommand(fallbackBuildCommand, 600, env, SWIFT_WORK_DIR);
  }

  private static CrossLanguageTest.Foo readFoo(
      RowEncoder<CrossLanguageTest.Foo> encoder, Path dataFile) throws IOException {
    MemoryBuffer buffer = MemoryUtils.wrap(Files.readAllBytes(dataFile));
    BinaryRow row = new BinaryRow(encoder.schema());
    row.pointTo(buffer, 0, buffer.size());
    return encoder.fromRow(row);
  }

  private static Path createTempFile(String prefix) throws IOException {
    Path file = Files.createTempFile(prefix, "data");
    file.toFile().deleteOnExit();
    return file;
  }

  private boolean runSwiftPeer(String caseName, Path... files) {
    List<String> command = new ArrayList<>();
    command.add(swiftPeerBinaryPath);
    command.add(caseName);
    for (Path file : files) {
      command.add(file.toAbsolutePath().toString());
    }
    return TestUtils.executeCommand(command, 30, SWIFT_PEER_ENV, SWIFT_WORK_DIR);
  }
}
