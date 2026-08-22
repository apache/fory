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
// sliceDynSerializer is pointer-owned because serializers are reused configuration objects;
// pointer receivers avoid copying cached element budget/type state on hot read/write paths.
type sliceDynSerializer struct {
	elemType                 reflect.Type
	declaredElemType         reflect.Type
	declaredElemSerializer   Serializer
	declaredElemTypeInfo     *TypeInfo
	elemDeclType             bool
	declaredElemReferencable bool
	elemBytes                int
	declaredElemBytes        int
	maxLength                int64
}

// newSliceDynSerializer creates a new sliceDynSerializer.
// This serializer is ONLY for slices with interface or pointer to interface element types.
// For other slice types, use sliceSerializer instead.
func newSliceDynSerializer(elemType reflect.Type) (*sliceDynSerializer, error) {
	// Nil element type is allowed for fully dynamic slices (e.g., []any)
	if elemType == nil {
		elemBytes := graphSizeOf[any]()
		return &sliceDynSerializer{
			declaredElemType: elemType,
			elemBytes:        elemBytes,
			maxLength:        maxGraphCount(elemBytes),
		}, nil
	}
	// Validate element type is interface or pointer to interface
	isInterface := elemType.Kind() == reflect.Interface
	isPointerToInterface := elemType.Kind() == reflect.Ptr && elemType.Elem().Kind() == reflect.Interface
	if !isInterface && !isPointerToInterface {
		return nil, fmt.Errorf(
			"sliceDynSerializer only supports interface or pointer to interface element types, got %v; use sliceSerializer for other types", elemType)
	}
	elemBytes := int(elemType.Size())
	return &sliceDynSerializer{
		elemType:         elemType,
		declaredElemType: elemType,
		elemBytes:        elemBytes,
		maxLength:        maxGraphCount(elemBytes),
	}, nil
}

// mustNewSliceDynSerializer is like newSliceDynSerializer but panics on error.
// Used for initialization code where the element type is known to be valid.
func mustNewSliceDynSerializer(elemType reflect.Type) *sliceDynSerializer {
	s, err := newSliceDynSerializer(elemType)
	if err != nil {
		panic(err)
	}
	return s
}

func (s *sliceDynSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeSliceRefAndType(ctx, refMode, writeType, value, LIST)
	if done || ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s *sliceDynSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	buf := ctx.Buffer()
	// Get slice length and handle empty slice case
	length := value.Len()
	if length == 0 {
		buf.WriteVarUint32(0) // WriteData 0 for empty slice
		return
	}

	// WriteData collection header and get type information
	var collectFlag byte
	var elemTypeInfo *TypeInfo
	serializer := Serializer(nil)
	if s.declaredElemSerializer != nil {
		collectFlag, elemTypeInfo = s.writeDeclaredHeader(ctx, buf, value)
		serializer = s.declaredElemSerializer
	} else {
		collectFlag, elemTypeInfo = s.writeHeader(ctx, buf, value)
		if elemTypeInfo != nil {
			serializer = elemTypeInfo.Serializer
		}
	}
	if ctx.HasError() {
		return
	}

	// Choose serialization path based on type consistency
	if (collectFlag & CollectionIsSameType) != 0 {
		if s.declaredElemSerializer != nil && serializerNeedsGenericDispatch(serializer) {
			s.writeDeclaredSameType(ctx, buf, value, serializer, collectFlag)
			return
		}
		s.writeSameType(ctx, buf, value, serializer, collectFlag) // Optimized path for same-type elements
	} else {
		s.writeDifferentTypes(ctx, buf, value, collectFlag) // Fallback path for mixed-type elements
	}
}

