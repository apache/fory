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

// Union represents a tagged union type that can hold one of several alternative types.
// It's equivalent to Rust's enum, C++'s std::variant, or Python's typing.Union.
//
// The Value field holds the actual value, which must be one of the types specified
// when registering the Union type.
//
// Example usage:
//
//	// Create a union that can hold int32 or string
//	union := fory.Union{Value: int32(42)}
//	// or
//	union := fory.Union{Value: "hello"}
type Union struct {
	Value interface{}
}

// NewUnion creates a new Union with the given value.
func NewUnion(value interface{}) Union {
	return Union{Value: value}
}

// IsNil returns true if the union holds no value.
func (u Union) IsNil() bool {
	return u.Value == nil
}

// unionSerializer serializes Union types.
//
// Serialization format:
// 1. Write variant index (varuint32) - identifies which alternative type is active
// 2. In xlang mode, write type info for the active alternative
// 3. Write the value data using the alternative's serializer
type unionSerializer struct {
	type_               reflect.Type
	alternativeTypes    []reflect.Type
	typeResolver        *TypeResolver
	alternativeTypeInfo []*TypeInfo
}

// newUnionSerializer creates a new serializer for Union types with the specified alternatives.
// The alternativeTypes slice defines the allowed types in order - the index is used as the variant index.
func newUnionSerializer(typeResolver *TypeResolver, alternativeTypes []reflect.Type) *unionSerializer {
	typeInfos := make([]*TypeInfo, len(alternativeTypes))
	return &unionSerializer{
		type_:               reflect.TypeOf(Union{}),
		alternativeTypes:    alternativeTypes,
		typeResolver:        typeResolver,
		alternativeTypeInfo: typeInfos,
	}
}

// findAlternativeIndex finds the index of the type that matches the given value.
// Returns -1 if no match is found.
func (s *unionSerializer) findAlternativeIndex(value reflect.Value) int {
	if !value.IsValid() || (value.Kind() == reflect.Interface && value.IsNil()) {
		return -1
	}

	valueType := value.Type()
	if valueType.Kind() == reflect.Interface {
		valueType = value.Elem().Type()
	}

	for i, altType := range s.alternativeTypes {
		if valueType == altType {
			return i
		}
		// Also check if the value is assignable to the alternative type
		if valueType.AssignableTo(altType) {
			return i
		}
		// For pointer types, check the elem type
		if valueType.Kind() == reflect.Ptr && altType.Kind() == reflect.Ptr {
			if valueType.Elem() == altType.Elem() {
				return i
			}
		}
	}
	return -1
}

func (s *unionSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, value reflect.Value) error {
	buf := ctx.Buffer()

	// Get the Union value
	var union Union
	if value.Kind() == reflect.Ptr {
		if value.IsNil() {
			buf.WriteInt8(NullFlag)
			return nil
		}
		union = value.Elem().Interface().(Union)
	} else {
		union = value.Interface().(Union)
	}

	// Handle null union value
	if union.Value == nil {
		switch refMode {
		case RefModeTracking, RefModeNullOnly:
			buf.WriteInt8(NullFlag)
		}
		return nil
	}

	// Write ref flag for non-null
	switch refMode {
	case RefModeTracking:
		refWritten, err := ctx.RefResolver().WriteRefOrNull(buf, value)
		if err != nil {
			return err
		}
		if refWritten {
			return nil
		}
	case RefModeNullOnly:
		buf.WriteInt8(NotNullValueFlag)
	}

	// Write type info if needed
	if writeType {
		buf.WriteVaruint32Small7(uint32(UNION))
	}

	return s.WriteData(ctx, value)
}

