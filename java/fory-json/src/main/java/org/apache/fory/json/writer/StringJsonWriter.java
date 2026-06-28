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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.memory.LittleEndian;
import org.apache.fory.memory.NativeByteOrder;
import org.apache.fory.serializer.StringSerializer;

public final class StringJsonWriter extends JsonWriter {
  private static final byte LATIN1 = 0;
  private static final byte UTF16 = 1;
  private static final int RETAINED_CAPACITY = 8192;
  private static final byte[] MIN_INT_BYTES = "-2147483648".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] MIN_LONG_BYTES =
      "-9223372036854775808".getBytes(StandardCharsets.ISO_8859_1);
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
  private static final byte[] DIGIT_HUNDREDS = new byte[1000];
  private static final byte[] DIGIT_TENS = new byte[1000];
  private static final byte[] DIGIT_ONES = new byte[1000];
  private static final int[] DIGIT_TRIPLES = new int[1000];
  private static final int[] DIGIT_QUADS = new int[10000];
  private static final long UTF16_BYTE_MASK = 0x00FF00FF00FF00FFL;
  private static final long UTF16_PAIR_MASK = 0x0000FFFF0000FFFFL;
  private static final boolean STRING_BYTES_BACKED = StringSerializer.isBytesBackedString();
  private static final boolean LITTLE_ENDIAN = NativeByteOrder.IS_LITTLE_ENDIAN;

  static {
    for (int i = 0; i < 1000; i++) {
      int c0 = '0' + i / 100;
      int c1 = '0' + (i / 10) % 10;
      int c2 = '0' + i % 10;
      DIGIT_HUNDREDS[i] = (byte) c0;
      DIGIT_TENS[i] = (byte) c1;
      DIGIT_ONES[i] = (byte) c2;
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
  private byte[] scratch;
  private byte coder;
  private int position;

  public StringJsonWriter(boolean writeNullFields) {
    this(writeNullFields, new byte[512]);
  }

  public StringJsonWriter(boolean writeNullFields, byte[] buffer) {
    super(writeNullFields);
    this.buffer = buffer;
    scratch = new byte[buffer.length];
  }

  public void reset() {
    if (buffer.length > RETAINED_CAPACITY) {
      buffer = new byte[RETAINED_CAPACITY];
    }
    if (scratch.length > RETAINED_CAPACITY) {
      scratch = new byte[RETAINED_CAPACITY];
    }
    coder = LATIN1;
    position = 0;
  }

  public String toJson() {
    // The returned String may zero-copy this exact array, so pooled writer storage is never
    // exposed.
    byte[] bytes = Arrays.copyOf(buffer, position);
    return StringSerializer.newBytesStringZeroCopy(coder, bytes);
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
    if (coder == LATIN1) {
      ensure(11);
      writeIntLatin1NoEnsure(value);
      return;
    }
    ensure(22);
    writeIntUtf16NoEnsure(value);
  }

  @Override
  public void writeLong(long value) {
    if (coder == LATIN1) {
      writeLongLatin1(value);
      return;
    }
    writeLongUtf16(value);
  }

  private void writeLongLatin1(long value) {
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
    if (coder == LATIN1) {
      writeStringLatin1(value);
      return;
    }
    writeStringUtf16(value);
  }

  private void writeStringLatin1(String value) {
    if (STRING_BYTES_BACKED) {
      byte[] bytes = StringSerializer.getStringBytes(value);
      byte stringCoder = StringSerializer.getStringCoder(value);
      if (StringSerializer.isLatin1Coder(stringCoder)) {
        ensure(bytes.length + 2);
        writeLatin1StringNoEnsure(bytes);
        return;
      }
    }
    ensure(value.length() * 6 + 2);
    writeStringCharsNoEnsure(value);
  }

  @Override
  public void writeFieldName(String name) {
    writeString(name);
    writeByteRaw((byte) ':');
  }

  @Override
  public void writeFieldName(JsonFieldInfo field) {
    writeRaw(field.stringNamePrefix());
  }

  public void writeFieldName(JsonFieldInfo field, int index) {
    writeRaw(index == 0 ? field.stringNamePrefix() : field.stringCommaNamePrefix());
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
    if (coder == UTF16) {
      writeBooleanFieldUtf16(prefix, value);
      return;
    }
    ensure(prefix.length + 5);
    writeRawLatin1NoEnsure(prefix);
    writeAsciiLatin1NoEnsure(value ? "true" : "false");
  }

  private void writeBooleanFieldUtf16(byte[] prefix, boolean value) {
    ensure((prefix.length + 5) << 1);
    writeRawUtf16NoEnsure(prefix);
    writeAsciiUtf16NoEnsure(value ? "true" : "false", value ? 4 : 5);
  }

  public void writeIntField(byte[] namePrefix, byte[] commaNamePrefix, int index, int value) {
    byte[] prefix = index == 0 ? namePrefix : commaNamePrefix;
    writeIntField(prefix, value);
  }

  public void writeIntField(byte[] prefix, int value) {
    if (coder == LATIN1) {
      writeIntFieldLatin1(prefix, value);
      return;
    }
    writeIntFieldUtf16(prefix, value);
  }

  private void writeIntFieldLatin1(byte[] prefix, int value) {
    ensure(prefix.length + 11);
    writeRawLatin1NoEnsure(prefix);
    writeIntNoEnsure(value);
  }

  private void writeIntFieldUtf16(byte[] prefix, int value) {
    ensure((prefix.length << 1) + 22);
    writeRawUtf16NoEnsure(prefix);
    writeIntUtf16NoEnsure(value);
  }

  public void writeObjectIntField(byte[] namePrefix, int value) {
    if (coder == LATIN1) {
      writeObjectIntFieldLatin1(namePrefix, value);
      return;
    }
    writeObjectIntFieldUtf16(namePrefix, value);
  }

  private void writeObjectIntFieldLatin1(byte[] namePrefix, int value) {
    ensure(namePrefix.length + 12);
    buffer[position++] = (byte) '{';
    writeRawLatin1NoEnsure(namePrefix);
    writeIntNoEnsure(value);
  }

  private void writeObjectIntFieldUtf16(byte[] namePrefix, int value) {
    ensure(((namePrefix.length + 1) << 1) + 22);
    writeUtf16ByteNoEnsure((byte) '{');
    writeRawUtf16NoEnsure(namePrefix);
    writeIntUtf16NoEnsure(value);
  }

  public void writeLongField(byte[] namePrefix, byte[] commaNamePrefix, int index, long value) {
    byte[] prefix = index == 0 ? namePrefix : commaNamePrefix;
    writeLongField(prefix, value);
  }

  public void writeLongField(byte[] prefix, long value) {
    if (coder == LATIN1) {
      writeLongFieldLatin1(prefix, value);
      return;
    }
    writeLongFieldUtf16(prefix, value);
  }

  private void writeLongFieldLatin1(byte[] prefix, long value) {
    ensure(prefix.length + 20);
    writeRawLatin1NoEnsure(prefix);
    writeLongNoEnsure(value);
  }

  private void writeLongFieldUtf16(byte[] prefix, long value) {
    ensure((prefix.length << 1) + 40);
    writeRawUtf16NoEnsure(prefix);
    writeLongUtf16NoEnsure(value);
  }

  public void writeObjectLongField(byte[] namePrefix, long value) {
    if (coder == LATIN1) {
      writeObjectLongFieldLatin1(namePrefix, value);
      return;
    }
    writeObjectLongFieldUtf16(namePrefix, value);
  }

  private void writeObjectLongFieldLatin1(byte[] namePrefix, long value) {
    ensure(namePrefix.length + 21);
    buffer[position++] = (byte) '{';
    writeRawLatin1NoEnsure(namePrefix);
    writeLongNoEnsure(value);
  }

  private void writeObjectLongFieldUtf16(byte[] namePrefix, long value) {
    ensure(((namePrefix.length + 1) << 1) + 40);
    writeUtf16ByteNoEnsure((byte) '{');
    writeRawUtf16NoEnsure(namePrefix);
    writeLongUtf16NoEnsure(value);
  }

  public void writeStringField(byte[] namePrefix, byte[] commaNamePrefix, int index, String value) {
    byte[] prefix = index == 0 ? namePrefix : commaNamePrefix;
    writeStringField(prefix, value);
  }

  public void writeStringField(byte[] prefix, String value) {
    if (coder == LATIN1) {
      writeStringFieldLatin1(prefix, value);
      return;
    }
    writeStringFieldUtf16(prefix, value);
  }

  private void writeStringFieldLatin1(byte[] prefix, String value) {
    if (STRING_BYTES_BACKED) {
      byte[] bytes = StringSerializer.getStringBytes(value);
      byte stringCoder = StringSerializer.getStringCoder(value);
      if (StringSerializer.isLatin1Coder(stringCoder)) {
        ensure(prefix.length + bytes.length + 2);
        writeRawLatin1NoEnsure(prefix);
        writeLatin1StringNoEnsure(bytes);
        return;
      }
    }
    ensure(prefix.length + value.length() * 6 + 2);
    writeRawLatin1NoEnsure(prefix);
    writeStringCharsNoEnsure(value);
  }

  private void writeStringFieldUtf16(byte[] prefix, String value) {
    ensure(prefix.length << 1);
    writeRawUtf16NoEnsure(prefix);
    writeString(value);
  }

  public void writeStringElement(int index, String value) {
    int comma = index == 0 ? 0 : 1;
    if (value == null) {
      writeNullStringElement(comma);
      return;
    }
    if (coder == LATIN1) {
      writeStringElementLatin1(comma, value);
      return;
    }
    writeStringElementUtf16(comma, value);
  }

  private void writeNullStringElement(int comma) {
    if (coder == UTF16) {
      ensure((comma + 4) << 1);
      if (comma != 0) {
        writeUtf16ByteNoEnsure((byte) ',');
      }
      writeAsciiUtf16NoEnsure("null", 4);
      return;
    }
    ensure(comma + 4);
    if (comma != 0) {
      buffer[position++] = ',';
    }
    writeAsciiLatin1NoEnsure("null");
  }

  private void writeStringElementLatin1(int comma, String value) {
    if (STRING_BYTES_BACKED) {
      byte[] bytes = StringSerializer.getStringBytes(value);
      if (StringSerializer.isLatin1Coder(StringSerializer.getStringCoder(value))) {
        ensure(comma + bytes.length + 2);
        if (comma != 0) {
          buffer[position++] = ',';
        }
        writeLatin1StringNoEnsure(bytes);
        return;
      }
    }
    ensure(comma + value.length() * 6 + 2);
    if (comma != 0) {
      buffer[position++] = ',';
    }
    writeStringNoEnsure(value);
  }

  private void writeStringElementUtf16(int comma, String value) {
    ensure(comma << 1);
    if (comma != 0) {
      writeUtf16ByteNoEnsure((byte) ',');
    }
    writeStringUtf16(value);
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

  private void writeStringNoEnsure(String value) {
    if (coder == LATIN1) {
      if (STRING_BYTES_BACKED) {
        byte[] bytes = StringSerializer.getStringBytes(value);
        byte stringCoder = StringSerializer.getStringCoder(value);
        if (StringSerializer.isLatin1Coder(stringCoder)) {
          writeLatin1StringNoEnsure(bytes);
          return;
        }
      }
      writeStringCharsNoEnsure(value);
      return;
    }
    writeStringUtf16(value);
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
      if (isJsonLatin1(c0) && isJsonLatin1(c1) && isJsonLatin1(c2) && isJsonLatin1(c3)) {
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
      if (isJsonLatin1(ch)) {
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

  private void writeLatin1StringNoEnsure(byte[] value) {
    int length = value.length;
    byte[] bytes = buffer;
    int pos = position;
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
    while (i < length) {
      byte ch = value[i];
      if (isJsonLatin1Byte(ch)) {
        bytes[pos++] = ch;
        i++;
      } else {
        position = pos;
        writeLatin1StringSlow(value, i, length);
        return;
      }
    }
    bytes[pos++] = (byte) '"';
    position = pos;
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
        writeCharRaw(ch);
        writeCharRaw(low);
      } else if (Character.isLowSurrogate(ch)) {
        throw new ForyJsonException("Unpaired low surrogate in string");
      } else {
        writeEscapedChar(ch);
      }
    }
    writeByteRaw((byte) '"');
  }

  private void writeLatin1StringSlow(byte[] value, int index, int length) {
    for (int i = index; i < length; i++) {
      writeEscapedChar((char) (value[i] & 0xff));
    }
    writeByteRaw((byte) '"');
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
        } else {
          writeCharRaw(ch);
        }
    }
  }

  private void writeStringUtf16(String value) {
    int length = value.length();
    ensure((length + 2) << 1);
    writeUtf16ByteNoEnsure((byte) '"');
    for (int i = 0; i < length; i++) {
      char ch = value.charAt(i);
      if (isJsonUtf16(ch)) {
        writeUtf16CharNoEnsure(ch);
      } else {
        writeStringUtf16Slow(value, i, length);
        return;
      }
    }
    writeByteRaw((byte) '"');
  }

  private void writeStringUtf16Slow(String value, int index, int length) {
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
        ensure(4);
        writeUtf16CharNoEnsure(ch);
        writeUtf16CharNoEnsure(low);
      } else if (Character.isLowSurrogate(ch)) {
        throw new ForyJsonException("Unpaired low surrogate in string");
      } else {
        writeEscapedChar(ch);
      }
    }
    writeByteRaw((byte) '"');
  }

  private void writeUnicodeEscape(char ch) {
    if (coder == UTF16) {
      ensure(12);
      writeUtf16ByteNoEnsure((byte) '\\');
      writeUtf16ByteNoEnsure((byte) 'u');
      writeUtf16CharNoEnsure(hex((ch >>> 12) & 0xF));
      writeUtf16CharNoEnsure(hex((ch >>> 8) & 0xF));
      writeUtf16CharNoEnsure(hex((ch >>> 4) & 0xF));
      writeUtf16CharNoEnsure(hex(ch & 0xF));
    } else {
      ensure(6);
      buffer[position++] = '\\';
      buffer[position++] = 'u';
      buffer[position++] = (byte) hex((ch >>> 12) & 0xF);
      buffer[position++] = (byte) hex((ch >>> 8) & 0xF);
      buffer[position++] = (byte) hex((ch >>> 4) & 0xF);
      buffer[position++] = (byte) hex(ch & 0xF);
    }
  }

  private void writeAscii(String value) {
    int length = value.length();
    if (coder == LATIN1) {
      ensure(length);
      writeAsciiNoEnsure(value);
      return;
    }
    writeAsciiUtf16(value, length);
  }

  private void writeAsciiNoEnsure(String value) {
    int length = value.length();
    if (coder == LATIN1) {
      for (int i = 0; i < length; i++) {
        buffer[position++] = (byte) value.charAt(i);
      }
      return;
    }
    writeAsciiUtf16NoEnsure(value, length);
  }

  private void writeAsciiUtf16(String value, int length) {
    ensure(length << 1);
    writeAsciiUtf16NoEnsure(value, length);
  }

  private void writeAsciiUtf16NoEnsure(String value, int length) {
    byte[] bytes = buffer;
    int pos = position;
    for (int i = 0; i < length; i++) {
      pos = putUtf16Char(bytes, pos, value.charAt(i));
    }
    position = pos;
  }

  private void writeAsciiLatin1NoEnsure(String value) {
    int length = value.length();
    for (int i = 0; i < length; i++) {
      buffer[position++] = (byte) value.charAt(i);
    }
  }

  private void writeRaw(byte[] bytes) {
    if (coder == LATIN1) {
      ensure(bytes.length);
      writeRawNoEnsure(bytes);
      return;
    }
    writeRawUtf16(bytes);
  }

  private void writeRawNoEnsure(byte[] bytes) {
    if (coder == LATIN1) {
      System.arraycopy(bytes, 0, buffer, position, bytes.length);
      position += bytes.length;
      return;
    }
    writeRawUtf16NoEnsure(bytes);
  }

  private void writeRawUtf16(byte[] bytes) {
    ensure(bytes.length << 1);
    writeRawUtf16NoEnsure(bytes);
  }

  private void writeRawUtf16NoEnsure(byte[] bytes) {
    byte[] target = buffer;
    int pos = position;
    for (byte value : bytes) {
      pos = putUtf16Char(target, pos, (char) (value & 0xff));
    }
    position = pos;
  }

  private void writeRawLatin1NoEnsure(byte[] bytes) {
    System.arraycopy(bytes, 0, buffer, position, bytes.length);
    position += bytes.length;
  }

  private void writeByteRaw(byte value) {
    if (coder == LATIN1) {
      ensure(1);
      buffer[position++] = value;
      return;
    }
    writeByteRawUtf16(value);
  }

  private void writeByteRawUtf16(byte value) {
    ensure(2);
    writeUtf16ByteNoEnsure(value);
  }

  private void writeCharRaw(char value) {
    if (coder == LATIN1 && value <= 0xff) {
      ensure(1);
      buffer[position++] = (byte) value;
      return;
    }
    writeCharRawUtf16(value);
  }

  private void writeCharRawUtf16(char value) {
    if (coder == LATIN1) {
      upgradeToUtf16((position << 1) + 2);
    } else {
      ensure(2);
    }
    writeUtf16CharNoEnsure(value);
  }

  private void reverse(int start, int end) {
    while (start < end) {
      byte tmp = buffer[start];
      buffer[start++] = buffer[end];
      buffer[end--] = tmp;
    }
  }

  private void reverseUtf16Chars(int start, int end) {
    while (start < end) {
      byte b0 = buffer[start];
      byte b1 = buffer[start + 1];
      buffer[start] = buffer[end];
      buffer[start + 1] = buffer[end + 1];
      buffer[end] = b0;
      buffer[end + 1] = b1;
      start += 2;
      end -= 2;
    }
  }

  private void writeUtf16ByteNoEnsure(byte value) {
    position = putUtf16Char(buffer, position, (char) (value & 0xff));
  }

  private void writeUtf16CharNoEnsure(char value) {
    position = putUtf16Char(buffer, position, value);
  }

  private static int putUtf16Char(byte[] bytes, int pos, char value) {
    if (LITTLE_ENDIAN) {
      bytes[pos] = (byte) value;
      bytes[pos + 1] = (byte) (value >>> 8);
    } else {
      bytes[pos] = (byte) (value >>> 8);
      bytes[pos + 1] = (byte) value;
    }
    return pos + 2;
  }

  private void ensure(int additional) {
    int minCapacity = position + additional;
    if (minCapacity > buffer.length) {
      grow(minCapacity);
    }
  }

  private void grow(int minCapacity) {
    buffer = Arrays.copyOf(buffer, growCapacity(buffer.length, minCapacity));
  }

  private void upgradeToUtf16(int minCapacity) {
    int oldPosition = position;
    int newPosition = oldPosition << 1;
    int required = Math.max(minCapacity, newPosition);
    byte[] source = buffer;
    byte[] target = scratch;
    int minTargetCapacity = Math.max(source.length, required);
    if (target.length < minTargetCapacity) {
      target = growScratch(minTargetCapacity);
    }
    if (LITTLE_ENDIAN) {
      widenLatin1ToUtf16LE(source, target, oldPosition);
    } else {
      widenLatin1ToUtf16BE(source, target, oldPosition);
    }
    scratch = source;
    buffer = target;
    coder = UTF16;
    position = newPosition;
  }

  private byte[] growScratch(int minCapacity) {
    return new byte[growCapacity(buffer.length, minCapacity)];
  }

  private static int growCapacity(int capacity, int minCapacity) {
    int expanded = capacity + Math.max(capacity, 1);
    return expanded >= minCapacity && expanded > 0 ? expanded : minCapacity;
  }

  private static void widenLatin1ToUtf16LE(byte[] source, byte[] target, int length) {
    // JDK21 AArch64 C2 does not SuperWord-vectorize the plain byte-stride widening loop; hsdis
    // shows scalar ldrsb/strb. Keep this explicit 8-byte widening path so the hot upgrade uses
    // wide loads/stores without direct Unsafe in fory-json.
    int i = 0;
    int j = 0;
    int bulkEnd = length & ~7;
    for (; i < bulkEnd; i += 8, j += 16) {
      long word = LittleEndian.getInt64(source, i);
      LittleEndian.putInt64(target, j, spreadLatin1ToUtf16(word & 0xFFFFFFFFL));
      LittleEndian.putInt64(target, j + 8, spreadLatin1ToUtf16(word >>> 32));
    }
    for (; i < length; i++, j += 2) {
      target[j] = source[i];
      target[j + 1] = 0;
    }
  }

  private static void widenLatin1ToUtf16BE(byte[] source, byte[] target, int length) {
    int i = 0;
    int j = 0;
    int bulkEnd = length & ~7;
    for (; i < bulkEnd; i += 8, j += 16) {
      long word = LittleEndian.getInt64(source, i);
      LittleEndian.putInt64(target, j, spreadLatin1ToUtf16(word & 0xFFFFFFFFL) << 8);
      LittleEndian.putInt64(target, j + 8, spreadLatin1ToUtf16(word >>> 32) << 8);
    }
    for (; i < length; i++, j += 2) {
      target[j] = 0;
      target[j + 1] = source[i];
    }
  }

  private static long spreadLatin1ToUtf16(long value) {
    value = (value | (value << 16)) & UTF16_PAIR_MASK;
    return (value | (value << 8)) & UTF16_BYTE_MASK;
  }

  private static boolean isJsonLatin1(char ch) {
    return ch > 0x1F && ch <= 0xff && ch != '"' && ch != '\\';
  }

  private static boolean isJsonUtf16(char ch) {
    return ch > 0x1F && ch != '"' && ch != '\\' && !Character.isSurrogate(ch);
  }

  private static boolean isJsonLatin1Byte(byte value) {
    int ch = value & 0xff;
    return ch > 0x1F && ch != '"' && ch != '\\';
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

  private void writeIntNoEnsure(int value) {
    if (coder == LATIN1) {
      writeIntLatin1NoEnsure(value);
      return;
    }
    writeIntUtf16NoEnsure(value);
  }

  private void writeIntLatin1NoEnsure(int value) {
    if (value == Integer.MIN_VALUE) {
      writeRawLatin1NoEnsure(MIN_INT_BYTES);
      return;
    }
    if (value < 0) {
      buffer[position++] = (byte) '-';
      value = -value;
    }
    writePositiveIntNoEnsure(value);
  }

  private void writeLongNoEnsure(long value) {
    if (coder == LATIN1) {
      writeLongLatin1NoEnsure(value);
      return;
    }
    writeLongUtf16NoEnsure(value);
  }

  private void writeLongLatin1NoEnsure(long value) {
    if (value == Long.MIN_VALUE) {
      writeRawLatin1NoEnsure(MIN_LONG_BYTES);
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

  private void writeLongUtf16(long value) {
    ensure(40);
    writeLongUtf16NoEnsure(value);
  }

  private void writeIntUtf16NoEnsure(int value) {
    if (value == Integer.MIN_VALUE) {
      writeRawUtf16NoEnsure(MIN_INT_BYTES);
      return;
    }
    if (value < 0) {
      writeUtf16ByteNoEnsure((byte) '-');
      value = -value;
    }
    writePositiveIntUtf16NoEnsure(value);
  }

  private void writeLongUtf16NoEnsure(long value) {
    if (value == Long.MIN_VALUE) {
      writeRawUtf16NoEnsure(MIN_LONG_BYTES);
      return;
    }
    if (value < 0) {
      writeUtf16ByteNoEnsure((byte) '-');
      value = -value;
    }
    if (value <= Integer.MAX_VALUE) {
      writePositiveIntUtf16NoEnsure((int) value);
      return;
    }
    int start = position;
    do {
      writeUtf16CharNoEnsure((char) ('0' + value % 10));
      value /= 10;
    } while (value != 0);
    reverseUtf16Chars(start, position - 2);
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

  private void writePositiveIntUtf16NoEnsure(int value) {
    if (value < 10000) {
      writeIntUpTo4Utf16(value);
      return;
    }
    int high = divide10000(value);
    int low = value - high * 10000;
    if (high < 10000) {
      writeIntUpTo4Utf16(high);
      writePadded4Utf16(low);
      return;
    }
    int top = divide10000(high);
    int middle = high - top * 10000;
    writeIntUpTo4Utf16(top);
    writePadded4Utf16(middle);
    writePadded4Utf16(low);
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

  private void writeIntUpTo4Utf16(int value) {
    if (value < 10) {
      writeUtf16ByteNoEnsure(DIGIT_ONES[value]);
    } else if (value < 100) {
      writeUtf16ByteNoEnsure(DIGIT_TENS[value]);
      writeUtf16ByteNoEnsure(DIGIT_ONES[value]);
    } else if (value < 1000) {
      writePadded3Utf16(value);
    } else {
      writePadded4Utf16(value);
    }
  }

  private void writePadded3Utf16(int value) {
    writeUtf16ByteNoEnsure(DIGIT_HUNDREDS[value]);
    writeUtf16ByteNoEnsure(DIGIT_TENS[value]);
    writeUtf16ByteNoEnsure(DIGIT_ONES[value]);
  }

  private void writePadded4Utf16(int value) {
    int high = value / 100;
    int low = value - high * 100;
    writeUtf16ByteNoEnsure((byte) ('0' + high / 10));
    writeUtf16ByteNoEnsure((byte) ('0' + high % 10));
    writeUtf16ByteNoEnsure((byte) ('0' + low / 10));
    writeUtf16ByteNoEnsure((byte) ('0' + low % 10));
  }

  private static char hex(int value) {
    return (char) (value < 10 ? '0' + value : 'a' + value - 10);
  }
}
