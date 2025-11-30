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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.apache.fory.format.encoder.Encoders;
import org.apache.fory.format.encoder.RowEncoder;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.test.TestUtils;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** Tests in this class need fory python installed. */
@Test
public class CrossLanguageTest {
  private static final Logger LOG = LoggerFactory.getLogger(CrossLanguageTest.class);
  private static final String PYTHON_MODULE = "pyfory.tests.test_cross_language";
  private static final String PYTHON_EXECUTABLE = "python";

  @BeforeClass
  public void isPythonInstalled() {
    TestUtils.verifyPyforyInstalled();
  }

  @Data
  public static class A {
    public Integer f1;
    public Map<String, String> f2;

    public static A create() {
      A a = new A();
      a.f1 = 1;
      a.f2 = new HashMap<>();
      a.f2.put("pid", "12345");
      a.f2.put("ip", "0.0.0.0");
      a.f2.put("k1", "v1");
      return a;
    }
  }

  public void testMapEncoder() throws IOException {
    A a = A.create();
    RowEncoder<A> encoder = Encoders.bean(A.class);
    System.out.println("Schema: " + encoder.schema());
    Assert.assertEquals(a, encoder.decode(encoder.encode(a)));
    Path dataFile = Files.createTempFile("foo", "tmp");
    dataFile.toFile().deleteOnExit();
    {
      Files.write(dataFile, encoder.encode(a));
      ImmutableList<String> command =
          ImmutableList.of(
              PYTHON_EXECUTABLE,
              "-m",
              PYTHON_MODULE,
              "test_map_encoder",
              dataFile.toAbsolutePath().toString());
      Assert.assertTrue(executeCommand(command, 30));
    }
    Assert.assertEquals(encoder.decode(Files.readAllBytes(dataFile)), a);
  }

  public void testSerializationWithoutSchema() throws IOException {
    Foo foo = Foo.create();
    RowEncoder<Foo> encoder = Encoders.bean(Foo.class);
    Path dataFile = Files.createTempFile("foo", "data");
    {
      BinaryRow row = encoder.toRow(foo);
      Files.write(dataFile, row.toBytes());
      ImmutableList<String> command =
          ImmutableList.of(
              PYTHON_EXECUTABLE,
              "-m",
              PYTHON_MODULE,
              "test_serialization_without_schema",
              dataFile.toAbsolutePath().toString());
      Assert.assertTrue(executeCommand(command, 30));
    }

    MemoryBuffer buffer = MemoryUtils.wrap(Files.readAllBytes(dataFile));
    BinaryRow newRow = new BinaryRow(encoder.schema());
    newRow.pointTo(buffer, 0, buffer.size());
    Assert.assertEquals(foo, encoder.fromRow(newRow));
  }

  public void testSerializationWithSchema() throws IOException {
    Foo foo = Foo.create();
    RowEncoder<Foo> encoder = Encoders.bean(Foo.class);
    Path dataFile = Files.createTempFile("foo", "data");
    {
      BinaryRow row = encoder.toRow(foo);
      Path schemaFile = Files.createTempFile("foo_schema", "data");
      Files.write(dataFile, row.toBytes());
      LOG.info("Schema {}", row.getSchema());
      Files.write(schemaFile, DataTypes.serializeSchema(row.getSchema()));
      org.apache.fory.format.type.Schema forySchema =
          DataTypes.deserializeSchema(Files.readAllBytes(schemaFile));
      Assert.assertEquals(forySchema, row.getSchema());
      ImmutableList<String> command =
          ImmutableList.of(
              PYTHON_EXECUTABLE,
              "-m",
              PYTHON_MODULE,
              "test_serialization_with_schema",
              schemaFile.toAbsolutePath().toString(),
              dataFile.toAbsolutePath().toString());
      Assert.assertTrue(executeCommand(command, 30));
    }

    MemoryBuffer buffer = MemoryUtils.wrap(Files.readAllBytes(dataFile));
    BinaryRow newRow = new BinaryRow(encoder.schema());
    newRow.pointTo(buffer, 0, buffer.size());
    Assert.assertEquals(foo, encoder.fromRow(newRow));
  }

  /** Keep this in sync with `foo_schema` in test_cross_language.py */
  @Data
  public static class Foo {
    public Integer f1;
    public String f2;
    public List<String> f3;
    public Map<String, Integer> f4;
    public Bar f5;

    public static Foo create() {
      Foo foo = new Foo();
      foo.f1 = 1;
      foo.f2 = "str";
      foo.f3 = Arrays.asList("str1", null, "str2");
      foo.f4 =
          new HashMap<String, Integer>() {
            {
              put("k1", 1);
              put("k2", 2);
              put("k3", 3);
              put("k4", 4);
              put("k5", 5);
              put("k6", 6);
            }
          };
      foo.f5 = Bar.create();
      return foo;
    }
  }

  /** Keep this in sync with `bar_schema` in test_cross_language.py */
  @Data
  public static class Bar {
    public Integer f1;
    public String f2;

    public static Bar create() {
      Bar bar = new Bar();
      bar.f1 = 1;
      bar.f2 = "str";
      return bar;
    }
  }

  /**
   * Execute an external command.
   *
   * @return Whether the command succeeded.
   */
  private boolean executeCommand(List<String> command, int waitTimeoutSeconds) {
    return TestUtils.executeCommand(
        command, waitTimeoutSeconds, ImmutableMap.of("ENABLE_CROSS_LANGUAGE_TESTS", "true"));
  }
}
