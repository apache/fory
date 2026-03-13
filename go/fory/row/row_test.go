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
	"encoding/hex"
	"math"
	"testing"

	"github.com/stretchr/testify/require"
)

// javaGoldenNullHex is the Java golden hex for {id=1, value=null} (null-field cross-language test).
const javaGoldenNullHex = "020000000000000001000000000000000000000000000000"

// javaGoldenArrayHex is the Java golden hex for {id=7, scores=[10,20,30]} (array cross-language test).
const javaGoldenArrayHex = "000000000000000007000000000000002800000018000000" +
	"030000000000000000000000000000000a00000000000000" +
	"14000000000000001e00000000000000"

// javaGoldenMapHex is the Java golden hex for {id=1, config={"a":100}} (map cross-language test).
const javaGoldenMapHex = "00000000000000000100000000000000400000001800000020000000000000000100000000000000" +
	"000000000000000001000000180000006100000000000000" +
	"010000000000000000000000000000006400000000000000"

// javaGoldenHex is the Java golden hex for {id=42, name="Alice"} (basic cross-language test).
const javaGoldenHex = "00000000000000002a000000000000000500000018000000416c696365000000"

// TestLayoutConstants validates the pure arithmetic at the foundation of every other test.
func TestLayoutConstants(t *testing.T) {
	require.Equal(t, 8, BitmapSize(2), "2-field bitmap must be 8 bytes")
	require.Equal(t, 24, FixedRegionSize(2), "2-field fixed region = 8 + 2*8")
	require.Equal(t, 8, SlotOffset(0, 2), "field 0 slot starts immediately after bitmap")
	require.Equal(t, 16, SlotOffset(1, 2), "field 1 slot = bitmap + 8")
	require.Equal(t, 8, PadTo8(5), "5 bytes pads to 8")
	require.Equal(t, 8, PadTo8(8), "8 bytes needs no padding")
	require.Equal(t, 16, PadTo8(9), "9 bytes pads to 16")
}

// TestRoundtrip encodes then decodes with Go only, verifying logical correctness.
func TestRoundtrip(t *testing.T) {
	w := NewRowWriter(2, 16)
	w.WriteInt64(0, 42)
	w.WriteString(1, "Alice")

	r := NewRowReader(w.Bytes(), 2)
	require.False(t, r.IsNull(0))
	require.False(t, r.IsNull(1))
	require.Equal(t, int64(42), r.ReadInt64(0))
	require.Equal(t, "Alice", r.ReadString(1))
}

// TestGoldenEncode verifies the Go encoder produces bytes identical to the Java implementation.
func TestGoldenEncode(t *testing.T) {
	w := NewRowWriter(2, 16)
	w.WriteInt64(0, 42)
	w.WriteString(1, "Alice")

	got := hex.EncodeToString(w.Bytes())
	require.Equal(t, javaGoldenHex, got,
		"Go encoder output does not match Java golden bytes — check slot packing or padding")
}

// TestRandomAccess parses the Java golden bytes and reads id without touching the string region.
func TestRandomAccess(t *testing.T) {
	raw, err := hex.DecodeString(javaGoldenHex)
	require.NoError(t, err)

	r := NewRowReader(raw, 2)

	// Reading id touches only bytes 8-15; the variable region (bytes 24-31) is never read.
	require.Equal(t, int64(42), r.ReadInt64(0))

	require.False(t, r.IsNull(0))
	require.False(t, r.IsNull(1))
	require.Equal(t, "Alice", r.ReadString(1))
}

// TestNullBitmap verifies the bit=1=null convention from both writer and reader sides.
func TestNullBitmap(t *testing.T) {
	w := NewRowWriter(3, 0)
	w.SetNull(0)
	w.SetNull(2) // field 1 intentionally left non-null

	r := NewRowReader(w.Bytes(), 3)
	require.True(t, r.IsNull(0))
	require.False(t, r.IsNull(1))
	require.True(t, r.IsNull(2))
}

// TestMultipleStrings validates the variable-cursor advances correctly across two string fields.
func TestMultipleStrings(t *testing.T) {
	// Schema: {a string, b string} — 2 variable-width fields
	w := NewRowWriter(2, 32)
	w.WriteString(0, "hello")  // 5 bytes → padded to 8, relOffset=16
	w.WriteString(1, "world!") // 6 bytes → padded to 8, relOffset=24

	r := NewRowReader(w.Bytes(), 2)
	require.Equal(t, "hello", r.ReadString(0))
	require.Equal(t, "world!", r.ReadString(1))
}

