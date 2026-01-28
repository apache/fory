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

package float16

import (
	"fmt"
	"math"
)

// Float16 represents a half-precision floating point number (IEEE 754-2008 binary16).
// It is stored as a uint16.
type Float16 uint16

// Constants for half-precision floating point
const (
	uvNan     = 0x7e00 // 0 11111 1000000000 (standard quiet NaN)
	uvInf     = 0x7c00 // 0 11111 0000000000 (+Inf)
	uvNegInf  = 0xfc00 // 1 11111 0000000000 (-Inf)
	uvNegZero = 0x8000 // 1 00000 0000000000 (-0)
	uvMax     = 0x7bff // 65504
	uvMinNorm = 0x0400 // 2^-14 (highest subnormal is 0x03ff, lowest normal is 0x0400)
	uvMinSub  = 0x0001 // 2^-24
	uvOne     = 0x3c00 // 1.0
	maskSign  = 0x8000
	maskExp   = 0x7c00
	maskMant  = 0x03ff
)

// Common values
var (
	NaN      = Float16(uvNan)
	Inf      = Float16(uvInf)
	NegInf   = Float16(uvNegInf)
	Zero     = Float16(0)
	NegZero  = Float16(uvNegZero)
	Max      = Float16(uvMax)
	Smallest = Float16(uvMinSub) // Smallest non-zero
	One      = Float16(uvOne)
)

// Float16FromBits returns the Float16 corresponding to the given bit pattern.
func Float16FromBits(b uint16) Float16 {
	return Float16(b)
}

// Bits returns the raw bit pattern of the floating point number.
func (f Float16) Bits() uint16 {
	return uint16(f)
}

// Float16FromFloat32 converts a float32 to a Float16.
// Rounds to nearest, ties to even.
func Float16FromFloat32(f32 float32) Float16 {
	bits := math.Float32bits(f32)
	sign := (bits >> 31) & 0x1
	exp := (bits >> 23) & 0xff
	mant := bits & 0x7fffff

	var outSign uint16 = uint16(sign) << 15
	var outExp uint16
	var outMant uint16

	if exp == 0xff {
		// NaN or Inf
		outExp = 0x1f
		if mant != 0 {
			// NaN - preserve top bit of mantissa for quiet/signaling if possible, but simplest is canonical QNaN
			outMant = 0x200 | (uint16(mant>>13) & 0x1ff)
			if outMant == 0 {
				outMant = 0x200 // Ensure at least one bit
			}
		} else {
			// Inf
			outMant = 0
		}
	} else if exp == 0 {
		// Signed zero or subnormal float32 (which becomes zero in float16 usually)
		outExp = 0
		outMant = 0
	} else {
		// Normalized
		newExp := int(exp) - 127 + 15
		if newExp >= 31 {
			// Overflow to Inf
			outExp = 0x1f
			outMant = 0
		} else if newExp <= 0 {
			// Underflow to subnormal or zero
			// Shift mantissa to align with float16 subnormal range
			// float32 mantissa has implicit 1.
			fullMant := mant | 0x800000
			shift := 1 - newExp // 1 for implicit bit alignment
			// We need to round.
			// Mantissa bits: 23. Subnormal 16 mant bits: 10.
			// We want to shift right by (13 + shift).

			// Let's do a more precise soft-float rounding
			// Re-assemble float value to handle subnormal rounding correctly is hard with just bit shifts
			// But since we have hardware float32...
			// Actually pure bit manipulation is robust if careful.

			// Shift right amount
			netShift := 13 + shift // 23 - 10 + shift

			if netShift >= 24 {
				// Too small, becomes zero
				outExp = 0
				outMant = 0
			} else {
				outExp = 0
				roundBit := (fullMant >> (netShift - 1)) & 1
				sticky := (fullMant & ((1 << (netShift - 1)) - 1))
				outMant = uint16(fullMant >> netShift)

				if roundBit == 1 {
					if sticky != 0 || (outMant&1) == 1 {
						outMant++
					}
				}
			}
		} else {
			// Normal range
			outExp = uint16(newExp)
			// Mantissa: float32 has 23 bits, float16 has 10.
			// We need to round based on the dropped 13 bits.
			// Last kept bit at index 13 (0-indexed from LSB of float32 mant)
			// Round bit is index 12.

			// Using helper to round
			outMant = uint16(mant >> 13)
			roundBit := (mant >> 12) & 1
			sticky := mant & 0xfff

			if roundBit == 1 {
				// Round to nearest, ties to even
				if sticky != 0 || (outMant&1) == 1 {
					outMant++
					if outMant > 0x3ff {
						// Overflow mantissa, increment exponent
						outMant = 0
						outExp++
						if outExp >= 31 {
							outExp = 0x1f // Inf
						}
					}
				}
			}
		}
	}

	return Float16(outSign | (outExp << 10) | outMant)
}

