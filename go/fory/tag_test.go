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

func TestParseForyTag(t *testing.T) {
	type TestStruct struct {
		Field1  string  `fory:"id=0"`
		Field2  int     `fory:"id=1,nullable=false,ref=false,ignore=false"`
		Field3  *string `fory:"id=2,nullable=true,ref=true"`
		Field4  string  `fory:"nullable,ref"`
		Field5  string  `fory:"ignore"`
		Field6  string  `fory:"ignore=true"`
		Field7  string  `fory:"ignore=false"`
		Field8  string  `fory:"-"`
		Field9  string
		Field10 string `fory:"id=3,ignore=false"`
		Field11 string `fory:"id=-1"`
		Field12 string `fory:"nullable=true,ref=false"`
	}

	typ := reflect.TypeOf(TestStruct{})

	// Test Field1: id=0
	tag1 := parseForyTag(typ.Field(0))
	require.True(t, tag1.HasTag)
	require.Equal(t, 0, tag1.ID)
	require.False(t, tag1.Nullable)
	require.False(t, tag1.Ref)
	require.False(t, tag1.Ignore)
	require.False(t, tag1.NullableSet)
	require.False(t, tag1.RefSet)
	require.False(t, tag1.IgnoreSet)

	// Test Field2: all explicit false values
	tag2 := parseForyTag(typ.Field(1))
	require.Equal(t, 1, tag2.ID)
	require.False(t, tag2.Nullable)
	require.False(t, tag2.Ref)
	require.False(t, tag2.Ignore)
	require.True(t, tag2.NullableSet)
	require.True(t, tag2.RefSet)
	require.True(t, tag2.IgnoreSet)

	// Test Field3: explicit true values
	tag3 := parseForyTag(typ.Field(2))
	require.Equal(t, 2, tag3.ID)
	require.True(t, tag3.Nullable)
	require.True(t, tag3.Ref)
	require.False(t, tag3.Ignore)

	// Test Field4: standalone flags (presence = true)
	tag4 := parseForyTag(typ.Field(3))
	require.Equal(t, TagIDUseFieldName, tag4.ID)
	require.True(t, tag4.Nullable)
	require.True(t, tag4.Ref)
	require.True(t, tag4.NullableSet)
	require.True(t, tag4.RefSet)

	// Test Field5: standalone ignore
	tag5 := parseForyTag(typ.Field(4))
	require.True(t, tag5.Ignore)
	require.True(t, tag5.IgnoreSet)

	// Test Field6: explicit ignore=true
	tag6 := parseForyTag(typ.Field(5))
	require.True(t, tag6.Ignore)
	require.True(t, tag6.IgnoreSet)

	// Test Field7: explicit ignore=false
	tag7 := parseForyTag(typ.Field(6))
	require.False(t, tag7.Ignore)
	require.True(t, tag7.IgnoreSet)

	// Test Field8: "-" shorthand
	tag8 := parseForyTag(typ.Field(7))
	require.True(t, tag8.Ignore)
	require.True(t, tag8.IgnoreSet)

	// Test Field9: no tag
	tag9 := parseForyTag(typ.Field(8))
	require.False(t, tag9.HasTag)
	require.False(t, tag9.Ignore)
	require.Equal(t, TagIDUseFieldName, tag9.ID)

	// Test Field10: has ID but not ignored
	tag10 := parseForyTag(typ.Field(9))
	require.Equal(t, 3, tag10.ID)
	require.False(t, tag10.Ignore)
	require.True(t, tag10.IgnoreSet)

	// Test Field11: explicit id=-1 (use field name)
	tag11 := parseForyTag(typ.Field(10))
	require.Equal(t, TagIDUseFieldName, tag11.ID)
	require.True(t, tag11.HasTag)

	// Test Field12: nullable=true,ref=false
	tag12 := parseForyTag(typ.Field(11))
	require.True(t, tag12.Nullable)
	require.False(t, tag12.Ref)
	require.True(t, tag12.NullableSet)
	require.True(t, tag12.RefSet)
}

