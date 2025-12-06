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
	"strconv"
)

const (
	CollectionDefaultFlag       = 0b0000
	CollectionTrackingRef       = 0b0001
	CollectionHasNull           = 0b0010
	CollectionIsDeclElementType = 0b0100
	CollectionIsSameType        = 0b1000
	CollectionDeclSameType      = CollectionIsSameType | CollectionIsDeclElementType
)

// sliceSerializer provides the dynamic slice implementation(e.g. []interface{}) that inspects
// element values at runtime
type sliceSerializer struct {
	elemInfo     TypeInfo
	declaredType reflect.Type
}

func (s sliceSerializer) TypeId() TypeId {
	return LIST
}

func (s sliceSerializer) NeedToWriteRef() bool {
	return true
}

func (s sliceSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	// Get slice length and handle empty slice case
	length := value.Len()
	if length == 0 {
		buf.WriteVarUint32(0) // Write 0 for empty slice
		return nil
	}

	// Write collection header and get type information
	collectFlag, elemTypeInfo, err := s.writeHeader(ctx, buf, value)
	if err != nil {
		return err
	}

	// Choose serialization path based on type consistency
	if (collectFlag & CollectionIsSameType) != 0 {
		return s.writeSameType(ctx, buf, value, elemTypeInfo, collectFlag) // Optimized path for same-type elements
	}
	return s.writeDifferentTypes(ctx, buf, value) // Fallback path for mixed-type elements
}

// writeHeader prepares and writes collection metadata including:
// - Collection size
// - Type consistency flags
// - Element type information (if homogeneous)
func (s sliceSerializer) writeHeader(ctx *WriteContext, buf *ByteBuffer, value reflect.Value) (byte, TypeInfo, error) {
	collectFlag := CollectionDefaultFlag
	var elemTypeInfo TypeInfo
	hasNull := false
	hasSameType := true

	// Seed elemTypeInfo from the first element so writeSameType can reuse it.
	// Empty slices leave elemTypeInfo zero-value, which is also fine because
	// writeSameType won't do anything in that case.
	if value.Len() > 0 {
		elemTypeInfo, _ = ctx.TypeResolver().getTypeInfo(value.Index(0), true)
	}

	if s.declaredType != nil {
		collectFlag |= CollectionIsDeclElementType | CollectionIsSameType
	} else {
		// Iterate through elements to check for nulls and type consistency
		var firstType reflect.Type
		for i := 0; i < value.Len(); i++ {
			elem := value.Index(i)
			if elem.Kind() == reflect.Interface || elem.Kind() == reflect.Ptr {
				elem = elem.Elem()
			}
			if isNull(elem) {
				hasNull = true
				continue
			}

			// Compare each element's type with the first element's type
			if firstType == nil {
				firstType = elem.Type()
			} else {
				if firstType != elem.Type() {
					hasSameType = false
				}
			}
		}
	}

	// Set collection flags based on findings
	if hasNull {
		collectFlag |= CollectionHasNull // Mark if collection contains null values
	}
	if hasSameType {
		collectFlag |= CollectionIsSameType // Mark if elements have same types
	}

	// Enable reference tracking if configured and element type supports it
	if ctx.TrackRef() && (elemTypeInfo.Serializer == nil || elemTypeInfo.Serializer.NeedToWriteRef()) {
		collectFlag |= CollectionTrackingRef
	}

	// Write metadata to buffer
	buf.WriteVarUint32(uint32(value.Len())) // Collection size
	buf.WriteInt8(int8(collectFlag))        // Collection flags

	// Write element type info if all elements have same type and not using declared type
	if hasSameType && (collectFlag&CollectionIsDeclElementType == 0) {
		// For namespaced types, write full type info
		internalTypeID := elemTypeInfo.TypeID
		if IsNamespacedType(TypeId(internalTypeID)) {
			if err := ctx.TypeResolver().writeTypeInfo(buf, elemTypeInfo); err != nil {
				return 0, TypeInfo{}, err
			}
		} else {
			buf.WriteVarInt32(elemTypeInfo.TypeID)
		}
	}

	return byte(collectFlag), elemTypeInfo, nil
}

