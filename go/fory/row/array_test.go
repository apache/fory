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
	"encoding/binary"
	"math"
	"testing"

	fory "github.com/apache/fory/go/fory"
	"github.com/stretchr/testify/require"
)

// Arrays store an 8-byte count, a null bitmap rounded to 8-byte words,
// then elements at their natural width, padded to an 8-byte boundary.
func TestArrayExactLayout(t *testing.T) {
	buf := fory.NewByteBuffer(nil)
	w := NewArrayWriter(List(Int32Type{}).Elem, buf)
	require.NoError(t, w.Reset(3))
	w.WriteInt32(0, 1)
	w.WriteInt32(1, 2)
	w.WriteInt32(2, 3)
	require.Equal(t, []byte{
		3, 0, 0, 0, 0, 0, 0, 0, // element count
		0, 0, 0, 0, 0, 0, 0, 0, // null bitmap
		1, 0, 0, 0, 2, 0, 0, 0, // int32 elements at natural width
		3, 0, 0, 0, 0, 0, 0, 0, // last element + alignment padding
	}, w.ToBytes())

	a := NewArrayData(List(Int32Type{}).Elem, w.ToBytes())
	require.Equal(t, 3, a.NumElements())
	require.Equal(t, int32(2), a.Int32(1))
}

func TestStringArrayWithNullElement(t *testing.T) {
	buf := fory.NewByteBuffer(nil)
	elem := List(StringType{}).Elem
	w := NewArrayWriter(elem, buf)
	require.NoError(t, w.Reset(3))
	w.WriteString(0, "str1")
	w.SetNullAt(1)
	w.WriteString(2, "str2")

	a := NewArrayData(elem, w.ToBytes())
	require.Equal(t, 3, a.NumElements())
	require.Equal(t, "str1", a.String(0))
	require.True(t, a.IsNullAt(1))
	require.Equal(t, "", a.String(1))
	require.Equal(t, "str2", a.String(2))
}

func TestEmptyArray(t *testing.T) {
	buf := fory.NewByteBuffer(nil)
	elem := List(Int64Type{}).Elem
	w := NewArrayWriter(elem, buf)
	require.NoError(t, w.Reset(0))
	require.Equal(t, []byte{0, 0, 0, 0, 0, 0, 0, 0}, w.ToBytes())

	a := NewArrayData(elem, w.ToBytes())
	require.Equal(t, 0, a.NumElements())
	require.Panics(t, func() { a.Int64(0) })
}

func TestNestedArray(t *testing.T) {
	buf := fory.NewByteBuffer(nil)
	outerElem := List(List(Int32Type{})).Elem // item: list<int32>
	innerElem := List(Int32Type{}).Elem       // item: int32

	outer := NewArrayWriter(outerElem, buf)
	require.NoError(t, outer.Reset(2))
	inner := NewArrayWriter(innerElem, buf)
	for i, values := range [][]int32{{1, 2}, {3, 4, 5}} {
		start := buf.WriterIndex()
		require.NoError(t, inner.Reset(len(values)))
		for j, v := range values {
			inner.WriteInt32(j, v)
		}
		outer.SetOffsetAndSize(i, start, buf.WriterIndex()-start)
	}

	a := NewArrayData(outerElem, outer.ToBytes())
	require.Equal(t, 2, a.NumElements())
	first := a.Array(0)
	require.Equal(t, 2, first.NumElements())
	require.Equal(t, int32(2), first.Int32(1))
	second := a.Array(1)
	require.Equal(t, 3, second.NumElements())
	require.Equal(t, int32(5), second.Int32(2))
}

func TestArrayResetRejectsInvalidLength(t *testing.T) {
	w := NewArrayWriter(List(Int64Type{}).Elem, fory.NewByteBuffer(nil))
	require.Error(t, w.Reset(-1))
	require.Error(t, w.Reset(maxArrayDataBytes/8+1))
	// Extreme counts must fail the limit check, not overflow past it.
	require.Error(t, w.Reset(math.MaxInt))
}

// Forged element counts must fail validation even when the arithmetic
// would overflow int64 and wrap the required size negative.
func TestArrayValidateBoundsRejectsForgedCounts(t *testing.T) {
	for _, count := range []uint64{
		math.MaxUint64,              // negative after int conversion
		math.MaxInt64,               // bitmap width and product overflow
		uint64(math.MaxInt64/8) + 1, // product overflow with 8-byte elements
		1 << 32,                     // plausible-looking but far beyond the data
	} {
		data := make([]byte, 16)
		binary.LittleEndian.PutUint64(data, count)
		a := NewArrayData(List(Int64Type{}).Elem, data)
		require.Error(t, a.validateBounds(), "count %d", count)
	}
}
