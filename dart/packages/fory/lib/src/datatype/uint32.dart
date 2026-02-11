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

import 'fory_fixed_num.dart';

/// UInt32: 32-bit unsigned integer (0 to 4294967295)
final class UInt32 extends FixedNum {
  static const int MIN_VALUE = 0;
  static const int MAX_VALUE = 4294967295;

  static UInt32 get maxValue => UInt32(MAX_VALUE);
  static UInt32 get minValue => UInt32(MIN_VALUE);

  final int _value;

  UInt32(num input) : _value = _convert(input);

  static int _convert(num value) {
    if (value is int) {
      // Apply 32-bit unsigned integer overflow behavior
      return value & 0xFFFFFFFF; // Keep only the lowest 32 bits (0-4294967295)
    } else {
      return _convert(value.toInt());
    }
  }

  @override
  int get value => _value;

  // Operators
  UInt32 operator +(dynamic other) =>
      UInt32(_value + (other is FixedNum ? other.value : other));

  UInt32 operator -(dynamic other) =>
      UInt32(_value - (other is FixedNum ? other.value : other));

  UInt32 operator *(dynamic other) =>
      UInt32(_value * (other is FixedNum ? other.value : other));

  double operator /(dynamic other) =>
      _value / (other is FixedNum ? other.value : other);

  UInt32 operator ~/(dynamic other) =>
      UInt32(_value ~/ (other is FixedNum ? other.value : other));

  UInt32 operator %(dynamic other) =>
      UInt32(_value % (other is FixedNum ? other.value : other));

  UInt32 operator -() => UInt32(-_value);

  // Bitwise operations
  UInt32 operator &(dynamic other) =>
      UInt32(_value & (other is FixedNum ? other.value : other).toInt());

  UInt32 operator |(dynamic other) =>
      UInt32(_value | (other is FixedNum ? other.value : other).toInt());

  UInt32 operator ^(dynamic other) =>
      UInt32(_value ^ (other is FixedNum ? other.value : other).toInt());

  UInt32 operator ~() => UInt32(~_value);

  UInt32 operator <<(int shiftAmount) => UInt32(_value << shiftAmount);
  UInt32 operator >>(int shiftAmount) => UInt32(_value >> shiftAmount);

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
  int abs() => _value;
  int get sign => _value == 0 ? 0 : 1;
  bool get isNegative => false;

  // Type conversions
  int toInt() => _value;
  double toDouble() => _value.toDouble();

  @override
  String toString() => _value.toString();
}
