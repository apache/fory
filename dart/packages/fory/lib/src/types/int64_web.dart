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

const int _int64SegmentBits = 22;
const int _int64SegmentMask = (1 << _int64SegmentBits) - 1;
const int _int64SegmentBase = 1 << _int64SegmentBits;
const int _int64HighBits = 20;
const int _int64HighMask = (1 << _int64HighBits) - 1;
const int _int64HighBase = 1 << _int64HighBits;
const int _int64HighSignBit = 1 << (_int64HighBits - 1);
const int _int64SafeHighMax = 0x1ff;
const int _int64SafeHighMin = -0x200;
const int _int64HighValueBase = 17592186044416;
const int _int64WordBase = 0x100000000;
final BigInt _int64Mask64Big = (BigInt.one << 64) - BigInt.one;
final BigInt _int64Mask32Big = (BigInt.one << 32) - BigInt.one;
final BigInt _int64SignBit64Big = BigInt.one << 63;

/// Signed 64-bit integer wrapper used by the xlang type system on web builds.
final class Int64 implements Comparable<Int64> {
  final int _l;
  final int _m;
  final int _h;

  const Int64._parts(this._l, this._m, this._h);

  /// Creates a signed 64-bit value by truncating [value] to 64 bits.
  factory Int64(int value) {
    final low32 = value.toUnsigned(32);
    final high32 = (value - low32) ~/ _int64WordBase;
    return Int64.fromWords(low32, high32);
  }

  /// Creates a signed 64-bit value by truncating [value] to 64 bits.
  factory Int64.fromBigInt(BigInt value) {
    var normalized = value & _int64Mask64Big;
    if ((normalized & _int64SignBit64Big) != BigInt.zero) {
      normalized -= BigInt.one << 64;
    }
    final unsigned =
        normalized.isNegative ? normalized + (BigInt.one << 64) : normalized;
    return Int64.fromWords(
      (unsigned & _int64Mask32Big).toInt(),
      ((unsigned >> 32) & _int64Mask32Big).toInt(),
    );
  }

  /// Creates a signed 64-bit value from little-endian 32-bit words.
  factory Int64.fromWords(int low32, int high32) {
    final normalizedLow32 = low32.toUnsigned(32);
    final normalizedHigh32 = high32.toSigned(32);
    return Int64._parts(
      normalizedLow32 & _int64SegmentMask,
      ((normalizedLow32 >>> _int64SegmentBits) |
              ((normalizedHigh32 & 0xfff) << 10)) &
          _int64SegmentMask,
      _normalizeInt64High(normalizedHigh32 >> 12),
    );
  }

  /// Parses a hexadecimal two's-complement payload.
  factory Int64.parseHex(String value) =>
      Int64.fromBigInt(BigInt.parse(value, radix: 16));

  /// Returns whether this value is negative.
  bool get isNegative => _h < 0;

  /// Returns whether this value is zero.
  bool get isZero => _l == 0 && _m == 0 && _h == 0;

  /// Returns the low 32 bits as an unsigned integer.
  int get low32 => _l | ((_m & 0x3ff) << 22);

  /// Returns the high 32 bits as an unsigned integer.
  int get high32Unsigned =>
      ((_m >>> 10) & 0xfff) | ((_h & _int64HighMask) << 12);

  /// Returns the high 32 bits as a signed integer.
  int get high32Signed => high32Unsigned.toSigned(32);

  /// Returns the exact value as a [BigInt].
  BigInt toBigInt() {
    final unsigned = (BigInt.from(high32Unsigned) << 32) | BigInt.from(low32);
    return isNegative ? unsigned - (BigInt.one << 64) : unsigned;
  }

  /// Returns this value as a Dart [int] when it is JS-safe.
  int toInt() {
    if (_h > _int64SafeHighMax ||
        _h < _int64SafeHighMin ||
        (_h == _int64SafeHighMin && _m == 0 && _l == 0)) {
      throw StateError('Int64 value ${toBigInt()} is not a JS-safe int.');
    }
    return _h * _int64HighValueBase + _m * _int64SegmentBase + _l;
  }

