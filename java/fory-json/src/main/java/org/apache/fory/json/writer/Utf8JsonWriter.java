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

package org.apache.fory.json.writer;

import java.util.Arrays;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.memory.LittleEndian;
import org.apache.fory.serializer.StringSerializer;

public final class Utf8JsonWriter extends JsonWriter {
  private static final int RETAINED_CAPACITY = 8192;
  private static final byte[] MIN_INT_BYTES =
      "-2147483648".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
  private static final byte[] MIN_LONG_BYTES =
      "-9223372036854775808".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
  private static final long HIGH_BITS = 0x8080808080808080L;
  private static final int INT_HIGH_BITS = 0x80808080;
  private static final long ASCII_CONTROL_OFFSET = 0x6060606060606060L;
  private static final int INT_ASCII_CONTROL_OFFSET = 0x60606060;
  private static final long ONE_BYTES = 0x0101010101010101L;
  private static final int INT_ONE_BYTES = 0x01010101;
  private static final long QUOTE_BYTES_COMPLEMENT = ~0x2222222222222222L;
  private static final int INT_QUOTE_BYTES_COMPLEMENT = ~0x22222222;
  private static final long BACKSLASH_BYTES_COMPLEMENT = ~0x5C5C5C5C5C5C5C5CL;
  private static final int INT_BACKSLASH_BYTES_COMPLEMENT = ~0x5C5C5C5C;
  private static final long UTF16_ASCII_MASK = 0xFF80FF80FF80FF80L;
  private static final int[] DIGIT_TRIPLES = new int[1000];
  private static final int[] DIGIT_QUADS = new int[10000];
  private static final boolean STRING_BYTES_BACKED = StringSerializer.isBytesBackedString();

  static {
    for (int i = 0; i < 1000; i++) {
      int c0 = '0' + i / 100;
      int c1 = '0' + (i / 10) % 10;
      int c2 = '0' + i % 10;
      int skip = i < 10 ? 2 : i < 100 ? 1 : 0;
      DIGIT_TRIPLES[i] = skip | (c0 << 8) | (c1 << 16) | (c2 << 24);
    }
    for (int i = 0; i < 10000; i++) {
      int high = i / 100;
      int low = i - high * 100;
      int c0 = '0' + high / 10;
      int c1 = '0' + high % 10;
      int c2 = '0' + low / 10;
      int c3 = '0' + low % 10;
      DIGIT_QUADS[i] = c0 | (c1 << 8) | (c2 << 16) | (c3 << 24);
    }
  }

  private byte[] buffer;
  private int position;

  public Utf8JsonWriter(boolean writeNullFields) {
    this(writeNullFields, new byte[512]);
  }

  public Utf8JsonWriter(boolean writeNullFields, byte[] buffer) {
    super(writeNullFields);
    this.buffer = buffer;
  }

  public void reset() {
    if (buffer.length > RETAINED_CAPACITY) {
      buffer = new byte[RETAINED_CAPACITY];
    }
    position = 0;
  }

  public byte[] toJsonBytes() {
    return Arrays.copyOf(buffer, position);
  }

  @Override
  public void writeNull() {
    writeAscii("null");
  }

  @Override
  public void writeBoolean(boolean value) {
    writeAscii(value ? "true" : "false");
  }

  @Override
  public void writeInt(int value) {
    ensure(11);
    writeIntNoEnsure(value);
  }

  @Override
  public void writeLong(long value) {
    if (value == Long.MIN_VALUE) {
      writeRaw(MIN_LONG_BYTES);
      return;
    }
    ensure(20);
    if (value < 0) {
      buffer[position++] = (byte) '-';
      value = -value;
    }
    if (value <= Integer.MAX_VALUE) {
      writePositiveIntNoEnsure((int) value);
      return;
    }
    int start = position;
    do {
      buffer[position++] = (byte) ('0' + value % 10);
      value /= 10;
    } while (value != 0);
    reverse(start, position - 1);
  }

  @Override
  public void writeFloat(float value) {
    if (!Float.isFinite(value)) {
      throw new ForyJsonException("JSON does not support non-finite float " + value);
    }
    writeAscii(Float.toString(value));
  }

  @Override
  public void writeDouble(double value) {
    if (!Double.isFinite(value)) {
      throw new ForyJsonException("JSON does not support non-finite double " + value);
    }
    writeAscii(Double.toString(value));
  }

