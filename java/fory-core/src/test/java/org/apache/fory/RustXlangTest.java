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
import org.apache.commons.lang3.tuple.Pair;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.test.TestUtils;
import org.apache.fory.util.MurmurHash3;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** Tests in this class need fory rust installed. */
// cd java/fory-core && mvn test -Dtest=org.apache.fory.RustXlangTest
@Test
public class RustXlangTest extends ForyTestBase {
  private static final Logger LOG = LoggerFactory.getLogger(RustXlangTest.class);

  private static final String RUST_EXECUTABLE = "cargo";
  private static final String RUST_MODULE = "test_cross_language";

  private static final List<String> rustBaseCommand =
      Arrays.asList(
          RUST_EXECUTABLE,
          "test",
          "--test",
          RUST_MODULE,
          "<RUST_TESTCASE>",
          "--",
          // Use this to get output
          "--nocapture",
          // Run unittest that ignored in rust
          "--ignored",
          // Exact match test name rather than prefix matching.
          "--exact");

  private static final int RUST_TESTCASE_INDEX = 4;

  @BeforeClass
  public void isRustJavaCIEnabled() {
    String enabled = System.getenv("FORY_RUST_JAVA_CI");
    enabled = null;
    if (enabled == null || !enabled.equals("1")) {
      throw new SkipException("Skipping RustXlangTest: FORY_RUST_JAVA_CI not set to 1");
    }
    boolean rustInstalled = true;
    try {
      Process process = new ProcessBuilder("rustc", "--version").start();
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        rustInstalled = false;
      }
    } catch (IOException | InterruptedException e) {
      rustInstalled = false;
    }
    if (!rustInstalled) {
      throw new SkipException("Skipping RustXlangTest: rust not installed");
    }
  }

  private List<String> setTestCase(String name) {
    List<String> command = rustBaseCommand;
    command.set(RUST_TESTCASE_INDEX, name);
    return command;
  }

  @Test
  public void testBuffer() throws IOException {
    String caseName = "test_buffer";
    List<String> command = setTestCase(caseName);
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

    Path dataFile = Files.createTempFile(caseName, "data");
    Pair<Map<String, String>, File> env_workdir =
        setFilePath(dataFile, buffer.getBytes(0, buffer.writerIndex()));
    Assert.assertTrue(executeCommand(command, 30, env_workdir.getLeft(), env_workdir.getRight()));

    buffer = MemoryUtils.wrap(Files.readAllBytes(dataFile));
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
    List<String> command = setTestCase(caseName);
    Path dataFile = Files.createTempFile(caseName, "data");
    MemoryBuffer buffer = MemoryUtils.buffer(100);
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

    Pair<Map<String, String>, File> env_workdir =
        setFilePath(dataFile, buffer.getBytes(0, buffer.writerIndex()));
    Assert.assertTrue(executeCommand(command, 30, env_workdir.getLeft(), env_workdir.getRight()));

    buffer = MemoryUtils.wrap(Files.readAllBytes(dataFile));
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
    List<String> command = setTestCase(caseName);
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    byte[] hash1 = Hashing.murmur3_128(47).hashBytes(new byte[] {1, 2, 8}).asBytes();
    buffer.writeBytes(hash1);
    byte[] hash2 =
        Hashing.murmur3_128(47)
            .hashBytes("01234567890123456789".getBytes(StandardCharsets.UTF_8))
            .asBytes();
    buffer.writeBytes(hash2);

    Path dataFile = Files.createTempFile(caseName, "data");
    Pair<Map<String, String>, File> env_workdir =
        setFilePath(dataFile, buffer.getBytes(0, buffer.writerIndex()));
    Assert.assertTrue(executeCommand(command, 30, env_workdir.getLeft(), env_workdir.getRight()));

    long[] longs = MurmurHash3.murmurhash3_x64_128(new byte[] {1, 2, 8}, 0, 3, 47);
    buffer.writerIndex(0);
    buffer.writeInt64(longs[0]);
    buffer.writeInt64(longs[1]);
    Files.write(
        dataFile, buffer.getBytes(0, buffer.writerIndex()), StandardOpenOption.TRUNCATE_EXISTING);
    Assert.assertTrue(executeCommand(command, 30, env_workdir.getLeft(), env_workdir.getRight()));
  }

  private void _testStringSerializer(Fory fory, Path dataFile, List<String> command)
      throws IOException {
    MemoryBuffer buffer = MemoryUtils.buffer(100);
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
    Pair<Map<String, String>, File> env_workdir =
        setFilePath(dataFile, buffer.getBytes(0, buffer.writerIndex()));
    Assert.assertTrue(executeCommand(command, 30, env_workdir.getLeft(), env_workdir.getRight()));
    buffer = MemoryUtils.wrap(Files.readAllBytes(dataFile));
    for (String expected : testStrings) {
      String actual = (String) fory.deserialize(buffer);
      Assert.assertEquals(actual, expected);
    }
  }

  @Test
  public void testStringSerializer() throws Exception {
    String caseName = "test_string_serializer";
    List<String> command = setTestCase(caseName);
    Path dataFile = Files.createTempFile(caseName, "data");
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    _testStringSerializer(fory, dataFile, command);
    Fory foryCompress =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withStringCompressed(true)
            .withWriteNumUtf16BytesForUtf8Encoding(false)
            .build();
    _testStringSerializer(foryCompress, dataFile, command);
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
    List<String> command = setTestCase(caseName);
    List<String> strList = Arrays.asList("hello", "world");
    Set<String> strSet = new HashSet<>(strList);
    Map<String, String> strMap = new HashMap();
    strMap.put("hello", "world");
    strMap.put("foo", "bar");
    Color color = Color.White;

    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory.register(Color.class, 101);
    MemoryBuffer buffer = MemoryUtils.buffer(32);
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
    Path dataFile = Files.createTempFile(caseName, "data");
    Pair<Map<String, String>, File> env_workdir =
        setFilePath(dataFile, buffer.getBytes(0, buffer.writerIndex()));
    Assert.assertTrue(executeCommand(command, 30, env_workdir.getLeft(), env_workdir.getRight()));
    MemoryBuffer buffer2 = MemoryUtils.wrap(Files.readAllBytes(dataFile));
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
    Integer f8;
    Integer last;
  }

  @Test
  public void testSimpleStruct() throws java.io.IOException {
    String caseName = "test_simple_struct";
    List<String> command = setTestCase(caseName);
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

    MemoryBuffer buffer = MemoryUtils.buffer(32);
    fory.serialize(buffer, obj);

    Path dataFile = Files.createTempFile(caseName, "data");
    Pair<Map<String, String>, File> env_workdir =
        setFilePath(dataFile, buffer.getBytes(0, buffer.writerIndex()));
    Assert.assertTrue(executeCommand(command, 30, env_workdir.getLeft(), env_workdir.getRight()));
    MemoryBuffer buffer2 = MemoryUtils.wrap(Files.readAllBytes(dataFile));
    Assert.assertEquals(fory.deserialize(buffer2), obj);
  }

  @Test
  public void testSimpleNamedStruct() throws java.io.IOException {
    String caseName = "test_named_simple_struct";
    List<String> command = setTestCase(caseName);
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

    MemoryBuffer buffer = MemoryUtils.buffer(32);
    fory.serialize(buffer, obj);

    Path dataFile = Files.createTempFile(caseName, "data");
    Pair<Map<String, String>, File> env_workdir =
        setFilePath(dataFile, buffer.getBytes(0, buffer.writerIndex()));
    Assert.assertTrue(executeCommand(command, 30, env_workdir.getLeft(), env_workdir.getRight()));
    MemoryBuffer buffer2 = MemoryUtils.wrap(Files.readAllBytes(dataFile));
    Assert.assertEquals(fory.deserialize(buffer2), obj);
  }

  @Test
  public void testList() throws java.io.IOException {
    String caseName = "test_list";
    List<String> command = setTestCase(caseName);
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(false)
            .build();
    fory.register(Item.class, 102);
    MemoryBuffer buffer = MemoryUtils.buffer(32);
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

    Path dataFile = Files.createTempFile(caseName, "data");
    Pair<Map<String, String>, File> env_workdir =
        setFilePath(dataFile, buffer.getBytes(0, buffer.writerIndex()));
    Assert.assertTrue(executeCommand(command, 30, env_workdir.getLeft(), env_workdir.getRight()));
    MemoryBuffer buffer2 = MemoryUtils.wrap(Files.readAllBytes(dataFile));
    Assert.assertEquals(fory.deserialize(buffer2), strList);
    Assert.assertEquals(fory.deserialize(buffer2), strList2);
    Assert.assertEquals(fory.deserialize(buffer2), itemList);
    Assert.assertEquals(fory.deserialize(buffer2), itemList2);
  }

  @Test
  public void testMap() throws java.io.IOException {
    String caseName = "test_map";
    List<String> command = setTestCase(caseName);
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(false)
            .build();
    fory.register(Item.class, 102);
    MemoryBuffer buffer = MemoryUtils.buffer(32);
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
    Path dataFile = Files.createTempFile(caseName, "data");
    Pair<Map<String, String>, File> env_workdir =
        setFilePath(dataFile, buffer.getBytes(0, buffer.writerIndex()));
    Assert.assertTrue(executeCommand(command, 30, env_workdir.getLeft(), env_workdir.getRight()));
    MemoryBuffer buffer2 = MemoryUtils.wrap(Files.readAllBytes(dataFile));
    Assert.assertEquals(fory.deserialize(buffer2), strMap);
    Assert.assertEquals(fory.deserialize(buffer2), itemMap);
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
    List<String> command = setTestCase(caseName);
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
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

    Path dataFile = Files.createTempFile(caseName, "data");
    Pair<Map<String, String>, File> env_workdir =
        setFilePath(dataFile, buffer.getBytes(0, buffer.writerIndex()));
    Assert.assertTrue(executeCommand(command, 30, env_workdir.getLeft(), env_workdir.getRight()));

    MemoryBuffer buffer2 = MemoryUtils.wrap(Files.readAllBytes(dataFile));
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
      buffer.writeVarInt32(value.id);
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

  private void _testSkipCustom(Fory fory1, Fory fory2, List<String> command, String caseName)
      throws IOException {
    MyWrapper wrapper = new MyWrapper();
    wrapper.color = Color.White;
    MyStruct myStruct = new MyStruct(42);
    MyExt myExt = new MyExt(43);
    wrapper.my_ext = myExt;
    wrapper.my_struct = myStruct;
    byte[] serialize = fory1.serialize(wrapper);
    Path dataFile = Files.createTempFile(caseName, "data");
    Pair<Map<String, String>, File> env_workdir = setFilePath(dataFile, serialize);
    Assert.assertTrue(executeCommand(command, 30, env_workdir.getLeft(), env_workdir.getRight()));
    MemoryBuffer buffer2 = MemoryUtils.wrap(Files.readAllBytes(dataFile));
    EmptyWrapper newWrapper = (EmptyWrapper) fory2.deserialize(buffer2);
    Assert.assertEquals(newWrapper, new EmptyWrapper());
  }

  @Test
  public void testSkipIdCustom() throws java.io.IOException {
    String caseName = "test_skip_id_custom";
    List<String> command = setTestCase(caseName);
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
    _testSkipCustom(fory1, fory2, command, caseName);
  }

  @Test
  public void testSkipNameCustom() throws java.io.IOException {
    String caseName = "test_skip_name_custom";
    List<String> command = setTestCase(caseName);
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
    _testSkipCustom(fory1, fory2, command, caseName);
  }

  @Test
  public void testConsistentNamed() throws java.io.IOException {
    String caseName = "test_consistent_named";
    List<String> command = setTestCase(caseName);
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
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);
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
    Path dataFile = Files.createTempFile(caseName, "data");
    Pair<Map<String, String>, File> env_workdir = setFilePath(dataFile, bytes);
    Assert.assertTrue(executeCommand(command, 30, env_workdir.getLeft(), env_workdir.getRight()));
    MemoryBuffer buffer2 = MemoryUtils.wrap(Files.readAllBytes(dataFile));
    for (int i = 0; i < 3; i++) {
      Assert.assertEquals(fory.deserialize(buffer2), Color.White);
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
    List<String> command = setTestCase(caseName);
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
    // Debug: print first 30 bytes
    System.out.println(
        "Java serialized bytes (first 30): "
            + java.util.Arrays.toString(
                java.util.Arrays.copyOf(bytes, Math.min(30, bytes.length))));
    Path dataFile = Files.createTempFile(caseName, "data");
    Pair<Map<String, String>, File> env_workdir = setFilePath(dataFile, bytes);
    Assert.assertTrue(executeCommand(command, 30, env_workdir.getLeft(), env_workdir.getRight()));
    MemoryBuffer buffer2 = MemoryUtils.wrap(Files.readAllBytes(dataFile));
    Assert.assertEquals(fory.deserialize(buffer2), obj);
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

  private static Pair<Map<String, String>, File> setFilePath(Path dataFile, byte[] data)
      throws IOException {
    Files.deleteIfExists(dataFile);
    Files.write(dataFile, data);
    dataFile.toFile().deleteOnExit();
    return Pair.of(
        ImmutableMap.of(
            "DATA_FILE", dataFile.toAbsolutePath().toString(),
            "RUSTFLAGS", "-Awarnings",
            "RUST_BACKTRACE", "1",
            "ENABLE_FORY_DEBUG_OUTPUT", "1",
            "FORY_PANIC_ON_ERROR", "1"),
        new File("../../rust"));
  }

  private Object xserDe(Fory fory, Object obj) {
    byte[] bytes = fory.serialize(obj);
    return fory.deserialize(bytes);
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
}
