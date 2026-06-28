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

import static org.apache.fory.util.Preconditions.checkArgument;

import java.nio.ByteBuffer;

/**
 * Android-safe byte-array memory operations.
 *
 * <p>All multi-byte primitive operations are little-endian. Varint helpers intentionally read and
 * write one byte at a time and must not use chunked primitive loads.
 */
final class MemoryOps {
  private MemoryOps() {}

  private static int heapIndex(MemoryBuffer buffer, int index) {
    return buffer.heapOffset + index;
  }

  private static void checkHeap(MemoryBuffer buffer) {
    if (buffer.heapMemory == null) {
      throw new UnsupportedOperationException("Only heap MemoryBuffer is supported on Android");
    }
  }

  private static void checkPosition(MemoryBuffer buffer, int index, int length) {
    if (BoundsChecking.BOUNDS_CHECKING_ENABLED && (index < 0 || index > buffer.size - length)) {
      throwOOBException(buffer);
    }
  }

  private static void throwOOBException(MemoryBuffer buffer) {
    throw new IndexOutOfBoundsException(
        String.format(
            "size: %d, address %s, addressLimit %d",
            buffer.size, buffer.address, buffer.addressLimit));
  }

  private static void throwIndexOOBExceptionForRead(MemoryBuffer buffer, int length) {
    throw new IndexOutOfBoundsException(
        String.format(
            "readerIndex: %d (expected: 0 <= readerIndex <= size(%d)), length %d",
            buffer.readerIndex, buffer.size, length));
  }

  static void throwDirectByteBufferUnsupported() {
    throw new UnsupportedOperationException("Direct ByteBuffer memory is not supported on Android");
  }

  static MemoryBuffer directByteBufferUnsupported() {
    throw new UnsupportedOperationException("Direct ByteBuffer memory is not supported on Android");
  }

  static MemoryBuffer fromByteBuffer(ByteBuffer buffer) {
    ByteBuffer duplicate = buffer.duplicate();
    byte[] bytes = new byte[duplicate.remaining()];
    duplicate.get(bytes);
    return MemoryBuffer.fromByteArray(bytes);
  }

  static void initHeapBuffer(MemoryBuffer buffer, byte[] heapMemory, int offset, int length) {
    if (heapMemory == null) {
      throw new NullPointerException("buffer");
    }
    buffer.heapMemory = heapMemory;
    buffer.heapOffset = offset;
    buffer.address = offset;
    buffer.size = length;
    buffer.addressLimit = (long) offset + length;
  }

  static void get(MemoryBuffer buffer, int offset, ByteBuffer target, int numBytes) {
    if ((offset | numBytes | (offset + numBytes)) < 0) {
      throwOOBException(buffer);
    }
    if (target.remaining() < numBytes) {
      throwOOBException(buffer);
    }
    if (target.isReadOnly()) {
      throw new IllegalArgumentException("read only buffer");
    }
    int targetPos = target.position();
    checkHeap(buffer);
    if (offset <= buffer.size - numBytes) {
      target.put(buffer.heapMemory, heapIndex(buffer, offset), numBytes);
    } else {
      throwOOBException(buffer);
    }
    if (target.position() == targetPos) {
      ByteBufferUtil.position(target, targetPos + numBytes);
    }
  }

  static void put(MemoryBuffer buffer, int offset, ByteBuffer source, int numBytes) {
    int remaining = source.remaining();
    if ((offset | numBytes | (offset + numBytes) | (remaining - numBytes)) < 0) {
      throwOOBException(buffer);
    }
    int sourcePos = source.position();
    checkHeap(buffer);
    if (offset <= buffer.size - numBytes) {
      source.get(buffer.heapMemory, heapIndex(buffer, offset), numBytes);
    } else {
      throwOOBException(buffer);
    }
    if (source.position() == sourcePos) {
      ByteBufferUtil.position(source, sourcePos + numBytes);
    }
  }

  static byte getByte(MemoryBuffer buffer, int index) {
    checkPosition(buffer, index, 1);
    return getByte(buffer.heapMemory, heapIndex(buffer, index));
  }

  static byte getByte(byte[] source, int index) {
    return source[index];
  }

  static byte unsafeGetByte(MemoryBuffer buffer, int index) {
    return getByte(buffer.heapMemory, heapIndex(buffer, index));
  }

  static void putByte(MemoryBuffer buffer, int index, int value) {
    checkPosition(buffer, index, 1);
    putByte(buffer.heapMemory, heapIndex(buffer, index), (byte) value);
  }

  static void putByte(MemoryBuffer buffer, int index, byte value) {
    checkPosition(buffer, index, 1);
    putByte(buffer.heapMemory, heapIndex(buffer, index), value);
  }

  static void putByte(byte[] target, int index, byte value) {
    target[index] = value;
  }

  static void unsafePutByte(MemoryBuffer buffer, int index, byte value) {
    putByte(buffer.heapMemory, heapIndex(buffer, index), value);
  }

  static boolean getBoolean(MemoryBuffer buffer, int index) {
    checkPosition(buffer, index, 1);
    return getBoolean(buffer.heapMemory, heapIndex(buffer, index));
  }

  static boolean getBoolean(byte[] source, int index) {
    return source[index] != 0;
  }

  static boolean unsafeGetBoolean(MemoryBuffer buffer, int index) {
    return getBoolean(buffer.heapMemory, heapIndex(buffer, index));
  }

  static void putBoolean(MemoryBuffer buffer, int index, boolean value) {
    putBoolean(buffer.heapMemory, heapIndex(buffer, index), value);
  }

  static void putBoolean(byte[] target, int index, boolean value) {
    target[index] = (byte) (value ? 1 : 0);
  }

  static char getChar(MemoryBuffer buffer, int index) {
    checkPosition(buffer, index, 2);
    return (char) getInt16(buffer.heapMemory, heapIndex(buffer, index));
  }

  static char unsafeGetChar(MemoryBuffer buffer, int index) {
    return (char) getInt16(buffer.heapMemory, heapIndex(buffer, index));
  }

  static void putChar(MemoryBuffer buffer, int index, char value) {
    checkPosition(buffer, index, 2);
    putInt16(buffer.heapMemory, heapIndex(buffer, index), (short) value);
  }

  static void unsafePutChar(MemoryBuffer buffer, int index, char value) {
    putInt16(buffer.heapMemory, heapIndex(buffer, index), (short) value);
  }

  static short getInt16(MemoryBuffer buffer, int index) {
    checkPosition(buffer, index, 2);
    return getInt16(buffer.heapMemory, heapIndex(buffer, index));
  }

  static short getInt16(byte[] source, int index) {
    return (short) ((source[index] & 0xFF) | (source[index + 1] << 8));
  }

  static short unsafeGetInt16(MemoryBuffer buffer, int index) {
    return getInt16(buffer.heapMemory, heapIndex(buffer, index));
  }

  static void putInt16(MemoryBuffer buffer, int index, short value) {
    checkPosition(buffer, index, 2);
    putInt16(buffer.heapMemory, heapIndex(buffer, index), value);
  }

  static void putInt16(byte[] target, int index, short value) {
    target[index] = (byte) value;
    target[index + 1] = (byte) (value >>> 8);
  }

  static void unsafePutInt16(MemoryBuffer buffer, int index, short value) {
    putInt16(buffer.heapMemory, heapIndex(buffer, index), value);
  }

  static int getInt32(MemoryBuffer buffer, int index) {
    checkPosition(buffer, index, 4);
    return getInt32(buffer.heapMemory, heapIndex(buffer, index));
  }

  static int getInt32(byte[] source, int index) {
    return (source[index] & 0xFF)
        | ((source[index + 1] & 0xFF) << 8)
        | ((source[index + 2] & 0xFF) << 16)
        | (source[index + 3] << 24);
  }

  static int unsafeGetInt32(MemoryBuffer buffer, int index) {
    return getInt32(buffer.heapMemory, heapIndex(buffer, index));
  }

  static void putInt32(MemoryBuffer buffer, int index, int value) {
    checkPosition(buffer, index, 4);
    putInt32(buffer.heapMemory, heapIndex(buffer, index), value);
  }

