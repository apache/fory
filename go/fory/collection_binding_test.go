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

type bindingValue interface {
	bindingValue()
}

type bindingBase struct {
	Value int32
}

func (bindingBase) bindingValue() {}

type bindingSubtype struct {
	Value int32
}

func (bindingSubtype) bindingValue() {}

type mapBoxBinding struct {
	Value   int32
	Padding [256]byte
}

func (mapBoxBinding) bindingValue() {}

type pointerBindingValue interface {
	pointerBindingValue()
}

type pointerBinding struct {
	Value int32
}

func (*pointerBinding) pointerBindingValue() {}

type bindingCodec struct{}

func (bindingCodec) WriteData(ctx *WriteContext, value reflect.Value) {
	ctx.Buffer().WriteVarint32(int32(value.Field(0).Int()))
}

func (bindingCodec) ReadData(ctx *ReadContext, value reflect.Value) {
	value.Field(0).SetInt(int64(ctx.Buffer().ReadVarint32(ctx.Err())))
}

type pointerBindingCodec struct{}

func (pointerBindingCodec) WriteData(ctx *WriteContext, value reflect.Value) {
	if value.Kind() == reflect.Ptr {
		value = value.Elem()
	}
	ctx.Buffer().WriteVarint32(int32(value.Field(0).Int()))
}

func (pointerBindingCodec) ReadData(ctx *ReadContext, value reflect.Value) {
	value.Field(0).SetInt(int64(ctx.Buffer().ReadVarint32(ctx.Err())))
}

type bindingUnion struct {
	caseID uint32
	value  any
}

func (bindingUnion) ForyUnionMarker() {}

func (u bindingUnion) ForyUnionGet() (uint32, any) {
	return u.caseID, u.value
}

func (u *bindingUnion) ForyUnionSet(caseID uint32, value any) {
	u.caseID = caseID
	u.value = value
}

type setBindingA struct {
	Value int32
}

type setBindingB struct {
	Value int32
}

type setBindingCodec struct {
	type_ reflect.Type
}

func (s setBindingCodec) WriteData(ctx *WriteContext, value reflect.Value) {
	if value.Type() != s.type_ {
		ctx.Err().SetError(SerializationErrorf(
			"set codec for %v cannot write %v", s.type_, value.Type()))
		return
	}
	ctx.Buffer().WriteVarint32(int32(value.Field(0).Int()))
}

func (s setBindingCodec) ReadData(ctx *ReadContext, value reflect.Value) {
	if value.Type() != s.type_ {
		ctx.Err().SetError(DeserializationErrorf(
			"set codec for %v cannot read %v", s.type_, value.Type()))
		return
	}
	value.Field(0).SetInt(int64(ctx.Buffer().ReadVarint32(ctx.Err())))
}

type setRefNode struct {
	Value int32
}

type setRefOwner struct {
	AAnchor *setRefNode
	BValues Set[any]
	ZTail   int32
}

type skipMapSource struct {
	AAnchor *bindingBase
	BValues map[any]any
	ZTail   int32
}

type skipMapTarget struct {
	AAnchor *bindingBase
	ZTail   int32
}

type unregisteredBinding struct {
	Value int32
}

func bindingSpec() *TypeSpec {
	spec := NewSimpleTypeSpec(NAMED_EXT)
	spec.GoType = reflect.TypeOf(bindingBase{})
	return spec
}

func pointerBindingSpec() *TypeSpec {
	spec := NewSimpleTypeSpec(NAMED_EXT)
	spec.GoType = reflect.TypeOf(pointerBinding{})
	return spec
}

func mapBoxBindingSpec() *TypeSpec {
	spec := NewSimpleTypeSpec(NAMED_EXT)
	spec.GoType = reflect.TypeOf(mapBoxBinding{})
	return spec
}

func bindingSerializer(t *testing.T, f *Fory, type_ reflect.Type, spec *TypeSpec) Serializer {
	t.Helper()
	require.NoError(t, f.RegisterExtensionByName(
		bindingBase{}, "test.BindingBase", bindingCodec{}))
	serializer, err := serializerForTypeSpec(f.typeResolver, type_, spec)
	require.NoError(t, err)
	return serializer
}

func roundTripBody(t *testing.T, f *Fory, serializer Serializer, source any) any {
	t.Helper()
	return roundTripBodies(t, f, serializer, source)[0]
}

