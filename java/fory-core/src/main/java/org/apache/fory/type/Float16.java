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

package org.apache.fory.type;

import java.io.Serializable;

public final class Float16 extends Number implements Comparable<Float16>, Serializable {
  private static final long serialVersionUID = 1L;

  private static final int SIGN_MASK = 0x8000;
  private static final int EXP_MASK = 0x7C00;
  private static final int MANT_MASK = 0x03FF;

  private static final short BITS_NAN = (short) 0x7E00;
  private static final short BITS_POS_INF = (short) 0x7C00; // +Inf
  private static final short BITS_NEG_INF = (short) 0xFC00; // -Inf
  private static final short BITS_NEG_ZERO = (short) 0x8000; // -0
  private static final short BITS_MAX = (short) 0x7BFF; // 65504
  private static final short BITS_ONE = (short) 0x3C00; // 1.0
  private static final short BITS_MIN_NORMAL = (short) 0x0400;
  private static final short BITS_MIN_VALUE = (short) 0x0001;

  public static final Float16 NaN = new Float16(BITS_NAN);

  public static final Float16 POSITIVE_INFINITY = new Float16(BITS_POS_INF);

  public static final Float16 NEGATIVE_INFINITY = new Float16(BITS_NEG_INF);

  public static final Float16 ZERO = new Float16((short) 0);

  public static final Float16 NEGATIVE_ZERO = new Float16(BITS_NEG_ZERO);

  public static final Float16 ONE = new Float16(BITS_ONE);

  public static final Float16 MAX_VALUE = new Float16(BITS_MAX);

  public static final Float16 MIN_NORMAL = new Float16(BITS_MIN_NORMAL);

  public static final Float16 MIN_VALUE = new Float16(BITS_MIN_VALUE);

  public static final int SIZE_BITS = 16;

  public static final int SIZE_BYTES = 2;

  private final short bits;

  private Float16(short bits) {
    this.bits = bits;
  }

  public static Float16 fromBits(short bits) {
    return new Float16(bits);
  }

  public static Float16 valueOf(float value) {
    return new Float16(floatToFloat16Bits(value));
  }

  public short toBits() {
    return bits;
  }

  public float toFloat() {
    return floatValue();
  }

  private static short floatToFloat16Bits(float f32) {
    int bits32 = Float.floatToRawIntBits(f32);
    int sign = (bits32 >>> 31) & 0x1;
    int exp = (bits32 >>> 23) & 0xFF;
    int mant = bits32 & 0x7FFFFF;

    int outSign = sign << 15;
    int outExp;
    int outMant;

    if (exp == 0xFF) {
      outExp = 0x1F;
      if (mant != 0) {
        outMant = 0x200 | ((mant >>> 13) & 0x1FF);
        if (outMant == 0x200) {
          outMant = 0x201;
        }
      } else {
        outMant = 0;
      }
    } else if (exp == 0) {
      outExp = 0;
      outMant = 0;
    } else {
      int newExp = exp - 127 + 15;

      if (newExp >= 31) {
        outExp = 0x1F;
        outMant = 0;
      } else if (newExp <= 0) {
        int fullMant = mant | 0x800000;
        int shift = 1 - newExp;
        int netShift = 13 + shift;

        if (netShift >= 24) {
          outExp = 0;
          outMant = 0;
        } else {
          outExp = 0;
          int roundBit = (fullMant >>> (netShift - 1)) & 1;
          int sticky = fullMant & ((1 << (netShift - 1)) - 1);
          outMant = fullMant >>> netShift;

          if (roundBit == 1 && (sticky != 0 || (outMant & 1) == 1)) {
            outMant++;
          }
        }
      } else {
        outExp = newExp;
        outMant = mant >>> 13;

        int roundBit = (mant >>> 12) & 1;
        int sticky = mant & 0xFFF;

        if (roundBit == 1 && (sticky != 0 || (outMant & 1) == 1)) {
          outMant++;
          if (outMant > 0x3FF) {
            outMant = 0;
            outExp++;
            if (outExp >= 31) {
              outExp = 0x1F;
            }
          }
        }
      }
    }

    return (short) (outSign | (outExp << 10) | outMant);
  }

