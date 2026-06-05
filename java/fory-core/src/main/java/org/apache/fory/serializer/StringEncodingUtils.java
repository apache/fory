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

import static org.apache.fory.util.StringUtils.MULTI_CHARS_NON_ASCII_MASK;
import static org.apache.fory.util.StringUtils.MULTI_CHARS_NON_LATIN_MASK;

import org.apache.fory.memory.LittleEndian;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.NativeByteOrder;
import org.apache.fory.util.MathUtils;

/** String Encoding Utils. */
public class StringEncodingUtils {
  private static final byte LATIN1 = 0;
  private static final byte UTF16 = 1;
  private static final byte UTF8 = 2;

  public static boolean isLatin(char[] chars) {
    return isLatin(chars, 0);
  }

  public static boolean isLatin(char[] chars, int start) {
    if (start > chars.length) {
      return false;
    }
    int numChars = chars.length;
    int charIndex = start;
    while (charIndex + 4 <= numChars) {
      // Check 4 chars in a vectorized way. See CompressStringSuite.latinSuperWordCheck.
      long multiChars = PlatformStringUtils.getCharsLong(chars, charIndex);
      if ((multiChars & MULTI_CHARS_NON_LATIN_MASK) != 0) {
        return false;
      }
      charIndex += 4;
    }
    for (int i = charIndex; i < numChars; i++) {
      if (chars[i] > 0xFF) {
        return false;
      }
    }
    return true;
  }

  static boolean isLatin(char[] chars, int offset, int count) {
    int end = offset + count;
    int vectorizedChars = count & ~3;
    int vectorEnd = offset + vectorizedChars;
    for (int charIndex = offset; charIndex < vectorEnd; charIndex += 4) {
      long multiChars = PlatformStringUtils.getCharsLong(chars, charIndex);
      if ((multiChars & MULTI_CHARS_NON_LATIN_MASK) != 0) {
        return false;
      }
    }
    for (int i = vectorEnd; i < end; i++) {
      if (chars[i] > 0xFF) {
        return false;
      }
    }
    return true;
  }

  /** A fast convert algorithm to convert an utf16 char array into an utf8 byte array. */
  public static int convertUTF16ToUTF8(char[] src, byte[] dst, int dp) {
    int numChars = src.length;
    for (int charOffset = 0; charOffset < numChars; ) {
      if (charOffset + 4 <= numChars
          && (PlatformStringUtils.getCharsLong(src, charOffset) & MULTI_CHARS_NON_ASCII_MASK)
              == 0) {
        // ascii only
        dst[dp] = (byte) src[charOffset];
        dst[dp + 1] = (byte) src[charOffset + 1];
        dst[dp + 2] = (byte) src[charOffset + 2];
        dst[dp + 3] = (byte) src[charOffset + 3];
        dp += 4;
        charOffset += 4;
      } else {
        char c = src[charOffset++];
        if (c < 0x80) {
          dst[dp++] = (byte) c;
        } else if (c < 0x800) {
          dst[dp] = (byte) (0xc0 | (c >> 6));
          dst[dp + 1] = (byte) (0x80 | (c & 0x3f));
          dp += 2;
        } else if (c >= '\uD800' && c <= Character.MAX_LOW_SURROGATE) {
          utf8ToChar2(src, charOffset, c, dst, dp);
          dp += 4;
          charOffset++;
        } else {
          dst[dp] = (byte) (0xe0 | ((c >> 12)));
          dst[dp + 1] = (byte) (0x80 | ((c >> 6) & 0x3f));
          dst[dp + 2] = (byte) (0x80 | (c & 0x3f));
          dp += 3;
        }
      }
    }
    return dp;
  }

