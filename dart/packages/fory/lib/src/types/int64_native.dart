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

import 'dart:typed_data' as td;

/// Signed 64-bit integer wrapper used by the xlang type system.
extension type Int64._(int _value) implements int {
  /// Creates a signed 64-bit value by truncating [value] to 64 bits.
  factory Int64(int value) => Int64._(value.toSigned(64));

  /// Creates a signed 64-bit value by truncating [value] to 64 bits.
  factory Int64.fromBigInt(BigInt value) => Int64._(_normalizeSigned64(value));

  /// Creates a signed 64-bit value from little-endian 32-bit words.
  factory Int64.fromWords(int low32, int high32) => Int64._(
      ((high32 & 0xffffffff) << 32 | (low32 & 0xffffffff)).toSigned(64));

  /// Parses a hexadecimal two's-complement payload.
  factory Int64.parseHex(String value) =>
      Int64.fromBigInt(BigInt.parse(value, radix: 16));

  /// The normalized signed 64-bit value on native platforms.
  int get value => _value;

  /// Returns whether this value is negative.
  bool get isNegative => _value < 0;

  /// Returns whether this value is zero.
  bool get isZero => _value == 0;

  /// Returns the low 32 bits as an unsigned integer.
  int get low32 => _value & 0xffffffff;

  /// Returns the high 32 bits as an unsigned integer.
  int get high32Unsigned => (_value >> 32) & 0xffffffff;

  /// Returns the high 32 bits as a signed integer.
  int get high32Signed => _value >> 32;

  /// Returns the exact value as a [BigInt].
  BigInt toBigInt() => BigInt.from(_value);

  /// Returns the exact native [int] value.
  int toInt() => _value;

  int compareTo(Int64 other) => _value.compareTo(other._value);

  Int64 operator +(Object other) => switch (other) {
        int otherValue => Int64(_value + otherValue),
        _ => throw ArgumentError.value(
            other,
            'other',
            'Expected an int or Int64.',
          ),
      };

  Int64 operator -(Object other) => switch (other) {
        int otherValue => Int64(_value - otherValue),
        _ => throw ArgumentError.value(
            other,
            'other',
            'Expected an int or Int64.',
          ),
      };

  Int64 operator *(Object other) => switch (other) {
        int otherValue => Int64(_value * otherValue),
        _ => throw ArgumentError.value(
            other,
            'other',
            'Expected an int or Int64.',
          ),
      };

  Int64 operator ~/(Object other) => switch (other) {
        int otherValue => Int64(_value ~/ otherValue),
        _ => throw ArgumentError.value(
            other,
            'other',
            'Expected an int or Int64.',
          ),
      };

  Int64 operator %(Object other) => switch (other) {
        int otherValue => Int64(_value % otherValue),
        _ => throw ArgumentError.value(
            other,
            'other',
            'Expected an int or Int64.',
          ),
      };

  double operator /(Object other) => switch (other) {
        int otherValue => _value / otherValue,
        _ => throw ArgumentError.value(
            other,
            'other',
            'Expected an int or Int64.',
          ),
      };

  Int64 operator -() => Int64(-_value);

  Int64 operator ~() => Int64(~_value);

  Int64 operator &(Object other) => switch (other) {
        int otherValue => Int64(_value & otherValue),
        _ => throw ArgumentError.value(
            other,
            'other',
            'Expected an int or Int64.',
          ),
      };

  Int64 operator |(Object other) => switch (other) {
        int otherValue => Int64(_value | otherValue),
        _ => throw ArgumentError.value(
            other,
            'other',
            'Expected an int or Int64.',
          ),
      };

  Int64 operator ^(Object other) => switch (other) {
        int otherValue => Int64(_value ^ otherValue),
        _ => throw ArgumentError.value(
            other,
            'other',
            'Expected an int or Int64.',
          ),
      };

  Int64 operator <<(int shift) => Int64(_value << shift);

  Int64 operator >>(int shift) => Int64(_value >> shift);

  Int64 operator >>>(int shift) => Int64(_value >>> shift);

  bool operator <(Object other) => switch (other) {
        int otherValue => _value < otherValue,
        _ => throw ArgumentError.value(
            other,
            'other',
            'Expected an int or Int64.',
          ),
      };

  bool operator <=(Object other) => switch (other) {
        int otherValue => _value <= otherValue,
        _ => throw ArgumentError.value(
            other,
            'other',
            'Expected an int or Int64.',
          ),
      };

  bool operator >(Object other) => switch (other) {
        int otherValue => _value > otherValue,
        _ => throw ArgumentError.value(
            other,
            'other',
            'Expected an int or Int64.',
          ),
      };

  bool operator >=(Object other) => switch (other) {
        int otherValue => _value >= otherValue,
        _ => throw ArgumentError.value(
            other,
            'other',
            'Expected an int or Int64.',
          ),
      };
}

/// Fixed-length contiguous storage for [Int64] values on native platforms.
extension type Int64List._(td.Int64List _storage) implements td.Int64List {
  /// The number of bytes used by one [Int64] element.
  static const int bytesPerElement = td.Int64List.bytesPerElement;

  /// Creates a zero-initialized list with [length] signed 64-bit elements.
  Int64List(int length) : _storage = td.Int64List(length);

  /// Copies [values] into a new contiguous signed 64-bit list.
  factory Int64List.fromList(Iterable<Object> values) {
    final copied = values.toList(growable: false);
    final storage = td.Int64List(copied.length);
    for (var index = 0; index < copied.length; index += 1) {
      storage[index] = switch (copied[index]) {
        int value => Int64(value).value,
        _ => throw ArgumentError.value(
            copied[index],
            'values[$index]',
            'Expected an int or Int64.',
          ),
      };
    }
    return Int64List._(storage);
  }

  /// Creates a zero-copy view over [buffer].
  factory Int64List.view(
    td.ByteBuffer buffer, [
    int offsetInBytes = 0,
    int? length,
  ]) {
    return Int64List._(td.Int64List.view(buffer, offsetInBytes, length));
  }

  /// Creates a zero-copy element-range view of [data].
  factory Int64List.sublistView(td.TypedData data, [int start = 0, int? end]) {
    return Int64List._(td.Int64List.sublistView(data, start, end));
  }

  /// The number of elements in this list.
  int get length => _storage.length;

  /// Returns the element at [index].
  Int64 operator [](int index) => Int64(_storage[index]);

  /// Stores [value] at [index].
  void operator []=(int index, Int64 value) {
    _storage[index] = value.value;
  }

  td.ByteBuffer get buffer => _storage.buffer;

  int get elementSizeInBytes => _storage.elementSizeInBytes;

  int get offsetInBytes => _storage.offsetInBytes;

  int get lengthInBytes => _storage.lengthInBytes;

  Iterator<Int64> get iterator =>
      Iterable<Int64>.generate(length, (index) => this[index]).iterator;
}

int _normalizeSigned64(BigInt value) {
  final mask64 = (BigInt.one << 64) - BigInt.one;
  final signBit64 = BigInt.one << 63;
  var normalized = value & mask64;
  if ((normalized & signBit64) != BigInt.zero) {
    normalized -= BigInt.one << 64;
  }
  return normalized.toInt();
}
