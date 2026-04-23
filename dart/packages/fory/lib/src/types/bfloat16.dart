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

const int _bfloat16ImplicitMantissaBit = 0x0010000000000000;
const int _bfloat16RoundIncrement = 0x0000100000000000;
const int _bfloat16MantissaDivisor = 0x0000200000000000;

/// Brain floating-point wrapper used by the xlang type system.
///
/// [Bfloat16] stores an IEEE 754 bfloat16 payload exactly. Constructing from a
/// Dart number rounds directly from the incoming IEEE 754 binary64 value to the
/// closest representable bfloat16 value using round-to-nearest-even semantics.
final class Bfloat16 implements Comparable<Bfloat16> {
  final int _bits;

  /// Creates a value directly from IEEE 754 bfloat16 bits.
  const Bfloat16.fromBits(int bits) : _bits = bits & 0xffff;

  /// Converts [value] to the closest representable bfloat16 value.
  factory Bfloat16(num value) => Bfloat16.fromDouble(value.toDouble());

  /// Converts [value] to the closest representable bfloat16 value.
  factory Bfloat16.fromDouble(double value) {
    final data = ByteData(8)..setFloat64(0, value, Endian.little);
    final low32 = data.getUint32(0, Endian.little);
    final high32 = data.getUint32(4, Endian.little);
    final sign = (high32 >>> 31) & 0x1;
    final exponent = (high32 >>> 20) & 0x7ff;
    final mantissa = ((high32 & 0x000fffff) * 0x100000000) + low32;

    if (exponent == 0x7ff) {
      if (mantissa == 0) {
        return Bfloat16.fromBits((sign << 15) | 0x7f80);
      }
      var payload = (mantissa ~/ _bfloat16MantissaDivisor) & 0x7f;
      if (payload == 0) {
        payload = 0x40;
      } else {
        payload |= 0x40;
      }
      return Bfloat16.fromBits((sign << 15) | 0x7f80 | payload);
    }

    final adjustedExponent = exponent - 1023 + 127;
    if (adjustedExponent >= 0xff) {
      return Bfloat16.fromBits((sign << 15) | 0x7f80);
    }
    if (adjustedExponent <= 0) {
      if (adjustedExponent < -7) {
        return Bfloat16.fromBits(sign << 15);
      }
      final shifted = (mantissa + _bfloat16ImplicitMantissaBit) ~/
          _pow2Int(46 - adjustedExponent);
      final rounded = (shifted + 1) ~/ 2;
      return Bfloat16.fromBits((sign << 15) | rounded);
    }

    var roundedMantissa = mantissa + _bfloat16RoundIncrement;
    var roundedExponent = adjustedExponent;
    if (roundedMantissa >= _bfloat16ImplicitMantissaBit) {
      roundedMantissa = 0;
      roundedExponent += 1;
      if (roundedExponent >= 0xff) {
        return Bfloat16.fromBits((sign << 15) | 0x7f80);
      }
    }
    return Bfloat16.fromBits(
      (sign << 15) |
          (roundedExponent << 7) |
          (roundedMantissa ~/ _bfloat16MantissaDivisor),
    );
  }

  /// Returns the raw IEEE 754 bfloat16 bits for this value.
  int toBits() => _bits;

  /// Expands this bfloat16 value to a Dart [double].
  double toDouble() {
    final data = ByteData(4)..setUint32(0, _bits << 16, Endian.little);
    return data.getFloat32(0, Endian.little);
  }

  /// Returns the rounded bfloat16 value as a Dart [double].
  double get value => toDouble();

  /// Returns a bfloat16-rounded sum.
  Bfloat16 operator +(Object other) => switch (other) {
        Bfloat16 otherValue => Bfloat16(value + otherValue.value),
        num otherValue => Bfloat16(value + otherValue.toDouble()),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected a num or Bfloat16.'),
      };

  /// Returns a bfloat16-rounded difference.
  Bfloat16 operator -(Object other) => switch (other) {
        Bfloat16 otherValue => Bfloat16(value - otherValue.value),
        num otherValue => Bfloat16(value - otherValue.toDouble()),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected a num or Bfloat16.'),
      };

  /// Returns a bfloat16-rounded product.
  Bfloat16 operator *(Object other) => switch (other) {
        Bfloat16 otherValue => Bfloat16(value * otherValue.value),
        num otherValue => Bfloat16(value * otherValue.toDouble()),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected a num or Bfloat16.'),
      };

  /// Returns a bfloat16-rounded quotient.
  Bfloat16 operator /(Object other) => switch (other) {
        Bfloat16 otherValue => Bfloat16(value / otherValue.value),
        num otherValue => Bfloat16(value / otherValue.toDouble()),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected a num or Bfloat16.'),
      };

  /// Returns a bfloat16-rounded remainder.
  Bfloat16 operator %(Object other) => switch (other) {
        Bfloat16 otherValue => Bfloat16(value % otherValue.value),
        num otherValue => Bfloat16(value % otherValue.toDouble()),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected a num or Bfloat16.'),
      };