  /** A fast convert algorithm to convert an utf16 char array slice into an utf8 byte array. */
  public static int convertUTF16ToUTF8(char[] src, int offset, int len, byte[] dst, int dp) {
    int end = offset + len;
    for (int charOffset = offset; charOffset < end; ) {
      if (charOffset + 4 <= end
          && (PlatformStringUtils.getCharsLong(src, charOffset) & MULTI_CHARS_NON_ASCII_MASK)
              == 0) {
        dst[dp] = (byte) src[charOffset];
        dst[dp + 1] = (byte) src[charOffset + 1];
        dst[dp + 2] = (byte) src[charOffset + 2];
        dst[dp + 3] = (byte) src[charOffset + 3];
        dp += 4;
        charOffset += 4;
      } else {
        char c = src[charOffset++];
        if (c < 0x80) {
          dst[dp++] = (byte) c;
        } else if (c < 0x800) {
          dst[dp] = (byte) (0xc0 | (c >> 6));
          dst[dp + 1] = (byte) (0x80 | (c & 0x3f));
          dp += 2;
        } else if (c >= '\uD800' && c <= Character.MAX_LOW_SURROGATE) {
          if (charOffset >= end) {
            throw new RuntimeException("malformed input off : " + charOffset);
          }
          utf8ToChar2(src, charOffset, c, dst, dp);
          dp += 4;
          charOffset++;
        } else {
          dst[dp] = (byte) (0xe0 | ((c >> 12)));
          dst[dp + 1] = (byte) (0x80 | ((c >> 6) & 0x3f));
          dst[dp + 2] = (byte) (0x80 | (c & 0x3f));
          dp += 3;
        }
      }
    }
    return dp;
  }

  /** A fast convert algorithm to convert an utf16 byte array into an utf8 byte array. */
  public static int convertUTF16ToUTF8(byte[] src, byte[] dst, int dp) {
    int numBytes = src.length;
    for (int offset = 0; offset < numBytes; ) {
      if (offset + 8 <= numBytes
          && (PlatformStringUtils.getBytesLong(src, offset) & MULTI_CHARS_NON_ASCII_MASK) == 0) {
        // ascii only
        if (NativeByteOrder.IS_LITTLE_ENDIAN) {
          dst[dp] = src[offset];
          dst[dp + 1] = src[offset + 2];
          dst[dp + 2] = src[offset + 4];
          dst[dp + 3] = src[offset + 6];
        } else {
          dst[dp] = src[offset + 1];
          dst[dp + 1] = src[offset + 3];
          dst[dp + 2] = src[offset + 5];
          dst[dp + 3] = src[offset + 7];
        }
        dp += 4;
        offset += 8;
      } else {
        char c = PlatformStringUtils.getBytesChar(src, offset);
        offset += 2;

        if (c < 0x80) {
          dst[dp++] = (byte) c;
        } else {
          if (c < 0x800) {
            // 2 bytes, 11 bits
            dst[dp] = (byte) (0xc0 | (c >> 6));
            dst[dp + 1] = (byte) (0x80 | (c & 0x3f));
            dp += 2;
          } else if (c >= '\uD800' && c <= Character.MAX_LOW_SURROGATE) {
            utf8ToChar2(src, offset, c, numBytes, dst, dp);
            dp += 4;
            offset += 2;
          } else {
            // 3 bytes, 16 bits
            dst[dp] = (byte) (0xe0 | ((c >> 12)));
            dst[dp + 1] = (byte) (0x80 | ((c >> 6) & 0x3f));
            dst[dp + 2] = (byte) (0x80 | (c & 0x3f));
            dp += 3;
          }
        }
      }
    }
    return dp;
  }

