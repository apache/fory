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
import java.util.Objects;
import java.util.UUID;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.JsonConfig;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldNameHash;
import org.apache.fory.json.meta.JsonFieldTable;
import org.apache.fory.json.meta.JsonSubtypeScanInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;

/**
 * Representation-neutral JSON cursor and common scalar parsing owner.
 *
 * <p>The base class retains the resolver used by dynamic codecs, the current code-unit position,
 * configured and current container depth, a reusable ASCII token view, and a fixed workspace for
 * exact floating-point boundary correction. Concrete readers own input storage, string decoding,
 * field-name probes, and direct primitive numeric fast paths for their representation.
 *
 * <p>Primitive {@code int}, {@code long}, {@code float}, and {@code double} parsing does not
 * materialize a String or arbitrary-precision number. Precision-sensitive floating input uses the
 * reusable boundary workspace. The internal length and scale limits apply only when constructing
 * {@link BigInteger} or {@link BigDecimal}; raw number text, primitive scans, and skipped values do
 * not inherit that resource policy.
 *
 * <p>Readers are mutable and confined to one borrowed {@code ForyJson} state. Concrete reset
 * methods borrow an input and reset the cursor and depth; {@code clear()} detaches that input
 * before the state returns to the pool. A failed nested parse may leave depth nonzero because the
 * root cleanup resets it; nested codec paths intentionally avoid cleanup-only {@code try/finally}
 * regions.
 */
public abstract class JsonReader {
  private static final int MAX_BIG_NUMBER_LENGTH = 10_000;
  private static final byte[] EMPTY_BYTES = new byte[0];
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
  private static final long DOUBLE_INFINITY_BITS = 0x7ff0_0000_0000_0000L;
  private static final long DOUBLE_MAX_FINITE_BITS = 0x7fef_ffff_ffff_ffffL;
  private static final int DECIMAL_BOUNDARY_DIGITS = 768;
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
  private static final int FLOAT_EXPONENT_MASK = 0x7f80_0000;
  private static final int FLOAT_INFINITY_BITS = 0x7f80_0000;
  private static final int FLOAT_MAX_FINITE_BITS = 0x7f7f_ffff;
  // Past twice the maximum token digit count, an exponent cannot be canceled back into the
  // finite float range by integer, fractional, or truncated digits.
  private static final long TOKEN_EXPONENT_LIMIT = 2L * Integer.MAX_VALUE + 1_000L;
  private static final float[] FLOAT_POWERS_OF_TEN = {
    1.0f, 10.0f, 100.0f, 1_000.0f, 10_000.0f, 100_000.0f, 1_000_000.0f, 10_000_000.0f
  };

  private final JsonTypeResolver typeResolver;
  protected int position;
  private final int maxDepth;
  private int depth;

  /**
   * Scans one complete object for a closed-subtype string discriminator without consuming input.
   *
   * <p>The concrete representation owner validates the complete object before returning. Position
   * and depth are restored on success and on every failure, so subtype construction starts from the
   * original cursor and malformed input cannot publish an object before its trailing syntax is
   * checked.
   */
  public final int scanObjectStringField(JsonSubtypeScanInfo info) {
    int savedPosition = position;
    int savedDepth = depth;
    try {
      int scanDepth = 1;
      checkScanDepth(savedDepth, scanDepth, savedPosition);
      int cursor = scanWhitespace(savedPosition);
      if (cursor >= length() || charAt(cursor) != '{') {
        throw errorAt("Expected '{'", cursor);
      }
      cursor = scanWhitespace(cursor + 1);
      int found = -1;
      if (cursor < length() && charAt(cursor) == '}') {
        throw errorAt("Missing JSON subtype discriminator", cursor);
      }
      while (true) {
        int fieldStart = cursor;
        int fieldEnd = scanStringEnd(fieldStart);
        long fieldHash = scanStringHash(fieldStart, fieldEnd);
        cursor = scanWhitespace(fieldEnd);
        if (cursor >= length() || charAt(cursor) != ':') {
          throw errorAt("Expected ':'", cursor);
        }
        cursor = scanWhitespace(cursor + 1);
        if (fieldHash == info.propertyHash()
            && matchesScannedString(fieldStart, fieldEnd, info.property())) {
          if (found >= 0) {
            throw errorAt("Duplicate JSON subtype discriminator", fieldStart);
          }
          if (cursor >= length() || charAt(cursor) != '"') {
            throw errorAt("JSON subtype discriminator must be a string", cursor);
          }
          int valueStart = cursor;
          int valueEnd = scanStringEnd(valueStart);
          int candidate = info.nameIndex(scanStringHash(valueStart, valueEnd));
          if (candidate < 0 || !matchesScannedString(valueStart, valueEnd, info.name(candidate))) {
            throw errorAt("Unknown JSON subtype discriminator", valueStart);
          }
          found = candidate;
          cursor = valueEnd;
        } else {
          cursor = scanValue(cursor, savedDepth, scanDepth);
        }
        cursor = scanWhitespace(cursor);
        if (cursor >= length()) {
          throw errorAt("Expected ',' or '}'", cursor);
        }
        char separator = charAt(cursor++);
        if (separator == '}') {
          break;
        }
        if (separator != ',') {
          throw errorAt("Expected ',' or '}'", cursor - 1);
        }
        cursor = scanWhitespace(cursor);
        if (cursor < length() && charAt(cursor) == '}') {
          throw errorAt("Expected object field", cursor);
        }
      }
      if (found < 0) {
        throw errorAt("Missing JSON subtype discriminator", cursor - 1);
      }
      return found;
    } finally {
      // Scan helpers use only offsets, but restoring here keeps this entry's invariant explicit and
      // prevents a future representation-specific optimization from leaking reader state.
      position = savedPosition;
      depth = savedDepth;
    }
  }

  protected abstract int scanStringEnd(int start);

  protected abstract long scanStringHash(int start, int end);

  protected abstract boolean matchesScannedString(int start, int end, String expected);

  /** Reads one subtype name from a fixed validated table without materializing a String. */
  public abstract int readSubtypeName(JsonSubtypeScanInfo info);

  private final AsciiStringView asciiStringView = new AsciiStringView(this);
  // Primitive floating fallback reuses this exact-boundary workspace. Reader construction is the
  // cold owner so the first precision-sensitive scalar cannot allocate on the numeric hot path.
  private final byte[] decimalBoundaryDigits = new byte[DECIMAL_BOUNDARY_DIGITS];

  protected JsonReader(JsonConfig config, JsonTypeResolver typeResolver) {
    this.typeResolver = Objects.requireNonNull(typeResolver, "typeResolver");
    maxDepth = config.maxDepth();
  }

