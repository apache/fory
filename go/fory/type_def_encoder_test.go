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

// TestTypeDefEncodingDecoding tests the encoding and decoding of TypeDef
func TestTypeDefEncodingDecoding(t *testing.T) {
	// Create a Fory instance for testing
	fory := NewFory(false)

	// Create a test struct instance
	testStruct := SimpleStruct{
		ID:   42,
		Name: "test",
	}

	if err := fory.RegisterTagType("example.SimpleStruct", testStruct); err != nil {
		t.Fatalf("Failed to register tag type: %v", err)
	}

	// Build TypeDef from the struct
	structValue := reflect.ValueOf(testStruct)
	originalTypeDef, err := buildTypeDef(fory, structValue)
	if err != nil {
		t.Fatalf("Failed to build TypeDef: %v", err)
	}

	// Create a buffer with the encoded data
	buffer := NewByteBuffer(make([]byte, 0, 256))
	originalTypeDef.writeTypeDef(buffer)

	// Decode the TypeDef
	decodedTypeDef, err := readTypeDef(fory, buffer)
	if err != nil {
		t.Fatalf("Failed to decode TypeDef: %v", err)
	}

	// Verify typeId(ignore sign)
	assert.True(t, decodedTypeDef.typeId == originalTypeDef.typeId || decodedTypeDef.typeId == -originalTypeDef.typeId, "TypeId mismatch")
	assert.Equal(t, originalTypeDef.registerByName, decodedTypeDef.registerByName, "RegisterByName mismatch")
	assert.Equal(t, originalTypeDef.compressed, decodedTypeDef.compressed, "Compressed flag mismatch")

	// Verify field count matches
	assert.Equal(t, len(originalTypeDef.fieldDefs), len(decodedTypeDef.fieldDefs), "Field count mismatch")

	// Verify field names match
	for i, originalField := range originalTypeDef.fieldDefs {
		decodedField := decodedTypeDef.fieldDefs[i]

		assert.Equal(t, originalField.name, decodedField.name, "Field name mismatch at index %d", i)
		assert.Equal(t, originalField.nameEncoding, decodedField.nameEncoding, "Field name encoding mismatch at index %d", i)
		assert.Equal(t, originalField.nullable, decodedField.nullable, "Field nullable mismatch at index %d", i)
		assert.Equal(t, originalField.trackingRef, decodedField.trackingRef, "Field trackingRef mismatch at index %d", i)
		assert.Equal(t, originalField.fieldType.TypeId(), decodedField.fieldType.TypeId(), "Field type ID mismatch at index %d", i)
	}
}
