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

import 'dart:math' as math;
import 'dart:typed_data';

import 'float32.dart' show Float32;
import 'fory_fixed_num.dart';
import 'int16.dart' show Int16;
import 'int32.dart' show Int32;
import 'int8.dart' show Int8;

/// Float16: 16-bit floating point (IEEE 754 half precision)
/// Wraps a 16-bit integer representing the IEEE 754 binary16 format.
final class Float16 extends FixedNum {
  /// The raw 16-bit integer storage.
  final int _bits;

  /// Masks the bits to ensure 16-bit range.
  static const int _mask = 0xFFFF;

  // --- Constants ---
  static const int _exponentBias = 15;
  static const int _maxExponent = 31; // 2^5 - 1
  
  // Bit placeholders
  static const int _signMask = 0x8000;
  static const int _exponentMask = 0x7C00;
  static const int _mantissaMask = 0x03FF;

  // --- Public Constants ---
  static const Float16 positiveZero = Float16.fromBits(0x0000);
  static const Float16 negativeZero = Float16.fromBits(0x8000);
  static const Float16 positiveInfinity = Float16.fromBits(0x7C00);
  static const Float16 negativeInfinity = Float16.fromBits(0xFC00);
  static const Float16 nan = Float16.fromBits(0x7E00);

  static const double minValue = 6.103515625e-05;
  static const double minSubnormal = 5.960464477539063e-08;
  static const double maxValue = 65504.0;
  static const double epsilon = 0.0009765625;

  /// Constructs a [Float16] from a number. 
  /// Delegates to [Float16.fromDouble].
  factory Float16(num value) => Float16.fromDouble(value.toDouble());

  /// Constructs a [Float16] directly from raw bits.
  const Float16.fromBits(int bits) : _bits = bits & _mask, super();

  /// Converts a [double] to [Float16] using IEEE 754 half-precision rules (round-to-nearest-even).
  factory Float16.fromDouble(double value) {
     if (value.isNaN) {
       return Float16.nan;
     }

     final int doubleBits = _doubleToBits(value);
     final int sign = (doubleBits >> 63) & 0x1;
     final int rawExp = (doubleBits >> 52) & 0x7FF;
     final int rawMantissa = doubleBits & 0xFFFFFFFFFFFFF;

     // 1. Convert double exp to float16 exp
     // Double bias: 1023, Float16 bias: 15.
     // Shift: 1023 - 15 = 1008.
     int exp = rawExp - 1023 + _exponentBias;
     
     if (exp >= _maxExponent) {
       // Overflow or Infinity
        return rawExp == 2047 && rawMantissa != 0
            ? Float16.nan // Should have been caught by value.isNaN check above usually
            : (sign == 0 ? Float16.positiveInfinity : Float16.negativeInfinity);
     } else if (exp <= 0) {
       // Subnormal or Zero
       if (exp < -10) {
         // Too small for subnormal -> signed zero
         return sign == 0 ? Float16.positiveZero : Float16.negativeZero;
       }
       
       // Convert to subnormal
       return Float16.fromBits(_doubleToFloat16Bits(value));
     } else {
       // Normalized
       return Float16.fromBits(_doubleToFloat16Bits(value));
     }
  }

  static int _doubleToBits(double value) {
    var bdata = ByteData(8);
    bdata.setFloat64(0, value, Endian.little);
    return bdata.getUint64(0, Endian.little);
  }
  
  static double _bitsToDouble(int bits) {
      // Logic for converting float16 bits to double
      int s = (bits >> 15) & 0x0001;
      int e = (bits >> 10) & 0x001f;
      int m = bits & 0x03ff;
      
      if (e == 0) {
          if (m == 0) {
              // signed zero
              return s == 1 ? -0.0 : 0.0;
          } else {
              // subnormal
              return (s == 1 ? -1 : 1) * math.pow(2, -14) * (m / 1024.0);
          }
      } else if (e == 31) {
          if (m == 0) {
              return s == 1 ? double.negativeInfinity : double.infinity;
          } else {
              return double.nan;
          }
      } else {
          // normalized
          return (s == 1 ? -1 : 1) * math.pow(2, e - 15) * (1 + m / 1024.0);
      }
  }
  
  /// Helper to convert double to float16 bits with proper rounding
  static int _doubleToFloat16Bits(double val) {
      if (val.isNaN) return 0x7E00; // Canonical NaN
      
      // Check for zero
      if (val == 0.0) {
          return (1 / val) == double.negativeInfinity ? 0x8000 : 0x0000; 
      }
      
      int fbits = _doubleToBits(val);
      int sign = (fbits >> 63) & 1;
      int exp = (fbits >> 52) & 0x7FF; // 11 bits
      
      // Bias adjustment
      int newExp = exp - 1023 + 15;
      
      // Inf / NaN handled?
      if (exp == 2047) {
          // Infinity only (NaN handled at start)
          return (sign << 15) | 0x7C00;
      }
      
      if (newExp >= 31) {
           // Overflow to Infinity
           return (sign << 15) | 0x7C00;
      }
      
      if (newExp <= 0) {
          // Possible subnormal or zero
          if (newExp < -10) {
             // Underflow to zero
             return sign << 15;
          }
          
          double absVal = val.abs();
          if (absVal < minValue) {
              // It is subnormal
              // val = 0.m * 2^-14
              // m = val / 2^-14
              double m = absVal / math.pow(2, -14);
              // m is now in [0, 1). 
              // We want 10 bits of it.
              // bits = round(m * 1024)
              int mBits = (m * 1024).round();
              return (sign << 15) | mBits;
          }
      }
      
      // Normalized
      // We need to round the mantissa.
      
      int fullMantissa = (fbits & 0xFFFFFFFFFFFFF);
      // We want to reduce 52 bits to 10 bits.
      // So we shift right by 42.
      // The bits we shift out are the last 42 bits.
      // The 42nd bit (from right, 0-indexed) is the round bit.
      // The bits 0-41 are safety/sticky bits.
      
      int m10 = fullMantissa >> 42;
      int guard = (fullMantissa >> 41) & 1;
      int sticky = (fullMantissa & 0x1FFFFFFFFFF) != 0 ? 1 : 0;
      
      if (guard == 1) {
          if (sticky == 1 || (m10 & 1) == 1) {
              m10++;
          }
      }
      
      if (m10 >= 1024) {
          // Mantissa overflowed, increment exponent
          m10 = 0;
          newExp++;
          if (newExp >= 31) {
              return (sign << 15) | 0x7C00; // Inf
          }
      }
      
      return (sign << 15) | (newExp << 10) | m10;
  }

