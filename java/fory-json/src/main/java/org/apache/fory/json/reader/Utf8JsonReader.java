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
import java.util.UUID;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.JsonConfig;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldNameHash;
import org.apache.fory.json.meta.JsonFieldTable;
import org.apache.fory.json.meta.JsonSubtypeScanInfo;
import org.apache.fory.json.resolver.JsonSharedRegistry;
import org.apache.fory.json.resolver.JsonSharedRegistry.CachedFieldName;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.memory.LittleEndian;
import org.apache.fory.memory.NativeByteOrder;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.platform.internal._JDKAccess;
import org.apache.fory.serializer.StringSerializer;

/**
 * JSON reader for borrowed UTF-8 byte arrays.
 *
 * <p>ASCII syntax, field-name probes, and primitive numbers operate directly on bytes. Unicode
 * string and field-name paths decode and validate UTF-8, including continuation bytes, overlong
 * forms, surrogate encodings, and the Unicode code-point range. Returned Strings own their storage
 * and never retain the input or reusable decode buffer.
 *
 * <p>This concrete owner implements UTF-8 token probes, packed digit parsing, string decoding, and
 * field hashing. {@link #clear()} releases the input and bounds the retained decode workspace
 * before the owning pooled state is reused.
 */
public final class Utf8JsonReader extends JsonReader {
  private static final byte[] EMPTY_BYTES = new byte[0];
  private static final MethodHandle INSTANT_FACTORY = instantFactory();
  private static final MethodHandle LOCAL_TIME_FACTORY = localTimeFactory();
  private static final MethodHandle LOCAL_DATE_FACTORY = localDateFactory();
  private static final MethodHandle YEAR_MONTH_CONSTRUCTOR = yearMonthConstructor();
  private static final MethodHandle MONTH_DAY_CONSTRUCTOR = monthDayConstructor();
  private static final MethodHandle ZONED_DATE_TIME_CONSTRUCTOR = zonedDateTimeConstructor();
  private static final int[] NANO_SCALE = {
    1, 10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000, 1000000000
  };
  private static final int INITIAL_STRING_DECODE_BUFFER_SIZE = 1024;
  private static final int RETAINED_STRING_DECODE_BUFFER_SIZE = 8192;
  private static final boolean LITTLE_ENDIAN = NativeByteOrder.IS_LITTLE_ENDIAN;
  private static final long BYTE_ONES = 0x0101010101010101L;
  private static final int INT_BYTE_ONES = 0x01010101;
  private static final long BYTE_TWOS = 0x0202020202020202L;
  private static final int INT_BYTE_TWOS = 0x02020202;
  private static final long BYTE_HIGH_BITS = 0x8080808080808080L;
  private static final int INT_BYTE_HIGH_BITS = 0x80808080;
  private static final long BACKSLASH_BYTES = 0x5c5c5c5c5c5c5c5cL;
  private static final int INT_BACKSLASH_BYTES = 0x5c5c5c5c;
  private static final long QUOTE_CONTROL_LIMIT_BYTES = 0x2121212121212121L;
  private static final int INT_QUOTE_CONTROL_LIMIT_BYTES = 0x21212121;
  private static final int INT_MAX_DIV_10 = Integer.MAX_VALUE / 10;
  private static final int INT_MAX_MOD_10 = Integer.MAX_VALUE % 10;
  private static final long LONG_MAX_DIV_10 = Long.MAX_VALUE / 10;
  private static final int LONG_MAX_MOD_10 = (int) (Long.MAX_VALUE % 10);
  private static final long LONG_MAX_DIV_100 = Long.MAX_VALUE / 100;
  private static final int LONG_MAX_MOD_100 = (int) (Long.MAX_VALUE % 100);
  private static final long FOUR_DIGITS = 10_000L;
  private static final long LONG_MAX_DIV_FOUR_DIGITS = Long.MAX_VALUE / FOUR_DIGITS;
  private static final long LONG_MIN_DIV_10 = Long.MIN_VALUE / 10;
  private static final int LONG_MIN_LAST_DIGIT = (int) -(Long.MIN_VALUE % 10);
  private static final long EIGHT_DIGITS = 100_000_000L;
  private static final long LONG_MAX_DIV_EIGHT_DIGITS = Long.MAX_VALUE / EIGHT_DIGITS;
  private static final int LONG_MAX_MOD_EIGHT_DIGITS = (int) (Long.MAX_VALUE % EIGHT_DIGITS);
  private static final long ASCII_ZEROES = 0x3030_3030_3030_3030L;
  private static final long ASCII_NINES = 0x3939_3939_3939_3939L;
  private static final long ASCII_HIGH_BITS = 0x8080_8080_8080_8080L;
  // Little-endian packed ASCII bytes for the fixed JSON literals.
  private static final int NULL_LITERAL = 0x6C6C756E;
  private static final int TRUE_LITERAL = 0x65757274;
  private static final int FALSE_PREFIX = 0x736C6166;

  /** The generated String-array loop consumed the closing bracket. */
  @Internal public static final int STRING_ARRAY_END = 0;

  /** The generated String-array loop consumed both a comma and the next opening quote. */
  @Internal public static final int STRING_ARRAY_QUOTED = 1;

  /** The generated String-array loop consumed a comma and left the next value unread. */
  @Internal public static final int STRING_ARRAY_VALUE = 2;

  // JSON syntax bytes are ASCII, so hot token checks can compare signed bytes directly.
  // UTF-8 string decoding must keep unsigned byte conversion for non-ASCII content.
  private byte[] input;
  private int inputLimit;
  private byte[] stringDecodeBuffer = new byte[INITIAL_STRING_DECODE_BUFFER_SIZE];
  // Keep the cache after hot representation fields; an inherited reference shifts their offsets.
  private final FieldNameCache fieldNameCache;

  public Utf8JsonReader(JsonConfig config, JsonTypeResolver typeResolver) {
    super(config, typeResolver);
    input = EMPTY_BYTES;
    inputLimit = 0;
    // The configured limit belongs to each reader; pooled-state concurrency must not divide it.
    int maxEntries = config.maxCachedFieldNames();
    fieldNameCache = maxEntries == 0 ? null : new FieldNameCache(maxEntries);
  }

  @Override
  protected int scanStringEnd(int start) {
    int inputLimit = this.inputLimit;
    if (start >= inputLimit || input[start] != '"') {
      throw errorAt("Expected string", start);
    }
    int cursor = start + 1;
    int wordEnd = inputLimit - Long.BYTES;
    while (cursor <= wordEnd) {
      long stopMask = stringStopMask(LittleEndian.getInt64(input, cursor));
      if (stopMask == 0) {
        cursor += Long.BYTES;
        continue;
      }
      cursor += Long.numberOfTrailingZeros(stopMask) >>> 3;
      int raw = input[cursor] & 0xff;
      if (raw == '"') {
        return cursor + 1;
      }
      if (raw < 0x20) {
        throw errorAt("Control character in string", cursor);
      }
      if (raw == '\\') {
        cursor = scanEscape(cursor, inputLimit);
      } else {
        cursor = (int) (scanUtf8CodePoint(cursor) >>> 32);
      }
    }
    while (cursor < inputLimit) {
      int raw = input[cursor] & 0xff;
      if (raw == '"') {
        return cursor + 1;
      }
      if (raw < 0x20) {
        throw errorAt("Control character in string", cursor);
      }
      if (raw == '\\') {
        cursor = scanEscape(cursor, inputLimit);
      } else if (raw < 0x80) {
        cursor++;
      } else {
        cursor = (int) (scanUtf8CodePoint(cursor) >>> 32);
      }
    }
    throw errorAt("Unterminated string", cursor);
  }

  @Override
  protected long scanStringHash(int start, int end) {
    long hash = JsonFieldNameHash.MAGIC_HASH_CODE;
    long value = 0;
    int decodedLength = 0;
    boolean latin1 = true;
    int cursor = start + 1;
    int limit = end - 1;
    while (cursor < limit) {
      int raw = input[cursor] & 0xff;
      int codePoint;
      if (raw == '\\') {
        int escaped = input[cursor + 1] & 0xff;
        cursor += 2;
        if (escaped == 'u') {
          codePoint = scanUnicodeEscape(cursor);
          cursor += 4;
        } else {
          codePoint = scanSimpleEscape(escaped, cursor - 1);
        }
      } else if (raw < 0x80) {
        codePoint = raw;
        cursor++;
      } else {
        long decoded = scanUtf8CodePoint(cursor);
        cursor = (int) (decoded >>> 32);
        codePoint = (int) decoded;
      }
      if (codePoint <= 0xffff && Character.isHighSurrogate((char) codePoint)) {
        if (latin1) {
          hash = JsonFieldNameHash.hashPacked(value, decodedLength);
          latin1 = false;
        }
        hash = JsonFieldNameHash.update(hash, (char) codePoint);
        decodedLength++;
        cursor += 2;
        char low = scanUnicodeEscape(cursor);
        cursor += 4;
        hash = JsonFieldNameHash.update(hash, low);
        decodedLength++;
      } else if (codePoint <= 0xffff) {
        char ch = (char) codePoint;
        if (latin1 && ch <= 0xff && ch != 0 && decodedLength < Long.BYTES) {
          value = JsonFieldNameHash.value(value, decodedLength++, ch);
        } else {
          if (latin1) {
            hash = JsonFieldNameHash.hashPacked(value, decodedLength);
            latin1 = false;
          }
          hash = JsonFieldNameHash.update(hash, ch);
          decodedLength++;
        }
      } else {
        if (latin1) {
          hash = JsonFieldNameHash.hashPacked(value, decodedLength);
          latin1 = false;
        }
        hash = JsonFieldNameHash.update(hash, Character.highSurrogate(codePoint));
        hash = JsonFieldNameHash.update(hash, Character.lowSurrogate(codePoint));
        decodedLength += 2;
      }
    }
    return JsonFieldNameHash.finish(hash, value, decodedLength, latin1);
  }

  @Override
  protected boolean matchesScannedString(int start, int end, String expected) {
    int cursor = start + 1;
    int limit = end - 1;
    int index = 0;
    boolean matches = true;
    while (cursor < limit) {
      int raw = input[cursor] & 0xff;
      if (raw == '\\') {
        int escapedByte = input[cursor + 1] & 0xff;
        cursor += 2;
        char escaped;
        if (escapedByte == 'u') {
          escaped = scanUnicodeEscape(cursor);
          cursor += 4;
        } else {
          escaped = scanSimpleEscape(escapedByte, cursor - 1);
        }
        matches &= index < expected.length() && expected.charAt(index++) == escaped;
        if (Character.isHighSurrogate(escaped)) {
          cursor += 2;
          char low = scanUnicodeEscape(cursor);
          cursor += 4;
          matches &= index < expected.length() && expected.charAt(index++) == low;
        }
        continue;
      }
      int codePoint;
      if (raw < 0x80) {
        codePoint = raw;
        cursor++;
      } else {
        long decoded = scanUtf8CodePoint(cursor);
        cursor = (int) (decoded >>> 32);
        codePoint = (int) decoded;
      }
      if (codePoint <= 0xffff) {
        matches &= index < expected.length() && expected.charAt(index++) == (char) codePoint;
      } else {
        char high = Character.highSurrogate(codePoint);
        char low = Character.lowSurrogate(codePoint);
        matches &= index < expected.length() && expected.charAt(index++) == high;
        matches &= index < expected.length() && expected.charAt(index++) == low;
      }
    }
    return matches && index == expected.length();
  }

  @Override
  protected CharSequence decodeQuotedText(int start, int end) {
    byte[] outBytes = stringDecodeBuffer;
    int out = 0;
    int offset = start;
    while (offset < end) {
      int raw = input[offset++] & 0xff;
      if (raw == '\\') {
        int escaped = input[offset++] & 0xff;
        char ch;
        if (escaped == 'u') {
          ch = scanUnicodeEscape(offset);
          offset += 4;
        } else {
          ch = scanSimpleEscape(escaped, offset - 1);
        }
        if (Character.isHighSurrogate(ch)) {
          offset += 2;
          char low = scanUnicodeEscape(offset);
          offset += 4;
          outBytes = ensureStringDecodeCapacity(outBytes, out + 4);
          out = putUtf16Char(outBytes, out, ch);
          out = putUtf16Char(outBytes, out, low);
        } else {
          outBytes = ensureStringDecodeCapacity(outBytes, out + 2);
          out = putUtf16Char(outBytes, out, ch);
        }
        continue;
      }
      if (raw < 0x80) {
        outBytes = ensureStringDecodeCapacity(outBytes, out + 2);
        out = putUtf16Char(outBytes, out, (char) raw);
        continue;
      }
      long decoded = scanUtf8CodePoint(offset - 1);
      offset = (int) (decoded >>> 32);
      int codePoint = (int) decoded;
      if (codePoint <= 0xffff) {
        outBytes = ensureStringDecodeCapacity(outBytes, out + 2);
        out = putUtf16Char(outBytes, out, (char) codePoint);
      } else {
        outBytes = ensureStringDecodeCapacity(outBytes, out + 4);
        out = putUtf16Char(outBytes, out, Character.highSurrogate(codePoint));
        out = putUtf16Char(outBytes, out, Character.lowSurrogate(codePoint));
      }
    }
    return decodedQuotedText(outBytes, out, true);
  }

  private int scanEscape(int slash, int inputLimit) {
    int cursor = slash + 1;
    if (cursor >= inputLimit) {
      throw errorAt("Unterminated escape", slash);
    }
    int escaped = input[cursor++] & 0xff;
    if (escaped != 'u') {
      scanSimpleEscape(escaped, cursor - 1);
      return cursor;
    }
    char ch = scanUnicodeEscape(cursor);
    cursor += 4;
    if (Character.isHighSurrogate(ch)) {
      if (cursor + 6 > inputLimit || input[cursor] != '\\' || input[cursor + 1] != 'u') {
        throw errorAt("Unpaired high surrogate escape", slash);
      }
      char low = scanUnicodeEscape(cursor + 2);
      if (!Character.isLowSurrogate(low)) {
        throw errorAt("Unpaired high surrogate escape", slash);
      }
      return cursor + 6;
    }
    if (Character.isLowSurrogate(ch)) {
      throw errorAt("Unpaired low surrogate escape", slash);
    }
    return cursor;
  }

  private long scanUtf8CodePoint(int offset) {
    int first = input[offset] & 0xff;
    int count;
    int codePoint;
    int minimum;
    if ((first & 0xe0) == 0xc0) {
      count = 2;
      codePoint = first & 0x1f;
      minimum = 0x80;
    } else if ((first & 0xf0) == 0xe0) {
      count = 3;
      codePoint = first & 0x0f;
      minimum = 0x800;
    } else if ((first & 0xf8) == 0xf0) {
      count = 4;
      codePoint = first & 0x07;
      minimum = 0x10000;
    } else {
      throw errorAt("Invalid UTF-8 sequence", offset);
    }
    if (offset > inputLimit - count) {
      throw errorAt("Incomplete UTF-8 sequence", offset);
    }
    for (int i = 1; i < count; i++) {
      int continuation = input[offset + i] & 0xff;
      if ((continuation & 0xc0) != 0x80) {
        throw errorAt("Invalid UTF-8 continuation byte", offset + i);
      }
      codePoint = (codePoint << 6) | (continuation & 0x3f);
    }
    if (codePoint < minimum
        || codePoint > 0x10ffff
        || (codePoint >= 0xd800 && codePoint <= 0xdfff)) {
      throw errorAt("Invalid UTF-8 sequence", offset);
    }
    return ((long) (offset + count) << 32) | codePoint;
  }

  private char scanUnicodeEscape(int offset) {
    if (offset > inputLimit - 4) {
      throw errorAt("Incomplete unicode escape", offset);
    }
    int value = 0;
    for (int i = 0; i < 4; i++) {
      int ch = input[offset + i] & 0xff;
      int digit;
      if (ch >= '0' && ch <= '9') {
        digit = ch - '0';
      } else {
        int lower = ch | 0x20;
        if (lower < 'a' || lower > 'f') {
          throw errorAt("Invalid unicode escape", offset + i);
        }
        digit = lower - 'a' + 10;
      }
      value = (value << 4) | digit;
    }
    return (char) value;
  }

  private char scanSimpleEscape(int escaped, int offset) {
    switch (escaped) {
      case '"':
      case '\\':
      case '/':
        return (char) escaped;
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
      default:
        throw errorAt("Invalid escape", offset);
    }
  }

  @Override
  public int readSubtypeName(JsonSubtypeScanInfo info) {
    skipWhitespaceFast();
    int start = position;
    int candidate = info.nameIndex(readStringHash());
    int end = position;
    if (candidate < 0 || !matchesScannedString(start, end, info.name(candidate))) {
      throw error("Unknown JSON subtype name");
    }
    return candidate;
  }

  public Utf8JsonReader(JsonConfig config, JsonTypeResolver typeResolver, byte[] input) {
    this(config, typeResolver);
    reset(input);
  }

  public Utf8JsonReader reset(byte[] input) {
    this.input = input;
    inputLimit = input.length;
    position = 0;
    reset();
    return this;
  }

  /** Resets this reader to a logical range of a borrowed byte array. */
  @Internal
  public Utf8JsonReader reset(byte[] input, int offset, int length) {
    int inputLength = input.length;
    if ((offset | length) < 0 || offset > inputLength - length) {
      throwInvalidByteRange(offset, length);
    }
    this.input = input;
    inputLimit = offset + length;
    position = offset;
    reset();
    return this;
  }

