// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

//! IEEE 754 half-precision (binary16) floating-point type.
//!
//! This module provides a `float16` type that represents IEEE 754 binary16
//! format (16-bit floating point). The type is a transparent wrapper around
//! `u16` and provides IEEE-compliant conversions to/from `f32`, classification
//! methods, and arithmetic operations.

use std::cmp::Ordering;
use std::fmt;
use std::hash::{Hash, Hasher};
use std::ops::{Add, Div, Mul, Neg, Sub};

/// IEEE 754 binary16 (half-precision) floating-point type.
///
/// This is a 16-bit floating-point format with:
/// - 1 sign bit
/// - 5 exponent bits (bias = 15)
/// - 10 mantissa bits (with implicit leading 1 for normalized values)
///
/// Special values:
/// - ±0: exponent = 0, mantissa = 0
/// - ±Inf: exponent = 31, mantissa = 0
/// - NaN: exponent = 31, mantissa ≠ 0
/// - Subnormals: exponent = 0, mantissa ≠ 0
#[repr(transparent)]
#[derive(Copy, Clone, Default)]
#[allow(non_camel_case_types)]
pub struct float16(u16);

// Bit layout constants
const SIGN_MASK: u16 = 0x8000;
const EXP_MASK: u16 = 0x7C00;
const MANTISSA_MASK: u16 = 0x03FF;
const EXP_SHIFT: u32 = 10;
// const EXP_BIAS: i32 = 15;  // Reserved for future use
const MAX_EXP: i32 = 31;

// Special bit patterns
const INFINITY_BITS: u16 = 0x7C00;
const NEG_INFINITY_BITS: u16 = 0xFC00;
const QUIET_NAN_BITS: u16 = 0x7E00;

impl float16 {
    // ============ Construction ============

    /// Create a `float16` from raw bits.
    ///
    /// This is a const function that performs no validation.
    #[inline(always)]
    pub const fn from_bits(bits: u16) -> Self {
        Self(bits)
    }

    /// Extract the raw bit representation.
    #[inline(always)]
    pub const fn to_bits(self) -> u16 {
        self.0
    }

    // ============ Constants ============

    /// Positive zero (+0.0).
    pub const ZERO: Self = Self(0x0000);

    /// Negative zero (-0.0).
    pub const NEG_ZERO: Self = Self(0x8000);

    /// Positive infinity.
    pub const INFINITY: Self = Self(INFINITY_BITS);

    /// Negative infinity.
    pub const NEG_INFINITY: Self = Self(NEG_INFINITY_BITS);

    /// Quiet NaN (canonical).
    pub const NAN: Self = Self(QUIET_NAN_BITS);

    /// Maximum finite value (65504.0).
    pub const MAX: Self = Self(0x7BFF);

    /// Minimum positive normal value (2^-14 ≈ 6.104e-5).
    pub const MIN_POSITIVE: Self = Self(0x0400);

    /// Minimum positive subnormal value (2^-24 ≈ 5.96e-8).
    pub const MIN_POSITIVE_SUBNORMAL: Self = Self(0x0001);

    // ============ IEEE 754 Conversion ============