// writeHeader prepares and writes collection metadata including:
// - Collection size
// - Type consistency flags
// - Element type information (if homogeneous)
// Returns pointer to TypeInfo to avoid copy overhead.
func (s *sliceDynSerializer) writeHeader(ctx *WriteContext, buf *ByteBuffer, value reflect.Value) (byte, *TypeInfo) {
	collectFlag := CollectionDefaultFlag
	var elemTypeInfo *TypeInfo
	hasNull := false
	hasSameType := true
	ctxErr := ctx.Err()

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
		var typeErr error
		elemTypeInfo, typeErr = ctx.TypeResolver().GetTypeInfo(firstElem, true)
		if typeErr != nil {
			ctxErr.SetError(typeErr)
			return CollectionDefaultFlag, nil
		}
	}
	if hasSameType && elemTypeInfo == nil {
		hasSameType = false
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
		ctx.TypeResolver().WriteTypeInfo(buf, elemTypeInfo, ctxErr)
	}

	return byte(collectFlag), elemTypeInfo
}

func (s *sliceDynSerializer) writeDeclaredSameType(
	ctx *WriteContext,
	buf *ByteBuffer,
	value reflect.Value,
	serializer Serializer,
	flag byte,
) {
	trackRefs := (flag & CollectionTrackingRef) != 0
	hasNull := (flag & CollectionHasNull) != 0
	for i := 0; i < value.Len(); i++ {
		elem := value.Index(i).Elem()
		if trackRefs {
			serializer.Write(ctx, RefModeTracking, false, true, elem)
		} else if hasNull {
			if isNull(elem) {
				buf.WriteInt8(NullFlag)
				continue
			}
			buf.WriteInt8(NotNullValueFlag)
			serializer.Write(ctx, RefModeNone, false, true, elem)
		} else {
			serializer.Write(ctx, RefModeNone, false, true, elem)
		}
		if ctx.HasError() {
			return
		}
	}
}

func (s *sliceDynSerializer) writeDeclaredHeader(
	ctx *WriteContext,
	buf *ByteBuffer,
	value reflect.Value,
) (byte, *TypeInfo) {
	ctxErr := ctx.Err()
	collectFlag := byte(CollectionIsSameType)
	for i := 0; i < value.Len(); i++ {
		if isNull(value.Index(i).Elem()) {
			collectFlag |= CollectionHasNull
			break
		}
	}
	if s.elemDeclType {
		collectFlag |= CollectionIsDeclElementType
	} else if s.declaredElemTypeInfo == nil {
		ctxErr.SetError(SerializationError("declared collection element TypeInfo is unavailable"))
		return CollectionDefaultFlag, nil
	}
	if ctx.TrackRef() && s.declaredElemReferencable {
		collectFlag |= CollectionTrackingRef
	}
	buf.WriteVarUint32(uint32(value.Len()))
	buf.WriteInt8(int8(collectFlag))
	if !s.elemDeclType {
		ctx.TypeResolver().WriteTypeInfo(buf, s.declaredElemTypeInfo, ctxErr)
	}
	return collectFlag, s.declaredElemTypeInfo
}