  static void putInt32(byte[] target, int index, int value) {
    target[index] = (byte) value;
    target[index + 1] = (byte) (value >>> 8);
    target[index + 2] = (byte) (value >>> 16);
    target[index + 3] = (byte) (value >>> 24);
  }

  static void unsafePutInt32(MemoryBuffer buffer, int index, int value) {
    putInt32(buffer.heapMemory, heapIndex(buffer, index), value);
  }

  static long getInt64(MemoryBuffer buffer, int index) {
    checkPosition(buffer, index, 8);
    return getInt64(buffer.heapMemory, heapIndex(buffer, index));
  }

  static long getInt64(byte[] source, int index) {
    return ((long) source[index] & 0xFF)
        | (((long) source[index + 1] & 0xFF) << 8)
        | (((long) source[index + 2] & 0xFF) << 16)
        | (((long) source[index + 3] & 0xFF) << 24)
        | (((long) source[index + 4] & 0xFF) << 32)
        | (((long) source[index + 5] & 0xFF) << 40)
        | (((long) source[index + 6] & 0xFF) << 48)
        | ((long) source[index + 7] << 56);
  }

  static long unsafeGetInt64(MemoryBuffer buffer, int index) {
    return getInt64(buffer.heapMemory, heapIndex(buffer, index));
  }

  static void putInt64(MemoryBuffer buffer, int index, long value) {
    checkPosition(buffer, index, 8);
    putInt64(buffer.heapMemory, heapIndex(buffer, index), value);
  }

  static void putInt64(byte[] target, int index, long value) {
    target[index] = (byte) value;
    target[index + 1] = (byte) (value >>> 8);
    target[index + 2] = (byte) (value >>> 16);
    target[index + 3] = (byte) (value >>> 24);
    target[index + 4] = (byte) (value >>> 32);
    target[index + 5] = (byte) (value >>> 40);
    target[index + 6] = (byte) (value >>> 48);
    target[index + 7] = (byte) (value >>> 56);
  }

  static void unsafePutInt64(MemoryBuffer buffer, int index, long value) {
    putInt64(buffer.heapMemory, heapIndex(buffer, index), value);
  }

  static float getFloat32(MemoryBuffer buffer, int index) {
    checkPosition(buffer, index, 4);
    return getFloat32(buffer.heapMemory, heapIndex(buffer, index));
  }

  static float getFloat32(byte[] source, int index) {
    return Float.intBitsToFloat(getInt32(source, index));
  }

  static void putFloat32(MemoryBuffer buffer, int index, float value) {
    checkPosition(buffer, index, 4);
    putFloat32(buffer.heapMemory, heapIndex(buffer, index), value);
  }

  static void putFloat32(byte[] target, int index, float value) {
    putInt32(target, index, Float.floatToRawIntBits(value));
  }

  static double getFloat64(MemoryBuffer buffer, int index) {
    checkPosition(buffer, index, 8);
    return getFloat64(buffer.heapMemory, heapIndex(buffer, index));
  }

  static double getFloat64(byte[] source, int index) {
    return Double.longBitsToDouble(getInt64(source, index));
  }

  static void putFloat64(MemoryBuffer buffer, int index, double value) {
    checkPosition(buffer, index, 8);
    putFloat64(buffer.heapMemory, heapIndex(buffer, index), value);
  }

  static void putFloat64(byte[] target, int index, double value) {
    putInt64(target, index, Double.doubleToRawLongBits(value));
  }

  static void writeBoolean(MemoryBuffer buffer, boolean value) {
    int writerIdx = buffer.writerIndex;
    int newIdx = writerIdx + 1;
    buffer.ensure(newIdx);
    putBoolean(buffer.heapMemory, heapIndex(buffer, writerIdx), value);
    buffer.writerIndex = newIdx;
  }

  static void unsafeWriteByte(MemoryBuffer buffer, byte value) {
    int writerIdx = buffer.writerIndex;
    putByte(buffer.heapMemory, heapIndex(buffer, writerIdx), value);
    buffer.writerIndex = writerIdx + 1;
  }

  static void writeByte(MemoryBuffer buffer, byte value) {
    int writerIdx = buffer.writerIndex;
    int newIdx = writerIdx + 1;
    buffer.ensure(newIdx);
    putByte(buffer.heapMemory, heapIndex(buffer, writerIdx), value);
    buffer.writerIndex = newIdx;
  }

  static void writeChar(MemoryBuffer buffer, char value) {
    int writerIdx = buffer.writerIndex;
    int newIdx = writerIdx + 2;
    buffer.ensure(newIdx);
    putInt16(buffer.heapMemory, heapIndex(buffer, writerIdx), (short) value);
    buffer.writerIndex = newIdx;
  }

  static void writeInt16(MemoryBuffer buffer, short value) {
    int writerIdx = buffer.writerIndex;
    int newIdx = writerIdx + 2;
    buffer.ensure(newIdx);
    putInt16(buffer.heapMemory, heapIndex(buffer, writerIdx), value);
    buffer.writerIndex = newIdx;
  }

  static void writeInt32(MemoryBuffer buffer, int value) {
    int writerIdx = buffer.writerIndex;
    int newIdx = writerIdx + 4;
    buffer.ensure(newIdx);
    putInt32(buffer.heapMemory, heapIndex(buffer, writerIdx), value);
    buffer.writerIndex = newIdx;
  }

  static void writeInt64(MemoryBuffer buffer, long value) {
    int writerIdx = buffer.writerIndex;
    int newIdx = writerIdx + 8;
    buffer.ensure(newIdx);
    putInt64(buffer.heapMemory, heapIndex(buffer, writerIdx), value);
    buffer.writerIndex = newIdx;
  }

  static void writeFloat32(MemoryBuffer buffer, float value) {
    int writerIdx = buffer.writerIndex;
    int newIdx = writerIdx + 4;
    buffer.ensure(newIdx);
    putFloat32(buffer.heapMemory, heapIndex(buffer, writerIdx), value);
    buffer.writerIndex = newIdx;
  }

  static void writeFloat64(MemoryBuffer buffer, double value) {
    int writerIdx = buffer.writerIndex;
    int newIdx = writerIdx + 8;
    buffer.ensure(newIdx);
    putFloat64(buffer.heapMemory, heapIndex(buffer, writerIdx), value);
    buffer.writerIndex = newIdx;
  }

  static int writeVarInt32(MemoryBuffer buffer, int value) {
    buffer.ensure(buffer.writerIndex + 8);
    return unsafeWriteVarInt32(buffer, value);
  }

  static int unsafeWriteVarInt32(MemoryBuffer buffer, int value) {
    return unsafeWriteVarUInt32(buffer, (value << 1) ^ (value >> 31));
  }

  static int writeVarUInt32(MemoryBuffer buffer, int value) {
    buffer.ensure(buffer.writerIndex + 8);
    return unsafeWriteVarUInt32(buffer, value);
  }

  static int unsafeWriteVarUInt32(MemoryBuffer buffer, int value) {
    int writerIdx = buffer.writerIndex;
    int varintBytes = putVarUInt32(buffer.heapMemory, heapIndex(buffer, writerIdx), value);
    buffer.writerIndex = writerIdx + varintBytes;
    return varintBytes;
  }

  static int writeVarUInt32Small7(MemoryBuffer buffer, int value) {
    buffer.ensure(buffer.writerIndex + 8);
    int writerIdx = buffer.writerIndex;
    int varintBytes = putVarUInt32Small7(buffer.heapMemory, heapIndex(buffer, writerIdx), value);
    buffer.writerIndex = writerIdx + varintBytes;
    return varintBytes;
  }

  static int unsafePutVarUInt32(MemoryBuffer buffer, int index, int value) {
    return putVarUInt32(buffer.heapMemory, heapIndex(buffer, index), value);
  }

  static int unsafePutVarUint36Small(MemoryBuffer buffer, int index, long value) {
    return putVarUint36Small(buffer.heapMemory, heapIndex(buffer, index), value);
  }

