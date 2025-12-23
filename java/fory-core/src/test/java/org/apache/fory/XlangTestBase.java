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
import com.google.common.hash.Hashing;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import lombok.Data;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.test.TestUtils;
import org.apache.fory.util.MurmurHash3;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public abstract class XlangTestBase extends ForyTestBase {

  protected static class CommandContext {
    private final List<String> command;
    private final Map<String, String> environment;
    private final File workDir;

    protected CommandContext(List<String> command, Map<String, String> environment, File workDir) {
      this.command = Collections.unmodifiableList(new ArrayList<>(command));
      this.environment =
          environment == null ? Collections.emptyMap() : Collections.unmodifiableMap(environment);
      this.workDir = workDir;
    }

    List<String> command() {
      return command;
    }

    Map<String, String> environment() {
      return environment;
    }

    File workDir() {
      return workDir;
    }
  }

  protected static class ExecutionContext {
    private final String caseName;
    private final Path dataFile;
    private final CommandContext commandContext;

    ExecutionContext(String caseName, Path dataFile, CommandContext commandContext) {
      this.caseName = caseName;
      this.dataFile = dataFile;
      this.commandContext = commandContext;
    }

    String caseName() {
      return caseName;
    }

    Path dataFile() {
      return dataFile;
    }

    CommandContext commandContext() {
      return commandContext;
    }
  }

  @BeforeClass
  public void ensurePeerReadyForTests() {
    ensurePeerReady();
  }

  protected abstract void ensurePeerReady();

  protected abstract CommandContext buildCommandContext(String caseName, Path dataFile)
      throws IOException;

  protected ImmutableMap.Builder<String, String> envBuilder(Path dataFile) {
    ImmutableMap.Builder<String, String> builder =
        ImmutableMap.<String, String>builder()
            .put("DATA_FILE", dataFile.toAbsolutePath().toString());
    String dumpCase = System.getenv("FORY_CPP_DUMP_CASE");
    if (dumpCase != null) {
      builder.put("FORY_CPP_DUMP_CASE", dumpCase);
    }
    String dumpDir = System.getenv("FORY_CPP_DUMP_DIR");
    if (dumpDir != null) {
      builder.put("FORY_CPP_DUMP_DIR", dumpDir);
    }
    return builder;
  }

  protected ExecutionContext prepareExecution(String caseName, byte[] payload) throws IOException {
    Path dataFile = createDataFile(caseName, payload);
    return new ExecutionContext(caseName, dataFile, buildCommandContext(caseName, dataFile));
  }

  private Path createDataFile(String caseName, byte[] payload) throws IOException {
    Path dataFile = Files.createTempFile(caseName, "data");
    writeData(dataFile, payload == null ? new byte[0] : payload);
    dataFile.toFile().deleteOnExit();
    return dataFile;
  }

  protected void writeData(Path dataFile, byte[] payload) throws IOException {
    Files.write(
        dataFile,
        payload == null ? new byte[0] : payload,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
  }

  protected byte[] readBytes(Path dataFile) throws IOException {
    return Files.readAllBytes(dataFile);
  }

  protected MemoryBuffer readBuffer(Path dataFile) throws IOException {
    return MemoryUtils.wrap(readBytes(dataFile));
  }

  protected void runPeer(ExecutionContext ctx) {
    runPeer(ctx, 30);
  }

  protected void runPeer(ExecutionContext ctx, int timeoutSeconds) {
    Assert.assertTrue(
        executeCommand(
            ctx.commandContext().command(),
            timeoutSeconds,
            ctx.commandContext().environment(),
            ctx.commandContext().workDir()),
        "Failed to execute peer test " + ctx.caseName());
  }

  @Test
  public void testBuffer() throws IOException {
    String caseName = "test_buffer";
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    buffer.writeBoolean(true);
    buffer.writeByte(Byte.MAX_VALUE);
    buffer.writeInt16(Short.MAX_VALUE);
    buffer.writeInt32(Integer.MAX_VALUE);
    buffer.writeInt64(Long.MAX_VALUE);
    buffer.writeFloat32(-1.1f);
    buffer.writeFloat64(-1.1);
    buffer.writeVarUint32(100);
    byte[] bytes = {'a', 'b'};
    buffer.writeInt32(bytes.length);
    buffer.writeBytes(bytes);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    buffer = readBuffer(ctx.dataFile());
    Assert.assertTrue(buffer.readBoolean());
    Assert.assertEquals(buffer.readByte(), Byte.MAX_VALUE);
    Assert.assertEquals(buffer.readInt16(), Short.MAX_VALUE);
    Assert.assertEquals(buffer.readInt32(), Integer.MAX_VALUE);
    Assert.assertEquals(buffer.readInt64(), Long.MAX_VALUE);
    Assert.assertEquals(buffer.readFloat32(), -1.1f, 0.0001);
    Assert.assertEquals(buffer.readFloat64(), -1.1, 0.0001);
    Assert.assertEquals(buffer.readVarUint32(), 100);
    Assert.assertTrue(Arrays.equals(buffer.readBytes(buffer.readInt32()), bytes));
  }

  @Test
  public void testBufferVar() throws IOException {
    String caseName = "test_buffer_var";
    MemoryBuffer buffer = MemoryUtils.buffer(256);
    int[] varInt32Values = {
      Integer.MIN_VALUE,
      Integer.MIN_VALUE + 1,
      -1000000,
      -1000,
      -128,
      -1,
      0,
      1,
      127,
      128,
      16383,
      16384,
      2097151,
      2097152,
      268435455,
      268435456,
      Integer.MAX_VALUE - 1,
      Integer.MAX_VALUE
    };
    for (int value : varInt32Values) {
      buffer.writeVarInt32(value);
    }

    int[] varUint32Values = {
      0,
      1,
      127,
      128,
      16383,
      16384,
      2097151,
      2097152,
      268435455,
      268435456,
      Integer.MAX_VALUE - 1,
      Integer.MAX_VALUE
    };
    for (int value : varUint32Values) {
      buffer.writeVarUint32(value);
    }

    long[] varUint64Values = {
      0L,
      1L,
      127L,
      128L,
      16383L,
      16384L,
      2097151L,
      2097152L,
      268435455L,
      268435456L,
      34359738367L,
      34359738368L,
      4398046511103L,
      4398046511104L,
      562949953421311L,
      562949953421312L,
      72057594037927935L,
      72057594037927936L,
      Long.MAX_VALUE,
    };
    for (long value : varUint64Values) {
      buffer.writeVarUint64(value);
    }

    long[] varInt64Values = {
      Long.MIN_VALUE,
      Long.MIN_VALUE + 1,
      -1000000000000L,
      -1000000L,
      -1000L,
      -128L,
      -1L,
      0L,
      1L,
      127L,
      1000L,
      1000000L,
      1000000000000L,
      Long.MAX_VALUE - 1,
      Long.MAX_VALUE
    };
    for (long value : varInt64Values) {
      buffer.writeVarInt64(value);
    }

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    buffer = readBuffer(ctx.dataFile());
    for (int expected : varInt32Values) {
      int actual = buffer.readVarInt32();
      Assert.assertEquals(actual, expected);
    }
    for (int expected : varUint32Values) {
      int actual = buffer.readVarUint32();
      Assert.assertEquals(actual, expected);
    }
    for (long expected : varUint64Values) {
      long actual = buffer.readVarUint64();
      Assert.assertEquals(actual, expected);
    }
    for (long expected : varInt64Values) {
      long actual = buffer.readVarInt64();
      Assert.assertEquals(actual, expected);
    }
  }

  @Test
  public void testMurmurHash3() throws IOException {
    String caseName = "test_murmurhash3";
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    byte[] hash1 = Hashing.murmur3_128(47).hashBytes(new byte[] {1, 2, 8}).asBytes();
    buffer.writeBytes(hash1);
    byte[] hash2 =
        Hashing.murmur3_128(47)
            .hashBytes("01234567890123456789".getBytes(StandardCharsets.UTF_8))
            .asBytes();
    buffer.writeBytes(hash2);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    long[] longs = MurmurHash3.murmurhash3_x64_128(new byte[] {1, 2, 8}, 0, 3, 47);
    buffer.writerIndex(0);
    buffer.writeInt64(longs[0]);
    buffer.writeInt64(longs[1]);
    writeData(ctx.dataFile(), buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);
  }

  private void _testStringSerializer(Fory fory, String caseName) throws IOException {
    MemoryBuffer buffer = MemoryUtils.buffer(256);
    String[] testStrings =
        new String[] {
          // Latin1
          "ab",
          "Rust123",
          "Çüéâäàåçêëèïî",
          // UTF16
          "こんにちは",
          "Привет",
          "𝄞🎵🎶",
          // UTF8
          "Hello, 世界",
        };
    for (String s : testStrings) {
      fory.serialize(buffer, s);
    }
    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);
    buffer = readBuffer(ctx.dataFile());
    for (String expected : testStrings) {
      String actual = (String) fory.deserialize(buffer);
      Assert.assertEquals(actual, expected);
    }
  }

  @Test
  public void testStringSerializer() throws Exception {
    String caseName = "test_string_serializer";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    _testStringSerializer(fory, caseName);
    Fory foryCompress =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withStringCompressed(true)
            .withWriteNumUtf16BytesForUtf8Encoding(false)
            .build();
    _testStringSerializer(foryCompress, caseName);
  }

  enum Color {
    Green,
    Red,
    Blue,
    White,
  }

  @Test
  public void testCrossLanguageSerializer() throws Exception {
    String caseName = "test_cross_language_serializer";
    List<String> strList = Arrays.asList("hello", "world");
    Set<String> strSet = new HashSet<>(strList);
    Map<String, String> strMap = new HashMap<>();
    strMap.put("hello", "world");
    strMap.put("foo", "bar");
    Color color = Color.White;

    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory.register(Color.class, 101);
    MemoryBuffer buffer = MemoryUtils.buffer(64);
    fory.serialize(buffer, true);
    fory.serialize(buffer, false);
    fory.serialize(buffer, -1);
    fory.serialize(buffer, Byte.MAX_VALUE);
    fory.serialize(buffer, Byte.MIN_VALUE);
    fory.serialize(buffer, Short.MAX_VALUE);
    fory.serialize(buffer, Short.MIN_VALUE);
    fory.serialize(buffer, Integer.MAX_VALUE);
    fory.serialize(buffer, Integer.MIN_VALUE);
    fory.serialize(buffer, Long.MAX_VALUE);
    fory.serialize(buffer, Long.MIN_VALUE);
    fory.serialize(buffer, -1.f);
    fory.serialize(buffer, -1.d);
    fory.serialize(buffer, "str");
    LocalDate day = LocalDate.of(2021, 11, 23);
    fory.serialize(buffer, day);
    Instant instant = Instant.ofEpochSecond(100);
    fory.serialize(buffer, instant);
    fory.serialize(buffer, new boolean[] {true, false});
    fory.serialize(buffer, new byte[] {1, Byte.MAX_VALUE});
    fory.serialize(buffer, new short[] {1, Short.MAX_VALUE});
    fory.serialize(buffer, new int[] {1, Integer.MAX_VALUE});
    fory.serialize(buffer, new long[] {1, Long.MAX_VALUE});
    fory.serialize(buffer, new float[] {1.f, 2.f});
    fory.serialize(buffer, new double[] {1.0, 2.0});
    fory.serialize(buffer, strList);
    fory.serialize(buffer, strSet);
    fory.serialize(buffer, strMap);
    fory.serialize(buffer, color);

    BiConsumer<MemoryBuffer, Boolean> function =
        (MemoryBuffer buf, Boolean useToString) -> {
          assertStringEquals(fory.deserialize(buf), true, useToString);
          assertStringEquals(fory.deserialize(buf), false, useToString);
          assertStringEquals(fory.deserialize(buf), -1, useToString);
          assertStringEquals(fory.deserialize(buf), Byte.MAX_VALUE, useToString);
          assertStringEquals(fory.deserialize(buf), Byte.MIN_VALUE, useToString);
          assertStringEquals(fory.deserialize(buf), Short.MAX_VALUE, useToString);
          assertStringEquals(fory.deserialize(buf), Short.MIN_VALUE, useToString);
          assertStringEquals(fory.deserialize(buf), Integer.MAX_VALUE, useToString);
          assertStringEquals(fory.deserialize(buf), Integer.MIN_VALUE, useToString);
          assertStringEquals(fory.deserialize(buf), Long.MAX_VALUE, useToString);
          assertStringEquals(fory.deserialize(buf), Long.MIN_VALUE, useToString);
          assertStringEquals(fory.deserialize(buf), -1.f, useToString);
          assertStringEquals(fory.deserialize(buf), -1.d, useToString);
          assertStringEquals(fory.deserialize(buf), "str", useToString);
          assertStringEquals(fory.deserialize(buf), day, useToString);
          assertStringEquals(fory.deserialize(buf), instant, useToString);
          assertStringEquals(fory.deserialize(buf), new boolean[] {true, false}, false);
          assertStringEquals(fory.deserialize(buf), new byte[] {1, Byte.MAX_VALUE}, false);
          assertStringEquals(fory.deserialize(buf), new short[] {1, Short.MAX_VALUE}, false);
          assertStringEquals(fory.deserialize(buf), new int[] {1, Integer.MAX_VALUE}, false);
          assertStringEquals(fory.deserialize(buf), new long[] {1, Long.MAX_VALUE}, false);
          assertStringEquals(fory.deserialize(buf), new float[] {1.f, 2.f}, false);
          assertStringEquals(fory.deserialize(buf), new double[] {1.0, 2.0}, false);
          assertStringEquals(fory.deserialize(buf), strList, useToString);
          assertStringEquals(fory.deserialize(buf), strSet, useToString);
          assertStringEquals(fory.deserialize(buf), strMap, useToString);
          assertStringEquals(fory.deserialize(buf), color, useToString);
        };
    function.accept(buffer, false);
    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);
    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    function.accept(buffer2, true);
  }

  @Data
  static class Item {
    String name;
  }

  @Data
  static class SimpleStruct {
    HashMap<Integer, Double> f1;
    int f2;
    Item f3;
    String f4;
    Color f5;
    List<String> f6;
    int f7;
    int f8; // Changed from Integer to int to match Rust
    int last; // Changed from Integer to int to match Rust
  }

  @Test
  public void testSimpleStruct() throws java.io.IOException {
    String caseName = "test_simple_struct";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(false)
            .build();
    fory.register(Color.class, 101);
    fory.register(Item.class, 102);
    fory.register(SimpleStruct.class, 103);

    Item item = new Item();
    item.name = "item";
    HashMap<Integer, Double> f1 = new HashMap<>();
    f1.put(1, 1.0);
    f1.put(2, 2.0);
    SimpleStruct obj = new SimpleStruct();
    obj.f1 = f1;
    obj.f2 = 39;
    obj.f3 = item;
    obj.f4 = "f4";
    obj.f5 = Color.White;
    obj.f6 = Collections.singletonList("f6");
    obj.f7 = 40;
    obj.f8 = 41;
    obj.last = 42;

    MemoryBuffer buffer = MemoryUtils.buffer(64);
    fory.serialize(buffer, obj);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);
    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    Assert.assertEquals(fory.deserialize(buffer2), obj);
  }

  @Test
  public void testSimpleNamedStruct() throws java.io.IOException {
    String caseName = "test_named_simple_struct";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(false)
            .build();
    fory.register(Color.class, "demo", "color");
    fory.register(Item.class, "demo", "item");
    fory.register(SimpleStruct.class, "demo", "simple_struct");

    Item item = new Item();
    item.name = "item";
    HashMap<Integer, Double> f1 = new HashMap<>();
    f1.put(1, 1.0);
    f1.put(2, 2.0);
    SimpleStruct obj = new SimpleStruct();
    obj.f1 = f1;
    obj.f2 = 39;
    obj.f3 = item;
    obj.f4 = "f4";
    obj.f5 = Color.White;
    obj.f6 = Collections.singletonList("f6");
    obj.f7 = 40;
    obj.f8 = 41;
    obj.last = 42;

    MemoryBuffer buffer = MemoryUtils.buffer(64);
    fory.serialize(buffer, obj);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);
    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    Assert.assertEquals(fory.deserialize(buffer2), obj);
  }

  @Test
  public void testList() throws java.io.IOException {
    String caseName = "test_list";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(false)
            .build();
    fory.register(Item.class, 102);
    MemoryBuffer buffer = MemoryUtils.buffer(64);
    List<String> strList = Arrays.asList("a", "b");
    List<String> strList2 = Arrays.asList(null, "b");
    Item item = new Item();
    item.name = "a";
    Item item2 = new Item();
    item2.name = "b";
    Item item3 = new Item();
    item3.name = "c";
    List<Item> itemList = Arrays.asList(item, item2);
    List<Item> itemList2 = Arrays.asList(null, item3);
    fory.serialize(buffer, strList);
    fory.serialize(buffer, strList2);
    fory.serialize(buffer, itemList);
    fory.serialize(buffer, itemList2);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);
    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    Assert.assertEquals(fory.deserialize(buffer2), strList);
    assertEqualsNullTolerant(fory.deserialize(buffer2), strList2);
    Assert.assertEquals(fory.deserialize(buffer2), itemList);
    assertEqualsNullTolerant(fory.deserialize(buffer2), itemList2);
  }

  @Test
  public void testMap() throws java.io.IOException {
    String caseName = "test_map";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(false)
            .build();
    fory.register(Item.class, 102);
    MemoryBuffer buffer = MemoryUtils.buffer(64);
    Map<String, String> strMap = new HashMap<>();
    strMap.put("k1", "v1");
    strMap.put(null, "v2");
    strMap.put("k3", null);
    strMap.put("k4", "v4");
    Item item = new Item();
    item.name = "item1";
    Item item2 = new Item();
    item2.name = "item2";
    Item item3 = new Item();
    item3.name = "item3";
    Map<String, Item> itemMap = new HashMap<>();
    itemMap.put("k1", item);
    itemMap.put(null, item2);
    itemMap.put("k3", null);
    itemMap.put("k4", item3);
    fory.serialize(buffer, strMap);
    fory.serialize(buffer, itemMap);
    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);
    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    assertEqualsNullTolerant(fory.deserialize(buffer2), strMap);
    assertEqualsNullTolerant(fory.deserialize(buffer2), itemMap);
  }

  static class Item1 {
    int f1;
    int f2;
    Integer f3;
    Integer f4;
    Integer f5;
    Integer f6;
  }

  @Test
  public void testInteger() throws java.io.IOException {
    String caseName = "test_integer";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCodegen(false)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory.register(Item1.class, 101);
    Item1 item1 = new Item1();
    int f1 = 1;
    int f2 = 2;
    Integer f3 = 3;
    Integer f4 = 4;
    Integer f5 = null;
    Integer f6 = null;
    item1.f1 = f1;
    item1.f2 = f2;
    item1.f3 = f3;
    item1.f4 = f4;
    item1.f5 = f5;
    item1.f6 = f6;
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);
    fory.serialize(buffer, item1);
    fory.serialize(buffer, f1);
    fory.serialize(buffer, f2);
    fory.serialize(buffer, f3);
    fory.serialize(buffer, f4);
    fory.serialize(buffer, f5);
    fory.serialize(buffer, f6);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    Item1 newItem1 = (Item1) fory.deserialize(buffer2);
    Assert.assertEquals(newItem1.f1, 1);
    Assert.assertEquals(newItem1.f2, 2);
    Assert.assertEquals(newItem1.f3, 3);
    Assert.assertEquals(newItem1.f4, 4);
    Assert.assertEquals(newItem1.f5, 0);
    Assert.assertNull(newItem1.f6);
    Assert.assertEquals(fory.deserialize(buffer2), 1);
    Assert.assertEquals(fory.deserialize(buffer2), 2);
    Assert.assertEquals(fory.deserialize(buffer2), 3);
    Assert.assertEquals(fory.deserialize(buffer2), 4);
    Assert.assertEquals(fory.deserialize(buffer2), 0);
    Assert.assertNull(fory.deserialize(buffer2));
  }

  @Test
  public void testItem() throws java.io.IOException {
    String caseName = "test_item";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(false)
            .build();
    fory.register(Item.class, 102);

    Item item1 = new Item();
    item1.name = "test_item_1";
    Item item2 = new Item();
    item2.name = "test_item_2";
    Item item3 = new Item();
    item3.name = null;

    MemoryBuffer buffer = MemoryUtils.buffer(64);
    fory.serialize(buffer, item1);
    fory.serialize(buffer, item2);
    fory.serialize(buffer, item3);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    Item readItem1 = (Item) fory.deserialize(buffer2);
    Assert.assertEquals(readItem1.name, "test_item_1");
    Item readItem2 = (Item) fory.deserialize(buffer2);
    Assert.assertEquals(readItem2.name, "test_item_2");
    Item readItem3 = (Item) fory.deserialize(buffer2);
    // Go uses string (not *string), so null becomes empty string
    // Rust uses Option<String>, so null stays null
    Assert.assertTrue(
        readItem3.name == null || readItem3.name.isEmpty(),
        "Expected null or empty string but got: " + readItem3.name);
  }

  @Test
  public void testColor() throws java.io.IOException {
    String caseName = "test_color";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(false)
            .build();
    fory.register(Color.class, 101);

    MemoryBuffer buffer = MemoryUtils.buffer(32);
    fory.serialize(buffer, Color.Green);
    fory.serialize(buffer, Color.Red);
    fory.serialize(buffer, Color.Blue);
    fory.serialize(buffer, Color.White);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    Assert.assertEquals(fory.deserialize(buffer2), Color.Green);
    Assert.assertEquals(fory.deserialize(buffer2), Color.Red);
    Assert.assertEquals(fory.deserialize(buffer2), Color.Blue);
    Assert.assertEquals(fory.deserialize(buffer2), Color.White);
  }

  @Data
  static class StructWithList {
    List<String> items;
  }

  @Test
  public void testStructWithList() throws java.io.IOException {
    String caseName = "test_struct_with_list";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(false)
            .build();
    fory.register(StructWithList.class, 201);

    StructWithList struct1 = new StructWithList();
    struct1.items = Arrays.asList("a", "b", "c");

    StructWithList struct2 = new StructWithList();
    struct2.items = Arrays.asList("x", null, "z");

    MemoryBuffer buffer = MemoryUtils.buffer(64);
    fory.serialize(buffer, struct1);
    fory.serialize(buffer, struct2);

    byte[] allBytes = buffer.getBytes(0, buffer.writerIndex());
    ExecutionContext ctx = prepareExecution(caseName, allBytes);
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    StructWithList readStruct1 = (StructWithList) fory.deserialize(buffer2);
    Assert.assertEquals(readStruct1.items, Arrays.asList("a", "b", "c"));
    StructWithList readStruct2 = (StructWithList) fory.deserialize(buffer2);
    assertEqualsNullTolerant(readStruct2.items, struct2.items);
  }

  @Data
  static class StructWithMap {
    Map<String, String> data;
  }

  @Test
  public void testStructWithMap() throws java.io.IOException {
    String caseName = "test_struct_with_map";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(false)
            .build();
    fory.register(StructWithMap.class, 202);

    StructWithMap struct1 = new StructWithMap();
    struct1.data = new HashMap<>();
    struct1.data.put("key1", "value1");
    struct1.data.put("key2", "value2");

    StructWithMap struct2 = new StructWithMap();
    struct2.data = new HashMap<>();
    struct2.data.put("k1", null);
    struct2.data.put(null, "v2");

    MemoryBuffer buffer = MemoryUtils.buffer(64);
    fory.serialize(buffer, struct1);
    fory.serialize(buffer, struct2);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    StructWithMap readStruct1 = (StructWithMap) fory.deserialize(buffer2);
    Assert.assertEquals(readStruct1.data.get("key1"), "value1");
    Assert.assertEquals(readStruct1.data.get("key2"), "value2");
    StructWithMap readStruct2 = (StructWithMap) fory.deserialize(buffer2);
    assertEqualsNullTolerant(readStruct2.data, struct2.data);
  }

  @Data
  static class MyStruct {
    int id;

    public MyStruct(int id) {
      this.id = id;
    }
  }

  @Data
  static class MyExt {
    int id;

    public MyExt(int id) {
      this.id = id;
    }

    public MyExt() {}
  }

  private static class MyExtSerializer extends Serializer<MyExt> {

    public MyExtSerializer(Fory fory, Class<MyExt> cls) {
      super(fory, cls);
    }

    @Override
    public void write(MemoryBuffer buffer, MyExt value) {
      xwrite(buffer, value);
    }

    @Override
    public MyExt read(MemoryBuffer buffer) {
      return xread(buffer);
    }

    @Override
    public void xwrite(MemoryBuffer buffer, MyExt value) {
      System.out.println("Writer Index Before write myext: " + buffer.writerIndex());
      buffer.writeVarInt32(value.id);
      System.out.println("Write id " + value.id);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MyExt xread(MemoryBuffer buffer) {
      MyExt obj = new MyExt();
      obj.id = buffer.readVarInt32();
      return obj;
    }
  }

  @Data
  static class MyWrapper {
    Color color;
    MyExt my_ext;
    MyStruct my_struct;
  }

  @Data
  static class EmptyWrapper {}

  private void _testSkipCustom(Fory fory1, Fory fory2, String caseName) throws IOException {
    MyWrapper wrapper = new MyWrapper();
    wrapper.color = Color.White;
    MyStruct myStruct = new MyStruct(42);
    MyExt myExt = new MyExt(43);
    wrapper.my_ext = myExt;
    wrapper.my_struct = myStruct;
    byte[] serialize = fory1.serialize(wrapper);
    ExecutionContext ctx = prepareExecution(caseName, serialize);
    runPeer(ctx);
    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    EmptyWrapper newWrapper = (EmptyWrapper) fory2.deserialize(buffer2);
    Assert.assertEquals(newWrapper, new EmptyWrapper());
  }

  @Test
  public void testSkipIdCustom() throws java.io.IOException {
    String caseName = "test_skip_id_custom";
    Fory fory1 =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(false)
            .build();
    fory1.register(Color.class, 101);
    fory1.register(MyStruct.class, 102);
    fory1.register(MyExt.class, 103);
    fory1.registerSerializer(MyExt.class, MyExtSerializer.class);
    fory1.register(MyWrapper.class, 104);
    Fory fory2 =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(false)
            .build();
    fory2.register(MyExt.class, 103);
    fory2.registerSerializer(MyExt.class, MyExtSerializer.class);
    fory2.register(EmptyWrapper.class, 104);
    _testSkipCustom(fory1, fory2, caseName);
  }

  @Test
  public void testSkipNameCustom() throws java.io.IOException {
    String caseName = "test_skip_name_custom";
    Fory fory1 =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(false)
            .build();
    fory1.register(Color.class, "color");
    fory1.register(MyStruct.class, "my_struct");
    fory1.register(MyExt.class, "my_ext");
    fory1.registerSerializer(MyExt.class, MyExtSerializer.class);
    fory1.register(MyWrapper.class, "my_wrapper");
    Fory fory2 =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(false)
            .build();
    fory2.register(MyExt.class, "my_ext");
    fory2.registerSerializer(MyExt.class, MyExtSerializer.class);
    fory2.register(EmptyWrapper.class, "my_wrapper");
    _testSkipCustom(fory1, fory2, caseName);
  }

  @Test
  public void testConsistentNamed() throws java.io.IOException {
    String caseName = "test_consistent_named";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
            .withCodegen(false)
            .withClassVersionCheck(true)
            .build();
    fory.register(Color.class, "color");
    fory.register(MyStruct.class, "my_struct");
    fory.register(MyExt.class, "my_ext");
    fory.registerSerializer(MyExt.class, MyExtSerializer.class);

    MyStruct myStruct = new MyStruct(42);
    MyExt myExt = new MyExt(43);
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(64);
    for (int i = 0; i < 3; i++) {
      fory.serialize(buffer, Color.White);
    }
    for (int i = 0; i < 3; i++) {
      fory.serialize(buffer, myStruct);
    }
    for (int i = 0; i < 3; i++) {
      fory.serialize(buffer, myExt);
    }
    byte[] bytes = buffer.getBytes(0, buffer.writerIndex());
    ExecutionContext ctx = prepareExecution(caseName, bytes);
    runPeer(ctx);
    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    for (int i = 0; i < 3; i++) {
      Object deserialized = fory.deserialize(buffer2);
      System.out.println("deserialized: " + deserialized);
      Assert.assertEquals(deserialized, Color.White);
    }
    for (int i = 0; i < 3; i++) {
      Assert.assertEquals(fory.deserialize(buffer2), myStruct);
    }
    for (int i = 0; i < 3; i++) {
      Assert.assertEquals(fory.deserialize(buffer2), myExt);
    }
  }

  @Data
  static class VersionCheckStruct {
    int f1;
    String f2;
    double f3;
  }

  @Test
  public void testStructVersionCheck() throws java.io.IOException {
    String caseName = "test_struct_version_check";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
            .withCodegen(false)
            .withClassVersionCheck(true)
            .build();
    fory.register(VersionCheckStruct.class, 201);

    VersionCheckStruct obj = new VersionCheckStruct();
    obj.f1 = 10;
    obj.f2 = "test";
    obj.f3 = 3.2;

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);
    fory.serialize(buffer, obj);
    byte[] bytes = buffer.getBytes(0, buffer.writerIndex());
    ExecutionContext ctx = prepareExecution(caseName, bytes);
    runPeer(ctx);
    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    Assert.assertEquals(fory.deserialize(buffer2), obj);
  }

  // ============================================================================
  // Polymorphic Container Tests - Test List/Map with interface element types
  // ============================================================================

  /** Base interface for polymorphic testing */
  public interface Animal {
    int getAge();

    String speak();
  }

  @Data
  public static class Dog implements Animal {
    int age;
    String name;

    @Override
    public String speak() {
      return "Woof";
    }
  }

  @Data
  public static class Cat implements Animal {
    int age;
    int lives;

    @Override
    public String speak() {
      return "Meow";
    }
  }

  @Data
  static class AnimalListHolder {
    List<Animal> animals;
  }

  @Data
  static class AnimalMapHolder {
    Map<String, Animal> animal_map;
  }

  @Test
  public void testPolymorphicList() throws java.io.IOException {
    String caseName = "test_polymorphic_list";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(false)
            .build();
    // Register concrete types, not the interface
    fory.register(Dog.class, 302);
    fory.register(Cat.class, 303);
    fory.register(AnimalListHolder.class, 304);

    // Part 1: Test List<Animal> with mixed types directly
    Dog dog = new Dog();
    dog.age = 3;
    dog.name = "Buddy";
    Cat cat = new Cat();
    cat.age = 5;
    cat.lives = 9;
    List<Animal> animals = Arrays.asList(dog, cat);

    // Part 2: Test List<Animal> as struct field
    AnimalListHolder holder = new AnimalListHolder();
    Dog dog2 = new Dog();
    dog2.age = 2;
    dog2.name = "Rex";
    Cat cat2 = new Cat();
    cat2.age = 4;
    cat2.lives = 7;
    holder.animals = Arrays.asList(dog2, cat2);

    MemoryBuffer buffer = MemoryUtils.buffer(128);
    fory.serialize(buffer, animals);
    fory.serialize(buffer, holder);

    byte[] allBytes = buffer.getBytes(0, buffer.writerIndex());
    ExecutionContext ctx = prepareExecution(caseName, allBytes);
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    List<Animal> readAnimals = (List<Animal>) fory.deserialize(buffer2);
    Assert.assertEquals(readAnimals.size(), 2);
    Assert.assertTrue(readAnimals.get(0) instanceof Dog);
    Assert.assertEquals(((Dog) readAnimals.get(0)).name, "Buddy");
    Assert.assertEquals(readAnimals.get(0).getAge(), 3);
    Assert.assertTrue(readAnimals.get(1) instanceof Cat);
    Assert.assertEquals(((Cat) readAnimals.get(1)).lives, 9);
    Assert.assertEquals(readAnimals.get(1).getAge(), 5);

    AnimalListHolder readHolder = (AnimalListHolder) fory.deserialize(buffer2);
    Assert.assertEquals(readHolder.animals.size(), 2);
    Assert.assertTrue(readHolder.animals.get(0) instanceof Dog);
    Assert.assertEquals(((Dog) readHolder.animals.get(0)).name, "Rex");
    Assert.assertTrue(readHolder.animals.get(1) instanceof Cat);
    Assert.assertEquals(((Cat) readHolder.animals.get(1)).lives, 7);
  }

  @Test
  public void testPolymorphicMap() throws java.io.IOException {
    String caseName = "test_polymorphic_map";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(false)
            .build();
    fory.register(Dog.class, 302);
    fory.register(Cat.class, 303);
    fory.register(AnimalMapHolder.class, 305);

    // Part 1: Test Map<String, Animal> with mixed types directly
    Dog dog = new Dog();
    dog.age = 2;
    dog.name = "Rex";
    Cat cat = new Cat();
    cat.age = 4;
    cat.lives = 9;
    Map<String, Animal> animalMap = new HashMap<>();
    animalMap.put("dog1", dog);
    animalMap.put("cat1", cat);

    // Part 2: Test Map<String, Animal> as struct field
    AnimalMapHolder holder = new AnimalMapHolder();
    Dog dog2 = new Dog();
    dog2.age = 1;
    dog2.name = "Fido";
    Cat cat2 = new Cat();
    cat2.age = 3;
    cat2.lives = 8;
    holder.animal_map = new HashMap<>();
    holder.animal_map.put("myDog", dog2);
    holder.animal_map.put("myCat", cat2);

    MemoryBuffer buffer = MemoryUtils.buffer(128);
    fory.serialize(buffer, animalMap);
    fory.serialize(buffer, holder);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    Map<String, Animal> readAnimalMap = (Map<String, Animal>) fory.deserialize(buffer2);
    Assert.assertEquals(readAnimalMap.size(), 2);
    Assert.assertTrue(readAnimalMap.get("dog1") instanceof Dog);
    Assert.assertEquals(((Dog) readAnimalMap.get("dog1")).name, "Rex");
    Assert.assertTrue(readAnimalMap.get("cat1") instanceof Cat);
    Assert.assertEquals(((Cat) readAnimalMap.get("cat1")).lives, 9);

    AnimalMapHolder readHolder = (AnimalMapHolder) fory.deserialize(buffer2);
    Assert.assertEquals(readHolder.animal_map.size(), 2);
    Assert.assertTrue(readHolder.animal_map.get("myDog") instanceof Dog);
    Assert.assertEquals(((Dog) readHolder.animal_map.get("myDog")).name, "Fido");
    Assert.assertTrue(readHolder.animal_map.get("myCat") instanceof Cat);
    Assert.assertEquals(((Cat) readHolder.animal_map.get("myCat")).lives, 8);
  }

  /**
   * Execute an external command.
   *
   * @return Whether the command succeeded.
   */
  private boolean executeCommand(
      List<String> command, int waitTimeoutSeconds, Map<String, String> env, File workDir) {
    ImmutableMap<String, String> mergedEnv =
        ImmutableMap.<String, String>builder()
            .putAll(env)
            .put("ENABLE_CROSS_LANGUAGE_TESTS", "true")
            .build();
    return TestUtils.executeCommand(command, waitTimeoutSeconds, mergedEnv, workDir);
  }

  private Object xserDe(Fory fory, Object obj) {
    byte[] bytes = fory.serialize(obj);
    return fory.deserialize(bytes);
  }

  // ============================================================================
  // String Field Struct Tests - Test schema evolution with string fields
  // ============================================================================

  @Data
  static class EmptyStruct {}

  @Data
  static class OneStringFieldStruct {
    String f1;
  }

  @Data
  static class TwoStringFieldStruct {
    String f1;
    String f2;
  }

  @Test
  public void testOneStringFieldSchemaConsistent() throws java.io.IOException {
    String caseName = "test_one_string_field_schema";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
            .build();
    fory.register(OneStringFieldStruct.class, 200);

    OneStringFieldStruct obj = new OneStringFieldStruct();
    obj.f1 = "hello";

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(64);
    fory.serialize(buffer, obj);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    OneStringFieldStruct result = (OneStringFieldStruct) fory.deserialize(buffer2);
    Assert.assertEquals(result.f1, "hello");
  }

  @Test
  public void testOneStringFieldCompatible() throws java.io.IOException {
    String caseName = "test_one_string_field_compatible";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory.register(OneStringFieldStruct.class, 200);

    OneStringFieldStruct obj = new OneStringFieldStruct();
    obj.f1 = "hello";

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(64);
    fory.serialize(buffer, obj);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    OneStringFieldStruct result = (OneStringFieldStruct) fory.deserialize(buffer2);
    Assert.assertEquals(result.f1, "hello");
  }

  @Test
  public void testTwoStringFieldCompatible() throws java.io.IOException {
    String caseName = "test_two_string_field_compatible";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory.register(TwoStringFieldStruct.class, 201);

    TwoStringFieldStruct obj = new TwoStringFieldStruct();
    obj.f1 = "first";
    obj.f2 = "second";

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(64);
    fory.serialize(buffer, obj);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    TwoStringFieldStruct result = (TwoStringFieldStruct) fory.deserialize(buffer2);
    Assert.assertEquals(result.f1, "first");
    Assert.assertEquals(result.f2, "second");
  }

  @Test
  public void testSchemaEvolutionCompatible() throws java.io.IOException {
    String caseName = "test_schema_evolution_compatible";
    // Fory for TwoStringFieldStruct
    Fory fory2 =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory2.register(TwoStringFieldStruct.class, 200);

    // Fory for EmptyStruct and OneStringFieldStruct with same type ID
    Fory foryEmpty =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    foryEmpty.register(EmptyStruct.class, 200);

    Fory fory1 =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory1.register(OneStringFieldStruct.class, 200);

    // Test 1: Serialize TwoStringFieldStruct, deserialize as Empty
    TwoStringFieldStruct obj2 = new TwoStringFieldStruct();
    obj2.f1 = "first";
    obj2.f2 = "second";

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(128);
    fory2.serialize(buffer, obj2);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());

    // Deserialize as EmptyStruct (should skip all fields)
    EmptyStruct emptyResult = (EmptyStruct) foryEmpty.deserialize(buffer2);
    Assert.assertNotNull(emptyResult);

    // Test 2: Serialize OneStringFieldStruct, deserialize as TwoStringFieldStruct
    OneStringFieldStruct obj1 = new OneStringFieldStruct();
    obj1.f1 = "only_one";

    buffer = MemoryBuffer.newHeapBuffer(64);
    fory1.serialize(buffer, obj1);

    // Debug: print Java output bytes
    byte[] javaBytes = buffer.getBytes(0, buffer.writerIndex());
    System.out.println("Java OneStringFieldStruct output " + javaBytes.length + " bytes:");
    for (int i = 0; i < javaBytes.length; i++) {
      if (i > 0 && i % 16 == 0) System.out.println();
      System.out.printf("%02x ", javaBytes[i] & 0xFF);
    }
    System.out.println();

    String caseName2 = "test_schema_evolution_compatible_reverse";
    ExecutionContext ctx2 = prepareExecution(caseName2, javaBytes);
    runPeer(ctx2);

    MemoryBuffer buffer3 = readBuffer(ctx2.dataFile());
    // Debug: print Go output bytes
    byte[] goBytes = buffer3.getBytes(0, buffer3.size());
    System.out.println("Go output " + goBytes.length + " bytes:");
    for (int i = 0; i < goBytes.length; i++) {
      if (i > 0 && i % 16 == 0) System.out.println();
      System.out.printf("%02x ", goBytes[i] & 0xFF);
    }
    System.out.println();

    TwoStringFieldStruct result2 = (TwoStringFieldStruct) fory2.deserialize(buffer3);
    Assert.assertEquals(result2.f1, "only_one");
    // Go uses empty string for missing fields (Go string can't be null)
    // Rust uses Option<String>, so missing fields are None/null
    Assert.assertTrue(
        result2.f2 == null || result2.f2.isEmpty(),
        "Expected null or empty string but got: " + result2.f2);
  }

  // Enum field structs for testing
  enum TestEnum {
    VALUE_A,
    VALUE_B,
    VALUE_C
  }

  @Data
  static class OneEnumFieldStruct {
    TestEnum f1;
  }

  @Data
  static class TwoEnumFieldStruct {
    TestEnum f1;
    TestEnum f2;
  }

  @Test
  public void testOneEnumFieldSchemaConsistent() throws java.io.IOException {
    String caseName = "test_one_enum_field_schema";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
            .build();
    fory.register(TestEnum.class, 210);
    fory.register(OneEnumFieldStruct.class, 211);

    OneEnumFieldStruct obj = new OneEnumFieldStruct();
    obj.f1 = TestEnum.VALUE_B;

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(64);
    fory.serialize(buffer, obj);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    OneEnumFieldStruct result = (OneEnumFieldStruct) fory.deserialize(buffer2);
    Assert.assertEquals(result.f1, TestEnum.VALUE_B);
  }

  @Test
  public void testOneEnumFieldCompatible() throws java.io.IOException {
    String caseName = "test_one_enum_field_compatible";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory.register(TestEnum.class, 210);
    fory.register(OneEnumFieldStruct.class, 211);

    OneEnumFieldStruct obj = new OneEnumFieldStruct();
    obj.f1 = TestEnum.VALUE_A;

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(64);
    fory.serialize(buffer, obj);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    OneEnumFieldStruct result = (OneEnumFieldStruct) fory.deserialize(buffer2);
    Assert.assertEquals(result.f1, TestEnum.VALUE_A);
  }

  @Test
  public void testTwoEnumFieldCompatible() throws java.io.IOException {
    String caseName = "test_two_enum_field_compatible";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory.register(TestEnum.class, 210);
    fory.register(TwoEnumFieldStruct.class, 212);

    TwoEnumFieldStruct obj = new TwoEnumFieldStruct();
    obj.f1 = TestEnum.VALUE_A;
    obj.f2 = TestEnum.VALUE_C;

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(64);
    fory.serialize(buffer, obj);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    TwoEnumFieldStruct result = (TwoEnumFieldStruct) fory.deserialize(buffer2);
    Assert.assertEquals(result.f1, TestEnum.VALUE_A);
    Assert.assertEquals(result.f2, TestEnum.VALUE_C);
  }

  @Test
  public void testEnumSchemaEvolutionCompatible() throws java.io.IOException {
    String caseName = "test_enum_schema_evolution_compatible";
    // Fory for TwoEnumFieldStruct
    Fory fory2 =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory2.register(TestEnum.class, 210);
    fory2.register(TwoEnumFieldStruct.class, 211);

    // Fory for EmptyStruct and OneEnumFieldStruct with same type ID
    Fory foryEmpty =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    foryEmpty.register(TestEnum.class, 210);
    foryEmpty.register(EmptyStruct.class, 211);

    Fory fory1 =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory1.register(TestEnum.class, 210);
    fory1.register(OneEnumFieldStruct.class, 211);

    // Test 1: Serialize TwoEnumFieldStruct, deserialize as Empty
    TwoEnumFieldStruct obj2 = new TwoEnumFieldStruct();
    obj2.f1 = TestEnum.VALUE_A;
    obj2.f2 = TestEnum.VALUE_B;

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(128);
    fory2.serialize(buffer, obj2);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());

    // Deserialize as EmptyStruct (should skip all fields)
    EmptyStruct emptyResult = (EmptyStruct) foryEmpty.deserialize(buffer2);
    Assert.assertNotNull(emptyResult);

    // Test 2: Serialize OneEnumFieldStruct, deserialize as TwoEnumFieldStruct
    OneEnumFieldStruct obj1 = new OneEnumFieldStruct();
    obj1.f1 = TestEnum.VALUE_C;

    buffer = MemoryBuffer.newHeapBuffer(64);
    fory1.serialize(buffer, obj1);

    // Debug: print Java output bytes
    byte[] javaBytes = buffer.getBytes(0, buffer.writerIndex());
    System.out.println("Java OneEnumFieldStruct output " + javaBytes.length + " bytes:");
    for (int i = 0; i < javaBytes.length; i++) {
      if (i > 0 && i % 16 == 0) System.out.println();
      System.out.printf("%02x ", javaBytes[i] & 0xFF);
    }
    System.out.println();

    String caseName2 = "test_enum_schema_evolution_compatible_reverse";
    ExecutionContext ctx2 = prepareExecution(caseName2, javaBytes);
    runPeer(ctx2);

    MemoryBuffer buffer3 = readBuffer(ctx2.dataFile());
    // Debug: print Go output bytes
    byte[] goBytes = buffer3.getBytes(0, buffer3.size());
    System.out.println("Go output " + goBytes.length + " bytes:");
    for (int i = 0; i < goBytes.length; i++) {
      if (i > 0 && i % 16 == 0) System.out.println();
      System.out.printf("%02x ", goBytes[i] & 0xFF);
    }
    System.out.println();

    TwoEnumFieldStruct result2 = (TwoEnumFieldStruct) fory2.deserialize(buffer3);
    Assert.assertEquals(result2.f1, TestEnum.VALUE_C);
    // Go uses zero value for missing enum fields (first enum value)
    Assert.assertNull(result2.f2);
  }

  // ============================================================================
  // Union type tests
  // ============================================================================

  /** Struct containing Union fields of different arities (Union through Union6). */
  @Data
  public static class StructWithUnionFields {
    public org.apache.fory.type.union.Union union;
    public org.apache.fory.type.union.Union2<String, Long> union2;
    public org.apache.fory.type.union.Union3<Integer, String, Double> union3;
    public org.apache.fory.type.union.Union4<Integer, String, Double, Boolean> union4;
    public org.apache.fory.type.union.Union5<Integer, String, Double, Boolean, Long> union5;
    public org.apache.fory.type.union.Union6<Integer, String, Double, Boolean, Long, Float> union6;
  }

  @Test
  public void testStructWithUnionFields() throws java.io.IOException {
    String caseName = "test_struct_with_union_fields";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
            .withCodegen(false)
            .build();
    fory.register(StructWithUnionFields.class, 200);

    StructWithUnionFields obj = new StructWithUnionFields();
    obj.union = new org.apache.fory.type.union.Union(0, 42);
    obj.union2 = org.apache.fory.type.union.Union2.ofT1("hello");
    obj.union3 = org.apache.fory.type.union.Union3.ofT2("world");
    obj.union4 = org.apache.fory.type.union.Union4.ofT3(3.14);
    obj.union5 = org.apache.fory.type.union.Union5.ofT4(true);
    obj.union6 = org.apache.fory.type.union.Union6.ofT5(999L);

    MemoryBuffer buffer = MemoryUtils.buffer(128);
    fory.serialize(buffer, obj);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    StructWithUnionFields result = (StructWithUnionFields) fory.deserialize(buffer2);

    Assert.assertEquals(result.union.getIndex(), 0);
    Assert.assertEquals(result.union.getValue(), 42);
    Assert.assertEquals(result.union2.getIndex(), 0);
    Assert.assertEquals(result.union2.getValue(), "hello");
    Assert.assertTrue(result.union2.isT1());
    Assert.assertEquals(result.union3.getIndex(), 1);
    Assert.assertEquals(result.union3.getValue(), "world");
    Assert.assertTrue(result.union3.isT2());
    Assert.assertEquals(result.union4.getIndex(), 2);
    Assert.assertEquals((Double) result.union4.getValue(), 3.14, 0.001);
    Assert.assertTrue(result.union4.isT3());
    Assert.assertEquals(result.union5.getIndex(), 3);
    Assert.assertEquals(result.union5.getValue(), true);
    Assert.assertTrue(result.union5.isT4());
    Assert.assertEquals(result.union6.getIndex(), 4);
    Assert.assertEquals(result.union6.getValue(), 999L);
    Assert.assertTrue(result.union6.isT5());
  }

  @Test
  public void testTopLevelUnion() throws java.io.IOException {
    String caseName = "test_top_level_union";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
            .withCodegen(false)
            .build();

    // Test Union with integer value
    org.apache.fory.type.union.Union union1 = new org.apache.fory.type.union.Union(0, 42);
    MemoryBuffer buffer = MemoryUtils.buffer(64);
    fory.serialize(buffer, union1);

    ExecutionContext ctx1 =
        prepareExecution(caseName + "_int", buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx1);

    MemoryBuffer buffer2 = readBuffer(ctx1.dataFile());
    org.apache.fory.type.union.Union result1 =
        (org.apache.fory.type.union.Union) fory.deserialize(buffer2);
    Assert.assertEquals(result1.getIndex(), 0);
    Assert.assertEquals(result1.getValue(), 42);

    // Test Union with string value
    org.apache.fory.type.union.Union union2 = new org.apache.fory.type.union.Union(1, "hello");
    buffer = MemoryUtils.buffer(64);
    fory.serialize(buffer, union2);

    ExecutionContext ctx2 =
        prepareExecution(caseName + "_str", buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx2);

    MemoryBuffer buffer3 = readBuffer(ctx2.dataFile());
    org.apache.fory.type.union.Union result2 =
        (org.apache.fory.type.union.Union) fory.deserialize(buffer3);
    Assert.assertEquals(result2.getIndex(), 1);
    Assert.assertEquals(result2.getValue(), "hello");
  }

  @SuppressWarnings("unchecked")
  private void assertStringEquals(Object actual, Object expected, boolean useToString) {
    if (useToString) {
      if (expected instanceof Map) {
        Map actualMap =
            (Map)
                ((Map) actual)
                    .entrySet().stream()
                        .collect(
                            Collectors.toMap(
                                (Map.Entry e) -> e.getKey().toString(),
                                (Map.Entry e) -> e.getValue().toString()));
        Map expectedMap =
            (Map)
                ((Map) expected)
                    .entrySet().stream()
                        .collect(
                            Collectors.toMap(
                                (Map.Entry e) -> e.getKey().toString(),
                                (Map.Entry e) -> e.getValue().toString()));
        Assert.assertEquals(actualMap, expectedMap);
      } else if (expected instanceof Set) {
        Object actualSet =
            ((Set) actual).stream().map(Object::toString).collect(Collectors.toSet());
        Object expectedSet =
            ((Set) expected).stream().map(Object::toString).collect(Collectors.toSet());
        Assert.assertEquals(actualSet, expectedSet);
      } else {
        Assert.assertEquals(actual.toString(), expected.toString());
      }
    } else {
      Assert.assertEquals(actual, expected);
    }
  }

  /**
   * Assert equality with null-tolerance for cross-language tests. Go strings cannot be null, so
   * null strings become empty strings "". This method first tries direct comparison, then
   * normalizes null to empty string and compares again. Prints normalized values on mismatch.
   */
  @SuppressWarnings("unchecked")
  protected void assertEqualsNullTolerant(Object actual, Object expected) {
    // First try direct comparison
    if (Objects.equals(actual, expected)) {
      return;
    }

    // Normalize and compare
    Object normalizedActual = normalizeNulls(actual);
    Object normalizedExpected = normalizeNulls(expected);

    if (!Objects.equals(normalizedActual, normalizedExpected)) {
      System.out.println("Assertion failed after null normalization:");
      System.out.println("  Expected (normalized): " + normalizedExpected);
      System.out.println("  Actual (normalized):   " + normalizedActual);
      Assert.fail(
          "Values differ even after null normalization.\n"
              + "Expected: "
              + expected
              + "\nActual: "
              + actual
              + "\nNormalized expected: "
              + normalizedExpected
              + "\nNormalized actual: "
              + normalizedActual);
    }
  }

  /** Normalize null values to empty strings in collections and maps recursively. */
  @SuppressWarnings("unchecked")
  private Object normalizeNulls(Object obj) {
    if (obj == null) {
      return "";
    }
    if (obj instanceof String) {
      return obj;
    }
    if (obj instanceof List) {
      List<?> list = (List<?>) obj;
      List<Object> normalized = new ArrayList<>(list.size());
      for (Object elem : list) {
        normalized.add(normalizeNulls(elem));
      }
      return normalized;
    }
    if (obj instanceof Set) {
      Set<?> set = (Set<?>) obj;
      Set<Object> normalized = new HashSet<>();
      for (Object elem : set) {
        normalized.add(normalizeNulls(elem));
      }
      return normalized;
    }
    if (obj instanceof Map) {
      Map<?, ?> map = (Map<?, ?>) obj;
      Map<Object, Object> normalized = new HashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        Object key = normalizeNulls(entry.getKey());
        Object value = normalizeNulls(entry.getValue());
        normalized.put(key, value);
      }
      return normalized;
    }
    // For other objects, return as-is
    return obj;
  }
}