// writeSameType efficiently serializes a slice where all elements share the same type
func (s *sliceDynSerializer) writeSameType(ctx *WriteContext, buf *ByteBuffer, value reflect.Value, serializer Serializer, flag byte) {
	if serializer == nil {
		return
	}
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
func (s *sliceDynSerializer) writeDifferentTypes(ctx *WriteContext, buf *ByteBuffer, value reflect.Value, flag byte) {
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
				typeInfo, err := ctx.TypeResolver().GetTypeInfo(elem, true)
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
			typeInfo, err := ctx.TypeResolver().GetTypeInfo(elem, true)
			if err != nil {
				ctx.SetError(FromError(err))
				return
			}
			ctx.TypeResolver().WriteTypeInfo(buf, typeInfo, ctx.Err())
			typeInfo.Serializer.WriteData(ctx, elem)
		} else {
			// No ref tracking and no nulls - write type + data directly
			typeInfo, err := ctx.TypeResolver().GetTypeInfo(elem, true)
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

func (s *sliceDynSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
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

func (s *sliceDynSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	s.readData(ctx, value, -1)
}

func (s *sliceDynSerializer) readData(ctx *ReadContext, value reflect.Value, expectedLength int) {
	if ctx.HasError() || !ctx.enterDepth() {
		return
	}
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	length := ctx.ReadCollectionLength()
	sliceType := value.Type()
	if ctx.HasError() {
		return
	}
	if expectedLength >= 0 && length != expectedLength {
		ctx.SetError(DeserializationErrorf("array length %d does not match serialized length %d", expectedLength, length))
		return
	}
	allocatedByCaller := expectedLength >= 0
	if !allocatedByCaller {
		if length < 0 {
			ctx.SetError(DeserializationErrorf("negative graph element count: %d", length))
			return
		}
		if int64(length) > s.maxLength {
			ctx.SetError(DeserializationErrorf("graph memory estimate overflows: length=%d elementBytes=%d", length, s.elemBytes))
			return
		}
		if !ctx.ReserveGraphMemory(int64(graphSliceOwnerBytes) + int64(length)*int64(s.elemBytes)) {
			return
		}
	}
	if length == 0 {
		if !allocatedByCaller {
			value.Set(reflect.MakeSlice(sliceType, 0, 0))
			ctx.RefResolver().Reference(value)
		}
		ctx.decDepth()
		return
	}

	collectFlag := buf.ReadInt8(ctxErr)
	if ctx.HasError() {
		return
	}

	var elemTypeInfo *TypeInfo
	var elemType reflect.Type
	var elemSerializer Serializer
	elemValueBytes := 0
	if (collectFlag & CollectionIsSameType) != 0 {
		if (collectFlag & CollectionIsDeclElementType) == 0 {
			if s.declaredElemSerializer != nil {
				ctx.TypeResolver().consumeTypeInfoForCodec(buf, ctxErr)
			} else {
				elemTypeInfo = ctx.TypeResolver().ReadTypeInfo(buf, ctxErr)
			}
		}
		if s.declaredElemSerializer != nil {
			elemType = s.declaredElemType
			elemSerializer = s.declaredElemSerializer
			elemValueBytes = s.declaredElemBytes
			// An explicitly selected element codec owns the body. The TypeInfo read
			// from the header supplies wire identity only and must not replace it.
		} else if elemTypeInfo != nil && elemTypeInfo.Serializer != nil {
			elemType = elemTypeInfo.Type
			elemSerializer = elemTypeInfo.Serializer
			elemValueBytes = elemTypeInfo.ValueBytes
		}
		if ctx.HasError() {
			return
		}
		elementReadAlwaysAdvances := (collectFlag&(CollectionTrackingRef|CollectionHasNull)) != 0 ||
			serializerReadDataAlwaysAdvances(elemSerializer)
		if !allocatedByCaller {
			if elementReadAlwaysAdvances {
				if !buf.CheckReadable(length, ctxErr) {
					return
				}
			} else if !checkUnbackedContainerAllocation(ctx, length) {
				return
			}
		}
		if !allocatedByCaller {
			value.Set(reflect.MakeSlice(sliceType, length, length))
			ctx.RefResolver().Reference(value)
		}
		s.readSameType(ctx, buf, value, elemType, elemSerializer, elemValueBytes, collectFlag, length)
		if ctx.HasError() {
			return
		}
		ctx.decDepth()
		return
	}
	if !buf.CheckReadable(length, ctxErr) {
		return
	}
	if !allocatedByCaller {
		value.Set(reflect.MakeSlice(sliceType, length, length))
		ctx.RefResolver().Reference(value)
	}
	s.readDifferentTypes(ctx, buf, value, collectFlag, length)
	if ctx.HasError() {
		return
	}
	ctx.decDepth()
}

func (s *sliceDynSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	// typeInfo is already read, don't read it again
	s.Read(ctx, refMode, false, false, value)
}

// readSameType handles deserialization of slices where all elements share the same type
func (s *sliceDynSerializer) readSameType(ctx *ReadContext, buf *ByteBuffer, value reflect.Value, elemType reflect.Type, serializer Serializer, valueBytes int, flag int8, length int) {
	trackRefs := (flag & CollectionTrackingRef) != 0
	hasNull := (flag & CollectionHasNull) != 0
	ctxErr := ctx.Err()
	if serializer == nil {
		ctx.SetError(FromError(fmt.Errorf("no serializer available for element type %v", elemType)))
		return
	}

	// Wrap serializer to produce pointers if needed for interface implementation
	elemType, serializer = s.wrapSerializerIfNeeded(ctx, elemType, serializer, valueBytes)
	if ctx.HasError() {
		return
	}
	// Check if element is a named struct type (needs pointer for circular ref support)
	isNamedStruct := false
	if _, ok := serializer.(*structSerializer); ok && elemType.Kind() == reflect.Struct {
		isNamedStruct = true
	}
	boxedStructBytes := int64(0)
	if elemType.Kind() == reflect.Struct {
		if _, pointerOwner := serializer.(*ptrToValueSerializer); !pointerOwner {
			if valueBytes > 0 {
				boxedStructBytes = int64(valueBytes)
			} else if structSer, ok := serializer.(*structSerializer); ok {
				boxedStructBytes = int64(structSer.valueBytes)
			}
		}
	}
	if !trackRefs && !hasNull && !serializerReadDataAlwaysAdvances(serializer) {
		checkpoint := buf.logicalReaderIndex()
		for i := 0; i < length; i++ {
			if boxedStructBytes > 0 && !ctx.ReserveGraphMemory(boxedStructBytes) {
				return
			}
			elem := reflect.New(elemType).Elem()
			serializer.ReadData(ctx, elem)
			if ctx.HasError() {
				return
			}
			value.Index(i).Set(elem)
			if (i+1)&(unbackedContainerCheckInterval-1) == 0 {
				if !ctx.settleUnbackedContainerItems(unbackedContainerCheckInterval, checkpoint) {
					return
				}
				checkpoint = buf.logicalReaderIndex()
			}
		}
		if tail := length & (unbackedContainerCheckInterval - 1); tail != 0 {
			if !ctx.settleUnbackedContainerItems(tail, checkpoint) {
				return
			}
		}
		return
	}

	for i := 0; i < length; i++ {
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
				if !assignReadRef(ctx, refID, value.Index(i)) {
					return
				}
				continue
			}

			// For named struct types, use pointer for circular reference support
			var elem reflect.Value
			if boxedStructBytes > 0 && !ctx.ReserveGraphMemory(boxedStructBytes) {
				return
			}
			if isNamedStruct {
				// Create pointer to struct: *B
				elem = reflect.New(elemType)
				// Register reference BEFORE reading data for circular ref support
				if !publishReadRef(ctx, refID, elem) {
					return
				}
				// Read into the struct element
				serializer.ReadData(ctx, elem.Elem())
			} else {
				elem = reflect.New(elemType).Elem()
				serializer.ReadData(ctx, elem)
				ctx.RefResolver().Reference(elem)
			}
			if ctx.HasError() {
				return
			}
			value.Index(i).Set(elem)
		} else if hasNull {
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				continue
			}
			if boxedStructBytes > 0 && !ctx.ReserveGraphMemory(boxedStructBytes) {
				return
			}
			elem := reflect.New(elemType).Elem()
			serializer.ReadData(ctx, elem)
			if ctx.HasError() {
				return
			}
			value.Index(i).Set(elem)
		} else {
			if boxedStructBytes > 0 && !ctx.ReserveGraphMemory(boxedStructBytes) {
				return
			}
			elem := reflect.New(elemType).Elem()
			serializer.ReadData(ctx, elem)
			if ctx.HasError() {
				return
			}
			value.Index(i).Set(elem)
		}
	}
}

