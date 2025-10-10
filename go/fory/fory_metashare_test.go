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

// Test structs for metashare testing

type SimpleDataClass struct {
	Name   string
	Age    int32
	Active bool
}
type InconsistentDataClass struct {
	Name   int32 // Different type
	Age    int32
	Active bool
}
type ExtendedDataClass struct {
	Name   string
	Age    int32
	Active bool
	Email  string // Additional field
}

type ReducedDataClass struct {
	Name string
	Age  int32
	// Missing 'active' field
}

type SliceDataClass struct {
	Name  string
	Items []string
	Nums  []int32
}

type MapDataClass struct {
	Name     string
	Metadata map[string]string
	Counters map[string]int32
}

type UnsortedStruct struct {
	StringField string
	FloatField  float64
	BoolField   bool
	IntField    int32
	ByteField   byte
}

type InconsistentSliceDataClass struct {
	Name  string
	Items []int32 // Different element type
	Nums  []int32
}

type InconsistentMapDataClass struct {
	Name     string
	Metadata map[string]int32 // Different value type
	Counters map[int32]int32  // Different key type
}

type NestedOuter struct {
	Name  string
	Inner SimpleDataClass
}

type NestedOuterIncompatible struct {
	Name  string
	Inner InconsistentDataClass
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

func TestCompatibleSerializationScenarios(t *testing.T) {
	cases := []compatibilityCase{
		{
			name:      "SimpleRoundTrip",
			tag:       "SimpleDataClass",
			writeType: SimpleDataClass{},
			readType:  SimpleDataClass{},
			input:     SimpleDataClass{Name: "test", Age: 25, Active: true},
			assertFunc: func(t *testing.T, input interface{}, output interface{}) {
				in := input.(SimpleDataClass)
				out := output.(SimpleDataClass)
				assert.Equal(t, in.Age, out.Age)
				assert.Equal(t, in.Active, out.Active)
				assert.Equal(t, in.Name, out.Name)
			},
		},
		{
			name:      "InconsistentTypeFallsBackToZeroValue",
			tag:       "TestStruct",
			writeType: SimpleDataClass{},
			readType:  InconsistentDataClass{},
			input:     SimpleDataClass{Name: "test", Age: 25, Active: true},
			assertFunc: func(t *testing.T, input interface{}, output interface{}) {
				in := input.(SimpleDataClass)
				out := output.(InconsistentDataClass)
				assert.Zero(t, out.Name)
				assert.Equal(t, in.Age, out.Age)
				assert.Equal(t, in.Active, out.Active)
			},
		},
		{
			name:      "FieldSorting",
			tag:       "UnsortedStruct",
			writeType: UnsortedStruct{},
			readType:  UnsortedStruct{},
			input: UnsortedStruct{
				StringField: "test",
				FloatField:  3.14,
				BoolField:   true,
				IntField:    42,
				ByteField:   255,
			},
			assertFunc: func(t *testing.T, input interface{}, output interface{}) {
				in := input.(UnsortedStruct)
				out := output.(UnsortedStruct)
				assert.Equal(t, in.FloatField, out.FloatField)
				assert.Equal(t, in.IntField, out.IntField)
				assert.Equal(t, in.BoolField, out.BoolField)
				assert.Equal(t, in.ByteField, out.ByteField)
				assert.Equal(t, in.StringField, out.StringField)
			},
		},
		{
			name:      "SchemaEvolutionAddField",
			tag:       "TestStructAdd",
			writeType: SimpleDataClass{},
			readType:  ExtendedDataClass{},
			input:     SimpleDataClass{Name: "test", Age: 25, Active: true},
			assertFunc: func(t *testing.T, input interface{}, output interface{}) {
				in := input.(SimpleDataClass)
				out := output.(ExtendedDataClass)
				assert.Equal(t, in.Name, out.Name)
				assert.Equal(t, in.Age, out.Age)
				assert.Equal(t, in.Active, out.Active)
				assert.Equal(t, "", out.Email)
			},
		},
		{
			name:      "SchemaEvolutionRemoveField",
			tag:       "TestStructRemove",
			writeType: SimpleDataClass{},
			readType:  ReducedDataClass{},
			input:     SimpleDataClass{Name: "test", Age: 25, Active: true},
			assertFunc: func(t *testing.T, input interface{}, output interface{}) {
				in := input.(SimpleDataClass)
				out := output.(ReducedDataClass)
				assert.Equal(t, in.Name, out.Name)
				assert.Equal(t, in.Age, out.Age)
			},
		},
		{
			name:      "SliceFields",
			tag:       "SliceDataClass",
			writeType: SliceDataClass{},
			readType:  SliceDataClass{},
			input: SliceDataClass{
				Name:  "test",
				Items: []string{"item1", "item2", "item3"},
				Nums:  []int32{10, 20, 30, 40},
			},
			assertFunc: func(t *testing.T, input interface{}, output interface{}) {
				in := input.(SliceDataClass)
				out := output.(SliceDataClass)
				assert.Equal(t, in.Name, out.Name)
				assert.Equal(t, in.Items, out.Items)
				assert.Equal(t, in.Nums, out.Nums)
			},
		},
		{
			name:      "InconsistentSliceElements",
			tag:       "SliceDataClass",
			writeType: SliceDataClass{},
			readType:  InconsistentSliceDataClass{},
			input: SliceDataClass{
				Name:  "test",
				Items: []string{"item1", "item2"},
				Nums:  []int32{1, 2, 3},
			},
			assertFunc: func(t *testing.T, input interface{}, output interface{}) {
				in := input.(SliceDataClass)
				out := output.(InconsistentSliceDataClass)
				assert.Equal(t, in.Name, out.Name)
				assert.Nil(t, out.Items)
				assert.Equal(t, in.Nums, out.Nums)
			},
		},
		{
			name:      "MapFields",
			tag:       "MapDataClass",
			writeType: MapDataClass{},
			readType:  MapDataClass{},
			input: MapDataClass{
				Name: "test",
				Metadata: map[string]string{
					"version": "1.0",
					"author":  "test_user",
					"env":     "production",
				},
				Counters: map[string]int32{
					"requests": 100,
					"errors":   5,
					"success":  95,
				},
			},
			assertFunc: func(t *testing.T, input interface{}, output interface{}) {
				in := input.(MapDataClass)
				out := output.(MapDataClass)
				assert.Equal(t, in.Name, out.Name)
				assert.Equal(t, len(in.Metadata), len(out.Metadata))
				assert.Equal(t, in.Metadata, out.Metadata)
				assert.Equal(t, len(in.Counters), len(out.Counters))
				assert.Equal(t, in.Counters, out.Counters)
			},
		},
		{
			name:      "InconsistentMapValues",
			tag:       "MapDataClass",
			writeType: MapDataClass{},
			readType:  InconsistentMapDataClass{},
			input: MapDataClass{
				Name: "test",
				Metadata: map[string]string{
					"key1": "value1",
					"key2": "value2",
				},
				Counters: map[string]int32{
					"c1": 10,
					"c2": 20,
				},
			},
			assertFunc: func(t *testing.T, input interface{}, output interface{}) {
				in := input.(MapDataClass)
				out := output.(InconsistentMapDataClass)
				assert.Equal(t, in.Name, out.Name)
				assert.Nil(t, out.Metadata)
				assert.Nil(t, out.Counters)
			},
		},
		{
			name:      "NestedStruct",
			tag:       "NestedOuter",
			writeType: NestedOuter{},
			readType:  NestedOuter{},
			input: NestedOuter{
				Name:  "outer",
				Inner: SimpleDataClass{Name: "inner", Age: 18, Active: true},
			},
			writerSetup: func(f *Fory) error {
				if err := f.RegisterNamedType(SimpleDataClass{}, "SimpleDataClass"); err != nil {
					return err
				}
				return nil
			},
			readerSetup: func(f *Fory) error {
				if err := f.RegisterNamedType(SimpleDataClass{}, "SimpleDataClass"); err != nil {
					return err
				}
				return nil
			},
			assertFunc: func(t *testing.T, input interface{}, output interface{}) {
				in := input.(NestedOuter)
				out := output.(NestedOuter)
				assert.Equal(t, in.Name, out.Name)
				assert.Equal(t, in.Inner, out.Inner)
			},
		},
		{
			name:      "NestedStructIncompatible",
			tag:       "NestedOuter",
			writeType: NestedOuter{},
			readType:  NestedOuterIncompatible{},
			input: NestedOuter{
				Name:  "outer",
				Inner: SimpleDataClass{Name: "inner", Age: 18, Active: true},
			},
			writerSetup: func(f *Fory) error {
				if err := f.RegisterNamedType(SimpleDataClass{}, "SimpleDataClass"); err != nil {
					return err
				}
				return nil
			},
			readerSetup: func(f *Fory) error {
				if err := f.RegisterNamedType(InconsistentDataClass{}, "SimpleDataClass"); err != nil {
					return err
				}
				return nil
			},
			assertFunc: func(t *testing.T, input interface{}, output interface{}) {
				in := input.(NestedOuter)
				out := output.(NestedOuterIncompatible)
				assert.Equal(t, in.Name, out.Name)
				assert.Zero(t, out.Inner.Name)
				assert.Equal(t, in.Inner.Age, out.Inner.Age)
				assert.Equal(t, in.Inner.Active, out.Inner.Active)
			},
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			runCompatibilityCase(t, tc)
		})
	}
}