    /// Convert `f32` to `float16` using IEEE 754 round-to-nearest, ties-to-even.
    ///
    /// Handles:
    /// - NaN → NaN (preserves payload bits where possible, ensures quiet NaN)
    /// - ±Inf → ±Inf
    /// - ±0 → ±0 (preserves sign)
    /// - Overflow → ±Inf
    /// - Underflow → subnormal or ±0
    /// - Normal values → rounded to nearest representable value
    pub fn from_f32(value: f32) -> Self {
        let bits = value.to_bits();
        let sign = bits & 0x8000_0000;
        let exp = ((bits >> 23) & 0xFF) as i32;
        let mantissa = bits & 0x007F_FFFF;

        // Handle special cases
        if exp == 255 {
            // Inf or NaN
            if mantissa == 0 {
                // Infinity
                return Self(((sign >> 16) | INFINITY_BITS as u32) as u16);
            } else {
                // NaN - preserve lower 10 bits of payload, ensure quiet NaN
                let nan_payload = (mantissa >> 13) & MANTISSA_MASK as u32;
                let quiet_bit = 0x0200; // Bit 9 = quiet NaN bit
                return Self(
                    ((sign >> 16) | INFINITY_BITS as u32 | quiet_bit | nan_payload) as u16,
                );
            }
        }

        // Convert exponent from f32 bias (127) to f16 bias (15)
        let exp16 = exp - 127 + 15;

        // Handle zero
        if exp == 0 && mantissa == 0 {
            return Self((sign >> 16) as u16);
        }

        // Handle overflow (exponent too large for f16)
        if exp16 >= MAX_EXP {
            // Overflow to infinity
            return Self(((sign >> 16) | INFINITY_BITS as u32) as u16);
        }

        // Handle underflow (exponent too small for f16)
        if exp16 <= 0 {
            // Subnormal or underflow to zero
            if exp16 < -10 {
                // Too small even for subnormal - round to zero
                return Self((sign >> 16) as u16);
            }

            // Create subnormal
            // Shift mantissa right by (1 - exp16) positions
            let shift = 1 - exp16;
            let implicit_bit = 1u32 << 23; // f32 implicit leading 1
            let full_mantissa = implicit_bit | mantissa;

            // Shift and round
            let shift_total = 13 + shift;
            let round_bit = 1u32 << (shift_total - 1);
            let sticky_mask = round_bit - 1;
            let sticky = (full_mantissa & sticky_mask) != 0;
            let mantissa16 = full_mantissa >> shift_total;

            // Round to nearest, ties to even
            let result = if (full_mantissa & round_bit) != 0 && (sticky || (mantissa16 & 1) != 0) {
                mantissa16 + 1
            } else {
                mantissa16
            };

            return Self(((sign >> 16) | result) as u16);
        }

        // Normal case: convert mantissa (23 bits → 10 bits)
        // f32 mantissa has 23 bits, f16 has 10 bits
        // Need to round off 13 bits

        let round_bit = 1u32 << 12; // Bit 12 of f32 mantissa
        let sticky_mask = round_bit - 1;
        let sticky = (mantissa & sticky_mask) != 0;
        let mantissa10 = mantissa >> 13;

        // Round to nearest, ties to even
        let rounded_mantissa = if (mantissa & round_bit) != 0 && (sticky || (mantissa10 & 1) != 0) {
            mantissa10 + 1
        } else {
            mantissa10
        };

        // Check if rounding caused mantissa overflow
        if rounded_mantissa > MANTISSA_MASK as u32 {
            // Mantissa overflow - increment exponent
            let new_exp = exp16 + 1;
            if new_exp >= MAX_EXP {
                // Overflow to infinity
                return Self(((sign >> 16) | INFINITY_BITS as u32) as u16);
            }
            // Carry into exponent, mantissa becomes 0
            return Self(((sign >> 16) | ((new_exp as u32) << EXP_SHIFT)) as u16);
        }

        // Assemble the result
        let result = (sign >> 16) | ((exp16 as u32) << EXP_SHIFT) | rounded_mantissa;
        Self(result as u16)
    }

