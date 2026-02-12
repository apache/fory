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

package fory

import (
	"testing"

	"github.com/apache/fory/go/fory/bfloat16"
	"github.com/apache/fory/go/fory/float16"
	"github.com/stretchr/testify/require"
)

func TestFloat16Primitive(t *testing.T) {
	f := New(WithXlang(true))
	f16 := float16.Float16FromFloat32(3.14)

	// Directly serialize a float16 value
	data, err := f.Serialize(f16)
	require.NoError(t, err)

	var res float16.Float16
	err = f.Deserialize(data, &res)
	require.NoError(t, err)

	require.True(t, f16.Equal(res))

	// Value check (approximate)
	require.InDelta(t, 3.14, res.Float32(), 0.01)
}

func TestFloat16PrimitiveSliceDirect(t *testing.T) {
	// Tests serializing a slice as a root object
	f := New(WithXlang(true))
	f16 := float16.Float16FromFloat32(3.14)

	slice := []float16.Float16{f16, float16.Zero}
	data, err := f.Serialize(slice)
	require.NoError(t, err)

	var resSlice []float16.Float16
	err = f.Deserialize(data, &resSlice)
	require.NoError(t, err)
	require.Equal(t, slice, resSlice)
}

func TestBFloat16Primitive(t *testing.T) {
	f := New(WithXlang(true))
	bf16 := bfloat16.BFloat16FromFloat32(3.14)

	// Directly serialize a bfloat16 value
	data, err := f.Serialize(bf16)
	require.NoError(t, err)

	var res bfloat16.BFloat16
	err = f.Deserialize(data, &res)
	require.NoError(t, err)

	require.Equal(t, bf16.Bits(), res.Bits())

	// Value check (approximate because BF16 precision is low)
	require.InDelta(t, 3.14, res.Float32(), 0.1)
}

func TestBFloat16PrimitiveSliceDirect(t *testing.T) {
	// Tests serializing a slice as a root object
	f := New(WithXlang(true))
	bf16 := bfloat16.BFloat16FromFloat32(3.14)

	slice := []bfloat16.BFloat16{bf16, bfloat16.BFloat16(0)}
	data, err := f.Serialize(slice)
	require.NoError(t, err)

	var resSlice []bfloat16.BFloat16
	err = f.Deserialize(data, &resSlice)
	require.NoError(t, err)
	require.Equal(t, slice, resSlice)
}
