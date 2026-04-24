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
	"fmt"
	"github.com/stretchr/testify/require"
	"reflect"
	"testing"
	"unsafe"
)

func TestReferenceResolver(t *testing.T) {
	refResolver := newRefResolver(true)
	buf := NewByteBuffer(nil)
	var values []any
	values = append(values, commonSlice()...)
	values = append(values, commonMap()...)
	foo := newFoo()
	bar := Bar{}
	values = append(values, &foo, &bar)
	for _, data := range values {
		refWritten, err := refResolver.WriteRefOrNull(buf, reflect.ValueOf(data))
		require.Nil(t, err)
		require.False(t, refWritten)
		refWritten, err = refResolver.WriteRefOrNull(buf, reflect.ValueOf(data))
		require.Nil(t, err)
		require.True(t, refWritten)
	}
	refResolver.readObjects = make([]reflect.Value, len(refResolver.writtenObjects))
	ctxErr := &Error{}
	for range values {
		require.Equal(t, refResolver.ReadRefOrNull(buf, ctxErr), RefValueFlag)
		require.Equal(t, refResolver.ReadRefOrNull(buf, ctxErr), RefFlag)
	}
	{
		s := []int{1, 2, 3}
		require.True(t, same(s, s))
		require.False(t, same(s, s[1:]))
		refWritten, err := refResolver.WriteRefOrNull(buf, reflect.ValueOf(s))
		require.Nil(t, err)
		require.False(t, refWritten)
		refWritten, err = refResolver.WriteRefOrNull(buf, reflect.ValueOf(s))
		require.Nil(t, err)
		require.True(t, refWritten)
		refWritten, err = refResolver.WriteRefOrNull(buf, reflect.ValueOf(s[1:]))
		require.Nil(t, err)
		require.False(t, refWritten)
	}
}

func TestNonReferenceResolver(t *testing.T) {
	refResolver := newRefResolver(false)
	buf := NewByteBuffer(nil)
	var values []any
	values = append(values, commonSlice()...)
	values = append(values, commonMap()...)
	foo := newFoo()
	bar := Bar{}
	values = append(values, "", "str", &foo, &bar)
	for _, data := range values {
		refWritten, err := refResolver.WriteRefOrNull(buf, reflect.ValueOf(data))
		require.Nil(t, err)
		require.False(t, refWritten)
		refWritten, err = refResolver.WriteRefOrNull(buf, reflect.ValueOf(data))
		require.Nil(t, err)
		require.False(t, refWritten)
	}
	ctxErr := &Error{}
	for range values {
		require.Equal(t, refResolver.ReadRefOrNull(buf, ctxErr), NotNullValueFlag)
		require.Equal(t, refResolver.ReadRefOrNull(buf, ctxErr), NotNullValueFlag)
	}
}

func TestNullable(t *testing.T) {
	var values []any
	values = append(values, commonSlice()...)
	values = append(values, commonMap()...)
	foo := newFoo()
	bar := Bar{}
	values = append(values, "", "str", &foo, &bar)
	for _, data := range values {
		require.True(t, nullable(reflect.ValueOf(data).Type()))
	}
	require.False(t, nullable(reflect.ValueOf(1).Type()))
	var v1 []int
	require.True(t, isNil(reflect.ValueOf(v1)))
	var v2 map[string]int
	require.True(t, isNil(reflect.ValueOf(v2)))
	require.False(t, isNil(reflect.ValueOf("")))
	var v3 any
	require.True(t, isNil(reflect.ValueOf(v3)))
}

func same(x, y any) bool {
	var vx, vy = reflect.ValueOf(x), reflect.ValueOf(y)
	if vx.Type() != vy.Type() {
		return false
	}
	if vx.Type().Kind() == reflect.Slice {
		if vx.Len() != vy.Len() {
			return false
		}
	}
	return unsafe.Pointer(reflect.ValueOf(x).Pointer()) == unsafe.Pointer(reflect.ValueOf(y).Pointer())
}

// TestRefTrackingLargeCount tests that reference tracking works correctly
// when the number of tracked objects exceeds 127 (the int8 overflow boundary).
// This is a regression test for https://github.com/apache/fory/issues/3085
func TestRefTrackingLargeCount(t *testing.T) {
	type Inner struct {
		Name     string
		Operator string
		Version  string
	}

	type Outer struct {
		Id    int32
		Name  string
		Items []Inner
	}

	tests := []struct {
		name  string
		count int
	}{
		{"127 items (boundary)", 127},
		{"128 items (overflow boundary)", 128},
		{"200 items (well over boundary)", 200},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f := New(WithXlang(true), WithRefTracking(true))

			err := f.RegisterNamedStruct(&Inner{}, fmt.Sprintf("RefTest_Inner_%d", tt.count))
			require.NoError(t, err)
			err = f.RegisterNamedStruct(&Outer{}, fmt.Sprintf("RefTest_Outer_%d", tt.count))
			require.NoError(t, err)

			original := make([]Outer, tt.count)
			for i := 0; i < tt.count; i++ {
				original[i] = Outer{
					Id:   int32(i),
					Name: fmt.Sprintf("item%d", i),
					Items: []Inner{
						{Name: "dep1", Operator: ">=", Version: "1.0.0"},
					},
				}
			}

			data, err := f.Marshal(original)
			require.NoError(t, err)

			var loaded []Outer
			err = f.Unmarshal(data, &loaded)
			require.NoError(t, err, "Unmarshal should succeed with %d items", tt.count)
			require.Equal(t, len(original), len(loaded))

			// Verify data integrity
			for i := 0; i < tt.count; i++ {
				require.Equal(t, original[i].Id, loaded[i].Id)
				require.Equal(t, original[i].Name, loaded[i].Name)
				require.Equal(t, len(original[i].Items), len(loaded[i].Items))
			}
		})
	}
}

// TestRefResolverOOBPanic reproduces the CRITICAL security bug in RefResolver
// where a huge refId in the buffer triggers an OOB panic.
func TestRefResolverOOBPanic(t *testing.T) {
	resolver := newRefResolver(true)
	buffer := NewByteBuffer(nil)

	// Craft a buffer with RefFlag (-2) followed by a huge RefID (9999)
	buffer.WriteInt8(RefFlag)
	buffer.WriteVarUint32(9999)
	buffer.SetReaderIndex(0)

	var ctxErr Error
	// This should NOT panic. The fix handles the invalid index gracefully.
	require.NotPanics(t, func() {
		resolver.ReadRefOrNull(buffer, &ctxErr)
	}, "RefResolver.GetReadObject should not panic on OOB index")
}

// TestRefResolverBoundaryRegression verifies that valid boundary indices
// (0 and len-1) still resolve correctly after the security fix.
func TestRefResolverBoundaryRegression(t *testing.T) {
	resolver := newRefResolver(true)
	val := reflect.ValueOf("test")

	// Fill the resolver with 2 objects
	id0, _ := resolver.PreserveRefId()
	resolver.SetReadObject(id0, val)
	id1, _ := resolver.PreserveRefId()
	resolver.SetReadObject(id1, val)

	// Verify boundary indices still work
	require.Equal(t, val.String(), resolver.GetReadObject(0).String(), "Should resolve index 0")
	require.Equal(t, val.String(), resolver.GetReadObject(1).String(), "Should resolve index len-1")
}