    /// Convert `float16` to `f32` (exact conversion).
    ///
    /// All `float16` values are exactly representable in `f32`.
    pub fn to_f32(self) -> f32 {
        let bits = self.0;
        let sign = (bits & SIGN_MASK) as u32;
        let exp = ((bits & EXP_MASK) >> EXP_SHIFT) as i32;
        let mantissa = (bits & MANTISSA_MASK) as u32;

        // Handle special cases
        if exp == MAX_EXP {
            // Inf or NaN
            if mantissa == 0 {
                // Infinity
                return f32::from_bits((sign << 16) | 0x7F80_0000);
            } else {
                // NaN - preserve payload
                let nan_payload = mantissa << 13;
                return f32::from_bits((sign << 16) | 0x7F80_0000 | nan_payload);
            }
        }

        if exp == 0 {
            if mantissa == 0 {
                // Zero
                return f32::from_bits(sign << 16);
            } else {
                // Subnormal - convert to normal f32
                // Find leading 1 in mantissa
                let mut m = mantissa;
                let mut e = -14i32; // f16 subnormal exponent

                // Normalize
                while (m & 0x0400) == 0 {
                    m <<= 1;
                    e -= 1;
                }

                // Remove implicit leading 1
                m &= 0x03FF;

                // Convert to f32 exponent
                let exp32 = e + 127;
                let mantissa32 = m << 13;

                return f32::from_bits((sign << 16) | ((exp32 as u32) << 23) | mantissa32);
            }
        }

        // Normal value
        let exp32 = exp - 15 + 127; // Convert bias from 15 to 127
        let mantissa32 = mantissa << 13; // Expand mantissa from 10 to 23 bits

        f32::from_bits((sign << 16) | ((exp32 as u32) << 23) | mantissa32)
    }

    // ============ Classification ============

    /// Returns `true` if this value is NaN.
    #[inline]
    pub fn is_nan(self) -> bool {
        (self.0 & EXP_MASK) == EXP_MASK && (self.0 & MANTISSA_MASK) != 0
    }

    /// Returns `true` if this value is positive or negative infinity.
    #[inline]
    pub fn is_infinite(self) -> bool {
        (self.0 & EXP_MASK) == EXP_MASK && (self.0 & MANTISSA_MASK) == 0
    }

    /// Returns `true` if this value is finite (not NaN or infinity).
    #[inline]
    pub fn is_finite(self) -> bool {
        (self.0 & EXP_MASK) != EXP_MASK
    }

    /// Returns `true` if this value is a normal number (not zero, subnormal, infinite, or NaN).
    #[inline]
    pub fn is_normal(self) -> bool {
        let exp = self.0 & EXP_MASK;
        exp != 0 && exp != EXP_MASK
    }

    /// Returns `true` if this value is subnormal.
    #[inline]
    pub fn is_subnormal(self) -> bool {
        (self.0 & EXP_MASK) == 0 && (self.0 & MANTISSA_MASK) != 0
    }

    /// Returns `true` if this value is ±0.
    #[inline]
    pub fn is_zero(self) -> bool {
        (self.0 & !SIGN_MASK) == 0
    }

    /// Returns `true` if the sign bit is set (negative).
    #[inline]
    pub fn is_sign_negative(self) -> bool {
        (self.0 & SIGN_MASK) != 0
    }

    /// Returns `true` if the sign bit is not set (positive).
    #[inline]
    pub fn is_sign_positive(self) -> bool {
        (self.0 & SIGN_MASK) == 0
    }

    // ============ IEEE Value Comparison (separate from bitwise ==) ============

    /// IEEE-754 numeric equality: NaN != NaN, +0 == -0.
    #[inline]
    pub fn eq_value(self, other: Self) -> bool {
        if self.is_nan() || other.is_nan() {
            false
        } else if self.is_zero() && other.is_zero() {
            true // +0 == -0
        } else {
            self.0 == other.0
        }
    }

    /// IEEE-754 partial comparison: returns `None` if either value is NaN.
    #[inline]
    pub fn partial_cmp_value(self, other: Self) -> Option<Ordering> {
        self.to_f32().partial_cmp(&other.to_f32())
    }

    /// Total ordering comparison (including NaN).
    ///
    /// This matches the behavior of `f32::total_cmp`.
    #[inline]
    pub fn total_cmp(self, other: Self) -> Ordering {
        self.to_f32().total_cmp(&other.to_f32())
    }

    // ============ Arithmetic (explicit methods) ============

    /// Add two `float16` values (via f32).
    #[inline]
    #[allow(clippy::should_implement_trait)]
    pub fn add(self, rhs: Self) -> Self {
        Self::from_f32(self.to_f32() + rhs.to_f32())
    }

