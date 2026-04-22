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

/// Unsigned 16-bit integer wrapper used by the xlang type system.
///
/// The constructor truncates [value] to 16 bits using unsigned wrap-around
/// semantics. Arithmetic and bitwise operators normalize their results back to
/// the `0..65535` range.
final class Uint16 implements Comparable<Uint16> {
  /// The normalized unsigned 16-bit value.
  final int value;

  /// Creates an unsigned 16-bit value by truncating [value] to 16 bits.
  Uint16(int value) : value = value.toUnsigned(16);

  @override
  int compareTo(Uint16 other) => value.compareTo(other.value);

  Uint16 operator +(Object other) => switch (other) {
        int otherValue => Uint16(value + otherValue),
        Uint16 otherValue => Uint16(value + otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint16.'),
      };

  Uint16 operator -(Object other) => switch (other) {
        int otherValue => Uint16(value - otherValue),
        Uint16 otherValue => Uint16(value - otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint16.'),
      };

  Uint16 operator *(Object other) => switch (other) {
        int otherValue => Uint16(value * otherValue),
        Uint16 otherValue => Uint16(value * otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint16.'),
      };

  Uint16 operator ~/(Object other) => switch (other) {
        int otherValue => Uint16(value ~/ otherValue),
        Uint16 otherValue => Uint16(value ~/ otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint16.'),
      };

  Uint16 operator %(Object other) => switch (other) {
        int otherValue => Uint16(value % otherValue),
        Uint16 otherValue => Uint16(value % otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint16.'),
      };

  double operator /(Object other) => switch (other) {
        int otherValue => value / otherValue,
        Uint16 otherValue => value / otherValue.value,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint16.'),
      };

  Uint16 operator -() => Uint16(-value);

  Uint16 operator ~() => Uint16(~value);

  Uint16 operator &(Object other) => switch (other) {
        int otherValue => Uint16(value & otherValue),
        Uint16 otherValue => Uint16(value & otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint16.'),
      };

  Uint16 operator |(Object other) => switch (other) {
        int otherValue => Uint16(value | otherValue),
        Uint16 otherValue => Uint16(value | otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint16.'),
      };

  Uint16 operator ^(Object other) => switch (other) {
        int otherValue => Uint16(value ^ otherValue),
        Uint16 otherValue => Uint16(value ^ otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint16.'),
      };

  Uint16 operator <<(int shift) => Uint16(value << shift);

  Uint16 operator >>(int shift) => Uint16(value >>> shift);

  Uint16 operator >>>(int shift) => Uint16(value >>> shift);

  bool operator <(Object other) => switch (other) {
        int otherValue => value < otherValue,
        Uint16 otherValue => value < otherValue.value,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint16.'),
      };

  bool operator <=(Object other) => switch (other) {
        int otherValue => value <= otherValue,
        Uint16 otherValue => value <= otherValue.value,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint16.'),
      };

  bool operator >(Object other) => switch (other) {
        int otherValue => value > otherValue,
        Uint16 otherValue => value > otherValue.value,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint16.'),
      };

  bool operator >=(Object other) => switch (other) {
        int otherValue => value >= otherValue,
        Uint16 otherValue => value >= otherValue.value,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint16.'),
      };

  int toInt() => value;

  @override
  bool operator ==(Object other) =>
      identical(this, other) || other is Uint16 && other.value == value;

  @override
  int get hashCode => value.hashCode;

  @override
  String toString() => value.toString();
}
