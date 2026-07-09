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

package org.apache.fory.json.writer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Arrays;
import java.util.UUID;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.JsonConfig;
import org.apache.fory.json.meta.JsonFieldInfo;

public abstract class JsonWriter {
  private static final BigInteger BIG_INTEGER_CHUNK_BASE = BigInteger.valueOf(1_000_000_000);
  private static final int MAX_RETAINED_BIG_NUMBER_CHUNKS = 1024;
  private static final int[] POWERS_OF_TEN = {
    1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000,
  };
  private final boolean writeNullFields;
  private final int maxDepth;
  private int[] bigNumberChunks;
  private int depth;

  JsonWriter(boolean writeNullFields) {
    this(writeNullFields, ForyJson.DEFAULT_MAX_DEPTH);
  }

  JsonWriter(JsonConfig config) {
    this(config.writeNullFields(), config.maxDepth());
  }

  JsonWriter(boolean writeNullFields, int maxDepth) {
    this.writeNullFields = writeNullFields;
    this.maxDepth = maxDepth;
  }

  public final boolean writeNullFields() {
    return writeNullFields;
  }

  public void reset() {
    depth = 0;
    if (bigNumberChunks != null && bigNumberChunks.length > MAX_RETAINED_BIG_NUMBER_CHUNKS) {
      bigNumberChunks = null;
    }
  }

  protected final void enterDepth() {
    int nextDepth = depth + 1;
    if (nextDepth > maxDepth) {
      throwMaxDepthExceeded();
    }
    depth = nextDepth;
  }

  protected final void exitDepth() {
    depth--;
  }

  private void throwMaxDepthExceeded() {
    throw new ForyJsonException("JSON max depth " + maxDepth + " exceeded");
  }

  public abstract void writeNull();

  public abstract void writeBoolean(boolean value);

  public abstract void writeInt(int value);

  public abstract void writeLong(long value);

  public abstract void writeFloat(float value);

  public abstract void writeDouble(double value);

  public abstract void writeNumber(String value);

  public abstract void writeChar(char value);

  public abstract void writeString(String value);

  public void writeString(CharSequence value) {
    writeString(value.toString());
  }

  // Keep arbitrary-precision formatting writer-owned; codecs may receive subclasses whose
  // toString() is not the JSON numeric format owner.
  public void writeBigInteger(BigInteger value) {
    try {
      writeLong(value.longValueExact());
    } catch (ArithmeticException e) {
      int signum = value.signum();
      if (signum < 0) {
        writeNumber("-");
        value = value.negate();
      }
      writeBigIntegerMagnitude(value);
    }
  }

  public void writeBigDecimal(BigDecimal value) {
    if (value.scale() == 0) {
      try {
        writeLong(value.longValueExact());
        return;
      } catch (ArithmeticException e) {
        // Fall through to chunked arbitrary-precision formatting.
      }
    }
    int signum = value.signum();
    BigInteger unscaled = value.unscaledValue();
    if (signum < 0) {
      writeNumber("-");
      unscaled = unscaled.negate();
    }
    int chunkCount = collectBigIntegerChunks(unscaled);
    int precision = digitCount(bigNumberChunks[chunkCount - 1]) + (chunkCount - 1) * 9;
    int scale = value.scale();
    long adjustedExponent = (long) precision - scale - 1L;
    if (scale >= 0 && adjustedExponent >= -6) {
      writePlainDecimal(chunkCount, precision, scale);
    } else {
      writeScientificDecimal(chunkCount, precision, adjustedExponent);
    }
  }

  private void writeBigIntegerMagnitude(BigInteger value) {
    int chunkCount = collectBigIntegerChunks(value);
    writeIntegerChunks(chunkCount);
  }