    /// Subtract two `float16` values (via f32).
    #[inline]
    #[allow(clippy::should_implement_trait)]
    pub fn sub(self, rhs: Self) -> Self {
        Self::from_f32(self.to_f32() - rhs.to_f32())
    }

    /// Multiply two `float16` values (via f32).
    #[inline]
    #[allow(clippy::should_implement_trait)]
    pub fn mul(self, rhs: Self) -> Self {
        Self::from_f32(self.to_f32() * rhs.to_f32())
    }

    /// Divide two `float16` values (via f32).
    #[inline]
    #[allow(clippy::should_implement_trait)]
    pub fn div(self, rhs: Self) -> Self {
        Self::from_f32(self.to_f32() / rhs.to_f32())
    }

    /// Negate this `float16` value.
    #[inline]
    #[allow(clippy::should_implement_trait)]
    pub fn neg(self) -> Self {
        Self(self.0 ^ SIGN_MASK)
    }

    /// Absolute value.
    #[inline]
    pub fn abs(self) -> Self {
        Self(self.0 & !SIGN_MASK)
    }
}

// ============ Trait Implementations ============

// Policy A: Bitwise equality and hashing (allows use in HashMap)
impl PartialEq for float16 {
    #[inline]
    fn eq(&self, other: &Self) -> bool {
        self.0 == other.0
    }
}

impl Eq for float16 {}

impl Hash for float16 {
    #[inline]
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.0.hash(state);
    }
}

// IEEE partial ordering (NaN breaks total order)
impl PartialOrd for float16 {
    #[inline]
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        self.to_f32().partial_cmp(&other.to_f32())
    }
}

// Arithmetic operator traits
impl Add for float16 {
    type Output = Self;
    #[inline]
    fn add(self, rhs: Self) -> Self {
        Self::add(self, rhs)
    }
}

impl Sub for float16 {
    type Output = Self;
    #[inline]
    fn sub(self, rhs: Self) -> Self {
        Self::sub(self, rhs)
    }
}

impl Mul for float16 {
    type Output = Self;
    #[inline]
    fn mul(self, rhs: Self) -> Self {
        Self::mul(self, rhs)
    }
}

impl Div for float16 {
    type Output = Self;
    #[inline]
    fn div(self, rhs: Self) -> Self {
        Self::div(self, rhs)
    }
}

impl Neg for float16 {
    type Output = Self;
    #[inline]
    fn neg(self) -> Self {
        Self::neg(self)
    }
}

// Display and Debug
impl fmt::Display for float16 {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.to_f32())
    }
}

impl fmt::Debug for float16 {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "float16({})", self.to_f32())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_zero() {
        assert_eq!(float16::ZERO.to_bits(), 0x0000);
        assert!(float16::ZERO.is_zero());
        assert!(!float16::ZERO.is_sign_negative());

