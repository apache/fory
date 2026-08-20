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

package org.apache.fory.serializer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import org.apache.fory.config.Config;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.memory.MemoryBuffer;

/** Serializer for {@link BigDecimal} in native and xlang modes. */
public final class DecimalSerializer extends ImmutableSerializer<BigDecimal> implements Shareable {
  static final int MAX_MAGNITUDE_BYTES = 10_000;
  static final int MAX_MAGNITUDE_BITS = MAX_MAGNITUDE_BYTES * Byte.SIZE;
  // Compare scale bounds directly because Math.abs(Integer.MIN_VALUE) overflows.
  private static final int MAX_SCALE = 10_000;
  private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
  private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
  private final boolean xlang;

  public DecimalSerializer(Config config) {
    super(config, BigDecimal.class);
    xlang = config.isXlang();
  }

  @Override
  public void write(WriteContext writeContext, BigDecimal value) {
    if (xlang) {
      writeXlang(writeContext, value);
    } else {
      writeNative(writeContext, value);
    }
  }

  @Override
  public BigDecimal read(ReadContext readContext) {
    if (xlang) {
      return readXlang(readContext);
    }
    return readNative(readContext);
  }

  private void writeNative(WriteContext writeContext, BigDecimal value) {
    MemoryBuffer buffer = writeContext.getBuffer();
    int scale = value.scale();
    if (scale < -MAX_SCALE || scale > MAX_SCALE) {
      throw new IllegalArgumentException("Decimal scale out of range: " + scale);
    }
    BigInteger unscaled = value.unscaledValue();
    if (magnitudeExceedsLimit(unscaled)) {
      throw new IllegalArgumentException(
          "Decimal magnitude exceeds " + MAX_MAGNITUDE_BYTES + " bytes");
    }
    byte[] bytes = unscaled.toByteArray();
    buffer.writeVarUInt32Small7(scale);
    buffer.writeVarUInt32Small7(value.precision());
    buffer.writeVarUInt32Small7(bytes.length);
    buffer.writeBytes(bytes);
  }

  private BigDecimal readNative(ReadContext readContext) {
    MemoryBuffer buffer = readContext.getBuffer();
    int scale = buffer.readVarUInt32Small7();
    if (scale < -MAX_SCALE || scale > MAX_SCALE) {
      throw new DeserializationException("Decimal scale out of range: " + scale);
    }
    int precision = buffer.readVarUInt32Small7();
    int len = buffer.readVarUInt32Small7();
    checkBinaryBodyLength(len);
    buffer.checkReadableBytes(len);
    if (len == MAX_MAGNITUDE_BYTES + 1 && magnitudeExceedsLimit(buffer, len)) {
      throw new DeserializationException(
          "Decimal magnitude exceeds " + MAX_MAGNITUDE_BYTES + " bytes");
    }
    byte[] bytes = buffer.readBytes(len);
    BigInteger bigInteger = new BigInteger(bytes);
    return new BigDecimal(bigInteger, scale, new MathContext(precision));
  }

  private void writeXlang(WriteContext writeContext, BigDecimal value) {
    writeXlangDecimal(writeContext.getBuffer(), value.scale(), value.unscaledValue());
  }

  private BigDecimal readXlang(ReadContext readContext) {
    return readXlangDecimal(readContext.getBuffer());
  }

  static void writeXlangDecimal(MemoryBuffer buffer, int scale, BigInteger unscaled) {
    if (scale < -MAX_SCALE || scale > MAX_SCALE) {
      throw new IllegalArgumentException("Decimal scale out of range: " + scale);
    }
    if (canUseSmallEncoding(unscaled)) {
      long smallValue = unscaled.longValue();
      long header = encodeZigZag64(smallValue) << 1;
      buffer.writeVarInt32(scale);
      buffer.writeVarUInt64(header);
      return;
    }

    if (magnitudeExceedsLimit(unscaled)) {
      throw new IllegalArgumentException(
          "Decimal magnitude exceeds " + MAX_MAGNITUDE_BYTES + " bytes");
    }
    int sign = unscaled.signum() < 0 ? 1 : 0;
    BigInteger abs = unscaled.abs();
    byte[] magnitudeBytes = toCanonicalLittleEndianMagnitude(abs);
    long meta = (((long) magnitudeBytes.length) << 1) | sign;
    long header = (meta << 1) | 1L;
    buffer.writeVarInt32(scale);
    buffer.writeVarUInt64(header);
    buffer.writeBytes(magnitudeBytes);
  }

  static BigDecimal readXlangDecimal(MemoryBuffer buffer) {
    int scale = buffer.readVarInt32();
    if (scale < -MAX_SCALE || scale > MAX_SCALE) {
      throw new IllegalArgumentException("Decimal scale out of range: " + scale);
    }
    return new BigDecimal(readXlangUnscaled(buffer), scale);
  }

