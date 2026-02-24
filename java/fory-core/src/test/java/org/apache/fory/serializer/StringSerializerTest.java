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

package org.apache.fory.serializer;

import static org.apache.fory.serializer.StringSerializer.newBytesStringZeroCopy;
import static org.testng.Assert.assertEquals;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.util.MathUtils;
import org.apache.fory.util.StringUtils;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class StringSerializerTest extends ForyTestBase {
  @DataProvider(name = "stringCompress")
  public static Object[][] stringCompress() {
    return new Object[][] {{false}, {true}};
  }

  @Test
  public void testJavaStringZeroCopy() {
    if (Platform.JAVA_VERSION >= 17) {
      throw new SkipException("Skip on jdk17+");
    }
    // Ensure JavaStringZeroCopy work for CI and most development environments.
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);
    for (int i = 0; i < 32; i++) {
      for (int j = 0; j < 32; j++) {
        String str = StringUtils.random(j);
        if (j % 2 == 0) {
          str += "你好"; // utf16
        }
        Assert.assertTrue(writeJavaStringZeroCopy(buffer, str));
        String newStr = readJavaStringZeroCopy(buffer);
        Assert.assertEquals(str, newStr, String.format("i %s j %s", i, j));
      }
    }
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testJavaStringCopy(Fory fory) {
    for (int i = 0; i < 32; i++) {
      for (int j = 0; j < 32; j++) {
        String str = StringUtils.random(j);
        if (j % 2 == 0) {
          str += "你好"; // utf16
        }
        copyCheckWithoutSame(fory, str);
      }
    }
  }

  private static String readJavaStringZeroCopy(MemoryBuffer buffer) {
    try {
      Field valueIsBytesField =
          StringSerializer.class.getDeclaredField("STRING_VALUE_FIELD_IS_BYTES");
      valueIsBytesField.setAccessible(true);
      boolean STRING_VALUE_FIELD_IS_BYTES = (boolean) valueIsBytesField.get(null);
      Field valueIsCharsField =
          StringSerializer.class.getDeclaredField("STRING_VALUE_FIELD_IS_CHARS");
      valueIsCharsField.setAccessible(true);
      boolean STRING_VALUE_FIELD_IS_CHARS = (Boolean) valueIsCharsField.get(null);
      if (STRING_VALUE_FIELD_IS_BYTES) {
        return readJDK11String(buffer);
      } else if (STRING_VALUE_FIELD_IS_CHARS) {
        return StringSerializer.newCharsStringZeroCopy(buffer.readChars(buffer.readVarUint32()));
      }
      return null;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  static String readJDK11String(MemoryBuffer buffer) {
    long header = buffer.readVarUint36Small();
    byte coder = (byte) (header & 0b11);
    int numBytes = (int) (header >>> 2);
    return newBytesStringZeroCopy(coder, buffer.readBytes(numBytes));
  }

  private static boolean writeJavaStringZeroCopy(MemoryBuffer buffer, String value) {
    try {
      Field valueIsBytesField =
          StringSerializer.class.getDeclaredField("STRING_VALUE_FIELD_IS_BYTES");
      valueIsBytesField.setAccessible(true);
      boolean STRING_VALUE_FIELD_IS_BYTES = (boolean) valueIsBytesField.get(null);
      Field valueIsCharsField =
          StringSerializer.class.getDeclaredField("STRING_VALUE_FIELD_IS_CHARS");
      valueIsCharsField.setAccessible(true);
      boolean STRING_VALUE_FIELD_IS_CHARS = (Boolean) valueIsCharsField.get(null);
      if (STRING_VALUE_FIELD_IS_BYTES) {
        StringSerializer.writeBytesString(buffer, value);
      } else if (STRING_VALUE_FIELD_IS_CHARS) {
        writeJDK8String(buffer, value);
      } else {
        return false;
      }
      return true;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  static void writeJDK8String(MemoryBuffer buffer, String value) {
    final char[] chars =
        (char[]) Platform.getObject(value, ReflectionUtils.getFieldOffset(String.class, "value"));
    int numBytes = MathUtils.doubleExact(value.length());
    buffer.writePrimitiveArrayWithSize(chars, Platform.CHAR_ARRAY_OFFSET, numBytes);
  }

  @Test
  public void testJavaStringSimple() {
    Fory fory = Fory.builder().withStringCompressed(true).requireClassRegistration(false).build();
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    StringSerializer serializer = new StringSerializer(fory);
    {
      String str = "str";
      serializer.writeString(buffer, str);
      assertEquals(str, serializer.readString(buffer));
      Assert.assertEquals(buffer.writerIndex(), buffer.readerIndex());
    }
    {
      String str = "你好, Fory";
      serializer.writeString(buffer, str);
      assertEquals(str, serializer.readString(buffer));
      Assert.assertEquals(buffer.writerIndex(), buffer.readerIndex());
    }
  }

  @Data
  public static class Simple {
    private String str;

    public Simple(String str) {
      this.str = str;
    }
  }

  /** Test for <a href="https://github.com/apache/fory/issues/1984">#1984</a> */
  @Test(dataProvider = "oneBoolOption")
  public void testJavaCompressedString(boolean b) {
    Fory fory =
        Fory.builder()
            .withStringCompressed(true)
            .withWriteNumUtf16BytesForUtf8Encoding(b)
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .build();
    Simple a =
        new Simple(
            "STG@ON DEMAND Solutions@GeoComputing Switch/ Hub@Digi Edgeport/216 – 16 port Serial Hub");
    serDeCheck(fory, a);
  }

  @Test
  public void testCompressedStringEstimatedWrongSize() {
    Fory fory =
        Fory.builder()
            .withStringCompressed(true)
            .withWriteNumUtf16BytesForUtf8Encoding(false)
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .build();
    // estimated 41 bytes, header needs 2 byte.
    // encoded utf8 is 31 bytes, took 1 byte for header.
    serDeCheck(fory, StringUtils.random(25, 47) + "你好");
    // estimated 31 bytes, header needs 1 byte.
    // encoded utf8 is 32 bytes, took 2 byte for header.
    serDeCheck(fory, "hello, world. 你好，世界。");
  }

  @Test(dataProvider = "twoBoolOptions")
  public void testJavaString(boolean stringCompress, boolean writeNumUtf16BytesForUtf8Encoding) {
    Fory fory =
        Fory.builder()
            .withStringCompressed(stringCompress)
            .withWriteNumUtf16BytesForUtf8Encoding(writeNumUtf16BytesForUtf8Encoding)
            .requireClassRegistration(false)
            .build();
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    StringSerializer serializer = new StringSerializer(fory);

    String longStr = new String(new char[50]).replace("\0", "abc");
    buffer.writerIndex(0);
    buffer.readerIndex(0);
    serializer.writeString(buffer, longStr);
    assertEquals(longStr, serializer.readString(buffer));

    serDe(fory, "你好, Fory" + StringUtils.random(64));
    serDe(fory, "你好, Fory" + StringUtils.random(64));
    serDe(fory, StringUtils.random(64));
    serDe(
        fory,
        new String[] {"你好, Fory" + StringUtils.random(64), "你好, Fory" + StringUtils.random(64)});
  }

  @Test(dataProvider = "twoBoolOptions")
  public void testJavaStringOffHeap(
      boolean stringCompress, boolean writeNumUtf16BytesForUtf8Encoding) {
    Fory fory =
        Fory.builder()
            .withStringCompressed(stringCompress)
            .withWriteNumUtf16BytesForUtf8Encoding(writeNumUtf16BytesForUtf8Encoding)
            .requireClassRegistration(false)
            .build();
    MemoryBuffer buffer = MemoryUtils.wrap(ByteBuffer.allocateDirect(1024));
    Object o1 = "你好, Fory" + StringUtils.random(64);
    Object o2 =
        new String[] {"你好, Fory" + StringUtils.random(64), "你好, Fory" + StringUtils.random(64)};
    fory.serialize(buffer, o1);
    fory.serialize(buffer, o2);
    assertEquals(fory.deserialize(buffer), o1);
    assertEquals(fory.deserialize(buffer), o2);
  }

  @Test
  public void testJavaStringMemoryModel() {
    BlockingQueue<Tuple2<String, byte[]>> dataQueue = new ArrayBlockingQueue<>(1024);
    ConcurrentLinkedQueue<Tuple2<String, String>> results = new ConcurrentLinkedQueue<>();
    Thread producer1 = new Thread(new DataProducer(dataQueue));
    Thread producer2 = new Thread(new DataProducer(dataQueue));
    Thread consumer1 = new Thread(new DataConsumer(dataQueue, results));
    Thread consumer2 = new Thread(new DataConsumer(dataQueue, results));
    Thread consumer3 = new Thread(new DataConsumer(dataQueue, results));
    Arrays.asList(producer1, producer2, consumer1, consumer2, consumer3).forEach(Thread::start);
    int count = DataProducer.numItems * 2;
    while (count > 0) {
      Tuple2<String, String> item = results.poll();
      if (item != null) {
        count--;
        assertEquals(item.f0, item.f1);
      }
    }
    Arrays.asList(producer1, producer2, consumer1, consumer2, consumer3).forEach(Thread::interrupt);
  }

  public static class DataProducer implements Runnable {
    static int numItems = 4 + 32 * 1024 * 2;
    private final Fory fory;
    private final BlockingQueue<Tuple2<String, byte[]>> dataQueue;

    public DataProducer(BlockingQueue<Tuple2<String, byte[]>> dataQueue) {
      this.dataQueue = dataQueue;
      this.fory =
          Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    }

    public void run() {
      try {
        dataQueue.put(Tuple2.of("", fory.serialize("")));
        dataQueue.put(Tuple2.of("a", fory.serialize("a")));
        dataQueue.put(Tuple2.of("ab", fory.serialize("ab")));
        dataQueue.put(Tuple2.of("abc", fory.serialize("abc")));
        for (int i = 0; i < 32; i++) {
          for (int j = 0; j < 1024; j++) {
            String str = StringUtils.random(j);
            dataQueue.put(Tuple2.of(str, fory.serialize(str)));
            str = String.valueOf(i);
            dataQueue.put(Tuple2.of(str, fory.serialize(str)));
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public static class DataConsumer implements Runnable {
    private final Fory fory;
    private final BlockingQueue<Tuple2<String, byte[]>> dataQueue;
    private final ConcurrentLinkedQueue<Tuple2<String, String>> results;

    public DataConsumer(
        BlockingQueue<Tuple2<String, byte[]>> dataQueue,
        ConcurrentLinkedQueue<Tuple2<String, String>> results) {
      this.fory =
          Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
      this.dataQueue = dataQueue;
      this.results = results;
    }

    @Override
    public void run() {
      try {
        while (!Thread.currentThread().isInterrupted()) {
          Tuple2<String, byte[]> dataItem = dataQueue.take();
          String newStr = (String) fory.deserialize(dataItem.f1);
          results.add(Tuple2.of(dataItem.f0, newStr));
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Test
  public void testCompressJava8String() {
    if (Platform.JAVA_VERSION != 8) {
      throw new SkipException("Java 8 only");
    }
    Fory fory = Fory.builder().withStringCompressed(true).requireClassRegistration(false).build();
    StringSerializer stringSerializer =
        (StringSerializer) fory.getClassResolver().getSerializer(String.class);

    String utf16Str = "你好, Fory" + StringUtils.random(64);
    char[] utf16StrChars = utf16Str.toCharArray();
    for (MemoryBuffer buffer :
        new MemoryBuffer[] {
          MemoryUtils.buffer(512), MemoryUtils.wrap(ByteBuffer.allocateDirect(512)),
        }) {
      stringSerializer.writeString(buffer, utf16Str);
      assertEquals(stringSerializer.readString(buffer), utf16Str);
      assertEquals(buffer.writerIndex(), buffer.readerIndex());

      String latinStr = StringUtils.random(utf16StrChars.length, 0);
      stringSerializer.writeString(buffer, latinStr);
      assertEquals(stringSerializer.readString(buffer), latinStr);
      assertEquals(buffer.writerIndex(), buffer.readerIndex());
    }
  }

  @Test(dataProvider = "oneBoolOption")
  public void testReadUtf8String(boolean writeNumUtf16BytesForUtf8Encoding) {
    Fory fory =
        Fory.builder()
            .withStringCompressed(true)
            .withWriteNumUtf16BytesForUtf8Encoding(writeNumUtf16BytesForUtf8Encoding)
            .requireClassRegistration(false)
            .build();
    for (MemoryBuffer buffer :
        new MemoryBuffer[] {
          MemoryUtils.buffer(32), MemoryUtils.wrap(ByteBuffer.allocateDirect(2048))
        }) {
      StringSerializer serializer = new StringSerializer(fory);
      serializer.write(buffer, "abc你好");
      assertEquals(serializer.read(buffer), "abc你好");
      byte[] bytes = "abc你好".getBytes(StandardCharsets.UTF_8);
      byte UTF8 = 2;
      if (writeNumUtf16BytesForUtf8Encoding) {
        buffer.writeVarUint64(((long) "abc你好".length() << 1) << 2 | UTF8);
        buffer.writeInt32(bytes.length);
      } else {
        buffer.writeVarUint64((((long) bytes.length) << 2 | UTF8));
      }
      buffer.writeBytes(bytes);
      assertEquals(serializer.read(buffer), "abc你好");
      assertEquals(buffer.readerIndex(), buffer.writerIndex());
    }
  }

  /**
   * Comprehensive tests for readBytesUTF8ForXlang method. Tests the optimized single-pass UTF-8 to
   * Latin1/UTF-16 conversion.
   */
  @Test
  public void testReadBytesUTF8ForXlang_PureAscii() {
    Fory fory =
        Fory.builder()
            .withStringCompressed(true)
            .withLanguage(Language.XLANG)
            .requireClassRegistration(false)
            .build();

    // Test various ASCII string lengths to verify vectorized path (8 bytes at a time)
    String[] testStrings = {
      "", // Empty
      "a", // Single char
      "hello", // 5 chars
      "helloabc", // Exactly 8 chars (1 vectorized chunk)
      "hello world!", // 12 chars
      "hello world, this is a test", // 28 chars (multiple vectorized chunks)
      new String(new char[100]).replace("\0", "x") // Long ASCII string
    };

    for (MemoryBuffer buffer :
        new MemoryBuffer[] {
          MemoryUtils.buffer(1024), MemoryUtils.wrap(ByteBuffer.allocateDirect(1024))
        }) {
      for (String testStr : testStrings) {
        buffer.writerIndex(0);
        buffer.readerIndex(0);

        StringSerializer serializer = new StringSerializer(fory);
        serializer.write(buffer, testStr);
        String result = serializer.read(buffer);

        assertEquals(result, testStr, "Failed for ASCII string: " + testStr);
        assertEquals(buffer.readerIndex(), buffer.writerIndex());
      }
    }
  }

  @Test
  public void testReadBytesUTF8ForXlang_Latin1() {
    Fory fory =
        Fory.builder()
            .withStringCompressed(true)
            .withLanguage(Language.XLANG)
            .requireClassRegistration(false)
            .build();

    // Test Latin1 characters (0x80-0xFF range)
    // These are 2-byte UTF-8 sequences but fit in Latin1 encoding
    String[] testStrings = {
      "café", // Contains é (0xE9)
      "résumé", // Multiple accented chars
      "Ñoño", // Spanish characters
      "Größe", // German umlaut
      "\u00A0\u00FF", // Non-breaking space and ÿ
      "hello " + "\u00E9" + " world", // Mixed ASCII and Latin1
      new String(new char[50]).replace("\0", "\u00E9") // Repeated Latin1
    };

    for (MemoryBuffer buffer :
        new MemoryBuffer[] {
          MemoryUtils.buffer(1024), MemoryUtils.wrap(ByteBuffer.allocateDirect(1024))
        }) {
      for (String testStr : testStrings) {
        buffer.writerIndex(0);
        buffer.readerIndex(0);

        StringSerializer serializer = new StringSerializer(fory);
        serializer.write(buffer, testStr);
        String result = serializer.read(buffer);

        assertEquals(result, testStr, "Failed for Latin1 string: " + testStr);
        assertEquals(buffer.readerIndex(), buffer.writerIndex());
      }
    }
  }

  @Test
  public void testReadBytesUTF8ForXlang_Utf16() {
    Fory fory =
        Fory.builder()
            .withStringCompressed(true)
            .withLanguage(Language.XLANG)
            .requireClassRegistration(false)
            .build();

    // Test UTF-16 characters (beyond Latin1 range)
    String[] testStrings = {
      "你好", // Chinese characters
      "Hello 世界", // Mixed ASCII and Chinese
      "こんにちは", // Japanese Hiragana
      "안녕하세요", // Korean Hangul
      "Привет", // Russian Cyrillic
      "🎉🎊", // Emoji (surrogate pairs)
      "test " + "\uD83D\uDE00" + " emoji", // Grinning face emoji
      "\u4E00\u4E01\u4E03", // CJK ideographs
      new String(new char[30]).replace("\0", "你") // Repeated UTF-16
    };

    for (MemoryBuffer buffer :
        new MemoryBuffer[] {
          MemoryUtils.buffer(2048), MemoryUtils.wrap(ByteBuffer.allocateDirect(2048))
        }) {
      for (String testStr : testStrings) {
        buffer.writerIndex(0);
        buffer.readerIndex(0);

        StringSerializer serializer = new StringSerializer(fory);
        serializer.write(buffer, testStr);
        String result = serializer.read(buffer);

        assertEquals(result, testStr, "Failed for UTF-16 string: " + testStr);
        assertEquals(buffer.readerIndex(), buffer.writerIndex());
      }
    }
  }

  @Test
  public void testReadBytesUTF8ForXlang_MixedContent() {
    Fory fory =
        Fory.builder()
            .withStringCompressed(true)
            .withLanguage(Language.XLANG)
            .requireClassRegistration(false)
            .build();

    // Test mixed content that transitions between ASCII, Latin1, and UTF-16
    String[] testStrings = {
      "hello café 你好", // ASCII + Latin1 + UTF-16
      "test\u00E9test你test", // Alternating encodings
      "abc" + "\u00FF" + "你好" + "xyz", // All three types
      StringUtils.random(20) + "你好" + StringUtils.random(20), // Random ASCII with UTF-16
      "🎉hello世界café🎊", // Complex mix with emoji
    };

    for (MemoryBuffer buffer :
        new MemoryBuffer[] {
          MemoryUtils.buffer(2048), MemoryUtils.wrap(ByteBuffer.allocateDirect(2048))
        }) {
      for (String testStr : testStrings) {
        buffer.writerIndex(0);
        buffer.readerIndex(0);

        StringSerializer serializer = new StringSerializer(fory);
        serializer.write(buffer, testStr);
        String result = serializer.read(buffer);

        assertEquals(result, testStr, "Failed for mixed content string: " + testStr);
        assertEquals(buffer.readerIndex(), buffer.writerIndex());
      }
    }
  }

  @Test
  public void testReadBytesUTF8ForXlang_EdgeCases() {
    Fory fory =
        Fory.builder()
            .withStringCompressed(true)
            .withLanguage(Language.XLANG)
            .requireClassRegistration(false)
            .build();

    // Test edge cases
    String[] testStrings = {
      "", // Empty string
      " ", // Single space
      "\u0001", // Control character
      "\u007F", // DEL character
      "\u0080", // First Latin1 extended char
      "\u00FF", // Last Latin1 char
      "\u0100", // First char beyond Latin1
      new String(new char[7]).replace("\0", "a"), // 7 chars (just under vectorized chunk)
      new String(new char[9]).replace("\0", "a"), // 9 chars (just over vectorized chunk)
      new String(new char[16]).replace("\0", "a"), // Exactly 2 vectorized chunks
      new String(new char[17]).replace("\0", "a"), // 2 chunks + 1 byte
    };

    for (MemoryBuffer buffer :
        new MemoryBuffer[] {
          MemoryUtils.buffer(1024), MemoryUtils.wrap(ByteBuffer.allocateDirect(1024))
        }) {
      for (String testStr : testStrings) {
        buffer.writerIndex(0);
        buffer.readerIndex(0);

        StringSerializer serializer = new StringSerializer(fory);
        serializer.write(buffer, testStr);
        String result = serializer.read(buffer);

        assertEquals(result, testStr, "Failed for edge case string: " + testStr);
        assertEquals(buffer.readerIndex(), buffer.writerIndex());
      }
    }
  }

  @Test
  public void testReadBytesUTF8ForXlang_SurrogatePairs() {
    Fory fory =
        Fory.builder()
            .withStringCompressed(true)
            .withLanguage(Language.XLANG)
            .requireClassRegistration(false)
            .build();

    // Test surrogate pairs (4-byte UTF-8 sequences)
    String[] testStrings = {
      "😀", // Grinning face
      "😀😁😂", // Multiple emoji
      "hello😀world", // Emoji in middle
      "test🎉test🎊test", // Multiple emoji separated
      "\uD83D\uDC4D", // Thumbs up (explicit surrogate pair)
      "\uD83D\uDE00\uD83D\uDE01", // Multiple explicit pairs
    };

    for (MemoryBuffer buffer :
        new MemoryBuffer[] {
          MemoryUtils.buffer(2048), MemoryUtils.wrap(ByteBuffer.allocateDirect(2048))
        }) {
      for (String testStr : testStrings) {
        buffer.writerIndex(0);
        buffer.readerIndex(0);

        StringSerializer serializer = new StringSerializer(fory);
        serializer.write(buffer, testStr);
        String result = serializer.read(buffer);

        assertEquals(result, testStr, "Failed for surrogate pair string: " + testStr);
        assertEquals(buffer.readerIndex(), buffer.writerIndex());
      }
    }
  }

  @Test
  public void testReadBytesUTF8ForXlang_LargeStrings() {
    Fory fory =
        Fory.builder()
            .withStringCompressed(true)
            .withLanguage(Language.XLANG)
            .requireClassRegistration(false)
            .build();

    // Test large strings to verify buffer reuse and no overflow
    String[] testStrings = {
      new String(new char[1000]).replace("\0", "a"), // Large ASCII
      new String(new char[1000]).replace("\0", "\u00E9"), // Large Latin1
      new String(new char[500]).replace("\0", "你"), // Large UTF-16
      StringUtils.random(500) + new String(new char[500]).replace("\0", "你"), // Large mixed
    };

    for (MemoryBuffer buffer :
        new MemoryBuffer[] {
          MemoryUtils.buffer(8192), MemoryUtils.wrap(ByteBuffer.allocateDirect(8192))
        }) {
      for (String testStr : testStrings) {
        buffer.writerIndex(0);
        buffer.readerIndex(0);

        StringSerializer serializer = new StringSerializer(fory);
        serializer.write(buffer, testStr);
        String result = serializer.read(buffer);

        assertEquals(result, testStr, "Failed for large string");
        assertEquals(buffer.readerIndex(), buffer.writerIndex());
      }
    }
  }

  @Test
  public void testReadBytesUTF8ForXlang_VectorizedPathVerification() {
    Fory fory =
        Fory.builder()
            .withStringCompressed(true)
            .withLanguage(Language.XLANG)
            .requireClassRegistration(false)
            .build();

    // Specifically test strings that exercise vectorized paths
    // Multiples of 8 to ensure vectorized loop is used
    for (int length : new int[] {8, 16, 24, 32, 64, 128}) {
      String asciiStr = new String(new char[length]).replace("\0", "x");

      for (MemoryBuffer buffer :
          new MemoryBuffer[] {
            MemoryUtils.buffer(1024), MemoryUtils.wrap(ByteBuffer.allocateDirect(1024))
          }) {
        buffer.writerIndex(0);
        buffer.readerIndex(0);

        StringSerializer serializer = new StringSerializer(fory);
        serializer.write(buffer, asciiStr);
        String result = serializer.read(buffer);

        assertEquals(result, asciiStr, "Failed for vectorized ASCII string of length " + length);
        assertEquals(buffer.readerIndex(), buffer.writerIndex());
      }
    }
  }

  @Test
  public void testReadBytesUTF8ForXlang_BufferReuseCorrectness() {
    Fory fory =
        Fory.builder()
            .withStringCompressed(true)
            .withLanguage(Language.XLANG)
            .requireClassRegistration(false)
            .build();

    MemoryBuffer buffer = MemoryUtils.buffer(2048);
    StringSerializer serializer = new StringSerializer(fory);

    // Test multiple consecutive reads/writes to verify buffer reuse doesn't cause issues
    String[] testSequence = {
      "short",
      new String(new char[1000]).replace("\0", "a"), // Trigger buffer growth
      "short again",
      "café",
      "你好",
      new String(new char[500]).replace("\0", "\u00E9"),
      "final test"
    };

    for (String testStr : testSequence) {
      buffer.writerIndex(0);
      buffer.readerIndex(0);

      serializer.write(buffer, testStr);
      String result = serializer.read(buffer);

      assertEquals(result, testStr, "Failed during buffer reuse test for: " + testStr);
      assertEquals(buffer.readerIndex(), buffer.writerIndex());
    }
  }

  @Test
  public void disabled_testReadBytesUTF8ForXlang_DirectRawBytes() {
    if (Platform.JAVA_VERSION <= 8) {
      // readBytesUTF8ForXlang will be invoked only in java9+
      return;
    }
    Fory fory = Fory.builder().withLanguage(Language.XLANG).requireClassRegistration(false).build();

    // Direct test with raw UTF-8 bytes - bypasses full serialization
    // This tests the method directly with known UTF-8 byte sequences

    // Test 1: Pure ASCII "hello"
    byte[] asciiBytes = "hello".getBytes(StandardCharsets.UTF_8);
    for (MemoryBuffer buffer :
        new MemoryBuffer[] {
          MemoryUtils.buffer(1024), MemoryUtils.wrap(ByteBuffer.allocateDirect(1024))
        }) {
      // Create fresh serializer for each test to avoid buffer reuse issues
      StringSerializer serializer = new StringSerializer(fory);
      buffer.writerIndex(0);
      buffer.readerIndex(0);
      buffer.writeBytes(asciiBytes);
      String result = serializer.readBytesUTF8ForXlang(buffer, asciiBytes.length);
      assertEquals(result, "hello", "Direct ASCII test failed");
    }

    // Test 2: Latin1 "café" (UTF-8: 63 61 66 C3 A9)
    byte[] latin1Bytes = "café".getBytes(StandardCharsets.UTF_8);
    for (MemoryBuffer buffer :
        new MemoryBuffer[] {
          MemoryUtils.buffer(1024), MemoryUtils.wrap(ByteBuffer.allocateDirect(1024))
        }) {
      StringSerializer serializer = new StringSerializer(fory);
      buffer.writerIndex(0);
      buffer.readerIndex(0);
      buffer.writeBytes(latin1Bytes);
      String result = serializer.readBytesUTF8ForXlang(buffer, latin1Bytes.length);
      assertEquals(result, "café", "Direct Latin1 test failed");
    }

    // Test 3: UTF-16 "你好" (3-byte UTF-8 sequences)
    byte[] utf16Bytes = "你好".getBytes(StandardCharsets.UTF_8);
    for (MemoryBuffer buffer :
        new MemoryBuffer[] {
          MemoryUtils.buffer(1024), MemoryUtils.wrap(ByteBuffer.allocateDirect(1024))
        }) {
      StringSerializer serializer = new StringSerializer(fory);
      buffer.writerIndex(0);
      buffer.readerIndex(0);
      buffer.writeBytes(utf16Bytes);
      String result = serializer.readBytesUTF8ForXlang(buffer, utf16Bytes.length);
      assertEquals(result, "你好", "Direct UTF-16 test failed");
    }

    // Test 4: Emoji with surrogate pairs "😀" (4-byte UTF-8: F0 9F 98 80)
    byte[] emojiBytes = "😀".getBytes(StandardCharsets.UTF_8);
    for (MemoryBuffer buffer :
        new MemoryBuffer[] {
          MemoryUtils.buffer(1024), MemoryUtils.wrap(ByteBuffer.allocateDirect(1024))
        }) {
      StringSerializer serializer = new StringSerializer(fory);
      buffer.writerIndex(0);
      buffer.readerIndex(0);
      buffer.writeBytes(emojiBytes);
      String result = serializer.readBytesUTF8ForXlang(buffer, emojiBytes.length);
      assertEquals(result, "😀", "Direct emoji test failed");
    }

    // Test 5: Mixed content - simpler case
    byte[] mixedBytes = "abc你好".getBytes(StandardCharsets.UTF_8);
    for (MemoryBuffer buffer :
        new MemoryBuffer[] {
          MemoryUtils.buffer(1024), MemoryUtils.wrap(ByteBuffer.allocateDirect(1024))
        }) {
      StringSerializer serializer = new StringSerializer(fory);
      buffer.writerIndex(0);
      buffer.readerIndex(0);
      buffer.writeBytes(mixedBytes);
      String result = serializer.readBytesUTF8ForXlang(buffer, mixedBytes.length);
      assertEquals(result, "abc你好", "Direct mixed content test failed");
    }
  }

  @Test
  public void testReadBytesUTF8ForXlang_SpecialCharacters() {
    Fory fory =
        Fory.builder()
            .withStringCompressed(true)
            .withLanguage(Language.XLANG)
            .requireClassRegistration(false)
            .build();

    // Test various special characters and Unicode ranges
    String[] testStrings = {
      "\n\r\t", // Control characters
      "\u0000", // Null character
      "line1\nline2\rline3\tline4", // Mixed with text
      "©®™", // Copyright, registered, trademark
      "€£¥", // Currency symbols
      "αβγδ", // Greek letters
      "←↑→↓", // Arrows
      "♠♣♥♦", // Card suits
      "½⅓¼", // Fractions
    };

    for (MemoryBuffer buffer :
        new MemoryBuffer[] {
          MemoryUtils.buffer(1024), MemoryUtils.wrap(ByteBuffer.allocateDirect(1024))
        }) {
      for (String testStr : testStrings) {
        buffer.writerIndex(0);
        buffer.readerIndex(0);

        StringSerializer serializer = new StringSerializer(fory);
        serializer.write(buffer, testStr);
        String result = serializer.read(buffer);

        assertEquals(result, testStr, "Failed for special characters: " + testStr);
        assertEquals(buffer.readerIndex(), buffer.writerIndex());
      }
    }
  }
}