  static int writeVarUInt32Aligned(MemoryBuffer buffer, int value) {
    int writerIdx = buffer.writerIndex;
    buffer.ensure(writerIdx + 10);
    byte[] heapMemory = buffer.heapMemory;
    int heapIdx = heapIndex(buffer, writerIdx);
    int dataBytes = 1;
    int shifted = value >>> 6;
    while (shifted != 0) {
      dataBytes++;
      shifted >>>= 6;
    }
    for (int i = 0; i < dataBytes; i++) {
      int b = (value >>> (i * 6)) & 0x3F;
      if (i != dataBytes - 1) {
        b |= 0x80;
      }
      putByte(heapMemory, heapIdx + i, (byte) b);
    }
    int paddingBytes = (4 - ((writerIdx + dataBytes) & 0x3)) & 0x3;
    int bytesWritten = dataBytes + paddingBytes;
    if (paddingBytes == 0) {
      putByte(
          heapMemory,
          heapIdx + dataBytes - 1,
          (byte) (getByte(heapMemory, heapIdx + dataBytes - 1) | 0x40));
    } else {
      for (int i = 0; i < paddingBytes - 1; i++) {
        putByte(heapMemory, heapIdx + dataBytes + i, (byte) 0);
      }
      putByte(heapMemory, heapIdx + bytesWritten - 1, (byte) 0x40);
    }
    buffer.writerIndex = writerIdx + bytesWritten;
    return bytesWritten;
  }

  static int writeVarInt64(MemoryBuffer buffer, long value) {
    buffer.ensure(buffer.writerIndex + 9);
    return unsafeWriteVarUInt64(buffer, (value << 1) ^ (value >> 63));
  }

  static int unsafeWriteVarInt64(MemoryBuffer buffer, long value) {
    return unsafeWriteVarUInt64(buffer, (value << 1) ^ (value >> 63));
  }

  static int writeVarUInt64(MemoryBuffer buffer, long value) {
    buffer.ensure(buffer.writerIndex + 9);
    return unsafeWriteVarUInt64(buffer, value);
  }

  static int unsafeWriteVarUInt64(MemoryBuffer buffer, long value) {
    int writerIdx = buffer.writerIndex;
    int varintBytes = putVarUInt64(buffer.heapMemory, heapIndex(buffer, writerIdx), value);
    buffer.writerIndex = writerIdx + varintBytes;
    return varintBytes;
  }

  static int unsafeWriteTaggedUInt64(MemoryBuffer buffer, long value) {
    int writerIdx = buffer.writerIndex;
    int heapIndex = heapIndex(buffer, writerIdx);
    if (value >= 0 && value <= Integer.MAX_VALUE) {
      putInt32(buffer.heapMemory, heapIndex, ((int) value) << 1);
      buffer.writerIndex = writerIdx + 4;
      return 4;
    }
    putByte(buffer.heapMemory, heapIndex, (byte) 1);
    putInt64(buffer.heapMemory, heapIndex + 1, value);
    buffer.writerIndex = writerIdx + 9;
    return 9;
  }

  static int unsafeWriteTaggedInt64(MemoryBuffer buffer, long value) {
    int writerIdx = buffer.writerIndex;
    int heapIndex = heapIndex(buffer, writerIdx);
    if (value >= Integer.MIN_VALUE / 2L && value <= Integer.MAX_VALUE / 2L) {
      putInt32(buffer.heapMemory, heapIndex, ((int) value) << 1);
      buffer.writerIndex = writerIdx + 4;
      return 4;
    }
    putByte(buffer.heapMemory, heapIndex, (byte) 1);
    putInt64(buffer.heapMemory, heapIndex + 1, value);
    buffer.writerIndex = writerIdx + 9;
    return 9;
  }

  static void writeBooleans(MemoryBuffer buffer, boolean[] values, int offset, int numElements) {
    int writerIdx = buffer.writerIndex;
    int newIdx = writerIdx + numElements;
    buffer.ensure(newIdx);
    writeBooleans(buffer.heapMemory, heapIndex(buffer, writerIdx), values, offset, numElements);
    buffer.writerIndex = newIdx;
  }

  static void writeBooleans(
      byte[] target, int targetIndex, boolean[] source, int sourceIndex, int length) {
    for (int i = 0; i < length; i++) {
      target[targetIndex + i] = (byte) (source[sourceIndex + i] ? 1 : 0);
    }
  }

  static void writeBytesWithSize(MemoryBuffer buffer, byte[] values) {
    writeVarUInt32Small7(buffer, values.length);
    int writerIdx = buffer.writerIndex;
    int newIdx = writerIdx + values.length;
    buffer.ensure(newIdx);
    System.arraycopy(values, 0, buffer.heapMemory, heapIndex(buffer, writerIdx), values.length);
    buffer.writerIndex = newIdx;
  }

  static void writeBooleansWithSize(MemoryBuffer buffer, boolean[] values) {
    writeVarUInt32Small7(buffer, values.length);
    writeBooleans(buffer, values, 0, values.length);
  }

  static void writeCharsWithSize(MemoryBuffer buffer, char[] values) {
    int numBytes = Math.multiplyExact(values.length, 2);
    writeVarUInt32Small7(buffer, numBytes);
    writeChars(buffer, values, 0, values.length);
  }

  static void writeShortsWithSize(MemoryBuffer buffer, short[] values) {
    int numBytes = Math.multiplyExact(values.length, 2);
    writeVarUInt32Small7(buffer, numBytes);
    writeShorts(buffer, values, 0, values.length);
  }

  static void writeIntsWithSize(MemoryBuffer buffer, int[] values) {
    int numBytes = Math.multiplyExact(values.length, 4);
    writeVarUInt32Small7(buffer, numBytes);
    writeInts(buffer, values, 0, values.length);
  }

  static void writeLongsWithSize(MemoryBuffer buffer, long[] values) {
    int numBytes = Math.multiplyExact(values.length, 8);
    writeVarUInt32Small7(buffer, numBytes);
    writeLongs(buffer, values, 0, values.length);
  }

  static void writeFloatsWithSize(MemoryBuffer buffer, float[] values) {
    int numBytes = Math.multiplyExact(values.length, 4);
    writeVarUInt32Small7(buffer, numBytes);
    writeFloats(buffer, values, 0, values.length);
  }

  static void writeDoublesWithSize(MemoryBuffer buffer, double[] values) {
    int numBytes = Math.multiplyExact(values.length, 8);
    writeVarUInt32Small7(buffer, numBytes);
    writeDoubles(buffer, values, 0, values.length);
  }

  static void writeChars(MemoryBuffer buffer, char[] values, int offset, int numElements) {
    int numBytes = Math.multiplyExact(numElements, 2);
    int writerIdx = buffer.writerIndex;
    int newIdx = writerIdx + numBytes;
    buffer.ensure(newIdx);
    writeChars(buffer.heapMemory, heapIndex(buffer, writerIdx), values, offset, numElements);
    buffer.writerIndex = newIdx;
  }

  static void writeChars(
      byte[] target, int targetIndex, char[] source, int sourceIndex, int length) {
    for (int i = 0; i < length; i++) {
      putInt16(target, targetIndex + i * 2, (short) source[sourceIndex + i]);
    }
  }

  static void writeShorts(MemoryBuffer buffer, short[] values, int offset, int numElements) {
    int numBytes = Math.multiplyExact(numElements, 2);
    int writerIdx = buffer.writerIndex;
    int newIdx = writerIdx + numBytes;
    buffer.ensure(newIdx);
    writeShorts(buffer.heapMemory, heapIndex(buffer, writerIdx), values, offset, numElements);
    buffer.writerIndex = newIdx;
  }

  static void writeShorts(
      byte[] target, int targetIndex, short[] source, int sourceIndex, int length) {
    for (int i = 0; i < length; i++) {
      putInt16(target, targetIndex + i * 2, source[sourceIndex + i]);
    }
  }

