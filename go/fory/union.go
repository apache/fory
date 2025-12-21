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
	"fmt"
	"reflect"
)

// ============================================================================
// Generic Union Types
// ============================================================================

// Union2 represents a tagged union type that can hold one of two alternative types.
// It's equivalent to Rust's enum with two variants, C++'s std::variant<T1, T2>,
// or Python's typing.Union[T1, T2].
//
// Note: The fields are exported for serialization purposes. Use the provided
// methods (Index, First, Second, Match) for normal usage.
//
// Example usage:
//
//	// Create a union that holds an int32
//	union := fory.NewUnion2A[int32, string](42)
//	// or create a union that holds a string
//	union := fory.NewUnion2B[int32, string]("hello")
//
//	// Pattern matching
//	union.Match(
//	    func(i int32) { fmt.Println("got int:", i) },
//	    func(s string) { fmt.Println("got string:", s) },
//	)
type Union2[T1 any, T2 any] struct {
	V1  *T1
	V2  *T2
	Idx int
}

// NewUnion2A creates a Union2 containing the first alternative type.
func NewUnion2A[T1 any, T2 any](t T1) Union2[T1, T2] {
	return Union2[T1, T2]{V1: &t, Idx: 1}
}

// NewUnion2B creates a Union2 containing the second alternative type.
func NewUnion2B[T1 any, T2 any](t T2) Union2[T1, T2] {
	return Union2[T1, T2]{V2: &t, Idx: 2}
}

// Match performs pattern matching on the union, calling the appropriate function
// based on which alternative is active.
func (u Union2[T1, T2]) Match(case1 func(T1), case2 func(T2)) {
	switch u.Idx {
	case 1:
		case1(*u.V1)
	case 2:
		case2(*u.V2)
	default:
		panic("Union2 is uninitialized")
	}
}

// Index returns the 1-based index of the active alternative.
func (u Union2[T1, T2]) Index() int {
	return u.Idx
}

// IsFirst returns true if the first alternative is active.
func (u Union2[T1, T2]) IsFirst() bool {
	return u.Idx == 1
}

// IsSecond returns true if the second alternative is active.
func (u Union2[T1, T2]) IsSecond() bool {
	return u.Idx == 2
}

// First returns the first alternative value. Panics if not the active alternative.
func (u Union2[T1, T2]) First() T1 {
	if u.Idx != 1 {
		panic("Union2: First() called but second alternative is active")
	}
	return *u.V1
}

// Second returns the second alternative value. Panics if not the active alternative.
func (u Union2[T1, T2]) Second() T2 {
	if u.Idx != 2 {
		panic("Union2: Second() called but first alternative is active")
	}
	return *u.V2
}

// Union3 represents a tagged union type that can hold one of three alternative types.
type Union3[T1 any, T2 any, T3 any] struct {
	V1  *T1
	V2  *T2
	V3  *T3
	Idx int
}

// NewUnion3A creates a Union3 containing the first alternative type.
func NewUnion3A[T1 any, T2 any, T3 any](t T1) Union3[T1, T2, T3] {
	return Union3[T1, T2, T3]{V1: &t, Idx: 1}
}

// NewUnion3B creates a Union3 containing the second alternative type.
func NewUnion3B[T1 any, T2 any, T3 any](t T2) Union3[T1, T2, T3] {
	return Union3[T1, T2, T3]{V2: &t, Idx: 2}
}

// NewUnion3C creates a Union3 containing the third alternative type.
func NewUnion3C[T1 any, T2 any, T3 any](t T3) Union3[T1, T2, T3] {
	return Union3[T1, T2, T3]{V3: &t, Idx: 3}
}

// Match performs pattern matching on the union.
func (u Union3[T1, T2, T3]) Match(f1 func(T1), f2 func(T2), f3 func(T3)) {
	switch u.Idx {
	case 1:
		f1(*u.V1)
	case 2:
		f2(*u.V2)
	case 3:
		f3(*u.V3)
	default:
		panic("Union3 is uninitialized")
	}
}

