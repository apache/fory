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
	"bytes"
	"io"
	"reflect"
	"testing"

	"github.com/apache/fory/go/fory/bfloat16"
	"github.com/apache/fory/go/fory/float16"
	"github.com/stretchr/testify/require"
)

type hardeningWireA struct {
	Value int32
}

type hardeningWireB struct {
	Value string
}

type hardeningWireSource struct {
	Values []hardeningWireB
}

type hardeningWireTarget struct {
	Values []hardeningWireA
}

type hardeningNarrow interface {
	hardeningMarker()
}

type hardeningMeta struct {
	Value int32
}

type hardeningExtension struct {
	Value int32
}

type hardeningExtensionSerializer struct{}

func (hardeningExtensionSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	ctx.Buffer().WriteInt32(int32(value.FieldByName("Value").Int()))
}

func (hardeningExtensionSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	value.FieldByName("Value").SetInt(int64(ctx.Buffer().ReadInt32(ctx.Err())))
}

type hardeningDepthNode struct {
	Children []*hardeningDepthNode
}

type hardeningConcreteMap struct {
	Values map[string]string
}

type hardeningStaticKeyMap struct {
	Values map[string]any
}

type hardeningStaticValueMap struct {
	Values map[any]string
}

type hardeningDynamicMap struct {
	Values map[any]any
}

type hardeningConcreteLists struct {
	Values   []string
	Nullable []*string
	Fixed    [2]string
}

type hardeningDynamicLists struct {
	Values   []any
	Nullable []any
	Fixed    [2]any
}

type hardeningConcreteSet struct {
	Values Set[string]
}

type hardeningDynamicSet struct {
	Values Set[any]
}

type hardeningConcreteScalars struct {
	Value   int32
	Present *int32
	Missing *int32
}

type hardeningDynamicScalars struct {
	Value   any
	Present any
	Missing any
}

type hardeningStructChild struct {
	Value int32
}

type hardeningConcreteStructs struct {
	Value   hardeningStructChild
	Present *hardeningStructChild
	Missing *hardeningStructChild
}

type hardeningDynamicStructs struct {
	Value   any
	Present any
	Missing any
}

type hardeningTrackedStructs struct {
	First  *hardeningStructChild
	Second *hardeningStructChild
}

type hardeningDynamicTrackedStructs struct {
	First  any
	Second any
}

type emptyReadThenData struct {
	empty int
	data  []byte
	calls int
}

func (r *emptyReadThenData) Read(p []byte) (int, error) {
	r.calls++
	if r.empty > 0 {
		r.empty--
		return 0, nil
	}
	if len(r.data) == 0 {
		return 0, io.EOF
	}
	n := copy(p, r.data)
	r.data = r.data[n:]
	return n, nil
}

func TestConcreteWireTypeMismatch(t *testing.T) {
	writer := New(WithXlang(false), WithCompatible(true))
	require.NoError(t, writer.RegisterStructByName(hardeningWireB{}, "test.HardeningWireB"))
	require.NoError(t, writer.RegisterStructByName(hardeningWireSource{}, "test.HardeningWireHolder"))
	data, err := writer.Serialize(&hardeningWireSource{
		Values: []hardeningWireB{{Value: "wrong storage"}},
	})
	require.NoError(t, err)

	reader := New(WithXlang(false), WithCompatible(true))
	require.NoError(t, reader.RegisterStructByName(hardeningWireA{}, "test.HardeningWireA"))
	require.NoError(t, reader.RegisterStructByName(hardeningWireB{}, "test.HardeningWireB"))
	require.NoError(t, reader.RegisterStructByName(hardeningWireTarget{}, "test.HardeningWireHolder"))

	var target hardeningWireTarget
	require.NotPanics(t, func() {
		err = reader.Deserialize(data, &target)
	})
	require.Error(t, err)
	require.Contains(t, err.Error(), "does not match declared type")
	require.Empty(t, target.Values)
}