func TestShouldIncludeField(t *testing.T) {
	type TestStruct struct {
		Included1 string `fory:"id=0"`
		Included2 string `fory:"ignore=false"`
		Ignored1  string `fory:"ignore"`
		Ignored2  string `fory:"ignore=true"`
		Ignored3  string `fory:"-"`
		NoTag     string
	}

	typ := reflect.TypeOf(TestStruct{})

	require.True(t, shouldIncludeField(typ.Field(0)))  // Included1
	require.True(t, shouldIncludeField(typ.Field(1)))  // Included2 (ignore=false)
	require.False(t, shouldIncludeField(typ.Field(2))) // Ignored1
	require.False(t, shouldIncludeField(typ.Field(3))) // Ignored2
	require.False(t, shouldIncludeField(typ.Field(4))) // Ignored3
	require.True(t, shouldIncludeField(typ.Field(5)))  // NoTag (default: include)
}

func TestValidateForyTags(t *testing.T) {
	// Test valid struct
	type ValidStruct struct {
		Field1 string `fory:"id=0"`
		Field2 string `fory:"id=1"`
		Field3 string `fory:"id=-1"`
		Field4 string // No tag
	}
	err := validateForyTags(reflect.TypeOf(ValidStruct{}))
	require.NoError(t, err)

	// Test duplicate tag IDs
	type DuplicateIDs struct {
		Field1 string `fory:"id=0"`
		Field2 string `fory:"id=0"`
	}
	err = validateForyTags(reflect.TypeOf(DuplicateIDs{}))
	require.Error(t, err)
	require.Contains(t, err.Error(), "duplicate")
	foryErr, ok := err.(Error)
	require.True(t, ok, "error should be fory.Error type")
	require.Equal(t, ErrKindInvalidTag, foryErr.Kind())

	// Test invalid ID (< -1)
	type InvalidID struct {
		Field1 string `fory:"id=-2"`
	}
	err = validateForyTags(reflect.TypeOf(InvalidID{}))
	require.Error(t, err)
	require.Contains(t, err.Error(), "invalid")
	foryErr, ok = err.(Error)
	require.True(t, ok, "error should be fory.Error type")
	require.Equal(t, ErrKindInvalidTag, foryErr.Kind())

	// Test that ignored fields don't count for ID uniqueness
	type IgnoredFields struct {
		Field1 string `fory:"id=0"`
		Field2 string `fory:"id=0,ignore"` // Same ID but ignored
		Field3 string `fory:"id=1"`
	}
	err = validateForyTags(reflect.TypeOf(IgnoredFields{}))
	require.NoError(t, err)
}

func TestParseForyTagEdgeCases(t *testing.T) {
	// Test whitespace handling
	type WhitespaceStruct struct {
		Field1 string `fory:" id = 0 , nullable = true "`
	}
	typ := reflect.TypeOf(WhitespaceStruct{})
	tag := parseForyTag(typ.Field(0))
	require.Equal(t, 0, tag.ID)
	require.True(t, tag.Nullable)

	// Test empty tag value
	type EmptyTagStruct struct {
		Field1 string `fory:""`
	}
	typ2 := reflect.TypeOf(EmptyTagStruct{})
	tag2 := parseForyTag(typ2.Field(0))
	require.True(t, tag2.HasTag)
	require.Equal(t, TagIDUseFieldName, tag2.ID)

	// Test boolean values
	type BoolValuesStruct struct {
		Field1 string `fory:"nullable=1"`
		Field2 string `fory:"nullable=yes"`
		Field3 string `fory:"nullable=TRUE"`
		Field4 string `fory:"nullable=no"`
	}
	typ3 := reflect.TypeOf(BoolValuesStruct{})

	tag3 := parseForyTag(typ3.Field(0))
	require.True(t, tag3.Nullable) // "1" -> true

	tag4 := parseForyTag(typ3.Field(1))
	require.True(t, tag4.Nullable) // "yes" -> true

	tag5 := parseForyTag(typ3.Field(2))
	require.True(t, tag5.Nullable) // "TRUE" -> true

	tag6 := parseForyTag(typ3.Field(3))
	require.False(t, tag6.Nullable) // "no" -> false
}

// Test struct with tags for serialization tests
type PersonWithTags struct {
	Name   string `fory:"id=0"`
	Age    int32  `fory:"id=1"`
	Email  string `fory:"id=2,nullable=true"`
	Secret string `fory:"-"`
}

