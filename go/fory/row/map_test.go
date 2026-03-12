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

// TestMapFixedFixed roundtrips a map with int64 keys and int64 values.
func TestMapFixedFixed(t *testing.T) {
	keys := NewFixedArrayWriter(3, 8)
	keys.WriteInt64(0, 1)
	keys.WriteInt64(1, 2)
	keys.WriteInt64(2, 3)

	vals := NewFixedArrayWriter(3, 8)
	vals.WriteInt64(0, 100)
	vals.WriteInt64(1, 200)
	vals.WriteInt64(2, 300)

	mw := NewMapWriter(keys, vals)
	mr := NewMapReader(mw.Bytes())

	kr := mr.KeysFixedArray(8)
	vr := mr.ValuesFixedArray(8)

	require.Equal(t, 3, kr.Len())
	require.Equal(t, 3, vr.Len())
	require.Equal(t, int64(1), kr.ReadInt64(0))
	require.Equal(t, int64(2), kr.ReadInt64(1))
	require.Equal(t, int64(3), kr.ReadInt64(2))
	require.Equal(t, int64(100), vr.ReadInt64(0))
	require.Equal(t, int64(200), vr.ReadInt64(1))
	require.Equal(t, int64(300), vr.ReadInt64(2))
}

// TestMapVarVar roundtrips a map with string keys and string values.
func TestMapVarVar(t *testing.T) {
	keys := NewVarArrayWriter(2, 32)
	keys.WriteString(0, "alpha")
	keys.WriteString(1, "beta")

	vals := NewVarArrayWriter(2, 32)
	vals.WriteString(0, "one")
	vals.WriteString(1, "two")

	mw := NewMapWriter(keys, vals)
	mr := NewMapReader(mw.Bytes())

	kr := mr.KeysVarArray()
	vr := mr.ValuesVarArray()

	require.Equal(t, 2, kr.Len())
	require.Equal(t, "alpha", kr.ReadString(0))
	require.Equal(t, "beta", kr.ReadString(1))
	require.Equal(t, "one", vr.ReadString(0))
	require.Equal(t, "two", vr.ReadString(1))
}

// TestMapVarFixed roundtrips a map with string keys and int64 values (mixed width types).
func TestMapVarFixed(t *testing.T) {
	keys := NewVarArrayWriter(2, 32)
	keys.WriteString(0, "score")
	keys.WriteString(1, "rank")

	vals := NewFixedArrayWriter(2, 8)
	vals.WriteInt64(0, 42)
	vals.WriteInt64(1, 1)

	mw := NewMapWriter(keys, vals)
	mr := NewMapReader(mw.Bytes())

	kr := mr.KeysVarArray()
	vr := mr.ValuesFixedArray(8)

	require.Equal(t, "score", kr.ReadString(0))
	require.Equal(t, "rank", kr.ReadString(1))
	require.Equal(t, int64(42), vr.ReadInt64(0))
	require.Equal(t, int64(1), vr.ReadInt64(1))
}

// TestMapNullValues verifies null bitmap handling inside a map's values array.
func TestMapNullValues(t *testing.T) {
	keys := NewFixedArrayWriter(3, 8)
	keys.WriteInt64(0, 10)
	keys.WriteInt64(1, 20)
	keys.WriteInt64(2, 30)

	vals := NewFixedArrayWriter(3, 8)
	vals.WriteInt64(0, 100)
	vals.SetNull(1) // middle value is null
	vals.WriteInt64(2, 300)

	mw := NewMapWriter(keys, vals)
	mr := NewMapReader(mw.Bytes())

	vr := mr.ValuesFixedArray(8)
	require.False(t, vr.IsNull(0))
	require.True(t, vr.IsNull(1))
	require.False(t, vr.IsNull(2))
	require.Equal(t, int64(100), vr.ReadInt64(0))
	require.Equal(t, int64(300), vr.ReadInt64(2))
}

// TestMapInRow verifies a map can be embedded in a row field and read back correctly.
func TestMapInRow(t *testing.T) {
	// Row schema: {name string, config map<string,int64>}
	keys := NewVarArrayWriter(2, 32)
	keys.WriteString(0, "timeout")
	keys.WriteString(1, "retries")

	vals := NewFixedArrayWriter(2, 8)
	vals.WriteInt64(0, 30)
	vals.WriteInt64(1, 3)

	mw := NewMapWriter(keys, vals)

	rw := NewRowWriter(2, 128)
	rw.WriteString(0, "service-a")
	rw.WriteMap(1, mw)

	rr := NewRowReader(rw.Bytes(), 2)
	require.Equal(t, "service-a", rr.ReadString(0))

	mr := rr.ReadMap(1)
	kr := mr.KeysVarArray()
	vr := mr.ValuesFixedArray(8)

	require.Equal(t, 2, kr.Len())
	require.Equal(t, "timeout", kr.ReadString(0))
	require.Equal(t, "retries", kr.ReadString(1))
	require.Equal(t, int64(30), vr.ReadInt64(0))
	require.Equal(t, int64(3), vr.ReadInt64(1))
}

// TestMapKeySizePrefix verifies the 8-byte keys-size prefix is written correctly.
// The prefix must equal len(keysArray.Bytes()) exactly.
func TestMapKeySizePrefix(t *testing.T) {
	keys := NewFixedArrayWriter(2, 8)
	keys.WriteInt64(0, 1)
	keys.WriteInt64(1, 2)

	vals := NewFixedArrayWriter(1, 8)
	vals.WriteInt64(0, 99)

	kb := keys.Bytes()
	mw := NewMapWriter(keys, vals)
	buf := mw.Bytes()

	// First 8 bytes must encode len(kb) as little-endian uint64.
	require.Equal(t, uint64(len(kb)), readUint64LE(buf[0:8]))
}

// TestMapReaderPanicUndersized verifies NewMapReader panics on a buffer smaller than 8 bytes.
func TestMapReaderPanicUndersized(t *testing.T) {
	require.Panics(t, func() {
		NewMapReader([]byte{0x01, 0x02})
	})
}

// readUint64LE is a test helper to decode a uint64 from a byte slice.
func readUint64LE(b []byte) uint64 {
	return uint64(b[0]) | uint64(b[1])<<8 | uint64(b[2])<<16 | uint64(b[3])<<24 |
		uint64(b[4])<<32 | uint64(b[5])<<40 | uint64(b[6])<<48 | uint64(b[7])<<56
}
