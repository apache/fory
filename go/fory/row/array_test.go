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
	"math"
	"testing"

	"github.com/stretchr/testify/require"
)

// TestArrayHeaderSize validates the layout arithmetic for array headers.
func TestArrayHeaderSize(t *testing.T) {
	require.Equal(t, 16, ArrayHeaderSize(1), "1-element: 8 (count) + 8 (bitmap)")
	require.Equal(t, 16, ArrayHeaderSize(64), "64-element: 8 + 8")
	require.Equal(t, 24, ArrayHeaderSize(65), "65-element: 8 + 16")
}

// TestFixedArrayInt64 roundtrips an int64 array through writer and reader.
func TestFixedArrayInt64(t *testing.T) {
	w := NewFixedArrayWriter(3, 8)
	w.WriteInt64(0, math.MinInt64)
	w.WriteInt64(1, 0)
	w.WriteInt64(2, math.MaxInt64)

	r := NewFixedArrayReader(w.Bytes(), 8)
	require.Equal(t, 3, r.Len())
	require.Equal(t, int64(math.MinInt64), r.ReadInt64(0))
	require.Equal(t, int64(0), r.ReadInt64(1))
	require.Equal(t, int64(math.MaxInt64), r.ReadInt64(2))
}

// TestFixedArrayInt32 roundtrips an int32 array (4-byte element width).
func TestFixedArrayInt32(t *testing.T) {
	w := NewFixedArrayWriter(2, 4)
	w.WriteInt32(0, math.MinInt32)
	w.WriteInt32(1, math.MaxInt32)

	r := NewFixedArrayReader(w.Bytes(), 4)
	require.Equal(t, 2, r.Len())
	require.Equal(t, int32(math.MinInt32), r.ReadInt32(0))
	require.Equal(t, int32(math.MaxInt32), r.ReadInt32(1))
}

// TestFixedArrayBool roundtrips a bool array (1-byte element width).
func TestFixedArrayBool(t *testing.T) {
	w := NewFixedArrayWriter(3, 1)
	w.WriteBool(0, true)
	w.WriteBool(1, false)
	w.WriteBool(2, true)

	r := NewFixedArrayReader(w.Bytes(), 1)
	require.Equal(t, 3, r.Len())
	require.True(t, r.ReadBool(0))
	require.False(t, r.ReadBool(1))
	require.True(t, r.ReadBool(2))
}

// TestFixedArrayFloat64 roundtrips a float64 array.
func TestFixedArrayFloat64(t *testing.T) {
	w := NewFixedArrayWriter(2, 8)
	w.WriteFloat64(0, math.Pi)
	w.WriteFloat64(1, math.E)

	r := NewFixedArrayReader(w.Bytes(), 8)
	require.Equal(t, math.Pi, r.ReadFloat64(0))
	require.Equal(t, math.E, r.ReadFloat64(1))
}

// TestVarArrayString roundtrips a string array including variable-length data.
func TestVarArrayString(t *testing.T) {
	w := NewVarArrayWriter(3, 32)
	w.WriteString(0, "foo")
	w.WriteString(1, "bar")
	w.WriteString(2, "baz")

	r := NewVarArrayReader(w.Bytes())
	require.Equal(t, 3, r.Len())
	require.Equal(t, "foo", r.ReadString(0))
	require.Equal(t, "bar", r.ReadString(1))
	require.Equal(t, "baz", r.ReadString(2))
}

// TestVarArrayBinary roundtrips a binary ([]byte) array.
func TestVarArrayBinary(t *testing.T) {
	w := NewVarArrayWriter(2, 16)
	w.WriteBinary(0, []byte{0x01, 0x02, 0x03})
	w.WriteBinary(1, []byte{0xFF})

	r := NewVarArrayReader(w.Bytes())
	require.Equal(t, []byte{0x01, 0x02, 0x03}, r.ReadBinary(0))
	require.Equal(t, []byte{0xFF}, r.ReadBinary(1))
}

// TestArrayNullBitmap verifies bit=1=null for array elements.
func TestArrayNullBitmap(t *testing.T) {
	w := NewFixedArrayWriter(3, 8)
	w.SetNull(0)
	w.WriteInt64(1, 42)
	w.SetNull(2)

	r := NewFixedArrayReader(w.Bytes(), 8)
	require.True(t, r.IsNull(0))
	require.False(t, r.IsNull(1))
	require.True(t, r.IsNull(2))
	require.Equal(t, int64(42), r.ReadInt64(1))
}

