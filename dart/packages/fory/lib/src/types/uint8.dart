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

/// Unsigned 8-bit integer wrapper used by the xlang type system.
///
/// The constructor truncates [value] to 8 bits using unsigned wrap-around
/// semantics. Arithmetic and bitwise operators normalize their results back to
/// the `0..255` range.
final class Uint8 implements Comparable<Uint8> {
  /// The normalized unsigned 8-bit value.
  final int value;

  /// Creates an unsigned 8-bit value by truncating [value] to 8 bits.
  Uint8(int value) : value = value.toUnsigned(8);

  @override
  int compareTo(Uint8 other) => value.compareTo(other.value);

  Uint8 operator +(Object other) => switch (other) {
        int otherValue => Uint8(value + otherValue),
        Uint8 otherValue => Uint8(value + otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint8.'),
      };

  Uint8 operator -(Object other) => switch (other) {
        int otherValue => Uint8(value - otherValue),
        Uint8 otherValue => Uint8(value - otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint8.'),
      };

  Uint8 operator *(Object other) => switch (other) {
        int otherValue => Uint8(value * otherValue),
        Uint8 otherValue => Uint8(value * otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint8.'),
      };

  Uint8 operator ~/(Object other) => switch (other) {
        int otherValue => Uint8(value ~/ otherValue),
        Uint8 otherValue => Uint8(value ~/ otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint8.'),
      };

  Uint8 operator %(Object other) => switch (other) {
        int otherValue => Uint8(value % otherValue),
        Uint8 otherValue => Uint8(value % otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint8.'),
      };

  double operator /(Object other) => switch (other) {
        int otherValue => value / otherValue,
        Uint8 otherValue => value / otherValue.value,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint8.'),
      };

  Uint8 operator -() => Uint8(-value);

  Uint8 operator ~() => Uint8(~value);

  Uint8 operator &(Object other) => switch (other) {
        int otherValue => Uint8(value & otherValue),
        Uint8 otherValue => Uint8(value & otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint8.'),
      };

  Uint8 operator |(Object other) => switch (other) {
        int otherValue => Uint8(value | otherValue),
        Uint8 otherValue => Uint8(value | otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint8.'),
      };

  Uint8 operator ^(Object other) => switch (other) {
        int otherValue => Uint8(value ^ otherValue),
        Uint8 otherValue => Uint8(value ^ otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint8.'),
      };

  Uint8 operator <<(int shift) => Uint8(value << shift);

  Uint8 operator >>(int shift) => Uint8(value >>> shift);

  Uint8 operator >>>(int shift) => Uint8(value >>> shift);

  bool operator <(Object other) => switch (other) {
        int otherValue => value < otherValue,
        Uint8 otherValue => value < otherValue.value,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint8.'),
      };

  bool operator <=(Object other) => switch (other) {
        int otherValue => value <= otherValue,
        Uint8 otherValue => value <= otherValue.value,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint8.'),
      };

  bool operator >(Object other) => switch (other) {
        int otherValue => value > otherValue,
        Uint8 otherValue => value > otherValue.value,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint8.'),
      };

  bool operator >=(Object other) => switch (other) {
        int otherValue => value >= otherValue,
        Uint8 otherValue => value >= otherValue.value,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint8.'),
      };

  int toInt() => value;

  @override
  bool operator ==(Object other) =>
      identical(this, other) || other is Uint8 && other.value == value;

  @override
  int get hashCode => value.hashCode;

  @override
  String toString() => value.toString();
}
