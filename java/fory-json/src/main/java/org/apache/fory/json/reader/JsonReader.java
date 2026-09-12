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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Base64;
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
import org.apache.fory.memory.LittleEndian;
import org.apache.fory.memory.NativeByteOrder;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.platform.internal._JDKAccess;
import org.apache.fory.serializer.StringSerializer;

/**
 * Representation-neutral JSON cursor and common scalar parsing owner.
 *
 * <p>The base class retains the resolver used by dynamic codecs, the current code-unit position,
 * configured and current container depth, reusable creator and numeric workspaces, and a quoted
 * text view. Concrete readers own input storage, string decoding, field-name probes, and direct
 * primitive numeric fast paths for their representation.
 *
 * <p>Primitive numeric common paths parse directly from the input. Floating-point conversion uses
 * integer bounds on decimal powers and delegates ambiguous rounding or long mantissas to the JDK.
 * The internal length and scale limits apply only when constructing {@link BigInteger} or {@link
 * BigDecimal}; raw number text, primitive scans, and skipped values do not inherit that resource
 * policy.
 *
 * <p>Declared boolean and numeric scalars accept their native JSON token or the same token text in
 * quotes without a configuration gate. Quoted common paths consume only the quotes around the
 * existing token parser, so they retain its allocation and validation behavior.
 *
 * <p>Readers are mutable and confined to one borrowed {@code ForyJson} state. Concrete reset
 * methods borrow an input and reset the cursor, depth, and graph-memory budget; {@code clear()}
 * detaches that input and clears the root state before the state returns to the pool. A failed
 * nested parse may leave depth nonzero because the root cleanup resets it; nested codec paths
 * intentionally avoid cleanup-only {@code try/finally} regions.
 */
