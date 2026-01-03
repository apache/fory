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

	"github.com/stretchr/testify/require"
)

// ============================================================================
// Union2 Tests
// ============================================================================

func TestUnion2BasicTypes(t *testing.T) {
	f := NewFory()
	err := RegisterUnion2Type[int32, string](f)
	require.NoError(t, err)

	// Test with int32 value (first alternative)
	union1 := NewUnion2A[int32, string](42)
	data, err := f.Serialize(union1)
	require.NoError(t, err)

	var result1 Union2[int32, string]
	err = f.Deserialize(data, &result1)
	require.NoError(t, err)
	require.Equal(t, 1, result1.Index())
	require.True(t, result1.IsFirst())
	require.Equal(t, int32(42), result1.First())

	// Test with string value (second alternative)
	union2 := NewUnion2B[int32, string]("hello")
	data, err = f.Serialize(union2)
	require.NoError(t, err)

	var result2 Union2[int32, string]
	err = f.Deserialize(data, &result2)
	require.NoError(t, err)
	require.Equal(t, 2, result2.Index())
	require.True(t, result2.IsSecond())
	require.Equal(t, "hello", result2.Second())
}

func TestUnion2Match(t *testing.T) {
	union1 := NewUnion2A[int32, string](42)
	var matchedInt int32
	var matchedStr string

	union1.Match(
		func(i int32) { matchedInt = i },
		func(s string) { matchedStr = s },
	)
	require.Equal(t, int32(42), matchedInt)
	require.Empty(t, matchedStr)

	union2 := NewUnion2B[int32, string]("hello")
	matchedInt = 0
	matchedStr = ""

	union2.Match(
		func(i int32) { matchedInt = i },
		func(s string) { matchedStr = s },
	)
	require.Equal(t, int32(0), matchedInt)
	require.Equal(t, "hello", matchedStr)
}

func TestUnion2WithFloats(t *testing.T) {
	f := NewFory()
	err := RegisterUnion2Type[float32, float64](f)
	require.NoError(t, err)

	// Test with float32
	union1 := NewUnion2A[float32, float64](float32(3.14))
	data, err := f.Serialize(union1)
	require.NoError(t, err)

	var result1 Union2[float32, float64]
	err = f.Deserialize(data, &result1)
	require.NoError(t, err)
	require.True(t, result1.IsFirst())
	require.InDelta(t, float32(3.14), result1.First(), 0.0001)

	// Test with float64
	union2 := NewUnion2B[float32, float64](float64(2.71828))
	data, err = f.Serialize(union2)
	require.NoError(t, err)

	var result2 Union2[float32, float64]
	err = f.Deserialize(data, &result2)
	require.NoError(t, err)
	require.True(t, result2.IsSecond())
	require.InDelta(t, float64(2.71828), result2.Second(), 0.0001)
}

func TestUnion2WithBoolAndInt64(t *testing.T) {
	f := NewFory()
	err := RegisterUnion2Type[bool, int64](f)
	require.NoError(t, err)

	// Test with bool
	union1 := NewUnion2A[bool, int64](true)
	data, err := f.Serialize(union1)
	require.NoError(t, err)

	var result1 Union2[bool, int64]
	err = f.Deserialize(data, &result1)
	require.NoError(t, err)
	require.True(t, result1.IsFirst())
	require.True(t, result1.First())

	// Test with int64
	union2 := NewUnion2B[bool, int64](int64(9999999999))
	data, err = f.Serialize(union2)
	require.NoError(t, err)

	var result2 Union2[bool, int64]
	err = f.Deserialize(data, &result2)
	require.NoError(t, err)
	require.True(t, result2.IsSecond())
	require.Equal(t, int64(9999999999), result2.Second())
}

// ============================================================================
// Union3 Tests
// ============================================================================

func TestUnion3BasicTypes(t *testing.T) {
	f := NewFory()
	err := RegisterUnion3Type[int32, string, float64](f)
	require.NoError(t, err)

	// Test with int32 value (first alternative)
	union1 := NewUnion3A[int32, string, float64](123)
	data, err := f.Serialize(union1)
	require.NoError(t, err)

	var result1 Union3[int32, string, float64]
	err = f.Deserialize(data, &result1)
	require.NoError(t, err)
	require.Equal(t, 1, result1.Index())

	// Test with string value (second alternative)
	union2 := NewUnion3B[int32, string, float64]("test")
	data, err = f.Serialize(union2)
	require.NoError(t, err)

	var result2 Union3[int32, string, float64]
	err = f.Deserialize(data, &result2)
	require.NoError(t, err)
	require.Equal(t, 2, result2.Index())

	// Test with float64 value (third alternative)
	union3 := NewUnion3C[int32, string, float64](3.14)
	data, err = f.Serialize(union3)
	require.NoError(t, err)

	var result3 Union3[int32, string, float64]
	err = f.Deserialize(data, &result3)
	require.NoError(t, err)
	require.Equal(t, 3, result3.Index())
}

