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

type nullContainerNode struct {
	Value int32
}

func TestMapNullRoundTrip(t *testing.T) {
	f := New(WithXlang(true), WithCompatible(false), WithTrackRef(false))
	require.NoError(t, f.RegisterStructByName(nullContainerNode{}, "test.NullContainerNode"))

	t.Run("interface_key", func(t *testing.T) {
		input := map[any]any{nil: "value"}
		data, err := f.Serialize(input)
		require.NoError(t, err)

		var output map[any]any
		require.NoError(t, f.Deserialize(data, &output))
		require.Len(t, output, 1)
		require.Equal(t, "value", output[nil])
	})

	t.Run("null_entry", func(t *testing.T) {
		input := map[any]any{nil: nil}
		data, err := f.Serialize(input)
		require.NoError(t, err)

		var output map[any]any
		require.NoError(t, f.Deserialize(data, &output))
		require.Len(t, output, 1)
		value, ok := output[nil]
		require.True(t, ok)
		require.Nil(t, value)
	})

	t.Run("pointer_value", func(t *testing.T) {
		input := map[string]*nullContainerNode{"key": nil}
		data, err := f.Serialize(input)
		require.NoError(t, err)

		var output map[string]*nullContainerNode
		require.NoError(t, f.Deserialize(data, &output))
		require.Contains(t, output, "key")
		require.Nil(t, output["key"])
	})

	t.Run("pointer_key", func(t *testing.T) {
		input := map[*nullContainerNode]string{nil: "value"}
		data, err := f.Serialize(input)
		require.NoError(t, err)

		var output map[*nullContainerNode]string
		require.NoError(t, f.Deserialize(data, &output))
		require.Len(t, output, 1)
		require.Equal(t, "value", output[nil])
	})
}

func TestTrackedMapNullRoundTrip(t *testing.T) {
	f := New(WithXlang(true), WithCompatible(false), WithTrackRef(true))
	require.NoError(t, f.RegisterStructByName(nullContainerNode{}, "test.TrackedNullContainerNode"))

	t.Run("new_key", func(t *testing.T) {
		input := map[any]any{&nullContainerNode{Value: 1}: nil}
		data, err := f.Serialize(input)
		require.NoError(t, err)

		var output map[any]any
		require.NoError(t, f.Deserialize(data, &output))
		require.Len(t, output, 1)
		for key, value := range output {
			node, ok := key.(*nullContainerNode)
			require.True(t, ok)
			require.Equal(t, int32(1), node.Value)
			require.Nil(t, value)
		}
	})

	t.Run("new_value", func(t *testing.T) {
		input := map[any]any{nil: &nullContainerNode{Value: 2}}
		data, err := f.Serialize(input)
		require.NoError(t, err)

		var output map[any]any
		require.NoError(t, f.Deserialize(data, &output))
		require.Len(t, output, 1)
		node, ok := output[nil].(*nullContainerNode)
		require.True(t, ok)
		require.Equal(t, int32(2), node.Value)
	})

	t.Run("key_backref", func(t *testing.T) {
		node := &nullContainerNode{Value: 3}
		input := []any{node, map[any]any{node: nil}, "tail"}
		data, err := f.Serialize(input)
		require.NoError(t, err)

		var output []any
		require.NoError(t, f.Deserialize(data, &output))
		require.Len(t, output, 3)
		first, ok := output[0].(*nullContainerNode)
		require.True(t, ok)
		values, ok := output[1].(map[any]any)
		require.True(t, ok)
		require.Len(t, values, 1)
		for key, value := range values {
			require.Same(t, first, key)
			require.Nil(t, value)
		}
		require.Equal(t, "tail", output[2])
	})

	t.Run("value_backref", func(t *testing.T) {
		node := &nullContainerNode{Value: 4}
		input := []any{node, map[any]any{nil: node}, "tail"}
		data, err := f.Serialize(input)
		require.NoError(t, err)

		var output []any
		require.NoError(t, f.Deserialize(data, &output))
		require.Len(t, output, 3)
		first, ok := output[0].(*nullContainerNode)
		require.True(t, ok)
		values, ok := output[1].(map[any]any)
		require.True(t, ok)
		require.Len(t, values, 1)
		require.Same(t, first, values[nil])
		require.Equal(t, "tail", output[2])
	})
}

func TestSetNullRoundTrip(t *testing.T) {
	for _, trackRef := range []bool{false, true} {
		t.Run("track_ref_"+map[bool]string{false: "off", true: "on"}[trackRef], func(t *testing.T) {
			f := New(WithXlang(true), WithCompatible(false), WithTrackRef(trackRef))
			require.NoError(t, f.RegisterStructByName(nullContainerNode{}, "test.NullSetNode"))

			t.Run("interface", func(t *testing.T) {
				input := NewSet[any]()
				input.Add(nil)
				data, err := f.Serialize(input)
				require.NoError(t, err)

				var output Set[any]
				require.NoError(t, f.Deserialize(data, &output))
				require.True(t, output.Contains(nil))
			})

			t.Run("mixed", func(t *testing.T) {
				input := NewSet[any]()
				input.Add(nil, "value")
				data, err := f.Serialize(input)
				require.NoError(t, err)

				var output Set[any]
				require.NoError(t, f.Deserialize(data, &output))
				require.Len(t, output, 2)
				require.True(t, output.Contains(nil))
				require.True(t, output.Contains("value"))
			})

			t.Run("pointer", func(t *testing.T) {
				input := NewSet[*nullContainerNode]()
				input.Add(nil)
				data, err := f.Serialize(input)
				require.NoError(t, err)

				var output Set[*nullContainerNode]
				require.NoError(t, f.Deserialize(data, &output))
				require.True(t, output.Contains(nil))
			})
		})
	}
}

func TestNullCarrierRejects(t *testing.T) {
	for _, trackRef := range []bool{false, true} {
		t.Run("track_ref_"+map[bool]string{false: "off", true: "on"}[trackRef], func(t *testing.T) {
			f := New(WithXlang(true), WithCompatible(false), WithTrackRef(trackRef))

			t.Run("map_key", func(t *testing.T) {
				data, err := f.Serialize(map[any]any{nil: "value"})
				require.NoError(t, err)

				var output map[string]any
				require.Error(t, f.Deserialize(data, &output))
			})

			t.Run("map_value", func(t *testing.T) {
				data, err := f.Serialize(map[any]any{"key": nil})
				require.NoError(t, err)

				var output map[any]string
				require.Error(t, f.Deserialize(data, &output))
			})

			t.Run("set", func(t *testing.T) {
				input := NewSet[any]()
				input.Add(nil)
				data, err := f.Serialize(input)
				require.NoError(t, err)

				var output Set[string]
				require.Error(t, f.Deserialize(data, &output))
			})
		})
	}
}