  @Override
  public void writeNumber(String value) {
    writeAscii(value);
  }

  @Override
  public void writeChar(char value) {
    if (Character.isSurrogate(value)) {
      throw new ForyJsonException("JSON char cannot be a surrogate: " + Integer.toHexString(value));
    }
    writeByteRaw((byte) '"');
    writeEscapedChar(value);
    writeByteRaw((byte) '"');
  }

  @Override
  public void writeString(String value) {
    if (STRING_BYTES_BACKED) {
      byte[] bytes = StringSerializer.getStringBytes(value);
      byte stringCoder = StringSerializer.getStringCoder(value);
      if (StringSerializer.isLatin1Coder(stringCoder)) {
        if (writeLatin1String(bytes)) {
          return;
        }
      } else if (StringSerializer.isUtf16Coder(stringCoder)) {
        if (writeUtf16String(bytes)) {
          return;
        }
      }
    }
    writeStringChars(value);
  }

  private void writeStringChars(String value) {
    int length = value.length();
    ensure(length + 2);
    byte[] bytes = buffer;
    int pos = position;
    bytes[pos++] = (byte) '"';
    int i = 0;
    while (i + 4 <= length) {
      char c0 = value.charAt(i);
      char c1 = value.charAt(i + 1);
      char c2 = value.charAt(i + 2);
      char c3 = value.charAt(i + 3);
      if (isJsonAscii(c0) && isJsonAscii(c1) && isJsonAscii(c2) && isJsonAscii(c3)) {
        bytes[pos] = (byte) c0;
        bytes[pos + 1] = (byte) c1;
        bytes[pos + 2] = (byte) c2;
        bytes[pos + 3] = (byte) c3;
        pos += 4;
        i += 4;
      } else {
        break;
      }
    }
    while (i < length) {
      char ch = value.charAt(i);
      if (isJsonAscii(ch)) {
        bytes[pos++] = (byte) ch;
        i++;
      } else {
        position = pos;
        writeStringSlow(value, i, length);
        return;
      }
    }
    bytes[pos++] = (byte) '"';
    position = pos;
  }

  @Override
  public void writeFieldName(String name) {
    writeString(name);
    writeByteRaw((byte) ':');
  }

  @Override
  public void writeFieldName(JsonFieldInfo field) {
    writeRaw(field.utf8NamePrefix());
  }

  public void writeFieldName(JsonFieldInfo field, int index) {
    writeRaw(index == 0 ? field.utf8NamePrefix() : field.utf8CommaNamePrefix());
  }

  @Override
  public void writeIntFieldName(int value) {
    writeByteRaw((byte) '"');
    writeInt(value);
    writeByteRaw((byte) '"');
    writeByteRaw((byte) ':');
  }

  @Override
  public void writeLongFieldName(long value) {
    writeByteRaw((byte) '"');
    writeLong(value);
    writeByteRaw((byte) '"');
    writeByteRaw((byte) ':');
  }

  public void writeBooleanField(
      byte[] namePrefix, byte[] commaNamePrefix, int index, boolean value) {
    byte[] prefix = index == 0 ? namePrefix : commaNamePrefix;
    ensure(prefix.length + 5);
    writeRawNoEnsure(prefix);
    writeAsciiNoEnsure(value ? "true" : "false");
  }

  public void writeIntField(byte[] namePrefix, byte[] commaNamePrefix, int index, int value) {
    byte[] prefix = index == 0 ? namePrefix : commaNamePrefix;
    writeIntField(prefix, value);
  }

  public void writeIntField(byte[] prefix, int value) {
    ensure(prefix.length + 11);
    writeRawNoEnsure(prefix);
    writeIntNoEnsure(value);
  }

  public void writeObjectIntField(byte[] namePrefix, int value) {
    ensure(namePrefix.length + 12);
    buffer[position++] = (byte) '{';
    writeRawNoEnsure(namePrefix);
    writeIntNoEnsure(value);
  }

  public void writeLongField(byte[] namePrefix, byte[] commaNamePrefix, int index, long value) {
    byte[] prefix = index == 0 ? namePrefix : commaNamePrefix;
    writeLongField(prefix, value);
  }

