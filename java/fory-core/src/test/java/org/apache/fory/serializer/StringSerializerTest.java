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
import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.type.Types;
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
    buffer.writeArrayWithSize(chars, 0, value.length(), Types.JavaArray.CHAR);
  }

  @Test
  public void testJavaStringSimple() {
    Fory fory = Fory.builder().withStringCompressed(true).requireClassRegistration(false).build();
    MemoryBuffer buffer = MemoryBuffer.buffer(32);
    StringSerializer serializer = new StringSerializer(fory);
    {
      String str = "str";
      serializer.writeJavaString(buffer, str);
      assertEquals(str, serializer.readJavaString(buffer));
      Assert.assertEquals(buffer.writerIndex(), buffer.readerIndex());
    }
    {
      String str = "你好, Fory";
      serializer.writeJavaString(buffer, str);
      assertEquals(str, serializer.readJavaString(buffer));
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
    MemoryBuffer buffer = MemoryBuffer.buffer(32);
    StringSerializer serializer = new StringSerializer(fory);

    String longStr = new String(new char[50]).replace("\0", "abc");
    buffer.writerIndex(0);
    buffer.readerIndex(0);
    serializer.writeJavaString(buffer, longStr);
    assertEquals(longStr, serializer.readJavaString(buffer));

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
    MemoryBuffer buffer = MemoryBuffer.bufferDirect(1024);
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
          MemoryBuffer.buffer(512), MemoryBuffer.bufferDirect(512),
        }) {
      stringSerializer.writeJavaString(buffer, utf16Str);
      assertEquals(stringSerializer.readJavaString(buffer), utf16Str);
      assertEquals(buffer.writerIndex(), buffer.readerIndex());

      String latinStr = StringUtils.random(utf16StrChars.length, 0);
      stringSerializer.writeJavaString(buffer, latinStr);
      assertEquals(stringSerializer.readJavaString(buffer), latinStr);
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
          MemoryBuffer.buffer(32), MemoryBuffer.bufferDirect(2048),
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
}
