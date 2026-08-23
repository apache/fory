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

package org.apache.fory.json;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import javax.annotation.concurrent.NotThreadSafe;
import org.apache.fory.reflect.TypeRef;

/**
 * Incrementally decodes values from one UTF-8 JSON array or newline-delimited JSON stream.
 *
 * <p>One instance owns exactly one stream and is not thread-safe. Calls may move between threads
 * only when the caller serializes them with a happens-before edge. The decoder advances each
 * supplied {@link ByteBuffer} position but never retains the buffer or changes its limit or byte
 * order.
 *
 * <p>Each returned value is parsed by an independent {@link ForyJson} root operation. The decoder
 * retains one bounded staging array whose capacity grows with committed value bytes and is reused
 * until successful {@link #finish()} or any {@link #decodeNext(ByteBuffer) decodeNext} or {@code
 * finish} failure permanently terminates the decoder.
 *
 * <p>For array streams, {@code maxValueBytes} excludes outer punctuation and whitespace skipped
 * before an element, but includes whitespace after the element and before its delimiter. For
 * NDJSON, it counts every record byte except LF or the CRLF line ending. Whitespace-only NDJSON
 * lines are skipped as records but remain subject to the limit.
 */
@NotThreadSafe
public final class JsonStreamDecoder<T> {
  private static final byte ARRAY = 0;
  private static final byte NDJSON = 1;

  private static final byte ACTIVE = 0;
  private static final byte FINISHED = 1;
  private static final byte FAILED = 2;

  private static final byte BEFORE_ARRAY = 0;
  private static final byte FIRST_VALUE = 1;
  private static final byte NEXT_VALUE = 2;
  private static final byte IN_VALUE = 3;
  private static final byte AFTER_ARRAY = 4;

  private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;
  private static final byte[] EMPTY_BYTES = new byte[0];

  private static final long BYTE_ONES = 0x0101010101010101L;
  private static final long BYTE_HIGH_BITS = 0x8080808080808080L;
  private static final long QUOTES = 0x2222222222222222L;
  private static final long BACKSLASHES = 0x5c5c5c5c5c5c5c5cL;
  private static final long OPEN_BRACES = 0x7b7b7b7b7b7b7b7bL;
  private static final long CLOSE_BRACES = 0x7d7d7d7d7d7d7d7dL;
  private static final long OPEN_BRACKETS = 0x5b5b5b5b5b5b5b5bL;
  private static final long CLOSE_BRACKETS = 0x5d5d5d5d5d5d5d5dL;
  private static final long COMMAS = 0x2c2c2c2c2c2c2c2cL;
  private static final long CARRIAGE_RETURNS = 0x0d0d0d0d0d0d0d0dL;
  private static final long LINE_FEEDS = 0x0a0a0a0a0a0a0a0aL;

  private final ForyJson foryJson;
  private final Class<T> elementClass;
  private final TypeRef<T> elementType;
  private final int maxValueBytes;
  private final byte format;

  private byte[] valueBytes = EMPTY_BYTES;
  private int valueLength;
  private int objectDepth;
  private int arrayDepth;
  private byte lifecycle = ACTIVE;
  private byte arrayState = BEFORE_ARRAY;
  private boolean inString;
  private boolean escaped;
  private boolean recordStarted;
  private boolean pendingCarriageReturn;
  private boolean valueAvailable;
  private T currentValue;

  static <T> JsonStreamDecoder<T> forArray(
      ForyJson foryJson, Class<T> elementType, int maxValueBytes) {
    return new JsonStreamDecoder<>(foryJson, elementType, null, maxValueBytes, ARRAY);
  }

  static <T> JsonStreamDecoder<T> forArray(
      ForyJson foryJson, TypeRef<T> elementType, int maxValueBytes) {
    return new JsonStreamDecoder<>(foryJson, null, elementType, maxValueBytes, ARRAY);
  }

  static <T> JsonStreamDecoder<T> forNdjson(
      ForyJson foryJson, Class<T> elementType, int maxValueBytes) {
    return new JsonStreamDecoder<>(foryJson, elementType, null, maxValueBytes, NDJSON);
  }

  static <T> JsonStreamDecoder<T> forNdjson(
      ForyJson foryJson, TypeRef<T> elementType, int maxValueBytes) {
    return new JsonStreamDecoder<>(foryJson, null, elementType, maxValueBytes, NDJSON);
  }