  /**
   * Returns the resolver owned by this reader for custom codecs that resolve dynamic child types.
   */
  public final JsonTypeResolver typeResolver() {
    return typeResolver;
  }

  protected abstract int length();

  protected abstract char charAt(int index);

  public abstract String readString();

  /**
   * Reads a JSON object field name that the caller retains as a String key.
   *
   * <p>The returned String has normal value semantics. Implementations may reuse its reference for
   * common names, but callers must not depend on reference identity.
   */
  public abstract String readFieldName();

  protected static long fieldNameHash(int length, long word0, long word1) {
    if (length == 0) {
      return JsonFieldNameHash.MAGIC_HASH_CODE;
    }
    if (length <= Long.BYTES) {
      return word0;
    }
    long hash = JsonFieldNameHash.hashPacked(word0, Long.BYTES);
    for (int i = Long.BYTES; i < length; i++) {
      hash = JsonFieldNameHash.update(hash, (char) ((word1 >>> ((i - Long.BYTES) << 3)) & 0xff));
    }
    return hash;
  }

  protected static long fieldNameWord(long word, int length) {
    return length == Long.BYTES ? word : word & ((1L << (length << 3)) - 1);
  }

  @Internal
  public final int position() {
    return position;
  }

  @Internal
  public final String materializeFieldName(int start) {
    int current = position;
    position = start;
    String name = readFieldName();
    position = current;
    return name;
  }

  public abstract double readDouble();

  public abstract float readFloat();

