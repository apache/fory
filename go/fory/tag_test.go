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

func mustParseFieldSpec(t *testing.T, field reflect.StructField) FieldSpec {
	t.Helper()
	spec, err := parseFieldSpec(field, true, true)
	require.NoError(t, err)
	spec.Type = bindResolvedTypeSpec(New(WithXlang(true)).typeResolver, field.Type, spec.Type)
	return spec
}

func TestParseFieldSpecBasic(t *testing.T) {
	type Example struct {
		ID      int32   `fory:"id=0"`
		Version *uint32 `fory:"id=1,nullable=true,ref=false"`
		Skip    string  `fory:"ignore"`
		Default string
	}

	typ := reflect.TypeOf(Example{})

	idSpec := mustParseFieldSpec(t, typ.Field(0))
	require.Equal(t, 0, idSpec.TagID)
	require.False(t, idSpec.NullableSet)
	require.False(t, idSpec.RefSet)
	require.EqualValues(t, VARINT32, idSpec.Type.TypeId())
	require.False(t, idSpec.Type.Nullable)

	versionSpec := mustParseFieldSpec(t, typ.Field(1))
	require.Equal(t, 1, versionSpec.TagID)
	require.True(t, versionSpec.NullableSet)
	require.True(t, versionSpec.Nullable)
	require.True(t, versionSpec.RefSet)
	require.False(t, versionSpec.Ref)
	require.True(t, versionSpec.Type.Nullable)
	require.False(t, versionSpec.Type.TrackRef)

	skipSpec := mustParseFieldSpec(t, typ.Field(2))
	require.True(t, skipSpec.Ignore)

	defaultSpec := mustParseFieldSpec(t, typ.Field(3))
	require.Equal(t, TagIDUseFieldName, defaultSpec.TagID)
	require.False(t, defaultSpec.HasTag)
	require.EqualValues(t, STRING, defaultSpec.Type.TypeId())
}

func TestParseFieldSpecNestedTypeHints(t *testing.T) {
	type Example struct {
		Size   uint64              `fory:"id=0,encoding=tagged"`
		Values []*int32            `fory:"id=1,type=list(element=int32(encoding=fixed))"`
		Data   map[int32]*int32    `fory:"id=2,type=map(value=int32(encoding=fixed))"`
		Nested map[string][]*int32 `fory:"id=3,type=map(value=list(element=int32(encoding=fixed)))"`
		Packed map[string][]int32  `fory:"id=4,type=map(value=array(element=int32))"`
		Ptrs   []*int32            `fory:"id=5"`
	}

	typ := reflect.TypeOf(Example{})

	sizeSpec := mustParseFieldSpec(t, typ.Field(0))
	require.EqualValues(t, TAGGED_UINT64, sizeSpec.Type.TypeId())

	valuesSpec := mustParseFieldSpec(t, typ.Field(1))
	require.EqualValues(t, LIST, valuesSpec.Type.TypeId())
	require.NotNil(t, valuesSpec.Type.Element)
	require.EqualValues(t, INT32, valuesSpec.Type.Element.TypeId())
	require.True(t, valuesSpec.Type.Element.Nullable)

	dataSpec := mustParseFieldSpec(t, typ.Field(2))
	require.EqualValues(t, MAP, dataSpec.Type.TypeId())
	require.NotNil(t, dataSpec.Type.Key)
	require.NotNil(t, dataSpec.Type.Value)
	require.EqualValues(t, VARINT32, dataSpec.Type.Key.TypeId())
	require.EqualValues(t, INT32, dataSpec.Type.Value.TypeId())
	require.True(t, dataSpec.Type.Value.Nullable)

	nestedSpec := mustParseFieldSpec(t, typ.Field(3))
	require.EqualValues(t, MAP, nestedSpec.Type.TypeId())
	require.EqualValues(t, LIST, nestedSpec.Type.Value.TypeId())
	require.EqualValues(t, INT32, nestedSpec.Type.Value.Element.TypeId())
	require.True(t, nestedSpec.Type.Value.Element.Nullable)

	packedSpec := mustParseFieldSpec(t, typ.Field(4))
	require.EqualValues(t, MAP, packedSpec.Type.TypeId())
	require.EqualValues(t, INT32_ARRAY, packedSpec.Type.Value.TypeId())
	require.NotNil(t, packedSpec.Type.Value.Element)
	require.EqualValues(t, INT32, packedSpec.Type.Value.Element.TypeId())

	ptrsSpec := mustParseFieldSpec(t, typ.Field(5))
	require.EqualValues(t, LIST, ptrsSpec.Type.TypeId())
	require.True(t, ptrsSpec.Type.Element.Nullable)
}

