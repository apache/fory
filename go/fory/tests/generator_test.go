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

	"github.com/apache/fory/go/fory"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

//go:generate fory -file structs.go

func TestValidationDemo(t *testing.T) {
	// 1. Create test instance
	original := &ValidationDemo{
		A: 12345,         // int32
		B: "Hello Fory!", // string
		C: 98765,         // int64
		D: 3.14159,       // float64
		E: true,          // bool
	}

	// Validate original data structure
	assert.Equal(t, int32(12345), original.A, "Original A should be 12345")
	assert.Equal(t, "Hello Fory!", original.B, "Original B should be 'Hello Fory!'")
	assert.Equal(t, int64(98765), original.C, "Original C should be 98765")
	assert.Equal(t, 3.14159, original.D, "Original D should be 3.14159")
	assert.Equal(t, true, original.E, "Original E should be true")

	// 2. Serialize using generated code
	f := fory.NewFory(true)
	data, err := f.Marshal(original)
	require.NoError(t, err, "Serialization should not fail")
	require.NotEmpty(t, data, "Serialized data should not be empty")
	assert.Greater(t, len(data), 0, "Serialized data should have positive length")

	// 3. Deserialize using generated code
	var result *ValidationDemo
	err = f.Unmarshal(data, &result)
	require.NoError(t, err, "Deserialization should not fail")
	require.NotNil(t, result, "Deserialized result should not be nil")

	// 4. Assert that serializer is the generated serializer
	validationSerializer := NewSerializerFor_ValidationDemo()
	_, ok := validationSerializer.(ValidationDemo_ForyGenSerializer)
	assert.True(t, ok, "Serializer should be the generated ValidationDemo_ForyGenSerializer")
}

func TestSliceDemo(t *testing.T) {
	// 1. Create test instance with various slice types
	original := &SliceDemo{
		IntSlice:    []int32{10, 20, 30, 40, 50},
		StringSlice: []string{"hello", "world", "fory", "slice"},
		FloatSlice:  []float64{1.1, 2.2, 3.3, 4.4, 5.5},
		BoolSlice:   []bool{true, false, true, false},
	}

	// Validate original data structure (quick sanity check)
	assert.NotEmpty(t, original.IntSlice, "IntSlice should not be empty")
	assert.NotEmpty(t, original.StringSlice, "StringSlice should not be empty")
	assert.NotEmpty(t, original.FloatSlice, "FloatSlice should not be empty")
	assert.NotEmpty(t, original.BoolSlice, "BoolSlice should not be empty")

	// 2. Serialize using generated code
	f := fory.NewFory(true)
	data, err := f.Marshal(original)
	require.NoError(t, err, "Serialization should not fail")
	require.NotEmpty(t, data, "Serialized data should not be empty")
	assert.Greater(t, len(data), 0, "Serialized data should have positive length")

	// 3. Deserialize using generated code
	var result *SliceDemo
	err = f.Unmarshal(data, &result)
	require.NoError(t, err, "Deserialization should not fail")
	require.NotNil(t, result, "Deserialized result should not be nil")

	// 4. Assert that serializer is the generated serializer
	sliceSerializer := NewSerializerFor_SliceDemo()
	_, ok := sliceSerializer.(SliceDemo_ForyGenSerializer)
	assert.True(t, ok, "Serializer should be the generated SliceDemo_ForyGenSerializer")
}

