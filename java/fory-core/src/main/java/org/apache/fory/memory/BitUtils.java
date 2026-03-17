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

/**
 * Util class for bits related operations. We assume that the bitmap data is word-aligned (that is,
 * a multiple of 8 bytes in length).
 */
public class BitUtils {

  private static final int WORD_SIZE = 8;

  /**
   * Sets the bit at the specified index to {@code true}.
   *
   * <p>Every byte is set from right to left(the least significant → the most significant): {@code
   * 1L << bitIndex}.
   */
  public static void set(MemoryBuffer bitmapBuffer, int baseOffset, int index) {
    final int byteIndex = baseOffset + (index >> 3);
    final int bitIndex = index & 7;
    byte currentByte = bitmapBuffer.getByte(byteIndex);
    final byte bitMask = (byte) (1L << bitIndex);
    currentByte |= bitMask;
    bitmapBuffer.putByte(byteIndex, currentByte);
  }

  public static void setAll(MemoryBuffer bitmapBuffer, int baseOffset, int valueCount) {
    final int sizeInBytes = (valueCount + 7) / 8;
    // If value count is not a multiple of 8, then calculate number of used bits in the last byte
    final int remainder = valueCount % 8;

    final int sizeInBytesMinus1 = sizeInBytes - 1;
    int bytesMinus1EndOffset = baseOffset + sizeInBytesMinus1;
    for (int i = baseOffset; i < bytesMinus1EndOffset; i++) {
      bitmapBuffer.putByte(i, (byte) 0xff);
    }

    // handling with the last byte
    // since unsafe putLong use native byte order, maybe not big endian,
    // see java.nio.DirectByteBuffer.putLong(long, long), we can't use unsafe.putLong
    // for bit operations, native byte order may be subject to change between machine
    if (remainder != 0) {
      // Every byte is set form right to left
      byte byteValue = (byte) (0xff >>> (8 - remainder));
      bitmapBuffer.putByte(baseOffset + sizeInBytesMinus1, byteValue);
    }
  }

  public static void unset(MemoryBuffer bitmapBuffer, int baseOffset, int index) {
    setBit(bitmapBuffer, baseOffset, index, 0);
  }

  /** Set the bit at a given index to provided value (1 or 0). */
  public static void setBit(MemoryBuffer bitmapBuffer, int baseOffset, int index, int value) {
    final int byteIndex = baseOffset + (index >> 3);
    final int bitIndex = index & 7;
    byte current = bitmapBuffer.getByte(byteIndex);
    final byte bitMask = (byte) (1L << bitIndex);
    if (value != 0) {
      current |= bitMask;
    } else {
      current -= (bitMask & current);
    }
    bitmapBuffer.putByte(byteIndex, current);
  }

  public static boolean isSet(MemoryBuffer bitmapBuffer, int baseOffset, int index) {
    final int byteIndex = baseOffset + (index >> 3);
    final int bitIndex = index & 7;
    final byte b = bitmapBuffer.getByte(byteIndex);
    return ((b >> bitIndex) & 0x01) != 0;
  }

  public static boolean isNotSet(MemoryBuffer bitmapBuffer, int baseOffset, int index) {
    final int byteIndex = baseOffset + (index >> 3);
    final int bitIndex = index & 7;
    final byte b = bitmapBuffer.getByte(byteIndex);
    return ((b >> bitIndex) & 0x01) == 0;
  }

  /** Returns {@code true} if any bit is set. */
  public static boolean anySet(MemoryBuffer bitmapBuffer, int baseOffset, int bitmapWidthInBytes) {
    int addr = baseOffset;
    int bitmapWidthInWords = bitmapWidthInBytes / WORD_SIZE;
    for (int i = 0; i < bitmapWidthInWords; i++, addr += WORD_SIZE) {
      if (bitmapBuffer.getInt64(addr) != 0) {
        return true;
      }
    }
    return false;
  }

  /** Returns {@code true} if any bit is not set. */
  public static boolean anyUnSet(MemoryBuffer bitmapBuffer, int baseOffset, int valueCount) {
    final int sizeInBytes = (valueCount + 7) / 8;
    // If value count is not a multiple of 8, then calculate number of used bits in the last byte
    final int remainder = valueCount % 8;

    final int sizeInBytesMinus1 = sizeInBytes - 1;
    int bytesMinus1EndOffset = baseOffset + sizeInBytesMinus1;
    for (int i = baseOffset; i < bytesMinus1EndOffset; i++) {
      if (bitmapBuffer.getByte(i) != (byte) 0xFF) {
        return true;
      }
    }

    // handling with the last byte
    // since unsafe putLong use native byte order, maybe not big endian,
    // see java.nio.DirectByteBuffer.putLong(long, long), we can't use unsafe.putLong
    // for bit operations, native byte order may be subject to change between machine,
    // so we use getByte
    if (remainder != 0) {
      byte byteValue = bitmapBuffer.getByte(baseOffset + sizeInBytesMinus1);
      // Every byte is set form right to left
      byte mask = (byte) (0xFF >>> (8 - remainder));
      return byteValue != mask;
    }

    return false;
  }

  /**
   * Given a bitmap buffer, count the number of bits that are not set.
   *
   * <p>Every byte is set from right to left: {@code 0xFF << remainder}
   *
   * @return number of bits not set.
   */
  public static int getNullCount(final MemoryBuffer bitmapBuffer, int baseOffset, int valueCount) {
    // not null count + remainder
    int count = 0;
    final int sizeInBytes = (valueCount + 7) / 8;
    // If value count is not a multiple of 8, then calculate number of used bits in the last byte
    final int remainder = valueCount % 8;

    final int sizeInBytesMinus1 = sizeInBytes - 1;
    int bytesMinus1EndOffset = baseOffset + sizeInBytesMinus1;
    for (int i = baseOffset; i < bytesMinus1EndOffset; i++) {
      byte byteValue = bitmapBuffer.getByte(i);
      // byteValue & 0xFF: sets int to the (unsigned) 8 bits value resulting from
      // putting the 8 bits of value in the lowest 8 bits of int.
      count += Integer.bitCount(byteValue & 0xFF);
    }

    // handling with the last byte
    byte byteValue = bitmapBuffer.getByte(baseOffset + sizeInBytesMinus1);
    if (remainder != 0) {
      // making the remaining bits all 1s if it is not fully filled
      byte mask = (byte) (0xFF << remainder);
      byteValue = (byte) (byteValue | mask);
    }
    count += Integer.bitCount(byteValue & 0xFF);

    return 8 * sizeInBytes - count;
  }

  public static int calculateBitmapWidthInBytes(int numFields) {
    return ((numFields + 63) / 64) * WORD_SIZE;
  }
}
