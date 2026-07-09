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

package org.apache.fory.json.reader;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.JsonConfig;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldNameHash;
import org.apache.fory.json.meta.JsonFieldTable;

public abstract class JsonReader {
  private static final int MAX_BIG_NUMBER_LENGTH = 10_000;
  static final int MAX_BIG_DECIMAL_SCALE = 10_000;
  private static final int COMPACT_DECIMAL_MAX_SCALE = 18;
  private static final long[] LONG_POWERS_OF_TEN = {
    1L,
    10L,
    100L,
    1_000L,
    10_000L,
    100_000L,
    1_000_000L,
    10_000_000L,
    100_000_000L,
    1_000_000_000L,
    10_000_000_000L,
    100_000_000_000L,
    1_000_000_000_000L,
    10_000_000_000_000L,
    100_000_000_000_000L,
    1_000_000_000_000_000L,
    10_000_000_000_000_000L,
    100_000_000_000_000_000L,
    1_000_000_000_000_000_000L
  };
  private static final int DOUBLE_FAST_MAX_SCALE = 15;
  private static final long DOUBLE_FAST_MAX_UNSCALED = 1L << 53;
  private static final int DOUBLE_FRACTION_BITS = 52;
  private static final long DOUBLE_SIGN_BIT = 0x8000_0000_0000_0000L;
  private static final long DOUBLE_FRACTION_MASK = (1L << DOUBLE_FRACTION_BITS) - 1;
  private static final double[] DOUBLE_POWERS_OF_TEN = {
    1.0d,
    10.0d,
    100.0d,
    1_000.0d,
    10_000.0d,
    100_000.0d,
    1_000_000.0d,
    10_000_000.0d,
    100_000_000.0d,
    1_000_000_000.0d,
    10_000_000_000.0d,
    100_000_000_000.0d,
    1_000_000_000_000.0d,
    10_000_000_000_000.0d,
    100_000_000_000_000.0d,
    1_000_000_000_000_000.0d
  };
  private static final int FLOAT_FAST_MAX_SCALE = 7;
  private static final long FLOAT_FAST_MAX_UNSCALED = 1L << 24;
  private static final int FLOAT_FRACTION_BITS = 23;
  private static final int FLOAT_SIGN_BIT = 0x8000_0000;
  private static final int FLOAT_FRACTION_MASK = (1 << FLOAT_FRACTION_BITS) - 1;
  private static final float[] FLOAT_POWERS_OF_TEN = {
    1.0f, 10.0f, 100.0f, 1_000.0f, 10_000.0f, 100_000.0f, 1_000_000.0f, 10_000_000.0f
  };

  protected int position;
  private final int maxDepth;
  private int depth;
  private final AsciiStringView asciiStringView = new AsciiStringView(this);

  protected JsonReader() {
    this.maxDepth = ForyJson.DEFAULT_MAX_DEPTH;
  }

  protected JsonReader(JsonConfig config) {
    this.maxDepth = config.maxDepth();
  }

  protected abstract int length();

  protected abstract char charAt(int index);

  public abstract String readString();

  protected final void reset() {
    depth = 0;
  }

  public final void enterDepth() {
    int nextDepth = depth + 1;
    if (nextDepth > maxDepth) {
      throwMaxDepthExceeded();
    }
    depth = nextDepth;
  }

  private void throwMaxDepthExceeded() {
    throw error("JSON max depth " + maxDepth + " exceeded");
  }

  public final void exitDepth() {
    depth--;
  }

  public String readNullableString() {
    return tryReadNull() ? null : readString();
  }

  public String readCharSequence() {
    return readString();
  }

