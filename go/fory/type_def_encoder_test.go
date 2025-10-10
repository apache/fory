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

	"github.com/stretchr/testify/assert"
)

// Test structs for encoding/decoding
type SimpleStruct struct {
	ID   int32
	Name string
}

type SliceStruct struct {
	ID    int32
	Items []string
}

type NestedSliceStruct struct {
	ID      int32
	Matrix  [][]int
	Records [][]string
}

type MapStruct struct {
	ID   int32
	Data map[string]int
}

type ComplexStruct struct {
	ID       int32
	SliceMap map[string][]int
	MapSlice []map[string]int
}

// TestTypeDefEncodingDecodingTableDriven tests encoding and decoding of TypeDef
// This ensure the peer can successfully encode and decode the same TypeDef, and obtain appropriate serializer to read or skip data
func TestTypeDefEncodingDecoding(t *testing.T) {
	tests := []struct {
		name       string
		tagName    string
		testStruct interface{}
	}{
		{
			name:    "SimpleStruct with basic fields",
			tagName: "example.SimpleStruct",
			testStruct: SimpleStruct{
				ID:   42,
				Name: "test",
			},
		},
		{
			name:    "SliceStruct with basic items",
			tagName: "example.SliceStruct",
			testStruct: SliceStruct{
				ID:    100,
				Items: []string{"item1", "item2", "item3"},
			},
		},
		{
			name:    "NestedSliceStruct with nested collections",
			tagName: "example.NestedSliceStruct",
			testStruct: NestedSliceStruct{
				ID:      200,
				Matrix:  [][]int{{1, 2}, {3, 4}},
				Records: [][]string{{"a", "b"}, {"c", "d"}},
			},
		},
		{
			name:    "MapStruct with map fields",
			tagName: "example.MapStruct",
			testStruct: MapStruct{
				ID:   300,
				Data: map[string]int{"key1": 1, "key2": 2},
			},
		},
		{
			name:    "ComplexStruct with complex nested types",
			tagName: "example.ComplexStruct",
			testStruct: ComplexStruct{
				ID:       400,
				SliceMap: map[string][]int{"list1": {1, 2, 3}, "list2": {4, 5, 6}},
				MapSlice: []map[string]int{{"a": 1}, {"b": 2}},
			},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			fory := NewFory(false)

			if err := fory.RegisterNamedType(tt.testStruct, tt.tagName); err != nil {
				t.Fatalf("Failed to register tag type: %v", err)
			}

			structValue := reflect.ValueOf(tt.testStruct)
			originalTypeDef, err := buildTypeDef(fory, structValue)
			if err != nil {
				t.Fatalf("Failed to build TypeDef: %v", err)
			}

			buffer := NewByteBuffer(make([]byte, 0, 256))
			originalTypeDef.writeTypeDef(buffer)

			decodedTypeDef, err := readTypeDef(fory, buffer, int64(buffer.ReadInt64()))
			if err != nil {
				t.Fatalf("Failed to decode TypeDef: %v", err)
			}

			// basic checks
			assert.True(t, decodedTypeDef.typeId == originalTypeDef.typeId || decodedTypeDef.typeId == -originalTypeDef.typeId, "TypeId mismatch")
			assert.Equal(t, originalTypeDef.registerByName, decodedTypeDef.registerByName, "RegisterByName mismatch")
			assert.Equal(t, originalTypeDef.compressed, decodedTypeDef.compressed, "Compressed flag mismatch")
			assert.Equal(t, len(originalTypeDef.fieldDefs), len(decodedTypeDef.fieldDefs), "Field count mismatch")

			for i, originalField := range originalTypeDef.fieldDefs {
				checkFieldDef(t, originalField, decodedTypeDef.fieldDefs[i])
			}
		})
	}
}

func checkFieldDef(t *testing.T, original, decoded FieldDef) {
	assert.Equal(t, original.name, decoded.name, "Field name mismatch")
	assert.Equal(t, original.nameEncoding, decoded.nameEncoding, "Field name encoding mismatch")
	assert.Equal(t, original.nullable, decoded.nullable, "Field nullable mismatch")
	assert.Equal(t, original.trackingRef, decoded.trackingRef, "Field trackingRef mismatch")
	checkFieldTypeRecursively(t, original.fieldType, decoded.fieldType, original.name)
}

func checkFieldTypeRecursively(t *testing.T, original, decoded FieldType, path string) {
	// Check TypeId
	assert.Equal(t, original.TypeId(), decoded.TypeId(), "FieldType TypeId mismatch at path: %s", path)

	// Check type consistency based on the actual type
	switch originalType := original.(type) {
	case *SimpleFieldType:
		_, ok := decoded.(*SimpleFieldType)
		assert.True(t, ok, "Type mismatch at path %s: original is SimpleFieldType but decoded is not", path)

	case *CollectionFieldType:
		decodedCollection, ok := decoded.(*CollectionFieldType)
		assert.True(t, ok, "Type mismatch at path %s: original is CollectionFieldType but decoded is not", path)
		if ok {
			// Recursively check element type
			checkFieldTypeRecursively(t, originalType.elementType, decodedCollection.elementType, path+"[]")
		}

	case *MapFieldType:
		decodedMap, ok := decoded.(*MapFieldType)
		assert.True(t, ok, "Type mismatch at path %s: original is MapFieldType but decoded is not", path)
		if ok {
			// Recursively check key and value types
			checkFieldTypeRecursively(t, originalType.keyType, decodedMap.keyType, path+"[key]")
			checkFieldTypeRecursively(t, originalType.valueType, decodedMap.valueType, path+"[value]")
		}

	default:
		t.Errorf("Unknown FieldType at path %s: %T", path, original)
	}
}