func TestReferenceInputValidation(t *testing.T) {
	resolver := newRefResolver(true)
	require.Error(t, resolver.SetReadObject(0, reflect.ValueOf("out of bounds")))

	f := New(WithTrackRef(true))
	refID, err := f.refResolver.PreserveRefId()
	require.NoError(t, err)
	require.NoError(t, f.refResolver.SetReadObject(refID, reflect.ValueOf("string")))
	var target int32
	require.False(t, assignReadRef(f.readCtx, refID, reflect.ValueOf(&target).Elem()))
	require.Error(t, f.readCtx.CheckError())

	f.readCtx.Reset()
	target = 7
	require.True(t, assignReadRef(f.readCtx, int32(NullFlag), reflect.ValueOf(&target).Elem()))
	require.NoError(t, f.readCtx.CheckError())
	require.Equal(t, int32(7), target)

	f = New(WithTrackRef(true))
	mapType := reflect.TypeOf(map[int32]int32{})
	serializer, err := f.typeResolver.getSerializerByType(mapType, false)
	require.NoError(t, err)
	buf := NewByteBuffer(nil)
	buf.WriteLength(1)
	buf.WriteUint8(KEY_HAS_NULL)
	buf.WriteByte(0)
	f.readCtx.SetData(buf.Bytes())
	f.readCtx.remainingGraphMemoryBytes = f.config.MaxGraphMemoryBytes
	serializer.ReadData(f.readCtx, reflect.New(mapType).Elem())
	readErr := f.readCtx.CheckError()
	require.Error(t, readErr)
}

func TestForgedMapDeclaredFlags(t *testing.T) {
	tests := []struct {
		name  string
		write func(*ByteBuffer)
	}{
		{
			name: "declared_key",
			write: func(buf *ByteBuffer) {
				buf.WriteUint8(KEY_DECL_TYPE | VALUE_DECL_TYPE)
				buf.WriteUint8(1)
			},
		},
		{
			name: "declared_value",
			write: func(buf *ByteBuffer) {
				buf.WriteUint8(VALUE_DECL_TYPE)
				buf.WriteUint8(1)
				buf.WriteUint8(uint8(STRING))
			},
		},
		{
			name: "null_entry",
			write: func(buf *ByteBuffer) {
				buf.WriteUint8(VALUE_HAS_NULL | KEY_DECL_TYPE)
			},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			f := New(WithXlang(true), WithCompatible(false))
			buf := NewByteBuffer(nil)
			buf.WriteByte(XLangFlag)
			buf.WriteInt8(RefValueFlag)
			buf.WriteUint8(uint8(MAP))
			buf.WriteVarUint32(1)
			test.write(buf)
			buf.WriteByte(0)

			var target map[any]any
			var err error
			require.NotPanics(t, func() {
				err = f.Deserialize(buf.Bytes(), &target)
			})
			require.Error(t, err)

			nextData, err := f.Serialize(int32(7))
			require.NoError(t, err)
			var next int32
			require.NoError(t, f.Deserialize(nextData, &next))
			require.Equal(t, int32(7), next)
		})
	}
}

func TestCompatibleDeclaredMap(t *testing.T) {
	writer := New(WithXlang(true), WithCompatible(true))
	require.NoError(t, writer.RegisterStructByName(
		hardeningConcreteMap{}, "test.HardeningMap"))
	compatibleData, err := writer.Serialize(&hardeningConcreteMap{
		Values: map[string]string{"key": "value"},
	})
	require.NoError(t, err)
	compatibleData = bytes.Clone(compatibleData)
	nextData, err := writer.Serialize(&hardeningConcreteMap{
		Values: map[string]string{},
	})
	require.NoError(t, err)

	reader := New(WithXlang(true), WithCompatible(true))
	require.NoError(t, reader.RegisterStructByName(
		hardeningDynamicMap{}, "test.HardeningMap"))

	var target hardeningDynamicMap
	require.NotPanics(t, func() {
		err = reader.Deserialize(compatibleData, &target)
	})
	require.NoError(t, err)
	require.Len(t, target.Values, 1)
	value, ok := target.Values["key"]
	require.True(t, ok)
	require.IsType(t, "", value)
	require.Equal(t, "value", value)

	target = hardeningDynamicMap{}
	require.NoError(t, reader.Deserialize(nextData, &target))
	require.NotNil(t, target.Values)
	require.Empty(t, target.Values)
}

func TestCompatibleDeclaredMapKey(t *testing.T) {
	writer := New(WithXlang(true), WithCompatible(true))
	require.NoError(t, writer.RegisterStructByName(
		hardeningStaticKeyMap{}, "test.HardeningStaticKeyMap"))
	compatibleData, err := writer.Serialize(&hardeningStaticKeyMap{
		Values: map[string]any{
			"value": int32(3),
			"nil":   nil,
		},
	})
	require.NoError(t, err)
	compatibleData = bytes.Clone(compatibleData)
	nextData, err := writer.Serialize(int32(7))
	require.NoError(t, err)

	reader := New(WithXlang(true), WithCompatible(true))
	require.NoError(t, reader.RegisterStructByName(
		hardeningDynamicMap{}, "test.HardeningStaticKeyMap"))

	var target hardeningDynamicMap
	require.NoError(t, reader.Deserialize(compatibleData, &target))
	require.Len(t, target.Values, 2)
	require.Equal(t, int32(3), target.Values["value"])
	nilValue, ok := target.Values["nil"]
	require.True(t, ok)
	require.Nil(t, nilValue)

	var next int32
	require.NoError(t, reader.Deserialize(nextData, &next))
	require.Equal(t, int32(7), next)
}

