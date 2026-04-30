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
 * Unsigned 32-bit integer backed by an int.
 *
 * <p>Operations wrap modulo {@code 2^32} to mirror the behavior of unsigned arithmetic. Use {@link
 * #toLong()} to obtain an unsigned magnitude when interacting with APIs that require a larger
 * signed container.
 */
public final class UInt32 implements Comparable<UInt32>, Serializable {
  public static final int SIZE_BITS = 32;
  public static final int SIZE_BYTES = 4;

  public static final UInt32 MIN_VALUE = new UInt32(0);
  public static final UInt32 MAX_VALUE = new UInt32(-1);

  private final int data;

  public UInt32(int data) {
    this.data = data;
  }

  public static UInt32 valueOf(int value) {
    return new UInt32(value);
  }

  public static UInt32 add(int a, int b) {
    return new UInt32(a + b);
  }

  /** Adds {@code other} with wrapping semantics. */
  public UInt32 add(UInt32 other) {
    return add(data, other.data);
  }

  public static UInt32 subtract(int a, int b) {
    return new UInt32(a - b);
  }

  /** Subtracts {@code other} with wrapping semantics. */
  public UInt32 subtract(UInt32 other) {
    return subtract(data, other.data);
  }

  public static UInt32 multiply(int a, int b) {
    return new UInt32(a * b);
  }

  /** Multiplies by {@code other} with wrapping semantics. */
  public UInt32 multiply(UInt32 other) {
    return multiply(data, other.data);
  }

  public static UInt32 divide(int a, int b) {
    return new UInt32(Integer.divideUnsigned(a, b));
  }

  /** Divides by {@code other} treating both operands as unsigned. */
  public UInt32 divide(UInt32 other) {
    return divide(data, other.data);
  }

  public static UInt32 remainder(int a, int b) {
    return new UInt32(Integer.remainderUnsigned(a, b));
  }

  /** Computes the remainder of the unsigned division by {@code other}. */
  public UInt32 remainder(UInt32 other) {
    return remainder(data, other.data);
  }

  public static UInt32 min(int a, int b) {
    return compare(a, b) <= 0 ? new UInt32(a) : new UInt32(b);
  }

  public static UInt32 max(int a, int b) {
    return compare(a, b) >= 0 ? new UInt32(a) : new UInt32(b);
  }

  /** Parses an unsigned decimal string into a {@link UInt32}. */
  public static UInt32 parse(String value) {
    return parse(value, 10);
  }

  /** Parses an unsigned string in {@code radix} into a {@link UInt32}. */
  public static UInt32 parse(String value, int radix) {
    return new UInt32(Integer.parseUnsignedInt(value, radix));
  }

  public int toInt() {
    return data;
  }

  public static long toLong(int value) {
    return Integer.toUnsignedLong(value);
  }

  public long toLong() {
    return Integer.toUnsignedLong(data);
  }

  public static int compare(int a, int b) {
    return Integer.compareUnsigned(a, b);
  }

  public static String toString(int value) {
    return Integer.toUnsignedString(value);
  }

  public static String toString(int value, int radix) {
    return Integer.toUnsignedString(value, radix);
  }

  @Override
  public String toString() {
    return Integer.toUnsignedString(data);
  }

  /** Returns the hexadecimal string representation without sign-extension. */
  public String toHexString() {
    return Integer.toHexString(data);
  }

  /** Returns the unsigned string representation using the provided {@code radix}. */
  public String toUnsignedString(int radix) {
    return Integer.toUnsignedString(data, radix);
  }

  /** Returns {@code true} if the value equals zero. */
  public boolean isZero() {
    return data == 0;
  }

  /** Returns {@code true} if the value equals {@link #MAX_VALUE}. */
  public boolean isMaxValue() {
    return data == -1;
  }

  /** Bitwise AND with {@code other}. */
  public UInt32 and(UInt32 other) {
    return new UInt32(data & other.data);
  }

  /** Bitwise OR with {@code other}. */
  public UInt32 or(UInt32 other) {
    return new UInt32(data | other.data);
  }

  /** Bitwise XOR with {@code other}. */
  public UInt32 xor(UInt32 other) {
    return new UInt32(data ^ other.data);
  }

  /** Bitwise NOT. */
  public UInt32 not() {
    return new UInt32(~data);
  }

  /** Logical left shift; bits shifted out are discarded. */
  public UInt32 shiftLeft(int bits) {
    int shift = bits & 0x1F;
    return new UInt32(data << shift);
  }

  /** Logical right shift; zeros are shifted in from the left. */
  public UInt32 shiftRight(int bits) {
    int shift = bits & 0x1F;
    return new UInt32(data >>> shift);
  }

  @Override
  public int compareTo(UInt32 other) {
    return compare(data, other.data);
  }

  public int intValue() {
    return data;
  }

  public long longValue() {
    return toLong();
  }

  public float floatValue() {
    return (float) toLong();
  }

  public double doubleValue() {
    return (double) toLong();
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
    if (!(obj instanceof UInt32)) {
      return false;
    }
    return data == ((UInt32) obj).data;
  }

  @Override
  public int hashCode() {
    return data;
  }
}