  private static float float16BitsToFloat(short bits16) {
    int bits = bits16 & 0xFFFF;
    int sign = (bits >>> 15) & 0x1;
    int exp = (bits >>> 10) & 0x1F;
    int mant = bits & 0x3FF;

    int outBits = sign << 31;

    if (exp == 0x1F) {
      outBits |= 0xFF << 23;
      if (mant != 0) {
        outBits |= mant << 13;
      }
    } else if (exp == 0) {
      if (mant != 0) {
        int shift = Integer.numberOfLeadingZeros(mant) - 21;
        mant = (mant << shift) & 0x3FF;
        int newExp = 1 - 15 - shift + 127;
        outBits |= newExp << 23;
        outBits |= mant << 13;
      }
    } else {
      outBits |= (exp - 15 + 127) << 23;
      outBits |= mant << 13;
    }

    return Float.intBitsToFloat(outBits);
  }

  public boolean isNaN() {
    return (bits & EXP_MASK) == EXP_MASK && (bits & MANT_MASK) != 0;
  }

  public boolean isInfinite() {
    return (bits & EXP_MASK) == EXP_MASK && (bits & MANT_MASK) == 0;
  }

  public boolean isFinite() {
    return (bits & EXP_MASK) != EXP_MASK;
  }

  public boolean isZero() {
    return (bits & (EXP_MASK | MANT_MASK)) == 0;
  }

  public boolean isNormal() {
    int exp = bits & EXP_MASK;
    return exp != 0 && exp != EXP_MASK;
  }

  public boolean isSubnormal() {
    return (bits & EXP_MASK) == 0 && (bits & MANT_MASK) != 0;
  }

  public boolean signbit() {
    return (bits & SIGN_MASK) != 0;
  }

  public Float16 add(Float16 other) {
    return valueOf(floatValue() + other.floatValue());
  }

  public Float16 subtract(Float16 other) {
    return valueOf(floatValue() - other.floatValue());
  }

  public Float16 multiply(Float16 other) {
    return valueOf(floatValue() * other.floatValue());
  }

  public Float16 divide(Float16 other) {
    return valueOf(floatValue() / other.floatValue());
  }

  public Float16 negate() {
    return fromBits((short) (bits ^ SIGN_MASK));
  }

  public Float16 abs() {
    return fromBits((short) (bits & ~SIGN_MASK));
  }

  @Override
  public float floatValue() {
    return float16BitsToFloat(bits);
  }

  @Override
  public double doubleValue() {
    return floatValue();
  }

  @Override
  public int intValue() {
    return (int) floatValue();
  }

  @Override
  public long longValue() {
    return (long) floatValue();
  }

  @Override
  public byte byteValue() {
    return (byte) floatValue();
  }

  @Override
  public short shortValue() {
    return (short) floatValue();
  }

  public boolean isNumericEqual(Float16 other) {
    if (isNaN() || other.isNaN()) {
      return false;
    }
    if (isZero() && other.isZero()) {
      return true;
    }
    return bits == other.bits;
  }

  public boolean equalsValue(Float16 other) {
    return isNumericEqual(other);
  }

  public boolean lessThan(Float16 other) {
    if (isNaN() || other.isNaN()) {
      return false;
    }
    return floatValue() < other.floatValue();
  }

  public boolean lessThanOrEqual(Float16 other) {
    if (isNaN() || other.isNaN()) {
      return false;
    }
    return floatValue() <= other.floatValue();
  }

  public boolean greaterThan(Float16 other) {
    if (isNaN() || other.isNaN()) {
      return false;
    }
    return floatValue() > other.floatValue();
  }

  public boolean greaterThanOrEqual(Float16 other) {
    if (isNaN() || other.isNaN()) {
      return false;
    }
    return floatValue() >= other.floatValue();
  }

  public static int compare(Float16 a, Float16 b) {
    return Float.compare(a.floatValue(), b.floatValue());
  }

  public static Float16 parse(String s) {
    return valueOf(Float.parseFloat(s));
  }

  @Override
  public int compareTo(Float16 other) {
    return compare(this, other);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof Float16)) {
      return false;
    }
    Float16 other = (Float16) obj;
    return bits == other.bits;
  }

  @Override
  public int hashCode() {
    return Short.hashCode(bits);
  }

  @Override
  public String toString() {
    return Float.toString(floatValue());
  }
}