  private static void throwInvalidByteRange(int offset, int length) {
    throw new IndexOutOfBoundsException(
        "Invalid UTF-8 byte range: offset=" + offset + ", length=" + length);
  }

  public void clear() {
    reset();
    input = EMPTY_BYTES;
    inputLimit = 0;
    position = 0;
    if (stringDecodeBuffer.length > RETAINED_STRING_DECODE_BUFFER_SIZE) {
      stringDecodeBuffer = new byte[RETAINED_STRING_DECODE_BUFFER_SIZE];
    }
  }

  @Override
  public char peekToken() {
    skipWhitespaceFast();
    if (position >= inputLimit) {
      throw error("Expected token");
    }
    return (char) (input[position] & 0xff);
  }

  public boolean consumeToken(char expected) {
    skipWhitespaceFast();
    if (position < inputLimit && input[position] == expected) {
      position++;
      return true;
    }
    return false;
  }

  public boolean consumeNextToken(char expected) {
    if (position < inputLimit && input[position] == expected) {
      position++;
      return true;
    }
    return consumeToken(expected);
  }

  /** Consumes a string quote without classifying whitespace, null, or malformed input. */
  @Internal
  public boolean tryConsumeStringQuote() {
    byte[] bytes = input;
    int offset = position;
    if (offset < inputLimit && bytes[offset] == '"') {
      position = offset + 1;
      return true;
    }
    return false;
  }

  public void expectToken(char expected) {
    if (!consumeToken(expected)) {
      throw error("Expected '" + expected + "'");
    }
  }

  public void expectNextToken(char expected) {
    if (position < inputLimit && input[position] == expected) {
      position++;
      return;
    }
    expectNextTokenSlow(expected);
  }

  private void expectNextTokenSlow(char expected) {
    expectToken(expected);
  }

  public boolean consumeNextCommaOrEndObject() {
    if (tryConsumeNextComma()) {
      return true;
    }
    return consumeNextObjectEndOrSlow();
  }

  /**
   * Consumes an adjacent comma without classifying an object end or malformed input.
   *
   * <p>Generated readers use this primitive directly so each schema callsite keeps its own
   * separator profile. The object-end path must not sit behind one shared, frequently inlined
   * wrapper profile: doing so copies the rare end branch into every generated field site.
   */
  @Internal
  public boolean tryConsumeNextComma() {
    if (position < inputLimit) {
      if (input[position] == ',') {
        position++;
        return true;
      }
    }
    return false;
  }

  /**
   * Consumes an adjacent comma and positions an ordered raw-token reader at its next field.
   *
   * <p>Only generated ordered creator readers need this stronger postcondition. General field loops
   * classify whitespace while reading the next name; normalizing it here as well would scan the
   * same separator twice.
   */
  @Internal
  public boolean tryConsumeNextOrderedComma() {
    if (position < inputLimit && input[position] == ',') {
      position++;
      skipWhitespaceFast();
      return true;
    }
    return false;
  }

  /**
   * Consumes an object end or a separator requiring whitespace/error classification.
   *
   * <p>This is the complement of {@link #tryConsumeNextComma()}. Generated readers call it only
   * after their local comma probe fails, preserving the final-field profile at the generated
   * callsite while the concrete reader remains the sole owner of cursor and syntax state.
   */
  @Internal
  public boolean consumeNextObjectEndOrSlow() {
    if (position < inputLimit) {
      if (input[position] == '}') {
        position++;
        return false;
      }
    }
    return consumeNextCommaOrEndObjectSlow();
  }

  @Internal
  public boolean consumeNextOrderedObjectEndOrSlow() {
    boolean hasNext = consumeNextObjectEndOrSlow();
    if (hasNext) {
      skipWhitespaceFast();
    }
    return hasNext;
  }