  /**
   * A fast convert algorithm to convert an utf8 encoded byte array into an utf16 encoded byte
   * array.
   */
  public static int convertUTF8ToUTF16(byte[] src, int offset, int len, byte[] dst) {
    final int end = offset + len;
    int dp = 0;

    while (offset < end) {
      if (offset + 8 <= end
          && (PlatformStringUtils.getBytesLong(src, offset) & 0x8080808080808080L) == 0) {
        // ascii only
        if (NativeByteOrder.IS_LITTLE_ENDIAN) {
          dst[dp] = src[offset];
          dst[dp + 2] = src[offset + 1];
          dst[dp + 4] = src[offset + 2];
          dst[dp + 6] = src[offset + 3];
          dst[dp + 8] = src[offset + 4];
          dst[dp + 10] = src[offset + 5];
          dst[dp + 12] = src[offset + 6];
          dst[dp + 14] = src[offset + 7];
        } else {
          dst[dp + 1] = src[offset];
          dst[dp + 3] = src[offset + 1];
          dst[dp + 5] = src[offset + 2];
          dst[dp + 7] = src[offset + 3];
          dst[dp + 9] = src[offset + 4];
          dst[dp + 11] = src[offset + 5];
          dst[dp + 13] = src[offset + 6];
          dst[dp + 15] = src[offset + 7];
        }
        dp += 16;
        offset += 8;
      } else {
        int b0 = src[offset++];
        if (b0 >= 0) {
          // 1 byte, 7 bits: 0xxxxxxx
          dst[dp] = (byte) b0;
          dst[dp + 1] = 0;
          dp += 2;
        } else if ((b0 >> 5) == -2 && (b0 & 0x1e) != 0) {
          // 2 bytes, 11 bits: 110xxxxx 10xxxxxx
          if (offset >= end) {
            return -1;
          }
          int b1 = src[offset++];
          if ((b1 & 0xc0) != 0x80) { // isNotContinuation(b2)
            return -1;
          } else {
            char c = (char) (((b0 << 6) ^ b1) ^ (((byte) 0xC0 << 6) ^ ((byte) 0x80)));
            dst[dp] = (byte) c;
            dst[dp + 1] = (byte) (c >> 8);
            dp += 2;
          }
        } else if ((b0 >> 4) == -2) {
          // 3 bytes, 16 bits: 1110xxxx 10xxxxxx 10xxxxxx
          if (offset + 1 >= end) {
            return -1;
          }
          int b1 = src[offset];
          int b2 = src[offset + 1];
          offset += 2;
          if ((b0 == (byte) 0xe0 && (b1 & 0xe0) == 0x80) //
              || (b1 & 0xc0) != 0x80 //
              || (b2 & 0xc0) != 0x80) { // isMalformed3(b0, b1, b2)
            return -1;
          } else {
            char c =
                (char)
                    ((b0 << 12)
                        ^ (b1 << 6)
                        ^ (b2 ^ (((byte) 0xE0 << 12) ^ ((byte) 0x80 << 6) ^ ((byte) 0x80))));
            boolean isSurrogate = c >= '\uD800' && c < (Character.MAX_LOW_SURROGATE + 1);
            if (isSurrogate) {
              return -1;
            } else {
              dst[dp] = (byte) c;
              dst[dp + 1] = (byte) (c >> 8);
              dp += 2;
            }
          }
        } else if ((b0 >> 3) == -2) {
          // 4 bytes, 21 bits: 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
          if (offset + 2 >= end) {
            return -1;
          }
          int b2 = src[offset];
          int b3 = src[offset + 1];
          int b4 = src[offset + 2];
          offset += 3;
          int uc =
              ((b0 << 18)
                  ^ (b2 << 12)
                  ^ (b3 << 6)
                  ^ (b4
                      ^ (((byte) 0xF0 << 18)
                          ^ ((byte) 0x80 << 12)
                          ^ ((byte) 0x80 << 6)
                          ^ ((byte) 0x80))));
          if (((b2 & 0xc0) != 0x80 || (b3 & 0xc0) != 0x80 || (b4 & 0xc0) != 0x80) // isMalformed4
              ||
              // shortest form check
              !(uc >= 0x010000 && uc < 0X10FFFF + 1) // !Character.isSupplementaryCodePoint(uc)
          ) {
            return -1;
          } else {
            char c = (char) ((uc >>> 10) + ('\uD800' - (0x010000 >>> 10)));
            dst[dp] = (byte) c;
            dst[dp + 1] = (byte) (c >> 8);
            dp += 2;

            c = (char) ((uc & 0x3ff) + Character.MIN_LOW_SURROGATE);
            dst[dp] = (byte) c;
            dst[dp + 1] = (byte) (c >> 8);
            dp += 2;
          }
        } else {
          return -1;
        }
      }
    }
    return dp;
  }

