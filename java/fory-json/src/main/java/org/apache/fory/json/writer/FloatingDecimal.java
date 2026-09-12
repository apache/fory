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

/*
 * Conversion and packed digit arithmetic adapted from Zmij:
 * https://github.com/vitaut/zmij, revision 7f08c399a32562cc4f8c26a89da4a1cc3d33dca2.
 *
 * MIT License
 *
 * Copyright (c) 2025 Victor Zverovich
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.apache.fory.json.writer;

import java.math.BigInteger;
import org.apache.fory.memory.LittleEndian;
import org.apache.fory.memory.NativeByteOrder;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.JdkVersion;

/** Shortest binary32/binary64 conversion with Java's decimal notation. */
final class FloatingDecimal {
  static final int FLOAT_MAX_CHARS = 16;
  static final int DOUBLE_MAX_CHARS = 24;

  // JDK 19 adopted shortest-decimal spelling. Older runtimes retain their own spelling.
  static final boolean AVAILABLE = JdkVersion.MAJOR_VERSION >= 19 && !AndroidSupport.IS_ANDROID;
  private static final long ASCII_ZEROES = 0x3030303030303030L;
  private static final long[] POWERS = {
    1L,
    10L,
    100L,
    1000L,
    10000L,
    100000L,
    1000000L,
    10000000L,
    100000000L,
    1000000000L,
    10000000000L,
    100000000000L,
    1000000000000L,
    10000000000000L,
    100000000000000L,
    1000000000000000L,
    10000000000000000L,
    100000000000000000L
  };

  private FloatingDecimal() {}

  static void appendTo(float value, StringBuilder builder) {
    builder.setLength(0);
    builder.append(value);
  }

  static void appendTo(double value, StringBuilder builder) {
    builder.setLength(0);
    builder.append(value);
  }

  static int write(byte[] bytes, int pos, float value) {
    int bits = Float.floatToRawIntBits(value);
    if (bits < 0) {
      bytes[pos++] = '-';
    }
    int rawExponent = (bits >>> 23) & 255;
    int significand = bits & 0x7fffff;
    if (rawExponent == 0 && significand == 0) {
      bytes[pos] = '0';
      bytes[pos + 1] = '.';
      bytes[pos + 2] = '0';
      return pos + 3;
    }
    boolean regular = significand != 0 || rawExponent == 0;
    int exponent = rawExponent == 0 ? -149 : rawExponent - 150;
    int adjustment = 0;
    if (rawExponent != 0) {
      significand |= 1 << 23;
    } else if (significand < 8) {
      // Java chooses the closest representation with at least two significant digits.
      significand *= 10;
      adjustment = -1;
    }
    if (!regular) {
      return writeIrregular(bytes, pos, significand, exponent, adjustment);
    }
    int decimalExponent = (exponent * 315653) >> 20;
    int shift = exponent + ((-(decimalExponent + 1) * 217707) >> 16) + 35;
    long power = Powers.SIGNIFICANDS[(-decimalExponent - 1 + 307) * 2];
    long product = FloatingDecimalMath.unsignedMultiplyHigh(power + 1, (long) significand << shift);
    long integral = product >>> 34;
    long fractional = product & ((1L << 34) - 1);
    long halfUlp = (power >>> (65 - shift)) + (1 - (significand & 1));
    boolean up = ((fractional + halfUlp) >>> 34) != 0;
    boolean down = halfUlp > fractional;
    // Java selects the closest value on a grid with at least two significant digits.
    if (integral < 10) {
      up = false;
      down = false;
    }
    integral += up ? 1 : 0;
    if (!up && !down) {
      int digit = (int) ((fractional * 10 + (1L << 33)) >>> 34);
      if (fractional == (1L << 32)) {
        digit = 2;
      }
      return writeDecimal(bytes, pos, integral * 10 + digit, decimalExponent + adjustment);
    }
    return writeDecimal(bytes, pos, integral, decimalExponent + 1 + adjustment);
  }