// readDifferentTypes handles deserialization of slices with mixed element types
func (s *sliceDynSerializer) readDifferentTypes(
	ctx *ReadContext, buf *ByteBuffer, value reflect.Value, flag int8, length int) {
	trackRefs := (flag & CollectionTrackingRef) != 0
	hasNull := (flag & CollectionHasNull) != 0
	ctxErr := ctx.Err()

	for i := 0; i < length; i++ {
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
				if !assignReadRef(ctx, refID, value.Index(i)) {
					return
				}
				continue
			}
			typeInfo := ctx.TypeResolver().ReadTypeInfo(buf, ctxErr)
			if ctxErr.HasError() {
				return
			}
			elemType, serializer := s.wrapSerializerIfNeeded(
				ctx, typeInfo.Type, typeInfo.Serializer, typeInfo.ValueBytes)
			if ctx.HasError() {
				return
			}
			boxedStructBytes := int64(0)
			if elemType.Kind() == reflect.Struct {
				if _, pointerOwner := serializer.(*ptrToValueSerializer); !pointerOwner {
					if typeInfo.ValueBytes > 0 {
						boxedStructBytes = int64(typeInfo.ValueBytes)
					} else if structSer, ok := serializer.(*structSerializer); ok {
						boxedStructBytes = int64(structSer.valueBytes)
					}
				}
			}
			if boxedStructBytes > 0 && !ctx.ReserveGraphMemory(boxedStructBytes) {
				return
			}
			elem := reflect.New(elemType).Elem()
			serializer.ReadData(ctx, elem)
			if ctx.HasError() {
				return
			}
			if !publishReadRef(ctx, refID, elem) {
				return
			}
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
			elemType, serializer := s.wrapSerializerIfNeeded(
				ctx, typeInfo.Type, typeInfo.Serializer, typeInfo.ValueBytes)
			if ctx.HasError() {
				return
			}
			boxedStructBytes := int64(0)
			if elemType.Kind() == reflect.Struct {
				if _, pointerOwner := serializer.(*ptrToValueSerializer); !pointerOwner {
					if typeInfo.ValueBytes > 0 {
						boxedStructBytes = int64(typeInfo.ValueBytes)
					} else if structSer, ok := serializer.(*structSerializer); ok {
						boxedStructBytes = int64(structSer.valueBytes)
					}
				}
			}
			if boxedStructBytes > 0 && !ctx.ReserveGraphMemory(boxedStructBytes) {
				return
			}
			elem := reflect.New(elemType).Elem()
			serializer.ReadData(ctx, elem)
			if ctx.HasError() {
				return
			}
			value.Index(i).Set(elem)
		}
	}
}