// writeSameType efficiently serializes a slice where all elements share the same type
func (s sliceSerializer) writeSameType(ctx *WriteContext, buf *ByteBuffer, value reflect.Value, typeInfo TypeInfo, flag byte) error {
	serializer := typeInfo.Serializer
	trackRefs := (flag & CollectionTrackingRef) != 0 // Check if reference tracking is enabled

	for i := 0; i < value.Len(); i++ {
		elem := value.Index(i)
		// For reference tracking, we need to track the original value (pointer or interface),
		// not the dereferenced value. This ensures that identical pointers are recognized.
		origElem := elem
		if elem.Kind() == reflect.Interface || elem.Kind() == reflect.Ptr {
			elem = elem.Elem()
		}
		if isNull(elem) {
			buf.WriteInt8(NullFlag) // Write null marker
			continue
		}

	if trackRefs {
		// Track the original element (pointer/interface) for reference tracking
		// This ensures identical pointers are recognized as back-references
		refWritten, err := ctx.RefResolver().WriteRefOrNull(buf, origElem)
		if err != nil {
			return err
		}
		if !refWritten {
			// Write actual value if not a reference
			if err := serializer.Write(ctx, elem); err != nil {
				return err
			}
		}
	} else {
		// Directly write value without reference tracking
		if err := serializer.Write(ctx, elem); err != nil {
			return err
		}
	}
}
return nil
}// writeDifferentTypes handles serialization of slices with mixed element types
func (s sliceSerializer) writeDifferentTypes(ctx *WriteContext, buf *ByteBuffer, value reflect.Value) error {
	for i := 0; i < value.Len(); i++ {
		elem := value.Index(i)
		if elem.Kind() == reflect.Interface || elem.Kind() == reflect.Ptr {
			elem = elem.Elem()
		}
		if isNull(elem) {
			buf.WriteInt8(NullFlag) // Write null marker
			continue
		}
		// The following write logic doesn't cover the fast path for strings, so add it here
		if elem.Kind() == reflect.String {
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteVarInt32(STRING)
			err := writeString(buf, elem.Interface().(string))
			if err != nil {
				return err
			}
			continue
		}
		// Handle reference tracking
		refWritten, err := ctx.RefResolver().WriteRefOrNull(buf, elem)
		if err != nil {
			return err
		}
		if refWritten {
			continue // Skip if element was written as reference
		}

		// Get and write type info for each element (since types vary)
		typeInfo, _ := ctx.TypeResolver().getTypeInfo(elem, true)
		ctx.TypeResolver().writeTypeInfo(buf, typeInfo)
		// When writing the actual value, detect if elem is an array and convert it
		// to the corresponding slice type so the existing slice serializer can be reused
		if elem.Kind() == reflect.Array {
			sliceType := reflect.SliceOf(elem.Type().Elem())
			slice := reflect.MakeSlice(sliceType, elem.Len(), elem.Len())
			reflect.Copy(slice, elem)
			elem = slice
		}
		if err := typeInfo.Serializer.Write(ctx, elem); err != nil {
			return err
		}
	}
	return nil
}

func (s sliceSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	// Read slice length from buffer
	length := int(buf.ReadVarUint32())
	if length == 0 {
		// Initialize empty slice if length is 0
		value.Set(reflect.MakeSlice(type_, 0, 0))
		return nil
	}

	// Read collection flags that indicate special characteristics
	collectFlag := buf.ReadInt8()
	var elemTypeInfo TypeInfo
	// Read element type information if all elements are same type
	if (collectFlag & CollectionIsSameType) != 0 {
		if (collectFlag & CollectionIsDeclElementType) == 0 {
			typeID := buf.ReadVarInt32()
			var err error
			// Use readTypeInfoWithTypeID which handles both namespaced and non-namespaced types
			elemTypeInfo, err = ctx.TypeResolver().readTypeInfoWithTypeID(buf, typeID)
			if err != nil {
				elemTypeInfo = s.elemInfo
			}
		} else {
			elemTypeInfo = s.elemInfo
		}
	}
	// Initialize slice with proper capacity
	isArrayType := type_.Kind() == reflect.Array
	var arrayValue reflect.Value
	if isArrayType {
		arrayValue = value // Save the original array value
		// For arrays, we'll work with a slice and copy back later
		type_ = reflect.SliceOf(type_.Elem())
	}

	var readValue reflect.Value
	if value.IsZero() || value.Cap() < length {
		if type_.Kind() != reflect.Slice {
			if type_.Kind() == reflect.Interface {
				type_ = reflect.TypeOf([]interface{}{})
			} else {
				panic(fmt.Sprintf("sliceSerializer.ReadValue: unexpected type %v (kind=%v)", type_, type_.Kind()))
			}
		}
		// For arrays, create a temp slice to read into
		if isArrayType {
			readValue = reflect.MakeSlice(type_, length, length)
		} else {
			value.Set(reflect.MakeSlice(type_, length, length))
			readValue = value
			if readValue.Kind() == reflect.Interface || readValue.Kind() == reflect.Ptr {
				readValue = readValue.Elem()
			}
		}
	} else {
		if !isArrayType {
			value.Set(value.Slice(0, length))
		}
		readValue = value
	}
	// Register reference for tracking (handles circular references)
	ctx.RefResolver().Reference(readValue)

	// Choose appropriate deserialization path based on type consistency
	var err error
	if (collectFlag & CollectionIsSameType) != 0 {
		err = s.readSameType(ctx, buf, readValue, elemTypeInfo, collectFlag)
	} else {
		err = s.readDifferentTypes(ctx, buf, readValue)
	}
	if err != nil {
		return err
	}

	// For arrays, copy from the temp slice back to the array
	if isArrayType && arrayValue.IsValid() {
		arrayLen := arrayValue.Len()
		copyLen := length
		if copyLen > arrayLen {
			copyLen = arrayLen
		}
		for i := 0; i < copyLen; i++ {
			arrayValue.Index(i).Set(readValue.Index(i))
		}
	}

	return nil
}