  public void writeLongField(byte[] prefix, long value) {
    ensure(prefix.length + 20);
    writeRawNoEnsure(prefix);
    writeLongNoEnsure(value);
  }

  public void writeObjectLongField(byte[] namePrefix, long value) {
    ensure(namePrefix.length + 21);
    buffer[position++] = (byte) '{';
    writeRawNoEnsure(namePrefix);
    writeLongNoEnsure(value);
  }

  public void writeStringField(byte[] namePrefix, byte[] commaNamePrefix, int index, String value) {
    byte[] prefix = index == 0 ? namePrefix : commaNamePrefix;
    writeStringField(prefix, value);
  }

  public void writeStringField(byte[] prefix, String value) {
    if (STRING_BYTES_BACKED) {
      byte[] bytes = StringSerializer.getStringBytes(value);
      byte stringCoder = StringSerializer.getStringCoder(value);
      int start = position;
      if (StringSerializer.isLatin1Coder(stringCoder)) {
        ensure(prefix.length + bytes.length + 2);
        writeRawNoEnsure(prefix);
        if (writeLatin1StringNoEnsure(bytes)) {
          return;
        }
        position = start;
      } else if (StringSerializer.isUtf16Coder(stringCoder)) {
        ensure(prefix.length + (bytes.length >> 1) * 3 + 2);
        writeRawNoEnsure(prefix);
        if (writeUtf16StringNoEnsure(bytes)) {
          return;
        }
        position = start;
      }
    }
    writeStringFieldChars(prefix, value);
  }

  private void writeStringFieldChars(byte[] prefix, String value) {
    ensure(prefix.length + value.length() * 3 + 2);
    writeRawNoEnsure(prefix);
    writeStringNoEnsure(value);
  }

  public void writeStringElement(int index, String value) {
    int comma = index == 0 ? 0 : 1;
    if (value == null) {
      writeNullStringElement(comma);
      return;
    }
    if (STRING_BYTES_BACKED) {
      byte[] bytes = StringSerializer.getStringBytes(value);
      byte stringCoder = StringSerializer.getStringCoder(value);
      int start = position;
      if (StringSerializer.isLatin1Coder(stringCoder)) {
        ensure(comma + bytes.length + 2);
        if (comma != 0) {
          buffer[position++] = ',';
        }
        if (writeLatin1StringNoEnsure(bytes)) {
          return;
        }
        position = start;
      } else if (StringSerializer.isUtf16Coder(stringCoder)) {
        ensure(comma + (bytes.length >> 1) * 3 + 2);
        if (comma != 0) {
          buffer[position++] = ',';
        }
        if (writeUtf16StringNoEnsure(bytes)) {
          return;
        }
        position = start;
      }
    }
    writeStringElementChars(comma, value);
  }

  private void writeNullStringElement(int comma) {
    ensure(comma + 4);
    if (comma != 0) {
      buffer[position++] = ',';
    }
    writeAsciiNoEnsure("null");
  }

  private void writeStringElementChars(int comma, String value) {
    ensure(comma + value.length() * 3 + 2);
    if (comma != 0) {
      buffer[position++] = ',';
    }
    writeStringNoEnsure(value);
  }

  public void writeRawValue(byte[] value) {
    writeRaw(value);
  }

  @Override
  public void writeObjectStart() {
    writeByteRaw((byte) '{');
  }

  @Override
  public void writeObjectEnd() {
    writeByteRaw((byte) '}');
  }

  @Override
  public void writeArrayStart() {
    writeByteRaw((byte) '[');
  }

  @Override
  public void writeArrayEnd() {
    writeByteRaw((byte) ']');
  }

  @Override
  public void writeComma(int index) {
    if (index != 0) {
      writeByteRaw((byte) ',');
    }
  }

  private boolean writeLatin1String(byte[] value) {
    int length = value.length;
    ensure(length + 2);
    return writeLatin1StringNoEnsure(value);
  }