// wrapSerializerIfNeeded wraps the serializer with ptrToValueSerializer if:
//  1. Slice element type is pointer-to-interface and the deserialized type is not a pointer, OR
//  2. Slice element type is interface and the deserialized type doesn't directly implement it
//     but the pointer type does (common case where interface has pointer receivers)
func (s *sliceDynSerializer) wrapSerializerIfNeeded(
	ctx *ReadContext, elemType reflect.Type, serializer Serializer, valueBytes int,
) (reflect.Type, Serializer) {
	if elemType == nil || serializer == nil {
		ctx.SetError(DeserializationError("dynamic slice element type cannot be materialized"))
		return nil, nil
	}
	if valueBytes == 0 {
		if structSer, ok := serializer.(*structSerializer); ok {
			valueBytes = structSer.valueBytes
		}
	}
	declaredType := s.elemType
	if declaredType == nil {
		declaredType = interfaceType
	}
	if elemType.AssignableTo(declaredType) {
		return elemType, serializer
	}
	if elemType.Kind() != reflect.Ptr {
		ptrType := reflect.PtrTo(elemType)
		if ptrType.AssignableTo(declaredType) {
			return ptrType, &ptrToValueSerializer{valueSerializer: serializer, valueBytes: valueBytes}
		}
	}
	ctx.SetError(DeserializationErrorf(
		"dynamic slice element type %v is not assignable to %v", elemType, declaredType))
	return nil, nil
}