func TestFieldSpecSerializerSelection(t *testing.T) {
	type Example struct {
		DefaultList []int32  `fory:"id=0"`
		Dense       []int32  `fory:"id=1,type=array(element=int32)"`
		Explicit    []int32  `fory:"id=2,type=list(element=int32(encoding=fixed))"`
		Ptrs        []*int32 `fory:"id=3"`
		U8List      []uint8  `fory:"id=4"`
		U8Dense     []uint8  `fory:"id=5,type=array(element=uint8)"`
		Bytes       []byte   `fory:"id=6,type=bytes"`
	}

	f := New(WithXlang(true))
	typ := reflect.TypeOf(Example{})

	defaultListSpec := mustParseFieldSpec(t, typ.Field(0))
	defaultListSerializer, err := serializerForTypeSpec(f.typeResolver, typ.Field(0).Type, defaultListSpec.Type)
	require.NoError(t, err)
	defaultListPrimitive, ok := defaultListSerializer.(primitiveListSerializer)
	require.True(t, ok)
	require.EqualValues(t, VARINT32, defaultListPrimitive.elemTypeID)
	require.EqualValues(t, LIST, defaultListSpec.Type.TypeId())
	require.EqualValues(t, VARINT32, defaultListSpec.Type.Element.TypeId())

	denseSpec := mustParseFieldSpec(t, typ.Field(1))
	denseSerializer, err := serializerForTypeSpec(f.typeResolver, typ.Field(1).Type, denseSpec.Type)
	require.NoError(t, err)
	require.IsType(t, int32SliceSerializer{}, denseSerializer)
	require.EqualValues(t, INT32_ARRAY, denseSpec.Type.TypeId())

	explicitSpec := mustParseFieldSpec(t, typ.Field(2))
	explicitSerializer, err := serializerForTypeSpec(f.typeResolver, typ.Field(2).Type, explicitSpec.Type)
	require.NoError(t, err)
	explicitPrimitive, ok := explicitSerializer.(primitiveListSerializer)
	require.True(t, ok)
	require.EqualValues(t, INT32, explicitPrimitive.elemTypeID)
	require.EqualValues(t, LIST, explicitSpec.Type.TypeId())

	ptrsSpec := mustParseFieldSpec(t, typ.Field(3))
	ptrsSerializer, err := serializerForTypeSpec(f.typeResolver, typ.Field(3).Type, ptrsSpec.Type)
	require.NoError(t, err)
	require.IsType(t, &sliceSerializer{}, ptrsSerializer)
	require.EqualValues(t, LIST, ptrsSpec.Type.TypeId())

	u8ListSpec := mustParseFieldSpec(t, typ.Field(4))
	u8ListSerializer, err := serializerForTypeSpec(f.typeResolver, typ.Field(4).Type, u8ListSpec.Type)
	require.NoError(t, err)
	u8Primitive, ok := u8ListSerializer.(primitiveListSerializer)
	require.True(t, ok)
	require.EqualValues(t, UINT8, u8Primitive.elemTypeID)
	require.EqualValues(t, LIST, u8ListSpec.Type.TypeId())
	require.EqualValues(t, UINT8, u8ListSpec.Type.Element.TypeId())

	u8DenseSpec := mustParseFieldSpec(t, typ.Field(5))
	u8DenseSerializer, err := serializerForTypeSpec(f.typeResolver, typ.Field(5).Type, u8DenseSpec.Type)
	require.NoError(t, err)
	require.IsType(t, encodedByteSliceSerializer{}, u8DenseSerializer)
	require.EqualValues(t, UINT8_ARRAY, u8DenseSpec.Type.TypeId())

	bytesSpec := mustParseFieldSpec(t, typ.Field(6))
	bytesSerializer, err := serializerForTypeSpec(f.typeResolver, typ.Field(6).Type, bytesSpec.Type)
	require.NoError(t, err)
	require.IsType(t, encodedByteSliceSerializer{}, bytesSerializer)
	require.EqualValues(t, BINARY, bytesSpec.Type.TypeId())
}