func roundTripBodies(t *testing.T, f *Fory, serializer Serializer, sources ...any) []any {
	t.Helper()
	f.writeCtx.Reset()
	for _, source := range sources {
		serializer.WriteData(f.writeCtx, reflect.ValueOf(source))
	}
	require.NoError(t, f.writeCtx.CheckError())
	data := append([]byte(nil), f.writeCtx.Buffer().Bytes()...)
	f.resetWriteState()

	f.readCtx.SetData(data)
	f.readCtx.remainingGraphMemoryBytes = f.config.MaxGraphMemoryBytes
	results := make([]any, len(sources))
	for i, source := range sources {
		target := reflect.New(reflect.TypeOf(source)).Elem()
		serializer.ReadData(f.readCtx, target)
		results[i] = target.Interface()
	}
	require.NoError(t, f.readCtx.CheckError())
	f.resetReadState()
	return results
}

func TestSelectedCollectionCodec(t *testing.T) {
	tests := []struct {
		name     string
		type_    reflect.Type
		spec     *TypeSpec
		source   any
		expected any
	}{
		{
			name:     "slice",
			type_:    reflect.TypeOf([]bindingValue{}),
			spec:     NewCollectionTypeSpec(LIST, bindingSpec()),
			source:   []bindingValue{bindingSubtype{Value: 1}, bindingSubtype{Value: 2}},
			expected: []bindingValue{bindingBase{Value: 1}, bindingBase{Value: 2}},
		},
		{
			name:     "set",
			type_:    reflect.TypeOf(Set[bindingValue]{}),
			spec:     NewCollectionTypeSpec(SET, bindingSpec()),
			source:   Set[bindingValue]{bindingSubtype{Value: 3}: {}},
			expected: Set[bindingValue]{bindingBase{Value: 3}: {}},
		},
	}
	for _, mode := range []struct {
		name       string
		compatible bool
	}{
		{name: "schema_consistent"},
		{name: "compatible", compatible: true},
	} {
		for _, test := range tests {
			t.Run(mode.name+"_"+test.name, func(t *testing.T) {
				f := New(WithXlang(true), WithCompatible(mode.compatible), WithTrackRef(false))
				serializer := bindingSerializer(t, f, test.type_, test.spec)
				subtype := reflect.TypeOf(bindingSubtype{})
				_, registered := f.typeResolver.typesInfo[subtype]
				require.False(t, registered)
				sources := []any{test.source}
				if mode.compatible {
					// The second body uses the shared-metadata index emitted for the first.
					sources = append(sources, test.source)
				}
				for _, result := range roundTripBodies(t, f, serializer, sources...) {
					require.Equal(t, test.expected, result)
				}
				_, registered = f.typeResolver.typesInfo[subtype]
				require.False(t, registered)
			})
		}
	}
}

func TestSelectedMapBoxBudget(t *testing.T) {
	tests := []struct {
		name   string
		type_  reflect.Type
		spec   *TypeSpec
		source any
	}{
		{
			name:   "regular",
			type_:  reflect.TypeOf(map[int32]bindingValue{}),
			spec:   NewMapTypeSpec(MAP, NewSimpleTypeSpec(VARINT32), mapBoxBindingSpec()),
			source: map[int32]bindingValue{1: mapBoxBinding{Value: 7}},
		},
		{
			name:   "null_value",
			type_:  reflect.TypeOf(map[bindingValue]*int32{}),
			spec:   NewMapTypeSpec(MAP, mapBoxBindingSpec(), nil),
			source: map[bindingValue]*int32{mapBoxBinding{Value: 7}: nil},
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			f := New(WithXlang(true), WithCompatible(true), WithTrackRef(false))
			require.NoError(t, f.RegisterExtensionByName(
				mapBoxBinding{}, "test.MapBoxBinding", bindingCodec{}))
			serializer, err := serializerForTypeSpec(f.typeResolver, test.type_, test.spec)
			require.NoError(t, err)

			f.writeCtx.Reset()
			serializer.WriteData(f.writeCtx, reflect.ValueOf(test.source))
			require.NoError(t, f.writeCtx.CheckError())
			data := append([]byte(nil), f.writeCtx.Buffer().Bytes()...)
			f.resetWriteState()

			entryBytes := int64(test.type_.Key().Size() + test.type_.Elem().Size())
			boxBytes := int64(reflect.TypeOf(mapBoxBinding{}).Size())
			required := int64(graphMapOwnerBytes) + entryBytes + boxBytes
			for _, budget := range []int64{required - 1, required} {
				target := reflect.New(test.type_).Elem()
				f.readCtx.SetData(data)
				f.readCtx.remainingGraphMemoryBytes = budget
				serializer.ReadData(f.readCtx, target)
				readErr := f.readCtx.CheckError()
				f.resetReadState()
				if budget < required {
					require.Error(t, readErr)
					require.Contains(t, readErr.Error(), "maxGraphMemoryBytes")
					continue
				}
				require.NoError(t, readErr)
				require.Len(t, target.Interface(), 1)
			}
		})
	}
}