func TestUnion3Match(t *testing.T) {
	union := NewUnion3C[int32, string, float64](2.5)

	var matchedInt int32
	var matchedStr string
	var matchedFloat float64

	union.Match(
		func(i int32) { matchedInt = i },
		func(s string) { matchedStr = s },
		func(f float64) { matchedFloat = f },
	)

	require.Equal(t, int32(0), matchedInt)
	require.Empty(t, matchedStr)
	require.InDelta(t, 2.5, matchedFloat, 0.0001)
}

// ============================================================================
// Union4 Tests
// ============================================================================

func TestUnion4BasicTypes(t *testing.T) {
	f := NewFory()
	err := RegisterUnion4Type[int32, string, float64, bool](f)
	require.NoError(t, err)

	// Test with bool value (fourth alternative)
	union := NewUnion4D[int32, string, float64, bool](true)
	data, err := f.Serialize(union)
	require.NoError(t, err)

	var result Union4[int32, string, float64, bool]
	err = f.Deserialize(data, &result)
	require.NoError(t, err)
	require.Equal(t, 4, result.Index())
}

func TestUnion4Match(t *testing.T) {
	union := NewUnion4B[int32, string, float64, bool]("world")

	var matchedInt int32
	var matchedStr string
	var matchedFloat float64
	var matchedBool bool

	union.Match(
		func(i int32) { matchedInt = i },
		func(s string) { matchedStr = s },
		func(f float64) { matchedFloat = f },
		func(b bool) { matchedBool = b },
	)

	require.Equal(t, int32(0), matchedInt)
	require.Equal(t, "world", matchedStr)
	require.Equal(t, float64(0), matchedFloat)
	require.False(t, matchedBool)
}

// ============================================================================
// Edge Cases
// ============================================================================

func TestUnion2WithRefTracking(t *testing.T) {
	f := NewFory(WithTrackRef(true))
	err := RegisterUnion2Type[int32, string](f)
	require.NoError(t, err)

	// Test with int32 value
	union := NewUnion2A[int32, string](42)
	data, err := f.Serialize(union)
	require.NoError(t, err)

	var result Union2[int32, string]
	err = f.Deserialize(data, &result)
	require.NoError(t, err)
	require.Equal(t, int32(42), result.First())
}

func TestUnion2PanicOnWrongAccess(t *testing.T) {
	union := NewUnion2A[int32, string](42)

	// Accessing First() should work
	require.NotPanics(t, func() {
		_ = union.First()
	})

	// Accessing Second() should panic
	require.Panics(t, func() {
		_ = union.Second()
	})
}

func TestUnion2MultipleRegistrations(t *testing.T) {
	f := NewFory()

	// Register first Union2 type
	err := RegisterUnion2Type[int32, string](f)
	require.NoError(t, err)

	// Register second different Union2 type
	err = RegisterUnion2Type[bool, float64](f)
	require.NoError(t, err)

	// Serialize and deserialize first type
	union1 := NewUnion2A[int32, string](42)
	data1, err := f.Serialize(union1)
	require.NoError(t, err)

	var result1 Union2[int32, string]
	err = f.Deserialize(data1, &result1)
	require.NoError(t, err)
	require.Equal(t, int32(42), result1.First())

	// Serialize and deserialize second type
	union2 := NewUnion2A[bool, float64](true)
	data2, err := f.Serialize(union2)
	require.NoError(t, err)

	var result2 Union2[bool, float64]
	err = f.Deserialize(data2, &result2)
	require.NoError(t, err)
	require.True(t, result2.First())
}

func TestUnion2Bytes(t *testing.T) {
	f := NewFory()
	err := RegisterUnion2Type[[]byte, string](f)
	require.NoError(t, err)

	// Test with bytes
	union1 := NewUnion2A[[]byte, string]([]byte("hello bytes"))
	data, err := f.Serialize(union1)
	require.NoError(t, err)

	var result1 Union2[[]byte, string]
	err = f.Deserialize(data, &result1)
	require.NoError(t, err)
	require.True(t, result1.IsFirst())
	require.Equal(t, []byte("hello bytes"), result1.First())

	// Test with string
	union2 := NewUnion2B[[]byte, string]("hello string")
	data, err = f.Serialize(union2)
	require.NoError(t, err)

	var result2 Union2[[]byte, string]
	err = f.Deserialize(data, &result2)
	require.NoError(t, err)
	require.True(t, result2.IsSecond())
	require.Equal(t, "hello string", result2.Second())
}