  static void writeInts(MemoryBuffer buffer, int[] values, int offset, int numElements) {
    int numBytes = Math.multiplyExact(numElements, 4);
    int writerIdx = buffer.writerIndex;
    int newIdx = writerIdx + numBytes;
    buffer.ensure(newIdx);
    writeInts(buffer.heapMemory, heapIndex(buffer, writerIdx), values, offset, numElements);
    buffer.writerIndex = newIdx;
  }

  static void writeInts(byte[] target, int targetIndex, int[] source, int sourceIndex, int length) {
    for (int i = 0; i < length; i++) {
      putInt32(target, targetIndex + i * 4, source[sourceIndex + i]);
    }
  }

  static void writeLongs(MemoryBuffer buffer, long[] values, int offset, int numElements) {
    int numBytes = Math.multiplyExact(numElements, 8);
    int writerIdx = buffer.writerIndex;
    int newIdx = writerIdx + numBytes;
    buffer.ensure(newIdx);
    writeLongs(buffer.heapMemory, heapIndex(buffer, writerIdx), values, offset, numElements);
    buffer.writerIndex = newIdx;
  }

  static void writeLongs(
      byte[] target, int targetIndex, long[] source, int sourceIndex, int length) {
    for (int i = 0; i < length; i++) {
      putInt64(target, targetIndex + i * 8, source[sourceIndex + i]);
    }
  }

  static void writeFloats(MemoryBuffer buffer, float[] values, int offset, int numElements) {
    int numBytes = Math.multiplyExact(numElements, 4);
    int writerIdx = buffer.writerIndex;
    int newIdx = writerIdx + numBytes;
    buffer.ensure(newIdx);
    writeFloats(buffer.heapMemory, heapIndex(buffer, writerIdx), values, offset, numElements);
    buffer.writerIndex = newIdx;
  }

  static void writeFloats(
      byte[] target, int targetIndex, float[] source, int sourceIndex, int length) {
    for (int i = 0; i < length; i++) {
      putFloat32(target, targetIndex + i * 4, source[sourceIndex + i]);
    }
  }

  static void writeDoubles(MemoryBuffer buffer, double[] values, int offset, int numElements) {
    int numBytes = Math.multiplyExact(numElements, 8);
    int writerIdx = buffer.writerIndex;
    int newIdx = writerIdx + numBytes;
    buffer.ensure(newIdx);
    writeDoubles(buffer.heapMemory, heapIndex(buffer, writerIdx), values, offset, numElements);
    buffer.writerIndex = newIdx;
  }

  static void writeDoubles(
      byte[] target, int targetIndex, double[] source, int sourceIndex, int length) {
    for (int i = 0; i < length; i++) {
      putFloat64(target, targetIndex + i * 8, source[sourceIndex + i]);
    }
  }

  private static int prepareRead(MemoryBuffer buffer, int numBytes) {
    int readerIdx = buffer.readerIndex;
    int remaining = buffer.size - readerIdx;
    if (remaining < numBytes) {
      buffer.streamReader.fillBuffer(numBytes - remaining);
    }
    return readerIdx;
  }

  static byte readByte(MemoryBuffer buffer) {
    int readerIdx = prepareRead(buffer, 1);
    buffer.readerIndex = readerIdx + 1;
    return getByte(buffer.heapMemory, heapIndex(buffer, readerIdx));
  }

  static boolean readBoolean(MemoryBuffer buffer) {
    return readByte(buffer) != 0;
  }

  static int readUInt8(MemoryBuffer buffer) {
    return readByte(buffer) & 0b11111111;
  }

  static char readChar(MemoryBuffer buffer) {
    int readerIdx = prepareRead(buffer, 2);
    buffer.readerIndex = readerIdx + 2;
    return (char) getInt16(buffer.heapMemory, heapIndex(buffer, readerIdx));
  }

  static short readInt16(MemoryBuffer buffer) {
    int readerIdx = prepareRead(buffer, 2);
    buffer.readerIndex = readerIdx + 2;
    return getInt16(buffer.heapMemory, heapIndex(buffer, readerIdx));
  }

  static int readInt32(MemoryBuffer buffer) {
    int readerIdx = prepareRead(buffer, 4);
    buffer.readerIndex = readerIdx + 4;
    return getInt32(buffer.heapMemory, heapIndex(buffer, readerIdx));
  }

  static long readInt64(MemoryBuffer buffer) {
    int readerIdx = prepareRead(buffer, 8);
    buffer.readerIndex = readerIdx + 8;
    return getInt64(buffer.heapMemory, heapIndex(buffer, readerIdx));
  }

  static long readTaggedUInt64(MemoryBuffer buffer) {
    int readIdx = buffer.readerIndex;
    ensureReadableFrom(buffer, readIdx, 4);
    int i = getInt32(buffer.heapMemory, heapIndex(buffer, readIdx));
    if ((i & 0b1) != 0b1) {
      buffer.readerIndex = readIdx + 4;
      return i >>> 1;
    }
    ensureReadableFrom(buffer, readIdx, 9);
    buffer.readerIndex = readIdx + 9;
    return getInt64(buffer.heapMemory, heapIndex(buffer, readIdx) + 1);
  }

  static long readTaggedInt64(MemoryBuffer buffer) {
    int readIdx = buffer.readerIndex;
    ensureReadableFrom(buffer, readIdx, 4);
    int i = getInt32(buffer.heapMemory, heapIndex(buffer, readIdx));
    if ((i & 0b1) != 0b1) {
      buffer.readerIndex = readIdx + 4;
      return i >> 1;
    }
    ensureReadableFrom(buffer, readIdx, 9);
    buffer.readerIndex = readIdx + 9;
    return getInt64(buffer.heapMemory, heapIndex(buffer, readIdx) + 1);
  }

  static float readFloat32(MemoryBuffer buffer) {
    return Float.intBitsToFloat(readInt32(buffer));
  }

  static double readFloat64(MemoryBuffer buffer) {
    return Double.longBitsToDouble(readInt64(buffer));
  }

  static int readVarInt32(MemoryBuffer buffer) {
    int result = readVarUInt32(buffer);
    return (result >>> 1) ^ -(result & 1);
  }

  static int readVarInt32(byte[] source, int index) {
    int result = readVarUInt32(source, index);
    return (result >>> 1) ^ -(result & 1);
  }

  static int readVarUInt32(MemoryBuffer buffer) {
    int readIdx = buffer.readerIndex;
    if (buffer.size - readIdx < 5) {
      return readVarUInt32Slow(buffer);
    }
    int heapIndex = heapIndex(buffer, readIdx);
    int result = readVarUInt32(buffer.heapMemory, heapIndex);
    buffer.readerIndex = readIdx + varUInt32Bytes(buffer.heapMemory, heapIndex);
    return result;
  }