func TestDynamicSliceDemo(t *testing.T) {
	// 1. Create test instance with various interface{} types
	original := &DynamicSliceDemo{
		DynamicSlice: []interface{}{
			int32(42),
			"hello",
			float64(3.14),
			true,
			int64(12345),
		},
	}

	// Validate original data structure (quick sanity check)
	assert.Equal(t, 5, len(original.DynamicSlice), "DynamicSlice should have 5 elements")
	assert.Equal(t, int32(42), original.DynamicSlice[0], "First element should be int32(42)")
	assert.Equal(t, "hello", original.DynamicSlice[1], "Second element should be 'hello'")
	assert.Equal(t, float64(3.14), original.DynamicSlice[2], "Third element should be float64(3.14)")
	assert.Equal(t, true, original.DynamicSlice[3], "Fourth element should be true")
	assert.Equal(t, int64(12345), original.DynamicSlice[4], "Fifth element should be int64(12345)")

	// 2. Serialize using generated code
	f := fory.NewFory(true)
	data, err := f.Marshal(original)
	require.NoError(t, err, "Serialization should not fail")
	require.NotEmpty(t, data, "Serialized data should not be empty")
	assert.Greater(t, len(data), 0, "Serialized data should have positive length")

	// 3. Deserialize using generated code
	var result *DynamicSliceDemo
	err = f.Unmarshal(data, &result)
	require.NoError(t, err, "Deserialization should not fail")
	require.NotNil(t, result, "Deserialized result should not be nil")

	// 4. Assert that serializer is the generated serializer
	dynamicSerializer := NewSerializerFor_DynamicSliceDemo()
	_, ok := dynamicSerializer.(DynamicSliceDemo_ForyGenSerializer)
	assert.True(t, ok, "Serializer should be the generated DynamicSliceDemo_ForyGenSerializer")
}

func TestDynamicSliceDemoWithNilAndEmpty(t *testing.T) {
	// Test with nil and empty dynamic slices
	original := &DynamicSliceDemo{
		DynamicSlice: nil, // nil slice
	}

	// Serialize using generated code
	f := fory.NewFory(true)
	data, err := f.Marshal(original)
	require.NoError(t, err, "Serialization should not fail")
	require.NotEmpty(t, data, "Serialized data should not be empty")

	// Deserialize using generated code
	var result *DynamicSliceDemo
	err = f.Unmarshal(data, &result)
	require.NoError(t, err, "Deserialization should not fail")
	require.NotNil(t, result, "Deserialized result should not be nil")

	// Validate nil slice handling
	assert.Nil(t, result.DynamicSlice, "DynamicSlice should be nil after round-trip")

	// Test with empty slice
	originalEmpty := &DynamicSliceDemo{
		DynamicSlice: []interface{}{}, // empty slice
	}

	dataEmpty, err := f.Marshal(originalEmpty)
	require.NoError(t, err, "Empty slice serialization should not fail")

	var resultEmpty *DynamicSliceDemo
	err = f.Unmarshal(dataEmpty, &resultEmpty)
	require.NoError(t, err, "Empty slice deserialization should not fail")
	require.NotNil(t, resultEmpty, "Deserialized result should not be nil")

	// Empty slice should remain empty (or become nil, depending on reflection behavior)
	assert.Equal(t, 0, len(resultEmpty.DynamicSlice), "DynamicSlice should be empty after round-trip")
}

// TestMapDemo tests basic map serialization and deserialization (including nil maps)
func TestMapDemo(t *testing.T) {
	// Create test instance with various map types (including nil)
	instance := &MapDemo{
		StringMap: map[string]string{
			"key1": "value1",
			"key2": "value2",
		},
		IntMap: map[int]int{
			1: 100,
			2: 200,
			3: 300,
		},
		MixedMap: nil, // Test nil map handling
	}

	// Serialize with codegen
	f := fory.NewFory(true)
	data, err := f.Marshal(instance)
	require.NoError(t, err, "Serialization failed")

	// Deserialize back
	var result MapDemo
	err = f.Unmarshal(data, &result)
	require.NoError(t, err, "Deserialization failed")

	// Verify using generated serializer
	serializer := NewSerializerFor_MapDemo()
	assert.NotNil(t, serializer, "Generated serializer should exist")

	// Verify map contents
	assert.EqualValues(t, instance.StringMap, result.StringMap, "StringMap mismatch")
	assert.EqualValues(t, instance.IntMap, result.IntMap, "IntMap mismatch")
	// MixedMap was nil, should become empty after deserialization
	assert.NotNil(t, result.MixedMap, "Expected non-nil MixedMap after deserialization")
	assert.Empty(t, result.MixedMap, "Expected empty MixedMap since original was nil")
}