// Test struct without tags
type PersonWithoutTags struct {
	Name  string
	Age   int32
	Email string
}

func TestSerializationWithTags(t *testing.T) {
	fory := NewFory(WithXlang(true), WithRefTracking(false), WithCompatible(true))
	err := fory.RegisterNamedStruct(PersonWithTags{}, "test.PersonWithTags")
	require.NoError(t, err)

	person := PersonWithTags{
		Name:   "John",
		Age:    30,
		Email:  "john@example.com",
		Secret: "should-be-ignored",
	}

	// Serialize
	data, err := fory.Marshal(&person)
	require.NoError(t, err)

	// Deserialize
	var result PersonWithTags
	err = fory.Unmarshal(data, &result)
	require.NoError(t, err)

	require.Equal(t, person.Name, result.Name)
	require.Equal(t, person.Age, result.Age)
	require.Equal(t, person.Email, result.Email)
	require.Empty(t, result.Secret) // Secret should be empty (not serialized)
}

func TestSerializationWithoutTags(t *testing.T) {
	fory := NewFory(WithXlang(true), WithRefTracking(false), WithCompatible(true))
	err := fory.RegisterNamedStruct(PersonWithoutTags{}, "test.PersonWithoutTags")
	require.NoError(t, err)

	person := PersonWithoutTags{
		Name:  "Jane",
		Age:   25,
		Email: "jane@example.com",
	}

	// Serialize
	data, err := fory.Marshal(&person)
	require.NoError(t, err)

	// Deserialize
	var result PersonWithoutTags
	err = fory.Unmarshal(data, &result)
	require.NoError(t, err)

	require.Equal(t, person.Name, result.Name)
	require.Equal(t, person.Age, result.Age)
	require.Equal(t, person.Email, result.Email)
}

func TestTagIDReducesPayloadSize(t *testing.T) {
	fory1 := NewFory(WithXlang(true), WithRefTracking(false), WithCompatible(true))
	err := fory1.RegisterNamedStruct(PersonWithTags{}, "test.PersonWithTags")
	require.NoError(t, err)

	fory2 := NewFory(WithXlang(true), WithRefTracking(false), WithCompatible(true))
	err = fory2.RegisterNamedStruct(PersonWithoutTags{}, "test.PersonWithoutTags")
	require.NoError(t, err)

	// Create comparable data
	personWithTags := PersonWithTags{
		Name:  "John",
		Age:   30,
		Email: "john@example.com",
	}
	personWithoutTags := PersonWithoutTags{
		Name:  "John",
		Age:   30,
		Email: "john@example.com",
	}

	// Serialize both
	dataWithTags, err := fory1.Marshal(&personWithTags)
	require.NoError(t, err)

	dataWithoutTags, err := fory2.Marshal(&personWithoutTags)
	require.NoError(t, err)

	// Tag IDs should produce smaller or equal payload
	// (Tag IDs use compact integer encoding vs field name strings)
	t.Logf("With tags: %d bytes, Without tags: %d bytes", len(dataWithTags), len(dataWithoutTags))
	require.LessOrEqual(t, len(dataWithTags), len(dataWithoutTags),
		"Tag IDs should produce smaller or equal payload size")
}

// Test struct with numeric fields and tag IDs for compact encoding
type NumericStructWithTags struct {
	FieldA *int32 `fory:"id=0,nullable=false"`
	FieldB *int32 `fory:"id=1,nullable=false"`
	FieldC *int32 `fory:"id=2,nullable=false"`
	FieldD *int32 `fory:"id=3,nullable=false"`
	FieldE *int32 `fory:"id=4,nullable=false"`
}

type NumericStructWithoutTags struct {
	FieldA *int32
	FieldB *int32
	FieldC *int32
	FieldD *int32
	FieldE *int32
}

