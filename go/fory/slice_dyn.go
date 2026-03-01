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

// sliceDynSerializer provides the dynamic slice implementation that inspects
// element values at runtime.
// This serializer is designed for slices with any interface element type
// (e.g., []any, []io.Reader, []fmt.Stringer, or pointers to interfaces).
// For slices with concrete element types, use sliceSerializer instead.
type sliceDynSerializer struct {
	elemType        reflect.Type
	isInterfaceElem bool
	isPointerElem   bool
}

// newSliceDynSerializer creates a new sliceDynSerializer.
// This serializer is ONLY for slices with interface or pointer to interface element types.
// For other slice types, use sliceSerializer instead.
func newSliceDynSerializer(elemType reflect.Type) (sliceDynSerializer, error) {
	// Nil element type is allowed for fully dynamic slices (e.g., []any)
	if elemType == nil {
		return sliceDynSerializer{
			isInterfaceElem: true,
		}, nil
	}
	// Validate element type is interface or pointer to interface
	isInterface := elemType.Kind() == reflect.Interface
	isPointerToInterface := elemType.Kind() == reflect.Ptr && elemType.Elem().Kind() == reflect.Interface
	if !isInterface && !isPointerToInterface {
		return sliceDynSerializer{}, fmt.Errorf(
			"sliceDynSerializer only supports interface or pointer to interface element types, got %v; use sliceSerializer for other types", elemType)
	}
	return sliceDynSerializer{
		elemType:        elemType,
		isInterfaceElem: isInterface,
		isPointerElem:   isPointerToInterface,
	}, nil
}

// mustNewSliceDynSerializer is like newSliceDynSerializer but panics on error.
// Used for initialization code where the element type is known to be valid.
func mustNewSliceDynSerializer(elemType reflect.Type) sliceDynSerializer {
	s, err := newSliceDynSerializer(elemType)
	if err != nil {
		panic(err)
	}
	return s
}

func (s sliceDynSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeSliceRefAndType(ctx, refMode, writeType, value, LIST)
	if done || ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s sliceDynSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	buf := ctx.Buffer()
	// Get slice length and handle empty slice case
	length := value.Len()
	if length == 0 {
		buf.WriteVarUint32(0) // WriteData 0 for empty slice
		return
	}

	// WriteData collection header and get type information
	collectFlag, elemTypeInfo := s.writeHeader(ctx, buf, value)
	if ctx.HasError() {
		return
	}

	// Choose serialization path based on type consistency
	if (collectFlag & CollectionIsSameType) != 0 {
		s.writeSameType(ctx, buf, value, elemTypeInfo, collectFlag) // Optimized path for same-type elements
	} else {
		s.writeDifferentTypes(ctx, buf, value, collectFlag) // Fallback path for mixed-type elements
	}
}

// writeHeader prepares and writes collection metadata including:
// - Collection size
// - Type consistency flags
// - Element type information (if homogeneous)
// Returns pointer to TypeInfo to avoid copy overhead.
func (s sliceDynSerializer) writeHeader(ctx *WriteContext, buf *ByteBuffer, value reflect.Value) (byte, *TypeInfo) {
	collectFlag := CollectionDefaultFlag
	var elemTypeInfo *TypeInfo
	hasNull := false
	hasSameType := true

	// Iterate through elements to check for nulls and type consistency
	var firstType reflect.Type
	var firstElem reflect.Value
	for i := 0; i < value.Len(); i++ {
		elem := value.Index(i).Elem()
		if isNull(elem) {
			hasNull = true
			continue
		}

		// Track first non-null element type
		if firstType == nil {
			firstType = elem.Type()
			firstElem = elem
		} else {
			// Compare each element's type with the first element's type
			if firstType != elem.Type() {
				hasSameType = false
			}
		}
	}
	// Only get elemTypeInfo if all elements have same type
	if hasSameType && firstElem.IsValid() {
		elemTypeInfo, _ = ctx.TypeResolver().getTypeInfo(firstElem, true)
	}

	// Set collection flags based on findings
	if hasNull {
		collectFlag |= CollectionHasNull // Mark if collection contains null values
	}
	if hasSameType {
		collectFlag |= CollectionIsSameType // Mark if elements have same types
	}

	// Enable reference tracking if configured and element type supports it
	if ctx.TrackRef() && (elemTypeInfo == nil || elemTypeInfo.NeedWriteRef) {
		collectFlag |= CollectionTrackingRef
	}

	// WriteData metadata to buffer
	buf.WriteVarUint32(uint32(value.Len())) // Collection size
	buf.WriteInt8(int8(collectFlag))        // Collection flags

	// WriteData element type info if all elements have same type and not using declared type
	if hasSameType && (collectFlag&CollectionIsDeclElementType == 0) && elemTypeInfo != nil {
		ctx.TypeResolver().WriteTypeInfo(buf, elemTypeInfo, ctx.Err())
	}

	return byte(collectFlag), elemTypeInfo
}

