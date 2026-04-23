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
import 'dart:typed_data' as td;

const int _uint64SegmentBits = 22;
const int _uint64SegmentMask = (1 << _uint64SegmentBits) - 1;
const int _uint64SegmentBase = 1 << _uint64SegmentBits;
const int _uint64HighBits = 20;
const int _uint64HighMask = (1 << _uint64HighBits) - 1;
const int _uint64SafeHighMax = 0x1ff;
const int _uint64HighValueBase = 17592186044416;
const int _uint64WordBase = 0x100000000;
final BigInt _uint64Mask64Big = (BigInt.one << 64) - BigInt.one;
final BigInt _uint64Mask32Big = (BigInt.one << 32) - BigInt.one;

/// Unsigned 64-bit integer wrapper used by the xlang type system on web builds.
final class Uint64 implements Comparable<Uint64> {
  final int _l;
  final int _m;
  final int _h;

  const Uint64._parts(this._l, this._m, this._h);

  /// Creates an unsigned 64-bit value by truncating [value] to 64 bits.
  factory Uint64(int value) {
    final low32 = value.toUnsigned(32);
    final high32 = (value - low32) ~/ _uint64WordBase;
    return Uint64.fromWords(low32, high32);
  }

  /// Creates an unsigned 64-bit value by truncating [value] to 64 bits.
  factory Uint64.fromBigInt(BigInt value) {
    final normalized = value & _uint64Mask64Big;
    return Uint64.fromWords(
      (normalized & _uint64Mask32Big).toInt(),
      ((normalized >> 32) & _uint64Mask32Big).toInt(),
    );
  }

  /// Creates an unsigned 64-bit value from little-endian 32-bit words.
  factory Uint64.fromWords(int low32, int high32) {
    final normalizedLow32 = low32.toUnsigned(32);
    final normalizedHigh32 = high32.toUnsigned(32);
    return Uint64._parts(
      normalizedLow32 & _uint64SegmentMask,
      ((normalizedLow32 >>> _uint64SegmentBits) |
              ((normalizedHigh32 & 0xfff) << 10)) &
          _uint64SegmentMask,
      (normalizedHigh32 >>> 12) & _uint64HighMask,
    );
  }

  /// Parses a hexadecimal payload.
  factory Uint64.parseHex(String value) =>
      Uint64.fromBigInt(BigInt.parse(value, radix: 16));

  /// Returns whether this value is zero.
  bool get isZero => _l == 0 && _m == 0 && _h == 0;

  /// Returns the low 32 bits as an unsigned integer.
  int get low32 => _l | ((_m & 0x3ff) << 22);

  /// Returns the high 32 bits as an unsigned integer.
  int get high32Unsigned => ((_m >>> 10) & 0xfff) | (_h << 12);

  /// Returns the exact value as a [BigInt].
  BigInt toBigInt() => (BigInt.from(high32Unsigned) << 32) | BigInt.from(low32);

  /// Returns this value as a Dart [int] when it is JS-safe.
  int toInt() {
    if (_h > _uint64SafeHighMax) {
      throw StateError('Uint64 value ${toBigInt()} is not a JS-safe int.');
    }
    return _h * _uint64HighValueBase + _m * _uint64SegmentBase + _l;
  }

  @override
  int compareTo(Uint64 other) {
    if (_h != other._h) {
      return _h < other._h ? -1 : 1;
    }
    if (_m != other._m) {
      return _m < other._m ? -1 : 1;
    }
    if (_l != other._l) {
      return _l < other._l ? -1 : 1;
    }
    return 0;
  }