// readSameType handles deserialization of slices where all elements share the same type
func (s sliceSerializer) readSameType(ctx *ReadContext, buf *ByteBuffer, value reflect.Value, typeInfo TypeInfo, flag int8) error {
	// Determine if reference tracking is enabled
	trackRefs := (flag & CollectionTrackingRef) != 0
	serializer := typeInfo.Serializer

	// Check if the slice element type is a pointer type
	// This affects how we handle reference tracking
	sliceElemType := value.Type().Elem()
	isConcretePointerElem := sliceElemType.Kind() == reflect.Ptr
	isInterfaceElem := sliceElemType.Kind() == reflect.Interface

	// Check if the element type needs pointer wrapping.
	// Struct types need to be wrapped in pointers for circular reference support.
	// Slices, maps, and other reference types don't need pointer wrapping.
	elemTypeNeedsPointerWrap := typeInfo.Type.Kind() == reflect.Struct

	for i := 0; i < value.Len(); i++ {
		var refID int32 = -1
		if trackRefs {
			// Handle reference tracking if enabled
			refID, _ = ctx.RefResolver().TryPreserveRefId(buf)
			if int8(refID) < NotNullValueFlag {
				// Use existing reference if available - use EXACTLY what was stored
				refObj := ctx.RefResolver().GetCurrentReadObject()
				value.Index(i).Set(refObj)
				continue
			}
		}

		// For concrete pointer elements ([]*T), we always need to create a pointer.
		if isConcretePointerElem {
			// Target is []*T, create pointer and register before reading
			ptr := reflect.New(typeInfo.Type)
			if trackRefs && refID >= 0 {
				ctx.RefResolver().SetReadObject(refID, ptr)
			}
			if err := serializer.Read(ctx, typeInfo.Type, ptr.Elem()); err != nil {
				return err
			}
			value.Index(i).Set(ptr)
		} else if isInterfaceElem && refID >= 0 && elemTypeNeedsPointerWrap {
			// Target is []interface{} and element is a struct that was reference-tracked.
			// Wrap struct in pointer so circular references within the struct work.
			ptr := reflect.New(typeInfo.Type)
			ctx.RefResolver().SetReadObject(refID, ptr)
			if err := serializer.Read(ctx, typeInfo.Type, ptr.Elem()); err != nil {
				return err
			}
			value.Index(i).Set(ptr)
		} else {
			// Non-pointer elements, or reference types (slices/maps) that don't need wrapping:
			// read directly into a value
			elem := reflect.New(typeInfo.Type).Elem()
			if err := serializer.Read(ctx, elem.Type(), elem); err != nil {
				return err
			}
			value.Index(i).Set(elem)
			if trackRefs && refID >= 0 {
				ctx.RefResolver().SetReadObject(refID, elem)
			}
		}
	}
	return nil
}

// readDifferentTypes handles deserialization of slices with mixed element types
func (s sliceSerializer) readDifferentTypes(ctx *ReadContext, buf *ByteBuffer, value reflect.Value) error {
	for i := 0; i < value.Len(); i++ {
		// Handle reference tracking for each element
		refID, _ := ctx.RefResolver().TryPreserveRefId(buf)
		if int8(refID) < NotNullValueFlag {
			// Use existing reference if available
			value.Index(i).Set(ctx.RefResolver().GetCurrentReadObject())
			continue
		}

		typeInfo, _ := ctx.TypeResolver().readTypeInfo(buf, value)

		// Create new element and deserialize from buffer
		elem := reflect.New(typeInfo.Type).Elem()
		if err := typeInfo.Serializer.Read(ctx, typeInfo.Type, elem); err != nil {
			return err
		}
		// Set element in slice and register reference
		ctx.RefResolver().SetReadObject(refID, elem)
		value.Index(i).Set(elem)
	}
	return nil
}

// Helper function to check if a value is null/nil
func isNull(v reflect.Value) bool {
	switch v.Kind() {
	case reflect.Ptr, reflect.Interface, reflect.Slice, reflect.Map, reflect.Func:
		return v.IsNil() // Check if reference types are nil
	default:
		return false // Value types are never null
	}
}

// sliceConcreteValueSerializer serialize a slice whose elem is not an interface or pointer to interface.
// Use newSliceConcreteValueSerializer to create instances with proper type validation.
type sliceConcreteValueSerializer struct {
	type_          reflect.Type
	elemSerializer Serializer
	referencable   bool
}

// newSliceConcreteValueSerializer creates a sliceConcreteValueSerializer for slices with concrete element types.
// It returns an error if the element type is an interface or pointer to interface.
func newSliceConcreteValueSerializer(type_ reflect.Type, elemSerializer Serializer) (*sliceConcreteValueSerializer, error) {
	elem := type_.Elem()
	if elem.Kind() == reflect.Interface {
		return nil, fmt.Errorf("sliceConcreteValueSerializer does not support interface element type: %v", type_)
	}
	if elem.Kind() == reflect.Ptr && elem.Elem().Kind() == reflect.Interface {
		return nil, fmt.Errorf("sliceConcreteValueSerializer does not support pointer to interface element type: %v", type_)
	}
	return &sliceConcreteValueSerializer{
		type_:          type_,
		elemSerializer: elemSerializer,
		referencable:   nullable(elem),
	}, nil
}

func (s *sliceConcreteValueSerializer) TypeId() TypeId {
	return -LIST
}

func (s *sliceConcreteValueSerializer) NeedToWriteRef() bool {
	return true
}