func (s *unionSerializer) WriteData(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()

	// Get the Union value
	var union Union
	if value.Kind() == reflect.Ptr {
		union = value.Elem().Interface().(Union)
	} else {
		union = value.Interface().(Union)
	}

	// Find which alternative type matches the value
	innerValue := reflect.ValueOf(union.Value)
	activeIndex := s.findAlternativeIndex(innerValue)

	if activeIndex < 0 {
		return fmt.Errorf("union value type %T doesn't match any alternative in %v", union.Value, s.alternativeTypes)
	}

	// Write the active variant index
	buf.WriteVaruint32(uint32(activeIndex))

	// Get the serializer for the active alternative
	altType := s.alternativeTypes[activeIndex]
	serializer, err := ctx.TypeResolver().getSerializerByType(altType, false)
	if err != nil {
		return fmt.Errorf("no serializer for union alternative type %v: %w", altType, err)
	}

	// In xlang mode, write type info for the alternative
	if ctx.TypeResolver().isXlang {
		typeInfo, err := ctx.TypeResolver().getTypeInfo(innerValue, true)
		if err != nil {
			return err
		}
		if err := ctx.TypeResolver().WriteTypeInfo(buf, typeInfo); err != nil {
			return err
		}
	}

	// Write the value data
	return serializer.WriteData(ctx, innerValue)
}

func (s *unionSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, value reflect.Value) error {
	buf := ctx.Buffer()

	switch refMode {
	case RefModeTracking:
		refID, err := ctx.RefResolver().TryPreserveRefId(buf)
		if err != nil {
			return err
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
			return nil
		}
	case RefModeNullOnly:
		flag := buf.ReadInt8()
		if flag == NullFlag {
			return nil
		}
	}

	if readType {
		typeId := buf.ReadVaruint32Small7()
		if TypeId(typeId) != UNION {
			return fmt.Errorf("expected UNION type id %d, got %d", UNION, typeId)
		}
	}

	return s.ReadData(ctx, s.type_, value)
}

func (s *unionSerializer) ReadData(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()

	// Read the stored variant index
	storedIndex := buf.ReadVaruint32()

	// Validate index is within bounds
	if int(storedIndex) >= len(s.alternativeTypes) {
		return fmt.Errorf("union index out of bounds: %d (max: %d)", storedIndex, len(s.alternativeTypes)-1)
	}

	// Get the alternative type
	altType := s.alternativeTypes[storedIndex]

	// Get serializer for this alternative
	serializer, err := ctx.TypeResolver().getSerializerByType(altType, false)
	if err != nil {
		return fmt.Errorf("no serializer for union alternative type %v: %w", altType, err)
	}

	// In xlang mode, read type info for the alternative
	if ctx.TypeResolver().isXlang {
		// Read the type info - we need to pass a value for the ReadTypeInfo function
		dummyValue := reflect.New(altType).Elem()
		_, err := ctx.TypeResolver().ReadTypeInfo(buf, dummyValue)
		if err != nil {
			return err
		}
	}

	// Create a value to hold the alternative data
	altValue := reflect.New(altType).Elem()

	// Read the value data
	if err := serializer.ReadData(ctx, altType, altValue); err != nil {
		return err
	}

	// Set the union value
	union := Union{Value: altValue.Interface()}
	if value.Kind() == reflect.Ptr {
		value.Elem().Set(reflect.ValueOf(union))
	} else {
		value.Set(reflect.ValueOf(union))
	}

	return nil
}

func (s *unionSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) error {
	return s.Read(ctx, refMode, false, value)
}

// RegisterUnionType registers a Union type with the specified alternative types.
// The alternative types are the types that the union can hold.
// Returns an error if registration fails.
//
// Example:
//
//	f := fory.NewFory()
//	err := f.RegisterUnionType(reflect.TypeOf(int32(0)), reflect.TypeOf(""))
//	if err != nil {
//	    panic(err)
//	}
func (f *Fory) RegisterUnionType(alternativeTypes ...reflect.Type) error {
	if len(alternativeTypes) == 0 {
		return fmt.Errorf("union must have at least one alternative type")
	}

	unionType := reflect.TypeOf(Union{})
	serializer := newUnionSerializer(f.typeResolver, alternativeTypes)

	// Register the union type with the serializer
	f.typeResolver.typeToSerializers[unionType] = serializer

	// Also register pointer type
	ptrUnionType := reflect.PtrTo(unionType)
	f.typeResolver.typeToSerializers[ptrUnionType] = &ptrToValueSerializer{
		valueSerializer: serializer,
	}

	return nil
}
