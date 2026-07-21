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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
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
  private static final int INITIAL_STRING_DECODE_BUFFER_SIZE = 1024;
  private static final int RETAINED_STRING_DECODE_BUFFER_SIZE = 8192;
  private static final boolean LITTLE_ENDIAN = NativeByteOrder.IS_LITTLE_ENDIAN;
  private static final long BYTE_ONES = 0x0101010101010101L;
  private static final int INT_BYTE_ONES = 0x01010101;
  private static final long BYTE_HIGH_BITS = 0x8080808080808080L;
  private static final int INT_BYTE_HIGH_BITS = 0x80808080;
  private static final long BACKSLASH_BYTES = 0x5c5c5c5c5c5c5c5cL;
  private static final int INT_BACKSLASH_BYTES = 0x5c5c5c5c;
  private static final long CONTROL_LIMIT_BYTES = 0x2020202020202020L;
  private static final int INT_CONTROL_LIMIT_BYTES = 0x20202020;
  private static final long QUOTE_BYTES = 0x2222222222222222L;
  private static final int INT_QUOTE_BYTES = 0x22222222;
  private static final long LONG_MAX_DIV_10 = Long.MAX_VALUE / 10;
  private static final int LONG_MAX_MOD_10 = (int) (Long.MAX_VALUE % 10);
  private static final long LONG_MAX_DIV_100 = Long.MAX_VALUE / 100;
  private static final int LONG_MAX_MOD_100 = (int) (Long.MAX_VALUE % 100);
  private static final long LONG_MIN_DIV_10 = Long.MIN_VALUE / 10;
  private static final int LONG_MIN_LAST_DIGIT = (int) -(Long.MIN_VALUE % 10);
  private static final long EIGHT_DIGITS = 100_000_000L;
  private static final long ASCII_ZEROES = 0x3030_3030_3030_3030L;
  private static final long ASCII_NINES = 0x3939_3939_3939_3939L;
  private static final long ASCII_HIGH_BITS = 0x8080_8080_8080_8080L;
  // Little-endian packed ASCII bytes for "null".
  private static final int NULL_LITERAL = 0x6C6C756E;

  // JSON syntax bytes are ASCII, so hot token checks can compare signed bytes directly.
  // UTF-8 string decoding must keep unsigned byte conversion for non-ASCII content.
  private byte[] input;
  private byte[] stringDecodeBuffer = new byte[INITIAL_STRING_DECODE_BUFFER_SIZE];
  // Keep the cache after hot representation fields; an inherited reference shifts their offsets.
  private final FieldNameCache fieldNameCache;

  public Utf8JsonReader(JsonConfig config, JsonTypeResolver typeResolver) {
    super(config, typeResolver);
    input = EMPTY_BYTES;
    // The configured limit belongs to each reader; pooled-state concurrency must not divide it.
    int maxEntries = config.maxCachedFieldNames();
    fieldNameCache = maxEntries == 0 ? null : new FieldNameCache(maxEntries);
  }

  @Override
  protected int scanStringEnd(int start) {
    int inputLength = input.length;
    if (start >= inputLength || input[start] != '"') {
      throw errorAt("Expected string", start);
    }
    int cursor = start + 1;
    int wordEnd = inputLength - Long.BYTES;
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
        cursor = scanEscape(cursor, inputLength);
      } else {
        cursor = (int) (scanUtf8CodePoint(cursor) >>> 32);
      }
    }
    while (cursor < inputLength) {
      int raw = input[cursor] & 0xff;
      if (raw == '"') {
        return cursor + 1;
      }
      if (raw < 0x20) {
        throw errorAt("Control character in string", cursor);
      }
      if (raw == '\\') {
        cursor = scanEscape(cursor, inputLength);
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

  private int scanEscape(int slash, int inputLength) {
    int cursor = slash + 1;
    if (cursor >= inputLength) {
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
      if (cursor + 6 > inputLength || input[cursor] != '\\' || input[cursor + 1] != 'u') {
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
    if (offset > input.length - count) {
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
    if (offset > input.length - 4) {
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
    position = 0;
    reset();
    return this;
  }

  public void clear() {
    reset();
    input = EMPTY_BYTES;
    position = 0;
    if (stringDecodeBuffer.length > RETAINED_STRING_DECODE_BUFFER_SIZE) {
      stringDecodeBuffer = new byte[RETAINED_STRING_DECODE_BUFFER_SIZE];
    }
  }

  public boolean consumeToken(char expected) {
    skipWhitespaceFast();
    if (position < input.length && input[position] == expected) {
      position++;
      return true;
    }
    return false;
  }

  public boolean consumeNextToken(char expected) {
    if (position < input.length && input[position] == expected) {
      position++;
      return true;
    }
    return consumeToken(expected);
  }

  public void expectToken(char expected) {
    if (!consumeToken(expected)) {
      throw error("Expected '" + expected + "'");
    }
  }

  public void expectNextToken(char expected) {
    if (position < input.length && input[position] == expected) {
      position++;
      return;
    }
    expectNextTokenSlow(expected);
  }

  private void expectNextTokenSlow(char expected) {
    expectToken(expected);
  }

  public boolean consumeNextCommaOrEndObject() {
    if (position < input.length) {
      int ch = input[position];
      if (ch == ',') {
        position++;
        return true;
      }
      return consumeNextObjectEndOrSlow(ch);
    }
    return consumeNextCommaOrEndObjectSlow();
  }

  private boolean consumeNextObjectEndOrSlow(int ch) {
    if (ch == '}') {
      position++;
      return false;
    }
    return consumeNextCommaOrEndObjectSlow();
  }

  private boolean consumeNextCommaOrEndObjectSlow() {
    skipWhitespaceFast();
    if (position < input.length) {
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

  public boolean consumeNextCommaOrEndArray() {
    if (position < input.length) {
      int ch = input[position];
      if (ch == ',') {
        position++;
        return true;
      }
      return consumeNextArrayEndOrSlow(ch);
    }
    return consumeNextCommaOrEndArraySlow();
  }

  private boolean consumeNextArrayEndOrSlow(int ch) {
    if (ch == ']') {
      position++;
      return false;
    }
    return consumeNextCommaOrEndArraySlow();
  }

  private boolean consumeNextCommaOrEndArraySlow() {
    skipWhitespaceFast();
    if (position < input.length) {
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
    if (position < input.length) {
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
    if (offset + 3 < bytes.length && LittleEndian.getInt32(bytes, offset) == NULL_LITERAL) {
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
    if (position < input.length) {
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

  private boolean readBooleanToken() {
    byte[] bytes = input;
    int offset = position;
    if (offset + 3 < bytes.length
        && bytes[offset] == 't'
        && bytes[offset + 1] == 'r'
        && bytes[offset + 2] == 'u'
        && bytes[offset + 3] == 'e') {
      position = offset + 4;
      return true;
    } else if (offset + 4 < bytes.length
        && bytes[offset] == 'f'
        && bytes[offset + 1] == 'a'
        && bytes[offset + 2] == 'l'
        && bytes[offset + 3] == 's'
        && bytes[offset + 4] == 'e') {
      position = offset + 5;
      return false;
    }
    throw error("Expected boolean");
  }

  public int readIntValue() {
    skipWhitespaceFast();
    return readIntToken();
  }

  public int readNextIntValue() {
    if (position < input.length) {
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

  private int readIntToken() {
    byte[] bytes = input;
    int offset = position;
    int inputLength = bytes.length;
    if (offset >= inputLength) {
      throw error("Expected digit");
    }
    int ch = bytes[offset];
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
    if (safeEnd > inputLength) {
      safeEnd = inputLength;
    }
    while (offset < safeEnd) {
      ch = bytes[offset];
      if (ch < '0' || ch > '9') {
        break;
      }
      result = result * 10 + (ch - '0');
      offset++;
    }
    if (offset < inputLength) {
      ch = bytes[offset];
      if (ch >= '0' && ch <= '9') {
        return readPositiveIntTail(bytes, offset, inputLength, result);
      }
    }
    position = offset;
    rejectFractionOrExponentFast();
    return result;
  }

  private int readPositiveIntTail(byte[] bytes, int offset, int inputLength, int result) {
    while (offset < inputLength) {
      int ch = bytes[offset];
      if (ch < '0' || ch > '9') {
        break;
      }
      long value = (long) result * 10 + (ch - '0');
      if (value > Integer.MAX_VALUE) {
        position = offset;
        throw error("Integer overflow");
      }
      result = (int) value;
      offset++;
    }
    position = offset;
    rejectFractionOrExponentFast();
    return result;
  }

  private int readNegativeIntToken(int start) {
    position = start + 1;
    int result = 0;
    int limit = Integer.MIN_VALUE;
    if (position >= input.length) {
      throw error("Expected digit");
    }
    int ch = input[position];
    if (ch == '0') {
      position++;
      rejectLeadingDigitFast();
      rejectFractionOrExponentFast();
      return 0;
    }
    if (ch < '1' || ch > '9') {
      throw error("Expected digit");
    }
    int multmin = limit / 10;
    while (position < input.length) {
      ch = input[position];
      if (ch < '0' || ch > '9') {
        break;
      }
      int digit = ch - '0';
      if (result < multmin) {
        throw error("Integer overflow");
      }
      result *= 10;
      if (result < Integer.MIN_VALUE + digit) {
        throw error("Integer overflow");
      }
      result -= digit;
      position++;
    }
    rejectFractionOrExponentFast();
    return result;
  }

  public long readLongValue() {
    skipWhitespaceFast();
    return readLongToken();
  }

  public long readNextLongValue() {
    if (position < input.length) {
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

  public BigDecimal readBigDecimal() {
    skipWhitespaceFast();
    return readBigDecimalToken();
  }

  public UUID readUuid() {
    skipWhitespaceFast();
    int mark = position;
    try {
      return readUuidToken();
    } catch (RuntimeException e) {
      position = mark;
      return UUID.fromString(readStringToken());
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
    if (position < input.length) {
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

  public float readNextFloatValue() {
    if (position < input.length) {
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

  private long readLongToken() {
    byte[] bytes = input;
    int offset = position;
    int inputLength = bytes.length;
    if (offset >= inputLength) {
      throw error("Expected digit");
    }
    int ch = bytes[offset];
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
    if (safeEnd > inputLength) {
      safeEnd = inputLength;
    }
    int block = parseEightDigits(bytes, offset, safeEnd);
    if (block >= 0) {
      result = result * EIGHT_DIGITS + block;
      offset += 8;
      block = parseEightDigits(bytes, offset, safeEnd);
      if (block >= 0) {
        result = result * EIGHT_DIGITS + block;
        offset += 8;
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
    if (offset < inputLength) {
      ch = bytes[offset];
      if (ch >= '0' && ch <= '9') {
        return readPositiveLongTail(bytes, offset, inputLength, result);
      }
    }
    position = offset;
    rejectFractionOrExponentFast();
    return result;
  }

  private long readPositiveLongTail(byte[] bytes, int offset, int inputLength, long result) {
    while (offset < inputLength) {
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
    int inputLength = bytes.length;
    if (offset >= inputLength) {
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
    if (safeEnd > inputLength) {
      safeEnd = inputLength;
    }
    int block = parseEightDigits(bytes, offset, safeEnd);
    if (block >= 0) {
      result = result * EIGHT_DIGITS - block;
      offset += 8;
      block = parseEightDigits(bytes, offset, safeEnd);
      if (block >= 0) {
        result = result * EIGHT_DIGITS - block;
        offset += 8;
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
    if (offset < inputLength) {
      ch = bytes[offset];
      if (ch >= '0' && ch <= '9') {
        return readNegativeLongTail(bytes, offset, inputLength, result);
      }
    }
    position = offset;
    rejectFractionOrExponentFast();
    return result;
  }

  private long readNegativeLongTail(byte[] bytes, int offset, int inputLength, long result) {
    while (offset < inputLength) {
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
    long pairs = (digits * 10 + (digits >>> 8)) & 0x00FF_00FF_00FF_00FFL;
    long quads = (pairs * 100 + (pairs >>> 16)) & 0x0000_FFFF_0000_FFFFL;
    return (int) ((quads & 0xFFFF) * 10_000 + (quads >>> 32));
  }

  private BigDecimal readBigDecimalToken() {
    byte[] bytes = input;
    int offset = position;
    int start = offset;
    int inputLength = bytes.length;
    if (offset >= inputLength) {
      return readBigDecimalFallback(start);
    }
    int ch = bytes[offset];
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
      do {
        int digit = ch - '0';
        if (unscaled > LONG_MAX_DIV_10
            || (unscaled == LONG_MAX_DIV_10 && digit > LONG_MAX_MOD_10)) {
          return readBigDecimalFallback(start);
        }
        unscaled = unscaled * 10 + digit;
        offset++;
        if (offset >= inputLength) {
          break;
        }
        ch = bytes[offset];
      } while (ch >= '0' && ch <= '9');
    } else {
      return readBigDecimalFallback(start);
    }
    if (offset < inputLength && bytes[offset] == '.') {
      offset++;
      int fractionStart = offset;
      while (offset < inputLength) {
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
    if (offset < inputLength) {
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
    int inputLength = bytes.length;
    if (offset >= inputLength) {
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
      do {
        int digit = ch - '0';
        if (unscaled > LONG_MAX_DIV_10
            || (unscaled == LONG_MAX_DIV_10 && digit > LONG_MAX_MOD_10)) {
          return readBigDecimalFallback(start);
        }
        unscaled = unscaled * 10 + digit;
        offset++;
        if (offset >= inputLength) {
          break;
        }
        ch = bytes[offset];
      } while (ch >= '0' && ch <= '9');
    } else {
      return readBigDecimalFallback(start);
    }
    if (offset < inputLength && bytes[offset] == '.') {
      offset++;
      int fractionStart = offset;
      while (offset < inputLength) {
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
    if (offset < inputLength) {
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
    if (offset + 38 > bytes.length || bytes[offset] != '"') {
      throw new IllegalArgumentException();
    }
    if (bytes[start + 8] != '-'
        || bytes[start + 13] != '-'
        || bytes[start + 18] != '-'
        || bytes[start + 23] != '-'
        || bytes[start + 36] != '"') {
      throw new IllegalArgumentException();
    }
    long msb = parseHex(bytes, start, 8);
    msb = (msb << 16) | parseHex(bytes, start + 9, 4);
    msb = (msb << 16) | parseHex(bytes, start + 14, 4);
    long lsb = parseHex(bytes, start + 19, 4);
    lsb = (lsb << 48) | parseHex(bytes, start + 24, 12);
    position = start + 37;
    return new UUID(msb, lsb);
  }

  private static long parseHex(byte[] bytes, int offset, int length) {
    long value = 0;
    for (int i = 0; i < length; i++) {
      value = (value << 4) | hexValue(bytes[offset + i]);
    }
    return value;
  }

  private static int hexValue(int ch) {
    if (ch >= '0' && ch <= '9') {
      return ch - '0';
    }
    int lower = ch | 0x20;
    if (lower >= 'a' && lower <= 'f') {
      return lower - 'a' + 10;
    }
    throw new IllegalArgumentException();
  }

  private double readDoubleToken() {
    // Keep the byte-reader fast path narrow: compact plain decimals finish locally, while
    // exponents, overflow, and precision-sensitive values use the reader-owned exact fallback.
    byte[] bytes = input;
    int offset = position;
    int inputLength = bytes.length;
    if (offset >= inputLength) {
      return readDoubleFallback(offset);
    }
    int ch = bytes[offset];
    if (ch == '-') {
      return readSignedDoubleToken(offset);
    }
    return readPositiveDoubleToken(bytes, offset, inputLength, ch);
  }

  private float readFloatToken() {
    byte[] bytes = input;
    int offset = position;
    int inputLength = bytes.length;
    if (offset >= inputLength) {
      return readFloatFallback(offset);
    }
    int ch = bytes[offset];
    if (ch == '-') {
      return readSignedFloatToken(offset);
    }
    return readPositiveFloatToken(bytes, offset, inputLength, ch);
  }

  private float readPositiveFloatToken(byte[] bytes, int offset, int inputLength, int ch) {
    int start = offset;
    long unscaled = 0;
    if (ch == '0') {
      offset++;
      if (offset < inputLength) {
        ch = bytes[offset];
        if (ch >= '0' && ch <= '9') {
          return readFloatFallback(start);
        }
      }
    } else if (ch >= '1' && ch <= '9') {
      unscaled = ch - '0';
      offset++;
      while (offset + 1 < inputLength) {
        int high = bytes[offset] - '0';
        int low = bytes[offset + 1] - '0';
        if (high < 0 || high > 9 || low < 0 || low > 9) {
          break;
        }
        int pair = high * 10 + low;
        if (unscaled > LONG_MAX_DIV_100
            || (unscaled == LONG_MAX_DIV_100 && pair > LONG_MAX_MOD_100)) {
          return readFloatFallback(start);
        }
        unscaled = unscaled * 100 + pair;
        offset += 2;
      }
      if (offset < inputLength) {
        int digit = bytes[offset] - '0';
        if (digit >= 0 && digit <= 9) {
          if (unscaled > LONG_MAX_DIV_10
              || (unscaled == LONG_MAX_DIV_10 && digit > LONG_MAX_MOD_10)) {
            return readFloatFallback(start);
          }
          unscaled = unscaled * 10 + digit;
          offset++;
        }
      }
    } else {
      return readFloatFallback(start);
    }
    return readPositiveFloatTail(bytes, offset, inputLength, start, unscaled);
  }

  private float readSignedFloatToken(int start) {
    byte[] bytes = input;
    int offset = start + 1;
    int inputLength = bytes.length;
    if (offset >= inputLength) {
      return readFloatFallback(start);
    }
    int ch = bytes[offset];
    long unscaled = 0;
    if (ch == '0') {
      offset++;
      if (offset < inputLength) {
        ch = bytes[offset];
        if (ch >= '0' && ch <= '9') {
          return readFloatFallback(start);
        }
      }
    } else if (ch >= '1' && ch <= '9') {
      unscaled = ch - '0';
      offset++;
      while (offset + 1 < inputLength) {
        int high = bytes[offset] - '0';
        int low = bytes[offset + 1] - '0';
        if (high < 0 || high > 9 || low < 0 || low > 9) {
          break;
        }
        int pair = high * 10 + low;
        if (unscaled > LONG_MAX_DIV_100
            || (unscaled == LONG_MAX_DIV_100 && pair > LONG_MAX_MOD_100)) {
          return readFloatFallback(start);
        }
        unscaled = unscaled * 100 + pair;
        offset += 2;
      }
      if (offset < inputLength) {
        int digit = bytes[offset] - '0';
        if (digit >= 0 && digit <= 9) {
          if (unscaled > LONG_MAX_DIV_10
              || (unscaled == LONG_MAX_DIV_10 && digit > LONG_MAX_MOD_10)) {
            return readFloatFallback(start);
          }
          unscaled = unscaled * 10 + digit;
          offset++;
        }
      }
    } else {
      return readFloatFallback(start);
    }
    return readSignedFloatTail(bytes, offset, inputLength, start, unscaled);
  }

  private float readPositiveFloatTail(
      byte[] bytes, int offset, int inputLength, int start, long unscaled) {
    int scale = 0;
    if (offset < inputLength && bytes[offset] == '.') {
      offset++;
      int fractionStart = offset;
      while (offset + 1 < inputLength) {
        int high = bytes[offset] - '0';
        int low = bytes[offset + 1] - '0';
        if (high < 0 || high > 9 || low < 0 || low > 9) {
          break;
        }
        int pair = high * 10 + low;
        if (unscaled > LONG_MAX_DIV_100
            || (unscaled == LONG_MAX_DIV_100 && pair > LONG_MAX_MOD_100)) {
          return readFloatFallback(start);
        }
        unscaled = unscaled * 100 + pair;
        scale += 2;
        offset += 2;
      }
      if (offset < inputLength) {
        int digit = bytes[offset] - '0';
        if (digit >= 0 && digit <= 9) {
          if (unscaled > LONG_MAX_DIV_10
              || (unscaled == LONG_MAX_DIV_10 && digit > LONG_MAX_MOD_10)) {
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
    return finishFloatToken(bytes, offset, inputLength, start, unscaled, scale);
  }

  private float readSignedFloatTail(
      byte[] bytes, int offset, int inputLength, int start, long unscaled) {
    int scale = 0;
    if (offset < inputLength && bytes[offset] == '.') {
      offset++;
      int fractionStart = offset;
      while (offset + 1 < inputLength) {
        int high = bytes[offset] - '0';
        int low = bytes[offset + 1] - '0';
        if (high < 0 || high > 9 || low < 0 || low > 9) {
          break;
        }
        int pair = high * 10 + low;
        if (unscaled > LONG_MAX_DIV_100
            || (unscaled == LONG_MAX_DIV_100 && pair > LONG_MAX_MOD_100)) {
          return readFloatFallback(start);
        }
        unscaled = unscaled * 100 + pair;
        scale += 2;
        offset += 2;
      }
      if (offset < inputLength) {
        int digit = bytes[offset] - '0';
        if (digit >= 0 && digit <= 9) {
          if (unscaled > LONG_MAX_DIV_10
              || (unscaled == LONG_MAX_DIV_10 && digit > LONG_MAX_MOD_10)) {
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
    return finishSignedFloatToken(bytes, offset, inputLength, start, unscaled, scale);
  }

  private float finishFloatToken(
      byte[] bytes, int offset, int inputLength, int start, long unscaled, int scale) {
    if (offset < inputLength) {
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
      byte[] bytes, int offset, int inputLength, int start, long unscaled, int scale) {
    if (offset < inputLength) {
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

  private double readPositiveDoubleToken(byte[] bytes, int offset, int inputLength, int ch) {
    int start = offset;
    long unscaled = 0;
    if (ch == '0') {
      offset++;
      if (offset < inputLength) {
        ch = bytes[offset];
        if (ch >= '0' && ch <= '9') {
          return readDoubleFallback(start);
        }
      }
    } else if (ch >= '1' && ch <= '9') {
      unscaled = ch - '0';
      offset++;
      while (offset + 1 < inputLength) {
        int high = bytes[offset] - '0';
        int low = bytes[offset + 1] - '0';
        if (high < 0 || high > 9 || low < 0 || low > 9) {
          break;
        }
        int pair = high * 10 + low;
        if (unscaled > LONG_MAX_DIV_100
            || (unscaled == LONG_MAX_DIV_100 && pair > LONG_MAX_MOD_100)) {
          return readDoubleFallback(start);
        }
        unscaled = unscaled * 100 + pair;
        offset += 2;
      }
      if (offset < inputLength) {
        int digit = bytes[offset] - '0';
        if (digit >= 0 && digit <= 9) {
          if (unscaled > LONG_MAX_DIV_10
              || (unscaled == LONG_MAX_DIV_10 && digit > LONG_MAX_MOD_10)) {
            return readDoubleFallback(start);
          }
          unscaled = unscaled * 10 + digit;
          offset++;
        }
      }
    } else {
      return readDoubleFallback(start);
    }
    return readPositiveDoubleTail(bytes, offset, inputLength, start, unscaled);
  }

  private double readSignedDoubleToken(int start) {
    byte[] bytes = input;
    int offset = start + 1;
    int inputLength = bytes.length;
    if (offset >= inputLength) {
      return readDoubleFallback(start);
    }
    int ch = bytes[offset];
    long unscaled = 0;
    if (ch == '0') {
      offset++;
      if (offset < inputLength) {
        ch = bytes[offset];
        if (ch >= '0' && ch <= '9') {
          return readDoubleFallback(start);
        }
      }
    } else if (ch >= '1' && ch <= '9') {
      unscaled = ch - '0';
      offset++;
      while (offset + 1 < inputLength) {
        int high = bytes[offset] - '0';
        int low = bytes[offset + 1] - '0';
        if (high < 0 || high > 9 || low < 0 || low > 9) {
          break;
        }
        int pair = high * 10 + low;
        if (unscaled > LONG_MAX_DIV_100
            || (unscaled == LONG_MAX_DIV_100 && pair > LONG_MAX_MOD_100)) {
          return readDoubleFallback(start);
        }
        unscaled = unscaled * 100 + pair;
        offset += 2;
      }
      if (offset < inputLength) {
        int digit = bytes[offset] - '0';
        if (digit >= 0 && digit <= 9) {
          if (unscaled > LONG_MAX_DIV_10
              || (unscaled == LONG_MAX_DIV_10 && digit > LONG_MAX_MOD_10)) {
            return readDoubleFallback(start);
          }
          unscaled = unscaled * 10 + digit;
          offset++;
        }
      }
    } else {
      return readDoubleFallback(start);
    }
    return readSignedDoubleTail(bytes, offset, inputLength, start, unscaled);
  }

  private double readPositiveDoubleTail(
      byte[] bytes, int offset, int inputLength, int start, long unscaled) {
    int scale = 0;
    if (offset < inputLength && bytes[offset] == '.') {
      offset++;
      int fractionStart = offset;
      while (offset + 1 < inputLength) {
        int high = bytes[offset] - '0';
        int low = bytes[offset + 1] - '0';
        if (high < 0 || high > 9 || low < 0 || low > 9) {
          break;
        }
        int pair = high * 10 + low;
        if (unscaled > LONG_MAX_DIV_100
            || (unscaled == LONG_MAX_DIV_100 && pair > LONG_MAX_MOD_100)) {
          return readDoubleFallback(start);
        }
        unscaled = unscaled * 100 + pair;
        scale += 2;
        offset += 2;
      }
      if (offset < inputLength) {
        int digit = bytes[offset] - '0';
        if (digit >= 0 && digit <= 9) {
          if (unscaled > LONG_MAX_DIV_10
              || (unscaled == LONG_MAX_DIV_10 && digit > LONG_MAX_MOD_10)) {
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
    return finishDoubleToken(bytes, offset, inputLength, start, unscaled, scale);
  }

  private double readSignedDoubleTail(
      byte[] bytes, int offset, int inputLength, int start, long unscaled) {
    int scale = 0;
    if (offset < inputLength && bytes[offset] == '.') {
      offset++;
      int fractionStart = offset;
      while (offset + 1 < inputLength) {
        int high = bytes[offset] - '0';
        int low = bytes[offset + 1] - '0';
        if (high < 0 || high > 9 || low < 0 || low > 9) {
          break;
        }
        int pair = high * 10 + low;
        if (unscaled > LONG_MAX_DIV_100
            || (unscaled == LONG_MAX_DIV_100 && pair > LONG_MAX_MOD_100)) {
          return readDoubleFallback(start);
        }
        unscaled = unscaled * 100 + pair;
        scale += 2;
        offset += 2;
      }
      if (offset < inputLength) {
        int digit = bytes[offset] - '0';
        if (digit >= 0 && digit <= 9) {
          if (unscaled > LONG_MAX_DIV_10
              || (unscaled == LONG_MAX_DIV_10 && digit > LONG_MAX_MOD_10)) {
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
    return finishSignedDoubleToken(bytes, offset, inputLength, start, unscaled, scale);
  }

  private double finishDoubleToken(
      byte[] bytes, int offset, int inputLength, int start, long unscaled, int scale) {
    if (offset < inputLength) {
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
      byte[] bytes, int offset, int inputLength, int start, long unscaled, int scale) {
    if (offset < inputLength) {
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
    if (position >= input.length || input[position++] != '"') {
      throw error("Expected string");
    }
    int result = 0;
    int limit = -Integer.MAX_VALUE;
    boolean negative = false;
    if (position < input.length && input[position] == '-') {
      negative = true;
      limit = Integer.MIN_VALUE;
      position++;
    }
    if (position >= input.length) {
      throw error("Unterminated string");
    }
    int ch = input[position];
    if (ch == '\\') {
      position = nameStart;
      return super.readFieldNameInt();
    }
    if (ch == '0') {
      position++;
      return readZeroIntName(nameStart);
    }
    if (ch < '1' || ch > '9') {
      throw error("Expected integer field name");
    }
    int multmin = limit / 10;
    do {
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
      if (position >= input.length) {
        throw error("Unterminated string");
      }
      ch = input[position];
    } while (ch >= '0' && ch <= '9');
    if (ch == '\\') {
      position = nameStart;
      return super.readFieldNameInt();
    }
    if (ch != '"') {
      throw error("Expected integer field name");
    }
    position++;
    return negative ? result : -result;
  }

  @Override
  public long readFieldNameLong() {
    skipWhitespaceFast();
    int nameStart = position;
    if (position >= input.length || input[position++] != '"') {
      throw error("Expected string");
    }
    long result = 0;
    long limit = -Long.MAX_VALUE;
    boolean negative = false;
    if (position < input.length && input[position] == '-') {
      negative = true;
      limit = Long.MIN_VALUE;
      position++;
    }
    if (position >= input.length) {
      throw error("Unterminated string");
    }
    int ch = input[position];
    if (ch == '\\') {
      position = nameStart;
      return super.readFieldNameLong();
    }
    if (ch == '0') {
      position++;
      return readZeroLongName(nameStart);
    }
    if (ch < '1' || ch > '9') {
      throw error("Expected long field name");
    }
    long multmin = limit / 10;
    do {
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
      if (position >= input.length) {
        throw error("Unterminated string");
      }
      ch = input[position];
    } while (ch >= '0' && ch <= '9');
    if (ch == '\\') {
      position = nameStart;
      return super.readFieldNameLong();
    }
    if (ch != '"') {
      throw error("Expected long field name");
    }
    position++;
    return negative ? result : -result;
  }

  @Override
  protected int length() {
    return input.length;
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
    int inputLength = bytes.length;
    if (position >= inputLength || bytes[position++] != '"') {
      throw error("Expected string");
    }
    int start = position;
    if (start + Long.BYTES <= inputLength) {
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
      return readFieldNameAfterWord0(cache, start, word0, inputLength);
    }
    return readFieldNameTail(cache, start, start, 0, 0, 0);
  }

  private String readFieldNameAfterWord0(
      FieldNameCache cache, int start, long word0, int inputLength) {
    int offset = start + Long.BYTES;
    if (offset + Long.BYTES <= inputLength) {
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
      if (offset < inputLength && input[offset] == '"') {
        position = offset + 1;
        return resolveFieldName(cache, start, offset, 16, word0, word1);
      }
      return readStringTokenLongTail(start, offset, inputLength);
    }
    return readFieldNameTail(cache, start, offset, Long.BYTES, word0, 0);
  }

  private String readFieldNameTail(
      FieldNameCache cache, int start, int offset, int length, long word0, long word1) {
    int inputLength = input.length;
    while (offset < inputLength) {
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
    if (position < input.length) {
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
    if (offset < bytes.length && bytes[offset] == '"') {
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

  public LocalDate readIsoLocalDate() {
    skipWhitespaceFast();
    int mark = position;
    LocalDate value = tryReadIsoLocalDateToken();
    if (value != null) {
      return value;
    }
    position = mark;
    return readIsoLocalDateFallback(readStringToken());
  }

  public OffsetDateTime readIsoOffsetDateTime() {
    skipWhitespaceFast();
    int mark = position;
    OffsetDateTime value = tryReadIsoOffsetDateTimeToken();
    if (value != null) {
      return value;
    }
    position = mark;
    return readIsoOffsetDateTimeFallback(readStringToken());
  }

  private String readStringToken() {
    byte[] bytes = input;
    int inputLength = bytes.length;
    if (position >= inputLength || bytes[position++] != '"') {
      throw error("Expected string");
    }
    int start = position;
    int offset = start;
    if (offset + Long.BYTES <= inputLength) {
      long stopMask = stringStopMask(LittleEndian.getInt64(bytes, offset));
      if (stopMask != 0) {
        return readStringWordStop(start, offset, stopMask);
      }
      offset += Long.BYTES;
      if (offset + Long.BYTES <= inputLength) {
        stopMask = stringStopMask(LittleEndian.getInt64(bytes, offset));
        if (stopMask != 0) {
          return readStringWordStop(start, offset, stopMask);
        }
        offset += Long.BYTES;
        if (offset + Long.BYTES <= inputLength) {
          stopMask = stringStopMask(LittleEndian.getInt64(bytes, offset));
          if (stopMask != 0) {
            return readStringWordStop(start, offset, stopMask);
          }
          offset += Long.BYTES;
        }
      }
    }
    return readStringTokenLongTail(start, offset, inputLength);
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

  private String readStringTokenLongTail(int start, int offset, int inputLength) {
    byte[] bytes = input;
    int doubleWordEnd = inputLength - (Long.BYTES << 1);
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
    int wordEnd = inputLength - Long.BYTES;
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
    return readStringTokenTail(start, offset, inputLength);
  }

  private String readStringTokenTail(int start, int offset, int inputLength) {
    byte[] bytes = input;
    // Reached only when the whole input has fewer than eight bytes left. Keep this rare tail out
    // of the hot word scanner so C2 has more budget for common string call sites.
    if (offset + Integer.BYTES <= inputLength) {
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
    while (offset < inputLength) {
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
    int length = bytes.length;
    if (offset + 12 > length || bytes[offset] != '"') {
      return null;
    }
    offset++;
    int dateStart = offset;
    if (bytes[dateStart + 4] != '-' || bytes[dateStart + 7] != '-') {
      return null;
    }
    int year = parse4(bytes, dateStart);
    int month = parse2(bytes, dateStart + 5);
    int day = parse2(bytes, dateStart + 8);
    int end = dateStart + 10;
    int ch = bytes[end];
    if (ch == '"') {
      position = end + 1;
      return LocalDate.of(year, month, day);
    }
    if (ch == 'T') {
      int stringEnd = tryScanSimpleStringTail(bytes, end + 1);
      if (stringEnd < 0) {
        return null;
      }
      position = stringEnd;
      return LocalDate.of(year, month, day);
    }
    return null;
  }

  private OffsetDateTime tryReadIsoOffsetDateTimeToken() {
    byte[] bytes = input;
    int offset = position;
    int length = bytes.length;
    if (offset + 19 > length || bytes[offset] != '"') {
      return null;
    }
    offset++;
    int start = offset;
    if (bytes[start + 4] != '-'
        || bytes[start + 7] != '-'
        || bytes[start + 10] != 'T'
        || bytes[start + 13] != ':') {
      return null;
    }
    int year = parse4(bytes, start);
    int month = parse2(bytes, start + 5);
    int day = parse2(bytes, start + 8);
    int hour = parse2(bytes, start + 11);
    int minute = parse2(bytes, start + 14);
    return tryReadIsoOffsetDateTimeTail(bytes, start + 16, length, year, month, day, hour, minute);
  }

  private OffsetDateTime tryReadIsoOffsetDateTimeTail(
      byte[] bytes, int index, int length, int year, int month, int day, int hour, int minute) {
    int second = 0;
    int nano = 0;
    if (index < length && bytes[index] == ':') {
      second = parse2(bytes, index + 1);
      index += 3;
      if (index < length && bytes[index] == '.') {
        int fractionStart = index + 1;
        int fractionEnd = fractionStart;
        while (fractionEnd < length && isDigit(bytes[fractionEnd])) {
          fractionEnd++;
        }
        if (fractionEnd == fractionStart) {
          throw new IllegalArgumentException();
        }
        if (fractionEnd - fractionStart > 9) {
          throw error("OffsetDateTime fractional seconds exceed nanosecond precision");
        }
        nano = parseNano(bytes, fractionStart, fractionEnd);
        index = fractionEnd;
      }
    }
    if (index < length && bytes[index] == 'Z') {
      if (index + 1 >= length || bytes[index + 1] != '"') {
        return null;
      }
      position = index + 2;
      return OffsetDateTime.of(year, month, day, hour, minute, second, nano, ZoneOffset.UTC);
    }
    return tryReadIsoOffsetDateTimeOffsetTail(
        bytes, index, length, year, month, day, hour, minute, second, nano);
  }

  private OffsetDateTime tryReadIsoOffsetDateTimeOffsetTail(
      byte[] bytes,
      int index,
      int length,
      int year,
      int month,
      int day,
      int hour,
      int minute,
      int second,
      int nano) {
    long offsetAndEnd = tryParseOffsetAndEnd(bytes, index, length);
    if (offsetAndEnd == Long.MIN_VALUE) {
      return null;
    }
    position = (int) offsetAndEnd;
    return OffsetDateTime.of(
        year,
        month,
        day,
        hour,
        minute,
        second,
        nano,
        ZoneOffset.ofTotalSeconds((int) (offsetAndEnd >> 32)));
  }

  private int tryScanSimpleStringTail(byte[] bytes, int offset) {
    int length = bytes.length;
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

  private static long tryParseOffsetAndEnd(byte[] bytes, int index, int length) {
    if (index >= length) {
      return Long.MIN_VALUE;
    }
    int offset = bytes[index];
    if (offset == 'Z') {
      if (index + 1 >= length || bytes[index + 1] != '"') {
        return Long.MIN_VALUE;
      }
      return ((long) (index + 2)) & 0xFFFF_FFFFL;
    }
    if (offset != '+' && offset != '-') {
      return Long.MIN_VALUE;
    }
    if (index + 6 >= length || bytes[index + 3] != ':') {
      return Long.MIN_VALUE;
    }
    int hour = parse2(bytes, index + 1);
    int minute = parse2(bytes, index + 4);
    int second = 0;
    int end = index + 6;
    if (bytes[end] == ':') {
      if (end + 3 >= length) {
        throw new IllegalArgumentException();
      }
      second = parse2(bytes, end + 1);
      end += 3;
    }
    if (bytes[end] != '"') {
      return Long.MIN_VALUE;
    }
    int total = hour * 3600 + minute * 60 + second;
    if (offset == '-') {
      total = -total;
    }
    return ((long) total << 32) | ((long) (end + 1) & 0xFFFF_FFFFL);
  }

  private static int parseNano(byte[] bytes, int start, int end) {
    int nano = 0;
    for (int i = start; i < end; i++) {
      nano = nano * 10 + bytes[i] - '0';
    }
    for (int i = end - start; i < 9; i++) {
      nano *= 10;
    }
    return nano;
  }

  private static int parse4(byte[] bytes, int index) {
    return parse2(bytes, index) * 100 + parse2(bytes, index + 2);
  }

  private static int parse2(byte[] bytes, int index) {
    int high = bytes[index] - '0';
    int low = bytes[index + 1] - '0';
    if (high < 0 || high > 9 || low < 0 || low > 9) {
      throw new IllegalArgumentException();
    }
    return high * 10 + low;
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
    long syntaxStop = ((word ^ QUOTE_BYTES) - BYTE_ONES) | ((word ^ BACKSLASH_BYTES) - BYTE_ONES);
    return (syntaxStop | word | (word - CONTROL_LIMIT_BYTES)) & BYTE_HIGH_BITS;
  }

  private static int stringStopMask(int word) {
    int syntaxStop =
        ((word ^ INT_QUOTE_BYTES) - INT_BYTE_ONES) | ((word ^ INT_BACKSLASH_BYTES) - INT_BYTE_ONES);
    return (syntaxStop | word | (word - INT_CONTROL_LIMIT_BYTES)) & INT_BYTE_HIGH_BITS;
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

  public boolean tryReadFieldNameColon(long expectedHash, long expectedMask, int expectedLength) {
    int mark = position;
    skipWhitespaceFast();
    return tryReadFieldNameColonAt(mark, expectedHash, expectedMask, expectedLength);
  }

  public boolean tryReadNextFieldNameColon(
      long expectedHash, long expectedMask, int expectedLength) {
    int mark = position;
    if (mark < input.length) {
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
    if (mark + Long.BYTES <= bytes.length
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
    if (mark + tokenLength <= bytes.length
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
    if (mark + tokenLength <= bytes.length
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
    if (mark + tokenLength <= bytes.length
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
    if (mark + tokenLength <= bytes.length
        && LittleEndian.getInt64(bytes, mark) == prefix
        && readTokenSuffix(bytes, suffixOffset, tokenLength, suffixMask) == suffix) {
      position = mark + tokenLength;
      return true;
    }
    return false;
  }

  private static long readTokenSuffix(
      byte[] bytes, int suffixOffset, int tokenLength, long suffixMask) {
    if (suffixOffset + Long.BYTES <= bytes.length) {
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
    if (quoteOffset < bytes.length && bytes[offset] == '"') {
      if (nameOffset + Long.BYTES <= bytes.length) {
        if ((LittleEndian.getInt64(bytes, nameOffset) & expectedMask) == expectedHash
            && bytes[quoteOffset] == '"') {
          int colonOffset = quoteOffset + 1;
          if (colonOffset < bytes.length && bytes[colonOffset] == ':') {
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
        if (colonOffset < bytes.length && bytes[colonOffset] == ':') {
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
    if (position < input.length) {
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
    int length = bytes.length;
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
    int length = bytes.length;
    if (position >= length || bytes[position++] != '"') {
      throw error("Expected string");
    }
    long hash = JsonFieldNameHash.MAGIC_HASH_CODE;
    long value = 0;
    int nameLength = 0;
    boolean latin1 = true;
    while (position < length) {
      int b = bytes[position++] & 0xFF;
      if (b == '"') {
        return JsonFieldNameHash.finish(hash, value, nameLength, latin1);
      }
      if (b == '\\') {
        char escaped = readEscapedFieldNameChar();
        if (Character.isHighSurrogate(escaped)) {
          if (latin1) {
            hash = JsonFieldNameHash.hashPacked(value, nameLength);
            latin1 = false;
          }
          hash = JsonFieldNameHash.update(hash, escaped);
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
        } else if (Character.isLowSurrogate(escaped)) {
          throw error("Unpaired low surrogate escape");
        } else {
          if (latin1) {
            if (escaped <= 0xFF && escaped != 0 && nameLength < Long.BYTES) {
              value = JsonFieldNameHash.value(value, nameLength, escaped);
              nameLength++;
              continue;
            }
            hash = JsonFieldNameHash.hashPacked(value, nameLength);
            latin1 = false;
          }
          hash = JsonFieldNameHash.update(hash, escaped);
          nameLength++;
        }
        continue;
      }
      if (b < 0x20) {
        throw error("Control character in string");
      }
      if (b < 0x80) {
        if (latin1) {
          if (b != 0 && nameLength < Long.BYTES) {
            value = JsonFieldNameHash.value(value, nameLength, (char) b);
            nameLength++;
            continue;
          }
          hash = JsonFieldNameHash.hashPacked(value, nameLength);
          latin1 = false;
        }
        hash = JsonFieldNameHash.update(hash, (char) b);
        nameLength++;
        continue;
      }
      int codePoint = readUtf8CodePoint(b);
      if (codePoint <= 0xFFFF) {
        char ch = (char) codePoint;
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
      } else {
        if (latin1) {
          hash = JsonFieldNameHash.hashPacked(value, nameLength);
          latin1 = false;
        }
        hash = JsonFieldNameHash.update(hash, Character.highSurrogate(codePoint));
        hash = JsonFieldNameHash.update(hash, Character.lowSurrogate(codePoint));
        nameLength += 2;
      }
    }
    throw error("Unterminated string");
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
      if (position >= input.length) {
        throw error("Unterminated string");
      }
      b = input[position++] & 0xFF;
    }
  }

  private String readStringUtf16Tail(byte[] bytes, int out) {
    byte[] input = this.input;
    int position = this.position;
    int inputLength = input.length;
    int capacity = bytes.length;
    while (position < inputLength) {
      int b = input[position++] & 0xFF;
      if ((b & 0xF0) == 0xE0) {
        if (position >= inputLength) {
          this.position = position;
          throw error("Short UTF-8 sequence");
        }
        int second = input[position++] & 0xFF;
        if ((second & 0xC0) != 0x80) {
          this.position = position;
          throw error("Invalid UTF-8 continuation");
        }
        if (position >= inputLength) {
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
        this.position = position;
        char ch = readEscapedStringChar();
        position = this.position;
        if (Character.isHighSurrogate(ch)) {
          char low = readLowSurrogateEscape();
          position = this.position;
          if (out + 4 > capacity) {
            bytes = growStringDecodeBuffer(bytes, out + 4);
            capacity = bytes.length;
          }
          out = putUtf16Char(bytes, out, ch);
          out = putUtf16Char(bytes, out, low);
        } else if (Character.isLowSurrogate(ch)) {
          throw error("Unpaired low surrogate escape");
        } else {
          if (out + 2 > capacity) {
            bytes = growStringDecodeBuffer(bytes, out + 2);
            capacity = bytes.length;
          }
          out = putUtf16Char(bytes, out, ch);
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
        if (position >= inputLength) {
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
        if (position >= inputLength) {
          this.position = position;
          throw error("Short UTF-8 sequence");
        }
        int second = input[position++] & 0xFF;
        if ((second & 0xC0) != 0x80) {
          this.position = position;
          throw error("Invalid UTF-8 continuation");
        }
        if (position >= inputLength) {
          this.position = position;
          throw error("Short UTF-8 sequence");
        }
        int third = input[position++] & 0xFF;
        if ((third & 0xC0) != 0x80) {
          this.position = position;
          throw error("Invalid UTF-8 continuation");
        }
        if (position >= inputLength) {
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
      int inputLength = input.length;
      if (position >= inputLength) {
        this.position = position;
        throw error("Short UTF-8 sequence");
      }
      int second = input[position++] & 0xFF;
      if ((second & 0xC0) != 0x80) {
        this.position = position;
        throw error("Invalid UTF-8 continuation");
      }
      if (position >= inputLength) {
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

  private char readEscapedStringChar() {
    if (position >= input.length) {
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
    if (position + 2 > input.length || input[position] != '\\' || input[position + 1] != 'u') {
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
    if (position >= input.length) {
      throw error("Short UTF-8 sequence");
    }
    int value = input[position++] & 0xFF;
    if ((value & 0xC0) != 0x80) {
      throw error("Invalid UTF-8 continuation");
    }
    return value & 0x3F;
  }

  private void skipWhitespaceFast() {
    while (position < input.length) {
      int ch = input[position];
      if (ch > ' ') {
        return;
      }
      if (isWhitespace(ch)) {
        position++;
      } else {
        return;
      }
    }
  }

  private static boolean isWhitespace(int ch) {
    return ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t';
  }

  private void rejectLeadingDigitFast() {
    if (position < input.length) {
      int ch = input[position];
      if (ch >= '0' && ch <= '9') {
        throw error("Leading zero in number");
      }
    }
  }

  private void rejectFractionOrExponentFast() {
    if (position < input.length) {
      int ch = input[position];
      if (ch == '.' || ch == 'e' || ch == 'E') {
        throw error("Expected integer");
      }
    }
  }

  private int readZeroIntName(int nameStart) {
    if (position >= input.length) {
      throw error("Unterminated string");
    }
    int ch = input[position];
    if (ch == '\\') {
      position = nameStart;
      return super.readFieldNameInt();
    }
    if (ch >= '0' && ch <= '9') {
      throw error("Leading zero in number");
    }
    if (ch != '"') {
      throw error("Expected integer field name");
    }
    position++;
    return 0;
  }

  private long readZeroLongName(int nameStart) {
    if (position >= input.length) {
      throw error("Unterminated string");
    }
    int ch = input[position];
    if (ch == '\\') {
      position = nameStart;
      return super.readFieldNameLong();
    }
    if (ch >= '0' && ch <= '9') {
      throw error("Leading zero in number");
    }
    if (ch != '"') {
      throw error("Expected long field name");
    }
    position++;
    return 0L;
  }
}