func (s *sliceConcreteValueSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	length := value.Len()
	buf := ctx.Buffer()

	// Write length
	buf.WriteVarUint32(uint32(length))
	if length == 0 {
		return nil
	}

	// Determine collection flags - don't set CollectionIsDeclElementType
	// so that the element type ID is written and can be read by generic deserializer
	collectFlag := CollectionIsSameType
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
	if ctx.TrackRef() && s.referencable {
		collectFlag |= CollectionTrackingRef
	}
	buf.WriteInt8(int8(collectFlag))

	// Write element type info since CollectionIsDeclElementType is not set
	var elemTypeInfo TypeInfo
	if length > 0 {
		// Get type info for the first non-nil element to get proper typeID
		for i := 0; i < length; i++ {
			elem := value.Index(i)
			if isPointerElem {
				if !elem.IsNil() {
					elemTypeInfo, _ = ctx.TypeResolver().getTypeInfo(elem.Elem(), true)
					break
				}
			} else {
				elemTypeInfo, _ = ctx.TypeResolver().getTypeInfo(elem, true)
				break
			}
		}
	}
	// Write element type info (handles namespaced types properly)
	internalTypeID := elemTypeInfo.TypeID
	if IsNamespacedType(TypeId(internalTypeID)) {
		if err := ctx.TypeResolver().writeTypeInfo(buf, elemTypeInfo); err != nil {
			return err
		}
	} else {
		buf.WriteVarInt32(elemTypeInfo.TypeID)
	}

	// Write elements
	trackRefs := (collectFlag & CollectionTrackingRef) != 0

	for i := 0; i < length; i++ {
		elem := value.Index(i)

		// Handle null values (only for pointer element types)
		if hasNull && elem.IsNil() {
			buf.WriteInt8(NullFlag)
			continue
		}

		if trackRefs {
			// Track the element as-is (pointer for pointer types, value for value types)
			refWritten, err := ctx.RefResolver().WriteRefOrNull(buf, elem)
			if err != nil {
				return err
			}
			if refWritten {
				continue
			}
		}

		// Write the element using its serializer
		if err := s.elemSerializer.Write(ctx, elem); err != nil {
			return err
		}
	}
	return nil
}

func (s *sliceConcreteValueSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	length := int(buf.ReadVarUint32())
	if length == 0 {
		value.Set(reflect.MakeSlice(value.Type(), 0, 0))
		return nil
	}

	// Read collection flags
	collectFlag := buf.ReadInt8()

	// Read element type info if not using declared type
	if (collectFlag & CollectionIsDeclElementType) == 0 {
		typeID := buf.ReadVarInt32()
		// Read additional metadata for namespaced types
		_, _ = ctx.TypeResolver().readTypeInfoWithTypeID(buf, typeID)
	}

	if value.Cap() < length {
		value.Set(reflect.MakeSlice(value.Type(), length, length))
	} else if value.Len() < length {
		value.Set(value.Slice(0, length))
	}
	ctx.RefResolver().Reference(value)

	trackRefs := (collectFlag & CollectionTrackingRef) != 0
	elemType := s.type_.Elem()

	for i := 0; i < length; i++ {
		elem := value.Index(i)
		var refID int32 = -1

		if trackRefs {
			refID, _ = ctx.RefResolver().TryPreserveRefId(buf)
			if int8(refID) < NotNullValueFlag {
				// Back-reference or null
				if int8(refID) == NullFlag {
					continue // elem stays zero-valued
				}
				elem.Set(ctx.RefResolver().GetCurrentReadObject())
				continue
			}
		}

		// For pointer element types, we handle allocation and reference registration
		// ourselves since we've already read the reference flag at the collection level.
		if elemType.Kind() == reflect.Ptr {
			// Create a new pointer value
			newVal := reflect.New(elemType.Elem())
			// Register the reference BEFORE reading content (for circular refs)
			if trackRefs && refID >= 0 {
				ctx.RefResolver().SetReadObject(refID, newVal)
			}
			// Read the struct content. We need to handle different serializer types:
			// - ptrToStructSerializer: registered named types, use underlying structSerializer
			// - ptrToValueSerializer: generic pointer types, use underlying valueSerializer
			switch ser := s.elemSerializer.(type) {
			case *ptrToStructSerializer:
				if err := ser.structSerializer.Read(ctx, elemType.Elem(), newVal.Elem()); err != nil {
					return err
				}
			case *ptrToValueSerializer:
				if err := ser.valueSerializer.Read(ctx, elemType.Elem(), newVal.Elem()); err != nil {
					return err
				}
			default:
				// Fallback: call serializer directly (may not work correctly for all types)
				if err := s.elemSerializer.Read(ctx, elemType, newVal); err != nil {
					return err
				}
			}
			elem.Set(newVal)
		} else {
			if err := s.elemSerializer.Read(ctx, elemType, elem); err != nil {
				return err
			}
			if trackRefs && refID >= 0 {
				ctx.RefResolver().SetReadObject(refID, elem)
			}
		}
	}
	return nil
}

type byteSliceSerializer struct {
}

func (s byteSliceSerializer) TypeId() TypeId {
	return BINARY
}

func (s byteSliceSerializer) NeedToWriteRef() bool {
	return true
}

func (s byteSliceSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	if err := ctx.WriteBufferObject(&ByteSliceBufferObject{value.Interface().([]byte)}); err != nil {
		return err
	}
	return nil
}

func (s byteSliceSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	object, err := ctx.ReadBufferObject()
	if err != nil {
		return err
	}
	raw := object.GetData()
	switch type_.Kind() {
	case reflect.Slice:
		value.Set(reflect.ValueOf(raw))
		return nil
	case reflect.Array:
		if len(raw) != type_.Len() {
			return fmt.Errorf("byte array len %d ≠ %d", len(raw), type_.Len())
		}
		dst := reflect.New(type_).Elem()
		reflect.Copy(dst, reflect.ValueOf(raw))
		value.Set(dst)
		return nil

	default:
		return fmt.Errorf("unsupported kind %v; want slice or array", type_.Kind())
	}
}

type ByteSliceBufferObject struct {
	data []byte
}

func (o *ByteSliceBufferObject) TotalBytes() int {
	return len(o.data)
}