func TestCompatibleDeclaredMapValue(t *testing.T) {
	writer := New(WithXlang(true), WithCompatible(true))
	require.NoError(t, writer.RegisterStructByName(
		hardeningStaticValueMap{}, "test.HardeningStaticValueMap"))
	compatibleData, err := writer.Serialize(&hardeningStaticValueMap{
		Values: map[any]string{int32(3): "value"},
	})
	require.NoError(t, err)
	compatibleData = bytes.Clone(compatibleData)
	nextData, err := writer.Serialize(int32(7))
	require.NoError(t, err)

	reader := New(WithXlang(true), WithCompatible(true))
	require.NoError(t, reader.RegisterStructByName(
		hardeningDynamicMap{}, "test.HardeningStaticValueMap"))

	var target hardeningDynamicMap
	require.NoError(t, reader.Deserialize(compatibleData, &target))
	require.Len(t, target.Values, 1)
	require.Equal(t, "value", target.Values[int32(3)])

	var next int32
	require.NoError(t, reader.Deserialize(nextData, &next))
	require.Equal(t, int32(7), next)
}

func TestCompatibleInterfaceList(t *testing.T) {
	listValue := "present"
	writer := New(WithXlang(true), WithCompatible(true))
	require.NoError(t, writer.RegisterStructByName(
		hardeningConcreteLists{}, "test.HardeningInterfaceList"))
	compatibleData, err := writer.Serialize(&hardeningConcreteLists{
		Values:   []string{"one", "two"},
		Nullable: []*string{&listValue, nil},
		Fixed:    [2]string{"fixed-one", "fixed-two"},
	})
	require.NoError(t, err)
	compatibleData = bytes.Clone(compatibleData)
	nextData, err := writer.Serialize(int32(7))
	require.NoError(t, err)

	reader := New(WithXlang(true), WithCompatible(true))
	require.NoError(t, reader.RegisterStructByName(
		hardeningDynamicLists{}, "test.HardeningInterfaceList"))

	var target hardeningDynamicLists
	require.NoError(t, reader.Deserialize(compatibleData, &target))
	require.Equal(t, []any{"one", "two"}, target.Values)
	require.Len(t, target.Nullable, 2)
	require.Equal(t, "present", target.Nullable[0])
	require.Nil(t, target.Nullable[1])
	require.Equal(t, [2]any{"fixed-one", "fixed-two"}, target.Fixed)

	var next int32
	require.NoError(t, reader.Deserialize(nextData, &next))
	require.Equal(t, int32(7), next)
}

func TestCompatibleInterfaceSet(t *testing.T) {
	writer := New(WithXlang(true), WithCompatible(true))
	require.NoError(t, writer.RegisterStructByName(
		hardeningConcreteSet{}, "test.HardeningInterfaceSet"))
	compatibleData, err := writer.Serialize(&hardeningConcreteSet{
		Values: Set[string]{"one": {}, "two": {}},
	})
	require.NoError(t, err)
	compatibleData = bytes.Clone(compatibleData)
	nextData, err := writer.Serialize(int32(7))
	require.NoError(t, err)

	reader := New(WithXlang(true), WithCompatible(true))
	require.NoError(t, reader.RegisterStructByName(
		hardeningDynamicSet{}, "test.HardeningInterfaceSet"))

	var target hardeningDynamicSet
	require.NoError(t, reader.Deserialize(compatibleData, &target))
	require.Len(t, target.Values, 2)
	require.Contains(t, target.Values, "one")
	require.Contains(t, target.Values, "two")

	var next int32
	require.NoError(t, reader.Deserialize(nextData, &next))
	require.Equal(t, int32(7), next)
}

