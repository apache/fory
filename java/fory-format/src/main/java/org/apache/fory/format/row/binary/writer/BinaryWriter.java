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

package org.apache.fory.format.row.binary.writer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.util.DecimalUtility;
import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.row.binary.BinaryMap;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.vectorized.ArrowUtils;
import org.apache.fory.memory.BitUtils;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.util.DecimalUtils;

/** Base class for writing row-format structures. */
public abstract class BinaryWriter {

  public static int roundNumberOfBytesToNearestWord(int numBytes) {
    int remainder = numBytes & 0x07;
    if (remainder == 0) {
      return numBytes;
    } else {
      return numBytes + (8 - remainder);
    }
  }

  // MemoryBuffer should be word-aligned since binary is word-aligned
  protected MemoryBuffer buffer;
  // The writerIndex of the buffer where the writing starts.
  protected int startIndex;

  // avoid polymorphic setNullAt/setNotNullAt to inline for performance.
  // array use 8 byte for numElements
  protected final int bytesBeforeBitMap;
  protected final List<BinaryWriter> children;

  protected BinaryWriter(MemoryBuffer buffer, int bytesBeforeBitMap) {
    this.buffer = buffer;
    this.bytesBeforeBitMap = bytesBeforeBitMap;
    children = new ArrayList<>();
  }

  public final MemoryBuffer getBuffer() {
    return buffer;
  }

  public final int writerIndex() {
    return buffer.writerIndex();
  }

  public final int size() {
    return buffer.writerIndex() - startIndex;
  }

  public final int getStartIndex() {
    return startIndex;
  }

  public final void increaseWriterIndex(int val) {
    buffer.increaseWriterIndex(val);
  }

  public final void increaseWriterIndexToAligned(int val) {
    int writerIndex = buffer.writerIndex();
    int maybeEnd = writerIndex + val;
    int remainder = maybeEnd & 0x07;
    if (remainder == 0) {
      buffer.increaseWriterIndex(val);
    } else {
      int end = maybeEnd - remainder + 8;
      int newVal = end - writerIndex;
      buffer.grow(newVal);
      for (int i = maybeEnd; i < end; i++) {
        buffer.putByte(i, (byte) 0);
      }
      buffer.increaseWriterIndex(newVal);
    }
  }

  protected final void grow(int neededSize) {
    buffer.grow(neededSize);
  }

  public void setOffsetAndSize(int ordinal, int size) {
    setOffsetAndSize(ordinal, buffer.writerIndex(), size);
  }

  public void setOffsetAndSize(int ordinal, int absoluteOffset, int size) {
    final long relativeOffset = absoluteOffset - startIndex;
    final long offsetAndSize = (relativeOffset << 32) | (long) size;
    write(ordinal, offsetAndSize);
  }

  /** if numBytes is not multiple of 8, zero 8 byte until multiple of 8. */
  protected final void zeroOutPaddingBytes(int numBytes) {
    if ((numBytes & 0x07) > 0) {
      buffer.putInt64(buffer.writerIndex() + ((numBytes >> 3) << 3), 0L);
    }
  }

  /**
   * Writer might recycle buffers, so implementations should clear data from a previous not-null
   * write when setting null to avoid information leaks.
   */
  public void setNullAt(int ordinal) {
    BitUtils.set(buffer, startIndex + bytesBeforeBitMap, ordinal);
  }

  public void setNotNullAt(int ordinal) {
    BitUtils.unset(buffer, startIndex + bytesBeforeBitMap, ordinal);
  }

  public boolean isNullAt(int ordinal) {
    return BitUtils.isSet(buffer, startIndex + bytesBeforeBitMap, ordinal);
  }

  public abstract int getOffset(int ordinal);

  public abstract void write(int ordinal, byte value);

  public abstract void write(int ordinal, boolean value);

  public abstract void write(int ordinal, short value);

  public abstract void write(int ordinal, int value);

  public abstract void write(int ordinal, float value);

  public abstract void write(int ordinal, BigDecimal input);

  public final void write(int ordinal, long value) {
    buffer.putInt64(getOffset(ordinal), value);
  }

  public final void write(int ordinal, double value) {
    buffer.putFloat64(getOffset(ordinal), value);
  }

  // String is not 8-byte aligned
  public final void write(int ordinal, String input) {
    write(ordinal, input.getBytes(StandardCharsets.UTF_8));
  }

  // byte[] is not 8-byte aligned
  public final void write(int ordinal, byte[] input) {
    writeUnaligned(ordinal, input, 0, input.length);
  }

