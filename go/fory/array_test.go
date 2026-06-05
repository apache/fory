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

func TestArrayDynSerializer(t *testing.T) {
	t.Run("rejects non-interface element type", func(t *testing.T) {
		var arr [3]string
		_, err := newArrayDynSerializer(reflect.TypeOf(arr).Elem())
		require.Error(t, err)
	})

	t.Run("accepts interface element type", func(t *testing.T) {
		var arr [3]any
		_, err := newArrayDynSerializer(reflect.TypeOf(arr).Elem())
		require.NoError(t, err)
	})
}

func TestArrayDynSerializerRoundTrip(t *testing.T) {
	f := NewFory(WithXlang(false), WithCompatible(false))

	t.Run("array of interfaces with strings", func(t *testing.T) {
		arr := [3]any{"hello", "world", "test"}

		bytes, err := f.Marshal(arr)
		require.NoError(t, err)

		var result any
		err = f.Unmarshal(bytes, &result)
		require.NoError(t, err)

		// Result will be a slice, not an array
		resultSlice, ok := result.([]any)
		require.True(t, ok)
		require.Equal(t, 3, len(resultSlice))
		require.Equal(t, arr[0], resultSlice[0])
		require.Equal(t, arr[1], resultSlice[1])
		require.Equal(t, arr[2], resultSlice[2])
	})

	t.Run("array of interfaces with nil", func(t *testing.T) {
		arr := [3]any{"hello", nil, "world"}

		bytes, err := f.Marshal(arr)
		require.NoError(t, err)

		var result any
		err = f.Unmarshal(bytes, &result)
		require.NoError(t, err)

		resultSlice, ok := result.([]any)
		require.True(t, ok)
		require.Equal(t, 3, len(resultSlice))
		require.Equal(t, arr[0], resultSlice[0])
		require.Nil(t, resultSlice[1])
		require.Equal(t, arr[2], resultSlice[2])
	})

	t.Run("array of interfaces with mixed types", func(t *testing.T) {
		arr := [4]any{"string", int32(42), true, float64(3.14)}

		bytes, err := f.Marshal(arr)
		require.NoError(t, err)

		var result any
		err = f.Unmarshal(bytes, &result)
		require.NoError(t, err)

		resultSlice, ok := result.([]any)
		require.True(t, ok)
		require.Equal(t, 4, len(resultSlice))
		require.Equal(t, arr[0], resultSlice[0])
		require.Equal(t, arr[1], resultSlice[1])
		require.Equal(t, arr[2], resultSlice[2])
		require.Equal(t, arr[3], resultSlice[3])
	})
}