func TestCompatibleInterfaceScalar(t *testing.T) {
	present := int32(3)
	writer := New(WithXlang(true), WithCompatible(true))
	require.NoError(t, writer.RegisterStructByName(
		hardeningConcreteScalars{}, "test.HardeningInterfaceScalar"))
	compatibleData, err := writer.Serialize(&hardeningConcreteScalars{
		Value:   2,
		Present: &present,
	})
	require.NoError(t, err)
	compatibleData = bytes.Clone(compatibleData)
	nextData, err := writer.Serialize(int32(7))
	require.NoError(t, err)

	reader := New(WithXlang(true), WithCompatible(true))
	require.NoError(t, reader.RegisterStructByName(
		hardeningDynamicScalars{}, "test.HardeningInterfaceScalar"))

	var target hardeningDynamicScalars
	require.NoError(t, reader.Deserialize(compatibleData, &target))
	require.Equal(t, int32(2), target.Value)
	require.Equal(t, int32(3), target.Present)
	require.Nil(t, target.Missing)

	var next int32
	require.NoError(t, reader.Deserialize(nextData, &next))
	require.Equal(t, int32(7), next)
}

func TestInterfaceScalarSerializer(t *testing.T) {
	serializer := interfaceScalarSerializer{
		type_:      int32Type,
		serializer: encodedInt32Serializer{typeID: VARINT32},
	}
	writeCtx := NewWriteContext(false, 1)
	var value any = int32(3)
	serializer.Write(writeCtx, RefModeNullOnly, false, false, reflect.ValueOf(&value).Elem())
	require.NoError(t, writeCtx.CheckError())

	readCtx := NewReadContext(false)
	readCtx.SetData(bytes.Clone(writeCtx.Buffer().Bytes()))
	var target any
	serializer.Read(readCtx, RefModeNullOnly, false, false, reflect.ValueOf(&target).Elem())
	require.NoError(t, readCtx.CheckError())
	require.Equal(t, int32(3), target)

	writeCtx.Reset()
	var nilValue any
	serializer.Write(writeCtx, RefModeNullOnly, false, false, reflect.ValueOf(&nilValue).Elem())
	require.NoError(t, writeCtx.CheckError())
	readCtx = NewReadContext(false)
	readCtx.SetData(bytes.Clone(writeCtx.Buffer().Bytes()))
	target = int32(9)
	serializer.Read(readCtx, RefModeNullOnly, false, false, reflect.ValueOf(&target).Elem())
	require.NoError(t, readCtx.CheckError())
	require.Nil(t, target)

	writeCtx.Reset()
	var mismatch any = "value"
	serializer.Write(writeCtx, RefModeNone, false, false, reflect.ValueOf(&mismatch).Elem())
	require.Error(t, writeCtx.CheckError())
}

func TestCompatibleInterfaceStruct(t *testing.T) {
	t.Run("value and nullable", func(t *testing.T) {
		writer := New(WithXlang(true), WithCompatible(true))
		require.NoError(t, writer.RegisterStructByName(
			hardeningStructChild{}, "test.HardeningInterfaceStructChild"))
		require.NoError(t, writer.RegisterStructByName(
			hardeningConcreteStructs{}, "test.HardeningInterfaceStructs"))
		compatibleData, err := writer.Serialize(&hardeningConcreteStructs{
			Value:   hardeningStructChild{Value: 2},
			Present: &hardeningStructChild{Value: 3},
		})
		require.NoError(t, err)
		compatibleData = bytes.Clone(compatibleData)
		nextData, err := writer.Serialize(int32(7))
		require.NoError(t, err)

		reader := New(WithXlang(true), WithCompatible(true))
		require.NoError(t, reader.RegisterStructByName(
			hardeningStructChild{}, "test.HardeningInterfaceStructChild"))
		require.NoError(t, reader.RegisterStructByName(
			hardeningDynamicStructs{}, "test.HardeningInterfaceStructs"))

		var target hardeningDynamicStructs
		require.NoError(t, reader.Deserialize(compatibleData, &target))
		require.Equal(t, &hardeningStructChild{Value: 2}, target.Value)
		require.Equal(t, &hardeningStructChild{Value: 3}, target.Present)
		require.Nil(t, target.Missing)

		var next int32
		require.NoError(t, reader.Deserialize(nextData, &next))
		require.Equal(t, int32(7), next)
	})

	t.Run("tracking", func(t *testing.T) {
		child := &hardeningStructChild{Value: 4}
		writer := New(WithXlang(true), WithCompatible(true), WithTrackRef(true))
		require.NoError(t, writer.RegisterStructByName(
			hardeningStructChild{}, "test.HardeningTrackedStructChild"))
		require.NoError(t, writer.RegisterStructByName(
			hardeningTrackedStructs{}, "test.HardeningTrackedStructs"))
		compatibleData, err := writer.Serialize(&hardeningTrackedStructs{
			First:  child,
			Second: child,
		})
		require.NoError(t, err)
		compatibleData = bytes.Clone(compatibleData)
		nextData, err := writer.Serialize(int32(7))
		require.NoError(t, err)

		reader := New(WithXlang(true), WithCompatible(true), WithTrackRef(true))
		require.NoError(t, reader.RegisterStructByName(
			hardeningStructChild{}, "test.HardeningTrackedStructChild"))
		require.NoError(t, reader.RegisterStructByName(
			hardeningDynamicTrackedStructs{}, "test.HardeningTrackedStructs"))

		var target hardeningDynamicTrackedStructs
		require.NoError(t, reader.Deserialize(compatibleData, &target))
		require.IsType(t, &hardeningStructChild{}, target.First)
		require.Same(t, target.First, target.Second)

		var next int32
		require.NoError(t, reader.Deserialize(nextData, &next))
		require.Equal(t, int32(7), next)
	})
}