  private boolean writeLatin1StringNoEnsure(byte[] value) {
    int length = value.length;
    byte[] bytes = buffer;
    int start = position;
    int pos = start;
    bytes[pos++] = (byte) '"';
    int i = 0;
    int upperBound = length & ~15;
    for (; i < upperBound; i += 16) {
      long word0 = LittleEndian.getInt64(value, i);
      long word1 = LittleEndian.getInt64(value, i + 8);
      if (!isJsonAsciiWord(word0) || !isJsonAsciiWord(word1)) {
        break;
      }
      LittleEndian.putInt64(bytes, pos, word0);
      LittleEndian.putInt64(bytes, pos + 8, word1);
      pos += 16;
    }
    upperBound = length & ~7;
    for (; i < upperBound; i += 8) {
      long word = LittleEndian.getInt64(value, i);
      if (!isJsonAsciiWord(word)) {
        break;
      }
      LittleEndian.putInt64(bytes, pos, word);
      pos += 8;
    }
    if (i + 4 <= length) {
      int word = LittleEndian.getInt32(value, i);
      if (isJsonAsciiInt(word)) {
        LittleEndian.putInt32(bytes, pos, word);
        pos += 4;
        i += 4;
      }
    }
    for (; i < length; i++) {
      byte ch = value[i];
      if (isJsonAsciiByte(ch)) {
        bytes[pos++] = ch;
      } else {
        position = start;
        return false;
      }
    }
    bytes[pos++] = (byte) '"';
    position = pos;
    return true;
  }

  private boolean writeUtf16String(byte[] value) {
    int length = value.length;
    ensure((length >> 1) * 3 + 2);
    return writeUtf16StringNoEnsure(value);
  }

  private boolean writeUtf16StringNoEnsure(byte[] value) {
    int length = value.length;
    byte[] bytes = buffer;
    int start = position;
    int pos = start;
    bytes[pos++] = (byte) '"';
    int i = 0;
    int upperBound = length & ~7;
    for (; i < upperBound; i += 8) {
      long word = LittleEndian.getInt64(value, i);
      if ((word & UTF16_ASCII_MASK) != 0) {
        break;
      }
      int packed = packUtf16Ascii(word);
      if (!isJsonAsciiInt(packed)) {
        break;
      }
      LittleEndian.putInt32(bytes, pos, packed);
      pos += 4;
    }
    for (; i < length; i += 2) {
      char ch = StringSerializer.getBytesChar(value, i);
      if (ch < 0x80) {
        if (!isJsonAscii(ch)) {
          position = start;
          return false;
        }
        bytes[pos++] = (byte) ch;
      } else if (ch < 0x800) {
        bytes[pos++] = (byte) (0xC0 | (ch >>> 6));
        bytes[pos++] = (byte) (0x80 | (ch & 0x3F));
      } else if (Character.isSurrogate(ch)) {
        position = start;
        return false;
      } else {
        bytes[pos++] = (byte) (0xE0 | (ch >>> 12));
        bytes[pos++] = (byte) (0x80 | ((ch >>> 6) & 0x3F));
        bytes[pos++] = (byte) (0x80 | (ch & 0x3F));
      }
    }
    bytes[pos++] = (byte) '"';
    position = pos;
    return true;
  }

  private void writeEscapedChar(char ch) {
    switch (ch) {
      case '"':
        writeAscii("\\\"");
        return;
      case '\\':
        writeAscii("\\\\");
        return;
      case '\b':
        writeAscii("\\b");
        return;
      case '\f':
        writeAscii("\\f");
        return;
      case '\n':
        writeAscii("\\n");
        return;
      case '\r':
        writeAscii("\\r");
        return;
      case '\t':
        writeAscii("\\t");
        return;
      default:
        if (ch < 0x20) {
          writeUnicodeEscape(ch);
        } else if (ch < 0x80) {
          writeByteRaw((byte) ch);
        } else if (ch < 0x800) {
          ensure(2);
          buffer[position++] = (byte) (0xC0 | (ch >>> 6));
          buffer[position++] = (byte) (0x80 | (ch & 0x3F));
        } else {
          ensure(3);
          buffer[position++] = (byte) (0xE0 | (ch >>> 12));
          buffer[position++] = (byte) (0x80 | ((ch >>> 6) & 0x3F));
          buffer[position++] = (byte) (0x80 | (ch & 0x3F));
        }
    }
  }