  static int readVarUInt32(byte[] source, int index) {
    int result = 0;
    int shift = 0;
    for (int i = 0; i < 4; i++) {
      int b = source[index + i] & 0xFF;
      result |= (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
    int fifthByte = source[index + 4] & 0xFF;
    if ((fifthByte & 0xF0) != 0) {
      throwMalformedVarUInt32(fifthByte);
    }
    return result | (fifthByte << 28);
  }

  static long readVarUint36Small(MemoryBuffer buffer) {
    int readIdx = buffer.readerIndex;
    if (buffer.size - readIdx < 5) {
      return readVarUint36Slow(buffer);
    }
    int heapIndex = heapIndex(buffer, readIdx);
    long result = readVarUint36Small(buffer.heapMemory, heapIndex);
    buffer.readerIndex = readIdx + varUint36SmallBytes(buffer.heapMemory, heapIndex);
    return result;
  }

  static long readVarUint36Small(byte[] source, int index) {
    long result = 0;
    int shift = 0;
    for (int i = 0; i < 4; i++) {
      int b = source[index + i] & 0xFF;
      result |= (long) (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
    return result | ((long) source[index + 4] & 0xFF) << 28;
  }

  static long readVarInt64(MemoryBuffer buffer) {
    long result = readVarUInt64(buffer);
    return (result >>> 1) ^ -(result & 1);
  }

  static long readVarInt64(byte[] source, int index) {
    long result = readVarUInt64(source, index);
    return (result >>> 1) ^ -(result & 1);
  }

  static long readVarUInt64(MemoryBuffer buffer) {
    int readIdx = buffer.readerIndex;
    if (buffer.size - readIdx < 9) {
      return readVarUInt64Slow(buffer);
    }
    int heapIndex = heapIndex(buffer, readIdx);
    long result = readVarUInt64(buffer.heapMemory, heapIndex);
    buffer.readerIndex = readIdx + varUInt64Bytes(buffer.heapMemory, heapIndex);
    return result;
  }

  static long readVarUInt64(byte[] source, int index) {
    long result = 0;
    int shift = 0;
    for (int i = 0; i < 8; i++) {
      int b = source[index + i] & 0xFF;
      result |= (long) (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
    return result | ((long) source[index + 8] & 0xFF) << 56;
  }

  static int readAlignedVarUInt32(MemoryBuffer buffer) {
    int b = readByte(buffer);
    int result = b & 0x3F;
    if ((b & 0x80) != 0) {
      b = readByte(buffer);
      result |= (b & 0x3F) << 6;
      if ((b & 0x80) != 0) {
        b = readByte(buffer);
        result |= (b & 0x3F) << 12;
        if ((b & 0x80) != 0) {
          b = readByte(buffer);
          result |= (b & 0x3F) << 18;
          if ((b & 0x80) != 0) {
            b = readByte(buffer);
            result |= (b & 0x3F) << 24;
            if ((b & 0x80) != 0) {
              b = readByte(buffer);
              result |= (b & 0x3F) << 30;
            }
          }
        }
      }
    }
    if ((b & 0x40) == 0) {
      b = readByte(buffer);
      if ((b & 0x40) == 0) {
        b = readByte(buffer);
        if ((b & 0x40) == 0) {
          b = readByte(buffer);
          checkArgument((b & 0x40) != 0, "At most 3 padding bytes.");
        }
      }
    }
    return result;
  }

  static long readBytesAsInt64(MemoryBuffer buffer, int len) {
    int readerIdx = buffer.readerIndex;
    int remaining = buffer.size - readerIdx;
    if (remaining >= 8) {
      buffer.readerIndex = readerIdx + len;
      return getInt64(buffer.heapMemory, heapIndex(buffer, readerIdx))
          & (0xffffffffffffffffL >>> ((8 - len) * 8));
    }
    if (remaining < len) {
      buffer.streamReader.fillBuffer(len - remaining);
    }
    readerIdx = buffer.readerIndex;
    buffer.readerIndex = readerIdx + len;
    long result = 0;
    int start = heapIndex(buffer, readerIdx);
    for (int i = 0; i < len; i++) {
      result |= (((long) buffer.heapMemory[start + i]) & 0xff) << (i * 8);
    }
    return result;
  }

  static int readBinarySize(MemoryBuffer buffer) {
    int binarySize = readVarUInt32(buffer);
    int diff = buffer.size - buffer.readerIndex;
    if (diff < binarySize) {
      buffer.streamReader.fillBuffer(binarySize - diff);
    }
    return binarySize;
  }

  static void readByteArrayBytes(MemoryBuffer buffer, byte[] values, int numBytes) {
    if (buffer.readerIndex > buffer.size - numBytes) {
      buffer.streamReader.readTo(values, 0, numBytes);
      return;
    }
    int readerIdx = buffer.readerIndex;
    System.arraycopy(buffer.heapMemory, heapIndex(buffer, readerIdx), values, 0, numBytes);
    buffer.readerIndex = readerIdx + numBytes;
  }

  static void readBooleanArrayBytes(MemoryBuffer buffer, boolean[] values, int numBytes) {
    int readerIdx = buffer.readerIndex;
    if (readerIdx > buffer.size - numBytes) {
      buffer.streamReader.readBooleans(values, 0, numBytes);
      return;
    }
    byte[] source = buffer.heapMemory;
    int sourceIndex = heapIndex(buffer, readerIdx);
    for (int i = 0; i < numBytes; i++) {
      values[i] = source[sourceIndex + i] != 0;
    }
    buffer.readerIndex = readerIdx + numBytes;
  }

  static void readCharArrayBytes(MemoryBuffer buffer, char[] values, int numBytes) {
    int readerIdx = buffer.readerIndex;
    if (readerIdx > buffer.size - numBytes) {
      buffer.streamReader.readChars(values, 0, numBytes >>> 1);
      return;
    }
    byte[] source = buffer.heapMemory;
    int sourceIndex = heapIndex(buffer, readerIdx);
    int end = sourceIndex + numBytes;
    for (int i = 0; sourceIndex < end; i++, sourceIndex += 2) {
      values[i] = (char) ((source[sourceIndex] & 0xFF) | (source[sourceIndex + 1] << 8));
    }
    buffer.readerIndex = readerIdx + numBytes;
  }

  static void readInt16ArrayBytes(MemoryBuffer buffer, short[] values, int numBytes) {
    int readerIdx = buffer.readerIndex;
    if (readerIdx > buffer.size - numBytes) {
      buffer.streamReader.readShorts(values, 0, numBytes >>> 1);
      return;
    }
    byte[] source = buffer.heapMemory;
    int sourceIndex = heapIndex(buffer, readerIdx);
    int end = sourceIndex + numBytes;
    for (int i = 0; sourceIndex < end; i++, sourceIndex += 2) {
      values[i] = (short) ((source[sourceIndex] & 0xFF) | (source[sourceIndex + 1] << 8));
    }
    buffer.readerIndex = readerIdx + numBytes;
  }

  static void readInt32ArrayBytes(MemoryBuffer buffer, int[] values, int numBytes) {
    int readerIdx = buffer.readerIndex;
    if (readerIdx > buffer.size - numBytes) {
      buffer.streamReader.readInts(values, 0, numBytes >>> 2);
      return;
    }
    byte[] source = buffer.heapMemory;
    int sourceIndex = heapIndex(buffer, readerIdx);
    int end = sourceIndex + numBytes;
    for (int i = 0; sourceIndex < end; i++, sourceIndex += 4) {
      values[i] =
          (source[sourceIndex] & 0xFF)
              | ((source[sourceIndex + 1] & 0xFF) << 8)
              | ((source[sourceIndex + 2] & 0xFF) << 16)
              | (source[sourceIndex + 3] << 24);
    }
    buffer.readerIndex = readerIdx + numBytes;
  }

  static void readInt64ArrayBytes(MemoryBuffer buffer, long[] values, int numBytes) {
    int readerIdx = buffer.readerIndex;
    if (readerIdx > buffer.size - numBytes) {
      buffer.streamReader.readLongs(values, 0, numBytes >>> 3);
      return;
    }
    byte[] source = buffer.heapMemory;
    int sourceIndex = heapIndex(buffer, readerIdx);
    int end = sourceIndex + numBytes;
    for (int i = 0; sourceIndex < end; i++, sourceIndex += 8) {
      values[i] =
          ((long) source[sourceIndex] & 0xFF)
              | (((long) source[sourceIndex + 1] & 0xFF) << 8)
              | (((long) source[sourceIndex + 2] & 0xFF) << 16)
              | (((long) source[sourceIndex + 3] & 0xFF) << 24)
              | (((long) source[sourceIndex + 4] & 0xFF) << 32)
              | (((long) source[sourceIndex + 5] & 0xFF) << 40)
              | (((long) source[sourceIndex + 6] & 0xFF) << 48)
              | ((long) source[sourceIndex + 7] << 56);
    }
    buffer.readerIndex = readerIdx + numBytes;
  }

  static void readFloat32ArrayBytes(MemoryBuffer buffer, float[] values, int numBytes) {
    int readerIdx = buffer.readerIndex;
    if (readerIdx > buffer.size - numBytes) {
      buffer.streamReader.readFloats(values, 0, numBytes >>> 2);
      return;
    }
    byte[] source = buffer.heapMemory;
    int sourceIndex = heapIndex(buffer, readerIdx);
    int end = sourceIndex + numBytes;
    for (int i = 0; sourceIndex < end; i++, sourceIndex += 4) {
      values[i] =
          Float.intBitsToFloat(
              (source[sourceIndex] & 0xFF)
                  | ((source[sourceIndex + 1] & 0xFF) << 8)
                  | ((source[sourceIndex + 2] & 0xFF) << 16)
                  | (source[sourceIndex + 3] << 24));
    }
    buffer.readerIndex = readerIdx + numBytes;
  }

  static void readFloat64ArrayBytes(MemoryBuffer buffer, double[] values, int numBytes) {
    int readerIdx = buffer.readerIndex;
    if (readerIdx > buffer.size - numBytes) {
      buffer.streamReader.readDoubles(values, 0, numBytes >>> 3);
      return;
    }
    byte[] source = buffer.heapMemory;
    int sourceIndex = heapIndex(buffer, readerIdx);
    int end = sourceIndex + numBytes;
    for (int i = 0; sourceIndex < end; i++, sourceIndex += 8) {
      values[i] =
          Double.longBitsToDouble(
              ((long) source[sourceIndex] & 0xFF)
                  | (((long) source[sourceIndex + 1] & 0xFF) << 8)
                  | (((long) source[sourceIndex + 2] & 0xFF) << 16)
                  | (((long) source[sourceIndex + 3] & 0xFF) << 24)
                  | (((long) source[sourceIndex + 4] & 0xFF) << 32)
                  | (((long) source[sourceIndex + 5] & 0xFF) << 40)
                  | (((long) source[sourceIndex + 6] & 0xFF) << 48)
                  | ((long) source[sourceIndex + 7] << 56));
    }
    buffer.readerIndex = readerIdx + numBytes;
  }

  static void readBooleans(MemoryBuffer buffer, boolean[] values, int offset, int numElements) {
    if ((offset | numElements | (offset + numElements) | (values.length - offset - numElements))
        < 0) {
      throwOOBException(buffer);
    }
    int readerIdx = buffer.readerIndex;
    if (readerIdx > buffer.size - numElements) {
      buffer.streamReader.readBooleans(values, offset, numElements);
      return;
    }
    byte[] source = buffer.heapMemory;
    int sourceIndex = heapIndex(buffer, readerIdx);
    int end = offset + numElements;
    for (int i = offset; i < end; i++) {
      values[i] = source[sourceIndex++] != 0;
    }
    buffer.readerIndex = readerIdx + numElements;
  }

  static void readChars(MemoryBuffer buffer, char[] values, int offset, int numElements) {
    if ((offset | numElements | (offset + numElements) | (values.length - offset - numElements))
        < 0) {
      throwOOBException(buffer);
    }
    int numBytes = Math.multiplyExact(numElements, 2);
    int readerIdx = buffer.readerIndex;
    if (readerIdx > buffer.size - numBytes) {
      buffer.streamReader.readChars(values, offset, numElements);
      return;
    }
    byte[] source = buffer.heapMemory;
    int sourceIndex = heapIndex(buffer, readerIdx);
    int end = offset + numElements;
    for (int i = offset; i < end; i++, sourceIndex += 2) {
      values[i] = (char) ((source[sourceIndex] & 0xFF) | (source[sourceIndex + 1] << 8));
    }
    buffer.readerIndex = readerIdx + numBytes;
  }

  static void readShorts(MemoryBuffer buffer, short[] values, int offset, int numElements) {
    if ((offset | numElements | (offset + numElements) | (values.length - offset - numElements))
        < 0) {
      throwOOBException(buffer);
    }
    int numBytes = Math.multiplyExact(numElements, 2);
    int readerIdx = buffer.readerIndex;
    if (readerIdx > buffer.size - numBytes) {
      buffer.streamReader.readShorts(values, offset, numElements);
      return;
    }
    byte[] source = buffer.heapMemory;
    int sourceIndex = heapIndex(buffer, readerIdx);
    int end = offset + numElements;
    for (int i = offset; i < end; i++, sourceIndex += 2) {
      values[i] = (short) ((source[sourceIndex] & 0xFF) | (source[sourceIndex + 1] << 8));
    }
    buffer.readerIndex = readerIdx + numBytes;
  }

  static void readInts(MemoryBuffer buffer, int[] values, int offset, int numElements) {
    if ((offset | numElements | (offset + numElements) | (values.length - offset - numElements))
        < 0) {
      throwOOBException(buffer);
    }
    int numBytes = Math.multiplyExact(numElements, 4);
    int readerIdx = buffer.readerIndex;
    if (readerIdx > buffer.size - numBytes) {
      buffer.streamReader.readInts(values, offset, numElements);
      return;
    }
    byte[] source = buffer.heapMemory;
    int sourceIndex = heapIndex(buffer, readerIdx);
    int end = offset + numElements;
    for (int i = offset; i < end; i++, sourceIndex += 4) {
      values[i] =
          (source[sourceIndex] & 0xFF)
              | ((source[sourceIndex + 1] & 0xFF) << 8)
              | ((source[sourceIndex + 2] & 0xFF) << 16)
              | (source[sourceIndex + 3] << 24);
    }
    buffer.readerIndex = readerIdx + numBytes;
  }

  static void readLongs(MemoryBuffer buffer, long[] values, int offset, int numElements) {
    if ((offset | numElements | (offset + numElements) | (values.length - offset - numElements))
        < 0) {
      throwOOBException(buffer);
    }
    int numBytes = Math.multiplyExact(numElements, 8);
    int readerIdx = buffer.readerIndex;
    if (readerIdx > buffer.size - numBytes) {
      buffer.streamReader.readLongs(values, offset, numElements);
      return;
    }
    byte[] source = buffer.heapMemory;
    int sourceIndex = heapIndex(buffer, readerIdx);
    int end = offset + numElements;
    for (int i = offset; i < end; i++, sourceIndex += 8) {
      values[i] =
          ((long) source[sourceIndex] & 0xFF)
              | (((long) source[sourceIndex + 1] & 0xFF) << 8)
              | (((long) source[sourceIndex + 2] & 0xFF) << 16)
              | (((long) source[sourceIndex + 3] & 0xFF) << 24)
              | (((long) source[sourceIndex + 4] & 0xFF) << 32)
              | (((long) source[sourceIndex + 5] & 0xFF) << 40)
              | (((long) source[sourceIndex + 6] & 0xFF) << 48)
              | ((long) source[sourceIndex + 7] << 56);
    }
    buffer.readerIndex = readerIdx + numBytes;
  }

  static void readFloats(MemoryBuffer buffer, float[] values, int offset, int numElements) {
    if ((offset | numElements | (offset + numElements) | (values.length - offset - numElements))
        < 0) {
      throwOOBException(buffer);
    }
    int numBytes = Math.multiplyExact(numElements, 4);
    int readerIdx = buffer.readerIndex;
    if (readerIdx > buffer.size - numBytes) {
      buffer.streamReader.readFloats(values, offset, numElements);
      return;
    }
    byte[] source = buffer.heapMemory;
    int sourceIndex = heapIndex(buffer, readerIdx);
    int end = offset + numElements;
    for (int i = offset; i < end; i++, sourceIndex += 4) {
      values[i] =
          Float.intBitsToFloat(
              (source[sourceIndex] & 0xFF)
                  | ((source[sourceIndex + 1] & 0xFF) << 8)
                  | ((source[sourceIndex + 2] & 0xFF) << 16)
                  | (source[sourceIndex + 3] << 24));
    }
    buffer.readerIndex = readerIdx + numBytes;
  }

  static void readDoubles(MemoryBuffer buffer, double[] values, int offset, int numElements) {
    if ((offset | numElements | (offset + numElements) | (values.length - offset - numElements))
        < 0) {
      throwOOBException(buffer);
    }
    int numBytes = Math.multiplyExact(numElements, 8);
    int readerIdx = buffer.readerIndex;
    if (readerIdx > buffer.size - numBytes) {
      buffer.streamReader.readDoubles(values, offset, numElements);
      return;
    }
    byte[] source = buffer.heapMemory;
    int sourceIndex = heapIndex(buffer, readerIdx);
    int end = offset + numElements;
    for (int i = offset; i < end; i++, sourceIndex += 8) {
      values[i] =
          Double.longBitsToDouble(
              ((long) source[sourceIndex] & 0xFF)
                  | (((long) source[sourceIndex + 1] & 0xFF) << 8)
                  | (((long) source[sourceIndex + 2] & 0xFF) << 16)
                  | (((long) source[sourceIndex + 3] & 0xFF) << 24)
                  | (((long) source[sourceIndex + 4] & 0xFF) << 32)
                  | (((long) source[sourceIndex + 5] & 0xFF) << 40)
                  | (((long) source[sourceIndex + 6] & 0xFF) << 48)
                  | ((long) source[sourceIndex + 7] << 56));
    }
    buffer.readerIndex = readerIdx + numBytes;
  }

  static void copyTo(
      MemoryBuffer source, int offset, MemoryBuffer target, int targetOffset, int numBytes) {
    if (source.heapMemory != null
        && target.heapMemory != null
        && (numBytes | offset | targetOffset) >= 0
        && offset <= source.size - numBytes
        && targetOffset <= target.size - numBytes) {
      copy(
          source.heapMemory,
          heapIndex(source, offset),
          target.heapMemory,
          heapIndex(target, targetOffset),
          numBytes);
      return;
    }
    throw new IndexOutOfBoundsException(
        String.format(
            "offset=%d, targetOffset=%d, numBytes=%d, size=%d, targetSize=%d",
            offset, targetOffset, numBytes, source.size, target.size));
  }

  static void copyToByteArray(
      MemoryBuffer source, int offset, byte[] target, int targetOffset, int numBytes) {
    checkArrayCopy(source, offset, targetOffset, target.length, numBytes, 0);
    copy(source.heapMemory, heapIndex(source, offset), target, targetOffset, numBytes);
  }

  static void copyToBooleanArray(
      MemoryBuffer source, int offset, boolean[] target, int targetOffset, int numBytes) {
    checkArrayCopy(source, offset, targetOffset, target.length, numBytes, 0);
    byte[] bytes = source.heapMemory;
    int sourceIndex = heapIndex(source, offset);
    for (int i = 0; i < numBytes; i++) {
      target[targetOffset + i] = bytes[sourceIndex + i] != 0;
    }
  }

  static void copyToCharArray(
      MemoryBuffer source, int offset, char[] target, int targetOffset, int numBytes) {
    checkArrayCopy(source, offset, targetOffset, target.length, numBytes, 1);
    byte[] bytes = source.heapMemory;
    int sourceIndex = heapIndex(source, offset);
    int numElements = numBytes >>> 1;
    for (int i = 0; i < numElements; i++, sourceIndex += 2) {
      target[targetOffset + i] = (char) getInt16(bytes, sourceIndex);
    }
  }

  static void copyToShortArray(
      MemoryBuffer source, int offset, short[] target, int targetOffset, int numBytes) {
    checkArrayCopy(source, offset, targetOffset, target.length, numBytes, 1);
    byte[] bytes = source.heapMemory;
    int sourceIndex = heapIndex(source, offset);
    int numElements = numBytes >>> 1;
    for (int i = 0; i < numElements; i++, sourceIndex += 2) {
      target[targetOffset + i] = getInt16(bytes, sourceIndex);
    }
  }

  static void copyToIntArray(
      MemoryBuffer source, int offset, int[] target, int targetOffset, int numBytes) {
    checkArrayCopy(source, offset, targetOffset, target.length, numBytes, 2);
    byte[] bytes = source.heapMemory;
    int sourceIndex = heapIndex(source, offset);
    int numElements = numBytes >>> 2;
    for (int i = 0; i < numElements; i++, sourceIndex += 4) {
      target[targetOffset + i] = getInt32(bytes, sourceIndex);
    }
  }

  static void copyToLongArray(
      MemoryBuffer source, int offset, long[] target, int targetOffset, int numBytes) {
    checkArrayCopy(source, offset, targetOffset, target.length, numBytes, 3);
    byte[] bytes = source.heapMemory;
    int sourceIndex = heapIndex(source, offset);
    int numElements = numBytes >>> 3;
    for (int i = 0; i < numElements; i++, sourceIndex += 8) {
      target[targetOffset + i] = getInt64(bytes, sourceIndex);
    }
  }

  static void copyToFloatArray(
      MemoryBuffer source, int offset, float[] target, int targetOffset, int numBytes) {
    checkArrayCopy(source, offset, targetOffset, target.length, numBytes, 2);
    byte[] bytes = source.heapMemory;
    int sourceIndex = heapIndex(source, offset);
    int numElements = numBytes >>> 2;
    for (int i = 0; i < numElements; i++, sourceIndex += 4) {
      target[targetOffset + i] = getFloat32(bytes, sourceIndex);
    }
  }

  static void copyToDoubleArray(
      MemoryBuffer source, int offset, double[] target, int targetOffset, int numBytes) {
    checkArrayCopy(source, offset, targetOffset, target.length, numBytes, 3);
    byte[] bytes = source.heapMemory;
    int sourceIndex = heapIndex(source, offset);
    int numElements = numBytes >>> 3;
    for (int i = 0; i < numElements; i++, sourceIndex += 8) {
      target[targetOffset + i] = getFloat64(bytes, sourceIndex);
    }
  }

  static void copyFromByteArray(
      MemoryBuffer target, int offset, byte[] source, int sourceOffset, int numBytes) {
    checkArrayCopy(target, offset, sourceOffset, source.length, numBytes, 0);
    copy(source, sourceOffset, target.heapMemory, heapIndex(target, offset), numBytes);
  }

  static void copyFromBooleanArray(
      MemoryBuffer target, int offset, boolean[] source, int sourceOffset, int numBytes) {
    checkArrayCopy(target, offset, sourceOffset, source.length, numBytes, 0);
    byte[] bytes = target.heapMemory;
    int targetIndex = heapIndex(target, offset);
    for (int i = 0; i < numBytes; i++) {
      bytes[targetIndex + i] = source[sourceOffset + i] ? (byte) 1 : (byte) 0;
    }
  }

  static void copyFromCharArray(
      MemoryBuffer target, int offset, char[] source, int sourceOffset, int numBytes) {
    checkArrayCopy(target, offset, sourceOffset, source.length, numBytes, 1);
    byte[] bytes = target.heapMemory;
    int targetIndex = heapIndex(target, offset);
    int numElements = numBytes >>> 1;
    for (int i = 0; i < numElements; i++, targetIndex += 2) {
      putInt16(bytes, targetIndex, (short) source[sourceOffset + i]);
    }
  }

  static void copyFromShortArray(
      MemoryBuffer target, int offset, short[] source, int sourceOffset, int numBytes) {
    checkArrayCopy(target, offset, sourceOffset, source.length, numBytes, 1);
    byte[] bytes = target.heapMemory;
    int targetIndex = heapIndex(target, offset);
    int numElements = numBytes >>> 1;
    for (int i = 0; i < numElements; i++, targetIndex += 2) {
      putInt16(bytes, targetIndex, source[sourceOffset + i]);
    }
  }

  static void copyFromIntArray(
      MemoryBuffer target, int offset, int[] source, int sourceOffset, int numBytes) {
    checkArrayCopy(target, offset, sourceOffset, source.length, numBytes, 2);
    byte[] bytes = target.heapMemory;
    int targetIndex = heapIndex(target, offset);
    int numElements = numBytes >>> 2;
    for (int i = 0; i < numElements; i++, targetIndex += 4) {
      putInt32(bytes, targetIndex, source[sourceOffset + i]);
    }
  }

  static void copyFromLongArray(
      MemoryBuffer target, int offset, long[] source, int sourceOffset, int numBytes) {
    checkArrayCopy(target, offset, sourceOffset, source.length, numBytes, 3);
    byte[] bytes = target.heapMemory;
    int targetIndex = heapIndex(target, offset);
    int numElements = numBytes >>> 3;
    for (int i = 0; i < numElements; i++, targetIndex += 8) {
      putInt64(bytes, targetIndex, source[sourceOffset + i]);
    }
  }

  static void copyFromFloatArray(
      MemoryBuffer target, int offset, float[] source, int sourceOffset, int numBytes) {
    checkArrayCopy(target, offset, sourceOffset, source.length, numBytes, 2);
    byte[] bytes = target.heapMemory;
    int targetIndex = heapIndex(target, offset);
    int numElements = numBytes >>> 2;
    for (int i = 0; i < numElements; i++, targetIndex += 4) {
      putFloat32(bytes, targetIndex, source[sourceOffset + i]);
    }
  }

  static void copyFromDoubleArray(
      MemoryBuffer target, int offset, double[] source, int sourceOffset, int numBytes) {
    checkArrayCopy(target, offset, sourceOffset, source.length, numBytes, 3);
    byte[] bytes = target.heapMemory;
    int targetIndex = heapIndex(target, offset);
    int numElements = numBytes >>> 3;
    for (int i = 0; i < numElements; i++, targetIndex += 8) {
      putFloat64(bytes, targetIndex, source[sourceOffset + i]);
    }
  }

  private static void checkArrayCopy(
      MemoryBuffer source,
      int offset,
      int targetOffset,
      int targetLength,
      int numBytes,
      int shift) {
    checkHeap(source);
    int mask = (1 << shift) - 1;
    if ((numBytes & mask) != 0) {
      throw new IllegalArgumentException("numBytes is not aligned to array element size");
    }
    int numElements = numBytes >>> shift;
    if ((offset | targetOffset | numBytes | numElements) < 0
        || offset > source.size - numBytes
        || targetOffset > targetLength - numElements) {
      throwOOBException(source);
    }
  }

  static boolean equalTo(
      MemoryBuffer buffer, MemoryBuffer other, int offset1, int offset2, int len) {
    checkArgument(offset1 >= 0 && offset1 <= buffer.size - len);
    checkArgument(other != null && offset2 >= 0 && offset2 <= other.size - len);
    int pos1 = heapIndex(buffer, offset1);
    int pos2 = heapIndex(other, offset2);
    for (int i = 0; i < len; i++) {
      if (buffer.heapMemory[pos1 + i] != other.heapMemory[pos2 + i]) {
        return false;
      }
    }
    return true;
  }

  static boolean equalTo(MemoryBuffer buffer, byte[] bytes, int bytesOffset, int offset, int len) {
    int pos = heapIndex(buffer, offset);
    for (int i = 0; i < len; i++) {
      if (buffer.heapMemory[pos + i] != bytes[bytesOffset + i]) {
        return false;
      }
    }
    return true;
  }

  private static void ensureReadableFrom(MemoryBuffer buffer, int readIdx, int numBytes) {
    int diff = buffer.size - readIdx;
    if (diff < numBytes) {
      buffer.streamReader.fillBuffer(numBytes - diff);
    }
  }

  private static long readVarUint36Slow(MemoryBuffer buffer) {
    long b = readByte(buffer);
    long result = b & 0x7F;
    if ((b & 0x80) != 0) {
      b = readByte(buffer);
      result |= (b & 0x7F) << 7;
      if ((b & 0x80) != 0) {
        b = readByte(buffer);
        result |= (b & 0x7F) << 14;
        if ((b & 0x80) != 0) {
          b = readByte(buffer);
          result |= (b & 0x7F) << 21;
          if ((b & 0x80) != 0) {
            b = readByte(buffer);
            result |= (b & 0xFF) << 28;
          }
        }
      }
    }
    return result;
  }

  private static int readVarUInt32Slow(MemoryBuffer buffer) {
    int b = readByte(buffer) & 0xFF;
    int result = b & 0x7F;
    if ((b & 0x80) != 0) {
      b = readByte(buffer) & 0xFF;
      result |= (b & 0x7F) << 7;
      if ((b & 0x80) != 0) {
        b = readByte(buffer) & 0xFF;
        result |= (b & 0x7F) << 14;
        if ((b & 0x80) != 0) {
          b = readByte(buffer) & 0xFF;
          result |= (b & 0x7F) << 21;
          if ((b & 0x80) != 0) {
            b = readByte(buffer) & 0xFF;
            if ((b & 0xF0) != 0) {
              throwMalformedVarUInt32(b);
            }
            result |= b << 28;
          }
        }
      }
    }
    return result;
  }

  private static long readVarUInt64Slow(MemoryBuffer buffer) {
    long b = readByte(buffer);
    long result = b & 0x7F;
    if ((b & 0x80) != 0) {
      b = readByte(buffer);
      result |= (b & 0x7F) << 7;
      if ((b & 0x80) != 0) {
        b = readByte(buffer);
        result |= (b & 0x7F) << 14;
        if ((b & 0x80) != 0) {
          b = readByte(buffer);
          result |= (b & 0x7F) << 21;
          if ((b & 0x80) != 0) {
            b = readByte(buffer);
            result |= (b & 0x7F) << 28;
            if ((b & 0x80) != 0) {
              b = readByte(buffer);
              result |= (b & 0x7F) << 35;
              if ((b & 0x80) != 0) {
                b = readByte(buffer);
                result |= (b & 0x7F) << 42;
                if ((b & 0x80) != 0) {
                  b = readByte(buffer);
                  result |= (b & 0x7F) << 49;
                  if ((b & 0x80) != 0) {
                    b = readByte(buffer);
                    result |= b << 56;
                  }
                }
              }
            }
          }
        }
      }
    }
    return result;
  }

  static void copy(byte[] source, int sourceIndex, byte[] target, int targetIndex, int length) {
    System.arraycopy(source, sourceIndex, target, targetIndex, length);
  }

  static int putVarInt32(byte[] target, int index, int value) {
    return putVarUInt32(target, index, (value << 1) ^ (value >> 31));
  }

  static int putVarUInt32(byte[] target, int index, int value) {
    int start = index;
    while ((value & 0xFFFFFF80) != 0) {
      target[index++] = (byte) ((value & 0x7F) | 0x80);
      value >>>= 7;
    }
    target[index++] = (byte) value;
    return index - start;
  }

  static int putVarUInt32Small7(byte[] target, int index, int value) {
    return putVarUInt32(target, index, value);
  }

  static int putVarUint36Small(byte[] target, int index, long value) {
    int start = index;
    for (int i = 0; i < 4; i++) {
      if ((value >>> 7) == 0) {
        target[index++] = (byte) value;
        return index - start;
      }
      target[index++] = (byte) ((value & 0x7F) | 0x80);
      value >>>= 7;
    }
    target[index++] = (byte) value;
    return index - start;
  }

  static int putVarInt64(byte[] target, int index, long value) {
    return putVarUInt64(target, index, (value << 1) ^ (value >> 63));
  }

  static int putVarUInt64(byte[] target, int index, long value) {
    int start = index;
    for (int i = 0; i < 8; i++) {
      if ((value >>> 7) == 0) {
        target[index++] = (byte) value;
        return index - start;
      }
      target[index++] = (byte) ((value & 0x7F) | 0x80);
      value >>>= 7;
    }
    target[index++] = (byte) value;
    return index - start;
  }

  static int varUInt32Bytes(byte[] source, int index) {
    for (int i = 0; i < 4; i++) {
      if ((source[index + i] & 0x80) == 0) {
        return i + 1;
      }
    }
    int fifthByte = source[index + 4] & 0xFF;
    if ((fifthByte & 0xF0) != 0) {
      throwMalformedVarUInt32(fifthByte);
    }
    return 5;
  }

  static int varUint36SmallBytes(byte[] source, int index) {
    for (int i = 0; i < 4; i++) {
      if ((source[index + i] & 0x80) == 0) {
        return i + 1;
      }
    }
    return 5;
  }

  static int varUInt64Bytes(byte[] source, int index) {
    for (int i = 0; i < 8; i++) {
      if ((source[index + i] & 0x80) == 0) {
        return i + 1;
      }
    }
    return 9;
  }

  private static void throwMalformedVarUInt32(int fifthByte) {
    throw new IllegalArgumentException(
        "Malformed varuint32 fifth byte " + fifthByte + " exceeds 32 bits");
  }
}
