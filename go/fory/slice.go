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

const (
	CollectionDefaultFlag       = 0b0000
	CollectionTrackingRef       = 0b0001
	CollectionHasNull           = 0b0010
	CollectionIsDeclElementType = 0b0100
	CollectionIsSameType        = 0b1000
	CollectionDeclSameType      = CollectionIsSameType | CollectionIsDeclElementType
)

// writeSliceRefAndType handles reference and type writing for slice serializers.
// Returns true if the value was already written (nil or ref), false if data should be written.
func writeSliceRefAndType(ctx *WriteContext, refMode RefMode, writeType bool, value reflect.Value, typeId TypeId) bool {
	switch refMode {
	case RefModeTracking:
		if value.Kind() == reflect.Slice && value.IsNil() {
			ctx.Buffer().WriteInt8(NullFlag)
			return true
		}
		refWritten, err := ctx.RefResolver().WriteRefOrNull(ctx.Buffer(), value)
		if err != nil {
			ctx.SetError(FromError(err))
			return true
		}
		if refWritten {
			return true
		}
	case RefModeNullOnly:
		if value.Kind() == reflect.Slice && value.IsNil() {
			ctx.Buffer().WriteInt8(NullFlag)
			return true
		}
		ctx.Buffer().WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.Buffer().WriteUint8(uint8(typeId))
	}
	return false
}

// readSliceRefAndType handles reference and type reading for slice serializers.
// Returns (true, 0) if a reference was resolved (value already set).
// Returns (false, typeId) if data should be written and typeId was read (if readType=true).
func readSliceRefAndType(ctx *ReadContext, refMode RefMode, readType bool, value reflect.Value) (bool, uint32) {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	switch refMode {
	case RefModeTracking:
		refID, refErr := ctx.RefResolver().TryPreserveRefId(buf)
		if refErr != nil {
			ctx.SetError(FromError(refErr))
			return true, 0
		}
		if refID < int32(NotNullValueFlag) {
			obj := ctx.RefResolver().GetReadObject(refID)
			if obj.IsValid() {
				value.Set(obj)
			}
			return true, 0
		}
	case RefModeNullOnly:
		flag := buf.ReadInt8(ctxErr)
		if flag == NullFlag {
			return true, 0
		}
	}
	var typeId uint32
	if readType {
		typeId = uint32(buf.ReadUint8(ctxErr))
	}
	return false, typeId
}

// Helper function to check if a value is null/nil
func isNull(v reflect.Value) bool {
	// Zero value (Invalid kind) is considered null
	if !v.IsValid() {
		return true
	}
	switch v.Kind() {
	case reflect.Ptr, reflect.Interface, reflect.Slice, reflect.Map, reflect.Func:
		return v.IsNil() // Check if reference types are nil
	default:
		return false // Value types are never null
	}
}

// sliceSerializer serialize a slice whose elem is not an interface or pointer to interface.
// Use newSliceSerializer to create instances with proper type validation.
// This serializer uses LIST protocol for non-primitive element types.
type sliceSerializer struct {
	type_          reflect.Type
	elemSerializer Serializer
	referencable   bool
}

// newSliceSerializer creates a sliceSerializer for slices with concrete element types.
// It returns an error if the element type is an interface, pointer to interface, or a primitive type.
// Primitive numeric types (bool, int8, int16, int32, int64, uint8, float32, float64) must use
// dedicated primitive slice serializers that use ARRAY protocol (binary size + binary).
func newSliceSerializer(type_ reflect.Type, elemSerializer Serializer, xlang bool) (*sliceSerializer, error) {
	elem := type_.Elem()
	if elem.Kind() == reflect.Interface {
		return nil, fmt.Errorf("sliceSerializer does not support interface element type: %v", type_)
	}
	if elem.Kind() == reflect.Ptr && elem.Elem().Kind() == reflect.Interface {
		return nil, fmt.Errorf("sliceSerializer does not support pointer to interface element type: %v", type_)
	}
	// Primitive numeric types must use dedicated primitive slice serializers (ARRAY protocol)
	switch elem.Kind() {
	case reflect.Bool, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
		reflect.Uint8, reflect.Float32, reflect.Float64:
		return nil, fmt.Errorf("sliceSerializer does not support primitive element type %v: use dedicated primitive slice serializer", type_)
	}
	return &sliceSerializer{
		type_:          type_,
		elemSerializer: elemSerializer,
		referencable:   isRefType(elem, xlang),
	}, nil
}

func (s *sliceSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	s.writeDataWithGenerics(ctx, value, false)
}

