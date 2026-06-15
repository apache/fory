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

package org.apache.fory.memory;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.apache.fory.TestUtils;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.JdkVersion;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

public class MemoryBufferTest {

  @Test
  public void testBufferPut() {
    MemoryBuffer buffer = MemoryUtils.buffer(16);
    buffer.putByte(0, (byte) 10);
    assertEquals(buffer.getByte(0), (byte) 10);
    buffer.putChar(0, 'a');
    assertEquals(buffer.getChar(0), 'a');
    buffer.putInt16(0, (short) 10);
    assertEquals(buffer.getInt16(0), (short) 10);
    buffer.putInt32(0, Integer.MAX_VALUE);
    assertEquals(buffer.getInt32(0), Integer.MAX_VALUE);
    buffer.putInt64(0, Long.MAX_VALUE);
    assertEquals(buffer.getInt64(0), Long.MAX_VALUE);
    buffer.putFloat32(0, Float.MAX_VALUE);
    assertEquals(buffer.getFloat32(0), Float.MAX_VALUE);
    buffer.putFloat64(0, Double.MAX_VALUE);
    assertEquals(buffer.getFloat64(0), Double.MAX_VALUE);
  }

  @Test
  public void testBufferWrite() {
    MemoryBuffer buffer = MemoryUtils.buffer(8);
    buffer.writeBoolean(true);
    buffer.writeByte(Byte.MIN_VALUE);
    buffer.writeChar('a');
    buffer.writeInt16(Short.MAX_VALUE);
    buffer.writeInt32(Integer.MAX_VALUE);
    buffer.writeInt64(Long.MAX_VALUE);
    buffer.writeFloat32(Float.MAX_VALUE);
    buffer.writeFloat64(Double.MAX_VALUE);
    byte[] bytes = new byte[] {1, 2, 3, 4};
    buffer.writeBytes(bytes);

    assertTrue(buffer.readBoolean());
    assertEquals(buffer.readByte(), Byte.MIN_VALUE);
    assertEquals(buffer.readChar(), 'a');
    assertEquals(buffer.readInt16(), Short.MAX_VALUE);
    assertEquals(buffer.readInt32(), Integer.MAX_VALUE);
    assertEquals(buffer.readInt64(), Long.MAX_VALUE);
    assertEquals(buffer.readFloat32(), Float.MAX_VALUE, 0.1);
    assertEquals(buffer.readFloat64(), Double.MAX_VALUE, 0.1);
    assertEquals(buffer.readBytes(bytes.length), bytes);
    assertEquals(buffer.readerIndex(), buffer.writerIndex());
  }

  @Test
  public void testReadCharsAndSizeRequiresBodyBeforeAlloc() {
    MemoryBuffer buffer = MemoryUtils.buffer(8);
    buffer.writeVarUInt32(1 << 20);
    assertThrows(IndexOutOfBoundsException.class, buffer::readCharsAndSize);
  }

