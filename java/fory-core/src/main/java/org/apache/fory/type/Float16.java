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

/**
 * IEEE 754 binary16 半精度浮点数（16位）.
 *
 * <p>格式：1位符号 + 5位指数 + 10位尾数
 *
 * <ul>
 *   <li>指数偏移：15
 *   <li>范围：±65504（最大正规数）
 *   <li>最小正规数：2^-14 ≈ 6.104e-5
 *   <li>最小次正规数：2^-24 ≈ 5.96e-8
 * </ul>
 *
 * <p>此类是不可变的，线程安全。所有算术运算通过提升到 float32 执行，然后舍入回 float16。
 *
 * <p>转换使用 IEEE 754 round-to-nearest-even 舍入模式。
 */
public final class Float16 extends Number implements Comparable<Float16>, Serializable {
  private static final long serialVersionUID = 1L;

  // ========== 位掩码常量 ==========
  private static final int SIGN_MASK = 0x8000;
  private static final int EXP_MASK = 0x7C00;
  private static final int MANT_MASK = 0x03FF;

  // ========== 特殊值位模式 ==========
  private static final short BITS_NAN = (short) 0x7E00; // 标准静默 NaN
  private static final short BITS_POS_INF = (short) 0x7C00; // +Inf
  private static final short BITS_NEG_INF = (short) 0xFC00; // -Inf
  private static final short BITS_NEG_ZERO = (short) 0x8000; // -0
  private static final short BITS_MAX = (short) 0x7BFF; // 65504
  private static final short BITS_ONE = (short) 0x3C00; // 1.0
  private static final short BITS_MIN_NORMAL = (short) 0x0400; // 2^-14
  private static final short BITS_MIN_VALUE = (short) 0x0001; // 最小次正规数

  // ========== 公共常量 ==========
  /** 非数字（Not-a-Number）值. */
  public static final Float16 NaN = new Float16(BITS_NAN);

  /** 正无穷大. */
  public static final Float16 POSITIVE_INFINITY = new Float16(BITS_POS_INF);

  /** 负无穷大. */
  public static final Float16 NEGATIVE_INFINITY = new Float16(BITS_NEG_INF);

  /** 正零. */
  public static final Float16 ZERO = new Float16((short) 0);

  /** 负零. */
  public static final Float16 NEGATIVE_ZERO = new Float16(BITS_NEG_ZERO);

  /** 值为 1.0 的常量. */
  public static final Float16 ONE = new Float16(BITS_ONE);

  /** 最大有限值 65504. */
  public static final Float16 MAX_VALUE = new Float16(BITS_MAX);

  /** 最小正规数 2^-14. */
  public static final Float16 MIN_NORMAL = new Float16(BITS_MIN_NORMAL);

  /** 最小正值（次正规数）2^-24. */
  public static final Float16 MIN_VALUE = new Float16(BITS_MIN_VALUE);

  /** Float16 的位数. */
  public static final int SIZE_BITS = 16;

  /** Float16 的字节数. */
  public static final int SIZE_BYTES = 2;

  // ========== 存储字段 ==========
  private final short bits;

  // ========== 构造方法 ==========

  private Float16(short bits) {
    this.bits = bits;
  }

  /**
   * 从位模式创建 Float16.
   *
   * @param bits IEEE 754 binary16 位模式
   * @return Float16 实例
   */
  public static Float16 fromBits(short bits) {
    return new Float16(bits);
  }

  /**
   * 从 float 值创建 Float16（带舍入）.
   *
   * <p>使用 IEEE 754 round-to-nearest-even 舍入模式。超出范围的值会舍入到 ±Infinity。
   *
   * @param value float 值
   * @return Float16 实例
   */
  public static Float16 valueOf(float value) {
    return new Float16(floatToFloat16Bits(value));
  }

  /**
   * 获取位模式.
   *
   * @return IEEE 754 binary16 位模式
   */
  public short toBits() {
    return bits;
  }

  // ========== IEEE 754 转换算法 ==========

  /**
   * 将 float32 转换为 float16 位模式。使用 round-to-nearest-even 舍入模式。
   *
   * <p>参考 Go 实现：go/fory/float16/float16.go
   */
  private static short floatToFloat16Bits(float f32) {
    int bits32 = Float.floatToRawIntBits(f32);
    int sign = (bits32 >>> 31) & 0x1;
    int exp = (bits32 >>> 23) & 0xFF;
    int mant = bits32 & 0x7FFFFF;

    int outSign = sign << 15;
    int outExp;
    int outMant;

    // 特殊值：NaN 或 Inf
    if (exp == 0xFF) {
      outExp = 0x1F;
      if (mant != 0) {
        // NaN：保留尾数高位，确保至少有一位
        outMant = 0x200 | ((mant >>> 13) & 0x1FF);
        if (outMant == 0x200) {
          outMant = 0x201; // 确保至少一位非零
        }
      } else {
        // Inf
        outMant = 0;
      }
    }
    // 零或次正规 float32
    else if (exp == 0) {
      outExp = 0;
      outMant = 0;
    }
    // 正规数
    else {
      int newExp = exp - 127 + 15;

      // 上溢到 Inf
      if (newExp >= 31) {
        outExp = 0x1F;
        outMant = 0;
      }
      // 下溢到次正规或零
      else if (newExp <= 0) {
        // 隐式 1 + 尾数
        int fullMant = mant | 0x800000;
        int shift = 1 - newExp;
        int netShift = 13 + shift;

        if (netShift >= 24) {
          // 太小，变为零
          outExp = 0;
          outMant = 0;
        } else {
          outExp = 0;
          // 舍入到最近偶数
          int roundBit = (fullMant >>> (netShift - 1)) & 1;
          int sticky = fullMant & ((1 << (netShift - 1)) - 1);
          outMant = fullMant >>> netShift;

          if (roundBit == 1 && (sticky != 0 || (outMant & 1) == 1)) {
            outMant++;
          }
        }
      }
      // 正规范围
      else {
        outExp = newExp;
        outMant = mant >>> 13;

        // 舍入到最近偶数
        int roundBit = (mant >>> 12) & 1;
        int sticky = mant & 0xFFF;

        if (roundBit == 1 && (sticky != 0 || (outMant & 1) == 1)) {
          outMant++;
          if (outMant > 0x3FF) {
            // 尾数溢出，增加指数
            outMant = 0;
            outExp++;
            if (outExp >= 31) {
              outExp = 0x1F; // 溢出到 Inf
            }
          }
        }
      }
    }

    return (short) (outSign | (outExp << 10) | outMant);
  }