func TestPrimitiveSliceOuterRefs(t *testing.T) {
	primitiveList, ok := newPrimitiveListSerializer(reflect.TypeOf([]int32{}), INT32)
	require.True(t, ok)
	tests := []struct {
		name       string
		serializer Serializer
		value      any
	}{
		{"binary", byteSliceSerializer{}, []byte{1}},
		{"bool", boolSliceSerializer{}, []bool{true}},
		{"int8", int8SliceSerializer{}, []int8{1}},
		{"int16", int16SliceSerializer{}, []int16{1}},
		{"int32", int32SliceSerializer{}, []int32{1}},
		{"int64", int64SliceSerializer{}, []int64{1}},
		{"uint16", uint16SliceSerializer{}, []uint16{1}},
		{"uint32", uint32SliceSerializer{}, []uint32{1}},
		{"uint64", uint64SliceSerializer{}, []uint64{1}},
		{"float32", float32SliceSerializer{}, []float32{1}},
		{"float64", float64SliceSerializer{}, []float64{1}},
		{"int", intSliceSerializer{}, []int{1}},
		{"uint", uintSliceSerializer{}, []uint{1}},
		{"string", stringSliceSerializer{}, []string{"value"}},
		{"float16", float16SliceSerializer{}, []float16.Float16{float16.One}},
		{"bfloat16", bfloat16SliceSerializer{}, []bfloat16.BFloat16{bfloat16.BFloat16FromFloat32(1)}},
		{"primitive_list", primitiveList, []int32{1}},
		{"encoded_binary", encodedByteSliceSerializer{typeID: BINARY}, []byte{1}},
	}

	for _, test := range tests {
		for _, length := range []int{0, 1} {
			t.Run(test.name+"_"+string(rune('0'+length)), func(t *testing.T) {
				f := New(WithTrackRef(true), WithCompatible(false))
				value := reflect.ValueOf(test.value)
				if length == 0 {
					value = reflect.MakeSlice(value.Type(), 0, 1)
				}

				test.serializer.Write(f.writeCtx, RefModeTracking, false, true, value)
				test.serializer.Write(f.writeCtx, RefModeTracking, false, true, value)
				require.NoError(t, f.writeCtx.CheckError())
				data := bytes.Clone(f.writeCtx.Buffer().Bytes())

				f.readCtx.Reset()
				f.readCtx.SetData(data)
				f.readCtx.remainingGraphMemoryBytes = f.config.MaxGraphMemoryBytes
				first := reflect.New(value.Type()).Elem()
				second := reflect.New(value.Type()).Elem()

				test.serializer.Read(f.readCtx, RefModeTracking, false, true, first)
				require.NoError(t, f.readCtx.CheckError())
				require.Len(t, f.refResolver.readObjects, 1)
				require.Empty(t, f.refResolver.readRefIds)

				test.serializer.Read(f.readCtx, RefModeTracking, false, true, second)
				require.NoError(t, f.readCtx.CheckError())
				require.Empty(t, f.refResolver.readRefIds)
				require.Equal(t, first.Interface(), second.Interface())
				if length != 0 {
					require.Equal(t, first.Pointer(), second.Pointer())
				}
			})
		}
	}
}