func (o *ByteSliceBufferObject) WriteTo(buf *ByteBuffer) {
	buf.WriteBinary(o.data)
}

func (o *ByteSliceBufferObject) ToBuffer() *ByteBuffer {
	return NewByteBuffer(o.data)
}

type boolArraySerializer struct {
}

func (s boolArraySerializer) TypeId() TypeId {
	return BOOL_ARRAY
}

func (s boolArraySerializer) NeedToWriteRef() bool {
	return true
}

func (s boolArraySerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	v := value.Interface().([]bool)
	size := len(v)
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(v))
	}
	buf.WriteLength(size)
	for _, elem := range v {
		buf.WriteBool(elem)
	}
	return nil
}

func (s boolArraySerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	length := buf.ReadLength()
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	for i := 0; i < length; i++ {
		r.Index(i).SetBool(buf.ReadBool())
	}
	value.Set(r)
	return nil
}

type int8ArraySerializer struct {
}

func (s int8ArraySerializer) TypeId() TypeId {
	return INT8_ARRAY
}

func (s int8ArraySerializer) NeedToWriteRef() bool {
	return true
}

func (s int8ArraySerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	v := value.Interface().([]int8)
	size := len(v)
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(v))
	}
	buf.WriteLength(size)
	for _, elem := range v {
		buf.WriteByte_(byte(elem))
	}
	return nil
}

func (s int8ArraySerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	length := buf.ReadLength()
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	for i := 0; i < length; i++ {
		r.Index(i).SetInt(int64(int8(buf.ReadByte_())))
	}
	value.Set(r)
	return nil
}

type int16ArraySerializer struct {
}

func (s int16ArraySerializer) TypeId() TypeId {
	return INT16_ARRAY
}

func (s int16ArraySerializer) NeedToWriteRef() bool {
	return true
}

func (s int16ArraySerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	length := value.Len()
	size := length * 2
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", length)
	}
	buf.WriteLength(size)
	for i := 0; i < length; i++ {
		buf.WriteInt16(int16(value.Index(i).Int()))
	}
	return nil
}

func (s int16ArraySerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	length := buf.ReadLength() / 2
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	for i := 0; i < length; i++ {
		r.Index(i).SetInt(int64(buf.ReadInt16()))
	}
	value.Set(r)
	return nil
}

type int32ArraySerializer struct {
}

func (s int32ArraySerializer) TypeId() TypeId {
	return INT32_ARRAY
}

func (s int32ArraySerializer) NeedToWriteRef() bool {
	return true
}

func (s int32ArraySerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	v := value.Interface().([]int32)
	size := len(v) * 4
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(v))
	}
	buf.WriteLength(size)
	for _, elem := range v {
		buf.WriteInt32(elem)
	}
	return nil
}

func (s int32ArraySerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	length := buf.ReadLength() / 4
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	for i := 0; i < length; i++ {
		r.Index(i).SetInt(int64(buf.ReadInt32()))
	}
	value.Set(r)
	return nil
}

type int64ArraySerializer struct {
}

func (s int64ArraySerializer) TypeId() TypeId {
	return INT64_ARRAY
}

func (s int64ArraySerializer) NeedToWriteRef() bool {
	return true
}

func (s int64ArraySerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	v := value.Interface().([]int64)
	size := len(v) * 8
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(v))
	}
	buf.WriteLength(size)
	for _, elem := range v {
		buf.WriteInt64(elem)
	}
	return nil
}

func (s int64ArraySerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	length := buf.ReadLength() / 8
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	for i := 0; i < length; i++ {
		r.Index(i).SetInt(buf.ReadInt64())
	}
	value.Set(r)
	return nil
}

type float32ArraySerializer struct {
}

func (s float32ArraySerializer) TypeId() TypeId {
	return FLOAT32_ARRAY
}

func (s float32ArraySerializer) NeedToWriteRef() bool {
	return true
}

func (s float32ArraySerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	v := value.Interface().([]float32)
	size := len(v) * 4
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(v))
	}
	buf.WriteLength(size)
	for _, elem := range v {
		buf.WriteFloat32(elem)
	}
	return nil
}

func (s float32ArraySerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	length := buf.ReadLength() / 4
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	for i := 0; i < length; i++ {
		r.Index(i).SetFloat(float64(buf.ReadFloat32()))
	}
	value.Set(r)
	return nil
}

type float64ArraySerializer struct {
}

func (s float64ArraySerializer) TypeId() TypeId {
	return FLOAT64_ARRAY
}

func (s float64ArraySerializer) NeedToWriteRef() bool {
	return true
}

func (s float64ArraySerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	v := value.Interface().([]float64)
	size := len(v) * 8
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(v))
	}
	buf.WriteLength(size)
	for _, elem := range v {
		buf.WriteFloat64(elem)
	}
	return nil
}

func (s float64ArraySerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	length := buf.ReadLength() / 8
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	for i := 0; i < length; i++ {
		r.Index(i).SetFloat(buf.ReadFloat64())
	}
	value.Set(r)
	return nil
}

// ============================================================================
// Helper functions for primitive slice serialization
// ============================================================================

// writeBoolSlice writes []bool to buffer
func writeBoolSlice(buf *ByteBuffer, value []bool) error {
	size := len(value)
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", size)
	}
	buf.WriteLength(size)
	for _, elem := range value {
		buf.WriteBool(elem)
	}
	return nil
}