        assert_eq!(float16::NEG_ZERO.to_bits(), 0x8000);
        assert!(float16::NEG_ZERO.is_zero());
        assert!(float16::NEG_ZERO.is_sign_negative());
    }

    #[test]
    fn test_infinity() {
        assert_eq!(float16::INFINITY.to_bits(), 0x7C00);
        assert!(float16::INFINITY.is_infinite());
        assert!(!float16::INFINITY.is_nan());

        assert_eq!(float16::NEG_INFINITY.to_bits(), 0xFC00);
        assert!(float16::NEG_INFINITY.is_infinite());
        assert!(float16::NEG_INFINITY.is_sign_negative());
    }

    #[test]
    fn test_nan() {
        assert!(float16::NAN.is_nan());
        assert!(!float16::NAN.is_infinite());
        assert!(!float16::NAN.is_finite());
    }

    #[test]
    fn test_special_values_conversion() {
        // Infinity
        assert_eq!(float16::from_f32(f32::INFINITY), float16::INFINITY);
        assert_eq!(float16::from_f32(f32::NEG_INFINITY), float16::NEG_INFINITY);
        assert_eq!(float16::INFINITY.to_f32(), f32::INFINITY);
        assert_eq!(float16::NEG_INFINITY.to_f32(), f32::NEG_INFINITY);

        // Zero
        assert_eq!(float16::from_f32(0.0), float16::ZERO);
        assert_eq!(float16::from_f32(-0.0), float16::NEG_ZERO);
        assert_eq!(float16::ZERO.to_f32(), 0.0);

        // NaN
        assert!(float16::from_f32(f32::NAN).is_nan());
        assert!(float16::NAN.to_f32().is_nan());
    }

    #[test]
    fn test_max_min_values() {
        // Max finite value: 65504.0
        let max_f32 = 65504.0f32;
        assert_eq!(float16::from_f32(max_f32), float16::MAX);
        assert_eq!(float16::MAX.to_f32(), max_f32);

        // Min positive normal: 2^-14
        let min_normal = 2.0f32.powi(-14);
        assert_eq!(float16::from_f32(min_normal), float16::MIN_POSITIVE);
        assert_eq!(float16::MIN_POSITIVE.to_f32(), min_normal);

        // Min positive subnormal: 2^-24
        let min_subnormal = 2.0f32.powi(-24);
        let h = float16::from_f32(min_subnormal);
        assert_eq!(h, float16::MIN_POSITIVE_SUBNORMAL);
        assert!(h.is_subnormal());
    }

    #[test]
    fn test_overflow() {
        // Values larger than max should overflow to infinity
        let too_large = 70000.0f32;
        assert_eq!(float16::from_f32(too_large), float16::INFINITY);
        assert_eq!(float16::from_f32(-too_large), float16::NEG_INFINITY);
    }

    #[test]
    fn test_underflow() {
        // Very small values should underflow to zero or subnormal
        let very_small = 2.0f32.powi(-30);
        let h = float16::from_f32(very_small);
        assert!(h.is_zero() || h.is_subnormal());
    }

    #[test]
    fn test_rounding() {
        // Test round-to-nearest, ties-to-even
        // 1.0 is exactly representable
        let one = float16::from_f32(1.0);
        assert_eq!(one.to_f32(), 1.0);

        // 1.5 is exactly representable
        let one_half = float16::from_f32(1.5);
        assert_eq!(one_half.to_f32(), 1.5);
    }

    #[test]
    fn test_arithmetic() {
        let a = float16::from_f32(1.5);
        let b = float16::from_f32(2.5);

        assert_eq!((a + b).to_f32(), 4.0);
        assert_eq!((b - a).to_f32(), 1.0);
        assert_eq!((a * b).to_f32(), 3.75);
        assert_eq!((-a).to_f32(), -1.5);
        assert_eq!(a.abs().to_f32(), 1.5);
        assert_eq!((-a).abs().to_f32(), 1.5);
    }

    #[test]
    fn test_comparison() {
        let a = float16::from_f32(1.0);
        let b = float16::from_f32(2.0);
        let nan = float16::NAN;

        // Bitwise equality
        assert_eq!(a, a);
        assert_ne!(a, b);

        // IEEE value equality
        assert!(a.eq_value(a));
        assert!(!a.eq_value(b));
        assert!(!nan.eq_value(nan)); // NaN != NaN

        // +0 == -0 in IEEE
        assert!(float16::ZERO.eq_value(float16::NEG_ZERO));

        // Partial ordering
        assert_eq!(a.partial_cmp_value(b), Some(Ordering::Less));
        assert_eq!(b.partial_cmp_value(a), Some(Ordering::Greater));
        assert_eq!(a.partial_cmp_value(a), Some(Ordering::Equal));
        assert_eq!(nan.partial_cmp_value(a), None);
    }

    #[test]
    fn test_classification() {
        assert!(float16::from_f32(1.0).is_normal());
        assert!(float16::from_f32(1.0).is_finite());
        assert!(!float16::from_f32(1.0).is_zero());
        assert!(!float16::from_f32(1.0).is_subnormal());

        assert!(float16::MIN_POSITIVE_SUBNORMAL.is_subnormal());
        assert!(!float16::MIN_POSITIVE_SUBNORMAL.is_normal());
    }
}