  /**
   * A fast convert algorithm to convert an utf8 encoded byte array into utf16 encoded char array.
   */
  public static int convertUTF8ToUTF16(byte[] src, int offset, int len, char[] dst) {
    int end = offset + len;
    int dp = 0;
    while (offset < end) {
      if (offset + 8 <= end
          && (PlatformStringUtils.getBytesLong(src, offset) & 0x8080808080808080L) == 0) {
        // ascii only
        dst[dp] = (char) src[offset];
        dst[dp + 1] = (char) src[offset + 1];
        dst[dp + 2] = (char) src[offset + 2];
        dst[dp + 3] = (char) src[offset + 3];
        dst[dp + 4] = (char) src[offset + 4];
        dst[dp + 5] = (char) src[offset + 5];
        dst[dp + 6] = (char) src[offset + 6];
        dst[dp + 7] = (char) src[offset + 7];
        dp += 8;
        offset += 8;
      } else {
        int b1 = src[offset++];
        if (b1 >= 0) {
          // 1 byte, 7 bits: 0xxxxxxx
          dst[dp++] = (char) b1;
        } else if ((b1 >> 5) == -2 && (b1 & 0x1e) != 0) {
          // 2 bytes, 11 bits: 110xxxxx 10xxxxxx
          if (offset >= end) {
            return -1;
          }
          int b2 = src[offset++];
          if ((b2 & 0xc0) != 0x80) { // isNotContinuation(b2)
            return -1;
          } else {
            dst[dp++] = (char) (((b1 << 6) ^ b2) ^ (((byte) 0xC0 << 6) ^ ((byte) 0x80)));
          }
        } else if ((b1 >> 4) == -2) {
          // 3 bytes, 16 bits: 1110xxxx 10xxxxxx 10xxxxxx
          if (offset + 1 >= end) {
            return -1;
          }

          int b2 = src[offset];
          int b3 = src[offset + 1];
          offset += 2;
          if ((b1 == (byte) 0xe0 && (b2 & 0xe0) == 0x80) //
              || (b2 & 0xc0) != 0x80 //
              || (b3 & 0xc0) != 0x80) { // isMalformed3(b1, b2, b3)
            return -1;
          } else {
            char c =
                (char)
                    ((b1 << 12)
                        ^ (b2 << 6)
                        ^ (b3 ^ (((byte) 0xE0 << 12) ^ ((byte) 0x80 << 6) ^ ((byte) 0x80))));
            boolean isSurrogate = c >= '\uD800' && c < (Character.MAX_LOW_SURROGATE + 1);
            if (isSurrogate) {
              return -1;
            } else {
              dst[dp++] = c;
            }
          }
        } else if ((b1 >> 3) == -2) {
          // 4 bytes, 21 bits: 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
          if (offset + 2 >= end) {
            return -1;
          }
          int b2 = src[offset];
          int b3 = src[offset + 1];
          int b4 = src[offset + 2];
          offset += 3;
          int uc =
              ((b1 << 18)
                  ^ (b2 << 12)
                  ^ (b3 << 6)
                  ^ (b4
                      ^ (((byte) 0xF0 << 18)
                          ^ ((byte) 0x80 << 12)
                          ^ ((byte) 0x80 << 6)
                          ^ ((byte) 0x80))));
          if (((b2 & 0xc0) != 0x80 || (b3 & 0xc0) != 0x80 || (b4 & 0xc0) != 0x80) // isMalformed4
              ||
              // shortest form check
              !(uc >= 0x010000 && uc < 0X10FFFF + 1) // !Character.isSupplementaryCodePoint(uc)
          ) {
            return -1;
          } else {
            dst[dp] =
                (char)
                    ((uc >>> 10) + ('\uD800' - (0x010000 >>> 10))); // Character.highSurrogate(uc);
            dst[dp + 1] =
                (char) ((uc & 0x3ff) + Character.MIN_LOW_SURROGATE); // Character.lowSurrogate(uc);
            dp += 2;
          }
        } else {
          return -1;
        }
      }
    }
    return dp;
  }