// TestEmptyString ensures a zero-length string produces a valid zero-size slot entry.
func TestEmptyString(t *testing.T) {
	w := NewRowWriter(1, 0)
	w.WriteString(0, "")

	r := NewRowReader(w.Bytes(), 1)
	require.Equal(t, "", r.ReadString(0))
}

// TestAllFixedWidthTypes verifies every fixed-width type encodes and decodes correctly.
func TestAllFixedWidthTypes(t *testing.T) {
	// Schema: {bool, int8, int16, int32, int64, float32, float64, date32, timestamp, duration}
	w := NewRowWriter(10, 0)
	w.WriteBool(0, true)
	w.WriteInt8(1, -128)
	w.WriteInt16(2, -32768)
	w.WriteInt32(3, math.MinInt32)
	w.WriteInt64(4, math.MinInt64)
	w.WriteFloat32(5, math.SmallestNonzeroFloat32)
	w.WriteFloat64(6, math.Pi)
	w.WriteDate32(7, 19000)                    // days since epoch
	w.WriteTimestamp(8, 1_700_000_000_000_000) // microseconds since epoch
	w.WriteDuration(9, -1)                     // negative duration

	r := NewRowReader(w.Bytes(), 10)
	require.True(t, r.ReadBool(0))
	require.Equal(t, int8(-128), r.ReadInt8(1))
	require.Equal(t, int16(-32768), r.ReadInt16(2))
	require.Equal(t, int32(math.MinInt32), r.ReadInt32(3))
	require.Equal(t, int64(math.MinInt64), r.ReadInt64(4))
	require.Equal(t, float32(math.SmallestNonzeroFloat32), r.ReadFloat32(5))
	require.Equal(t, math.Pi, r.ReadFloat64(6))
	require.Equal(t, int32(19000), r.ReadDate32(7))
	require.Equal(t, int64(1_700_000_000_000_000), r.ReadTimestamp(8))
	require.Equal(t, int64(-1), r.ReadDuration(9))
}

// TestBinaryField verifies raw binary bytes round-trip correctly.
func TestBinaryField(t *testing.T) {
	payload := []byte{0x00, 0xFF, 0xDE, 0xAD, 0xBE, 0xEF}
	w := NewRowWriter(1, 16)
	w.WriteBinary(0, payload)

	r := NewRowReader(w.Bytes(), 1)
	require.Equal(t, payload, r.ReadBinary(0))
}

// TestMultiByteUTF8 verifies that multi-byte UTF-8 strings round-trip correctly.
func TestMultiByteUTF8(t *testing.T) {
	s := "こんにちは🌍" // 5×3-byte hiragana + 4-byte emoji = 19 bytes
	w := NewRowWriter(1, 32)
	w.WriteString(0, s)

	r := NewRowReader(w.Bytes(), 1)
	require.Equal(t, s, r.ReadString(0))
}

// TestBitmapBoundary64Fields verifies that a 64-field row uses an 8-byte bitmap and all slots are reachable.
func TestBitmapBoundary64Fields(t *testing.T) {
	const n = 64
	require.Equal(t, 8, BitmapSize(n), "64-field bitmap must be 8 bytes")

	w := NewRowWriter(n, 0)
	for i := 0; i < n; i++ {
		w.WriteInt64(i, int64(i))
	}

	r := NewRowReader(w.Bytes(), n)
	for i := 0; i < n; i++ {
		require.Equal(t, int64(i), r.ReadInt64(i), "field %d", i)
	}
}

// TestBitmapBoundary65Fields verifies that a 65-field row bumps the bitmap to 16 bytes.
func TestBitmapBoundary65Fields(t *testing.T) {
	const n = 65
	require.Equal(t, 16, BitmapSize(n), "65-field bitmap must be 16 bytes")

	w := NewRowWriter(n, 0)
	for i := 0; i < n; i++ {
		w.WriteInt64(i, int64(i*10))
	}
	w.SetNull(64) // last field null

	r := NewRowReader(w.Bytes(), n)
	for i := 0; i < 64; i++ {
		require.False(t, r.IsNull(i), "field %d should not be null", i)
		require.Equal(t, int64(i*10), r.ReadInt64(i), "field %d", i)
	}
	require.True(t, r.IsNull(64), "field 64 should be null")
}