func TestNumericStructTagIDReducesSize(t *testing.T) {
	fory1 := NewFory(WithXlang(true), WithRefTracking(false), WithCompatible(true))
	err := fory1.RegisterNamedStruct(NumericStructWithTags{}, "test.NumericStructWithTags")
	require.NoError(t, err)

	fory2 := NewFory(WithXlang(true), WithRefTracking(false), WithCompatible(true))
	err = fory2.RegisterNamedStruct(NumericStructWithoutTags{}, "test.NumericStructWithoutTags")
	require.NoError(t, err)

	// Create small int32 values
	v1, v2, v3, v4, v5 := int32(1), int32(2), int32(3), int32(4), int32(5)

	objWithTags := NumericStructWithTags{
		FieldA: &v1,
		FieldB: &v2,
		FieldC: &v3,
		FieldD: &v4,
		FieldE: &v5,
	}
	objWithoutTags := NumericStructWithoutTags{
		FieldA: &v1,
		FieldB: &v2,
		FieldC: &v3,
		FieldD: &v4,
		FieldE: &v5,
	}

	// Serialize both
	dataWithTags, err := fory1.Marshal(&objWithTags)
	require.NoError(t, err)

	dataWithoutTags, err := fory2.Marshal(&objWithoutTags)
	require.NoError(t, err)

	t.Logf("Numeric with tags: %d bytes, without tags: %d bytes, saved: %d bytes",
		len(dataWithTags), len(dataWithoutTags), len(dataWithoutTags)-len(dataWithTags))

	// Tag IDs + nullable=false should produce significantly smaller payload
	require.Less(t, len(dataWithTags), len(dataWithoutTags),
		"Tag IDs with nullable=false should produce smaller payload")

	// Deserialize and verify
	var result NumericStructWithTags
	err = fory1.Unmarshal(dataWithTags, &result)
	require.NoError(t, err)

	require.Equal(t, *objWithTags.FieldA, *result.FieldA)
	require.Equal(t, *objWithTags.FieldB, *result.FieldB)
	require.Equal(t, *objWithTags.FieldC, *result.FieldC)
	require.Equal(t, *objWithTags.FieldD, *result.FieldD)
	require.Equal(t, *objWithTags.FieldE, *result.FieldE)
}

// Test structs at package level for consistent naming
type TestStructNoNull struct {
	A *int32 `fory:"id=0,nullable=false,ref=false"`
	B *int32 `fory:"id=1,nullable=false,ref=false"`
	C *int32 `fory:"id=2,nullable=false,ref=false"`
	D *int32 `fory:"id=3,nullable=false,ref=false"`
	E *int32 `fory:"id=4,nullable=false,ref=false"`
}

type TestStructDefalt struct {
	A *int32 `fory:"id=0"`
	B *int32 `fory:"id=1"`
	C *int32 `fory:"id=2"`
	D *int32 `fory:"id=3"`
	E *int32 `fory:"id=4"`
}

func TestNullableRefFlagsRespected(t *testing.T) {
	// Debug: verify tag parsing
	typ1 := reflect.TypeOf(TestStructNoNull{})
	for i := 0; i < typ1.NumField(); i++ {
		field := typ1.Field(i)
		tag := parseForyTag(field)
		t.Logf("Field %s: ID=%d, Nullable=%v (set=%v), Ref=%v (set=%v)",
			field.Name, tag.ID, tag.Nullable, tag.NullableSet, tag.Ref, tag.RefSet)
	}

	fory1 := NewFory(WithXlang(true), WithRefTracking(false), WithCompatible(true))
	err := fory1.RegisterNamedStruct(TestStructNoNull{}, "test.TestStructNoNull")
	require.NoError(t, err)

	fory2 := NewFory(WithXlang(true), WithRefTracking(false), WithCompatible(true))
	err = fory2.RegisterNamedStruct(TestStructDefalt{}, "test.TestStructDefalt")
	require.NoError(t, err)

	v1, v2, v3, v4, v5 := int32(1), int32(2), int32(3), int32(4), int32(5)

	objNoFlags := TestStructNoNull{A: &v1, B: &v2, C: &v3, D: &v4, E: &v5}
	objDefault := TestStructDefalt{A: &v1, B: &v2, C: &v3, D: &v4, E: &v5}

	dataNoFlags, err := fory1.Marshal(&objNoFlags)
	require.NoError(t, err)

	dataDefault, err := fory2.Marshal(&objDefault)
	require.NoError(t, err)

	// With nullable=false, we should save 5 bytes (1 null flag per field)
	t.Logf("No nullable/ref flags: %d bytes, Default flags: %d bytes, saved: %d bytes",
		len(dataNoFlags), len(dataDefault), len(dataDefault)-len(dataNoFlags))

	require.Less(t, len(dataNoFlags), len(dataDefault),
		"nullable=false,ref=false should produce smaller payload by skipping null flags")

	// Verify deserialization works
	var result TestStructNoNull
	err = fory1.Unmarshal(dataNoFlags, &result)
	require.NoError(t, err)
	require.Equal(t, *objNoFlags.A, *result.A)
	require.Equal(t, *objNoFlags.B, *result.B)
	require.Equal(t, *objNoFlags.C, *result.C)
	require.Equal(t, *objNoFlags.D, *result.D)
	require.Equal(t, *objNoFlags.E, *result.E)
}

