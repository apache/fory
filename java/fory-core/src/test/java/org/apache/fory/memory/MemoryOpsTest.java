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
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import org.testng.annotations.Test;

public class MemoryOpsTest {

  @Test
  public void testPrimitiveLittleEndianAccess() {
    byte[] bytes = new byte[48];
    MemoryOps.putBoolean(bytes, 0, true);
    MemoryOps.putByte(bytes, 1, (byte) 0xFE);
    MemoryOps.putInt16(bytes, 2, (short) 0x1234);
    MemoryOps.putInt32(bytes, 4, 0x12345678);
    MemoryOps.putInt64(bytes, 8, 0x0102030405060708L);
    MemoryOps.putFloat32(bytes, 16, Float.intBitsToFloat(0x7FC12345));
    MemoryOps.putFloat64(bytes, 24, Double.longBitsToDouble(0x7FF8123456789ABCL));

    assertEquals(MemoryOps.getBoolean(bytes, 0), true);
    assertEquals(MemoryOps.getByte(bytes, 1), (byte) 0xFE);
    assertEquals(MemoryOps.getInt16(bytes, 2), (short) 0x1234);
    assertEquals(MemoryOps.getInt32(bytes, 4), 0x12345678);
    assertEquals(MemoryOps.getInt64(bytes, 8), 0x0102030405060708L);
    assertEquals(Float.floatToRawIntBits(MemoryOps.getFloat32(bytes, 16)), 0x7FC12345);
    assertEquals(Double.doubleToRawLongBits(MemoryOps.getFloat64(bytes, 24)), 0x7FF8123456789ABCL);

    assertEquals(
        Arrays.copyOfRange(bytes, 2, 16),
        new byte[] {0x34, 0x12, 0x78, 0x56, 0x34, 0x12, 8, 7, 6, 5, 4, 3, 2, 1});
  }

  @Test
  public void testVarInt32MatchesMemoryBuffer() {
    int[] values = {0, 1, 127, 128, 16_383, 16_384, Integer.MAX_VALUE, Integer.MIN_VALUE, -1};
    for (int value : values) {
      byte[] bytes = new byte[8];
      int size = MemoryOps.putVarUInt32(bytes, 0, value);
      MemoryBuffer buffer = MemoryUtils.buffer(16);
      int expectedSize = buffer.writeVarUInt32(value);
      assertEquals(size, expectedSize);
      assertEquals(Arrays.copyOf(bytes, size), buffer.getBytes(0, expectedSize));
      assertEquals(MemoryOps.readVarUInt32(bytes, 0), value);
      assertEquals(MemoryOps.varUInt32Bytes(bytes, 0), size);

      Arrays.fill(bytes, (byte) 0);
      size = MemoryOps.putVarInt32(bytes, 0, value);
      buffer = MemoryUtils.buffer(16);
      expectedSize = buffer.writeVarInt32(value);
      assertEquals(size, expectedSize);
      assertEquals(Arrays.copyOf(bytes, size), buffer.getBytes(0, expectedSize));
      assertEquals(MemoryOps.readVarInt32(bytes, 0), value);
      assertEquals(MemoryOps.varUInt32Bytes(bytes, 0), size);
    }
  }