func (s *sliceSerializer) writeDataWithGenerics(ctx *WriteContext, value reflect.Value, hasGenerics bool) {
	length := value.Len()
	buf := ctx.Buffer()

	// WriteData length
	buf.WriteVarUint32(uint32(length))
	if length == 0 {
		return
	}

	// Determine collection flags
	// When hasGenerics is true, element type is known from TypeDef, use CollectionDeclSameType
	// When hasGenerics is false, write element type info, use CollectionIsSameType
	var collectFlag int
	if hasGenerics {
		collectFlag = CollectionDeclSameType
	} else {
		collectFlag = CollectionIsSameType
	}
	hasNull := false
	elemType := s.type_.Elem()
	isPointerElem := elemType.Kind() == reflect.Ptr

	// Check for null values first (only applicable for pointer element types)
	if isPointerElem {
		for i := 0; i < length; i++ {
			elem := value.Index(i)
			if elem.IsNil() {
				hasNull = true
				break
			}
		}
	}

	if hasNull {
		collectFlag |= CollectionHasNull
	}
	trackRefs := ctx.TrackRef() && s.referencable
	if trackRefs {
		collectFlag |= CollectionTrackingRef
	}
	buf.WriteInt8(int8(collectFlag))

	// Write element type info for deserialization only when hasGenerics is false
	// When hasGenerics is true, the element type is known from the struct's TypeDef
	if !hasGenerics {
		elemTypeInfo, _ := ctx.TypeResolver().getTypeInfo(reflect.New(elemType).Elem(), false)
		ctx.TypeResolver().WriteTypeInfo(buf, elemTypeInfo, ctx.Err())
	}

	// WriteData elements
	trackRefs = (collectFlag & CollectionTrackingRef) != 0
	elemRefMode := RefModeNone
	if trackRefs {
		elemRefMode = RefModeTracking
	}

	// Serialize elements with ref tracking or nulls handling
	for i := 0; i < length; i++ {
		elem := value.Index(i)

		// Handle null values (only for pointer element types)
		if hasNull && elem.IsNil() {
			if trackRefs {
				// When tracking refs, the element serializer will write the null flag
				s.elemSerializer.Write(ctx, elemRefMode, false, false, elem)
			} else {
				buf.WriteInt8(NullFlag)
			}
			continue
		}

		if trackRefs {
			// Use Write with ref tracking enabled
			// The element serializer will handle writing ref flags
			s.elemSerializer.Write(ctx, elemRefMode, false, false, elem)
		} else if hasNull {
			// When hasNull is set but trackRefs is not, write NotNullValueFlag before data
			buf.WriteInt8(NotNullValueFlag)
			s.elemSerializer.WriteData(ctx, elem)
		} else {
			// No ref tracking and no nulls: directly write data
			s.elemSerializer.WriteData(ctx, elem)
		}
		if ctx.HasError() {
			return
		}
	}
}

func (s *sliceSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeSliceRefAndType(ctx, refMode, writeType, value, LIST)
	if done || ctx.HasError() {
		return
	}
	s.writeDataWithGenerics(ctx, value, hasGenerics)
}

func (s *sliceSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done, typeId := readSliceRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	if readType && typeId != uint32(LIST) {
		ctx.SetError(DeserializationErrorf("slice type mismatch: expected LIST (%d), got %d", LIST, typeId))
		return
	}
	s.ReadData(ctx, value)
}

func (s *sliceSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	// typeInfo is already read, don't read it again
	s.Read(ctx, refMode, false, false, value)
}

func (s *sliceSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	length := int(buf.ReadVarUint32(ctxErr))
	ctx.checkCollectionSize(length) 
    if ctx.HasError() { return } 
	isArrayType := value.Type().Kind() == reflect.Array

	if length == 0 {
		if !isArrayType {
			value.Set(reflect.MakeSlice(value.Type(), 0, 0))
		}
		return
	}

	// ReadData collection flags
	collectFlag := buf.ReadInt8(ctxErr)

	// ReadData element type info if present in buffer
	// We must consume these bytes for protocol compliance
	if (collectFlag & CollectionIsSameType) != 0 {
		if (collectFlag & CollectionIsDeclElementType) == 0 {
			ctx.TypeResolver().ReadTypeInfo(buf, ctxErr)
		}
	}

	trackRefs := (collectFlag & CollectionTrackingRef) != 0
	hasNull := (collectFlag & CollectionHasNull) != 0

	// Handle slice vs array allocation
	if isArrayType {
		// For arrays, verify the length matches (arrays have fixed size)
		if value.Len() < length {
			ctx.SetError(FromError(fmt.Errorf("array length %d is smaller than serialized length %d", value.Len(), length)))
			return
		}
	} else {
		// For slices, allocate or resize as needed
		if value.Cap() < length {
			value.Set(reflect.MakeSlice(value.Type(), length, length))
		} else if value.Len() < length {
			value.Set(value.Slice(0, length))
		}
	}
	ctx.RefResolver().Reference(value)

	elemRefMode := RefModeNone
	if trackRefs {
		elemRefMode = RefModeTracking
	}

	// Slow path: general deserialization with ref tracking or nulls
	for i := 0; i < length; i++ {
		elem := value.Index(i)

		if trackRefs {
			// When trackRefs is true, elemSerializer will read the ref flag via TryPreserveRefId
			// For pointer types, elemSerializer will handle allocation and reference tracking
			s.elemSerializer.Read(ctx, elemRefMode, false, false, elem)
		} else if hasNull {
			// When hasNull is set, read a flag byte for each element:
			// - NullFlag (-3) for null elements
			// - NotNullValueFlag (-1) + data for non-null elements
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				// Element is null, leave slice element as nil (zero value)
				continue
			}
			// refFlag should be NotNullValueFlag, now read the actual data
			s.elemSerializer.ReadData(ctx, elem)
		} else {
			// No ref tracking and no nulls: directly read data
			s.elemSerializer.ReadData(ctx, elem)
		}
		if ctx.HasError() {
			return
		}
	}
}