// TestNestedRow verifies a row can be embedded in another row's variable region.
func TestNestedRow(t *testing.T) {
	// Child row: {score int64, label string}
	child := NewRowWriter(2, 16)
	child.WriteInt64(0, 99)
	child.WriteString(1, "pass")

	// Parent row: {id int64, result <nested row>}
	parent := NewRowWriter(2, 64)
	parent.WriteInt64(0, 7)
	parent.WriteNestedRow(1, child)

	pr := NewRowReader(parent.Bytes(), 2)
	require.Equal(t, int64(7), pr.ReadInt64(0))

	cr := pr.ReadNestedRow(1, 2)
	require.Equal(t, int64(99), cr.ReadInt64(0))
	require.Equal(t, "pass", cr.ReadString(1))
}

// TestConstructionPanicUndersized verifies NewRowReader panics when the buffer is too small.
func TestConstructionPanicUndersized(t *testing.T) {
	require.Panics(t, func() {
		NewRowReader([]byte{0x00}, 2) // 2-field fixed region = 24 bytes; 1 byte is too small
	})
}

// TestConstructionPanicNegativeFields verifies NewRowReader panics on negative numFields.
func TestConstructionPanicNegativeFields(t *testing.T) {
	require.Panics(t, func() {
		NewRowReader([]byte{}, -1)
	})
}

// TestWriteBoolFalse verifies the false branch of WriteBool (covers the 66.7% gap).
func TestWriteBoolFalse(t *testing.T) {
	w := NewRowWriter(2, 0)
	w.WriteBool(0, true)
	w.WriteBool(1, false)

	r := NewRowReader(w.Bytes(), 2)
	require.True(t, r.ReadBool(0))
	require.False(t, r.ReadBool(1))
}

// TestWriterBufferGrowth verifies the buffer grows correctly when the initial extraVarBytes hint is too small.
func TestWriterBufferGrowth(t *testing.T) {
	// Start with 0 extra bytes — the first variable-length write must trigger growth.
	w := NewRowWriter(1, 0)
	long := "this string is definitely longer than zero extra bytes allocated"
	w.WriteString(0, long)

	r := NewRowReader(w.Bytes(), 1)
	require.Equal(t, long, r.ReadString(0))
}

// TestZeroFieldRow verifies a zero-field row produces an empty buffer and reads back cleanly.
func TestZeroFieldRow(t *testing.T) {
	require.Equal(t, 0, FixedRegionSize(0))
	w := NewRowWriter(0, 0)
	r := NewRowReader(w.Bytes(), 0)
	_ = r // nothing to read; just verify no panic
}

// TestGoldenEncodeNull verifies the Go encoder matches Java for a row containing a null field.
func TestGoldenEncodeNull(t *testing.T) {
	w := NewRowWriter(2, 0)
	w.WriteInt64(0, 1)
	w.SetNull(1)

	got := hex.EncodeToString(w.Bytes())
	require.Equal(t, javaGoldenNullHex, got,
		"Go null encoding does not match Java golden — check bitmap bit ordering")
}

// TestGoldenDecodeNull parses javaGoldenNullHex and verifies field 1 is null.
func TestGoldenDecodeNull(t *testing.T) {
	raw, err := hex.DecodeString(javaGoldenNullHex)
	require.NoError(t, err)

	r := NewRowReader(raw, 2)
	require.False(t, r.IsNull(0))
	require.True(t, r.IsNull(1))
	require.Equal(t, int64(1), r.ReadInt64(0))
}

// TestGoldenEncodeArray verifies the Go encoder matches Java for a row with a fixed int64 array.
func TestGoldenEncodeArray(t *testing.T) {
	w := NewRowWriter(2, 48)
	w.WriteInt64(0, 7)
	aw := NewFixedArrayWriter(3, 8)
	aw.WriteInt64(0, 10)
	aw.WriteInt64(1, 20)
	aw.WriteInt64(2, 30)
	w.WriteArray(1, aw)

	got := hex.EncodeToString(w.Bytes())
	require.Equal(t, javaGoldenArrayHex, got,
		"Go array encoding does not match Java golden — check array header or element layout")
}

