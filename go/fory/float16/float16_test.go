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

package float16_test

import (
	"math"
	"testing"

	"github.com/apache/fory/go/fory/float16"
	"github.com/stretchr/testify/assert"
)

func TestFloat16_Conversion(t *testing.T) {
	tests := []struct {
		name  string
		f32   float32
		want  uint16 // bits
		check bool   // if true, check exact bits, else check float32 roundtrip within epsilon
	}{
		{"Zero", 0.0, 0x0000, true},
		{"NegZero", float32(math.Copysign(0, -1)), 0x8000, true},
		{"One", 1.0, 0x3c00, true},
		{"MinusOne", -1.0, 0xbc00, true},
		{"Max", 65504.0, 0x7bff, true},
		{"Inf", float32(math.Inf(1)), 0x7c00, true},
		{"NegInf", float32(math.Inf(-1)), 0xfc00, true},
		// Smallest normal: 2^-14 = 0.000061035156
		{"SmallestNormal", float32(math.Pow(2, -14)), 0x0400, true},
		// Largest subnormal: 2^-14 - 2^-24 = 6.09756...e-5
		{"LargestSubnormal", float32(6.097555e-5), 0x03ff, true},
		// Smallest subnormal: 2^-24
		{"SmallestSubnormal", float32(math.Pow(2, -24)), 0x0001, true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f16 := float16.Float16FromFloat32(tt.f32)
			if tt.check {
				assert.Equal(t, tt.want, f16.Bits(), "Bits match")
			}

			// Round trip check
			roundTrip := f16.Float32()
			if math.IsInf(float64(tt.f32), 0) {
				assert.True(t, math.IsInf(float64(roundTrip), 0))
				assert.Equal(t, math.Signbit(float64(tt.f32)), math.Signbit(float64(roundTrip)))
			} else if math.IsNaN(float64(tt.f32)) {
				assert.True(t, math.IsNaN(float64(roundTrip)))
			} else {
				// Allow small error due to precision loss
				// Epsilon for float16 is 2^-10 ~= 0.001 relative error
				// But we check consistency
				if tt.check {
					// bit exact means round trip should map back to similar float (precision loss expected)
					// Verify that converting back to f16 gives same bits
					f16back := float16.Float16FromFloat32(roundTrip)
					assert.Equal(t, tt.want, f16back.Bits())
				}
			}
		})
	}
}

func TestFloat16_NaN(t *testing.T) {
	nan := float16.NaN
	assert.True(t, nan.IsNaN())
	assert.False(t, nan.IsInf(0))
	assert.False(t, nan.IsZero())

	// Comparison
	assert.False(t, nan.Equal(nan))

	// Conversion
	f32 := nan.Float32()
	assert.True(t, math.IsNaN(float64(f32)))
}

func TestFloat16_Arithmetic(t *testing.T) {
	one := float16.Float16FromFloat32(1.0)
	two := float16.Float16FromFloat32(2.0)
	three := float16.Float16FromFloat32(3.0)

	assert.Equal(t, "3", one.Add(two).String())
	assert.Equal(t, "2", three.Sub(one).String())
	assert.Equal(t, "6", two.Mul(three).String())
	assert.Equal(t, "1.5", three.Div(two).String())
}
