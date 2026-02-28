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

	"github.com/apache/fory/go/fory/bfloat16"
	"github.com/apache/fory/go/fory/float16"
	"github.com/stretchr/testify/require"
)

func TestTypeResolver(t *testing.T) {
	fory := NewFory(WithTrackRef(false), WithXlang(true))
	typeResolver := fory.typeResolver
	type A struct {
		F1 string
	}
	require.Nil(t, typeResolver.RegisterNamedStruct(reflect.TypeOf(A{}), 0, "", "example.A"))
	require.Error(t, typeResolver.RegisterNamedStruct(reflect.TypeOf(A{}), 0, "", "example.A"))

	var tests = []struct {
		type_    reflect.Type
		typeInfo string
	}{
		{reflect.TypeOf((*int)(nil)), "*int"},
		{reflect.TypeOf((*[10]int)(nil)), "*[10]int"},
		{reflect.TypeOf((*[10]int)(nil)).Elem(), "[10]int"},
		{reflect.TypeOf((*bfloat16.BFloat16)(nil)).Elem(), "bfloat16.BFloat16"},
		{reflect.TypeOf((*[]bfloat16.BFloat16)(nil)).Elem(), "[]bfloat16.BFloat16"},
		{reflect.TypeOf((*[10]bfloat16.BFloat16)(nil)).Elem(), "[10]bfloat16.BFloat16"},
		{reflect.TypeOf((*[]map[string][]map[string]*any)(nil)).Elem(),
			"[]map[string][]map[string]*interface {}"},
		{reflect.TypeOf((*A)(nil)), "*@example.A"},
		{reflect.TypeOf((*A)(nil)).Elem(), "@example.A"},
		{reflect.TypeOf([]uint16{}), "[]uint16"},
		{reflect.TypeOf([]uint32{}), "[]uint32"},
		{reflect.TypeOf([]uint64{}), "[]uint64"},
		{reflect.TypeOf((*[]map[string]int)(nil)), "*[]map[string]int"},
		{reflect.TypeOf((*[]map[A]int)(nil)), "*[]map[@example.A]int"},
		{reflect.TypeOf((*[]map[string]*A)(nil)), "*[]map[string]*@example.A"},
	}
	for _, test := range tests {
		typeStr, err := typeResolver.encodeType(test.type_)
		require.Nil(t, err)
		require.Equal(t, test.typeInfo, typeStr)
	}
	for _, test := range tests {
		type_, typeStr, err := typeResolver.decodeType(test.typeInfo)
		require.Nil(t, err)
		require.Equal(t, test.typeInfo, typeStr)
		require.Equal(t, test.type_, type_)
	}
}

func TestCreateSerializerSliceTypes(t *testing.T) {
	fory := NewFory()
	r := newTypeResolver(fory)

	tests := []struct {
		sliceType              reflect.Type
		expectedSerializerType reflect.Type
	}{
		{reflect.TypeOf([]bool{}), reflect.TypeOf(boolSliceSerializer{})},
		{reflect.TypeOf([]int8{}), reflect.TypeOf(int8SliceSerializer{})},
		{reflect.TypeOf([]int16{}), reflect.TypeOf(int16SliceSerializer{})},
		{reflect.TypeOf([]int32{}), reflect.TypeOf(int32SliceSerializer{})},
		{reflect.TypeOf([]int64{}), reflect.TypeOf(int64SliceSerializer{})},
		{reflect.TypeOf([]float32{}), reflect.TypeOf(float32SliceSerializer{})},
		{reflect.TypeOf([]float64{}), reflect.TypeOf(float64SliceSerializer{})},
		{reflect.TypeOf([]int{}), reflect.TypeOf(intSliceSerializer{})},
		{reflect.TypeOf([]uint{}), reflect.TypeOf(uintSliceSerializer{})},
		{reflect.TypeOf([]uint16{}), reflect.TypeOf(uint16SliceSerializer{})},
		{reflect.TypeOf([]float16.Float16{}), reflect.TypeOf(float16SliceSerializer{})},
		{reflect.TypeOf([]bfloat16.BFloat16{}), reflect.TypeOf(bfloat16SliceSerializer{})},
		{reflect.TypeOf([]uint32{}), reflect.TypeOf(uint32SliceSerializer{})},
		{reflect.TypeOf([]uint64{}), reflect.TypeOf(uint64SliceSerializer{})},
		{reflect.TypeOf([]string{}), reflect.TypeOf(stringSliceSerializer{})},
	}

	for _, test := range tests {
		serializer, err := r.createSerializer(test.sliceType, false)
		if err != nil {
			t.Fatalf("Failed to create serializer for %s: %v", test.sliceType, err)
		}

		if reflect.TypeOf(serializer) != test.expectedSerializerType {
			t.Errorf("For type %s, expected serializer of type %s, got %T", test.sliceType, test.expectedSerializerType, serializer)
		}
	}
}

