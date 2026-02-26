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

/// UInt16: 16-bit unsigned integer (0 to 65535)
final class UInt16 extends FixedNum {
  static const int MIN_VALUE = 0;
  static const int MAX_VALUE = 65535;

  static UInt16 get maxValue => UInt16(MAX_VALUE);
  static UInt16 get minValue => UInt16(MIN_VALUE);

  final int _value;

  UInt16(num input) : _value = _convert(input);

  static int _convert(num value) {
    if (value is int) {
      // Apply 16-bit unsigned integer overflow behavior
      return value & 0xFFFF; // Keep only the lowest 16 bits (0-65535)
    } else {
      return _convert(value.toInt());
    }
  }

  @override
  int get value => _value;

  // Operators
  UInt16 operator +(dynamic other) =>
      UInt16(_value + (other is FixedNum ? other.value : other));

  UInt16 operator -(dynamic other) =>
      UInt16(_value - (other is FixedNum ? other.value : other));

  UInt16 operator *(dynamic other) =>
      UInt16(_value * (other is FixedNum ? other.value : other));

  double operator /(dynamic other) =>
      _value / (other is FixedNum ? other.value : other);

  UInt16 operator ~/(dynamic other) =>
      UInt16(_value ~/ (other is FixedNum ? other.value : other));

  UInt16 operator %(dynamic other) =>
      UInt16(_value % (other is FixedNum ? other.value : other));

  UInt16 operator -() => UInt16(-_value);

  // Bitwise operations
  UInt16 operator &(dynamic other) =>
      UInt16(_value & (other is FixedNum ? other.value : other).toInt());

  UInt16 operator |(dynamic other) =>
      UInt16(_value | (other is FixedNum ? other.value : other).toInt());

  UInt16 operator ^(dynamic other) =>
      UInt16(_value ^ (other is FixedNum ? other.value : other).toInt());

  UInt16 operator ~() => UInt16(~_value);

  UInt16 operator <<(int shiftAmount) => UInt16(_value << shiftAmount);
  UInt16 operator >>(int shiftAmount) => UInt16(_value >> shiftAmount);

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