  /// Returns the truncated integer quotient, matching Dart `num` semantics.
  int operator ~/(Object other) => switch (other) {
        Bfloat16 otherValue => value ~/ otherValue.value,
        num otherValue => value ~/ otherValue.toDouble(),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected a num or Bfloat16.'),
      };

  /// Returns the negated bfloat16-rounded value.
  Bfloat16 operator -() => Bfloat16(-value);

  /// Returns whether this value is strictly less than [other].
  bool operator <(Object other) => switch (other) {
        Bfloat16 otherValue => value < otherValue.value,
        num otherValue => value < otherValue.toDouble(),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected a num or Bfloat16.'),
      };

  /// Returns whether this value is less than or equal to [other].
  bool operator <=(Object other) => switch (other) {
        Bfloat16 otherValue => value <= otherValue.value,
        num otherValue => value <= otherValue.toDouble(),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected a num or Bfloat16.'),
      };

  /// Returns whether this value is strictly greater than [other].
  bool operator >(Object other) => switch (other) {
        Bfloat16 otherValue => value > otherValue.value,
        num otherValue => value > otherValue.toDouble(),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected a num or Bfloat16.'),
      };

  /// Returns whether this value is greater than or equal to [other].
  bool operator >=(Object other) => switch (other) {
        Bfloat16 otherValue => value >= otherValue.value,
        num otherValue => value >= otherValue.toDouble(),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected a num or Bfloat16.'),
      };

  @override
  bool operator ==(Object other) =>
      identical(this, other) || other is Bfloat16 && other._bits == _bits;

  @override
  int get hashCode => _bits.hashCode;

  /// Compares this value with [other] after expanding both to Dart [double]s.
  @override
  int compareTo(Bfloat16 other) => value.compareTo(other.value);

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

/// Fixed-length contiguous storage for [Bfloat16] values.
///
/// [Bfloat16List] behaves like a typed-data-style list whose elements are
/// [Bfloat16] wrappers instead of Dart [double]s. Each element occupies
/// exactly two bytes, backed by a contiguous [ByteBuffer], so serializers can
/// process the list as a single dense binary payload.
final class Bfloat16List extends ListBase<Bfloat16> {
  /// The number of bytes used by one [Bfloat16] element.
  static const int bytesPerElement = Uint16List.bytesPerElement;

  final Uint16List _storage;

  /// Creates a zero-initialized list with [length] bfloat16 elements.
  Bfloat16List(int length) : _storage = Uint16List(length);

  Bfloat16List._(this._storage);

  /// Copies [values] into a new contiguous bfloat16 list.
  factory Bfloat16List.fromList(Iterable<Bfloat16> values) {
    final copied = values.toList(growable: false);
    final result = Bfloat16List(copied.length);
    for (var index = 0; index < copied.length; index += 1) {
      result[index] = copied[index];
    }
    return result;
  }

  /// Creates a zero-copy view over [buffer].
  ///
  /// [offsetInBytes] must be aligned to [bytesPerElement]. When [length] is
  /// omitted, the view spans the remaining aligned bfloat16 values.
  factory Bfloat16List.view(
    ByteBuffer buffer, [
    int offsetInBytes = 0,
    int? length,
  ]) {
    return Bfloat16List._(Uint16List.view(buffer, offsetInBytes, length));
  }

  /// Creates a zero-copy element-range view of [data].
  ///
  /// [start] and [end] are measured in [Bfloat16] elements, not bytes.
  factory Bfloat16List.sublistView(TypedData data, [int start = 0, int? end]) {
    if (data.lengthInBytes % bytesPerElement != 0) {
      throw ArgumentError.value(
        data,
        'data',
        'Expected typed data aligned to $bytesPerElement-byte elements.',
      );
    }
    final elementLength = data.lengthInBytes ~/ bytesPerElement;
    final actualEnd = RangeError.checkValidRange(start, end, elementLength);
    return Bfloat16List.view(
      data.buffer,
      data.offsetInBytes + start * bytesPerElement,
      actualEnd - start,
    );
  }

  /// Returns the shared backing buffer for this list.
  ByteBuffer get buffer => _storage.buffer;

  /// Returns the size in bytes of each [Bfloat16] element.
  int get elementSizeInBytes => bytesPerElement;

  /// Returns the byte length of this list view.
  int get lengthInBytes => _storage.lengthInBytes;

  /// Returns the byte offset of this view in [buffer].
  int get offsetInBytes => _storage.offsetInBytes;

  /// Returns the number of [Bfloat16] elements in this fixed-length list.
  @override
  int get length => _storage.length;

  /// Throws because [Bfloat16List] has a fixed length.
  @override
  set length(int newLength) {
    throw UnsupportedError('Bfloat16List has a fixed length.');
  }

  /// Reads the element at [index].
  @override
  Bfloat16 operator [](int index) => Bfloat16.fromBits(_storage[index]);

  /// Stores [value] at [index].
  @override
  void operator []=(int index, Bfloat16 value) {
    _storage[index] = value.toBits();
  }
}
