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

import 'dart:collection';
import 'dart:typed_data';

const int _float16ImplicitMantissaBit = 0x0010000000000000;
const int _float16RoundIncrement = 0x0000020000000000;
const int _float16MantissaDivisor = 0x0000040000000000;

/// Half-precision floating-point wrapper used by the xlang type system.
///
/// [Float16] stores an IEEE 754 binary16 payload exactly. Constructing from a
/// Dart number rounds to the closest representable binary16 value, and the
/// arithmetic operators round every result back to binary16 precision before
/// returning a new wrapper.
final class Float16 implements Comparable<Float16> {
  final int _bits;

  /// Creates a value directly from IEEE 754 binary16 bits.
  const Float16.fromBits(int bits) : _bits = bits & 0xffff;

  /// Converts [value] to the closest representable binary16 value.
  factory Float16(num value) => Float16.fromDouble(value.toDouble());

  /// Converts [value] to the closest representable binary16 value.
  factory Float16.fromDouble(double value) {
    if (value.isNaN) {
      return const Float16.fromBits(0x7e00);
    }
    final data = ByteData(8)..setFloat64(0, value, Endian.little);
    final low32 = data.getUint32(0, Endian.little);
    final high32 = data.getUint32(4, Endian.little);
    final sign = (high32 >>> 31) & 0x1;
    final exponent = (high32 >>> 20) & 0x7ff;
    final mantissa = ((high32 & 0x000fffff) * 0x100000000) + low32;

    if (exponent == 0x7ff) {
      return Float16.fromBits((sign << 15) | 0x7c00);
    }

    final adjustedExponent = exponent - 1023 + 15;
    if (adjustedExponent >= 0x1f) {
      return Float16.fromBits((sign << 15) | 0x7c00);
    }
    if (adjustedExponent <= 0) {
      if (adjustedExponent < -10) {
        return Float16.fromBits(sign << 15);
      }
      final shifted = (mantissa + _float16ImplicitMantissaBit) ~/
          _pow2Int(43 - adjustedExponent);
      final rounded = (shifted + 1) ~/ 2;
      return Float16.fromBits((sign << 15) | rounded);
    }

    var roundedMantissa = mantissa + _float16RoundIncrement;
    var roundedExponent = adjustedExponent;
    if (roundedMantissa >= _float16ImplicitMantissaBit) {
      roundedMantissa = 0;
      roundedExponent += 1;
      if (roundedExponent >= 0x1f) {
        return Float16.fromBits((sign << 15) | 0x7c00);
      }
    }
    return Float16.fromBits(
      (sign << 15) |
          (roundedExponent << 10) |
          (roundedMantissa ~/ _float16MantissaDivisor),
    );
  }

  /// Returns the raw IEEE 754 binary16 bits for this value.
  int toBits() => _bits;

  /// Expands this binary16 value to a Dart [double].
  double toDouble() {
    final sign = (_bits >> 15) & 0x1;
    final exponent = (_bits >> 10) & 0x1f;
    final mantissa = _bits & 0x03ff;
    if (exponent == 0) {
      if (mantissa == 0) {
        return sign == 0 ? 0.0 : -0.0;
      }
      final value = mantissa / 1024.0 * 0.00006103515625;
      return sign == 0 ? value : -value;
    }
    if (exponent == 0x1f) {
      return mantissa == 0
          ? (sign == 0 ? double.infinity : double.negativeInfinity)
          : double.nan;
    }
    final exponentValue = exponent - 15;
    final scale = exponentValue >= 0
        ? (1 << exponentValue).toDouble()
        : 1.0 / (1 << -exponentValue).toDouble();
    final value = (1.0 + mantissa / 1024.0) * scale;
    return sign == 0 ? value : -value;
  }

  /// Returns the rounded binary16 value as a Dart [double].
  double get value => toDouble();

  /// Returns a binary16-rounded sum.
  Float16 operator +(Object other) => switch (other) {
        Float16 otherValue => Float16(value + otherValue.value),
        num otherValue => Float16(value + otherValue.toDouble()),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected a num or Float16.'),
      };

  /// Returns a binary16-rounded difference.
  Float16 operator -(Object other) => switch (other) {
        Float16 otherValue => Float16(value - otherValue.value),
        num otherValue => Float16(value - otherValue.toDouble()),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected a num or Float16.'),
      };

  /// Returns a binary16-rounded product.
  Float16 operator *(Object other) => switch (other) {
        Float16 otherValue => Float16(value * otherValue.value),
        num otherValue => Float16(value * otherValue.toDouble()),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected a num or Float16.'),
      };

  /// Returns a binary16-rounded quotient.
  Float16 operator /(Object other) => switch (other) {
        Float16 otherValue => Float16(value / otherValue.value),
        num otherValue => Float16(value / otherValue.toDouble()),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected a num or Float16.'),
      };

  /// Returns a binary16-rounded remainder.
  Float16 operator %(Object other) => switch (other) {
        Float16 otherValue => Float16(value % otherValue.value),
        num otherValue => Float16(value % otherValue.toDouble()),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected a num or Float16.'),
      };