// Index returns the 1-based index of the active alternative.
func (u Union3[T1, T2, T3]) Index() int {
	return u.Idx
}

// Union4 represents a tagged union type that can hold one of four alternative types.
type Union4[T1 any, T2 any, T3 any, T4 any] struct {
	V1  *T1
	V2  *T2
	V3  *T3
	V4  *T4
	Idx int
}

// NewUnion4A creates a Union4 containing the first alternative type.
func NewUnion4A[T1 any, T2 any, T3 any, T4 any](t T1) Union4[T1, T2, T3, T4] {
	return Union4[T1, T2, T3, T4]{V1: &t, Idx: 1}
}

// NewUnion4B creates a Union4 containing the second alternative type.
func NewUnion4B[T1 any, T2 any, T3 any, T4 any](t T2) Union4[T1, T2, T3, T4] {
	return Union4[T1, T2, T3, T4]{V2: &t, Idx: 2}
}

// NewUnion4C creates a Union4 containing the third alternative type.
func NewUnion4C[T1 any, T2 any, T3 any, T4 any](t T3) Union4[T1, T2, T3, T4] {
	return Union4[T1, T2, T3, T4]{V3: &t, Idx: 3}
}

// NewUnion4D creates a Union4 containing the fourth alternative type.
func NewUnion4D[T1 any, T2 any, T3 any, T4 any](t T4) Union4[T1, T2, T3, T4] {
	return Union4[T1, T2, T3, T4]{V4: &t, Idx: 4}
}

// Match performs pattern matching on the union.
func (u Union4[T1, T2, T3, T4]) Match(f1 func(T1), f2 func(T2), f3 func(T3), f4 func(T4)) {
	switch u.Idx {
	case 1:
		f1(*u.V1)
	case 2:
		f2(*u.V2)
	case 3:
		f3(*u.V3)
	case 4:
		f4(*u.V4)
	default:
		panic("Union4 is uninitialized")
	}
}

// Index returns the 1-based index of the active alternative.
func (u Union4[T1, T2, T3, T4]) Index() int {
	return u.Idx
}

// ============================================================================
// Union Serializer
// ============================================================================

// unionSerializer serializes generic Union types.
//
// Serialization format:
// 1. Write variant index (varuint32) - identifies which alternative type is active
// 2. In xlang mode, write type info for the active alternative
// 3. Write the value data using the alternative's serializer
type unionSerializer struct {
	type_            reflect.Type
	alternativeTypes []reflect.Type
	typeResolver     *TypeResolver
}

// newUnionSerializer creates a new serializer for Union types with the specified alternatives.
func newUnionSerializer(type_ reflect.Type, typeResolver *TypeResolver, alternativeTypes []reflect.Type) *unionSerializer {
	return &unionSerializer{
		type_:            type_,
		alternativeTypes: alternativeTypes,
		typeResolver:     typeResolver,
	}
}

func (s *unionSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, value reflect.Value) {
	buf := ctx.Buffer()

	// Handle nil pointer
	if value.Kind() == reflect.Ptr && value.IsNil() {
		buf.WriteInt8(NullFlag)
		return
	}

	// Get the actual struct value
	if value.Kind() == reflect.Ptr {
		value = value.Elem()
	}

	// Get the index field to check if initialized
	indexField := value.FieldByName("Idx")
	if !indexField.IsValid() || indexField.Int() == 0 {
		switch refMode {
		case RefModeTracking, RefModeNullOnly:
			buf.WriteInt8(NullFlag)
		}
		return
	}

	// Write ref flag for non-null
	switch refMode {
	case RefModeTracking:
		refWritten, err := ctx.RefResolver().WriteRefOrNull(buf, value)
		if err != nil {
			ctx.SetError(FromError(err))
			return
		}
		if refWritten {
			return
		}
	case RefModeNullOnly:
		buf.WriteInt8(NotNullValueFlag)
	}

	// Write type info if needed
	if writeType {
		buf.WriteVaruint32Small7(uint32(UNION))
	}

	s.WriteData(ctx, value)
}

