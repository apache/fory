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

package org.apache.fory.type.unsigned;

import java.io.Serializable;

/**
 * Unsigned 64-bit integer backed by a long.
 *
 * <p>Operations wrap modulo {@code 2^64}. Use {@link #doubleValue()} or {@link #toString()} when
 * the full unsigned magnitude must be exposed beyond the signed {@code long} range.
 */
public final class UInt64 implements Comparable<UInt64>, Serializable {
  public static final int SIZE_BITS = 64;
  public static final int SIZE_BYTES = 8;

  public static final UInt64 MIN_VALUE = new UInt64(0L);
  public static final UInt64 MAX_VALUE = new UInt64(-1L);

  private final long data;

  public UInt64(long data) {
    this.data = data;
  }

  public static UInt64 valueOf(long value) {
    return new UInt64(value);
  }

  public static UInt64 add(long a, long b) {
    return new UInt64(a + b);
  }

  /** Adds {@code other} with wrapping semantics. */
  public UInt64 add(UInt64 other) {
    return add(data, other.data);
  }

  public static UInt64 subtract(long a, long b) {
    return new UInt64(a - b);
  }

  /** Subtracts {@code other} with wrapping semantics. */
  public UInt64 subtract(UInt64 other) {
    return subtract(data, other.data);
  }

  public static UInt64 multiply(long a, long b) {
    return new UInt64(a * b);
  }

  /** Multiplies by {@code other} with wrapping semantics. */
  public UInt64 multiply(UInt64 other) {
    return multiply(data, other.data);
  }

  public static UInt64 divide(long a, long b) {
    return new UInt64(Long.divideUnsigned(a, b));
  }

  /** Divides by {@code other} treating both operands as unsigned. */
  public UInt64 divide(UInt64 other) {
    return divide(data, other.data);
  }

  public static UInt64 remainder(long a, long b) {
    return new UInt64(Long.remainderUnsigned(a, b));
  }

  /** Computes the remainder of the unsigned division by {@code other}. */
  public UInt64 remainder(UInt64 other) {
    return remainder(data, other.data);
  }

  public static UInt64 min(long a, long b) {
    return compare(a, b) <= 0 ? new UInt64(a) : new UInt64(b);
  }

  public static UInt64 max(long a, long b) {
    return compare(a, b) >= 0 ? new UInt64(a) : new UInt64(b);
  }

  /** Parses an unsigned decimal string into a {@link UInt64}. */
  public static UInt64 parse(String value) {
    return parse(value, 10);
  }

  /** Parses an unsigned string in {@code radix} into a {@link UInt64}. */
  public static UInt64 parse(String value, int radix) {
    return new UInt64(Long.parseUnsignedLong(value, radix));
  }

  public long toLong() {
    return data;
  }

  public static int compare(long a, long b) {
    return Long.compareUnsigned(a, b);
  }

  public static String toString(long value) {
    return Long.toUnsignedString(value);
  }

  public static String toString(long value, int radix) {
    return Long.toUnsignedString(value, radix);
  }

  @Override
  public String toString() {
    return Long.toUnsignedString(data);
  }

  /** Returns the hexadecimal string representation without sign-extension. */
  public String toHexString() {
    return Long.toHexString(data);
  }

  /** Returns the unsigned string representation using the provided {@code radix}. */
  public String toUnsignedString(int radix) {
    return Long.toUnsignedString(data, radix);
  }

  /** Returns {@code true} if the value equals zero. */
  public boolean isZero() {
    return data == 0L;
  }

  /** Returns {@code true} if the value equals {@link #MAX_VALUE}. */
  public boolean isMaxValue() {
    return data == -1L;
  }

  /** Bitwise AND with {@code other}. */
  public UInt64 and(UInt64 other) {
    return new UInt64(data & other.data);
  }

  /** Bitwise OR with {@code other}. */
  public UInt64 or(UInt64 other) {
    return new UInt64(data | other.data);
  }

  /** Bitwise XOR with {@code other}. */
  public UInt64 xor(UInt64 other) {
    return new UInt64(data ^ other.data);
  }

  /** Bitwise NOT. */
  public UInt64 not() {
    return new UInt64(~data);
  }

  /** Logical left shift; bits shifted out are discarded. */
  public UInt64 shiftLeft(int bits) {
    int shift = bits & 0x3F;
    return new UInt64(data << shift);
  }

  /** Logical right shift; zeros are shifted in from the left. */
  public UInt64 shiftRight(int bits) {
    int shift = bits & 0x3F;
    return new UInt64(data >>> shift);
  }

  @Override
  public int compareTo(UInt64 other) {
    return compare(data, other.data);
  }

  public int intValue() {
    return (int) data;
  }

  public long longValue() {
    return data;
  }

  public float floatValue() {
    return (float) toUnsignedDouble(data);
  }

  public double doubleValue() {
    return toUnsignedDouble(data);
  }

  public byte byteValue() {
    return (byte) data;
  }

  public short shortValue() {
    return (short) data;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof UInt64)) {
      return false;
    }
    return data == ((UInt64) obj).data;
  }

  @Override
  public int hashCode() {
    return (int) (data ^ (data >>> 32));
  }

  private static double toUnsignedDouble(long value) {
    double high = (double) (value >>> 1);
    return high * 2.0 + (value & 1L);
  }
}