  Uint64 operator +(Object other) => switch (other) {
        int otherValue => _add(Uint64(otherValue)),
        Uint64 otherValue => _add(otherValue),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator -(Object other) => switch (other) {
        int otherValue => _subtract(Uint64(otherValue)),
        Uint64 otherValue => _subtract(otherValue),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator *(Object other) => switch (other) {
        int otherValue =>
          Uint64.fromBigInt(toBigInt() * BigInt.from(otherValue)),
        Uint64 otherValue =>
          Uint64.fromBigInt(toBigInt() * otherValue.toBigInt()),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator ~/(Object other) => switch (other) {
        int otherValue =>
          Uint64.fromBigInt(toBigInt() ~/ BigInt.from(otherValue)),
        Uint64 otherValue =>
          Uint64.fromBigInt(toBigInt() ~/ otherValue.toBigInt()),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator %(Object other) => switch (other) {
        int otherValue =>
          Uint64.fromBigInt(toBigInt().remainder(BigInt.from(otherValue))),
        Uint64 otherValue =>
          Uint64.fromBigInt(toBigInt().remainder(otherValue.toBigInt())),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  double operator /(Object other) => switch (other) {
        int otherValue => toBigInt().toDouble() / otherValue,
        Uint64 otherValue =>
          toBigInt().toDouble() / otherValue.toBigInt().toDouble(),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator -() => Uint64(0) - this;

  Uint64 operator ~() => Uint64._parts(
        _l ^ _uint64SegmentMask,
        _m ^ _uint64SegmentMask,
        _h ^ _uint64HighMask,
      );

  Uint64 operator &(Object other) => switch (other) {
        int otherValue => _bitwiseAnd(Uint64(otherValue)),
        Uint64 otherValue => _bitwiseAnd(otherValue),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator |(Object other) => switch (other) {
        int otherValue => _bitwiseOr(Uint64(otherValue)),
        Uint64 otherValue => _bitwiseOr(otherValue),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator ^(Object other) => switch (other) {
        int otherValue => _bitwiseXor(Uint64(otherValue)),
        Uint64 otherValue => _bitwiseXor(otherValue),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  Uint64 operator <<(int shift) {
    RangeError.checkNotNegative(shift, 'shift');
    if (shift == 0) {
      return this;
    }
    if (shift >= 64) {
      return Uint64(0);
    }
    if (shift < 22) {
      return Uint64._parts(
        (_l << shift) & _uint64SegmentMask,
        ((_m << shift) | (_l >>> (22 - shift))) & _uint64SegmentMask,
        (((_h << shift) | (_m >>> (22 - shift))) & _uint64HighMask),
      );
    }
    if (shift < 44) {
      final segmentShift = shift - 22;
      return Uint64._parts(
        0,
        (_l << segmentShift) & _uint64SegmentMask,
        (((_m << segmentShift) | (_l >>> (22 - segmentShift))) &
            _uint64HighMask),
      );
    }
    final segmentShift = shift - 44;
    return Uint64._parts(0, 0, (_l << segmentShift) & _uint64HighMask);
  }

  Uint64 operator >>(int shift) {
    RangeError.checkNotNegative(shift, 'shift');
    if (shift == 0) {
      return this;
    }
    if (shift >= 64) {
      return Uint64(0);
    }
    if (shift < 22) {
      return Uint64._parts(
        ((_l >>> shift) | ((_m & _uint64LowBitsMask(shift)) << (22 - shift))) &
            _uint64SegmentMask,
        ((_m >>> shift) | ((_h & _uint64LowBitsMask(shift)) << (22 - shift))) &
            _uint64SegmentMask,
        _h >>> shift,
      );
    }
    if (shift < 44) {
      final segmentShift = shift - 22;
      return Uint64._parts(
        ((_m >>> segmentShift) |
                ((_h & _uint64LowBitsMask(segmentShift)) <<
                    (22 - segmentShift))) &
            _uint64SegmentMask,
        _h >>> segmentShift,
        0,
      );
    }
    final segmentShift = shift - 44;
    return Uint64._parts(_h >>> segmentShift, 0, 0);
  }

  Uint64 operator >>>(int shift) => this >> shift;

  bool operator <(Object other) => switch (other) {
        int otherValue => compareTo(Uint64(otherValue)) < 0,
        Uint64 otherValue => compareTo(otherValue) < 0,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  bool operator <=(Object other) => switch (other) {
        int otherValue => compareTo(Uint64(otherValue)) <= 0,
        Uint64 otherValue => compareTo(otherValue) <= 0,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  bool operator >(Object other) => switch (other) {
        int otherValue => compareTo(Uint64(otherValue)) > 0,
        Uint64 otherValue => compareTo(otherValue) > 0,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  bool operator >=(Object other) => switch (other) {
        int otherValue => compareTo(Uint64(otherValue)) >= 0,
        Uint64 otherValue => compareTo(otherValue) >= 0,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Uint64.'),
      };

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is Uint64 && other._l == _l && other._m == _m && other._h == _h;

  @override
  int get hashCode => Object.hash(low32, high32Unsigned);

  @override
  String toString() => toBigInt().toString();

  Uint64 _add(Uint64 other) {
    var low = _l + other._l;
    var carry = low >> 22;
    low &= _uint64SegmentMask;
    var mid = _m + other._m + carry;
    carry = mid >> 22;
    mid &= _uint64SegmentMask;
    return Uint64._parts(low, mid, (_h + other._h + carry) & _uint64HighMask);
  }

  Uint64 _subtract(Uint64 other) {
    var low = _l - other._l;
    var borrow = 0;
    if (low < 0) {
      low += _uint64SegmentBase;
      borrow = 1;
    }
    var mid = _m - other._m - borrow;
    borrow = 0;
    if (mid < 0) {
      mid += _uint64SegmentBase;
      borrow = 1;
    }
    return Uint64._parts(low, mid, (_h - other._h - borrow) & _uint64HighMask);
  }

  Uint64 _bitwiseAnd(Uint64 other) => Uint64._parts(
        _l & other._l,
        _m & other._m,
        _h & other._h,
      );

  Uint64 _bitwiseOr(Uint64 other) => Uint64._parts(
        _l | other._l,
        _m | other._m,
        _h | other._h,
      );

  Uint64 _bitwiseXor(Uint64 other) => Uint64._parts(
        _l ^ other._l,
        _m ^ other._m,
        _h ^ other._h,
      );
}

/// Fixed-length contiguous storage for [Uint64] values on web builds.
final class Uint64List extends IterableBase<Uint64> {
  /// The number of bytes used by one [Uint64] element.
  static const int bytesPerElement = 8;

  final td.Uint8List _bytes;
  late final td.ByteData _view = td.ByteData.sublistView(_bytes);

  /// Creates a zero-initialized list with [length] unsigned 64-bit elements.
  Uint64List(int length) : _bytes = td.Uint8List(length * bytesPerElement);

  Uint64List._(this._bytes);

  /// Copies [values] into a new contiguous unsigned 64-bit list.
  factory Uint64List.fromList(Iterable<Object> values) {
    final copied = values.toList(growable: false);
    final result = Uint64List(copied.length);
    for (var index = 0; index < copied.length; index += 1) {
      result[index] = switch (copied[index]) {
        int value => Uint64(value),
        Uint64 value => value,
        _ => throw ArgumentError.value(
            copied[index],
            'values[$index]',
            'Expected an int or Uint64.',
          ),
      };
    }
    return result;
  }

  /// Creates a zero-copy view over [buffer].
  factory Uint64List.view(
    td.ByteBuffer buffer, [
    int offsetInBytes = 0,
    int? length,
  ]) {
    final byteLength = length == null
        ? buffer.lengthInBytes - offsetInBytes
        : length * bytesPerElement;
    return Uint64List._(td.Uint8List.view(buffer, offsetInBytes, byteLength));
  }

  /// Creates a zero-copy element-range view of [data].
  factory Uint64List.sublistView(td.TypedData data, [int start = 0, int? end]) {
    final elementCount = data.lengthInBytes ~/ data.elementSizeInBytes;
    final endIndex = RangeError.checkValidRange(start, end, elementCount);
    final byteOffset = data.offsetInBytes + start * data.elementSizeInBytes;
    final byteLength = (endIndex - start) * data.elementSizeInBytes;
    if (byteOffset % bytesPerElement != 0 ||
        byteLength % bytesPerElement != 0) {
      throw ArgumentError(
        'The selected byte range must align to $bytesPerElement-byte '
        'Uint64 elements.',
      );
    }
    return Uint64List.view(
      data.buffer,
      byteOffset,
      byteLength ~/ bytesPerElement,
    );
  }

  /// The number of elements in this list.
  @override
  int get length => _bytes.lengthInBytes ~/ bytesPerElement;

  /// Returns the element at [index].
  Uint64 operator [](int index) {
    RangeError.checkValidIndex(index, this, 'index', length);
    final byteOffset = index * bytesPerElement;
    return Uint64.fromWords(
      _view.getUint32(byteOffset, td.Endian.little),
      _view.getUint32(byteOffset + 4, td.Endian.little),
    );
  }

  /// Stores [value] at [index].
  void operator []=(int index, Uint64 value) {
    RangeError.checkValidIndex(index, this, 'index', length);
    final byteOffset = index * bytesPerElement;
    _view.setUint32(byteOffset, value.low32, td.Endian.little);
    _view.setUint32(byteOffset + 4, value.high32Unsigned, td.Endian.little);
  }

  td.ByteBuffer get buffer => _bytes.buffer;

  int get elementSizeInBytes => bytesPerElement;

  int get offsetInBytes => _bytes.offsetInBytes;

  int get lengthInBytes => _bytes.lengthInBytes;

  @override
  Iterator<Uint64> get iterator =>
      Iterable<Uint64>.generate(length, (index) => this[index]).iterator;

  @override
  String toString() => toList().toString();
}

int _uint64LowBitsMask(int bits) {
  if (bits <= 0) {
    return 0;
  }
  return (1 << bits) - 1;
}