  /** convert two utf16 char c and src[charOffset] to a four byte utf8 bytes. */
  private static void utf8ToChar2(char[] src, int charOffset, char c, byte[] dst, int dp) {
    char d;
    if (c > Character.MAX_HIGH_SURROGATE
        || charOffset == src.length
        || (d = src[charOffset]) < Character.MIN_LOW_SURROGATE
        || d > Character.MAX_LOW_SURROGATE) {
      throw new RuntimeException("malformed input off : " + charOffset);
    }

    int uc = ((c << 10) + d) + (0x010000 - ('\uD800' << 10) - Character.MIN_LOW_SURROGATE);
    dst[dp] = (byte) (0xf0 | ((uc >> 18)));
    dst[dp + 1] = (byte) (0x80 | ((uc >> 12) & 0x3f));
    dst[dp + 2] = (byte) (0x80 | ((uc >> 6) & 0x3f));
    dst[dp + 3] = (byte) (0x80 | (uc & 0x3f));
  }

  /** convert two utf16 char c and char(src[offset], src[offset+1]) to a four byte utf8 bytes. */
  private static void utf8ToChar2(
      byte[] src, int offset, char c, int numBytes, byte[] dst, int dp) {
    char d;
    if (c > Character.MAX_HIGH_SURROGATE
        || numBytes - offset < 1
        || (d = PlatformStringUtils.getBytesChar(src, offset)) < Character.MIN_LOW_SURROGATE
        || d > Character.MAX_LOW_SURROGATE) {
      throw new RuntimeException("malformed input off : " + offset);
    }

    int uc = ((c << 10) + d) + (0x010000 - ('\uD800' << 10) - Character.MIN_LOW_SURROGATE);
    dst[dp] = (byte) (0xf0 | ((uc >> 18)));
    dst[dp + 1] = (byte) (0x80 | ((uc >> 12) & 0x3f));
    dst[dp + 2] = (byte) (0x80 | ((uc >> 6) & 0x3f));
    dst[dp + 3] = (byte) (0x80 | (uc & 0x3f));
  }

  /**
   * Fast scan to check if UTF-8 data fits in Latin1 encoding (all code points {@code <= 0xFF}).
   * This is a read-only pass optimized for cache locality.
   */
  public static boolean isUTF8WithinLatin1(byte[] src, int offset, int length) {
    final int end = offset + length;

    while (offset < end) {
      int b0 = src[offset++] & 0xFF;

      if (b0 < 0x80) {
        // 1-byte UTF-8 (ASCII) - always Latin1
        continue;
      } else if ((b0 >> 5) == 0b110 && (b0 & 0x1e) != 0) {
        // 2-byte UTF-8
        if (offset >= end) {
          return false; // Malformed
        }
        int b1 = src[offset++] & 0xFF;
        if ((b1 & 0xc0) != 0x80) {
          return false; // Malformed
        }
        int codePoint = ((b0 & 0x1F) << 6) | (b1 & 0x3F);
        if (codePoint > 0xFF) {
          return false; // Beyond Latin1
        }
      } else {
        // 3-byte or 4-byte UTF-8 - definitely not Latin1
        return false;
      }
    }

    return true;
  }