  /**
   * 将 float16 位模式转换为 float32.
   *
   * <p>此转换对所有 float16 值都是精确的。
   */
  private static float float16BitsToFloat(short bits16) {
    int bits = bits16 & 0xFFFF;
    int sign = (bits >>> 15) & 0x1;
    int exp = (bits >>> 10) & 0x1F;
    int mant = bits & 0x3FF;

    int outBits = sign << 31;

    // NaN 或 Inf
    if (exp == 0x1F) {
      outBits |= 0xFF << 23;
      if (mant != 0) {
        // NaN：提升尾数
        outBits |= mant << 13;
      }
    }
    // 零或次正规
    else if (exp == 0) {
      if (mant == 0) {
        // 有符号零
        // outBits 已经正确
      } else {
        // 次正规：归一化
        // 值 = (-1)^S * 2^-14 * (mant / 1024)
        // 需要找到隐式 1 的位置
        int shift = Integer.numberOfLeadingZeros(mant) - 22; // 32 - 10
        mant = (mant << shift) & 0x3FF;
        int newExp = 1 - 15 - shift + 127;
        outBits |= newExp << 23;
        outBits |= mant << 13;
      }
    }
    // 正规数
    else {
      outBits |= (exp - 15 + 127) << 23;
      outBits |= mant << 13;
    }

    return Float.intBitsToFloat(outBits);
  }

  // ========== 分类方法 ==========

  /** 判断是否为 NaN. */
  public boolean isNaN() {
    return (bits & EXP_MASK) == EXP_MASK && (bits & MANT_MASK) != 0;
  }

  /** 判断是否为无穷大（正或负）. */
  public boolean isInfinite() {
    return (bits & EXP_MASK) == EXP_MASK && (bits & MANT_MASK) == 0;
  }

  /** 判断是否为有限值（非 NaN 且非无穷大）. */
  public boolean isFinite() {
    return (bits & EXP_MASK) != EXP_MASK;
  }

  /** 判断是否为零（正零或负零）. */
  public boolean isZero() {
    return (bits & (EXP_MASK | MANT_MASK)) == 0;
  }

  /** 判断是否为正规数（非零、非次正规、非无穷大、非 NaN）. */
  public boolean isNormal() {
    int exp = bits & EXP_MASK;
    return exp != 0 && exp != EXP_MASK;
  }

  /** 判断是否为次正规数. */
  public boolean isSubnormal() {
    return (bits & EXP_MASK) == 0 && (bits & MANT_MASK) != 0;
  }

  /** 判断符号位（true 表示负数或负零）. */
  public boolean signbit() {
    return (bits & SIGN_MASK) != 0;
  }

  // ========== 算术运算（通过 float32 提升）==========

  /**
   * 加法运算.
   *
   * @param other 另一个 Float16 值
   * @return 相加结果
   */
  public Float16 add(Float16 other) {
    return valueOf(floatValue() + other.floatValue());
  }

  /**
   * 减法运算.
   *
   * @param other 另一个 Float16 值
   * @return 相减结果
   */
  public Float16 subtract(Float16 other) {
    return valueOf(floatValue() - other.floatValue());
  }

  /**
   * 乘法运算.
   *
   * @param other 另一个 Float16 值
   * @return 相乘结果
   */
  public Float16 multiply(Float16 other) {
    return valueOf(floatValue() * other.floatValue());
  }

  /**
   * 除法运算.
   *
   * @param other 另一个 Float16 值
   * @return 相除结果
   */
  public Float16 divide(Float16 other) {
    return valueOf(floatValue() / other.floatValue());
  }

  /**
   * 取反运算.
   *
   * @return 取反结果
   */
  public Float16 negate() {
    return fromBits((short) (bits ^ SIGN_MASK));
  }

  /**
   * 绝对值运算.
   *
   * @return 绝对值
   */
  public Float16 abs() {
    return fromBits((short) (bits & ~SIGN_MASK));
  }

  // ========== Number 实现 ==========

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

  // ========== 比较方法 ==========

  /**
   * 值相等比较（IEEE 754 语义：NaN != NaN，+0 == -0）.
   *
   * @param other 另一个 Float16 值
   * @return 是否数值相等
   */
  public boolean equalsValue(Float16 other) {
    if (isNaN() || other.isNaN()) {
      return false;
    }
    if (isZero() && other.isZero()) {
      return true;
    }
    return bits == other.bits;
  }

  @Override
  public int compareTo(Float16 other) {
    return Float.compare(floatValue(), other.floatValue());
  }

  /**
   * 对象相等比较（位模式相等）.
   *
   * <p>注意：此方法使用位模式相等，与 {@link #equalsValue(Float16)} 不同。 对于 IEEE 754 数值相等，请使用 equalsValue()。
   */
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
