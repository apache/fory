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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
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
import org.apache.fory.memory.NativeByteOrder;
import org.apache.fory.serializer.StringSerializer;

/**
 * Concrete writer that builds a Java compact-string byte representation directly.
 *
 * <p>The writer owns a mutable byte buffer, its current LATIN1 or UTF16 coder, and one alternate
 * buffer used for coder widening or formatter staging. Each number or string entry dispatches to a
 * coder-specific output loop once; digit loops do not repeatedly branch on coder. {@link #toJson()}
 * detaches an exact byte array, optionally compresses UTF16 ASCII/Latin1 output, and constructs the
 * result String without exposing pooled storage. The result coder seeds the next reset to avoid
 * repeated widening for stable workloads.
 *
 * <p>Finite float and double spelling comes from the JDK formatter, directly when available and
 * through a retained {@link StringBuilder} otherwise. Compact {@link BigDecimal} values are emitted
 * directly with JDK-compatible spelling; inflated values and out-of-long {@link BigInteger} values
 * use canonical JDK text on the cold arbitrary-precision path. Reset applies the configured
 * retained-buffer limit. The {@link Appendable} methods emit escaped string content without adding
 * surrounding quotes and are used by formatter-owned quoted values.
 */
public final class StringJsonWriter extends JsonWriter implements Appendable {
  private static final byte LATIN1 = 0;
  private static final byte UTF16 = 1;
  private static final byte[] BASE64_DIGITS =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
          .getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] MIN_INT_BYTES = "-2147483648".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] MIN_LONG_BYTES =
      "-9223372036854775808".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] NAN_BYTES = "\"NaN\"".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] POSITIVE_INFINITY_BYTES =
      "\"Infinity\"".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] NEGATIVE_INFINITY_BYTES =
      "\"-Infinity\"".getBytes(StandardCharsets.ISO_8859_1);
  private static final long DECIMAL_8 = 100_000_000L;
  private static final long HIGH_BITS = 0x8080808080808080L;
  private static final int INT_HIGH_BITS = 0x80808080;
  private static final long ASCII_CONTROL_OFFSET = 0x6060606060606060L;
  private static final int INT_ASCII_CONTROL_OFFSET = 0x60606060;
  private static final long ASCII_GT_QUOTE_OFFSET = 0x5D5D5D5D5D5D5D5DL;
  private static final int INT_ASCII_GT_QUOTE_OFFSET = 0x5D5D5D5D;
  private static final long ONE_BYTES = 0x0101010101010101L;
  private static final int INT_ONE_BYTES = 0x01010101;
  private static final long QUOTE_BYTES_COMPLEMENT = ~0x2222222222222222L;
  private static final int INT_QUOTE_BYTES_COMPLEMENT = ~0x22222222;
  private static final long BACKSLASH_BYTES_COMPLEMENT = ~0x5C5C5C5C5C5C5C5CL;
  private static final int INT_BACKSLASH_BYTES_COMPLEMENT = ~0x5C5C5C5C;
  private static final int[] DIGIT_TRIPLES = new int[1000];
  private static final int[] DIGIT_QUADS = new int[10000];
  private static final long[] UTF16_DIGIT_QUADS = new long[10000];
  private static final long UTF16_BYTE_MASK = 0x00FF00FF00FF00FFL;
  private static final long UTF16_PAIR_MASK = 0x0000FFFF0000FFFFL;
  private static final long UTF16_ONES = 0x0001000100010001L;
  private static final long UTF16_HIGH_BITS = 0x8000800080008000L;
  private static final long UTF16_QUOTE_CHARS = 0x0022002200220022L;
  private static final long UTF16_BACKSLASH_CHARS = 0x005c005c005c005cL;
  private static final long UTF16_CONTROL_LIMIT = 0x0020002000200020L;
  private static final long UTF16_SURROGATE_MASK = 0xf800f800f800f800L;
  private static final long UTF16_SURROGATE_PREFIX = 0xd800d800d800d800L;
  private static final boolean STRING_BYTES_BACKED = StringSerializer.isBytesBackedString();
  // Compact byte-backed Strings use one byte per char for LATIN1 and two bytes per char for UTF16.
  // Length checks keep hot JSON string writing from loading the separate String coder field.
  private static final boolean LITTLE_ENDIAN = NativeByteOrder.IS_LITTLE_ENDIAN;

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
      long utf16 = spreadLatin1ToUtf16(DIGIT_QUADS[i] & 0xFFFFFFFFL);
      UTF16_DIGIT_QUADS[i] = LITTLE_ENDIAN ? utf16 : utf16 << 8;
    }
  }

  private byte[] buffer;
  // The alternate coder buffer also retains enough capacity for LATIN1 formatter staging on a
  // runtime that does not expose the direct UTF16 formatter.
  private byte[] scratch;
  private final StringBuilder decimalBuilder;
  private final int bufferSizeLimitBytes;
  private byte coder;
  private byte nextCoder;
  private boolean latin1Output;
  private int position;

  public StringJsonWriter(JsonConfig config, JsonTypeResolver typeResolver) {
    this(config, typeResolver, new byte[512]);
  }

  public StringJsonWriter(JsonConfig config, JsonTypeResolver typeResolver, byte[] buffer) {
    super(config, typeResolver);
    this.buffer = initialBuffer(buffer);
    scratch = new byte[this.buffer.length];
    bufferSizeLimitBytes = config.bufferSizeLimitBytes();
    decimalBuilder = newDecimalBuilder();
  }

  @Override
  public void reset() {
    super.reset();
    if (buffer.length > bufferSizeLimitBytes) {
      buffer = new byte[bufferSizeLimitBytes];
    }
    if (scratch.length > bufferSizeLimitBytes) {
      scratch = new byte[bufferSizeLimitBytes];
    }
    coder = nextCoder;
    latin1Output = coder == UTF16;
    position = 0;
  }

  public String toJson() {
    // The returned String may zero-copy this exact array, so pooled writer storage is never
    // exposed.
    byte resultCoder = coder;
    byte[] bytes;
    if (coder == UTF16 && latin1Output) {
      bytes = compressUtf16ToLatin1(buffer, position);
      resultCoder = LATIN1;
    } else {
      bytes = Arrays.copyOf(buffer, position);
    }
    // Stable workloads often produce the same compact-string coder across operations. Remember the
    // actual result coder so the next reset can avoid a LATIN1-to-UTF16 upgrade when UTF16 output
    // repeats, while all-Latin1 outputs still materialize and continue as compact LATIN1 strings.
    nextCoder = resultCoder;
    return StringSerializer.newBytesStringZeroCopy(resultCoder, bytes);
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
    writePositiveLongLatin1NoEnsure(value);
  }

  @Override
  public void writeFloat(float value) {
    if (!Float.isFinite(value)) {
      writeNonFiniteFloat(value);
      return;
    }
    if (coder == LATIN1) {
      ensure(JdkFloatFormatter.MAX_CHARS);
      int newPosition = JdkFloatFormatter.write(buffer, position, value);
      if (newPosition >= 0) {
        position = newPosition;
        return;
      }
    } else {
      ensure(JdkFloatFormatter.MAX_CHARS << 1);
      int newPosition = JdkFloatFormatter.writeUtf16(buffer, position, value);
      if (newPosition >= 0) {
        position = newPosition;
        return;
      }
      int end = JdkFloatFormatter.write(scratch, 0, value);
      if (end >= 0) {
        writeAsciiUtf16(scratch, end);
        return;
      }
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
    if (coder == LATIN1) {
      ensure(JdkDoubleFormatter.MAX_CHARS);
      int newPosition = JdkDoubleFormatter.write(buffer, position, value);
      if (newPosition >= 0) {
        position = newPosition;
        return;
      }
    } else {
      ensure(JdkDoubleFormatter.MAX_CHARS << 1);
      int newPosition = JdkDoubleFormatter.writeUtf16(buffer, position, value);
      if (newPosition >= 0) {
        position = newPosition;
        return;
      }
      int end = JdkDoubleFormatter.write(scratch, 0, value);
      if (end >= 0) {
        writeAsciiUtf16(scratch, end);
        return;
      }
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

  private static byte[] initialBuffer(byte[] buffer) {
    return buffer.length >= JdkDoubleFormatter.MAX_CHARS
        ? buffer
        : new byte[JdkDoubleFormatter.MAX_CHARS];
  }

  private void writeDecimalBuilder(StringBuilder builder) {
    int length = builder.length();
    if (coder == LATIN1) {
      byte[] bytes = buffer;
      int pos = position;
      for (int i = 0; i < length; i++) {
        bytes[pos++] = (byte) builder.charAt(i);
      }
      position = pos;
      return;
    }
    ensure(length << 1);
    byte[] bytes = buffer;
    int pos = position;
    for (int i = 0; i < length; i++) {
      pos = putUtf16Byte(bytes, pos, (byte) builder.charAt(i));
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
      int scale = BigDecimalFields.scale(value);
      if (coder == LATIN1) {
        writeCompactBigDecimalLatin1(compact, scale);
      } else {
        writeCompactBigDecimalUtf16(compact, scale);
      }
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
    if (coder == LATIN1) {
      writeStringLatin1(value);
      return;
    }
    writeStringUtf16(value);
  }

  @Override
  public void writeString(CharSequence value) {
    if (value instanceof String) {
      writeString((String) value);
      return;
    }
    if (coder == LATIN1) {
      ensure(value.length() * 6 + 2);
      writeStringCharsNoEnsure(value);
      return;
    }
    writeStringUtf16(value);
  }

  @Override
  public void writeUuid(UUID value) {
    writeByteRaw((byte) '"');
    long high = value.getMostSignificantBits();
    writeHex(high, 60, 8);
    writeByteRaw((byte) '-');
    writeHex(high, 28, 4);
    writeByteRaw((byte) '-');
    writeHex(high, 12, 4);
    long low = value.getLeastSignificantBits();
    writeByteRaw((byte) '-');
    writeHex(low, 60, 4);
    writeByteRaw((byte) '-');
    writeHex(low, 44, 12);
    writeByteRaw((byte) '"');
  }

  @Override
  public void writeLocalDate(LocalDate value) {
    int year = value.getYear();
    if (year < 0 || year > 9999) {
      writeTemporal(value, DateTimeFormatter.ISO_LOCAL_DATE);
      return;
    }
    writeByteRaw((byte) '"');
    writePadded4(year);
    writeByteRaw((byte) '-');
    writeTwoDigits(value.getMonthValue());
    writeByteRaw((byte) '-');
    writeTwoDigits(value.getDayOfMonth());
    writeByteRaw((byte) '"');
  }

  @Override
  public void writeOffsetDateTime(OffsetDateTime value) {
    int year = value.getYear();
    if (year < 0 || year > 9999 || value.getOffset().getTotalSeconds() != 0) {
      writeTemporal(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      return;
    }
    writeByteRaw((byte) '"');
    writePadded4(year);
    writeByteRaw((byte) '-');
    writeTwoDigits(value.getMonthValue());
    writeByteRaw((byte) '-');
    writeTwoDigits(value.getDayOfMonth());
    writeByteRaw((byte) 'T');
    writeTwoDigits(value.getHour());
    writeByteRaw((byte) ':');
    writeTwoDigits(value.getMinute());
    int second = value.getSecond();
    int nano = value.getNano();
    if (second != 0 || nano != 0) {
      writeByteRaw((byte) ':');
      writeTwoDigits(second);
      if (nano != 0) {
        writeByteRaw((byte) '.');
        writeNano(nano);
      }
    }
    writeByteRaw((byte) 'Z');
    writeByteRaw((byte) '"');
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
  public StringJsonWriter append(CharSequence value) {
    return append(value, 0, value.length());
  }

  @Override
  public StringJsonWriter append(CharSequence value, int start, int end) {
    for (int i = start; i < end; i++) {
      append(value.charAt(i));
    }
    return this;
  }

  @Override
  public StringJsonWriter append(char value) {
    writeEscapedChar(value);
    return this;
  }

  private void writeStringLatin1(String value) {
    if (STRING_BYTES_BACKED) {
      byte[] bytes = StringSerializer.getStringBytes(value);
      int length = value.length();
      if (bytes.length == length) {
        ensure(bytes.length + 2);
        writeLatin1StringNoEnsure(bytes);
        return;
      }
      if (LITTLE_ENDIAN) {
        upgradeToUtf16((position << 1) + ((length + 2) << 1));
        writeUtf16StringBytes(value, bytes);
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

  public void writeIntField(
      byte[] namePrefix,
      byte[] commaNamePrefix,
      byte[] utf16NamePrefix,
      byte[] utf16CommaNamePrefix,
      int index,
      int value) {
    if (coder == LATIN1) {
      writeIntField(index == 0 ? namePrefix : commaNamePrefix, value);
      return;
    }
    writeIntFieldUtf16Value(index == 0 ? utf16NamePrefix : utf16CommaNamePrefix, value);
  }

  public void writeIntField(byte[] prefix, int value) {
    if (coder == LATIN1) {
      writeIntFieldLatin1(prefix, value);
      return;
    }
    writeIntFieldUtf16(prefix, value);
  }

  public void writeIntField(byte[] prefix, byte[] utf16Prefix, int value) {
    if (coder == LATIN1) {
      writeIntFieldLatin1(prefix, value);
      return;
    }
    writeIntFieldUtf16Value(utf16Prefix, value);
  }

  public void writeIntField(
      byte[] prefix,
      long utf16Prefix0,
      long utf16Prefix1,
      long utf16Prefix2,
      long utf16Prefix3,
      int utf16PrefixLength,
      int value) {
    if (coder == LATIN1) {
      writeIntFieldLatin1(prefix, value);
      return;
    }
    writeIntFieldUtf16Packed(
        utf16Prefix0, utf16Prefix1, utf16Prefix2, utf16Prefix3, utf16PrefixLength, value);
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

  private void writeIntFieldUtf16Value(byte[] utf16Prefix, int value) {
    ensure(utf16Prefix.length + 22);
    writeRawUtf16ValueNoEnsure(utf16Prefix);
    writeIntUtf16NoEnsure(value);
  }

  private void writeIntFieldUtf16Packed(
      long utf16Prefix0,
      long utf16Prefix1,
      long utf16Prefix2,
      long utf16Prefix3,
      int utf16PrefixLength,
      int value) {
    ensurePackedUtf16Prefix(utf16PrefixLength, 22);
    writePackedUtf16ValueNoEnsure(
        utf16Prefix0, utf16Prefix1, utf16Prefix2, utf16Prefix3, utf16PrefixLength);
    writeIntUtf16NoEnsure(value);
  }

  public void writeObjectStartWithIntField(byte[] namePrefix, int value) {
    enterDepth();
    if (coder == LATIN1) {
      writeObjectStartWithIntFieldLatin1(namePrefix, value);
      return;
    }
    writeObjectStartWithIntFieldUtf16(namePrefix, value);
  }

  public void writeObjectStartWithIntField(byte[] namePrefix, byte[] utf16NamePrefix, int value) {
    enterDepth();
    if (coder == LATIN1) {
      writeObjectStartWithIntFieldLatin1(namePrefix, value);
      return;
    }
    writeObjectStartWithIntFieldUtf16Value(utf16NamePrefix, value);
  }

  public void writeObjectStartWithIntField(
      byte[] namePrefix,
      long utf16Prefix0,
      long utf16Prefix1,
      long utf16Prefix2,
      long utf16Prefix3,
      int utf16PrefixLength,
      int value) {
    enterDepth();
    if (coder == LATIN1) {
      writeObjectStartWithIntFieldLatin1(namePrefix, value);
      return;
    }
    writeObjectStartWithIntFieldUtf16Packed(
        utf16Prefix0, utf16Prefix1, utf16Prefix2, utf16Prefix3, utf16PrefixLength, value);
  }

  private void writeObjectStartWithIntFieldLatin1(byte[] namePrefix, int value) {
    ensure(namePrefix.length + 12);
    buffer[position++] = (byte) '{';
    writeRawLatin1NoEnsure(namePrefix);
    writeIntNoEnsure(value);
  }

  private void writeObjectStartWithIntFieldUtf16(byte[] namePrefix, int value) {
    ensure(((namePrefix.length + 1) << 1) + 22);
    writeUtf16ByteNoEnsure((byte) '{');
    writeRawUtf16NoEnsure(namePrefix);
    writeIntUtf16NoEnsure(value);
  }

  private void writeObjectStartWithIntFieldUtf16Value(byte[] utf16NamePrefix, int value) {
    ensure(utf16NamePrefix.length + 24);
    writeUtf16ByteNoEnsure((byte) '{');
    writeRawUtf16ValueNoEnsure(utf16NamePrefix);
    writeIntUtf16NoEnsure(value);
  }

  private void writeObjectStartWithIntFieldUtf16Packed(
      long utf16Prefix0,
      long utf16Prefix1,
      long utf16Prefix2,
      long utf16Prefix3,
      int utf16PrefixLength,
      int value) {
    ensurePackedUtf16Prefix(utf16PrefixLength, 24);
    writeUtf16ByteNoEnsure((byte) '{');
    writePackedUtf16ValueNoEnsure(
        utf16Prefix0, utf16Prefix1, utf16Prefix2, utf16Prefix3, utf16PrefixLength);
    writeIntUtf16NoEnsure(value);
  }

  public void writeLongField(byte[] namePrefix, byte[] commaNamePrefix, int index, long value) {
    byte[] prefix = index == 0 ? namePrefix : commaNamePrefix;
    writeLongField(prefix, value);
  }

  public void writeLongField(
      byte[] namePrefix,
      byte[] commaNamePrefix,
      byte[] utf16NamePrefix,
      byte[] utf16CommaNamePrefix,
      int index,
      long value) {
    if (coder == LATIN1) {
      writeLongField(index == 0 ? namePrefix : commaNamePrefix, value);
      return;
    }
    writeLongFieldUtf16Value(index == 0 ? utf16NamePrefix : utf16CommaNamePrefix, value);
  }

  public void writeLongField(byte[] prefix, long value) {
    if (coder == LATIN1) {
      writeLongFieldLatin1(prefix, value);
      return;
    }
    writeLongFieldUtf16(prefix, value);
  }

  public void writeLongField(byte[] prefix, byte[] utf16Prefix, long value) {
    if (coder == LATIN1) {
      writeLongFieldLatin1(prefix, value);
      return;
    }
    writeLongFieldUtf16Value(utf16Prefix, value);
  }

  public void writeLongField(
      byte[] prefix,
      long utf16Prefix0,
      long utf16Prefix1,
      long utf16Prefix2,
      long utf16Prefix3,
      int utf16PrefixLength,
      long value) {
    if (coder == LATIN1) {
      writeLongFieldLatin1(prefix, value);
      return;
    }
    writeLongFieldUtf16Packed(
        utf16Prefix0, utf16Prefix1, utf16Prefix2, utf16Prefix3, utf16PrefixLength, value);
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

  private void writeLongFieldUtf16Value(byte[] utf16Prefix, long value) {
    ensure(utf16Prefix.length + 40);
    writeRawUtf16ValueNoEnsure(utf16Prefix);
    writeLongUtf16NoEnsure(value);
  }

  private void writeLongFieldUtf16Packed(
      long utf16Prefix0,
      long utf16Prefix1,
      long utf16Prefix2,
      long utf16Prefix3,
      int utf16PrefixLength,
      long value) {
    ensurePackedUtf16Prefix(utf16PrefixLength, 40);
    writePackedUtf16ValueNoEnsure(
        utf16Prefix0, utf16Prefix1, utf16Prefix2, utf16Prefix3, utf16PrefixLength);
    writeLongUtf16NoEnsure(value);
  }

  public void writeObjectStartWithLongField(byte[] namePrefix, long value) {
    enterDepth();
    if (coder == LATIN1) {
      writeObjectStartWithLongFieldLatin1(namePrefix, value);
      return;
    }
    writeObjectStartWithLongFieldUtf16(namePrefix, value);
  }

  public void writeObjectStartWithLongField(byte[] namePrefix, byte[] utf16NamePrefix, long value) {
    enterDepth();
    if (coder == LATIN1) {
      writeObjectStartWithLongFieldLatin1(namePrefix, value);
      return;
    }
    writeObjectStartWithLongFieldUtf16Value(utf16NamePrefix, value);
  }

  public void writeObjectStartWithLongField(
      byte[] namePrefix,
      long utf16Prefix0,
      long utf16Prefix1,
      long utf16Prefix2,
      long utf16Prefix3,
      int utf16PrefixLength,
      long value) {
    enterDepth();
    if (coder == LATIN1) {
      writeObjectStartWithLongFieldLatin1(namePrefix, value);
      return;
    }
    writeObjectStartWithLongFieldUtf16Packed(
        utf16Prefix0, utf16Prefix1, utf16Prefix2, utf16Prefix3, utf16PrefixLength, value);
  }

  private void writeObjectStartWithLongFieldLatin1(byte[] namePrefix, long value) {
    ensure(namePrefix.length + 21);
    buffer[position++] = (byte) '{';
    writeRawLatin1NoEnsure(namePrefix);
    writeLongNoEnsure(value);
  }

  private void writeObjectStartWithLongFieldUtf16(byte[] namePrefix, long value) {
    ensure(((namePrefix.length + 1) << 1) + 40);
    writeUtf16ByteNoEnsure((byte) '{');
    writeRawUtf16NoEnsure(namePrefix);
    writeLongUtf16NoEnsure(value);
  }

  private void writeObjectStartWithLongFieldUtf16Value(byte[] utf16NamePrefix, long value) {
    ensure(utf16NamePrefix.length + 42);
    writeUtf16ByteNoEnsure((byte) '{');
    writeRawUtf16ValueNoEnsure(utf16NamePrefix);
    writeLongUtf16NoEnsure(value);
  }

  private void writeObjectStartWithLongFieldUtf16Packed(
      long utf16Prefix0,
      long utf16Prefix1,
      long utf16Prefix2,
      long utf16Prefix3,
      int utf16PrefixLength,
      long value) {
    ensurePackedUtf16Prefix(utf16PrefixLength, 42);
    writeUtf16ByteNoEnsure((byte) '{');
    writePackedUtf16ValueNoEnsure(
        utf16Prefix0, utf16Prefix1, utf16Prefix2, utf16Prefix3, utf16PrefixLength);
    writeLongUtf16NoEnsure(value);
  }

  public void writeStringField(byte[] namePrefix, byte[] commaNamePrefix, int index, String value) {
    byte[] prefix = index == 0 ? namePrefix : commaNamePrefix;
    writeStringField(prefix, value);
  }

  public void writeStringField(
      byte[] namePrefix,
      byte[] commaNamePrefix,
      byte[] utf16NamePrefix,
      byte[] utf16CommaNamePrefix,
      int index,
      String value) {
    if (coder == LATIN1) {
      writeStringField(index == 0 ? namePrefix : commaNamePrefix, value);
      return;
    }
    writeStringFieldUtf16Value(index == 0 ? utf16NamePrefix : utf16CommaNamePrefix, value);
  }

  public void writeStringField(byte[] prefix, String value) {
    if (coder == LATIN1) {
      writeStringFieldLatin1(prefix, value);
      return;
    }
    writeStringFieldUtf16(prefix, value);
  }

  public void writeStringField(byte[] prefix, byte[] utf16Prefix, String value) {
    if (coder == LATIN1) {
      writeStringFieldLatin1(prefix, utf16Prefix, value);
      return;
    }
    writeStringFieldUtf16Value(utf16Prefix, value);
  }

  public void writeStringField(
      byte[] prefix,
      long utf16Prefix0,
      long utf16Prefix1,
      long utf16Prefix2,
      long utf16Prefix3,
      int utf16PrefixLength,
      String value) {
    if (coder == LATIN1) {
      writeStringFieldLatin1(prefix, null, value);
      return;
    }
    writeStringFieldUtf16Packed(
        utf16Prefix0, utf16Prefix1, utf16Prefix2, utf16Prefix3, utf16PrefixLength, value);
  }

  private void writeStringFieldLatin1(byte[] prefix, String value) {
    writeStringFieldLatin1(prefix, null, value);
  }

  private void writeStringFieldLatin1(byte[] prefix, byte[] utf16Prefix, String value) {
    if (STRING_BYTES_BACKED) {
      byte[] bytes = StringSerializer.getStringBytes(value);
      int length = value.length();
      if (bytes.length == length) {
        ensure(prefix.length + bytes.length + 2);
        writeRawLatin1NoEnsure(prefix);
        writeLatin1StringNoEnsure(bytes);
        return;
      }
      if (LITTLE_ENDIAN) {
        int utf16PrefixLength = utf16Prefix == null ? prefix.length << 1 : utf16Prefix.length;
        // A compact UTF16 input cannot remain in a Latin1 JSON String. Upgrade before writing the
        // pending prefix so it is emitted once in the final coder instead of being written as
        // Latin1 and widened immediately by the first non-Latin1 value character.
        upgradeToUtf16((position << 1) + utf16PrefixLength + ((length + 2) << 1));
        if (utf16Prefix == null) {
          writeRawUtf16NoEnsure(prefix);
        } else {
          writeRawUtf16ValueNoEnsure(utf16Prefix);
        }
        writeUtf16StringBytes(value, bytes);
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

  private void writeStringFieldUtf16Value(byte[] utf16Prefix, String value) {
    ensure(utf16Prefix.length);
    writeRawUtf16ValueNoEnsure(utf16Prefix);
    writeStringUtf16(value);
  }

  private void writeStringFieldUtf16Packed(
      long utf16Prefix0,
      long utf16Prefix1,
      long utf16Prefix2,
      long utf16Prefix3,
      int utf16PrefixLength,
      String value) {
    ensurePackedUtf16Prefix(utf16PrefixLength, 0);
    writePackedUtf16ValueNoEnsure(
        utf16Prefix0, utf16Prefix1, utf16Prefix2, utf16Prefix3, utf16PrefixLength);
    writeStringUtf16(value);
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
      writeNullStringElementUtf16(comma);
      return;
    }
    ensure(comma + 4);
    if (comma != 0) {
      buffer[position++] = ',';
    }
    writeAsciiLatin1NoEnsure("null");
  }

  private void writeNullStringElementUtf16(int comma) {
    ensure((comma + 4) << 1);
    if (comma != 0) {
      writeUtf16ByteNoEnsure((byte) ',');
    }
    writeAsciiUtf16NoEnsure("null", 4);
  }

  private void writeStringElementLatin1(int comma, String value) {
    if (STRING_BYTES_BACKED) {
      byte[] bytes = StringSerializer.getStringBytes(value);
      int length = value.length();
      if (bytes.length == length) {
        ensure(comma + bytes.length + 2);
        if (comma != 0) {
          buffer[position++] = ',';
        }
        writeLatin1StringNoEnsure(bytes);
        return;
      }
      if (LITTLE_ENDIAN) {
        upgradeToUtf16((position << 1) + ((comma + length + 2) << 1));
        if (comma != 0) {
          writeUtf16ByteNoEnsure((byte) ',');
        }
        writeUtf16StringBytes(value, bytes);
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

  private void writeStringElementUtf16Nullable(int comma, String value) {
    if (value == null) {
      writeNullStringElementUtf16(comma);
      return;
    }
    writeStringElementUtf16(comma, value);
  }

  public void writeStringCollection(Collection<String> values) {
    writeArrayStart();
    if (coder == UTF16) {
      writeStringCollectionUtf16(values);
      writeArrayEnd();
      return;
    }
    if (values.getClass() == ArrayList.class) {
      ArrayList<String> list = (ArrayList<String>) values;
      int size = list.size();
      if (size != 0) {
        writeStringElement(0, list.get(0));
        for (int i = 1; i < size; i++) {
          writeStringElement(1, list.get(i));
        }
      }
    } else {
      int index = 0;
      for (String value : values) {
        writeStringElement(index++, value);
      }
    }
    writeArrayEnd();
  }

  private void writeStringCollectionUtf16(Collection<String> values) {
    if (values.getClass() == ArrayList.class) {
      ArrayList<String> list = (ArrayList<String>) values;
      int size = list.size();
      if (size != 0) {
        writeStringElementUtf16Nullable(0, list.get(0));
        for (int i = 1; i < size; i++) {
          writeStringElementUtf16Nullable(1, list.get(i));
        }
      }
    } else {
      int index = 0;
      for (String value : values) {
        writeStringElementUtf16Nullable(index++, value);
      }
    }
  }

  public void writeRawValue(byte[] value) {
    writeRaw(value);
  }

  /** Writes trusted JSON text without quoting, escaping, or validating its JSON grammar. */
  public void writeRawValue(String value) {
    int length = value.length();
    if (coder == UTF16) {
      writeRawStringUtf16(value, 0, length);
      return;
    }
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
      if ((c0 | c1 | c2 | c3) > 0xff) {
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
      if (ch > 0xff) {
        position = pos;
        upgradeToUtf16(rawUtf16Capacity(pos, length - index));
        latin1Output = false;
        writeRawStringUtf16(value, index, length);
        return;
      }
      bytes[pos++] = (byte) ch;
      index++;
    }
    position = pos;
  }

  public void writeRawValue(byte[] value, byte[] utf16Value) {
    if (coder == LATIN1) {
      writeRaw(value);
      return;
    }
    writeRawUtf16Value(utf16Value);
  }

  public void writeRawValue(
      byte[] value,
      long utf16Value0,
      long utf16Value1,
      long utf16Value2,
      long utf16Value3,
      int utf16Length) {
    if (coder == LATIN1) {
      writeRaw(value);
      return;
    }
    writePackedUtf16Value(utf16Value0, utf16Value1, utf16Value2, utf16Value3, utf16Length);
  }

  public void writeRawValue(
      byte[] namePrefix,
      byte[] commaNamePrefix,
      byte[] utf16NamePrefix,
      byte[] utf16CommaNamePrefix,
      int index) {
    if (coder == LATIN1) {
      writeRaw(index == 0 ? namePrefix : commaNamePrefix);
      return;
    }
    writeRawUtf16Value(index == 0 ? utf16NamePrefix : utf16CommaNamePrefix);
  }

  /** Writes a byte array as a quoted Base64 JSON string without an intermediate String. */
  public void writeBase64(byte[] value) {
    int encodedLength = base64Length(value.length);
    if (coder == LATIN1) {
      ensure(base64Additional(encodedLength, 1));
      byte[] target = buffer;
      int pos = position;
      target[pos++] = '"';
      pos = writeBase64Latin1(value, target, pos);
      target[pos++] = '"';
      position = pos;
      return;
    }
    ensure(base64Additional(encodedLength, 2));
    writeUtf16ByteNoEnsure((byte) '"');
    writeBase64Utf16(value);
    writeUtf16ByteNoEnsure((byte) '"');
  }

  private void writeRawStringUtf16(String value, int index, int length) {
    ensure(rawUtf16Additional(length - index));
    for (int i = index; i < length; i++) {
      char ch = value.charAt(i);
      if (ch > 0xff) {
        latin1Output = false;
      }
      writeUtf16CharNoEnsure(ch);
    }
  }

  private static int writeBase64Latin1(byte[] value, byte[] target, int pos) {
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
    return pos;
  }

  private void writeBase64Utf16(byte[] value) {
    int index = 0;
    int end = value.length - 2;
    while (index < end) {
      int bits =
          ((value[index++] & 0xff) << 16)
              | ((value[index++] & 0xff) << 8)
              | (value[index++] & 0xff);
      writeUtf16ByteNoEnsure(BASE64_DIGITS[bits >>> 18]);
      writeUtf16ByteNoEnsure(BASE64_DIGITS[(bits >>> 12) & 0x3f]);
      writeUtf16ByteNoEnsure(BASE64_DIGITS[(bits >>> 6) & 0x3f]);
      writeUtf16ByteNoEnsure(BASE64_DIGITS[bits & 0x3f]);
    }
    int remaining = value.length - index;
    if (remaining != 0) {
      int bits = (value[index] & 0xff) << 16;
      if (remaining == 2) {
        bits |= (value[index + 1] & 0xff) << 8;
      }
      writeUtf16ByteNoEnsure(BASE64_DIGITS[bits >>> 18]);
      writeUtf16ByteNoEnsure(BASE64_DIGITS[(bits >>> 12) & 0x3f]);
      writeUtf16ByteNoEnsure(remaining == 2 ? BASE64_DIGITS[(bits >>> 6) & 0x3f] : (byte) '=');
      writeUtf16ByteNoEnsure((byte) '=');
    }
  }

  private static int base64Length(int length) {
    long encoded = ((length + 2L) / 3L) * 4L;
    if (encoded > Integer.MAX_VALUE - 2L) {
      throw new ForyJsonException("Byte array is too large for Base64 JSON output");
    }
    return (int) encoded;
  }

  private int base64Additional(int encodedLength, int bytesPerChar) {
    long additional = (encodedLength + 2L) * bytesPerChar;
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

  private int rawUtf16Additional(int chars) {
    long additional = (long) chars << 1;
    if (additional > Integer.MAX_VALUE - (long) position) {
      throw new ForyJsonException("Raw JSON output is too large");
    }
    return (int) additional;
  }

  private static int rawUtf16Capacity(int latin1Position, int chars) {
    long required = ((long) latin1Position + chars) << 1;
    if (required > Integer.MAX_VALUE) {
      throw new ForyJsonException("Raw JSON output is too large");
    }
    return (int) required;
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

  private void writeStringCharsNoEnsure(CharSequence value) {
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
      if (!isJsonAsciiWords(word0, word1)) {
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
    if (STRING_BYTES_BACKED) {
      byte[] bytes = StringSerializer.getStringBytes(value);
      int length = value.length();
      if (bytes.length == length) {
        writeLatin1StringUtf16(bytes);
        return;
      }
      if (LITTLE_ENDIAN) {
        writeUtf16StringBytes(value, bytes);
        return;
      }
    }
    writeStringUtf16Chars(value);
  }

  private void writeStringUtf16(CharSequence value) {
    int length = value.length();
    ensure((length + 2) << 1);
    writeUtf16ByteNoEnsure((byte) '"');
    for (int i = 0; i < length; i++) {
      char ch = value.charAt(i);
      if (isJsonUtf16(ch)) {
        if (ch > 0xff) {
          latin1Output = false;
        }
        writeUtf16CharNoEnsure(ch);
      } else {
        writeStringUtf16Slow(value, i, length);
        return;
      }
    }
    writeByteRaw((byte) '"');
  }

  private void writeStringUtf16Chars(String value) {
    int length = value.length();
    ensure((length + 2) << 1);
    writeUtf16ByteNoEnsure((byte) '"');
    for (int i = 0; i < length; i++) {
      char ch = value.charAt(i);
      if (isJsonUtf16(ch)) {
        if (ch > 0xff) {
          latin1Output = false;
        }
        writeUtf16CharNoEnsure(ch);
      } else {
        writeStringUtf16Slow(value, i, length);
        return;
      }
    }
    writeByteRaw((byte) '"');
  }

  private void writeUtf16StringBytes(String value, byte[] source) {
    latin1Output = false;
    int length = value.length();
    int byteLength = length << 1;
    ensure(byteLength + 4);
    writeUtf16ByteNoEnsure((byte) '"');
    byte[] target = buffer;
    int pos = position;
    int index = 0;
    int wordEnd = byteLength & ~7;
    for (; index < wordEnd; index += 8) {
      long word = LittleEndian.getInt64(source, index);
      long quote = word ^ UTF16_QUOTE_CHARS;
      long backslash = word ^ UTF16_BACKSLASH_CHARS;
      long surrogate = (word & UTF16_SURROGATE_MASK) ^ UTF16_SURROGATE_PREFIX;
      long stop =
          ((quote - UTF16_ONES) & ~quote & UTF16_HIGH_BITS)
              | ((backslash - UTF16_ONES) & ~backslash & UTF16_HIGH_BITS)
              | ((word - UTF16_CONTROL_LIMIT) & ~word & UTF16_HIGH_BITS)
              | ((surrogate - UTF16_ONES) & ~surrogate & UTF16_HIGH_BITS);
      if (stop != 0) {
        position = pos;
        writeStringUtf16Slow(value, index >>> 1, length);
        return;
      }
      LittleEndian.putInt64(target, pos, word);
      pos += 8;
    }
    for (; index < byteLength; index += 2) {
      char ch = (char) ((source[index] & 0xff) | ((source[index + 1] & 0xff) << 8));
      if (!isJsonUtf16(ch)) {
        position = pos;
        writeStringUtf16Slow(value, index >>> 1, length);
        return;
      }
      target[pos] = source[index];
      target[pos + 1] = source[index + 1];
      pos += 2;
    }
    position = pos;
    writeUtf16ByteNoEnsure((byte) '"');
  }

  private void writeLatin1StringUtf16(byte[] value) {
    int length = value.length;
    ensure((length + 2) << 1);
    writeUtf16ByteNoEnsure((byte) '"');
    byte[] target = buffer;
    int pos = position;
    int i = 0;
    int upperBound = length & ~7;
    for (; i < upperBound; i += 8) {
      long word = LittleEndian.getInt64(value, i);
      if (!isJsonAsciiWord(word)) {
        break;
      }
      putLatin1WordAsUtf16(target, pos, word);
      pos += 16;
    }
    if (i + 4 <= length) {
      int word = LittleEndian.getInt32(value, i);
      if (isJsonAsciiInt(word)) {
        putLatin1IntAsUtf16(target, pos, word);
        pos += 8;
        i += 4;
      }
    }
    while (i < length) {
      byte ch = value[i];
      if (isJsonLatin1Byte(ch)) {
        pos = putUtf16Char(target, pos, (char) (ch & 0xff));
        i++;
      } else {
        position = pos;
        writeLatin1StringUtf16Slow(value, i, length);
        return;
      }
    }
    position = pos;
    writeUtf16ByteNoEnsure((byte) '"');
  }

  private void writeLatin1StringUtf16Slow(byte[] value, int index, int length) {
    for (int i = index; i < length; i++) {
      writeEscapedChar((char) (value[i] & 0xff));
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
        latin1Output = false;
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

  private void writeStringUtf16Slow(CharSequence value, int index, int length) {
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
        latin1Output = false;
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

  private void writeAsciiNumber(String value) {
    int length = value.length();
    if (coder == LATIN1) {
      ensure(length);
      writeLatin1NumberNoEnsure(value, length);
    } else {
      ensure(length << 1);
      writeUtf16NumberNoEnsure(value, length);
    }
  }

  private void writeBigNumberText(String value) {
    int length = value.length();
    if (coder == LATIN1) {
      ensureNumberLatin1(length);
      writeLatin1NumberNoEnsure(value, length);
      return;
    }
    ensureNumberUtf16(length);
    writeUtf16NumberNoEnsure(value, length);
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

  private void writeLatin1NumberNoEnsure(String value, int length) {
    if (STRING_BYTES_BACKED) {
      byte[] bytes = StringSerializer.getStringBytes(value);
      if (bytes.length == length) {
        System.arraycopy(bytes, 0, buffer, position, length);
        position += length;
        return;
      }
    }
    writeAsciiLatin1NoEnsure(value);
  }

  private void writeUtf16NumberNoEnsure(String value, int length) {
    if (STRING_BYTES_BACKED) {
      byte[] bytes = StringSerializer.getStringBytes(value);
      if (bytes.length == length) {
        writeAsciiUtf16NoEnsure(bytes, length);
        return;
      }
    }
    writeAsciiUtf16NoEnsure(value, length);
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

  private void writeAsciiUtf16(byte[] source, int length) {
    ensure(length << 1);
    writeAsciiUtf16NoEnsure(source, length);
  }

  private void writeAsciiUtf16NoEnsure(String value, int length) {
    byte[] bytes = buffer;
    int pos = position;
    for (int i = 0; i < length; i++) {
      pos = putUtf16Char(bytes, pos, value.charAt(i));
    }
    position = pos;
  }

  private void writeAsciiUtf16NoEnsure(byte[] source, int length) {
    byte[] target = buffer;
    int sourceOffset = 0;
    int pos = position;
    int bulkEnd = length & ~3;
    while (sourceOffset < bulkEnd) {
      long packed = LittleEndian.getInt32(source, sourceOffset) & 0xffff_ffffL;
      long utf16 = spreadLatin1ToUtf16(packed);
      LittleEndian.putInt64(target, pos, LITTLE_ENDIAN ? utf16 : utf16 << 8);
      sourceOffset += 4;
      pos += 8;
    }
    while (sourceOffset < length) {
      pos = putUtf16Byte(target, pos, source[sourceOffset++]);
    }
    position = pos;
  }

  private void writeAsciiLatin1NoEnsure(String value) {
    int length = value.length();
    for (int i = 0; i < length; i++) {
      buffer[position++] = (byte) value.charAt(i);
    }
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

  private void writeRawUtf16Value(byte[] bytes) {
    ensure(bytes.length);
    writeRawUtf16ValueNoEnsure(bytes);
  }

  private void writePackedUtf16Value(
      long value0, long value1, long value2, long value3, int length) {
    ensure(packedUtf16PrefixSize(length));
    writePackedUtf16ValueNoEnsure(value0, value1, value2, value3, length);
  }

  private void writeRawUtf16ValueNoEnsure(byte[] bytes) {
    System.arraycopy(bytes, 0, buffer, position, bytes.length);
    position += bytes.length;
  }

  private void writePackedUtf16ValueNoEnsure(
      long value0, long value1, long value2, long value3, int length) {
    byte[] target = buffer;
    int pos = position;
    LittleEndian.putInt64(target, pos, value0);
    if (length > Long.BYTES) {
      LittleEndian.putInt64(target, pos + Long.BYTES, value1);
      if (length > Long.BYTES * 2) {
        LittleEndian.putInt64(target, pos + Long.BYTES * 2, value2);
        if (length > Long.BYTES * 3) {
          LittleEndian.putInt64(target, pos + Long.BYTES * 3, value3);
        }
      }
    }
    position = pos + length;
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

  // Keep each coder's complete compact-decimal layout in one method. Small layout helpers are
  // recursively inlined into generated object writers and can exhaust their C2 node budget.
  private void writeCompactBigDecimalLatin1(long unscaled, int scale) {
    if (scale == 0) {
      writeLongLatin1(unscaled);
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
    ensureNumberLatin1(outputChars + BigNumberDigits.PACKED_WRITE_SLACK);
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
        writePaddedCompactLatin1(unscaled, precision);
        return;
      }
      long divisor = BigNumberDigits.LONG_POWERS_OF_TEN[scale];
      long integer = unscaled / divisor;
      long fraction = unscaled - integer * divisor;
      writePaddedCompactLatin1(integer, (int) point);
      buffer[position++] = (byte) '.';
      writePaddedCompactLatin1(fraction, scale);
      return;
    }
    if (precision == 1) {
      writePositiveLongLatin1NoEnsure(unscaled);
    } else {
      long divisor = BigNumberDigits.LONG_POWERS_OF_TEN[precision - 1];
      int first = (int) (unscaled / divisor);
      long rest = unscaled - first * divisor;
      byte[] bytes = buffer;
      int pos = position;
      bytes[pos++] = (byte) ('0' + first);
      bytes[pos++] = (byte) '.';
      position = pos;
      writePaddedCompactLatin1(rest, precision - 1);
    }
    byte[] bytes = buffer;
    int pos = position;
    bytes[pos++] = (byte) 'E';
    if (adjustedExponent >= 0) {
      bytes[pos++] = (byte) '+';
    }
    position = pos;
    writeLongLatin1NoEnsure(adjustedExponent);
  }

  private void writeCompactBigDecimalUtf16(long unscaled, int scale) {
    if (scale == 0) {
      writeLongUtf16(unscaled);
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
    ensureNumberUtf16(outputChars + BigNumberDigits.PACKED_WRITE_SLACK);
    if (negative) {
      position = putUtf16Byte(buffer, position, (byte) '-');
    }
    if (plain) {
      if (point <= 0) {
        byte[] bytes = buffer;
        int pos = position;
        pos = putUtf16Byte(bytes, pos, (byte) '0');
        pos = putUtf16Byte(bytes, pos, (byte) '.');
        long zeroes = -point;
        while (zeroes-- > 0) {
          pos = putUtf16Byte(bytes, pos, (byte) '0');
        }
        position = pos;
        writePaddedCompactUtf16(unscaled, precision);
        return;
      }
      long divisor = BigNumberDigits.LONG_POWERS_OF_TEN[scale];
      long integer = unscaled / divisor;
      long fraction = unscaled - integer * divisor;
      writePaddedCompactUtf16(integer, (int) point);
      position = putUtf16Byte(buffer, position, (byte) '.');
      writePaddedCompactUtf16(fraction, scale);
      return;
    }
    if (precision == 1) {
      position = writePositiveLongUtf16(buffer, position, unscaled);
    } else {
      long divisor = BigNumberDigits.LONG_POWERS_OF_TEN[precision - 1];
      int first = (int) (unscaled / divisor);
      long rest = unscaled - first * divisor;
      byte[] bytes = buffer;
      int pos = position;
      pos = putUtf16Byte(bytes, pos, (byte) ('0' + first));
      pos = putUtf16Byte(bytes, pos, (byte) '.');
      position = pos;
      writePaddedCompactUtf16(rest, precision - 1);
    }
    byte[] bytes = buffer;
    int pos = position;
    pos = putUtf16Byte(bytes, pos, (byte) 'E');
    if (adjustedExponent >= 0) {
      pos = putUtf16Byte(bytes, pos, (byte) '+');
    }
    position = pos;
    writeLongUtf16NoEnsure(adjustedExponent);
  }

  private void writePaddedCompactLatin1(long value, int digits) {
    if (digits <= 9) {
      position = writePaddedDigitsLatin1(buffer, position, (int) value, digits);
      return;
    }
    long high = value / 1_000_000_000L;
    int low = (int) (value - high * 1_000_000_000L);
    byte[] bytes = buffer;
    int pos = position;
    if (digits <= 18) {
      pos = writePaddedDigitsLatin1(bytes, pos, (int) high, digits - 9);
    } else {
      int top = (int) (high / 1_000_000_000L);
      int middle = (int) (high - (long) top * 1_000_000_000L);
      pos = writePaddedDigitsLatin1(bytes, pos, top, 1);
      pos = writePadded9Latin1(bytes, pos, middle);
    }
    position = writePadded9Latin1(bytes, pos, low);
  }

  private void writePaddedCompactUtf16(long value, int digits) {
    if (digits <= 9) {
      position = writePaddedDigitsUtf16(buffer, position, (int) value, digits);
      return;
    }
    long high = value / 1_000_000_000L;
    int low = (int) (value - high * 1_000_000_000L);
    byte[] bytes = buffer;
    int pos = position;
    if (digits <= 18) {
      pos = writePaddedDigitsUtf16(bytes, pos, (int) high, digits - 9);
    } else {
      int top = (int) (high / 1_000_000_000L);
      int middle = (int) (high - (long) top * 1_000_000_000L);
      pos = writePaddedDigitsUtf16(bytes, pos, top, 1);
      pos = writePadded9Utf16(bytes, pos, middle);
    }
    position = writePadded9Utf16(bytes, pos, low);
  }

  private void ensureNumberLatin1(long chars) {
    if (chars > Integer.MAX_VALUE - position) {
      throwNumberOutputTooLarge();
    }
    ensure((int) chars);
  }

  private void ensureNumberUtf16(long chars) {
    if (chars > ((long) Integer.MAX_VALUE - position) / 2) {
      throwNumberOutputTooLarge();
    }
    ensure((int) (chars << 1));
  }

  private static void throwNumberOutputTooLarge() {
    throw new ForyJsonException("JSON number output too large");
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
    if (value > 0xff) {
      latin1Output = false;
    }
    if (coder == LATIN1) {
      upgradeToUtf16((position << 1) + 2);
    } else {
      ensure(2);
    }
    writeUtf16CharNoEnsure(value);
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

  private void ensurePackedUtf16Prefix(int prefixLength, int additionalAfterPrefix) {
    ensure(Math.max(packedUtf16PrefixSize(prefixLength), prefixLength + additionalAfterPrefix));
  }

  private static int packedUtf16PrefixSize(int prefixLength) {
    return (prefixLength + Long.BYTES - 1) & -Long.BYTES;
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

  private static byte[] compressUtf16ToLatin1(byte[] source, int length) {
    int latin1Length = length >>> 1;
    byte[] target = new byte[latin1Length];
    if (LITTLE_ENDIAN) {
      for (int i = 0, j = 0; j < latin1Length; i += 2, j++) {
        target[j] = source[i];
      }
    } else {
      for (int i = 1, j = 0; j < latin1Length; i += 2, j++) {
        target[j] = source[i];
      }
    }
    return target;
  }

  private static void putLatin1WordAsUtf16(byte[] target, int offset, long word) {
    if (LITTLE_ENDIAN) {
      LittleEndian.putInt64(target, offset, spreadLatin1ToUtf16(word & 0xFFFFFFFFL));
      LittleEndian.putInt64(target, offset + 8, spreadLatin1ToUtf16(word >>> 32));
    } else {
      LittleEndian.putInt64(target, offset, spreadLatin1ToUtf16(word & 0xFFFFFFFFL) << 8);
      LittleEndian.putInt64(target, offset + 8, spreadLatin1ToUtf16(word >>> 32) << 8);
    }
  }

  private static void putLatin1IntAsUtf16(byte[] target, int offset, int word) {
    long value = spreadLatin1ToUtf16(word & 0xFFFFFFFFL);
    LittleEndian.putInt64(target, offset, LITTLE_ENDIAN ? value : value << 8);
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

  // Keep the exact uncommon fallback outside these per-word predicates. Folding it back in makes
  // the standalone predicates too large for C2 to inline into the compact-string writers.
  private static boolean isJsonAsciiWord(long word) {
    long notBackslash = ((word ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES) & HIGH_BITS;
    if ((notBackslash & (word + ASCII_GT_QUOTE_OFFSET)) == HIGH_BITS) {
      return true;
    }
    return isJsonAsciiWordFallback(word);
  }

  private static boolean isJsonAsciiWordFallback(long word) {
    long notBackslash = ((word ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES) & HIGH_BITS;
    return (((word + ASCII_CONTROL_OFFSET) & ~word) & HIGH_BITS) == HIGH_BITS
        && (((word ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES) & HIGH_BITS) == HIGH_BITS
        && notBackslash == HIGH_BITS;
  }

  // Aggregate every exact rejection mask before branching. Splitting this back into per-word
  // calls adds one common-path branch for each eight bytes written.
  private static boolean isJsonAsciiWords(long word0, long word1) {
    long notBackslash =
        ((word0 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
            & ((word1 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
            & HIGH_BITS;
    if ((notBackslash & (word0 + ASCII_GT_QUOTE_OFFSET) & (word1 + ASCII_GT_QUOTE_OFFSET))
        == HIGH_BITS) {
      return true;
    }
    return isJsonAsciiWordsFallback(word0, word1, notBackslash);
  }

  private static boolean isJsonAsciiWordsFallback(long word0, long word1, long notBackslash) {
    return ((word0 + ASCII_CONTROL_OFFSET)
            & (word1 + ASCII_CONTROL_OFFSET)
            & ((word0 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
            & ((word1 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
            & notBackslash)
        == HIGH_BITS;
  }

  private static boolean isJsonAsciiInt(int word) {
    int notBackslash = ((word ^ INT_BACKSLASH_BYTES_COMPLEMENT) + INT_ONE_BYTES) & INT_HIGH_BITS;
    if ((notBackslash & (word + INT_ASCII_GT_QUOTE_OFFSET)) == INT_HIGH_BITS) {
      return true;
    }
    return isJsonAsciiIntFallback(word, notBackslash);
  }

  private static boolean isJsonAsciiIntFallback(int word, int notBackslash) {
    return (((word + INT_ASCII_CONTROL_OFFSET) & ~word) & INT_HIGH_BITS) == INT_HIGH_BITS
        && (((word ^ INT_QUOTE_BYTES_COMPLEMENT) + INT_ONE_BYTES) & INT_HIGH_BITS) == INT_HIGH_BITS
        && notBackslash == INT_HIGH_BITS;
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
    writePositiveLongLatin1NoEnsure(value);
  }

  private void writePositiveLongLatin1NoEnsure(long value) {
    if (value <= Integer.MAX_VALUE) {
      writePositiveIntNoEnsure((int) value);
      return;
    }
    byte[] bytes = buffer;
    int pos = position;
    long high = value / DECIMAL_8;
    int low = (int) (value - high * DECIMAL_8);
    if (high < DECIMAL_8) {
      pos = writePositiveIntLatin1(bytes, pos, (int) high);
      position = writePadded8DigitsLatin1(bytes, pos, low);
      return;
    }
    long top = high / DECIMAL_8;
    int middle = (int) (high - top * DECIMAL_8);
    pos = writePositiveIntLatin1(bytes, pos, (int) top);
    pos = writePadded8DigitsLatin1(bytes, pos, middle);
    position = writePadded8DigitsLatin1(bytes, pos, low);
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
    byte[] bytes = buffer;
    int pos = position;
    if (value < 0) {
      pos = putUtf16Byte(bytes, pos, (byte) '-');
      value = -value;
    }
    position = writePositiveIntUtf16(bytes, pos, value);
  }

  private void writeLongUtf16NoEnsure(long value) {
    if (value == Long.MIN_VALUE) {
      writeRawUtf16NoEnsure(MIN_LONG_BYTES);
      return;
    }
    byte[] bytes = buffer;
    int pos = position;
    if (value < 0) {
      pos = putUtf16Byte(bytes, pos, (byte) '-');
      value = -value;
    }
    position = writePositiveLongUtf16(bytes, pos, value);
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

  private static int writePositiveIntUtf16(byte[] bytes, int pos, int value) {
    if (value < 10000) {
      return writeIntUpTo4Utf16(bytes, pos, value);
    }
    int high = divide10000(value);
    int low = value - high * 10000;
    if (high < 10000) {
      if (high >= 1000) {
        return writePadded8Utf16(bytes, pos, high, low);
      }
      pos = writeIntUpTo4Utf16(bytes, pos, high);
      return writePadded4Utf16(bytes, pos, low);
    }
    int top = divide10000(high);
    int middle = high - top * 10000;
    pos = writeIntUpTo4Utf16(bytes, pos, top);
    return writePadded8Utf16(bytes, pos, middle, low);
  }

  private static int writePositiveLongUtf16(byte[] bytes, int pos, long value) {
    if (value <= Integer.MAX_VALUE) {
      return writePositiveIntUtf16(bytes, pos, (int) value);
    }
    long high = value / DECIMAL_8;
    int low = (int) (value - high * DECIMAL_8);
    if (high < DECIMAL_8) {
      pos = writePositiveIntUtf16(bytes, pos, (int) high);
      return writePadded8Utf16(bytes, pos, low);
    }
    long top = high / DECIMAL_8;
    int middle = (int) (high - top * DECIMAL_8);
    pos = writePositiveIntUtf16(bytes, pos, (int) top);
    pos = writePadded8Utf16(bytes, pos, middle);
    return writePadded8Utf16(bytes, pos, low);
  }

  private void writeHex(long value, int shift, int count) {
    for (int i = 0; i < count; i++) {
      writeByteRaw((byte) hex((int) ((value >>> shift) & 0xF)));
      shift -= 4;
    }
  }

  private void writePadded4(int value) {
    if (coder == LATIN1) {
      ensure(4);
      position = writePadded4(buffer, position, value);
      return;
    }
    ensure(8);
    position = writePadded4Utf16(buffer, position, value);
  }

  private static int writePadded4(byte[] bytes, int pos, int value) {
    LittleEndian.putInt32(bytes, pos, DIGIT_QUADS[value]);
    return pos + 4;
  }

  private void writeTwoDigits(int value) {
    int high = value / 10;
    writeByteRaw((byte) ('0' + high));
    writeByteRaw((byte) ('0' + (value - high * 10)));
  }

  private void writeNano(int nano) {
    if (nano % 1_000_000 == 0) {
      writePadded3(nano / 1_000_000);
      return;
    }
    if (nano % 1000 == 0) {
      int micros = nano / 1000;
      int high = micros / 1000;
      int low = micros - high * 1000;
      writePadded3(high);
      writePadded3(low);
      return;
    }
    int first = nano / 100000000;
    int rem = nano - first * 100000000;
    int middle = rem / 10000;
    int low = rem - middle * 10000;
    writeByteRaw((byte) ('0' + first));
    writePadded4(middle);
    writePadded4(low);
  }

  private void writePadded3(int value) {
    int high = value / 100;
    int rem = value - high * 100;
    int middle = rem / 10;
    writeByteRaw((byte) ('0' + high));
    writeByteRaw((byte) ('0' + middle));
    writeByteRaw((byte) ('0' + (rem - middle * 10)));
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

  private static int writePadded8(byte[] bytes, int pos, int high, int low) {
    long value = (DIGIT_QUADS[high] & 0xFFFFFFFFL) | ((DIGIT_QUADS[low] & 0xFFFFFFFFL) << 32);
    LittleEndian.putInt64(bytes, pos, value);
    return pos + 8;
  }

  private static int writePositiveIntLatin1(byte[] bytes, int pos, int value) {
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
    pos = writeIntUpTo4(bytes, pos, top);
    return writePadded8(bytes, pos, middle, low);
  }

  private static int writePadded9Latin1(byte[] bytes, int pos, int value) {
    int high = value / 100_000_000;
    bytes[pos++] = (byte) ('0' + high);
    return writePadded8DigitsLatin1(bytes, pos, value - high * 100_000_000);
  }

  private static int writePaddedDigitsLatin1(byte[] bytes, int pos, int value, int digits) {
    int zeroes = digits - BigNumberDigits.digitCount(value);
    while (zeroes-- > 0) {
      bytes[pos++] = (byte) '0';
    }
    return writePositiveIntLatin1(bytes, pos, value);
  }

  private static int writePadded8DigitsLatin1(byte[] bytes, int pos, int value) {
    int high = divide10000(value);
    int low = value - high * 10000;
    return writePadded8(bytes, pos, high, low);
  }

  private static int writeIntUpTo4Utf16(byte[] bytes, int pos, int value) {
    if (value < 1000) {
      return writeIntUpTo3Utf16(bytes, pos, value);
    }
    return writePadded4Utf16(bytes, pos, value);
  }

  private static int writeIntUpTo3Utf16(byte[] bytes, int pos, int value) {
    int digits = DIGIT_TRIPLES[value];
    int skip = digits & 0xFF;
    int count = 3 - skip;
    int chars = digits >>> ((skip + 1) << 3);
    if (LITTLE_ENDIAN) {
      bytes[pos] = (byte) chars;
      bytes[pos + 1] = 0;
      if (count > 1) {
        bytes[pos + 2] = (byte) (chars >>> 8);
        bytes[pos + 3] = 0;
        if (count > 2) {
          bytes[pos + 4] = (byte) (chars >>> 16);
          bytes[pos + 5] = 0;
        }
      }
    } else {
      bytes[pos] = 0;
      bytes[pos + 1] = (byte) chars;
      if (count > 1) {
        bytes[pos + 2] = 0;
        bytes[pos + 3] = (byte) (chars >>> 8);
        if (count > 2) {
          bytes[pos + 4] = 0;
          bytes[pos + 5] = (byte) (chars >>> 16);
        }
      }
    }
    return pos + (count << 1);
  }

  private static int writePadded4Utf16(byte[] bytes, int pos, int value) {
    LittleEndian.putInt64(bytes, pos, UTF16_DIGIT_QUADS[value]);
    return pos + 8;
  }

  private static int writePadded8Utf16(byte[] bytes, int pos, int high, int low) {
    pos = writePadded4Utf16(bytes, pos, high);
    return writePadded4Utf16(bytes, pos, low);
  }

  private static int writePadded8Utf16(byte[] bytes, int pos, int value) {
    int high = divide10000(value);
    int low = value - high * 10000;
    return writePadded8Utf16(bytes, pos, high, low);
  }

  private static int writePadded9Utf16(byte[] bytes, int pos, int value) {
    int high = value / 100_000_000;
    pos = putUtf16Byte(bytes, pos, (byte) ('0' + high));
    return writePadded8Utf16(bytes, pos, value - high * 100_000_000);
  }

  private static int writePaddedDigitsUtf16(byte[] bytes, int pos, int value, int digits) {
    int zeroes = digits - BigNumberDigits.digitCount(value);
    while (zeroes-- > 0) {
      pos = putUtf16Byte(bytes, pos, (byte) '0');
    }
    return writePositiveIntUtf16(bytes, pos, value);
  }

  private static int putUtf16Byte(byte[] bytes, int pos, byte value) {
    if (LITTLE_ENDIAN) {
      bytes[pos] = value;
      bytes[pos + 1] = 0;
    } else {
      bytes[pos] = 0;
      bytes[pos + 1] = value;
    }
    return pos + 2;
  }

  private static char hex(int value) {
    return (char) (value < 10 ? '0' + value : 'a' + value - 10);
  }
}