  /** Fast UTF-8 to Latin1 conversion. Assumes scanUTF8IsLatin1 already validated the input. */
  public static int convertUTF8ToLatin1(byte[] src, int offset, int length, byte[] dst) {
    final int end = offset + length;
    int dstPos = 0;

    while (offset < end) {
      // Vectorized ASCII fast path
      if (offset + 8 <= end
          && (PlatformStringUtils.getBytesLong(src, offset) & 0x8080808080808080L) == 0) {
        // 8 ASCII bytes - direct copy
        dst[dstPos] = src[offset];
        dst[dstPos + 1] = src[offset + 1];
        dst[dstPos + 2] = src[offset + 2];
        dst[dstPos + 3] = src[offset + 3];
        dst[dstPos + 4] = src[offset + 4];
        dst[dstPos + 5] = src[offset + 5];
        dst[dstPos + 6] = src[offset + 6];
        dst[dstPos + 7] = src[offset + 7];
        dstPos += 8;
        offset += 8;
        continue;
      }

      int b0 = src[offset++] & 0xFF;

      if (b0 < 0x80) {
        // 1-byte UTF-8 (ASCII)
        dst[dstPos++] = (byte) b0;
      } else {
        // 2-byte UTF-8 (already validated to be Latin1)
        int b1 = src[offset++] & 0xFF;
        int codePoint = ((b0 & 0x1F) << 6) | (b1 & 0x3F);
        dst[dstPos++] = (byte) codePoint;
      }
    }

    return dstPos;
  }

  /**
   * Vectorized ASCII check - processes 8 bytes at a time. Returns true if all bytes are ASCII (high
   * bit not set).
   */
  public static boolean isUTF8WithinAscii(byte[] bytes, int offset, int length) {
    final int end = offset + length;
    int vectorizedEnd = offset + ((length >> 3) << 3);

    // Check 8 bytes at a time
    for (int i = offset; i < vectorizedEnd; i += 8) {
      if ((PlatformStringUtils.getBytesLong(bytes, i) & 0x8080808080808080L) != 0) {
        return false;
      }
    }

    // Check remaining bytes
    for (int i = vectorizedEnd; i < end; i++) {
      if (bytes[i] < 0) {
        return false;
      }
    }

    return true;
  }

  static void writeCharsLatin1WithOffset(
      StringSerializer serializer, MemoryBuffer buffer, char[] chars, int offset, int count) {
    int writerIndex = buffer.writerIndex();
    long header = ((long) count << 2) | LATIN1;
    buffer.ensure(writerIndex + 5 + count);
    byte[] targetArray = buffer.getHeapMemory();
    if (targetArray != null) {
      final int targetIndex = buffer._unsafeHeapWriterIndex();
      int arrIndex = targetIndex;
      arrIndex += LittleEndian.putVarUint36Small(targetArray, arrIndex, header);
      writerIndex += arrIndex - targetIndex;
      for (int i = 0; i < count; i++) {
        targetArray[arrIndex + i] = (byte) chars[offset + i];
      }
    } else {
      writerIndex += buffer._unsafePutVarUint36Small(writerIndex, header);
      final byte[] tmpArray = serializer.getByteArray(count);
      for (int i = 0; i < count; i++) {
        tmpArray[i] = (byte) chars[offset + i];
      }
      buffer.put(writerIndex, tmpArray, 0, count);
    }
    writerIndex += count;
    buffer._unsafeWriterIndex(writerIndex);
  }

  static void writeCharsUTF16WithOffset(
      StringSerializer serializer, MemoryBuffer buffer, char[] chars, int offset, int count) {
    int numBytes = MathUtils.doubleExact(count);
    int writerIndex = buffer.writerIndex();
    long header = ((long) numBytes << 2) | UTF16;
    buffer.ensure(writerIndex + 5 + numBytes);
    final byte[] targetArray = buffer.getHeapMemory();
    if (targetArray != null) {
      final int targetIndex = buffer._unsafeHeapWriterIndex();
      int arrIndex = targetIndex;
      arrIndex += LittleEndian.putVarUint36Small(targetArray, arrIndex, header);
      writerIndex += arrIndex - targetIndex + numBytes;
      if (NativeByteOrder.IS_LITTLE_ENDIAN) {
        // FIXME JDK11 utf16 string uses little-endian order.
        PlatformStringUtils.copyCharsToBytes(chars, offset, targetArray, arrIndex, numBytes);
      } else {
        writeCharsUTF16BEToHeap(chars, offset, arrIndex, numBytes, targetArray);
      }
    } else {
      writerIndex += buffer._unsafePutVarUint36Small(writerIndex, header);
      if (NativeByteOrder.IS_LITTLE_ENDIAN) {
        writerIndex =
            offHeapWriteCharsUTF16WithOffset(
                serializer, buffer, chars, offset, writerIndex, numBytes);
      } else {
        writerIndex =
            offHeapWriteCharsUTF16BEWithOffset(
                serializer, buffer, chars, offset, writerIndex, numBytes);
      }
    }
    buffer._unsafeWriterIndex(writerIndex);
  }

