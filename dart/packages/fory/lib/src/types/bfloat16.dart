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

/// Brain floating-point (bfloat16) wrapper used by the xlang type system.
///
/// Bfloat16 uses 1 sign bit, 8 exponent bits, and 7 mantissa bits.
/// It has the same exponent range as float32 but with reduced precision.
final class Bfloat16 implements Comparable<Bfloat16> {
  final int _bits;

  /// Creates a value directly from raw bfloat16 bits.
  const Bfloat16.fromBits(int bits) : _bits = bits & 0xffff;

  /// Converts [value] to the closest representable bfloat16 value.
  factory Bfloat16(num value) => Bfloat16.fromFloat32(value.toDouble());

  /// Converts a float32 [value] to the closest representable bfloat16 value
  /// using round-to-nearest, ties-to-even.
  factory Bfloat16.fromFloat32(double value) {
    final f32 = Float32List(1);
    final u32 = f32.buffer.asUint32List();
    f32[0] = value;
    final bits = u32[0];
    final exponent = (bits >> 23) & 0xff;

    // NaN/Inf: preserve sign and truncate mantissa (keeps NaN payload bits).
    if (exponent == 255) {
      return Bfloat16.fromBits((bits >> 16) & 0xffff);
    }

    // Round-to-nearest, ties-to-even.
    final remainder = bits & 0x1ffff;
    var u = (bits + 0x8000) >> 16;
    if (remainder == 0x8000 && (u & 1) != 0) {
      u--;
    }
    return Bfloat16.fromBits(u & 0xffff);
  }

  /// Returns the raw bfloat16 bits for this value.
  int toBits() => _bits;

  /// Expands this bfloat16 value to a Dart [double] (via float32).
  double toDouble() {
    final f32 = Float32List(1);
    final u32 = f32.buffer.asUint32List();
    u32[0] = _bits << 16;
    return f32[0];
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) || other is Bfloat16 && other._bits == _bits;

  @override
  int get hashCode => _bits.hashCode;

  @override
  int compareTo(Bfloat16 other) => toDouble().compareTo(other.toDouble());

  @override
  String toString() => toDouble().toString();
}
