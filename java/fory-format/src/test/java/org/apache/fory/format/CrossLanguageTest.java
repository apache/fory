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

import static org.apache.fory.format.vectorized.ArrowSerializersTest.assertRecordBatchEqual;
import static org.apache.fory.format.vectorized.ArrowSerializersTest.assertTableEqual;
import static org.apache.fory.format.vectorized.ArrowUtilsTest.createVectorSchemaRoot;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.VectorUnloader;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;
import org.apache.arrow.vector.ipc.WriteChannel;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.apache.arrow.vector.ipc.message.IpcOption;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.Fory;
import org.apache.fory.config.Language;
import org.apache.fory.format.encoder.Encoders;
import org.apache.fory.format.encoder.RowEncoder;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.vectorized.ArrowSerializers;
import org.apache.fory.format.vectorized.ArrowTable;
import org.apache.fory.format.vectorized.ArrowUtils;
import org.apache.fory.format.vectorized.ArrowWriter;
import org.apache.fory.io.MemoryBufferOutputStream;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.serializer.BufferObject;
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

    MemoryBuffer buffer = MemoryBuffer.wrap(Files.readAllBytes(dataFile));
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
      Schema schema = DataTypes.deserializeSchema(Files.readAllBytes(schemaFile));
      Assert.assertEquals(schema, row.getSchema());
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

    MemoryBuffer buffer = MemoryBuffer.wrap(Files.readAllBytes(dataFile));
    BinaryRow newRow = new BinaryRow(encoder.schema());
    newRow.pointTo(buffer, 0, buffer.size());
    Assert.assertEquals(foo, encoder.fromRow(newRow));
  }

  public void testRecordBatchBasic() throws IOException {
    BufferAllocator alloc = new RootAllocator(Long.MAX_VALUE);
    Field field = DataTypes.field("testField", DataTypes.int8());
    TinyIntVector vector =
        new TinyIntVector(
            "testField", FieldType.nullable(Types.MinorType.TINYINT.getType()), alloc);
    VectorSchemaRoot root =
        new VectorSchemaRoot(Collections.singletonList(field), Collections.singletonList(vector));
    Path dataFile = Files.createTempFile("foo", "data");
    MemoryBuffer buffer = MemoryBuffer.buffer(128);
    try (ArrowStreamWriter writer =
        new ArrowStreamWriter(root, null, new MemoryBufferOutputStream(buffer))) {
      writer.start();
      for (int i = 0; i < 1; i++) {
        vector.allocateNew(16);
        for (int j = 0; j < 8; j++) {
          vector.set(j, j + i);
          vector.set(j + 8, 0, (byte) (j + i));
        }
        vector.setValueCount(16);
        root.setRowCount(16);
        writer.writeBatch();
      }
      writer.end();
    }
    Files.write(dataFile, buffer.getBytes(0, buffer.writerIndex()));
    ImmutableList<String> command =
        ImmutableList.of(
            PYTHON_EXECUTABLE,
            "-m",
            PYTHON_MODULE,
            "test_record_batch_basic",
            dataFile.toAbsolutePath().toString());
    Assert.assertTrue(executeCommand(command, 30));
  }

  public void testRecordBatchWriter() throws IOException {
    Foo foo = Foo.create();
    RowEncoder<Foo> encoder = Encoders.bean(Foo.class);
    Path dataFile = Files.createTempFile("foo", "data");
    MemoryBuffer buffer = MemoryBuffer.buffer(128);
    ImmutableList<String> command =
        ImmutableList.of(
            PYTHON_EXECUTABLE,
            "-m",
            PYTHON_MODULE,
            "test_record_batch",
            dataFile.toAbsolutePath().toString());
    int numRows = 128;
    {
      VectorSchemaRoot root = ArrowUtils.createVectorSchemaRoot(encoder.schema());
      ArrowWriter arrowWriter = new ArrowWriter(root);
      try (ArrowStreamWriter writer =
          new ArrowStreamWriter(root, null, new MemoryBufferOutputStream(buffer))) {
        writer.start();
        for (int i = 0; i < numRows; i++) {
          BinaryRow row = encoder.toRow(foo);
          arrowWriter.write(row);
        }
        arrowWriter.finish();
        writer.writeBatch();
        writer.end();
      }
      Files.write(dataFile, buffer.getBytes(0, buffer.writerIndex()));
      Assert.assertTrue(executeCommand(command, 30));
    }
    {
      buffer.writerIndex(0);
      ArrowWriter arrowWriter = ArrowUtils.createArrowWriter(encoder.schema());
      for (int i = 0; i < numRows; i++) {
        BinaryRow row = encoder.toRow(foo);
        arrowWriter.write(row);
      }
      ArrowRecordBatch recordBatch = arrowWriter.finishAsRecordBatch();
      DataTypes.serializeSchema(encoder.schema(), buffer);
      ArrowUtils.serializeRecordBatch(recordBatch, buffer);
      arrowWriter.reset();
      ArrowStreamWriter.writeEndOfStream(
          new WriteChannel(Channels.newChannel(new MemoryBufferOutputStream(buffer))),
          new IpcOption());
      Files.write(dataFile, buffer.getBytes(0, buffer.writerIndex()));
      Assert.assertTrue(executeCommand(command, 30));
    }
  }

  public void testWriteMultiRecordBatch() throws IOException {
    Foo foo = Foo.create();
    RowEncoder<Foo> encoder = Encoders.bean(Foo.class);
    Path schemaFile = Files.createTempFile("foo", "schema");
    Path dataFile = Files.createTempFile("foo", "data");
    ImmutableList<String> command =
        ImmutableList.of(
            PYTHON_EXECUTABLE,
            "-m",
            PYTHON_MODULE,
            "test_write_multi_record_batch",
            schemaFile.toAbsolutePath().toString(),
            dataFile.toAbsolutePath().toString());
    {
      MemoryBuffer buffer = MemoryBuffer.buffer(128);
      buffer.writerIndex(0);
      DataTypes.serializeSchema(encoder.schema(), buffer);
      Files.write(schemaFile, buffer.getBytes(0, buffer.writerIndex()));
    }
    ArrowWriter arrowWriter = ArrowUtils.createArrowWriter(encoder.schema());
    int numBatches = 5;
    for (int i = 0; i < numBatches; i++) {
      int numRows = 128;
      for (int j = 0; j < numRows; j++) {
        BinaryRow row = encoder.toRow(foo);
        arrowWriter.write(row);
      }
      ArrowRecordBatch recordBatch = arrowWriter.finishAsRecordBatch();
      MemoryBuffer buffer = MemoryBuffer.buffer(128);
      ArrowUtils.serializeRecordBatch(recordBatch, buffer);
      arrowWriter.reset();
      Files.write(
          dataFile, buffer.getBytes(0, buffer.writerIndex()), StandardOpenOption.TRUNCATE_EXISTING);
      Assert.assertTrue(executeCommand(command, 30));
    }
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

  @Test
  public void testSerializeArrowInBand() throws Exception {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    ArrowSerializers.registerSerializers(fory);
    MemoryBuffer buffer = MemoryBuffer.buffer(32);
    int size = 2000;
    VectorSchemaRoot root = createVectorSchemaRoot(size);
    fory.serialize(buffer, root);
    Schema schema = root.getSchema();
    List<ArrowRecordBatch> recordBatches = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      VectorUnloader unloader = new VectorUnloader(root);
      recordBatches.add(unloader.getRecordBatch());
    }
    ArrowTable table = new ArrowTable(schema, recordBatches);
    fory.serialize(buffer, table);
    assertRecordBatchEqual((VectorSchemaRoot) fory.deserialize(buffer), root);
    assertTableEqual((ArrowTable) fory.deserialize(buffer), table);

    Path dataFile = Files.createTempFile("test_serialize_arrow_in_band", "data");
    Files.write(dataFile, buffer.getBytes(0, buffer.writerIndex()));
    ImmutableList<String> command =
        ImmutableList.of(
            PYTHON_EXECUTABLE,
            "-m",
            PYTHON_MODULE,
            "test_serialize_arrow_in_band",
            dataFile.toAbsolutePath().toString());
    Assert.assertTrue(executeCommand(command, 30));

    MemoryBuffer buffer2 = MemoryBuffer.wrap(Files.readAllBytes(dataFile));
    assertRecordBatchEqual((VectorSchemaRoot) fory.deserialize(buffer2), root);
    assertTableEqual((ArrowTable) fory.deserialize(buffer2), table);
  }

  @Test
  public void testSerializeArrowOutOfBand() throws Exception {
    List<BufferObject> bufferObjects = new ArrayList<>();
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    ArrowSerializers.registerSerializers(fory);

    MemoryBuffer buffer = MemoryBuffer.buffer(32);
    int size = 2000;
    VectorSchemaRoot root = createVectorSchemaRoot(size);
    Schema schema = root.getSchema();
    List<ArrowRecordBatch> recordBatches = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      VectorUnloader unloader = new VectorUnloader(root);
      recordBatches.add(unloader.getRecordBatch());
    }
    ArrowTable table = new ArrowTable(schema, recordBatches);
    fory.serialize(
        buffer,
        Arrays.asList(root, table),
        e -> {
          bufferObjects.add(e);
          return false;
        });
    List<MemoryBuffer> buffers =
        bufferObjects.stream().map(BufferObject::toBuffer).collect(Collectors.toList());
    List<?> objects = (List<?>) fory.deserialize(buffer, buffers);
    Assert.assertNotNull(objects);
    assertRecordBatchEqual((VectorSchemaRoot) objects.get(0), root);
    assertTableEqual((ArrowTable) objects.get(1), table);

    Path intBandDataFile =
        Files.createTempFile("test_serialize_arrow_out_of_band_", "in_band.data");
    Files.write(intBandDataFile, buffer.getBytes(0, buffer.writerIndex()));
    Path outOfBandDataFile =
        Files.createTempFile("test_serialize_arrow_out_of_band", "out_of_band.data");
    MemoryBuffer outOfBandBuffer = MemoryBuffer.buffer(32);
    outOfBandBuffer.writeInt32(bufferObjects.get(0).totalBytes());
    outOfBandBuffer.writeInt32(bufferObjects.get(1).totalBytes());
    bufferObjects.get(0).writeTo(outOfBandBuffer);
    bufferObjects.get(1).writeTo(outOfBandBuffer);
    Files.write(outOfBandDataFile, outOfBandBuffer.getBytes(0, outOfBandBuffer.writerIndex()));
    ImmutableList<String> command =
        ImmutableList.of(
            PYTHON_EXECUTABLE,
            "-m",
            PYTHON_MODULE,
            "test_serialize_arrow_out_of_band",
            intBandDataFile.toAbsolutePath().toString(),
            outOfBandDataFile.toAbsolutePath().toString());
    Assert.assertTrue(executeCommand(command, 30));

    MemoryBuffer intBandBuffer = MemoryBuffer.wrap(Files.readAllBytes(intBandDataFile));
    outOfBandBuffer = MemoryBuffer.wrap(Files.readAllBytes(outOfBandDataFile));
    int len1 = outOfBandBuffer.readInt32();
    int len2 = outOfBandBuffer.readInt32();
    buffers = Arrays.asList(outOfBandBuffer.slice(8, len1), outOfBandBuffer.slice(8 + len1, len2));
    objects = (List<?>) fory.deserialize(intBandBuffer, buffers);
    Assert.assertNotNull(objects);
    assertRecordBatchEqual((VectorSchemaRoot) objects.get(0), root);
    assertTableEqual((ArrowTable) objects.get(1), table);
  }
}