  private JsonStreamDecoder(
      ForyJson foryJson,
      Class<T> elementClass,
      TypeRef<T> elementType,
      int maxValueBytes,
      byte format) {
    this.foryJson = Objects.requireNonNull(foryJson, "foryJson");
    this.elementClass = elementClass;
    this.elementType = elementType;
    if (elementClass == null && elementType == null) {
      throw new NullPointerException("elementType");
    }
    if (maxValueBytes <= 0 || maxValueBytes > MAX_ARRAY_SIZE) {
      throw new IllegalArgumentException(
          "maxValueBytes must be between 1 and " + MAX_ARRAY_SIZE + ": " + maxValueBytes);
    }
    this.maxValueBytes = maxValueBytes;
    this.format = format;
  }

  /**
   * Advances {@code input} until one value is decoded or the buffer is exhausted.
   *
   * <p>A {@code true} result makes {@link #value()} available, including when the decoded JSON
   * value is {@code null}. When this method returns {@code true} with input remaining, call it
   * again with that same buffer before supplying a later chunk. A {@code false} result means the
   * buffer was consumed to its limit.
   */
  public boolean decodeNext(ByteBuffer input) {
    requireActive();
    clearCurrentValue();
    try {
      Objects.requireNonNull(input, "input");
      return format == ARRAY ? decodeArrayNext(input) : decodeNdjsonNext(input);
    } catch (RuntimeException e) {
      markFailed();
      throw e;
    } catch (Error e) {
      markFailed();
      throw e;
    }
  }

  /**
   * Returns the value produced when the immediately preceding decode or finish call returned true.
   */
  public T value() {
    if (!valueAvailable) {
      throw noCurrentValue();
    }
    return currentValue;
  }

  /**
   * Signals end of input and validates the complete stream.
   *
   * @return {@code true} only when an unterminated final NDJSON record produced one last value
   */
  public boolean finish() {
    requireActive();
    clearCurrentValue();
    try {
      boolean produced = format == ARRAY ? finishArray() : finishNdjson();
      lifecycle = FINISHED;
      valueBytes = EMPTY_BYTES;
      return produced;
    } catch (RuntimeException e) {
      markFailed();
      throw e;
    } catch (Error e) {
      markFailed();
      throw e;
    }
  }

  private boolean decodeArrayNext(ByteBuffer input) {
    while (true) {
      if (arrayState == IN_VALUE) {
        return scanArrayValue(input);
      }
      if (arrayState == AFTER_ARRAY) {
        consumeArraySuffix(input);
        return false;
      }
      int token = nextNonWhitespace(input);
      if (token < 0) {
        return false;
      }
      if (arrayState == BEFORE_ARRAY) {
        if (token != '[') {
          throw framingError("Expected top-level JSON array");
        }
        input.get();
        arrayState = FIRST_VALUE;
      } else if (arrayState == FIRST_VALUE && token == ']') {
        input.get();
        arrayState = AFTER_ARRAY;
      } else {
        if (token == ',' || token == ']') {
          throw framingError("Expected JSON array element");
        }
        arrayState = IN_VALUE;
      }
    }
  }

  private boolean scanArrayValue(ByteBuffer input) {
    int segmentStart = input.position();
    int scanCursor = segmentStart;
    int inputLimit = input.limit();
    int remaining = maxValueBytes - valueLength;
    int scanLimit = inputLimit;
    if (scanLimit - segmentStart > remaining) {
      scanLimit = segmentStart + remaining + 1;
    }
    // Keep the caller-visible position at the commit cursor while absolute scans update framing.
    // This lets the complete current-buffer value segment enter staging through one bulk copy.
    while (scanCursor < scanLimit) {
      if (escaped) {
        scanCursor++;
        escaped = false;
        continue;
      }
      int special =
          inString
              ? findStringSpecial(input, scanCursor, scanLimit)
              : findArraySpecial(input, scanCursor, scanLimit);
      scanCursor = special;
      if (scanCursor == scanLimit) {
        break;
      }
      byte ch = input.get(scanCursor);
      if (!inString && objectDepth == 0 && arrayDepth == 0 && (ch == ',' || ch == ']')) {
        appendSegment(input, scanCursor);
        input.position(scanCursor + 1);
        return completeArrayValue(ch);
      }
      updateArrayState(ch);
      scanCursor++;
    }
    appendSegment(input, scanCursor);
    return false;
  }

  private boolean completeArrayValue(byte delimiter) {
    T value = readFrame(valueLength);
    clearFrame();
    clearArrayValueState();
    arrayState = delimiter == ',' ? NEXT_VALUE : AFTER_ARRAY;
    publish(value);
    return true;
  }

  private void updateArrayState(byte ch) {
    if (inString) {
      if (ch == '\\') {
        escaped = true;
      } else if (ch == '"') {
        inString = false;
      }
      return;
    }
    switch (ch) {
      case '"':
        inString = true;
        return;
      case '{':
        objectDepth++;
        return;
      case '}':
        if (objectDepth != 0) {
          objectDepth--;
        }
        return;
      case '[':
        arrayDepth++;
        return;
      case ']':
        if (arrayDepth != 0) {
          arrayDepth--;
        }
        return;
      default:
    }
  }