  @Test
  public void testVarUInt32RejectsMalformedFifthByte() {
    byte[] malformed = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10};
    assertThrows(IllegalArgumentException.class, () -> MemoryOps.readVarUInt32(malformed, 0));
    assertThrows(IllegalArgumentException.class, () -> MemoryOps.varUInt32Bytes(malformed, 0));
  }

  @Test
  public void testVarInt64MatchesMemoryBuffer() {
    long[] values = {
      0L,
      1L,
      127L,
      128L,
      16_383L,
      16_384L,
      Integer.MAX_VALUE,
      Integer.MIN_VALUE,
      Long.MAX_VALUE,
      Long.MIN_VALUE,
      -1L
    };
    for (long value : values) {
      byte[] bytes = new byte[9];
      int size = MemoryOps.putVarUInt64(bytes, 0, value);
      MemoryBuffer buffer = MemoryUtils.buffer(16);
      int expectedSize = buffer.writeVarUInt64(value);
      assertEquals(size, expectedSize);
      assertEquals(Arrays.copyOf(bytes, size), buffer.getBytes(0, expectedSize));
      assertEquals(MemoryOps.readVarUInt64(bytes, 0), value);
      assertEquals(MemoryOps.varUInt64Bytes(bytes, 0), size);

      Arrays.fill(bytes, (byte) 0);
      size = MemoryOps.putVarInt64(bytes, 0, value);
      buffer = MemoryUtils.buffer(16);
      expectedSize = buffer.writeVarInt64(value);
      assertEquals(size, expectedSize);
      assertEquals(Arrays.copyOf(bytes, size), buffer.getBytes(0, expectedSize));
      assertEquals(MemoryOps.readVarInt64(bytes, 0), value);
      assertEquals(MemoryOps.varUInt64Bytes(bytes, 0), size);
    }
  }

  @Test
  public void testVarUint36MatchesMemoryBuffer() {
    long[] values = {
      0L,
      1L,
      127L,
      128L,
      16_383L,
      16_384L,
      Integer.MAX_VALUE,
      0xFFFFFFFFFL,
      (1L << 35) - 1,
      1L << 35,
      (1L << 36) - 1
    };
    for (long value : values) {
      byte[] bytes = new byte[8];
      int size = MemoryOps.putVarUint36Small(bytes, 0, value);
      MemoryBuffer buffer = MemoryUtils.buffer(16);
      int expectedSize = buffer._unsafePutVarUint36Small(0, value);
      assertEquals(size, expectedSize);
      assertEquals(Arrays.copyOf(bytes, size), buffer.getBytes(0, expectedSize));
      assertEquals(MemoryOps.readVarUint36Small(bytes, 0), value);
      assertEquals(MemoryOps.varUint36SmallBytes(bytes, 0), size);
    }
  }

  @Test
  public void testPrimitiveArrayRoundTrip() {
    byte[] bytes = new byte[128];
    MemoryBuffer buffer = MemoryBuffer.fromByteArray(bytes);

    boolean[] booleans = {true, false, true};
    boolean[] booleanCopy = new boolean[booleans.length];
    MemoryOps.writeBooleans(bytes, 1, booleans, 0, booleans.length);
    buffer.readerIndex(1);
    MemoryOps.readBooleans(buffer, booleanCopy, 0, booleanCopy.length);
    assertEquals(booleanCopy, booleans);

    char[] chars = {'a', '\u4f60', '\u597d'};
    char[] charCopy = new char[chars.length];
    MemoryOps.writeChars(bytes, 8, chars, 0, chars.length);
    buffer.readerIndex(8);
    MemoryOps.readChars(buffer, charCopy, 0, charCopy.length);
    assertEquals(charCopy, chars);

    short[] shorts = {Short.MIN_VALUE, -1, 0, Short.MAX_VALUE};
    short[] shortCopy = new short[shorts.length];
    MemoryOps.writeShorts(bytes, 16, shorts, 0, shorts.length);
    buffer.readerIndex(16);
    MemoryOps.readShorts(buffer, shortCopy, 0, shortCopy.length);
    assertEquals(shortCopy, shorts);

    int[] ints = {Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE};
    int[] intCopy = new int[ints.length];
    MemoryOps.writeInts(bytes, 24, ints, 0, ints.length);
    buffer.readerIndex(24);
    MemoryOps.readInts(buffer, intCopy, 0, intCopy.length);
    assertEquals(intCopy, ints);

    long[] longs = {Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE};
    long[] longCopy = new long[longs.length];
    MemoryOps.writeLongs(bytes, 40, longs, 0, longs.length);
    buffer.readerIndex(40);
    MemoryOps.readLongs(buffer, longCopy, 0, longCopy.length);
    assertEquals(longCopy, longs);

    float[] floats = {Float.NaN, Float.NEGATIVE_INFINITY, -1.5f, 0f, Float.MAX_VALUE};
    float[] floatCopy = new float[floats.length];
    MemoryOps.writeFloats(bytes, 72, floats, 0, floats.length);
    buffer.readerIndex(72);
    MemoryOps.readFloats(buffer, floatCopy, 0, floatCopy.length);
    assertEquals(floatRawBits(floatCopy), floatRawBits(floats));

    double[] doubles = {Double.NaN, Double.NEGATIVE_INFINITY, -1.5d, 0d, Double.MAX_VALUE};
    double[] doubleCopy = new double[doubles.length];
    MemoryOps.writeDoubles(bytes, 88, doubles, 0, doubles.length);
    buffer.readerIndex(88);
    MemoryOps.readDoubles(buffer, doubleCopy, 0, doubleCopy.length);
    assertEquals(doubleRawBits(doubleCopy), doubleRawBits(doubles));
  }

  @Test
  public void testMemoryOpsDoesNotUseUnsafeOrByteBufferWrappers() throws IOException {
    String source =
        new String(
            Files.readAllBytes(Paths.get("src/main/java/org/apache/fory/memory/MemoryOps.java")),
            StandardCharsets.UTF_8);
    assertFalse(source.contains("sun.misc.Unsafe"));
    assertFalse(source.contains("UnsafeOps"));
    assertFalse(source.contains("ByteBuffer.wrap"));
  }

  private static int[] floatRawBits(float[] values) {
    int[] bits = new int[values.length];
    for (int i = 0; i < values.length; i++) {
      bits[i] = Float.floatToRawIntBits(values[i]);
    }
    return bits;
  }

  private static long[] doubleRawBits(double[] values) {
    long[] bits = new long[values.length];
    for (int i = 0; i < values.length; i++) {
      bits[i] = Double.doubleToRawLongBits(values[i]);
    }
    return bits;
  }
}
