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
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.meta.MetaCompressor;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.test.TestUtils;
import org.apache.fory.util.MurmurHash3;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Base class for cross-language (xlang) serialization tests.
 *
 * <p>This class provides common test infrastructure for testing Fory serialization compatibility
 * between Java and other languages (Python, Go, Rust, C++, JavaScript, etc.).
 *
 * <p>Subclasses must:
 *
 * <ul>
 *   <li>Implement {@link #ensurePeerReady()} to set up the peer language environment
 *   <li>Implement {@link #buildCommandContext(String, Path)} to build the command for executing
 *       peer tests
 *   <li>Override test methods with {@code @Test} annotation so they can be discovered when running
 *       {@code mvn test -Dtest=SubclassName}
 * </ul>
 *
 * @see PythonXlangTest
 * @see GoXlangTest
 * @see RustXlangTest
 * @see CPPXlangTest
 */
public abstract class XlangTestBase extends ForyTestBase {

  /**
   * A no-op MetaCompressor that returns data unchanged. Used to disable meta compression for
   * cross-language tests since Rust doesn't support decompression yet.
   */
  static class NoOpMetaCompressor implements MetaCompressor {
    @Override
    public byte[] compress(byte[] data, int offset, int size) {
      // Return a larger array to ensure compression is never "better"
      // This effectively disables compression
      byte[] result = new byte[size + 1];
      System.arraycopy(data, offset, result, 0, size);
      return result;
    }

    @Override
    public byte[] decompress(byte[] data, int offset, int size) {
      // Not needed since we never compress
      byte[] result = new byte[size];
      System.arraycopy(data, offset, result, 0, size);
      return result;
    }

    @Override
    public int hashCode() {
      return NoOpMetaCompressor.class.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof NoOpMetaCompressor;
    }
  }

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
            .withCodegen(false)
            .build();
    _testStringSerializer(fory, caseName);
    Fory foryCompress =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(false)
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
            .withCodegen(false)
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

  @Test(dataProvider = "enableCodegen")
  public void testSimpleStruct(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_simple_struct";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
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

  @Test(dataProvider = "enableCodegen")
  public void testSimpleNamedStruct(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_named_simple_struct";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
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

  @Test(dataProvider = "enableCodegen")
  public void testList(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_list";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
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

  @Test(dataProvider = "enableCodegen")
  public void testMap(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_map";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
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

  @Test(dataProvider = "enableCodegen")
  public void testInteger(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_integer";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCodegen(enableCodegen)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory.register(Item1.class, 101);
    Item1 item1 = new Item1();
    int f1 = 1;
    int f2 = 2;
    Integer f3 = 3;
    Integer f4 = 4;
    // Use 0 instead of null for xlang mode with nullable=false default
    // Go uses int (not *int), so null is not allowed
    Integer f5 = 0;
    Integer f6 = 0;
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
    // With nullable=false (xlang default), 0 should round-trip correctly
    Assert.assertEquals(newItem1.f5, (Integer) 0);
    Assert.assertEquals(newItem1.f6, (Integer) 0);
    Assert.assertEquals(fory.deserialize(buffer2), 1);
    Assert.assertEquals(fory.deserialize(buffer2), 2);
    Assert.assertEquals(fory.deserialize(buffer2), 3);
    Assert.assertEquals(fory.deserialize(buffer2), 4);
    Assert.assertEquals(fory.deserialize(buffer2), 0);
    Assert.assertEquals(fory.deserialize(buffer2), 0);
  }

  @Test(dataProvider = "enableCodegen")
  public void testItem(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_item";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
            .build();
    fory.register(Item.class, 102);

    Item item1 = new Item();
    item1.name = "test_item_1";
    Item item2 = new Item();
    item2.name = "test_item_2";
    Item item3 = new Item();
    // Use empty string instead of null - xlang mode uses nullable=false by default
    // Go strings are always non-nil (empty string for "no value")
    item3.name = "";

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
    // With nullable=false (xlang default), empty string should round-trip correctly
    Assert.assertEquals(readItem3.name, "");
  }

  @Test(dataProvider = "enableCodegen")
  public void testColor(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_color";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
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

  // Union xlang test - Java Union2 <-> Rust enum with single-field variants
  //
  // Union fields in xlang mode follow a special format:
  // - Rust writes: ref_flag + union_data (no type_id, since Union fields skip type info)
  // - Java reads: null_flag + union_data (directly calls UnionSerializer.read())
  //
  // AbstractObjectSerializer.readFinalObjectFieldValue and readOtherFieldValue
  // have special handling for Union types to skip reading type_id.
  @Data
  static class StructWithUnion2 {
    org.apache.fory.type.union.Union2<String, Long> union;
  }

  @Test(dataProvider = "enableCodegen")
  public void testUnionXlang(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_union_xlang";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
            .build();
    fory.register(StructWithUnion2.class, 301);

    // Create Union with String value (index 0)
    StructWithUnion2 struct1 = new StructWithUnion2();
    struct1.union = org.apache.fory.type.union.Union2.ofT1("hello");

    // Create Union with Long value (index 1)
    StructWithUnion2 struct2 = new StructWithUnion2();
    struct2.union = org.apache.fory.type.union.Union2.ofT2(42L);

    MemoryBuffer buffer = MemoryUtils.buffer(64);
    fory.serialize(buffer, struct1);
    fory.serialize(buffer, struct2);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    StructWithUnion2 readStruct1 = (StructWithUnion2) fory.deserialize(buffer2);
    Assert.assertEquals(readStruct1.union.getValue(), "hello");
    Assert.assertEquals(readStruct1.union.getIndex(), 0);

    StructWithUnion2 readStruct2 = (StructWithUnion2) fory.deserialize(buffer2);
    Assert.assertEquals(readStruct2.union.getValue(), 42L);
    Assert.assertEquals(readStruct2.union.getIndex(), 1);
  }

  @Data
  static class StructWithList {
    List<String> items;
  }

  @Test(dataProvider = "enableCodegen")
  public void testStructWithList(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_struct_with_list";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
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

  @Test(dataProvider = "enableCodegen")
  public void testStructWithMap(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_struct_with_map";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
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
    MyExt myExt;
    MyStruct myStruct;
  }

  @Data
  static class EmptyWrapper {}

  private void _testSkipCustom(Fory fory1, Fory fory2, String caseName) throws IOException {
    MyWrapper wrapper = new MyWrapper();
    wrapper.color = Color.White;
    MyStruct myStruct = new MyStruct(42);
    wrapper.myExt = new MyExt(43);
    wrapper.myStruct = myStruct;
    byte[] serialize = fory1.serialize(wrapper);
    ExecutionContext ctx = prepareExecution(caseName, serialize);
    runPeer(ctx);
    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    EmptyWrapper newWrapper = (EmptyWrapper) fory2.deserialize(buffer2);
    Assert.assertEquals(newWrapper, new EmptyWrapper());
  }

  @Test(dataProvider = "enableCodegen")
  public void testSkipIdCustom(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_skip_id_custom";
    Fory fory1 =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
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
            .withCodegen(enableCodegen)
            .build();
    fory2.register(MyExt.class, 103);
    fory2.registerSerializer(MyExt.class, MyExtSerializer.class);
    fory2.register(EmptyWrapper.class, 104);
    _testSkipCustom(fory1, fory2, caseName);
  }

  @Test(dataProvider = "enableCodegen")
  public void testSkipNameCustom(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_skip_name_custom";
    Fory fory1 =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
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
            .withCodegen(enableCodegen)
            .build();
    fory2.register(MyExt.class, "my_ext");
    fory2.registerSerializer(MyExt.class, MyExtSerializer.class);
    fory2.register(EmptyWrapper.class, "my_wrapper");
    _testSkipCustom(fory1, fory2, caseName);
  }

  @Test(dataProvider = "enableCodegen")
  public void testConsistentNamed(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_consistent_named";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
            .withCodegen(enableCodegen)
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

    @ForyField(id = -1, nullable = true)
    String f2;

    double f3;
  }

  @Test(dataProvider = "enableCodegen")
  public void testStructVersionCheck(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_struct_version_check";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
            .withCodegen(enableCodegen)
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

    @ForyField(id = -1, nullable = true)
    String name;

    @Override
    public int getAge() {
      return age;
    }

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
    public int getAge() {
      return age;
    }

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
    // Using snake_case field name to test fallback lookup in ClassDef.getDescriptors()
    Map<String, Animal> animal_map;
  }

  @Test(dataProvider = "enableCodegen")
  public void testPolymorphicList(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_polymorphic_list";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
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

  @Test(dataProvider = "enableCodegen")
  public void testPolymorphicMap(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_polymorphic_map";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
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
    @ForyField(id = -1, nullable = true)
    String f1;
  }

  @Data
  static class TwoStringFieldStruct {
    String f1;
    String f2;
  }

  @Test(dataProvider = "enableCodegen")
  public void testOneStringFieldSchemaConsistent(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_one_string_field_schema";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
            .withCodegen(enableCodegen)
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

  @Test(dataProvider = "enableCodegen")
  public void testOneStringFieldCompatible(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_one_string_field_compatible";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
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

  @Test(dataProvider = "enableCodegen")
  public void testTwoStringFieldCompatible(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_two_string_field_compatible";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
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

  @Test(dataProvider = "enableCodegen")
  public void testSchemaEvolutionCompatible(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_schema_evolution_compatible";
    // Fory for TwoStringFieldStruct
    Fory fory2 =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
            .build();
    fory2.register(TwoStringFieldStruct.class, 200);

    // Fory for EmptyStruct and OneStringFieldStruct with same type ID
    Fory foryEmpty =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
            .build();
    foryEmpty.register(EmptyStruct.class, 200);

    Fory fory1 =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
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
    byte[] javaBytes = buffer.getBytes(0, buffer.writerIndex());
    String caseName2 = "test_schema_evolution_compatible_reverse";
    ExecutionContext ctx2 = prepareExecution(caseName2, javaBytes);
    runPeer(ctx2);

    MemoryBuffer buffer3 = readBuffer(ctx2.dataFile());
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

  @Test(dataProvider = "enableCodegen")
  public void testOneEnumFieldSchemaConsistent(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_one_enum_field_schema";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
            .withCodegen(enableCodegen)
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

  @Test(dataProvider = "enableCodegen")
  public void testOneEnumFieldCompatible(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_one_enum_field_compatible";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
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

  @Test(dataProvider = "enableCodegen")
  public void testTwoEnumFieldCompatible(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_two_enum_field_compatible";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
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

  @Test(dataProvider = "enableCodegen")
  public void testEnumSchemaEvolutionCompatible(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_enum_schema_evolution_compatible";
    // Fory for TwoEnumFieldStruct
    Fory fory2 =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
            .build();
    fory2.register(TestEnum.class, 210);
    fory2.register(TwoEnumFieldStruct.class, 211);

    // Fory for EmptyStruct and OneEnumFieldStruct with same type ID
    Fory foryEmpty =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
            .build();
    foryEmpty.register(TestEnum.class, 210);
    foryEmpty.register(EmptyStruct.class, 211);

    Fory fory1 =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
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
    String caseName2 = "test_enum_schema_evolution_compatible_reverse";
    ExecutionContext ctx2 = prepareExecution(caseName2, javaBytes);
    runPeer(ctx2);

    MemoryBuffer buffer3 = readBuffer(ctx2.dataFile());
    // Debug: print Go output bytes
    byte[] goBytes = buffer3.getBytes(0, buffer3.size());
    TwoEnumFieldStruct result2 = (TwoEnumFieldStruct) fory2.deserialize(buffer3);
    Assert.assertEquals(result2.f1, TestEnum.VALUE_C);
    // With nullable=false (xlang default), peer writes zero value for nil pointers.
    // So f2 should be VALUE_A (ordinal 0), not null.
    // Go is an exception: it writes null for nil pointers (nullable=true by default).
    Assert.assertEquals(result2.f2, TestEnum.VALUE_A);
  }

  // ============================================================================
  // Nullable Field Tests - Comprehensive nullable field testing
  // ============================================================================

  /**
   * Comprehensive struct for testing nullable fields in SCHEMA_CONSISTENT mode (compatible=false).
   *
   * <p>Fields are organized as:
   *
   * <ul>
   *   <li>Base non-nullable fields: byte, short, int, long, float, double, bool, string, list, set,
   *       map
   *   <li>Nullable fields (first half - boxed numeric types): Integer, Long, Float
   *   <li>Nullable fields (second half - @ForyField): Double, Boolean, String, List, Set, Map
   * </ul>
   */
  @Data
  static class NullableComprehensiveSchemaConsistent {
    // Base non-nullable primitive fields
    byte byteField;
    short shortField;
    int intField;
    long longField;
    float floatField;
    double doubleField;
    boolean boolField;

    // Base non-nullable reference fields
    String stringField;
    List<String> listField;
    Set<String> setField;
    Map<String, String> mapField;

    // Nullable fields - first half using boxed types
    @ForyField(nullable = true)
    Integer nullableInt;

    @ForyField(nullable = true)
    Long nullableLong;

    @ForyField(nullable = true)
    Float nullableFloat;

    // Nullable fields - second half using @ForyField annotation
    @ForyField(nullable = true)
    Double nullableDouble;

    @ForyField(nullable = true)
    Boolean nullableBool;

    @ForyField(nullable = true)
    String nullableString;

    @ForyField(nullable = true)
    List<String> nullableList;

    @ForyField(nullable = true)
    Set<String> nullableSet;

    @ForyField(nullable = true)
    Map<String, String> nullableMap;
  }

  @Test(dataProvider = "enableCodegen")
  public void testNullableFieldSchemaConsistentNotNull(boolean enableCodegen)
      throws java.io.IOException {
    String caseName = "test_nullable_field_schema_consistent_not_null";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
            .withCodegen(enableCodegen)
            .build();
    fory.register(NullableComprehensiveSchemaConsistent.class, 401);

    NullableComprehensiveSchemaConsistent obj = new NullableComprehensiveSchemaConsistent();
    // Base non-nullable primitive fields
    obj.byteField = 1;
    obj.shortField = 2;
    obj.intField = 42;
    obj.longField = 123456789L;
    obj.floatField = 1.5f;
    obj.doubleField = 2.5;
    obj.boolField = true;

    // Base non-nullable reference fields
    obj.stringField = "hello";
    obj.listField = Arrays.asList("a", "b", "c");
    obj.setField = new HashSet<>(Arrays.asList("x", "y"));
    obj.mapField = new HashMap<>();
    obj.mapField.put("key1", "value1");
    obj.mapField.put("key2", "value2");

    // Nullable fields - all have values (first half - boxed)
    obj.nullableInt = 100;
    obj.nullableLong = 200L;
    obj.nullableFloat = 1.5f;

    // Nullable fields - all have values (second half - @ForyField)
    obj.nullableDouble = 2.5;
    obj.nullableBool = false;
    obj.nullableString = "nullable_value";
    obj.nullableList = Arrays.asList("p", "q");
    obj.nullableSet = new HashSet<>(Arrays.asList("m", "n"));
    obj.nullableMap = new HashMap<>();
    obj.nullableMap.put("nk1", "nv1");

    // First verify Java serialization works
    Assert.assertEquals(xserDe(fory, obj), obj);

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(512);
    fory.serialize(buffer, obj);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    NullableComprehensiveSchemaConsistent result =
        (NullableComprehensiveSchemaConsistent) fory.deserialize(buffer2);
    Assert.assertEquals(result, obj);
  }

  @Test(dataProvider = "enableCodegen")
  public void testNullableFieldSchemaConsistentNull(boolean enableCodegen)
      throws java.io.IOException {
    String caseName = "test_nullable_field_schema_consistent_null";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
            .withCodegen(enableCodegen)
            .build();
    fory.register(NullableComprehensiveSchemaConsistent.class, 401);

    NullableComprehensiveSchemaConsistent obj = new NullableComprehensiveSchemaConsistent();
    // Base non-nullable primitive fields - must have values
    obj.byteField = 1;
    obj.shortField = 2;
    obj.intField = 42;
    obj.longField = 123456789L;
    obj.floatField = 1.5f;
    obj.doubleField = 2.5;
    obj.boolField = true;

    // Base non-nullable reference fields - must have values
    obj.stringField = "hello";
    obj.listField = Arrays.asList("a", "b", "c");
    obj.setField = new HashSet<>(Arrays.asList("x", "y"));
    obj.mapField = new HashMap<>();
    obj.mapField.put("key1", "value1");
    obj.mapField.put("key2", "value2");

    // Nullable fields - all null (first half - boxed)
    obj.nullableInt = null;
    obj.nullableLong = null;
    obj.nullableFloat = null;

    // Nullable fields - all null (second half - @ForyField)
    obj.nullableDouble = null;
    obj.nullableBool = null;
    obj.nullableString = null;
    obj.nullableList = null;
    obj.nullableSet = null;
    obj.nullableMap = null;

    // First verify Java serialization works
    Assert.assertEquals(xserDe(fory, obj), obj);

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(512);
    fory.serialize(buffer, obj);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    NullableComprehensiveSchemaConsistent result =
        (NullableComprehensiveSchemaConsistent) fory.deserialize(buffer2);
    Assert.assertEquals(result, obj);
  }

  /**
   * Comprehensive struct for testing nullable fields in COMPATIBLE mode.
   *
   * <p>Fields are organized as:
   *
   * <ul>
   *   <li>Base non-nullable fields: byte, short, int, long, float, double, bool, boxed types,
   *       string, list, set, map
   *   <li>Nullable group 1 (boxed types): Integer, Long, Float, Double, Boolean
   *   <li>Nullable group 2 (@ForyField): String, List, Set, Map
   * </ul>
   *
   * <p>In other languages, group 1 fields should be nullable, group 2 fields should be not-null.
   */
  @Data
  static class NullableComprehensiveCompatible {
    // Base non-nullable primitive fields
    byte byteField;
    short shortField;
    int intField;
    long longField;
    float floatField;
    double doubleField;
    boolean boolField;

    // Base non-nullable boxed fields (not nullable by default in xlang)
    Integer boxedInt;
    Long boxedLong;
    Float boxedFloat;
    Double boxedDouble;
    Boolean boxedBool;

    // Base non-nullable reference fields
    String stringField;
    List<String> listField;
    Set<String> setField;
    Map<String, String> mapField;

    // Nullable group 1 - boxed types with @ForyField(nullable=true)
    @ForyField(nullable = true)
    Integer nullableInt1;

    @ForyField(nullable = true)
    Long nullableLong1;

    @ForyField(nullable = true)
    Float nullableFloat1;

    @ForyField(nullable = true)
    Double nullableDouble1;

    @ForyField(nullable = true)
    Boolean nullableBool1;

    // Nullable group 2 - reference types with @ForyField(nullable=true)
    @ForyField(nullable = true)
    String nullableString2;

    @ForyField(nullable = true)
    List<String> nullableList2;

    @ForyField(nullable = true)
    Set<String> nullableSet2;

    @ForyField(nullable = true)
    Map<String, String> nullableMap2;
  }

  @Test(dataProvider = "enableCodegen")
  public void testNullableFieldCompatibleNotNull(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_nullable_field_compatible_not_null";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
            .withMetaCompressor(new NoOpMetaCompressor())
            .build();
    fory.register(NullableComprehensiveCompatible.class, 402);

    NullableComprehensiveCompatible obj = new NullableComprehensiveCompatible();
    // Base non-nullable primitive fields
    obj.byteField = 1;
    obj.shortField = 2;
    obj.intField = 42;
    obj.longField = 123456789L;
    obj.floatField = 1.5f;
    obj.doubleField = 2.5;
    obj.boolField = true;

    // Base non-nullable boxed fields
    obj.boxedInt = 10;
    obj.boxedLong = 20L;
    obj.boxedFloat = 1.1f;
    obj.boxedDouble = 2.2;
    obj.boxedBool = true;

    // Base non-nullable reference fields
    obj.stringField = "hello";
    obj.listField = Arrays.asList("a", "b", "c");
    obj.setField = new HashSet<>(Arrays.asList("x", "y"));
    obj.mapField = new HashMap<>();
    obj.mapField.put("key1", "value1");
    obj.mapField.put("key2", "value2");

    // Nullable group 1 - all have values
    obj.nullableInt1 = 100;
    obj.nullableLong1 = 200L;
    obj.nullableFloat1 = 1.5f;
    obj.nullableDouble1 = 2.5;
    obj.nullableBool1 = false;

    // Nullable group 2 - all have values
    obj.nullableString2 = "nullable_value";
    obj.nullableList2 = Arrays.asList("p", "q");
    obj.nullableSet2 = new HashSet<>(Arrays.asList("m", "n"));
    obj.nullableMap2 = new HashMap<>();
    obj.nullableMap2.put("nk1", "nv1");

    // First verify Java serialization works
    Assert.assertEquals(xserDe(fory, obj), obj);

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(1024);
    fory.serialize(buffer, obj);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    NullableComprehensiveCompatible result =
        (NullableComprehensiveCompatible) fory.deserialize(buffer2);
    Assert.assertEquals(result, obj);
  }

  @Test(dataProvider = "enableCodegen")
  public void testNullableFieldCompatibleNull(boolean enableCodegen) throws java.io.IOException {
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

    // First verify Java serialization works
    Assert.assertEquals(xserDe(fory, obj), obj);

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(1024);
    fory.serialize(buffer, obj);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    NullableComprehensiveCompatible result =
        (NullableComprehensiveCompatible) fory.deserialize(buffer2);

    // Build expected object: when Rust re-serializes, its non-nullable fields
    // send default values (0, false, empty) instead of null.
    // Java should receive these default values, not null.
    NullableComprehensiveCompatible expected = new NullableComprehensiveCompatible();
    // Base non-nullable fields - unchanged
    expected.byteField = obj.byteField;
    expected.shortField = obj.shortField;
    expected.intField = obj.intField;
    expected.longField = obj.longField;
    expected.floatField = obj.floatField;
    expected.doubleField = obj.doubleField;
    expected.boolField = obj.boolField;
    expected.boxedInt = obj.boxedInt;
    expected.boxedLong = obj.boxedLong;
    expected.boxedFloat = obj.boxedFloat;
    expected.boxedDouble = obj.boxedDouble;
    expected.boxedBool = obj.boxedBool;
    expected.stringField = obj.stringField;
    expected.listField = obj.listField;
    expected.setField = obj.setField;
    expected.mapField = obj.mapField;
    // Nullable group 1 - Rust's non-nullable fields send defaults
    expected.nullableInt1 = 0;
    expected.nullableLong1 = 0L;
    expected.nullableFloat1 = 0.0f;
    expected.nullableDouble1 = 0.0;
    expected.nullableBool1 = false;
    // Nullable group 2 - Rust's non-nullable fields send empty values
    expected.nullableString2 = "";
    expected.nullableList2 = new ArrayList<>();
    expected.nullableSet2 = new HashSet<>();
    expected.nullableMap2 = new HashMap<>();

    Assert.assertEquals(result, expected);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
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

  // ============================================================================
  // Reference Tracking Tests - Test struct field reference sharing
  // ============================================================================

  /**
   * Inner struct for reference tracking tests in SCHEMA_CONSISTENT mode (compatible=false). A
   * simple struct with id and name fields.
   */
  @Data
  static class RefInnerSchemaConsistent {
    int id;
    String name;
  }

  /**
   * Outer struct for reference tracking tests in SCHEMA_CONSISTENT mode. Contains two fields that
   * can point to the same RefInnerSchemaConsistent instance. Both fields have ref tracking enabled.
   */
  @Data
  static class RefOuterSchemaConsistent {
    @ForyField(ref = true, nullable = true, morphic = ForyField.Morphic.FINAL)
    RefInnerSchemaConsistent inner1;

    @ForyField(ref = true, nullable = true, morphic = ForyField.Morphic.FINAL)
    RefInnerSchemaConsistent inner2;
  }

  /**
   * Test reference tracking in SCHEMA_CONSISTENT mode (compatible=false). Creates an outer struct
   * with two fields pointing to the same inner struct instance. Verifies that after
   * serialization/deserialization across languages, both fields still reference the same object.
   */
  @Test(dataProvider = "enableCodegen")
  public void testRefSchemaConsistent(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_ref_schema_consistent";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
            .withRefTracking(true)
            .withCodegen(enableCodegen)
            .build();
    fory.register(RefInnerSchemaConsistent.class, 501);
    fory.register(RefOuterSchemaConsistent.class, 502);

    // Create inner struct
    RefInnerSchemaConsistent inner = new RefInnerSchemaConsistent();
    inner.id = 42;
    inner.name = "shared_inner";

    // Create outer struct with both fields pointing to the same inner struct
    RefOuterSchemaConsistent outer = new RefOuterSchemaConsistent();
    outer.inner1 = inner;
    outer.inner2 = inner; // Same reference as inner1

    // Verify Java serialization preserves reference identity
    byte[] javaBytes = fory.serialize(outer);
    RefOuterSchemaConsistent javaResult = (RefOuterSchemaConsistent) fory.deserialize(javaBytes);
    Assert.assertSame(
        javaResult.inner1, javaResult.inner2, "Java: inner1 and inner2 should be same object");
    Assert.assertEquals(javaResult.inner1.id, 42);
    Assert.assertEquals(javaResult.inner1.name, "shared_inner");

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(256);
    fory.serialize(buffer, outer);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    RefOuterSchemaConsistent result = (RefOuterSchemaConsistent) fory.deserialize(buffer2);

    // Verify reference identity is preserved after cross-language round-trip
    Assert.assertSame(
        result.inner1,
        result.inner2,
        "After xlang round-trip: inner1 and inner2 should be same object");
    Assert.assertEquals(result.inner1.id, 42);
    Assert.assertEquals(result.inner1.name, "shared_inner");
  }

  /**
   * Inner struct for reference tracking tests in COMPATIBLE mode (compatible=true). A simple struct
   * with id and name fields.
   */
  @Data
  static class RefInnerCompatible {
    int id;
    String name;
  }

  /**
   * Outer struct for reference tracking tests in COMPATIBLE mode. Contains two fields that can
   * point to the same RefInnerCompatible instance. Both fields have ref tracking enabled.
   */
  @Data
  static class RefOuterCompatible {
    @ForyField(ref = true, nullable = true)
    RefInnerCompatible inner1;

    @ForyField(ref = true, nullable = true)
    RefInnerCompatible inner2;
  }

  /**
   * Test reference tracking in COMPATIBLE mode (compatible=true). Creates an outer struct with two
   * fields pointing to the same inner struct instance. Verifies that after
   * serialization/deserialization across languages, both fields still reference the same object.
   */
  @Test(dataProvider = "enableCodegen")
  public void testRefCompatible(boolean enableCodegen) throws java.io.IOException {
    String caseName = "test_ref_compatible";
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withRefTracking(true)
            .withCodegen(enableCodegen)
            .withMetaCompressor(new NoOpMetaCompressor())
            .build();
    fory.register(RefInnerCompatible.class, 503);
    fory.register(RefOuterCompatible.class, 504);

    // Create inner struct
    RefInnerCompatible inner = new RefInnerCompatible();
    inner.id = 99;
    inner.name = "compatible_shared";

    // Create outer struct with both fields pointing to the same inner struct
    RefOuterCompatible outer = new RefOuterCompatible();
    outer.inner1 = inner;
    outer.inner2 = inner; // Same reference as inner1

    // Verify Java serialization preserves reference identity
    byte[] javaBytes = fory.serialize(outer);
    RefOuterCompatible javaResult = (RefOuterCompatible) fory.deserialize(javaBytes);
    Assert.assertSame(
        javaResult.inner1, javaResult.inner2, "Java: inner1 and inner2 should be same object");
    Assert.assertEquals(javaResult.inner1.id, 99);
    Assert.assertEquals(javaResult.inner1.name, "compatible_shared");

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(256);
    fory.serialize(buffer, outer);

    ExecutionContext ctx = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    runPeer(ctx);

    MemoryBuffer buffer2 = readBuffer(ctx.dataFile());
    RefOuterCompatible result = (RefOuterCompatible) fory.deserialize(buffer2);

    // Verify reference identity is preserved after cross-language round-trip
    Assert.assertSame(
        result.inner1,
        result.inner2,
        "After xlang round-trip: inner1 and inner2 should be same object");
    Assert.assertEquals(result.inner1.id, 99);
    Assert.assertEquals(result.inner1.name, "compatible_shared");
  }

  /** Normalize null values to empty strings in collections and maps recursively. */
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