// TestGoldenDecodeArray parses javaGoldenArrayHex and reads the scores array.
func TestGoldenDecodeArray(t *testing.T) {
	raw, err := hex.DecodeString(javaGoldenArrayHex)
	require.NoError(t, err)

	r := NewRowReader(raw, 2)
	require.Equal(t, int64(7), r.ReadInt64(0))

	ar := r.ReadFixedArray(1, 8)
	require.Equal(t, 3, ar.Len())
	require.Equal(t, int64(10), ar.ReadInt64(0))
	require.Equal(t, int64(20), ar.ReadInt64(1))
	require.Equal(t, int64(30), ar.ReadInt64(2))
}

// TestGoldenEncodeMap verifies the Go encoder matches Java for a row with a string→int64 map.
func TestGoldenEncodeMap(t *testing.T) {
	keys := NewVarArrayWriter(1, 16)
	keys.WriteString(0, "a")

	vals := NewFixedArrayWriter(1, 8)
	vals.WriteInt64(0, 100)

	mw := NewMapWriter(keys, vals)

	w := NewRowWriter(2, 96)
	w.WriteInt64(0, 1)
	w.WriteMap(1, mw)

	got := hex.EncodeToString(w.Bytes())
	require.Equal(t, javaGoldenMapHex, got,
		"Go map encoding does not match Java golden — check keysSize prefix or array slot encoding")
}

// TestGoldenDecodeMap parses javaGoldenMapHex and reads the config map.
func TestGoldenDecodeMap(t *testing.T) {
	raw, err := hex.DecodeString(javaGoldenMapHex)
	require.NoError(t, err)

	r := NewRowReader(raw, 2)
	require.Equal(t, int64(1), r.ReadInt64(0))

	mr := r.ReadMap(1)
	kr := mr.KeysVarArray()
	vr := mr.ValuesFixedArray(8)

	require.Equal(t, 1, kr.Len())
	require.Equal(t, "a", kr.ReadString(0))
	require.Equal(t, 1, vr.Len())
	require.Equal(t, int64(100), vr.ReadInt64(0))
}

// TestNullSlotIsZero verifies that a null field's slot bytes are zero.
func TestNullSlotIsZero(t *testing.T) {
	w := NewRowWriter(3, 0)
	w.WriteInt64(0, 99)
	w.SetNull(1)
	w.WriteInt64(2, 77)

	b := w.Bytes()
	// Slot for field 1 starts at BitmapSize(3)+8 = 8+8 = 16; must be all zero.
	for i := 16; i < 24; i++ {
		require.Equal(t, byte(0), b[i], "null slot byte %d must be zero", i)
	}
}

// TestAllFieldsNull verifies every field can be independently nulled in a large row.
func TestAllFieldsNull(t *testing.T) {
	const n = 10
	w := NewRowWriter(n, 0)
	for i := 0; i < n; i++ {
		w.SetNull(i)
	}

	r := NewRowReader(w.Bytes(), n)
	for i := 0; i < n; i++ {
		require.True(t, r.IsNull(i), "field %d should be null", i)
	}
}

// TestMixedNullAndValue verifies null and non-null fields interleave correctly across a bitmap word boundary.
func TestMixedNullAndValue(t *testing.T) {
	const n = 66
	w := NewRowWriter(n, 0)
	w.WriteInt64(0, 1)
	w.SetNull(1)
	w.WriteInt64(63, 63)
	w.SetNull(64)
	w.WriteInt64(65, 65)

	r := NewRowReader(w.Bytes(), n)
	require.False(t, r.IsNull(0))
	require.True(t, r.IsNull(1))
	require.False(t, r.IsNull(63))
	require.True(t, r.IsNull(64))
	require.False(t, r.IsNull(65))
	require.Equal(t, int64(1), r.ReadInt64(0))
	require.Equal(t, int64(63), r.ReadInt64(63))
	require.Equal(t, int64(65), r.ReadInt64(65))
}