func TestTypeDefEncodingSizeWithTagIDs(t *testing.T) {
	fory1 := NewFory(WithXlang(true), WithRefTracking(false), WithCompatible(true))
	err := fory1.RegisterNamedStruct(NumericStructWithTags{}, "test.NumericStructWithTags")
	require.NoError(t, err)

	fory2 := NewFory(WithXlang(true), WithRefTracking(false), WithCompatible(true))
	err = fory2.RegisterNamedStruct(NumericStructWithoutTags{}, "test.NumericStructWithoutTags")
	require.NoError(t, err)

	// Build TypeDef for struct with tags
	typeDefWithTags, err := buildTypeDef(fory1, reflect.ValueOf(NumericStructWithTags{}))
	require.NoError(t, err)

	// Build TypeDef for struct without tags
	typeDefWithoutTags, err := buildTypeDef(fory2, reflect.ValueOf(NumericStructWithoutTags{}))
	require.NoError(t, err)

	// Encode TypeDef with tags
	bufferWithTags := NewByteBuffer(make([]byte, 0, 256))
	writeErr := &Error{}
	typeDefWithTags.writeTypeDef(bufferWithTags, writeErr)
	require.False(t, writeErr.HasError())
	sizeWithTags := bufferWithTags.WriterIndex()

	// Encode TypeDef without tags
	bufferWithoutTags := NewByteBuffer(make([]byte, 0, 256))
	typeDefWithoutTags.writeTypeDef(bufferWithoutTags, writeErr)
	require.False(t, writeErr.HasError())
	sizeWithoutTags := bufferWithoutTags.WriterIndex()

	t.Logf("TypeDef with tags: %d bytes, without tags: %d bytes, saved: %d bytes",
		sizeWithTags, sizeWithoutTags, sizeWithoutTags-sizeWithTags)

	// TypeDef with tag IDs should be smaller (tag IDs use 1 byte vs field names)
	require.Less(t, sizeWithTags, sizeWithoutTags,
		"TypeDef with tag IDs should be smaller than with field names")
}

// Test struct with large tag IDs (>15 requires varint encoding)
type StructWithLargeTagIDs struct {
	Field1 string `fory:"id=0"`
	Field2 string `fory:"id=15"`
	Field3 string `fory:"id=100"`
	Field4 string `fory:"id=1000"`
}

func TestLargeTagIDs(t *testing.T) {
	fory := NewFory(WithXlang(true), WithRefTracking(false), WithCompatible(true))
	err := fory.RegisterNamedStruct(StructWithLargeTagIDs{}, "test.StructWithLargeTagIDs")
	require.NoError(t, err)

	obj := StructWithLargeTagIDs{
		Field1: "value1",
		Field2: "value2",
		Field3: "value3",
		Field4: "value4",
	}

	// Serialize
	data, err := fory.Marshal(&obj)
	require.NoError(t, err)

	// Deserialize
	var result StructWithLargeTagIDs
	err = fory.Unmarshal(data, &result)
	require.NoError(t, err)

	require.Equal(t, obj.Field1, result.Field1)
	require.Equal(t, obj.Field2, result.Field2)
	require.Equal(t, obj.Field3, result.Field3)
	require.Equal(t, obj.Field4, result.Field4)
}

// Test struct with mixed tag and no-tag fields
type MixedTagStruct struct {
	TaggedField   string `fory:"id=0"`
	UntaggedField string
	AnotherTagged int32 `fory:"id=1,nullable=false"`
}

