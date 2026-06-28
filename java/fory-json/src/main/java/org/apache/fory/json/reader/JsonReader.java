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

import org.apache.fory.json.ForyJson;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldNameHash;
import org.apache.fory.json.meta.JsonFieldTable;

public abstract class JsonReader {
  protected int position;
  private int depth;
  private int maxDepth = ForyJson.DEFAULT_MAX_DEPTH;

  protected abstract int length();

  protected abstract char charAt(int index);

  public abstract String readString();

  public final void resetDepth(int maxDepth) {
    this.maxDepth = maxDepth;
    depth = 0;
  }

  public final void clearDepth() {
    depth = 0;
  }

  public final void enterDepth() {
    int nextDepth = depth + 1;
    if (nextDepth > maxDepth) {
      throw error("JSON max depth " + maxDepth + " exceeded");
    }
    depth = nextDepth;
  }

  public final void exitDepth() {
    depth--;
  }

  public String readNullableString() {
    return tryReadNull() ? null : readString();
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

  public final String readNumber() {
    skipWhitespace();
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
      readNumber();
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

  protected final void appendEscape(StringBuilder builder) {
    if (position >= length()) {
      throw error("Unterminated escape");
    }
    char escaped = charAt(position++);
    switch (escaped) {
      case '"':
      case '\\':
      case '/':
        builder.append(escaped);
        return;
      case 'b':
        builder.append('\b');
        return;
      case 'f':
        builder.append('\f');
        return;
      case 'n':
        builder.append('\n');
        return;
      case 'r':
        builder.append('\r');
        return;
      case 't':
        builder.append('\t');
        return;
      case 'u':
        appendUnicodeEscape(builder);
        return;
      default:
        throw error("Invalid escape");
    }
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

  private void appendUnicodeEscape(StringBuilder builder) {
    char ch = readUnicodeEscape();
    if (Character.isHighSurrogate(ch)) {
      if (position + 2 > length() || charAt(position) != '\\' || charAt(position + 1) != 'u') {
        throw error("Unpaired high surrogate escape");
      }
      position += 2;
      char low = readUnicodeEscape();
      if (!Character.isLowSurrogate(low)) {
        throw error("Unpaired high surrogate escape");
      }
      builder.append(ch);
      builder.append(low);
    } else if (Character.isLowSurrogate(ch)) {
      throw error("Unpaired low surrogate escape");
    } else {
      builder.append(ch);
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
}