  static BigInteger readXlangBigInteger(MemoryBuffer buffer) {
    int scale = buffer.readVarInt32();
    if (scale != 0) {
      throw new IllegalArgumentException(
          "Cannot deserialize xlang decimal with scale " + scale + " into BigInteger");
    }
    return readXlangUnscaled(buffer);
  }

  private static BigInteger readXlangUnscaled(MemoryBuffer buffer) {
    long header = buffer.readVarUInt64();
    if ((header & 1L) == 0L) {
      return BigInteger.valueOf(decodeZigZag64(header >>> 1));
    }
    long meta = header >>> 1;
    int sign = (int) (meta & 1L);
    long lenLong = meta >>> 1;
    if (lenLong <= 0 || lenLong > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "Invalid decimal magnitude length " + lenLong + " in xlang body");
    }
    if (lenLong > MAX_MAGNITUDE_BYTES) {
      throw new IllegalArgumentException(
          "Decimal magnitude length exceeds " + MAX_MAGNITUDE_BYTES + " bytes: " + lenLong);
    }
    int len = (int) lenLong;
    buffer.checkReadableBytes(len);
    byte[] magnitudeBytes = buffer.readBytes(len);
    if (magnitudeBytes[len - 1] == 0) {
      throw new IllegalArgumentException("Non-canonical decimal body: trailing zero byte");
    }
    byte[] magnitude = toBigEndian(magnitudeBytes);
    BigInteger abs = new BigInteger(1, magnitude);
    if (abs.signum() == 0) {
      throw new IllegalArgumentException("Big decimal encoding must not represent zero");
    }
    return sign == 0 ? abs : abs.negate();
  }

  private static void checkBinaryBodyLength(int len) {
    if (len <= 0) {
      throw new DeserializationException("Decimal body length must be positive: " + len);
    }
    if (len > MAX_MAGNITUDE_BYTES + 1) {
      throw new DeserializationException(
          "Decimal magnitude exceeds " + MAX_MAGNITUDE_BYTES + " bytes");
    }
  }

  static boolean magnitudeExceedsLimit(MemoryBuffer buffer, int len) {
    int readerIndex = buffer.readerIndex();
    byte first = buffer.getByte(readerIndex);
    // Keep accepting redundant native sign extension. At this length, only a non-sign prefix or
    // the exact negative power -2^(MAX_MAGNITUDE_BITS) has a magnitude above the limit.
    if (first == 0) {
      return false;
    }
    if (first == -1) {
      for (int i = 1; i < len; i++) {
        if (buffer.getByte(readerIndex + i) != 0) {
          return false;
        }
      }
    }
    return true;
  }

  static boolean magnitudeExceedsLimit(BigInteger value) {
    int bitLength = value.bitLength();
    if (bitLength != MAX_MAGNITUDE_BITS) {
      return bitLength > MAX_MAGNITUDE_BITS;
    }
    // BigInteger.bitLength() is one below abs().bitLength() only for negative powers of two.
    // Check that shape solely at the limit so common negative values do not scan their words.
    return value.signum() < 0 && value.getLowestSetBit() == MAX_MAGNITUDE_BITS;
  }

  private static boolean canUseSmallEncoding(BigInteger value) {
    if (value.compareTo(LONG_MIN) < 0 || value.compareTo(LONG_MAX) > 0) {
      return false;
    }
    // The small form reserves the low header bit to distinguish small/big encodings,
    // so the zigzag value itself must still fit in 63 bits before the final << 1.
    long zigZag = encodeZigZag64(value.longValue());
    return (zigZag & Long.MIN_VALUE) == 0;
  }

  private static long encodeZigZag64(long value) {
    return (value << 1) ^ (value >> 63);
  }

  private static long decodeZigZag64(long value) {
    return (value >>> 1) ^ -(value & 1L);
  }

  private static byte[] toCanonicalLittleEndianMagnitude(BigInteger abs) {
    byte[] bigEndian = abs.toByteArray();
    int start = 0;
    while (start < bigEndian.length - 1 && bigEndian[start] == 0) {
      start++;
    }
    int len = bigEndian.length - start;
    if (len <= 0) {
      throw new IllegalArgumentException("Zero must use the small decimal encoding");
    }
    byte[] littleEndian = new byte[len];
    for (int i = 0; i < len; i++) {
      littleEndian[i] = bigEndian[bigEndian.length - 1 - i];
    }
    return littleEndian;
  }

  private static byte[] toBigEndian(byte[] littleEndian) {
    byte[] bigEndian = new byte[littleEndian.length];
    for (int i = 0; i < littleEndian.length; i++) {
      bigEndian[i] = littleEndian[littleEndian.length - 1 - i];
    }
    return bigEndian;
  }

  @Override
  public boolean readDataAlwaysAdvances() {
    return true;
  }
}