// readBoolSlice reads []bool from buffer
func readBoolSlice(buf *ByteBuffer) ([]bool, error) {
	length := buf.ReadLength()
	result := make([]bool, length)
	for i := 0; i < length; i++ {
		result[i] = buf.ReadBool()
	}
	return result, nil
}

// readBoolSliceInto reads []bool into target, reusing capacity when possible
func readBoolSliceInto(buf *ByteBuffer, target *[]bool) error {
	length := buf.ReadLength()
	if cap(*target) >= length {
		*target = (*target)[:length]
	} else {
		*target = make([]bool, length)
	}
	for i := 0; i < length; i++ {
		(*target)[i] = buf.ReadBool()
	}
	return nil
}

// writeInt8Slice writes []int8 to buffer
func writeInt8Slice(buf *ByteBuffer, value []int8) error {
	size := len(value)
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", size)
	}
	buf.WriteLength(size)
	for _, elem := range value {
		buf.WriteInt8(elem)
	}
	return nil
}

// readInt8Slice reads []int8 from buffer
func readInt8Slice(buf *ByteBuffer) ([]int8, error) {
	length := buf.ReadLength()
	result := make([]int8, length)
	for i := 0; i < length; i++ {
		result[i] = buf.ReadInt8()
	}
	return result, nil
}

// readInt8SliceInto reads []int8 into target, reusing capacity when possible
func readInt8SliceInto(buf *ByteBuffer, target *[]int8) error {
	length := buf.ReadLength()
	if cap(*target) >= length {
		*target = (*target)[:length]
	} else {
		*target = make([]int8, length)
	}
	for i := 0; i < length; i++ {
		(*target)[i] = buf.ReadInt8()
	}
	return nil
}

// writeInt16Slice writes []int16 to buffer
func writeInt16Slice(buf *ByteBuffer, value []int16) error {
	size := len(value) * 2
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(value))
	}
	buf.WriteLength(size)
	for _, elem := range value {
		buf.WriteInt16(elem)
	}
	return nil
}

// readInt16Slice reads []int16 from buffer
func readInt16Slice(buf *ByteBuffer) ([]int16, error) {
	size := buf.ReadLength()
	length := size / 2
	result := make([]int16, length)
	for i := 0; i < length; i++ {
		result[i] = buf.ReadInt16()
	}
	return result, nil
}

// readInt16SliceInto reads []int16 into target, reusing capacity when possible
func readInt16SliceInto(buf *ByteBuffer, target *[]int16) error {
	size := buf.ReadLength()
	length := size / 2
	if cap(*target) >= length {
		*target = (*target)[:length]
	} else {
		*target = make([]int16, length)
	}
	for i := 0; i < length; i++ {
		(*target)[i] = buf.ReadInt16()
	}
	return nil
}

// writeInt32Slice writes []int32 to buffer
func writeInt32Slice(buf *ByteBuffer, value []int32) error {
	size := len(value) * 4
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(value))
	}
	buf.WriteLength(size)
	for _, elem := range value {
		buf.WriteInt32(elem)
	}
	return nil
}

// readInt32Slice reads []int32 from buffer
func readInt32Slice(buf *ByteBuffer) ([]int32, error) {
	size := buf.ReadLength()
	length := size / 4
	result := make([]int32, length)
	for i := 0; i < length; i++ {
		result[i] = buf.ReadInt32()
	}
	return result, nil
}

// readInt32SliceInto reads []int32 into target, reusing capacity when possible
func readInt32SliceInto(buf *ByteBuffer, target *[]int32) error {
	size := buf.ReadLength()
	length := size / 4
	if cap(*target) >= length {
		*target = (*target)[:length]
	} else {
		*target = make([]int32, length)
	}
	for i := 0; i < length; i++ {
		(*target)[i] = buf.ReadInt32()
	}
	return nil
}

// writeInt64Slice writes []int64 to buffer
func writeInt64Slice(buf *ByteBuffer, value []int64) error {
	size := len(value) * 8
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(value))
	}
	buf.WriteLength(size)
	for _, elem := range value {
		buf.WriteInt64(elem)
	}
	return nil
}

// readInt64Slice reads []int64 from buffer
func readInt64Slice(buf *ByteBuffer) ([]int64, error) {
	size := buf.ReadLength()
	length := size / 8
	result := make([]int64, length)
	for i := 0; i < length; i++ {
		result[i] = buf.ReadInt64()
	}
	return result, nil
}

// readInt64SliceInto reads []int64 into target, reusing capacity when possible
func readInt64SliceInto(buf *ByteBuffer, target *[]int64) error {
	size := buf.ReadLength()
	length := size / 8
	if cap(*target) >= length {
		*target = (*target)[:length]
	} else {
		*target = make([]int64, length)
	}
	for i := 0; i < length; i++ {
		(*target)[i] = buf.ReadInt64()
	}
	return nil
}

// writeIntSlice writes []int to buffer
func writeIntSlice(buf *ByteBuffer, value []int) error {
	if strconv.IntSize == 64 {
		size := len(value) * 8
		if size >= MaxInt32 {
			return fmt.Errorf("too long slice: %d", len(value))
		}
		buf.WriteLength(size)
		for _, elem := range value {
			buf.WriteInt64(int64(elem))
		}
	} else {
		size := len(value) * 4
		if size >= MaxInt32 {
			return fmt.Errorf("too long slice: %d", len(value))
		}
		buf.WriteLength(size)
		for _, elem := range value {
			buf.WriteInt32(int32(elem))
		}
	}
	return nil
}