  static void writeCharsUTF8WithOffset(
      StringSerializer serializer, MemoryBuffer buffer, char[] chars, int offset, int count) {
    int estimateMaxBytes = count * 3;
    int approxNumBytes = (int) (count * 1.5) + 1;
    int writerIndex = buffer.writerIndex();
    buffer.ensure(writerIndex + 9 + estimateMaxBytes);
    byte[] targetArray = buffer.getHeapMemory();
    if (targetArray != null) {
      int targetIndex = buffer._unsafeHeapWriterIndex();
      int headerPos = targetIndex;
      int arrIndex = targetIndex;
      long header = ((long) approxNumBytes << 2) | UTF8;
      int headerBytesWritten = LittleEndian.putVarUint36Small(targetArray, arrIndex, header);
      arrIndex += headerBytesWritten;
      writerIndex += headerBytesWritten;
      targetIndex = convertUTF16ToUTF8(chars, offset, count, targetArray, arrIndex);
      byte stashedByte = targetArray[arrIndex];
      int written = targetIndex - arrIndex;
      header = ((long) written << 2) | UTF8;
      int diff =
          LittleEndian.putVarUint36Small(targetArray, headerPos, header) - headerBytesWritten;
      if (diff != 0) {
        handleWriteCharsUTF8UnalignedHeaderBytes(targetArray, arrIndex, diff, written, stashedByte);
      }
      buffer._unsafeWriterIndex(writerIndex + written + diff);
    } else {
      final byte[] tmpArray = serializer.getByteArray(estimateMaxBytes);
      int written = convertUTF16ToUTF8(chars, offset, count, tmpArray, 0);
      long header = ((long) written << 2) | UTF8;
      writerIndex += buffer._unsafePutVarUint36Small(writerIndex, header);
      buffer.put(writerIndex, tmpArray, 0, written);
      buffer._unsafeWriterIndex(writerIndex + written);
    }
  }

  static void writeCharsUTF8PerfOptimizedWithOffset(
      StringSerializer serializer, MemoryBuffer buffer, char[] chars, int offset, int count) {
    int estimateMaxBytes = count * 3;
    int numBytes = MathUtils.doubleExact(count);
    int writerIndex = buffer.writerIndex();
    long header = ((long) numBytes << 2) | UTF8;
    buffer.ensure(writerIndex + 9 + estimateMaxBytes);
    byte[] targetArray = buffer.getHeapMemory();
    if (targetArray != null) {
      int targetIndex = buffer._unsafeHeapWriterIndex();
      int arrIndex = targetIndex;
      arrIndex += LittleEndian.putVarUint36Small(targetArray, arrIndex, header);
      writerIndex += arrIndex - targetIndex;
      targetIndex = convertUTF16ToUTF8(chars, offset, count, targetArray, arrIndex + 4);
      int written = targetIndex - arrIndex - 4;
      buffer._unsafePutInt32(writerIndex, written);
      buffer._unsafeWriterIndex(writerIndex + 4 + written);
    } else {
      final byte[] tmpArray = serializer.getByteArray(estimateMaxBytes);
      int written = convertUTF16ToUTF8(chars, offset, count, tmpArray, 0);
      writerIndex += buffer._unsafePutVarUint36Small(writerIndex, header);
      buffer._unsafePutInt32(writerIndex, written);
      writerIndex += 4;
      buffer.put(writerIndex, tmpArray, 0, written);
      buffer._unsafeWriterIndex(writerIndex + written);
    }
  }

