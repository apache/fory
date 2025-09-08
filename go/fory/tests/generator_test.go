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
	"time"

	"github.com/apache/fory/go/fory"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

//go:generate fory --force -file structs.go

func TestValidationDemo(t *testing.T) {
	// 1. Create test instance
	original := &ValidationDemo{
		A: 12345,                                                   // int32
		B: "Hello Fory!",                                           // string
		C: 98765,                                                   // int64
		D: []int32{1, 2, 3, 4, 5},                                  // slice of int32
		E: []string{"hello", "world", "slice"},                     // slice of string
		F: []bool{true, false, true},                               // slice of bool
		G: map[string]int32{"one": 1, "two": 2, "three": 3},        // map[string]int32
		H: map[int32]string{10: "ten", 20: "twenty", 30: "thirty"}, // map[int32]string
		I: map[string]bool{"enabled": true, "disabled": false},     // map[string]bool
		J: time.Date(2023, 12, 25, 10, 30, 0, 0, time.UTC),         // time.Time
	}

	// Validate original data structure
	assert.Equal(t, int32(12345), original.A, "Original A should be 12345")
	assert.Equal(t, "Hello Fory!", original.B, "Original B should be 'Hello Fory!'")
	assert.Equal(t, int64(98765), original.C, "Original C should be 98765")
	assert.Equal(t, []int32{1, 2, 3, 4, 5}, original.D, "Original D should be [1, 2, 3, 4, 5]")
	assert.Equal(t, []string{"hello", "world", "slice"}, original.E, "Original E should be ['hello', 'world', 'slice']")
	assert.Equal(t, []bool{true, false, true}, original.F, "Original F should be [true, false, true]")
	assert.Equal(t, map[string]int32{"one": 1, "two": 2, "three": 3}, original.G, "Original G should be map[string]int32{'one': 1, 'two': 2, 'three': 3}")
	assert.Equal(t, map[int32]string{10: "ten", 20: "twenty", 30: "thirty"}, original.H, "Original H should be map[int32]string{10: 'ten', 20: 'twenty', 30: 'thirty'}")
	assert.Equal(t, map[string]bool{"enabled": true, "disabled": false}, original.I, "Original I should be map[string]bool{'enabled': true, 'disabled': false}")
	assert.Equal(t, time.Date(2023, 12, 25, 10, 30, 0, 0, time.UTC), original.J, "Original J should be time.Date(2023, 12, 25, 10, 30, 0, 0, time.UTC)")

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

	// 4. Validate round-trip serialization
	assert.Equal(t, original.A, result.A, "Field A should match after round-trip")
	assert.Equal(t, original.B, result.B, "Field B should match after round-trip")
	assert.Equal(t, original.C, result.C, "Field C should match after round-trip")
	assert.Equal(t, original.D, result.D, "Field D should match after round-trip")
	assert.Equal(t, original.E, result.E, "Field E should match after round-trip")
	assert.Equal(t, original.F, result.F, "Field F should match after round-trip")
	assert.Equal(t, original.G, result.G, "Field G should match after round-trip")
	assert.Equal(t, original.H, result.H, "Field H should match after round-trip")
	assert.Equal(t, original.I, result.I, "Field I should match after round-trip")
	// For time.Time, compare Unix timestamps since timezone info is lost during serialization
	assert.Equal(t, original.J.Unix(), result.J.Unix(), "Field J timestamps should match after round-trip")

	// 5. Validate data integrity (excluding time field due to timezone differences)
	// Create copies without time field for comparison
	originalCopy := *original
	resultCopy := *result
	originalCopy.J = time.Time{}
	resultCopy.J = time.Time{}
	assert.EqualValues(t, &originalCopy, &resultCopy, "Complete struct (except time) should match after round-trip")
}

func TestSimpleStruct(t *testing.T) {
	// 1. Create test instance
	original := &SimpleStruct{
		ID:   42,          // int field
		Name: "Test User", // string field
	}

	// Validate original data structure
	assert.Equal(t, 42, original.ID, "Original ID should be 42")
	assert.Equal(t, "Test User", original.Name, "Original Name should be 'Test User'")

	// 2. Serialize using generated code
	f := fory.NewFory(true)
	data, err := f.Marshal(original)
	require.NoError(t, err, "Serialization should not fail")
	require.NotEmpty(t, data, "Serialized data should not be empty")
	assert.Greater(t, len(data), 0, "Serialized data should have positive length")

	// 3. Deserialize using generated code
	var result *SimpleStruct
	err = f.Unmarshal(data, &result)
	require.NoError(t, err, "Deserialization should not fail")
	require.NotNil(t, result, "Deserialized result should not be nil")

	// 4. Validate round-trip serialization
	assert.Equal(t, original.ID, result.ID, "Field ID should match after round-trip")
	assert.Equal(t, original.Name, result.Name, "Field Name should match after round-trip")

	// 5. Validate complete data integrity
	assert.EqualValues(t, original, result, "Complete struct should match after round-trip")
}

