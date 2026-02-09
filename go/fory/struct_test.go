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

	"github.com/apache/fory/go/fory/float16"
	"github.com/apache/fory/go/fory/optional"
	"github.com/stretchr/testify/require"
)

func ptr[T any](v T) *T {
	return &v
}

func TestUnsignedTypeSerialization(t *testing.T) {
	type TestStruct struct {
		U32Var    uint32 `fory:"compress=true"`
		U32Fixed  uint32 `fory:"compress=false"`
		U64Var    uint64 `fory:"encoding=varint"`
		U64Fixed  uint64 `fory:"encoding=fixed"`
		U64Tagged uint64 `fory:"encoding=tagged"`
	}

	f := New(WithXlang(true), WithCompatible(false))
	f.RegisterStruct(TestStruct{}, 9999)

	obj := TestStruct{
		U32Var:    3000000000,
		U32Fixed:  4000000000,
		U64Var:    10000000000,
		U64Fixed:  15000000000,
		U64Tagged: 1000000000,
	}

	data, err := f.Serialize(&obj)
	if err != nil {
		t.Fatalf("Serialize failed: %v", err)
	}

	var result any
	err = f.Deserialize(data, &result)
	if err != nil {
		t.Fatalf("Deserialize failed: %v", err)
	}

	resultObj := result.(*TestStruct)
	if resultObj.U32Var != obj.U32Var {
		t.Errorf("U32Var mismatch: expected %d, got %d", obj.U32Var, resultObj.U32Var)
	}
	if resultObj.U32Fixed != obj.U32Fixed {
		t.Errorf("U32Fixed mismatch: expected %d, got %d", obj.U32Fixed, resultObj.U32Fixed)
	}
	if resultObj.U64Var != obj.U64Var {
		t.Errorf("U64Var mismatch: expected %d, got %d", obj.U64Var, resultObj.U64Var)
	}
	if resultObj.U64Fixed != obj.U64Fixed {
		t.Errorf("U64Fixed mismatch: expected %d, got %d", obj.U64Fixed, resultObj.U64Fixed)
	}
	if resultObj.U64Tagged != obj.U64Tagged {
		t.Errorf("U64Tagged mismatch: expected %d, got %d", obj.U64Tagged, resultObj.U64Tagged)
	}
}

func TestOptionFieldSerialization(t *testing.T) {
	type Nested struct {
		Name string
	}
	type OptionStruct struct {
		OptInt    optional.Optional[int32]
		OptZero   optional.Optional[int32]
		OptString optional.Optional[string]
		OptBool   optional.Optional[bool]
	}

	f := New(WithXlang(true), WithCompatible(false))
	require.NoError(t, f.RegisterStruct(OptionStruct{}, 1100))

	obj := OptionStruct{
		OptInt:    optional.Some[int32](123),
		OptZero:   optional.Some[int32](0),
		OptString: optional.Some("hello"),
		OptBool:   optional.Some(true),
	}

	data, err := f.Serialize(&obj)
	require.NoError(t, err)

	var result any
	err = f.Deserialize(data, &result)
	require.NoError(t, err)

	out := result.(*OptionStruct)
	require.True(t, out.OptInt.IsSome())
	require.Equal(t, int32(123), out.OptInt.Unwrap())
	require.True(t, out.OptZero.IsSome())
	require.Equal(t, int32(0), out.OptZero.Unwrap())
	require.True(t, out.OptString.IsSome())
	require.Equal(t, "hello", out.OptString.Unwrap())
	require.True(t, out.OptBool.IsSome())
	require.Equal(t, true, out.OptBool.Unwrap())
}

type evolvingStruct struct {
	ID int32
}

type fixedStruct struct {
	ID int32
}

func (fixedStruct) ForyEvolving() bool {
	return false
}

