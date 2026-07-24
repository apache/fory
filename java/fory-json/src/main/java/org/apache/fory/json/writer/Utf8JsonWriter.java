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

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.JsonConfig;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.memory.LittleEndian;
import org.apache.fory.serializer.StringSerializer;

/**
 * Concrete writer that emits UTF-8 JSON directly into an owned byte buffer.
 *
 * <p>Primitive numbers, field tokens, Latin1 strings, and UTF16 strings write directly to the
 * buffer; Unicode strings validate surrogate pairs while encoding. {@link #toJsonBytes()} returns a
 * detached copy, while {@link #writeTo(OutputStream)} writes the active range without closing or
 * flushing the destination. Reset applies the configured retained-buffer limit.
 *
 * <p>Finite float and double spelling comes from the JDK formatter, directly when available and
 * through a retained {@link StringBuilder} otherwise. Compact {@link BigDecimal} values are emitted
 * directly with JDK-compatible spelling; inflated values and out-of-long {@link BigInteger} values
 * use canonical JDK text on the cold arbitrary-precision path. The {@link Appendable} methods emit
 * escaped string content without adding surrounding quotes and are used by formatter-owned quoted
 * values.
 */
public final class Utf8JsonWriter extends JsonWriter implements Appendable {
  private static final byte[] MIN_INT_BYTES =
      "-2147483648".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
  private static final byte[] BASE64_DIGITS =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
          .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
  private static final byte[] MIN_LONG_BYTES =
      "-9223372036854775808".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
  private static final byte[] NAN_BYTES =
      "\"NaN\"".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
  private static final byte[] POSITIVE_INFINITY_BYTES =
      "\"Infinity\"".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
  private static final byte[] NEGATIVE_INFINITY_BYTES =
      "\"-Infinity\"".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
  private static final long EIGHT_DIGITS = 100_000_000L;
  private static final long HIGH_BITS = 0x8080808080808080L;
  private static final int INT_HIGH_BITS = 0x80808080;
  private static final int SHORT_HIGH_BITS = 0x8080;
  private static final long ASCII_CONTROL_OFFSET = 0x6060606060606060L;
  private static final int INT_ASCII_CONTROL_OFFSET = 0x60606060;
  private static final int SHORT_ASCII_CONTROL_OFFSET = 0x6060;
  private static final long ASCII_GT_QUOTE_OFFSET = 0x5D5D5D5D5D5D5D5DL;
  private static final int INT_ASCII_GT_QUOTE_OFFSET = 0x5D5D5D5D;
  private static final int SHORT_ASCII_GT_QUOTE_OFFSET = 0x5D5D;
  private static final long ONE_BYTES = 0x0101010101010101L;
  private static final int INT_ONE_BYTES = 0x01010101;
  private static final int SHORT_ONE_BYTES = 0x0101;
  private static final byte[] HEX_DIGITS =
      "0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
  private static final long QUOTE_BYTES_COMPLEMENT = ~0x2222222222222222L;
  private static final int INT_QUOTE_BYTES_COMPLEMENT = ~0x22222222;
  private static final int SHORT_QUOTE_BYTES_COMPLEMENT = ~0x2222;
  private static final long BACKSLASH_BYTES_COMPLEMENT = ~0x5C5C5C5C5C5C5C5CL;
  private static final int INT_BACKSLASH_BYTES_COMPLEMENT = ~0x5C5C5C5C;
  private static final int SHORT_BACKSLASH_BYTES_COMPLEMENT = ~0x5C5C;
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
  private final StringBuilder decimalBuilder;
  private final int bufferSizeLimitBytes;
  private int position;

  public Utf8JsonWriter(JsonConfig config, JsonTypeResolver typeResolver) {
    this(config, typeResolver, new byte[512]);
  }

  public Utf8JsonWriter(JsonConfig config, JsonTypeResolver typeResolver, byte[] buffer) {
    super(config, typeResolver);
    this.buffer = buffer;
    bufferSizeLimitBytes = config.bufferSizeLimitBytes();
    decimalBuilder = newDecimalBuilder();
  }

  @Override
  public void reset() {
    super.reset();
    if (buffer.length > bufferSizeLimitBytes) {
      buffer = new byte[bufferSizeLimitBytes];
    }
    position = 0;
  }

  public byte[] toJsonBytes() {
    return Arrays.copyOf(buffer, position);
  }

  public void writeTo(OutputStream output) {
    try {
      output.write(buffer, 0, position);
    } catch (IOException e) {
      throw new ForyJsonException("Cannot write JSON output", e);
    }
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
    writePositiveLongNoEnsure(value);
  }

  @Override
  public void writeFloat(float value) {
    if (!Float.isFinite(value)) {
      writeNonFiniteFloat(value);
      return;
    }
    ensure(JdkFloatFormatter.MAX_CHARS);
    int newPosition = JdkFloatFormatter.write(buffer, position, value);
    if (newPosition >= 0) {
      position = newPosition;
      return;
    }
    StringBuilder builder = decimalBuilder;
    JdkFloatFormatter.appendTo(value, builder);
    writeDecimalBuilder(builder);
  }

  @Override
  public void writeDouble(double value) {
    if (!Double.isFinite(value)) {
      writeNonFiniteDouble(value);
      return;
    }
    ensure(JdkDoubleFormatter.MAX_CHARS);
    int newPosition = JdkDoubleFormatter.write(buffer, position, value);
    if (newPosition >= 0) {
      position = newPosition;
      return;
    }
    StringBuilder builder = decimalBuilder;
    JdkDoubleFormatter.appendTo(value, builder);
    writeDecimalBuilder(builder);
  }

  private static StringBuilder newDecimalBuilder() {
    return JdkFloatFormatter.isAvailable() && JdkDoubleFormatter.isAvailable()
        ? null
        : new StringBuilder(JdkDoubleFormatter.MAX_CHARS);
  }

  private void writeDecimalBuilder(StringBuilder builder) {
    int length = builder.length();
    byte[] bytes = buffer;
    int pos = position;
    for (int i = 0; i < length; i++) {
      bytes[pos++] = (byte) builder.charAt(i);
    }
    position = pos;
  }

  @Override
  public void writeNumber(String value) {
    writeAsciiNumber(value);
  }

  @Override
  public void writeBigInteger(BigInteger value) {
    if (value.getClass() != BigInteger.class) {
      throwUnsupportedBigNumber(value.getClass());
    }
    if (BigNumberDigits.fitsLong(value)) {
      writeLong(value.longValue());
      return;
    }
    writeBigNumberText(value.toString());
  }

