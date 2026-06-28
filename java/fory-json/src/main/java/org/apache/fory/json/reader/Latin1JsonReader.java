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

import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldNameHash;
import org.apache.fory.json.meta.JsonFieldTable;
import org.apache.fory.memory.LittleEndian;
import org.apache.fory.serializer.StringSerializer;

public final class Latin1JsonReader extends JsonReader {
  private static final byte[] EMPTY_BYTES = new byte[0];
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

  // JSON syntax bytes are ASCII, so hot token checks can compare signed bytes directly.
  // Latin1 string content and field-name hashing must keep unsigned byte conversion.
  private byte[] input;

  public Latin1JsonReader() {
    input = EMPTY_BYTES;
  }

  public Latin1JsonReader(String input) {
    reset(input);
  }

  public Latin1JsonReader reset(String input) {
    if (!StringSerializer.isBytesBackedString()) {
      throw new IllegalStateException("Latin1JsonReader requires byte-backed strings");
    }
    byte coder = StringSerializer.getStringCoder(input);
    if (!StringSerializer.isLatin1Coder(coder)) {
      throw new IllegalArgumentException("Latin1JsonReader requires a Latin1 string");
    }
    this.input = StringSerializer.getStringBytes(input);
    position = 0;
    return this;
  }

  public void clear() {
    input = EMPTY_BYTES;
    position = 0;
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
      if (ch == '}') {
        position++;
        return false;
      }
      if (!isWhitespace(ch)) {
        return consumeNextCommaOrEndObjectSlow();
      }
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
      if (ch == ']') {
        position++;
        return false;
      }
      if (!isWhitespace(ch)) {
        return consumeNextCommaOrEndArraySlow();
      }
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
    if (position < input.length && !isWhitespace(input[position])) {
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
    if (position < input.length && !isWhitespace(input[position])) {
      return readIntToken();
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
    if (position < input.length && !isWhitespace(input[position])) {
      return readLongToken();
    }
    return readLongValue();
  }

  public long readLongTokenValue() {
    return readLongToken();
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
      if (result > (Long.MAX_VALUE - digit) / 10) {
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
    long multmin = limit / 10;
    while (position < input.length) {
      ch = input[position];
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
    return (char) (input[index] & 0xFF);
  }

  @Override
  public String readString() {
    skipWhitespaceFast();
    return readStringToken();
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
      if (!isWhitespace(ch)) {
        return readStringToken();
      }
    }
    return readNullableString();
  }

  public String readNullableStringToken() {
    if (position < input.length) {
      int ch = input[position];
      if (ch == '"') {
        return readStringToken();
      }
      if (ch == 'n' && tryReadNullLiteral()) {
        return null;
      }
    }
    return readStringToken();
  }

  private String readStringToken() {
    byte[] bytes = input;
    int inputLength = bytes.length;
    if (position >= inputLength || bytes[position++] != '"') {
      throw error("Expected string");
    }
    int start = position;
    int offset = start;
    int wordEnd = inputLength - Long.BYTES;
    while (offset <= wordEnd) {
      long stopMask = stringStopMask(LittleEndian.getInt64(bytes, offset));
      if (stopMask == 0) {
        offset += Long.BYTES;
        continue;
      }
      int stop = offset + (Long.numberOfTrailingZeros(stopMask) >>> 3);
      int ch = bytes[stop] & 0xFF;
      if (ch == '"') {
        position = stop + 1;
        return newLatin1String(start, stop);
      }
      return readStringStop(start, stop, ch);
    }
    if (offset + Integer.BYTES <= inputLength) {
      int stopMask = stringStopMask(LittleEndian.getInt32(bytes, offset));
      if (stopMask == 0) {
        offset += Integer.BYTES;
      } else {
        int stop = offset + (Integer.numberOfTrailingZeros(stopMask) >>> 3);
        int ch = bytes[stop] & 0xFF;
        if (ch == '"') {
          position = stop + 1;
          return newLatin1String(start, stop);
        }
        return readStringStop(start, stop, ch);
      }
    }
    while (offset < inputLength) {
      int ch = bytes[offset++] & 0xFF;
      if (ch == '"') {
        position = offset;
        return newLatin1String(start, offset - 1);
      }
      if (ch == '\\') {
        return readStringStop(start, offset - 1, ch);
      }
      if (ch < 0x20) {
        position = offset;
        throw error("Control character in string");
      }
    }
    throw error("Unterminated string");
  }

  private String readStringStop(int start, int stop, int ch) {
    position = stop + 1;
    if (ch == '\\') {
      StringBuilder builder = newStringBuilder(start, stop);
      appendLatin1(builder, start, stop);
      appendEscape(builder);
      return readStringTail(builder);
    }
    throw error("Control character in string");
  }

  private StringBuilder newStringBuilder(int start, int stop) {
    return new StringBuilder(Math.max(16, stop - start + 16));
  }

  private static long stringStopMask(long word) {
    return byteMatchMask(word, QUOTE_BYTES)
        | byteMatchMask(word, BACKSLASH_BYTES)
        | ((word - CONTROL_LIMIT_BYTES) & ~word & BYTE_HIGH_BITS);
  }

  private static int stringStopMask(int word) {
    return byteMatchMask(word, INT_QUOTE_BYTES)
        | byteMatchMask(word, INT_BACKSLASH_BYTES)
        | ((word - INT_CONTROL_LIMIT_BYTES) & ~word & INT_BYTE_HIGH_BITS);
  }

  private static long byteMatchMask(long word, long repeatedByte) {
    long match = word ^ repeatedByte;
    return (match - BYTE_ONES) & ~match & BYTE_HIGH_BITS;
  }

  private static int byteMatchMask(int word, int repeatedByte) {
    int match = word ^ repeatedByte;
    return (match - INT_BYTE_ONES) & ~match & INT_BYTE_HIGH_BITS;
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
      if (!isWhitespace(ch)) {
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
        && (bytes[suffixOffset] & 0xFF) == suffix) {
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
        // Full raw-word misses cannot match this generated packed-name probe. Escaped field names
        // are handled by the hash fallback after this direct probe fails.
        position = mark;
        return false;
      }
      offset = nameOffset;
      long value = 0;
      for (int i = 0; i < expectedLength; i++) {
        int ch = bytes[offset++] & 0xFF;
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
    if (position < input.length && !isWhitespace(input[position])) {
      return readPackedStringHashToken();
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
        int ch = bytes[offset++] & 0xFF;
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
      int ch = bytes[position++] & 0xFF;
      if (ch == '"') {
        return JsonFieldNameHash.finish(hash, value, nameLength, latin1);
      }
      if (ch == '\\') {
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
      if (ch < 0x20) {
        throw error("Control character in string");
      }
      if (latin1) {
        if (ch != 0 && nameLength < Long.BYTES) {
          value = JsonFieldNameHash.value(value, nameLength, (char) ch);
          nameLength++;
          continue;
        }
        hash = JsonFieldNameHash.hashPacked(value, nameLength);
        latin1 = false;
      }
      hash = JsonFieldNameHash.update(hash, (char) ch);
      nameLength++;
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

  private String readStringTail(StringBuilder builder) {
    while (position < input.length) {
      int ch = input[position++] & 0xFF;
      if (ch == '"') {
        return builder.toString();
      } else if (ch == '\\') {
        appendEscape(builder);
      } else if (ch < 0x20) {
        throw error("Control character in string");
      } else {
        builder.append((char) ch);
      }
    }
    throw error("Unterminated string");
  }

  private void appendLatin1(StringBuilder builder, int start, int end) {
    for (int i = start; i < end; i++) {
      builder.append((char) (input[i] & 0xFF));
    }
  }

  private void skipWhitespaceFast() {
    while (position < input.length) {
      int ch = input[position];
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
    if (end > input.length) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (input[position + i] != value.charAt(i)) {
        return false;
      }
    }
    return true;
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
