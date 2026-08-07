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
	"strings"
	"sync"
	"testing"
	"time"

	fory "github.com/apache/fory/go/fory"
	"github.com/stretchr/testify/require"
)

func int64StringSchema() *Schema {
	return NewSchema([]Field{
		NewField("f1", Int64Type{}, false),
		NewField("f2", StringType{}, true),
	})
}

// The full byte image is fixed by the spec: 8-byte bitmap, one 8-byte
// slot per field (variable-width slots hold offset<<32|size relative to
// the row base), then the variable region zero-padded to 8 bytes.
func TestRowExactLayout(t *testing.T) {
	w := NewRowWriter(int64StringSchema())
	w.Reset()
	w.WriteInt64(0, 0x0102030405060708)
	w.WriteString(1, "ab")
	require.Equal(t, []byte{
		0, 0, 0, 0, 0, 0, 0, 0, // null bitmap
		0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01, // f1 slot
		0x02, 0x00, 0x00, 0x00, 0x18, 0x00, 0x00, 0x00, // f2 slot: size=2, offset=24
		'a', 'b', 0, 0, 0, 0, 0, 0, // variable region, zero-padded
	}, w.ToBytes())

	r := NewRow(w.Schema(), w.ToBytes())
	require.Equal(t, int64(0x0102030405060708), r.Int64(0))
	require.Equal(t, "ab", r.String(1))
}

func TestNullAndEmptyString(t *testing.T) {
	w := NewRowWriter(int64StringSchema())
	w.Reset()
	w.WriteInt64(0, 42)
	w.SetNullAt(1)
	require.Equal(t, []byte{
		0x02, 0, 0, 0, 0, 0, 0, 0, // bit 1 set: f2 is null
		42, 0, 0, 0, 0, 0, 0, 0,
		0, 0, 0, 0, 0, 0, 0, 0, // null slot zeroed
	}, w.ToBytes())
	r := NewRow(w.Schema(), w.ToBytes())
	require.True(t, r.IsNullAt(1))
	require.Nil(t, r.Binary(1))
	require.Equal(t, "", r.String(1))

	// An empty string is not null: bit clear, size 0, no variable bytes.
	w.Buffer().SetWriterIndex(0)
	w.Reset()
	w.WriteInt64(0, 42)
	w.WriteString(1, "")
	r = NewRow(w.Schema(), w.ToBytes())
	require.Equal(t, 24, r.SizeBytes())
	require.False(t, r.IsNullAt(1))
	require.Equal(t, "", r.String(1))
	require.NotNil(t, r.Binary(1))
	require.Empty(t, r.Binary(1))
}

func TestPrimitiveRoundTrip(t *testing.T) {
	s := NewSchema([]Field{
		NewField("b", BoolType{}, false),
		NewField("i8", Int8Type{}, false),
		NewField("i16", Int16Type{}, false),
		NewField("i32", Int32Type{}, false),
		NewField("i64", Int64Type{}, false),
		NewField("f32", Float32Type{}, false),
		NewField("f64", Float64Type{}, false),
		NewField("bin", BinaryType{}, true),
	})
	w := NewRowWriter(s)
	w.Reset()
	w.WriteBool(0, true)
	w.WriteInt8(1, -5)
	w.WriteInt16(2, -1000)
	w.WriteInt32(3, -100000)
	w.WriteInt64(4, -5_000_000_000_000)
	w.WriteFloat32(5, 3.5)
	w.WriteFloat64(6, -2.25)
	w.WriteBytes(7, []byte{1, 2, 3})

	r := NewRow(s, w.ToBytes())
	require.True(t, r.Bool(0))
	require.Equal(t, int8(-5), r.Int8(1))
	require.Equal(t, int16(-1000), r.Int16(2))
	require.Equal(t, int32(-100000), r.Int32(3))
	require.Equal(t, int64(-5_000_000_000_000), r.Int64(4))
	require.Equal(t, float32(3.5), r.Float32(5))
	require.Equal(t, -2.25, r.Float64(6))
	require.Equal(t, []byte{1, 2, 3}, r.Binary(7))
}

func TestTemporalRoundTrip(t *testing.T) {
	s := NewSchema([]Field{
		NewField("d", Date32Type{}, false),
		NewField("ts", TimestampType{}, false),
		NewField("dur", DurationType{}, false),
	})
	w := NewRowWriter(s)
	w.Reset()
	date := fory.Date{Year: 2023, Month: time.March, Day: 15}
	require.NoError(t, w.WriteDate(0, date))
	w.WriteTimestamp(1, time.UnixMicro(1_234_567_890_123_456))
	w.WriteDuration(2, 90*time.Second)

	r := NewRow(s, w.ToBytes())
	require.Equal(t, date, r.Date(0))
	require.Equal(t, int64(1_234_567_890_123_456), r.Timestamp(1).UnixMicro())
	require.Equal(t, 90*time.Second, r.Duration(2))
}

