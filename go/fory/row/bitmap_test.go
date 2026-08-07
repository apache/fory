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

package row

import (
	"testing"

	"github.com/stretchr/testify/require"
)

func TestBitmapWidthInBytes(t *testing.T) {
	cases := []struct{ n, want int }{
		{0, 0},
		{1, 8},
		{10, 8},
		{63, 8},
		{64, 8},
		{65, 16},
		{128, 16},
		{129, 24},
	}
	for _, c := range cases {
		require.Equal(t, c.want, bitmapWidthInBytes(c.n), "n=%d", c.n)
	}
}

func TestBitOperations(t *testing.T) {
	bitmap := make([]byte, 16)

	// Bit 0 of byte 0 is index 0 (LSB-first).
	setBit(bitmap, 0)
	require.Equal(t, byte(0b1), bitmap[0])
	require.True(t, getBit(bitmap, 0))

	setBit(bitmap, 2)
	require.Equal(t, byte(0b101), bitmap[0])

	// Index 7 stays in byte 0; index 8 moves to byte 1.
	setBit(bitmap, 7)
	require.Equal(t, byte(0b10000101), bitmap[0])
	setBit(bitmap, 8)
	require.Equal(t, byte(0b1), bitmap[1])

	// Word boundary: index 63 is the last bit of the first word,
	// index 64 the first bit of the second.
	setBit(bitmap, 63)
	require.Equal(t, byte(0b10000000), bitmap[7])
	setBit(bitmap, 64)
	require.Equal(t, byte(0b1), bitmap[8])

	// Clearing affects only the targeted bit.
	clearBit(bitmap, 2)
	require.False(t, getBit(bitmap, 2))
	require.True(t, getBit(bitmap, 0))
	require.True(t, getBit(bitmap, 7))

	require.False(t, getBit(bitmap, 1))
}

func TestRoundToWord(t *testing.T) {
	cases := []struct{ n, want int }{
		{0, 0},
		{1, 8},
		{7, 8},
		{8, 8},
		{9, 16},
		{16, 16},
	}
	for _, c := range cases {
		require.Equal(t, c.want, roundToWord(c.n), "n=%d", c.n)
	}
}