  /// Returns the raw 16-bit integer.
  int toBits() => _bits;

  /// Returns the value as a [double].
  double toDouble() => _bitsToDouble(_bits);

  /// Returns the underlying values as a [double].
  @override
  double get value => toDouble();

  // --- Classification ---
  bool get isNaN => (_bits & _exponentMask) == _exponentMask && (_bits & _mantissaMask) != 0;
  
  bool get isInfinite => (_bits & _exponentMask) == _exponentMask && (_bits & _mantissaMask) == 0;
  
  bool get isFinite => (_bits & _exponentMask) != _exponentMask;
  
  bool get isNormal => 
      (_bits & _exponentMask) != 0 && (_bits & _exponentMask) != _exponentMask;
      
  bool get isSubnormal => 
      (_bits & _exponentMask) == 0 && (_bits & _mantissaMask) != 0;
      
  bool get isZero => (_bits & 0x7FFF) == 0;
  
  bool get isNegative => (_bits & _signMask) != 0;
  
  int get sign => isNaN ? 0 : (isNegative ? -1 : 1); 

  // --- Arithmetic (Explicit) ---

  Float16 add(Float16 other) => Float16.fromDouble(toDouble() + other.toDouble());
  Float16 sub(Float16 other) => Float16.fromDouble(toDouble() - other.toDouble());
  Float16 mul(Float16 other) => Float16.fromDouble(toDouble() * other.toDouble());
  Float16 div(Float16 other) => Float16.fromDouble(toDouble() / other.toDouble());
  
  Float16 neg() => Float16.fromBits(_bits ^ _signMask);
  
  Float16 abs() => Float16.fromBits(_bits & 0x7FFF);

  // --- Comparisons ---
  
  /// Bitwise equality. +0 != -0, NaN == NaN (if payload matches).
  /// This is effectively `_bits == other._bits`.
  bool equalsValue(Float16 other) => _bits == other._bits;
  
  /// IEEE comparison.
  /// NaN != NaN. +0 == -0.
  bool ieeeEquals(Float16 other) => toDouble() == other.toDouble();
  
  bool lessThan(Float16 other) => toDouble() < other.toDouble();
  bool lessThanOrEqual(Float16 other) => toDouble() <= other.toDouble();
  bool greaterThan(Float16 other) => toDouble() > other.toDouble();
  bool greaterThanOrEqual(Float16 other) => toDouble() >= other.toDouble();
  
  static int compare(Float16 a, Float16 b) => a.toDouble().compareTo(b.toDouble());

  // --- Operators (Delegation) ---
  
  Float16 operator +(dynamic other) =>
      add(other is Float16 ? other : Float16(other));

  Float16 operator -(dynamic other) =>
      sub(other is Float16 ? other : Float16(other));

  Float16 operator *(dynamic other) =>
      mul(other is Float16 ? other : Float16(other));

  double operator /(dynamic other) =>
      toDouble() / (other is Float16 ? other.toDouble() : other);
      
  Float16 operator ~/(dynamic other) =>
      Float16((toDouble() ~/ (other is Float16 ? other.toDouble() : other)));

  Float16 operator %(dynamic other) =>
      Float16(toDouble() % (other is Float16 ? other.toDouble() : other));

  Float16 operator -() => neg();

  // Comparison Operators
  bool operator <(dynamic other) => toDouble() < (other is Float16 ? other.toDouble() : other);

  bool operator <=(dynamic other) => toDouble() <= (other is Float16 ? other.toDouble() : other);

  bool operator >(dynamic other) => toDouble() > (other is Float16 ? other.toDouble() : other);

  bool operator >=(dynamic other) => toDouble() >= (other is Float16 ? other.toDouble() : other);

  @override
  bool operator ==(Object other) {
    if (other is Float16) return _bits == other._bits; // Policy A: Bitwise equality
    return false;
  }

  @override
  int get hashCode => _bits.hashCode;

  // --- Type Conversions ---
  
  int toInt() => toDouble().toInt();
  
  Int8 toInt8() => Int8(toInt());
  Int16 toInt16() => Int16(toInt());
  Int32 toInt32() => Int32(toInt());
  
  Float32 toFloat32() => Float32(toDouble());
  
  // --- String Formatting ---
  
  String toStringAsFixed(int fractionDigits) => toDouble().toStringAsFixed(fractionDigits);
  String toStringAsExponential([int? fractionDigits]) => toDouble().toStringAsExponential(fractionDigits);
  String toStringAsPrecision(int precision) => toDouble().toStringAsPrecision(precision);
  
  @override
  String toString() => toDouble().toString();
}