  public final void skipWhitespace() {
    while (position < length()) {
      char ch = charAt(position);
      if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') {
        position++;
      } else {
        return;
      }
    }
  }

  public final boolean consume(char expected) {
    skipWhitespace();
    if (position < length() && charAt(position) == expected) {
      position++;
      return true;
    }
    return false;
  }

  public final void expect(char expected) {
    if (!consume(expected)) {
      throw error("Expected '" + expected + "'");
    }
  }

  public final boolean consumeCommaOrEndObject() {
    skipWhitespace();
    if (position < length()) {
      char ch = charAt(position);
      if (ch == ',') {
        position++;
        return true;
      }
      if (ch == '}') {
        position++;
        return false;
      }
    }
    throw error("Expected ',' or '}'");
  }

  public final boolean consumeCommaOrEndArray() {
    skipWhitespace();
    if (position < length()) {
      char ch = charAt(position);
      if (ch == ',') {
        position++;
        return true;
      }
      if (ch == ']') {
        position++;
        return false;
      }
    }
    throw error("Expected ',' or ']'");
  }

  public final boolean peekNull() {
    skipWhitespace();
    return startsWith("null");
  }

  public final char peekToken() {
    skipWhitespace();
    if (position >= length()) {
      throw error("Expected token");
    }
    return charAt(position);
  }

  public final void readNull() {
    skipWhitespace();
    if (!startsWith("null")) {
      throw error("Expected null");
    }
    position += 4;
  }

  public final boolean tryReadNull() {
    skipWhitespace();
    if (startsWith("null")) {
      position += 4;
      return true;
    }
    return false;
  }

  public final boolean readBoolean() {
    skipWhitespace();
    if (startsWith("true")) {
      position += 4;
      return true;
    } else if (startsWith("false")) {
      position += 5;
      return false;
    }
    throw error("Expected boolean");
  }

  public final String readNumberAsString() {
    skipWhitespace();
    return readNumberToken();
  }

  public final Number readNumber() {
    return materializeNumber(readNumberAsString());
  }

  private String readNumberToken() {
    int start = position;
    if (position < length() && charAt(position) == '-') {
      position++;
    }
    readIntegerDigits();
    if (position < length() && charAt(position) == '.') {
      position++;
      readDigits();
    }
    if (position < length() && (charAt(position) == 'e' || charAt(position) == 'E')) {
      position++;
      if (position < length() && (charAt(position) == '+' || charAt(position) == '-')) {
        position++;
      }
      readDigits();
    }
    if (start == position) {
      throw error("Expected number");
    }
    return slice(start, position);
  }

  public final int readInt() {
    skipWhitespace();
    int start = position;
    int result = 0;
    int limit = -Integer.MAX_VALUE;
    boolean negative = false;
    if (position < length() && charAt(position) == '-') {
      negative = true;
      limit = Integer.MIN_VALUE;
      position++;
    }
    if (position >= length()) {
      throw error("Expected digit");
    }
    char ch = charAt(position);
    if (ch == '0') {
      position++;
      rejectLeadingDigit();
      rejectFractionOrExponent();
      return 0;
    }
    if (ch < '1' || ch > '9') {
      throw error("Expected digit");
    }
    int multmin = limit / 10;
    while (position < length()) {
      ch = charAt(position);
      if (ch < '0' || ch > '9') {
        break;
      }
      int digit = ch - '0';
      if (result < multmin) {
        throw error("Integer overflow");
      }
      result *= 10;
      if (result < limit + digit) {
        throw error("Integer overflow");
      }
      result -= digit;
      position++;
    }
    if (start == position || (negative && start + 1 == position)) {
      throw error("Expected digit");
    }
    rejectFractionOrExponent();
    return negative ? result : -result;
  }

  public final long readLong() {
    skipWhitespace();
    int start = position;
    long result = 0;
    long limit = -Long.MAX_VALUE;
    boolean negative = false;
    if (position < length() && charAt(position) == '-') {
      negative = true;
      limit = Long.MIN_VALUE;
      position++;
    }
    if (position >= length()) {
      throw error("Expected digit");
    }
    char ch = charAt(position);
    if (ch == '0') {
      position++;
      rejectLeadingDigit();
      rejectFractionOrExponent();
      return 0;
    }
    if (ch < '1' || ch > '9') {
      throw error("Expected digit");
    }
    long multmin = limit / 10;
    while (position < length()) {
      ch = charAt(position);
      if (ch < '0' || ch > '9') {
        break;
      }
      int digit = ch - '0';
      if (result < multmin) {
        throw error("Long overflow");
      }
      result *= 10;
      if (result < limit + digit) {
        throw error("Long overflow");
      }
      result -= digit;
      position++;
    }
    if (start == position || (negative && start + 1 == position)) {
      throw error("Expected digit");
    }
    rejectFractionOrExponent();
    return negative ? result : -result;
  }

  public double readDouble() {
    skipWhitespace();
    if (position < length() && charAt(position) == '"') {
      return readNonFiniteDoubleString();
    }
    return Double.parseDouble(readNumberToken());
  }

  public float readFloat() {
    skipWhitespace();
    if (position < length() && charAt(position) == '"') {
      return readNonFiniteFloatString();
    }
    return Float.parseFloat(readNumberToken());
  }

  public BigDecimal readBigDecimal() {
    skipWhitespace();
    return readBigDecimalToken();
  }

  public BigInteger readBigInteger() {
    skipWhitespace();
    int mark = position;
    try {
      return BigInteger.valueOf(readLong());
    } catch (RuntimeException e) {
      position = mark;
      return parseBigInteger(readNumberAsString());
    }
  }

  public char readChar() {
    skipWhitespace();
    int mark = position;
    if (position >= length() || charAt(position++) != '"') {
      throw error("Expected string");
    }
    if (position >= length()) {
      throw error("Unterminated string");
    }
    char ch = charAt(position++);
    if (ch > 0 && ch < 0x80 && ch != '\\' && ch != '"' && ch >= 0x20) {
      if (position < length() && charAt(position++) == '"') {
        return ch;
      }
    }
    position = mark;
    String value = readString();
    if (value.length() != 1) {
      throw new ForyJsonException("Expected one-character JSON string for char");
    }
    return value.charAt(0);
  }

  public UUID readUuid() {
    skipWhitespace();
    int mark = position;
    try {
      return readUuidToken();
    } catch (RuntimeException e) {
      position = mark;
      return UUID.fromString(readString());
    }
  }

  public LocalTime readIsoLocalTime() {
    CharSequence value = tryReadAsciiStringView();
    if (value != null) {
      return parseLocalTimeValue(value);
    }
    return parseLocalTimeValue(readString());
  }

  public LocalDateTime readIsoLocalDateTime() {
    CharSequence value = tryReadAsciiStringView();
    if (value != null) {
      return parseLocalDateTimeValue(value);
    }
    return parseLocalDateTimeValue(readString());
  }

  public Instant readIsoInstant() {
    CharSequence value = tryReadAsciiStringView();
    if (value != null) {
      return parseInstantValue(value);
    }
    return parseInstantValue(readString());
  }

  public Duration readDuration() {
    CharSequence value = tryReadAsciiStringView();
    if (value != null) {
      return parseDurationValue(value);
    }
    return parseDurationValue(readString());
  }

  public ZoneOffset readZoneOffset() {
    int mark = position;
    CharSequence value = tryReadAsciiStringView();
    if (value != null) {
      ZoneOffset offset = tryParseZoneOffset(value);
      if (offset != null) {
        return offset;
      } else {
        position = mark;
      }
    }
    return parseZoneOffsetString(readString());
  }

  public ZonedDateTime readZonedDateTime() {
    CharSequence value = tryReadAsciiStringView();
    if (value != null) {
      return parseZonedDateTimeValue(value);
    }
    return parseZonedDateTimeValue(readString());
  }

  public Year readYear() {
    CharSequence value = tryReadAsciiStringView();
    if (value != null) {
      return parseYearValue(value);
    }
    return parseYearString(readString());
  }

  public YearMonth readYearMonth() {
    CharSequence value = tryReadAsciiStringView();
    if (value != null) {
      return YearMonth.parse(value);
    }
    return parseYearMonthString(readString());
  }

  public MonthDay readMonthDay() {
    CharSequence value = tryReadAsciiStringView();
    if (value != null) {
      return MonthDay.parse(value);
    }
    return parseMonthDayString(readString());
  }

  public Period readPeriod() {
    CharSequence value = tryReadAsciiStringView();
    if (value != null) {
      return Period.parse(value);
    }
    return parsePeriodString(readString());
  }

  public OffsetTime readOffsetTime() {
    CharSequence value = tryReadAsciiStringView();
    if (value != null) {
      return parseOffsetTimeValue(value);
    }
    return parseOffsetTimeValue(readString());
  }

  private BigDecimal readBigDecimalToken() {
    int start = position;
    int inputLength = length();
    if (position >= inputLength) {
      return readBoundedBigDecimal(start);
    }
    char ch = charAt(position);
    if (ch == '-') {
      return readSignedBigDecimalToken(start);
    }
    long unscaled = 0;
    int scale = 0;
    if (ch == '0') {
      position++;
      rejectLeadingDigit();
    } else if (ch >= '1' && ch <= '9') {
      do {
        int digit = ch - '0';
        if (unscaled > (Long.MAX_VALUE - digit) / 10) {
          return readBoundedBigDecimal(start);
        }
        unscaled = unscaled * 10 + digit;
        position++;
        if (position >= inputLength) {
          break;
        }
        ch = charAt(position);
      } while (ch >= '0' && ch <= '9');
    } else {
      return readBoundedBigDecimal(start);
    }
    if (position < inputLength && charAt(position) == '.') {
      position++;
      int fractionStart = position;
      while (position < inputLength) {
        ch = charAt(position);
        if (ch < '0' || ch > '9') {
          break;
        }
        int digit = ch - '0';
        if (unscaled > (Long.MAX_VALUE - digit) / 10) {
          return readBoundedBigDecimal(start);
        }
        unscaled = unscaled * 10 + digit;
        scale++;
        if (scale > MAX_BIG_DECIMAL_SCALE) {
          throwBigDecimalScaleExceeded();
        }
        position++;
      }
      if (position == fractionStart) {
        return readBoundedBigDecimal(start);
      }
    }
    if (position < inputLength) {
      ch = charAt(position);
      if (ch == 'e' || ch == 'E') {
        return readBoundedBigDecimal(start);
      }
    }
    if (scale > MAX_BIG_DECIMAL_SCALE) {
      throwBigDecimalScaleExceeded();
    }
    return BigDecimal.valueOf(unscaled, scale);
  }

  private BigDecimal readSignedBigDecimalToken(int start) {
    position = start + 1;
    int inputLength = length();
    if (position >= inputLength) {
      return readBoundedBigDecimal(start);
    }
    char ch = charAt(position);
    long unscaled = 0;
    int scale = 0;
    if (ch == '0') {
      position++;
      rejectLeadingDigit();
    } else if (ch >= '1' && ch <= '9') {
      do {
        int digit = ch - '0';
        if (unscaled > (Long.MAX_VALUE - digit) / 10) {
          return readBoundedBigDecimal(start);
        }
        unscaled = unscaled * 10 + digit;
        position++;
        if (position >= inputLength) {
          break;
        }
        ch = charAt(position);
      } while (ch >= '0' && ch <= '9');
    } else {
      return readBoundedBigDecimal(start);
    }
    if (position < inputLength && charAt(position) == '.') {
      position++;
      int fractionStart = position;
      while (position < inputLength) {
        ch = charAt(position);
        if (ch < '0' || ch > '9') {
          break;
        }
        int digit = ch - '0';
        if (unscaled > (Long.MAX_VALUE - digit) / 10) {
          return readBoundedBigDecimal(start);
        }
        unscaled = unscaled * 10 + digit;
        scale++;
        if (scale > MAX_BIG_DECIMAL_SCALE) {
          throwBigDecimalScaleExceeded();
        }
        position++;
      }
      if (position == fractionStart) {
        return readBoundedBigDecimal(start);
      }
    }
    if (position < inputLength) {
      ch = charAt(position);
      if (ch == 'e' || ch == 'E') {
        return readBoundedBigDecimal(start);
      }
    }
    if (scale > MAX_BIG_DECIMAL_SCALE) {
      throwBigDecimalScaleExceeded();
    }
    return BigDecimal.valueOf(-unscaled, scale);
  }

  protected final BigDecimal readBoundedBigDecimal(int start) {
    checkBigNumberToken(start);
    position = start;
    return parseBigDecimal(readNumberAsString());
  }

  private void checkBigNumberToken(int start) {
    int offset = start;
    int inputLength = length();
    int tokenLength = 0;
    if (offset < inputLength && charAt(offset) == '-') {
      offset++;
      tokenLength++;
    }
    if (offset >= inputLength) {
      position = offset;
      throw error("Expected digit");
    }
    char ch = charAt(offset);
    if (ch == '0') {
      offset++;
      tokenLength++;
      if (tokenLength > MAX_BIG_NUMBER_LENGTH) {
        throwBigNumberLengthExceeded(offset);
      }
      if (offset < inputLength) {
        ch = charAt(offset);
        if (ch >= '0' && ch <= '9') {
          position = offset;
          throw error("Leading zero in number");
        }
      }
    } else if (ch >= '1' && ch <= '9') {
      do {
        offset++;
        tokenLength++;
        if (tokenLength > MAX_BIG_NUMBER_LENGTH) {
          throwBigNumberLengthExceeded(offset);
        }
        if (offset >= inputLength) {
          break;
        }
        ch = charAt(offset);
      } while (ch >= '0' && ch <= '9');
    } else {
      position = offset;
      throw error("Expected digit");
    }
    int scale = 0;
    if (offset < inputLength && charAt(offset) == '.') {
      offset++;
      tokenLength++;
      if (tokenLength > MAX_BIG_NUMBER_LENGTH) {
        throwBigNumberLengthExceeded(offset);
      }
      int fractionStart = offset;
      while (offset < inputLength) {
        ch = charAt(offset);
        if (ch < '0' || ch > '9') {
          break;
        }
        offset++;
        tokenLength++;
        scale++;
        if (tokenLength > MAX_BIG_NUMBER_LENGTH) {
          throwBigNumberLengthExceeded(offset);
        }
        if (scale > MAX_BIG_DECIMAL_SCALE) {
          position = offset;
          throwBigDecimalScaleExceeded();
        }
      }
      if (offset == fractionStart) {
        position = offset;
        throw error("Expected digit");
      }
    }
    if (offset < inputLength) {
      ch = charAt(offset);
      if (ch == 'e' || ch == 'E') {
        offset++;
        tokenLength++;
        if (tokenLength > MAX_BIG_NUMBER_LENGTH) {
          throwBigNumberLengthExceeded(offset);
        }
        if (offset < inputLength) {
          ch = charAt(offset);
          if (ch == '+' || ch == '-') {
            offset++;
            tokenLength++;
            if (tokenLength > MAX_BIG_NUMBER_LENGTH) {
              throwBigNumberLengthExceeded(offset);
            }
          }
        }
        int exponentStart = offset;
        while (offset < inputLength) {
          ch = charAt(offset);
          if (ch < '0' || ch > '9') {
            break;
          }
          offset++;
          tokenLength++;
          if (tokenLength > MAX_BIG_NUMBER_LENGTH) {
            throwBigNumberLengthExceeded(offset);
          }
        }
        if (offset == exponentStart) {
          position = offset;
          throw error("Expected digit");
        }
      }
    }
  }

  private UUID readUuidToken() {
    int offset = position;
    int start = offset + 1;
    if (offset + 38 > length() || charAt(offset) != '"') {
      throw new IllegalArgumentException();
    }
    if (charAt(start + 8) != '-'
        || charAt(start + 13) != '-'
        || charAt(start + 18) != '-'
        || charAt(start + 23) != '-'
        || charAt(start + 36) != '"') {
      throw new IllegalArgumentException();
    }
    long msb = parseHex(start, 8);
    msb = (msb << 16) | parseHex(start + 9, 4);
    msb = (msb << 16) | parseHex(start + 14, 4);
    long lsb = parseHex(start + 19, 4);
    lsb = (lsb << 48) | parseHex(start + 24, 12);
    position = start + 37;
    return new UUID(msb, lsb);
  }

  private long parseHex(int offset, int length) {
    long value = 0;
    for (int i = 0; i < length; i++) {
      value = (value << 4) | uuidHexValue(charAt(offset + i));
    }
    return value;
  }

  private static int uuidHexValue(char ch) {
    if (ch >= '0' && ch <= '9') {
      return ch - '0';
    }
    char lower = (char) (ch | 0x20);
    if (lower >= 'a' && lower <= 'f') {
      return lower - 'a' + 10;
    }
    throw new IllegalArgumentException();
  }

  private AsciiStringView tryReadAsciiStringView() {
    skipWhitespace();
    int mark = position;
    if (position >= length() || charAt(position++) != '"') {
      throw error("Expected string");
    }
    int start = position;
    while (position < length()) {
      char ch = charAt(position++);
      if (ch == '"') {
        asciiStringView.reset(start, position - 1);
        return asciiStringView;
      }
      if (ch == '\\' || ch < 0x20 || ch >= 0x80) {
        position = mark;
        return null;
      }
    }
    throw error("Unterminated string");
  }

  private ZoneOffset tryParseZoneOffset(CharSequence value) {
    int length = value.length();
    if (length == 1 && value.charAt(0) == 'Z') {
      return ZoneOffset.UTC;
    }
    if (length != 6 && length != 9) {
      return null;
    }
    char sign = value.charAt(0);
    if (sign != '+' && sign != '-') {
      return null;
    }
    if (value.charAt(3) != ':' || (length == 9 && value.charAt(6) != ':')) {
      return null;
    }
    int hour = parse2(value, 1);
    int minute = parse2(value, 4);
    int second = length == 9 ? parse2(value, 7) : 0;
    int total = hour * 3600 + minute * 60 + second;
    return ZoneOffset.ofTotalSeconds(sign == '-' ? -total : total);
  }

  protected final LocalDate readIsoLocalDateFallback(String value) {
    try {
      int length = value.length();
      if (length >= 10
          && (length == 10 || value.charAt(10) == 'T')
          && value.charAt(4) == '-'
          && value.charAt(7) == '-') {
        try {
          return LocalDate.of(parse4(value, 0), parse2(value, 5), parse2(value, 8));
        } catch (RuntimeException e) {
          if (length > 10 && value.charAt(10) == 'T') {
            return LocalDate.parse(value.substring(0, 10));
          }
          return LocalDate.parse(value);
        }
      }
      return LocalDate.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.LocalDate", e);
    }
  }

  protected final OffsetDateTime readIsoOffsetDateTimeFallback(String value) {
    try {
      return OffsetDateTime.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.OffsetDateTime", e);
    }
  }

  private LocalTime parseLocalTimeValue(CharSequence value) {
    try {
      return LocalTime.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.LocalTime", e);
    }
  }

  private LocalDateTime parseLocalDateTimeValue(CharSequence value) {
    try {
      return LocalDateTime.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.LocalDateTime", e);
    }
  }

  private Instant parseInstantValue(CharSequence value) {
    try {
      return Instant.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.Instant", e);
    }
  }

  private Duration parseDurationValue(CharSequence value) {
    try {
      return Duration.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.Duration", e);
    }
  }

  private ZoneOffset parseZoneOffsetString(String value) {
    try {
      return ZoneOffset.of(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.ZoneOffset", e);
    }
  }

  private ZonedDateTime parseZonedDateTimeValue(CharSequence value) {
    try {
      return ZonedDateTime.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.ZonedDateTime", e);
    }
  }

  private Year parseYearString(String value) {
    try {
      return parseYearValue(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.Year", e);
    }
  }

  private YearMonth parseYearMonthString(String value) {
    try {
      return YearMonth.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.YearMonth", e);
    }
  }

  private MonthDay parseMonthDayString(String value) {
    try {
      return MonthDay.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.MonthDay", e);
    }
  }

  private Period parsePeriodString(String value) {
    try {
      return Period.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.Period", e);
    }
  }

  private OffsetTime parseOffsetTimeValue(CharSequence value) {
    try {
      return OffsetTime.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.OffsetTime", e);
    }
  }

  private ForyJsonException invalidStringValue(String type, RuntimeException e) {
    return new ForyJsonException(
        "Invalid " + type + " JSON string at JSON position " + position, e);
  }

  private static int parse4(CharSequence value, int index) {
    return parse2(value, index) * 100 + parse2(value, index + 2);
  }

  private static int parse2(CharSequence value, int index) {
    int high = value.charAt(index) - '0';
    int low = value.charAt(index + 1) - '0';
    if (high < 0 || high > 9 || low < 0 || low > 9) {
      throw new IllegalArgumentException();
    }
    return high * 10 + low;
  }

  public static Year parseYearValue(CharSequence value) {
    // Fory writes Year values as unpadded integers, so parse that shape before
    // delegating to JDK parsing whose accepted forms differ across JDK versions.
    int year = tryParseYearInt(value);
    if (year != Integer.MIN_VALUE) {
      return Year.of(year);
    }
    return Year.parse(value);
  }

  private static int tryParseYearInt(CharSequence value) {
    int length = value.length();
    if (length == 0) {
      return Integer.MIN_VALUE;
    }
    int index = 0;
    boolean negative = false;
    char first = value.charAt(0);
    if (first == '-' || first == '+') {
      negative = first == '-';
      index = 1;
      if (index == length) {
        return Integer.MIN_VALUE;
      }
    }
    long year = 0;
    for (; index < length; index++) {
      int digit = value.charAt(index) - '0';
      if (digit < 0 || digit > 9) {
        return Integer.MIN_VALUE;
      }
      year = year * 10 + digit;
      if (year > 999999999L) {
        return Integer.MIN_VALUE;
      }
    }
    return negative ? (int) -year : (int) year;
  }

  private Number materializeNumber(String number) {
    if (number.indexOf('.') >= 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0) {
      return Double.parseDouble(number);
    }
    try {
      return Long.parseLong(number);
    } catch (NumberFormatException e) {
      return parseBigInteger(number);
    }
  }

  final BigInteger parseBigInteger(String number) {
    if (number.length() > MAX_BIG_NUMBER_LENGTH) {
      throwBigNumberLengthExceeded(position);
    }
    return new BigInteger(number);
  }

  final BigDecimal parseBigDecimal(String number) {
    if (number.length() > MAX_BIG_NUMBER_LENGTH) {
      throwBigNumberLengthExceeded(position);
    }
    BigDecimal value = new BigDecimal(number);
    int scale = value.scale();
    if (scale > MAX_BIG_DECIMAL_SCALE || scale < -MAX_BIG_DECIMAL_SCALE) {
      throwBigDecimalScaleExceeded();
    }
    return value;
  }

  final void throwBigDecimalScaleExceeded() {
    throw error("JSON big decimal scale " + MAX_BIG_DECIMAL_SCALE + " exceeded");
  }

  private void throwBigNumberLengthExceeded(int offset) {
    position = offset;
    throw error("JSON big number length " + MAX_BIG_NUMBER_LENGTH + " exceeded");
  }

  protected static boolean canUseFastDouble(long unscaled, int scale) {
    return scale <= DOUBLE_FAST_MAX_SCALE && unscaled <= DOUBLE_FAST_MAX_UNSCALED;
  }

  protected static double fastDoubleValue(long unscaled, int scale) {
    return (double) unscaled / DOUBLE_POWERS_OF_TEN[scale];
  }

  // Primitive readers reach this path after JSON grammar and long-overflow checks; keep compact
  // plain decimals off readNumberAsString() and BigDecimal construction.
  protected static boolean canUseCompactDouble(int scale) {
    return scale <= COMPACT_DECIMAL_MAX_SCALE;
  }

  protected static double compactDoubleValue(boolean negative, long unscaled, int scale) {
    long divisor = LONG_POWERS_OF_TEN[scale];
    int exponent = floorLog2Quotient(unscaled, divisor);
    long significand = roundedSignificand(unscaled, divisor, exponent, DOUBLE_FRACTION_BITS);
    if (significand == (1L << (DOUBLE_FRACTION_BITS + 1))) {
      exponent++;
      significand >>>= 1;
    }
    long bits = ((long) (exponent + 1023) << DOUBLE_FRACTION_BITS);
    bits |= significand & DOUBLE_FRACTION_MASK;
    if (negative) {
      bits |= DOUBLE_SIGN_BIT;
    }
    return Double.longBitsToDouble(bits);
  }

  protected static boolean canUseFastFloat(long unscaled, int scale) {
    return scale <= FLOAT_FAST_MAX_SCALE && unscaled <= FLOAT_FAST_MAX_UNSCALED;
  }

  protected static float fastFloatValue(long unscaled, int scale) {
    return (float) unscaled / FLOAT_POWERS_OF_TEN[scale];
  }

  protected static boolean canUseCompactFloat(int scale) {
    return scale <= COMPACT_DECIMAL_MAX_SCALE;
  }

  protected static float compactFloatValue(boolean negative, long unscaled, int scale) {
    long divisor = LONG_POWERS_OF_TEN[scale];
    int exponent = floorLog2Quotient(unscaled, divisor);
    long significand = roundedSignificand(unscaled, divisor, exponent, FLOAT_FRACTION_BITS);
    if (significand == (1L << (FLOAT_FRACTION_BITS + 1))) {
      exponent++;
      significand >>>= 1;
    }
    int bits = (exponent + 127) << FLOAT_FRACTION_BITS;
    bits |= (int) significand & FLOAT_FRACTION_MASK;
    if (negative) {
      bits |= FLOAT_SIGN_BIT;
    }
    return Float.intBitsToFloat(bits);
  }

  private static long roundedSignificand(
      long unscaled, long divisor, int exponent, int fractionBits) {
    int binaryShift = fractionBits - exponent;
    long numHigh;
    long numLow;
    long denHigh;
    long denLow;
    if (binaryShift >= 0) {
      numHigh = shiftedHigh(unscaled, binaryShift);
      numLow = shiftedLow(unscaled, binaryShift);
      denHigh = 0;
      denLow = divisor;
    } else {
      numHigh = 0;
      numLow = unscaled;
      int denominatorShift = -binaryShift;
      denHigh = shiftedHigh(divisor, denominatorShift);
      denLow = shiftedLow(divisor, denominatorShift);
    }

    int shift = bitLength(numHigh, numLow) - bitLength(denHigh, denLow);
    long shiftedDenHigh = shiftLeftHigh(denHigh, denLow, shift);
    long shiftedDenLow = shiftLeftLow(denLow, shift);
    long quotient = 0;
    for (int bit = shift; bit >= 0; bit--) {
      if (compareUnsigned(numHigh, numLow, shiftedDenHigh, shiftedDenLow) >= 0) {
        long newLow = numLow - shiftedDenLow;
        numHigh -= shiftedDenHigh + (Long.compareUnsigned(numLow, shiftedDenLow) < 0 ? 1 : 0);
        numLow = newLow;
        quotient |= 1L << bit;
      }
      long nextLow = (shiftedDenLow >>> 1) | (shiftedDenHigh << 63);
      shiftedDenHigh >>>= 1;
      shiftedDenLow = nextLow;
    }

    long twiceHigh = (numHigh << 1) | (numLow >>> 63);
    long twiceLow = numLow << 1;
    int cmp = compareUnsigned(twiceHigh, twiceLow, denHigh, denLow);
    if (cmp > 0 || (cmp == 0 && (quotient & 1) != 0)) {
      quotient++;
    }
    return quotient;
  }

  private static int floorLog2Quotient(long unscaled, long divisor) {
    int exponent = bitLength(0, unscaled) - bitLength(0, divisor);
    if (compareWithScaledDivisor(unscaled, divisor, exponent) < 0) {
      exponent--;
    }
    return exponent;
  }

  private static int compareWithScaledDivisor(long unscaled, long divisor, int exponent) {
    if (exponent >= 0) {
      return compareUnsigned(
          0, unscaled, shiftedHigh(divisor, exponent), shiftedLow(divisor, exponent));
    }
    int shift = -exponent;
    return compareUnsigned(shiftedHigh(unscaled, shift), shiftedLow(unscaled, shift), 0, divisor);
  }

  private static int bitLength(long high, long low) {
    if (high != 0) {
      return Long.SIZE + Long.SIZE - Long.numberOfLeadingZeros(high);
    }
    return Long.SIZE - Long.numberOfLeadingZeros(low);
  }

  private static long shiftedHigh(long value, int shift) {
    if (shift == 0) {
      return 0;
    }
    if (shift < Long.SIZE) {
      return value >>> (Long.SIZE - shift);
    }
    return value << (shift - Long.SIZE);
  }

  private static long shiftedLow(long value, int shift) {
    if (shift == 0) {
      return value;
    }
    if (shift < Long.SIZE) {
      return value << shift;
    }
    return 0;
  }

  private static long shiftLeftHigh(long high, long low, int shift) {
    if (shift == 0) {
      return high;
    }
    if (shift < Long.SIZE) {
      return (high << shift) | (low >>> (Long.SIZE - shift));
    }
    return low << (shift - Long.SIZE);
  }

  private static long shiftLeftLow(long low, int shift) {
    if (shift == 0) {
      return low;
    }
    if (shift < Long.SIZE) {
      return low << shift;
    }
    return 0;
  }

  private static int compareUnsigned(long high1, long low1, long high2, long low2) {
    int highCmp = Long.compareUnsigned(high1, high2);
    if (highCmp != 0) {
      return highCmp;
    }
    return Long.compareUnsigned(low1, low2);
  }

  protected final double readNonFiniteDoubleString() {
    String value = readString();
    switch (value) {
      case "NaN":
        return Double.NaN;
      case "Infinity":
        return Double.POSITIVE_INFINITY;
      case "-Infinity":
        return Double.NEGATIVE_INFINITY;
      default:
        // Numeric strings are intentionally not coerced; only writer-emitted non-finite tokens
        // are accepted here.
        throw error("Expected finite JSON number or non-finite double string");
    }
  }

  protected final float readNonFiniteFloatString() {
    String value = readString();
    switch (value) {
      case "NaN":
        return Float.NaN;
      case "Infinity":
        return Float.POSITIVE_INFINITY;
      case "-Infinity":
        return Float.NEGATIVE_INFINITY;
      default:
        // Numeric strings are intentionally not coerced; only writer-emitted non-finite tokens
        // are accepted here.
        throw error("Expected finite JSON number or non-finite float string");
    }
  }

  public int readFieldNameInt() {
    try {
      return Integer.parseInt(readString());
    } catch (NumberFormatException e) {
      throw new ForyJsonException("Invalid integer field name at JSON position " + position, e);
    }
  }

  public long readFieldNameLong() {
    try {
      return Long.parseLong(readString());
    } catch (NumberFormatException e) {
      throw new ForyJsonException("Invalid long field name at JSON position " + position, e);
    }
  }

  public JsonFieldInfo readField(JsonFieldTable table) {
    return table.get(readFieldNameHash());
  }

  public int readFieldIndex(JsonFieldTable table) {
    return table.index(readFieldNameHash());
  }

  public int readFieldIndex(JsonFieldTable table, long expectedHash, int expectedIndex) {
    long hash = readFieldNameHash();
    return hash == expectedHash ? expectedIndex : table.index(hash);
  }

  public long readFieldNameHash() {
    return readQuotedStringHash();
  }

  public long readStringHash() {
    return readQuotedStringHash();
  }

  private long readQuotedStringHash() {
    skipWhitespace();
    if (position >= length() || charAt(position++) != '"') {
      throw error("Expected string");
    }
    long hash = JsonFieldNameHash.MAGIC_HASH_CODE;
    long value = 0;
    int nameLength = 0;
    boolean latin1 = true;
    while (position < length()) {
      char ch = charAt(position++);
      if (ch == '"') {
        return JsonFieldNameHash.finish(hash, value, nameLength, latin1);
      }
      if (ch == '\\') {
        ch = readEscapedFieldNameChar();
        if (Character.isHighSurrogate(ch)) {
          if (latin1) {
            hash = JsonFieldNameHash.hashPacked(value, nameLength);
            latin1 = false;
          }
          hash = JsonFieldNameHash.update(hash, ch);
          nameLength++;
          if (position + 2 > length() || charAt(position) != '\\' || charAt(position + 1) != 'u') {
            throw error("Unpaired high surrogate escape");
          }
          position += 2;
          char low = readUnicodeEscape();
          if (!Character.isLowSurrogate(low)) {
            throw error("Unpaired high surrogate escape");
          }
          hash = JsonFieldNameHash.update(hash, low);
          nameLength++;
        } else if (Character.isLowSurrogate(ch)) {
          throw error("Unpaired low surrogate escape");
        } else {
          if (latin1) {
            if (ch <= 0xFF && ch != 0 && nameLength < Long.BYTES) {
              value = JsonFieldNameHash.value(value, nameLength, ch);
              nameLength++;
              continue;
            }
            hash = JsonFieldNameHash.hashPacked(value, nameLength);
            latin1 = false;
          }
          hash = JsonFieldNameHash.update(hash, ch);
          nameLength++;
        }
        continue;
      }
      if (ch < 0x20) {
        throw error("Control character in string");
      }
      if (Character.isHighSurrogate(ch)) {
        if (position >= length() || !Character.isLowSurrogate(charAt(position))) {
          throw error("Unpaired high surrogate in string");
        }
        if (latin1) {
          hash = JsonFieldNameHash.hashPacked(value, nameLength);
          latin1 = false;
        }
        hash = JsonFieldNameHash.update(hash, ch);
        hash = JsonFieldNameHash.update(hash, charAt(position++));
        nameLength += 2;
        continue;
      }
      if (Character.isLowSurrogate(ch)) {
        throw error("Unpaired low surrogate in string");
      }
      if (latin1) {
        if (ch <= 0xFF && ch != 0 && nameLength < Long.BYTES) {
          value = JsonFieldNameHash.value(value, nameLength, ch);
          nameLength++;
          continue;
        }
        hash = JsonFieldNameHash.hashPacked(value, nameLength);
        latin1 = false;
      }
      hash = JsonFieldNameHash.update(hash, ch);
      nameLength++;
    }
    throw error("Unterminated string");
  }

  public final void skipValue() {
    skipWhitespace();
    if (position >= length()) {
      throw error("Expected value");
    }
    char ch = charAt(position);
    if (ch == '"') {
      readString();
    } else if (ch == '{') {
      skipObject();
    } else if (ch == '[') {
      skipArray();
    } else if (startsWith("true")) {
      position += 4;
    } else if (startsWith("false")) {
      position += 5;
    } else if (startsWith("null")) {
      position += 4;
    } else {
      readNumberAsString();
    }
  }

  public final void finish() {
    skipWhitespace();
    if (position != length()) {
      throw error("Trailing content");
    }
  }

  protected final ForyJsonException error(String message) {
    return new ForyJsonException(message + " at JSON position " + position);
  }

  private void skipObject() {
    enterDepth();
    expect('{');
    if (consume('}')) {
      exitDepth();
      return;
    }
    do {
      skipWhitespace();
      readString();
      expect(':');
      skipValue();
    } while (consume(','));
    expect('}');
    exitDepth();
  }

  private void skipArray() {
    enterDepth();
    expect('[');
    if (consume(']')) {
      exitDepth();
      return;
    }
    do {
      skipValue();
    } while (consume(','));
    expect(']');
    exitDepth();
  }

  private boolean startsWith(String value) {
    int end = position + value.length();
    if (end > length()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (charAt(position + i) != value.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  private void readIntegerDigits() {
    if (position >= length()) {
      throw error("Expected digit");
    }
    char ch = charAt(position);
    if (ch == '0') {
      position++;
      if (position < length()) {
        ch = charAt(position);
        if (ch >= '0' && ch <= '9') {
          throw error("Leading zero in number");
        }
      }
      return;
    }
    if (ch < '1' || ch > '9') {
      throw error("Expected digit");
    }
    while (position < length()) {
      ch = charAt(position);
      if (ch >= '0' && ch <= '9') {
        position++;
      } else {
        break;
      }
    }
  }

  private void readDigits() {
    int start = position;
    while (position < length()) {
      char ch = charAt(position);
      if (ch >= '0' && ch <= '9') {
        position++;
      } else {
        break;
      }
    }
    if (start == position) {
      throw error("Expected digit");
    }
  }

  private void rejectLeadingDigit() {
    if (position < length()) {
      char ch = charAt(position);
      if (ch >= '0' && ch <= '9') {
        throw error("Leading zero in number");
      }
    }
  }

  private void rejectFractionOrExponent() {
    if (position < length()) {
      char ch = charAt(position);
      if (ch == '.' || ch == 'e' || ch == 'E') {
        throw error("Expected integer");
      }
    }
  }

  protected final char readEscapedFieldNameChar() {
    if (position >= length()) {
      throw error("Unterminated escape");
    }
    char escaped = charAt(position++);
    switch (escaped) {
      case '"':
      case '\\':
      case '/':
        return escaped;
      case 'b':
        return '\b';
      case 'f':
        return '\f';
      case 'n':
        return '\n';
      case 'r':
        return '\r';
      case 't':
        return '\t';
      case 'u':
        return readUnicodeEscape();
      default:
        throw error("Invalid escape");
    }
  }

  protected final char readUnicodeEscape() {
    if (position + 4 > length()) {
      throw error("Short unicode escape");
    }
    int value = 0;
    for (int i = 0; i < 4; i++) {
      value = (value << 4) | hexValue(charAt(position++));
    }
    return (char) value;
  }

  private int hexValue(char ch) {
    if (ch >= '0' && ch <= '9') {
      return ch - '0';
    } else if (ch >= 'a' && ch <= 'f') {
      return ch - 'a' + 10;
    } else if (ch >= 'A' && ch <= 'F') {
      return ch - 'A' + 10;
    }
    throw error("Invalid hex digit");
  }

  protected abstract String slice(int start, int end);

  private static final class AsciiStringView implements CharSequence {
    private final JsonReader reader;
    private int start;
    private int end;

    AsciiStringView(JsonReader reader) {
      this.reader = reader;
    }

    void reset(int start, int end) {
      this.start = start;
      this.end = end;
    }

    @Override
    public int length() {
      return end - start;
    }

    @Override
    public char charAt(int index) {
      if (index < 0 || start + index >= end) {
        throw new IndexOutOfBoundsException();
      }
      return reader.charAt(start + index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return toString().subSequence(start, end);
    }

    @Override
    public String toString() {
      return reader.slice(start, end);
    }
  }
}
