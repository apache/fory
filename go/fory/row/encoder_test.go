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
	"time"

	fory "github.com/apache/fory/go/fory"
	"github.com/stretchr/testify/require"
)

type address struct {
	City string
	Zip  int32
}

type person struct {
	Id    int64
	Name  *string
	Score float64
	Tags  []string
	Attrs map[string]int32
	Addr  *address
	Data  []byte
	Born  fory.Date
	At    time.Time
	Dur   time.Duration
}

func samplePerson() person {
	name := "ayush"
	return person{
		Id:    42,
		Name:  &name,
		Score: 99.5,
		Tags:  []string{"go", "fory"},
		Attrs: map[string]int32{"a": 1, "b": 2},
		Addr:  &address{City: "Delhi", Zip: 110001},
		Data:  []byte{1, 2, 3},
		Born:  fory.Date{Year: 2007, Month: time.June, Day: 1},
		At:    time.UnixMicro(1_234_567_890_123_456),
		Dur:   90 * time.Second,
	}
}

func TestEncoderRoundTrip(t *testing.T) {
	enc, err := NewEncoder[person]()
	require.NoError(t, err)

	original := samplePerson()
	rowBytes, err := enc.ToRow(&original)
	require.NoError(t, err)

	decoded, err := enc.FromRow(rowBytes)
	require.NoError(t, err)
	require.Equal(t, original, decoded)
}

func TestEncoderNullsAndEmptiness(t *testing.T) {
	enc, err := NewEncoder[person]()
	require.NoError(t, err)

	// All nilable fields nil: they round-trip as nil, not zero values.
	// Temporal fields stay valid; a zero fory.Date is rejected by
	// WriteDate just like in the object-graph serializer.
	original := samplePerson()
	original.Name, original.Tags, original.Attrs, original.Addr, original.Data = nil, nil, nil, nil, nil
	rowBytes, err := enc.ToRow(&original)
	require.NoError(t, err)
	decoded, err := enc.FromRow(rowBytes)
	require.NoError(t, err)
	require.Equal(t, original, decoded)
	require.Nil(t, decoded.Name)
	require.Nil(t, decoded.Tags)
	require.Nil(t, decoded.Attrs)

	// Empty is distinct from nil.
	original.Tags, original.Attrs, original.Data = []string{}, map[string]int32{}, []byte{}
	rowBytes, err = enc.ToRow(&original)
	require.NoError(t, err)
	decoded, err = enc.FromRow(rowBytes)
	require.NoError(t, err)
	require.NotNil(t, decoded.Tags)
	require.Empty(t, decoded.Tags)
	require.NotNil(t, decoded.Attrs)
	require.NotNil(t, decoded.Data)
}

func TestEncoderDeepNesting(t *testing.T) {
	type inner struct {
		Vals []int64
		KV   map[string][]int32
	}
	type outer struct {
		Rows []inner
		Ptrs []*int32
	}
	enc, err := NewEncoder[outer]()
	require.NoError(t, err)

	three := int32(3)
	original := outer{
		Rows: []inner{
			{Vals: []int64{1, 2}, KV: map[string][]int32{"x": {7, 8}}},
			{Vals: nil, KV: nil},
		},
		Ptrs: []*int32{&three, nil},
	}
	rowBytes, err := enc.ToRow(&original)
	require.NoError(t, err)
	decoded, err := enc.FromRow(rowBytes)
	require.NoError(t, err)
	require.Equal(t, original, decoded)
	require.Nil(t, decoded.Ptrs[1])
}

func TestEncodeDecodeFraming(t *testing.T) {
	enc, err := NewEncoder[person]()
	require.NoError(t, err)
	original := samplePerson()

	encoded, err := enc.Encode(&original)
	require.NoError(t, err)
	require.Equal(t, uint64(enc.SchemaHash()), binary.LittleEndian.Uint64(encoded))
	require.Equal(t, ComputeSchemaHash(enc.Schema()), enc.SchemaHash())

	decoded, err := enc.Decode(encoded)
	require.NoError(t, err)
	require.Equal(t, original, decoded)

	// Tampered hash is rejected with a descriptive error.
	tampered := append([]byte(nil), encoded...)
	tampered[0] ^= 0xFF
	_, err = enc.Decode(tampered)
	requireErrorContains(t, err, "hash mismatch")

	_, err = enc.Decode(encoded[:4])
	requireErrorContains(t, err, "too short")
}

// Corrupt row bytes must produce errors, never panics.
func TestEncoderCorruptRowData(t *testing.T) {
	enc, err := NewEncoder[person]()
	require.NoError(t, err)
	original := samplePerson()
	rowBytes, err := enc.ToRow(&original)
	require.NoError(t, err)

	for _, cut := range []int{1, 8, len(rowBytes) / 2, len(rowBytes) - 1} {
		_, err := enc.FromRow(rowBytes[:cut])
		require.Error(t, err, "truncated to %d bytes", cut)
	}
}

func TestNewEncoderRejectsNonStruct(t *testing.T) {
	_, err := NewEncoder[int]()
	require.Error(t, err)
	_, err = NewEncoder[map[string]int]()
	require.Error(t, err)
}