  static int write(byte[] bytes, int pos, double value) {
    long bits = Double.doubleToRawLongBits(value);
    if (bits < 0) {
      bytes[pos++] = '-';
    }
    int rawExponent = (int) (bits >>> 52) & 2047;
    long significand = bits & 0xfffffffffffffL;
    if (rawExponent == 0 && significand == 0) {
      bytes[pos] = '0';
      bytes[pos + 1] = '.';
      bytes[pos + 2] = '0';
      return pos + 3;
    }
    boolean regular = significand != 0 || rawExponent == 0;
    int exponent = rawExponent == 0 ? -1074 : rawExponent - 1075;
    int adjustment = 0;
    if (rawExponent != 0) {
      significand |= 1L << 52;
    } else if (significand < 3) {
      significand *= 10;
      adjustment = -1;
    }
    if (!regular) {
      return writeIrregular(bytes, pos, significand, exponent, adjustment);
    }
    int decimalExponent = (exponent * 315653) >> 20;
    int shift = exponent + ((-(decimalExponent + 1) * 217707) >> 16) + 10;
    int index = (-decimalExponent - 1 + 307) * 2;
    long high = Powers.SIGNIFICANDS[index];
    long low = Powers.SIGNIFICANDS[index + 1];
    long scaled = significand << shift;
    long productLow = high * scaled;
    long productHigh = FloatingDecimalMath.unsignedMultiplyHigh(high, scaled);
    long middle = productLow + FloatingDecimalMath.unsignedMultiplyHigh(low, scaled);
    productHigh += Long.compareUnsigned(middle, productLow) < 0 ? 1 : 0;
    long integral = productHigh >>> 9;
    long fractional = (productHigh << 55) | (middle >>> 9);
    long halfUlp = (high >>> (10 - shift)) + (1 - (significand & 1));
    boolean up = Long.compareUnsigned(fractional + halfUlp, fractional) < 0;
    boolean down = Long.compareUnsigned(halfUlp, fractional) > 0;
    // Java selects the closest value on a grid with at least two significant digits.
    if (integral < 10) {
      up = false;
      down = false;
    }
    integral += up ? 1 : 0;
    if (!up && !down) {
      int digit = roundedDigit(fractional, Long.MIN_VALUE + 6);
      if (fractional == (1L << 62)) {
        digit = 2;
      }
      return writeDecimal(bytes, pos, integral * 10 + digit, decimalExponent + adjustment);
    }
    return writeDecimal(bytes, pos, integral, decimalExponent + 1 + adjustment);
  }

  // Normal powers of two have a closer lower boundary; the smallest normal is also safe here.
  private static int writeIrregular(
      byte[] bytes, int pos, long significand, int exponent, int adjustment) {
    int decimalExponent = (exponent * 315653 - 131072) >> 20;
    int shift = exponent + ((-(decimalExponent + 1) * 217707) >> 16) + 10;
    int index = (-decimalExponent - 1 + 307) * 2;
    long high = Powers.SIGNIFICANDS[index];
    long low = Powers.SIGNIFICANDS[index + 1];
    long scaled = significand << shift;
    long productLow = high * scaled;
    long productHigh = FloatingDecimalMath.unsignedMultiplyHigh(high, scaled);
    long middle = productLow + FloatingDecimalMath.unsignedMultiplyHigh(low, scaled);
    productHigh += Long.compareUnsigned(middle, productLow) < 0 ? 1 : 0;
    long integral = productHigh >>> 9;
    long fractional = (productHigh << 55) | (middle >>> 9);
    long halfUlp = high >>> (10 - shift);
    boolean up = Long.compareUnsigned(halfUlp, ~fractional) > 0;
    boolean down = Long.compareUnsigned(halfUlp >>> 1, fractional) > 0;
    integral += up ? 1 : 0;
    if (!up && !down) {
      int digit = roundedDigit(fractional, Long.MAX_VALUE);
      int lower = roundedDigit(fractional - (halfUlp >>> 1), -1L);
      if (digit < lower) {
        digit = lower;
      }
      return writeDecimal(bytes, pos, integral * 10 + digit, decimalExponent + adjustment);
    }
    return writeDecimal(bytes, pos, integral, decimalExponent + 1 + adjustment);
  }

  private static int roundedDigit(long fraction, long bias) {
    long low = fraction * 10;
    return (int)
        (FloatingDecimalMath.unsignedMultiplyHigh(fraction, 10)
            + (Long.compareUnsigned(low + bias, low) < 0 ? 1 : 0));
  }

  static int writeUtf16(byte[] bytes, int pos, float value) {
    return widen(bytes, pos, write(bytes, pos, value));
  }

  static int writeUtf16(byte[] bytes, int pos, double value) {
    return widen(bytes, pos, write(bytes, pos, value));
  }

  private static int widen(byte[] bytes, int start, int end) {
    for (int i = end - 1; i >= start; i--) {
      int c = bytes[i];
      int out = start + (i - start) * 2;
      bytes[out] = (byte) (NativeByteOrder.IS_LITTLE_ENDIAN ? c : 0);
      bytes[out + 1] = (byte) (NativeByteOrder.IS_LITTLE_ENDIAN ? 0 : c);
    }
    return start + (end - start) * 2;
  }