func TestCompoundStruct(t *testing.T) {
	// 1. Create test instance with nested structs
	original := &CompoundStruct{
		ValidationData: ValidationDemo{
			A: 123,
			B: "Nested ValidationDemo",
			C: 789,
			D: []int32{10, 20, 30},
			E: []string{"nested", "test"},
			F: []bool{true, false},
			G: map[string]int32{"nested": 100},
			H: map[int32]string{1: "nested_value"},
			I: map[string]bool{"nested_flag": true},
			J: time.Date(2024, 1, 1, 12, 0, 0, 0, time.UTC),
		},
		SimpleData: SimpleStruct{
			ID:   999,
			Name: "Nested SimpleStruct",
		},
		Count: 42,
	}

	// Validate original data structure
	assert.Equal(t, int32(123), original.ValidationData.A, "Nested ValidationDemo.A should be 123")
	assert.Equal(t, "Nested ValidationDemo", original.ValidationData.B, "Nested ValidationDemo.B should be correct")
	assert.Equal(t, 999, original.SimpleData.ID, "Nested SimpleStruct.ID should be 999")
	assert.Equal(t, "Nested SimpleStruct", original.SimpleData.Name, "Nested SimpleStruct.Name should be correct")
	assert.Equal(t, 42, original.Count, "Count should be 42")

	// 2. Serialize using generated code
	f := fory.NewFory(true)
	data, err := f.Marshal(original)
	require.NoError(t, err, "Serialization should not fail")
	require.NotEmpty(t, data, "Serialized data should not be empty")
	assert.Greater(t, len(data), 0, "Serialized data should have positive length")

	// 3. Deserialize using generated code
	var result *CompoundStruct
	err = f.Unmarshal(data, &result)
	require.NoError(t, err, "Deserialization should not fail")
	require.NotNil(t, result, "Deserialized result should not be nil")

	// 4. Validate round-trip serialization for nested structs
	assert.Equal(t, original.ValidationData.A, result.ValidationData.A, "Nested ValidationDemo.A should match after round-trip")
	assert.Equal(t, original.ValidationData.B, result.ValidationData.B, "Nested ValidationDemo.B should match after round-trip")
	assert.Equal(t, original.ValidationData.C, result.ValidationData.C, "Nested ValidationDemo.C should match after round-trip")
	assert.Equal(t, original.ValidationData.D, result.ValidationData.D, "Nested ValidationDemo.D should match after round-trip")
	assert.Equal(t, original.ValidationData.E, result.ValidationData.E, "Nested ValidationDemo.E should match after round-trip")
	assert.Equal(t, original.ValidationData.F, result.ValidationData.F, "Nested ValidationDemo.F should match after round-trip")
	assert.Equal(t, original.ValidationData.G, result.ValidationData.G, "Nested ValidationDemo.G should match after round-trip")
	assert.Equal(t, original.ValidationData.H, result.ValidationData.H, "Nested ValidationDemo.H should match after round-trip")
	assert.Equal(t, original.ValidationData.I, result.ValidationData.I, "Nested ValidationDemo.I should match after round-trip")
	assert.Equal(t, original.ValidationData.J.Unix(), result.ValidationData.J.Unix(), "Nested ValidationDemo.J timestamps should match after round-trip")

	assert.Equal(t, original.SimpleData.ID, result.SimpleData.ID, "Nested SimpleStruct.ID should match after round-trip")
	assert.Equal(t, original.SimpleData.Name, result.SimpleData.Name, "Nested SimpleStruct.Name should match after round-trip")
	assert.Equal(t, original.Count, result.Count, "Count should match after round-trip")

	// 5. Validate complete nested data integrity
	// Compare ValidationData excluding time field
	originalValidation := original.ValidationData
	resultValidation := result.ValidationData
	originalValidation.J = time.Time{}
	resultValidation.J = time.Time{}
	assert.EqualValues(t, originalValidation, resultValidation, "Nested ValidationDemo (except time) should match after round-trip")

	// Compare SimpleData completely
	assert.EqualValues(t, original.SimpleData, result.SimpleData, "Nested SimpleStruct should match after round-trip")

	// Compare top-level field
	assert.Equal(t, original.Count, result.Count, "Top-level Count field should match after round-trip")
}