func TestDynamicWireTypeValidation(t *testing.T) {
	t.Run("unknown", func(t *testing.T) {
		f := New(WithTrackRef(true))
		buf := NewByteBuffer(nil)
		buf.WriteInt8(RefValueFlag)
		buf.WriteUint8(uint8(UNKNOWN))
		f.readCtx.SetData(buf.Bytes())
		var target any
		f.readCtx.ReadValue(reflect.ValueOf(&target).Elem(), RefModeTracking, true)
		err := f.readCtx.CheckError()
		require.Error(t, err)
		require.Contains(t, err.Error(), "no deserializer")
		require.Nil(t, target)
	})

	t.Run("unassignable", func(t *testing.T) {
		f := New(WithTrackRef(true))
		buf := NewByteBuffer(nil)
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteUint8(uint8(STRING))
		f.readCtx.SetData(buf.Bytes())
		var target hardeningNarrow
		f.readCtx.ReadValue(reflect.ValueOf(&target).Elem(), RefModeTracking, true)
		err := f.readCtx.CheckError()
		require.Error(t, err)
		require.Contains(t, err.Error(), "not assignable")
		require.Nil(t, target)
	})
}

func TestGenericReadStateCleanup(t *testing.T) {
	f := New(WithXlang(false), WithCompatible(true), WithTrackRef(true))
	require.NoError(t, f.RegisterStructByName(hardeningMeta{}, "test.HardeningMeta"))
	data, err := Serialize(f, &hardeningMeta{Value: 7})
	require.NoError(t, err)

	var target hardeningMeta
	require.NoError(t, Deserialize(f, bytes.Clone(data), &target))
	require.Equal(t, int32(7), target.Value)
	require.Empty(t, f.metaContext.readTypeInfos)
	require.Empty(t, f.refResolver.readObjects)
	require.Zero(t, f.readCtx.depth)

	f.metaContext.readTypeInfos = append(f.metaContext.readTypeInfos, &TypeInfo{})
	_, err = f.refResolver.PreserveRefId()
	require.NoError(t, err)
	f.readCtx.depth = 3
	err = Deserialize(f, nil, &target)
	require.Error(t, err)
	require.Empty(t, f.metaContext.readTypeInfos)
	require.Empty(t, f.refResolver.readObjects)
	require.Zero(t, f.readCtx.depth)

	f.metaContext.readTypeInfos = append(f.metaContext.readTypeInfos, &TypeInfo{})
	f.Reset()
	require.Empty(t, f.metaContext.readTypeInfos)
}

func TestExtensionSkipUsesConcreteValue(t *testing.T) {
	f := New(WithCompatible(false))
	buf := NewByteBuffer(nil)
	buf.WriteInt32(7)
	f.readCtx.SetData(buf.Bytes())
	adapter := &extensionSerializerAdapter{
		type_:      reflect.TypeOf(hardeningExtension{}),
		userSerial: hardeningExtensionSerializer{},
	}
	typeInfo := &TypeInfo{
		Type:       reflect.TypeOf(hardeningExtension{}),
		TypeID:     uint32(EXT),
		Serializer: adapter,
	}

	require.NotPanics(t, func() {
		skipValue(
			f.readCtx,
			FieldDef{typeSpec: NewSimpleTypeSpec(EXT)},
			false,
			false,
			typeInfo,
		)
	})
	require.NoError(t, f.readCtx.CheckError())
	require.Equal(t, buf.WriterIndex(), f.readCtx.Buffer().ReaderIndex())
}

func TestStreamDiscardNoProgress(t *testing.T) {
	stuck := &emptyReadThenData{empty: 101}
	buf := NewByteBufferFromReader(stuck, 1)
	var err Error
	require.False(t, buf.discardFromReader(1, &err))
	require.Error(t, err.CheckError())
	require.Contains(t, err.Error(), io.ErrNoProgress.Error())
	require.Equal(t, 100, stuck.calls)

	transient := &emptyReadThenData{empty: 3, data: []byte{1}}
	buf = NewByteBufferFromReader(transient, 1)
	err = Error{}
	require.True(t, buf.discardFromReader(1, &err))
	require.NoError(t, err.CheckError())
	require.Equal(t, 4, transient.calls)
}

