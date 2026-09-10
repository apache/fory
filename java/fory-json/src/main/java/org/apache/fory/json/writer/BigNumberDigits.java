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

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * Stateless decimal digit arithmetic shared by concrete writer implementations.
 *
 * <p>Digit-count methods accept non-negative magnitudes and use a bit-length estimate followed by
 * one exact power-of-ten comparison. The helper retains no writer state, buffer, or callback.
 * Bounded coefficient conversion uses local bytes; unbounded conversion remains with the JDK.
 */
final class BigNumberDigits {
  // Packed 1-3 digit stores write one four-byte word; concrete writers reserve this tail once.
  static final int PACKED_WRITE_SLACK = 3;
  static final long[] LONG_POWERS_OF_TEN = {
    1L,
    10L,
    100L,
    1_000L,
    10_000L,
    100_000L,
    1_000_000L,
    10_000_000L,
    100_000_000L,
    1_000_000_000L,
    10_000_000_000L,
    100_000_000_000L,
    1_000_000_000_000L,
    10_000_000_000_000L,
    100_000_000_000_000L,
    1_000_000_000_000_000L,
    10_000_000_000_000_000L,
    100_000_000_000_000_000L,
    1_000_000_000_000_000_000L,
  };

  private BigNumberDigits() {}

  static boolean fitsLong(BigInteger value) {
    return value.bitLength() <= 63;
  }

  /** Formats an exact JDK coefficient with bit length 64 through 127 and a decimal scale. */
  static String formatInt128(BigInteger value, int scale) {
    boolean negative = value.signum() < 0;
    byte[] magnitude = value.abs().toByteArray();
    int split = magnitude.length - 8;
    long high = 0;
    for (int i = 0; i < split; i++) {
      high = (high << 8) | (magnitude[i] & 0xffL);
    }
    long low = 0;
    for (int i = split; i < magnitude.length; i++) {
      low = (low << 8) | (magnitude[i] & 0xffL);
    }
    // Five nine-digit groups fit before index 64. The remaining space accommodates the decimal
    // point, sign and the exponent even when an extreme scale makes that exponent exceed int.
    byte[] digits = new byte[80];
    int start = 64;
    do {
      int remainder;
      if (high == 0) {
        long quotient = Long.divideUnsigned(low, 1_000_000_000L);
        remainder = (int) (low - quotient * 1_000_000_000L);
        low = quotient;
      } else {
        long quotientHigh = Long.divideUnsigned(high, 1_000_000_000L);
        long rest = high - quotientHigh * 1_000_000_000L;
        // rest is below 10^9, so extending it by one unsigned 32-bit limb fits a signed long.
        long middle = (rest << 32) | (low >>> 32);
        long quotientMiddle = middle / 1_000_000_000L;
        rest = middle - quotientMiddle * 1_000_000_000L;
        long last = (rest << 32) | (low & 0xffffffffL);
        long quotientLow = last / 1_000_000_000L;
        remainder = (int) (last - quotientLow * 1_000_000_000L);
        high = quotientHigh;
        low = (quotientMiddle << 32) | quotientLow;
      }
      for (int i = 0; i < 9; i++) {
        int quotient = remainder / 10;
        digits[--start] = (byte) ('0' + remainder - quotient * 10);
        remainder = quotient;
      }
    } while ((high | low) != 0);
    while (digits[start] == '0' && start < 63) {
      start++;
    }
    int end = 64;
    int precision = end - start;
    long exponent = (long) precision - scale - 1;
    if (scale != 0) {
      if (scale >= 0 && exponent >= -6) {
        int point = precision - scale;
        if (point > 0) {
          System.arraycopy(digits, start, digits, start - 1, point);
          digits[start + point - 1] = '.';
          start--;
        } else {
          for (int i = 0; i < -point; i++) {
            digits[--start] = '0';
          }
          digits[--start] = '.';
          digits[--start] = '0';
        }
      } else {
        if (precision > 1) {
          digits[start - 1] = digits[start];
          digits[start] = '.';
          start--;
        }
        digits[end++] = 'E';
        digits[end++] = exponent < 0 ? (byte) '-' : (byte) '+';
        long absoluteExponent = exponent < 0 ? -exponent : exponent;
        int exponentDigits = digitCount(absoluteExponent);
        int cursor = end + exponentDigits;
        while (cursor > end) {
          long quotient = absoluteExponent / 10;
          digits[--cursor] = (byte) ('0' + absoluteExponent - quotient * 10);
          absoluteExponent = quotient;
        }
        end += exponentDigits;
      }
    }
    if (negative) {
      digits[--start] = '-';
    }
    return new String(digits, start, end - start, StandardCharsets.US_ASCII);
  }

  static int digitCount(int value) {
    if (value < 10) {
      return 1;
    }
    // 1233 / 4096 approximates log10(2); one power-of-ten comparison makes it exact.
    int estimate = ((33 - Integer.numberOfLeadingZeros(value)) * 1233) >>> 12;
    return value < LONG_POWERS_OF_TEN[estimate] ? estimate : estimate + 1;
  }

  static int digitCount(long value) {
    if (value < 10) {
      return 1;
    }
    int estimate = ((65 - Long.numberOfLeadingZeros(value)) * 1233) >>> 12;
    return estimate >= LONG_POWERS_OF_TEN.length || value < LONG_POWERS_OF_TEN[estimate]
        ? estimate
        : estimate + 1;
  }
}