func TestCreateSerializerArrayTypes(t *testing.T) {
	fory := NewFory()
	r := newTypeResolver(fory)

	var expectedIntArraySerializerType reflect.Type
	if reflect.TypeOf(int(0)).Size() == 8 {
		expectedIntArraySerializerType = reflect.TypeOf(int64ArraySerializer{})
	} else {
		expectedIntArraySerializerType = reflect.TypeOf(int32ArraySerializer{})
	}

	var expectedUintArraySerializerType reflect.Type
	if reflect.TypeOf(uint(0)).Size() == 8 {
		expectedUintArraySerializerType = reflect.TypeOf(uint64ArraySerializer{})
	} else {
		expectedUintArraySerializerType = reflect.TypeOf(uint32ArraySerializer{})
	}

	tests := []struct {
		arrayType              reflect.Type
		expectedSerializerType reflect.Type
	}{
		{reflect.TypeOf([4]bool{}), reflect.TypeOf(boolArraySerializer{})},
		{reflect.TypeOf([4]int8{}), reflect.TypeOf(int8ArraySerializer{})},
		{reflect.TypeOf([4]int16{}), reflect.TypeOf(int16ArraySerializer{})},
		{reflect.TypeOf([4]int32{}), reflect.TypeOf(int32ArraySerializer{})},
		{reflect.TypeOf([4]int64{}), reflect.TypeOf(int64ArraySerializer{})},
		{reflect.TypeOf([4]float32{}), reflect.TypeOf(float32ArraySerializer{})},
		{reflect.TypeOf([4]float64{}), reflect.TypeOf(float64ArraySerializer{})},
		{reflect.TypeOf([4]int{}), expectedIntArraySerializerType},
		{reflect.TypeOf([4]uint{}), expectedUintArraySerializerType},
		{reflect.TypeOf([4]byte{}), reflect.TypeOf(uint8ArraySerializer{})},
		{reflect.TypeOf([4]uint16{}), reflect.TypeOf(uint16ArraySerializer{})},
		{reflect.TypeOf([4]float16.Float16{}), reflect.TypeOf(float16ArraySerializer{})},
		{reflect.TypeOf([4]bfloat16.BFloat16{}), reflect.TypeOf(bfloat16ArraySerializer{})},
		{reflect.TypeOf([4]uint32{}), reflect.TypeOf(uint32ArraySerializer{})},
		{reflect.TypeOf([4]uint64{}), reflect.TypeOf(uint64ArraySerializer{})},
	}

	for _, test := range tests {
		serializer, err := r.createSerializer(test.arrayType, false)
		if err != nil {
			t.Fatalf("Failed to create serializer for %s: %v", test.arrayType, err)
		}

		if reflect.TypeOf(serializer) != test.expectedSerializerType {
			t.Errorf("For type %s, expected serializer of type %s, got %T", test.arrayType, test.expectedSerializerType, serializer)
		}
	}
}