  private boolean consumeNextCommaOrEndObjectSlow() {
    skipWhitespaceFast();
    if (position < inputLimit) {
      int ch = input[position];
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

  // Generated collection readers inline this method at the loop back edge. Keep both common
  // separators in this owner so a still-cold end-array helper cannot reshape the whole generated
  // loop. Only whitespace, exhaustion, and malformed input belong in the cold fallback.
  public boolean consumeNextCommaOrEndArray() {
    if (position < inputLimit) {
      int ch = input[position];
      if (ch == ',') {
        position++;
        return true;
      }
      if (ch == ']') {
        position++;
        return false;
      }
    }
    return consumeNextCommaOrEndArraySlow();
  }

  /**
   * Consumes an array separator and, when adjacent, the next String's opening quote.
   *
   * <p>The concrete reader owns syntax and cursor publication. Generated exact String collections
   * own value decoding and can therefore continue directly from the returned state without a second
   * token probe.
   */
  @Internal
  public int consumeNextStringArrayElement() {
    byte[] bytes = input;
    int offset = position;
    if (offset < inputLimit) {
      int ch = bytes[offset];
      if (ch == ']') {
        position = offset + 1;
        return STRING_ARRAY_END;
      }
      if (ch == ',') {
        offset++;
        if (offset < inputLimit && bytes[offset] == '"') {
          position = offset + 1;
          return STRING_ARRAY_QUOTED;
        }
        position = offset;
        return STRING_ARRAY_VALUE;
      }
    }
    return consumeNextStringArrayElementSlow();
  }

  private int consumeNextStringArrayElementSlow() {
    skipWhitespaceFast();
    byte[] bytes = input;
    int offset = position;
    if (offset < inputLimit) {
      int ch = bytes[offset];
      if (ch == ']') {
        position = offset + 1;
        return STRING_ARRAY_END;
      }
      if (ch == ',') {
        position = offset + 1;
        skipWhitespaceFast();
        offset = position;
        if (offset < inputLimit && bytes[offset] == '"') {
          position = offset + 1;
          return STRING_ARRAY_QUOTED;
        }
        return STRING_ARRAY_VALUE;
      }
    }
    throw error("Expected ',' or ']'");
  }

  private boolean consumeNextCommaOrEndArraySlow() {
    skipWhitespaceFast();
    if (position < inputLimit) {
      int ch = input[position];
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

  @Override
  public boolean tryReadNullToken() {
    skipWhitespaceFast();
    return tryReadNullLiteral();
  }

  public boolean tryReadNextNullToken() {
    if (position < inputLimit) {
      int ch = input[position];
      if (ch == 'n') {
        return tryReadNullLiteral();
      }
      if (ch > ' ' || !isWhitespace(ch)) {
        return false;
      }
    }
    return tryReadNullToken();
  }

  private boolean tryReadNullLiteral() {
    byte[] bytes = input;
    int offset = position;
    if (offset + 3 < inputLimit && LittleEndian.getInt32(bytes, offset) == NULL_LITERAL) {
      position = offset + 4;
      return true;
    }
    return false;
  }

  public boolean readBooleanValue() {
    skipWhitespaceFast();
    return readBooleanToken();
  }

  public boolean readNextBooleanValue() {
    if (position < inputLimit) {
      int ch = input[position];
      if (ch > ' ' || !isWhitespace(ch)) {
        return readBooleanToken();
      }
    }
    return readBooleanValue();
  }

  public boolean readBooleanTokenValue() {
    return readBooleanToken();
  }

  private boolean readQuotedBooleanValue() {
    beginQuotedScalar();
    boolean value = readBooleanToken();
    finishQuotedScalar();
    return value;
  }

  @Override
  protected boolean readBooleanToken() {
    byte[] bytes = input;
    int offset = position;
    int limit = inputLimit;
    // Prove the whole word is in the input slice, independently of the backing array's length.
    if (offset <= limit - 4) {
      int word = LittleEndian.getInt32(bytes, offset);
      if (word == TRUE_LITERAL) {
        position = offset + 4;
        return true;
      }
      if (word == FALSE_PREFIX && offset < limit - 4 && bytes[offset + 4] == 'e') {
        position = offset + 5;
        return false;
      }
    }
    if (offset < limit && bytes[offset] == '"') {
      return readQuotedBooleanValue();
    }
    throw error("Expected boolean");
  }

  public int readIntValue() {
    skipWhitespaceFast();
    return readIntToken();
  }

  public int readNextIntValue() {
    if (position < inputLimit) {
      int ch = input[position];
      if (ch > ' ' || !isWhitespace(ch)) {
        return readIntToken();
      }
    }
    return readIntValue();
  }

  public int readIntTokenValue() {
    return readIntToken();
  }

  private int readQuotedIntValue() {
    beginQuotedScalar();
    int value = readIntToken();
    finishQuotedScalar();
    return value;
  }

  private int readIntToken() {
    byte[] bytes = input;
    int offset = position;
    int inputLimit = this.inputLimit;
    if (offset >= inputLimit) {
      throw error("Expected digit");
    }
    int ch = bytes[offset];
    if (ch == '"') {
      return readQuotedIntValue();
    }
    if (ch == '-') {
      return readNegativeIntToken(offset);
    }
    if (ch == '0') {
      position = offset + 1;
      rejectLeadingDigitFast();
      rejectFractionOrExponentFast();
      return 0;
    }
    if (ch < '1' || ch > '9') {
      throw error("Expected digit");
    }
    int result = ch - '0';
    offset++;
    int safeEnd = offset + 8;
    if (safeEnd > inputLimit) {
      safeEnd = inputLimit;
    }
    while (offset < safeEnd) {
      ch = bytes[offset];
      if (ch < '0' || ch > '9') {
        break;
      }
      result = result * 10 + (ch - '0');
      offset++;
    }
    if (offset < inputLimit) {
      ch = bytes[offset];
      if (ch >= '0' && ch <= '9') {
        return readPositiveIntTail(bytes, offset, inputLimit, result);
      }
    }
    position = offset;
    rejectFractionOrExponentFast();
    return result;
  }

  private int readPositiveIntTail(byte[] bytes, int offset, int inputLimit, int result) {
    // The caller has consumed exactly nine positive digits. A Java int can contain only one more;
    // any following digit is necessarily overflow rather than another loop iteration.
    int digit = bytes[offset] - '0';
    if (result > INT_MAX_DIV_10 || (result == INT_MAX_DIV_10 && digit > INT_MAX_MOD_10)) {
      position = offset;
      throw error("Integer overflow");
    }
    result = result * 10 + digit;
    offset++;
    if (offset < inputLimit) {
      int ch = bytes[offset];
      if (ch >= '0' && ch <= '9') {
        position = offset;
        throw error("Integer overflow");
      }
    }
    position = offset;
    rejectFractionOrExponentFast();
    return result;
  }

  private int readNegativeIntToken(int start) {
    byte[] bytes = input;
    int offset = start + 1;
    int inputLimit = this.inputLimit;
    if (offset >= inputLimit) {
      throw error("Expected digit");
    }
    int ch = bytes[offset];
    if (ch == '0') {
      position = offset + 1;
      rejectLeadingDigitFast();
      rejectFractionOrExponentFast();
      return 0;
    }
    if (ch < '1' || ch > '9') {
      throw error("Expected digit");
    }
    int result = '0' - ch;
    offset++;
    // Nine negative digits always fit. Only the tenth digit needs the asymmetric MIN_VALUE bound.
    int safeEnd = Math.min(offset + 8, inputLimit);
    while (offset < safeEnd) {
      ch = bytes[offset];
      if (ch < '0' || ch > '9') {
        break;
      }
      result = result * 10 - (ch - '0');
      offset++;
    }
    if (offset < inputLimit) {
      ch = bytes[offset];
      if (ch >= '0' && ch <= '9') {
        return readNegativeIntTail(bytes, offset, inputLimit, result);
      }
    }
    position = offset;
    rejectFractionOrExponentFast();
    return result;
  }

  private int readNegativeIntTail(byte[] bytes, int offset, int inputLimit, int result) {
    int digit = bytes[offset] - '0';
    if (result < Integer.MIN_VALUE / 10
        || (result == Integer.MIN_VALUE / 10 && digit > -(Integer.MIN_VALUE % 10))) {
      position = offset;
      throw error("Integer overflow");
    }
    result = result * 10 - digit;
    offset++;
    if (offset < inputLimit) {
      int ch = bytes[offset];
      if (ch >= '0' && ch <= '9') {
        position = offset;
        throw error("Integer overflow");
      }
    }
    position = offset;
    rejectFractionOrExponentFast();
    return result;
  }

  public long readLongValue() {
    skipWhitespaceFast();
    return readLongToken();
  }

  public long readNextLongValue() {
    if (position < inputLimit) {
      int ch = input[position];
      if (ch > ' ' || !isWhitespace(ch)) {
        return readLongToken();
      }
    }
    return readLongValue();
  }

  public long readLongTokenValue() {
    return readLongToken();
  }

  private long readQuotedLongValue() {
    beginQuotedScalar();
    long value = readLongToken();
    finishQuotedScalar();
    return value;
  }

  @Override
  long scanNumberToken() {
    byte[] bytes = input;
    int limit = inputLimit;
    int offset = position;
    int point = -1;
    int exponent = -1;
    if (offset < limit && bytes[offset] == '-') {
      offset++;
    }
    if (offset >= limit) {
      position = offset;
      throw error("Expected digit");
    }
    int first = bytes[offset];
    if (first == '0') {
      offset++;
      if (offset < limit && bytes[offset] >= '0' && bytes[offset] <= '9') {
        position = offset;
        throw error("Leading zero in number");
      }
    } else if (first >= '1' && first <= '9') {
      offset = scanNumberDigits(bytes, offset, limit);
    } else {
      position = offset;
      throw error("Expected digit");
    }
    if (offset < limit && bytes[offset] == '.') {
      point = offset;
      int fraction = ++offset;
      offset = scanNumberDigits(bytes, offset, limit);
      if (offset == fraction) {
        position = offset;
        throw error("Expected digit");
      }
    }
    if (offset < limit && (bytes[offset] == 'e' || bytes[offset] == 'E')) {
      exponent = offset;
      offset++;
      if (offset < limit && (bytes[offset] == '+' || bytes[offset] == '-')) {
        offset++;
      }
      int exponentStart = offset;
      offset = scanNumberDigits(bytes, offset, limit);
      if (offset == exponentStart) {
        position = offset;
        throw error("Expected digit");
      }
    }
    position = offset;
    return ((long) point << 32) | (exponent & 0xffff_ffffL);
  }

  private static int scanNumberDigits(byte[] bytes, int offset, int limit) {
    while (offset <= limit - 8) {
      long chunk = LittleEndian.getInt64(bytes, offset);
      long nonDigits = ((chunk - ASCII_ZEROES) | (ASCII_NINES - chunk)) & ASCII_HIGH_BITS;
      if (nonDigits != 0) {
        // Borrow may mark later lanes, but the first non-digit lane remains exact.
        return offset + (Long.numberOfTrailingZeros(nonDigits) >>> 3);
      }
      offset += 8;
    }
    while (offset < limit && bytes[offset] >= '0' && bytes[offset] <= '9') {
      offset++;
    }
    return offset;
  }

  @Override
  public BigInteger readBigInteger() {
    skipWhitespaceFast();
    if (position < inputLimit && input[position] == '"') {
      return readQuotedBigIntegerValue();
    }
    return readBigIntegerToken();
  }

  private BigInteger readQuotedBigIntegerValue() {
    beginQuotedScalar();
    BigInteger value = readBigIntegerToken();
    finishQuotedScalar();
    return value;
  }

  private BigInteger readBigIntegerToken() {
    byte[] bytes = input;
    int limit = inputLimit;
    int start = position;
    int offset = start;
    boolean negative = offset < limit && bytes[offset] == '-';
    if (negative) {
      offset++;
    }
    if (offset >= limit) {
      throw error("Expected digit");
    }
    int ch = bytes[offset];
    if (ch == '0') {
      position = offset + 1;
      rejectLeadingDigitFast();
      rejectFractionOrExponentFast();
      return BigInteger.ZERO;
    }
    if (ch < '1' || ch > '9') {
      throw error("Expected digit");
    }
    // Every nineteen-digit coefficient fits in an unsigned long. Keep the prefix unsigned and
    // let the existing magnitude converter handle values above signed MAX_VALUE without rescanning.
    int safeEnd = offset + Math.min(19, limit - offset);
    long value = 0;
    while (safeEnd - offset >= Long.BYTES) {
      long text = LittleEndian.getInt64(bytes, offset);
      long digits = text - ASCII_ZEROES;
      long stop = (digits | (ASCII_NINES - text)) & ASCII_HIGH_BITS;
      if (stop == 0) {
        value = value * EIGHT_DIGITS + combineEightDigits(digits);
        offset += Long.BYTES;
      } else {
        int count = Long.numberOfTrailingZeros(stop) >>> 3;
        // The first stop proves the preceding digit lanes. Right-align them among eight
        // decimal places to convert a short prefix without rereading its individual bytes.
        digits = (digits & ((1L << (count << 3)) - 1)) << ((Long.BYTES - count) << 3);
        value = value * LONG_POWERS_OF_TEN[count] + combineEightDigits(digits);
        offset += count;
        break;
      }
    }
    while (offset < safeEnd) {
      ch = bytes[offset];
      if (ch < '0' || ch > '9') {
        break;
      }
      value = value * 10 + (ch - '0');
      offset++;
    }
    if (offset < limit && bytes[offset] >= '0' && bytes[offset] <= '9') {
      return readBigIntegerTail(start, offset, value);
    }
    position = offset;
    rejectFractionOrExponentFast();
    if (value < 0 && (!negative || value != Long.MIN_VALUE)) {
      return parseBigInteger(bytes, start, offset, value, offset);
    }
    return BigInteger.valueOf(negative ? -value : value);
  }

  private BigInteger readBigIntegerTail(int start, int offset, long prefix) {
    int end = scanNumberDigits(input, offset, inputLimit);
    position = end;
    rejectFractionOrExponentFast();
    return parseBigInteger(input, start, end, prefix, offset);
  }

  public BigDecimal readBigDecimal() {
    skipWhitespaceFast();
    return readBigDecimalToken();
  }

  private BigDecimal readQuotedBigDecimalValue() {
    beginQuotedScalar();
    BigDecimal value = readBigDecimalToken();
    finishQuotedScalar();
    return value;
  }

  public UUID readUuid() {
    skipWhitespaceFast();
    int mark = position;
    try {
      return readUuidToken();
    } catch (RuntimeException e) {
      position = mark;
      return parseUuidValue(readQuotedTextValue());
    }
  }

  @Override
  public double readDouble() {
    skipWhitespaceFast();
    return readDoubleToken();
  }

  @Override
  public float readFloat() {
    skipWhitespaceFast();
    return readFloatToken();
  }

  public double readNextDoubleValue() {
    if (position < inputLimit) {
      int ch = input[position];
      if (ch > ' ' || !isWhitespace(ch)) {
        return readDoubleToken();
      }
    }
    return readDouble();
  }

  public double readDoubleTokenValue() {
    return readDoubleToken();
  }

  private double readQuotedDoubleValue() {
    if (isQuotedNonFiniteNumber()) {
      return readNonFiniteDoubleLiteral();
    }
    beginQuotedScalar();
    double value = readDoubleToken();
    finishQuotedScalar();
    return value;
  }

  public float readNextFloatValue() {
    if (position < inputLimit) {
      int ch = input[position];
      if (ch > ' ' || !isWhitespace(ch)) {
        return readFloatToken();
      }
    }
    return readFloat();
  }

  public float readFloatTokenValue() {
    return readFloatToken();
  }

  private float readQuotedFloatValue() {
    if (isQuotedNonFiniteNumber()) {
      return readNonFiniteFloatLiteral();
    }
    beginQuotedScalar();
    float value = readFloatToken();
    finishQuotedScalar();
    return value;
  }

  // Long parsing deliberately repeats the initial digit checks, zero handling, block scan, and
  // short tail used by Int parsing instead of sharing one generic token loop. The widths have
  // different safe digit counts, overflow rules, and runtime profiles; a small shared helper lets
  // one profile determine both callers' inline layout and loses the width-specific locals. Keep
  // malformed input and overflow in their cold tails. Do not deduplicate this common path without
  // matched intrinsic and aggregate C2 evidence, and never add padding or benchmark-specific
  // digit-count branches to create an inline boundary.
  private long readLongToken() {
    byte[] bytes = input;
    int offset = position;
    int inputLimit = this.inputLimit;
    if (offset >= inputLimit) {
      throw error("Expected digit");
    }
    int ch = bytes[offset];
    if (ch == '"') {
      return readQuotedLongValue();
    }
    if (ch == '-') {
      return readNegativeLongToken(offset);
    }
    if (ch == '0') {
      position = offset + 1;
      rejectLeadingDigitFast();
      rejectFractionOrExponentFast();
      return 0;
    }
    if (ch < '1' || ch > '9') {
      throw error("Expected digit");
    }
    long result = ch - '0';
    offset++;
    int safeEnd = offset + 17;
    if (safeEnd > inputLimit) {
      safeEnd = inputLimit;
    }
    while (safeEnd - offset >= Long.BYTES) {
      long text = LittleEndian.getInt64(bytes, offset);
      long digits = text - ASCII_ZEROES;
      long stop = (digits | (ASCII_NINES - text)) & ASCII_HIGH_BITS;
      if (stop == 0) {
        result = result * EIGHT_DIGITS + combineEightDigits(digits);
        offset += Long.BYTES;
      } else {
        // The first stop proves the short digit prefix; the eighteen-digit bound still makes
        // its complete accumulation safe without a per-digit overflow check.
        int count = Long.numberOfTrailingZeros(stop) >>> 3;
        digits = (digits & ((1L << (count << 3)) - 1)) << ((Long.BYTES - count) << 3);
        result = result * LONG_POWERS_OF_TEN[count] + combineEightDigits(digits);
        offset += count;
        break;
      }
    }
    while (offset < safeEnd) {
      ch = bytes[offset];
      if (ch < '0' || ch > '9') {
        break;
      }
      result = result * 10 + (ch - '0');
      offset++;
    }
    if (offset < inputLimit) {
      ch = bytes[offset];
      if (ch >= '0' && ch <= '9') {
        return readPositiveLongTail(bytes, offset, inputLimit, result);
      }
    }
    position = offset;
    rejectFractionOrExponentFast();
    return result;
  }

  private long readPositiveLongTail(byte[] bytes, int offset, int inputLimit, long result) {
    while (offset < inputLimit) {
      int ch = bytes[offset];
      if (ch < '0' || ch > '9') {
        break;
      }
      int digit = ch - '0';
      if (result > LONG_MAX_DIV_10 || (result == LONG_MAX_DIV_10 && digit > LONG_MAX_MOD_10)) {
        position = offset;
        throw error("Long overflow");
      }
      result = result * 10 + digit;
      offset++;
    }
    position = offset;
    rejectFractionOrExponentFast();
    return result;
  }

  private long readNegativeLongToken(int start) {
    byte[] bytes = input;
    int offset = start + 1;
    int inputLimit = this.inputLimit;
    if (offset >= inputLimit) {
      throw error("Expected digit");
    }
    int ch = bytes[offset];
    if (ch == '0') {
      position = offset + 1;
      rejectLeadingDigitFast();
      rejectFractionOrExponentFast();
      return 0;
    }
    if (ch < '1' || ch > '9') {
      throw error("Expected digit");
    }
    long result = '0' - ch;
    offset++;
    int safeEnd = offset + 17;
    if (safeEnd > inputLimit) {
      safeEnd = inputLimit;
    }
    while (safeEnd - offset >= Long.BYTES) {
      long text = LittleEndian.getInt64(bytes, offset);
      long digits = text - ASCII_ZEROES;
      long stop = (digits | (ASCII_NINES - text)) & ASCII_HIGH_BITS;
      if (stop == 0) {
        result = result * EIGHT_DIGITS - combineEightDigits(digits);
        offset += Long.BYTES;
      } else {
        // The first stop proves the short digit prefix; the eighteen-digit bound still makes
        // its complete accumulation safe without a per-digit overflow check.
        int count = Long.numberOfTrailingZeros(stop) >>> 3;
        digits = (digits & ((1L << (count << 3)) - 1)) << ((Long.BYTES - count) << 3);
        result = result * LONG_POWERS_OF_TEN[count] - combineEightDigits(digits);
        offset += count;
        break;
      }
    }
    while (offset < safeEnd) {
      ch = bytes[offset];
      if (ch < '0' || ch > '9') {
        break;
      }
      result = result * 10 - (ch - '0');
      offset++;
    }
    if (offset < inputLimit) {
      ch = bytes[offset];
      if (ch >= '0' && ch <= '9') {
        return readNegativeLongTail(bytes, offset, inputLimit, result);
      }
    }
    position = offset;
    rejectFractionOrExponentFast();
    return result;
  }

  private long readNegativeLongTail(byte[] bytes, int offset, int inputLimit, long result) {
    while (offset < inputLimit) {
      int ch = bytes[offset];
      if (ch < '0' || ch > '9') {
        break;
      }
      int digit = ch - '0';
      if (result < LONG_MIN_DIV_10 || (result == LONG_MIN_DIV_10 && digit > LONG_MIN_LAST_DIGIT)) {
        position = offset;
        throw error("Long overflow");
      }
      result = result * 10 - digit;
      offset++;
    }
    position = offset;
    rejectFractionOrExponentFast();
    return result;
  }

  private static int parseEightDigits(byte[] bytes, int offset, int safeEnd) {
    if (offset + 8 > safeEnd) {
      return -1;
    }
    // Keep this as one unaligned little-endian load. Eight separate byte loads made the helper too
    // large for C2 to place well under generated readers, while the byte-lane math stays compact.
    long chunk = LittleEndian.getInt64(bytes, offset);
    long digits = chunk - ASCII_ZEROES;
    if (((digits | (ASCII_NINES - chunk)) & ASCII_HIGH_BITS) != 0) {
      return -1;
    }
    return combineEightDigits(digits);
  }

  private static int combineEightDigits(long digits) {
    long pairs = (digits * 10 + (digits >>> 8)) & 0x00FF_00FF_00FF_00FFL;
    long quads = (pairs * 100 + (pairs >>> 16)) & 0x0000_FFFF_0000_FFFFL;
    return (int) ((quads & 0xFFFF) * 10_000 + (quads >>> 32));
  }

  private static int parseFourDigits(byte[] bytes, int offset, int safeEnd) {
    if (offset + 4 > safeEnd) {
      return -1;
    }
    int chunk = LittleEndian.getInt32(bytes, offset);
    int digits = chunk - (int) ASCII_ZEROES;
    if (((digits | ((int) ASCII_NINES - chunk)) & INT_BYTE_HIGH_BITS) != 0) {
      return -1;
    }
    int pairs = (digits * 10 + (digits >>> 8)) & 0x00FF_00FF;
    return (pairs & 0xFFFF) * 100 + (pairs >>> 16);
  }

  private static long appendEightDigits(byte[] bytes, int offset, int safeEnd, long unscaled) {
    int block = parseEightDigits(bytes, offset, safeEnd);
    if (block < 0 || !canAppendEightDigits(unscaled, block)) {
      return -1;
    }
    return unscaled * EIGHT_DIGITS + block;
  }

  private static long appendFourDigits(byte[] bytes, int offset, int safeEnd, long unscaled) {
    int block = parseFourDigits(bytes, offset, safeEnd);
    if (block < 0) {
      return -1;
    }
    // Callers use a strict divisor bound, so every validated four-digit block is safe here. The
    // one equality boundary stays on the pair path because its final block determines overflow.
    return unscaled * FOUR_DIGITS + block;
  }

  // Positive magnitudes below these power-of-two bounds can append the full decimal chunk without
  // overflowing a signed long. On the high branch, adding the remainder carry converts the exact
  // boundary into one unsigned divisor comparison; unsigned order also rejects MAX_VALUE + 1 after
  // it wraps. The validated digit and pair ranges make the shifts exact zero-or-one carries.
  private static boolean canAppendDigit(long unscaled, int digit) {
    if ((unscaled >>> 59) == 0) {
      return true;
    }
    long adjusted = unscaled + (digit >>> 3);
    return Long.compareUnsigned(adjusted, LONG_MAX_DIV_10) <= 0;
  }

  private static boolean canAppendTwoDigits(long unscaled, int pair) {
    if ((unscaled >>> 56) == 0) {
      return true;
    }
    long adjusted = unscaled + ((pair + (127 - LONG_MAX_MOD_100)) >>> 7);
    return Long.compareUnsigned(adjusted, LONG_MAX_DIV_100) <= 0;
  }

  private static boolean canAppendEightDigits(long unscaled, int block) {
    if ((unscaled >>> 36) == 0) {
      return true;
    }
    long adjusted = unscaled + (block > LONG_MAX_MOD_EIGHT_DIGITS ? 1 : 0);
    return Long.compareUnsigned(adjusted, LONG_MAX_DIV_EIGHT_DIGITS) <= 0;
  }

  @Override
  protected BigDecimal readBigDecimalFallback(int start) {
    position = start;
    long separators = scanNumberToken();
    int end = position;
    if (end - start > MAX_BIG_NUMBER_LENGTH) {
      throwBigNumberLengthExceeded(end);
    }
    byte[] bytes = input;
    int point = (int) (separators >>> 32);
    int exponent = (int) separators;
    int coefficientEnd = exponent < 0 ? end : exponent;
    long scale = point < 0 ? 0 : coefficientEnd - point - 1;
    if (exponent >= 0) {
      scale = readExponentScale(exponent, scale);
    }
    if (scale > MAX_BIG_DECIMAL_SCALE || scale < -MAX_BIG_DECIMAL_SCALE) {
      throwBigDecimalScaleExceeded();
    }
    // The scanner proved this borrowed ASCII span. Only removing the point needs a new array.
    BigInteger unscaled;
    if (point < 0) {
      unscaled = parseBigInteger(bytes, start, coefficientEnd, 0, start);
    } else {
      byte[] coefficient = new byte[coefficientEnd - start - 1];
      System.arraycopy(bytes, start, coefficient, 0, point - start);
      System.arraycopy(bytes, point + 1, coefficient, point - start, coefficientEnd - point - 1);
      unscaled = parseBigInteger(coefficient, 0, coefficient.length, 0, 0);
    }
    return new BigDecimal(unscaled, (int) scale);
  }

  private BigDecimal readBigDecimalToken() {
    byte[] bytes = input;
    int offset = position;
    int start = offset;
    int inputLimit = this.inputLimit;
    if (offset >= inputLimit) {
      return readBigDecimalFallback(start);
    }
    int ch = bytes[offset];
    if (ch == '"') {
      return readQuotedBigDecimalValue();
    }
    if (ch == '-') {
      return readSignedBigDecimalToken(start);
    }
    long unscaled = 0;
    int scale = 0;
    if (ch == '0') {
      offset++;
      position = offset;
      rejectLeadingDigitFast();
    } else if (ch >= '1' && ch <= '9') {
      // Eighteen digits fit in a positive long; defer overflow checks to the remaining digits.
      int safeEnd = offset + Math.min(18, inputLimit - offset);
      if (safeEnd - offset >= 8) {
        int block = parseEightDigits(bytes, offset, safeEnd);
        if (block >= 0) {
          unscaled = block;
          offset += 8;
          if (safeEnd - offset >= 8) {
            block = parseEightDigits(bytes, offset, safeEnd);
            if (block >= 0) {
              unscaled = unscaled * EIGHT_DIGITS + block;
              offset += 8;
            }
          }
        }
      }
      while (offset < safeEnd) {
        ch = bytes[offset];
        if (ch < '0' || ch > '9') {
          break;
        }
        unscaled = unscaled * 10 + ch - '0';
        offset++;
      }
      while (offset < inputLimit) {
        ch = bytes[offset];
        if (ch < '0' || ch > '9') {
          break;
        }
        int digit = ch - '0';
        if (unscaled > LONG_MAX_DIV_10
            || (unscaled == LONG_MAX_DIV_10 && digit > LONG_MAX_MOD_10)) {
          return readBigDecimalFallback(start);
        }
        unscaled = unscaled * 10 + digit;
        offset++;
      }
    } else {
      return readBigDecimalFallback(start);
    }
    if (offset < inputLimit && bytes[offset] == '.') {
      offset++;
      int fractionStart = offset;
      while (offset < inputLimit) {
        ch = bytes[offset];
        if (ch < '0' || ch > '9') {
          break;
        }
        int digit = ch - '0';
        if (unscaled > LONG_MAX_DIV_10
            || (unscaled == LONG_MAX_DIV_10 && digit > LONG_MAX_MOD_10)) {
          return readBigDecimalFallback(start);
        }
        unscaled = unscaled * 10 + digit;
        scale++;
        offset++;
      }
      if (offset == fractionStart) {
        return readBigDecimalFallback(start);
      }
    }
    if (offset < inputLimit) {
      ch = bytes[offset];
      if (ch == 'e' || ch == 'E') {
        return readBigDecimalExponentValue(false, unscaled, scale, offset);
      }
    }
    position = offset;
    if (scale > MAX_BIG_DECIMAL_SCALE) {
      throwBigDecimalScaleExceeded();
    }
    return BigDecimal.valueOf(unscaled, scale);
  }

  private BigDecimal readSignedBigDecimalToken(int start) {
    byte[] bytes = input;
    int offset = start + 1;
    int inputLimit = this.inputLimit;
    if (offset >= inputLimit) {
      return readBigDecimalFallback(start);
    }
    int ch = bytes[offset];
    long unscaled = 0;
    int scale = 0;
    if (ch == '0') {
      offset++;
      position = offset;
      rejectLeadingDigitFast();
    } else if (ch >= '1' && ch <= '9') {
      // Eighteen digits fit in a positive long; defer overflow checks to the remaining digits.
      int safeEnd = offset + Math.min(18, inputLimit - offset);
      if (safeEnd - offset >= 8) {
        int block = parseEightDigits(bytes, offset, safeEnd);
        if (block >= 0) {
          unscaled = block;
          offset += 8;
          if (safeEnd - offset >= 8) {
            block = parseEightDigits(bytes, offset, safeEnd);
            if (block >= 0) {
              unscaled = unscaled * EIGHT_DIGITS + block;
              offset += 8;
            }
          }
        }
      }
      while (offset < safeEnd) {
        ch = bytes[offset];
        if (ch < '0' || ch > '9') {
          break;
        }
        unscaled = unscaled * 10 + ch - '0';
        offset++;
      }
      while (offset < inputLimit) {
        ch = bytes[offset];
        if (ch < '0' || ch > '9') {
          break;
        }
        int digit = ch - '0';
        if (unscaled > LONG_MAX_DIV_10
            || (unscaled == LONG_MAX_DIV_10 && digit > LONG_MAX_MOD_10)) {
          return readBigDecimalFallback(start);
        }
        unscaled = unscaled * 10 + digit;
        offset++;
      }
    } else {
      return readBigDecimalFallback(start);
    }
    if (offset < inputLimit && bytes[offset] == '.') {
      offset++;
      int fractionStart = offset;
      while (offset < inputLimit) {
        ch = bytes[offset];
        if (ch < '0' || ch > '9') {
          break;
        }
        int digit = ch - '0';
        if (unscaled > LONG_MAX_DIV_10
            || (unscaled == LONG_MAX_DIV_10 && digit > LONG_MAX_MOD_10)) {
          return readBigDecimalFallback(start);
        }
        unscaled = unscaled * 10 + digit;
        scale++;
        offset++;
      }
      if (offset == fractionStart) {
        return readBigDecimalFallback(start);
      }
    }
    if (offset < inputLimit) {
      ch = bytes[offset];
      if (ch == 'e' || ch == 'E') {
        return readBigDecimalExponentValue(true, unscaled, scale, offset);
      }
    }
    position = offset;
    if (scale > MAX_BIG_DECIMAL_SCALE) {
      throwBigDecimalScaleExceeded();
    }
    return BigDecimal.valueOf(-unscaled, scale);
  }

  private UUID readUuidToken() {
    byte[] bytes = input;
    int offset = position;
    int start = offset + 1;
    if (offset > inputLimit - 38 || bytes[offset] != '"') {
      throw new IllegalArgumentException();
    }
    if (bytes[start + 8] != '-'
        || bytes[start + 13] != '-'
        || bytes[start + 18] != '-'
        || bytes[start + 23] != '-'
        || bytes[start + 36] != '"') {
      throw new IllegalArgumentException();
    }
    UUID value = parseUuidBytes(bytes, start);
    position = start + 37;
    return value;
  }

  private double readDoubleToken() {
    // Keep the byte-reader fast path narrow: compact plain decimals finish locally, while
    // exponents, overflow, and precision-sensitive values use the reader-owned exact fallback.
    byte[] bytes = input;
    int offset = position;
    int inputLimit = this.inputLimit;
    if (offset >= inputLimit) {
      return readDoubleFallback(offset);
    }
    int ch = bytes[offset];
    if (ch == '"') {
      return readQuotedDoubleValue();
    }
    if (ch == '-') {
      return readSignedDoubleToken(offset);
    }
    return readPositiveDoubleToken(bytes, offset, inputLimit, ch);
  }

  private float readFloatToken() {
    byte[] bytes = input;
    int offset = position;
    int inputLimit = this.inputLimit;
    if (offset >= inputLimit) {
      return readFloatFallback(offset);
    }
    int ch = bytes[offset];
    if (ch == '"') {
      return readQuotedFloatValue();
    }
    if (ch == '-') {
      return readSignedFloatToken(offset);
    }
    return readPositiveFloatToken(bytes, offset, inputLimit, ch);
  }

  private float readPositiveFloatToken(byte[] bytes, int offset, int inputLimit, int ch) {
    int start = offset;
    long unscaled = 0;
    if (ch == '0') {
      offset++;
      if (offset < inputLimit) {
        ch = bytes[offset];
        if (ch >= '0' && ch <= '9') {
          return readFloatFallback(start);
        }
      }
    } else if (ch >= '1' && ch <= '9') {
      unscaled = ch - '0';
      offset++;
      while (offset + 1 < inputLimit) {
        int chunk = (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
        int digits = chunk - 0x3030; // The two subtractions detect non-digit lanes.
        if (((digits | (0x3939 - chunk)) & 0x8080) != 0) {
          break;
        }
        int pair = (digits & 0xff) * 10 + (digits >>> 8);
        if (!canAppendTwoDigits(unscaled, pair)) {
          return readFloatFallback(start);
        }
        unscaled = unscaled * 100 + pair;
        offset += 2;
      }
      if (offset < inputLimit) {
        int digit = bytes[offset] - '0';
        if (digit >= 0 && digit <= 9) {
          if (!canAppendDigit(unscaled, digit)) {
            return readFloatFallback(start);
          }
          unscaled = unscaled * 10 + digit;
          offset++;
        }
      }
    } else {
      return readFloatFallback(start);
    }
    int scale = 0;
    if (offset < inputLimit && bytes[offset] == '.') {
      offset++;
      int fractionStart = offset;
      // This coefficient bound makes any eight-digit suffix Long-safe. Preserve the actual
      // scale so short fractions retain the small-coefficient conversion path.
      if (unscaled < LONG_MAX_DIV_EIGHT_DIGITS && offset <= inputLimit - Long.BYTES) {
        long text = LittleEndian.getInt64(bytes, offset);
        long digits = text - ASCII_ZEROES;
        long stop = (digits | (ASCII_NINES - text)) & ASCII_HIGH_BITS;
        if (stop != 0) {
          int count = Long.numberOfTrailingZeros(stop) >>> 3;
          if (count == 0) {
            return readFloatFallback(start);
          }
          digits = (digits & ((1L << (count << 3)) - 1)) << ((Long.BYTES - count) << 3);
          unscaled = unscaled * LONG_POWERS_OF_TEN[count] + combineEightDigits(digits);
          return finishFloatToken(bytes, offset + count, inputLimit, start, unscaled, count);
        }
        unscaled = unscaled * EIGHT_DIGITS + combineEightDigits(digits);
        offset += Long.BYTES;
        scale = Long.BYTES;
      }
      while (offset + 1 < inputLimit) {
        int chunk = (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
        int digits = chunk - 0x3030;
        if (((digits | (0x3939 - chunk)) & 0x8080) != 0) {
          break;
        }
        int pair = (digits & 0xff) * 10 + (digits >>> 8);
        if (!canAppendTwoDigits(unscaled, pair)) {
          return readFloatFallback(start);
        }
        unscaled = unscaled * 100 + pair;
        scale += 2;
        offset += 2;
      }
      if (offset < inputLimit) {
        int digit = bytes[offset] - '0';
        if (digit >= 0 && digit <= 9) {
          if (!canAppendDigit(unscaled, digit)) {
            return readFloatFallback(start);
          }
          unscaled = unscaled * 10 + digit;
          scale++;
          offset++;
        }
      }
      if (offset == fractionStart) {
        return readFloatFallback(start);
      }
    }
    return finishFloatToken(bytes, offset, inputLimit, start, unscaled, scale);
  }

  private float readSignedFloatToken(int start) {
    byte[] bytes = input;
    int offset = start + 1;
    int inputLimit = this.inputLimit;
    if (offset >= inputLimit) {
      return readFloatFallback(start);
    }
    int ch = bytes[offset];
    long unscaled = 0;
    if (ch == '0') {
      offset++;
      if (offset < inputLimit) {
        ch = bytes[offset];
        if (ch >= '0' && ch <= '9') {
          return readFloatFallback(start);
        }
      }
    } else if (ch >= '1' && ch <= '9') {
      unscaled = ch - '0';
      offset++;
      while (offset + 1 < inputLimit) {
        int chunk = (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
        int digits = chunk - 0x3030;
        if (((digits | (0x3939 - chunk)) & 0x8080) != 0) {
          break;
        }
        int pair = (digits & 0xff) * 10 + (digits >>> 8);
        if (!canAppendTwoDigits(unscaled, pair)) {
          return readFloatFallback(start);
        }
        unscaled = unscaled * 100 + pair;
        offset += 2;
      }
      if (offset < inputLimit) {
        int digit = bytes[offset] - '0';
        if (digit >= 0 && digit <= 9) {
          if (!canAppendDigit(unscaled, digit)) {
            return readFloatFallback(start);
          }
          unscaled = unscaled * 10 + digit;
          offset++;
        }
      }
    } else {
      return readFloatFallback(start);
    }
    int scale = 0;
    if (offset < inputLimit && bytes[offset] == '.') {
      offset++;
      int fractionStart = offset;
      // This coefficient bound makes any eight-digit suffix Long-safe. Preserve the actual
      // scale so short fractions retain the small-coefficient conversion path.
      if (unscaled < LONG_MAX_DIV_EIGHT_DIGITS && offset <= inputLimit - Long.BYTES) {
        long text = LittleEndian.getInt64(bytes, offset);
        long digits = text - ASCII_ZEROES;
        long stop = (digits | (ASCII_NINES - text)) & ASCII_HIGH_BITS;
        if (stop != 0) {
          int count = Long.numberOfTrailingZeros(stop) >>> 3;
          if (count == 0) {
            return readFloatFallback(start);
          }
          digits = (digits & ((1L << (count << 3)) - 1)) << ((Long.BYTES - count) << 3);
          unscaled = unscaled * LONG_POWERS_OF_TEN[count] + combineEightDigits(digits);
          return finishSignedFloatToken(bytes, offset + count, inputLimit, start, unscaled, count);
        }
        unscaled = unscaled * EIGHT_DIGITS + combineEightDigits(digits);
        offset += Long.BYTES;
        scale = Long.BYTES;
      }
      while (offset + 1 < inputLimit) {
        int chunk = (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
        int digits = chunk - 0x3030;
        if (((digits | (0x3939 - chunk)) & 0x8080) != 0) {
          break;
        }
        int pair = (digits & 0xff) * 10 + (digits >>> 8);
        if (!canAppendTwoDigits(unscaled, pair)) {
          return readFloatFallback(start);
        }
        unscaled = unscaled * 100 + pair;
        scale += 2;
        offset += 2;
      }
      if (offset < inputLimit) {
        int digit = bytes[offset] - '0';
        if (digit >= 0 && digit <= 9) {
          if (!canAppendDigit(unscaled, digit)) {
            return readFloatFallback(start);
          }
          unscaled = unscaled * 10 + digit;
          scale++;
          offset++;
        }
      }
      if (offset == fractionStart) {
        return readFloatFallback(start);
      }
    }
    return finishSignedFloatToken(bytes, offset, inputLimit, start, unscaled, scale);
  }

  private float finishFloatToken(
      byte[] bytes, int offset, int inputLimit, int start, long unscaled, int scale) {
    if (offset < inputLimit) {
      int ch = bytes[offset];
      if (ch == 'e' || ch == 'E') {
        return readFloatExponentValue(false, unscaled, scale, start, offset);
      }
    }
    position = offset;
    if (!canUseFastFloat(unscaled, scale)) {
      if (canUseCompactFloat(scale)) {
        return compactFloatValue(false, unscaled, scale);
      }
      return readScannedFloatValue(false, unscaled, scale, start, offset);
    }
    return fastFloatValue(unscaled, scale);
  }

  private float finishSignedFloatToken(
      byte[] bytes, int offset, int inputLimit, int start, long unscaled, int scale) {
    if (offset < inputLimit) {
      int ch = bytes[offset];
      if (ch == 'e' || ch == 'E') {
        return readFloatExponentValue(true, unscaled, scale, start, offset);
      }
    }
    position = offset;
    if (unscaled == 0) {
      return -0.0f;
    }
    if (!canUseFastFloat(unscaled, scale)) {
      if (canUseCompactFloat(scale)) {
        return compactFloatValue(true, unscaled, scale);
      }
      return readScannedFloatValue(true, unscaled, scale, start, offset);
    }
    return -fastFloatValue(unscaled, scale);
  }

  private float readFloatFallback(int start) {
    return readFloatFallbackValue(start);
  }

  // Keep the complete integer and fraction scan in one token owner. A separate inline-sized
  // fraction tail makes generated callers depend on which method C2 compiles first.
  private double readPositiveDoubleToken(byte[] bytes, int offset, int inputLimit, int ch) {
    int start = offset;
    long unscaled = 0;
    if (ch == '0') {
      offset++;
      if (offset < inputLimit) {
        ch = bytes[offset];
        if (ch >= '0' && ch <= '9') {
          return readDoubleFallback(start);
        }
      }
    } else if (ch >= '1' && ch <= '9') {
      unscaled = ch - '0';
      offset++;
      while (offset + 1 < inputLimit) {
        int high = bytes[offset] - '0';
        int low = bytes[offset + 1] - '0';
        if ((high | low | (9 - high) | (9 - low)) < 0) {
          break;
        }
        int pair = high * 10 + low;
        if (!canAppendTwoDigits(unscaled, pair)) {
          return readDoubleFallback(start);
        }
        unscaled = unscaled * 100 + pair;
        offset += 2;
      }
      if (offset < inputLimit) {
        int digit = bytes[offset] - '0';
        if (digit >= 0 && digit <= 9) {
          if (!canAppendDigit(unscaled, digit)) {
            return readDoubleFallback(start);
          }
          unscaled = unscaled * 10 + digit;
          offset++;
        }
      }
    } else {
      return readDoubleFallback(start);
    }
    int scale = 0;
    if (offset < inputLimit && bytes[offset] == '.') {
      offset++;
      int fractionStart = offset;
      long appended = appendEightDigits(bytes, offset, inputLimit, unscaled);
      while (appended >= 0) {
        unscaled = appended;
        scale += 8;
        offset += 8;
        appended = appendEightDigits(bytes, offset, inputLimit, unscaled);
      }
      if (scale != 0 && unscaled < LONG_MAX_DIV_FOUR_DIGITS) {
        appended = appendFourDigits(bytes, offset, inputLimit, unscaled);
        if (appended >= 0) {
          unscaled = appended;
          scale += 4;
          offset += 4;
        }
      }
      while (offset + 1 < inputLimit) {
        int high = bytes[offset] - '0';
        int low = bytes[offset + 1] - '0';
        if ((high | low | (9 - high) | (9 - low)) < 0) {
          break;
        }
        int pair = high * 10 + low;
        if (!canAppendTwoDigits(unscaled, pair)) {
          return readDoubleFallback(start);
        }
        unscaled = unscaled * 100 + pair;
        scale += 2;
        offset += 2;
      }
      if (offset < inputLimit) {
        int digit = bytes[offset] - '0';
        if (digit >= 0 && digit <= 9) {
          if (!canAppendDigit(unscaled, digit)) {
            return readDoubleFallback(start);
          }
          unscaled = unscaled * 10 + digit;
          scale++;
          offset++;
        }
      }
      if (offset == fractionStart) {
        return readDoubleFallback(start);
      }
    }
    return finishDoubleToken(bytes, offset, inputLimit, start, unscaled, scale);
  }

  private double readSignedDoubleToken(int start) {
    byte[] bytes = input;
    int offset = start + 1;
    int inputLimit = this.inputLimit;
    if (offset >= inputLimit) {
      return readDoubleFallback(start);
    }
    int ch = bytes[offset];
    long unscaled = 0;
    if (ch == '0') {
      offset++;
      if (offset < inputLimit) {
        ch = bytes[offset];
        if (ch >= '0' && ch <= '9') {
          return readDoubleFallback(start);
        }
      }
    } else if (ch >= '1' && ch <= '9') {
      unscaled = ch - '0';
      offset++;
      while (offset + 1 < inputLimit) {
        int high = bytes[offset] - '0';
        int low = bytes[offset + 1] - '0';
        if ((high | low | (9 - high) | (9 - low)) < 0) {
          break;
        }
        int pair = high * 10 + low;
        if (!canAppendTwoDigits(unscaled, pair)) {
          return readDoubleFallback(start);
        }
        unscaled = unscaled * 100 + pair;
        offset += 2;
      }
      if (offset < inputLimit) {
        int digit = bytes[offset] - '0';
        if (digit >= 0 && digit <= 9) {
          if (!canAppendDigit(unscaled, digit)) {
            return readDoubleFallback(start);
          }
          unscaled = unscaled * 10 + digit;
          offset++;
        }
      }
    } else {
      return readDoubleFallback(start);
    }
    int scale = 0;
    if (offset < inputLimit && bytes[offset] == '.') {
      offset++;
      int fractionStart = offset;
      long appended = appendEightDigits(bytes, offset, inputLimit, unscaled);
      while (appended >= 0) {
        unscaled = appended;
        scale += 8;
        offset += 8;
        appended = appendEightDigits(bytes, offset, inputLimit, unscaled);
      }
      if (scale != 0 && unscaled < LONG_MAX_DIV_FOUR_DIGITS) {
        appended = appendFourDigits(bytes, offset, inputLimit, unscaled);
        if (appended >= 0) {
          unscaled = appended;
          scale += 4;
          offset += 4;
        }
      }
      while (offset + 1 < inputLimit) {
        int high = bytes[offset] - '0';
        int low = bytes[offset + 1] - '0';
        if ((high | low | (9 - high) | (9 - low)) < 0) {
          break;
        }
        int pair = high * 10 + low;
        if (!canAppendTwoDigits(unscaled, pair)) {
          return readDoubleFallback(start);
        }
        unscaled = unscaled * 100 + pair;
        scale += 2;
        offset += 2;
      }
      if (offset < inputLimit) {
        int digit = bytes[offset] - '0';
        if (digit >= 0 && digit <= 9) {
          if (!canAppendDigit(unscaled, digit)) {
            return readDoubleFallback(start);
          }
          unscaled = unscaled * 10 + digit;
          scale++;
          offset++;
        }
      }
      if (offset == fractionStart) {
        return readDoubleFallback(start);
      }
    }
    return finishSignedDoubleToken(bytes, offset, inputLimit, start, unscaled, scale);
  }

  private double finishDoubleToken(
      byte[] bytes, int offset, int inputLimit, int start, long unscaled, int scale) {
    if (offset < inputLimit) {
      int ch = bytes[offset];
      if (ch == 'e' || ch == 'E') {
        return readDoubleExponentValue(false, unscaled, scale, start, offset);
      }
    }
    position = offset;
    if (!canUseFastDouble(unscaled, scale)) {
      if (canUseCompactDouble(scale)) {
        return compactDoubleValue(false, unscaled, scale);
      }
      return readScannedDoubleValue(false, unscaled, scale, start, offset);
    }
    return fastDoubleValue(unscaled, scale);
  }

  private double finishSignedDoubleToken(
      byte[] bytes, int offset, int inputLimit, int start, long unscaled, int scale) {
    if (offset < inputLimit) {
      int ch = bytes[offset];
      if (ch == 'e' || ch == 'E') {
        return readDoubleExponentValue(true, unscaled, scale, start, offset);
      }
    }
    position = offset;
    if (unscaled == 0) {
      return -0.0d;
    }
    if (!canUseFastDouble(unscaled, scale)) {
      if (canUseCompactDouble(scale)) {
        return compactDoubleValue(true, unscaled, scale);
      }
      return readScannedDoubleValue(true, unscaled, scale, start, offset);
    }
    return -fastDoubleValue(unscaled, scale);
  }

  private double readDoubleFallback(int start) {
    return readDoubleFallbackValue(start);
  }

  @Override
  public int readFieldNameInt() {
    skipWhitespaceFast();
    int nameStart = position;
    if (position >= inputLimit || input[position++] != '"') {
      throw error("Expected string");
    }
    int digitStart = position;
    if (digitStart < inputLimit && input[digitStart] == '-') {
      digitStart++;
    }
    if (digitStart >= inputLimit) {
      throw error("Unterminated string");
    }
    int ch = input[digitStart];
    if (ch == '\\') {
      position = nameStart;
      return super.readFieldNameInt();
    }
    if (ch < '0' || ch > '9') {
      throw error("Expected integer field name");
    }
    // Reuse the native token's bounded digit scan and overflow handling. Escaped member names
    // still need the decoded-string path, and the closing quote belongs to this operation.
    int result = readIntToken();
    if (position >= inputLimit) {
      throw error("Unterminated string");
    }
    ch = input[position];
    if (ch == '\\') {
      position = nameStart;
      return super.readFieldNameInt();
    }
    if (ch != '"') {
      throw error("Expected integer field name");
    }
    position++;
    return result;
  }

  @Override
  public long readFieldNameLong() {
    skipWhitespaceFast();
    int nameStart = position;
    if (position >= inputLimit || input[position++] != '"') {
      throw error("Expected string");
    }
    int digitStart = position;
    if (digitStart < inputLimit && input[digitStart] == '-') {
      digitStart++;
    }
    if (digitStart >= inputLimit) {
      throw error("Unterminated string");
    }
    int ch = input[digitStart];
    if (ch == '\\') {
      position = nameStart;
      return super.readFieldNameLong();
    }
    if (ch < '0' || ch > '9') {
      throw error("Expected long field name");
    }
    // Reuse the native token's bounded digit scan and overflow handling. Escaped member names
    // still need the decoded-string path, and the closing quote belongs to this operation.
    long result = readLongToken();
    if (position >= inputLimit) {
      throw error("Unterminated string");
    }
    ch = input[position];
    if (ch == '\\') {
      position = nameStart;
      return super.readFieldNameLong();
    }
    if (ch != '"') {
      throw error("Expected long field name");
    }
    position++;
    return result;
  }

  @Override
  protected int length() {
    return inputLimit;
  }

  @Override
  protected char charAt(int index) {
    // Base grammar fallbacks call charAt only for ASCII JSON syntax and number text. Unicode string
    // content is decoded and validated by this concrete reader's overridden string/hash paths.
    return (char) (input[index] & 0xFF);
  }

  @Override
  public String readString() {
    skipWhitespaceFast();
    return readStringToken();
  }

  @Override
  public String readFieldName() {
    FieldNameCache cache = fieldNameCache;
    if (cache == null) {
      return readString();
    }
    return readCachedFieldName(cache);
  }

  private String readCachedFieldName(FieldNameCache cache) {
    skipWhitespaceFast();
    byte[] bytes = input;
    int inputLimit = this.inputLimit;
    if (position >= inputLimit || bytes[position++] != '"') {
      throw error("Expected string");
    }
    int start = position;
    if (start + Long.BYTES <= inputLimit) {
      long word0 = LittleEndian.getInt64(bytes, start);
      long stopMask = stringStopMask(word0);
      if (stopMask != 0) {
        int length = Long.numberOfTrailingZeros(stopMask) >>> 3;
        int stop = start + length;
        int b = bytes[stop];
        if (b != '"') {
          return readStringStop(start, stop, b);
        }
        position = stop + 1;
        word0 = fieldNameWord(word0, length);
        long hash = length == 0 ? JsonFieldNameHash.MAGIC_HASH_CODE : word0;
        CachedFieldName entry = cache.get(hash);
        if (entry != null) {
          return entry.matches(length, word0, 0) ? entry.name() : newLatin1String(start, stop);
        }
        if (!cache.canPut(hash)) {
          return newLatin1String(start, stop);
        }
        return readFieldNameMiss(cache, start, stop, length, word0, 0, hash);
      }
      return readFieldNameAfterWord0(cache, start, word0, inputLimit);
    }
    return readFieldNameTail(cache, start, start, 0, 0, 0);
  }

  private String readFieldNameAfterWord0(
      FieldNameCache cache, int start, long word0, int inputLimit) {
    int offset = start + Long.BYTES;
    if (offset + Long.BYTES <= inputLimit) {
      long word1 = LittleEndian.getInt64(input, offset);
      long stopMask = stringStopMask(word1);
      if (stopMask != 0) {
        int length = Long.numberOfTrailingZeros(stopMask) >>> 3;
        int stop = offset + length;
        int b = input[stop];
        if (b != '"') {
          return readStringStop(start, stop, b);
        }
        position = stop + 1;
        return resolveFieldName(
            cache, start, stop, Long.BYTES + length, word0, fieldNameWord(word1, length));
      }
      offset += Long.BYTES;
      if (offset < inputLimit && input[offset] == '"') {
        position = offset + 1;
        return resolveFieldName(cache, start, offset, 16, word0, word1);
      }
      return readStringTokenLongTail(start, offset, inputLimit);
    }
    return readFieldNameTail(cache, start, offset, Long.BYTES, word0, 0);
  }

  private String readFieldNameTail(
      FieldNameCache cache, int start, int offset, int length, long word0, long word1) {
    int inputLimit = this.inputLimit;
    while (offset < inputLimit) {
      int ch = input[offset] & 0xff;
      if (ch == '"') {
        position = offset + 1;
        return resolveFieldName(cache, start, offset, length, word0, word1);
      }
      if (ch == '\\' || ch >= 0x80 || ch < 0x20) {
        return readStringStop(start, offset, input[offset]);
      }
      if (length < Long.BYTES) {
        word0 |= ((long) ch) << (length << 3);
      } else {
        word1 |= ((long) ch) << ((length - Long.BYTES) << 3);
      }
      length++;
      offset++;
    }
    throw error("Unterminated string");
  }

  private String resolveFieldName(
      FieldNameCache cache, int start, int end, int length, long word0, long word1) {
    long hash = fieldNameHash(length, word0, word1);
    CachedFieldName entry = cache.get(hash);
    if (entry != null) {
      return entry.matches(length, word0, word1) ? entry.name() : newLatin1String(start, end);
    }
    if (!cache.canPut(hash)) {
      return newLatin1String(start, end);
    }
    return readFieldNameMiss(cache, start, end, length, word0, word1, hash);
  }

  private String readFieldNameMiss(
      FieldNameCache cache, int start, int end, int length, long word0, long word1, long hash) {
    JsonSharedRegistry registry = typeResolver().sharedRegistry();
    CachedFieldName entry = registry.cachedFieldName(hash);
    if (entry != null) {
      cache.put(hash, entry);
      return entry.matches(length, word0, word1) ? entry.name() : newLatin1String(start, end);
    }
    String candidate = newLatin1String(start, end);
    entry = registry.cacheFieldName(hash, candidate, word0, word1);
    cache.put(hash, entry);
    return entry.matches(length, word0, word1) ? entry.name() : candidate;
  }

  @Override
  public String readNullableString() {
    skipWhitespaceFast();
    if (tryReadNullLiteral()) {
      return null;
    }
    return readStringToken();
  }

  public String readNextNullableString() {
    if (position < inputLimit) {
      int ch = input[position];
      if (ch == '"') {
        return readStringToken();
      }
      if (ch == 'n' && tryReadNullLiteral()) {
        return null;
      }
      if (ch > ' ' || !isWhitespace(ch)) {
        return readStringToken();
      }
    }
    return readNullableString();
  }

  public String readNullableStringToken() {
    // Token callers have already handled whitespace. Keep the overwhelmingly common string case
    // to one byte check; only a possible null or malformed token enters the cold classifier.
    byte[] bytes = input;
    int offset = position;
    if (offset < inputLimit && bytes[offset] == '"') {
      return readStringToken();
    }
    return readNullableStringTokenSlow();
  }

  private String readNullableStringTokenSlow() {
    if (tryReadNullLiteral()) {
      return null;
    }
    return readStringToken();
  }

  @Override
  public LocalTime readIsoLocalTime() {
    skipWhitespaceFast();
    int mark = position;
    if (mark < inputLimit && input[mark] == '"') {
      LocalTime value = tryReadTime(mark + 1);
      if (value != null && position < inputLimit && input[position] == '"') {
        position++;
        return value;
      }
    }
    position = mark;
    return super.readIsoLocalTime();
  }

  @Override
  public LocalDateTime readIsoLocalDateTime() {
    skipWhitespaceFast();
    int mark = position;
    LocalDateTime value = tryReadDateTime();
    if (value != null && position < inputLimit && input[position] == '"') {
      position++;
      return value;
    }
    position = mark;
    return super.readIsoLocalDateTime();
  }

  @Override
  public Instant readIsoInstant() {
    skipWhitespaceFast();
    int mark = position;
    Instant value = tryReadInstant();
    if (value != null) {
      return value;
    }
    position = mark;
    return super.readIsoInstant();
  }

  private Instant tryReadInstant() {
    byte[] bytes = input;
    int limit = inputLimit;
    int start = position + 1;
    if (start > limit - 21
        || bytes[start - 1] != '"'
        || bytes[start + 4] != '-'
        || bytes[start + 7] != '-'
        || bytes[start + 10] != 'T'
        || bytes[start + 13] != ':'
        || bytes[start + 16] != ':') {
      return null;
    }
    // Pack YYYY-MM-DD into eight digit lanes so date validation and pair conversion share loads.
    long datePrefix = LittleEndian.getInt64(bytes, start);
    int dateSuffix = LittleEndian.getInt32(bytes, start + 7);
    long dateText =
        (datePrefix & 0xffffffffL)
            | ((datePrefix >>> 8) & 0xffff00000000L)
            | ((long) ((dateSuffix >>> 8) & 0xffff) << 48);
    long dateDigits = dateText - ASCII_ZEROES;
    if (((dateDigits | (ASCII_NINES - dateText)) & ASCII_HIGH_BITS) != 0) {
      return null;
    }
    long datePairs =
        (dateDigits & 0x00ff00ff00ff00ffL) * 10 + ((dateDigits >>> 8) & 0x00ff00ff00ff00ffL);
    int year = (int) (datePairs & 0xffff) * 100 + (int) ((datePairs >>> 16) & 0xffff);
    int month = (int) ((datePairs >>> 32) & 0xffff);
    int day = (int) (datePairs >>> 48);
    long timeText =
        (LittleEndian.getInt64(bytes, start + 11) & ~0x0000ff0000ff0000L) | 0x0000300000300000L;
    long timeDigits = timeText - ASCII_ZEROES;
    if (((timeDigits | (ASCII_NINES - timeText)) & ASCII_HIGH_BITS) != 0) {
      return null;
    }
    // HH:mm:ss starts its digit pairs in byte lanes 0, 3, and 6. Each validated pair is at most
    // 99, so multiplying the three tens lanes together cannot carry into another result.
    long timePairs =
        (timeDigits & 0x00ff0000ff0000ffL) * 10 + ((timeDigits >>> 8) & 0x00ff0000ff0000ffL);
    int hour = (int) timePairs & 0xff;
    int minute = (int) (timePairs >>> 24) & 0xff;
    int second = (int) (timePairs >>> 48) & 0xff;
    // Validate the UTC components once, without constructing local date/time carriers whose
    // factories repeat range checks. ISO_INSTANT's leap seconds and 24:00 stay with its parser.
    if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 59) {
      return null;
    }
    if (day > 28) {
      int lastDay;
      if (month == 2) {
        lastDay = (year & 3) == 0 && (year % 100 != 0 || year % 400 == 0) ? 29 : 28;
      } else {
        lastDay = month == 4 || month == 6 || month == 9 || month == 11 ? 30 : 31;
      }
      if (day > lastDay) {
        return null;
      }
    }
    int end = start + 19;
    int nano = 0;
    position = end;
    if (bytes[end] == '.') {
      nano = readFractionNanos(end + 1);
      end = position;
    }
    if (end > limit - 2 || bytes[end] != 'Z' || bytes[end + 1] != '"') {
      return null;
    }
    int epochDay = epochDay(year, month, day);
    position = end + 2;
    return instant(epochDay * 86400L + hour * 3600 + minute * 60 + second, nano);
  }

  private static int epochDay(int year, int month, int day) {
    // Both component parsers prove a four-digit year. Neri and Schneider, Proposition 6.2:
    // https://arxiv.org/abs/2102.06959.
    // Moving four-digit years forward one Gregorian cycle keeps January/February of year zero
    // nonnegative. The epoch adjustment removes that cycle, and every product fits an int.
    int marchYear = year + 400;
    int marchMonth = month;
    if (month <= 2) {
      marchYear--;
      marchMonth += 12;
    }
    int century = marchYear / 100;
    return ((1461 * marchYear) >>> 2)
        - century
        + (century >>> 2)
        + ((979 * marchMonth - 2919) >>> 5)
        + day
        - 865566;
  }

  private static Instant instant(long seconds, int nano) {
    // The UTF-8 component parser already proved 0 <= nano < 1_000_000_000. The JDK factory
    // preserves its range and EPOCH handling without normalizing this fraction a second time.
    if (INSTANT_FACTORY == null) {
      return Instant.ofEpochSecond(seconds, nano);
    }
    try {
      return (Instant) INSTANT_FACTORY.invokeExact(seconds, nano);
    } catch (ThreadDeath e) {
      throw e;
    } catch (VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot construct JSON instant", e);
    }
  }

  private static MethodHandle instantFactory() {
    if (AndroidSupport.IS_ANDROID || GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return null;
    }
    try {
      return _JDKAccess._trustedLookup(Instant.class)
          .findStatic(
              Instant.class, "create", MethodType.methodType(Instant.class, long.class, int.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      return null;
    }
  }

  @Override
  public Duration readDuration() {
    skipWhitespaceFast();
    int mark = position;
    try {
      Duration value = tryReadDuration();
      if (value != null) {
        return value;
      }
    } catch (ArithmeticException e) {
      // The existing text path owns overflow reporting along with the remaining ISO forms.
    }
    position = mark;
    return super.readDuration();
  }

  private Duration tryReadDuration() {
    byte[] bytes = input;
    int limit = inputLimit;
    int offset = position;
    if (offset > limit - 6
        || bytes[offset] != '"'
        || bytes[offset + 1] != 'P'
        || bytes[offset + 2] != 'T') {
      return null;
    }
    offset += 3;
    int previousUnit = 0;
    long seconds = 0;
    int nanos = 0;
    while (offset < limit && bytes[offset] != '"') {
      boolean negative = bytes[offset] == '-';
      if (negative) {
        offset++;
      }
      int start = offset;
      long component = 0;
      while (offset < limit) {
        int digit = bytes[offset] - '0';
        if (digit < 0 || digit > 9) {
          break;
        }
        component = Math.subtractExact(Math.multiplyExact(component, 10L), digit);
        offset++;
      }
      if (offset == start || offset == limit) {
        return null;
      }
      if (!negative) {
        component = Math.negateExact(component);
      }
      int suffix = bytes[offset++];
      if (suffix == '.') {
        nanos = readFractionNanos(offset);
        offset = position;
        if (offset == limit || bytes[offset++] != 'S') {
          return null;
        }
        // Keep the lexical sign even when the seconds component is negative zero.
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
    if (previousUnit == 0 || offset == limit) {
      return null;
    }
    Duration value = Duration.ofSeconds(seconds, nanos);
    position = offset + 1;
    return value;
  }

  @Override
  public Period readPeriod() {
    skipWhitespaceFast();
    int mark = position;
    try {
      Period value = tryReadPeriod();
      if (value != null) {
        return value;
      }
    } catch (ForyJsonException e) {
      // ISO components also allow plus signs and leading zeros, unlike JSON integers.
    }
    position = mark;
    return super.readPeriod();
  }

  private Period tryReadPeriod() {
    byte[] bytes = input;
    int limit = inputLimit;
    int offset = position;
    if (offset > limit - 5 || bytes[offset] != '"' || bytes[offset + 1] != 'P') {
      return null;
    }
    position = offset + 2;
    int previousUnit = 0;
    int years = 0;
    int months = 0;
    int days = 0;
    while (position < limit && bytes[position] != '"') {
      int amount = readIntToken();
      if (position == limit) {
        return null;
      }
      int suffix = bytes[position++];
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
    if (previousUnit == 0 || position == limit) {
      return null;
    }
    position++;
    return Period.of(years, months, days);
  }

  @Override
  public ZoneOffset readZoneOffset() {
    skipWhitespaceFast();
    int mark = position;
    if (mark < inputLimit && input[mark] == '"') {
      position = mark + 1;
      ZoneOffset offset = tryReadOffset();
      if (offset != null && position < inputLimit && input[position] == '"') {
        position++;
        return offset;
      }
    }
    position = mark;
    return super.readZoneOffset();
  }

  @Override
  public OffsetTime readOffsetTime() {
    skipWhitespaceFast();
    int mark = position;
    if (mark < inputLimit && input[mark] == '"') {
      LocalTime time = tryReadTime(mark + 1);
      if (time != null) {
        ZoneOffset offset = tryReadOffset();
        if (offset != null && position < inputLimit && input[position] == '"') {
          position++;
          return OffsetTime.of(time, offset);
        }
      }
    }
    position = mark;
    return super.readOffsetTime();
  }

  @Override
  public ZonedDateTime readZonedDateTime() {
    skipWhitespaceFast();
    int mark = position;
    LocalDateTime dateTime = tryReadDateTime();
    if (dateTime != null) {
      ZoneOffset offset = tryReadOffset();
      if (offset != null && position < inputLimit) {
        if (input[position] == '"') {
          position++;
          return ZonedDateTime.ofInstant(dateTime, offset, offset);
        }
        if (input[position] == '[') {
          int start = position + 1;
          int end = start;
          // ZoneId rejects quotes and control characters as part of its name validation.
          // Only a JSON escape needs the text fallback before the bracketed ID is materialized.
          while (end < inputLimit && input[end] != ']' && input[end] != '\\') {
            end++;
          }
          if (end + 1 < inputLimit && input[end] == ']' && input[end + 1] == '"') {
            ZoneId zone = ZoneId.of(newLatin1String(start, end));
            position = end + 2;
            return zonedDateTime(dateTime, offset, zone);
          }
        }
      }
    }
    position = mark;
    return super.readZonedDateTime();
  }

  private static ZonedDateTime zonedDateTime(
      LocalDateTime dateTime, ZoneOffset offset, ZoneId zone) {
    if (ZONED_DATE_TIME_CONSTRUCTOR == null) {
      return ZonedDateTime.ofInstant(dateTime, offset, zone);
    }
    // The explicit offset determines the instant, including gaps and overlaps. An equal offset
    // at that instant proves the parsed local date/time is already correct, so it can be reused.
    long seconds =
        epochDay(dateTime.getYear(), dateTime.getMonthValue(), dateTime.getDayOfMonth()) * 86400L
            + dateTime.toLocalTime().toSecondOfDay()
            - offset.getTotalSeconds();
    Instant instant = Instant.ofEpochSecond(seconds, dateTime.getNano());
    if (!zone.getRules().getOffset(instant).equals(offset)) {
      return ZonedDateTime.ofInstant(instant, zone);
    }
    try {
      return (ZonedDateTime) ZONED_DATE_TIME_CONSTRUCTOR.invokeExact(dateTime, offset, zone);
    } catch (ThreadDeath | VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot construct JSON zoned date-time", e);
    }
  }

  private static MethodHandle zonedDateTimeConstructor() {
    if (AndroidSupport.IS_ANDROID || GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return null;
    }
    try {
      return _JDKAccess._trustedLookup(ZonedDateTime.class)
          .findConstructor(
              ZonedDateTime.class,
              MethodType.methodType(
                  void.class, LocalDateTime.class, ZoneOffset.class, ZoneId.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      return null;
    }
  }

  @Override
  public Year readYear() {
    skipWhitespaceFast();
    int offset = position;
    byte[] bytes = input;
    if (offset <= inputLimit - 6 && bytes[offset] == '"' && bytes[offset + 5] == '"') {
      int year = parseFourDigits(bytes, offset + 1, inputLimit);
      if (year >= 0) {
        position = offset + 6;
        return Year.of(year);
      }
    }
    return super.readYear();
  }

  @Override
  public YearMonth readYearMonth() {
    skipWhitespaceFast();
    int offset = position;
    byte[] bytes = input;
    if (offset <= inputLimit - 9
        && bytes[offset] == '"'
        && bytes[offset + 5] == '-'
        && bytes[offset + 8] == '"') {
      int year = parseFourDigits(bytes, offset + 1, inputLimit);
      int month = parse2(bytes, offset + 6);
      if (year >= 0 && month >= 0) {
        position = offset + 9;
        return yearMonth(year, month);
      }
    }
    return super.readYearMonth();
  }

  private static YearMonth yearMonth(int year, int month) {
    // The UTF-8 token parser proves a four-digit year. Validate the month before passing these
    // components to the constructor, which stores them without repeating the year range check.
    if (YEAR_MONTH_CONSTRUCTOR == null || month < 1 || month > 12) {
      return YearMonth.of(year, month);
    }
    try {
      return (YearMonth) YEAR_MONTH_CONSTRUCTOR.invokeExact(year, month);
    } catch (ThreadDeath e) {
      throw e;
    } catch (VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot construct JSON year-month", e);
    }
  }

  private static MethodHandle yearMonthConstructor() {
    if (AndroidSupport.IS_ANDROID || GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return null;
    }
    try {
      return _JDKAccess._trustedLookup(YearMonth.class)
          .findConstructor(
              YearMonth.class, MethodType.methodType(void.class, int.class, int.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      return null;
    }
  }

  @Override
  public MonthDay readMonthDay() {
    skipWhitespaceFast();
    int offset = position;
    byte[] bytes = input;
    if (offset <= inputLimit - 9) {
      long word = LittleEndian.getInt64(bytes, offset);
      if ((word & 0x0000ff0000ffffffL) == 0x00002d00002d2d22L && bytes[offset + 8] == '"') {
        // The nine-byte token proof covers the word and closing quote. Pack MM and DD together
        // after checking their separators; validated digit pairs cannot carry across lanes.
        int text = ((int) (word >>> 24) & 0xffff) | ((int) (word >>> 32) & 0xffff0000);
        int digits = text - (int) ASCII_ZEROES;
        if (((digits | ((int) ASCII_NINES - text)) & INT_BYTE_HIGH_BITS) == 0) {
          int pairs = (digits & 0x00ff00ff) * 10 + ((digits >>> 8) & 0x00ff00ff);
          position = offset + 9;
          return monthDay(pairs & 0xffff, pairs >>> 16);
        }
      }
    }
    return super.readMonthDay();
  }

  private static MonthDay monthDay(int month, int day) {
    // Every month contains days 1 through 28. The JDK factory keeps ownership of the remaining
    // calendar cases, including February 29, and of invalid components.
    if (MONTH_DAY_CONSTRUCTOR == null || month < 1 || month > 12 || day < 1 || day > 28) {
      return MonthDay.of(month, day);
    }
    try {
      return (MonthDay) MONTH_DAY_CONSTRUCTOR.invokeExact(month, day);
    } catch (ThreadDeath e) {
      throw e;
    } catch (VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot construct JSON month-day", e);
    }
  }

  private static MethodHandle monthDayConstructor() {
    if (AndroidSupport.IS_ANDROID || GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return null;
    }
    try {
      return _JDKAccess._trustedLookup(MonthDay.class)
          .findConstructor(MonthDay.class, MethodType.methodType(void.class, int.class, int.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      return null;
    }
  }

  public LocalDate readIsoLocalDate() {
    skipWhitespaceFast();
    int mark = position;
    LocalDate value = tryReadIsoLocalDateToken();
    if (value != null) {
      return value;
    }
    position = mark;
    return readIsoLocalDateFallback(readQuotedTextValue());
  }

  public OffsetDateTime readIsoOffsetDateTime() {
    skipWhitespaceFast();
    int mark = position;
    OffsetDateTime value = tryReadIsoOffsetDateTimeToken();
    if (value != null) {
      return value;
    }
    position = mark;
    return readIsoOffsetDateTimeFallback(readQuotedTextValue());
  }

  private String readStringToken() {
    byte[] bytes = input;
    int inputLimit = this.inputLimit;
    if (position >= inputLimit || bytes[position++] != '"') {
      throw error("Expected string");
    }
    int start = position;
    int offset = start;
    // Keep seven real bounded probes in the token owner. Besides covering ordinary Strings through
    // 56 bytes without a helper call, this keeps the complete scanner behind a natural C2 boundary
    // so nullable wrappers and generated object readers cannot absorb duplicate token closures.
    // A loop or forwarding helper would shrink this owner and restore compilation-order
    // sensitivity.
    if (offset + Long.BYTES <= inputLimit) {
      long stopMask = stringStopMask(LittleEndian.getInt64(bytes, offset));
      if (stopMask != 0) {
        return readStringWordStop(start, offset, stopMask);
      }
      offset += Long.BYTES;
      if (offset + Long.BYTES <= inputLimit) {
        stopMask = stringStopMask(LittleEndian.getInt64(bytes, offset));
        if (stopMask != 0) {
          return readStringWordStop(start, offset, stopMask);
        }
        offset += Long.BYTES;
        if (offset + Long.BYTES <= inputLimit) {
          stopMask = stringStopMask(LittleEndian.getInt64(bytes, offset));
          if (stopMask != 0) {
            return readStringWordStop(start, offset, stopMask);
          }
          offset += Long.BYTES;
          if (offset + Long.BYTES <= inputLimit) {
            stopMask = stringStopMask(LittleEndian.getInt64(bytes, offset));
            if (stopMask != 0) {
              return readStringWordStop(start, offset, stopMask);
            }
            offset += Long.BYTES;
            if (offset + Long.BYTES <= inputLimit) {
              stopMask = stringStopMask(LittleEndian.getInt64(bytes, offset));
              if (stopMask != 0) {
                return readStringWordStop(start, offset, stopMask);
              }
              offset += Long.BYTES;
              if (offset + Long.BYTES <= inputLimit) {
                stopMask = stringStopMask(LittleEndian.getInt64(bytes, offset));
                if (stopMask != 0) {
                  return readStringWordStop(start, offset, stopMask);
                }
                offset += Long.BYTES;
                if (offset + Long.BYTES <= inputLimit) {
                  stopMask = stringStopMask(LittleEndian.getInt64(bytes, offset));
                  if (stopMask != 0) {
                    return readStringWordStop(start, offset, stopMask);
                  }
                  offset += Long.BYTES;
                }
              }
            }
          }
        }
      }
    }
    return readStringTokenLongTail(start, offset, inputLimit);
  }

  private String readStringWordStop(int start, int offset, long stopMask) {
    int stop = offset + (Long.numberOfTrailingZeros(stopMask) >>> 3);
    int b = input[stop];
    if (b == '"') {
      position = stop + 1;
      return newLatin1String(start, stop);
    }
    return readStringStop(start, stop, b);
  }

  /** Returns the exclusive UTF-8 input limit to generated bounded String probes. */
  @Internal
  public int inputLimit() {
    return inputLimit;
  }

  /** Scans one in-bounds generated String word without publishing the reader cursor. */
  @Internal
  public long scanStringWord(int offset) {
    return stringStopMask(LittleEndian.getInt64(input, offset));
  }

  /** Finishes a generated String after a bounded word probe finds its first stop byte. */
  @Internal
  public String finishStringWord(int start, int offset, long stopMask) {
    return readStringWordStop(start, offset, stopMask);
  }

  /** Continues a String after generated bounded word probes found no stop byte. */
  @Internal
  public String readStringTokenLongTail(int start, int offset) {
    return readStringTokenLongTail(start, offset, inputLimit);
  }

  private String readStringTokenLongTail(int start, int offset, int inputLimit) {
    byte[] bytes = input;
    int doubleWordEnd = inputLimit - (Long.BYTES << 1);
    while (offset <= doubleWordEnd) {
      long stopMask = stringStopMask(LittleEndian.getInt64(bytes, offset));
      if (stopMask != 0) {
        int stop = offset + (Long.numberOfTrailingZeros(stopMask) >>> 3);
        int b = bytes[stop];
        if (b == '"') {
          position = stop + 1;
          return newLatin1String(start, stop);
        }
        return readStringStop(start, stop, b);
      }
      int nextOffset = offset + Long.BYTES;
      stopMask = stringStopMask(LittleEndian.getInt64(bytes, nextOffset));
      if (stopMask != 0) {
        int stop = nextOffset + (Long.numberOfTrailingZeros(stopMask) >>> 3);
        int b = bytes[stop];
        if (b == '"') {
          position = stop + 1;
          return newLatin1String(start, stop);
        }
        return readStringStop(start, stop, b);
      }
      offset = nextOffset + Long.BYTES;
    }
    int wordEnd = inputLimit - Long.BYTES;
    while (offset <= wordEnd) {
      long stopMask = stringStopMask(LittleEndian.getInt64(bytes, offset));
      if (stopMask == 0) {
        offset += Long.BYTES;
        continue;
      }
      int stop = offset + (Long.numberOfTrailingZeros(stopMask) >>> 3);
      int b = bytes[stop];
      if (b == '"') {
        position = stop + 1;
        return newLatin1String(start, stop);
      }
      return readStringStop(start, stop, b);
    }
    return readStringTokenTail(start, offset, inputLimit);
  }

  private String readStringTokenTail(int start, int offset, int inputLimit) {
    byte[] bytes = input;
    // Reached only when the whole input has fewer than eight bytes left. Keep this rare tail out
    // of the hot word scanner so C2 has more budget for common string call sites.
    if (offset + Integer.BYTES <= inputLimit) {
      int stopMask = stringStopMask(LittleEndian.getInt32(bytes, offset));
      if (stopMask == 0) {
        offset += Integer.BYTES;
      } else {
        int stop = offset + (Integer.numberOfTrailingZeros(stopMask) >>> 3);
        int b = bytes[stop];
        if (b == '"') {
          position = stop + 1;
          return newLatin1String(start, stop);
        }
        return readStringStop(start, stop, b);
      }
    }
    while (offset < inputLimit) {
      int b = bytes[offset++];
      if (b == '"') {
        position = offset;
        return newLatin1String(start, offset - 1);
      }
      if (b == '\\') {
        return readStringStop(start, offset - 1, b);
      }
      if (b < 0) {
        return readStringStop(start, offset - 1, b);
      }
      if (b < 0x20) {
        position = offset;
        throw error("Control character in string");
      }
    }
    throw error("Unterminated string");
  }

  private LocalDate tryReadIsoLocalDateToken() {
    byte[] bytes = input;
    int offset = position;
    int length = inputLimit;
    if (offset > length - 12 || bytes[offset] != '"') {
      return null;
    }
    offset++;
    int dateStart = offset;
    if (bytes[dateStart + 4] != '-' || bytes[dateStart + 7] != '-') {
      return null;
    }
    int year = parseFourDigits(bytes, dateStart, inputLimit);
    int month = parse2(bytes, dateStart + 5);
    int day = parse2(bytes, dateStart + 8);
    if (year < 0 || month < 0 || day < 0) {
      return null;
    }
    int end = dateStart + 10;
    int ch = bytes[end];
    if (ch == '"') {
      position = end + 1;
      return localDate(year, month, day);
    }
    if (ch == 'T') {
      int stringEnd = tryScanSimpleStringTail(bytes, end + 1);
      if (stringEnd < 0) {
        return null;
      }
      position = stringEnd;
      return localDate(year, month, day);
    }
    return null;
  }

  private OffsetDateTime tryReadIsoOffsetDateTimeToken() {
    LocalDateTime dateTime = tryReadDateTime();
    if (dateTime != null) {
      ZoneOffset offset = tryReadOffset();
      if (offset != null && position < inputLimit && input[position] == '"') {
        position++;
        return OffsetDateTime.of(dateTime, offset);
      }
    }
    return null;
  }

  private LocalDateTime tryReadDateTime() {
    byte[] bytes = input;
    int start = position + 1;
    if (start > inputLimit - 16
        || bytes[start - 1] != '"'
        || bytes[start + 4] != '-'
        || bytes[start + 7] != '-'
        || bytes[start + 10] != 'T') {
      return null;
    }
    LocalTime time = tryReadTime(start + 11);
    if (time == null) {
      return null;
    }
    int year = parseFourDigits(bytes, start, inputLimit);
    int month = parse2(bytes, start + 5);
    int day = parse2(bytes, start + 8);
    if (year < 0 || month < 0 || day < 0) {
      return null;
    }
    return LocalDateTime.of(localDate(year, month, day), time);
  }

  private static LocalDate localDate(int year, int month, int day) {
    // Both UTF-8 date parsers prove a four-digit year. Check the month/day ranges before the
    // JDK factory, which still owns the calendar validation for short months and leap years.
    if (LOCAL_DATE_FACTORY == null || month < 1 || month > 12 || day < 1 || day > 31) {
      return LocalDate.of(year, month, day);
    }
    try {
      return (LocalDate) LOCAL_DATE_FACTORY.invokeExact(year, month, day);
    } catch (ThreadDeath e) {
      throw e;
    } catch (VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot construct JSON local date", e);
    }
  }

  private static MethodHandle localDateFactory() {
    if (AndroidSupport.IS_ANDROID || GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return null;
    }
    try {
      return _JDKAccess._trustedLookup(LocalDate.class)
          .findStatic(
              LocalDate.class,
              "create",
              MethodType.methodType(LocalDate.class, int.class, int.class, int.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      return null;
    }
  }

  private LocalTime tryReadTime(int start) {
    byte[] bytes = input;
    int limit = inputLimit;
    if (start > limit - 5 || bytes[start + 2] != ':') {
      return null;
    }
    int hour;
    int minute;
    int second = 0;
    int nano = 0;
    int end;
    boolean hasSeconds = start <= limit - 8 && bytes[start + 5] == ':';
    if (hasSeconds) {
      long text = LittleEndian.getInt64(bytes, start);
      // Replace the two known colons with zero digits before validating all six digit lanes.
      long digitText = (text & ~0x0000ff0000ff0000L) | 0x0000300000300000L;
      long digits = digitText - ASCII_ZEROES;
      if (((digits | (ASCII_NINES - digitText)) & ASCII_HIGH_BITS) != 0) {
        return null;
      }
      hour = (int) (digits & 0xff) * 10 + (int) ((digits >>> 8) & 0xff);
      minute = (int) ((digits >>> 24) & 0xff) * 10 + (int) ((digits >>> 32) & 0xff);
      second = (int) ((digits >>> 48) & 0xff) * 10 + (int) (digits >>> 56);
      end = start + 8;
    } else {
      hour = parse2(bytes, start);
      minute = parse2(bytes, start + 3);
      end = start + 5;
    }
    position = end;
    if (hasSeconds && end < limit && bytes[end] == '.') {
      nano = readFractionNanos(end + 1);
    }
    if (hour < 0 || hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 59) {
      return null;
    }
    return localTime(hour, minute, second, nano);
  }

  private static LocalTime localTime(int hour, int minute, int second, int nano) {
    // tryReadTime validates the clock components, and readFractionNanos consumes at most nine
    // digits. The JDK factory retains whole-hour reuse without validating those ranges again.
    if (LOCAL_TIME_FACTORY == null) {
      return LocalTime.of(hour, minute, second, nano);
    }
    try {
      return (LocalTime) LOCAL_TIME_FACTORY.invokeExact(hour, minute, second, nano);
    } catch (ThreadDeath e) {
      throw e;
    } catch (VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot construct JSON local time", e);
    }
  }

  private static MethodHandle localTimeFactory() {
    if (AndroidSupport.IS_ANDROID || GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return null;
    }
    try {
      return _JDKAccess._trustedLookup(LocalTime.class)
          .findStatic(
              LocalTime.class,
              "create",
              MethodType.methodType(LocalTime.class, int.class, int.class, int.class, int.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      return null;
    }
  }

  private int readFractionNanos(int start) {
    byte[] bytes = input;
    int inputLimit = this.inputLimit;
    if (start <= inputLimit - 8) {
      long chunk = LittleEndian.getInt64(bytes, start);
      long digits = chunk - ASCII_ZEROES;
      long stopMask = (digits | (ASCII_NINES - chunk)) & ASCII_HIGH_BITS;
      if (stopMask != 0) {
        int count = Long.numberOfTrailingZeros(stopMask) >>> 3;
        // Valid prefix lanes cannot borrow. Clear the suffix to pad the fraction on the right;
        // eight decimal places followed by one zero give nanoseconds without a variable scale.
        digits &= (1L << (count << 3)) - 1;
        position = start + count;
        return combineEightDigits(digits) * 10;
      }
      int nano = combineEightDigits(digits) * 10;
      int end = start + 8;
      if (end < inputLimit) {
        int last = bytes[end] - '0';
        if (last >= 0 && last <= 9) {
          nano += last;
          end++;
        }
      }
      position = end;
      return nano;
    }
    int end = start;
    int limit = Math.min(inputLimit, start + 9);
    int nano = 0;
    while (end < limit) {
      int digit = bytes[end] - '0';
      if (digit < 0 || digit > 9) {
        break;
      }
      nano = nano * 10 + digit;
      end++;
    }
    position = end;
    return nano * NANO_SCALE[9 - end + start];
  }

  private ZoneOffset tryReadOffset() {
    byte[] bytes = input;
    int start = position;
    int limit = inputLimit;
    if (start >= limit) {
      return null;
    }
    if (bytes[start] == 'Z') {
      position = start + 1;
      return ZoneOffset.UTC;
    }
    int sign = bytes[start];
    if ((sign != '+' && sign != '-') || start > limit - 6 || bytes[start + 3] != ':') {
      return null;
    }
    // The six-byte prefix bounds the word load. Pack HH:mm's four digit lanes together;
    // the intervening colon was checked above and does not participate in digit arithmetic.
    int text = LittleEndian.getInt32(bytes, start + 2);
    int digitText = (text & 0xffff0000) | ((text & 0xff) << 8) | (bytes[start + 1] & 0xff);
    int digits = digitText - (int) ASCII_ZEROES;
    if (((digits | ((int) ASCII_NINES - digitText)) & INT_BYTE_HIGH_BITS) != 0) {
      return null;
    }
    int pairs = (digits * 10 + (digits >>> 8)) & 0x00ff00ff;
    int hours = pairs & 0xff;
    int minutes = pairs >>> 16;
    int seconds = 0;
    int end = start + 6;
    if (end < limit && bytes[end] == ':') {
      if (end > limit - 3) {
        return null;
      }
      seconds = parse2(bytes, end + 1);
      end += 3;
    }
    if (minutes > 59 || seconds < 0 || seconds > 59) {
      return null;
    }
    int total = hours * 3600 + minutes * 60 + seconds;
    ZoneOffset offset = ZoneOffset.ofTotalSeconds(sign == '-' ? -total : total);
    position = end;
    return offset;
  }

  private int tryScanSimpleStringTail(byte[] bytes, int offset) {
    int length = inputLimit;
    while (offset < length) {
      int b = bytes[offset++];
      if (b == '"') {
        return offset;
      }
      if (b == '\\' || b < 0x20 || b < 0) {
        return -1;
      }
    }
    throw error("Unterminated string");
  }

  private static int parse2(byte[] bytes, int index) {
    int chunk = (bytes[index] & 0xff) | ((bytes[index + 1] & 0xff) << 8);
    // Check both ASCII lanes together; valid digits cannot borrow across lanes.
    int digits = chunk - 0x3030;
    if (((digits | (0x3939 - chunk)) & 0x8080) != 0) {
      // A JSON escape can occur inside a digit pair; let the decoded-text parser handle it.
      return -1;
    }
    return (digits & 0xff) * 10 + (digits >>> 8);
  }

  private static boolean isDigit(byte b) {
    return b >= '0' && b <= '9';
  }

  private String readStringStop(int start, int stop, int b) {
    position = stop + 1;
    int out = stop - start;
    byte[] bytes = stringDecodeBuffer;
    if (out == 0 && b < 0) {
      int first = b & 0xFF;
      if ((first & 0xF0) == 0xE0 || (first & 0xF8) == 0xF0) {
        return readStringUtf16FromFirst(bytes, first);
      }
    }
    if (bytes.length < out) {
      bytes = growStringDecodeBuffer(bytes, out);
    }
    System.arraycopy(input, start, bytes, 0, out);
    return readStringLatin1Tail(bytes, out, b);
  }

  private static long stringStopMask(long word) {
    // UTF-8 mode stops on every high-bit byte, and readStringToken only uses the first stop bit.
    // Subtraction borrow may only create later high bits after an earlier real stop, so the
    // compact syntax/range expression preserves the first-stop position. Latin1JsonReader cannot
    // use this shortcut because high-bit Latin-1 bytes are valid string payload.
    // XOR by 2 preserves control bytes below 0x20 and maps quote 0x22 to 0x20. One relaxed
    // byte-lane comparison against 0x21 can therefore cover both cases without a separate quote
    // zero detector; printable bytes before the first stop remain at or above the limit.
    long quoteOrControl = (word ^ BYTE_TWOS) - QUOTE_CONTROL_LIMIT_BYTES;
    long backslash = (word ^ BACKSLASH_BYTES) - BYTE_ONES;
    return (quoteOrControl | backslash | word) & BYTE_HIGH_BITS;
  }

  private static int stringStopMask(int word) {
    int quoteOrControl = (word ^ INT_BYTE_TWOS) - INT_QUOTE_CONTROL_LIMIT_BYTES;
    int backslash = (word ^ INT_BACKSLASH_BYTES) - INT_BYTE_ONES;
    return (quoteOrControl | backslash | word) & INT_BYTE_HIGH_BITS;
  }

  @Override
  public JsonFieldInfo readField(JsonFieldTable table) {
    return table.get(readFieldNameHash());
  }

  @Override
  public int readFieldIndex(JsonFieldTable table) {
    return table.index(readFieldNameHash());
  }

  @Override
  public int readFieldIndex(JsonFieldTable table, long expectedHash, int expectedIndex) {
    long hash = readFieldNameHash();
    return hash == expectedHash ? expectedIndex : table.index(hash);
  }

  @Override
  public long readFieldNameHash() {
    return readQuotedStringHash();
  }

  /**
   * Returns the raw four-byte prefix at the next field name after consuming legal whitespace.
   *
   * <p>Generated object readers use this only as a discriminator before a complete field-token
   * check. A miss leaves the name unread so the ordinary hash parser retains escape, UTF-8, alias,
   * unknown-field, and malformed-input handling.
   */
  @Internal
  public int readFieldNamePrefix() {
    skipWhitespaceFast();
    int offset = position;
    if (offset <= inputLimit - Integer.BYTES) {
      return LittleEndian.getInt32(input, offset);
    }
    return 0;
  }

  public boolean tryReadFieldNameColon(long expectedHash, long expectedMask, int expectedLength) {
    int mark = position;
    skipWhitespaceFast();
    return tryReadFieldNameColonAt(mark, expectedHash, expectedMask, expectedLength);
  }

  public boolean tryReadNextFieldNameColon(
      long expectedHash, long expectedMask, int expectedLength) {
    int mark = position;
    if (mark < inputLimit) {
      int ch = input[mark];
      if (ch == '"') {
        return tryReadFieldNameColonAt(mark, expectedHash, expectedMask, expectedLength);
      }
      if (ch > ' ' || !isWhitespace(ch)) {
        return false;
      }
    }
    return tryReadFieldNameColon(expectedHash, expectedMask, expectedLength);
  }

  public boolean tryReadNextFieldNameToken0(long prefix, long prefixMask, int tokenLength) {
    return tryReadNextRawToken0(prefix, prefixMask, tokenLength);
  }

  public boolean tryReadNextStringToken0(long prefix, long prefixMask, int tokenLength) {
    return tryReadNextRawToken0(prefix, prefixMask, tokenLength);
  }

  private boolean tryReadNextRawToken0(long prefix, long prefixMask, int tokenLength) {
    byte[] bytes = input;
    int mark = position;
    if (mark <= inputLimit - Long.BYTES
        && (LittleEndian.getInt64(bytes, mark) & prefixMask) == prefix) {
      position = mark + tokenLength;
      return true;
    }
    return false;
  }

  public boolean tryReadNextFieldNameToken1(
      long prefix, long prefixMask, int suffix, int tokenLength) {
    return tryReadNextRawToken1(prefix, prefixMask, suffix, tokenLength);
  }

  public boolean tryReadNextStringToken1(
      long prefix, long prefixMask, int suffix, int tokenLength) {
    return tryReadNextRawToken1(prefix, prefixMask, suffix, tokenLength);
  }

  private boolean tryReadNextRawToken1(long prefix, long prefixMask, int suffix, int tokenLength) {
    byte[] bytes = input;
    int mark = position;
    int suffixOffset = mark + Long.BYTES;
    if (tokenLength <= inputLimit - mark
        && (LittleEndian.getInt64(bytes, mark) & prefixMask) == prefix
        && bytes[suffixOffset] == suffix) {
      position = mark + tokenLength;
      return true;
    }
    return false;
  }

  public boolean tryReadNextFieldNameToken2(
      long prefix, long prefixMask, int suffix, int tokenLength) {
    return tryReadNextRawToken2(prefix, prefixMask, suffix, tokenLength);
  }

  public boolean tryReadNextStringToken2(
      long prefix, long prefixMask, int suffix, int tokenLength) {
    return tryReadNextRawToken2(prefix, prefixMask, suffix, tokenLength);
  }

  private boolean tryReadNextRawToken2(long prefix, long prefixMask, int suffix, int tokenLength) {
    byte[] bytes = input;
    int mark = position;
    int suffixOffset = mark + Long.BYTES;
    if (tokenLength <= inputLimit - mark
        && (LittleEndian.getInt64(bytes, mark) & prefixMask) == prefix
        && ((bytes[suffixOffset] & 0xFF) | ((bytes[suffixOffset + 1] & 0xFF) << 8)) == suffix) {
      position = mark + tokenLength;
      return true;
    }
    return false;
  }

  public boolean tryReadNextFieldNameToken3(
      long prefix, long prefixMask, int suffix, int tokenLength) {
    return tryReadNextRawToken3(prefix, prefixMask, suffix, tokenLength);
  }

  public boolean tryReadNextStringToken3(
      long prefix, long prefixMask, int suffix, int tokenLength) {
    return tryReadNextRawToken3(prefix, prefixMask, suffix, tokenLength);
  }

  private boolean tryReadNextRawToken3(long prefix, long prefixMask, int suffix, int tokenLength) {
    byte[] bytes = input;
    int mark = position;
    int suffixOffset = mark + Long.BYTES;
    if (tokenLength <= inputLimit - mark
        && (LittleEndian.getInt64(bytes, mark) & prefixMask) == prefix
        && ((bytes[suffixOffset] & 0xFF)
                | ((bytes[suffixOffset + 1] & 0xFF) << 8)
                | ((bytes[suffixOffset + 2] & 0xFF) << 16))
            == suffix) {
      position = mark + tokenLength;
      return true;
    }
    return false;
  }

  public boolean tryReadNextFieldNameToken8(
      long prefix, long suffix, long suffixMask, int tokenLength) {
    return tryReadNextRawToken8(prefix, suffix, suffixMask, tokenLength);
  }

  private boolean tryReadNextRawToken8(long prefix, long suffix, long suffixMask, int tokenLength) {
    byte[] bytes = input;
    int mark = position;
    int suffixOffset = mark + Long.BYTES;
    if (tokenLength <= inputLimit - mark
        && LittleEndian.getInt64(bytes, mark) == prefix
        && readTokenSuffix(bytes, suffixOffset, tokenLength, suffixMask, inputLimit) == suffix) {
      position = mark + tokenLength;
      return true;
    }
    return false;
  }

  private static long readTokenSuffix(
      byte[] bytes, int suffixOffset, int tokenLength, long suffixMask, int inputLimit) {
    if (suffixOffset <= inputLimit - Long.BYTES) {
      return LittleEndian.getInt64(bytes, suffixOffset) & suffixMask;
    }
    int suffixLength = tokenLength - Long.BYTES;
    long suffix = 0;
    for (int i = 0; i < suffixLength; i++) {
      suffix |= (long) (bytes[suffixOffset + i] & 0xFF) << (i << 3);
    }
    return suffix;
  }

  private boolean tryReadFieldNameColonAt(
      int mark, long expectedHash, long expectedMask, int expectedLength) {
    byte[] bytes = input;
    int offset = position;
    int nameOffset = offset + 1;
    int quoteOffset = nameOffset + expectedLength;
    if (quoteOffset < inputLimit && bytes[offset] == '"') {
      if (nameOffset <= inputLimit - Long.BYTES) {
        if ((LittleEndian.getInt64(bytes, nameOffset) & expectedMask) == expectedHash
            && bytes[quoteOffset] == '"') {
          int colonOffset = quoteOffset + 1;
          if (colonOffset < inputLimit && bytes[colonOffset] == ':') {
            position = colonOffset + 1;
          } else {
            readFieldNameColon(colonOffset);
          }
          return true;
        }
        // Full raw-word misses cannot match this generated packed-name probe. Escaped and UTF8
        // field names are handled by the hash fallback after this direct probe fails.
        position = mark;
        return false;
      }
      offset = nameOffset;
      long value = 0;
      for (int i = 0; i < expectedLength; i++) {
        int ch = bytes[offset++];
        if (ch == 0 || ch == '"' || ch == '\\' || ch < 0x20) {
          position = mark;
          return false;
        }
        value = JsonFieldNameHash.value(value, i, (char) ch);
      }
      if (value == expectedHash && bytes[offset] == '"') {
        int colonOffset = offset + 1;
        if (colonOffset < inputLimit && bytes[colonOffset] == ':') {
          position = colonOffset + 1;
        } else {
          readFieldNameColon(colonOffset);
        }
        return true;
      }
    }
    position = mark;
    return false;
  }

  private void readFieldNameColon(int colonOffset) {
    position = colonOffset;
    expectNextToken(':');
  }

  @Override
  public long readStringHash() {
    return readQuotedStringHash();
  }

  public long readPackedStringHash() {
    skipWhitespaceFast();
    return readPackedStringHashToken();
  }

  public long readNextPackedStringHash() {
    if (position < inputLimit) {
      int ch = input[position];
      if (ch > ' ' || !isWhitespace(ch)) {
        return readPackedStringHashToken();
      }
    }
    return readPackedStringHash();
  }

  public long readPackedStringHashTokenValue() {
    return readPackedStringHashToken();
  }

  private long readPackedStringHashToken() {
    int mark = position;
    byte[] bytes = input;
    int length = inputLimit;
    int offset = position;
    if (offset < length && bytes[offset++] == '"') {
      long value = 0;
      int nameLength = 0;
      while (offset < length) {
        int ch = bytes[offset++];
        if (ch == '"') {
          if (nameLength > 0) {
            position = offset;
            return value;
          }
          break;
        }
        if (ch == 0 || ch == '\\' || ch < 0x20 || nameLength >= Long.BYTES) {
          break;
        }
        value = JsonFieldNameHash.value(value, nameLength++, (char) ch);
      }
    }
    return readQuotedStringHashFromMark(mark);
  }

  private long readQuotedStringHashFromMark(int mark) {
    position = mark;
    return readQuotedStringHashToken();
  }

  private long readQuotedStringHash() {
    skipWhitespaceFast();
    return readQuotedStringHashToken();
  }

  private long readQuotedStringHashToken() {
    byte[] bytes = input;
    int mark = position;
    int nameOffset = mark + 1;
    if (nameOffset < inputLimit - Long.BYTES && bytes[mark] == '"') {
      long word = LittleEndian.getInt64(bytes, nameOffset);
      long stopMask = stringStopMask(word);
      if (stopMask == 0) {
        if (bytes[nameOffset + Long.BYTES] == '"') {
          position = nameOffset + Long.BYTES + 1;
          return word;
        }
      } else {
        int nameLength = Long.numberOfTrailingZeros(stopMask) >>> 3;
        if (nameLength > 0 && ((word >>> (nameLength << 3)) & 0xFF) == '"') {
          position = nameOffset + nameLength + 1;
          return word & ((1L << (nameLength << 3)) - 1);
        }
      }
    }
    return readQuotedStringHashSlow();
  }

  private long readQuotedStringHashSlow() {
    byte[] bytes = input;
    int length = inputLimit;
    int cursor = position;
    if (cursor >= length || bytes[cursor++] != '"') {
      throw error("Expected string");
    }
    long hash = JsonFieldNameHash.MAGIC_HASH_CODE;
    long value = 0;
    int nameLength = 0;
    boolean latin1 = true;
    while (cursor < length) {
      int b = bytes[cursor++] & 0xFF;
      if (b == '"') {
        position = cursor;
        return JsonFieldNameHash.finish(hash, value, nameLength, latin1);
      }
      if (b == '\\') {
        // Escape and UTF-8 decoders own position; ordinary ASCII stays in the local cursor.
        position = cursor;
        b = readEscapedFieldNameChar();
        cursor = position;
        if (Character.isHighSurrogate((char) b)) {
          if (latin1) {
            hash = JsonFieldNameHash.hashPacked(value, nameLength);
            latin1 = false;
          }
          hash = JsonFieldNameHash.update(hash, (char) b);
          nameLength++;
          if (position + 2 > length() || charAt(position) != '\\' || charAt(position + 1) != 'u') {
            throw error("Unpaired high surrogate escape");
          }
          position += 2;
          char low = readUnicodeEscape();
          cursor = position;
          if (!Character.isLowSurrogate(low)) {
            throw error("Unpaired high surrogate escape");
          }
          hash = JsonFieldNameHash.update(hash, low);
          nameLength++;
          continue;
        }
        if (Character.isLowSurrogate((char) b)) {
          throw error("Unpaired low surrogate escape");
        }
      } else {
        if (b < 0x20) {
          throw errorAt("Control character in string", cursor);
        }
        if (b >= 0x80) {
          position = cursor;
          b = readUtf8CodePoint(b);
          cursor = position;
          if (b > 0xFFFF) {
            if (latin1) {
              hash = JsonFieldNameHash.hashPacked(value, nameLength);
              latin1 = false;
            }
            hash = JsonFieldNameHash.update(hash, Character.highSurrogate(b));
            hash = JsonFieldNameHash.update(hash, Character.lowSurrogate(b));
            nameLength += 2;
            continue;
          }
        }
      }
      if (latin1) {
        if (b <= 0xFF && b != 0 && nameLength < Long.BYTES) {
          value = JsonFieldNameHash.value(value, nameLength, (char) b);
          nameLength++;
          continue;
        }
        hash = JsonFieldNameHash.hashPacked(value, nameLength);
        latin1 = false;
      }
      hash = JsonFieldNameHash.update(hash, (char) b);
      nameLength++;
    }
    throw errorAt("Unterminated string", cursor);
  }

  @Override
  protected String slice(int start, int end) {
    return newLatin1String(start, end);
  }

  private String newLatin1String(int start, int end) {
    int length = end - start;
    byte[] bytes = new byte[length];
    System.arraycopy(input, start, bytes, 0, length);
    return StringSerializer.newLatin1StringZeroCopy(bytes);
  }

  private int readUtf8CodePoint(int first) {
    if ((first & 0xE0) == 0xC0) {
      int second = continuation();
      int codePoint = ((first & 0x1F) << 6) | second;
      if (codePoint < 0x80) {
        throw error("Overlong UTF-8 sequence");
      }
      return codePoint;
    } else if ((first & 0xF0) == 0xE0) {
      int second = continuation();
      int third = continuation();
      int codePoint = ((first & 0x0F) << 12) | (second << 6) | third;
      if (codePoint < 0x800 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
        throw error("Invalid UTF-8 sequence");
      }
      return codePoint;
    } else if ((first & 0xF8) == 0xF0) {
      int second = continuation();
      int third = continuation();
      int fourth = continuation();
      int codePoint = ((first & 0x07) << 18) | (second << 12) | (third << 6) | fourth;
      if (codePoint < 0x10000 || codePoint > 0x10FFFF) {
        throw error("Invalid UTF-8 sequence");
      }
      return codePoint;
    }
    throw error("Invalid UTF-8 sequence");
  }

  private String readStringLatin1Tail(byte[] bytes, int out, int b) {
    while (true) {
      if (b == '"') {
        return finishDecodedString(bytes, out, false);
      }
      if (b == '\\') {
        char ch = readEscapedStringChar();
        if (Character.isHighSurrogate(ch)) {
          char low = readLowSurrogateEscape();
          bytes = widenStringDecodeBuffer(bytes, out);
          out <<= 1;
          bytes = ensureStringDecodeCapacity(bytes, out + 4);
          out = putUtf16Char(bytes, out, ch);
          out = putUtf16Char(bytes, out, low);
          return readStringUtf16Tail(bytes, out);
        }
        if (Character.isLowSurrogate(ch)) {
          throw error("Unpaired low surrogate escape");
        }
        if (ch <= 0xFF) {
          bytes = ensureStringDecodeCapacity(bytes, out + 1);
          bytes[out++] = (byte) ch;
        } else {
          bytes = widenStringDecodeBuffer(bytes, out);
          out <<= 1;
          bytes = ensureStringDecodeCapacity(bytes, out + 2);
          out = putUtf16Char(bytes, out, ch);
          return readStringUtf16Tail(bytes, out);
        }
      } else if (b >= 0 && b < 0x20) {
        throw error("Control character in string");
      } else if (b >= 0 && b < 0x80) {
        bytes = ensureStringDecodeCapacity(bytes, out + 1);
        bytes[out++] = (byte) b;
      } else {
        int codePoint = readUtf8CodePoint(b & 0xFF);
        if (codePoint <= 0xFF) {
          bytes = ensureStringDecodeCapacity(bytes, out + 1);
          bytes[out++] = (byte) codePoint;
        } else {
          bytes = widenStringDecodeBuffer(bytes, out);
          out <<= 1;
          if (codePoint <= 0xFFFF) {
            bytes = ensureStringDecodeCapacity(bytes, out + 2);
            out = putUtf16Char(bytes, out, (char) codePoint);
          } else {
            bytes = ensureStringDecodeCapacity(bytes, out + 4);
            out = putUtf16Char(bytes, out, Character.highSurrogate(codePoint));
            out = putUtf16Char(bytes, out, Character.lowSurrogate(codePoint));
          }
          return readStringUtf16Tail(bytes, out);
        }
      }
      if (position >= inputLimit) {
        throw error("Unterminated string");
      }
      b = input[position++] & 0xFF;
    }
  }

  private String readStringUtf16Tail(byte[] bytes, int out) {
    byte[] input = this.input;
    int position = this.position;
    int inputLimit = this.inputLimit;
    int capacity = bytes.length;
    while (position < inputLimit) {
      int b = input[position++] & 0xFF;
      if ((b & 0xF0) == 0xE0) {
        if (position >= inputLimit) {
          this.position = position;
          throw error("Short UTF-8 sequence");
        }
        int second = input[position++] & 0xFF;
        if ((second & 0xC0) != 0x80) {
          this.position = position;
          throw error("Invalid UTF-8 continuation");
        }
        if (position >= inputLimit) {
          this.position = position;
          throw error("Short UTF-8 sequence");
        }
        int third = input[position++] & 0xFF;
        if ((third & 0xC0) != 0x80) {
          this.position = position;
          throw error("Invalid UTF-8 continuation");
        }
        int codePoint = ((b & 0x0F) << 12) | ((second & 0x3F) << 6) | (third & 0x3F);
        if (codePoint < 0x800 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
          this.position = position;
          throw error("Invalid UTF-8 sequence");
        }
        if (out + 2 > capacity) {
          bytes = growStringDecodeBuffer(bytes, out + 2);
          capacity = bytes.length;
        }
        out = putUtf16Char(bytes, out, (char) codePoint);
      } else if (b == '"') {
        this.position = position;
        return finishDecodedString(bytes, out, true);
      } else if (b == '\\') {
        if (position <= inputLimit - 11
            && input[position] == 'u'
            && input[position + 5] == '\\'
            && input[position + 6] == 'u') {
          int first = hexValue4(input, position + 1);
          int second = hexValue4(input, position + 7);
          if ((first | second) >= 0
              && !Character.isSurrogate((char) first)
              && !Character.isSurrogate((char) second)) {
            // Two complete non-surrogate escapes produce exactly four native UTF-16 bytes.
            // Surrogate pairs and malformed escapes retain the scalar validation path below.
            if (out + 4 > capacity) {
              bytes = growStringDecodeBuffer(bytes, out + 4);
              capacity = bytes.length;
            }
            int chars =
                LITTLE_ENDIAN
                    ? first | (second << 16)
                    : Integer.reverseBytes((first << 16) | second);
            LittleEndian.putInt32(bytes, out, chars);
            out += 4;
            position += 11;
            continue;
          }
        }
        this.position = position;
        char ch = readEscapedStringChar();
        position = this.position;
        if (!Character.isSurrogate(ch)) {
          if (out + 2 > capacity) {
            bytes = growStringDecodeBuffer(bytes, out + 2);
            capacity = bytes.length;
          }
          out = putUtf16Char(bytes, out, ch);
        } else {
          if (!Character.isHighSurrogate(ch)) {
            throw error("Unpaired low surrogate escape");
          }
          char low = readLowSurrogateEscape();
          position = this.position;
          if (out + 4 > capacity) {
            bytes = growStringDecodeBuffer(bytes, out + 4);
            capacity = bytes.length;
          }
          out = putUtf16Char(bytes, out, ch);
          out = putUtf16Char(bytes, out, low);
        }
      } else if (b < 0x20) {
        this.position = position;
        throw error("Control character in string");
      } else if (b < 0x80) {
        if (out + 2 > capacity) {
          bytes = growStringDecodeBuffer(bytes, out + 2);
          capacity = bytes.length;
        }
        out = putUtf16Char(bytes, out, (char) b);
      } else if ((b & 0xE0) == 0xC0) {
        if (position >= inputLimit) {
          this.position = position;
          throw error("Short UTF-8 sequence");
        }
        int second = input[position++] & 0xFF;
        if ((second & 0xC0) != 0x80) {
          this.position = position;
          throw error("Invalid UTF-8 continuation");
        }
        int codePoint = ((b & 0x1F) << 6) | (second & 0x3F);
        if (codePoint < 0x80) {
          this.position = position;
          throw error("Overlong UTF-8 sequence");
        }
        if (out + 2 > capacity) {
          bytes = growStringDecodeBuffer(bytes, out + 2);
          capacity = bytes.length;
        }
        out = putUtf16Char(bytes, out, (char) codePoint);
      } else if ((b & 0xF8) == 0xF0) {
        if (position >= inputLimit) {
          this.position = position;
          throw error("Short UTF-8 sequence");
        }
        int second = input[position++] & 0xFF;
        if ((second & 0xC0) != 0x80) {
          this.position = position;
          throw error("Invalid UTF-8 continuation");
        }
        if (position >= inputLimit) {
          this.position = position;
          throw error("Short UTF-8 sequence");
        }
        int third = input[position++] & 0xFF;
        if ((third & 0xC0) != 0x80) {
          this.position = position;
          throw error("Invalid UTF-8 continuation");
        }
        if (position >= inputLimit) {
          this.position = position;
          throw error("Short UTF-8 sequence");
        }
        int fourth = input[position++] & 0xFF;
        if ((fourth & 0xC0) != 0x80) {
          this.position = position;
          throw error("Invalid UTF-8 continuation");
        }
        int codePoint =
            ((b & 0x07) << 18) | ((second & 0x3F) << 12) | ((third & 0x3F) << 6) | (fourth & 0x3F);
        if (codePoint < 0x10000 || codePoint > 0x10FFFF) {
          this.position = position;
          throw error("Invalid UTF-8 sequence");
        }
        if (out + 4 > capacity) {
          bytes = growStringDecodeBuffer(bytes, out + 4);
          capacity = bytes.length;
        }
        out = putUtf16Char(bytes, out, Character.highSurrogate(codePoint));
        out = putUtf16Char(bytes, out, Character.lowSurrogate(codePoint));
      } else {
        this.position = position;
        throw error("Invalid UTF-8 sequence");
      }
    }
    throw error("Unterminated string");
  }

  private String readStringUtf16FromFirst(byte[] bytes, int first) {
    int codePoint;
    if ((first & 0xF0) == 0xE0) {
      byte[] input = this.input;
      int position = this.position;
      int inputLimit = this.inputLimit;
      if (position >= inputLimit) {
        this.position = position;
        throw error("Short UTF-8 sequence");
      }
      int second = input[position++] & 0xFF;
      if ((second & 0xC0) != 0x80) {
        this.position = position;
        throw error("Invalid UTF-8 continuation");
      }
      if (position >= inputLimit) {
        this.position = position;
        throw error("Short UTF-8 sequence");
      }
      int third = input[position++] & 0xFF;
      if ((third & 0xC0) != 0x80) {
        this.position = position;
        throw error("Invalid UTF-8 continuation");
      }
      codePoint = ((first & 0x0F) << 12) | ((second & 0x3F) << 6) | (third & 0x3F);
      if (codePoint < 0x800 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
        this.position = position;
        throw error("Invalid UTF-8 sequence");
      }
      this.position = position;
    } else {
      codePoint = readUtf8CodePoint(first);
    }
    int out;
    if (codePoint <= 0xFFFF) {
      bytes = ensureStringDecodeCapacity(bytes, 2);
      out = putUtf16Char(bytes, 0, (char) codePoint);
    } else {
      bytes = ensureStringDecodeCapacity(bytes, 4);
      out = putUtf16Char(bytes, 0, Character.highSurrogate(codePoint));
      out = putUtf16Char(bytes, out, Character.lowSurrogate(codePoint));
    }
    return readStringUtf16Tail(bytes, out);
  }

  @Override
  protected char readUnicodeEscape() {
    int offset = position;
    if (offset > inputLimit - 4) {
      throw error("Short unicode escape");
    }
    int value = hexValue4(input, offset);
    if (value < 0) {
      throw error("Invalid hex digit");
    }
    position = offset + 4;
    return (char) value;
  }

  private char readEscapedStringChar() {
    if (position >= inputLimit) {
      throw error("Unterminated escape");
    }
    char escaped = (char) (input[position++] & 0xFF);
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

  private char readLowSurrogateEscape() {
    if (position + 2 > inputLimit || input[position] != '\\' || input[position + 1] != 'u') {
      throw error("Unpaired high surrogate escape");
    }
    position += 2;
    char low = readUnicodeEscape();
    if (!Character.isLowSurrogate(low)) {
      throw error("Unpaired high surrogate escape");
    }
    return low;
  }

  private String finishDecodedString(byte[] bytes, int length, boolean utf16) {
    // Strings must not share the reader-owned decode buffer; the buffer is reused by later reads.
    byte[] result = new byte[length];
    System.arraycopy(bytes, 0, result, 0, length);
    return utf16
        ? StringSerializer.newUtf16StringZeroCopy(result)
        : StringSerializer.newLatin1StringZeroCopy(result);
  }

  private byte[] ensureStringDecodeCapacity(byte[] bytes, int capacity) {
    if (bytes.length < capacity) {
      return growStringDecodeBuffer(bytes, capacity);
    }
    return bytes;
  }

  private byte[] growStringDecodeBuffer(byte[] bytes, int capacity) {
    int newCapacity = Math.max(capacity, bytes.length << 1);
    byte[] grown = Arrays.copyOf(bytes, newCapacity);
    stringDecodeBuffer = grown;
    return grown;
  }

  private byte[] widenStringDecodeBuffer(byte[] bytes, int length) {
    int utf16Length = length << 1;
    bytes = ensureStringDecodeCapacity(bytes, utf16Length);
    for (int i = length - 1, pos = utf16Length - 2; i >= 0; i--, pos -= 2) {
      putUtf16Char(bytes, pos, (char) (bytes[i] & 0xFF));
    }
    return bytes;
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

  private int continuation() {
    if (position >= inputLimit) {
      throw error("Short UTF-8 sequence");
    }
    int value = input[position++] & 0xFF;
    if ((value & 0xC0) != 0x80) {
      throw error("Invalid UTF-8 continuation");
    }
    return value & 0x3F;
  }

  private void skipWhitespaceFast() {
    int offset = position;
    int limit = inputLimit;
    byte[] bytes = input;
    if (offset >= limit || bytes[offset] > ' ') {
      return;
    }
    while (offset < limit && isWhitespace(bytes[offset])) {
      offset++;
    }
    position = offset;
  }

  private static boolean isWhitespace(int ch) {
    return ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t';
  }

  private void rejectLeadingDigitFast() {
    if (position < inputLimit) {
      int ch = input[position];
      if (ch >= '0' && ch <= '9') {
        throw error("Leading zero in number");
      }
    }
  }

  private void rejectFractionOrExponentFast() {
    if (position < inputLimit) {
      int ch = input[position];
      if (ch == '.' || ch == 'e' || ch == 'E') {
        throw error("Expected integer");
      }
    }
  }
}