func TestStructEvolvingOverride(t *testing.T) {
	f := New(WithXlang(true), WithCompatible(true))
	require.NoError(t, f.RegisterStruct(evolvingStruct{}, 100))
	require.NoError(t, f.RegisterStruct(fixedStruct{}, 101))

	resolver := f.GetTypeResolver()

	evolvingInfo, err := resolver.GetTypeInfo(reflect.ValueOf(evolvingStruct{}), true)
	require.NoError(t, err)
	require.Equal(t, uint32(COMPATIBLE_STRUCT), evolvingInfo.TypeID)

	fixedInfo, err := resolver.GetTypeInfo(reflect.ValueOf(fixedStruct{}), true)
	require.NoError(t, err)
	require.Equal(t, uint32(STRUCT), fixedInfo.TypeID)
}

func TestOptionFieldUnsupportedTypes(t *testing.T) {
	type Nested struct {
		Name string
	}
	type OptionStruct struct {
		OptStruct optional.Optional[Nested]
	}
	type OptionSlice struct {
		OptSlice optional.Optional[[]int]
	}
	type OptionMap struct {
		OptMap optional.Optional[map[string]int]
	}

	f := New(WithXlang(true), WithCompatible(false))
	require.Error(t, f.RegisterStruct(OptionStruct{}, 1101))
	require.Error(t, f.RegisterStruct(OptionSlice{}, 1102))
	require.Error(t, f.RegisterStruct(OptionMap{}, 1103))
}