  @override
  int compareTo(Int64 other) {
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

  Int64 operator +(Object other) => switch (other) {
        int otherValue => _add(Int64(otherValue)),
        Int64 otherValue => _add(otherValue),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Int64.'),
      };

  Int64 operator -(Object other) => switch (other) {
        int otherValue => _subtract(Int64(otherValue)),
        Int64 otherValue => _subtract(otherValue),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Int64.'),
      };

  Int64 operator *(Object other) => switch (other) {
        int otherValue =>
          Int64.fromBigInt(toBigInt() * BigInt.from(otherValue)),
        Int64 otherValue =>
          Int64.fromBigInt(toBigInt() * otherValue.toBigInt()),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Int64.'),
      };

  Int64 operator ~/(Object other) => switch (other) {
        int otherValue =>
          Int64.fromBigInt(toBigInt() ~/ BigInt.from(otherValue)),
        Int64 otherValue =>
          Int64.fromBigInt(toBigInt() ~/ otherValue.toBigInt()),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Int64.'),
      };

  Int64 operator %(Object other) => switch (other) {
        int otherValue =>
          Int64.fromBigInt(toBigInt() % BigInt.from(otherValue)),
        Int64 otherValue =>
          Int64.fromBigInt(toBigInt() % otherValue.toBigInt()),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Int64.'),
      };

  double operator /(Object other) => switch (other) {
        int otherValue => toBigInt().toDouble() / otherValue,
        Int64 otherValue =>
          toBigInt().toDouble() / otherValue.toBigInt().toDouble(),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Int64.'),
      };

  Int64 operator -() => Int64(0) - this;

  Int64 operator ~() => Int64._parts(
      _l ^ _int64SegmentMask, _m ^ _int64SegmentMask, _normalizeInt64High(~_h));

  Int64 operator &(Object other) => switch (other) {
        int otherValue => _bitwiseAnd(Int64(otherValue)),
        Int64 otherValue => _bitwiseAnd(otherValue),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Int64.'),
      };

  Int64 operator |(Object other) => switch (other) {
        int otherValue => _bitwiseOr(Int64(otherValue)),
        Int64 otherValue => _bitwiseOr(otherValue),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Int64.'),
      };

  Int64 operator ^(Object other) => switch (other) {
        int otherValue => _bitwiseXor(Int64(otherValue)),
        Int64 otherValue => _bitwiseXor(otherValue),
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Int64.'),
      };

  Int64 operator <<(int shift) {
    RangeError.checkNotNegative(shift, 'shift');
    if (shift == 0) {
      return this;
    }
    if (shift >= 64) {
      return Int64(0);
    }
    final highBits = _h & _int64HighMask;
    if (shift < 22) {
      return Int64._parts(
        (_l << shift) & _int64SegmentMask,
        ((_m << shift) | (_l >>> (22 - shift))) & _int64SegmentMask,
        _normalizeInt64High((highBits << shift) | (_m >>> (22 - shift))),
      );
    }
    if (shift < 44) {
      final segmentShift = shift - 22;
      return Int64._parts(
        0,
        (_l << segmentShift) & _int64SegmentMask,
        _normalizeInt64High(
          (_m << segmentShift) | (_l >>> (22 - segmentShift)),
        ),
      );
    }
    final segmentShift = shift - 44;
    return Int64._parts(
      0,
      0,
      _normalizeInt64High(_l << segmentShift),
    );
  }

  Int64 operator >>(int shift) {
    RangeError.checkNotNegative(shift, 'shift');
    if (shift == 0) {
      return this;
    }
    if (shift >= 64) {
      return _h < 0 ? Int64(-1) : Int64(0);
    }
    if (shift < 22) {
      return Int64._parts(
        ((_l >>> shift) | ((_m & _int64LowBitsMask(shift)) << (22 - shift))) &
            _int64SegmentMask,
        ((_m >>> shift) | ((_h & _int64LowBitsMask(shift)) << (22 - shift))) &
            _int64SegmentMask,
        _normalizeInt64High(_h >> shift),
      );
    }
    if (shift < 44) {
      final segmentShift = shift - 22;
      return Int64._parts(
        ((_m >>> segmentShift) |
                ((_h & _int64LowBitsMask(segmentShift)) <<
                    (22 - segmentShift))) &
            _int64SegmentMask,
        (_h >> segmentShift) & _int64SegmentMask,
        _h < 0 ? -1 : 0,
      );
    }
    final segmentShift = shift - 44;
    return Int64._parts(
      (_h >> segmentShift) & _int64SegmentMask,
      _h < 0 ? _int64SegmentMask : 0,
      _h < 0 ? -1 : 0,
    );
  }

  Int64 operator >>>(int shift) {
    RangeError.checkNotNegative(shift, 'shift');
    if (shift == 0) {
      return this;
    }
    if (shift >= 64) {
      return Int64(0);
    }
    return _logicalShiftRight(shift);
  }

  bool operator <(Object other) => switch (other) {
        int otherValue => compareTo(Int64(otherValue)) < 0,
        Int64 otherValue => compareTo(otherValue) < 0,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Int64.'),
      };

  bool operator <=(Object other) => switch (other) {
        int otherValue => compareTo(Int64(otherValue)) <= 0,
        Int64 otherValue => compareTo(otherValue) <= 0,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Int64.'),
      };

  bool operator >(Object other) => switch (other) {
        int otherValue => compareTo(Int64(otherValue)) > 0,
        Int64 otherValue => compareTo(otherValue) > 0,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Int64.'),
      };

  bool operator >=(Object other) => switch (other) {
        int otherValue => compareTo(Int64(otherValue)) >= 0,
        Int64 otherValue => compareTo(otherValue) >= 0,
        _ => throw ArgumentError.value(
            other, 'other', 'Expected an int or Int64.'),
      };

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is Int64 && other._l == _l && other._m == _m && other._h == _h;

  @override
  int get hashCode => Object.hash(low32, high32Signed);

  @override
  String toString() => toBigInt().toString();

  Int64 _add(Int64 other) {
    var low = _l + other._l;
    var carry = low >> 22;
    low &= _int64SegmentMask;
    var mid = _m + other._m + carry;
    carry = mid >> 22;
    mid &= _int64SegmentMask;
    return Int64._parts(low, mid, _normalizeInt64High(_h + other._h + carry));
  }

  Int64 _subtract(Int64 other) {
    var low = _l - other._l;
    var borrow = 0;
    if (low < 0) {
      low += _int64SegmentBase;
      borrow = 1;
    }
    var mid = _m - other._m - borrow;
    borrow = 0;
    if (mid < 0) {
      mid += _int64SegmentBase;
      borrow = 1;
    }
    return Int64._parts(
      low,
      mid,
      _normalizeInt64High(_h - other._h - borrow),
    );
  }

  Int64 _bitwiseAnd(Int64 other) => Int64._parts(
        _l & other._l,
        _m & other._m,
        _normalizeInt64High(_h & other._h),
      );

  Int64 _bitwiseOr(Int64 other) => Int64._parts(
        _l | other._l,
        _m | other._m,
        _normalizeInt64High(_h | other._h),
      );

  Int64 _bitwiseXor(Int64 other) => Int64._parts(
        _l ^ other._l,
        _m ^ other._m,
        _normalizeInt64High(_h ^ other._h),
      );

  Int64 _logicalShiftRight(int shift) {
    final highBits = _h & _int64HighMask;
    if (shift < 22) {
      return Int64._parts(
        ((_l >>> shift) | ((_m & _int64LowBitsMask(shift)) << (22 - shift))) &
            _int64SegmentMask,
        ((_m >>> shift) |
                ((highBits & _int64LowBitsMask(shift)) << (22 - shift))) &
            _int64SegmentMask,
        highBits >>> shift,
      );
    }
    if (shift < 44) {
      final segmentShift = shift - 22;
      return Int64._parts(
        ((_m >>> segmentShift) |
                ((highBits & _int64LowBitsMask(segmentShift)) <<
                    (22 - segmentShift))) &
            _int64SegmentMask,
        highBits >>> segmentShift,
        0,
      );
    }
    final segmentShift = shift - 44;
    return Int64._parts(highBits >>> segmentShift, 0, 0);
  }
}

/// Fixed-length contiguous storage for [Int64] values on web builds.
final class Int64List extends IterableBase<Int64> {
  /// The number of bytes used by one [Int64] element.
  static const int bytesPerElement = 8;