// TestVariableFieldsOutOfOrder writes variable-width fields in reverse ordinal order and verifies correct reads.
func TestVariableFieldsOutOfOrder(t *testing.T) {
	w := NewRowWriter(3, 64)
	w.WriteString(2, "last")
	w.WriteString(0, "first")
	w.WriteString(1, "middle")

	r := NewRowReader(w.Bytes(), 3)
	require.Equal(t, "first", r.ReadString(0))
	require.Equal(t, "middle", r.ReadString(1))
	require.Equal(t, "last", r.ReadString(2))
}

// TestEmptyBinary verifies a zero-length binary field round-trips without panic.
func TestEmptyBinary(t *testing.T) {
	w := NewRowWriter(1, 0)
	w.WriteBinary(0, []byte{})

	r := NewRowReader(w.Bytes(), 1)
	require.Equal(t, []byte{}, r.ReadBinary(0))
}

// TestLargeBinaryField verifies a >64-byte binary payload is stored and retrieved intact.
func TestLargeBinaryField(t *testing.T) {
	payload := make([]byte, 200)
	for i := range payload {
		payload[i] = byte(i % 251)
	}
	w := NewRowWriter(1, 256)
	w.WriteBinary(0, payload)

	r := NewRowReader(w.Bytes(), 1)
	require.Equal(t, payload, r.ReadBinary(0))
}

// TestSlotIndependence verifies that writing field N does not corrupt adjacent field slots.
func TestSlotIndependence(t *testing.T) {
	w := NewRowWriter(4, 32)
	w.WriteInt64(0, 111)
	w.WriteString(1, "x")
	w.WriteInt64(2, 333)
	w.WriteString(3, "y")

	r := NewRowReader(w.Bytes(), 4)
	require.Equal(t, int64(111), r.ReadInt64(0))
	require.Equal(t, "x", r.ReadString(1))
	require.Equal(t, int64(333), r.ReadInt64(2))
	require.Equal(t, "y", r.ReadString(3))
}

// TestNestedRowNullField verifies a null field inside a nested row is correctly propagated.
func TestNestedRowNullField(t *testing.T) {
	child := NewRowWriter(2, 0)
	child.WriteInt64(0, 5)
	child.SetNull(1)

	parent := NewRowWriter(1, 32)
	parent.WriteNestedRow(0, child)

	pr := NewRowReader(parent.Bytes(), 1)
	cr := pr.ReadNestedRow(0, 2)
	require.Equal(t, int64(5), cr.ReadInt64(0))
	require.True(t, cr.IsNull(1))
}

// TestMapEmptyArrays verifies a map with zero-element key and value arrays round-trips cleanly.
func TestMapEmptyArrays(t *testing.T) {
	keys := NewFixedArrayWriter(0, 8)
	vals := NewFixedArrayWriter(0, 8)
	mw := NewMapWriter(keys, vals)

	mr := NewMapReader(mw.Bytes())
	kr := mr.KeysFixedArray(8)
	vr := mr.ValuesFixedArray(8)
	require.Equal(t, 0, kr.Len())
	require.Equal(t, 0, vr.Len())
}

// TestMapInRowRoundtrip verifies a map embedded in a row survives encode→decode intact.
func TestMapInRowRoundtrip(t *testing.T) {
	keys := NewVarArrayWriter(3, 32)
	keys.WriteString(0, "alpha")
	keys.WriteString(1, "beta")
	keys.WriteString(2, "gamma")

	vals := NewFixedArrayWriter(3, 8)
	vals.WriteInt64(0, -1)
	vals.WriteInt64(1, 0)
	vals.WriteInt64(2, math.MaxInt64)

	rw := NewRowWriter(2, 128)
	rw.WriteInt64(0, 42)
	rw.WriteMap(1, NewMapWriter(keys, vals))

	rr := NewRowReader(rw.Bytes(), 2)
	require.Equal(t, int64(42), rr.ReadInt64(0))

	mr := rr.ReadMap(1)
	kr := mr.KeysVarArray()
	vr := mr.ValuesFixedArray(8)

	require.Equal(t, "alpha", kr.ReadString(0))
	require.Equal(t, "beta", kr.ReadString(1))
	require.Equal(t, "gamma", kr.ReadString(2))
	require.Equal(t, int64(-1), vr.ReadInt64(0))
	require.Equal(t, int64(0), vr.ReadInt64(1))
	require.Equal(t, int64(math.MaxInt64), vr.ReadInt64(2))
}