func TestSelectedMapNullPointerOwner(t *testing.T) {
	f := New(WithXlang(true), WithCompatible(true), WithTrackRef(false))
	require.NoError(t, f.RegisterExtensionByName(
		pointerBinding{}, "test.PointerBinding", pointerBindingCodec{}))

	t.Run("key", func(t *testing.T) {
		mapType := reflect.TypeOf(map[pointerBindingValue]*int32{})
		serializer, err := serializerForTypeSpec(
			f.typeResolver, mapType, NewMapTypeSpec(MAP, pointerBindingSpec(), nil))
		require.NoError(t, err)
		result := roundTripBody(t, f, serializer, map[pointerBindingValue]*int32{
			&pointerBinding{Value: 7}: nil,
		}).(map[pointerBindingValue]*int32)
		require.Len(t, result, 1)
		for key, value := range result {
			require.Equal(t, int32(7), key.(*pointerBinding).Value)
			require.Nil(t, value)
		}
	})

	t.Run("value", func(t *testing.T) {
		mapType := reflect.TypeOf(map[*int32]pointerBindingValue{})
		serializer, err := serializerForTypeSpec(
			f.typeResolver, mapType, NewMapTypeSpec(MAP, nil, pointerBindingSpec()))
		require.NoError(t, err)
		result := roundTripBody(t, f, serializer, map[*int32]pointerBindingValue{
			nil: &pointerBinding{Value: 8},
		}).(map[*int32]pointerBindingValue)
		require.Len(t, result, 1)
		for key, value := range result {
			require.Nil(t, key)
			require.Equal(t, int32(8), value.(*pointerBinding).Value)
		}
	})
}

func TestDynamicArrayValue(t *testing.T) {
	f := New(WithXlang(true), WithCompatible(false), WithTrackRef(false))
	for _, source := range []any{
		[2]any{int32(1), int32(2)},
		[2]any{},
	} {
		serializer, err := f.typeResolver.getSerializerByType(reflect.TypeOf(source), false)
		require.NoError(t, err)
		result := roundTripBody(t, f, serializer, source)
		require.Equal(t, source, result)
	}
}

func TestDynamicSliceAllNullFraming(t *testing.T) {
	f := New(WithXlang(true), WithCompatible(false), WithTrackRef(false))
	serializer, err := f.typeResolver.getSerializerByType(reflect.TypeOf([]any{}), false)
	require.NoError(t, err)
	source := []any{nil, nil}
	result := roundTripBody(t, f, serializer, source)
	require.Equal(t, source, result)
}

func TestDynamicCollectionRegistrationError(t *testing.T) {
	for _, test := range []struct {
		name   string
		source any
	}{
		{name: "slice", source: []any{unregisteredBinding{Value: 1}}},
		{name: "set", source: Set[any]{unregisteredBinding{Value: 1}: {}}},
	} {
		t.Run(test.name, func(t *testing.T) {
			f := New(WithXlang(true), WithCompatible(false), WithTrackRef(false))
			_, err := f.Serialize(test.source)
			require.Error(t, err)
			require.Contains(t, err.Error(), "must be registered explicitly")
		})
	}
}