  final td.Uint8List _bytes;
  late final td.ByteData _view = td.ByteData.sublistView(_bytes);

  /// Creates a zero-initialized list with [length] signed 64-bit elements.
  Int64List(int length) : _bytes = td.Uint8List(length * bytesPerElement);

  Int64List._(this._bytes);

  /// Copies [values] into a new contiguous signed 64-bit list.
  factory Int64List.fromList(Iterable<Object> values) {
    final copied = values.toList(growable: false);
    final result = Int64List(copied.length);
    for (var index = 0; index < copied.length; index += 1) {
      result[index] = switch (copied[index]) {
        int value => Int64(value),
        Int64 value => value,
        _ => throw ArgumentError.value(
            copied[index],
            'values[$index]',
            'Expected an int or Int64.',
          ),
      };
    }
    return result;
  }

  /// Creates a zero-copy view over [buffer].
  factory Int64List.view(
    td.ByteBuffer buffer, [
    int offsetInBytes = 0,
    int? length,
  ]) {
    final byteLength = length == null
        ? buffer.lengthInBytes - offsetInBytes
        : length * bytesPerElement;
    return Int64List._(td.Uint8List.view(buffer, offsetInBytes, byteLength));
  }

  /// Creates a zero-copy element-range view of [data].
  factory Int64List.sublistView(td.TypedData data, [int start = 0, int? end]) {
    final elementCount = data.lengthInBytes ~/ data.elementSizeInBytes;
    final endIndex = RangeError.checkValidRange(start, end, elementCount);
    final byteOffset = data.offsetInBytes + start * data.elementSizeInBytes;
    final byteLength = (endIndex - start) * data.elementSizeInBytes;
    if (byteOffset % bytesPerElement != 0 ||
        byteLength % bytesPerElement != 0) {
      throw ArgumentError(
        'The selected byte range must align to $bytesPerElement-byte '
        'Int64 elements.',
      );
    }
    return Int64List.view(
      data.buffer,
      byteOffset,
      byteLength ~/ bytesPerElement,
    );
  }

  /// The number of elements in this list.
  @override
  int get length => _bytes.lengthInBytes ~/ bytesPerElement;

  /// Returns the element at [index].
  Int64 operator [](int index) {
    RangeError.checkValidIndex(index, this, 'index', length);
    final byteOffset = index * bytesPerElement;
    return Int64.fromWords(
      _view.getUint32(byteOffset, td.Endian.little),
      _view.getInt32(byteOffset + 4, td.Endian.little),
    );
  }

  /// Stores [value] at [index].
  void operator []=(int index, Int64 value) {
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
  Iterator<Int64> get iterator =>
      Iterable<Int64>.generate(length, (index) => this[index]).iterator;

  @override
  String toString() => toList().toString();
}

int _normalizeInt64High(int value) {
  final normalized = value & _int64HighMask;
  if ((normalized & _int64HighSignBit) != 0) {
    return normalized - _int64HighBase;
  }
  return normalized;
}

int _int64LowBitsMask(int bits) {
  if (bits <= 0) {
    return 0;
  }
  return (1 << bits) - 1;
}