  private int collectBigIntegerChunks(BigInteger value) {
    if (value.signum() == 0) {
      int[] chunks = ensureBigNumberChunks(1);
      chunks[0] = 0;
      return 1;
    }
    long digitEstimate = (((long) value.bitLength() * 1233) >>> 12) + 1;
    int capacity = Math.max(1, (int) Math.min(Integer.MAX_VALUE, (digitEstimate + 8) / 9));
    int[] chunks = ensureBigNumberChunks(capacity);
    int count = 0;
    while (value.signum() != 0) {
      if (count == chunks.length) {
        chunks = Arrays.copyOf(chunks, count << 1);
        bigNumberChunks = chunks;
      }
      BigInteger[] divRem = value.divideAndRemainder(BIG_INTEGER_CHUNK_BASE);
      chunks[count++] = divRem[1].intValue();
      value = divRem[0];
    }
    return count;
  }

  private int[] ensureBigNumberChunks(int capacity) {
    int[] chunks = bigNumberChunks;
    if (chunks == null || chunks.length < capacity) {
      chunks = new int[capacity];
      bigNumberChunks = chunks;
    }
    return chunks;
  }

  private void writeIntegerChunks(int chunkCount) {
    int[] chunks = bigNumberChunks;
    writeInt(chunks[chunkCount - 1]);
    for (int i = chunkCount - 2; i >= 0; i--) {
      writePaddedChunk(chunks[i]);
    }
  }

  private void writePlainDecimal(int chunkCount, int precision, int scale) {
    long point = (long) precision - scale;
    if (point <= 0) {
      writeNumber("0.");
      writeZeroes(-point);
      writeDigits(chunkCount, -1);
      return;
    }
    writeDigits(chunkCount, point);
  }

  private void writeScientificDecimal(int chunkCount, int precision, long adjustedExponent) {
    writeDigits(chunkCount, precision == 1 ? -1 : 1);
    writeNumber("E");
    if (adjustedExponent >= 0) {
      writeNumber("+");
    }
    writeLong(adjustedExponent);
  }

  private void writeDigits(int chunkCount, long point) {
    int[] chunks = bigNumberChunks;
    long index = 0;
    for (int i = chunkCount - 1; i >= 0; i--) {
      int chunk = chunks[i];
      int digits = i == chunkCount - 1 ? digitCount(chunk) : 9;
      int divisor = POWERS_OF_TEN[digits - 1];
      for (int j = 0; j < digits; j++) {
        if (index == point) {
          writeNumber(".");
        }
        int digit = chunk / divisor;
        chunk -= digit * divisor;
        divisor /= 10;
        writeInt(digit);
        index++;
      }
    }
  }

  private void writePaddedChunk(int chunk) {
    writeZeroes(9 - digitCount(chunk));
    writeInt(chunk);
  }

  private void writeZeroes(long count) {
    while (count >= 9) {
      writeNumber("000000000");
      count -= 9;
    }
    for (long i = 0; i < count; i++) {
      writeNumber("0");
    }
  }

  private static int digitCount(int value) {
    if (value >= 100_000_000) {
      return 9;
    } else if (value >= 10_000_000) {
      return 8;
    } else if (value >= 1_000_000) {
      return 7;
    } else if (value >= 100_000) {
      return 6;
    } else if (value >= 10_000) {
      return 5;
    } else if (value >= 1_000) {
      return 4;
    } else if (value >= 100) {
      return 3;
    } else if (value >= 10) {
      return 2;
    }
    return 1;
  }

  public void writeUuid(UUID value) {
    writeString(value.toString());
  }

  public void writeLocalDate(LocalDate value) {
    writeString(value.toString());
  }

  public void writeOffsetDateTime(OffsetDateTime value) {
    writeString(value.toString());
  }

  public void writeTemporal(TemporalAccessor value, DateTimeFormatter formatter) {
    writeString(formatter.format(value));
  }

  public void writeDuration(Duration value) {
    writeString(value.toString());
  }

  public void writePeriod(Period value) {
    writeString(value.toString());
  }

  public void writeYear(Year value) {
    writeString(value.toString());
  }

  public abstract void writeFieldName(String name);

  public abstract void writeFieldName(JsonFieldInfo field);

  public abstract void writeIntFieldName(int value);

  public abstract void writeLongFieldName(long value);

  public abstract void writeObjectStart();

  public abstract void writeObjectEnd();

  public abstract void writeArrayStart();

  public abstract void writeArrayEnd();

  public abstract void writeComma(int index);
}