  // Convert eight decimal digits in parallel within one machine word, low byte first.
  private static long digits8(long value) {
    long groups4 = value + 4294957296L * ((value * 109951163L) >>> 40);
    long groups2 = groups4 + 65436L * (((groups4 * 5243L) >>> 19) & 0x7f0000007fL);
    long digits = groups2 + 246L * (((groups2 * 103L) >>> 10) & 0xf000f000f000fL);
    return Long.reverseBytes(digits);
  }

  // The caller reserves 16/24 bytes. Packed stores may cover trailing digits that are omitted
  // from the logical result, but never extend past that reservation; following tokens overwrite
  // them.
  private static int writeDecimal(byte[] bytes, int pos, long significand, int exponent) {
    int length = ((64 - Long.numberOfLeadingZeros(significand)) * 1233) >>> 12;
    length += significand >= POWERS[length] ? 1 : 0;
    long low;
    long high = 0;
    int highValue = 0;
    int trailing;
    if (length <= 8) {
      low = digits8(significand);
      trailing = Long.numberOfLeadingZeros(low) >>> 3;
    } else {
      highValue = (int) (significand / 100000000L);
      low = digits8(significand - (long) highValue * 100000000L);
      high = digits8(highValue % 100000000);
      trailing =
          low != 0
              ? Long.numberOfLeadingZeros(low) >>> 3
              : 8 + (Long.numberOfLeadingZeros(high) >>> 3);
    }
    int significant = length - trailing;
    int point = length + exponent;
    boolean scientific = point < -2 || point > 7;
    int digitsStart;
    if (scientific) {
      digitsStart = pos + 1;
    } else if (point <= 0) {
      bytes[pos++] = '0';
      bytes[pos++] = '.';
      for (int i = 0; i < -point; i++) {
        bytes[pos++] = '0';
      }
      digitsStart = pos;
    } else {
      digitsStart = pos;
    }
    if (length <= 8) {
      LittleEndian.putInt64(bytes, digitsStart, (low | ASCII_ZEROES) >>> ((8 - length) * 8));
    } else {
      int count = length - 8;
      if (count == 9) {
        bytes[digitsStart] = (byte) ('0' + highValue / 100000000);
        LittleEndian.putInt64(bytes, digitsStart + 1, high | ASCII_ZEROES);
      } else {
        LittleEndian.putInt64(bytes, digitsStart, (high | ASCII_ZEROES) >>> ((8 - count) * 8));
      }
      LittleEndian.putInt64(bytes, digitsStart + count, low | ASCII_ZEROES);
    }
    if (scientific) {
      bytes[pos] = bytes[pos + 1];
      bytes[pos + 1] = '.';
      int end = pos + significant + 1;
      if (significant == 1) {
        bytes[end++] = '0';
      }
      bytes[end++] = 'E';
      int scientificExponent = point - 1;
      if (scientificExponent < 0) {
        bytes[end++] = '-';
        scientificExponent = -scientificExponent;
      }
      if (scientificExponent >= 100) {
        bytes[end++] = (byte) ('0' + scientificExponent / 100);
        scientificExponent %= 100;
        bytes[end++] = (byte) ('0' + scientificExponent / 10);
      } else if (scientificExponent >= 10) {
        bytes[end++] = (byte) ('0' + scientificExponent / 10);
      }
      bytes[end++] = (byte) ('0' + scientificExponent % 10);
      return end;
    }
    if (point <= 0) {
      return digitsStart + significant;
    }
    if (point >= significant) {
      for (int i = significant; i < point; i++) {
        bytes[pos + i] = '0';
      }
      bytes[pos + point] = '.';
      bytes[pos + point + 1] = '0';
      return pos + point + 2;
    }
    System.arraycopy(bytes, pos + point, bytes, pos + point + 1, significant - point);
    bytes[pos + point] = '.';
    return pos + significant + 1;
  }

  private static final class Powers {
    // floor(10^e * 2^(127 - floor(log2(10^e)))), e in [-307, 341].
    private static final long[] SIGNIFICANDS = significands();

    private static long[] significands() {
      long[] result = new long[649 * 2];
      for (int exponent = -307; exponent <= 341; exponent++) {
        BigInteger n = BigInteger.TEN.pow(Math.abs(exponent));
        int bits = exponent >= 0 ? n.bitLength() - 1 : -n.bitLength();
        BigInteger scaled =
            exponent >= 0
                ? (127 - bits >= 0 ? n.shiftLeft(127 - bits) : n.shiftRight(bits - 127))
                : BigInteger.ONE.shiftLeft(127 - bits).divide(n);
        int i = (exponent + 307) * 2;
        result[i] = scaled.shiftRight(64).longValue();
        result[i + 1] = scaled.longValue();
      }
      return result;
    }
  }
}