  /// Returns the truncated integer quotient, matching Dart `num` semantics.
  int operator ~/(Object other) => switch (other) {
        Float16 otherValue => value ~/ otherValue.value,
        num otherValue => value ~/ otherValue.toDouble(),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected a num or Float16.'),
      };

  /// Returns the negated binary16-rounded value.
  Float16 operator -() => Float16(-value);

  /// Returns whether this value is strictly less than [other].
  bool operator <(Object other) => switch (other) {
        Float16 otherValue => value < otherValue.value,
        num otherValue => value < otherValue.toDouble(),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected a num or Float16.'),
      };

  /// Returns whether this value is less than or equal to [other].
  bool operator <=(Object other) => switch (other) {
        Float16 otherValue => value <= otherValue.value,
        num otherValue => value <= otherValue.toDouble(),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected a num or Float16.'),
      };

  /// Returns whether this value is strictly greater than [other].
  bool operator >(Object other) => switch (other) {
        Float16 otherValue => value > otherValue.value,
        num otherValue => value > otherValue.toDouble(),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected a num or Float16.'),
      };

  /// Returns whether this value is greater than or equal to [other].
  bool operator >=(Object other) => switch (other) {
        Float16 otherValue => value >= otherValue.value,
        num otherValue => value >= otherValue.toDouble(),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected a num or Float16.'),
      };

  @override
  bool operator ==(Object other) =>
      identical(this, other) || other is Float16 && other._bits == _bits;

  @override
  int get hashCode => _bits.hashCode;

  /// Compares this value with [other] after expanding both to Dart [double]s.
  @override
  int compareTo(Float16 other) => value.compareTo(other.value);

  /// Returns the expanded Dart [double] string form of this value.
  @override
  String toString() => value.toString();
}

int _pow2Int(int exponent) {
  var result = 1;
  for (var index = 0; index < exponent; index += 1) {
    result *= 2;
  }
  return result;
}

/// Fixed-length contiguous storage for [Float16] values.
///
/// [Float16List] behaves like a typed-data-style list whose elements are
/// [Float16] wrappers instead of Dart [double]s. Each element occupies exactly
/// two bytes, backed by a contiguous [ByteBuffer], so serializers can write or
/// read the list without repacking each element.
final class Float16List extends ListBase<Float16> {
  /// The number of bytes used by one [Float16] element.
  static const int bytesPerElement = Uint16List.bytesPerElement;

  final Uint16List _storage;

  /// Creates a zero-initialized list with [length] binary16 elements.
  Float16List(int length) : _storage = Uint16List(length);

  Float16List._(this._storage);

  /// Copies [values] into a new contiguous binary16 list.
  factory Float16List.fromList(Iterable<Float16> values) {
    final copied = values.toList(growable: false);
    final result = Float16List(copied.length);
    for (var index = 0; index < copied.length; index += 1) {
      result[index] = copied[index];
    }
    return result;
  }

  /// Creates a zero-copy view over [buffer].
  ///
  /// [offsetInBytes] must be aligned to [bytesPerElement]. When [length] is
  /// omitted, the view spans the remaining aligned binary16 values.
  factory Float16List.view(
    ByteBuffer buffer, [
    int offsetInBytes = 0,
    int? length,
  ]) {
    return Float16List._(Uint16List.view(buffer, offsetInBytes, length));
  }

  /// Creates a zero-copy element-range view of [data].
  ///
  /// [start] and [end] are measured in [Float16] elements, not bytes.
  factory Float16List.sublistView(TypedData data, [int start = 0, int? end]) {
    if (data.lengthInBytes % bytesPerElement != 0) {
      throw ArgumentError.value(
        data,
        'data',
        'Expected typed data aligned to $bytesPerElement-byte elements.',
      );
    }
    final elementLength = data.lengthInBytes ~/ bytesPerElement;
    final actualEnd = RangeError.checkValidRange(start, end, elementLength);
    return Float16List.view(
      data.buffer,
      data.offsetInBytes + start * bytesPerElement,
      actualEnd - start,
    );
  }

  /// Returns the shared backing buffer for this list.
  ByteBuffer get buffer => _storage.buffer;

  /// Returns the size in bytes of each [Float16] element.
  int get elementSizeInBytes => bytesPerElement;

  /// Returns the byte length of this list view.
  int get lengthInBytes => _storage.lengthInBytes;

  /// Returns the byte offset of this view in [buffer].
  int get offsetInBytes => _storage.offsetInBytes;

  /// Returns the number of [Float16] elements in this fixed-length list.
  @override
  int get length => _storage.length;

  /// Throws because [Float16List] has a fixed length.
  @override
  set length(int newLength) {
    throw UnsupportedError('Float16List has a fixed length.');
  }

  /// Reads the element at [index].
  @override
  Float16 operator [](int index) => Float16.fromBits(_storage[index]);

  /// Stores [value] at [index].
  @override
  void operator []=(int index, Float16 value) {
    _storage[index] = value.toBits();
  }
}
