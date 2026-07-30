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
import java.util.Collections;
import java.util.List;
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
 * Row format cross-language tests against a Go peer built from {@code go/fory/tests/row_xlang}.
 * Data shapes are shared with {@link CrossLanguageTest} so the Java, Python, and Go peers exercise
 * the same schemas.
 */
@Test
public class GoCrossLanguageTest {
  private static final boolean IS_WINDOWS =
      System.getProperty("os.name").toLowerCase().contains("windows");
  private static final String GO_BINARY = IS_WINDOWS ? "row_xlang_bin.exe" : "row_xlang_bin";

  @BeforeClass
  public void ensureGoPeerReady() {
    String enabled = System.getenv("FORY_GO_JAVA_CI");
    if (!"1".equals(enabled)) {
      throw new SkipException("Skipping GoCrossLanguageTest: FORY_GO_JAVA_CI not set to 1");
    }
    boolean goInstalled = true;
    try {
      Process process = new ProcessBuilder("go", "version").start();
      if (process.waitFor() != 0) {
        goInstalled = false;
      }
    } catch (IOException | InterruptedException e) {
      goInstalled = false;
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }
    if (!goInstalled) {
      throw new SkipException("Skipping GoCrossLanguageTest: go not installed");
    }
    List<String> buildCommand =
        Arrays.asList("go", "build", "-o", "tests/" + GO_BINARY, "./tests/row_xlang");
    boolean buildSuccess =
        TestUtils.executeCommand(
            buildCommand, 120, Collections.emptyMap(), new File("../../go/fory"));
    if (!buildSuccess || !new File("../../go/fory/tests/" + GO_BINARY).exists()) {
      throw new SkipException("Skipping GoCrossLanguageTest: failed to build " + GO_BINARY);
    }
  }

  public void testMapEncoder() throws IOException {
    CrossLanguageTest.A a = CrossLanguageTest.A.create();
    RowEncoder<CrossLanguageTest.A> encoder = Encoders.bean(CrossLanguageTest.A.class);
    Path dataFile = createTempFile("row_go_map");
    Files.write(dataFile, encoder.encode(a));
    Assert.assertTrue(runGoPeer("test_map_encoder", dataFile));
    Assert.assertEquals(encoder.decode(Files.readAllBytes(dataFile)), a);
  }

  public void testSerializationWithoutSchema() throws IOException {
    CrossLanguageTest.Foo foo = CrossLanguageTest.Foo.create();
    RowEncoder<CrossLanguageTest.Foo> encoder = Encoders.bean(CrossLanguageTest.Foo.class);
    Path dataFile = createTempFile("row_go_foo");
    Files.write(dataFile, encoder.toRow(foo).toBytes());
    Assert.assertTrue(runGoPeer("test_serialization_without_schema", dataFile));
    Assert.assertEquals(readFoo(encoder, dataFile), foo);
  }

  public void testSerializationWithSchema() throws IOException {
    CrossLanguageTest.Foo foo = CrossLanguageTest.Foo.create();
    RowEncoder<CrossLanguageTest.Foo> encoder = Encoders.bean(CrossLanguageTest.Foo.class);
    Path dataFile = createTempFile("row_go_foo");
    Path schemaFile = createTempFile("row_go_foo_schema");
    BinaryRow row = encoder.toRow(foo);
    Files.write(dataFile, row.toBytes());
    Files.write(schemaFile, DataTypes.serializeSchema(row.getSchema()));
    Assert.assertTrue(runGoPeer("test_serialization_with_schema", schemaFile, dataFile));
    Assert.assertEquals(readFoo(encoder, dataFile), foo);
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

  private boolean runGoPeer(String caseName, Path... files) {
    List<String> command = new ArrayList<>();
    command.add(IS_WINDOWS ? GO_BINARY : "./" + GO_BINARY);
    command.add(caseName);
    for (Path file : files) {
      command.add(file.toAbsolutePath().toString());
    }
    return TestUtils.executeCommand(
        command,
        30,
        ImmutableMap.of("ENABLE_CROSS_LANGUAGE_TESTS", "true"),
        new File("../../go/fory/tests"));
  }
}