func (s *unionSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	buf := ctx.Buffer()

	// Get the actual struct value
	if value.Kind() == reflect.Ptr {
		value = value.Elem()
	}

	// Get the active index (1-based in the struct)
	indexField := value.FieldByName("Idx")
	activeIndex := int(indexField.Int()) - 1 // Convert to 0-based

	if activeIndex < 0 || activeIndex >= len(s.alternativeTypes) {
		ctx.SetError(SerializationErrorf("union index out of bounds: %d", activeIndex+1))
		return
	}

	// Write the active variant index (0-based for protocol)
	buf.WriteVaruint32(uint32(activeIndex))

	// Get the value pointer field (V1, V2, V3, V4)
	fieldName := fmt.Sprintf("V%d", activeIndex+1)
	valueField := value.FieldByName(fieldName)
	if !valueField.IsValid() || valueField.IsNil() {
		ctx.SetError(SerializationErrorf("union value field %s is nil", fieldName))
		return
	}

	// Get the actual value (dereference the pointer)
	innerValue := valueField.Elem()

	// Get the serializer for the active alternative
	altType := s.alternativeTypes[activeIndex]
	serializer, err := ctx.TypeResolver().getSerializerByType(altType, false)
	if err != nil {
		ctx.SetError(FromError(fmt.Errorf("no serializer for union alternative type %v: %w", altType, err)))
		return
	}

	// In xlang mode, write type info for the alternative
	if ctx.TypeResolver().isXlang {
		typeInfo, err := ctx.TypeResolver().getTypeInfo(innerValue, true)
		if err != nil {
			ctx.SetError(FromError(err))
			return
		}
		if err := ctx.TypeResolver().WriteTypeInfo(buf, typeInfo); err != nil {
			ctx.SetError(FromError(err))
			return
		}
	}

	// Write the value data
	serializer.WriteData(ctx, innerValue)
}

func (s *unionSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, value reflect.Value) {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()

	switch refMode {
	case RefModeTracking:
		refID, err := ctx.RefResolver().TryPreserveRefId(buf)
		if err != nil {
			ctx.SetError(FromError(err))
			return
		}
		if int8(refID) < NotNullValueFlag {
			obj := ctx.RefResolver().GetReadObject(refID)
			if obj.IsValid() {
				if value.Kind() == reflect.Ptr {
					value.Elem().Set(obj)
				} else {
					value.Set(obj)
				}
			}
			return
		}
	case RefModeNullOnly:
		flag := buf.ReadInt8(ctxErr)
		if flag == NullFlag {
			return
		}
	}

	if readType {
		typeId := buf.ReadVaruint32Small7(ctxErr)
		if TypeId(typeId) != UNION {
			ctx.SetError(DeserializationErrorf("expected UNION type id %d, got %d", UNION, typeId))
			return
		}
	}

	s.ReadData(ctx, s.type_, value)
}

func (s *unionSerializer) ReadData(ctx *ReadContext, type_ reflect.Type, value reflect.Value) {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()

	// Read the stored variant index (0-based)
	storedIndex := int(buf.ReadVaruint32(ctxErr))
	if ctx.HasError() {
		return
	}

	// Validate index is within bounds
	if storedIndex < 0 || storedIndex >= len(s.alternativeTypes) {
		ctx.SetError(DeserializationErrorf("union index out of bounds: %d (max: %d)", storedIndex, len(s.alternativeTypes)-1))
		return
	}

	// Get the alternative type
	altType := s.alternativeTypes[storedIndex]

	// Get serializer for this alternative
	serializer, err := ctx.TypeResolver().getSerializerByType(altType, false)
	if err != nil {
		ctx.SetError(FromError(fmt.Errorf("no serializer for union alternative type %v: %w", altType, err)))
		return
	}

	// In xlang mode, read type info for the alternative
	if ctx.TypeResolver().isXlang {
		dummyValue := reflect.New(altType).Elem()
		_, err := ctx.TypeResolver().ReadTypeInfo(buf, dummyValue)
		if err != nil {
			ctx.SetError(FromError(err))
			return
		}
	}

	// Create a value to hold the alternative data
	altValue := reflect.New(altType).Elem()

	// Read the value data
	serializer.ReadData(ctx, altType, altValue)
	if ctx.HasError() {
		return
	}

	// Get the target struct value
	targetValue := value
	if targetValue.Kind() == reflect.Ptr {
		if targetValue.IsNil() {
			targetValue.Set(reflect.New(targetValue.Type().Elem()))
		}
		targetValue = targetValue.Elem()
	}

	// Set the index field (1-based)
	indexField := targetValue.FieldByName("Idx")
	indexField.SetInt(int64(storedIndex + 1))

	// Set the value pointer field
	fieldName := fmt.Sprintf("V%d", storedIndex+1)
	valueField := targetValue.FieldByName(fieldName)
	// Create a pointer to the value and set it
	ptrValue := reflect.New(altType)
	ptrValue.Elem().Set(altValue)
	valueField.Set(ptrValue)
}

