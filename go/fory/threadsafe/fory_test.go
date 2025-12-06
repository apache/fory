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

package threadsafe

import (
	"testing"

	"github.com/apache/fory/go/fory"
	"github.com/stretchr/testify/require"
)

// TestFory tests the thread-safe Fory wrapper
func TestFory(t *testing.T) {
	f := New(fory.WithRefTracking(true))

	t.Run("BasicSerialization", func(t *testing.T) {
		data, err := f.Serialize(int32(42))
		require.NoError(t, err)

		var result int32
		err = f.Deserialize(data, &result)
		require.NoError(t, err)
		require.Equal(t, int32(42), result)
	})

	t.Run("GenericSerialization", func(t *testing.T) {
		val := "hello world"
		data, err := Serialize(f, &val)
		require.NoError(t, err)

		var result string
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.Equal(t, "hello world", result)
	})

	t.Run("ConcurrentAccess", func(t *testing.T) {
		done := make(chan bool, 10)
		for i := 0; i < 10; i++ {
			go func(val int32) {
				data, err := f.Serialize(val)
				require.NoError(t, err)

				var result int32
				err = f.Deserialize(data, &result)
				require.NoError(t, err)
				require.Equal(t, val, result)
				done <- true
			}(int32(i))
		}
		for i := 0; i < 10; i++ {
			<-done
		}
	})

	t.Run("ConcurrentGenericAccess", func(t *testing.T) {
		done := make(chan bool, 10)
		for i := 0; i < 10; i++ {
			go func(val int64) {
				data, err := Serialize(f, &val)
				require.NoError(t, err)

				var result int64
				err = Deserialize(f, data, &result)
				require.NoError(t, err)
				require.Equal(t, val, result)
				done <- true
			}(int64(i * 1000))
		}
		for i := 0; i < 10; i++ {
			<-done
		}
	})
}

// TestSerializeAny tests the SerializeAny/DeserializeAny methods
func TestSerializeAny(t *testing.T) {
	f := New(fory.WithRefTracking(true))

	t.Run("Primitives", func(t *testing.T) {
		data, err := f.SerializeAny(int32(42))
		require.NoError(t, err)

		result, err := f.DeserializeAny(data)
		require.NoError(t, err)
		require.Equal(t, int32(42), result)
	})

	t.Run("String", func(t *testing.T) {
		data, err := f.SerializeAny("hello")
		require.NoError(t, err)

		result, err := f.DeserializeAny(data)
		require.NoError(t, err)
		require.Equal(t, "hello", result)
	})
}

// TestDeserialize tests the Deserialize generic function
func TestDeserialize(t *testing.T) {
	f := New(fory.WithRefTracking(true))

	t.Run("Int32", func(t *testing.T) {
		val := int32(42)
		data, err := Serialize(f, &val)
		require.NoError(t, err)

		var result int32
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.Equal(t, int32(42), result)
	})

	t.Run("String", func(t *testing.T) {
		val := "hello"
		data, err := Serialize(f, &val)
		require.NoError(t, err)

		var result string
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.Equal(t, "hello", result)
	})

	t.Run("Slice", func(t *testing.T) {
		original := []int32{1, 2, 3, 4, 5}
		data, err := Serialize(f, &original)
		require.NoError(t, err)

		var result []int32
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.Equal(t, original, result)
	})
}

// TestGlobalFunctions tests the global convenience functions
func TestGlobalFunctions(t *testing.T) {
	t.Run("Marshal", func(t *testing.T) {
		val := int32(42)
		data, err := Marshal(&val)
		require.NoError(t, err)

		var result int32
		err = Unmarshal(data, &result)
		require.NoError(t, err)
		require.Equal(t, int32(42), result)
	})

	t.Run("UnmarshalTo", func(t *testing.T) {
		// Use non-generic Serialize for compatibility with non-generic UnmarshalTo
		data, err := globalFory.SerializeAny("hello")
		require.NoError(t, err)

		var result string
		err = UnmarshalTo(data, &result)
		require.NoError(t, err)
		require.Equal(t, "hello", result)
	})
}