func TestReadDepthOwners(t *testing.T) {
	writer := New(WithCompatible(false))
	require.NoError(t, writer.RegisterStructByName(hardeningDepthNode{}, "test.HardeningDepthNode"))
	deepData, err := writer.Serialize(&hardeningDepthNode{
		Children: []*hardeningDepthNode{{}},
	})
	require.NoError(t, err)
	deepData = bytes.Clone(deepData)
	shallowData, err := writer.Serialize(&hardeningDepthNode{})
	require.NoError(t, err)

	reader := New(WithCompatible(false), WithMaxDepth(2))
	require.NoError(t, reader.RegisterStructByName(hardeningDepthNode{}, "test.HardeningDepthNode"))
	var target hardeningDepthNode
	err = reader.Deserialize(deepData, &target)
	require.Error(t, err)
	require.Contains(t, err.Error(), "depth=3")
	require.Zero(t, reader.readCtx.depth)
	require.NoError(t, reader.Deserialize(shallowData, &target))
	require.Zero(t, reader.readCtx.depth)

	reader = New(WithCompatible(false), WithMaxDepth(3))
	require.NoError(t, reader.RegisterStructByName(hardeningDepthNode{}, "test.HardeningDepthNode"))
	err = reader.Deserialize(deepData, &target)
	require.Error(t, err)
	require.Contains(t, err.Error(), "depth=4")

	reader = New(WithCompatible(false), WithMaxDepth(4))
	require.NoError(t, reader.RegisterStructByName(hardeningDepthNode{}, "test.HardeningDepthNode"))
	require.NoError(t, reader.Deserialize(deepData, &target))
	require.Len(t, target.Children, 1)
}

func TestReadDepthRootCleanup(t *testing.T) {
	writer := New(WithCompatible(false))
	require.NoError(t, writer.RegisterStructByName(hardeningDepthNode{}, "test.HardeningDepthNode"))
	deepData, err := writer.Serialize(&hardeningDepthNode{
		Children: []*hardeningDepthNode{{}},
	})
	require.NoError(t, err)
	deepData = bytes.Clone(deepData)
	shallowData, err := writer.Serialize(&hardeningDepthNode{})
	require.NoError(t, err)

	reader := New(WithCompatible(false), WithMaxDepth(2))
	require.NoError(t, reader.RegisterStructByName(hardeningDepthNode{}, "test.HardeningDepthNode"))
	reader.readCtx.SetData(deepData)
	reader.readCtx.remainingGraphMemoryBytes = reader.config.MaxGraphMemoryBytes
	readHeader(reader.readCtx)
	require.NoError(t, reader.readCtx.CheckError())

	var target hardeningDepthNode
	reader.readCtx.ReadValue(reflect.ValueOf(&target).Elem(), RefModeTracking, true)
	err = reader.readCtx.CheckError()
	require.Error(t, err)
	require.Contains(t, err.Error(), "depth=3")
	// The struct and list owners remain active after the nested struct is
	// rejected. Only root cleanup owns exceptional depth unwinding.
	require.Equal(t, 2, reader.readCtx.depth)

	reader.resetReadState()
	require.Zero(t, reader.readCtx.depth)
	require.NoError(t, reader.Deserialize(shallowData, &target))

	reader = New(WithCompatible(false), WithMaxDepth(4))
	require.NoError(t, reader.RegisterStructByName(hardeningDepthNode{}, "test.HardeningDepthNode"))
	reader.readCtx.SetData(deepData)
	reader.readCtx.remainingGraphMemoryBytes = reader.config.MaxGraphMemoryBytes
	readHeader(reader.readCtx)
	require.NoError(t, reader.readCtx.CheckError())
	reader.readCtx.ReadValue(reflect.ValueOf(&target).Elem(), RefModeTracking, true)
	require.NoError(t, reader.readCtx.CheckError())
	require.Zero(t, reader.readCtx.depth)
}