  // BinaryRow is aligned
  public final void write(int ordinal, BinaryRow row) {
    writeAlignedBytes(ordinal, row.getBuffer(), row.getBaseOffset(), row.getSizeInBytes());
  }

  // BinaryMap is aligned
  public final void write(int ordinal, BinaryMap map) {
    writeAlignedBytes(ordinal, map.getBuf(), map.getBaseOffset(), map.getSizeInBytes());
  }

  // BinaryArray is aligned
  public final void write(int ordinal, BinaryArray array) {
    writeAlignedBytes(ordinal, array.getBuffer(), array.getBaseOffset(), array.getSizeInBytes());
  }

  /** This operation will increase writerIndex by aligned 8-byte. */
  public void writeUnaligned(int ordinal, byte[] input, int offset, int numBytes) {
    final int roundedSize = roundNumberOfBytesToNearestWord(numBytes);
    buffer.grow(roundedSize);
    zeroOutPaddingBytes(numBytes);
    buffer.put(bufferWriteIndexFor(ordinal), input, offset, numBytes);
    setOffsetAndSize(ordinal, numBytes);
    if (copyShouldIncreaseWriterIndex(ordinal)) {
      buffer._increaseWriterIndexUnsafe(roundedSize);
    }
  }

  /** This operation will increase writerIndex by aligned 8-byte. */
  public void writeUnaligned(int ordinal, MemoryBuffer input, int offset, int numBytes) {
    final int roundedSize = roundNumberOfBytesToNearestWord(numBytes);
    buffer.grow(roundedSize);
    zeroOutPaddingBytes(numBytes);
    buffer.copyFrom(bufferWriteIndexFor(ordinal), input, offset, numBytes);
    setOffsetAndSize(ordinal, numBytes);
    if (copyShouldIncreaseWriterIndex(ordinal)) {
      buffer._increaseWriterIndexUnsafe(roundedSize);
    }
  }

  public void writeAlignedBytes(int ordinal, MemoryBuffer input, int baseOffset, int numBytes) {
    buffer.grow(numBytes);
    buffer.copyFrom(bufferWriteIndexFor(ordinal), input, baseOffset, numBytes);
    setOffsetAndSize(ordinal, numBytes);
    if (copyShouldIncreaseWriterIndex(ordinal)) {
      buffer.increaseWriterIndex(numBytes);
    }
  }

  protected final void writeDecimal(int ordinal, BigDecimal value, ArrowType.Decimal type) {
    if (value != null) {
      DecimalUtility.checkPrecisionAndScale(value, type.getPrecision(), type.getScale());
      grow(DecimalUtils.DECIMAL_BYTE_LENGTH);
      ArrowBuf arrowBuf = ArrowUtils.buffer(DecimalUtils.DECIMAL_BYTE_LENGTH);
      DecimalUtility.writeBigDecimalToArrowBuf(
          value, arrowBuf, 0, DecimalUtils.DECIMAL_BYTE_LENGTH);
      buffer.copyFromUnsafe(
          bufferWriteIndexFor(ordinal),
          null,
          arrowBuf.memoryAddress(),
          DecimalUtils.DECIMAL_BYTE_LENGTH);
      arrowBuf.getReferenceManager().release();
      setOffsetAndSize(ordinal, writerIndex(), DecimalUtils.DECIMAL_BYTE_LENGTH);
      if (copyShouldIncreaseWriterIndex(ordinal)) {
        increaseWriterIndex(DecimalUtils.DECIMAL_BYTE_LENGTH);
      }
    } else {
      setNullAt(ordinal);
    }
  }

  protected int bufferWriteIndexFor(int ordinal) {
    return buffer.writerIndex();
  }

  protected boolean copyShouldIncreaseWriterIndex(int ordinal) {
    return true;
  }

  /** write long value to position pointed by current writerIndex. */
  public final void writeDirectly(long value) {
    buffer.grow(8);
    buffer.putInt64(writerIndex(), value);
    buffer._increaseWriterIndexUnsafe(8);
  }

  /** write long value to position pointed by offset. */
  public final void writeDirectly(int offset, long value) {
    buffer.putInt64(offset, value);
  }

  public final void copyTo(BinaryWriter writer, int ordinal) {
    writer.writeAlignedBytes(ordinal, buffer, startIndex, buffer.writerIndex());
  }

  public final void setBuffer(MemoryBuffer buffer) {
    this.buffer = buffer;
    for (BinaryWriter child : children) {
      child.setBuffer(buffer);
    }
  }
}
