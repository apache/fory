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
 * Unsigned 8-bit integer backed by a byte.
 *
 * <p>All arithmetic, bitwise, and shift operations wrap modulo {@code 2^8}. Use the unsigned
 * conversion helpers such as {@link #toInt()} and {@link #toLong()} when interacting with APIs that
 * expect unsigned magnitudes.
 */
public final class UInt8 implements Comparable<UInt8>, Serializable {
  public static final int SIZE_BITS = 8;
  public static final int SIZE_BYTES = 1;

  private static final int MASK = 0xFF;

  public static final UInt8 MIN_VALUE = new UInt8((byte) 0);
  public static final UInt8 MAX_VALUE = new UInt8((byte) -1);

  private final byte data;

  public UInt8(byte data) {
    this.data = data;
  }

  public static UInt8 valueOf(byte value) {
    return new UInt8(value);
  }

  public static UInt8 valueOf(int value) {
    return new UInt8((byte) value);
  }

  public static UInt8 add(byte a, byte b) {
    return new UInt8((byte) (a + b));
  }

  /** Adds {@code other} with wrapping semantics. */
  public UInt8 add(UInt8 other) {
    return add(data, other.data);
  }

  public static UInt8 subtract(byte a, byte b) {
    return new UInt8((byte) (a - b));
  }

  /** Subtracts {@code other} with wrapping semantics. */
  public UInt8 subtract(UInt8 other) {
    return subtract(data, other.data);
  }

  public static UInt8 multiply(byte a, byte b) {
    return new UInt8((byte) (a * b));
  }

  /** Multiplies by {@code other} with wrapping semantics. */
  public UInt8 multiply(UInt8 other) {
    return multiply(data, other.data);
  }

  public static UInt8 divide(byte a, byte b) {
    int divisor = b & 0xFF;
    return new UInt8((byte) ((a & 0xFF) / divisor));
  }

  /** Divides by {@code other} treating both operands as unsigned. */
  public UInt8 divide(UInt8 other) {
    return divide(data, other.data);
  }

  public static UInt8 remainder(byte a, byte b) {
    int divisor = b & 0xFF;
    return new UInt8((byte) ((a & 0xFF) % divisor));
  }

  /** Computes the remainder of the unsigned division by {@code other}. */
  public UInt8 remainder(UInt8 other) {
    return remainder(data, other.data);
  }

  public static UInt8 min(byte a, byte b) {
    return compare(a, b) <= 0 ? new UInt8(a) : new UInt8(b);
  }

  public static UInt8 max(byte a, byte b) {
    return compare(a, b) >= 0 ? new UInt8(a) : new UInt8(b);
  }

  /** Parses an unsigned decimal string into a {@link UInt8}. */
  public static UInt8 parse(String value) {
    return parse(value, 10);
  }

  /** Parses an unsigned string in {@code radix} into a {@link UInt8}. */
  public static UInt8 parse(String value, int radix) {
    int parsed = Integer.parseUnsignedInt(value, radix);
    if ((parsed & ~MASK) != 0) {
      throw new NumberFormatException("Value out of range for UInt8: " + value);
    }
    return new UInt8((byte) parsed);
  }

  public byte toByte() {
    return data;
  }

  public short toShort() {
    return (short) (data & 0xFF);
  }

  public static int toInt(byte value) {
    return value & MASK;
  }

  public int toInt() {
    return data & MASK;
  }

  public static long toLong(byte value) {
    return toInt(value);
  }

  public long toLong() {
    return toInt();
  }

  public static int compare(byte a, byte b) {
    return Integer.compare(a & MASK, b & MASK);
  }

  public static String toString(byte value) {
    return Integer.toString(value & MASK);
  }

  public static String toString(byte value, int radix) {
    return Integer.toString(value & MASK, radix);
  }

  @Override
  public String toString() {
    return Integer.toString(toInt());
  }

  /** Returns the hexadecimal string representation without sign-extension. */
  public String toHexString() {
    return Integer.toHexString(toInt());
  }

  /** Returns the unsigned string representation using the provided {@code radix}. */
  public String toUnsignedString(int radix) {
    return Integer.toString(toInt(), radix);
  }

  /** Returns {@code true} if the value equals zero. */
  public boolean isZero() {
    return data == 0;
  }

  /** Returns {@code true} if the value equals {@link #MAX_VALUE}. */
  public boolean isMaxValue() {
    return data == (byte) -1;
  }

  /** Bitwise AND with {@code other}. */
  public UInt8 and(UInt8 other) {
    return new UInt8((byte) ((data & MASK) & (other.data & MASK)));
  }

  /** Bitwise OR with {@code other}. */
  public UInt8 or(UInt8 other) {
    return new UInt8((byte) ((data & MASK) | (other.data & MASK)));
  }

  /** Bitwise XOR with {@code other}. */
  public UInt8 xor(UInt8 other) {
    return new UInt8((byte) ((data & MASK) ^ (other.data & MASK)));
  }

  /** Bitwise NOT. */
  public UInt8 not() {
    return new UInt8((byte) (~toInt()));
  }

  /** Logical left shift; bits shifted out are discarded. */
  public UInt8 shiftLeft(int bits) {
    int shift = bits & 0x1F;
    return new UInt8((byte) ((toInt() << shift) & MASK));
  }

  /** Logical right shift; zeros are shifted in from the left. */
  public UInt8 shiftRight(int bits) {
    int shift = bits & 0x1F;
    return new UInt8((byte) (toInt() >>> shift));
  }

  @Override
  public int compareTo(UInt8 other) {
    return compare(data, other.data);
  }

  public int intValue() {
    return toInt();
  }

  public long longValue() {
    return toLong();
  }

  public float floatValue() {
    return toInt();
  }

  public double doubleValue() {
    return toInt();
  }

  public byte byteValue() {
    return data;
  }

  public short shortValue() {
    return (short) (data & 0xFF);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof UInt8)) {
      return false;
    }
    return data == ((UInt8) obj).data;
  }

  @Override
  public int hashCode() {
    return data;
  }
}
