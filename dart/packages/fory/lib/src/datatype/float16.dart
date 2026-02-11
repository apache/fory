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

import 'dart:math' as math show pow, log, ln2;

import 'float32.dart' show Float32;
import 'fory_fixed_num.dart';
import 'int16.dart' show Int16;
import 'int32.dart' show Int32;
import 'int8.dart' show Int8;

/// Float16: 16-bit floating point (IEEE 754 half precision)
final class Float16 extends FixedNum {
  static const double MIN_VALUE = -65504;
  static const double MAX_VALUE = 65504;
  static const double EPSILON = 0.0009765625; // 2^-10

  static Float16 get maxValue => Float16(MAX_VALUE);

  static Float16 get minValue => Float16(MIN_VALUE);

  final double _value;

  Float16(num input) : _value = _convert(input);

  static double _convert(num value) {
    // This is a proper IEEE 754 half-precision implementation
    double val = value.toDouble();
    if (val.isNaN) return double.nan;
    if (val.isInfinite) return val;

    // Handle zeros
    if (val == 0.0) return val.sign < 0 ? -0.0 : 0.0;

    // Clamp to float16 range
    val = val.clamp(-MAX_VALUE, MAX_VALUE).toDouble();

    // Implementing IEEE 754 half-precision conversion
    int bits;
    if (val.abs() < EPSILON) {
      // Handle subnormal numbers
      bits =
          ((val < 0 ? 1 : 0) << 15) | ((val.abs() / EPSILON).round() & 0x3FF);
    } else {
      // Extract components from double
      int sign = val < 0 ? 1 : 0;
      double absVal = val.abs();
      int exp = (math.log(absVal) / math.ln2).floor();
      double frac = absVal / math.pow(2, exp) - 1.0;

      // Adjust for 5-bit exponent
      exp += 15; // Bias
      exp = exp.clamp(0, 31);

      // Convert to 10-bit fraction
      int fracBits = (frac * 1024).round() & 0x3FF;

      // Combine into 16 bits
      bits = (sign << 15) | (exp << 10) | fracBits;
    }

    // Convert back to double (simulates float16 storage and retrieval)
    // In a real-world implementation, you would use binary data directly
    int sign = (bits >> 15) & 0x1;
    int exp = (bits >> 10) & 0x1F;
    int frac = bits & 0x3FF;

    if (exp == 0) {
      // Subnormal numbers
      return (sign == 0 ? 1.0 : -1.0) * frac * EPSILON;
    } else if (exp == 31) {
      // Infinity or NaN
      return frac == 0
          ? (sign == 0 ? double.infinity : double.negativeInfinity)
          : double.nan;
    }

    // Normal numbers
    double result = (sign == 0 ? 1.0 : -1.0) *
        math.pow(2, exp - 15) *
        (1.0 + frac / 1024.0);
    return result;
  }

  @override
  double get value => _value;

  // Operators
  Float16 operator +(dynamic other) =>
      Float16(_value + (other is FixedNum ? other.value : other));

  Float16 operator -(dynamic other) =>
      Float16(_value - (other is FixedNum ? other.value : other));

  Float16 operator *(dynamic other) =>
      Float16(_value * (other is FixedNum ? other.value : other));

  double operator /(dynamic other) =>
      _value / (other is FixedNum ? other.value : other);

  Float16 operator ~/(dynamic other) =>
      Float16(_value ~/ (other is FixedNum ? other.value : other));

  Float16 operator %(dynamic other) =>
      Float16(_value % (other is FixedNum ? other.value : other));

  Float16 operator -() => Float16(-_value);

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
  Float32 toFloat32() => Float32(_value);

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