// TestFixedArrayInt8 roundtrips an int8 array (1-byte element width).
func TestFixedArrayInt8(t *testing.T) {
	w := NewFixedArrayWriter(3, 1)
	w.WriteInt8(0, -128)
	w.WriteInt8(1, 0)
	w.WriteInt8(2, 127)

	r := NewFixedArrayReader(w.Bytes(), 1)
	require.Equal(t, 3, r.Len())
	require.Equal(t, int8(-128), r.ReadInt8(0))
	require.Equal(t, int8(0), r.ReadInt8(1))
	require.Equal(t, int8(127), r.ReadInt8(2))
}

// TestFixedArrayInt16 roundtrips an int16 array (2-byte element width).
func TestFixedArrayInt16(t *testing.T) {
	w := NewFixedArrayWriter(2, 2)
	w.WriteInt16(0, -32768)
	w.WriteInt16(1, 32767)

	r := NewFixedArrayReader(w.Bytes(), 2)
	require.Equal(t, 2, r.Len())
	require.Equal(t, int16(-32768), r.ReadInt16(0))
	require.Equal(t, int16(32767), r.ReadInt16(1))
}

// TestFixedArrayFloat32 roundtrips a float32 array (4-byte element width).
func TestFixedArrayFloat32(t *testing.T) {
	w := NewFixedArrayWriter(2, 4)
	w.WriteFloat32(0, math.SmallestNonzeroFloat32)
	w.WriteFloat32(1, -math.MaxFloat32)

	r := NewFixedArrayReader(w.Bytes(), 4)
	require.Equal(t, float32(math.SmallestNonzeroFloat32), r.ReadFloat32(0))
	require.Equal(t, float32(-math.MaxFloat32), r.ReadFloat32(1))
}

// TestVarArrayBufferGrowth verifies the variable-width array buffer grows correctly
// when the initial extraVarBytes hint is too small.
func TestVarArrayBufferGrowth(t *testing.T) {
	// Start with 0 extra bytes — every write forces a buffer reallocation.
	w := NewVarArrayWriter(3, 0)
	w.WriteString(0, "longer-string-that-forces-growth")
	w.WriteString(1, "another-one")
	w.WriteString(2, "x")

	r := NewVarArrayReader(w.Bytes())
	require.Equal(t, "longer-string-that-forces-growth", r.ReadString(0))
	require.Equal(t, "another-one", r.ReadString(1))
	require.Equal(t, "x", r.ReadString(2))
}

// TestArrayDataPadding verifies the data region is 8-byte padded.
// A 3-element int32 array needs 12 bytes of data, padded to 16.
func TestArrayDataPadding(t *testing.T) {
	w := NewFixedArrayWriter(3, 4)
	w.WriteInt32(0, 1)
	w.WriteInt32(1, 2)
	w.WriteInt32(2, 3)

	headerSize := ArrayHeaderSize(3) // 16
	dataSize := PadTo8(3 * 4)        // PadTo8(12) = 16
	require.Equal(t, headerSize+dataSize, len(w.Bytes()))

	// Padding bytes (bytes 28-31 within data) must be zero.
	b := w.Bytes()
	for i := headerSize + 12; i < len(b); i++ {
		require.Equal(t, byte(0), b[i], "padding byte %d must be zero", i)
	}
}

// TestArrayInRow verifies an array can be embedded in a row field and read back.
func TestArrayInRow(t *testing.T) {
	// Row schema: {name string, scores []int64}
	rw := NewRowWriter(2, 64)
	rw.WriteString(0, "Alice")

	aw := NewFixedArrayWriter(3, 8)
	aw.WriteInt64(0, 10)
	aw.WriteInt64(1, 20)
	aw.WriteInt64(2, 30)
	rw.WriteArray(1, aw)

	rr := NewRowReader(rw.Bytes(), 2)
	require.Equal(t, "Alice", rr.ReadString(0))

	ar := rr.ReadFixedArray(1, 8)
	require.Equal(t, 3, ar.Len())
	require.Equal(t, int64(10), ar.ReadInt64(0))
	require.Equal(t, int64(20), ar.ReadInt64(1))
	require.Equal(t, int64(30), ar.ReadInt64(2))
}

// TestStringArrayInRow verifies a variable-width string array can be embedded in a row.
func TestStringArrayInRow(t *testing.T) {
	rw := NewRowWriter(1, 64)

	aw := NewVarArrayWriter(2, 32)
	aw.WriteString(0, "hello")
	aw.WriteString(1, "world")
	rw.WriteArray(0, aw)

	rr := NewRowReader(rw.Bytes(), 1)
	ar := rr.ReadVarArray(0)
	require.Equal(t, 2, ar.Len())
	require.Equal(t, "hello", ar.ReadString(0))
	require.Equal(t, "world", ar.ReadString(1))
}
