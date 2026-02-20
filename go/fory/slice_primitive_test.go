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
	"math"
	"testing"

	"github.com/apache/fory/go/fory/bfloat16"
	"github.com/apache/fory/go/fory/float16"
	"github.com/stretchr/testify/assert"
)

func TestFloat16Slice(t *testing.T) {
	f := NewFory()

	t.Run("float16_slice", func(t *testing.T) {
		slice := []float16.Float16{
			float16.Float16FromFloat32(1.0),
			float16.Float16FromFloat32(2.5),
			float16.Float16FromFloat32(-0.5),
		}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []float16.Float16
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Equal(t, slice, result)
	})

	t.Run("float16_slice_empty", func(t *testing.T) {
		slice := []float16.Float16{}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []float16.Float16
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Empty(t, result)
	})

	t.Run("float16_slice_nil", func(t *testing.T) {
		var slice []float16.Float16 = nil
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []float16.Float16
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Nil(t, result)
	})
}

func TestBFloat16Slice(t *testing.T) {
	f := NewFory()

	t.Run("bfloat16_slice", func(t *testing.T) {
		slice := []bfloat16.BFloat16{
			bfloat16.BFloat16FromFloat32(1.0),
			bfloat16.BFloat16FromFloat32(2.5),
			bfloat16.BFloat16FromFloat32(-3.5),
		}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []bfloat16.BFloat16
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Equal(t, slice, result)
	})

	t.Run("bfloat16_slice_empty", func(t *testing.T) {
		slice := []bfloat16.BFloat16{}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []bfloat16.BFloat16
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Empty(t, result)
	})

	t.Run("bfloat16_slice_nil", func(t *testing.T) {
		var slice []bfloat16.BFloat16 = nil
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []bfloat16.BFloat16
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Nil(t, result)
	})
}

func TestIntSlice(t *testing.T) {
	f := NewFory()

	t.Run("int_slice_large_numbers", func(t *testing.T) {
		slice := []int{1, -2, 3, -4, math.MaxInt, math.MinInt}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []int
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Equal(t, slice, result)
	})

	t.Run("int_slice_empty", func(t *testing.T) {
		slice := []int{}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []int
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Empty(t, result)
	})

	t.Run("int_slice_nil", func(t *testing.T) {
		var slice []int = nil
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []int
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Nil(t, result)
	})
}

func TestUintSlice(t *testing.T) {
	f := NewFory()

	t.Run("uint_slice_large_numbers", func(t *testing.T) {
		slice := []uint{1, 2, 3, 4, math.MaxUint}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []uint
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Equal(t, slice, result)
	})

	t.Run("uint_slice_empty", func(t *testing.T) {
		slice := []uint{}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []uint
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Empty(t, result)
	})

	t.Run("uint_slice_nil", func(t *testing.T) {
		var slice []uint = nil
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []uint
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Nil(t, result)
	})
}

func TestInt8Slice(t *testing.T) {
	f := NewFory()

	t.Run("int8_slice_large_numbers", func(t *testing.T) {
		slice := []int8{1, -2, 3, -4, math.MaxInt8, math.MinInt8}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []int8
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Equal(t, slice, result)
	})

	t.Run("int8_slice_empty", func(t *testing.T) {
		slice := []int8{}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []int8
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Empty(t, result)
	})

	t.Run("int8_slice_nil", func(t *testing.T) {
		var slice []int8 = nil
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []int8
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Nil(t, result)
	})
}

func TestInt16Slice(t *testing.T) {
	f := NewFory()

	t.Run("int16_slice_large_numbers", func(t *testing.T) {
		slice := []int16{1, -2, 3, -4, math.MaxInt16, math.MinInt16}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []int16
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Equal(t, slice, result)
	})

	t.Run("int16_slice_empty", func(t *testing.T) {
		slice := []int16{}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []int16
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Empty(t, result)
	})

	t.Run("int16_slice_nil", func(t *testing.T) {
		var slice []int16 = nil
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []int16
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Nil(t, result)
	})
}

func TestInt32Slice(t *testing.T) {
	f := NewFory()

	t.Run("int32_slice_large_numbers", func(t *testing.T) {
		slice := []int32{1, -2, 3, -4, math.MaxInt32, math.MinInt32}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []int32
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Equal(t, slice, result)
	})

	t.Run("int32_slice_empty", func(t *testing.T) {
		slice := []int32{}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []int32
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Empty(t, result)
	})

	t.Run("int32_slice_nil", func(t *testing.T) {
		var slice []int32 = nil
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []int32
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Nil(t, result)
	})
}

func TestInt64Slice(t *testing.T) {
	f := NewFory()

	t.Run("int64_slice_large_numbers", func(t *testing.T) {
		slice := []int64{1, -2, 3, -4, math.MaxInt64, math.MinInt64}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []int64
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Equal(t, slice, result)
	})

	t.Run("int64_slice_empty", func(t *testing.T) {
		slice := []int64{}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []int64
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Empty(t, result)
	})

	t.Run("int64_slice_nil", func(t *testing.T) {
		var slice []int64 = nil
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []int64
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Nil(t, result)
	})
}

func TestUint16Slice(t *testing.T) {
	f := NewFory()

	t.Run("uint16_slice_large_numbers", func(t *testing.T) {
		slice := []uint16{1, 2, 3, 4, math.MaxUint16}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []uint16
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Equal(t, slice, result)
	})

	t.Run("uint16_slice_empty", func(t *testing.T) {
		slice := []uint16{}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []uint16
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Empty(t, result)
	})

	t.Run("uint16_slice_nil", func(t *testing.T) {
		var slice []uint16 = nil
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []uint16
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Nil(t, result)
	})
}

func TestUint32Slice(t *testing.T) {
	f := NewFory()

	t.Run("uint32_slice_large_numbers", func(t *testing.T) {
		slice := []uint32{1, 2, 3, 4, math.MaxUint32}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []uint32
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Equal(t, slice, result)
	})

	t.Run("uint32_slice_empty", func(t *testing.T) {
		slice := []uint32{}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []uint32
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Empty(t, result)
	})

	t.Run("uint32_slice_nil", func(t *testing.T) {
		var slice []uint32 = nil
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []uint32
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Nil(t, result)
	})
}

func TestUint64Slice(t *testing.T) {
	f := NewFory()

	t.Run("uint64_slice_large_numbers", func(t *testing.T) {
		slice := []uint64{1, 2, 3, 4, math.MaxUint64}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []uint64
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Equal(t, slice, result)
	})

	t.Run("uint64_slice_empty", func(t *testing.T) {
		slice := []uint64{}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []uint64
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Empty(t, result)
	})

	t.Run("uint64_slice_nil", func(t *testing.T) {
		var slice []uint64 = nil
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []uint64
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Nil(t, result)
	})
}