// readIntSlice reads []int from buffer
func readIntSlice(buf *ByteBuffer) ([]int, error) {
	size := buf.ReadLength()
	if strconv.IntSize == 64 {
		length := size / 8
		result := make([]int, length)
		for i := 0; i < length; i++ {
			result[i] = int(buf.ReadInt64())
		}
		return result, nil
	} else {
		length := size / 4
		result := make([]int, length)
		for i := 0; i < length; i++ {
			result[i] = int(buf.ReadInt32())
		}
		return result, nil
	}
}

// readIntSliceInto reads []int into target, reusing capacity when possible
func readIntSliceInto(buf *ByteBuffer, target *[]int) error {
	size := buf.ReadLength()
	if strconv.IntSize == 64 {
		length := size / 8
		if cap(*target) >= length {
			*target = (*target)[:length]
		} else {
			*target = make([]int, length)
		}
		for i := 0; i < length; i++ {
			(*target)[i] = int(buf.ReadInt64())
		}
	} else {
		length := size / 4
		if cap(*target) >= length {
			*target = (*target)[:length]
		} else {
			*target = make([]int, length)
		}
		for i := 0; i < length; i++ {
			(*target)[i] = int(buf.ReadInt32())
		}
	}
	return nil
}

// writeFloat32Slice writes []float32 to buffer
func writeFloat32Slice(buf *ByteBuffer, value []float32) error {
	size := len(value) * 4
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(value))
	}
	buf.WriteLength(size)
	for _, elem := range value {
		buf.WriteFloat32(elem)
	}
	return nil
}

// readFloat32Slice reads []float32 from buffer
func readFloat32Slice(buf *ByteBuffer) ([]float32, error) {
	size := buf.ReadLength()
	length := size / 4
	result := make([]float32, length)
	for i := 0; i < length; i++ {
		result[i] = buf.ReadFloat32()
	}
	return result, nil
}

// readFloat32SliceInto reads []float32 into target, reusing capacity when possible
func readFloat32SliceInto(buf *ByteBuffer, target *[]float32) error {
	size := buf.ReadLength()
	length := size / 4
	if cap(*target) >= length {
		*target = (*target)[:length]
	} else {
		*target = make([]float32, length)
	}
	for i := 0; i < length; i++ {
		(*target)[i] = buf.ReadFloat32()
	}
	return nil
}

// writeFloat64Slice writes []float64 to buffer
func writeFloat64Slice(buf *ByteBuffer, value []float64) error {
	size := len(value) * 8
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(value))
	}
	buf.WriteLength(size)
	for _, elem := range value {
		buf.WriteFloat64(elem)
	}
	return nil
}

// readFloat64Slice reads []float64 from buffer
func readFloat64Slice(buf *ByteBuffer) ([]float64, error) {
	size := buf.ReadLength()
	length := size / 8
	result := make([]float64, length)
	for i := 0; i < length; i++ {
		result[i] = buf.ReadFloat64()
	}
	return result, nil
}

// readFloat64SliceInto reads []float64 into target, reusing capacity when possible
func readFloat64SliceInto(buf *ByteBuffer, target *[]float64) error {
	size := buf.ReadLength()
	length := size / 8
	if cap(*target) >= length {
		*target = (*target)[:length]
	} else {
		*target = make([]float64, length)
	}
	for i := 0; i < length; i++ {
		(*target)[i] = buf.ReadFloat64()
	}
	return nil
}

// ============================================================================
// Legacy slice serializers - kept for backward compatibility but not used for xlang
// ============================================================================

type boolSliceSerializer struct {
}

func (s boolSliceSerializer) TypeId() TypeId {
	return BOOL_ARRAY // Use legacy type ID to avoid conflicts
}

func (s boolSliceSerializer) NeedToWriteRef() bool {
	return true
}

func (s boolSliceSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	v := value.Interface().([]bool)
	size := len(v)
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(v))
	}
	buf.WriteLength(size)
	for _, elem := range v {
		buf.WriteBool(elem)
	}
	return nil
}

func (s boolSliceSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	length := buf.ReadLength()
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	for i := 0; i < length; i++ {
		r.Index(i).SetBool(buf.ReadBool())
	}
	value.Set(r)
	return nil
}

type int8SliceSerializer struct {
}

func (s int8SliceSerializer) TypeId() TypeId {
	return INT8_ARRAY
}

func (s int8SliceSerializer) NeedToWriteRef() bool {
	return true
}

func (s int8SliceSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	v := value.Interface().([]int8)
	return writeInt8Slice(buf, v)
}

func (s int8SliceSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	length := buf.ReadLength()
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	for i := 0; i < length; i++ {
		r.Index(i).SetInt(int64(buf.ReadInt8()))
	}
	value.Set(r)
	return nil
}

type int16SliceSerializer struct {
}

func (s int16SliceSerializer) TypeId() TypeId {
	return INT16_ARRAY
}

func (s int16SliceSerializer) NeedToWriteRef() bool {
	return true
}

func (s int16SliceSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	v := value.Interface().([]int16)
	size := len(v) * 2
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(v))
	}
	buf.WriteLength(size)
	for _, elem := range v {
		buf.WriteInt16(elem)
	}
	return nil
}

func (s int16SliceSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	length := buf.ReadLength() / 2
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	for i := 0; i < length; i++ {
		r.Index(i).SetInt(int64(buf.ReadInt16()))
	}
	value.Set(r)
	return nil
}

type int32SliceSerializer struct {
}

func (s int32SliceSerializer) TypeId() TypeId {
	return INT32_ARRAY
}

