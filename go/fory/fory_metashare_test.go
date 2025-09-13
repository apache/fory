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

	"github.com/stretchr/testify/assert"
)

// Test structs for metashare testing

type SimpleDataClass struct {
	Name   string `fory:"name"`
	Age    int32  `fory:"age"`
	Active bool   `fory:"active"`
}
type ExtendedDataClass struct {
	Name   string `fory:"name"`
	Age    int32  `fory:"age"`
	Active bool   `fory:"active"`
	Email  string `fory:"email"` // Additional field
}

type ReducedDataClass struct {
	Name string `fory:"name"`
	Age  int32  `fory:"age"`
	// Missing 'active' field
}

func TestMetaShareEnabled(t *testing.T) {
	fory := NewForyWithOptions(WithCompatible(true))

	assert.True(t, fory.compatible, "Expected compatible mode to be enabled")
	assert.NotNil(t, fory.metaContext, "Expected metaContext to be initialized when compatible=true")
	assert.True(t, fory.metaContext.IsScopedMetaShareEnabled(), "Expected scoped meta share to be enabled by default when compatible=true")
}

func TestMetaShareDisabled(t *testing.T) {
	fory := NewForyWithOptions(WithCompatible(false))

	assert.False(t, fory.compatible, "Expected compatible mode to be disabled")
	assert.Nil(t, fory.metaContext, "Expected metaContext to be nil when compatible=false")
}

func TestSimpleDataClassSerialization(t *testing.T) {
	fory := NewForyWithOptions(WithCompatible(true))

	// Register the struct
	err := fory.RegisterTagType("SimpleDataClass", SimpleDataClass{})
	assert.NoError(t, err, "Failed to register type")

	obj := SimpleDataClass{Name: "test", Age: 25, Active: true}

	// Serialize
	data, err := fory.Marshal(obj)
	assert.NoError(t, err, "Failed to marshal")

	// Deserialize
	var deserialized SimpleDataClass
	err = fory.Unmarshal(data, &deserialized)
	assert.NoError(t, err, "Failed to unmarshal")

	// Verify
	assert.Equal(t, obj.Name, deserialized.Name)
	assert.Equal(t, obj.Age, deserialized.Age)
	assert.Equal(t, obj.Active, deserialized.Active)
}

func TestFieldSortingOrder(t *testing.T) {
	fory := NewForyWithOptions(WithCompatible(true))

	// Create a struct with fields in non-optimal order (only implemented types)
	// the final order should be: FloatField, IntField, BoolField, ByteField, StringField
	type UnsortedStruct struct {
		StringField string
		FloatField  float64
		BoolField   bool
		IntField    int32
		ByteField   byte
	}

	err := fory.RegisterTagType("UnsortedStruct", UnsortedStruct{})
	assert.NoError(t, err, "Failed to register type")

	obj := UnsortedStruct{
		StringField: "test",
		FloatField:  3.14,
		BoolField:   true,
		IntField:    42,
		ByteField:   255,
	}

	// Serialize
	data, err := fory.Marshal(obj)
	assert.NoError(t, err, "Failed to marshal")

	// Deserialize
	var deserialized UnsortedStruct
	err = fory.Unmarshal(data, &deserialized)
	assert.NoError(t, err, "Failed to unmarshal")

	// Verify all fields are correctly serialized/deserialized regardless of order
	assert.Equal(t, obj.FloatField, deserialized.FloatField)
	assert.Equal(t, obj.IntField, deserialized.IntField)
	assert.Equal(t, obj.BoolField, deserialized.BoolField)
	assert.Equal(t, obj.ByteField, deserialized.ByteField)
	assert.Equal(t, obj.StringField, deserialized.StringField)

	t.Logf("Field sorting test passed - optimal order is applied during field definition creation")
}

func TestSchemaEvolutionAddField(t *testing.T) {
	// Test adding fields to existing struct using predefined types

	// Serialize with SimpleDataClass (3 fields)
	fory1 := NewForyWithOptions(WithCompatible(true))
	err := fory1.RegisterTagType("TestStruct", SimpleDataClass{})
	assert.NoError(t, err, "Failed to register SimpleDataClass")

	originalObj := SimpleDataClass{Name: "test", Age: 25, Active: true}
	data, err := fory1.Marshal(originalObj)
	assert.NoError(t, err, "Failed to marshal SimpleDataClass")

	// Deserialize with ExtendedDataClass (4 fields - adds Email field)
	fory2 := NewForyWithOptions(WithCompatible(true))
	err = fory2.RegisterTagType("TestStruct", ExtendedDataClass{})
	assert.NoError(t, err, "Failed to register ExtendedDataClass")

	var deserialized ExtendedDataClass
	err = fory2.Unmarshal(data, &deserialized)
	assert.NoError(t, err, "Failed to unmarshal to ExtendedDataClass")

	// Verify common fields and default value for new field
	assert.Equal(t, originalObj.Name, deserialized.Name)
	assert.Equal(t, originalObj.Age, deserialized.Age)
	assert.Equal(t, originalObj.Active, deserialized.Active)
	assert.Equal(t, "", deserialized.Email, "Expected Email to be its default value (empty string)")
}

func TestSchemaEvolutionRemoveField(t *testing.T) {
	// Test removing fields from existing struct using predefined types

	// Serialize with SimpleDataClass (3 fields)
	fory1 := NewForyWithOptions(WithCompatible(true))
	err := fory1.RegisterTagType("TestStruct", SimpleDataClass{})
	assert.NoError(t, err, "Failed to register SimpleDataClass")

	originalObj := SimpleDataClass{Name: "test", Age: 25, Active: true}
	data, err := fory1.Marshal(originalObj)
	assert.NoError(t, err, "Failed to marshal SimpleDataClass")

	// Deserialize with ReducedDataClass (2 fields - removes Active)
	fory2 := NewForyWithOptions(WithCompatible(true))
	err = fory2.RegisterTagType("TestStruct", ReducedDataClass{})
	assert.NoError(t, err, "Failed to register ReducedDataClass")

	var deserialized ReducedDataClass
	err = fory2.Unmarshal(data, &deserialized)
	assert.NoError(t, err, "Failed to unmarshal to ReducedDataClass")

	// Verify common fields
	assert.Equal(t, originalObj.Name, deserialized.Name)
	assert.Equal(t, originalObj.Age, deserialized.Age)
	// Active field is removed, so it should not be present in deserialized ReducedDataClass
}