func TestConcreteArrayFraming(t *testing.T) {
	f := New(WithXlang(true), WithCompatible(false), WithTrackRef(false))
	require.NoError(t, f.RegisterExtensionByName(
		bindingBase{}, "test.ArrayBindingBase", bindingCodec{}))
	arrayType := reflect.TypeOf([2]*bindingBase{})
	arraySerializer, err := f.typeResolver.getSerializerByType(arrayType, false)
	require.NoError(t, err)

	t.Run("all_null", func(t *testing.T) {
		result := roundTripBody(t, f, arraySerializer, [2]*bindingBase{})
		require.Equal(t, [2]*bindingBase{}, result)
	})

	t.Run("mixed", func(t *testing.T) {
		source := [2]*bindingBase{nil, {Value: 4}}
		result := roundTripBody(t, f, arraySerializer, source)
		require.Equal(t, source, result)
	})

	t.Run("nullable_slice_wire", func(t *testing.T) {
		sliceSerializer, err := f.typeResolver.getSerializerByType(
			reflect.TypeOf([]*bindingBase{}), false)
		require.NoError(t, err)
		f.writeCtx.Reset()
		sliceSerializer.WriteData(
			f.writeCtx, reflect.ValueOf([]*bindingBase{nil, {Value: 5}}))
		require.NoError(t, f.writeCtx.CheckError())
		data := append([]byte(nil), f.writeCtx.Buffer().Bytes()...)
		f.resetWriteState()

		var result [2]*bindingBase
		f.readCtx.SetData(data)
		f.readCtx.remainingGraphMemoryBytes = f.config.MaxGraphMemoryBytes
		arraySerializer.ReadData(f.readCtx, reflect.ValueOf(&result).Elem())
		require.NoError(t, f.readCtx.CheckError())
		f.resetReadState()
		require.Equal(t, [2]*bindingBase{nil, {Value: 5}}, result)
	})
}

func TestExtensionReadsTypeInfo(t *testing.T) {
	for _, compatible := range []bool{false, true} {
		f := New(WithXlang(true), WithCompatible(compatible), WithTrackRef(false))
		require.NoError(t, f.RegisterExtensionByName(
			bindingBase{}, "test.UnionBindingBase", bindingCodec{}))
		require.NoError(t, f.RegisterUnionByName(
			bindingUnion{},
			"test.BindingUnion",
			NewUnionSerializer(UnionCase{
				ID: 1, Type: reflect.TypeOf(bindingBase{}), TypeID: NAMED_EXT, Spec: bindingSpec(),
			}),
		))
		data, err := f.Serialize(&bindingUnion{caseID: 1, value: bindingBase{Value: 7}})
		require.NoError(t, err)
		var result bindingUnion
		require.NoError(t, f.Deserialize(data, &result))
		require.Equal(t, bindingBase{Value: 7}, result.value)
	}
}

func TestDynamicSetTypeIdentity(t *testing.T) {
	f := New(WithXlang(true), WithCompatible(false), WithTrackRef(false))
	typeA := reflect.TypeOf(setBindingA{})
	typeB := reflect.TypeOf(setBindingB{})
	require.NoError(t, f.RegisterExtensionByName(
		typeA, "test.SetBindingA", setBindingCodec{type_: typeA}))
	require.NoError(t, f.RegisterExtensionByName(
		typeB, "test.SetBindingB", setBindingCodec{type_: typeB}))

	serializer, err := f.typeResolver.getSerializerByType(reflect.TypeOf(Set[any]{}), false)
	require.NoError(t, err)
	source := Set[any]{setBindingA{Value: 1}: {}, setBindingB{Value: 2}: {}}
	result := roundTripBody(t, f, serializer, source).(Set[any])
	require.Len(t, result, 2)
	var foundA, foundB bool
	for value := range result {
		switch value := value.(type) {
		case *setBindingA:
			require.Equal(t, int32(1), value.Value)
			foundA = true
		case *setBindingB:
			require.Equal(t, int32(2), value.Value)
			foundB = true
		}
	}
	require.True(t, foundA)
	require.True(t, foundB)
}

func TestSelectedSetBudgetOwner(t *testing.T) {
	f := New(WithXlang(true), WithCompatible(false), WithTrackRef(false))
	serializer := bindingSerializer(
		t, f, reflect.TypeOf(Set[bindingValue]{}), NewCollectionTypeSpec(SET, bindingSpec()),
	).(setSerializer)
	serializer.declaredElemTypeInfo = f.typeResolver.getTypeInfoByType(boolType)
	require.NotNil(t, serializer.declaredElemTypeInfo)

	f.writeCtx.Reset()
	serializer.WriteData(f.writeCtx, reflect.ValueOf(
		Set[bindingValue]{bindingBase{Value: 5}: {}},
	))
	require.NoError(t, f.writeCtx.CheckError())
	data := append([]byte(nil), f.writeCtx.Buffer().Bytes()...)
	f.resetWriteState()

	setType := reflect.TypeOf(Set[bindingValue]{})
	entryBytes := int64(setType.Key().Size() + setType.Elem().Size())
	ownerBytes := int64(reflect.TypeOf(bindingBase{}).Size())
	f.readCtx.SetData(data)
	f.readCtx.remainingGraphMemoryBytes = int64(graphSetOwnerBytes) + entryBytes + ownerBytes - 1
	var result Set[bindingValue]
	serializer.ReadData(f.readCtx, reflect.ValueOf(&result).Elem())
	err := f.readCtx.CheckError()
	f.resetReadState()
	require.Error(t, err)
	require.Contains(t, err.Error(), "maxGraphMemoryBytes")
}

