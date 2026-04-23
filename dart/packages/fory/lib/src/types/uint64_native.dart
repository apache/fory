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

final BigInt _uint64Mask64Big = (BigInt.one << 64) - BigInt.one;
final BigInt _uint64SignBit64Big = BigInt.one << 63;

/// Unsigned 64-bit integer wrapper used by the xlang type system.
extension type Uint64._(int _value) implements int {
  /// Creates an unsigned 64-bit value by truncating [value] to 64 bits.
  factory Uint64(int value) => Uint64._(value.toSigned(64));

  /// Creates an unsigned 64-bit value by truncating [value] to 64 bits.
  factory Uint64.fromBigInt(BigInt value) =>
      Uint64._(_normalizeUnsigned64(value));

  /// Creates an unsigned 64-bit value from little-endian 32-bit words.
  factory Uint64.fromWords(int low32, int high32) {
    final normalizedLow32 = low32 & 0xffffffff;
    final normalizedHigh32 = high32 & 0xffffffff;
    return Uint64._(
      (((normalizedHigh32 << 32) | normalizedLow32).toSigned(64)),
    );
  }

  /// Parses a hexadecimal payload.
  factory Uint64.parseHex(String value) =>
      Uint64.fromBigInt(BigInt.parse(value, radix: 16));

  /// The normalized 64-bit bit pattern stored in a native [int].
  int get value => _value;

  /// Returns whether this value is zero.
  bool get isZero => _value == 0;

  /// Returns the low 32 bits as an unsigned integer.
  int get low32 => _value & 0xffffffff;

  /// Returns the high 32 bits as an unsigned integer.
  int get high32Unsigned => (_value >> 32) & 0xffffffff;

  /// Returns the exact value as a [BigInt].
  BigInt toBigInt() => _value >= 0
      ? BigInt.from(_value)
      : BigInt.from(_value) + (BigInt.one << 64);

  /// Returns this value as a native [int] when it is exactly representable.
  int toInt() {
    if (_value < 0) {
      final exact = toBigInt();
      throw StateError(
        'Uint64 value $exact is not representable as a native int.',
      );
    }
    return _value;
  }

  int compareTo(Uint64 other) {
    final highCompare = high32Unsigned.compareTo(other.high32Unsigned);
    if (highCompare != 0) {
      return highCompare;
    }
    return low32.compareTo(other.low32);
  }

  Uint64 operator +(Object other) => switch (other) {
        int otherValue => Uint64(_value + otherValue),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator -(Object other) => switch (other) {
        int otherValue => Uint64(_value - otherValue),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator *(Object other) => switch (other) {
        int otherValue => Uint64(_value * Uint64(otherValue).value),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator ~/(Object other) => switch (other) {
        int otherValue =>
          Uint64.fromBigInt(toBigInt() ~/ Uint64(otherValue).toBigInt()),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator %(Object other) => switch (other) {
        int otherValue => Uint64.fromBigInt(
            toBigInt().remainder(Uint64(otherValue).toBigInt())),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  double operator /(Object other) => switch (other) {
        int otherValue =>
          toBigInt().toDouble() / Uint64(otherValue).toBigInt().toDouble(),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator -() => Uint64(-_value);

  Uint64 operator ~() => Uint64(~_value);

  Uint64 operator &(Object other) => switch (other) {
        int otherValue => Uint64(_value & otherValue),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator |(Object other) => switch (other) {
        int otherValue => Uint64(_value | otherValue),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator ^(Object other) => switch (other) {
        int otherValue => Uint64(_value ^ otherValue),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator <<(int shift) => Uint64(_value << shift);

  Uint64 operator >>(int shift) => Uint64(_value >>> shift);

  Uint64 operator >>>(int shift) => Uint64(_value >>> shift);

  bool operator <(Object other) => switch (other) {
        int otherValue => compareTo(Uint64(otherValue)) < 0,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  bool operator <=(Object other) => switch (other) {
        int otherValue => compareTo(Uint64(otherValue)) <= 0,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  bool operator >(Object other) => switch (other) {
        int otherValue => compareTo(Uint64(otherValue)) > 0,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  bool operator >=(Object other) => switch (other) {
        int otherValue => compareTo(Uint64(otherValue)) >= 0,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };
}

/// Fixed-length contiguous storage for [Uint64] values on native platforms.
extension type Uint64List._(td.Uint64List _storage) implements td.Uint64List {
  /// The number of bytes used by one [Uint64] element.
  static const int bytesPerElement = td.Uint64List.bytesPerElement;

  /// Creates a zero-initialized list with [length] unsigned 64-bit elements.
  Uint64List(int length) : _storage = td.Uint64List(length);

  /// Copies [values] into a new contiguous unsigned 64-bit list.
  factory Uint64List.fromList(Iterable<Object> values) {
    final copied = values.toList(growable: false);
    final storage = td.Uint64List(copied.length);
    for (var index = 0; index < copied.length; index += 1) {
      storage[index] = switch (copied[index]) {
        int value => Uint64(value).value,
        _ => throw ArgumentError.value(
            copied[index],
            'values[$index]',
            'Expected an int or Uint64.',
          ),
      };
    }
    return Uint64List._(storage);
  }

  /// Creates a zero-copy view over [buffer].
  factory Uint64List.view(
    td.ByteBuffer buffer, [
    int offsetInBytes = 0,
    int? length,
  ]) {
    return Uint64List._(td.Uint64List.view(buffer, offsetInBytes, length));
  }

  /// Creates a zero-copy element-range view of [data].
  factory Uint64List.sublistView(td.TypedData data, [int start = 0, int? end]) {
    return Uint64List._(td.Uint64List.sublistView(data, start, end));
  }

  /// The number of elements in this list.
  int get length => _storage.length;

  /// Returns the element at [index].
  Uint64 operator [](int index) => Uint64(_storage[index]);

  /// Stores [value] at [index].
  void operator []=(int index, Uint64 value) {
    _storage[index] = value.value;
  }

  td.ByteBuffer get buffer => _storage.buffer;

  int get elementSizeInBytes => _storage.elementSizeInBytes;

  int get offsetInBytes => _storage.offsetInBytes;

  int get lengthInBytes => _storage.lengthInBytes;

  Iterator<Uint64> get iterator =>
      Iterable<Uint64>.generate(length, (index) => this[index]).iterator;
}

int _normalizeUnsigned64(BigInt value) {
  var normalized = value & _uint64Mask64Big;
  if ((normalized & _uint64SignBit64Big) != BigInt.zero) {
    normalized -= BigInt.one << 64;
  }
  return normalized.toInt();
}
