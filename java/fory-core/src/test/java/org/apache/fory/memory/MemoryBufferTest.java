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
import static org.testng.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.Random;
import org.testng.Assert;
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
  public void testBufferUnsafeWrite() {
    {
      MemoryBuffer buffer = MemoryUtils.buffer(1024);
      byte[] heapMemory = buffer.getHeapMemory();
      long pos = buffer.getUnsafeAddress();
      assertEquals(buffer._unsafeWriterAddress(), pos);
      assertEquals(buffer.getUnsafeReaderAddress(), pos);
      Platform.putByte(heapMemory, pos, Byte.MIN_VALUE);
      pos += 1;
      Platform.putShort(heapMemory, pos, Short.MAX_VALUE);
      pos += 2;
      LittleEndian.putInt32(heapMemory, pos, Integer.MIN_VALUE);
      pos += 4;
      Platform.putLong(heapMemory, pos, Long.MAX_VALUE);
      pos += 8;
      LittleEndian.putFloat64(heapMemory, pos, -1);
      pos += 8;
      LittleEndian.putFloat32(heapMemory, pos, -1);
      assertEquals(buffer.getFloat32((int) (pos - Platform.BYTE_ARRAY_OFFSET)), -1);
      pos -= 8;
      assertEquals(buffer.getFloat64((int) (pos - Platform.BYTE_ARRAY_OFFSET)), -1);
      pos -= 8;
      assertEquals(LittleEndian.getInt64(heapMemory, pos), Long.MAX_VALUE);
      pos -= 4;
      assertEquals(LittleEndian.getInt32(heapMemory, pos), Integer.MIN_VALUE);
      pos -= 2;
      assertEquals(buffer.getInt16((int) (pos - Platform.BYTE_ARRAY_OFFSET)), Short.MAX_VALUE);
      pos -= 1;
      assertEquals(buffer.getByte((int) (pos - Platform.BYTE_ARRAY_OFFSET)), Byte.MIN_VALUE);
    }
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
      Assert.assertEquals(
          ByteBufferUtil.getAddress(buffer.sliceAsByteBuffer()),
          ByteBufferUtil.getAddress(direct) + 5);
    }
    {
      long address = 0;
      try {
        address = Platform.allocateMemory(10);
        ByteBuffer direct = ByteBufferUtil.wrapDirectBuffer(address, 10);
        direct.put(data);
        direct.flip();
        direct.position(5);
        MemoryBuffer buffer = MemoryUtils.wrap(direct);
        assertEquals(buffer.sliceAsByteBuffer(), direct);
        assertEquals(ByteBufferUtil.getAddress(buffer.sliceAsByteBuffer()), address + 5);
      } finally {
        Platform.freeMemory(address);
      }
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
    buf1.putByte(9, (byte) 2);
    Assert.assertFalse(buf1.equalTo(buf2, 0, 0, buf1.size()));
  }

  @Test
  public void testEqualToZeroSize() {
    MemoryBuffer buf1 = MemoryUtils.buffer(0);
    MemoryBuffer buf2 = MemoryUtils.buffer(0);
    Assert.assertTrue(buf1.equalTo(buf2, 0, 0, buf1.size()));
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
    buf.writePrimitiveArrayWithSize(bytes, Platform.BYTE_ARRAY_OFFSET, bytes.length);
    buf.writePrimitiveArrayWithSize(chars, Platform.CHAR_ARRAY_OFFSET, chars.length * 2);
    assertEquals(bytes, buf.readBytesAndSize());
    assertEquals(chars, buf.readChars(buf.readVarUint32()));
    buf.writePrimitiveArrayAlignedSize(bytes, Platform.BYTE_ARRAY_OFFSET, bytes.length);
    buf.writePrimitiveArrayAlignedSize(chars, Platform.CHAR_ARRAY_OFFSET, chars.length * 2);
    assertEquals(bytes, buf.readBytesWithAlignedSize());
    assertEquals(chars, buf.readCharsWithAlignedSize());
  }

  @Test
  public void testWriteVarUint32() {
    for (int i = 0; i < 32; i++) {
      MemoryBuffer buf = MemoryUtils.buffer(8);
      for (int j = 0; j < i; j++) {
        buf.writeByte((byte) 1); // make address unaligned.
        buf.readByte();
      }
      checkVarUint32(buf, 1, 1);
      checkVarUint32(buf, 1 << 6, 1);
      checkVarUint32(buf, 1 << 7, 2);
      checkVarUint32(buf, 1 << 13, 2);
      checkVarUint32(buf, 1 << 14, 3);
      checkVarUint32(buf, 1 << 20, 3);
      checkVarUint32(buf, 1 << 21, 4);
      checkVarUint32(buf, 1 << 27, 4);
      checkVarUint32(buf, 1 << 28, 5);
      checkVarUint32(buf, Integer.MAX_VALUE, 5);

      checkVarUint32(buf, -1);
      checkVarUint32(buf, -1 << 6);
      checkVarUint32(buf, -1 << 7);
      checkVarUint32(buf, -1 << 13);
      checkVarUint32(buf, -1 << 14);
      checkVarUint32(buf, -1 << 20);
      checkVarUint32(buf, -1 << 21);
      checkVarUint32(buf, -1 << 27);
      checkVarUint32(buf, -1 << 28);
      checkVarUint32(buf, Byte.MIN_VALUE);
      checkVarUint32(buf, Short.MIN_VALUE);
      checkVarUint32(buf, Integer.MIN_VALUE);
    }
  }

  private void checkVarUint32(MemoryBuffer buf, int value, int bytesWritten) {
    assertEquals(buf.writerIndex(), buf.readerIndex());
    int actualBytesWritten = buf.writeVarUint32(value);
    assertEquals(actualBytesWritten, bytesWritten);
    int varInt = buf.readVarUint32();
    assertEquals(buf.writerIndex(), buf.readerIndex());
    assertEquals(value, varInt);
  }

  private void checkVarUint32(MemoryBuffer buf, int value) {
    int readerIndex = buf.readerIndex();
    assertEquals(buf.writerIndex(), readerIndex);
    buf.writeVarUint32(value);
    int varInt = buf.readVarUint32();
    assertEquals(buf.writerIndex(), buf.readerIndex());
    assertEquals(value, varInt);
    // test slow read branch in `readVarUint`
    assertEquals(buf.slice(readerIndex, buf.readerIndex() - readerIndex).readVarUint32(), value);
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
  public void testWriteVarUint64() {
    MemoryBuffer buf = MemoryUtils.buffer(8);
    checkVarUint64(buf, -1, 9);
    for (int i = 0; i < 9; i++) {
      for (int j = 0; j < i; j++) {
        checkVarUint64(buf(i), -1, 9);
        checkVarUint64(buf(i), 1, 1);
        checkVarUint64(buf(i), 1L << 6, 1);
        checkVarUint64(buf(i), 1L << 7, 2);
        checkVarUint64(buf(i), -(2 << 5), 9);
        checkVarUint64(buf(i), -(2 << 6), 9);
        checkVarUint64(buf(i), 1L << 13, 2);
        checkVarUint64(buf(i), 1L << 14, 3);
        checkVarUint64(buf(i), -(2 << 12), 9);
        checkVarUint64(buf(i), -(2 << 13), 9);
        checkVarUint64(buf(i), 1L << 20, 3);
        checkVarUint64(buf(i), 1L << 21, 4);
        checkVarUint64(buf(i), -(2 << 19), 9);
        checkVarUint64(buf(i), -(2 << 20), 9);
        checkVarUint64(buf(i), 1L << 27, 4);
        checkVarUint64(buf(i), 1L << 28, 5);
        checkVarUint64(buf(i), -(2 << 26), 9);
        checkVarUint64(buf(i), -(2 << 27), 9);
        checkVarUint64(buf(i), 1L << 30, 5);
        checkVarUint64(buf(i), -(2L << 29), 9);
        checkVarUint64(buf(i), 1L << 30, 5);
        checkVarUint64(buf(i), -(2L << 30), 9);
        checkVarUint64(buf(i), 1L << 32, 5);
        checkVarUint64(buf(i), -(2L << 31), 9);
        checkVarUint64(buf(i), 1L << 34, 5);
        checkVarUint64(buf(i), -(2L << 33), 9);
        checkVarUint64(buf(i), 1L << 35, 6);
        checkVarUint64(buf(i), -(2L << 34), 9);
        checkVarUint64(buf(i), 1L << 41, 6);
        checkVarUint64(buf(i), -(2L << 40), 9);
        checkVarUint64(buf(i), 1L << 42, 7);
        checkVarUint64(buf(i), -(2L << 41), 9);
        checkVarUint64(buf(i), 1L << 48, 7);
        checkVarUint64(buf(i), -(2L << 47), 9);
        checkVarUint64(buf(i), 1L << 49, 8);
        checkVarUint64(buf(i), -(2L << 48), 9);
        checkVarUint64(buf(i), 1L << 55, 8);
        checkVarUint64(buf(i), -(2L << 54), 9);
        checkVarUint64(buf(i), 1L << 56, 9);
        checkVarUint64(buf(i), -(2L << 55), 9);
        checkVarUint64(buf(i), 1L << 62, 9);
        checkVarUint64(buf(i), -(2L << 62), 9);
        checkVarUint64(buf(i), 1L << 63 - 1, 9);
        checkVarUint64(buf(i), -(2L << 62), 9);
        checkVarUint64(buf(i), Long.MAX_VALUE, 9);
        checkVarUint64(buf(i), Long.MIN_VALUE, 9);
      }
    }
  }

  private void checkVarUint64(MemoryBuffer buf, long value, int bytesWritten) {
    int readerIndex = buf.readerIndex();
    assertEquals(buf.writerIndex(), readerIndex);
    int actualBytesWritten = buf.writeVarUint64(value);
    assertEquals(actualBytesWritten, bytesWritten);
    long varLong = buf.readVarUint64();
    assertEquals(buf.writerIndex(), buf.readerIndex());
    assertEquals(value, varLong);
    // test slow read branch in `readVarUint64`
    assertEquals(buf.slice(readerIndex, buf.readerIndex() - readerIndex).readVarUint64(), value);
  }

  @Test
  public void testWriteVarUint32Aligned() {
    MemoryBuffer buf = MemoryUtils.buffer(16);
    assertEquals(buf.writeVarUint32Aligned(1), 4);
    assertEquals(buf.readAlignedVarUint(), 1);
    assertEquals(buf.writeVarUint32Aligned(1 << 5), 4);
    assertEquals(buf.readAlignedVarUint(), 1 << 5);
    assertEquals(buf.writeVarUint32Aligned(1 << 10), 4);
    assertEquals(buf.readAlignedVarUint(), 1 << 10);
    assertEquals(buf.writeVarUint32Aligned(1 << 15), 4);
    assertEquals(buf.readAlignedVarUint(), 1 << 15);
    assertEquals(buf.writeVarUint32Aligned(1 << 20), 4);
    assertEquals(buf.readAlignedVarUint(), 1 << 20);
    assertEquals(buf.writeVarUint32Aligned(1 << 25), 8);
    assertEquals(buf.readAlignedVarUint(), 1 << 25);
    assertEquals(buf.writeVarUint32Aligned(1 << 30), 8);
    assertEquals(buf.readAlignedVarUint(), 1 << 30);
    assertEquals(buf.writeVarUint32Aligned(Integer.MAX_VALUE), 8);
    assertEquals(buf.readAlignedVarUint(), Integer.MAX_VALUE);
    buf.writeByte((byte) 1); // make address unaligned.
    buf.writeInt16((short) 1); // make address unaligned.
    assertEquals(buf.writeVarUint32Aligned(Integer.MAX_VALUE), 9);
    buf.readByte();
    buf.readInt16();
    assertEquals(buf.readAlignedVarUint(), Integer.MAX_VALUE);
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
      buffer.writeVarUint32Aligned(1 << j);
      assertEquals(buffer.writerIndex() % 4, 0);
      buffer.readByte();
      assertEquals(buffer.readAlignedVarUint(), 1 << j);
      for (int k = 0; k < i % 4; k++) {
        buffer.writeByte((byte) i); // make address unaligned.
        buffer.writeVarUint32Aligned(1 << j);
        assertEquals(buffer.writerIndex() % 4, 0);
        buffer.readByte();
        assertEquals(buffer.readAlignedVarUint(), 1 << j);
      }
    }
    buffer.writeByte((byte) i); // make address unaligned.
    buffer.writeVarUint32Aligned(Integer.MAX_VALUE);
    assertEquals(buffer.writerIndex() % 4, 0);
    buffer.readByte();
    assertEquals(buffer.readAlignedVarUint(), Integer.MAX_VALUE);
  }

  @Test
  public void testGetShortB() {
    byte[] data = new byte[4];
    data[0] = (byte) 0xac;
    data[1] = (byte) 0xed;
    assertEquals(BigEndian.getShortB(data, 0), (short) 0xaced);
  }

  @Test
  public void testWriteSliInt64() {
    MemoryBuffer buf = MemoryUtils.buffer(8);
    checkSliInt64(buf, -1, 4);
    for (int i = 0; i < 10; i++) {
      for (int j = 0; j < i; j++) {
        checkSliInt64(buf(i), -1, 4);
        checkSliInt64(buf(i), 1, 4);
        checkSliInt64(buf(i), 1L << 6, 4);
        checkSliInt64(buf(i), 1L << 7, 4);
        checkSliInt64(buf(i), -(2 << 5), 4);
        checkSliInt64(buf(i), -(2 << 6), 4);
        checkSliInt64(buf(i), 1L << 28, 4);
        checkSliInt64(buf(i), Integer.MAX_VALUE / 2, 4);
        checkSliInt64(buf(i), Integer.MIN_VALUE / 2, 4);
        checkSliInt64(buf(i), -1L << 30, 4);
        checkSliInt64(buf(i), 1L << 30, 9);
        checkSliInt64(buf(i), Integer.MAX_VALUE, 9);
        checkSliInt64(buf(i), Integer.MIN_VALUE, 9);
        checkSliInt64(buf(i), -1L << 31, 9);
        checkSliInt64(buf(i), 1L << 31, 9);
        checkSliInt64(buf(i), -1L << 32, 9);
        checkSliInt64(buf(i), 1L << 32, 9);
        checkSliInt64(buf(i), Long.MAX_VALUE, 9);
        checkSliInt64(buf(i), Long.MIN_VALUE, 9);
      }
    }
  }

  private void checkSliInt64(MemoryBuffer buf, long value, int bytesWritten) {
    int readerIndex = buf.readerIndex();
    assertEquals(buf.writerIndex(), readerIndex);
    int actualBytesWritten = buf.writeSliInt64(value);
    assertEquals(actualBytesWritten, bytesWritten);
    long varLong = buf.readSliInt64();
    assertEquals(buf.writerIndex(), buf.readerIndex());
    assertEquals(value, varLong);
    assertEquals(buf.slice(readerIndex, buf.readerIndex() - readerIndex).readSliInt64(), value);
  }

  @Test
  public void testVarUint32Small7() {
    MemoryBuffer buf = MemoryUtils.buffer(1);
    buf.writeVarUint32Small7(1);
    assertEquals(buf.readVarUint32Small7(), 1);
    assertEquals(buf.writeVarUint32Small7(127), 1);
    assertEquals(buf.readVarUint32Small7(), 127);
    assertEquals(buf.writeVarUint32Small7(Short.MAX_VALUE), 3);
    assertEquals(buf.readVarUint32Small7(), Short.MAX_VALUE);
    assertEquals(buf.writeVarUint32Small7(Integer.MAX_VALUE), 5);
    assertEquals(buf.readVarUint32Small7(), Integer.MAX_VALUE);
    assertEquals(buf.writeVarUint32Small7(-1), 5);
    assertEquals(buf.readVarUint32Small7(), -1);
    assertEquals(buf.writeVarUint32Small7(0), 1);
    assertEquals(buf.readVarUint32Small7(), 0);
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