func TestNumericPointerOptionalInterop(t *testing.T) {
	type NumericPtrStruct struct {
		I8  *int8
		I16 *int16
		I32 *int32
		I64 *int64
		I   *int
		U8  *uint8
		U16 *uint16
		U32 *uint32
		U64 *uint64
		U   *uint
		F32 *float32
		F64 *float64
	}
	type NumericOptStruct struct {
		I8  optional.Optional[int8]
		I16 optional.Optional[int16]
		I32 optional.Optional[int32]
		I64 optional.Optional[int64]
		I   optional.Optional[int]
		U8  optional.Optional[uint8]
		U16 optional.Optional[uint16]
		U32 optional.Optional[uint32]
		U64 optional.Optional[uint64]
		U   optional.Optional[uint]
		F32 optional.Optional[float32]
		F64 optional.Optional[float64]
	}

	ptrValues := NumericPtrStruct{
		I8:  ptr(int8(-8)),
		I16: ptr(int16(-16)),
		I32: ptr(int32(-32)),
		I64: ptr(int64(-64)),
		I:   ptr(int(-7)),
		U8:  ptr(uint8(8)),
		U16: ptr(uint16(16)),
		U32: ptr(uint32(32)),
		U64: ptr(uint64(64)),
		U:   ptr(uint(7)),
		F32: ptr(float32(3.25)),
		F64: ptr(float64(-6.5)),
	}
	optValues := NumericOptStruct{
		I8:  optional.Some[int8](-8),
		I16: optional.Some[int16](-16),
		I32: optional.Some[int32](-32),
		I64: optional.Some[int64](-64),
		I:   optional.Some[int](-7),
		U8:  optional.Some[uint8](8),
		U16: optional.Some[uint16](16),
		U32: optional.Some[uint32](32),
		U64: optional.Some[uint64](64),
		U:   optional.Some[uint](7),
		F32: optional.Some[float32](3.25),
		F64: optional.Some[float64](-6.5),
	}

	t.Run("PointerToOptionalNull", func(t *testing.T) {
		writer := New(WithXlang(true), WithCompatible(true))
		require.NoError(t, writer.RegisterNamedStruct(NumericPtrStruct{}, "NumericInterop"))
		data, err := writer.Marshal(&NumericPtrStruct{})
		require.NoError(t, err)

		reader := New(WithXlang(true), WithCompatible(true))
		require.NoError(t, reader.RegisterNamedStruct(NumericOptStruct{}, "NumericInterop"))
		var out NumericOptStruct
		require.NoError(t, reader.Unmarshal(data, &out))

		require.False(t, out.I8.IsSome())
		require.False(t, out.I16.IsSome())
		require.False(t, out.I32.IsSome())
		require.False(t, out.I64.IsSome())
		require.False(t, out.I.IsSome())
		require.False(t, out.U8.IsSome())
		require.False(t, out.U16.IsSome())
		require.False(t, out.U32.IsSome())
		require.False(t, out.U64.IsSome())
		require.False(t, out.U.IsSome())
		require.False(t, out.F32.IsSome())
		require.False(t, out.F64.IsSome())
	})

	t.Run("PointerToOptionalValue", func(t *testing.T) {
		writer := New(WithXlang(true), WithCompatible(true))
		require.NoError(t, writer.RegisterNamedStruct(NumericPtrStruct{}, "NumericInterop"))
		data, err := writer.Marshal(&ptrValues)
		require.NoError(t, err)

		reader := New(WithXlang(true), WithCompatible(true))
		require.NoError(t, reader.RegisterNamedStruct(NumericOptStruct{}, "NumericInterop"))
		var out NumericOptStruct
		require.NoError(t, reader.Unmarshal(data, &out))

		require.Equal(t, optValues, out)
	})

	t.Run("OptionalToPointerNull", func(t *testing.T) {
		writer := New(WithXlang(true), WithCompatible(true))
		require.NoError(t, writer.RegisterNamedStruct(NumericOptStruct{}, "NumericInterop"))
		data, err := writer.Marshal(&NumericOptStruct{})
		require.NoError(t, err)

		reader := New(WithXlang(true), WithCompatible(true))
		require.NoError(t, reader.RegisterNamedStruct(NumericPtrStruct{}, "NumericInterop"))
		var out NumericPtrStruct
		require.NoError(t, reader.Unmarshal(data, &out))

		require.Nil(t, out.I8)
		require.Nil(t, out.I16)
		require.Nil(t, out.I32)
		require.Nil(t, out.I64)
		require.Nil(t, out.I)
		require.Nil(t, out.U8)
		require.Nil(t, out.U16)
		require.Nil(t, out.U32)
		require.Nil(t, out.U64)
		require.Nil(t, out.U)
		require.Nil(t, out.F32)
		require.Nil(t, out.F64)
	})

	t.Run("OptionalToPointerValue", func(t *testing.T) {
		writer := New(WithXlang(true), WithCompatible(true))
		require.NoError(t, writer.RegisterNamedStruct(NumericOptStruct{}, "NumericInterop"))
		data, err := writer.Marshal(&optValues)
		require.NoError(t, err)

		reader := New(WithXlang(true), WithCompatible(true))
		require.NoError(t, reader.RegisterNamedStruct(NumericPtrStruct{}, "NumericInterop"))
		var out NumericPtrStruct
		require.NoError(t, reader.Unmarshal(data, &out))

		require.NotNil(t, out.I8)
		require.Equal(t, *ptrValues.I8, *out.I8)
		require.NotNil(t, out.I16)
		require.Equal(t, *ptrValues.I16, *out.I16)
		require.NotNil(t, out.I32)
		require.Equal(t, *ptrValues.I32, *out.I32)
		require.NotNil(t, out.I64)
		require.Equal(t, *ptrValues.I64, *out.I64)
		require.NotNil(t, out.I)
		require.Equal(t, *ptrValues.I, *out.I)
		require.NotNil(t, out.U8)
		require.Equal(t, *ptrValues.U8, *out.U8)
		require.NotNil(t, out.U16)
		require.Equal(t, *ptrValues.U16, *out.U16)
		require.NotNil(t, out.U32)
		require.Equal(t, *ptrValues.U32, *out.U32)
		require.NotNil(t, out.U64)
		require.Equal(t, *ptrValues.U64, *out.U64)
		require.NotNil(t, out.U)
		require.Equal(t, *ptrValues.U, *out.U)
		require.NotNil(t, out.F32)
		require.Equal(t, *ptrValues.F32, *out.F32)
		require.NotNil(t, out.F64)
		require.Equal(t, *ptrValues.F64, *out.F64)
	})
}

// Test struct for compatible mode tests (must be named struct at package level)
type SetFieldsStruct struct {
	SetField    Set[string]
	NullableSet Set[string] `fory:"nullable"`
	MapField    map[string]bool
	NullableMap map[string]bool `fory:"nullable"`
}