  private void consumeArraySuffix(ByteBuffer input) {
    while (input.hasRemaining()) {
      int ch = input.get() & 0xff;
      if (!isWhitespace(ch)) {
        throw framingError("Trailing content after top-level JSON array");
      }
    }
  }

  private boolean finishArray() {
    if (arrayState != AFTER_ARRAY) {
      throw framingError("Incomplete top-level JSON array");
    }
    return false;
  }

  private boolean decodeNdjsonNext(ByteBuffer input) {
    while (true) {
      if (pendingCarriageReturn) {
        if (!input.hasRemaining()) {
          return false;
        }
        if (input.get(input.position()) == '\n') {
          input.get();
          pendingCarriageReturn = false;
          if (completeNdjsonLine()) {
            return true;
          }
          continue;
        }
        appendByte((byte) '\r');
        pendingCarriageReturn = false;
      }
      int start = input.position();
      int limit = input.limit();
      int remaining = maxValueBytes - valueLength;
      if (limit - start > remaining) {
        limit = start + remaining + 1;
      }
      int special = findLineSpecial(input, start, limit);
      markRecordStart(input, start, special);
      appendSegment(input, special);
      if (special == limit) {
        return false;
      }
      byte ch = input.get();
      if (ch == '\r') {
        pendingCarriageReturn = true;
      } else if (completeNdjsonLine()) {
        return true;
      }
    }
  }

  private boolean completeNdjsonLine() {
    if (!recordStarted) {
      clearFrame();
      return false;
    }
    T value = readFrame(valueLength);
    clearFrame();
    recordStarted = false;
    publish(value);
    return true;
  }

  private boolean finishNdjson() {
    if (pendingCarriageReturn) {
      appendByte((byte) '\r');
      pendingCarriageReturn = false;
    }
    if (!recordStarted) {
      clearFrame();
      return false;
    }
    T value = readFrame(valueLength);
    clearFrame();
    recordStarted = false;
    publish(value);
    return true;
  }

  private void markRecordStart(ByteBuffer input, int start, int end) {
    if (recordStarted) {
      return;
    }
    while (start < end) {
      int ch = input.get(start++) & 0xff;
      if (ch != ' ' && ch != '\t') {
        recordStarted = true;
        return;
      }
    }
  }

  private int nextNonWhitespace(ByteBuffer input) {
    int start = input.position();
    int cursor = start;
    int limit = input.limit();
    while (cursor < limit) {
      int ch = input.get(cursor) & 0xff;
      if (!isWhitespace(ch)) {
        if (cursor != start) {
          input.position(cursor);
        }
        return ch;
      }
      cursor++;
    }
    input.position(limit);
    return -1;
  }

  private void appendSegment(ByteBuffer input, int end) {
    int length = end - input.position();
    if (length == 0) {
      return;
    }
    ensureAppend(length);
    input.get(valueBytes, valueLength, length);
    valueLength += length;
  }

  private void appendByte(byte value) {
    ensureAppend(1);
    valueBytes[valueLength++] = value;
  }

  private void ensureAppend(int length) {
    if (length > maxValueBytes - valueLength) {
      throw valueTooLarge();
    }
    int required = valueLength + length;
    if (required > valueBytes.length) {
      growValueBytes(required);
    }
  }

  private void growValueBytes(int required) {
    int current = valueBytes.length;
    long grown = current == 0 ? required : (long) current + (current >> 1);
    if (grown < required) {
      grown = (long) required + (required >> 1);
    }
    int capacity = (int) Math.min((long) maxValueBytes, grown);
    valueBytes = Arrays.copyOf(valueBytes, capacity);
  }

  private T readFrame(int length) {
    return elementClass != null
        ? foryJson.fromJson(valueBytes, 0, length, elementClass)
        : foryJson.fromJson(valueBytes, 0, length, elementType);
  }

  private void publish(T value) {
    currentValue = value;
    valueAvailable = true;
  }

  private void clearCurrentValue() {
    currentValue = null;
    valueAvailable = false;
  }

  private void clearFrame() {
    valueLength = 0;
  }

  private void clearArrayValueState() {
    objectDepth = 0;
    arrayDepth = 0;
    inString = false;
    escaped = false;
  }

  private void requireActive() {
    if (lifecycle != ACTIVE) {
      throw terminatedDecoder();
    }
  }

