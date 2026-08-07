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
	"testing"

	fory "github.com/apache/fory/go/fory"
	"github.com/stretchr/testify/require"
)

// Maps are an 8-byte keys-array size followed by two complete arrays;
// both halves must decode independently.
func TestMapLayout(t *testing.T) {
	buf := fory.NewByteBuffer(nil)
	mapType := Map(StringType{}, Int64Type{})
	m := NewMapWriter(buf)
	m.Reset()
	keys := NewArrayWriter(mapType.Key, buf)
	require.NoError(t, keys.Reset(2))
	keys.WriteString(0, "k1")
	keys.WriteString(1, "k2")
	m.FinishKeys()
	values := NewArrayWriter(mapType.Value, buf)
	require.NoError(t, values.Reset(2))
	values.WriteInt64(0, 10)
	values.WriteInt64(1, 20)

	data := buf.GetByteSlice(0, buf.WriterIndex())
	// keys array: 8 count + 8 bitmap + 2*8 slots + 2*8 padded strings.
	require.Equal(t, uint64(48), binary.LittleEndian.Uint64(data))
	require.Equal(t, 8+48+32, m.Size())

	md := NewMapData(mapType, data)
	require.Equal(t, 2, md.NumElements())
	require.Equal(t, "k1", md.Keys().String(0))
	require.Equal(t, "k2", md.Keys().String(1))
	require.Equal(t, int64(10), md.Values().Int64(0))
	require.Equal(t, int64(20), md.Values().Int64(1))
}

func TestMapFieldInRow(t *testing.T) {
	mapType := Map(StringType{}, Int32Type{})
	s := NewSchema([]Field{
		NewField("id", Int64Type{}, false),
		NewField("attrs", mapType, true),
	})
	w := NewRowWriter(s)
	w.Reset()
	w.WriteInt64(0, 1)

	buf := w.Buffer()
	start := buf.WriterIndex()
	m := NewMapWriter(buf)
	m.Reset()
	keys := NewArrayWriter(mapType.Key, buf)
	require.NoError(t, keys.Reset(2))
	keys.WriteString(0, "a")
	keys.WriteString(1, "b")
	m.FinishKeys()
	values := NewArrayWriter(mapType.Value, buf)
	require.NoError(t, values.Reset(2))
	values.WriteInt32(0, 1)
	values.SetNullAt(1)
	w.SetOffsetAndSize(1, start, buf.WriterIndex()-start)

	r := NewRow(s, w.ToBytes())
	md := r.Map(1)
	require.NotNil(t, md)
	require.Equal(t, 2, md.NumElements())
	require.Equal(t, "a", md.Keys().String(0))
	require.Equal(t, int32(1), md.Values().Int32(0))
	require.True(t, md.Values().IsNullAt(1))
}