func (s *unionSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, value)
}

// ============================================================================
// Registration Functions
// ============================================================================

// RegisterUnion2Type registers a Union2[T1, T2] type for serialization.
// Returns an error if registration fails.
//
// Example:
//
//	f := fory.NewFory()
//	err := fory.RegisterUnion2Type[int32, string](f)
//	if err != nil {
//	    panic(err)
//	}
func RegisterUnion2Type[T1 any, T2 any](f *Fory) error {
	var zero1 T1
	var zero2 T2

	unionType := reflect.TypeOf(Union2[T1, T2]{})
	alternativeTypes := []reflect.Type{
		reflect.TypeOf(zero1),
		reflect.TypeOf(zero2),
	}

	serializer := newUnionSerializer(unionType, f.typeResolver, alternativeTypes)

	// Register the union type with the serializer
	f.typeResolver.typeToSerializers[unionType] = serializer

	// Also register pointer type
	ptrUnionType := reflect.PtrTo(unionType)
	f.typeResolver.typeToSerializers[ptrUnionType] = &ptrToValueSerializer{
		valueSerializer: serializer,
	}

	return nil
}

// RegisterUnion3Type registers a Union3[T1, T2, T3] type for serialization.
func RegisterUnion3Type[T1 any, T2 any, T3 any](f *Fory) error {
	var zero1 T1
	var zero2 T2
	var zero3 T3

	unionType := reflect.TypeOf(Union3[T1, T2, T3]{})
	alternativeTypes := []reflect.Type{
		reflect.TypeOf(zero1),
		reflect.TypeOf(zero2),
		reflect.TypeOf(zero3),
	}

	serializer := newUnionSerializer(unionType, f.typeResolver, alternativeTypes)

	f.typeResolver.typeToSerializers[unionType] = serializer
	ptrUnionType := reflect.PtrTo(unionType)
	f.typeResolver.typeToSerializers[ptrUnionType] = &ptrToValueSerializer{
		valueSerializer: serializer,
	}

	return nil
}

// RegisterUnion4Type registers a Union4[T1, T2, T3, T4] type for serialization.
func RegisterUnion4Type[T1 any, T2 any, T3 any, T4 any](f *Fory) error {
	var zero1 T1
	var zero2 T2
	var zero3 T3
	var zero4 T4

	unionType := reflect.TypeOf(Union4[T1, T2, T3, T4]{})
	alternativeTypes := []reflect.Type{
		reflect.TypeOf(zero1),
		reflect.TypeOf(zero2),
		reflect.TypeOf(zero3),
		reflect.TypeOf(zero4),
	}

	serializer := newUnionSerializer(unionType, f.typeResolver, alternativeTypes)

	f.typeResolver.typeToSerializers[unionType] = serializer
	ptrUnionType := reflect.PtrTo(unionType)
	f.typeResolver.typeToSerializers[ptrUnionType] = &ptrToValueSerializer{
		valueSerializer: serializer,
	}

	return nil
}