func TestGroupFieldsUsesFlatOrderForFullyTaggedStructs(t *testing.T) {
	fields := []FieldInfo{
		{
			DispatchId: PrimitiveInt64DispatchId,
			Meta: &FieldMeta{
				Name: "long_value",
				Spec: &FieldSpec{TagID: 3},
			},
		},
		{
			DispatchId: PrimitiveBoolDispatchId,
			Meta: &FieldMeta{
				Name: "bool_value",
				Spec: &FieldSpec{TagID: 1},
			},
		},
		{
			DispatchId: StringDispatchId,
			Meta: &FieldMeta{
				Name: "string_value",
				Spec: &FieldSpec{TagID: 2},
			},
		},
	}

	group := GroupFields(fields)

	require.Empty(t, group.FixedFields)
	require.Empty(t, group.VarintFields)
	require.Len(t, group.RemainingFields, 3)
	require.Equal(t, "bool_value", group.RemainingFields[0].Meta.Name)
	require.Equal(t, "string_value", group.RemainingFields[1].Meta.Name)
	require.Equal(t, "long_value", group.RemainingFields[2].Meta.Name)
}

func TestParseFieldSpecRejectsInvalidTags(t *testing.T) {
	type DuplicateKeys struct {
		Value int32 `fory:"id=0,id=1"`
	}
	type UnknownKey struct {
		Value int32 `fory:"id=0,unknown=true"`
	}
	type LegacyCompress struct {
		Value uint32 `fory:"compress=true"`
	}
	type LegacyNestedRef struct {
		Value []int32 `fory:"nested_ref=[[]]"`
	}
	type BadDSL struct {
		Value []int32 `fory:"type=list(element=int32(encoding=fixed)"`
	}
	type ImpossibleOverride struct {
		Value int32 `fory:"type=list(element=int32)"`
	}
	type Conflict struct {
		Value int32 `fory:"encoding=fixed,type=int32"`
	}
	type InvalidArrayEncoding struct {
		Value []int32 `fory:"type=array(element=int32(encoding=varint))"`
	}
	type InvalidArrayElement struct {
		Value []string `fory:"type=array(element=string)"`
	}
	type InvalidArrayKey struct {
		Value map[[2]int32]string `fory:"type=map(key=array(element=int32))"`
	}
	type InvalidArrayNullableElement struct {
		Value []*int32 `fory:"type=array(element=int32(nullable=true))"`
	}

	tests := []struct {
		name string
		typ  reflect.Type
	}{
		{name: "duplicate keys", typ: reflect.TypeOf(DuplicateKeys{})},
		{name: "unknown key", typ: reflect.TypeOf(UnknownKey{})},
		{name: "legacy compress", typ: reflect.TypeOf(LegacyCompress{})},
		{name: "legacy nested_ref", typ: reflect.TypeOf(LegacyNestedRef{})},
		{name: "bad dsl", typ: reflect.TypeOf(BadDSL{})},
		{name: "impossible override", typ: reflect.TypeOf(ImpossibleOverride{})},
		{name: "encoding conflict", typ: reflect.TypeOf(Conflict{})},
		{name: "invalid array encoding", typ: reflect.TypeOf(InvalidArrayEncoding{})},
		{name: "invalid array element", typ: reflect.TypeOf(InvalidArrayElement{})},
		{name: "invalid array key", typ: reflect.TypeOf(InvalidArrayKey{})},
		{name: "invalid array nullable element", typ: reflect.TypeOf(InvalidArrayNullableElement{})},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			_, err := parseFieldSpec(tc.typ.Field(0), true, true)
			require.Error(t, err)
		})
	}
}

func TestValidateForyTagsStrict(t *testing.T) {
	type Valid struct {
		Field1 string  `fory:"id=0"`
		Field2 []int32 `fory:"id=1,type=list(element=int32(encoding=fixed))"`
	}
	type DuplicateIDs struct {
		Field1 string `fory:"id=0"`
		Field2 string `fory:"id=0"`
	}
	type IgnoredDuplicate struct {
		Field1 string `fory:"id=0"`
		Field2 string `fory:"id=0,ignore"`
	}
	type InvalidID struct {
		Field1 string `fory:"id=-2"`
	}

	require.NoError(t, validateForyTags(reflect.TypeOf(Valid{})))
	require.NoError(t, validateForyTags(reflect.TypeOf(IgnoredDuplicate{})))
	require.Error(t, validateForyTags(reflect.TypeOf(DuplicateIDs{})))
	require.Error(t, validateForyTags(reflect.TypeOf(InvalidID{})))
}

func TestShouldIncludeField(t *testing.T) {
	type Example struct {
		Included string `fory:"id=0"`
		Ignored  string `fory:"ignore"`
		Default  string
	}

	typ := reflect.TypeOf(Example{})
	require.True(t, shouldIncludeField(typ.Field(0)))
	require.False(t, shouldIncludeField(typ.Field(1)))
	require.True(t, shouldIncludeField(typ.Field(2)))
}