  public abstract BigDecimal readBigDecimal();

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
    // Failed root reads reset depth when the reader is cleared. Nested codecs decrement only after
    // a successful value; do not add try/finally solely to restore depth on parse failure.
    depth--;
  }

  public String readNullableString() {
    return tryReadNull() ? null : readString();
  }

  /** Reads a nullable Base64 JSON string directly into its decoded bytes. */
  public final byte[] readBase64() {
    if (tryReadNull()) {
      return null;
    }
    if (position >= length() || charAt(position++) != '"') {
      throw error("Expected Base64 JSON string");
    }
    int bodyStart = position;
    // Validate and consume the complete encoded text before allocating, so untrusted input can
    // only request decoded storage proportional to code units already proven readable.
    long shape = scanBase64Shape();
    int encodedLength = (int) (shape >>> 2);
    if (encodedLength == 0) {
      return EMPTY_BYTES;
    }
    int end = position;
    int padding = (int) (shape & 3);
    byte[] decoded = new byte[(encodedLength >>> 2) * 3 - padding];
    position = bodyStart;
    decodeBase64(decoded, encodedLength);
    position = end;
    return decoded;
  }

  private long scanBase64Shape() {
    int encodedLength = 0;
    int padding = 0;
    while (position < length()) {
      char ch = charAt(position++);
      if (ch == '"') {
        if ((encodedLength & 3) != 0) {
          throw error("Invalid Base64 JSON string length");
        }
        return ((long) encodedLength << 2) | padding;
      }
      if (ch == '\\') {
        ch = readEscapedFieldNameChar();
      } else if (ch < 0x20) {
        throw error("Unescaped control character in Base64 JSON string");
      }
      if (ch == '=') {
        if (++padding > 2) {
          throw error("Invalid Base64 JSON string padding");
        }
      } else {
        if (padding != 0 || decodeBase64Digit(ch) < 0) {
          throw error("Invalid Base64 JSON string");
        }
      }
      encodedLength++;
    }
    throw error("Unterminated Base64 JSON string");
  }

  private void decodeBase64(byte[] decoded, int encodedLength) {
    int output = 0;
    for (int index = 0; index < encodedLength; index += 4) {
      int bits =
          (decodeBase64Digit(readBase64Char()) << 18) | (decodeBase64Digit(readBase64Char()) << 12);
      char third = readBase64Char();
      char fourth = readBase64Char();
      if (third != '=') {
        bits |= decodeBase64Digit(third) << 6;
      }
      if (fourth != '=') {
        bits |= decodeBase64Digit(fourth);
      }
      decoded[output++] = (byte) (bits >>> 16);
      if (output < decoded.length) {
        decoded[output++] = (byte) (bits >>> 8);
        if (output < decoded.length) {
          decoded[output++] = (byte) bits;
        }
      }
    }
  }

  private char readBase64Char() {
    char ch = charAt(position++);
    return ch == '\\' ? readEscapedFieldNameChar() : ch;
  }

  private static int decodeBase64Digit(char ch) {
    if (ch >= 'A' && ch <= 'Z') {
      return ch - 'A';
    }
    if (ch >= 'a' && ch <= 'z') {
      return ch - 'a' + 26;
    }
    if (ch >= '0' && ch <= '9') {
      return ch - '0' + 52;
    }
    if (ch == '+') {
      return 62;
    }
    return ch == '/' ? 63 : -1;
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

  /**
   * Tries to read a JSON {@code null} token.
   *
   * <p>Concrete readers override this method with their direct representation-specific token scan.
   */
  public boolean tryReadNullToken() {
    return tryReadNull();
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

  protected final BigDecimal readBigDecimalFallback(int start) {
    position = start;
    return parseBigDecimal(readNumberAsString());
  }

  protected final BigDecimal readBigDecimalExponentValue(
      boolean negative, long unscaled, int scale, int exponentOffset) {
    int offset = exponentOffset + 1;
    boolean negativeExponent = false;
    if (offset < length()) {
      int ch = charAt(offset);
      if (ch == '-' || ch == '+') {
        negativeExponent = ch == '-';
        offset++;
      }
    }
    int exponentStart = offset;
    long exponent = 0;
    // A long run of fractional zeroes may be canceled by a positive exponent without requiring
    // arbitrary-precision coefficient construction, so the saturation point must include scale.
    long exponentLimit = (long) scale + MAX_BIG_DECIMAL_SCALE + 1;
    int inputLength = length();
    while (offset < inputLength) {
      int ch = charAt(offset);
      if (ch < '0' || ch > '9') {
        break;
      }
      if (exponent < exponentLimit) {
        exponent = exponent * 10 + ch - '0';
        if (exponent > exponentLimit) {
          exponent = exponentLimit;
        }
      }
      offset++;
    }
    if (offset == exponentStart) {
      position = offset;
      throw error("Expected exponent digit");
    }
    position = offset;
    long adjustedScale = negativeExponent ? (long) scale + exponent : (long) scale - exponent;
    if (adjustedScale > MAX_BIG_DECIMAL_SCALE || adjustedScale < -MAX_BIG_DECIMAL_SCALE) {
      throwBigDecimalScaleExceeded();
    }
    return BigDecimal.valueOf(negative ? -unscaled : unscaled, (int) adjustedScale);
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
    int length = number.length();
    for (int i = 0; i < length; i++) {
      char ch = number.charAt(i);
      if (ch == '.' || ch == 'e' || ch == 'E') {
        return Double.parseDouble(number);
      }
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
    try {
      return new BigInteger(number);
    } catch (NumberFormatException e) {
      throw new ForyJsonException("Invalid JSON big integer at JSON position " + position, e);
    }
  }

  final BigDecimal parseBigDecimal(String number) {
    if (number.length() > MAX_BIG_NUMBER_LENGTH) {
      throwBigNumberLengthExceeded(position);
    }
    BigDecimal value;
    try {
      value = new BigDecimal(number);
    } catch (NumberFormatException e) {
      throw new ForyJsonException("Invalid JSON big decimal at JSON position " + position, e);
    }
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

  // Primitive readers reach this path after JSON grammar and long-overflow checks. The nonzero
  // numerator is a positive long and scale is 0..18, so candidates are normal finite values and
  // the exact midpoint products fit the local unsigned 128-bit arithmetic.
  protected static boolean canUseCompactDouble(int scale) {
    return scale <= COMPACT_DECIMAL_MAX_SCALE;
  }

  protected static double compactDoubleValue(boolean negative, long unscaled, int scale) {
    if (unscaled == 0) {
      return negative ? -0.0d : 0.0d;
    }
    long divisor = LONG_POWERS_OF_TEN[scale];
    double estimate = (double) unscaled / (double) divisor;
    long bits = correctCompactDouble(unscaled, divisor, Double.doubleToRawLongBits(estimate));
    if (negative) {
      bits |= DOUBLE_SIGN_BIT;
    }
    return Double.longBitsToDouble(bits);
  }

  private static long correctCompactDouble(long unscaled, long divisor, long bits) {
    // Rounded long operands plus one hardware division stay close to the exact decimal rational.
    // Exact midpoint comparisons repair the candidate; the old full division remains the cold
    // fallback so correctness does not depend on that proximity bound.
    for (int i = 0; i < 4; i++) {
      int cmp = compareDecimalToDoubleBoundary(unscaled, divisor, bits - 1, bits);
      if (cmp < 0 || (cmp == 0 && (bits & 1) != 0)) {
        bits--;
        continue;
      }
      cmp = compareDecimalToDoubleBoundary(unscaled, divisor, bits, bits + 1);
      if (cmp > 0 || (cmp == 0 && (bits & 1) != 0)) {
        bits++;
        continue;
      }
      return bits;
    }
    return exactCompactDoubleBits(unscaled, divisor);
  }

  private static long exactCompactDoubleBits(long unscaled, long divisor) {
    int exponent = floorLog2Quotient(unscaled, divisor);
    long significand = roundedSignificand(unscaled, divisor, exponent, DOUBLE_FRACTION_BITS);
    if (significand == (1L << (DOUBLE_FRACTION_BITS + 1))) {
      exponent++;
      significand >>>= 1;
    }
    long bits = ((long) (exponent + 1023) << DOUBLE_FRACTION_BITS);
    bits |= significand & DOUBLE_FRACTION_MASK;
    return bits;
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
    if (unscaled == 0) {
      return negative ? -0.0f : 0.0f;
    }
    long divisor = LONG_POWERS_OF_TEN[scale];
    float estimate = (float) unscaled / (float) divisor;
    int bits = correctCompactFloat(unscaled, divisor, Float.floatToRawIntBits(estimate));
    if (negative) {
      bits |= FLOAT_SIGN_BIT;
    }
    return Float.intBitsToFloat(bits);
  }

  private static int correctCompactFloat(long unscaled, long divisor, int bits) {
    for (int i = 0; i < 4; i++) {
      int cmp = compareDecimalToFloatBoundary(unscaled, divisor, bits - 1, bits);
      if (cmp < 0 || (cmp == 0 && (bits & 1) != 0)) {
        bits--;
        continue;
      }
      cmp = compareDecimalToFloatBoundary(unscaled, divisor, bits, bits + 1);
      if (cmp > 0 || (cmp == 0 && (bits & 1) != 0)) {
        bits++;
        continue;
      }
      return bits;
    }
    return exactCompactFloatBits(unscaled, divisor);
  }

  private static int exactCompactFloatBits(long unscaled, long divisor) {
    int exponent = floorLog2Quotient(unscaled, divisor);
    long significand = roundedSignificand(unscaled, divisor, exponent, FLOAT_FRACTION_BITS);
    if (significand == (1L << (FLOAT_FRACTION_BITS + 1))) {
      exponent++;
      significand >>>= 1;
    }
    int bits = (exponent + 127) << FLOAT_FRACTION_BITS;
    bits |= (int) significand & FLOAT_FRACTION_MASK;
    return bits;
  }

  private static int compareDecimalToDoubleBoundary(
      long unscaled, long divisor, long lowBits, long highBits) {
    long lowMantissa = doubleMantissa(lowBits);
    int lowExponent = doubleBinaryExponent(lowBits);
    long highMantissa = doubleMantissa(highBits);
    int highExponent = doubleBinaryExponent(highBits);
    int exponent = Math.min(lowExponent, highExponent);
    long numerator =
        (lowMantissa << (lowExponent - exponent)) + (highMantissa << (highExponent - exponent));
    return compareDecimalToBinary(unscaled, divisor, numerator, exponent - 1);
  }

  private static int compareDecimalToFloatBoundary(
      long unscaled, long divisor, int lowBits, int highBits) {
    long lowMantissa = floatMantissa(lowBits);
    int lowExponent = floatBinaryExponent(lowBits);
    long highMantissa = floatMantissa(highBits);
    int highExponent = floatBinaryExponent(highBits);
    int exponent = Math.min(lowExponent, highExponent);
    long numerator =
        (lowMantissa << (lowExponent - exponent)) + (highMantissa << (highExponent - exponent));
    return compareDecimalToBinary(unscaled, divisor, numerator, exponent - 1);
  }

  private static int compareDecimalToBinary(
      long unscaled, long divisor, long numerator, int binaryExponent) {
    long productLow = divisor * numerator;
    long productHigh = multiplyHigh(divisor, numerator);
    int productBits = bitLength(productHigh, productLow);
    int unscaledBits = Long.SIZE - Long.numberOfLeadingZeros(unscaled);
    if (binaryExponent < 0) {
      int shift = -binaryExponent;
      int shiftedBits = unscaledBits + shift;
      if (shiftedBits != productBits) {
        return shiftedBits < productBits ? -1 : 1;
      }
      return compareUnsigned(
          shiftedHigh(unscaled, shift), shiftedLow(unscaled, shift), productHigh, productLow);
    }
    int shiftedBits = productBits + binaryExponent;
    if (unscaledBits != shiftedBits) {
      return unscaledBits < shiftedBits ? -1 : 1;
    }
    return compareUnsigned(
        0,
        unscaled,
        shiftLeftHigh(productHigh, productLow, binaryExponent),
        shiftLeftLow(productLow, binaryExponent));
  }

  private static long doubleMantissa(long bits) {
    long fraction = bits & DOUBLE_FRACTION_MASK;
    return ((bits >>> DOUBLE_FRACTION_BITS) & 0x7ffL) == 0
        ? fraction
        : fraction | (1L << DOUBLE_FRACTION_BITS);
  }

  private static int doubleBinaryExponent(long bits) {
    int exponent = (int) ((bits >>> DOUBLE_FRACTION_BITS) & 0x7ffL);
    return exponent == 0 ? -1074 : exponent - 1075;
  }

  private static long multiplyHigh(long x, long y) {
    long xlo = x & 0xffff_ffffL;
    long xhi = x >>> 32;
    long ylo = y & 0xffff_ffffL;
    long yhi = y >>> 32;
    long lowProduct = xlo * ylo;
    long carryProduct = xhi * ylo + (lowProduct >>> 32);
    long middle = (carryProduct & 0xffff_ffffL) + xlo * yhi;
    return xhi * yhi + (carryProduct >>> 32) + (middle >>> 32);
  }

  protected final double readDoubleFallbackValue(int start) {
    position = start;
    if (start < length() && charAt(start) == '"') {
      return readNonFiniteDoubleLiteral();
    }
    return readDoubleNumberFallback(start);
  }

  protected final double readDoubleExponentValue(
      boolean negative, long unscaled, int scale, int start, int exponentOffset) {
    long adjustedScale = readExponentScale(exponentOffset, scale);
    return doubleFromDecimal(negative, unscaled, adjustedScale, false, start, position);
  }

  protected final double readScannedDoubleValue(
      boolean negative, long unscaled, long scale, int start, int end) {
    return doubleFromDecimal(negative, unscaled, scale, false, start, end);
  }

  // Mantissa overflow is the only concrete-reader fallback that rescans a valid token. It keeps
  // the first 18 significant digits for a close estimate and compares the original token with
  // exact IEEE midpoints, so primitive double parsing never materializes a String or big number.
  private double readDoubleNumberFallback(int start) {
    int offset = start;
    int inputLength = length();
    boolean negative = false;
    if (offset < inputLength && charAt(offset) == '-') {
      negative = true;
      offset++;
    }
    if (offset >= inputLength) {
      throw numberError(offset, "Expected double");
    }

    int ch = charAt(offset);
    long significand = 0;
    int storedDigits = 0;
    int truncatedDigits = 0;
    int fractionDigits = 0;
    boolean nonZeroSeen = false;
    boolean sticky = false;
    if (ch == '0') {
      offset++;
      if (offset < inputLength) {
        ch = charAt(offset);
        if (ch >= '0' && ch <= '9') {
          throw numberError(offset, "Leading zero in number");
        }
      }
    } else if (ch >= '1' && ch <= '9') {
      do {
        int digit = ch - '0';
        if (digit != 0 || nonZeroSeen) {
          nonZeroSeen = true;
          if (storedDigits < 18) {
            significand = significand * 10 + digit;
            storedDigits++;
          } else {
            truncatedDigits++;
            sticky |= digit != 0;
          }
        }
        offset++;
        ch = offset < inputLength ? charAt(offset) : -1;
      } while (ch >= '0' && ch <= '9');
    } else {
      throw numberError(offset, "Expected double");
    }

    if (offset < inputLength && charAt(offset) == '.') {
      offset++;
      int fractionStart = offset;
      while (offset < inputLength) {
        ch = charAt(offset);
        if (ch < '0' || ch > '9') {
          break;
        }
        int digit = ch - '0';
        if (digit != 0 || nonZeroSeen) {
          nonZeroSeen = true;
          if (storedDigits < 18) {
            significand = significand * 10 + digit;
            storedDigits++;
          } else {
            truncatedDigits++;
            sticky |= digit != 0;
          }
        }
        fractionDigits++;
        offset++;
      }
      if (offset == fractionStart) {
        throw numberError(offset, "Expected digit");
      }
    }

    long exponent = 0;
    if (offset < inputLength) {
      ch = charAt(offset);
      if (ch == 'e' || ch == 'E') {
        offset++;
        boolean negativeExponent = false;
        if (offset < inputLength) {
          ch = charAt(offset);
          if (ch == '-' || ch == '+') {
            negativeExponent = ch == '-';
            offset++;
          }
        }
        int exponentStart = offset;
        while (offset < inputLength) {
          ch = charAt(offset);
          if (ch < '0' || ch > '9') {
            break;
          }
          if (exponent < TOKEN_EXPONENT_LIMIT) {
            exponent = exponent * 10 + ch - '0';
            if (exponent > TOKEN_EXPONENT_LIMIT) {
              exponent = TOKEN_EXPONENT_LIMIT;
            }
          }
          offset++;
        }
        if (offset == exponentStart) {
          throw numberError(offset, "Expected exponent digit");
        }
        if (negativeExponent) {
          exponent = -exponent;
        }
      }
    }

    position = offset;
    if (!nonZeroSeen) {
      return negative ? -0.0d : 0.0d;
    }
    long scale = (long) fractionDigits - exponent - truncatedDigits;
    return doubleFromDecimal(negative, significand, scale, sticky, start, offset);
  }

  private double doubleFromDecimal(
      boolean negative, long significand, long scale, boolean sticky, int start, int end) {
    if (significand == 0) {
      return negative ? -0.0d : 0.0d;
    }
    if (!sticky) {
      if (scale >= 0 && scale <= COMPACT_DECIMAL_MAX_SCALE) {
        return compactDoubleValue(negative, significand, (int) scale);
      }
      if (scale < 0 && scale >= -COMPACT_DECIMAL_MAX_SCALE) {
        long multiplied = multiplyByPowerOfTen(significand, (int) -scale);
        if (multiplied >= 0) {
          return compactDoubleValue(negative, multiplied, 0);
        }
      }
    }
    double estimate = approximateDouble(significand, scale);
    return correctDoubleToken(negative, estimate, start, end);
  }

  private static double approximateDouble(long significand, long scale) {
    long decimalExponent = -scale;
    if (decimalExponent > 308) {
      return Double.POSITIVE_INFINITY;
    }
    if (decimalExponent < -342) {
      return 0.0d;
    }
    double value = (double) significand;
    if (decimalExponent > 0) {
      return value * Math.pow(10.0d, decimalExponent);
    }
    if (decimalExponent >= -308) {
      return value / Math.pow(10.0d, -decimalExponent);
    }
    value /= 1.0e308d;
    return value / Math.pow(10.0d, -decimalExponent - 308);
  }

  private double correctDoubleToken(boolean negative, double estimate, int start, int end) {
    long bits = Double.doubleToRawLongBits(estimate) & ~DOUBLE_SIGN_BIT;
    byte[] boundary = decimalBoundaryDigits;
    // Eighteen retained digits, one correctly rounded multiply/divide, and Math.pow's one-ULP
    // contract keep the estimate within this local window. The exact search is a correctness-only
    // fallback and is not expected on valid JDK implementations.
    for (int i = 0; i < 4; i++) {
      if (bits == DOUBLE_INFINITY_BITS) {
        int packed = buildDoubleBoundary(DOUBLE_MAX_FINITE_BITS, DOUBLE_INFINITY_BITS, boundary);
        int cmp = compareTokenToBoundary(start, end, boundary, packed >>> 16, packed & 0xffff);
        if (cmp < 0) {
          bits = DOUBLE_MAX_FINITE_BITS;
          continue;
        }
        return signedDouble(negative, bits);
      }
      if (bits == 0) {
        int packed = buildDoubleBoundary(0, 1, boundary);
        int cmp = compareTokenToBoundary(start, end, boundary, packed >>> 16, packed & 0xffff);
        if (cmp > 0) {
          bits = 1;
          continue;
        }
        return signedDouble(negative, bits);
      }

      int packed = buildDoubleBoundary(bits - 1, bits, boundary);
      int cmp = compareTokenToBoundary(start, end, boundary, packed >>> 16, packed & 0xffff);
      if (cmp < 0 || (cmp == 0 && (bits & 1) != 0)) {
        bits--;
        continue;
      }

      packed = buildDoubleBoundary(bits, bits + 1, boundary);
      cmp = compareTokenToBoundary(start, end, boundary, packed >>> 16, packed & 0xffff);
      if (cmp > 0 || (cmp == 0 && (bits & 1) != 0)) {
        bits++;
        continue;
      }
      return signedDouble(negative, bits);
    }
    return signedDouble(negative, exactDoubleTokenBits(start, end, boundary));
  }

  private long exactDoubleTokenBits(int start, int end, byte[] boundary) {
    long low = 0;
    long high = DOUBLE_INFINITY_BITS;
    while (low < high) {
      long middle = (low + high) >>> 1;
      int packed = buildDoubleBoundary(middle, middle + 1, boundary);
      int cmp = compareTokenToBoundary(start, end, boundary, packed >>> 16, packed & 0xffff);
      if (cmp < 0) {
        high = middle;
      } else if (cmp > 0) {
        low = middle + 1;
      } else {
        return (middle & 1) == 0 ? middle : middle + 1;
      }
    }
    return low;
  }

  private static double signedDouble(boolean negative, long bits) {
    if (negative) {
      bits |= DOUBLE_SIGN_BIT;
    }
    return Double.longBitsToDouble(bits);
  }

  private static int buildDoubleBoundary(long lowBits, long highBits, byte[] digits) {
    long numerator;
    int binaryExponent;
    if (highBits == DOUBLE_INFINITY_BITS) {
      numerator = (1L << (DOUBLE_FRACTION_BITS + 2)) - 1;
      binaryExponent = 970;
    } else {
      long lowMantissa = doubleMantissa(lowBits);
      int lowExponent = doubleBinaryExponent(lowBits);
      long highMantissa = doubleMantissa(highBits);
      int highExponent = doubleBinaryExponent(highBits);
      int exponent = Math.min(lowExponent, highExponent);
      numerator =
          (lowMantissa << (lowExponent - exponent)) + (highMantissa << (highExponent - exponent));
      binaryExponent = exponent - 1;
    }
    int length = writeBoundaryDigits(numerator, binaryExponent, digits);
    int scale = binaryExponent < 0 ? -binaryExponent : 0;
    return (length << 16) | scale;
  }

  protected final float readFloatExponentValue(
      boolean negative, long unscaled, int scale, int start, int exponentOffset) {
    long adjustedScale = readExponentScale(exponentOffset, scale);
    return floatFromDecimal(negative, unscaled, adjustedScale, false, start, position);
  }

  protected final float readScannedFloatValue(
      boolean negative, long unscaled, long scale, int start, int end) {
    return floatFromDecimal(negative, unscaled, scale, false, start, end);
  }

  private long readExponentScale(int offset, long scale) {
    offset++;
    boolean negativeExponent = false;
    if (offset < length()) {
      int ch = charAt(offset);
      if (ch == '-' || ch == '+') {
        negativeExponent = ch == '-';
        offset++;
      }
    }
    int exponentStart = offset;
    long exponent = 0;
    int inputLength = length();
    while (offset < inputLength) {
      int ch = charAt(offset);
      if (ch < '0' || ch > '9') {
        break;
      }
      if (exponent < TOKEN_EXPONENT_LIMIT) {
        exponent = exponent * 10 + ch - '0';
        if (exponent > TOKEN_EXPONENT_LIMIT) {
          exponent = TOKEN_EXPONENT_LIMIT;
        }
      }
      offset++;
    }
    if (offset == exponentStart) {
      throw numberError(offset, "Expected exponent digit");
    }
    position = offset;
    return negativeExponent ? scale + exponent : scale - exponent;
  }

  protected final float readFloatFallbackValue(int start) {
    position = start;
    if (start < length() && charAt(start) == '"') {
      return readNonFiniteFloatLiteral();
    }
    return readFloatNumberFallback(start);
  }

  // Float fallback remains reader-owned: it must not materialize a number String or construct
  // arbitrary-precision numbers. Big number allocation is owned only by BigInteger/BigDecimal.
  private float readFloatNumberFallback(int start) {
    int offset = start;
    int inputLength = length();
    boolean negative = false;
    if (offset < inputLength && charAt(offset) == '-') {
      negative = true;
      offset++;
    }
    if (offset >= inputLength) {
      throw numberError(offset, "Expected float");
    }

    int ch = charAt(offset);
    long significand = 0;
    int storedDigits = 0;
    int truncatedDigits = 0;
    int fractionDigits = 0;
    boolean nonZeroSeen = false;
    boolean sticky = false;

    if (ch == '0') {
      offset++;
      if (offset < inputLength) {
        ch = charAt(offset);
        if (ch >= '0' && ch <= '9') {
          throw numberError(offset, "Leading zero in number");
        }
      }
    } else if (ch >= '1' && ch <= '9') {
      do {
        int digit = ch - '0';
        if (digit != 0 || nonZeroSeen) {
          nonZeroSeen = true;
          if (storedDigits < 18) {
            significand = significand * 10 + digit;
            storedDigits++;
          } else {
            truncatedDigits++;
            sticky |= digit != 0;
          }
        }
        offset++;
        ch = offset < inputLength ? charAt(offset) : -1;
      } while (ch >= '0' && ch <= '9');
    } else {
      throw numberError(offset, "Expected float");
    }

    if (offset < inputLength && charAt(offset) == '.') {
      offset++;
      int fractionStart = offset;
      while (offset < inputLength) {
        ch = charAt(offset);
        if (ch < '0' || ch > '9') {
          break;
        }
        int digit = ch - '0';
        if (digit != 0 || nonZeroSeen) {
          nonZeroSeen = true;
          if (storedDigits < 18) {
            significand = significand * 10 + digit;
            storedDigits++;
          } else {
            truncatedDigits++;
            sticky |= digit != 0;
          }
        }
        fractionDigits++;
        offset++;
      }
      if (offset == fractionStart) {
        throw numberError(offset, "Expected digit");
      }
    }

    long exponent = 0;
    if (offset < inputLength) {
      ch = charAt(offset);
      if (ch == 'e' || ch == 'E') {
        offset++;
        boolean negativeExponent = false;
        if (offset < inputLength) {
          ch = charAt(offset);
          if (ch == '-' || ch == '+') {
            negativeExponent = ch == '-';
            offset++;
          }
        }
        int exponentStart = offset;
        while (offset < inputLength) {
          ch = charAt(offset);
          if (ch < '0' || ch > '9') {
            break;
          }
          if (exponent < TOKEN_EXPONENT_LIMIT) {
            exponent = exponent * 10 + ch - '0';
            if (exponent > TOKEN_EXPONENT_LIMIT) {
              exponent = TOKEN_EXPONENT_LIMIT;
            }
          }
          offset++;
        }
        if (offset == exponentStart) {
          throw numberError(offset, "Expected exponent digit");
        }
        if (negativeExponent) {
          exponent = -exponent;
        }
      }
    }

    position = offset;
    if (!nonZeroSeen) {
      return negative ? -0.0f : 0.0f;
    }
    long scale = (long) fractionDigits - exponent - truncatedDigits;
    return floatFromDecimal(negative, significand, scale, sticky, start, offset);
  }

  private float floatFromDecimal(
      boolean negative, long significand, long scale, boolean sticky, int start, int end) {
    if (significand == 0) {
      return negative ? -0.0f : 0.0f;
    }
    if (!sticky) {
      if (scale >= 0 && scale <= COMPACT_DECIMAL_MAX_SCALE) {
        return compactFloatValue(negative, significand, (int) scale);
      }
      if (scale < 0 && scale >= -COMPACT_DECIMAL_MAX_SCALE) {
        long multiplied = multiplyByPowerOfTen(significand, (int) -scale);
        if (multiplied >= 0) {
          return compactFloatValue(negative, multiplied, 0);
        }
      }
    }
    float result = approximateFloat(negative, significand, scale);
    return correctFloatToken(negative, result, start, end);
  }

  private static float approximateFloat(boolean negative, long significand, long scale) {
    double value = (double) significand;
    long decimalExponent = -scale;
    if (decimalExponent > 0) {
      if (decimalExponent > 50) {
        return negative ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
      }
      value *= Math.pow(10.0d, decimalExponent);
    } else if (decimalExponent < 0) {
      if (decimalExponent < -350) {
        return negative ? -0.0f : 0.0f;
      }
      value /= Math.pow(10.0d, -decimalExponent);
    }
    float result = (float) value;
    return negative ? -result : result;
  }

  // Long float tokens can sit within one double ULP of a float midpoint. Correct the close
  // estimate against exact adjacent-float boundaries so fallback never needs a number String.
  private float correctFloatToken(boolean negative, float estimate, int start, int end) {
    int bits = Float.floatToRawIntBits(estimate);
    bits &= ~FLOAT_SIGN_BIT;
    byte[] boundary = decimalBoundaryDigits;
    for (int i = 0; i < 4; i++) {
      if (bits == FLOAT_INFINITY_BITS) {
        int packed = buildFloatBoundary(FLOAT_MAX_FINITE_BITS, FLOAT_INFINITY_BITS, boundary);
        int cmp = compareTokenToBoundary(start, end, boundary, packed >>> 16, packed & 0xffff);
        if (cmp < 0) {
          bits = FLOAT_MAX_FINITE_BITS;
          continue;
        }
        return signedFloat(negative, bits);
      }
      if (bits == 0) {
        int packed = buildFloatBoundary(0, 1, boundary);
        int cmp = compareTokenToBoundary(start, end, boundary, packed >>> 16, packed & 0xffff);
        if (cmp > 0) {
          bits = 1;
          continue;
        }
        return signedFloat(negative, bits);
      }

      int packed = buildFloatBoundary(bits - 1, bits, boundary);
      int cmp = compareTokenToBoundary(start, end, boundary, packed >>> 16, packed & 0xffff);
      if (cmp < 0 || (cmp == 0 && !isEvenFloat(bits))) {
        bits--;
        continue;
      }

      packed = buildFloatBoundary(bits, bits + 1, boundary);
      cmp = compareTokenToBoundary(start, end, boundary, packed >>> 16, packed & 0xffff);
      if (cmp > 0 || (cmp == 0 && !isEvenFloat(bits))) {
        bits++;
        continue;
      }
      return signedFloat(negative, bits);
    }
    return signedFloat(negative, exactFloatTokenBits(start, end, boundary));
  }

  private int exactFloatTokenBits(int start, int end, byte[] boundary) {
    int low = 0;
    int high = FLOAT_INFINITY_BITS;
    while (low < high) {
      int middle = (low + high) >>> 1;
      int packed = buildFloatBoundary(middle, middle + 1, boundary);
      int cmp = compareTokenToBoundary(start, end, boundary, packed >>> 16, packed & 0xffff);
      if (cmp < 0) {
        high = middle;
      } else if (cmp > 0) {
        low = middle + 1;
      } else {
        return isEvenFloat(middle) ? middle : middle + 1;
      }
    }
    return low;
  }

  private static float signedFloat(boolean negative, int bits) {
    float result = Float.intBitsToFloat(bits);
    return negative ? -result : result;
  }

  private static int buildFloatBoundary(int lowBits, int highBits, byte[] digits) {
    int numerator;
    int binaryExponent;
    if (highBits == FLOAT_INFINITY_BITS) {
      numerator = (1 << (FLOAT_FRACTION_BITS + 2)) - 1;
      binaryExponent = 103;
    } else {
      int lowMantissa = floatMantissa(lowBits);
      int lowExponent = floatBinaryExponent(lowBits);
      int highMantissa = floatMantissa(highBits);
      int highExponent = floatBinaryExponent(highBits);
      int exponent = Math.min(lowExponent, highExponent);
      numerator =
          (lowMantissa << (lowExponent - exponent)) + (highMantissa << (highExponent - exponent));
      binaryExponent = exponent - 1;
    }
    int length = writeBoundaryDigits(numerator, binaryExponent, digits);
    int scale = binaryExponent < 0 ? -binaryExponent : 0;
    return (length << 16) | scale;
  }

  private static int floatMantissa(int bits) {
    int fraction = bits & FLOAT_FRACTION_MASK;
    return (bits & FLOAT_EXPONENT_MASK) == 0 ? fraction : fraction | (1 << FLOAT_FRACTION_BITS);
  }

  private static int floatBinaryExponent(int bits) {
    int exponent = (bits & FLOAT_EXPONENT_MASK) >>> FLOAT_FRACTION_BITS;
    return exponent == 0 ? -149 : exponent - 150;
  }

  private static int writeBoundaryDigits(long numerator, int binaryExponent, byte[] digits) {
    int length = 0;
    long value = numerator;
    do {
      digits[length++] = (byte) (value % 10);
      value /= 10;
    } while (value != 0);
    int factor = binaryExponent >= 0 ? 2 : 5;
    int count = binaryExponent >= 0 ? binaryExponent : -binaryExponent;
    for (int i = 0; i < count; i++) {
      length = multiplyDecimalDigits(digits, length, factor);
    }
    for (int left = 0, right = length - 1; left < right; left++, right--) {
      byte digit = digits[left];
      digits[left] = digits[right];
      digits[right] = digit;
    }
    return length;
  }

  private static int multiplyDecimalDigits(byte[] digits, int length, int factor) {
    int carry = 0;
    for (int i = 0; i < length; i++) {
      int product = digits[i] * factor + carry;
      digits[i] = (byte) (product % 10);
      carry = product / 10;
    }
    while (carry != 0) {
      digits[length++] = (byte) (carry % 10);
      carry /= 10;
    }
    return length;
  }

  private int compareTokenToBoundary(
      int start, int end, byte[] boundaryDigits, int boundaryLength, int boundaryScale) {
    int offset = start;
    if (offset < end && charAt(offset) == '-') {
      offset++;
    }
    int scan = offset;
    int digitCount = 0;
    int fractionDigits = 0;
    boolean fraction = false;
    boolean significant = false;
    while (scan < end) {
      int ch = charAt(scan);
      if (ch >= '0' && ch <= '9') {
        if (ch != '0' || significant) {
          significant = true;
          digitCount++;
        }
        if (fraction) {
          fractionDigits++;
        }
        scan++;
      } else if (ch == '.') {
        fraction = true;
        scan++;
      } else {
        break;
      }
    }
    if (!significant) {
      return -1;
    }
    long exponent = 0;
    if (scan < end) {
      int ch = charAt(scan);
      if (ch == 'e' || ch == 'E') {
        scan++;
        boolean negativeExponent = false;
        if (scan < end) {
          ch = charAt(scan);
          if (ch == '-' || ch == '+') {
            negativeExponent = ch == '-';
            scan++;
          }
        }
        while (scan < end) {
          ch = charAt(scan);
          if (ch < '0' || ch > '9') {
            break;
          }
          if (exponent < TOKEN_EXPONENT_LIMIT) {
            exponent = exponent * 10 + ch - '0';
            if (exponent > TOKEN_EXPONENT_LIMIT) {
              exponent = TOKEN_EXPONENT_LIMIT;
            }
          }
          scan++;
        }
        if (negativeExponent) {
          exponent = -exponent;
        }
      }
    }

    long adjustedLength = (long) digitCount + exponent - fractionDigits;
    long boundaryAdjustedLength = (long) boundaryLength - boundaryScale;
    if (adjustedLength != boundaryAdjustedLength) {
      return adjustedLength < boundaryAdjustedLength ? -1 : 1;
    }

    scan = offset;
    significant = false;
    int emitted = 0;
    int max = Math.max(digitCount, boundaryLength);
    for (int i = 0; i < max; i++) {
      int digit = 0;
      if (emitted < digitCount) {
        while (scan < end) {
          int ch = charAt(scan++);
          if (ch == 'e' || ch == 'E') {
            break;
          }
          if (ch < '0' || ch > '9') {
            continue;
          }
          if (ch == '0' && !significant) {
            continue;
          }
          significant = true;
          digit = ch - '0';
          emitted++;
          break;
        }
      }
      int boundaryDigit = i < boundaryLength ? boundaryDigits[i] : 0;
      if (digit != boundaryDigit) {
        return digit < boundaryDigit ? -1 : 1;
      }
    }
    return 0;
  }

  private static boolean isEvenFloat(int bits) {
    return (bits & 1) == 0;
  }

  private static long multiplyByPowerOfTen(long value, int power) {
    long multiplier = LONG_POWERS_OF_TEN[power];
    if (value != 0 && value > Long.MAX_VALUE / multiplier) {
      return -1;
    }
    return value * multiplier;
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

  protected final double readNonFiniteDoubleLiteral() {
    if (matchesQuotedAscii("NaN")) {
      position += 5;
      return Double.NaN;
    }
    if (matchesQuotedAscii("Infinity")) {
      position += 10;
      return Double.POSITIVE_INFINITY;
    }
    if (matchesQuotedAscii("-Infinity")) {
      position += 11;
      return Double.NEGATIVE_INFINITY;
    }
    // Numeric strings are intentionally not coerced; only writer-emitted non-finite tokens
    // are accepted here.
    throw error("Expected finite JSON number or non-finite double string");
  }

  protected final float readNonFiniteFloatLiteral() {
    if (matchesQuotedAscii("NaN")) {
      position += 5;
      return Float.NaN;
    }
    if (matchesQuotedAscii("Infinity")) {
      position += 10;
      return Float.POSITIVE_INFINITY;
    }
    if (matchesQuotedAscii("-Infinity")) {
      position += 11;
      return Float.NEGATIVE_INFINITY;
    }
    // Numeric strings are intentionally not coerced; only writer-emitted non-finite tokens
    // are accepted here.
    throw error("Expected finite JSON number or non-finite float string");
  }

  private boolean matchesQuotedAscii(String value) {
    int start = position;
    int valueLength = value.length();
    if (start + valueLength + 2 > length()
        || charAt(start) != '"'
        || charAt(start + valueLength + 1) != '"') {
      return false;
    }
    for (int i = 0; i < valueLength; i++) {
      if (charAt(start + i + 1) != value.charAt(i)) {
        return false;
      }
    }
    return true;
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
      // Unknown values are validation-only. Hashing validates the complete escaped string without
      // materializing storage that no object can own.
      readStringHash();
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
      skipNumberToken();
    }
  }

  private void skipNumberToken() {
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

  protected final ForyJsonException errorAt(String message, int offset) {
    return new ForyJsonException(message + " at JSON position " + offset);
  }

  private int scanWhitespace(int cursor) {
    int inputLength = length();
    while (cursor < inputLength) {
      char ch = charAt(cursor);
      if (ch != ' ' && ch != '\n' && ch != '\r' && ch != '\t') {
        break;
      }
      cursor++;
    }
    return cursor;
  }

  private int scanValue(int cursor, int savedDepth, int scanDepth) {
    cursor = scanWhitespace(cursor);
    if (cursor >= length()) {
      throw errorAt("Expected value", cursor);
    }
    char ch = charAt(cursor);
    if (ch == '"') {
      return scanStringEnd(cursor);
    }
    if (ch == '{') {
      return scanObject(cursor, savedDepth, scanDepth + 1);
    }
    if (ch == '[') {
      return scanArray(cursor, savedDepth, scanDepth + 1);
    }
    if (ch == 't') {
      return scanLiteral(cursor, "true");
    }
    if (ch == 'f') {
      return scanLiteral(cursor, "false");
    }
    if (ch == 'n') {
      return scanLiteral(cursor, "null");
    }
    return scanNumber(cursor);
  }

  private int scanObject(int cursor, int savedDepth, int scanDepth) {
    checkScanDepth(savedDepth, scanDepth, cursor);
    cursor = scanWhitespace(cursor + 1);
    if (cursor < length() && charAt(cursor) == '}') {
      return cursor + 1;
    }
    while (true) {
      cursor = scanStringEnd(cursor);
      cursor = scanWhitespace(cursor);
      if (cursor >= length() || charAt(cursor) != ':') {
        throw errorAt("Expected ':'", cursor);
      }
      cursor = scanValue(cursor + 1, savedDepth, scanDepth);
      cursor = scanWhitespace(cursor);
      if (cursor >= length()) {
        throw errorAt("Expected ',' or '}'", cursor);
      }
      char separator = charAt(cursor++);
      if (separator == '}') {
        return cursor;
      }
      if (separator != ',') {
        throw errorAt("Expected ',' or '}'", cursor - 1);
      }
      cursor = scanWhitespace(cursor);
      if (cursor < length() && charAt(cursor) == '}') {
        throw errorAt("Expected object field", cursor);
      }
    }
  }

  private int scanArray(int cursor, int savedDepth, int scanDepth) {
    checkScanDepth(savedDepth, scanDepth, cursor);
    cursor = scanWhitespace(cursor + 1);
    if (cursor < length() && charAt(cursor) == ']') {
      return cursor + 1;
    }
    while (true) {
      cursor = scanValue(cursor, savedDepth, scanDepth);
      cursor = scanWhitespace(cursor);
      if (cursor >= length()) {
        throw errorAt("Expected ',' or ']'", cursor);
      }
      char separator = charAt(cursor++);
      if (separator == ']') {
        return cursor;
      }
      if (separator != ',') {
        throw errorAt("Expected ',' or ']'", cursor - 1);
      }
      cursor = scanWhitespace(cursor);
      if (cursor < length() && charAt(cursor) == ']') {
        throw errorAt("Expected array value", cursor);
      }
    }
  }

  private int scanLiteral(int cursor, String literal) {
    int end = cursor + literal.length();
    if (end > length()) {
      throw errorAt("Expected '" + literal + "'", cursor);
    }
    for (int i = 0; i < literal.length(); i++) {
      if (charAt(cursor + i) != literal.charAt(i)) {
        throw errorAt("Expected '" + literal + "'", cursor);
      }
    }
    return end;
  }

  private int scanNumber(int cursor) {
    int inputLength = length();
    int start = cursor;
    if (cursor < inputLength && charAt(cursor) == '-') {
      cursor++;
    }
    if (cursor >= inputLength) {
      throw errorAt("Expected digit", cursor);
    }
    char ch = charAt(cursor);
    if (ch == '0') {
      cursor++;
      if (cursor < inputLength) {
        ch = charAt(cursor);
        if (ch >= '0' && ch <= '9') {
          throw errorAt("Leading zero in number", cursor);
        }
      }
    } else if (ch >= '1' && ch <= '9') {
      do {
        cursor++;
      } while (cursor < inputLength && charAt(cursor) >= '0' && charAt(cursor) <= '9');
    } else {
      throw errorAt("Expected digit", cursor);
    }
    if (cursor < inputLength && charAt(cursor) == '.') {
      cursor++;
      int digits = cursor;
      while (cursor < inputLength && charAt(cursor) >= '0' && charAt(cursor) <= '9') {
        cursor++;
      }
      if (cursor == digits) {
        throw errorAt("Expected digit", cursor);
      }
    }
    if (cursor < inputLength && (charAt(cursor) == 'e' || charAt(cursor) == 'E')) {
      cursor++;
      if (cursor < inputLength && (charAt(cursor) == '+' || charAt(cursor) == '-')) {
        cursor++;
      }
      int digits = cursor;
      while (cursor < inputLength && charAt(cursor) >= '0' && charAt(cursor) <= '9') {
        cursor++;
      }
      if (cursor == digits) {
        throw errorAt("Expected digit", cursor);
      }
    }
    if (cursor == start) {
      throw errorAt("Expected number", cursor);
    }
    return cursor;
  }

  private void checkScanDepth(int savedDepth, int scanDepth, int cursor) {
    if (scanDepth > maxDepth - savedDepth) {
      throw errorAt("JSON max depth " + maxDepth + " exceeded", cursor);
    }
  }

  private ForyJsonException numberError(int offset, String message) {
    position = offset;
    return error(message);
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