func TestSetNullableFraming(t *testing.T) {
	f := New(WithXlang(true), WithCompatible(false), WithTrackRef(false))
	require.NoError(t, f.RegisterExtensionByName(
		bindingBase{}, "test.NullableBindingBase", bindingCodec{}))
	serializer, err := f.typeResolver.getSerializerByType(
		reflect.TypeOf(Set[*bindingBase]{}), false)
	require.NoError(t, err)
	source := Set[*bindingBase]{nil: {}, {Value: 5}: {}}
	for attempt := 0; attempt < 100; attempt++ {
		f.writeCtx.Reset()
		serializer.WriteData(f.writeCtx, reflect.ValueOf(source))
		require.NoError(t, f.writeCtx.CheckError())
		data := append([]byte(nil), f.writeCtx.Buffer().Bytes()...)
		f.resetWriteState()

		buf := NewByteBuffer(data)
		bufErr := &Error{}
		require.Equal(t, uint32(2), buf.ReadVarUint32(bufErr))
		flag := buf.ReadByte(bufErr)
		require.False(t, bufErr.HasError())
		if flag&CollectionIsSameType == 0 {
			continue
		}
		require.NotZero(t, flag&CollectionHasNull)

		var result Set[*bindingBase]
		f.readCtx.SetData(data)
		f.readCtx.remainingGraphMemoryBytes = f.config.MaxGraphMemoryBytes
		serializer.ReadData(f.readCtx, reflect.ValueOf(&result).Elem())
		require.NoError(t, f.readCtx.CheckError())
		f.resetReadState()
		require.Len(t, result, 2)
		require.Contains(t, result, (*bindingBase)(nil))
		for value := range result {
			if value != nil {
				require.Equal(t, int32(5), value.Value)
			}
		}
		return
	}
	t.Fatal("same-type set path was not selected")
}

func TestSetBackrefFraming(t *testing.T) {
	f := New(WithXlang(true), WithCompatible(false), WithTrackRef(true))
	require.NoError(t, f.RegisterStructByName(setRefNode{}, "test.SetRefNode"))
	require.NoError(t, f.RegisterStructByName(setRefOwner{}, "test.SetRefOwner"))
	anchor := &setRefNode{Value: 1}
	data, err := f.Serialize(&setRefOwner{
		AAnchor: anchor,
		BValues: Set[any]{anchor: {}, "other": {}},
		ZTail:   3,
	})
	require.NoError(t, err)
	var result setRefOwner
	require.NoError(t, f.Deserialize(data, &result))
	require.Equal(t, int32(3), result.ZTail)
	require.Len(t, result.BValues, 2)
	require.Contains(t, result.BValues, result.AAnchor)
}

func TestSkipTrackedNullMap(t *testing.T) {
	for _, test := range []struct {
		name   string
		values func(*bindingBase) map[any]any
	}{
		{name: "null_key", values: func(value *bindingBase) map[any]any {
			return map[any]any{nil: value}
		}},
		{name: "null_value", values: func(value *bindingBase) map[any]any {
			return map[any]any{value: nil}
		}},
	} {
		t.Run(test.name, func(t *testing.T) {
			writer := New(WithXlang(true), WithCompatible(true), WithTrackRef(true))
			reader := New(WithXlang(true), WithCompatible(true), WithTrackRef(true))
			require.NoError(t, writer.RegisterExtensionByName(
				bindingBase{}, "test.SkipBindingBase", bindingCodec{}))
			require.NoError(t, reader.RegisterExtensionByName(
				bindingBase{}, "test.SkipBindingBase", bindingCodec{}))
			require.NoError(t, writer.RegisterStructByName(
				skipMapSource{}, "test.SkipTrackedMap"))
			require.NoError(t, reader.RegisterStructByName(
				skipMapTarget{}, "test.SkipTrackedMap"))
			anchor := &bindingBase{Value: 5}
			data, err := writer.Serialize(&skipMapSource{
				AAnchor: anchor, BValues: test.values(anchor), ZTail: 9,
			})
			require.NoError(t, err)
			var result skipMapTarget
			require.NoError(t, reader.Deserialize(data, &result))
			require.Equal(t, int32(9), result.ZTail)
		})
	}
}
