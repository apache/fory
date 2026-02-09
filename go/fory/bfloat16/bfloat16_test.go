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

package bfloat16_test

import (
	"math"
	"testing"

	"github.com/apache/fory/go/fory/bfloat16"
	"github.com/stretchr/testify/assert"
)

func TestBFloat16_Conversion(t *testing.T) {
	tests := []struct {
		name  string
		f32   float32
		want  uint16 // bits
		check bool   // if true, check exact bits
	}{
		{"Zero", 0.0, 0x0000, true},
		{"NegZero", float32(math.Copysign(0, -1)), 0x8000, true},
		{"One", 1.0, 0x3F80, true},
		{"MinusOne", -1.0, 0xBF80, true},
		{"Inf", float32(math.Inf(1)), 0x7F80, true},
		{"NegInf", float32(math.Inf(-1)), 0xFF80, true},
		// 1.5 -> 0x3FC0. (0x3FC00000 is 1.5)
		{"OnePointFive", 1.5, 0x3FC0, true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			bf16 := bfloat16.BFloat16FromFloat32(tt.f32)
			if tt.check {
				assert.Equal(t, tt.want, bf16.Bits(), "Bits match")
			}

			// Round trip check
			roundTrip := bf16.Float32()
			if math.IsInf(float64(tt.f32), 0) {
				assert.True(t, math.IsInf(float64(roundTrip), 0))
				assert.Equal(t, math.Signbit(float64(tt.f32)), math.Signbit(float64(roundTrip)))
			} else if math.IsNaN(float64(tt.f32)) {
				assert.True(t, math.IsNaN(float64(roundTrip)))
			} else {
				if tt.check {
					assert.Equal(t, tt.f32, roundTrip, "Round trip value match")
				}
			}
		})
	}
}

func TestBFloat16_Rounding(t *testing.T) {
	// BFloat16 has 7 bits of mantissa. For 1.0, ULP is 2^-7, and half ULP is 2^-8.
	// Values are rounded to nearest even.  1.0 + 2^-8 should round to 1.0 (even mantissa).

	// The float32 representation of 1.0 is 0x3F800000.
	// Adding 2^-8 (1/256) means setting bit 15 (23-8).
	// So, 1.0 + 2^-8 in float32 is 0x3F808000.
	val1 := math.Float32frombits(0x3F808000) // 1.0 + 2^-8
	bf1 := bfloat16.BFloat16FromFloat32(val1)
	assert.Equal(t, uint16(0x3F80), bf1.Bits(), "Round to even (down)")

	// For 1.0 + 3 * 2^-8 (1.5 ULP), bits 15 and 14 are set,
	// making the float32 representation 0x3F80C000. This rounds up.
	val2 := math.Float32frombits(0x3F80C000)
	bf2 := bfloat16.BFloat16FromFloat32(val2)
	assert.Equal(t, uint16(0x3F81), bf2.Bits(), "Round up")

	// 1.0 + 2^-7 is the next representable number after 1.0. In float32, this is 0x3F810000.
	val3 := math.Float32frombits(0x3F810000)
	bf3 := bfloat16.BFloat16FromFloat32(val3)
	assert.Equal(t, uint16(0x3F81), bf3.Bits(), "Exact")

	// For 1.0 + 2^-7 + 2^-8 (0x3F818000), the LSB (bit 16) of 0x3F81 is 1 (odd),
	// and the guard bit (bit 15) is 1. Rounding to nearest even means rounding up.
	// Result: 0x3F82.
	val4 := math.Float32frombits(0x3F818000)
	bf4 := bfloat16.BFloat16FromFloat32(val4)
	assert.Equal(t, uint16(0x3F82), bf4.Bits(), "Round to even (up)")
}
