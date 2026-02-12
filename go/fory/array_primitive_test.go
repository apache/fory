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
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestPrimitiveArraySerializer(t *testing.T) {
	f := NewFory()

	// Test uint16 array
	t.Run("uint16_array", func(t *testing.T) {
		arr := [3]uint16{10, 20, 30}
		data, err := f.Serialize(arr)
		assert.NoError(t, err)

		var result [3]uint16
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Equal(t, arr, result)
	})

	// Test uint32 array
	t.Run("uint32_array", func(t *testing.T) {
		arr := [3]uint32{100, 200, 300}
		data, err := f.Serialize(arr)
		assert.NoError(t, err)

		var result [3]uint32
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Equal(t, arr, result)
	})

	// Test uint64 array
	t.Run("uint64_array", func(t *testing.T) {
		arr := [3]uint64{1000, 2000, 3000}
		data, err := f.Serialize(arr)
		assert.NoError(t, err)

		var result [3]uint64
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Equal(t, arr, result)
	})

	t.Run("uint array", func(t *testing.T) {
		arr := [3]uint{1, 100, 200}
		bytes, err := f.Serialize(arr)
		assert.NoError(t, err)

		var result [3]uint
		err = f.Unmarshal(bytes, &result)
		require.NoError(t, err)
		require.Equal(t, arr, result)
	})

	t.Run("bfloat16_array", func(t *testing.T) {
		arr := [3]bfloat16.BFloat16{
			bfloat16.BFloat16FromFloat32(1.0),
			bfloat16.BFloat16FromFloat32(2.5),
			bfloat16.BFloat16FromFloat32(-3.5),
		}
		data, err := f.Serialize(arr)
		assert.NoError(t, err)

		var result [3]bfloat16.BFloat16
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Equal(t, arr, result)
	})
}

func TestArraySliceInteroperability(t *testing.T) {
	f := NewFory()

	t.Run("array_to_slice", func(t *testing.T) {
		// Serialize Array [3]int32
		arr := [3]int32{1, 2, 3}
		data, err := f.Serialize(arr)
		assert.NoError(t, err)

		// Deserialize into Slice []int32
		var slice []int32
		err = f.Deserialize(data, &slice)
		assert.NoError(t, err)
		assert.Equal(t, []int32{1, 2, 3}, slice)
	})

	t.Run("slice_to_array", func(t *testing.T) {
		// Serialize Slice []int32
		slice := []int32{4, 5, 6}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		// Deserialize into Array [3]int32
		var arr [3]int32
		err = f.Deserialize(data, &arr)
		assert.NoError(t, err)
		assert.Equal(t, [3]int32{4, 5, 6}, arr)
	})

	t.Run("array_to_slice_mismatch_type", func(t *testing.T) {
		// Serialize Array [3]int32
		arr := [3]int32{1, 2, 3}
		data, err := f.Serialize(arr)
		assert.NoError(t, err)

		var slice []int64 // different type
		err = f.Deserialize(data, &slice)
		// Strict checking means this should error immediately upon reading wrong TypeID
		assert.Error(t, err)
	})

	t.Run("slice_to_array_size_mismatch", func(t *testing.T) {
		// Serialize Slice []int32 with len 2
		slice := []int32{1, 2}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		// Deserialize into Array [3]int32 - should fail size check
		var arr [3]int32
		err = f.Deserialize(data, &arr)
		// Serialized as list with len 2. Array expects 3.
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "array length")
	})
}

func TestFloat16Array(t *testing.T) {
	f := NewFory()

	t.Run("float16_array", func(t *testing.T) {
		arr := [3]float16.Float16{
			float16.Float16FromFloat32(1.0),
			float16.Float16FromFloat32(2.5),
			float16.Float16FromFloat32(-0.5),
		}
		data, err := f.Serialize(arr)
		assert.NoError(t, err)

		var result [3]float16.Float16
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Equal(t, arr, result)
	})
}
