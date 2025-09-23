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
	"reflect"
	"testing"

	forygo "github.com/apache/fory/go/fory"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestActualCodegenName - Analyze actual type names used by codegen
func TestActualCodegenName(t *testing.T) {
	// From source code analysis:
	// RegisterSerializerFactory calculates: typeTag := pkgPath + "." + typeName

	validationDemoType := reflect.TypeOf(ValidationDemo{})
	pkgPath := validationDemoType.PkgPath()
	typeName := validationDemoType.Name()
	expectedTypeTag := pkgPath + "." + typeName

	// Create test data
	codegenInstance := &ValidationDemo{
		A: 100,
		B: "test_data",
		C: 200,
		D: 3.14159,
		E: true,
	}

	type ReflectStruct struct {
		A int32
		B string
		C int64
		D float64
		E bool
	}

	reflectInstance := &ReflectStruct{
		A: 100,
		B: "test_data",
		C: 200,
		D: 3.14159,
		E: true,
	}

	// Codegen mode (automatically uses full name)
	foryForCodegen := forygo.NewFory(true)

	// Reflect mode (register with full name)
	foryForReflect := forygo.NewFory(true)
	err := foryForReflect.RegisterTagType(expectedTypeTag, ReflectStruct{})
	require.NoError(t, err, "Should be able to register ReflectStruct with full name")

	// Serialization test
	codegenData, err := foryForCodegen.Marshal(codegenInstance)
	require.NoError(t, err, "Codegen serialization should not fail")

	reflectData, err := foryForReflect.Marshal(reflectInstance)
	require.NoError(t, err, "Reflect serialization should not fail")

	// Use reflect to deserialize codegen data
	var reflectResult *ReflectStruct
	err = foryForReflect.Unmarshal(codegenData, &reflectResult)
	require.NoError(t, err, "Reflect should be able to deserialize codegen data")
	require.NotNil(t, reflectResult, "Reflect result should not be nil")

	// Verify content matches original
	assert.EqualValues(t, codegenInstance, reflectResult, "Reflect deserialized data should match original")

	// Use codegen to deserialize reflect data
	var codegenResult *ValidationDemo
	err = foryForCodegen.Unmarshal(reflectData, &codegenResult)
	require.NoError(t, err, "Codegen should be able to deserialize reflect data")
	require.NotNil(t, codegenResult, "Codegen result should not be nil")

	// Verify content matches original
	assert.EqualValues(t, reflectInstance, codegenResult, "Codegen deserialized data should match original")
}

// TestSliceDemoXlang - Test cross-language compatibility of SliceDemo
func TestSliceDemoXlang(t *testing.T) {
	// Get SliceDemo type information
	sliceDemoType := reflect.TypeOf(SliceDemo{})
	pkgPath := sliceDemoType.PkgPath()
	typeName := sliceDemoType.Name()
	expectedTypeTag := pkgPath + "." + typeName

	// Create test data
	codegenInstance := &SliceDemo{
		IntSlice:    []int32{1, 2, 3, 4, 5},
		StringSlice: []string{"hello", "world", "fory"},
		FloatSlice:  []float64{1.1, 2.2, 3.3},
		BoolSlice:   []bool{true, false, true},
	}

	// Define equivalent struct using reflection
	type ReflectSliceStruct struct {
		IntSlice    []int32
		StringSlice []string
		FloatSlice  []float64
		BoolSlice   []bool
	}

	reflectInstance := &ReflectSliceStruct{
		IntSlice:    []int32{1, 2, 3, 4, 5},
		StringSlice: []string{"hello", "world", "fory"},
		FloatSlice:  []float64{1.1, 2.2, 3.3},
		BoolSlice:   []bool{true, false, true},
	}

	// Codegen mode - enable reference tracking
	foryForCodegen := forygo.NewFory(true)

	// Reflect mode - enable reference tracking
	foryForReflect := forygo.NewFory(true)
	err := foryForReflect.RegisterTagType(expectedTypeTag, ReflectSliceStruct{})
	require.NoError(t, err, "Should be able to register ReflectSliceStruct with full name")

	// Serialization test
	codegenData, err := foryForCodegen.Marshal(codegenInstance)
	require.NoError(t, err, "Codegen serialization should not fail")

	reflectData, err := foryForReflect.Marshal(reflectInstance)
	require.NoError(t, err, "Reflect serialization should not fail")

	// Verify cross serialization

	// Use reflect to deserialize codegen data
	var reflectResult *ReflectSliceStruct
	err = foryForReflect.Unmarshal(codegenData, &reflectResult)
	require.NoError(t, err, "Reflect should be able to deserialize codegen data")
	require.NotNil(t, reflectResult, "Reflect result should not be nil")

	// Verify content matches original
	assert.EqualValues(t, codegenInstance.IntSlice, reflectResult.IntSlice, "IntSlice mismatch")
	assert.EqualValues(t, codegenInstance.StringSlice, reflectResult.StringSlice, "StringSlice mismatch")
	assert.EqualValues(t, codegenInstance.FloatSlice, reflectResult.FloatSlice, "FloatSlice mismatch")
	assert.EqualValues(t, codegenInstance.BoolSlice, reflectResult.BoolSlice, "BoolSlice mismatch")

	// Use codegen to deserialize reflect data
	var codegenResult *SliceDemo
	err = foryForCodegen.Unmarshal(reflectData, &codegenResult)
	require.NoError(t, err, "Codegen should be able to deserialize reflect data")
	require.NotNil(t, codegenResult, "Codegen result should not be nil")

	// Verify content matches original
	assert.EqualValues(t, reflectInstance.IntSlice, codegenResult.IntSlice, "IntSlice mismatch")
	assert.EqualValues(t, reflectInstance.StringSlice, codegenResult.StringSlice, "StringSlice mismatch")
	assert.EqualValues(t, reflectInstance.FloatSlice, codegenResult.FloatSlice, "FloatSlice mismatch")
	assert.EqualValues(t, reflectInstance.BoolSlice, codegenResult.BoolSlice, "BoolSlice mismatch")

}