  static byte bestCoder(char[] chars, int offset, int count) {
    int sampleNum = Math.min(64, count);
    int vectorizedLen = sampleNum >> 2;
    int vectorizedChars = vectorizedLen << 2;
    int asciiCount = 0;
    int latin1Count = 0;
    int charOffset = offset;
    int vectorEnd = offset + vectorizedChars;
    for (; charOffset < vectorEnd; charOffset += 4) {
      long multiChars = PlatformStringUtils.getCharsLong(chars, charOffset);
      if ((multiChars & MULTI_CHARS_NON_ASCII_MASK) == 0) {
        latin1Count += 4;
        asciiCount += 4;
      } else if ((multiChars & MULTI_CHARS_NON_LATIN_MASK) == 0) {
        latin1Count += 4;
        for (int i = 0; i < 4; ++i) {
          if (chars[charOffset + i] < 0x80) {
            asciiCount++;
          }
        }
      } else {
        for (int i = 0; i < 4; ++i) {
          char c = chars[charOffset + i];
          if (c < 0x80) {
            latin1Count++;
            asciiCount++;
          } else if (c <= 0xFF) {
            latin1Count++;
          }
        }
      }
    }

    for (int i = vectorizedChars; i < sampleNum; i++) {
      char c = chars[offset + i];
      if (c < 0x80) {
        latin1Count++;
        asciiCount++;
      } else if (c <= 0xFF) {
        latin1Count++;
      }
    }

    if (latin1Count == count || (latin1Count == sampleNum && isLatin(chars, offset, count))) {
      return LATIN1;
    } else if (asciiCount >= sampleNum * 0.5) {
      return UTF8;
    } else {
      return UTF16;
    }
  }

  private static void handleWriteCharsUTF8UnalignedHeaderBytes(
      byte[] targetArray, int arrIndex, int diff, int written, byte stashed) {
    if (diff == 1) {
      System.arraycopy(targetArray, arrIndex + 1, targetArray, arrIndex + 2, written - 1);
      targetArray[arrIndex + 1] = stashed;
    } else {
      System.arraycopy(targetArray, arrIndex, targetArray, arrIndex - 1, written);
    }
  }

  private static void writeCharsUTF16BEToHeap(
      char[] chars, int offset, int arrIndex, int numBytes, byte[] targetArray) {
    int charIndex = offset;
    for (int i = arrIndex, end = i + numBytes; i < end; i += 2) {
      char c = chars[charIndex++];
      targetArray[i] = (byte) c;
      targetArray[i + 1] = (byte) (c >>> 8);
    }
  }

  private static int offHeapWriteCharsUTF16WithOffset(
      StringSerializer serializer,
      MemoryBuffer buffer,
      char[] chars,
      int offset,
      int writerIndex,
      int numBytes) {
    byte[] tmpArray = serializer.getByteArray(numBytes);
    PlatformStringUtils.copyCharsToBytes(chars, offset, tmpArray, 0, numBytes);
    buffer.put(writerIndex, tmpArray, 0, numBytes);
    writerIndex += numBytes;
    return writerIndex;
  }

  private static int offHeapWriteCharsUTF16BEWithOffset(
      StringSerializer serializer,
      MemoryBuffer buffer,
      char[] chars,
      int offset,
      int writerIndex,
      int numBytes) {
    byte[] tmpArray = serializer.getByteArray(numBytes);
    int charIndex = offset;
    for (int i = 0; i < numBytes; i += 2) {
      char c = chars[charIndex++];
      tmpArray[i] = (byte) c;
      tmpArray[i + 1] = (byte) (c >>> 8);
    }
    buffer.put(writerIndex, tmpArray, 0, numBytes);
    writerIndex += numBytes;
    return writerIndex;
  }
}
