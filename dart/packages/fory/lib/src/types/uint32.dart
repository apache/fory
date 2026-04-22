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

/// Unsigned 32-bit integer wrapper used by the xlang type system.
///
/// This wrapper preserves xlang `uint32` and `varuint32` semantics on the Dart
/// side. Arithmetic and bitwise operators normalize every result back to the
/// unsigned 32-bit range.
final class Uint32 implements Comparable<Uint32> {
  /// The normalized unsigned 32-bit value.
  final int value;

  /// Creates an unsigned 32-bit value by truncating [value] to 32 bits.
  Uint32(int value) : value = value.toUnsigned(32);

  @override
  int compareTo(Uint32 other) => value.compareTo(other.value);

  Uint32 operator +(Object other) => switch (other) {
        int otherValue => Uint32(value + otherValue),
        Uint32 otherValue => Uint32(value + otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint32.'),
      };

  Uint32 operator -(Object other) => switch (other) {
        int otherValue => Uint32(value - otherValue),
        Uint32 otherValue => Uint32(value - otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint32.'),
      };

  Uint32 operator *(Object other) => switch (other) {
        int otherValue => Uint32(value * otherValue),
        Uint32 otherValue => Uint32(value * otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint32.'),
      };

  Uint32 operator ~/(Object other) => switch (other) {
        int otherValue => Uint32(value ~/ otherValue),
        Uint32 otherValue => Uint32(value ~/ otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint32.'),
      };

  Uint32 operator %(Object other) => switch (other) {
        int otherValue => Uint32(value % otherValue),
        Uint32 otherValue => Uint32(value % otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint32.'),
      };

  double operator /(Object other) => switch (other) {
        int otherValue => value / otherValue,
        Uint32 otherValue => value / otherValue.value,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint32.'),
      };

  Uint32 operator -() => Uint32(-value);

  Uint32 operator ~() => Uint32(~value);

  Uint32 operator &(Object other) => switch (other) {
        int otherValue => Uint32(value & otherValue),
        Uint32 otherValue => Uint32(value & otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint32.'),
      };

  Uint32 operator |(Object other) => switch (other) {
        int otherValue => Uint32(value | otherValue),
        Uint32 otherValue => Uint32(value | otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint32.'),
      };

  Uint32 operator ^(Object other) => switch (other) {
        int otherValue => Uint32(value ^ otherValue),
        Uint32 otherValue => Uint32(value ^ otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint32.'),
      };

  Uint32 operator <<(int shift) => Uint32(value << shift);

  Uint32 operator >>(int shift) => Uint32(value >>> shift);

  Uint32 operator >>>(int shift) => Uint32(value >>> shift);

  bool operator <(Object other) => switch (other) {
        int otherValue => value < otherValue,
        Uint32 otherValue => value < otherValue.value,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint32.'),
      };

  bool operator <=(Object other) => switch (other) {
        int otherValue => value <= otherValue,
        Uint32 otherValue => value <= otherValue.value,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint32.'),
      };

  bool operator >(Object other) => switch (other) {
        int otherValue => value > otherValue,
        Uint32 otherValue => value > otherValue.value,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint32.'),
      };

  bool operator >=(Object other) => switch (other) {
        int otherValue => value >= otherValue,
        Uint32 otherValue => value >= otherValue.value,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint32.'),
      };

  int toInt() => value;

  @override
  bool operator ==(Object other) =>
      identical(this, other) || other is Uint32 && other.value == value;

  @override
  int get hashCode => value.hashCode;

  @override
  String toString() => value.toString();
}
