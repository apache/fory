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

/// Unsigned 64-bit integer wrapper used by the xlang type system.
///
/// The constructor truncates [value] to 64 bits using unsigned wrap-around
/// semantics. This wrapper is the default Dart carrier for root and untyped
/// xlang `uint64`, `varuint64`, and `taggeduint64` payloads; declared `int`
/// fields annotated with `@Uint64Type(...)` still read back as plain Dart
/// [int] values through generated conversion paths.
final class Uint64 implements Comparable<Uint64> {
  /// The normalized unsigned 64-bit value.
  final int value;

  /// Creates an unsigned 64-bit value by truncating [value] to 64 bits.
  Uint64(int value) : value = value.toUnsigned(64);

  @override
  int compareTo(Uint64 other) => value.compareTo(other.value);

  Uint64 operator +(Object other) => switch (other) {
        int otherValue => Uint64(value + otherValue),
        Uint64 otherValue => Uint64(value + otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator -(Object other) => switch (other) {
        int otherValue => Uint64(value - otherValue),
        Uint64 otherValue => Uint64(value - otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator *(Object other) => switch (other) {
        int otherValue => Uint64(value * otherValue),
        Uint64 otherValue => Uint64(value * otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator ~/(Object other) => switch (other) {
        int otherValue => Uint64(value ~/ otherValue),
        Uint64 otherValue => Uint64(value ~/ otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator %(Object other) => switch (other) {
        int otherValue => Uint64(value % otherValue),
        Uint64 otherValue => Uint64(value % otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  double operator /(Object other) => switch (other) {
        int otherValue => value / otherValue,
        Uint64 otherValue => value / otherValue.value,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator -() => Uint64(-value);

  Uint64 operator ~() => Uint64(~value);

  Uint64 operator &(Object other) => switch (other) {
        int otherValue => Uint64(value & otherValue),
        Uint64 otherValue => Uint64(value & otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator |(Object other) => switch (other) {
        int otherValue => Uint64(value | otherValue),
        Uint64 otherValue => Uint64(value | otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator ^(Object other) => switch (other) {
        int otherValue => Uint64(value ^ otherValue),
        Uint64 otherValue => Uint64(value ^ otherValue.value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator <<(int shift) => Uint64(value << shift);

  Uint64 operator >>(int shift) => Uint64(value >>> shift);

  Uint64 operator >>>(int shift) => Uint64(value >>> shift);

  bool operator <(Object other) => switch (other) {
        int otherValue => value < otherValue,
        Uint64 otherValue => value < otherValue.value,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  bool operator <=(Object other) => switch (other) {
        int otherValue => value <= otherValue,
        Uint64 otherValue => value <= otherValue.value,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  bool operator >(Object other) => switch (other) {
        int otherValue => value > otherValue,
        Uint64 otherValue => value > otherValue.value,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  bool operator >=(Object other) => switch (other) {
        int otherValue => value >= otherValue,
        Uint64 otherValue => value >= otherValue.value,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  int toInt() => value;

  @override
  bool operator ==(Object other) =>
      identical(this, other) || other is Uint64 && other.value == value;

  @override
  int get hashCode => value.hashCode;

  @override
  String toString() => value.toString();
}