  @Override
  public void writeBigDecimal(BigDecimal value) {
    long compact = BigDecimalFields.compactValue(value);
    if (BigDecimalFields.isCompact(compact)) {
      writeCompactBigDecimal(compact, BigDecimalFields.scale(value));
      return;
    }
    writeInflatedBigDecimal(value);
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
      if (bytes.length == value.length()) {
        if (writeLatin1String(bytes)) {
          return;
        }
      } else {
        if (writeUtf16String(bytes)) {
          return;
        }
      }
    }
    writeStringChars(value);
  }

  @Override
  public void writeString(CharSequence value) {
    if (value instanceof String) {
      writeString((String) value);
      return;
    }
    writeStringChars(value);
  }

  @Override
  public void writeUuid(UUID value) {
    ensure(38);
    byte[] bytes = buffer;
    int pos = position;
    bytes[pos++] = (byte) '"';
    long high = value.getMostSignificantBits();
    pos = writeHex(bytes, pos, high, 60, 8);
    bytes[pos++] = (byte) '-';
    pos = writeHex(bytes, pos, high, 28, 4);
    bytes[pos++] = (byte) '-';
    pos = writeHex(bytes, pos, high, 12, 4);
    long low = value.getLeastSignificantBits();
    bytes[pos++] = (byte) '-';
    pos = writeHex(bytes, pos, low, 60, 4);
    bytes[pos++] = (byte) '-';
    pos = writeHex(bytes, pos, low, 44, 12);
    bytes[pos++] = (byte) '"';
    position = pos;
  }

  @Override
  public void writeLocalDate(LocalDate value) {
    int year = value.getYear();
    if (year < 0 || year > 9999) {
      writeTemporal(value, DateTimeFormatter.ISO_LOCAL_DATE);
      return;
    }
    ensure(12);
    byte[] bytes = buffer;
    int pos = position;
    bytes[pos++] = (byte) '"';
    pos = writeLocalDateBytes(bytes, pos, year, value.getMonthValue(), value.getDayOfMonth());
    bytes[pos++] = (byte) '"';
    position = pos;
  }

  @Override
  public void writeOffsetDateTime(OffsetDateTime value) {
    int year = value.getYear();
    if (year < 0 || year > 9999 || value.getOffset().getTotalSeconds() != 0) {
      writeTemporal(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      return;
    }
    ensure(32);
    byte[] bytes = buffer;
    int pos = position;
    bytes[pos++] = (byte) '"';
    pos = writeLocalDateBytes(bytes, pos, year, value.getMonthValue(), value.getDayOfMonth());
    bytes[pos++] = (byte) 'T';
    pos =
        writeTime(
            bytes, pos, value.getHour(), value.getMinute(), value.getSecond(), value.getNano());
    bytes[pos++] = (byte) 'Z';
    bytes[pos++] = (byte) '"';
    position = pos;
  }

  @Override
  public void writeTemporal(TemporalAccessor value, DateTimeFormatter formatter) {
    writeByteRaw((byte) '"');
    formatter.formatTo(value, this);
    writeByteRaw((byte) '"');
  }

  @Override
  public void writeDuration(Duration value) {
    writeByteRaw((byte) '"');
    writeDurationBody(value);
    writeByteRaw((byte) '"');
  }

  @Override
  public void writePeriod(Period value) {
    writeByteRaw((byte) '"');
    if (value.isZero()) {
      writeAscii("P0D");
    } else {
      writeByteRaw((byte) 'P');
      int years = value.getYears();
      if (years != 0) {
        writeInt(years);
        writeByteRaw((byte) 'Y');
      }
      int months = value.getMonths();
      if (months != 0) {
        writeInt(months);
        writeByteRaw((byte) 'M');
      }
      int days = value.getDays();
      if (days != 0) {
        writeInt(days);
        writeByteRaw((byte) 'D');
      }
    }
    writeByteRaw((byte) '"');
  }

  @Override
  public void writeYear(Year value) {
    writeByteRaw((byte) '"');
    writeInt(value.getValue());
    writeByteRaw((byte) '"');
  }

  @Override
  public Utf8JsonWriter append(CharSequence value) {
    return append(value, 0, value.length());
  }

  @Override
  public Utf8JsonWriter append(CharSequence value, int start, int end) {
    for (int i = start; i < end; i++) {
      append(value.charAt(i));
    }
    return this;
  }

  @Override
  public Utf8JsonWriter append(char value) {
    writeEscapedChar(value);
    return this;
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

  private void writeStringChars(CharSequence value) {
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

  public void writeIntField(
      long namePrefix,
      long commaPrefix,
      int namePrefixLength,
      int commaPrefixLength,
      int index,
      int value) {
    if (index == 0) {
      writeIntField(namePrefix, 0L, namePrefixLength, value);
    } else {
      writeIntField(commaPrefix, 0L, commaPrefixLength, value);
    }
  }

  public void writeIntField(byte[] prefix, int value) {
    ensure(prefix.length + 11);
    writeRawNoEnsure(prefix);
    writeIntNoEnsure(value);
  }

  public void writeIntField(long prefix0, long prefix1, int prefixLength, int value) {
    ensurePackedPrefix(prefixLength, 11);
    writePackedRawNoEnsure(prefix0, prefix1, prefixLength);
    writeIntNoEnsure(value);
  }

  public void writeObjectStartWithIntField(byte[] namePrefix, int value) {
    enterDepth();
    ensure(namePrefix.length + 12);
    buffer[position++] = (byte) '{';
    writeRawNoEnsure(namePrefix);
    writeIntNoEnsure(value);
  }

  public void writeObjectStartWithIntField(
      long prefix0, long prefix1, int prefixLength, int value) {
    enterDepth();
    ensurePackedPrefix(prefixLength, 12);
    buffer[position++] = (byte) '{';
    writePackedRawNoEnsure(prefix0, prefix1, prefixLength);
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

  public void writeLongField(long prefix0, long prefix1, int prefixLength, long value) {
    ensurePackedPrefix(prefixLength, 20);
    writePackedRawNoEnsure(prefix0, prefix1, prefixLength);
    writeLongNoEnsure(value);
  }

  public void writeObjectStartWithLongField(byte[] namePrefix, long value) {
    enterDepth();
    ensure(namePrefix.length + 21);
    buffer[position++] = (byte) '{';
    writeRawNoEnsure(namePrefix);
    writeLongNoEnsure(value);
  }

  public void writeObjectStartWithLongField(
      long prefix0, long prefix1, int prefixLength, long value) {
    enterDepth();
    ensurePackedPrefix(prefixLength, 21);
    buffer[position++] = (byte) '{';
    writePackedRawNoEnsure(prefix0, prefix1, prefixLength);
    writeLongNoEnsure(value);
  }

  public void writeObjectStartWithStringField(
      long prefix0, long prefix1, int prefixLength, String value) {
    enterDepth();
    writeStringField(prefix0, prefix1, prefixLength, value);
  }

  public void writeStringField(byte[] namePrefix, byte[] commaNamePrefix, int index, String value) {
    byte[] prefix = index == 0 ? namePrefix : commaNamePrefix;
    writeStringField(prefix, value);
  }

  public void writeStringField(
      long namePrefix,
      long commaPrefix,
      int namePrefixLength,
      int commaPrefixLength,
      int index,
      String value) {
    if (index == 0) {
      writeStringField(namePrefix, 0L, namePrefixLength, value);
    } else {
      writeStringField(commaPrefix, 0L, commaPrefixLength, value);
    }
  }

  public void writeStringField(byte[] prefix, String value) {
    if (STRING_BYTES_BACKED) {
      byte[] bytes = StringSerializer.getStringBytes(value);
      int start = position;
      if (bytes.length == value.length()) {
        ensure(prefix.length + bytes.length + 2);
        writeRawNoEnsure(prefix);
        if (writeLatin1StringNoEnsure(bytes)) {
          return;
        }
        position = start;
      } else {
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

  public void writeStringField(long prefix0, long prefix1, int prefixLength, String value) {
    if (STRING_BYTES_BACKED) {
      byte[] bytes = StringSerializer.getStringBytes(value);
      int start = position;
      if (bytes.length == value.length()) {
        ensurePackedPrefix(prefixLength, bytes.length + 2);
        writePackedRawNoEnsure(prefix0, prefix1, prefixLength);
        if (writeLatin1StringNoEnsure(bytes)) {
          return;
        }
        position = start;
      } else {
        ensurePackedPrefix(prefixLength, (bytes.length >> 1) * 3 + 2);
        writePackedRawNoEnsure(prefix0, prefix1, prefixLength);
        if (writeUtf16StringNoEnsure(bytes)) {
          return;
        }
        position = start;
      }
    }
    writeStringFieldChars(prefix0, prefix1, prefixLength, value);
  }

  private void writeStringFieldChars(byte[] prefix, String value) {
    ensure(prefix.length + value.length() * 3 + 2);
    writeRawNoEnsure(prefix);
    writeStringNoEnsure(value);
  }

  private void writeStringFieldChars(long prefix0, long prefix1, int prefixLength, String value) {
    ensurePackedPrefix(prefixLength, value.length() * 3 + 2);
    writePackedRawNoEnsure(prefix0, prefix1, prefixLength);
    writeStringNoEnsure(value);
  }

  public void writeStringCollection(Collection<String> values) {
    writeArrayStart();
    if (values.getClass() == ArrayList.class) {
      ArrayList<String> list = (ArrayList<String>) values;
      int size = list.size();
      if (size != 0) {
        writeStringElementWithComma(0, list.get(0));
        for (int i = 1; i < size; i++) {
          writeStringElementWithComma(1, list.get(i));
        }
      }
    } else {
      int index = 0;
      for (String value : values) {
        writeStringElementWithComma(index++ == 0 ? 0 : 1, value);
      }
    }
    writeArrayEnd();
  }

  public void writeStringArray(String[] values) {
    writeArrayStart();
    int length = values.length;
    if (length != 0) {
      int i = 1;
      writeStringElementWithComma(0, values[0]);
      if ((length & 1) == 0) {
        writeStringElementWithComma(1, values[i]);
        i++;
      }
      for (; i < length; i += 2) {
        writeStringElementWithComma(1, values[i]);
        writeStringElementWithComma(1, values[i + 1]);
      }
    }
    writeArrayEnd();
  }

  public void writeLongArray(long[] values) {
    enterDepth();
    ensure(2);
    buffer[position++] = '[';
    int length = values.length;
    if (length != 0) {
      ensure(22);
      writeLongNoEnsure(values[0]);
      int i = 1;
      if ((length & 1) == 0) {
        ensure(22);
        buffer[position++] = ',';
        writeLongNoEnsure(values[i]);
        i++;
      }
      for (; i < length; i += 2) {
        ensure(44);
        buffer[position++] = ',';
        writeLongNoEnsure(values[i]);
        buffer[position++] = ',';
        writeLongNoEnsure(values[i + 1]);
      }
    }
    buffer[position++] = ']';
    exitDepth();
  }

  public void writeStringElement(int index, String value) {
    writeStringElementWithComma(index == 0 ? 0 : 1, value);
  }

  private void writeStringElementWithComma(int comma, String value) {
    if (value == null) {
      writeNullStringElement(comma);
      return;
    }
    if (STRING_BYTES_BACKED) {
      byte[] bytes = StringSerializer.getStringBytes(value);
      int start = position;
      if (bytes.length == value.length()) {
        ensure(comma + bytes.length + 2);
        if (comma != 0) {
          buffer[position++] = ',';
        }
        if (writeLatin1StringNoEnsure(bytes)) {
          return;
        }
        position = start;
      } else {
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

  /** Writes trusted JSON text without quoting, escaping, or validating its JSON grammar. */
  public void writeRawValue(String value) {
    int length = value.length();
    ensureRawCapacity(length);
    ensure(length);
    byte[] bytes = buffer;
    int pos = position;
    int index = 0;
    while (index + 4 <= length) {
      char c0 = value.charAt(index);
      char c1 = value.charAt(index + 1);
      char c2 = value.charAt(index + 2);
      char c3 = value.charAt(index + 3);
      if ((c0 | c1 | c2 | c3) > 0x7f) {
        break;
      }
      bytes[pos] = (byte) c0;
      bytes[pos + 1] = (byte) c1;
      bytes[pos + 2] = (byte) c2;
      bytes[pos + 3] = (byte) c3;
      pos += 4;
      index += 4;
    }
    while (index < length) {
      char ch = value.charAt(index);
      if (ch > 0x7f) {
        position = pos;
        writeRawStringSlow(value, index, length);
        return;
      }
      bytes[pos++] = (byte) ch;
      index++;
    }
    position = pos;
  }

  public void writeRawValue(long prefix0, long prefix1, int prefixLength) {
    ensure(packedPrefixSize(prefixLength));
    writePackedRawNoEnsure(prefix0, prefix1, prefixLength);
  }

  /** Writes a byte array as a quoted Base64 JSON string without an intermediate String. */
  public void writeBase64(byte[] value) {
    int encodedLength = base64Length(value.length);
    ensure(base64Additional(encodedLength));
    byte[] target = buffer;
    int pos = position;
    target[pos++] = '"';
    int index = 0;
    int end = value.length - 2;
    while (index < end) {
      int bits =
          ((value[index++] & 0xff) << 16)
              | ((value[index++] & 0xff) << 8)
              | (value[index++] & 0xff);
      target[pos++] = BASE64_DIGITS[bits >>> 18];
      target[pos++] = BASE64_DIGITS[(bits >>> 12) & 0x3f];
      target[pos++] = BASE64_DIGITS[(bits >>> 6) & 0x3f];
      target[pos++] = BASE64_DIGITS[bits & 0x3f];
    }
    int remaining = value.length - index;
    if (remaining != 0) {
      int bits = (value[index] & 0xff) << 16;
      if (remaining == 2) {
        bits |= (value[index + 1] & 0xff) << 8;
      }
      target[pos++] = BASE64_DIGITS[bits >>> 18];
      target[pos++] = BASE64_DIGITS[(bits >>> 12) & 0x3f];
      target[pos++] = remaining == 2 ? BASE64_DIGITS[(bits >>> 6) & 0x3f] : (byte) '=';
      target[pos++] = '=';
    }
    target[pos++] = '"';
    position = pos;
  }

  private void writeRawStringSlow(String value, int index, int length) {
    int remaining = length - index;
    long additional = (long) remaining * 3L;
    if (additional > Integer.MAX_VALUE - (long) position) {
      throw new ForyJsonException("Raw JSON output is too large");
    }
    ensure((int) additional);
    for (int i = index; i < length; i++) {
      char ch = value.charAt(i);
      if (ch < 0x80) {
        buffer[position++] = (byte) ch;
      } else if (ch < 0x800) {
        buffer[position++] = (byte) (0xc0 | (ch >>> 6));
        buffer[position++] = (byte) (0x80 | (ch & 0x3f));
      } else if (!Character.isSurrogate(ch)) {
        buffer[position++] = (byte) (0xe0 | (ch >>> 12));
        buffer[position++] = (byte) (0x80 | ((ch >>> 6) & 0x3f));
        buffer[position++] = (byte) (0x80 | (ch & 0x3f));
      } else if (Character.isHighSurrogate(ch)
          && i + 1 < length
          && Character.isLowSurrogate(value.charAt(i + 1))) {
        int codePoint = Character.toCodePoint(ch, value.charAt(++i));
        buffer[position++] = (byte) (0xf0 | (codePoint >>> 18));
        buffer[position++] = (byte) (0x80 | ((codePoint >>> 12) & 0x3f));
        buffer[position++] = (byte) (0x80 | ((codePoint >>> 6) & 0x3f));
        buffer[position++] = (byte) (0x80 | (codePoint & 0x3f));
      } else {
        throw new ForyJsonException("Invalid Unicode surrogate in raw JSON value at index " + i);
      }
    }
  }

  private static int base64Length(int length) {
    long encoded = ((length + 2L) / 3L) * 4L;
    if (encoded > Integer.MAX_VALUE - 2) {
      throw new ForyJsonException("Byte array is too large for Base64 JSON output");
    }
    return (int) encoded;
  }

  private int base64Additional(int encodedLength) {
    long additional = encodedLength + 2L;
    if (additional > Integer.MAX_VALUE - (long) position) {
      throw new ForyJsonException("Base64 JSON output is too large");
    }
    return (int) additional;
  }

  private void ensureRawCapacity(int additional) {
    if (additional > Integer.MAX_VALUE - position) {
      throw new ForyJsonException("Raw JSON output is too large");
    }
  }

  @Override
  public void writeObjectStart() {
    enterDepth();
    writeByteRaw((byte) '{');
  }

  @Override
  public void writeObjectEnd() {
    writeByteRaw((byte) '}');
    exitDepth();
  }

  @Override
  public void writeArrayStart() {
    enterDepth();
    writeByteRaw((byte) '[');
  }

  @Override
  public void writeArrayEnd() {
    writeByteRaw((byte) ']');
    exitDepth();
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
    return writeLatin1StringNoEnsure(value, length);
  }

  private boolean writeLatin1StringNoEnsure(byte[] value) {
    int length = value.length;
    return writeLatin1StringNoEnsure(value, length);
  }

  private boolean writeLatin1StringNoEnsure(byte[] value, int length) {
    if (length < 32) {
      return writeShortLatin1StringNoEnsure(value, length);
    }
    return writeLongLatin1StringNoEnsure(value, length);
  }

  private boolean writeLongLatin1StringNoEnsure(byte[] value, int length) {
    byte[] bytes = buffer;
    int start = position;
    if (!isJsonAsciiBytes(value, length)) {
      return false;
    }
    int pos = start;
    bytes[pos++] = (byte) '"';
    System.arraycopy(value, 0, bytes, pos, length);
    pos += length;
    bytes[pos++] = (byte) '"';
    position = pos;
    return true;
  }

  private static boolean isJsonAsciiBytes(byte[] value, int length) {
    int i = 0;
    int upperBound = length & ~15;
    for (; i < upperBound; i += 16) {
      long word0 = LittleEndian.getInt64(value, i);
      long word1 = LittleEndian.getInt64(value, i + 8);
      if (!isJsonAsciiWords(word0, word1)) {
        return false;
      }
    }
    upperBound = length & ~7;
    for (; i < upperBound; i += 8) {
      long word = LittleEndian.getInt64(value, i);
      if (!isJsonAsciiWord(word)) {
        return false;
      }
    }
    if (i + 4 <= length) {
      int word = LittleEndian.getInt32(value, i);
      if (isJsonAsciiInt(word)) {
        i += 4;
      } else {
        return false;
      }
    }
    if (i + 2 <= length) {
      int word = (value[i] & 0xFF) | ((value[i + 1] & 0xFF) << 8);
      if (isJsonAsciiShort(word)) {
        i += 2;
      } else {
        return false;
      }
    }
    for (; i < length; i++) {
      if (!isJsonAsciiByte(value[i])) {
        return false;
      }
    }
    return true;
  }

  private boolean writeShortLatin1StringNoEnsure(byte[] value, int length) {
    if (length > 24) {
      return writeLatin1String25To31(value, length);
    }
    if (length > 16) {
      return writeLatin1String17To24(value, length);
    }
    byte[] bytes = buffer;
    int pos = position;
    bytes[pos++] = (byte) '"';
    // Short compact strings dominate generated JSON writers. Keep the 8-16 byte path exact here;
    // longer short-string bands stay in helpers so this common path remains small.
    // Position is published only after complete validation, so every false return leaves the
    // writer cursor unchanged even though scratch bytes may already have been overwritten.
    if (length >= 8) {
      long word = LittleEndian.getInt64(value, 0);
      if (!isJsonAsciiWord(word)) {
        return false;
      }
      LittleEndian.putInt64(bytes, pos, word);
      pos += 8;
      int index = Long.BYTES;
      if (index + Long.BYTES <= length) {
        long tail = LittleEndian.getInt64(value, index);
        if (!isJsonAsciiWord(tail)) {
          return false;
        }
        LittleEndian.putInt64(bytes, pos, tail);
        pos += Long.BYTES;
        index += Long.BYTES;
      }
      if (index + Integer.BYTES <= length) {
        int tail = LittleEndian.getInt32(value, index);
        if (!isJsonAsciiInt(tail)) {
          return false;
        }
        LittleEndian.putInt32(bytes, pos, tail);
        pos += Integer.BYTES;
        index += Integer.BYTES;
      }
      if (index + Short.BYTES <= length) {
        int tail = (value[index] & 0xFF) | ((value[index + 1] & 0xFF) << 8);
        if (!isJsonAsciiShort(tail)) {
          return false;
        }
        bytes[pos] = (byte) tail;
        bytes[pos + 1] = (byte) (tail >>> 8);
        pos += Short.BYTES;
        index += Short.BYTES;
      }
      if (index < length) {
        byte tail = value[index];
        if (!isJsonAsciiByte(tail)) {
          return false;
        }
        bytes[pos++] = tail;
      }
    } else {
      // Keep the sub-8 tail outside this method so the common word-sized short-string path stays
      // small enough to inline into generated object writers.
      return writeLatin1String0To7(value, length);
    }
    bytes[pos++] = (byte) '"';
    position = pos;
    return true;
  }

  private boolean writeLatin1String0To7(byte[] value, int length) {
    byte[] bytes = buffer;
    int pos = position;
    bytes[pos++] = (byte) '"';
    if (length >= 4) {
      int word = LittleEndian.getInt32(value, 0);
      if (!isJsonAsciiInt(word)) {
        return false;
      }
      LittleEndian.putInt32(bytes, pos, word);
      if (length > 4) {
        int tailOffset = length - Integer.BYTES;
        int tail = LittleEndian.getInt32(value, tailOffset);
        if (!isJsonAsciiInt(tail)) {
          return false;
        }
        LittleEndian.putInt32(bytes, pos + tailOffset, tail);
      }
      pos += length;
    } else if (length >= 2) {
      int word = (value[0] & 0xFF) | ((value[1] & 0xFF) << 8);
      if (!isJsonAsciiShort(word)) {
        return false;
      }
      bytes[pos] = (byte) word;
      bytes[pos + 1] = (byte) (word >>> 8);
      if (length == 3) {
        byte ch = value[2];
        if (!isJsonAsciiByte(ch)) {
          return false;
        }
        bytes[pos + 2] = ch;
      }
      pos += length;
    } else if (length == 1) {
      byte ch = value[0];
      if (!isJsonAsciiByte(ch)) {
        return false;
      }
      bytes[pos++] = ch;
    }
    bytes[pos++] = (byte) '"';
    position = pos;
    return true;
  }

  private boolean writeLatin1String25To31(byte[] value, int length) {
    byte[] bytes = buffer;
    int pos = position;
    bytes[pos++] = (byte) '"';
    long word0 = LittleEndian.getInt64(value, 0);
    long word1 = LittleEndian.getInt64(value, 8);
    long word2 = LittleEndian.getInt64(value, 16);
    int tailOffset = length - Long.BYTES;
    long tail = LittleEndian.getInt64(value, tailOffset);
    if (!isJsonAsciiWords(word0, word1, word2, tail)) {
      return false;
    }
    LittleEndian.putInt64(bytes, pos, word0);
    LittleEndian.putInt64(bytes, pos + 8, word1);
    LittleEndian.putInt64(bytes, pos + 16, word2);
    LittleEndian.putInt64(bytes, pos + tailOffset, tail);
    pos += length;
    bytes[pos++] = (byte) '"';
    position = pos;
    return true;
  }

  private boolean writeLatin1String17To24(byte[] value, int length) {
    byte[] bytes = buffer;
    int pos = position;
    bytes[pos++] = (byte) '"';
    long word0 = LittleEndian.getInt64(value, 0);
    long word1 = LittleEndian.getInt64(value, 8);
    int tailOffset = length - Long.BYTES;
    long tail = LittleEndian.getInt64(value, tailOffset);
    if (!isJsonAsciiWords(word0, word1, tail)) {
      return false;
    }
    LittleEndian.putInt64(bytes, pos, word0);
    LittleEndian.putInt64(bytes, pos + 8, word1);
    LittleEndian.putInt64(bytes, pos + tailOffset, tail);
    pos += length;
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
    while (i < length) {
      if (i + 8 <= length) {
        char c0 = StringSerializer.getBytesChar(value, i);
        char c1 = StringSerializer.getBytesChar(value, i + 2);
        char c2 = StringSerializer.getBytesChar(value, i + 4);
        char c3 = StringSerializer.getBytesChar(value, i + 6);
        if (isUtf8ThreeByte(c0)
            && isUtf8ThreeByte(c1)
            && isUtf8ThreeByte(c2)
            && isUtf8ThreeByte(c3)) {
          bytes[pos++] = (byte) (0xE0 | (c0 >>> 12));
          bytes[pos++] = (byte) (0x80 | ((c0 >>> 6) & 0x3F));
          bytes[pos++] = (byte) (0x80 | (c0 & 0x3F));
          bytes[pos++] = (byte) (0xE0 | (c1 >>> 12));
          bytes[pos++] = (byte) (0x80 | ((c1 >>> 6) & 0x3F));
          bytes[pos++] = (byte) (0x80 | (c1 & 0x3F));
          bytes[pos++] = (byte) (0xE0 | (c2 >>> 12));
          bytes[pos++] = (byte) (0x80 | ((c2 >>> 6) & 0x3F));
          bytes[pos++] = (byte) (0x80 | (c2 & 0x3F));
          bytes[pos++] = (byte) (0xE0 | (c3 >>> 12));
          bytes[pos++] = (byte) (0x80 | ((c3 >>> 6) & 0x3F));
          bytes[pos++] = (byte) (0x80 | (c3 & 0x3F));
          i += 8;
          continue;
        }
      }
      char ch = StringSerializer.getBytesChar(value, i);
      i += 2;
      if (ch < 0x80) {
        if (!isJsonAscii(ch)) {
          position = start;
          return false;
        }
        bytes[pos++] = (byte) ch;
      } else if (ch < 0x800) {
        bytes[pos++] = (byte) (0xC0 | (ch >>> 6));
        bytes[pos++] = (byte) (0x80 | (ch & 0x3F));
      } else if (ch >= Character.MIN_SURROGATE && ch <= Character.MAX_SURROGATE) {
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

  private static boolean isUtf8ThreeByte(char ch) {
    return ch >= 0x800 && (ch < Character.MIN_SURROGATE || ch > Character.MAX_SURROGATE);
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

  private void writeStringSlow(CharSequence value, int index, int length) {
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

  private void writeDurationBody(Duration value) {
    long seconds = value.getSeconds();
    int nano = value.getNano();
    if (seconds == 0 && nano == 0) {
      writeAscii("PT0S");
      return;
    }
    writeAscii("PT");
    long hours = seconds / 3600;
    int minutes = (int) ((seconds % 3600) / 60);
    int secs = (int) (seconds % 60);
    if (hours != 0) {
      writeLong(hours);
      writeByteRaw((byte) 'H');
    }
    if (minutes != 0) {
      writeInt(minutes);
      writeByteRaw((byte) 'M');
    }
    if (secs == 0 && nano == 0 && (hours != 0 || minutes != 0)) {
      return;
    }
    if (secs < 0 && nano > 0) {
      if (secs == -1) {
        writeAscii("-0");
      } else {
        writeInt(secs + 1);
      }
    } else {
      writeInt(secs);
    }
    if (nano > 0) {
      int fraction = secs < 0 ? 2_000_000_000 - nano : 1_000_000_000 + nano;
      writeDurationFraction(fraction);
    }
    writeByteRaw((byte) 'S');
  }

  private void writeDurationFraction(int value) {
    int fraction = value % 1_000_000_000;
    int digits = 9;
    while (fraction % 10 == 0) {
      fraction /= 10;
      digits--;
    }
    writeByteRaw((byte) '.');
    int divisor = 1;
    for (int i = 1; i < digits; i++) {
      divisor *= 10;
    }
    for (int i = 0; i < digits; i++) {
      int digit = fraction / divisor;
      writeByteRaw((byte) ('0' + digit));
      fraction -= digit * divisor;
      divisor /= 10;
    }
  }

  private void writeStringNoEnsure(String value) {
    if (STRING_BYTES_BACKED) {
      byte[] bytes = StringSerializer.getStringBytes(value);
      if (bytes.length == value.length()) {
        if (writeLatin1StringNoEnsure(bytes)) {
          return;
        }
      } else {
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

  private void writeAsciiNumber(String value) {
    int length = value.length();
    ensure(length);
    writeAsciiNumberNoEnsure(value, length);
  }

  private void writeBigNumberText(String value) {
    int length = value.length();
    ensureNumberChars(length);
    writeAsciiNumberNoEnsure(value, length);
  }

  private void writeInflatedBigDecimal(BigDecimal value) {
    if (value.getClass() == BigDecimal.class) {
      writeBigNumberText(value.toString());
      return;
    }
    // Never invoke overridable numeric methods on a subtype. Rebuild the canonical JDK value from
    // BigDecimal's base fields on this cold path.
    if (!BigDecimalFields.canReadInflatedValue()) {
      throwUnsupportedBigNumber(value.getClass());
    }
    BigDecimal canonical =
        new BigDecimal(BigDecimalFields.inflatedValue(value), BigDecimalFields.scale(value));
    writeBigNumberText(canonical.toString());
  }

  private void writeAsciiNumberNoEnsure(String value, int length) {
    if (STRING_BYTES_BACKED) {
      byte[] bytes = StringSerializer.getStringBytes(value);
      if (bytes.length == length) {
        System.arraycopy(bytes, 0, buffer, position, length);
        position += length;
        return;
      }
    }
    writeAsciiNoEnsure(value);
  }

  private void writeNonFiniteFloat(float value) {
    writeRaw(nonFiniteBytes(value));
  }

  private void writeNonFiniteDouble(double value) {
    writeRaw(nonFiniteBytes(value));
  }

  private static byte[] nonFiniteBytes(double value) {
    if (Double.isNaN(value)) {
      return NAN_BYTES;
    }
    return value > 0 ? POSITIVE_INFINITY_BYTES : NEGATIVE_INFINITY_BYTES;
  }

  private void writeRaw(byte[] bytes) {
    ensure(bytes.length);
    writeRawNoEnsure(bytes);
  }

  private void writeRawNoEnsure(byte[] bytes) {
    System.arraycopy(bytes, 0, buffer, position, bytes.length);
    position += bytes.length;
  }

  // Keep the complete compact-decimal layout in this concrete-writer method. Splitting its
  // branches into small helpers lets C2 inline the whole formatter into generated object writers,
  // exhausting their node budget and slowing unrelated fields.
  private void writeCompactBigDecimal(long unscaled, int scale) {
    if (scale == 0) {
      writeLong(unscaled);
      return;
    }
    boolean negative = unscaled < 0;
    if (negative) {
      unscaled = -unscaled;
    }
    int precision = BigNumberDigits.digitCount(unscaled);
    long adjustedExponent = (long) precision - scale - 1L;
    boolean plain = scale >= 0 && adjustedExponent >= -6;
    long point = (long) precision - scale;
    long outputChars;
    if (plain) {
      outputChars = point <= 0 ? 2L - point + precision : precision + 1L;
    } else {
      long magnitude = adjustedExponent < 0 ? -adjustedExponent : adjustedExponent;
      outputChars =
          (long) precision + (precision == 1 ? 0 : 1) + 2 + BigNumberDigits.digitCount(magnitude);
    }
    outputChars += negative ? 1 : 0;
    ensureNumberChars(outputChars + BigNumberDigits.PACKED_WRITE_SLACK);
    if (negative) {
      buffer[position++] = (byte) '-';
    }
    if (plain) {
      if (point <= 0) {
        byte[] bytes = buffer;
        int pos = position;
        bytes[pos++] = (byte) '0';
        bytes[pos++] = (byte) '.';
        long zeroes = -point;
        while (zeroes-- > 0) {
          bytes[pos++] = (byte) '0';
        }
        position = pos;
        writePaddedCompactDigits(unscaled, precision);
        return;
      }
      long divisor = BigNumberDigits.LONG_POWERS_OF_TEN[scale];
      long integer = unscaled / divisor;
      long fraction = unscaled - integer * divisor;
      writePaddedCompactDigits(integer, (int) point);
      buffer[position++] = (byte) '.';
      writePaddedCompactDigits(fraction, scale);
      return;
    }
    if (precision == 1) {
      writePositiveLongNoEnsure(unscaled);
    } else {
      long divisor = BigNumberDigits.LONG_POWERS_OF_TEN[precision - 1];
      int first = (int) (unscaled / divisor);
      long rest = unscaled - first * divisor;
      byte[] bytes = buffer;
      int pos = position;
      bytes[pos++] = (byte) ('0' + first);
      bytes[pos++] = (byte) '.';
      position = pos;
      writePaddedCompactDigits(rest, precision - 1);
    }
    byte[] bytes = buffer;
    int pos = position;
    bytes[pos++] = (byte) 'E';
    if (adjustedExponent >= 0) {
      bytes[pos++] = (byte) '+';
    }
    position = pos;
    writeLongNoEnsure(adjustedExponent);
  }

  private void writePaddedCompactDigits(long value, int digits) {
    if (digits <= 9) {
      position = writePaddedDigits(buffer, position, (int) value, digits);
      return;
    }
    long high = value / 1_000_000_000L;
    int low = (int) (value - high * 1_000_000_000L);
    byte[] bytes = buffer;
    int pos = position;
    if (digits <= 18) {
      pos = writePaddedDigits(bytes, pos, (int) high, digits - 9);
    } else {
      int top = (int) (high / 1_000_000_000L);
      int middle = (int) (high - (long) top * 1_000_000_000L);
      pos = writePaddedDigits(bytes, pos, top, 1);
      pos = writePadded9(bytes, pos, middle);
    }
    position = writePadded9(bytes, pos, low);
  }

  private void ensureNumberChars(long chars) {
    if (chars > Integer.MAX_VALUE - position) {
      throwNumberOutputTooLarge();
    }
    ensure((int) chars);
  }

  private static void throwNumberOutputTooLarge() {
    throw new ForyJsonException("JSON number output too large");
  }

  private void writePackedRawNoEnsure(long prefix0, long prefix1, int prefixLength) {
    LittleEndian.putInt64(buffer, position, prefix0);
    if (prefixLength > Long.BYTES) {
      LittleEndian.putInt64(buffer, position + Long.BYTES, prefix1);
    }
    position += prefixLength;
  }

  private void ensurePackedPrefix(int prefixLength, int additionalAfterPrefix) {
    ensure(Math.max(packedPrefixSize(prefixLength), prefixLength + additionalAfterPrefix));
  }

  private static int packedPrefixSize(int prefixLength) {
    return prefixLength <= Long.BYTES ? Long.BYTES : Long.BYTES * 2;
  }

  private void writeByteRaw(byte value) {
    ensure(1);
    buffer[position++] = value;
  }

  private void ensure(int additional) {
    int minCapacity = position + additional;
    if (minCapacity > buffer.length) {
      grow(minCapacity);
    }
  }

  private void grow(int minCapacity) {
    int capacity = buffer.length;
    int expanded = capacity + Math.max(capacity, 1);
    int newCapacity = expanded >= minCapacity && expanded > 0 ? expanded : minCapacity;
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

  // Keep the exact uncommon fallback outside these per-word predicates. Folding it back in makes
  // the standalone predicates too large for C2 to inline into the short-string writers.
  private static boolean isJsonAsciiWord(long word) {
    long notBackslashMask = ((word ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES) & HIGH_BITS;
    // Common unescaped bytes are greater than '"' and not '\\'. The fallback keeps the exact
    // quote, backslash, control, and high-bit predicate for the remaining byte values.
    if ((notBackslashMask & (word + ASCII_GT_QUOTE_OFFSET)) == HIGH_BITS) {
      return true;
    }
    return isJsonAsciiWordFallback(word);
  }

  private static boolean isJsonAsciiWordFallback(long word) {
    long notBackslashMask = ((word ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES) & HIGH_BITS;
    return (((word + ASCII_CONTROL_OFFSET) & ~word) & HIGH_BITS) == HIGH_BITS
        && (((word ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES) & HIGH_BITS) == HIGH_BITS
        && notBackslashMask == HIGH_BITS;
  }

  // Aggregate every exact rejection mask before branching. Splitting this back into per-word
  // calls adds one common-path branch for each eight bytes written.
  private static boolean isJsonAsciiWords(long word0, long word1) {
    long notBackslashMask =
        ((word0 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
            & ((word1 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
            & HIGH_BITS;
    if ((notBackslashMask & (word0 + ASCII_GT_QUOTE_OFFSET) & (word1 + ASCII_GT_QUOTE_OFFSET))
        == HIGH_BITS) {
      return true;
    }
    return isJsonAsciiWordsFallback(word0, word1, notBackslashMask);
  }

  private static boolean isJsonAsciiWords(long word0, long word1, long word2) {
    long notBackslashMask =
        ((word0 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
            & ((word1 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
            & ((word2 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
            & HIGH_BITS;
    if ((notBackslashMask
            & (word0 + ASCII_GT_QUOTE_OFFSET)
            & (word1 + ASCII_GT_QUOTE_OFFSET)
            & (word2 + ASCII_GT_QUOTE_OFFSET))
        == HIGH_BITS) {
      return true;
    }
    return isJsonAsciiWordsFallback(word0, word1, word2, notBackslashMask);
  }

  private static boolean isJsonAsciiWords(long word0, long word1, long word2, long word3) {
    long notBackslashMask =
        ((word0 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
            & ((word1 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
            & ((word2 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
            & ((word3 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
            & HIGH_BITS;
    if ((notBackslashMask
            & (word0 + ASCII_GT_QUOTE_OFFSET)
            & (word1 + ASCII_GT_QUOTE_OFFSET)
            & (word2 + ASCII_GT_QUOTE_OFFSET)
            & (word3 + ASCII_GT_QUOTE_OFFSET))
        == HIGH_BITS) {
      return true;
    }
    return isJsonAsciiWordsFallback(word0, word1, word2, word3, notBackslashMask);
  }

  private static boolean isJsonAsciiWordsFallback(long word0, long word1, long notBackslashMask) {
    return ((word0 + ASCII_CONTROL_OFFSET)
            & (word1 + ASCII_CONTROL_OFFSET)
            & ((word0 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
            & ((word1 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
            & notBackslashMask)
        == HIGH_BITS;
  }

  private static boolean isJsonAsciiWordsFallback(
      long word0, long word1, long word2, long notBackslashMask) {
    return ((word0 + ASCII_CONTROL_OFFSET)
            & (word1 + ASCII_CONTROL_OFFSET)
            & (word2 + ASCII_CONTROL_OFFSET)
            & ((word0 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
            & ((word1 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
            & ((word2 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
            & notBackslashMask)
        == HIGH_BITS;
  }

  private static boolean isJsonAsciiWordsFallback(
      long word0, long word1, long word2, long word3, long notBackslashMask) {
    return ((word0 + ASCII_CONTROL_OFFSET)
            & (word1 + ASCII_CONTROL_OFFSET)
            & (word2 + ASCII_CONTROL_OFFSET)
            & (word3 + ASCII_CONTROL_OFFSET)
            & ((word0 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
            & ((word1 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
            & ((word2 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
            & ((word3 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
            & notBackslashMask)
        == HIGH_BITS;
  }

  private static boolean isJsonAsciiInt(int word) {
    int notBackslashMask =
        ((word ^ INT_BACKSLASH_BYTES_COMPLEMENT) + INT_ONE_BYTES) & INT_HIGH_BITS;
    if ((notBackslashMask & (word + INT_ASCII_GT_QUOTE_OFFSET)) == INT_HIGH_BITS) {
      return true;
    }
    return isJsonAsciiIntFallback(word, notBackslashMask);
  }

  private static boolean isJsonAsciiIntFallback(int word, int notBackslashMask) {
    return (((word + INT_ASCII_CONTROL_OFFSET) & ~word) & INT_HIGH_BITS) == INT_HIGH_BITS
        && (((word ^ INT_QUOTE_BYTES_COMPLEMENT) + INT_ONE_BYTES) & INT_HIGH_BITS) == INT_HIGH_BITS
        && notBackslashMask == INT_HIGH_BITS;
  }

  private static boolean isJsonAsciiShort(int word) {
    int notBackslashMask =
        ((word ^ SHORT_BACKSLASH_BYTES_COMPLEMENT) + SHORT_ONE_BYTES) & SHORT_HIGH_BITS;
    if ((notBackslashMask & (word + SHORT_ASCII_GT_QUOTE_OFFSET)) == SHORT_HIGH_BITS) {
      return true;
    }
    return isJsonAsciiShortFallback(word, notBackslashMask);
  }

  private static boolean isJsonAsciiShortFallback(int word, int notBackslashMask) {
    return (((word + SHORT_ASCII_CONTROL_OFFSET) & ~word) & SHORT_HIGH_BITS) == SHORT_HIGH_BITS
        && (((word ^ SHORT_QUOTE_BYTES_COMPLEMENT) + SHORT_ONE_BYTES) & SHORT_HIGH_BITS)
            == SHORT_HIGH_BITS
        && notBackslashMask == SHORT_HIGH_BITS;
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
    writePositiveLongNoEnsure(value);
  }

  private void writePositiveIntNoEnsure(int value) {
    position = writePositiveInt(buffer, position, value);
  }

  private void writePositiveLongNoEnsure(long value) {
    if (value <= Integer.MAX_VALUE) {
      writePositiveIntNoEnsure((int) value);
      return;
    }
    byte[] bytes = buffer;
    int pos = position;
    long high = value / EIGHT_DIGITS;
    int low = (int) (value - high * EIGHT_DIGITS);
    if (high <= Integer.MAX_VALUE) {
      pos = writePositiveInt(bytes, pos, (int) high);
    } else {
      long top = high / EIGHT_DIGITS;
      int middle = (int) (high - top * EIGHT_DIGITS);
      // A positive long has at most 19 decimal digits, so removing two eight-digit chunks leaves a
      // top chunk in [1, 922]. Bypass the general int branch tree for this proven three-digit
      // bound.
      pos = writeIntUpTo3(bytes, pos, (int) top);
      pos = writePadded8Digits(bytes, pos, middle);
    }
    position = writePadded8Digits(bytes, pos, low);
  }

  private static int writePositiveInt(byte[] bytes, int pos, int value) {
    if (value < 10000) {
      return writeIntUpTo4(bytes, pos, value);
    }
    int high = divide10000(value);
    int low = value - high * 10000;
    if (high < 10000) {
      if (high >= 1000) {
        return writePadded8(bytes, pos, high, low);
      }
      pos = writeIntUpTo4(bytes, pos, high);
      return writePadded4(bytes, pos, low);
    }
    int top = divide10000(high);
    int middle = high - top * 10000;
    // Integer.MAX_VALUE has ten digits, so removing two four-digit chunks leaves a top chunk in
    // [1, 21]. Avoid the up-to-four dispatcher for this proven two-digit bound.
    pos = writeIntUpTo3(bytes, pos, top);
    return writePadded8(bytes, pos, middle, low);
  }

  private static int writePadded8Digits(byte[] bytes, int pos, int value) {
    int high = divide10000(value);
    int low = value - high * 10000;
    return writePadded8(bytes, pos, high, low);
  }

  private static int writePadded9(byte[] bytes, int pos, int value) {
    int high = value / 100_000_000;
    bytes[pos++] = (byte) ('0' + high);
    return writePadded8Digits(bytes, pos, value - high * 100_000_000);
  }

  private static int writePaddedDigits(byte[] bytes, int pos, int value, int digits) {
    int zeroes = digits - BigNumberDigits.digitCount(value);
    while (zeroes-- > 0) {
      bytes[pos++] = (byte) '0';
    }
    return writePositiveInt(bytes, pos, value);
  }

  private static int writeHex(byte[] bytes, int pos, long value, int shift, int count) {
    for (int i = 0; i < count; i++) {
      bytes[pos++] = HEX_DIGITS[(int) ((value >>> shift) & 0xF)];
      shift -= 4;
    }
    return pos;
  }

  private static int writeLocalDateBytes(byte[] bytes, int pos, int year, int month, int day) {
    pos = writePadded4(bytes, pos, year);
    bytes[pos++] = (byte) '-';
    pos = writeTwoDigits(bytes, pos, month);
    bytes[pos++] = (byte) '-';
    return writeTwoDigits(bytes, pos, day);
  }

  private static int writeTime(byte[] bytes, int pos, int hour, int minute, int second, int nano) {
    pos = writeTwoDigits(bytes, pos, hour);
    bytes[pos++] = (byte) ':';
    pos = writeTwoDigits(bytes, pos, minute);
    if (second != 0 || nano != 0) {
      bytes[pos++] = (byte) ':';
      pos = writeTwoDigits(bytes, pos, second);
      if (nano != 0) {
        bytes[pos++] = (byte) '.';
        pos = writeNano(bytes, pos, nano);
      }
    }
    return pos;
  }

  private static int writeNano(byte[] bytes, int pos, int nano) {
    if (nano % 1_000_000 == 0) {
      return writePadded3(bytes, pos, nano / 1_000_000);
    }
    if (nano % 1000 == 0) {
      int micros = nano / 1000;
      int high = micros / 1000;
      int low = micros - high * 1000;
      pos = writePadded3(bytes, pos, high);
      return writePadded3(bytes, pos, low);
    }
    int first = nano / 100000000;
    int rem = nano - first * 100000000;
    int middle = rem / 10000;
    int low = rem - middle * 10000;
    bytes[pos++] = (byte) ('0' + first);
    pos = writePadded4(bytes, pos, middle);
    return writePadded4(bytes, pos, low);
  }

  private static int writePadded3(byte[] bytes, int pos, int value) {
    int high = value / 100;
    int rem = value - high * 100;
    int middle = rem / 10;
    bytes[pos++] = (byte) ('0' + high);
    bytes[pos++] = (byte) ('0' + middle);
    bytes[pos++] = (byte) ('0' + (rem - middle * 10));
    return pos;
  }

  private static int writeTwoDigits(byte[] bytes, int pos, int value) {
    int high = value / 10;
    bytes[pos++] = (byte) ('0' + high);
    bytes[pos++] = (byte) ('0' + (value - high * 10));
    return pos;
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
    // Shifting the upper quad discards int-to-long sign extension, so only the lower quad needs a
    // mask before both packed words are stored together.
    LittleEndian.putInt64(
        bytes, pos, (DIGIT_QUADS[high] & 0xFFFFFFFFL) | ((long) DIGIT_QUADS[low] << 32));
    return pos + 8;
  }
}
