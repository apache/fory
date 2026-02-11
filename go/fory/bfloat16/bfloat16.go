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

package bfloat16

import (
	"fmt"
	"math"
)

// BFloat16 represents a brain floating point number (bfloat16).
// It is stored as a uint16.
type BFloat16 uint16

// BFloat16FromBits returns the BFloat16 corresponding to the given bit pattern.
func BFloat16FromBits(b uint16) BFloat16 {
	return BFloat16(b)
}

// Bits returns the raw bit pattern of the floating point number.
func (f BFloat16) Bits() uint16 {
	return uint16(f)
}

// BFloat16FromFloat32 converts a float32 to a BFloat16.
// Rounds to nearest, ties to even.
func BFloat16FromFloat32(f float32) BFloat16 {
	u := math.Float32bits(f)

	// NaN check
	if (u&0x7F800000) == 0x7F800000 && (u&0x007FFFFF) != 0 {
		return BFloat16(0x7FC0) // Canonical NaN
	}

	// Fast path for rounding
	// We want to add a rounding bias and then truncate.
	// For ties-to-even:
	// If LSB of result (bit 16) is 0: Rounding bias is 0x7FFF
	// If LSB of result (bit 16) is 1: Rounding bias is 0x8000
	// lsb is (u >> 16) & 1.
	// bias = 0x7FFF + lsb

	lsb := (u >> 16) & 1
	roundingBias := uint32(0x7FFF) + lsb
	u += roundingBias
	return BFloat16(u >> 16)
}

// Float32 returns the float32 representation of the BFloat16.
func (f BFloat16) Float32() float32 {
	// Just shift left by 16 bits
	return math.Float32frombits(uint32(f) << 16)
}

// String returns the string representation of f.
func (f BFloat16) String() string {
	return fmt.Sprintf("%g", f.Float32())
}
