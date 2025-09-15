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

	"github.com/apache/fory/go/fory"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

//go:generate go run ../cmd/fory/main.go --force -file structs.go

func TestBasicTypesStruct(t *testing.T) {
	t.Log("🎯 Testing BasicTypesStruct - codegen path serialization/deserialization")

	// Create test object with various basic types
	original := &BasicTypesStruct{
		BoolField:    true,
		Int8Field:    -8,
		Int16Field:   -16,
		Int32Field:   -32,
		Int64Field:   -64,
		IntField:     123456,
		Uint8Field:   255,
		Float32Field: 3.14,
		Float64Field: 2.718281828,
		StringField:  "test_string",
	}

	// Test with codegen-enabled fory
	f := fory.NewFory(true)

	// Verify that generated serializer is used
	serializer, err := f.GetSerializer(reflect.TypeOf(*original))
	require.NoError(t, err, "should be able to get serializer")

	// Assert that serializer is the generated serializer (not reflection)
	assert.IsType(t, BasicTypesStruct_ForyGenSerializer{}, serializer,
		"should use generated serializer instead of reflection serializer")

	// Serialize
	data, err := f.Marshal(original)
	require.NoError(t, err, "serialization should succeed")

	// Deserialize
	var result BasicTypesStruct
	err = f.Unmarshal(data, &result)
	require.NoError(t, err, "deserialization should succeed")

	// Use == to compare directly instead of comparing fields manually
	assert.Equal(t, *original, result, "serialization/deserialization result should equal original object")

	t.Logf("✅ BasicTypesStruct codegen path test successful (%d bytes)", len(data))
}

func TestCollectionTypesStruct(t *testing.T) {
	t.Log("🎯 Testing CollectionTypesStruct - codegen path serialization/deserialization")

	// Create test object with various collection types
	original := &CollectionTypesStruct{
		IntSlice:      []int32{1, 2, 3, 4, 5},
		StringSlice:   []string{"hello", "world", "fory"},
		BoolSlice:     []bool{true, false, true},
		StringIntMap:  map[string]int32{"key1": 100, "key2": 200},
		IntStringMap:  map[int32]string{10: "value1", 20: "value2"},
		StringBoolMap: map[string]bool{"flag1": true, "flag2": false},
	}

	// Test with codegen-enabled fory
	f := fory.NewFory(true)

	// Verify that generated serializer is used
	serializer, err := f.GetSerializer(reflect.TypeOf(*original))
	require.NoError(t, err, "should be able to get serializer")

	// Assert that serializer is the generated serializer (not reflection)
	assert.IsType(t, CollectionTypesStruct_ForyGenSerializer{}, serializer,
		"should use generated serializer instead of reflection serializer")

	// Serialize
	data, err := f.Marshal(original)
	require.NoError(t, err, "serialization should succeed")

	// Deserialize
	var result CollectionTypesStruct
	err = f.Unmarshal(data, &result)
	require.NoError(t, err, "deserialization should succeed")

	// Use == to compare directly instead of comparing fields manually
	assert.Equal(t, *original, result, "serialization/deserialization result should equal original object")

	t.Logf("✅ CollectionTypesStruct codegen path test successful (%d bytes)", len(data))
}