type compatibilityCase struct {
	name        string
	tag         string
	writeType   interface{}
	readType    interface{}
	input       interface{}
	assertFunc  func(t *testing.T, input interface{}, output interface{})
	writerSetup func(*Fory) error
	readerSetup func(*Fory) error
}

func runCompatibilityCase(t *testing.T, tc compatibilityCase) {
	t.Helper()

	writer := NewForyWithOptions(WithCompatible(true))
	if tc.writerSetup != nil {
		err := tc.writerSetup(writer)
		assert.NoError(t, err)
	}
	err := writer.RegisterNamedType(tc.writeType, tc.tag)
	assert.NoError(t, err)

	data, err := writer.Marshal(tc.input)
	assert.NoError(t, err)

	reader := NewForyWithOptions(WithCompatible(true))
	if tc.readerSetup != nil {
		err = tc.readerSetup(reader)
		assert.NoError(t, err)
	}
	err = reader.RegisterNamedType(tc.readType, tc.tag)
	assert.NoError(t, err)

	target := reflect.New(reflect.TypeOf(tc.readType))
	var unmarshalErr error
	assert.NotPanics(t, func() {
		unmarshalErr = reader.Unmarshal(data, target.Interface())
	})
	assert.NoError(t, unmarshalErr)

	tc.assertFunc(t, tc.input, target.Elem().Interface())
}
