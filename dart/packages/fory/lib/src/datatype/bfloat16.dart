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

import 'dart:typed_data';

import 'fory_fixed_num.dart';

/// BFloat16: 16-bit brain floating point type.
/// Wraps a 16-bit integer representing the bfloat16 format.
final class BFloat16 extends FixedNum {
  /// The raw 16-bit integer storage.
  final int _bits;

  /// Internal constructor from raw bits.
  const BFloat16._(this._bits);

  /// Creates a [BFloat16] from a number.
  factory BFloat16(num value) => BFloat16.fromFloat32(value.toDouble());

  /// Returns the raw 16-bit integer representation.
  int toBits() {
    return _bits;
  }

  /// Creates a [BFloat16] from a raw 16-bit integer.
  factory BFloat16.fromBits(int bits) {
    return BFloat16._(bits & 0xffff);
  }

  /// Creates a [BFloat16] by converting a standard 32-bit floating-point number.
  factory BFloat16.fromFloat32(double f32) {
    var float32View = Float32List(1);
    var uint32View = Uint32List.view(float32View.buffer);

    float32View[0] = f32;
    var bits = uint32View[0];
    var exponent = (bits >> 23) & 0xff;
    if (exponent == 255) {
      return BFloat16.fromBits((bits >> 16) & 0xffff);
    }
    var remainder = bits & 0x1ffff;
    var u = (bits + 0x8000) >> 16;
    if (remainder == 0x8000 && (u & 1) != 0) {
      u--;
    }
    return BFloat16.fromBits(u & 0xffff);
  }

  /// Converts this [BFloat16] to a standard 32-bit floating-point number.
  double toFloat32() {
    var float32View = Float32List(1);
    var uint32View = Uint32List.view(float32View.buffer);
    
    float32View[0] = 0.0;
    uint32View[0] = _bits << 16;
    return float32View[0];
  }

  /// Gets the numeric value as a double.
  @override
  num get value => toFloat32();

  @override
  String toString() => 'BFloat16($value)';

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is BFloat16 &&
          runtimeType == other.runtimeType &&
          _bits == other._bits;

  @override
  int get hashCode => _bits.hashCode;
}

/// A fixed-length list of [BFloat16] values, backed by a [Uint16List].
class BFloat16Array {
  /// The underlying raw 16-bit storage.
  final Uint16List _data;

  /// Creates an array of the given [length], initialized to zero.
  BFloat16Array.fromLength(int length) : _data = Uint16List(length);

  /// Creates an array backed by a copy of the given [Uint16List].
  BFloat16Array.fromUint16List(Uint16List source)
      : _data = Uint16List.fromList(source);

  /// Creates an array from a list of [BFloat16] or [double] values.
  BFloat16Array.fromList(List<dynamic> source)
      : _data = Uint16List(source.length) {
    for (int i = 0; i < source.length; i++) {
      var v = source[i];
      _data[i] = v is BFloat16 ? v.toBits() : BFloat16.fromFloat32(v as double).toBits();
    }
  }

  /// The length of the array.
  int get length => _data.length;

  /// Retrieves the [BFloat16] at the given [index].
  BFloat16 get(int index) => BFloat16.fromBits(_data[index]);

  /// Sets the [BFloat16] value at the given [index]. 
  /// Will automatically convert a [double] to a [BFloat16].
  void set(int index, dynamic value) {
    _data[index] = value is BFloat16
        ? value.toBits()
        : BFloat16.fromFloat32(value as double).toBits();
  }

  /// Exposes the underlying [Uint16List] used for storage.
  Uint16List get raw => _data;

  /// Creates a [BFloat16Array] initialized using the provided raw [Uint16List].
  factory BFloat16Array.fromRaw(Uint16List data) {
    return BFloat16Array.fromUint16List(data);
  }
}