// writeSameType efficiently serializes a slice where all elements share the same type
func (s sliceDynSerializer) writeSameType(
	ctx *WriteContext, buf *ByteBuffer, value reflect.Value, typeInfo *TypeInfo, flag byte) {
	if typeInfo == nil {
		return
	}
	serializer := typeInfo.Serializer
	trackRefs := (flag & CollectionTrackingRef) != 0 // Check if reference tracking is enabled
	hasNull := (flag & CollectionHasNull) != 0

	for i := 0; i < value.Len(); i++ {
		elem := value.Index(i).Elem()
		if trackRefs {
			// serializer.Write handles null via ref tracking
			serializer.Write(ctx, RefModeTracking, false, false, elem)
		} else if hasNull {
			// Check null only when hasNull flag is set
			if isNull(elem) {
				buf.WriteInt8(NullFlag)
				continue
			}
			buf.WriteInt8(NotNullValueFlag)
			serializer.WriteData(ctx, elem)
		} else {
			// No ref tracking and no nulls: directly write data
			serializer.WriteData(ctx, elem)
		}
		if ctx.HasError() {
			return
		}
	}
}

// writeDifferentTypes handles serialization of slices with mixed element types
func (s sliceDynSerializer) writeDifferentTypes(ctx *WriteContext, buf *ByteBuffer, value reflect.Value, flag byte) {
	trackRefs := (flag & CollectionTrackingRef) != 0
	hasNull := (flag & CollectionHasNull) != 0

	for i := 0; i < value.Len(); i++ {
		elem := value.Index(i).Elem()
		if trackRefs {
			// WriteRefOrNull handles null via ref tracking
			refWritten, err := ctx.RefResolver().WriteRefOrNull(buf, elem)
			if err != nil {
				ctx.SetError(FromError(err))
				return
			}
			if !refWritten {
				typeInfo, err := ctx.TypeResolver().getTypeInfo(elem, true)
				if err != nil {
					ctx.SetError(FromError(err))
					return
				}
				ctx.TypeResolver().WriteTypeInfo(buf, typeInfo, ctx.Err())
				typeInfo.Serializer.WriteData(ctx, elem)
			}
		} else if hasNull {
			// Check null only when hasNull flag is set
			if isNull(elem) {
				buf.WriteInt8(NullFlag)
				continue
			}
			buf.WriteInt8(NotNullValueFlag)
			typeInfo, err := ctx.TypeResolver().getTypeInfo(elem, true)
			if err != nil {
				ctx.SetError(FromError(err))
				return
			}
			ctx.TypeResolver().WriteTypeInfo(buf, typeInfo, ctx.Err())
			typeInfo.Serializer.WriteData(ctx, elem)
		} else {
			// No ref tracking and no nulls - write type + data directly
			typeInfo, err := ctx.TypeResolver().getTypeInfo(elem, true)
			if err != nil {
				ctx.SetError(FromError(err))
				return
			}
			ctx.TypeResolver().WriteTypeInfo(buf, typeInfo, ctx.Err())
			typeInfo.Serializer.WriteData(ctx, elem)
		}
		if ctx.HasError() {
			return
		}
	}
}