  private void writeStringSlow(String value, int index, int length) {
    for (int i = index; i < length; i++) {
      char ch = value.charAt(i);
      if (Character.isHighSurrogate(ch)) {
        if (i + 1 >= length) {
          throw new ForyJsonException("Unpaired high surrogate in string");
        }
        char low = value.charAt(++i);
        if (!Character.isLowSurrogate(low)) {
          throw new ForyJsonException("Unpaired high surrogate in string");
        }
        writeCodePoint(Character.toCodePoint(ch, low));
      } else if (Character.isLowSurrogate(ch)) {
        throw new ForyJsonException("Unpaired low surrogate in string");
      } else {
        writeEscapedChar(ch);
      }
    }
    writeByteRaw((byte) '"');
  }

  private void writeStringNoEnsure(String value) {
    if (STRING_BYTES_BACKED) {
      byte[] bytes = StringSerializer.getStringBytes(value);
      byte stringCoder = StringSerializer.getStringCoder(value);
      if (StringSerializer.isLatin1Coder(stringCoder)) {
        if (writeLatin1StringNoEnsure(bytes)) {
          return;
        }
      } else if (StringSerializer.isUtf16Coder(stringCoder)) {
        if (writeUtf16StringNoEnsure(bytes)) {
          return;
        }
      }
    }
    writeStringCharsNoEnsure(value);
  }

  private void writeStringCharsNoEnsure(String value) {
    int length = value.length();
    byte[] bytes = buffer;
    int pos = position;
    bytes[pos++] = (byte) '"';
    int i = 0;
    while (i + 4 <= length) {
      char c0 = value.charAt(i);
      char c1 = value.charAt(i + 1);
      char c2 = value.charAt(i + 2);
      char c3 = value.charAt(i + 3);
      if (isJsonAscii(c0) && isJsonAscii(c1) && isJsonAscii(c2) && isJsonAscii(c3)) {
        bytes[pos] = (byte) c0;
        bytes[pos + 1] = (byte) c1;
        bytes[pos + 2] = (byte) c2;
        bytes[pos + 3] = (byte) c3;
        pos += 4;
        i += 4;
      } else {
        break;
      }
    }
    while (i < length) {
      char ch = value.charAt(i);
      if (isJsonAscii(ch)) {
        bytes[pos++] = (byte) ch;
        i++;
      } else {
        position = pos;
        writeStringSlow(value, i, length);
        return;
      }
    }
    bytes[pos++] = (byte) '"';
    position = pos;
  }

  private void writeCodePoint(int codePoint) {
    ensure(4);
    buffer[position++] = (byte) (0xF0 | (codePoint >>> 18));
    buffer[position++] = (byte) (0x80 | ((codePoint >>> 12) & 0x3F));
    buffer[position++] = (byte) (0x80 | ((codePoint >>> 6) & 0x3F));
    buffer[position++] = (byte) (0x80 | (codePoint & 0x3F));
  }

  private void writeUnicodeEscape(char ch) {
    ensure(6);
    buffer[position++] = '\\';
    buffer[position++] = 'u';
    buffer[position++] = '0';
    buffer[position++] = '0';
    buffer[position++] = (byte) hex((ch >>> 4) & 0xF);
    buffer[position++] = (byte) hex(ch & 0xF);
  }

  private void writeAscii(String value) {
    int length = value.length();
    ensure(length);
    writeAsciiNoEnsure(value);
  }

  private void writeAsciiNoEnsure(String value) {
    int length = value.length();
    for (int i = 0; i < length; i++) {
      buffer[position++] = (byte) value.charAt(i);
    }
  }

  private void writeRaw(byte[] bytes) {
    ensure(bytes.length);
    writeRawNoEnsure(bytes);
  }

  private void writeRawNoEnsure(byte[] bytes) {
    System.arraycopy(bytes, 0, buffer, position, bytes.length);
    position += bytes.length;
  }

  private void writeByteRaw(byte value) {
    ensure(1);
    buffer[position++] = value;
  }

  private void reverse(int start, int end) {
    while (start < end) {
      byte tmp = buffer[start];
      buffer[start++] = buffer[end];
      buffer[end--] = tmp;
    }
  }

  private void ensure(int additional) {
    int minCapacity = position + additional;
    if (minCapacity > buffer.length) {
      grow(minCapacity);
    }
  }

  private void grow(int minCapacity) {
    int newCapacity = buffer.length << 1;
    while (newCapacity < minCapacity) {
      newCapacity <<= 1;
    }
    buffer = Arrays.copyOf(buffer, newCapacity);
  }

  private static char hex(int value) {
    return (char) (value < 10 ? '0' + value : 'a' + value - 10);
  }

