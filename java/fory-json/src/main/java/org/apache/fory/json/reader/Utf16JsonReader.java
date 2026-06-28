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
import org.apache.fory.serializer.StringSerializer;

public final class Utf16JsonReader extends JsonReader {
  private String input;
  private byte[] bytes;
  private int length;

  public Utf16JsonReader() {
    input = "";
    bytes = null;
    length = 0;
  }

  public Utf16JsonReader(String input) {
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
        return this;
      }
    }
    bytes = null;
    length = input.length();
    position = 0;
    return this;
  }

  public void clear() {
    input = "";
    bytes = null;
    length = 0;
    position = 0;
  }

  public boolean consumeToken(char expected) {
    skipWhitespaceFast();
    if (position < length && charAtFast(position) == expected) {
      position++;
      return true;
    }
    return false;
  }

  public boolean consumeNextToken(char expected) {
    if (position < length && charAtFast(position) == expected) {
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
    if (position < length && charAtFast(position) == expected) {
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
      char ch = charAtFast(position);
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
      char ch = charAtFast(position);
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
      char ch = charAtFast(position);
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
      char ch = charAtFast(position);
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
    if (position < length) {
      char ch = charAtFast(position);
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
    if (position < length && !isWhitespace(charAtFast(position))) {
      return readBooleanToken();
    }
    return readBooleanValue();
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
    if (position < length && !isWhitespace(charAtFast(position))) {
      return readIntToken();
    }
    return readIntValue();
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
    if (position < length && !isWhitespace(charAtFast(position))) {
      return readLongToken();
    }
    return readLongValue();
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
  public String readNullableString() {
    skipWhitespaceFast();
    if (tryReadNullLiteral()) {
      return null;
    }
    return readStringToken();
  }

  public String readNextNullableString() {
    if (position < length) {
      char ch = charAtFast(position);
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

  private String readStringToken() {
    if (position >= length || charAtFast(position++) != '"') {
      throw error("Expected string");
    }
    int start = position;
    StringBuilder builder = null;
    while (position < length) {
      char ch = charAtFast(position++);
      if (ch == '"') {
        if (builder == null) {
          return input.substring(start, position - 1);
        }
        builder.append(input, start, position - 1);
        return builder.toString();
      } else if (ch == '\\') {
        if (builder == null) {
          builder = new StringBuilder();
        }
        builder.append(input, start, position - 1);
        appendEscape(builder);
        start = position;
      } else if (ch < 0x20) {
        throw error("Control character in string");
      } else if (Character.isHighSurrogate(ch)) {
        if (position >= length || !Character.isLowSurrogate(charAtFast(position))) {
          throw error("Unpaired high surrogate in string");
        }
        position++;
      } else if (Character.isLowSurrogate(ch)) {
        throw error("Unpaired low surrogate in string");
      }
    }
    throw error("Unterminated string");
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
      char ch = charAtFast(mark);
      if (ch == '"') {
        return tryReadFieldNameColonAt(mark, expectedHash, expectedMask, expectedLength);
      }
      if (!isWhitespace(ch)) {
        return false;
      }
    }
    return tryReadFieldNameColon(expectedHash, expectedMask, expectedLength);
  }

  private boolean tryReadFieldNameColonAt(
      int mark, long expectedHash, long expectedMask, int expectedLength) {
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
      char ch = charAtFast(position);
      if (isWhitespace(ch)) {
        position++;
      } else {
        return;
      }
    }
  }

  private static boolean isWhitespace(char ch) {
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
}