func (s int32SliceSerializer) NeedToWriteRef() bool {
	return true
}

func (s int32SliceSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	v := value.Interface().([]int32)
	size := len(v) * 4
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(v))
	}
	buf.WriteLength(size)
	for _, elem := range v {
		buf.WriteInt32(elem)
	}
	return nil
}

func (s int32SliceSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	length := buf.ReadLength() / 4
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	for i := 0; i < length; i++ {
		r.Index(i).SetInt(int64(buf.ReadInt32()))
	}
	value.Set(r)
	return nil
}

type int64SliceSerializer struct {
}

func (s int64SliceSerializer) TypeId() TypeId {
	return INT64_ARRAY
}

func (s int64SliceSerializer) NeedToWriteRef() bool {
	return true
}

func (s int64SliceSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	v := value.Interface().([]int64)
	size := len(v) * 8
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(v))
	}
	buf.WriteLength(size)
	for _, elem := range v {
		buf.WriteInt64(elem)
	}
	return nil
}

func (s int64SliceSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	length := buf.ReadLength() / 8
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	for i := 0; i < length; i++ {
		r.Index(i).SetInt(buf.ReadInt64())
	}
	value.Set(r)
	return nil
}

type intSliceSerializer struct {
}

func (s intSliceSerializer) TypeId() TypeId {
	if strconv.IntSize == 64 {
		return INT64_ARRAY
	}
	return INT32_ARRAY
}

func (s intSliceSerializer) NeedToWriteRef() bool {
	return true
}

func (s intSliceSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	v := value.Interface().([]int)
	return writeIntSlice(buf, v)
}

func (s intSliceSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	size := buf.ReadLength()
	var length int
	if strconv.IntSize == 64 {
		length = size / 8
	} else {
		length = size / 4
	}
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	if strconv.IntSize == 64 {
		for i := 0; i < length; i++ {
			r.Index(i).SetInt(buf.ReadInt64())
		}
	} else {
		for i := 0; i < length; i++ {
			r.Index(i).SetInt(int64(buf.ReadInt32()))
		}
	}
	value.Set(r)
	return nil
}

type float32SliceSerializer struct {
}

func (s float32SliceSerializer) TypeId() TypeId {
	return FLOAT32_ARRAY
}

func (s float32SliceSerializer) NeedToWriteRef() bool {
	return true
}

func (s float32SliceSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	v := value.Interface().([]float32)
	size := len(v) * 4
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(v))
	}
	buf.WriteLength(size)
	for _, elem := range v {
		buf.WriteFloat32(elem)
	}
	return nil
}

func (s float32SliceSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	length := buf.ReadLength() / 4
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	for i := 0; i < length; i++ {
		r.Index(i).SetFloat(float64(buf.ReadFloat32()))
	}
	value.Set(r)
	return nil
}

type float64SliceSerializer struct {
}

func (s float64SliceSerializer) TypeId() TypeId {
	return FLOAT64_ARRAY
}

func (s float64SliceSerializer) NeedToWriteRef() bool {
	return true
}

func (s float64SliceSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	v := value.Interface().([]float64)
	size := len(v) * 8
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(v))
	}
	buf.WriteLength(size)
	for _, elem := range v {
		buf.WriteFloat64(elem)
	}
	return nil
}

func (s float64SliceSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	length := buf.ReadLength() / 8
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	for i := 0; i < length; i++ {
		r.Index(i).SetFloat(buf.ReadFloat64())
	}
	value.Set(r)
	return nil
}

type stringSliceSerializer struct {
	strSerializer stringSerializer
}

func (s stringSliceSerializer) TypeId() TypeId {
	return LIST
}

func (s stringSliceSerializer) NeedToWriteRef() bool {
	return true
}

func (s stringSliceSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	v := value.Interface().([]string)
	err := ctx.WriteLength(len(v))
	if err != nil {
		return err
	}
	for _, str := range v {
		if refWritten, err := ctx.RefResolver().WriteRefOrNull(buf, reflect.ValueOf(str)); err == nil {
			if !refWritten {
				if err := writeString(buf, str); err != nil {
					return err
				}
			}
		} else {
			return err
		}
	}
	return nil
}

func (s stringSliceSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	length := ctx.ReadLength()
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}

	elemTyp := type_.Elem()
	set := func(i int, s string) {
		if elemTyp.Kind() == reflect.String {
			r.Index(i).SetString(s)
		} else {
			r.Index(i).Set(reflect.ValueOf(s).Convert(elemTyp))
		}
	}

	for i := 0; i < length; i++ {
		refFlag := ctx.RefResolver().ReadRefOrNull(buf)
		if refFlag == RefValueFlag || refFlag == NotNullValueFlag {
			var nextReadRefId int32
			if refFlag == RefValueFlag {
				var err error
				nextReadRefId, err = ctx.RefResolver().PreserveRefId()
				if err != nil {
					return err
				}
			}
			elem := readString(buf)
			if ctx.TrackRef() && refFlag == RefValueFlag {
				// If value is not nil(reflect), then value is a pointer to some variable, we can update the `value`,
				// then record `value` in the reference resolver.
				ctx.RefResolver().SetReadObject(nextReadRefId, reflect.ValueOf(elem))
			}
			set(i, elem)
		} else if refFlag == NullFlag {
			set(i, "")
		} else { // RefNoneFlag
			set(i, ctx.RefResolver().GetCurrentReadObject().Interface().(string))
		}
	}
	value.Set(r)
	return nil
}
