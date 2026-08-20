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

type arrayConcreteItem struct {
	Value string
}

const compatibleArrayRefLength = 16

type compatibleSliceRefOwner struct {
	Values []any `fory:"nullable=false,ref"`
}

type compatibleArrayRefOwner struct {
	Values [compatibleArrayRefLength]any `fory:"nullable=false,ref"`
}

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

func TestArrayRejectsLengthMismatch(t *testing.T) {
	f := NewFory(WithXlang(false), WithCompatible(false))

	t.Run("concrete", func(t *testing.T) {
		bytes, err := f.Marshal([3]string{"a", "b", "c"})
		require.NoError(t, err)

		var out [2]string
		require.Error(t, f.Unmarshal(bytes, &out))
	})

	t.Run("shorter concrete", func(t *testing.T) {
		bytes, err := f.Marshal([2]string{"a", "b"})
		require.NoError(t, err)

		var out [3]string
		require.Error(t, f.Unmarshal(bytes, &out))
	})

	t.Run("dynamic", func(t *testing.T) {
		bytes, err := f.Marshal([3]any{"a", "b", "c"})
		require.NoError(t, err)

		var out [2]any
		require.Error(t, f.Unmarshal(bytes, &out))
	})
}

func TestArraySliceWireReader(t *testing.T) {
	f := NewFory(WithXlang(true), WithCompatible(false), WithTrackRef(false))
	require.NoError(t, f.RegisterStructByName(arrayConcreteItem{}, "test.ArrayConcreteItem"))

	t.Run("concrete", func(t *testing.T) {
		input := [2]arrayConcreteItem{{Value: "a"}, {Value: "b"}}
		data, err := f.Marshal(input)
		require.NoError(t, err)

		var out [2]arrayConcreteItem
		require.NoError(t, f.Unmarshal(data, &out))
		require.Equal(t, input, out)
	})

	t.Run("nullable pointers", func(t *testing.T) {
		first := &arrayConcreteItem{Value: "a"}
		third := &arrayConcreteItem{Value: "c"}
		input := [3]*arrayConcreteItem{first, nil, third}
		data, err := f.Marshal(input)
		require.NoError(t, err)

		var out [3]*arrayConcreteItem
		require.NoError(t, f.Unmarshal(data, &out))
		require.Equal(t, input, out)
	})
}

func TestArrayBodyRequiresBytes(t *testing.T) {
	f := NewFory(WithXlang(false), WithCompatible(false))
	var target [8]any
	buf := NewByteBuffer(nil)
	buf.WriteLength(len(target))
	buf.WriteInt8(CollectionDefaultFlag)
	f.readCtx.SetData(buf.Bytes())
	f.readCtx.ReadArrayValue(reflect.ValueOf(&target).Elem(), RefModeNone, false)

	err := f.readCtx.CheckError()
	require.Error(t, err)
	readErr, ok := err.(Error)
	require.True(t, ok)
	require.Equal(t, ErrKindBufferOutOfBound, readErr.Kind())
	require.Equal(t, len(target), readErr.need)
}

func TestArrayBackrefsUseSliceOwner(t *testing.T) {
	const length = 64
	writer := NewFory(WithXlang(true), WithCompatible(false), WithTrackRef(true))
	input := make([]any, length)
	for i := range input {
		input[i] = input
	}
	data, err := writer.Marshal(input)
	require.NoError(t, err)

	reader := NewFory(
		WithXlang(true),
		WithCompatible(false),
		WithTrackRef(true),
		WithMaxGraphMemoryBytes(1),
	)
	var out [length]any
	require.NoError(t, reader.Unmarshal(data, &out))
	for i := range out {
		view, ok := out[i].([]any)
		require.Truef(t, ok, "element %d has owner type %T", i, out[i])
		require.Len(t, view, length)
	}
	view := out[0].([]any)
	view[length/2] = "shared"
	require.Equal(t, "shared", out[length/2])
}

func TestCompatibleArrayBackrefs(t *testing.T) {
	writer := NewFory(WithXlang(true), WithCompatible(true), WithTrackRef(true))
	require.NoError(t, writer.RegisterStructByName(compatibleSliceRefOwner{}, "test.ArrayRefOwner"))
	input := compatibleSliceRefOwner{Values: make([]any, compatibleArrayRefLength)}
	for i := range input.Values {
		input.Values[i] = input.Values
	}
	data, err := writer.Marshal(&input)
	require.NoError(t, err)

	reader := NewFory(WithXlang(true), WithCompatible(true), WithTrackRef(true))
	require.NoError(t, reader.RegisterStructByName(compatibleArrayRefOwner{}, "test.ArrayRefOwner"))
	var out compatibleArrayRefOwner
	require.NoError(t, reader.Unmarshal(data, &out))
	for i := range out.Values {
		view, ok := out.Values[i].([]any)
		require.Truef(t, ok, "element %d has owner type %T", i, out.Values[i])
		require.Len(t, view, compatibleArrayRefLength)
	}
	view := out.Values[0].([]any)
	view[compatibleArrayRefLength/2] = "shared"
	require.Equal(t, "shared", out.Values[compatibleArrayRefLength/2])
}

func TestCompatiblePrimitiveArrayRef(t *testing.T) {
	f := NewFory(WithXlang(true), WithCompatible(true), WithTrackRef(true))
	serializer, ok := newPrimitiveListSerializer(reflect.TypeOf([]int32{}), INT32)
	require.True(t, ok)
	listSerializer := serializer.(primitiveListSerializer)

	f.writeCtx.Reset()
	listSerializer.Write(
		f.writeCtx,
		RefModeTracking,
		true,
		true,
		reflect.ValueOf([]int32{1, 2, 3}),
	)
	require.NoError(t, f.writeCtx.CheckError())
	data := append([]byte(nil), f.writeCtx.Buffer().Bytes()...)

	f.readCtx.Reset()
	f.readCtx.SetData(data)
	arraySerializer := compatiblePrimitiveListToArraySerializer{
		arrayType:  reflect.TypeOf([3]int32{}),
		listReader: listSerializer,
	}
	var out [3]int32
	arraySerializer.Read(f.readCtx, RefModeTracking, true, false, reflect.ValueOf(&out).Elem())
	require.NoError(t, f.readCtx.CheckError())
	require.Equal(t, [3]int32{1, 2, 3}, out)
	require.Empty(t, f.refResolver.readRefIds)

	owner := f.refResolver.GetReadObject(0)
	require.Equal(t, reflect.Slice, owner.Kind())
	out[0] = 9
	require.Equal(t, int32(9), owner.Index(0).Interface())
}
