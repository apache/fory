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

	"github.com/stretchr/testify/require"
)

// TestSerializeGenericPrimitives tests Serialize[T]/DeserializeWithCallbackBuffers[T] with primitives.
// Both functions take pointers to avoid interface heap allocation and struct copy.
func TestSerializeGenericPrimitives(t *testing.T) {
	f := NewFory(WithXlang(false), WithRefTracking(true), WithCompatible(false))

	t.Run("Bool", func(t *testing.T) {
		val := true
		data, err := Serialize(f, &val)
		require.NoError(t, err)
		var result bool
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.True(t, result)

		val = false
		data, err = Serialize(f, &val)
		require.NoError(t, err)
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.False(t, result)
	})

	t.Run("Int8", func(t *testing.T) {
		val := int8(-42)
		data, err := Serialize(f, &val)
		require.NoError(t, err)
		var result int8
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.Equal(t, int8(-42), result)
	})

	t.Run("Int16", func(t *testing.T) {
		val := int16(1234)
		data, err := Serialize(f, &val)
		require.NoError(t, err)
		var result int16
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.Equal(t, int16(1234), result)
	})

	t.Run("Int32", func(t *testing.T) {
		val := int32(42)
		data, err := Serialize(f, &val)
		require.NoError(t, err)
		var result int32
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.Equal(t, int32(42), result)

		// Test negative
		val = int32(-12345)
		data, err = Serialize(f, &val)
		require.NoError(t, err)
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.Equal(t, int32(-12345), result)
	})

	t.Run("Int64", func(t *testing.T) {
		val := int64(9876543210)
		data, err := Serialize(f, &val)
		require.NoError(t, err)
		var result int64
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.Equal(t, int64(9876543210), result)
	})

	t.Run("Float32", func(t *testing.T) {
		val := float32(3.14)
		data, err := Serialize(f, &val)
		require.NoError(t, err)
		var result float32
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.InDelta(t, float32(3.14), result, 0.001)
	})

	t.Run("Float64", func(t *testing.T) {
		val := 2.71828
		data, err := Serialize(f, &val)
		require.NoError(t, err)
		var result float64
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.InDelta(t, 2.71828, result, 0.00001)
	})

	t.Run("String", func(t *testing.T) {
		val := "hello fory"
		data, err := Serialize(f, &val)
		require.NoError(t, err)
		var result string
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.Equal(t, "hello fory", result)

		// Test empty string
		val = ""
		data, err = Serialize(f, &val)
		require.NoError(t, err)
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.Equal(t, "", result)
	})
}

func TestDeserializeRejectsRootTypeMismatch(t *testing.T) {
	f := NewFory(WithXlang(false), WithCompatible(false))

	data := []byte{0, 0xff, byte(STRING)}
	var result bool
	require.Error(t, Deserialize(f, data, &result))

	data = []byte{0, 0xff, byte(BOOL)}
	var mapResult map[string]string
	require.Error(t, Deserialize(f, data, &mapResult))
}

func TestDeserializeRejectsRootPrimitiveSliceTypeMismatch(t *testing.T) {
	f := NewFory(WithXlang(false), WithCompatible(false))

	data := []byte{0, 0xff, byte(BINARY)}
	var int32Result []int32
	require.Error(t, Deserialize(f, data, &int32Result))

	data = []byte{0, 0xff, byte(INT8_ARRAY)}
	var byteResult []byte
	require.Error(t, Deserialize(f, data, &byteResult))
}

func TestDeserializeByteSliceAcceptsUint8ArrayRootType(t *testing.T) {
	f := NewFory(WithXlang(false), WithCompatible(false))
	buf := NewByteBuffer(nil)
	buf.WriteByte(0)
	buf.WriteInt8(NotNullValueFlag)
	buf.WriteUint8(uint8(UINT8_ARRAY))
	buf.WriteLength(3)
	buf.WriteBinary([]byte{1, 2, 3})

	var result []byte
	require.NoError(t, Deserialize(f, buf.Bytes(), &result))
	require.Equal(t, []byte{1, 2, 3}, result)
}

// TestSerializeGenericComplex tests Serialize[T]/DeserializeWithCallbackBuffers[T] with complex types.
// Struct wrappers must be registered explicitly before reflection-based serialization.
func TestSerializeGenericComplex(t *testing.T) {
	f := NewFory(WithXlang(false), WithRefTracking(true), WithCompatible(false))

	t.Run("Struct", func(t *testing.T) {
		type TestStruct struct {
			Name  string
			Value int32
		}
		err := f.RegisterStructByName(TestStruct{}, "example.TestStruct")
		require.NoError(t, err)

		original := TestStruct{Name: "test", Value: 100}
		data, err := Serialize(f, &original)
		require.NoError(t, err)

		// Use reflection-based path for deserialization
		var result TestStruct
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.Equal(t, original, result)
	})

	t.Run("Slice", func(t *testing.T) {
		// Note: *[]T is not supported, use wrapper struct instead
		type SliceWrapper struct {
			Items []int32
		}
		require.NoError(t, f.RegisterStructByName(SliceWrapper{}, "example.SliceWrapper"))
		original := SliceWrapper{Items: []int32{1, 2, 3, 4, 5}}
		data, err := Serialize(f, &original)
		require.NoError(t, err)

		var result SliceWrapper
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.Equal(t, original.Items, result.Items)
	})

	t.Run("Map", func(t *testing.T) {
		// Note: *map[K]V is not supported, use wrapper struct instead
		type MapWrapper struct {
			Items map[string]int32
		}
		require.NoError(t, f.RegisterStructByName(MapWrapper{}, "example.MapWrapper"))
		original := MapWrapper{Items: map[string]int32{"a": 1, "b": 2, "c": 3}}
		data, err := Serialize(f, &original)
		require.NoError(t, err)

		var result MapWrapper
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.Equal(t, original.Items, result.Items)
	})
}

// TestSerializeDeserializeRoundTrip tests that serialized data can be correctly deserialized.
func TestSerializeDeserializeRoundTrip(t *testing.T) {
	f := NewFory(WithXlang(false), WithRefTracking(true), WithCompatible(false))

	// Test that SerializeWithCallback[T] uses pointer-based fast path when available
	t.Run("TypedSerializerPath", func(t *testing.T) {
		// Int32 has a registered fast path
		original := int32(999)
		data, err := Serialize(f, &original)
		require.NoError(t, err)
		require.NotEmpty(t, data)

		var result int32
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.Equal(t, original, result)
	})

	t.Run("ReflectionFallbackPath", func(t *testing.T) {
		// Custom struct falls back to reflection
		type CustomStruct struct {
			ID   int64
			Name string
		}
		f.RegisterStructByName(CustomStruct{}, "test.CustomStruct")

		original := CustomStruct{ID: 123, Name: "test"}
		data, err := Serialize(f, &original)
		require.NoError(t, err)

		var result CustomStruct
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.Equal(t, original, result)
	})
}