public abstract class JsonReader {
  static final int MAX_BIG_NUMBER_LENGTH = 10_000;
  private static final MethodHandle BIG_INTEGER_CONSTRUCTOR = bigIntegerConstructor();
  private static final byte[] EMPTY_BYTES = new byte[0];
  private static final byte[] HEX_VALUES = hexValues();
  static final int MAX_BIG_DECIMAL_SCALE = 10_000;
  private static final int COMPACT_DECIMAL_MAX_SCALE = 18;
  static final long[] LONG_POWERS_OF_TEN = {
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
  private static final long[] COMPACT_DECIMAL_MANTISSAS = {
    0x8000_0000_0000_0000L,
    0xcccc_cccc_cccc_ccccL,
    0xa3d7_0a3d_70a3_d70aL,
    0x8312_6e97_8d4f_df3bL,
    0xd1b7_1758_e219_652bL,
    0xa7c5_ac47_1b47_8423L,
    0x8637_bd05_af6c_69b5L,
    0xd6bf_94d5_e57a_42bcL,
    0xabcc_7711_8461_cefcL,
    0x8970_5f41_36b4_a597L,
    0xdbe6_fece_bded_d5beL,
    0xafeb_ff0b_cb24_aafeL,
    0x8cbc_cc09_6f50_88cbL,
    0xe12e_1342_4bb4_0e13L,
    0xb424_dc35_095c_d80fL,
    0x901d_7cf7_3ab0_acd9L,
    0xe695_94be_c44d_e15bL,
    0xb877_aa32_36a4_b449L,
    0x9392_ee8e_921d_5d07L
  };
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
  // Past twice the maximum token digit count, an exponent cannot be canceled back into the
  // finite float range by integer, fractional, or truncated digits.
  private static final long TOKEN_EXPONENT_LIMIT = 2L * Integer.MAX_VALUE + 1_000L;
  private static final float[] FLOAT_POWERS_OF_TEN = {
    1.0f, 10.0f, 100.0f, 1_000.0f, 10_000.0f, 100_000.0f, 1_000_000.0f, 10_000_000.0f
  };

  private final JsonConfig config;
  private final JsonTypeResolver typeResolver;
  protected int position;
  private final int maxDepth;
  private int depth;
  private long remainingGraphMemoryBytes;

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
        // The hash selects only the declared discriminator-property candidate. The complete
        // decoded member name must match before this field can control subtype selection.
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
          // Logical subtype names use the same two-stage contract: select by hash, then compare the
          // complete decoded name before returning the closed-table class index.
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

  /** Decodes one validated quoted token body into the concrete reader's reusable string scratch. */
  protected abstract CharSequence decodeQuotedText(int start, int end);

  /**
   * Reads one wrapper subtype name from a fixed validated table without materializing a String.
   *
   * <p>Concrete readers may use the decoded-name hash to select a candidate, but must compare the
   * complete decoded name before returning its closed-table class index.
   */
  public abstract int readSubtypeName(JsonSubtypeScanInfo info);

  private final QuotedTextView quotedTextView = new QuotedTextView(this);
  private final Object[] creatorArguments = new Object[1];
  // Keep all reusable numeric arrays behind the original single workspace reference: adding an
  // inherited reference here shifts concrete readers' representation fields and measurably harms
  // their native-token hot paths. Reader construction remains the cold allocation owner.
  private final NumericWorkspace numericWorkspace = new NumericWorkspace();

  protected JsonReader(JsonConfig config, JsonTypeResolver typeResolver) {
    this.config = config;
    this.typeResolver = Objects.requireNonNull(typeResolver, "typeResolver");
    maxDepth = config.maxDepth();
  }

  /**
   * Returns the resolver owned by this reader for custom codecs that resolve dynamic child types.
   */
  public final JsonTypeResolver typeResolver() {
    return typeResolver;
  }

  /** Returns this reader's reusable one-value creator argument array. */
  @Internal
  public final Object[] creatorArguments(Object value) {
    creatorArguments[0] = value;
    return creatorArguments;
  }

  /** Releases the value retained by {@link #creatorArguments(Object)}. */
  @Internal
  public final void clearCreatorArguments() {
    creatorArguments[0] = null;
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

  /** Consumes {@code expected} after whitespace when present. */
  public abstract boolean consumeNextToken(char expected);

  /** Consumes {@code expected} after whitespace or fails. */
  public abstract void expectNextToken(char expected);

  /** Consumes the comma before the next object member, or the closing brace. */
  public abstract boolean consumeNextCommaOrEndObject();

  /** Consumes the comma before the next array element, or the closing bracket. */
  public abstract boolean consumeNextCommaOrEndArray();

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

  /**
   * Proves that an already selected input span contains at least {@code bytes} readable bytes.
   *
   * <p>Every supported input code unit is backed by at least one byte, so the
   * representation-neutral length is a conservative byte lower bound. Composite codecs use this
   * before a compact scalar controls a larger variable-capacity allocation.
   */
  @Internal
  public final void checkReadableBytesFrom(int start, int bytes) {
    int inputLength = length();
    if (start < 0 || start > position || bytes < 0 || bytes > inputLength - start) {
      throwInsufficientReadableBytes(start, bytes, inputLength);
    }
  }

  private void throwInsufficientReadableBytes(int start, int bytes, int inputLength) {
    throw new ForyJsonException(
        "Insufficient JSON input for variable-capacity value: requested "
            + bytes
            + " readable bytes from position "
            + start
            + ", input length "
            + inputLength);
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
    remainingGraphMemoryBytes = config.maxGraphMemoryBytes();
    quotedTextView.clear();
  }

  /**
   * Reserves estimated graph memory before an owner or its retained storage is materialized.
   *
   * <p>Custom codecs use this raw byte API before allocating a composite owner and at their bounded
   * batch boundaries for retained container storage. Dedicated leaf values do not reserve graph
   * memory.
   */
  public final void reserveGraphMemory(long bytes) {
    long remaining = remainingGraphMemoryBytes;
    long nextRemaining = remaining - bytes;
    if ((bytes | nextRemaining) < 0) {
      throwInvalidGraphMemory(bytes, remaining);
    }
    remainingGraphMemoryBytes = nextRemaining;
  }

  /**
   * Reserves estimated graph memory before an owner or its retained storage is materialized.
   *
   * <p>This overload keeps the common integer-estimate path to one comparison and subtraction.
   */
  public final void reserveGraphMemory(int bytes) {
    long remaining = remainingGraphMemoryBytes;
    if (bytes < 0 || remaining < bytes) {
      throwInvalidGraphMemory(bytes, remaining);
    }
    remainingGraphMemoryBytes = remaining - bytes;
  }

  private void throwInvalidGraphMemory(long bytes, long remaining) {
    if (bytes < 0) {
      throw new ForyJsonException(
          "Estimated graph memory must be non-negative, but got " + bytes + " bytes");
    }
    throw new ForyJsonException(
        "Estimated graph memory request "
            + bytes
            + " bytes exceeds maxGraphMemoryBytes remaining budget "
            + remaining
            + " bytes out of configured limit "
            + config.maxGraphMemoryBytes()
            + " bytes; increase ForyJsonBuilder#withMaxGraphMemoryBytes for trusted data");
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

  /**
   * Reads nullable quoted text without materializing a String.
   *
   * <p>The returned view is reused by this reader and is valid only until the next reader
   * operation. Canonical unescaped ASCII points directly at the input. Escaped and non-ASCII text
   * is decoded into the concrete reader's bounded reusable string scratch.
   */
  public final CharSequence readQuotedText() {
    if (tryReadNullToken()) {
      return null;
    }
    return readQuotedTextValue();
  }

  /** Reads a quoted-text value after its owner has already rejected or consumed {@code null}. */
  protected final CharSequence readQuotedTextValue() {
    CharSequence value = tryReadQuotedTextView();
    if (value != null) {
      return value;
    }
    int tokenStart = position;
    int tokenEnd = scanStringEnd(tokenStart);
    value = decodeQuotedText(tokenStart + 1, tokenEnd - 1);
    position = tokenEnd;
    return value;
  }

  /** Publishes decoded Latin1 or native-order UTF16 scratch through the reusable quoted view. */
  protected final CharSequence decodedQuotedText(byte[] bytes, int length, boolean utf16) {
    quotedTextView.reset(bytes, length, utf16);
    return quotedTextView;
  }

  /** Reads a nullable Base64 JSON string into its decoded bytes. */
  public final byte[] readBase64() {
    if (tryReadNullToken()) {
      return null;
    }
    String encoded = readString();
    int encodedLength = encoded.length();
    if (encodedLength == 0) {
      return EMPTY_BYTES;
    }
    // The JDK also accepts unpadded input; Fory's Base64 representation requires complete quartets.
    if ((encodedLength & 3) != 0) {
      throw error("Invalid Base64 JSON string length");
    }
    // The string reader proves the complete input before any decoded binary storage is allocated.
    // The JDK decoder can use its bulk intrinsic after the representation-specific string scan.
    try {
      return Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException e) {
      throw new ForyJsonException("Invalid Base64 JSON string at JSON position " + position, e);
    }
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

  public char peekToken() {
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
    return readBooleanToken();
  }

  // Concrete readers own both native and quoted boolean token parsing.
  protected abstract boolean readBooleanToken();

  public final String readNumberAsString() {
    skipWhitespace();
    return readNumberToken();
  }

  public final Number readNumber() {
    return materializeNumber(readNumberAsString());
  }

  private String readNumberToken() {
    int start = position;
    scanNumberToken();
    return slice(start, position);
  }

  // Absolute point/exponent offsets occupy the high/low 32 bits; -1 means a separator is absent.
  long scanNumberToken() {
    int start = position;
    int point = -1;
    int exponent = -1;
    if (position < length() && charAt(position) == '-') {
      position++;
    }
    readIntegerDigits();
    if (position < length() && charAt(position) == '.') {
      point = position;
      position++;
      readDigits();
    }
    if (position < length() && (charAt(position) == 'e' || charAt(position) == 'E')) {
      exponent = position;
      position++;
      if (position < length() && (charAt(position) == '+' || charAt(position) == '-')) {
        position++;
      }
      readDigits();
    }
    if (start == position) {
      throw error("Expected number");
    }
    return ((long) point << 32) | (exponent & 0xffff_ffffL);
  }

  public final int readInt() {
    skipWhitespace();
    if (position < length() && charAt(position) == '"') {
      return readQuotedInt();
    }
    return readIntToken();
  }

  private int readIntToken() {
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

  private int readQuotedInt() {
    beginQuotedScalar();
    int value = readIntToken();
    finishQuotedScalar();
    return value;
  }

  public final long readLong() {
    skipWhitespace();
    if (position < length() && charAt(position) == '"') {
      return readQuotedLong();
    }
    return readLongToken();
  }

  private long readLongToken() {
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

  private long readQuotedLong() {
    beginQuotedScalar();
    long value = readLongToken();
    finishQuotedScalar();
    return value;
  }

  /** Reads one canonical unsigned 32-bit JSON integer and returns its raw bits. */
  public final int readUnsignedInt() {
    long value = readUnsignedLong();
    if (Long.compareUnsigned(value, 0xffff_ffffL) > 0) {
      throw error("Unsigned integer overflow");
    }
    return (int) value;
  }

  /** Reads one canonical unsigned 64-bit JSON integer and returns its raw bits. */
  public final long readUnsignedLong() {
    skipWhitespace();
    if (position < length() && charAt(position) == '"') {
      beginQuotedScalar();
      long value = readUnsignedDigits();
      rejectFractionOrExponent();
      finishQuotedScalar();
      return value;
    }
    long value = readUnsignedDigits();
    rejectFractionOrExponent();
    return value;
  }

  private long readUnsignedDigits() {
    int start = position;
    if (position >= length()) {
      throw error("Expected unsigned digit");
    }
    char ch = charAt(position);
    if (ch == '0') {
      position++;
      rejectLeadingDigit();
      return 0;
    }
    if (ch < '1' || ch > '9') {
      throw error("Expected unsigned digit");
    }
    long result = 0;
    long maxDiv10 = 1_844_674_407_370_955_161L;
    while (position < length()) {
      ch = charAt(position);
      if (ch < '0' || ch > '9') {
        break;
      }
      int digit = ch - '0';
      int comparison = Long.compareUnsigned(result, maxDiv10);
      if (comparison > 0 || comparison == 0 && digit > 5) {
        throw error("Unsigned long overflow");
      }
      result = result * 10 + digit;
      position++;
    }
    if (position == start) {
      throw error("Expected unsigned digit");
    }
    return result;
  }

  public BigInteger readBigInteger() {
    skipWhitespace();
    if (position < length() && charAt(position) == '"') {
      return readQuotedBigInteger();
    }
    return readBigIntegerToken();
  }

  private BigInteger readBigIntegerToken() {
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
      return BigInteger.ZERO;
    }
    if (ch < '1' || ch > '9') {
      throw error("Expected digit");
    }
    long multmin = limit / 10;
    boolean overflow = false;
    while (position < length()) {
      ch = charAt(position);
      if (ch < '0' || ch > '9') {
        break;
      }
      if (!overflow) {
        int digit = ch - '0';
        if (result < multmin) {
          overflow = true;
        } else {
          result *= 10;
          if (result < limit + digit) {
            overflow = true;
          } else {
            result -= digit;
          }
        }
      }
      position++;
    }
    rejectFractionOrExponent();
    if (!overflow) {
      return BigInteger.valueOf(negative ? result : -result);
    }
    // Overflow is normal for this arbitrary-precision target. Keeping it out of exception control
    // flow avoids a transient allocation whose captured stack would differ on the quoted path.
    return parseBigInteger(slice(start, position));
  }

  private BigInteger readQuotedBigInteger() {
    beginQuotedScalar();
    BigInteger value = readBigIntegerToken();
    finishQuotedScalar();
    return value;
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
      return parseUuidValue(readQuotedTextValue());
    }
  }

  public LocalTime readIsoLocalTime() {
    return parseLocalTimeValue(readQuotedTextValue());
  }

  public LocalDateTime readIsoLocalDateTime() {
    return parseLocalDateTimeValue(readQuotedTextValue());
  }

  public Instant readIsoInstant() {
    return parseInstantValue(readQuotedTextValue());
  }

  public Duration readDuration() {
    return parseDurationValue(readQuotedTextValue());
  }

  public ZoneOffset readZoneOffset() {
    CharSequence value = readQuotedTextValue();
    ZoneOffset offset = tryParseZoneOffset(value);
    if (offset == null) {
      throw invalidStringValue("java.time.ZoneOffset");
    }
    return offset;
  }

  public ZonedDateTime readZonedDateTime() {
    return parseZonedDateTimeValue(readQuotedTextValue());
  }

  public Year readYear() {
    return parseYearText(readQuotedTextValue());
  }

  public YearMonth readYearMonth() {
    return parseYearMonthValue(readQuotedTextValue());
  }

  public MonthDay readMonthDay() {
    return parseMonthDayValue(readQuotedTextValue());
  }

  public Period readPeriod() {
    return parsePeriodValue(readQuotedTextValue());
  }

  public OffsetTime readOffsetTime() {
    return parseOffsetTimeValue(readQuotedTextValue());
  }

  protected BigDecimal readBigDecimalFallback(int start) {
    position = start;
    long separators = scanNumberToken();
    int numberLength = position - start;
    if (numberLength > MAX_BIG_NUMBER_LENGTH) {
      throwBigNumberLengthExceeded(position);
    }
    String number = slice(start, position);
    int exponent = (int) separators;
    if (exponent >= 0) {
      exponent -= start;
    }
    int coefficientEnd = exponent < 0 ? numberLength : exponent;
    int point = (int) (separators >>> 32);
    if (point >= 0) {
      point -= start;
    }
    long scale = point < 0 ? 0 : coefficientEnd - point - 1;
    if (exponent >= 0) {
      scale = readExponentScale(start + exponent, scale);
    }
    if (scale > MAX_BIG_DECIMAL_SCALE || scale < -MAX_BIG_DECIMAL_SCALE) {
      throwBigDecimalScaleExceeded();
    }
    // The scanner has validated the decimal grammar. Preserve every coefficient digit, including
    // trailing zeroes, and reuse integer conversion instead of repeating JDK decimal digit parsing.
    byte[] bytes = bigNumberBytes(number);
    BigInteger unscaled;
    if (point < 0) {
      unscaled = parseBigInteger(bytes, 0, coefficientEnd, 0, 0);
    } else {
      byte[] coefficient = new byte[coefficientEnd - 1];
      System.arraycopy(bytes, 0, coefficient, 0, point);
      System.arraycopy(bytes, point + 1, coefficient, point, coefficientEnd - point - 1);
      unscaled = parseBigInteger(coefficient, 0, coefficient.length, 0, 0);
    }
    return new BigDecimal(unscaled, (int) scale);
  }

  protected final void beginQuotedScalar() {
    position++;
    // Concrete token parsers also dispatch an opening quote. Reject another raw quote at this
    // boundary so malformed nested quoting cannot recursively re-enter the quoted cold path.
    if (position < length() && charAt(position) == '"') {
      throw error("Expected quoted scalar value");
    }
  }

  protected final void finishQuotedScalar() {
    if (position < length() && charAt(position) == '"') {
      position++;
      return;
    }
    throw error("Expected closing quote");
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

  static UUID parseUuidBytes(byte[] bytes, int start) {
    // The concrete reader has already checked the token bounds and separators.
    // Invalid digits remain negative through packing, so one test validates all eight groups.
    int a = hexValue4(bytes, start);
    int b = hexValue4(bytes, start + 4);
    int c = hexValue4(bytes, start + 9);
    int d = hexValue4(bytes, start + 14);
    int e = hexValue4(bytes, start + 19);
    int f = hexValue4(bytes, start + 24);
    int g = hexValue4(bytes, start + 28);
    int h = hexValue4(bytes, start + 32);
    if ((a | b | c | d | e | f | g | h) < 0) {
      throw new IllegalArgumentException();
    }
    return new UUID(
        ((long) a << 48) | ((long) b << 32) | ((long) c << 16) | d,
        ((long) e << 48) | ((long) f << 32) | ((long) g << 16) | h);
  }

  static int hexValue4(byte[] bytes, int offset) {
    byte[] values = HEX_VALUES;
    return (values[bytes[offset] & 0xff] << 12)
        | (values[bytes[offset + 1] & 0xff] << 8)
        | (values[bytes[offset + 2] & 0xff] << 4)
        | values[bytes[offset + 3] & 0xff];
  }

  private static byte[] hexValues() {
    byte[] values = new byte[256];
    Arrays.fill(values, (byte) -1);
    for (int i = 0; i < 10; i++) {
      values['0' + i] = (byte) i;
    }
    for (int i = 0; i < 6; i++) {
      values['a' + i] = values['A' + i] = (byte) (10 + i);
    }
    return values;
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

  /** Parses the {@link UUID#fromString(String)} grammar from the reusable quoted-text view. */
  protected final UUID parseUuidValue(CharSequence value) {
    try {
      int first = indexOf(value, '-', 0);
      int second = indexOf(value, '-', first + 1);
      int third = indexOf(value, '-', second + 1);
      int fourth = indexOf(value, '-', third + 1);
      if (first < 0
          || second < 0
          || third < 0
          || fourth < 0
          || indexOf(value, '-', fourth + 1) >= 0) {
        throw new IllegalArgumentException();
      }
      long msb = parseHexGroup(value, 0, first, 8);
      msb = (msb << 16) | parseHexGroup(value, first + 1, second, 4);
      msb = (msb << 16) | parseHexGroup(value, second + 1, third, 4);
      long lsb = parseHexGroup(value, third + 1, fourth, 4);
      lsb = (lsb << 48) | parseHexGroup(value, fourth + 1, value.length(), 12);
      return new UUID(msb, lsb);
    } catch (RuntimeException e) {
      try {
        // UUID.fromString historically accepts some noncanonical group widths. Keep the canonical
        // path allocation-free, but preserve that public compatibility on the cold fallback.
        return UUID.fromString(value.toString());
      } catch (RuntimeException fallback) {
        throw invalidStringValue("java.util.UUID", fallback);
      }
    }
  }

  private static int indexOf(CharSequence value, char expected, int start) {
    for (int i = start; i < value.length(); i++) {
      if (value.charAt(i) == expected) {
        return i;
      }
    }
    return -1;
  }

  private static long parseHexGroup(CharSequence value, int start, int end, int maxLength) {
    int length = end - start;
    if (length <= 0 || length > maxLength) {
      throw new IllegalArgumentException();
    }
    return parseHex(value, start, length);
  }

  private static long parseHex(CharSequence value, int offset, int length) {
    long result = 0;
    for (int i = 0; i < length; i++) {
      result = (result << 4) | uuidHexValue(value.charAt(offset + i));
    }
    return result;
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

  private QuotedTextView tryReadQuotedTextView() {
    skipWhitespace();
    int mark = position;
    if (position >= length() || charAt(position++) != '"') {
      throw error("Expected string");
    }
    int start = position;
    while (position < length()) {
      char ch = charAt(position++);
      if (ch == '"') {
        quotedTextView.reset(start, position - 1);
        return quotedTextView;
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
    if (length < 2) {
      return null;
    }
    char sign = value.charAt(0);
    if (sign != '+' && sign != '-') {
      return null;
    }
    int hour;
    int minute = 0;
    int second = 0;
    try {
      switch (length) {
        case 2:
          hour = value.charAt(1) - '0';
          if (hour < 0 || hour > 9) {
            return null;
          }
          break;
        case 3:
          hour = parse2(value, 1);
          break;
        case 5:
          hour = parse2(value, 1);
          minute = parse2(value, 3);
          break;
        case 6:
          if (value.charAt(3) != ':') {
            return null;
          }
          hour = parse2(value, 1);
          minute = parse2(value, 4);
          break;
        case 7:
          hour = parse2(value, 1);
          minute = parse2(value, 3);
          second = parse2(value, 5);
          break;
        case 9:
          if (value.charAt(3) != ':' || value.charAt(6) != ':') {
            return null;
          }
          hour = parse2(value, 1);
          minute = parse2(value, 4);
          second = parse2(value, 7);
          break;
        default:
          return null;
      }
      int total = hour * 3600 + minute * 60 + second;
      return ZoneOffset.ofTotalSeconds(sign == '-' ? -total : total);
    } catch (RuntimeException e) {
      return null;
    }
  }

  protected final LocalDate readIsoLocalDateFallback(CharSequence value) {
    try {
      int length = value.length();
      if (length >= 10
          && (length == 10 || value.charAt(10) == 'T')
          && value.charAt(4) == '-'
          && value.charAt(7) == '-') {
        try {
          return LocalDate.of(parse4(value, 0), parse2(value, 5), parse2(value, 8));
        } catch (RuntimeException e) {
          return LocalDate.parse(value);
        }
      }
      return LocalDate.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.LocalDate", e);
    }
  }

  protected final OffsetDateTime readIsoOffsetDateTimeFallback(CharSequence value) {
    try {
      return OffsetDateTime.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.OffsetDateTime", e);
    }
  }

  private LocalTime parseLocalTimeValue(CharSequence value) {
    try {
      LocalTime time = parseIsoTime(value, 0, value.length());
      return time != null ? time : LocalTime.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.LocalTime", e);
    }
  }

  private LocalDateTime parseLocalDateTimeValue(CharSequence value) {
    try {
      LocalDateTime dateTime = parseIsoDateTime(value, value.length());
      if (dateTime != null) {
        return dateTime;
      }
      return LocalDateTime.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.LocalDateTime", e);
    }
  }

  private Instant parseInstantValue(CharSequence value) {
    try {
      int end = value.length() - 1;
      if (end >= 19 && value.charAt(end) == 'Z') {
        LocalDateTime dateTime = parseIsoDateTime(value, end);
        if (dateTime != null) {
          return dateTime.toInstant(ZoneOffset.UTC);
        }
      }
      return Instant.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.Instant", e);
    }
  }

  private Duration parseDurationValue(CharSequence value) {
    try {
      Duration duration = parseIsoDuration(value);
      if (duration != null) {
        return duration;
      }
      return Duration.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.Duration", e);
    }
  }

  private ZonedDateTime parseZonedDateTimeValue(CharSequence value) {
    try {
      int end = value.length();
      int zoneStart = indexOf(value, '[', 16);
      int offsetEnd = zoneStart < 0 ? end : zoneStart;
      int offsetStart = isoOffsetStart(value, 16, offsetEnd);
      if (offsetStart >= 0 && (zoneStart < 0 || value.charAt(end - 1) == ']')) {
        ZoneOffset offset = parseIsoOffset(value, offsetStart, offsetEnd);
        LocalDateTime dateTime = parseIsoDateTime(value, offsetStart);
        if (offset != null && dateTime != null) {
          ZoneId zone =
              zoneStart < 0
                  ? offset
                  : ZoneId.of(value.subSequence(zoneStart + 1, end - 1).toString());
          // ISO parsing resolves an explicit offset to an instant before applying the region's
          // rules, including when the supplied local time falls in a gap or uses another offset.
          // JDK 8's generic parser can discard that explicit offset; preserve the encoded instant.
          return ZonedDateTime.ofInstant(dateTime, offset, zone);
        }
      }
      return ZonedDateTime.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.ZonedDateTime", e);
    }
  }

  private Year parseYearText(CharSequence value) {
    try {
      return parseYearValue(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.Year", e);
    }
  }

  private YearMonth parseYearMonthValue(CharSequence value) {
    try {
      if (value.length() == 7 && value.charAt(4) == '-') {
        return YearMonth.of(parse4(value, 0), parse2(value, 5));
      }
      return YearMonth.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.YearMonth", e);
    }
  }

  private MonthDay parseMonthDayValue(CharSequence value) {
    try {
      if (value.length() == 7
          && value.charAt(0) == '-'
          && value.charAt(1) == '-'
          && value.charAt(4) == '-') {
        return MonthDay.of(parse2(value, 2), parse2(value, 5));
      }
      return MonthDay.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.MonthDay", e);
    }
  }

  private Period parsePeriodValue(CharSequence value) {
    try {
      Period period = parseIsoPeriod(value);
      if (period != null) {
        return period;
      }
      return Period.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.Period", e);
    }
  }

  private OffsetTime parseOffsetTimeValue(CharSequence value) {
    try {
      int end = value.length();
      int offsetStart = isoOffsetStart(value, 5, end);
      if (offsetStart >= 0) {
        ZoneOffset offset = parseIsoOffset(value, offsetStart, end);
        LocalTime time = parseIsoTime(value, 0, offsetStart);
        if (offset != null && time != null) {
          return OffsetTime.of(time, offset);
        }
      }
      return OffsetTime.parse(value);
    } catch (RuntimeException e) {
      throw invalidStringValue("java.time.OffsetTime", e);
    }
  }

  private static LocalDateTime parseIsoDateTime(CharSequence value, int end) {
    if (end < 16 || value.charAt(4) != '-' || value.charAt(7) != '-' || value.charAt(10) != 'T') {
      return null;
    }
    LocalTime time = parseIsoTime(value, 11, end);
    if (time == null) {
      return null;
    }
    LocalDate date = LocalDate.of(parse4(value, 0), parse2(value, 5), parse2(value, 8));
    return LocalDateTime.of(date, time);
  }

  private static LocalTime parseIsoTime(CharSequence value, int start, int end) {
    int length = end - start;
    if (length < 5 || value.charAt(start + 2) != ':') {
      return null;
    }
    int hour = parse2(value, start);
    int minute = parse2(value, start + 3);
    int second = 0;
    int nano = 0;
    if (length != 5) {
      if (length < 8 || value.charAt(start + 5) != ':') {
        return null;
      }
      second = parse2(value, start + 6);
      if (length != 8) {
        if (length > 18 || value.charAt(start + 8) != '.') {
          return null;
        }
        for (int i = start + 9; i < end; i++) {
          int digit = value.charAt(i) - '0';
          if (digit < 0 || digit > 9) {
            return null;
          }
          nano = nano * 10 + digit;
        }
        nano *= (int) LONG_POWERS_OF_TEN[18 - length];
      }
    }
    // ISO_INSTANT additionally accepts leap seconds and midnight at 24:00. Its JDK parser owns
    // those uncommon forms; normal LocalTime construction cannot represent them.
    if (hour > 23 || minute > 59 || second > 59) {
      return null;
    }
    return LocalTime.of(hour, minute, second, nano);
  }

  private static int isoOffsetStart(CharSequence value, int start, int end) {
    for (int i = start; i < end; i++) {
      char ch = value.charAt(i);
      if (ch == 'Z' || ch == '+' || ch == '-') {
        return i;
      }
    }
    return -1;
  }

  private static ZoneOffset parseIsoOffset(CharSequence value, int start, int end) {
    int length = end - start;
    if (value.charAt(start) == 'Z') {
      return length == 1 ? ZoneOffset.UTC : null;
    }
    if ((length != 6 && length != 9) || value.charAt(start + 3) != ':') {
      return null;
    }
    int hour = parse2(value, start + 1);
    int minute = parse2(value, start + 4);
    int second = 0;
    if (length == 9) {
      if (value.charAt(start + 6) != ':') {
        return null;
      }
      second = parse2(value, start + 7);
    }
    if (minute > 59 || second > 59) {
      return null;
    }
    int seconds = hour * 3600 + minute * 60 + second;
    return ZoneOffset.ofTotalSeconds(value.charAt(start) == '-' ? -seconds : seconds);
  }

  private static Duration parseIsoDuration(CharSequence value) {
    int length = value.length();
    if (length < 4 || value.charAt(0) != 'P' || value.charAt(1) != 'T') {
      return null;
    }
    int offset = 2;
    int previousUnit = 0;
    long seconds = 0;
    int nanos = 0;
    while (offset < length) {
      boolean negative = value.charAt(offset) == '-';
      if (negative) {
        offset++;
      }
      int start = offset;
      long component = 0;
      while (offset < length) {
        int digit = value.charAt(offset) - '0';
        if (digit < 0 || digit > 9) {
          break;
        }
        component = Math.subtractExact(Math.multiplyExact(component, 10L), digit);
        offset++;
      }
      if (offset == start || offset == length) {
        return null;
      }
      if (!negative) {
        component = Math.negateExact(component);
      }
      char suffix = value.charAt(offset++);
      if (suffix == '.') {
        int multiplier = 100_000_000;
        while (offset < length) {
          int digit = value.charAt(offset) - '0';
          if (digit < 0 || digit > 9) {
            break;
          }
          if (multiplier == 0) {
            return null;
          }
          nanos += digit * multiplier;
          multiplier /= 10;
          offset++;
        }
        if (offset == length || value.charAt(offset++) != 'S') {
          return null;
        }
        // Keep the lexical sign for -0.fraction; JDK 8 loses it when parsing the integer zero.
        if (negative) {
          nanos = -nanos;
        }
        suffix = 'S';
      }
      int unit;
      int factor;
      if (suffix == 'H') {
        unit = 1;
        factor = 3600;
      } else if (suffix == 'M') {
        unit = 2;
        factor = 60;
      } else if (suffix == 'S') {
        unit = 3;
        factor = 1;
      } else {
        return null;
      }
      if (unit <= previousUnit) {
        return null;
      }
      previousUnit = unit;
      seconds = Math.addExact(seconds, Math.multiplyExact(component, (long) factor));
    }
    return Duration.ofSeconds(seconds, nanos);
  }

  private static Period parseIsoPeriod(CharSequence value) {
    int length = value.length();
    if (length < 3 || value.charAt(0) != 'P') {
      return null;
    }
    int offset = 1;
    int previousUnit = 0;
    int years = 0;
    int months = 0;
    int days = 0;
    while (offset < length) {
      boolean negative = value.charAt(offset) == '-';
      if (negative) {
        offset++;
      }
      int start = offset;
      long component = 0;
      while (offset < length) {
        int digit = value.charAt(offset) - '0';
        if (digit < 0 || digit > 9) {
          break;
        }
        component = Math.addExact(Math.multiplyExact(component, 10L), digit);
        offset++;
      }
      if (offset == start || offset == length) {
        return null;
      }
      int amount = Math.toIntExact(negative ? -component : component);
      char suffix = value.charAt(offset++);
      int unit;
      if (suffix == 'Y') {
        unit = 1;
        years = amount;
      } else if (suffix == 'M') {
        unit = 2;
        months = amount;
      } else if (suffix == 'D') {
        unit = 3;
        days = amount;
      } else {
        return null;
      }
      if (unit <= previousUnit) {
        return null;
      }
      previousUnit = unit;
    }
    return Period.of(years, months, days);
  }

  private ForyJsonException invalidStringValue(String type, RuntimeException e) {
    return new ForyJsonException(
        "Invalid " + type + " JSON string at JSON position " + position, e);
  }

  private ForyJsonException invalidStringValue(String type) {
    return new ForyJsonException("Invalid " + type + " JSON string at JSON position " + position);
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

  private byte[] bigNumberBytes(String number) {
    if (StringSerializer.isBytesBackedString() && StringSerializer.getStringCoder(number) == 0) {
      return StringSerializer.getStringBytes(number);
    }
    // Bound the transcoding allocation on JDK8 or when compact strings are disabled.
    if (number.length() > MAX_BIG_NUMBER_LENGTH) {
      throwBigNumberLengthExceeded(position);
    }
    return number.getBytes(StandardCharsets.ISO_8859_1);
  }

  final BigInteger parseBigInteger(String number) {
    byte[] bytes = bigNumberBytes(number);
    return parseBigInteger(bytes, 0, bytes.length, 0, 0);
  }

  final BigInteger parseBigInteger(byte[] bytes, int start, int end, long prefix, int prefixEnd) {
    // UTF8 callers lend a span already validated within the input limit. The converter does not
    // retain it; the resulting BigInteger owns its magnitude storage.
    if (end - start > MAX_BIG_NUMBER_LENGTH) {
      throwBigNumberLengthExceeded(position);
    }
    // Callers have validated ASCII integer or coefficient syntax. Accumulate eighteen digits at a
    // time in unsigned 64-bit words; each multiplication produces an exact high half and one carry.
    // The length gate above bounds scratch storage by the text already proven readable.
    boolean negative = bytes[start] == '-';
    int offset = start + (negative ? 1 : 0);
    int digits = end - offset;
    int capacity = (digits + 17) / 18;
    long[] words = numericWorkspace.bigIntegerBuffer;
    if (words.length < capacity) {
      words = new long[capacity];
      numericWorkspace.bigIntegerBuffer = words;
    }
    int firstEnd;
    int wordCount = 1;
    if (prefixEnd > offset) {
      // The UTF-8 integer reader already parsed this unsigned prefix, including abs(MIN_VALUE).
      // Append the short suffix group first so the remaining loop keeps its fixed 18-digit stride.
      words[0] = prefix;
      firstEnd = prefixEnd;
      int count = (end - firstEnd) % 18;
      if (count != 0) {
        long multiplier = LONG_POWERS_OF_TEN[count];
        long product = prefix * multiplier;
        long high = DecimalMath.unsignedMultiplyHigh(prefix, multiplier);
        long sum = product + parseDecimalChunk(bytes, firstEnd, firstEnd + count);
        words[0] = sum;
        high += Long.compareUnsigned(sum, product) < 0 ? 1 : 0;
        if (high != 0) {
          words[wordCount++] = high;
        }
        firstEnd += count;
      }
    } else {
      firstEnd = offset + (digits - 1) % 18 + 1;
      words[0] = parseDecimalChunk(bytes, offset, firstEnd);
    }
    for (offset = firstEnd; offset < end; offset += 18) {
      long carry = parseDecimalChunk(bytes, offset, offset + 18);
      for (int i = 0; i < wordCount; i++) {
        long word = words[i];
        long product = word * 1_000_000_000_000_000_000L;
        long high = DecimalMath.unsignedMultiplyHigh(word, 1_000_000_000_000_000_000L);
        long sum = product + carry;
        words[i] = sum;
        carry = high + (Long.compareUnsigned(sum, product) < 0 ? 1 : 0);
      }
      if (carry != 0) {
        words[wordCount++] = carry;
      }
    }
    long highWord = words[wordCount - 1];
    if (wordCount == 1 && highWord >= 0) {
      return BigInteger.valueOf(negative ? -highWord : highWord);
    }
    if (BIG_INTEGER_CONSTRUCTOR != null) {
      int leadingInts = Long.numberOfLeadingZeros(highWord) >>> 5;
      // The constructor retains this canonical big-endian magnitude. Never lend the reusable
      // numeric workspace: later reads must not mutate a previously returned BigInteger.
      int[] magnitude = new int[wordCount * 2 - leadingInts];
      if (leadingInts == 0) {
        magnitude[0] = (int) (highWord >>> 32);
      }
      magnitude[1 - leadingInts] = (int) highWord;
      for (int i = 0; i < wordCount - 1; i++) {
        long word = words[i];
        int index = magnitude.length - (i + 1) * 2;
        magnitude[index] = (int) (word >>> 32);
        magnitude[index + 1] = (int) word;
      }
      return bigInteger(magnitude, negative ? -1 : 1);
    }
    int leadingBytes = Long.numberOfLeadingZeros(highWord) >>> 3;
    byte[] magnitude = new byte[wordCount * Long.BYTES - leadingBytes];
    // A non-compact magnitude has at least eight bytes. Emit its partial high word first; the
    // following full-word stores overwrite any padding, so no leading-zero byte scan is needed.
    LittleEndian.putInt64(magnitude, 0, Long.reverseBytes(highWord) >>> (leadingBytes << 3));
    for (int i = 0; i < wordCount - 1; i++) {
      LittleEndian.putInt64(
          magnitude, magnitude.length - (i + 1) * Long.BYTES, Long.reverseBytes(words[i]));
    }
    return new BigInteger(negative ? -1 : 1, magnitude);
  }

  private static BigInteger bigInteger(int[] magnitude, int signum) {
    try {
      return (BigInteger) BIG_INTEGER_CONSTRUCTOR.invokeExact(magnitude, signum);
    } catch (ThreadDeath e) {
      throw e;
    } catch (VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot construct JSON integer magnitude", e);
    }
  }

  private static MethodHandle bigIntegerConstructor() {
    if (AndroidSupport.IS_ANDROID || GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return null;
    }
    try {
      return _JDKAccess._trustedLookup(BigInteger.class)
          .findConstructor(
              BigInteger.class, MethodType.methodType(void.class, int[].class, int.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      return null;
    }
  }

  private static long parseDecimalChunk(byte[] bytes, int start, int end) {
    long value = 0;
    while (end - start >= 8) {
      // The complete token has already passed ASCII digit validation.
      long digits = LittleEndian.getInt64(bytes, start) - 0x3030_3030_3030_3030L;
      // Each decimal pair is below 100, so the packed product has no byte carries.
      long pairs = ((digits * (10 * 256 + 1)) >>> 8) & 0x00ff_00ff_00ff_00ffL;
      long groups = (pairs * 100 + (pairs >>> 16)) & 0x0000_ffff_0000_ffffL;
      long block = (groups & 0xffff) * 10_000 + (groups >>> 32);
      value = value * 100_000_000 + block;
      start += 8;
    }
    for (int i = start; i < end; i++) {
      value = value * 10 + bytes[i] - '0';
    }
    return value;
  }

  final void throwBigDecimalScaleExceeded() {
    throw error("JSON big decimal scale " + MAX_BIG_DECIMAL_SCALE + " exceeded");
  }

  final void throwBigNumberLengthExceeded(int offset) {
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
    if (unscaled == 0 || scale == 0) {
      double value = (double) unscaled;
      return negative ? -value : value;
    }
    long bits = tryCompactDoubleBits(unscaled, scale);
    if (bits == 0) {
      long divisor = LONG_POWERS_OF_TEN[scale];
      double estimate = (double) unscaled / (double) divisor;
      bits = correctCompactDouble(unscaled, divisor, Double.doubleToRawLongBits(estimate));
    }
    if (negative) {
      bits |= DOUBLE_SIGN_BIT;
    }
    return Double.longBitsToDouble(bits);
  }

  // The caller supplies a nonzero positive long and scale 0..18. A zero result is therefore not a
  // value; it asks the caller to use the exact midpoint path when the high product cannot prove the
  // rounding direction. The common path is the Eisel-Lemire decimal conversion specialized to
  // this bounded compact domain.
  private static long tryCompactDoubleBits(long unscaled, int scale) {
    int leadingZeros = Long.numberOfLeadingZeros(unscaled);
    long upper =
        DecimalMath.unsignedMultiplyHigh(
            unscaled << leadingZeros, COMPACT_DECIMAL_MANTISSAS[scale]);
    int upperBit = (int) (upper >>> 63);
    long mantissa = upper >>> (upperBit + 9);
    leadingZeros += 1 ^ upperBit;
    long roundBits = upper & 0x1ff;
    if (roundBits == 0x1ff || (roundBits == 0 && (mantissa & 3) == 1)) {
      return 0;
    }
    mantissa = (mantissa + 1) >>> 1;
    if (mantissa >= (1L << (DOUBLE_FRACTION_BITS + 1))) {
      mantissa = 1L << DOUBLE_FRACTION_BITS;
      leadingZeros--;
    }
    mantissa &= DOUBLE_FRACTION_MASK;
    long exponent = ((217706L * -scale) >> 16) + 1023 + 64 - leadingZeros;
    if (exponent < 1 || exponent > 2046) {
      return 0;
    }
    return mantissa | (exponent << DOUBLE_FRACTION_BITS);
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
    if (unscaled == 0 || scale == 0) {
      float value = (float) unscaled;
      return negative ? -value : value;
    }
    int converted = tryCompactFloatBits(unscaled, scale);
    if (converted >= 0) {
      return Float.intBitsToFloat(converted | (negative ? FLOAT_SIGN_BIT : 0));
    }
    long divisor = LONG_POWERS_OF_TEN[scale];
    float estimate = (float) unscaled / (float) divisor;
    int bits = correctCompactFloat(unscaled, divisor, Float.floatToRawIntBits(estimate));
    if (negative) {
      bits |= FLOAT_SIGN_BIT;
    }
    return Float.intBitsToFloat(bits);
  }

  private static int tryCompactFloatBits(long unscaled, int scale) {
    int shift = Long.numberOfLeadingZeros(unscaled);
    long high =
        DecimalMath.unsignedMultiplyHigh(unscaled << shift, COMPACT_DECIMAL_MANTISSAS[scale]);
    int upperBit = (int) (high >>> 63);
    int discardedBits = 39 + upperBit;
    long remainder = high & ((1L << discardedBits) - 1);
    long halfway = 1L << (discardedBits - 1);
    // A positive long divided by 10^scale (0..18) is normal and finite. As in decimalToBinary,
    // the exact high product lies in [high, high + 2); only a midpoint overlap needs correction.
    // Round at float precision here: converting through double can round a midpoint twice.
    if (remainder <= halfway && remainder + 2 >= halfway) {
      return -1;
    }
    int significand = (int) (high >>> discardedBits) + (remainder > halfway ? 1 : 0);
    int exponent = (int) ((-scale * 217706L) >> 16) + 63 + upperBit - shift;
    if (significand == (1 << (FLOAT_FRACTION_BITS + 1))) {
      significand >>>= 1;
      exponent++;
    }
    return ((exponent + 127) << FLOAT_FRACTION_BITS) | (significand & FLOAT_FRACTION_MASK);
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
    long productHigh = DecimalMath.unsignedMultiplyHigh(divisor, numerator);
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

  // Validate JSON grammar before JDK conversion: its accepted syntax also includes non-JSON
  // numbers. Materializing the uncommon token avoids the much greater cost of constructing
  // decimal midpoint boundaries, while preserving subnormals and exact halfway rounding.
  protected final double readDoubleFallbackValue(int start) {
    position = start;
    scanNumberToken();
    return Double.parseDouble(floatingToken(start, position));
  }

  protected final double readDoubleExponentValue(
      boolean negative, long unscaled, int scale, int start, int exponentOffset) {
    long adjustedScale = readExponentScale(exponentOffset, scale);
    return readScannedDoubleValue(negative, unscaled, adjustedScale, start, position);
  }

  protected final double readScannedDoubleValue(
      boolean negative, long unscaled, long scale, int start, int end) {
    if (unscaled == 0) {
      return negative ? -0.0d : 0.0d;
    }
    if (scale >= 0 && scale <= COMPACT_DECIMAL_MAX_SCALE) {
      return compactDoubleValue(negative, unscaled, (int) scale);
    }
    if (scale < 0 && scale >= -COMPACT_DECIMAL_MAX_SCALE) {
      long multiplied = multiplyByPowerOfTen(unscaled, (int) -scale);
      if (multiplied >= 0) {
        return compactDoubleValue(negative, multiplied, 0);
      }
    }
    long bits = decimalToBinary(unscaled, scale, DOUBLE_FRACTION_BITS, -1022, 1023);
    if (bits < 0) {
      return Double.parseDouble(floatingToken(start, end));
    }
    return Double.longBitsToDouble(bits | (negative ? DOUBLE_SIGN_BIT : 0));
  }

  protected final float readFloatExponentValue(
      boolean negative, long unscaled, int scale, int start, int exponentOffset) {
    long adjustedScale = readExponentScale(exponentOffset, scale);
    return readScannedFloatValue(negative, unscaled, adjustedScale, start, position);
  }

  protected final float readScannedFloatValue(
      boolean negative, long unscaled, long scale, int start, int end) {
    if (unscaled == 0) {
      return negative ? -0.0f : 0.0f;
    }
    if (scale >= 0 && scale <= COMPACT_DECIMAL_MAX_SCALE) {
      return compactFloatValue(negative, unscaled, (int) scale);
    }
    if (scale < 0 && scale >= -COMPACT_DECIMAL_MAX_SCALE) {
      long multiplied = multiplyByPowerOfTen(unscaled, (int) -scale);
      if (multiplied >= 0) {
        return compactFloatValue(negative, multiplied, 0);
      }
    }
    long bits = decimalToBinary(unscaled, scale, FLOAT_FRACTION_BITS, -126, 127);
    if (bits < 0) {
      // Parsing as double first would round some halfway values twice.
      return Float.parseFloat(floatingToken(start, end));
    }
    return Float.intBitsToFloat((int) bits | (negative ? FLOAT_SIGN_BIT : 0));
  }

  private static long decimalToBinary(
      long unscaled, long scale, int fractionBits, int minExponent, int maxExponent) {
    if (scale > 342) {
      return 0;
    }
    if (scale < -308) {
      return (long) (maxExponent - minExponent + 2) << fractionBits;
    }
    int decimalExponent = (int) -scale;
    int shift = Long.numberOfLeadingZeros(unscaled);
    long high =
        DecimalMath.unsignedMultiplyHigh(
            unscaled << shift, DecimalPowers.MANTISSAS[decimalExponent + 342]);
    int binaryExponent = (int) ((decimalExponent * 217706L) >> 16) + 1 - shift;
    int leadingZeros = Long.numberOfLeadingZeros(high);
    int exponent = 63 - leadingZeros + binaryExponent;
    int discardedBits =
        Math.max(63 - leadingZeros - fractionBits, minExponent - fractionBits - binaryExponent);
    if (discardedBits > 64) {
      return 0;
    }
    // The table rounds its normalized power down by less than one, and the high product drops
    // less than one more unit. The exact value is therefore in [high, high + 2) * 2^binaryExponent.
    // Return a result only if that whole interval lies on the same side of the rounding midpoint.
    // A normalized positive signed-long significand is at most unsigned -2, so high + 2 cannot
    // wrap.
    if (discardedBits == 64) {
      if (Long.compareUnsigned(high, Long.MIN_VALUE) > 0) {
        return 1;
      }
      return Long.compareUnsigned(high + 2, Long.MIN_VALUE) < 0 ? 0 : -1;
    }
    long remainder = high & ((1L << discardedBits) - 1);
    long halfway = 1L << (discardedBits - 1);
    if (remainder <= halfway && remainder + 2 >= halfway) {
      return -1;
    }
    long significand = (high >>> discardedBits) + (remainder > halfway ? 1 : 0);
    if (exponent < minExponent) {
      return significand;
    }
    if (significand == (1L << (fractionBits + 1))) {
      significand >>>= 1;
      exponent++;
    }
    if (exponent > maxExponent) {
      return (long) (maxExponent - minExponent + 2) << fractionBits;
    }
    return ((long) (exponent - minExponent + 1) << fractionBits)
        | (significand & ((1L << fractionBits) - 1));
  }

  final long readExponentScale(int offset, long scale) {
    int inputLength = length();
    offset++;
    int ch = offset < inputLength ? charAt(offset) : -1;
    boolean negativeExponent = ch == '-';
    if (negativeExponent || ch == '+') {
      offset++;
      ch = offset < inputLength ? charAt(offset) : -1;
    }
    if (ch < '0' || ch > '9') {
      throw numberError(offset, "Expected exponent digit");
    }
    long exponent = ch - '0';
    offset++;
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
    position = offset;
    return negativeExponent ? scale + exponent : scale - exponent;
  }

  protected final float readFloatFallbackValue(int start) {
    position = start;
    scanNumberToken();
    return Float.parseFloat(floatingToken(start, position));
  }

  private String floatingToken(int start, int end) {
    if (end - start <= 1024) {
      return slice(start, end);
    }
    return normalizeFloatingToken(start, end);
  }

  private String normalizeFloatingToken(int start, int end) {
    // JDK 8 bounds the parsed exponent using significant digits, excluding leading fractional
    // zeros. Move the decimal point after removing those zeros so cancelling exponents remain
    // correct. Retain every significant digit: truncation here could change halfway rounding.
    boolean negative = charAt(start) == '-';
    int offset = negative ? start + 1 : start;
    int first = -1;
    int last = -1;
    int fractionDigits = 0;
    int trailingZeros = 0;
    boolean fraction = false;
    while (offset < end) {
      char ch = charAt(offset);
      if (ch == 'e' || ch == 'E') {
        break;
      }
      if (ch == '.') {
        fraction = true;
      } else {
        if (fraction) {
          fractionDigits++;
        }
        if (ch != '0') {
          if (first < 0) {
            first = offset;
          }
          last = offset;
          trailingZeros = 0;
        } else {
          trailingZeros++;
        }
      }
      offset++;
    }
    if (first < 0) {
      return negative ? "-0" : "0";
    }
    long scale = offset < end ? readExponentScale(offset, fractionDigits) : fractionDigits;
    StringBuilder token = new StringBuilder(last - first + 32);
    if (negative) {
      token.append('-');
    }
    for (int i = first; i <= last; i++) {
      char ch = charAt(i);
      if (ch != '.') {
        token.append(ch);
      }
    }
    return token.append('e').append((long) trailingZeros - scale).toString();
  }

  private static int floatMantissa(int bits) {
    int fraction = bits & FLOAT_FRACTION_MASK;
    return (bits & FLOAT_EXPONENT_MASK) == 0 ? fraction : fraction | (1 << FLOAT_FRACTION_BITS);
  }

  private static int floatBinaryExponent(int bits) {
    int exponent = (bits & FLOAT_EXPONENT_MASK) >>> FLOAT_FRACTION_BITS;
    return exponent == 0 ? -149 : exponent - 150;
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
    throw error("Expected quoted double");
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
    throw error("Expected quoted float");
  }

  protected final boolean isQuotedNonFiniteNumber() {
    return matchesQuotedAscii("NaN")
        || matchesQuotedAscii("Infinity")
        || matchesQuotedAscii("-Infinity");
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

  /** Reads an unsigned 32-bit decimal JSON member name without materializing a String. */
  public int readFieldNameUnsignedInt() {
    long value = readFieldNameUnsignedLong();
    if (Long.compareUnsigned(value, 0xffff_ffffL) > 0) {
      throw error("Unsigned integer field name overflow");
    }
    return (int) value;
  }

  /** Reads an unsigned 64-bit decimal JSON member name without materializing a String. */
  public long readFieldNameUnsignedLong() {
    skipWhitespace();
    int quotePosition = position;
    if (position >= length() || charAt(position++) != '"') {
      throw error("Expected unsigned integer field name");
    }
    if (position >= length()) {
      return readDecodedUnsignedFieldName(quotePosition);
    }
    char first = charAt(position);
    if (first == '0') {
      position++;
      if (position < length() && charAt(position) == '"') {
        position++;
        return 0;
      }
      return readDecodedUnsignedFieldName(quotePosition);
    }
    if (first < '1' || first > '9') {
      return readDecodedUnsignedFieldName(quotePosition);
    }
    long value = 0;
    long maxDiv10 = 1_844_674_407_370_955_161L;
    while (position < length()) {
      char ch = charAt(position);
      if (ch < '0' || ch > '9') {
        if (ch == '"') {
          position++;
          return value;
        }
        return readDecodedUnsignedFieldName(quotePosition);
      }
      int digit = ch - '0';
      int comparison = Long.compareUnsigned(value, maxDiv10);
      if (comparison > 0 || comparison == 0 && digit > 5) {
        return throwUnsignedFieldNameOverflow();
      }
      value = value * 10 + digit;
      position++;
    }
    return readDecodedUnsignedFieldName(quotePosition);
  }

  private long readDecodedUnsignedFieldName(int quotePosition) {
    // Canonical ASCII digits stay allocation-free. Escapes and other forms accepted by the
    // decoded member-name contract restore the opening quote and use the ordinary String parser.
    position = quotePosition;
    try {
      return Long.parseUnsignedLong(readString());
    } catch (NumberFormatException e) {
      throw new ForyJsonException(
          "Invalid unsigned long field name at JSON position " + position, e);
    }
  }

  private long throwUnsignedFieldNameOverflow() {
    throw error("Unsigned long field name overflow");
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
    switch (peekToken()) {
      case '"':
        // Skipped text still needs escape and Unicode validation, but no decoded storage or hash.
        position = scanStringEnd(position);
        return;
      case '{':
        skipObject();
        return;
      case '[':
        skipArray();
        return;
      case 't':
      case 'f':
        readBooleanToken();
        return;
      case 'n':
        if (!tryReadNullToken()) {
          skipNumberToken();
        }
        return;
      default:
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
    // skipValue already checked the opening delimiter.
    position++;
    if (consumeNextToken('}')) {
      exitDepth();
      return;
    }
    do {
      expectNextToken('"');
      position = scanStringEnd(position - 1);
      expectNextToken(':');
      skipValue();
    } while (consumeNextCommaOrEndObject());
    exitDepth();
  }

  private void skipArray() {
    enterDepth();
    // skipValue already checked the opening delimiter.
    position++;
    if (consumeNextToken(']')) {
      exitDepth();
      return;
    }
    do {
      skipValue();
    } while (consumeNextCommaOrEndArray());
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

  protected char readUnicodeEscape() {
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

  private static final class NumericWorkspace {
    private long[] bigIntegerBuffer = new long[4];
  }

  private static final class QuotedTextView implements CharSequence {
    private final JsonReader reader;
    private byte[] decodedBytes;
    private int start;
    private int end;
    private boolean utf16;

    QuotedTextView(JsonReader reader) {
      this.reader = reader;
    }

    void reset(int start, int end) {
      decodedBytes = null;
      this.start = start;
      this.end = end;
      utf16 = false;
    }

    void reset(byte[] bytes, int length, boolean utf16) {
      decodedBytes = bytes;
      start = 0;
      end = length;
      this.utf16 = utf16;
    }

    void clear() {
      decodedBytes = null;
      start = 0;
      end = 0;
      utf16 = false;
    }

    @Override
    public int length() {
      int length = end - start;
      return decodedBytes != null && utf16 ? length >>> 1 : length;
    }

    @Override
    public char charAt(int index) {
      int length = length();
      if (index < 0 || index >= length) {
        throw new IndexOutOfBoundsException();
      }
      byte[] bytes = decodedBytes;
      if (bytes == null) {
        return reader.charAt(start + index);
      }
      if (!utf16) {
        return (char) (bytes[start + index] & 0xff);
      }
      int offset = start + (index << 1);
      if (NativeByteOrder.IS_LITTLE_ENDIAN) {
        return (char) ((bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8));
      }
      return (char) (((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff));
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return toString().subSequence(start, end);
    }

    @Override
    public String toString() {
      if (decodedBytes == null) {
        return reader.slice(start, end);
      }
      int length = length();
      StringBuilder builder = new StringBuilder(length);
      for (int i = 0; i < length; i++) {
        builder.append(charAt(i));
      }
      return builder.toString();
    }
  }
}