  private void markFailed() {
    lifecycle = FAILED;
    clearCurrentValue();
    valueLength = 0;
    valueBytes = EMPTY_BYTES;
  }

  private JsonStreamValueLimitException valueTooLarge() {
    return new JsonStreamValueLimitException(maxValueBytes);
  }

  private static ForyJsonException framingError(String message) {
    return new ForyJsonException(message);
  }

  private static IllegalStateException noCurrentValue() {
    return new IllegalStateException("No decoded JSON stream value is available");
  }

  private static IllegalStateException terminatedDecoder() {
    return new IllegalStateException("JSON stream decoder is already terminated");
  }

  private static int findStringSpecial(ByteBuffer input, int cursor, int limit) {
    while (limit - cursor >= 16) {
      if (stringSpecialMask(input.getLong(cursor)) != 0) {
        return findStringSpecialScalar(input, cursor, cursor + 8);
      }
      if (stringSpecialMask(input.getLong(cursor + 8)) != 0) {
        return findStringSpecialScalar(input, cursor + 8, cursor + 16);
      }
      cursor += 16;
    }
    if (limit - cursor >= 8) {
      if (stringSpecialMask(input.getLong(cursor)) != 0) {
        return findStringSpecialScalar(input, cursor, cursor + 8);
      }
      cursor += 8;
    }
    return findStringSpecialScalar(input, cursor, limit);
  }

  private static int findArraySpecial(ByteBuffer input, int cursor, int limit) {
    while (limit - cursor >= 16) {
      if (arraySpecialMask(input.getLong(cursor)) != 0) {
        return findArraySpecialScalar(input, cursor, cursor + 8);
      }
      if (arraySpecialMask(input.getLong(cursor + 8)) != 0) {
        return findArraySpecialScalar(input, cursor + 8, cursor + 16);
      }
      cursor += 16;
    }
    if (limit - cursor >= 8) {
      if (arraySpecialMask(input.getLong(cursor)) != 0) {
        return findArraySpecialScalar(input, cursor, cursor + 8);
      }
      cursor += 8;
    }
    return findArraySpecialScalar(input, cursor, limit);
  }

  private static int findLineSpecial(ByteBuffer input, int cursor, int limit) {
    while (limit - cursor >= 16) {
      if (lineSpecialMask(input.getLong(cursor)) != 0) {
        return findLineSpecialScalar(input, cursor, cursor + 8);
      }
      if (lineSpecialMask(input.getLong(cursor + 8)) != 0) {
        return findLineSpecialScalar(input, cursor + 8, cursor + 16);
      }
      cursor += 16;
    }
    if (limit - cursor >= 8) {
      if (lineSpecialMask(input.getLong(cursor)) != 0) {
        return findLineSpecialScalar(input, cursor, cursor + 8);
      }
      cursor += 8;
    }
    return findLineSpecialScalar(input, cursor, limit);
  }

  private static long stringSpecialMask(long word) {
    return zeroByteMask(word ^ QUOTES) | zeroByteMask(word ^ BACKSLASHES);
  }

  private static long arraySpecialMask(long word) {
    return zeroByteMask(word ^ QUOTES)
        | zeroByteMask(word ^ OPEN_BRACES)
        | zeroByteMask(word ^ CLOSE_BRACES)
        | zeroByteMask(word ^ OPEN_BRACKETS)
        | zeroByteMask(word ^ CLOSE_BRACKETS)
        | zeroByteMask(word ^ COMMAS);
  }

  private static long lineSpecialMask(long word) {
    return zeroByteMask(word ^ CARRIAGE_RETURNS) | zeroByteMask(word ^ LINE_FEEDS);
  }

  private static long zeroByteMask(long value) {
    return (value - BYTE_ONES) & ~value & BYTE_HIGH_BITS;
  }

  private static int findStringSpecialScalar(ByteBuffer input, int cursor, int limit) {
    while (cursor < limit) {
      byte ch = input.get(cursor);
      if (ch == '"' || ch == '\\') {
        break;
      }
      cursor++;
    }
    return cursor;
  }

  private static int findArraySpecialScalar(ByteBuffer input, int cursor, int limit) {
    while (cursor < limit) {
      byte ch = input.get(cursor);
      if (ch == '"' || ch == '{' || ch == '}' || ch == '[' || ch == ']' || ch == ',') {
        break;
      }
      cursor++;
    }
    return cursor;
  }

  private static int findLineSpecialScalar(ByteBuffer input, int cursor, int limit) {
    while (cursor < limit) {
      byte ch = input.get(cursor);
      if (ch == '\r' || ch == '\n') {
        break;
      }
      cursor++;
    }
    return cursor;
  }

  private static boolean isWhitespace(int ch) {
    return ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t';
  }
}
