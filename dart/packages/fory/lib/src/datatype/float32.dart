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

import 'float16.dart' show Float16;
import 'fory_fixed_num.dart';
import 'int16.dart' show Int16;
import 'int32.dart' show Int32;
import 'int8.dart' show Int8;

/// Float32: 32-bit floating point (IEEE 754 single precision)
final class Float32 extends FixedNum {
  static const double MIN_VALUE = -3.4028234663852886e+38;
  static const double MAX_VALUE = 3.4028234663852886e+38;

  static Float32 get maxValue => Float32(MAX_VALUE);

  static Float32 get minValue => Float32(MIN_VALUE);

  final double _value;

  Float32(num input) : _value = _convert(input);

  static double _convert(num value) {
    // This simulates the precision loss when storing as float32
    var bytes = Float32List(1);
    bytes[0] = value.toDouble();
    // Read back to get float32 precision
    return bytes[0];
  }

  @override
  double get value => _value;

  // Operators
  Float32 operator +(dynamic other) =>
      Float32(_value + (other is FixedNum ? other.value : other));

  Float32 operator -(dynamic other) =>
      Float32(_value - (other is FixedNum ? other.value : other));

  Float32 operator *(dynamic other) =>
      Float32(_value * (other is FixedNum ? other.value : other));

  double operator /(dynamic other) =>
      _value / (other is FixedNum ? other.value : other);

  Float32 operator ~/(dynamic other) =>
      Float32(_value ~/ (other is FixedNum ? other.value : other));

  Float32 operator %(dynamic other) =>
      Float32(_value % (other is FixedNum ? other.value : other));

  Float32 operator -() => Float32(-_value);

  // Comparison
  bool operator <(dynamic other) =>
      _value < (other is FixedNum ? other.value : other);

  bool operator <=(dynamic other) =>
      _value <= (other is FixedNum ? other.value : other);

  bool operator >(dynamic other) =>
      _value > (other is FixedNum ? other.value : other);

  bool operator >=(dynamic other) =>
      _value >= (other is FixedNum ? other.value : other);

  // Equality
  @override
  bool operator ==(Object other) {
    if (other is FixedNum) return _value == other.value;
    if (other is num) return _value == other;
    return false;
  }

  @override
  int get hashCode => _value.hashCode;

  // Common num methods
  double abs() => _value.abs();
  double get sign => _value.sign;
  bool get isNegative => _value < 0;
  bool get isNaN => _value.isNaN;
  bool get isInfinite => _value.isInfinite;
  bool get isFinite => _value.isFinite;

  // Type conversions
  int toInt() => _value.toInt();
  double toDouble() => _value;
  Int8 toInt8() => Int8(_value);
  Int16 toInt16() => Int16(_value);
  Int32 toInt32() => Int32(_value);
  Float16 toFloat16() => Float16(_value);

  // String formatting
  String toStringAsFixed(int fractionDigits) =>
      _value.toStringAsFixed(fractionDigits);
  String toStringAsExponential([int? fractionDigits]) =>
      _value.toStringAsExponential(fractionDigits);
  String toStringAsPrecision(int precision) =>
      _value.toStringAsPrecision(precision);

  @override
  String toString() => _value.toString();
}
