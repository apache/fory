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
 * JSON reader for Java String input represented as UTF16 code units.
 *
 * <p>On compact-string JVMs, a UTF16-coded String is read through its borrowed native byte storage.
 * On char-backed JVMs the public facade copies characters once into a reusable native-layout byte
 * mirror, retaining the original String for result slicing. Direct character access remains
 * available when no byte mirror exists. Returned values never retain the reusable decode buffer.
 *
 * <p>This concrete owner implements UTF16 token probes, packed digit parsing, string decoding, and
 * field hashing. {@link #clear()} releases both borrowed representations and bounds the retained
 * decode workspace before the pooled state is reused.
 */
public final class Utf16JsonReader extends JsonReader {
  private static final int INITIAL_STRING_DECODE_BUFFER_SIZE = 1024;
  private static final int RETAINED_STRING_DECODE_BUFFER_SIZE = 8192;
  private static final boolean LITTLE_ENDIAN = NativeByteOrder.IS_LITTLE_ENDIAN;
  private static final long BYTE_ONES = 0x0101010101010101L;
  private static final long BYTE_HIGH_BITS = 0x8080808080808080L;
  private static final long BACKSLASH_BYTES = 0x5c5c5c5c5c5c5c5cL;
  private static final long CONTROL_LIMIT_BYTES = 0x2020202020202020L;
  private static final long QUOTE_BYTES = 0x2222222222222222L;
  private static final long UTF16_PAIR_MASK = 0x0000FFFF0000FFFFL;
  private static final long UTF16_BYTE_MASK = 0x00FF00FF00FF00FFL;
  private static final long UTF16_ONES = 0x0001000100010001L;
  private static final long UTF16_HIGH_BITS = 0x8000800080008000L;
  private static final long UTF16_QUOTE_CHARS = 0x0022002200220022L;
  private static final long UTF16_BACKSLASH_CHARS = 0x005c005c005c005cL;
  private static final long UTF16_CONTROL_LIMIT = 0x0020002000200020L;
  private static final long UTF16_NON_LATIN_BYTES = 0xFF00FF00FF00FF00L;
  private static final long UTF16_NON_ASCII_BITS = 0xFF80FF80FF80FF80L;
  private static final long UTF16_SURROGATE_MASK = 0xf800f800f800f800L;
  private static final long UTF16_SURROGATE_PREFIX = 0xd800d800d800d800L;
  private static final long LONG_MAX_DIV_10 = Long.MAX_VALUE / 10;
  private static final int LONG_MAX_MOD_10 = (int) (Long.MAX_VALUE % 10);
  private static final long LONG_MAX_DIV_100 = Long.MAX_VALUE / 100;
  private static final int LONG_MAX_MOD_100 = (int) (Long.MAX_VALUE % 100);
  private String input;
  private byte[] bytes;
  private int length;
  private byte[] stringDecodeBuffer = new byte[INITIAL_STRING_DECODE_BUFFER_SIZE];
  // Keep the cache after hot representation fields; an inherited reference shifts their offsets.
  private final FieldNameCache fieldNameCache;

  public Utf16JsonReader(JsonConfig config, JsonTypeResolver typeResolver) {
    super(config, typeResolver);
    input = "";
    bytes = null;
    length = 0;
    // The configured limit belongs to each reader; pooled-state concurrency must not divide it.
    int maxEntries = config.maxCachedFieldNames();
    fieldNameCache = maxEntries == 0 ? null : new FieldNameCache(maxEntries);
  }

  @Override
  protected int scanStringEnd(int start) {
    if (start >= length || charAtFast(start) != '"') {
      throw errorAt("Expected string", start);
    }
    int cursor = start + 1;
    if (LITTLE_ENDIAN && bytes != null) {
      int wordEnd = length - 4;
      while (cursor <= wordEnd) {
        long word = LittleEndian.getInt64(bytes, cursor << 1);
        long stopMask = utf16StringStopMask(word, word & UTF16_NON_LATIN_BYTES);
        if (stopMask == 0) {
          cursor += 4;
          continue;
        }
        cursor += Long.numberOfTrailingZeros(stopMask) >>> 4;
        char ch = charAtFast(cursor);
        if (ch == '"') {
          return cursor + 1;
        }
        if (ch < 0x20) {
          throw errorAt("Control character in string", cursor);
        }
        if (ch == '\\') {
          cursor = scanEscape(cursor);
        } else if (Character.isHighSurrogate(ch)) {
          cursor = scanRawSurrogate(cursor);
        } else if (Character.isLowSurrogate(ch)) {
          throw errorAt("Unpaired low surrogate in string", cursor);
        } else {
          cursor++;
        }
      }
    }
    while (cursor < length) {
      char ch = charAtFast(cursor);
      if (ch == '"') {
        return cursor + 1;
      }
      if (ch < 0x20) {
        throw errorAt("Control character in string", cursor);
      }
      if (ch == '\\') {
        cursor = scanEscape(cursor);
      } else if (Character.isHighSurrogate(ch)) {
        cursor = scanRawSurrogate(cursor);
      } else if (Character.isLowSurrogate(ch)) {
        throw errorAt("Unpaired low surrogate in string", cursor);
      } else {
        cursor++;
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
      char ch = charAtFast(cursor++);
      if (ch == '\\') {
        char escaped = charAtFast(cursor++);
        if (escaped == 'u') {
          ch = scanUnicodeEscape(cursor);
          cursor += 4;
        } else {
          ch = scanSimpleEscape(escaped, cursor - 1);
        }
      }
      if (Character.isHighSurrogate(ch)) {
        if (latin1) {
          hash = JsonFieldNameHash.hashPacked(value, decodedLength);
          latin1 = false;
        }
        hash = JsonFieldNameHash.update(hash, ch);
        decodedLength++;
        char low;
        if (cursor < limit && charAtFast(cursor) == '\\') {
          cursor += 2;
          low = scanUnicodeEscape(cursor);
          cursor += 4;
        } else {
          low = charAtFast(cursor++);
        }
        hash = JsonFieldNameHash.update(hash, low);
        decodedLength++;
      } else if (latin1 && ch <= 0xff && ch != 0 && decodedLength < Long.BYTES) {
        value = JsonFieldNameHash.value(value, decodedLength++, ch);
      } else {
        if (latin1) {
          hash = JsonFieldNameHash.hashPacked(value, decodedLength);
          latin1 = false;
        }
        hash = JsonFieldNameHash.update(hash, ch);
        decodedLength++;
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
      char ch = charAtFast(cursor++);
      if (ch == '\\') {
        char escaped = charAtFast(cursor++);
        if (escaped == 'u') {
          ch = scanUnicodeEscape(cursor);
          cursor += 4;
        } else {
          ch = scanSimpleEscape(escaped, cursor - 1);
        }
        matches &= index < expected.length() && expected.charAt(index++) == ch;
        if (Character.isHighSurrogate(ch)) {
          cursor += 2;
          char low = scanUnicodeEscape(cursor);
          cursor += 4;
          matches &= index < expected.length() && expected.charAt(index++) == low;
        }
        continue;
      }
      matches &= index < expected.length() && expected.charAt(index++) == ch;
      if (Character.isHighSurrogate(ch)) {
        char low = charAtFast(cursor++);
        matches &= index < expected.length() && expected.charAt(index++) == low;
      }
    }
    return matches && index == expected.length();
  }

  private int scanEscape(int slash) {
    int cursor = slash + 1;
    if (cursor >= length) {
      throw errorAt("Unterminated escape", slash);
    }
    char escaped = charAtFast(cursor++);
    if (escaped != 'u') {
      scanSimpleEscape(escaped, cursor - 1);
      return cursor;
    }
    char ch = scanUnicodeEscape(cursor);
    cursor += 4;
    if (Character.isHighSurrogate(ch)) {
      if (cursor + 6 > length || charAtFast(cursor) != '\\' || charAtFast(cursor + 1) != 'u') {
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

  private int scanRawSurrogate(int cursor) {
    if (cursor + 1 >= length || !Character.isLowSurrogate(charAtFast(cursor + 1))) {
      throw errorAt("Unpaired high surrogate in string", cursor);
    }
    return cursor + 2;
  }

  private char scanUnicodeEscape(int offset) {
    if (offset > length - 4) {
      throw errorAt("Incomplete unicode escape", offset);
    }
    int value = 0;
    for (int i = 0; i < 4; i++) {
      char ch = charAtFast(offset + i);
      int digit;
      if (ch >= '0' && ch <= '9') {
        digit = ch - '0';
      } else {
        char lower = (char) (ch | 0x20);
        if (lower < 'a' || lower > 'f') {
          throw errorAt("Invalid unicode escape", offset + i);
        }
        digit = lower - 'a' + 10;
      }
      value = (value << 4) | digit;
    }
    return (char) value;
  }

  private char scanSimpleEscape(char escaped, int offset) {
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

  public Utf16JsonReader(JsonConfig config, JsonTypeResolver typeResolver, String input) {
    this(config, typeResolver);
    reset(input);
  }

  public Utf16JsonReader reset(String input) {
    this.input = input;
    if (StringSerializer.isBytesBackedString()) {
      byte coder = StringSerializer.getStringCoder(input);
      if (StringSerializer.isUtf16Coder(coder)) {
        bytes = StringSerializer.getStringBytes(input);
        length = bytes.length >>> 1;
        position = 0;
        reset();
        return this;
      }
    }
    bytes = null;
    length = input.length();
    position = 0;
    reset();
    return this;
  }

  /**
   * Resets this reader with a String and its exact native-layout UTF16 byte mirror.
   *
   * <p>The caller owns creating the mirror. Its first {@code input.length() * 2} bytes must encode
   * the same UTF16 code units in the layout used by {@link StringSerializer}; this method validates
   * capacity but does not compare the mirror on the hot setup path.
   */
  public Utf16JsonReader reset(String input, byte[] bytes) {
    int length = input.length();
    if (length > (Integer.MAX_VALUE >>> 1)) {
      throw new IllegalArgumentException("String is too large");
    }
    if (bytes.length < (length << 1)) {
      throw new IllegalArgumentException("UTF16 byte array is too small");
    }
    this.input = input;
    this.bytes = bytes;
    this.length = length;
    position = 0;
    reset();
    return this;
  }

  public void clear() {
    reset();
    input = "";
    bytes = null;
    length = 0;
    position = 0;
    if (stringDecodeBuffer.length > RETAINED_STRING_DECODE_BUFFER_SIZE) {
      stringDecodeBuffer = new byte[RETAINED_STRING_DECODE_BUFFER_SIZE];
    }
  }

  public boolean consumeToken(char expected) {
    skipWhitespaceFast();
    if (position < length && asciiAtFast(position) == expected) {
      position++;
      return true;
    }
    return false;
  }

  public boolean consumeNextToken(char expected) {
    if (position < length && asciiAtFast(position) == expected) {
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
    if (position < length && asciiAtFast(position) == expected) {
      position++;
      return;
    }
    expectNextTokenSlow(expected);
  }

  private void expectNextTokenSlow(char expected) {
    expectToken(expected);
  }

  public boolean consumeNextCommaOrEndObject() {
    int inputLength = length;
    if (position < inputLength) {
      int ch = asciiAtFast(position);
      if (ch == ',') {
        position++;
        return true;
      }
      if (ch == '}') {
        position++;
        return false;
      }
      if (!isWhitespace(ch)) {
        return consumeNextCommaOrEndObjectSlow(inputLength);
      }
    }
    return consumeNextCommaOrEndObjectSlow(inputLength);
  }

  private boolean consumeNextCommaOrEndObjectSlow(int inputLength) {
    skipWhitespaceFast();
    if (position < inputLength) {
      int ch = asciiAtFast(position);
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
    int inputLength = length;
    if (position < inputLength) {
      int ch = asciiAtFast(position);
      if (ch == ',') {
        position++;
        return true;
      }
      if (ch == ']') {
        position++;
        return false;
      }
      if (!isWhitespace(ch)) {
        return consumeNextCommaOrEndArraySlow(inputLength);
      }
    }
    return consumeNextCommaOrEndArraySlow(inputLength);
  }

  private boolean consumeNextCommaOrEndArraySlow(int inputLength) {
    skipWhitespaceFast();
    if (position < inputLength) {
      int ch = asciiAtFast(position);
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
    if (position < length) {
      int ch = asciiAtFast(position);
      if (ch == 'n') {
        return tryReadNullLiteral();
      }
      if (!isWhitespace(ch)) {
        return false;
      }
    }
    return tryReadNullToken();
  }

  private boolean tryReadNullLiteral() {
    if (startsWithAscii("null")) {
      position += 4;
      return true;
    }
    return false;
  }

  public boolean readBooleanValue() {
    skipWhitespaceFast();
    return readBooleanToken();
  }

  public boolean readNextBooleanValue() {
    if (position < length && !isWhitespace(asciiAtFast(position))) {
      return readBooleanToken();
    }
    return readBooleanValue();
  }

  public boolean readBooleanTokenValue() {
    return readBooleanToken();
  }

  private boolean readBooleanToken() {
    if (startsWithAscii("true")) {
      position += 4;
      return true;
    } else if (startsWithAscii("false")) {
      position += 5;
      return false;
    }
    throw error("Expected boolean");
  }

  public int readIntValue() {
    skipWhitespaceFast();
    return readIntToken();
  }

  public int readNextIntValue() {
    if (position < length && !isWhitespace(asciiAtFast(position))) {
      return readIntToken();
    }
    return readIntValue();
  }

  public int readIntTokenValue() {
    return readIntToken();
  }

  private int readIntToken() {
    int offset = position;
    int inputLength = length;
    if (offset >= inputLength) {
      throw error("Expected digit");
    }
    char ch = charAtFast(offset);
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
      ch = charAtFast(offset);
      if (ch < '0' || ch > '9') {
        break;
      }
      result = result * 10 + (ch - '0');
      offset++;
    }
    if (offset < inputLength) {
      ch = charAtFast(offset);
      if (ch >= '0' && ch <= '9') {
        return readPositiveIntTail(offset, inputLength, result);
      }
    }
    position = offset;
    rejectFractionOrExponentFast();
    return result;
  }

  private int readPositiveIntTail(int offset, int inputLength, int result) {
    while (offset < inputLength) {
      char ch = charAtFast(offset);
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
    if (position >= length) {
      throw error("Expected digit");
    }
    char ch = charAtFast(position);
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
    while (position < length) {
      ch = charAtFast(position);
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
    if (position < length && !isWhitespace(asciiAtFast(position))) {
      return readLongToken();
    }
    return readLongValue();
  }

  public long readLongTokenValue() {
    return readLongToken();
  }

  private long readLongToken() {
    int offset = position;
    int inputLength = length;
    if (offset >= inputLength) {
      throw error("Expected digit");
    }
    char ch = charAtFast(offset);
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
    while (offset < safeEnd) {
      ch = charAtFast(offset);
      if (ch < '0' || ch > '9') {
        break;
      }
      result = result * 10 + (ch - '0');
      offset++;
    }
    if (offset < inputLength) {
      ch = charAtFast(offset);
      if (ch >= '0' && ch <= '9') {
        return readPositiveLongTail(offset, inputLength, result);
      }
    }
    position = offset;
    rejectFractionOrExponentFast();
    return result;
  }

  private long readPositiveLongTail(int offset, int inputLength, long result) {
    while (offset < inputLength) {
      char ch = charAtFast(offset);
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
    position = start + 1;
    long result = 0;
    long limit = Long.MIN_VALUE;
    if (position >= length) {
      throw error("Expected digit");
    }
    char ch = charAtFast(position);
    if (ch == '0') {
      position++;
      rejectLeadingDigitFast();
      rejectFractionOrExponentFast();
      return 0;
    }
    if (ch < '1' || ch > '9') {
      throw error("Expected digit");
    }
    long multmin = limit / 10;
    while (position < length) {
      ch = charAtFast(position);
      if (ch < '0' || ch > '9') {
        break;
      }
      int digit = ch - '0';
      if (result < multmin) {
        throw error("Long overflow");
      }
      result *= 10;
      if (result < Long.MIN_VALUE + digit) {
        throw error("Long overflow");
      }
      result -= digit;
      position++;
    }
    rejectFractionOrExponentFast();
    return result;
  }

  @Override
  public BigDecimal readBigDecimal() {
    skipWhitespaceFast();
    return readBigDecimalToken();
  }

  private BigDecimal readBigDecimalToken() {
    int offset = position;
    int start = offset;
    int inputLength = length;
    if (offset >= inputLength) {
      return readBigDecimalFallback(start);
    }
    char ch = charAtFast(offset);
    if (ch == '-') {
      return readSignedBigDecimalToken(start);
    }
    long unscaled = 0;
    int scale = 0;
    if (ch == '0') {
      position = ++offset;
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
        ch = charAtFast(offset);
      } while (ch >= '0' && ch <= '9');
    } else {
      return readBigDecimalFallback(start);
    }
    if (offset < inputLength && charAtFast(offset) == '.') {
      offset++;
      int fractionStart = offset;
      while (offset < inputLength) {
        ch = charAtFast(offset);
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
      ch = charAtFast(offset);
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
    int offset = start + 1;
    int inputLength = length;
    if (offset >= inputLength) {
      return readBigDecimalFallback(start);
    }
    char ch = charAtFast(offset);
    long unscaled = 0;
    int scale = 0;
    if (ch == '0') {
      position = ++offset;
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
        ch = charAtFast(offset);
      } while (ch >= '0' && ch <= '9');
    } else {
      return readBigDecimalFallback(start);
    }
    if (offset < inputLength && charAtFast(offset) == '.') {
      offset++;
      int fractionStart = offset;
      while (offset < inputLength) {
        ch = charAtFast(offset);
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
      ch = charAtFast(offset);
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

  @Override
  public double readDouble() {
    skipWhitespaceFast();
    return readDoubleToken();
  }

  public double readNextDoubleValue() {
    if (position < length) {
      char ch = charAtFast(position);
      if (ch > ' ' || !isWhitespace(ch)) {
        return readDoubleToken();
      }
    }
    return readDouble();
  }

  public double readDoubleTokenValue() {
    return readDoubleToken();
  }

  @Override
  public float readFloat() {
    skipWhitespaceFast();
    return readFloatToken();
  }

  public float readNextFloatValue() {
    if (position < length) {
      char ch = charAtFast(position);
      if (ch > ' ' || !isWhitespace(ch)) {
        return readFloatToken();
      }
    }
    return readFloat();
  }

  public float readFloatTokenValue() {
    return readFloatToken();
  }

  private double readDoubleToken() {
    int offset = position;
    int inputLength = length;
    if (offset >= inputLength) {
      return readDoubleFallback(offset);
    }
    char ch = charAtFast(offset);
    if (ch == '-') {
      return readSignedDoubleToken(offset);
    }
    return readPositiveDoubleToken(offset, inputLength, ch);
  }

  private float readFloatToken() {
    int offset = position;
    int inputLength = length;
    if (offset >= inputLength) {
      return readFloatFallback(offset);
    }
    char ch = charAtFast(offset);
    if (ch == '-') {
      return readSignedFloatToken(offset);
    }
    return readPositiveFloatToken(offset, inputLength, ch);
  }

  private float readPositiveFloatToken(int offset, int inputLength, char ch) {
    int start = offset;
    long unscaled = 0;
    if (ch == '0') {
      offset++;
      if (offset < inputLength) {
        ch = charAtFast(offset);
        if (ch >= '0' && ch <= '9') {
          return readFloatFallback(start);
        }
      }
    } else if (ch >= '1' && ch <= '9') {
      unscaled = ch - '0';
      offset++;
      while (offset + 1 < inputLength) {
        int high = charAtFast(offset) - '0';
        int low = charAtFast(offset + 1) - '0';
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
        int digit = charAtFast(offset) - '0';
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
    return readPositiveFloatTail(offset, inputLength, start, unscaled);
  }

  private float readSignedFloatToken(int start) {
    int offset = start + 1;
    int inputLength = length;
    if (offset >= inputLength) {
      return readFloatFallback(start);
    }
    char ch = charAtFast(offset);
    long unscaled = 0;
    if (ch == '0') {
      offset++;
      if (offset < inputLength) {
        ch = charAtFast(offset);
        if (ch >= '0' && ch <= '9') {
          return readFloatFallback(start);
        }
      }
    } else if (ch >= '1' && ch <= '9') {
      unscaled = ch - '0';
      offset++;
      while (offset + 1 < inputLength) {
        int high = charAtFast(offset) - '0';
        int low = charAtFast(offset + 1) - '0';
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
        int digit = charAtFast(offset) - '0';
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
    return readSignedFloatTail(offset, inputLength, start, unscaled);
  }

  private float readPositiveFloatTail(int offset, int inputLength, int start, long unscaled) {
    int scale = 0;
    if (offset < inputLength && charAtFast(offset) == '.') {
      offset++;
      int fractionStart = offset;
      while (offset + 1 < inputLength) {
        int high = charAtFast(offset) - '0';
        int low = charAtFast(offset + 1) - '0';
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
        int digit = charAtFast(offset) - '0';
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
    return finishFloatToken(offset, inputLength, start, unscaled, scale);
  }

  private float readSignedFloatTail(int offset, int inputLength, int start, long unscaled) {
    int scale = 0;
    if (offset < inputLength && charAtFast(offset) == '.') {
      offset++;
      int fractionStart = offset;
      while (offset + 1 < inputLength) {
        int high = charAtFast(offset) - '0';
        int low = charAtFast(offset + 1) - '0';
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
        int digit = charAtFast(offset) - '0';
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
    return finishSignedFloatToken(offset, inputLength, start, unscaled, scale);
  }

  private float finishFloatToken(int offset, int inputLength, int start, long unscaled, int scale) {
    if (offset < inputLength) {
      char ch = charAtFast(offset);
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
      int offset, int inputLength, int start, long unscaled, int scale) {
    if (offset < inputLength) {
      char ch = charAtFast(offset);
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

  private double readPositiveDoubleToken(int offset, int inputLength, char ch) {
    int start = offset;
    long unscaled = 0;
    if (ch == '0') {
      offset++;
      if (offset < inputLength) {
        ch = charAtFast(offset);
        if (ch >= '0' && ch <= '9') {
          return readDoubleFallback(start);
        }
      }
    } else if (ch >= '1' && ch <= '9') {
      unscaled = ch - '0';
      offset++;
      while (offset + 1 < inputLength) {
        int high = charAtFast(offset) - '0';
        int low = charAtFast(offset + 1) - '0';
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
        int digit = charAtFast(offset) - '0';
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
    return readPositiveDoubleTail(offset, inputLength, start, unscaled);
  }

  private double readSignedDoubleToken(int start) {
    int offset = start + 1;
    int inputLength = length;
    if (offset >= inputLength) {
      return readDoubleFallback(start);
    }
    char ch = charAtFast(offset);
    long unscaled = 0;
    if (ch == '0') {
      offset++;
      if (offset < inputLength) {
        ch = charAtFast(offset);
        if (ch >= '0' && ch <= '9') {
          return readDoubleFallback(start);
        }
      }
    } else if (ch >= '1' && ch <= '9') {
      unscaled = ch - '0';
      offset++;
      while (offset + 1 < inputLength) {
        int high = charAtFast(offset) - '0';
        int low = charAtFast(offset + 1) - '0';
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
        int digit = charAtFast(offset) - '0';
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
    return readSignedDoubleTail(offset, inputLength, start, unscaled);
  }

  private double readPositiveDoubleTail(int offset, int inputLength, int start, long unscaled) {
    int scale = 0;
    if (offset < inputLength && charAtFast(offset) == '.') {
      offset++;
      int fractionStart = offset;
      while (offset + 1 < inputLength) {
        int high = charAtFast(offset) - '0';
        int low = charAtFast(offset + 1) - '0';
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
        int digit = charAtFast(offset) - '0';
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
    return finishDoubleToken(offset, inputLength, start, unscaled, scale);
  }

  private double readSignedDoubleTail(int offset, int inputLength, int start, long unscaled) {
    int scale = 0;
    if (offset < inputLength && charAtFast(offset) == '.') {
      offset++;
      int fractionStart = offset;
      while (offset + 1 < inputLength) {
        int high = charAtFast(offset) - '0';
        int low = charAtFast(offset + 1) - '0';
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
        int digit = charAtFast(offset) - '0';
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
    return finishSignedDoubleToken(offset, inputLength, start, unscaled, scale);
  }

  private double finishDoubleToken(
      int offset, int inputLength, int start, long unscaled, int scale) {
    if (offset < inputLength) {
      char ch = charAtFast(offset);
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
      int offset, int inputLength, int start, long unscaled, int scale) {
    if (offset < inputLength) {
      char ch = charAtFast(offset);
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
    if (position >= length || charAtFast(position++) != '"') {
      throw error("Expected string");
    }
    int result = 0;
    int limit = -Integer.MAX_VALUE;
    boolean negative = false;
    if (position < length && charAtFast(position) == '-') {
      negative = true;
      limit = Integer.MIN_VALUE;
      position++;
    }
    if (position >= length) {
      throw error("Unterminated string");
    }
    char ch = charAtFast(position);
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
      if (position >= length) {
        throw error("Unterminated string");
      }
      ch = charAtFast(position);
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
    if (position >= length || charAtFast(position++) != '"') {
      throw error("Expected string");
    }
    long result = 0;
    long limit = -Long.MAX_VALUE;
    boolean negative = false;
    if (position < length && charAtFast(position) == '-') {
      negative = true;
      limit = Long.MIN_VALUE;
      position++;
    }
    if (position >= length) {
      throw error("Unterminated string");
    }
    char ch = charAtFast(position);
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
      if (position >= length) {
        throw error("Unterminated string");
      }
      ch = charAtFast(position);
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
    return length;
  }

  @Override
  protected char charAt(int index) {
    return charAtFast(index);
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
    if (position >= length || charAtFast(position++) != '"') {
      throw error("Expected string");
    }
    int start = position;
    if (LITTLE_ENDIAN && bytes != null) {
      return readFieldNameWords(cache, start);
    }
    return readFieldNameTail(cache, start, start, 0, 0, 0);
  }

  private String readFieldNameWords(FieldNameCache cache, int start) {
    byte[] localBytes = bytes;
    int inputLength = length;
    if (start + 4 > inputLength) {
      return readFieldNameTail(cache, start, start, 0, 0, 0);
    }
    long word = LittleEndian.getInt64(localBytes, start << 1);
    long stopMask =
        utf16StringStopMask(word, word & UTF16_NON_LATIN_BYTES) | (word & UTF16_NON_ASCII_BITS);
    if (stopMask != 0) {
      int chars = Long.numberOfTrailingZeros(stopMask) >>> 4;
      int stop = start + chars;
      if (charAtFast(stop) != '"') {
        return continueFieldName(start, stop);
      }
      position = stop + 1;
      return resolveShortFieldName(
          cache, start, stop, chars, fieldNameWord(packAsciiUtf16(word), chars));
    }
    long word0 = packAsciiUtf16(word);
    int offset = start + 4;
    if (offset + 4 > inputLength) {
      return readFieldNameTail(cache, start, offset, 4, word0, 0);
    }
    word = LittleEndian.getInt64(localBytes, offset << 1);
    stopMask =
        utf16StringStopMask(word, word & UTF16_NON_LATIN_BYTES) | (word & UTF16_NON_ASCII_BITS);
    if (stopMask != 0) {
      int chars = Long.numberOfTrailingZeros(stopMask) >>> 4;
      int stop = offset + chars;
      if (charAtFast(stop) != '"') {
        return continueFieldName(start, stop);
      }
      position = stop + 1;
      word0 |= fieldNameWord(packAsciiUtf16(word), chars) << 32;
      return resolveShortFieldName(cache, start, stop, 4 + chars, word0);
    }
    word0 |= packAsciiUtf16(word) << 32;
    return readFieldNameAfterWord0(cache, start, offset + 4, word0);
  }

  private String readFieldNameAfterWord0(FieldNameCache cache, int start, int offset, long word0) {
    byte[] localBytes = bytes;
    long word1 = 0;
    int nameLength = Long.BYTES;
    while (nameLength < 16 && offset + 4 <= length) {
      long word = LittleEndian.getInt64(localBytes, offset << 1);
      long stopMask =
          utf16StringStopMask(word, word & UTF16_NON_LATIN_BYTES) | (word & UTF16_NON_ASCII_BITS);
      if (stopMask != 0) {
        int chars = Long.numberOfTrailingZeros(stopMask) >>> 4;
        int stop = offset + chars;
        char ch = charAtFast(stop);
        if (ch != '"') {
          return continueFieldName(start, stop);
        }
        position = stop + 1;
        long packed = fieldNameWord(packAsciiUtf16(word), chars);
        word1 |= packed << ((nameLength - Long.BYTES) << 3);
        return resolveFieldName(cache, start, stop, nameLength + chars, word0, word1);
      }
      long packed = packAsciiUtf16(word);
      word1 |= packed << ((nameLength - Long.BYTES) << 3);
      nameLength += 4;
      offset += 4;
    }
    if (nameLength == 16) {
      if (offset < length && charAtFast(offset) == '"') {
        position = offset + 1;
        return resolveFieldName(cache, start, offset, nameLength, word0, word1);
      }
      return continueFieldName(start, offset);
    }
    return readFieldNameTail(cache, start, offset, nameLength, word0, word1);
  }

  private String resolveShortFieldName(
      FieldNameCache cache, int start, int end, int nameLength, long word0) {
    long hash = nameLength == 0 ? JsonFieldNameHash.MAGIC_HASH_CODE : word0;
    CachedFieldName entry = cache.get(hash);
    if (entry != null) {
      return entry.matches(nameLength, word0, 0) ? entry.name() : input.substring(start, end);
    }
    if (!cache.canPut(hash)) {
      return input.substring(start, end);
    }
    return readFieldNameMiss(cache, start, end, nameLength, word0, 0, hash);
  }

  private String readFieldNameTail(
      FieldNameCache cache, int start, int offset, int nameLength, long word0, long word1) {
    while (offset < length) {
      char ch = charAtFast(offset);
      if (ch == '"') {
        position = offset + 1;
        return resolveFieldName(cache, start, offset, nameLength, word0, word1);
      }
      if (ch == '\\' || ch > 0x7f || ch < 0x20) {
        return continueFieldName(start, offset);
      }
      if (nameLength == 16) {
        return continueFieldName(start, offset);
      }
      if (nameLength < Long.BYTES) {
        word0 |= ((long) ch) << (nameLength << 3);
      } else {
        word1 |= ((long) ch) << ((nameLength - Long.BYTES) << 3);
      }
      nameLength++;
      offset++;
    }
    throw error("Unterminated string");
  }

  private String continueFieldName(int start, int offset) {
    position = offset;
    if (LITTLE_ENDIAN && bytes != null) {
      return readUtf16StringToken(start, offset, false);
    }
    return readStringLoop(start, false);
  }

  private String readFieldNameMiss(
      FieldNameCache cache, int start, int end, int length, long word0, long word1, long hash) {
    JsonSharedRegistry registry = typeResolver().sharedRegistry();
    CachedFieldName entry = registry.cachedFieldName(hash);
    if (entry != null) {
      cache.put(hash, entry);
      return entry.matches(length, word0, word1) ? entry.name() : input.substring(start, end);
    }
    String candidate = input.substring(start, end);
    entry = registry.cacheFieldName(hash, candidate, word0, word1);
    cache.put(hash, entry);
    return entry.matches(length, word0, word1) ? entry.name() : candidate;
  }

  private String resolveFieldName(
      FieldNameCache cache, int start, int end, int length, long word0, long word1) {
    long hash = fieldNameHash(length, word0, word1);
    CachedFieldName entry = cache.get(hash);
    if (entry != null) {
      return entry.matches(length, word0, word1) ? entry.name() : input.substring(start, end);
    }
    if (!cache.canPut(hash)) {
      return input.substring(start, end);
    }
    return readFieldNameMiss(cache, start, end, length, word0, word1, hash);
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
    if (position < length) {
      int ch = asciiAtFast(position);
      if (ch == '"') {
        position++;
        return readStringAfterQuote();
      }
      if (ch == 'n' && tryReadNullLiteral()) {
        return null;
      }
      if (!isWhitespace(ch)) {
        return readStringToken();
      }
    }
    return readNullableString();
  }

  public String readNullableStringToken() {
    if (position < length) {
      int ch = asciiAtFast(position);
      if (ch == '"') {
        position++;
        return readStringAfterQuote();
      }
      if (ch == 'n' && tryReadNullLiteral()) {
        return null;
      }
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
    if (position >= length || charAtFast(position++) != '"') {
      throw error("Expected string");
    }
    return readStringAfterQuote();
  }

  private String readStringAfterQuote() {
    int start = position;
    if (LITTLE_ENDIAN && bytes != null) {
      return readUtf16StringToken(start, start, false);
    }
    return readStringLoop(start, false);
  }

  private String readUtf16StringToken(int start, int offset, boolean nonLatin) {
    byte[] localBytes = bytes;
    int inputLength = length;
    int doubleWordEnd = inputLength - 8;
    while (offset <= doubleWordEnd) {
      long word = LittleEndian.getInt64(localBytes, offset << 1);
      long nonLatinBytes = word & UTF16_NON_LATIN_BYTES;
      long stopMask = utf16StringStopMask(word, nonLatinBytes);
      if (stopMask != 0) {
        int stop = offset + (Long.numberOfTrailingZeros(stopMask) >>> 4);
        boolean currentNonLatin =
            nonLatin || nonLatinBytes != 0 && hasNonLatinBeforeStop(word, stop - offset);
        char ch = charAtFast(stop);
        if (Character.isHighSurrogate(ch)) {
          currentNonLatin = true;
        }
        String value = readUtf16StringStop(localBytes, start, stop, currentNonLatin, ch);
        if (value != null) {
          return value;
        }
        nonLatin = currentNonLatin;
        offset = position;
        continue;
      }
      nonLatin |= nonLatinBytes != 0;
      int nextOffset = offset + 4;
      word = LittleEndian.getInt64(localBytes, nextOffset << 1);
      nonLatinBytes = word & UTF16_NON_LATIN_BYTES;
      stopMask = utf16StringStopMask(word, nonLatinBytes);
      if (stopMask != 0) {
        int stop = nextOffset + (Long.numberOfTrailingZeros(stopMask) >>> 4);
        boolean currentNonLatin =
            nonLatin || nonLatinBytes != 0 && hasNonLatinBeforeStop(word, stop - nextOffset);
        char ch = charAtFast(stop);
        if (Character.isHighSurrogate(ch)) {
          currentNonLatin = true;
        }
        String value = readUtf16StringStop(localBytes, start, stop, currentNonLatin, ch);
        if (value != null) {
          return value;
        }
        nonLatin = currentNonLatin;
        offset = position;
        continue;
      }
      nonLatin |= nonLatinBytes != 0;
      offset = nextOffset + 4;
    }
    int wordEnd = inputLength - 4;
    while (offset <= wordEnd) {
      long word = LittleEndian.getInt64(localBytes, offset << 1);
      long nonLatinBytes = word & UTF16_NON_LATIN_BYTES;
      long stopMask = utf16StringStopMask(word, nonLatinBytes);
      if (stopMask == 0) {
        nonLatin |= nonLatinBytes != 0;
        offset += 4;
        continue;
      }
      int stop = offset + (Long.numberOfTrailingZeros(stopMask) >>> 4);
      boolean currentNonLatin =
          nonLatin || nonLatinBytes != 0 && hasNonLatinBeforeStop(word, stop - offset);
      char ch = charAtFast(stop);
      if (Character.isHighSurrogate(ch)) {
        currentNonLatin = true;
      }
      String value = readUtf16StringStop(localBytes, start, stop, currentNonLatin, ch);
      if (value != null) {
        return value;
      }
      nonLatin = currentNonLatin;
      offset = position;
    }
    position = offset;
    return readStringLoop(start, nonLatin);
  }

  private String readUtf16StringStop(
      byte[] localBytes, int start, int stop, boolean nonLatin, char ch) {
    if (ch == '"') {
      position = stop + 1;
      // Preserve Java compact-string invariants: a LATIN1-representable token must keep the JDK's
      // normal LATIN1 representation, otherwise equality/hash behavior can differ for map/set keys.
      if (nonLatin) {
        return newUtf16String(localBytes, start, stop);
      }
      return input.substring(start, stop);
    }
    if (ch == '\\') {
      position = stop + 1;
      return readEscapedStringTail(start, stop, nonLatin);
    }
    if (ch < 0x20) {
      position = stop;
      throw error("Control character in string");
    }
    if (Character.isHighSurrogate(ch)) {
      int lowOffset = stop + 1;
      if (lowOffset >= length || !Character.isLowSurrogate(charAtFast(lowOffset))) {
        position = stop;
        throw error("Unpaired high surrogate in string");
      }
      position = lowOffset + 1;
      return null;
    }
    position = stop;
    throw error("Unpaired low surrogate in string");
  }

  private static boolean hasNonLatinBeforeStop(long word, int chars) {
    long mask = chars == 4 ? -1L : (1L << (chars << 4)) - 1;
    return (word & mask & UTF16_NON_LATIN_BYTES) != 0;
  }

  private String newUtf16String(byte[] localBytes, int start, int stop) {
    byte[] valueBytes = new byte[(stop - start) << 1];
    System.arraycopy(localBytes, start << 1, valueBytes, 0, valueBytes.length);
    return StringSerializer.newUtf16StringZeroCopy(valueBytes);
  }

  private String readEscapedStringTail(int start, int stop, boolean nonLatin) {
    if (nonLatin) {
      int out = (stop - start) << 1;
      byte[] outBytes = stringDecodeBuffer;
      if (outBytes.length < out) {
        outBytes = growStringDecodeBuffer(outBytes, out);
      }
      copyPrefixUtf16(start, stop, outBytes);
      return readStringUtf16Tail(outBytes, out, '\\');
    }
    int out = stop - start;
    byte[] outBytes = stringDecodeBuffer;
    if (outBytes.length < out) {
      outBytes = growStringDecodeBuffer(outBytes, out);
    }
    copyPrefixLatin1(start, stop, outBytes);
    return readStringLatin1Tail(outBytes, out, '\\');
  }

  private void copyPrefixLatin1(int start, int stop, byte[] outBytes) {
    for (int i = start, out = 0; i < stop; i++) {
      outBytes[out++] = (byte) charAtFast(i);
    }
  }

  private void copyPrefixUtf16(int start, int stop, byte[] outBytes) {
    byte[] localBytes = bytes;
    int length = (stop - start) << 1;
    if (localBytes != null) {
      System.arraycopy(localBytes, start << 1, outBytes, 0, length);
      return;
    }
    for (int i = start, out = 0; i < stop; i++) {
      out = putUtf16Char(outBytes, out, charAtFast(i));
    }
  }

  private String readStringLatin1Tail(byte[] outBytes, int out, int ch) {
    while (true) {
      if (ch == '"') {
        return finishDecodedString(outBytes, out, false);
      }
      if (ch == '\\') {
        char escaped = readEscapedStringChar();
        if (Character.isHighSurrogate(escaped)) {
          char low = readLowSurrogateEscape();
          outBytes = widenStringDecodeBuffer(outBytes, out);
          out <<= 1;
          outBytes = ensureStringDecodeCapacity(outBytes, out + 4);
          out = putUtf16Char(outBytes, out, escaped);
          out = putUtf16Char(outBytes, out, low);
          return readStringUtf16Tail(outBytes, out, nextStringChar());
        }
        if (Character.isLowSurrogate(escaped)) {
          throw error("Unpaired low surrogate escape");
        }
        if (escaped <= 0xFF) {
          outBytes = ensureStringDecodeCapacity(outBytes, out + 1);
          outBytes[out++] = (byte) escaped;
        } else {
          outBytes = widenStringDecodeBuffer(outBytes, out);
          out <<= 1;
          outBytes = ensureStringDecodeCapacity(outBytes, out + 2);
          out = putUtf16Char(outBytes, out, escaped);
          return readStringUtf16Tail(outBytes, out, nextStringChar());
        }
      } else if (ch < 0x20) {
        throw error("Control character in string");
      } else if (Character.isHighSurrogate((char) ch)) {
        char low = readRawLowSurrogate();
        outBytes = widenStringDecodeBuffer(outBytes, out);
        out <<= 1;
        outBytes = ensureStringDecodeCapacity(outBytes, out + 4);
        out = putUtf16Char(outBytes, out, (char) ch);
        out = putUtf16Char(outBytes, out, low);
        return readStringUtf16Tail(outBytes, out, nextStringChar());
      } else if (Character.isLowSurrogate((char) ch)) {
        throw error("Unpaired low surrogate in string");
      } else if (ch <= 0xFF) {
        outBytes = ensureStringDecodeCapacity(outBytes, out + 1);
        outBytes[out++] = (byte) ch;
      } else {
        outBytes = widenStringDecodeBuffer(outBytes, out);
        out <<= 1;
        outBytes = ensureStringDecodeCapacity(outBytes, out + 2);
        out = putUtf16Char(outBytes, out, (char) ch);
        return readStringUtf16Tail(outBytes, out, nextStringChar());
      }
      ch = nextStringChar();
    }
  }

  private String readStringUtf16Tail(byte[] outBytes, int out, int ch) {
    while (true) {
      if (ch == '"') {
        return finishDecodedString(outBytes, out, true);
      }
      if (ch == '\\') {
        char escaped = readEscapedStringChar();
        if (Character.isHighSurrogate(escaped)) {
          char low = readLowSurrogateEscape();
          outBytes = ensureStringDecodeCapacity(outBytes, out + 4);
          out = putUtf16Char(outBytes, out, escaped);
          out = putUtf16Char(outBytes, out, low);
        } else if (Character.isLowSurrogate(escaped)) {
          throw error("Unpaired low surrogate escape");
        } else {
          outBytes = ensureStringDecodeCapacity(outBytes, out + 2);
          out = putUtf16Char(outBytes, out, escaped);
        }
      } else if (ch < 0x20) {
        throw error("Control character in string");
      } else if (Character.isHighSurrogate((char) ch)) {
        char low = readRawLowSurrogate();
        outBytes = ensureStringDecodeCapacity(outBytes, out + 4);
        out = putUtf16Char(outBytes, out, (char) ch);
        out = putUtf16Char(outBytes, out, low);
      } else if (Character.isLowSurrogate((char) ch)) {
        throw error("Unpaired low surrogate in string");
      } else {
        outBytes = ensureStringDecodeCapacity(outBytes, out + 2);
        out = putUtf16Char(outBytes, out, (char) ch);
      }
      ch = nextStringChar();
    }
  }

  private int nextStringChar() {
    if (position >= length) {
      throw error("Unterminated string");
    }
    return charAtFast(position++);
  }

  private char readRawLowSurrogate() {
    if (position >= length) {
      throw error("Unpaired high surrogate in string");
    }
    char low = charAtFast(position++);
    if (!Character.isLowSurrogate(low)) {
      throw error("Unpaired high surrogate in string");
    }
    return low;
  }

  private char readEscapedStringChar() {
    if (position >= length) {
      throw error("Unterminated escape");
    }
    char escaped = charAtFast(position++);
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
    if (position + 2 > length || charAtFast(position) != '\\' || charAtFast(position + 1) != 'u') {
      throw error("Unpaired high surrogate escape");
    }
    position += 2;
    char low = readUnicodeEscape();
    if (!Character.isLowSurrogate(low)) {
      throw error("Unpaired high surrogate escape");
    }
    return low;
  }

  private String finishDecodedString(byte[] outBytes, int length, boolean utf16) {
    // The decode buffer is reader-owned reusable storage; returned Strings must own exact bytes.
    byte[] result = new byte[length];
    System.arraycopy(outBytes, 0, result, 0, length);
    return utf16
        ? StringSerializer.newUtf16StringZeroCopy(result)
        : StringSerializer.newLatin1StringZeroCopy(result);
  }

  private byte[] ensureStringDecodeCapacity(byte[] outBytes, int capacity) {
    if (outBytes.length < capacity) {
      return growStringDecodeBuffer(outBytes, capacity);
    }
    return outBytes;
  }

  private byte[] growStringDecodeBuffer(byte[] outBytes, int capacity) {
    int newCapacity = Math.max(capacity, outBytes.length << 1);
    byte[] grown = Arrays.copyOf(outBytes, newCapacity);
    stringDecodeBuffer = grown;
    return grown;
  }

  private byte[] widenStringDecodeBuffer(byte[] outBytes, int length) {
    int utf16Length = length << 1;
    outBytes = ensureStringDecodeCapacity(outBytes, utf16Length);
    for (int i = length - 1, pos = utf16Length - 2; i >= 0; i--, pos -= 2) {
      putUtf16Char(outBytes, pos, (char) (outBytes[i] & 0xFF));
    }
    return outBytes;
  }

  private static int putUtf16Char(byte[] outBytes, int pos, char value) {
    if (LITTLE_ENDIAN) {
      outBytes[pos] = (byte) value;
      outBytes[pos + 1] = (byte) (value >>> 8);
    } else {
      outBytes[pos] = (byte) (value >>> 8);
      outBytes[pos + 1] = (byte) value;
    }
    return pos + 2;
  }

  private String readStringLoop(int start, boolean nonLatin) {
    while (position < length) {
      char ch = charAtFast(position++);
      if (ch == '"') {
        return input.substring(start, position - 1);
      } else if (ch == '\\') {
        return readEscapedStringTail(start, position - 1, nonLatin);
      } else if (ch < 0x20) {
        throw error("Control character in string");
      } else if (Character.isHighSurrogate(ch)) {
        if (position >= length || !Character.isLowSurrogate(charAtFast(position))) {
          throw error("Unpaired high surrogate in string");
        }
        position++;
        nonLatin = true;
      } else if (Character.isLowSurrogate(ch)) {
        throw error("Unpaired low surrogate in string");
      } else if (ch > 0xFF) {
        nonLatin = true;
      }
    }
    throw error("Unterminated string");
  }

  private LocalDate tryReadIsoLocalDateToken() {
    int offset = position;
    int inputLength = length;
    if (offset + 12 > inputLength || charAtFast(offset) != '"') {
      return null;
    }
    offset++;
    int dateStart = offset;
    if (charAtFast(dateStart + 4) != '-' || charAtFast(dateStart + 7) != '-') {
      return null;
    }
    int year = parse4(dateStart);
    int month = parse2(dateStart + 5);
    int day = parse2(dateStart + 8);
    int end = dateStart + 10;
    char ch = charAtFast(end);
    if (ch == '"') {
      position = end + 1;
      return LocalDate.of(year, month, day);
    }
    if (ch == 'T') {
      int stringEnd = tryScanSimpleStringTail(end + 1);
      if (stringEnd < 0) {
        return null;
      }
      position = stringEnd;
      return LocalDate.of(year, month, day);
    }
    return null;
  }

  private OffsetDateTime tryReadIsoOffsetDateTimeToken() {
    int offset = position;
    int inputLength = length;
    if (offset + 19 > inputLength || charAtFast(offset) != '"') {
      return null;
    }
    offset++;
    int start = offset;
    if (charAtFast(start + 4) != '-'
        || charAtFast(start + 7) != '-'
        || charAtFast(start + 10) != 'T'
        || charAtFast(start + 13) != ':') {
      return null;
    }
    int year = parse4(start);
    int month = parse2(start + 5);
    int day = parse2(start + 8);
    int hour = parse2(start + 11);
    int minute = parse2(start + 14);
    return tryReadIsoOffsetDateTimeTail(start + 16, inputLength, year, month, day, hour, minute);
  }

  private OffsetDateTime tryReadIsoOffsetDateTimeTail(
      int index, int inputLength, int year, int month, int day, int hour, int minute) {
    int second = 0;
    int nano = 0;
    if (index < inputLength && charAtFast(index) == ':') {
      second = parse2(index + 1);
      index += 3;
      if (index < inputLength && charAtFast(index) == '.') {
        int fractionStart = index + 1;
        int fractionEnd = fractionStart;
        while (fractionEnd < inputLength && isDigit(charAtFast(fractionEnd))) {
          fractionEnd++;
        }
        if (fractionEnd == fractionStart) {
          throw new IllegalArgumentException();
        }
        if (fractionEnd - fractionStart > 9) {
          throw error("OffsetDateTime fractional seconds exceed nanosecond precision");
        }
        nano = parseNano(fractionStart, fractionEnd);
        index = fractionEnd;
      }
    }
    if (index < inputLength && charAtFast(index) == 'Z') {
      if (index + 1 >= inputLength || charAtFast(index + 1) != '"') {
        return null;
      }
      position = index + 2;
      return OffsetDateTime.of(year, month, day, hour, minute, second, nano, ZoneOffset.UTC);
    }
    return tryReadIsoOffsetDateTimeOffsetTail(
        index, inputLength, year, month, day, hour, minute, second, nano);
  }

  private OffsetDateTime tryReadIsoOffsetDateTimeOffsetTail(
      int index,
      int inputLength,
      int year,
      int month,
      int day,
      int hour,
      int minute,
      int second,
      int nano) {
    long offsetAndEnd = tryParseOffsetAndEnd(index, inputLength);
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

  private int tryScanSimpleStringTail(int offset) {
    int inputLength = length;
    while (offset < inputLength) {
      char ch = charAtFast(offset++);
      if (ch == '"') {
        return offset;
      }
      if (ch == '\\' || ch < 0x20 || Character.isSurrogate(ch)) {
        return -1;
      }
    }
    throw error("Unterminated string");
  }

  private long tryParseOffsetAndEnd(int index, int inputLength) {
    if (index >= inputLength) {
      return Long.MIN_VALUE;
    }
    char offset = charAtFast(index);
    if (offset == 'Z') {
      if (index + 1 >= inputLength || charAtFast(index + 1) != '"') {
        return Long.MIN_VALUE;
      }
      return ((long) (index + 2)) & 0xFFFF_FFFFL;
    }
    if (offset != '+' && offset != '-') {
      return Long.MIN_VALUE;
    }
    if (index + 6 >= inputLength || charAtFast(index + 3) != ':') {
      return Long.MIN_VALUE;
    }
    int hour = parse2(index + 1);
    int minute = parse2(index + 4);
    int second = 0;
    int end = index + 6;
    if (charAtFast(end) == ':') {
      if (end + 3 >= inputLength) {
        throw new IllegalArgumentException();
      }
      second = parse2(end + 1);
      end += 3;
    }
    if (charAtFast(end) != '"') {
      return Long.MIN_VALUE;
    }
    int total = hour * 3600 + minute * 60 + second;
    if (offset == '-') {
      total = -total;
    }
    return ((long) total << 32) | ((long) (end + 1) & 0xFFFF_FFFFL);
  }

  private int parseNano(int start, int end) {
    int nano = 0;
    for (int i = start; i < end; i++) {
      nano = nano * 10 + charAtFast(i) - '0';
    }
    for (int i = end - start; i < 9; i++) {
      nano *= 10;
    }
    return nano;
  }

  private int parse4(int index) {
    return parse2(index) * 100 + parse2(index + 2);
  }

  private int parse2(int index) {
    int high = charAtFast(index) - '0';
    int low = charAtFast(index + 1) - '0';
    if (high < 0 || high > 9 || low < 0 || low > 9) {
      throw new IllegalArgumentException();
    }
    return high * 10 + low;
  }

  private static boolean isDigit(char ch) {
    return ch >= '0' && ch <= '9';
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
    if (mark < length) {
      int ch = asciiAtFast(mark);
      if (ch == '"') {
        return tryReadFieldNameColonAt(mark, expectedHash, expectedMask, expectedLength);
      }
      if (!isWhitespace(ch)) {
        return false;
      }
    }
    return tryReadFieldNameColon(expectedHash, expectedMask, expectedLength);
  }

  public boolean tryReadNextFieldNameUtf16(
      long expectedHash,
      long expectedMask,
      long firstWord,
      long firstMask,
      long secondWord,
      long secondMask,
      int expectedLength) {
    byte[] localBytes = bytes;
    if (localBytes == null) {
      return tryReadNextFieldNameColon(expectedHash, expectedMask, expectedLength);
    }
    int mark = position;
    if (mark < length) {
      if (utf16CharEquals(localBytes, mark << 1, '"')) {
        return tryReadUtf16FieldNameAt(
            localBytes,
            mark,
            expectedHash,
            expectedLength,
            firstWord,
            firstMask,
            secondWord,
            secondMask);
      }
      if (!isWhitespace(asciiAtFast(mark))) {
        return false;
      }
    }
    skipWhitespaceFast();
    return tryReadUtf16FieldNameAt(
        localBytes,
        mark,
        expectedHash,
        expectedLength,
        firstWord,
        firstMask,
        secondWord,
        secondMask);
  }

  public boolean tryReadNextFieldNameUtf16Token2(
      long firstWord, long firstMask, long secondWord, long secondMask, int tokenLength) {
    byte[] localBytes = bytes;
    int mark = position;
    int tokenEnd = mark + tokenLength;
    if (localBytes != null && tokenEnd <= length) {
      int byteOffset = mark << 1;
      if ((LittleEndian.getInt64(localBytes, byteOffset) & firstMask) == firstWord
          && (secondMask == 0
              || readUtf16TokenWord(localBytes, byteOffset + Long.BYTES, tokenLength - 4)
                  == secondWord)) {
        position = tokenEnd;
        return true;
      }
    }
    return false;
  }

  public boolean tryReadNextFieldNameUtf16Token3(
      long firstWord, long secondWord, long thirdWord, int tokenLength) {
    byte[] localBytes = bytes;
    int mark = position;
    int tokenEnd = mark + tokenLength;
    if (localBytes != null && tokenEnd <= length) {
      int byteOffset = mark << 1;
      if (LittleEndian.getInt64(localBytes, byteOffset) == firstWord
          && LittleEndian.getInt64(localBytes, byteOffset + Long.BYTES) == secondWord
          && readUtf16TokenWord(localBytes, byteOffset + (Long.BYTES << 1), tokenLength - 8)
              == thirdWord) {
        position = tokenEnd;
        return true;
      }
    }
    return false;
  }

  private static long readUtf16TokenWord(byte[] localBytes, int byteOffset, int chars) {
    if (chars == 4 || byteOffset + Long.BYTES <= localBytes.length) {
      return LittleEndian.getInt64(localBytes, byteOffset) & utf16WordMask(chars);
    }
    long word = 0;
    for (int i = 0; i < chars; i++) {
      int offset = byteOffset + (i << 1);
      long ch = (localBytes[offset] & 0xFFL) | ((localBytes[offset + 1] & 0xFFL) << 8);
      word |= ch << (i << 4);
    }
    return word;
  }

  private boolean tryReadFieldNameColonAt(
      int mark, long expectedHash, long expectedMask, int expectedLength) {
    byte[] localBytes = bytes;
    if (localBytes != null
        && expectedLength > 0
        && expectedLength <= Long.BYTES
        && isJsonPackedName(expectedHash, expectedMask)) {
      return tryReadUtf16FieldNameAt(
          localBytes,
          mark,
          expectedHash,
          expectedLength,
          packedUtf16Word(expectedHash),
          utf16WordMask(Math.min(expectedLength, 4)),
          expectedLength > 4 ? packedUtf16Word(expectedHash >>> 32) : 0,
          expectedLength > 4 ? utf16WordMask(expectedLength - 4) : 0);
    }
    return tryReadFieldNameColonLoop(mark, expectedHash, expectedLength);
  }

  private boolean tryReadFieldNameColonLoop(int mark, long expectedHash, int expectedLength) {
    int offset = position;
    int end = offset + expectedLength + 1;
    if (end < length && charAtFast(offset++) == '"') {
      long value = 0;
      for (int i = 0; i < expectedLength; i++) {
        char ch = charAtFast(offset++);
        if (ch == 0 || ch == '"' || ch == '\\' || ch < 0x20 || ch > 0xFF) {
          position = mark;
          return false;
        }
        value = JsonFieldNameHash.value(value, i, ch);
      }
      if (value == expectedHash && charAtFast(offset) == '"') {
        int colonOffset = offset + 1;
        if (colonOffset < length && charAtFast(colonOffset) == ':') {
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

  private boolean tryReadUtf16FieldNameAt(
      byte[] localBytes,
      int mark,
      long expectedHash,
      int expectedLength,
      long firstWord,
      long firstMask,
      long secondWord,
      long secondMask) {
    int offset = position;
    int quoteOffset = offset + expectedLength + 1;
    int nameOffset = (offset + 1) << 1;
    if ((nameOffset + Long.BYTES > localBytes.length)
        || (secondMask != 0 && nameOffset + (Long.BYTES << 1) > localBytes.length)) {
      return tryReadFieldNameColonLoop(mark, expectedHash, expectedLength);
    }
    if (quoteOffset < length
        && utf16CharEquals(localBytes, offset << 1, '"')
        && (LittleEndian.getInt64(localBytes, nameOffset) & firstMask) == firstWord
        && (secondMask == 0
            || (LittleEndian.getInt64(localBytes, nameOffset + Long.BYTES) & secondMask)
                == secondWord)
        && utf16CharEquals(localBytes, quoteOffset << 1, '"')) {
      int colonOffset = quoteOffset + 1;
      if (colonOffset < length && utf16CharEquals(localBytes, colonOffset << 1, ':')) {
        position = colonOffset + 1;
      } else {
        readFieldNameColon(colonOffset);
      }
      return true;
    }
    position = mark;
    return false;
  }

  private static boolean isJsonPackedName(long packed, long mask) {
    long value = (packed & mask) | (~mask & CONTROL_LIMIT_BYTES);
    long control = (value - CONTROL_LIMIT_BYTES) & ~value & BYTE_HIGH_BITS;
    return (control | byteMatchMask(value, QUOTE_BYTES) | byteMatchMask(value, BACKSLASH_BYTES))
        == 0;
  }

  private static long byteMatchMask(long word, long repeatedByte) {
    long match = word ^ repeatedByte;
    return (match - BYTE_ONES) & ~match & BYTE_HIGH_BITS;
  }

  private static long utf16StringStopMask(long word, long nonLatinBytes) {
    long syntaxStop =
        utf16CharMatchMask(word, UTF16_QUOTE_CHARS)
            | utf16CharMatchMask(word, UTF16_BACKSLASH_CHARS);
    long controlStop = (word - UTF16_CONTROL_LIMIT) & ~word & UTF16_HIGH_BITS;
    long stop = syntaxStop | controlStop;
    if (nonLatinBytes != 0) {
      stop |= utf16CharMatchMask(word & UTF16_SURROGATE_MASK, UTF16_SURROGATE_PREFIX);
    }
    return stop;
  }

  private static long utf16CharMatchMask(long word, long repeatedChar) {
    long match = word ^ repeatedChar;
    return (match - UTF16_ONES) & ~match & UTF16_HIGH_BITS;
  }

  private static long packedUtf16Word(long packed) {
    long word = spreadLatin1ToUtf16(packed & 0xFFFFFFFFL);
    return LITTLE_ENDIAN ? word : word << 8;
  }

  private static long packAsciiUtf16(long word) {
    return (word & 0xff)
        | ((word >>> 8) & 0xff00)
        | ((word >>> 16) & 0xff0000)
        | ((word >>> 24) & 0xff000000L);
  }

  private static long utf16WordMask(int length) {
    return length == 4 ? -1L : (1L << (length << 4)) - 1;
  }

  private static long spreadLatin1ToUtf16(long value) {
    value = (value | (value << 16)) & UTF16_PAIR_MASK;
    return (value | (value << 8)) & UTF16_BYTE_MASK;
  }

  private static boolean utf16CharEquals(byte[] localBytes, int byteOffset, int expected) {
    if (LITTLE_ENDIAN) {
      return (localBytes[byteOffset] & 0xFF) == expected && localBytes[byteOffset + 1] == 0;
    }
    return localBytes[byteOffset] == 0 && (localBytes[byteOffset + 1] & 0xFF) == expected;
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
    if (position < length && !isWhitespace(charAtFast(position))) {
      return readPackedStringHashToken();
    }
    return readPackedStringHash();
  }

  private long readPackedStringHashToken() {
    int mark = position;
    int inputLength = length;
    int offset = position;
    if (offset < inputLength && charAtFast(offset++) == '"') {
      long value = 0;
      int nameLength = 0;
      while (offset < inputLength) {
        char ch = charAtFast(offset++);
        if (ch == '"') {
          if (nameLength > 0) {
            position = offset;
            return value;
          }
          break;
        }
        if (ch == 0 || ch == '\\' || ch < 0x20 || ch > 0xFF || nameLength >= Long.BYTES) {
          break;
        }
        value = JsonFieldNameHash.value(value, nameLength++, ch);
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
    int inputLength = length;
    if (position >= inputLength || charAtFast(position++) != '"') {
      throw error("Expected string");
    }
    long hash = JsonFieldNameHash.MAGIC_HASH_CODE;
    long value = 0;
    int nameLength = 0;
    boolean latin1 = true;
    while (position < inputLength) {
      char ch = charAtFast(position++);
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
          if (position + 2 > inputLength
              || charAtFast(position) != '\\'
              || charAtFast(position + 1) != 'u') {
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
        if (position >= inputLength || !Character.isLowSurrogate(charAtFast(position))) {
          throw error("Unpaired high surrogate in string");
        }
        if (latin1) {
          hash = JsonFieldNameHash.hashPacked(value, nameLength);
          latin1 = false;
        }
        hash = JsonFieldNameHash.update(hash, ch);
        hash = JsonFieldNameHash.update(hash, charAtFast(position++));
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

  @Override
  protected String slice(int start, int end) {
    return input.substring(start, end);
  }

  private void skipWhitespaceFast() {
    int inputLength = length;
    while (position < inputLength) {
      int ch = asciiAtFast(position);
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

  private boolean startsWithAscii(String value) {
    int end = position + value.length();
    if (end > length) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (charAtFast(position + i) != value.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  private void rejectLeadingDigitFast() {
    if (position < length) {
      char ch = charAtFast(position);
      if (ch >= '0' && ch <= '9') {
        throw error("Leading zero in number");
      }
    }
  }

  private void rejectFractionOrExponentFast() {
    if (position < length) {
      char ch = charAtFast(position);
      if (ch == '.' || ch == 'e' || ch == 'E') {
        throw error("Expected integer");
      }
    }
  }

  private int readZeroIntName(int nameStart) {
    if (position >= length) {
      throw error("Unterminated string");
    }
    char ch = charAtFast(position);
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
    if (position >= length) {
      throw error("Unterminated string");
    }
    char ch = charAtFast(position);
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

  private char charAtFast(int index) {
    byte[] localBytes = bytes;
    return localBytes == null
        ? input.charAt(index)
        : StringSerializer.getBytesChar(localBytes, index << 1);
  }

  private int asciiAtFast(int index) {
    byte[] localBytes = bytes;
    if (localBytes == null) {
      return input.charAt(index);
    }
    // JSON structural tokens are ASCII; non-ASCII code units cannot match them or whitespace.
    int byteOffset = index << 1;
    if (LITTLE_ENDIAN) {
      return localBytes[byteOffset + 1] == 0 ? localBytes[byteOffset] & 0xff : 0x100;
    }
    return localBytes[byteOffset] == 0 ? localBytes[byteOffset + 1] & 0xff : 0x100;
  }
}