// Float32 returns the float32 representation of the Float16.
func (f Float16) Float32() float32 {
	bits := uint16(f)
	sign := (bits >> 15) & 0x1
	exp := (bits >> 10) & 0x1f
	mant := bits & 0x3ff

	var outBits uint32
	outBits = uint32(sign) << 31

	if exp == 0x1f {
		// NaN or Inf
		outBits |= 0xff << 23
		if mant != 0 {
			// NaN - promote mantissa
			outBits |= uint32(mant) << 13
		}
	} else if exp == 0 {
		if mant == 0 {
			// Signed zero
			outBits |= 0
		} else {
			// Subnormal
			// Convert to float32 normal
			// Normalize the subnormal
			shift := 0
			m := uint32(mant)
			for (m & 0x400) == 0 {
				m <<= 1
				shift++
			}
			// m now has bit 10 set (implicit 1 for float32)
			// discard implicit bit
			m &= 0x3ff
			// new float32 exponent
			// subnormal 16 is 2^-14 * 0.mant
			// = 2^-14 * 2^-10 * mant_integer
			// = 2^-24 * mant_integer
			// Normalized float32 is 1.mant * 2^(E-127)
			// We effectively shift left until we hit the 1.
			// The effective exponent is (1 - 15) - shift = -14 - shift?
			// Simpler:
			// value = mant * 2^-24
			// Reconstruct using float32 operations to avoid bit headaches?
			// No, bit ops are faster.

			// Float16 subnormal: (-1)^S * 2^(1-15) * (mant / 1024)
			// = (-1)^S * 2^-14 * (mant * 2^-10)
			// = (-1)^S * 2^-24 * mant

			// Float32: (-1)^S * 2^(E-127) * (1 + M/2^23)

			// Let's use the magic number method or just float32 arithmetic if lazy
			val := float32(mant) * float32(math.Pow(2, -24))
			if sign == 1 {
				val = -val
			}
			return float32(val)
		}
	} else {
		// Normal
		outBits |= (uint32(exp) - 15 + 127) << 23
		outBits |= uint32(mant) << 13
	}

	return math.Float32frombits(outBits)
}

// IsNaN reports whether f is an IEEE 754 “not-a-number” value.
func (f Float16) IsNaN() bool {
	return (f&maskExp) == maskExp && (f&maskMant) != 0
}

// IsInf reports whether f is an infinity, according to sign.
// If sign > 0, IsInf reports whether f is positive infinity.
// If sign < 0, IsInf reports whether f is negative infinity.
// If sign == 0, IsInf reports whether f is either infinity.
func (f Float16) IsInf(sign int) bool {
	isInf := (f&maskExp) == maskExp && (f&maskMant) == 0
	if !isInf {
		return false
	}
	if sign == 0 {
		return true
	}
	hasSign := (f & maskSign) != 0
	if sign > 0 {
		return !hasSign
	}
	return hasSign
}

// IsZero reports whether f is +0 or -0.
func (f Float16) IsZero() bool {
	return (f & (maskExp | maskMant)) == 0
}

// IsFinite reports whether f is neither NaN nor an infinity.
func (f Float16) IsFinite() bool {
	return (f & maskExp) != maskExp
}

// IsNormal reports whether f is a normal value (not zero, subnormal, infinite, or NaN).
func (f Float16) IsNormal() bool {
	exp := f & maskExp
	return exp != 0 && exp != maskExp
}

// IsSubnormal reports whether f is a subnormal value.
func (f Float16) IsSubnormal() bool {
	exp := f & maskExp
	mant := f & maskMant
	return exp == 0 && mant != 0
}

// Signbit reports whether f is negative or negative zero.
func (f Float16) Signbit() bool {
	return (f & maskSign) != 0
}

// String returns the string representation of f.
func (f Float16) String() string {
	return fmt.Sprintf("%g", f.Float32())
}

// Arithmetic operations (promoted to float32)

func (f Float16) Add(other Float16) Float16 {
	return Float16FromFloat32(f.Float32() + other.Float32())
}

func (f Float16) Sub(other Float16) Float16 {
	return Float16FromFloat32(f.Float32() - other.Float32())
}

func (f Float16) Mul(other Float16) Float16 {
	return Float16FromFloat32(f.Float32() * other.Float32())
}

func (f Float16) Div(other Float16) Float16 {
	return Float16FromFloat32(f.Float32() / other.Float32())
}

func (f Float16) Neg() Float16 {
	return f ^ maskSign
}

func (f Float16) Abs() Float16 {
	return f &^ maskSign
}

// Comparison

func (f Float16) Equal(other Float16) bool {
	// IEEE 754: NaN != NaN
	if f.IsNaN() || other.IsNaN() {
		return false
	}
	// +0 == -0
	if f.IsZero() && other.IsZero() {
		return true
	}
	// Direct bit comparison works for typical normals with same sign
	// But mixed signs or negative numbers need caear
	return f.Float32() == other.Float32()
}

func (f Float16) Less(other Float16) bool {
	if f.IsNaN() || other.IsNaN() {
		return false
	}
	// Handle signed zero: -0 is not less than +0
	if f.IsZero() && other.IsZero() {
		return false
	}
	return f.Float32() < other.Float32()
}

func (f Float16) LessEq(other Float16) bool {
	if f.IsNaN() || other.IsNaN() {
		return false
	}
	if f.IsZero() && other.IsZero() {
		return true
	}
	return f.Float32() <= other.Float32()
}

func (f Float16) Greater(other Float16) bool {
	return other.Less(f)
}

func (f Float16) GreaterEq(other Float16) bool {
	return other.LessEq(f)
}