func TestSetFieldSerializationSchemaConsistent(t *testing.T) {
	f := New(WithXlang(true), WithCompatible(false))
	err := f.RegisterStruct(SetFieldsStruct{}, 1001)
	require.NoError(t, err, "register struct error")

	// Create test object with Set and Map fields
	obj := SetFieldsStruct{
		SetField:    NewSet[string](),
		NullableSet: NewSet[string](),
		MapField:    map[string]bool{"key1": true, "key2": true},
		NullableMap: map[string]bool{"nk1": true},
	}
	obj.SetField.Add("x", "y")
	obj.NullableSet.Add("m", "n")

	// Serialize
	data, err := f.Serialize(&obj)
	require.NoError(t, err, "Serialize failed")
	t.Logf("Serialized %d bytes", len(data))

	// Deserialize
	var result any
	err = f.Deserialize(data, &result)
	require.NoError(t, err, "Deserialize failed")

	resultObj := result.(*SetFieldsStruct)

	// Verify SetField
	require.Equal(t, 2, len(resultObj.SetField), "SetField length mismatch")
	require.True(t, resultObj.SetField.Contains("x"), "SetField should contain 'x'")
	require.True(t, resultObj.SetField.Contains("y"), "SetField should contain 'y'")

	// Verify NullableSet
	require.Equal(t, 2, len(resultObj.NullableSet), "NullableSet length mismatch")
	require.True(t, resultObj.NullableSet.Contains("m"), "NullableSet should contain 'm'")
	require.True(t, resultObj.NullableSet.Contains("n"), "NullableSet should contain 'n'")

	// Verify MapField
	require.Equal(t, 2, len(resultObj.MapField), "MapField length mismatch")
	require.True(t, resultObj.MapField["key1"])
	require.True(t, resultObj.MapField["key2"])

	// Verify NullableMap
	require.Equal(t, 1, len(resultObj.NullableMap), "NullableMap length mismatch")
	require.True(t, resultObj.NullableMap["nk1"])
}

func TestSetFieldSerializationCompatible(t *testing.T) {
	f := New(WithXlang(true), WithCompatible(true))
	err := f.RegisterStruct(SetFieldsStruct{}, 1002)
	require.NoError(t, err, "register struct error")

	// Create test object with Set and Map fields
	obj := SetFieldsStruct{
		SetField:    NewSet[string](),
		NullableSet: NewSet[string](),
		MapField:    map[string]bool{"key1": true, "key2": true},
		NullableMap: map[string]bool{"nk1": true},
	}
	obj.SetField.Add("x", "y")
	obj.NullableSet.Add("m", "n")

	// Serialize
	data, err := f.Serialize(&obj)
	require.NoError(t, err, "Serialize failed")
	t.Logf("Serialized %d bytes", len(data))

	// Deserialize
	var result any
	err = f.Deserialize(data, &result)
	require.NoError(t, err, "Deserialize failed")

	resultObj := result.(*SetFieldsStruct)

	// Verify SetField
	require.Equal(t, 2, len(resultObj.SetField), "SetField length mismatch")
	require.True(t, resultObj.SetField.Contains("x"), "SetField should contain 'x'")
	require.True(t, resultObj.SetField.Contains("y"), "SetField should contain 'y'")

	// Verify NullableSet
	require.Equal(t, 2, len(resultObj.NullableSet), "NullableSet length mismatch")
	require.True(t, resultObj.NullableSet.Contains("m"), "NullableSet should contain 'm'")
	require.True(t, resultObj.NullableSet.Contains("n"), "NullableSet should contain 'n'")

	// Verify MapField
	require.Equal(t, 2, len(resultObj.MapField), "MapField length mismatch")
	require.True(t, resultObj.MapField["key1"])
	require.True(t, resultObj.MapField["key2"])

	// Verify NullableMap
	require.Equal(t, 1, len(resultObj.NullableMap), "NullableMap length mismatch")
	require.True(t, resultObj.NullableMap["nk1"])
}