// A 65-field schema needs a 16-byte bitmap, shifting every slot by 16.
func TestMultiWordBitmap(t *testing.T) {
	fields := make([]Field, 65)
	for i := range fields {
		fields[i] = NewField("f"+string(rune('a'+i%26))+string(rune('0'+i/26)), Int64Type{}, true)
	}
	s := NewSchema(fields)
	w := NewRowWriter(s)
	w.Reset()
	w.WriteInt64(0, 100)
	w.WriteInt64(64, 200)
	w.SetNullAt(63)

	data := w.ToBytes()
	require.Equal(t, 16+65*8, len(data))
	require.Equal(t, byte(0x80), data[7], "bit 63 is the top bit of bitmap byte 7")

	r := NewRow(s, data)
	require.Equal(t, int64(100), r.Int64(0))
	require.Equal(t, int64(200), r.Int64(64))
	require.True(t, r.IsNullAt(63))
	require.Equal(t, int64(0), r.Int64(63))
}

// The buffer reuses dirty capacity, so a rewritten smaller row must
// still produce byte-identical output with zeroed padding and slots.
func TestWriterReuseZeroesDirtyBuffer(t *testing.T) {
	w := NewRowWriter(int64StringSchema())
	w.Reset()
	w.WriteInt64(0, 1)
	w.WriteString(1, strings.Repeat("Z", 64))

	w.Buffer().SetWriterIndex(0)
	w.Reset()
	w.WriteInt64(0, 0x0102030405060708)
	w.WriteString(1, "ab")
	require.Equal(t, []byte{
		0, 0, 0, 0, 0, 0, 0, 0,
		0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01,
		0x02, 0x00, 0x00, 0x00, 0x18, 0x00, 0x00, 0x00,
		'a', 'b', 0, 0, 0, 0, 0, 0,
	}, w.ToBytes())

	w.Buffer().SetWriterIndex(0)
	w.Reset()
	w.WriteInt64(0, 42)
	w.SetNullAt(1)
	require.Equal(t, []byte{
		0x02, 0, 0, 0, 0, 0, 0, 0,
		42, 0, 0, 0, 0, 0, 0, 0,
		0, 0, 0, 0, 0, 0, 0, 0,
	}, w.ToBytes())
}

func TestNestedStruct(t *testing.T) {
	inner := Struct([]Field{
		NewField("x", Float64Type{}, false),
		NewField("s", StringType{}, true),
	})
	s := NewSchema([]Field{
		NewField("id", Int64Type{}, false),
		NewField("inner", inner, true),
	})
	w := NewRowWriter(s)
	w.Reset()
	w.WriteInt64(0, 7)
	child := NewRowWriterWithBuffer(NewSchema(inner.Fields), w.Buffer())
	start := w.Buffer().WriterIndex()
	child.Reset()
	child.WriteFloat64(0, 3.5)
	child.WriteString(1, "hi")
	w.SetOffsetAndSize(1, start, w.Buffer().WriterIndex()-start)

	r := NewRow(s, w.ToBytes())
	require.Equal(t, int64(7), r.Int64(0))
	nested := r.Struct(1)
	require.NotNil(t, nested)
	require.Equal(t, 3.5, nested.Float64(0))
	require.Equal(t, "hi", nested.String(1))
}

// A value larger than the initial buffer forces mid-row growth; slots
// patched after reallocation must still land in the right place.
func TestLargeStringGrowth(t *testing.T) {
	s := NewSchema([]Field{NewField("s", StringType{}, true)})
	w := NewRowWriter(s)
	w.Reset()
	big := strings.Repeat("x", 5000)
	w.WriteString(0, big)

	r := NewRow(s, w.ToBytes())
	require.Equal(t, big, r.String(0))
	require.Equal(t, 8+8+roundToWord(5000), r.SizeBytes())
}

// One-past-the-end indices must be rejected, matching the other
// row format implementations.
func TestOutOfRangeIndexPanics(t *testing.T) {
	w := NewRowWriter(int64StringSchema())
	w.Reset()
	require.Panics(t, func() { w.WriteInt64(2, 1) })
	require.Panics(t, func() { w.SetNullAt(-1) })

	r := NewRow(w.Schema(), w.ToBytes())
	require.Panics(t, func() { r.Int64(2) })
	require.Panics(t, func() { r.IsNullAt(-1) })
}

// The wire format packs offset and size into 32 bits each; larger
// values must be rejected, never silently truncated.
func TestOffsetAndSizeRejectWireLimit(t *testing.T) {
	w := NewRowWriter(int64StringSchema())
	w.Reset()
	require.Error(t, w.SetOffsetAndSize(1, w.Buffer().WriterIndex(), 1<<33))

	aw := NewArrayWriter(List(StringType{}).Elem, w.Buffer())
	require.NoError(t, aw.Reset(1))
	require.Error(t, aw.SetOffsetAndSize(0, w.Buffer().WriterIndex(), 1<<33))
}

func TestConcurrentReads(t *testing.T) {
	w := NewRowWriter(int64StringSchema())
	w.Reset()
	w.WriteInt64(0, 99)
	w.WriteString(1, "shared")
	r := NewRow(w.Schema(), w.ToBytes())

	var wg sync.WaitGroup
	for g := 0; g < 4; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 200; j++ {
				require.Equal(t, int64(99), r.Int64(0))
				require.Equal(t, "shared", r.String(1))
			}
		}()
	}
	wg.Wait()
}