  private static boolean isJsonAscii(char ch) {
    return ch > 0x1F && ch < 0x80 && ch != '"' && ch != '\\';
  }

  private static boolean isJsonAsciiByte(byte value) {
    int ch = value & 0xff;
    return ch > 0x1F && ch < 0x80 && ch != '"' && ch != '\\';
  }

  private static boolean isJsonAsciiWord(long word) {
    return (((word + ASCII_CONTROL_OFFSET) & ~word) & HIGH_BITS) == HIGH_BITS
        && (((word ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES) & HIGH_BITS) == HIGH_BITS
        && (((word ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES) & HIGH_BITS) == HIGH_BITS;
  }

  private static boolean isJsonAsciiInt(int word) {
    return (((word + INT_ASCII_CONTROL_OFFSET) & ~word) & INT_HIGH_BITS) == INT_HIGH_BITS
        && (((word ^ INT_QUOTE_BYTES_COMPLEMENT) + INT_ONE_BYTES) & INT_HIGH_BITS) == INT_HIGH_BITS
        && (((word ^ INT_BACKSLASH_BYTES_COMPLEMENT) + INT_ONE_BYTES) & INT_HIGH_BITS)
            == INT_HIGH_BITS;
  }

  private static int packUtf16Ascii(long word) {
    return (int)
        ((word & 0xFFL)
            | ((word >>> 8) & 0xFF00L)
            | ((word >>> 16) & 0xFF0000L)
            | ((word >>> 24) & 0xFF000000L));
  }

  private void writeIntNoEnsure(int value) {
    if (value == Integer.MIN_VALUE) {
      writeRawNoEnsure(MIN_INT_BYTES);
      return;
    }
    if (value < 0) {
      buffer[position++] = (byte) '-';
      value = -value;
    }
    writePositiveIntNoEnsure(value);
  }

  private void writeLongNoEnsure(long value) {
    if (value == Long.MIN_VALUE) {
      writeRawNoEnsure(MIN_LONG_BYTES);
      return;
    }
    if (value < 0) {
      buffer[position++] = (byte) '-';
      value = -value;
    }
    if (value <= Integer.MAX_VALUE) {
      writePositiveIntNoEnsure((int) value);
      return;
    }
    int start = position;
    do {
      buffer[position++] = (byte) ('0' + value % 10);
      value /= 10;
    } while (value != 0);
    reverse(start, position - 1);
  }

  private void writePositiveIntNoEnsure(int value) {
    byte[] bytes = buffer;
    int pos = position;
    if (value < 10000) {
      position = writeIntUpTo4(bytes, pos, value);
      return;
    }
    int high = divide10000(value);
    int low = value - high * 10000;
    if (high < 10000) {
      if (high >= 1000) {
        position = writePadded8(bytes, pos, high, low);
        return;
      }
      pos = writeIntUpTo4(bytes, pos, high);
      position = writePadded4(bytes, pos, low);
      return;
    }
    int top = divide10000(high);
    int middle = high - top * 10000;
    pos = writeIntUpTo4(bytes, pos, top);
    position = writePadded8(bytes, pos, middle, low);
  }

  private static int divide10000(int value) {
    return (int) (((long) value * 1759218605L) >> 44);
  }

  private static int writeIntUpTo4(byte[] bytes, int pos, int value) {
    if (value < 1000) {
      return writeIntUpTo3(bytes, pos, value);
    }
    return writePadded4(bytes, pos, value);
  }

  private static int writeIntUpTo3(byte[] bytes, int pos, int value) {
    int digits = DIGIT_TRIPLES[value];
    int skip = digits & 0xFF;
    LittleEndian.putInt32(bytes, pos, digits >>> ((skip + 1) << 3));
    return pos + 3 - skip;
  }

  private static int writePadded4(byte[] bytes, int pos, int value) {
    LittleEndian.putInt32(bytes, pos, DIGIT_QUADS[value]);
    return pos + 4;
  }

  private static int writePadded8(byte[] bytes, int pos, int high, int low) {
    long value = (DIGIT_QUADS[high] & 0xFFFFFFFFL) | ((DIGIT_QUADS[low] & 0xFFFFFFFFL) << 32);
    LittleEndian.putInt64(bytes, pos, value);
    return pos + 8;
  }
}
