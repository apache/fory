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
		testStruct any
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
			fory := NewFory(WithRefTracking(false))

			if err := fory.RegisterNamedStruct(tt.testStruct, tt.tagName); err != nil {
				t.Fatalf("Failed to register tag type: %v", err)
			}

			structValue := reflect.ValueOf(tt.testStruct)
			originalTypeDef, err := buildTypeDef(fory, structValue)
			if err != nil {
				t.Fatalf("Failed to build TypeDef: %v", err)
			}

			buffer := NewByteBuffer(make([]byte, 0, 256))
			readErr := &Error{}
			originalTypeDef.writeTypeDef(buffer, readErr)

			decodedTypeDef := readTypeDef(fory, buffer, int64(buffer.ReadInt64(readErr)), readErr)
			if readErr.HasError() {
				t.Fatalf("Failed to decode TypeDef: %v", readErr.Error())
			}

			// basic checks
			assert.True(t, decodedTypeDef.typeId == originalTypeDef.typeId || decodedTypeDef.typeId == -originalTypeDef.typeId, "TypeId mismatch")
			assert.Equal(t, originalTypeDef.registerByName, decodedTypeDef.registerByName, "RegisterNamedStruct mismatch")
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
	if needsUserTypeID(original.TypeId()) || needsUserTypeID(decoded.TypeId()) {
		assert.Equal(t, original.UserTypeId(), decoded.UserTypeId(), "FieldType UserTypeId mismatch at path: %s", path)
	}

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

// Item1 struct with mixed nullable (pointer) and non-nullable (primitive) fields
type Item1 struct {
	F1 int32
	F2 int32
	F3 *int32
	F4 *int32
	F5 int32  // Non-nullable: null → 0 per test expectation
	F6 *int32 // Nullable: null stays nil
}

// TestTypeDefNullableFields verifies that pointer fields are correctly encoded as nullable
// and primitive fields are encoded as non-nullable in TypeDef
func TestTypeDefNullableFields(t *testing.T) {
	fory := NewFory(WithRefTracking(false))

	// Register the type
	if err := fory.RegisterNamedStruct(Item1{}, "test.Item1"); err != nil {
		t.Fatalf("Failed to register type: %v", err)
	}

	// Create test instance with some pointer fields set, some nil
	v3, v4, v6 := int32(30), int32(40), int32(60)
	testItem := Item1{
		F1: 10,
		F2: 20,
		F3: &v3,
		F4: &v4,
		F5: 50,
		F6: &v6,
	}

	// Build TypeDef
	structValue := reflect.ValueOf(testItem)
	typeDef, err := buildTypeDef(fory, structValue)
	if err != nil {
		t.Fatalf("Failed to build TypeDef: %v", err)
	}

	// Expected nullable status for each field:
	// F1, F2, F5 = int32 = non-nullable
	// F3, F4, F6 = *int32 = nullable
	expectedNullable := map[string]bool{
		"f1": false, // int32
		"f2": false, // int32
		"f3": true,  // *int32
		"f4": true,  // *int32
		"f5": false, // int32
		"f6": true,  // *int32
	}

	// Verify original TypeDef has correct nullable flags
	t.Run("Original TypeDef nullable flags", func(t *testing.T) {
		for _, fieldDef := range typeDef.fieldDefs {
			expected, ok := expectedNullable[fieldDef.name]
			if !ok {
				t.Errorf("Unexpected field name: %s", fieldDef.name)
				continue
			}
			assert.Equal(t, expected, fieldDef.nullable,
				"Field %s nullable mismatch: expected %v, got %v",
				fieldDef.name, expected, fieldDef.nullable)
		}
	})

	// Encode and decode TypeDef, then verify nullable flags are preserved
	t.Run("Encoded/Decoded TypeDef nullable flags", func(t *testing.T) {
		buffer := NewByteBuffer(make([]byte, 0, 256))
		readErr := &Error{}
		typeDef.writeTypeDef(buffer, readErr)

		decodedTypeDef := readTypeDef(fory, buffer, int64(buffer.ReadInt64(readErr)), readErr)
		if readErr.HasError() {
			t.Fatalf("Failed to decode TypeDef: %v", readErr.Error())
		}

		// Verify decoded TypeDef has correct nullable flags
		for _, fieldDef := range decodedTypeDef.fieldDefs {
			expected, ok := expectedNullable[fieldDef.name]
			if !ok {
				t.Errorf("Unexpected field name in decoded TypeDef: %s", fieldDef.name)
				continue
			}
			assert.Equal(t, expected, fieldDef.nullable,
				"Decoded field %s nullable mismatch: expected %v, got %v",
				fieldDef.name, expected, fieldDef.nullable)
		}
	})

	// Test with nil pointer fields
	t.Run("TypeDef with nil pointer fields", func(t *testing.T) {
		testItemWithNils := Item1{
			F1: 10,
			F2: 20,
			F3: nil, // nil pointer
			F4: &v4,
			F5: 50,
			F6: nil, // nil pointer
		}

		structValue := reflect.ValueOf(testItemWithNils)
		typeDefWithNils, err := buildTypeDef(fory, structValue)
		if err != nil {
			t.Fatalf("Failed to build TypeDef with nils: %v", err)
		}

		// Nullable flags should be the same regardless of actual values
		for _, fieldDef := range typeDefWithNils.fieldDefs {
			expected, ok := expectedNullable[fieldDef.name]
			if !ok {
				t.Errorf("Unexpected field name: %s", fieldDef.name)
				continue
			}
			assert.Equal(t, expected, fieldDef.nullable,
				"Field %s nullable mismatch (with nils): expected %v, got %v",
				fieldDef.name, expected, fieldDef.nullable)
		}
	})
}