// TestDynamicSliceDemoXlang - Test cross-language compatibility of DynamicSliceDemo
func TestDynamicSliceDemoXlang(t *testing.T) {
	// Get DynamicSliceDemo type information
	dynamicSliceType := reflect.TypeOf(DynamicSliceDemo{})
	pkgPath := dynamicSliceType.PkgPath()
	typeName := dynamicSliceType.Name()
	expectedTypeTag := pkgPath + "." + typeName

	// Create test data with simpler types to avoid reflection issues
	codegenInstance := &DynamicSliceDemo{
		DynamicSlice: []interface{}{
			"first",
			200, // Testing mixed types in dynamic slice
			"third",
		},
	}

	// Define equivalent struct using reflection
	type ReflectDynamicStruct struct {
		DynamicSlice []interface{} `json:"dynamic_slice"`
	}

	reflectInstance := &ReflectDynamicStruct{
		DynamicSlice: []interface{}{
			"first",
			200, // Testing mixed types in dynamic slice
			"third",
		},
	}

	// Codegen mode - enable reference tracking
	foryForCodegen := forygo.NewFory(true)

	// Reflect mode - enable reference tracking
	foryForReflect := forygo.NewFory(true)
	err := foryForReflect.RegisterTagType(expectedTypeTag, ReflectDynamicStruct{})
	require.NoError(t, err, "Should be able to register ReflectDynamicStruct with full name")

	// Serialization test
	codegenData, err := foryForCodegen.Marshal(codegenInstance)
	require.NoError(t, err, "Codegen serialization should not fail")

	reflectData, err := foryForReflect.Marshal(reflectInstance)
	require.NoError(t, err, "Reflect serialization should not fail")

	// Test cross deserialization - reflect deserializes codegen data
	var reflectResult *ReflectDynamicStruct
	err = foryForReflect.Unmarshal(codegenData, &reflectResult)
	require.NoError(t, err, "Reflect should be able to deserialize codegen data")
	require.NotNil(t, reflectResult, "Reflect result should not be nil")

	// Verify content matches original
	assert.EqualValues(t, codegenInstance.DynamicSlice, reflectResult.DynamicSlice, "DynamicSlice mismatch")

	// Test opposite direction - codegen deserializes reflect data
	var codegenResult *DynamicSliceDemo
	err = foryForCodegen.Unmarshal(reflectData, &codegenResult)
	require.NoError(t, err, "Codegen should be able to deserialize reflect data")
	require.NotNil(t, codegenResult, "Codegen result should not be nil")

	// Verify content matches original
	assert.EqualValues(t, reflectInstance.DynamicSlice, codegenResult.DynamicSlice, "DynamicSlice mismatch")
}

// TestMapDemoXlang tests cross-language compatibility for map types
func TestMapDemoXlang(t *testing.T) {
	// Create test instance with same data for both codegen and reflection
	codegenInstance := &MapDemo{
		StringMap: map[string]string{
			"key1": "value1",
			"key2": "value2",
		},
		IntMap: map[int]int{
			1: 100,
			2: 200,
			3: 300,
		},
		MixedMap: map[string]int{
			"one":   1,
			"two":   2,
			"three": 3,
		},
	}

	// Use same instance for reflection (simplified test)
	reflectInstance := codegenInstance

	// Create Fory instances with reference tracking enabled
	foryForCodegen := forygo.NewFory(true)
	foryForReflect := forygo.NewFory(true)

	// No need to register MapDemo - it has codegen serializer automatically

	// Serialize both instances
	codegenData, err := foryForCodegen.Marshal(codegenInstance)
	require.NoError(t, err, "Codegen serialization should not fail")

	reflectData, err := foryForReflect.Marshal(reflectInstance)
	require.NoError(t, err, "Reflect serialization should not fail")

	// Test cross deserialization - reflect deserializes codegen data
	var reflectResult MapDemo
	err = foryForReflect.Unmarshal(codegenData, &reflectResult)
	require.NoError(t, err, "Reflect should be able to deserialize codegen data")

	// Verify content matches original
	assert.EqualValues(t, codegenInstance.StringMap, reflectResult.StringMap, "StringMap mismatch")
	assert.EqualValues(t, codegenInstance.IntMap, reflectResult.IntMap, "IntMap mismatch")
	assert.EqualValues(t, codegenInstance.MixedMap, reflectResult.MixedMap, "MixedMap mismatch")

	// Test opposite direction - codegen deserializes reflect data
	var codegenResult MapDemo
	err = foryForCodegen.Unmarshal(reflectData, &codegenResult)
	require.NoError(t, err, "Codegen should be able to deserialize reflect data")

	// Verify content matches original
	assert.EqualValues(t, reflectInstance.StringMap, codegenResult.StringMap, "StringMap mismatch")
	assert.EqualValues(t, reflectInstance.IntMap, codegenResult.IntMap, "IntMap mismatch")
	assert.EqualValues(t, reflectInstance.MixedMap, codegenResult.MixedMap, "MixedMap mismatch")
}