func TestSetFieldTypeId(t *testing.T) {
	// Test that Set fields have the correct TypeId in fingerprint
	type TestStruct struct {
		SetField Set[string]
		MapField map[string]bool
	}

	f := New(WithXlang(true), WithCompatible(false))
	err := f.RegisterStruct(TestStruct{}, 1003)
	require.NoError(t, err, "register struct error")

	// Get the struct serializer to inspect the fields
	typeInfo, err := f.typeResolver.getTypeInfo(reflect.ValueOf(TestStruct{}), false)
	require.NoError(t, err, "getTypeInfo failed")
	require.NotNil(t, typeInfo, "typeInfo is nil")

	structSer, ok := typeInfo.Serializer.(*structSerializer)
	require.True(t, ok, "serializer is not structSerializer")

	// Check each field
	for _, field := range structSer.fields {
		t.Logf("Field: %s, Type: %v, TypeId: %d, Serializer: %T",
			field.Meta.Name, field.Meta.Type, field.Meta.TypeId, field.Serializer)

		if field.Meta.Name == "set_field" {
			require.Equal(t, TypeId(SET), field.Meta.TypeId, "SetField should have TypeId=SET(21)")
			require.NotNil(t, field.Serializer, "SetField serializer should not be nil")
			_, isSetSerializer := field.Serializer.(setSerializer)
			require.True(t, isSetSerializer, "SetField serializer should be setSerializer")
		}

		if field.Meta.Name == "map_field" {
			require.Equal(t, TypeId(MAP), field.Meta.TypeId, "MapField should have TypeId=MAP(22)")
			require.NotNil(t, field.Serializer, "MapField serializer should not be nil")
		}
	}
}

func TestSkipAnyValueReadsSharedTypeMeta(t *testing.T) {
	type First struct {
		ID int
	}
	type Second struct {
		Name string
	}

	f := New(WithXlang(true), WithCompatible(true))
	require.NoError(t, f.RegisterStruct(First{}, 2001))
	require.NoError(t, f.RegisterStruct(Second{}, 2002))

	buf := NewByteBuffer(nil)
	require.NoError(t, f.SerializeTo(buf, First{ID: 10}))
	require.NoError(t, f.SerializeTo(buf, Second{Name: "ok"}))

	f.resetReadState()
	f.readCtx.SetData(buf.Bytes())

	isNull := readHeader(f.readCtx)
	require.False(t, isNull)
	SkipAnyValue(f.readCtx, true)
	require.NoError(t, f.readCtx.CheckError())

	f.resetReadState()
	isNull = readHeader(f.readCtx)
	require.False(t, isNull)

	var out any
	f.readCtx.ReadValue(reflect.ValueOf(&out).Elem(), RefModeTracking, true)
	require.NoError(t, f.readCtx.CheckError())

	result, ok := out.(*Second)
	require.True(t, ok)
	require.Equal(t, "ok", result.Name)
}

func TestFloat16StructField(t *testing.T) {
	type StructWithFloat16 struct {
		F16      float16.Float16
		SliceF16 []float16.Float16
		ArrayF16 [3]float16.Float16
	}

	f := New(WithXlang(true))
	require.NoError(t, f.RegisterStruct(StructWithFloat16{}, 3001))

	val := &StructWithFloat16{
		F16:      float16.Float16FromFloat32(1.5),
		SliceF16: []float16.Float16{float16.Float16FromFloat32(1.0), float16.Float16FromFloat32(2.5)},
		ArrayF16: [3]float16.Float16{float16.Zero, float16.One, float16.NegZero},
	}

	data, err := f.Serialize(val)
	require.NoError(t, err)

	// Create new instance
	res := &StructWithFloat16{}
	err = f.Deserialize(data, res)
	require.NoError(t, err)

	// Verify
	require.Equal(t, val.F16, res.F16)
	require.Equal(t, val.SliceF16, res.SliceF16)
	require.Equal(t, val.ArrayF16, res.ArrayF16)

	// Specific value check
	require.Equal(t, float32(1.5), res.F16.Float32())
}