func TestDepthOwnerEntrances(t *testing.T) {
	materializers := []struct {
		name string
		read func(*ReadContext)
	}{
		{"struct", func(ctx *ReadContext) {
			(&structSerializer{}).ReadData(ctx, reflect.Value{})
		}},
		{"skip_struct_serializer", func(ctx *ReadContext) {
			(&skipStructSerializer{}).ReadData(ctx, reflect.Value{})
		}},
		{"slice", func(ctx *ReadContext) {
			(&sliceSerializer{}).ReadData(ctx, reflect.ValueOf(&[]int32{}).Elem())
		}},
		{"dynamic_slice", func(ctx *ReadContext) {
			(&sliceDynSerializer{}).ReadData(ctx, reflect.ValueOf(&[]any{}).Elem())
		}},
		{"array", func(ctx *ReadContext) {
			(&arrayConcreteValueSerializer{}).ReadData(ctx, reflect.ValueOf(&[0]int32{}).Elem())
		}},
		{"map", func(ctx *ReadContext) {
			(mapSerializer{}).ReadData(ctx, reflect.ValueOf(&map[int32]int32{}).Elem())
		}},
		{"set", func(ctx *ReadContext) {
			(setSerializer{}).ReadData(ctx, reflect.ValueOf(&Set[int32]{}).Elem())
		}},
		{"union", func(ctx *ReadContext) {
			(&UnionSerializer{}).ReadData(ctx, reflect.Value{})
		}},
		{"extension", func(ctx *ReadContext) {
			(&extensionSerializerAdapter{}).ReadData(ctx, reflect.Value{})
		}},
	}
	for _, test := range materializers {
		t.Run(test.name, func(t *testing.T) {
			ctx := NewReadContext(false)
			ctx.maxDepth = 0
			require.NotPanics(t, func() { test.read(ctx) })
			err := ctx.CheckError()
			require.Error(t, err)
			require.Contains(t, err.Error(), "depth=1")
			require.Zero(t, ctx.depth)
		})
	}

	skips := []struct {
		name string
		skip func(*ReadContext)
	}{
		{"collection", func(ctx *ReadContext) {
			skipCollection(ctx, FieldDef{
				typeSpec: NewCollectionTypeSpec(LIST, NewSimpleTypeSpec(INT32)),
			})
		}},
		{"map", func(ctx *ReadContext) {
			skipMap(ctx, FieldDef{
				typeSpec: NewMapTypeSpec(
					MAP,
					NewSimpleTypeSpec(INT32),
					NewSimpleTypeSpec(INT32),
				),
			})
		}},
		{"struct", func(ctx *ReadContext) {
			skipStruct(ctx, nil)
		}},
		{"union", func(ctx *ReadContext) {
			skipValue(
				ctx,
				FieldDef{typeSpec: NewSimpleTypeSpec(UNION)},
				false,
				false,
				nil,
			)
		}},
	}
	for _, test := range skips {
		t.Run("skip_"+test.name, func(t *testing.T) {
			ctx := NewReadContext(false)
			ctx.maxDepth = 0
			require.NotPanics(t, func() { test.skip(ctx) })
			err := ctx.CheckError()
			require.Error(t, err)
			require.Contains(t, err.Error(), "depth=1")
			require.Zero(t, ctx.depth)
		})
	}
}

func TestRemoteTypeKeyLimit(t *testing.T) {
	f := New(WithXlang(false), WithCompatible(true))
	for i := 0; i < maxRemoteTypeKeys-1; i++ {
		f.typeResolver.remoteSchemaVersionsByType[uint32(i)] = 1
	}
	f.typeResolver.totalAcceptedSchemaVersions = int64(maxRemoteTypeKeys - 1)

	last := NewTypeDef(
		uint32(STRUCT),
		uint32(maxRemoteTypeKeys-1),
		nil,
		nil,
		false,
		false,
		nil,
	)
	key, err := f.typeResolver.checkRemoteTypeDefLimit(last)
	require.NoError(t, err)
	f.typeResolver.recordRemoteTypeDef(key)
	require.Len(t, f.typeResolver.remoteSchemaVersionsByType, maxRemoteTypeKeys)
	require.Equal(t, int64(maxRemoteTypeKeys), f.typeResolver.totalAcceptedSchemaVersions)

	beforeCount := len(f.typeResolver.remoteSchemaVersionsByType)
	beforeTotal := f.typeResolver.totalAcceptedSchemaVersions
	extra := NewTypeDef(
		uint32(STRUCT),
		uint32(maxRemoteTypeKeys),
		nil,
		nil,
		false,
		false,
		nil,
	)
	_, err = f.typeResolver.checkRemoteTypeDefLimit(extra)
	require.Error(t, err)
	require.Contains(t, err.Error(), "remote logical type limit")
	require.Len(t, f.typeResolver.remoteSchemaVersionsByType, beforeCount)
	require.Equal(t, beforeTotal, f.typeResolver.totalAcceptedSchemaVersions)

	existing := NewTypeDef(uint32(STRUCT), 0, nil, nil, false, false, nil)
	_, err = f.typeResolver.checkRemoteTypeDefLimit(existing)
	require.NoError(t, err)
}

func TestTypeDefFieldCountIntRange(t *testing.T) {
	f := New(WithXlang(false), WithCompatible(false))
	buffer := NewByteBuffer(nil)
	buffer.WriteByte(StructTypeDefFlag | SmallNumFieldsThreshold)
	buffer.WriteVarUint32(^uint32(0))

	_, err := decodeTypeDef(f, buffer, int64(buffer.WriterIndex()))
	require.Error(t, err)
	if intSize == 32 {
		require.Contains(t, err.Error(), "supported int range")
	} else {
		require.Contains(t, err.Error(), "MaxTypeFields")
	}
}