  @Test
  public void testDirectBufferRejectsHeap() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MemoryBuffer.fromDirectByteBuffer(ByteBuffer.allocate(8), 8, null));
  }

  @Test
  public void testDirectByteBufferNoNioOpen() throws Exception {
    ProcessBuilder processBuilder =
        new ProcessBuilder(TestUtils.javaCommand(DirectByteBufferNoNioOpenProbe.class))
            .redirectErrorStream(true);
    for (String commandPart : processBuilder.command()) {
      assertTrue(!commandPart.contains("java.base/java.nio"), processBuilder.command().toString());
    }
    processBuilder.environment().remove("JDK_JAVA_OPTIONS");
    processBuilder.environment().remove("JAVA_TOOL_OPTIONS");
    processBuilder.environment().remove("_JAVA_OPTIONS");
    Process process = processBuilder.start();
    String output = readFully(process.getInputStream());
    assertEquals(process.waitFor(), 0, output);
  }

  @Test
  public void testAndroidHeapMemoryBufferPaths() throws Exception {
    String javaBin =
        System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    Process process =
        new ProcessBuilder(
                javaBin,
                "-cp",
                System.getProperty("java.class.path"),
                AndroidHeapMemoryBufferProbe.class.getName())
            .redirectErrorStream(true)
            .start();
    String output = readFully(process.getInputStream());
    assertEquals(process.waitFor(), 0, output);
  }

  private static String readFully(InputStream inputStream) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int read;
    while ((read = inputStream.read(buffer)) != -1) {
      outputStream.write(buffer, 0, read);
    }
    return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
  }

  public static final class AndroidHeapMemoryBufferProbe {
    public static void main(String[] args) {
      System.setProperty("java.vm.name", "Dalvik");
      System.setProperty("java.runtime.name", "Android Runtime");
      if (!AndroidSupport.IS_ANDROID) {
        throw new AssertionError("AndroidSupport should detect Dalvik runtime");
      }

      MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(1);
      buffer.writeBoolean(true);
      buffer.writeByte(Byte.MIN_VALUE);
      buffer.writeChar('a');
      buffer.writeInt16(Short.MAX_VALUE);
      buffer.writeInt32(Integer.MAX_VALUE);
      buffer.writeInt64(Long.MAX_VALUE);
      buffer.writeFloat32(Float.MAX_VALUE);
      buffer.writeFloat64(Double.MAX_VALUE);
      check(buffer.readBoolean(), true);
      check(buffer.readByte(), Byte.MIN_VALUE);
      check(buffer.readChar(), 'a');
      check(buffer.readInt16(), Short.MAX_VALUE);
      check(buffer.readInt32(), Integer.MAX_VALUE);
      check(buffer.readInt64(), Long.MAX_VALUE);
      check(buffer.readFloat32(), Float.MAX_VALUE);
      check(buffer.readFloat64(), Double.MAX_VALUE);

      buffer = MemoryBuffer.newHeapBuffer(32);
      buffer.putByte(0, (byte) 7);
      buffer.putInt16(1, (short) 8);
      buffer.putInt32(3, 9);
      buffer.putInt64(7, 10L);
      check(buffer.getByte(0), (byte) 7);
      check(buffer.getInt16(1), (short) 8);
      check(buffer.getInt32(3), 9);
      check(buffer.getInt64(7), 10L);

      MemoryBuffer slice = buffer.slice(3, 4);
      slice.putInt32(0, 11);
      check(buffer.getInt32(3), 11);

      buffer = MemoryBuffer.newHeapBuffer(1);
      check(buffer.writeVarUInt32(-1), 5);
      check(buffer.writeVarInt32(Integer.MIN_VALUE), 5);
      check(buffer.writeVarUInt64(-1), 9);
      check(buffer.writeVarInt64(Long.MIN_VALUE), 9);
      check(buffer.writeTaggedInt64(Long.MAX_VALUE), 9);
      check(buffer.writeTaggedUInt64(Long.MAX_VALUE), 9);
      check(buffer.readVarUInt32(), -1);
      check(buffer.readVarInt32(), Integer.MIN_VALUE);
      check(buffer.readVarUInt64(), -1L);
      check(buffer.readVarInt64(), Long.MIN_VALUE);
      check(buffer.readTaggedInt64(), Long.MAX_VALUE);
      check(buffer.readTaggedUInt64(), Long.MAX_VALUE);

      buffer = MemoryBuffer.newHeapBuffer(1);
      check(buffer.writeVarUInt32Aligned(Integer.MAX_VALUE), 8);
      check(buffer.readAlignedVarUInt32(), Integer.MAX_VALUE);

      buffer = MemoryBuffer.newHeapBuffer(1);
      buffer.writeVarUInt32(3);
      buffer.writeBytes(new byte[] {1, 2, 3});
      check(buffer.readBinarySize(), 3);
      check(buffer.readBytes(3), new byte[] {1, 2, 3});

      buffer = MemoryBuffer.newHeapBuffer(1);
      buffer.writeInt64(0x0102030405060708L);
      check(buffer.readBytesAsInt64(3), 0x060708L);

      MemoryBuffer source = MemoryBuffer.fromByteArray(new byte[] {1, 2, 3, 4});
      MemoryBuffer target = MemoryBuffer.newHeapBuffer(4);
      source.copyTo(0, target, 0, 4);
      check(target.equalTo(new byte[] {1, 2, 3, 4}, 0, 0, 4), true);
      check(source.equalTo(target, 0, 0, 4), true);
      check(source.getBytes(1, 2), new byte[] {2, 3});

      byte[] bytes = new byte[4];
      source.copyToByteArray(0, bytes, 0, 4);
      check(bytes, new byte[] {1, 2, 3, 4});
      target.copyFromByteArray(0, new byte[] {4, 3, 2, 1}, 0, 4);
      check(target.getBytes(0, 4), new byte[] {4, 3, 2, 1});
    }

    private static void check(boolean actual, boolean expected) {
      if (actual != expected) {
        throw new AssertionError("Expected " + expected + " but got " + actual);
      }
    }

    private static void check(byte actual, byte expected) {
      if (actual != expected) {
        throw new AssertionError("Expected " + expected + " but got " + actual);
      }
    }

    private static void check(char actual, char expected) {
      if (actual != expected) {
        throw new AssertionError("Expected " + expected + " but got " + actual);
      }
    }

    private static void check(short actual, short expected) {
      if (actual != expected) {
        throw new AssertionError("Expected " + expected + " but got " + actual);
      }
    }

    private static void check(int actual, int expected) {
      if (actual != expected) {
        throw new AssertionError("Expected " + expected + " but got " + actual);
      }
    }

    private static void check(long actual, long expected) {
      if (actual != expected) {
        throw new AssertionError("Expected " + expected + " but got " + actual);
      }
    }

    private static void check(float actual, float expected) {
      if (Float.floatToRawIntBits(actual) != Float.floatToRawIntBits(expected)) {
        throw new AssertionError("Expected " + expected + " but got " + actual);
      }
    }

    private static void check(double actual, double expected) {
      if (Double.doubleToRawLongBits(actual) != Double.doubleToRawLongBits(expected)) {
        throw new AssertionError("Expected " + expected + " but got " + actual);
      }
    }

    private static void check(byte[] actual, byte[] expected) {
      if (!java.util.Arrays.equals(actual, expected)) {
        throw new AssertionError(
            "Expected "
                + java.util.Arrays.toString(expected)
                + " but got "
                + java.util.Arrays.toString(actual));
      }
    }
  }

  public static final class DirectByteBufferNoNioOpenProbe {
    public static void main(String[] args) {
      if (JdkVersion.MAJOR_VERSION >= 25) {
        for (String inputArg :
            java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
          if (inputArg.contains("java.base/java.nio")) {
            throw new AssertionError("Unexpected java.nio open: " + inputArg);
          }
        }
        if (isNioOpenToProbe()) {
          throw new AssertionError("java.base/java.nio must not be open to this test probe");
        }
      }
      MemoryBuffer buffer = MemoryUtils.wrap(ByteBuffer.allocateDirect(128));
      buffer.writeInt32(17);
      buffer.writeInt64(19);
      checkEqual(buffer.readInt32(), 17);
      checkEqual(buffer.readInt64(), 19L);

      int[] ints = new int[] {1, 2, 3, 4};
      buffer.writerIndex(0);
      buffer.copyFromIntArray(0, ints, 0, ints.length * Integer.BYTES);
      int[] readInts = new int[ints.length];
      buffer.copyToIntArray(0, readInts, 0, readInts.length * Integer.BYTES);
      if (!java.util.Arrays.equals(readInts, ints)) {
        throw new AssertionError(
            "Expected "
                + java.util.Arrays.toString(ints)
                + " but got "
                + java.util.Arrays.toString(readInts));
      }
    }

    private static void checkEqual(int actual, int expected) {
      if (actual != expected) {
        throw new AssertionError("Expected " + expected + " but got " + actual);
      }
    }

    private static void checkEqual(long actual, long expected) {
      if (actual != expected) {
        throw new AssertionError("Expected " + expected + " but got " + actual);
      }
    }

    private static boolean isNioOpenToProbe() {
      try {
        Class<?> moduleType = Class.forName("java.lang.Module");
        java.lang.reflect.Method getModule = Class.class.getMethod("getModule");
        Object byteBufferModule = getModule.invoke(ByteBuffer.class);
        Object probeModule = getModule.invoke(DirectByteBufferNoNioOpenProbe.class);
        java.lang.reflect.Method isOpen = moduleType.getMethod("isOpen", String.class, moduleType);
        return (Boolean) isOpen.invoke(byteBufferModule, "java.nio", probeModule);
      } catch (ClassNotFoundException | NoSuchMethodException e) {
        return false;
      } catch (ReflectiveOperationException e) {
        throw new AssertionError("Failed to inspect java.nio module opens", e);
      }
    }
  }

  @Test
  public void testBufferUnsafeWrite() {
    {
      MemoryBuffer buffer = MemoryUtils.buffer(1024);
      int index = 0;
      buffer.putByte(index, Byte.MIN_VALUE);
      index += 1;
      buffer.putInt16(index, Short.MAX_VALUE);
      index += 2;
      buffer.putInt32(index, Integer.MIN_VALUE);
      index += 4;
      buffer.putInt64(index, Long.MAX_VALUE);
      index += 8;
      buffer.putFloat64(index, -1);
      index += 8;
      buffer.putFloat32(index, -1);
      assertEquals(buffer.getFloat32(index), -1);
      index -= 8;
      assertEquals(buffer.getFloat64(index), -1);
      index -= 8;
      assertEquals(buffer._unsafeGetInt64(index), Long.MAX_VALUE);
      index -= 4;
      assertEquals(buffer.getInt32(index), Integer.MIN_VALUE);
      index -= 2;
      assertEquals(buffer.getInt16(index), Short.MAX_VALUE);
      index -= 1;
      assertEquals(buffer.getByte(index), Byte.MIN_VALUE);
    }
  }

  @Test
  public void testWrapBuffer() {
    {
      byte[] bytes = new byte[8];
      int offset = 2;
      bytes[offset] = 1;
      MemoryBuffer buffer = MemoryUtils.wrap(bytes, offset, 2);
      assertEquals(buffer.readByte(), bytes[offset]);
    }
    {
      byte[] bytes = new byte[8];
      int offset = 2;
      MemoryBuffer buffer = MemoryUtils.wrap(ByteBuffer.wrap(bytes, offset, 2));
      assertEquals(buffer.readByte(), bytes[offset]);
    }
    {
      ByteBuffer direct = ByteBuffer.allocateDirect(8);
      int offset = 2;
      direct.put(offset, (byte) 1);
      direct.position(offset);
      MemoryBuffer buffer = MemoryUtils.wrap(direct);
      assertEquals(buffer.readByte(), direct.get(offset));
    }
  }

  @Test
  public void testJdk25DirectBufferNoRawAddress() {
    if (JdkVersion.MAJOR_VERSION < 25) {
      throw new SkipException("Skip on jdk" + JdkVersion.MAJOR_VERSION);
    }
    MemoryBuffer buffer = MemoryUtils.wrap(ByteBuffer.allocateDirect(8));
    buffer.writeByte((byte) 1);
    assertThrows(UnsupportedOperationException.class, () -> buffer.getUnsafeReaderAddress());
    assertThrows(UnsupportedOperationException.class, () -> buffer._unsafeWriterAddress());
  }

  @Test
  public void testSliceAsByteBuffer() {
    byte[] data = new byte[10];
    new Random().nextBytes(data);
    {
      MemoryBuffer buffer = MemoryUtils.wrap(data, 5, 5);
      assertEquals(buffer.sliceAsByteBuffer(), ByteBuffer.wrap(data, 5, 5));
    }
    {
      ByteBuffer direct = ByteBuffer.allocateDirect(10);
      direct.put(data);
      direct.flip();
      direct.position(5);
      MemoryBuffer buffer = MemoryUtils.wrap(direct);
      assertEquals(buffer.sliceAsByteBuffer(), direct);
    }
  }

  @Test
  public void testSliceAndGetRemainingBytes() {
    MemoryBuffer buf = MemoryBuffer.newHeapBuffer(16);
    for (int i = 0; i < 16; i++) {
      buf.writeByte(i);
    }
    byte[] sliceRemaining = buf.slice(4, 8).getRemainingBytes();
    byte[] expected = buf.getBytes(4, 8);
    assertEquals(sliceRemaining, expected);
  }

  @Test
  public void testEqualTo() {
    MemoryBuffer buf1 = MemoryUtils.buffer(16);
    MemoryBuffer buf2 = MemoryUtils.buffer(16);
    buf1.putInt64(0, 10);
    buf2.putInt64(0, 10);
    buf1.putByte(9, (byte) 1);
    buf2.putByte(9, (byte) 1);
    Assert.assertTrue(buf1.equalTo(buf2, 0, 0, buf1.size()));
    Assert.assertTrue(buf1.equalTo(buf2, 1, 1, 9));
    Assert.assertTrue(buf1.equalTo(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 1}, 0, 1, 9));
    buf1.putByte(9, (byte) 2);
    Assert.assertFalse(buf1.equalTo(buf2, 0, 0, buf1.size()));
    Assert.assertFalse(buf1.equalTo(buf2, 1, 1, 9));
  }

  @Test
  public void testEqualToZeroSize() {
    MemoryBuffer buf1 = MemoryUtils.buffer(0);
    MemoryBuffer buf2 = MemoryUtils.buffer(0);
    Assert.assertTrue(buf1.equalTo(buf2, 0, 0, buf1.size()));
  }

  @Test
  public void testDirectCopyTo() {
    byte[] values = new byte[16];
    for (int i = 0; i < values.length; i++) {
      values[i] = (byte) i;
    }
    MemoryBuffer source = MemoryUtils.wrap(ByteBuffer.allocateDirect(values.length));
    source.writeBytes(values);
    MemoryBuffer directTarget = MemoryUtils.wrap(ByteBuffer.allocateDirect(values.length));
    source.copyTo(0, directTarget, 0, values.length);
    assertEquals(directTarget.getBytes(0, values.length), values);

    MemoryBuffer heapTarget = MemoryUtils.buffer(values.length);
    source.copyTo(0, heapTarget, 0, values.length);
    assertEquals(heapTarget.getBytes(0, values.length), values);

    MemoryBuffer heapSource = MemoryUtils.wrap(values);
    MemoryBuffer directFromHeap = MemoryUtils.wrap(ByteBuffer.allocateDirect(values.length));
    heapSource.copyTo(0, directFromHeap, 0, values.length);
    assertEquals(directFromHeap.getBytes(0, values.length), values);

    source.copyTo(0, source, 4, 8);
    assertEquals(source.getBytes(4, 8), new byte[] {0, 1, 2, 3, 4, 5, 6, 7});
  }

  @Test
  public void testDirectPrimitiveArrays() {
    MemoryBuffer direct = MemoryUtils.wrap(ByteBuffer.allocateDirect(64));
    int[] ints = {1, -2, 3, Integer.MIN_VALUE};
    long[] longs = {4L, -5L, Long.MAX_VALUE};
    direct.writeInts(ints);
    direct.writeLongs(longs);

    int[] readInts = new int[ints.length];
    long[] readLongs = new long[longs.length];
    direct.readerIndex(0);
    direct.readInts(readInts, 0, readInts.length);
    direct.readLongs(readLongs, 0, readLongs.length);
    assertEquals(readInts, ints);
    assertEquals(readLongs, longs);
  }

  @Test
  public void testTypedArrayCopies() {
    assertTypedArrayCopies(MemoryUtils.buffer(256));
    assertTypedArrayCopies(MemoryUtils.wrap(ByteBuffer.allocateDirect(256)));
  }

  private void assertTypedArrayCopies(MemoryBuffer buffer) {
    byte[] bytes = {1, 2, 3, 4};
    int byteOffset = buffer.writerIndex();
    buffer.writeBytes(bytes);
    byte[] byteCopy = new byte[bytes.length];
    buffer.copyToByteArray(byteOffset, byteCopy, 0, bytes.length);
    assertEquals(byteCopy, bytes);

    boolean[] booleans = {true, false, true};
    int booleanOffset = buffer.writerIndex();
    buffer.writeBooleans(booleans);
    boolean[] booleanCopy = new boolean[booleans.length];
    buffer.copyToBooleanArray(booleanOffset, booleanCopy, 0, booleans.length);
    assertEquals(booleanCopy, booleans);

    char[] chars = {'a', 0x1234, Character.MAX_VALUE};
    int charOffset = buffer.writerIndex();
    buffer.writeChars(chars);
    char[] charCopy = new char[chars.length];
    buffer.copyToCharArray(charOffset, charCopy, 0, chars.length * Character.BYTES);
    assertEquals(charCopy, chars);

    short[] shorts = {1, -2, Short.MAX_VALUE};
    int shortOffset = buffer.writerIndex();
    buffer.writeShorts(shorts);
    short[] shortCopy = new short[shorts.length];
    buffer.copyToShortArray(shortOffset, shortCopy, 0, shorts.length * Short.BYTES);
    assertEquals(shortCopy, shorts);

    int[] ints = {1, -2, Integer.MIN_VALUE};
    int intOffset = buffer.writerIndex();
    buffer.writeInts(ints);
    int[] intCopy = new int[ints.length];
    buffer.copyToIntArray(intOffset, intCopy, 0, ints.length * Integer.BYTES);
    assertEquals(intCopy, ints);

    long[] longs = {1L, -2L, Long.MAX_VALUE};
    int longOffset = buffer.writerIndex();
    buffer.writeLongs(longs);
    long[] longCopy = new long[longs.length];
    buffer.copyToLongArray(longOffset, longCopy, 0, longs.length * Long.BYTES);
    assertEquals(longCopy, longs);

    float[] floats = {1.5f, -2.5f, Float.MAX_VALUE};
    int floatOffset = buffer.writerIndex();
    buffer.writeFloats(floats);
    float[] floatCopy = new float[floats.length];
    buffer.copyToFloatArray(floatOffset, floatCopy, 0, floats.length * Float.BYTES);
    assertEquals(floatCopy, floats);

    double[] doubles = {1.5d, -2.5d, Double.MAX_VALUE};
    int doubleOffset = buffer.writerIndex();
    buffer.writeDoubles(doubles);
    double[] doubleCopy = new double[doubles.length];
    buffer.copyToDoubleArray(doubleOffset, doubleCopy, 0, doubles.length * Double.BYTES);
    assertEquals(doubleCopy, doubles);
  }

  @Test
  public void testWritePrimitiveArrayWithSizeEmbedded() {
    MemoryBuffer buf = MemoryUtils.buffer(16);
    Random random = new Random(0);
    byte[] bytes = new byte[100];
    random.nextBytes(bytes);
    char[] chars = new char[100];
    for (int i = 0; i < chars.length; i++) {
      chars[i] = (char) random.nextInt();
    }
    buf.writeBytesWithSize(bytes);
    buf.writeCharsWithSize(chars);
    assertEquals(bytes, buf.readBytesAndSize());
    char[] readChars = new char[buf.readVarUInt32() >>> 1];
    buf.readChars(readChars, 0, readChars.length);
    assertEquals(chars, readChars);
  }

  @Test
  public void testReadCharsAndSizeAlignment() {
    MemoryBuffer buf = MemoryUtils.buffer(8);
    buf.writeVarUInt32(3);
    buf.writeBytes(new byte[] {1, 2, 3});
    assertThrows(IllegalArgumentException.class, buf::readCharsAndSize);
  }

  @Test
  public void testWriteVarUInt32() {
    for (int i = 0; i < 32; i++) {
      MemoryBuffer buf = MemoryUtils.buffer(8);
      for (int j = 0; j < i; j++) {
        buf.writeByte((byte) 1); // make address unaligned.
        buf.readByte();
      }
      checkVarUInt32(buf, 1, 1);
      checkVarUInt32(buf, 1 << 6, 1);
      checkVarUInt32(buf, 1 << 7, 2);
      checkVarUInt32(buf, 1 << 13, 2);
      checkVarUInt32(buf, 1 << 14, 3);
      checkVarUInt32(buf, 1 << 20, 3);
      checkVarUInt32(buf, 1 << 21, 4);
      checkVarUInt32(buf, 1 << 27, 4);
      checkVarUInt32(buf, 1 << 28, 5);
      checkVarUInt32(buf, Integer.MAX_VALUE, 5);

      checkVarUInt32(buf, -1);
      checkVarUInt32(buf, -1 << 6);
      checkVarUInt32(buf, -1 << 7);
      checkVarUInt32(buf, -1 << 13);
      checkVarUInt32(buf, -1 << 14);
      checkVarUInt32(buf, -1 << 20);
      checkVarUInt32(buf, -1 << 21);
      checkVarUInt32(buf, -1 << 27);
      checkVarUInt32(buf, -1 << 28);
      checkVarUInt32(buf, Byte.MIN_VALUE);
      checkVarUInt32(buf, Short.MIN_VALUE);
      checkVarUInt32(buf, Integer.MIN_VALUE);
    }
  }

  @Test
  public void testReadVarUInt32RejectsFifthByte() {
    byte[] malformed = new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10};
    assertThrows(IllegalArgumentException.class, () -> MemoryUtils.wrap(malformed).readVarUInt32());
    assertThrows(
        IllegalArgumentException.class, () -> MemoryUtils.wrap(malformed).readVarUInt32Small7());
    assertThrows(
        IllegalArgumentException.class, () -> MemoryUtils.wrap(malformed).readVarUInt32Small14());
    assertThrows(IllegalArgumentException.class, () -> MemoryUtils.wrap(malformed).readVarInt32());
    assertThrows(
        IllegalArgumentException.class, () -> MemoryUtils.wrap(malformed).readBinarySize());

    byte[] maxUInt32 = new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x0f};
    assertEquals(MemoryUtils.wrap(maxUInt32).readVarUInt32(), -1);
    assertEquals(MemoryUtils.wrap(maxUInt32).readVarUInt32Small7(), -1);
    assertEquals(MemoryUtils.wrap(maxUInt32).readVarUInt32Small14(), -1);
  }

  private void checkVarUInt32(MemoryBuffer buf, int value, int bytesWritten) {
    assertEquals(buf.writerIndex(), buf.readerIndex());
    int actualBytesWritten = buf.writeVarUInt32(value);
    assertEquals(actualBytesWritten, bytesWritten);
    int varInt = buf.readVarUInt32();
    assertEquals(buf.writerIndex(), buf.readerIndex());
    assertEquals(value, varInt);
  }

  private void checkVarUInt32(MemoryBuffer buf, int value) {
    int readerIndex = buf.readerIndex();
    assertEquals(buf.writerIndex(), readerIndex);
    buf.writeVarUInt32(value);
    int varInt = buf.readVarUInt32();
    assertEquals(buf.writerIndex(), buf.readerIndex());
    assertEquals(value, varInt);
    // test slow read branch in `readVarUint`
    assertEquals(buf.slice(readerIndex, buf.readerIndex() - readerIndex).readVarUInt32(), value);
  }

  @Test
  public void testWriteVarInt() {
    for (int i = 0; i < 5; i++) {
      checkVarInt(buf(i), 1, 1);
      checkVarInt(buf(i), 1 << 5, 1);
      checkVarInt(buf(i), 1 << 6, 2);
      checkVarInt(buf(i), 1 << 7, 2);
      checkVarInt(buf(i), 1 << 12, 2);
      checkVarInt(buf(i), 1 << 13, 3);
      checkVarInt(buf(i), 1 << 14, 3);
      checkVarInt(buf(i), 1 << 19, 3);
      checkVarInt(buf(i), 1 << 20, 4);
      checkVarInt(buf(i), 1 << 26, 4);
      checkVarInt(buf(i), 1 << 27, 5);
      checkVarInt(buf(i), 1 << 28, 5);
      checkVarInt(buf(i), Integer.MAX_VALUE, 5);

      checkVarInt(buf(i), -1, 1);
      checkVarInt(buf(i), -1 << 6, 1);
      checkVarInt(buf(i), -1 << 7, 2);
      checkVarInt(buf(i), -1 << 13, 2);
      checkVarInt(buf(i), -1 << 14, 3);
      checkVarInt(buf(i), -1 << 20, 3);
      checkVarInt(buf(i), -1 << 21, 4);
      checkVarInt(buf(i), -1 << 27, 4);
      checkVarInt(buf(i), -1 << 28, 5);
      checkVarInt(buf(i), Byte.MIN_VALUE, 2);
      checkVarInt(buf(i), Byte.MAX_VALUE, 2);
      checkVarInt(buf(i), Short.MAX_VALUE, 3);
      checkVarInt(buf(i), Short.MIN_VALUE, 3);
      checkVarInt(buf(i), Integer.MAX_VALUE, 5);
      checkVarInt(buf(i), Integer.MIN_VALUE, 5);
    }
  }

  private MemoryBuffer buf(int numUnaligned) {
    MemoryBuffer buf = MemoryUtils.buffer(1);
    for (int j = 0; j < numUnaligned; j++) {
      buf.writeByte((byte) 1); // make address unaligned.
      buf.readByte();
    }
    return buf;
  }

  private void checkVarInt(MemoryBuffer buf, int value, int bytesWritten) {
    int readerIndex = buf.readerIndex();
    assertEquals(buf.writerIndex(), readerIndex);
    int actualBytesWritten = buf.writeVarInt32(value);
    assertEquals(actualBytesWritten, bytesWritten);
    int varInt = buf.readVarInt32();
    assertEquals(buf.writerIndex(), buf.readerIndex());
    assertEquals(value, varInt);
    // test slow read branch in `readVarInt32`
    assertEquals(buf.slice(readerIndex, buf.readerIndex() - readerIndex).readVarInt32(), value);
  }

  @Test
  public void testWriteVarInt64() {
    MemoryBuffer buf = MemoryUtils.buffer(8);
    checkVarInt64(buf, -1, 1);
    for (int i = 0; i < 9; i++) {
      for (int j = 0; j < i; j++) {
        checkVarInt64(buf(i), -1, 1);
        checkVarInt64(buf(i), 1, 1);
        checkVarInt64(buf(i), 1L << 6, 2);
        checkVarInt64(buf(i), 1L << 7, 2);
        checkVarInt64(buf(i), -(2 << 5), 1);
        checkVarInt64(buf(i), -(2 << 6), 2);
        checkVarInt64(buf(i), 1L << 13, 3);
        checkVarInt64(buf(i), 1L << 14, 3);
        checkVarInt64(buf(i), -(2 << 12), 2);
        checkVarInt64(buf(i), -(2 << 13), 3);
        checkVarInt64(buf(i), 1L << 19, 3);
        checkVarInt64(buf(i), 1L << 20, 4);
        checkVarInt64(buf(i), 1L << 21, 4);
        checkVarInt64(buf(i), -(2 << 19), 3);
        checkVarInt64(buf(i), -(2 << 20), 4);
        checkVarInt64(buf(i), 1L << 26, 4);
        checkVarInt64(buf(i), 1L << 27, 5);
        checkVarInt64(buf(i), 1L << 28, 5);
        checkVarInt64(buf(i), -(2 << 26), 4);
        checkVarInt64(buf(i), -(2 << 27), 5);
        checkVarInt64(buf(i), 1L << 30, 5);
        checkVarInt64(buf(i), -(2L << 29), 5);
        checkVarInt64(buf(i), 1L << 30, 5);
        checkVarInt64(buf(i), -(2L << 30), 5);
        checkVarInt64(buf(i), 1L << 32, 5);
        checkVarInt64(buf(i), -(2L << 31), 5);
        checkVarInt64(buf(i), 1L << 34, 6);
        checkVarInt64(buf(i), -(2L << 33), 5);
        checkVarInt64(buf(i), 1L << 35, 6);
        checkVarInt64(buf(i), -(2L << 34), 6);
        checkVarInt64(buf(i), 1L << 41, 7);
        checkVarInt64(buf(i), -(2L << 40), 6);
        checkVarInt64(buf(i), 1L << 42, 7);
        checkVarInt64(buf(i), -(2L << 41), 7);
        checkVarInt64(buf(i), 1L << 48, 8);
        checkVarInt64(buf(i), -(2L << 47), 7);
        checkVarInt64(buf(i), -(2L << 48), 8);
        checkVarInt64(buf(i), 1L << 49, 8);
        checkVarInt64(buf(i), -(2L << 48), 8);
        checkVarInt64(buf(i), -(2L << 54), 8);
        checkVarInt64(buf(i), 1L << 54, 8);
        checkVarInt64(buf(i), 1L << 55, 9);
        checkVarInt64(buf(i), 1L << 56, 9);
        checkVarInt64(buf(i), -(2L << 55), 9);
        checkVarInt64(buf(i), 1L << 62, 9);
        checkVarInt64(buf(i), -(2L << 62), 9);
        checkVarInt64(buf(i), 1L << 63 - 1, 9);
        checkVarInt64(buf(i), -(2L << 62), 9);
        checkVarInt64(buf(i), Long.MAX_VALUE, 9);
        checkVarInt64(buf(i), Long.MIN_VALUE, 9);
      }
    }
  }

  private void checkVarInt64(MemoryBuffer buf, long value, int bytesWritten) {
    int readerIndex = buf.readerIndex();
    assertEquals(buf.writerIndex(), readerIndex);
    int actualBytesWritten = buf.writeVarInt64(value);
    assertEquals(actualBytesWritten, bytesWritten);
    long varLong = buf.readVarInt64();
    assertEquals(buf.writerIndex(), buf.readerIndex());
    assertEquals(value, varLong);
    // test slow read branch in `readVarInt64`
    assertEquals(buf.slice(readerIndex, buf.readerIndex() - readerIndex).readVarInt64(), value);
  }

  @Test
  public void testWriteVarUInt64() {
    MemoryBuffer buf = MemoryUtils.buffer(8);
    checkVarUInt64(buf, -1, 9);
    for (int i = 0; i < 9; i++) {
      for (int j = 0; j < i; j++) {
        checkVarUInt64(buf(i), -1, 9);
        checkVarUInt64(buf(i), 1, 1);
        checkVarUInt64(buf(i), 1L << 6, 1);
        checkVarUInt64(buf(i), 1L << 7, 2);
        checkVarUInt64(buf(i), -(2 << 5), 9);
        checkVarUInt64(buf(i), -(2 << 6), 9);
        checkVarUInt64(buf(i), 1L << 13, 2);
        checkVarUInt64(buf(i), 1L << 14, 3);
        checkVarUInt64(buf(i), -(2 << 12), 9);
        checkVarUInt64(buf(i), -(2 << 13), 9);
        checkVarUInt64(buf(i), 1L << 20, 3);
        checkVarUInt64(buf(i), 1L << 21, 4);
        checkVarUInt64(buf(i), -(2 << 19), 9);
        checkVarUInt64(buf(i), -(2 << 20), 9);
        checkVarUInt64(buf(i), 1L << 27, 4);
        checkVarUInt64(buf(i), 1L << 28, 5);
        checkVarUInt64(buf(i), -(2 << 26), 9);
        checkVarUInt64(buf(i), -(2 << 27), 9);
        checkVarUInt64(buf(i), 1L << 30, 5);
        checkVarUInt64(buf(i), -(2L << 29), 9);
        checkVarUInt64(buf(i), 1L << 30, 5);
        checkVarUInt64(buf(i), -(2L << 30), 9);
        checkVarUInt64(buf(i), 1L << 32, 5);
        checkVarUInt64(buf(i), -(2L << 31), 9);
        checkVarUInt64(buf(i), 1L << 34, 5);
        checkVarUInt64(buf(i), -(2L << 33), 9);
        checkVarUInt64(buf(i), 1L << 35, 6);
        checkVarUInt64(buf(i), -(2L << 34), 9);
        checkVarUInt64(buf(i), 1L << 41, 6);
        checkVarUInt64(buf(i), -(2L << 40), 9);
        checkVarUInt64(buf(i), 1L << 42, 7);
        checkVarUInt64(buf(i), -(2L << 41), 9);
        checkVarUInt64(buf(i), 1L << 48, 7);
        checkVarUInt64(buf(i), -(2L << 47), 9);
        checkVarUInt64(buf(i), 1L << 49, 8);
        checkVarUInt64(buf(i), -(2L << 48), 9);
        checkVarUInt64(buf(i), 1L << 55, 8);
        checkVarUInt64(buf(i), -(2L << 54), 9);
        checkVarUInt64(buf(i), 1L << 56, 9);
        checkVarUInt64(buf(i), -(2L << 55), 9);
        checkVarUInt64(buf(i), 1L << 62, 9);
        checkVarUInt64(buf(i), -(2L << 62), 9);
        checkVarUInt64(buf(i), 1L << 63 - 1, 9);
        checkVarUInt64(buf(i), -(2L << 62), 9);
        checkVarUInt64(buf(i), Long.MAX_VALUE, 9);
        checkVarUInt64(buf(i), Long.MIN_VALUE, 9);
      }
    }
  }

  private void checkVarUInt64(MemoryBuffer buf, long value, int bytesWritten) {
    int readerIndex = buf.readerIndex();
    assertEquals(buf.writerIndex(), readerIndex);
    int actualBytesWritten = buf.writeVarUInt64(value);
    assertEquals(actualBytesWritten, bytesWritten);
    long varLong = buf.readVarUInt64();
    assertEquals(buf.writerIndex(), buf.readerIndex());
    assertEquals(value, varLong);
    // test slow read branch in `readVarUInt64`
    assertEquals(buf.slice(readerIndex, buf.readerIndex() - readerIndex).readVarUInt64(), value);
  }

  @Test
  public void testWriteVarUInt32Aligned() {
    MemoryBuffer buf = MemoryUtils.buffer(16);
    assertEquals(buf.writeVarUInt32Aligned(1), 4);
    assertEquals(buf.readAlignedVarUInt32(), 1);
    assertEquals(buf.writeVarUInt32Aligned(1 << 5), 4);
    assertEquals(buf.readAlignedVarUInt32(), 1 << 5);
    assertEquals(buf.writeVarUInt32Aligned(1 << 10), 4);
    assertEquals(buf.readAlignedVarUInt32(), 1 << 10);
    assertEquals(buf.writeVarUInt32Aligned(1 << 15), 4);
    assertEquals(buf.readAlignedVarUInt32(), 1 << 15);
    assertEquals(buf.writeVarUInt32Aligned(1 << 20), 4);
    assertEquals(buf.readAlignedVarUInt32(), 1 << 20);
    assertEquals(buf.writeVarUInt32Aligned(1 << 25), 8);
    assertEquals(buf.readAlignedVarUInt32(), 1 << 25);
    assertEquals(buf.writeVarUInt32Aligned(1 << 30), 8);
    assertEquals(buf.readAlignedVarUInt32(), 1 << 30);
    assertEquals(buf.writeVarUInt32Aligned(Integer.MAX_VALUE), 8);
    assertEquals(buf.readAlignedVarUInt32(), Integer.MAX_VALUE);
    buf.writeByte((byte) 1); // make address unaligned.
    buf.writeInt16((short) 1); // make address unaligned.
    assertEquals(buf.writeVarUInt32Aligned(Integer.MAX_VALUE), 9);
    buf.readByte();
    buf.readInt16();
    assertEquals(buf.readAlignedVarUInt32(), Integer.MAX_VALUE);
    for (int i = 0; i < 32; i++) {
      MemoryBuffer buf1 = MemoryUtils.buffer(16);
      assertAligned(i, buf1);
    }
    MemoryBuffer buf1 = MemoryUtils.buffer(16);
    for (int i = 0; i < 32; i++) {
      assertAligned(i, buf1);
    }
  }

  private void assertAligned(int i, MemoryBuffer buffer) {
    for (int j = 0; j < 31; j++) {
      buffer.writeByte((byte) i); // make address unaligned.
      buffer.writeVarUInt32Aligned(1 << j);
      assertEquals(buffer.writerIndex() % 4, 0);
      buffer.readByte();
      assertEquals(buffer.readAlignedVarUInt32(), 1 << j);
      for (int k = 0; k < i % 4; k++) {
        buffer.writeByte((byte) i); // make address unaligned.
        buffer.writeVarUInt32Aligned(1 << j);
        assertEquals(buffer.writerIndex() % 4, 0);
        buffer.readByte();
        assertEquals(buffer.readAlignedVarUInt32(), 1 << j);
      }
    }
    buffer.writeByte((byte) i); // make address unaligned.
    buffer.writeVarUInt32Aligned(Integer.MAX_VALUE);
    assertEquals(buffer.writerIndex() % 4, 0);
    buffer.readByte();
    assertEquals(buffer.readAlignedVarUInt32(), Integer.MAX_VALUE);
  }

  @Test
  public void testGetShortB() {
    byte[] data = new byte[4];
    data[0] = (byte) 0xac;
    data[1] = (byte) 0xed;
    assertEquals(BigEndian.getShortB(data, 0), (short) 0xaced);
  }

  @Test
  public void testWriteTaggedInt64() {
    MemoryBuffer buf = MemoryUtils.buffer(8);
    checkTaggedInt64(buf, -1, 4);
    for (int i = 0; i < 10; i++) {
      for (int j = 0; j < i; j++) {
        checkTaggedInt64(buf(i), -1, 4);
        checkTaggedInt64(buf(i), 1, 4);
        checkTaggedInt64(buf(i), 1L << 6, 4);
        checkTaggedInt64(buf(i), 1L << 7, 4);
        checkTaggedInt64(buf(i), -(2 << 5), 4);
        checkTaggedInt64(buf(i), -(2 << 6), 4);
        checkTaggedInt64(buf(i), 1L << 28, 4);
        checkTaggedInt64(buf(i), Integer.MAX_VALUE / 2, 4);
        checkTaggedInt64(buf(i), Integer.MIN_VALUE / 2, 4);
        checkTaggedInt64(buf(i), -1L << 30, 4);
        checkTaggedInt64(buf(i), 1L << 30, 9);
        checkTaggedInt64(buf(i), Integer.MAX_VALUE, 9);
        checkTaggedInt64(buf(i), Integer.MIN_VALUE, 9);
        checkTaggedInt64(buf(i), -1L << 31, 9);
        checkTaggedInt64(buf(i), 1L << 31, 9);
        checkTaggedInt64(buf(i), -1L << 32, 9);
        checkTaggedInt64(buf(i), 1L << 32, 9);
        checkTaggedInt64(buf(i), Long.MAX_VALUE, 9);
        checkTaggedInt64(buf(i), Long.MIN_VALUE, 9);
      }
    }
  }

  private void checkTaggedInt64(MemoryBuffer buf, long value, int bytesWritten) {
    int readerIndex = buf.readerIndex();
    assertEquals(buf.writerIndex(), readerIndex);
    int actualBytesWritten = buf.writeTaggedInt64(value);
    assertEquals(actualBytesWritten, bytesWritten);
    long varLong = buf.readTaggedInt64();
    assertEquals(buf.writerIndex(), buf.readerIndex());
    assertEquals(value, varLong);
    assertEquals(buf.slice(readerIndex, buf.readerIndex() - readerIndex).readTaggedInt64(), value);
  }

  @Test
  public void testWriteTaggedUInt64() {
    MemoryBuffer buf = MemoryUtils.buffer(8);
    checkTaggedUInt64(buf, 0, 4);
    checkTaggedUInt64(buf, 1, 4);
    for (int i = 0; i < 10; i++) {
      for (int j = 0; j < i; j++) {
        // Values in [0, Integer.MAX_VALUE] should use 4 bytes
        checkTaggedUInt64(buf(i), 0, 4);
        checkTaggedUInt64(buf(i), 1, 4);
        checkTaggedUInt64(buf(i), 1L << 6, 4);
        checkTaggedUInt64(buf(i), 1L << 7, 4);
        checkTaggedUInt64(buf(i), 1L << 28, 4);
        checkTaggedUInt64(buf(i), 1L << 30, 4);
        checkTaggedUInt64(buf(i), Integer.MAX_VALUE, 4);
        // Values > Integer.MAX_VALUE should use 9 bytes
        checkTaggedUInt64(buf(i), (long) Integer.MAX_VALUE + 1, 9);
        checkTaggedUInt64(buf(i), 1L << 31, 9);
        checkTaggedUInt64(buf(i), 1L << 32, 9);
        checkTaggedUInt64(buf(i), 1L << 62, 9);
        checkTaggedUInt64(buf(i), Long.MAX_VALUE, 9);
        // Negative values (large unsigned) should use 9 bytes
        checkTaggedUInt64(buf(i), -1, 9);
        checkTaggedUInt64(buf(i), -1L << 30, 9);
        checkTaggedUInt64(buf(i), Integer.MIN_VALUE, 9);
        checkTaggedUInt64(buf(i), Long.MIN_VALUE, 9);
      }
    }
  }

  private void checkTaggedUInt64(MemoryBuffer buf, long value, int bytesWritten) {
    int readerIndex = buf.readerIndex();
    assertEquals(buf.writerIndex(), readerIndex);
    int actualBytesWritten = buf.writeTaggedUInt64(value);
    assertEquals(actualBytesWritten, bytesWritten);
    long varLong = buf.readTaggedUInt64();
    assertEquals(buf.writerIndex(), buf.readerIndex());
    assertEquals(value, varLong);
    assertEquals(buf.slice(readerIndex, buf.readerIndex() - readerIndex).readTaggedUInt64(), value);
  }

  @Test
  public void testVarUInt32Small7() {
    MemoryBuffer buf = MemoryUtils.buffer(1);
    buf.writeVarUInt32Small7(1);
    assertEquals(buf.readVarUInt32Small7(), 1);
    assertEquals(buf.writeVarUInt32Small7(127), 1);
    assertEquals(buf.readVarUInt32Small7(), 127);
    assertEquals(buf.writeVarUInt32Small7(Short.MAX_VALUE), 3);
    assertEquals(buf.readVarUInt32Small7(), Short.MAX_VALUE);
    assertEquals(buf.writeVarUInt32Small7(Integer.MAX_VALUE), 5);
    assertEquals(buf.readVarUInt32Small7(), Integer.MAX_VALUE);
    assertEquals(buf.writeVarUInt32Small7(-1), 5);
    assertEquals(buf.readVarUInt32Small7(), -1);
    assertEquals(buf.writeVarUInt32Small7(0), 1);
    assertEquals(buf.readVarUInt32Small7(), 0);
  }

  @Test
  public void testVarUint36Small() {
    MemoryBuffer buf = MemoryUtils.buffer(80);
    int index = 0;
    {
      int diff = LittleEndian.putVarUint36Small(buf.getHeapMemory(), index, 10);
      assertEquals(buf.readVarUint36Small(), 10);
      buf.increaseReaderIndex(-diff);
      index += buf._unsafePutVarUint36Small(index, 10);
      assertEquals(buf.readVarUint36Small(), 10);
    }
    {
      int diff = LittleEndian.putVarUint36Small(buf.getHeapMemory(), index, Short.MAX_VALUE);
      assertEquals(buf.readVarUint36Small(), Short.MAX_VALUE);
      buf.increaseReaderIndex(-diff);
      index += buf._unsafePutVarUint36Small(index, Short.MAX_VALUE);
      assertEquals(buf.readVarUint36Small(), Short.MAX_VALUE);
    }
    {
      int diff = LittleEndian.putVarUint36Small(buf.getHeapMemory(), index, Integer.MAX_VALUE);
      assertEquals(buf.readVarUint36Small(), Integer.MAX_VALUE);
      buf.increaseReaderIndex(-diff);
      index += buf._unsafePutVarUint36Small(index, Integer.MAX_VALUE);
      assertEquals(buf.readVarUint36Small(), Integer.MAX_VALUE);
    }
    {
      int diff =
          LittleEndian.putVarUint36Small(
              buf.getHeapMemory(), index, 0b111111111111111111111111111111111111L);
      assertEquals(buf.readVarUint36Small(), 0b111111111111111111111111111111111111L);
      buf.increaseReaderIndex(-diff);
      buf._unsafePutVarUint36Small(index, 0b1000000000000000000000000000000000000L);
      assertEquals(buf.readVarUint36Small(), 0); // overflow
    }
    {
      // With buffer size 9
      MemoryBuffer buf1 = MemoryBuffer.newHeapBuffer(9);
      // With buffer size 8
      MemoryBuffer buf2 = MemoryBuffer.newHeapBuffer(8);
      long uint36Max = 0b111111111111111111111111111111111111L;
      buf1._unsafePutVarUint36Small(0, uint36Max);
      buf2._unsafePutVarUint36Small(0, uint36Max);
      assertEquals(buf1.readVarUint36Small(), uint36Max);
      assertEquals(buf2.readVarUint36Small(), uint36Max);
    }
  }

  @Test
  public void testReadBytesAsInt64() {
    for (MemoryBuffer buffer :
        new MemoryBuffer[] {
          MemoryUtils.buffer(16), MemoryUtils.wrap(ByteBuffer.allocateDirect(32)),
        }) {
      buffer.writeByte(10);
      buffer.writeByte(20);
      assertEquals(buffer.readBytesAsInt64(2), (20 << 8) | 10);
    }
  }
}
