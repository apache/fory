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

	"github.com/stretchr/testify/require"
)

func TestUnionBasicTypes(t *testing.T) {
	f := NewFory()
	err := f.RegisterUnionType(reflect.TypeOf(int32(0)), reflect.TypeOf(""))
	require.NoError(t, err)

	// Test with int32 value
	unionInt := Union{Value: int32(42)}
	data, err := f.Serialize(unionInt)
	require.NoError(t, err)

	var result Union
	err = f.Deserialize(data, &result)
	require.NoError(t, err)
	require.Equal(t, int32(42), result.Value)

	// Test with string value
	unionStr := Union{Value: "hello"}
	data, err = f.Serialize(unionStr)
	require.NoError(t, err)

	err = f.Deserialize(data, &result)
	require.NoError(t, err)
	require.Equal(t, "hello", result.Value)
}

func TestUnionMultipleTypes(t *testing.T) {
	f := NewFory()
	err := f.RegisterUnionType(
		reflect.TypeOf(int32(0)),
		reflect.TypeOf(""),
		reflect.TypeOf(float64(0)),
	)
	require.NoError(t, err)

	// Test with int32
	union1 := Union{Value: int32(123)}
	data, err := f.Serialize(union1)
	require.NoError(t, err)

	var result Union
	err = f.Deserialize(data, &result)
	require.NoError(t, err)
	require.Equal(t, int32(123), result.Value)

	// Test with string
	union2 := Union{Value: "test"}
	data, err = f.Serialize(union2)
	require.NoError(t, err)

	err = f.Deserialize(data, &result)
	require.NoError(t, err)
	require.Equal(t, "test", result.Value)

	// Test with float64
	union3 := Union{Value: float64(3.14)}
	data, err = f.Serialize(union3)
	require.NoError(t, err)

	err = f.Deserialize(data, &result)
	require.NoError(t, err)
	require.InDelta(t, 3.14, result.Value.(float64), 0.0001)
}

func TestUnionNullValue(t *testing.T) {
	f := NewFory(WithTrackRef(true))
	err := f.RegisterUnionType(reflect.TypeOf(int32(0)), reflect.TypeOf(""))
	require.NoError(t, err)

	// Test with nil value
	unionNil := Union{Value: nil}
	data, err := f.Serialize(unionNil)
	require.NoError(t, err)

	var result Union
	err = f.Deserialize(data, &result)
	require.NoError(t, err)
	require.Nil(t, result.Value)
}

func TestUnionWithPointerValue(t *testing.T) {
	f := NewFory()
	err := f.RegisterUnionType(reflect.TypeOf((*int32)(nil)), reflect.TypeOf(""))
	require.NoError(t, err)

	// Test with pointer to int32
	val := int32(42)
	unionPtr := Union{Value: &val}
	data, err := f.Serialize(unionPtr)
	require.NoError(t, err)

	var result Union
	err = f.Deserialize(data, &result)
	require.NoError(t, err)

	resultPtr, ok := result.Value.(*int32)
	require.True(t, ok)
	require.Equal(t, int32(42), *resultPtr)
}

func TestUnionNewHelper(t *testing.T) {
	union := NewUnion(int32(42))
	require.Equal(t, int32(42), union.Value)
	require.False(t, union.IsNil())

	unionNil := NewUnion(nil)
	require.True(t, unionNil.IsNil())
}

func TestUnionInvalidAlternative(t *testing.T) {
	f := NewFory()
	err := f.RegisterUnionType(reflect.TypeOf(int32(0)), reflect.TypeOf(""))
	require.NoError(t, err)

	// Try to serialize a union with an unregistered alternative type
	unionBool := Union{Value: true}
	_, err = f.Serialize(unionBool)
	require.Error(t, err)
	require.Contains(t, err.Error(), "doesn't match any alternative")
}

func TestUnionEmptyRegistration(t *testing.T) {
	f := NewFory()
	err := f.RegisterUnionType()
	require.Error(t, err)
	require.Contains(t, err.Error(), "at least one alternative type")
}

func TestUnionWithBytes(t *testing.T) {
	f := NewFory()
	err := f.RegisterUnionType(reflect.TypeOf([]byte{}), reflect.TypeOf(""))
	require.NoError(t, err)

	// Test with bytes
	unionBytes := Union{Value: []byte("hello")}
	data, err := f.Serialize(unionBytes)
	require.NoError(t, err)

	var result Union
	err = f.Deserialize(data, &result)
	require.NoError(t, err)
	require.Equal(t, []byte("hello"), result.Value)

	// Test with string
	unionStr := Union{Value: "world"}
	data, err = f.Serialize(unionStr)
	require.NoError(t, err)

	err = f.Deserialize(data, &result)
	require.NoError(t, err)
	require.Equal(t, "world", result.Value)
}

func TestUnionWithRefTracking(t *testing.T) {
	f := NewFory(WithTrackRef(true))
	err := f.RegisterUnionType(reflect.TypeOf(int32(0)), reflect.TypeOf(""))
	require.NoError(t, err)

	// Test with int32 value
	unionInt := Union{Value: int32(42)}
	data, err := f.Serialize(unionInt)
	require.NoError(t, err)

	var result Union
	err = f.Deserialize(data, &result)
	require.NoError(t, err)
	require.Equal(t, int32(42), result.Value)

	// Test with string value
	unionStr := Union{Value: "hello"}
	data, err = f.Serialize(unionStr)
	require.NoError(t, err)

	err = f.Deserialize(data, &result)
	require.NoError(t, err)
	require.Equal(t, "hello", result.Value)
}

func TestUnionWithInt64AndBool(t *testing.T) {
	f := NewFory()
	err := f.RegisterUnionType(reflect.TypeOf(int64(0)), reflect.TypeOf(false))
	require.NoError(t, err)

	// Test with int64
	union1 := Union{Value: int64(9999999999)}
	data, err := f.Serialize(union1)
	require.NoError(t, err)

	var result Union
	err = f.Deserialize(data, &result)
	require.NoError(t, err)
	require.Equal(t, int64(9999999999), result.Value)

	// Test with bool
	union2 := Union{Value: true}
	data, err = f.Serialize(union2)
	require.NoError(t, err)

	err = f.Deserialize(data, &result)
	require.NoError(t, err)
	require.Equal(t, true, result.Value)
}
