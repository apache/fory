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

import 'float16.dart' show Float16;
import 'float32.dart' show Float32;
import 'fory_fixed_num.dart';
import 'int16.dart' show Int16;
import 'int32.dart' show Int32;

/// Int8: 8-bit signed integer (-128 to 127)
final class Int8 extends FixedNum {
  static const int MIN_VALUE = -128;
  static const int MAX_VALUE = 127;

  static Int8 get maxValue => Int8(MAX_VALUE);

  static Int8 get minValue => Int8(MIN_VALUE);

  final int _value;

  Int8(num input) : _value = _convert(input);

  static int _convert(num value) {
    if (value is int) {
      // Apply 8-bit signed integer overflow behavior
      int result = value & 0xFF; // Keep only the lowest 8 bits
      // Convert to signed by checking the sign bit
      return (result & 0x80) != 0 ? result - 256 : result;
    } else {
      return _convert(value.toInt());
    }
  }

  @override
  int get value => _value;

  // Operators
  Int8 operator +(dynamic other) =>
      Int8(_value + (other is FixedNum ? other.value : other));

  Int8 operator -(dynamic other) =>
      Int8(_value - (other is FixedNum ? other.value : other));

  Int8 operator *(dynamic other) =>
      Int8(_value * (other is FixedNum ? other.value : other));

  double operator /(dynamic other) =>
      _value / (other is FixedNum ? other.value : other);

  Int8 operator ~/(dynamic other) =>
      Int8(_value ~/ (other is FixedNum ? other.value : other));

  Int8 operator %(dynamic other) =>
      Int8(_value % (other is FixedNum ? other.value : other));

  Int8 operator -() => Int8(-_value);

  // Bitwise operations
  Int8 operator &(dynamic other) =>
      Int8(_value & (other is FixedNum ? other.value : other).toInt());

  Int8 operator |(dynamic other) =>
      Int8(_value | (other is FixedNum ? other.value : other).toInt());

  Int8 operator ^(dynamic other) =>
      Int8(_value ^ (other is FixedNum ? other.value : other).toInt());

  Int8 operator ~() => Int8(~_value);

  Int8 operator <<(int shiftAmount) => Int8(_value << shiftAmount);
  Int8 operator >>(int shiftAmount) => Int8(_value >> shiftAmount);

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
  int abs() => _value.abs();
  int get sign => _value.sign;
  bool get isNegative => _value < 0;

  // Type conversions
  int toInt() => _value;
  double toDouble() => _value.toDouble();
  Int16 toInt16() => Int16(_value);
  Int32 toInt32() => Int32(_value);
  Float16 toFloat16() => Float16(_value);
  Float32 toFloat32() => Float32(_value);

  @override
  String toString() => _value.toString();
}