func (s sliceDynSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
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

func (s sliceDynSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	length := int(buf.ReadVarUint32(ctxErr))
	ctx.checkCollectionSize(length)
	if ctx.HasError() {
		return
	}
	sliceType := value.Type()
	value.Set(reflect.MakeSlice(sliceType, length, length))
	if length == 0 {
		return
	}

	collectFlag := buf.ReadInt8(ctxErr)
	ctx.RefResolver().Reference(value)

	var elemTypeInfo *TypeInfo
	var elemType reflect.Type
	var elemSerializer Serializer
	if (collectFlag & CollectionIsSameType) != 0 {
		if (collectFlag & CollectionIsDeclElementType) == 0 {
			elemTypeInfo = ctx.TypeResolver().ReadTypeInfo(buf, ctxErr)
		}
		if elemTypeInfo != nil && elemTypeInfo.Serializer != nil {
			elemType = elemTypeInfo.Type
			elemSerializer = elemTypeInfo.Serializer
		} else {
			// When CollectionIsDeclElementType is set, get serializer from the declared element type
			elemType = sliceType.Elem()
			elemSerializer, _ = ctx.TypeResolver().getSerializerByType(elemType, false)
		}
		s.readSameType(ctx, buf, value, elemType, elemSerializer, collectFlag)
		return
	}
	s.readDifferentTypes(ctx, buf, value, collectFlag)
}

func (s sliceDynSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	// typeInfo is already read, don't read it again
	s.Read(ctx, refMode, false, false, value)
}

// readSameType handles deserialization of slices where all elements share the same type
func (s sliceDynSerializer) readSameType(ctx *ReadContext, buf *ByteBuffer, value reflect.Value, elemType reflect.Type, serializer Serializer, flag int8) {
	trackRefs := (flag & CollectionTrackingRef) != 0
	hasNull := (flag & CollectionHasNull) != 0
	ctxErr := ctx.Err()
	if serializer == nil {
		ctx.SetError(FromError(fmt.Errorf("no serializer available for element type %v", elemType)))
		return
	}

	// Wrap serializer to produce pointers if needed for interface implementation
	elemType, serializer = s.wrapSerializerIfNeeded(elemType, serializer)

	// Check if element is a named struct type (needs pointer for circular ref support)
	isNamedStruct := false
	if _, ok := serializer.(*structSerializer); ok && elemType.Kind() == reflect.Struct {
		isNamedStruct = true
	}

	for i := 0; i < value.Len(); i++ {
		if trackRefs {
			refID, refErr := ctx.RefResolver().TryPreserveRefId(buf)
			if refErr != nil {
				ctx.SetError(FromError(refErr))
				return
			}
			if refID == int32(NullFlag) {
				continue
			}
			// Handle RefFlag - element references a previously read object
			if refID < int32(NotNullValueFlag) {
				obj := ctx.RefResolver().GetReadObject(refID)
				if obj.IsValid() {
					value.Index(i).Set(obj)
				}
				continue
			}

			// For named struct types, use pointer for circular reference support
			var elem reflect.Value
			if isNamedStruct {
				// Create pointer to struct: *B
				elem = reflect.New(elemType)
				// Register reference BEFORE reading data for circular ref support
				ctx.RefResolver().SetReadObject(refID, elem)
				// Read into the struct element
				serializer.ReadData(ctx, elem.Elem())
			} else {
				elem = reflect.New(elemType).Elem()
				serializer.ReadData(ctx, elem)
				ctx.RefResolver().Reference(elem)
			}
			value.Index(i).Set(elem)
		} else if hasNull {
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				continue
			}
			elem := reflect.New(elemType).Elem()
			serializer.ReadData(ctx, elem)
			value.Index(i).Set(elem)
		} else {
			elem := reflect.New(elemType).Elem()
			serializer.ReadData(ctx, elem)
			value.Index(i).Set(elem)
		}
		if ctx.HasError() {
			return
		}
	}
}

// readDifferentTypes handles deserialization of slices with mixed element types
func (s sliceDynSerializer) readDifferentTypes(
	ctx *ReadContext, buf *ByteBuffer, value reflect.Value, flag int8) {
	trackRefs := (flag & CollectionTrackingRef) != 0
	hasNull := (flag & CollectionHasNull) != 0
	ctxErr := ctx.Err()

	for i := 0; i < value.Len(); i++ {
		if trackRefs {
			refID, refErr := ctx.RefResolver().TryPreserveRefId(buf)
			if refErr != nil {
				ctx.SetError(FromError(refErr))
				return
			}
			if refID == int32(NullFlag) {
				continue
			}
			if refID < int32(NotNullValueFlag) {
				// Reference to existing object
				obj := ctx.RefResolver().GetReadObject(refID)
				if obj.IsValid() {
					value.Index(i).Set(obj)
				}
				continue
			}
			typeInfo := ctx.TypeResolver().ReadTypeInfo(buf, ctxErr)
			if ctxErr.HasError() {
				return
			}
			elemType, serializer := s.wrapSerializerIfNeeded(typeInfo.Type, typeInfo.Serializer)
			elem := reflect.New(elemType).Elem()
			serializer.ReadData(ctx, elem)
			ctx.RefResolver().SetReadObject(refID, elem)
			value.Index(i).Set(elem)
		} else {
			if hasNull {
				headFlag := buf.ReadInt8(ctxErr)
				if headFlag == NullFlag {
					continue
				}
			}
			typeInfo := ctx.TypeResolver().ReadTypeInfo(buf, ctxErr)
			if ctxErr.HasError() {
				return
			}
			elemType, serializer := s.wrapSerializerIfNeeded(typeInfo.Type, typeInfo.Serializer)
			elem := reflect.New(elemType).Elem()
			serializer.ReadData(ctx, elem)
			value.Index(i).Set(elem)
		}
		if ctx.HasError() {
			return
		}
	}
}

// wrapSerializerIfNeeded wraps the serializer with ptrToValueSerializer if:
//  1. Slice element type is pointer-to-interface and the deserialized type is not a pointer, OR
//  2. Slice element type is interface and the deserialized type doesn't directly implement it
//     but the pointer type does (common case where interface has pointer receivers)
func (s sliceDynSerializer) wrapSerializerIfNeeded(elemType reflect.Type, serializer Serializer) (reflect.Type, Serializer) {
	if elemType.Kind() == reflect.Ptr {
		return elemType, serializer
	}
	// Check if we need pointer wrapper for isPointerElem or interface implementation
	needsPointer := s.isPointerElem ||
		(s.isInterfaceElem && s.elemType != nil && !elemType.AssignableTo(s.elemType))
	if needsPointer {
		return reflect.PtrTo(elemType), &ptrToValueSerializer{valueSerializer: serializer}
	}
	return elemType, serializer
}
