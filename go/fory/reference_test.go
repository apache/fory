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
	"unsafe"

	"github.com/stretchr/testify/require"
)

func TestReferenceResolver(t *testing.T) {
	refResolver := newRefResolver(true)
	buf := NewByteBuffer(nil)
	var values []interface{}
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
	for range values {
		require.Equal(t, refResolver.ReadRefOrNull(buf), RefValueFlag)
		require.Equal(t, refResolver.ReadRefOrNull(buf), RefFlag)
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
	var values []interface{}
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
	for range values {
		require.Equal(t, refResolver.ReadRefOrNull(buf), NotNullValueFlag)
		require.Equal(t, refResolver.ReadRefOrNull(buf), NotNullValueFlag)
	}
}

func TestNullable(t *testing.T) {
	var values []interface{}
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
	var v3 interface{}
	require.True(t, isNil(reflect.ValueOf(v3)))
}

func same(x, y interface{}) bool {
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