func TestMixedTagFields(t *testing.T) {
	fory := NewFory(WithXlang(true), WithRefTracking(false), WithCompatible(true))
	err := fory.RegisterNamedStruct(MixedTagStruct{}, "test.MixedTagStruct")
	require.NoError(t, err)

	obj := MixedTagStruct{
		TaggedField:   "tagged",
		UntaggedField: "untagged",
		AnotherTagged: 42,
	}

	// Serialize
	data, err := fory.Marshal(&obj)
	require.NoError(t, err)

	// Deserialize
	var result MixedTagStruct
	err = fory.Unmarshal(data, &result)
	require.NoError(t, err)

	require.Equal(t, obj.TaggedField, result.TaggedField)
	require.Equal(t, obj.UntaggedField, result.UntaggedField)
	require.Equal(t, obj.AnotherTagged, result.AnotherTagged)
}

// Test nested struct with tags
type InnerWithTags struct {
	Value string `fory:"id=0"`
	Count int32  `fory:"id=1"`
}

type OuterWithTags struct {
	Name  string        `fory:"id=0"`
	Inner InnerWithTags `fory:"id=1"`
	Items []string      `fory:"id=2"`
}

func TestNestedStructWithTags(t *testing.T) {
	fory := NewFory(WithXlang(true), WithRefTracking(false), WithCompatible(true))
	err := fory.RegisterNamedStruct(InnerWithTags{}, "test.InnerWithTags")
	require.NoError(t, err)
	err = fory.RegisterNamedStruct(OuterWithTags{}, "test.OuterWithTags")
	require.NoError(t, err)

	obj := OuterWithTags{
		Name: "outer",
		Inner: InnerWithTags{
			Value: "inner-value",
			Count: 10,
		},
		Items: []string{"a", "b", "c"},
	}

	// Serialize
	data, err := fory.Marshal(&obj)
	require.NoError(t, err)

	// Deserialize
	var result OuterWithTags
	err = fory.Unmarshal(data, &result)
	require.NoError(t, err)

	require.Equal(t, obj.Name, result.Name)
	require.Equal(t, obj.Inner.Value, result.Inner.Value)
	require.Equal(t, obj.Inner.Count, result.Inner.Count)
	require.Equal(t, obj.Items, result.Items)
}

func TestParseTypeIDTag(t *testing.T) {
	type TestStruct struct {
		U32Var    uint32 `fory:"compress=true"`
		U32Fixed  uint32 `fory:"compress=false"`
		U64Var    uint64 `fory:"encoding=varint"`
		U64Fixed  uint64 `fory:"encoding=fixed"`
		U64Tagged uint64 `fory:"encoding=tagged"`
	}

	typ := reflect.TypeOf(TestStruct{})

	// Test U32Var
	field := typ.Field(0)
	tag := parseForyTag(field)
	if !tag.CompressSet {
		t.Errorf("U32Var: CompressSet should be true")
	}
	if !tag.Compress {
		t.Errorf("U32Var: Compress should be true")
	}

	// Test U32Fixed
	field = typ.Field(1)
	tag = parseForyTag(field)
	if !tag.CompressSet {
		t.Errorf("U32Fixed: CompressSet should be true")
	}
	if tag.Compress {
		t.Errorf("U32Fixed: Compress should be false")
	}

	// Test U64Var
	field = typ.Field(2)
	tag = parseForyTag(field)
	if !tag.EncodingSet {
		t.Errorf("U64Var: EncodingSet should be true")
	}
	if tag.Encoding != "varint" {
		t.Errorf("U64Var: expected encoding 'varint', got %s", tag.Encoding)
	}

	// Test U64Fixed
	field = typ.Field(3)
	tag = parseForyTag(field)
	if !tag.EncodingSet {
		t.Errorf("U64Fixed: EncodingSet should be true")
	}
	if tag.Encoding != "fixed" {
		t.Errorf("U64Fixed: expected encoding 'fixed', got %s", tag.Encoding)
	}

	// Test U64Tagged
	field = typ.Field(4)
	tag = parseForyTag(field)
	if !tag.EncodingSet {
		t.Errorf("U64Tagged: EncodingSet should be true")
	}
	if tag.Encoding != "tagged" {
		t.Errorf("U64Tagged: expected encoding 'tagged', got %s", tag.Encoding)
	}
}
