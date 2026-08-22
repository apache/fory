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
)

// Set is a generic set type using Go generics.
// Uses struct{} as value type for zero memory overhead.
type Set[T comparable] map[T]struct{}

// NewSet creates a new empty Set.
func NewSet[T comparable]() Set[T] {
	return make(Set[T])
}

// Add adds one or more elements to the set.
func (s Set[T]) Add(values ...T) {
	for _, v := range values {
		s[v] = struct{}{}
	}
}

// Remove removes an element from the set.
func (s Set[T]) Remove(value T) {
	delete(s, value)
}

// Contains checks if an element is in the set.
func (s Set[T]) Contains(value T) bool {
	_, ok := s[value]
	return ok
}

// Len returns the number of elements in the set.
func (s Set[T]) Len() int {
	return len(s)
}

// Values returns all elements as a slice.
func (s Set[T]) Values() []T {
	result := make([]T, 0, len(s))
	for v := range s {
		result = append(result, v)
	}
	return result
}

// Clear removes all elements from the set.
func (s Set[T]) Clear() {
	for k := range s {
		delete(s, k)
	}
}

// emptyStructVal is a pre-created reflect.Value of struct{}{} to avoid repeated allocations
var emptyStructVal = reflect.ValueOf(struct{}{})

type setSerializer struct {
	elemSerializer       Serializer
	declaredElemType     reflect.Type
	declaredElemTypeInfo *TypeInfo
	elemDeclType         bool
	elemReferencable     bool
	hasGenerics          bool
	type_                reflect.Type
	keyBytes             int
	valueBytes           int
	declaredElemBytes    int
	maxLength            int64
}

func (s setSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	s.writeDataWithGenerics(ctx, value, s.hasGenerics)
}

func (s setSerializer) writeDataWithGenerics(ctx *WriteContext, value reflect.Value, hasGenerics bool) {
	buf := ctx.Buffer()
	// Get all map keys (set elements)
	keys := value.MapKeys()
	length := len(keys)

	// Handle empty set case
	if length == 0 {
		buf.WriteVarUint32(0) // WriteData 0 length for empty set
		return
	}

	// WriteData collection header and get type information
	collectFlag, elemTypeInfo := s.writeHeader(ctx, buf, keys, hasGenerics)
	if ctx.HasError() {
		return
	}

	// Check if all elements are of same type
	if (collectFlag & CollectionIsSameType) != 0 {
		// Optimized path for same-type elements
		s.writeSameType(ctx, buf, keys, elemTypeInfo, collectFlag)
		return
	}
	// Fallback path for mixed-type elements
	s.writeDifferentTypes(ctx, buf, keys, collectFlag)
}

func (s setSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	if refMode != RefModeNone {
		if value.IsNil() {
			ctx.buffer.WriteInt8(NullFlag)
			return
		}
		refWritten, err := ctx.RefResolver().WriteRefOrNull(ctx.buffer, value)
		if err != nil {
			ctx.SetError(FromError(err))
			return
		}
		if refWritten {
			return
		}
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(SET))
	}
	s.writeDataWithGenerics(ctx, value, hasGenerics || s.hasGenerics)
}

// writeHeader prepares and writes collection metadata including:
// - Collection size
// - Type consistency flags
// - Element type information (if homogeneous and not declared from schema)
func (s setSerializer) writeHeader(ctx *WriteContext, buf *ByteBuffer, keys []reflect.Value, hasGenerics bool) (byte, *TypeInfo) {
	// Initialize collection flags and type tracking variables
	collectFlag := CollectionDefaultFlag
	var elemTypeInfo *TypeInfo
	hasNull := false
	hasSameType := true
	ctxErr := ctx.Err()
	declaredGenerics := hasGenerics && s.elemSerializer != nil
	if declaredGenerics {
		for _, key := range keys {
			if isNull(UnwrapReflectValue(key)) {
				hasNull = true
				break
			}
		}
		elemTypeInfo = s.declaredElemTypeInfo
		if !s.elemDeclType && elemTypeInfo == nil {
			ctxErr.SetError(SerializationError("declared set element TypeInfo is unavailable"))
			return CollectionDefaultFlag, nil
		}
	} else {
		// Find the first non-null binding while checking type consistency. Map key
		// iteration is unordered, so the first key cannot be assumed non-null.
		for _, key := range keys {
			key = UnwrapReflectValue(key)
			if isNull(key) {
				hasNull = true
				continue
			}

			if elemTypeInfo == nil {
				var err error
				elemTypeInfo, err = ctx.TypeResolver().GetTypeInfo(key, true)
				if err != nil {
					ctxErr.SetError(err)
					return CollectionDefaultFlag, nil
				}
				continue
			}
			currentTypeInfo, typeErr := ctx.TypeResolver().GetTypeInfo(key, true)
			if typeErr != nil {
				ctxErr.SetError(typeErr)
				return CollectionDefaultFlag, nil
			}
			// NAMED_STRUCT/NAMED_EXT is only a wire category. Distinct registered
			// concrete types must not share the first element's serializer.
			if currentTypeInfo == nil || currentTypeInfo.Type != elemTypeInfo.Type {
				hasSameType = false
			}
		}
	}

	// Set collection flags based on findings
	// An all-null dynamic set has no shared TypeInfo, so it must use the
	// per-element null framing.
	if hasSameType && !declaredGenerics && elemTypeInfo == nil {
		hasSameType = false
	}
	if hasNull {
		collectFlag |= CollectionHasNull // Mark if collection contains null values
	}
	if hasSameType {
		collectFlag |= CollectionIsSameType // Mark if elements have same type
	}
	// When hasGenerics is true, element type is declared from schema (known at compile time)
	// so we don't need to write the element type ID.
	if declaredGenerics && s.elemDeclType {
		collectFlag |= CollectionIsDeclElementType
		collectFlag |= CollectionIsSameType
	}

	// Enable reference tracking if configured
	if ctx.TrackRef() && (!declaredGenerics || s.elemReferencable) {
		collectFlag |= CollectionTrackingRef
	}

	// WriteData metadata to buffer
	buf.WriteVarUint32(uint32(len(keys))) // Collection size
	buf.WriteInt8(int8(collectFlag))      // Collection flags

	// WriteData element type ID only if:
	// 1. All elements have same type (IS_SAME_TYPE is set)
	// 2. Element type is NOT declared from schema (IS_DECL_ELEMENT_TYPE is NOT set)
	if hasSameType && (!declaredGenerics || !s.elemDeclType) && elemTypeInfo != nil {
		ctx.TypeResolver().WriteTypeInfo(buf, elemTypeInfo, ctxErr)
	}

	return byte(collectFlag), elemTypeInfo
}

// writeSameType efficiently serializes a collection where all elements share the same type
func (s setSerializer) writeSameType(ctx *WriteContext, buf *ByteBuffer, keys []reflect.Value, typeInfo *TypeInfo, flag byte) {
	ctxErr := ctx.Err()
	if typeInfo == nil && s.elemSerializer == nil {
		return
	}
	serializer := s.elemSerializer
	if serializer == nil {
		serializer = typeInfo.Serializer
	}
	trackRefs := (flag & CollectionTrackingRef) != 0 // Check if reference tracking is enabled
	hasNull := (flag & CollectionHasNull) != 0
	declaredGenerics := (flag & CollectionIsDeclElementType) != 0

	for _, key := range keys {
		key = UnwrapReflectValue(key)
		if isNull(key) {
			buf.WriteInt8(NullFlag) // WriteData null marker
			continue
		}

		if trackRefs {
			// Handle reference tracking if enabled
			refWritten, err := ctx.RefResolver().WriteRefOrNull(buf, key)
			if err != nil {
				ctxErr.SetError(err)
				return
			}
			if !refWritten {
				// WriteData actual value if not a reference
				writeSerializerData(ctx, serializer, declaredGenerics, key)
				if ctx.HasError() {
					return
				}
			}
		} else {
			// Directly write value without reference tracking
			if hasNull {
				// Same-type nullable entries still need one flag per element so
				// the reader can distinguish a null from a body.
				buf.WriteInt8(NotNullValueFlag)
			}
			writeSerializerData(ctx, serializer, declaredGenerics, key)
			if ctx.HasError() {
				return
			}
		}
	}
}

// writeDifferentTypes handles serialization of collections with mixed element types
func (s setSerializer) writeDifferentTypes(ctx *WriteContext, buf *ByteBuffer, keys []reflect.Value, flag byte) {
	ctxErr := ctx.Err()
	trackRefs := (flag & CollectionTrackingRef) != 0
	hasNull := (flag & CollectionHasNull) != 0

	for _, key := range keys {
		key = UnwrapReflectValue(key)
		if isNull(key) {
			buf.WriteInt8(NullFlag) // WriteData null marker
			continue
		}

		if trackRefs {
			// Write ref flag, type ID, and data
			refWritten, err := ctx.RefResolver().WriteRefOrNull(buf, key)
			if err != nil {
				ctxErr.SetError(err)
				return
			}
			if !refWritten {
				typeInfo, err := ctx.TypeResolver().GetTypeInfo(key, true)
				if err != nil {
					ctxErr.SetError(err)
					return
				}
				ctx.TypeResolver().WriteTypeInfo(buf, typeInfo, ctxErr)
				typeInfo.Serializer.WriteData(ctx, key)
				if ctx.HasError() {
					return
				}
			}
			continue
		}

		typeInfo, err := ctx.TypeResolver().GetTypeInfo(key, true)
		if err != nil {
			ctxErr.SetError(err)
			return
		}
		if hasNull {
			// No ref tracking but may have nulls - write NotNullValueFlag before type + data
			buf.WriteInt8(NotNullValueFlag)
			ctx.TypeResolver().WriteTypeInfo(buf, typeInfo, ctxErr)
			typeInfo.Serializer.WriteData(ctx, key)
			if ctx.HasError() {
				return
			}
		} else {
			// No ref tracking and no nulls - write type + data directly
			ctx.TypeResolver().WriteTypeInfo(buf, typeInfo, ctxErr)
			typeInfo.Serializer.WriteData(ctx, key)
			if ctx.HasError() {
				return
			}
		}
	}
}

// Read deserializes a set from the buffer into the provided reflect.Value
func (s setSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	if ctx.HasError() || !ctx.enterDepth() {
		return
	}
	buf := ctx.Buffer()
	err := ctx.Err()
	type_ := value.Type()
	// ReadData collection length from buffer
	length := ctx.ReadCollectionLength()
	if ctx.HasError() {
		return
	}
	keyBytes := s.keyBytes
	valueBytes := s.valueBytes
	elemBytes := keyBytes + valueBytes
	maxLength := s.maxLength
	if elemBytes < keyBytes {
		ctx.SetError(DeserializationErrorf("map entry size overflows: key=%d value=%d", keyBytes, valueBytes))
		return
	}
	if length == 0 {
		if !ctx.ReserveGraphMemory(int64(graphSetOwnerBytes)) {
			return
		}
		// Initialize empty set if length is 0
		value.Set(reflect.MakeMap(type_))
		ctx.RefResolver().Reference(value)
		ctx.decDepth()
		return
	}

	// ReadData collection flags that indicate special characteristics
	collectFlag := buf.ReadInt8(err)
	if ctx.HasError() {
		return
	}
	var elemTypeInfo *TypeInfo

	// If all elements are same type, get element type info
	if (collectFlag & CollectionIsSameType) != 0 {
		if (collectFlag & CollectionIsDeclElementType) != 0 {
			elemSerializer := s.elemSerializer
			if elemSerializer == nil {
				err.SetError(DeserializationError("declared set element serializer is unavailable"))
				return
			}
			elemTypeInfo = &TypeInfo{
				Type:       s.declaredElemType,
				Serializer: elemSerializer,
				ValueBytes: s.declaredElemBytes,
			}
		} else if s.elemSerializer != nil {
			ctx.TypeResolver().consumeTypeInfoForCodec(buf, err)
		} else {
			// Element type is not declared, read from buffer
			elemTypeInfo = ctx.TypeResolver().ReadTypeInfo(buf, err)
		}
	}
	if ctx.HasError() {
		return
	}
	bodySerializer := s.elemSerializer
	if bodySerializer == nil && elemTypeInfo != nil {
		bodySerializer = elemTypeInfo.Serializer
	}
	elementReadAlwaysAdvances := (collectFlag&CollectionIsSameType) == 0 ||
		(collectFlag&(CollectionTrackingRef|CollectionHasNull)) != 0 ||
		serializerReadDataAlwaysAdvances(bodySerializer)
	if elementReadAlwaysAdvances {
		if !buf.CheckReadable(length, err) {
			return
		}
	} else if !checkUnbackedContainerAllocation(ctx, length) {
		return
	}
	if length < 0 {
		ctx.SetError(DeserializationErrorf("negative graph element count: %d", length))
		return
	}
	if int64(length) > maxLength {
		ctx.SetError(DeserializationErrorf("graph memory estimate overflows: length=%d elementBytes=%d", length, elemBytes))
		return
	}
	if !ctx.ReserveGraphMemory(int64(graphSetOwnerBytes) + int64(length)*int64(elemBytes)) {
		return
	}

	// Initialize set if nil
	if value.IsNil() {
		value.Set(reflect.MakeMapWithSize(type_, length))
	}
	// Register reference for tracking (handles circular references)
	ctx.RefResolver().Reference(value)

	// Choose appropriate deserialization path based on type consistency
	if (collectFlag & CollectionIsSameType) != 0 {
		s.readSameType(ctx, buf, value, elemTypeInfo, collectFlag, length)
		if ctx.HasError() {
			return
		}
		ctx.decDepth()
		return
	}
	s.readDifferentTypes(ctx, buf, value, length, collectFlag)
	if ctx.HasError() {
		return
	}
	ctx.decDepth()
}

// readSameType handles deserialization of sets where all elements share the same type
func (s setSerializer) readSameType(ctx *ReadContext, buf *ByteBuffer, value reflect.Value, typeInfo *TypeInfo, flag int8, length int) {
	// Determine if reference tracking is enabled
	trackRefs := (flag & CollectionTrackingRef) != 0
	declaredGenerics := (flag & CollectionIsDeclElementType) != 0
	hasNull := (flag & CollectionHasNull) != 0
	serializer := s.elemSerializer
	keyType := value.Type().Key()
	ctxErr := ctx.Err()
	elemType := s.declaredElemType
	// An explicitly selected element codec owns the body. The TypeInfo read
	// from the header supplies wire identity only and must not replace it.
	if !declaredGenerics && typeInfo != nil && s.elemSerializer == nil {
		elemType, serializer = wrapMapSerializerIfNeeded(
			ctx, keyType, typeInfo.Type, typeInfo.Serializer, typeInfo.ValueBytes)
		if ctx.HasError() {
			return
		}
	}
	if keyType.Kind() != reflect.Ptr && keyType.Kind() != reflect.Interface {
		if ptrSer, ok := serializer.(*ptrToValueSerializer); ok {
			serializer = ptrSer.valueSerializer
		}
	}
	declaredGenericDispatch := declaredGenerics && serializerNeedsGenericDispatch(serializer)
	boxedStructBytes := int64(0)
	if keyType.Kind() == reflect.Interface && elemType.Kind() == reflect.Struct {
		// Interface set keys can box struct values; pointer wrappers reserve their own pointee.
		if _, pointerOwner := serializer.(*ptrToValueSerializer); !pointerOwner {
			if s.elemSerializer != nil && s.declaredElemBytes > 0 {
				boxedStructBytes = int64(s.declaredElemBytes)
			} else if s.elemSerializer == nil && typeInfo != nil && typeInfo.ValueBytes > 0 {
				boxedStructBytes = int64(typeInfo.ValueBytes)
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
			readSerializerData(ctx, serializer, declaredGenericDispatch, elem)
			if ctx.HasError() {
				return
			}
			if !setMapKey(ctx, value, elem, keyType) {
				return
			}
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
				ctxErr.SetError(refErr)
				return
			}
			if refID == int32(NullFlag) {
				if !setNullKey(ctx, value, keyType) {
					return
				}
				continue
			}
			if refID < int32(NotNullValueFlag) {
				elem := ctx.RefResolver().GetReadObject(refID)
				if !setMapKey(ctx, value, elem, keyType) {
					return
				}
				continue
			}
			if boxedStructBytes > 0 && !ctx.ReserveGraphMemory(boxedStructBytes) {
				return
			}
			elem := reflect.New(elemType).Elem()
			readSerializerData(ctx, serializer, declaredGenericDispatch, elem)
			if ctx.HasError() {
				return
			}
			if isNull(elem) {
				continue
			}
			if !publishReadRef(ctx, refID, elem) || !setMapKey(ctx, value, elem, keyType) {
				return
			}
		} else if hasNull {
			refFlag := buf.ReadInt8(ctxErr)
			if ctxErr.HasError() {
				return
			}
			if refFlag == NullFlag {
				if !setNullKey(ctx, value, keyType) {
					return
				}
				continue
			}
			if boxedStructBytes > 0 && !ctx.ReserveGraphMemory(boxedStructBytes) {
				return
			}
			elem := reflect.New(elemType).Elem()
			readSerializerData(ctx, serializer, declaredGenericDispatch, elem)
			if ctx.HasError() {
				return
			}
			if !setMapKey(ctx, value, elem, keyType) {
				return
			}
		} else {
			if boxedStructBytes > 0 && !ctx.ReserveGraphMemory(boxedStructBytes) {
				return
			}
			elem := reflect.New(elemType).Elem()
			readSerializerData(ctx, serializer, declaredGenericDispatch, elem)
			if ctx.HasError() {
				return
			}
			if !setMapKey(ctx, value, elem, keyType) {
				return
			}
		}
	}
}

// readDifferentTypes handles deserialization of sets with mixed element types
func (s setSerializer) readDifferentTypes(ctx *ReadContext, buf *ByteBuffer, value reflect.Value, length int, flag int8) {
	trackRefs := (flag & CollectionTrackingRef) != 0
	hasNull := (flag & CollectionHasNull) != 0
	keyType := value.Type().Key()
	ctxErr := ctx.Err()

	for i := 0; i < length; i++ {
		refID := int32(-1)
		if trackRefs {
			var refErr error
			refID, refErr = ctx.RefResolver().TryPreserveRefId(buf)
			if refErr != nil {
				ctxErr.SetError(refErr)
				return
			}
			if refID == int32(NullFlag) {
				if !setNullKey(ctx, value, keyType) {
					return
				}
				continue
			}
			if refID < int32(NotNullValueFlag) {
				elem := ctx.RefResolver().GetReadObject(refID)
				if !setMapKey(ctx, value, elem, keyType) {
					return
				}
				continue
			}
		} else if hasNull {
			headFlag := buf.ReadInt8(ctxErr)
			if ctxErr.HasError() {
				return
			}
			if headFlag == NullFlag {
				if !setNullKey(ctx, value, keyType) {
					return
				}
				continue
			}
		}
		typeInfo := ctx.TypeResolver().ReadTypeInfo(buf, ctxErr)
		if ctxErr.HasError() {
			return
		}
		valueBytes := typeInfo.ValueBytes
		if valueBytes == 0 {
			if structSer, ok := typeInfo.Serializer.(*structSerializer); ok {
				valueBytes = structSer.valueBytes
			}
		}
		elemType, serializer := wrapMapSerializerIfNeeded(
			ctx, keyType, typeInfo.Type, typeInfo.Serializer, valueBytes)
		if ctx.HasError() {
			return
		}
		if keyType.Kind() == reflect.Interface && typeInfo.Type != nil && typeInfo.Type.Kind() == reflect.Struct {
			// Interface set keys can box struct values; pointer wrappers reserve their own pointee.
			if _, pointerOwner := serializer.(*ptrToValueSerializer); !pointerOwner && valueBytes > 0 {
				if !ctx.ReserveGraphMemory(int64(valueBytes)) {
					return
				}
			}
		}
		elem := reflect.New(elemType).Elem()
		serializer.ReadData(ctx, elem)
		if ctx.HasError() {
			return
		}
		if trackRefs {
			if !publishReadRef(ctx, refID, elem) {
				return
			}
		}
		if !setMapKey(ctx, value, elem, keyType) {
			return
		}
	}
}

func setNullKey(ctx *ReadContext, value reflect.Value, keyType reflect.Type) bool {
	key := reflect.Zero(keyType)
	if !isNull(key) {
		ctxErr := ctx.Err()
		ctxErr.SetError(DeserializationErrorf(
			"set element type %v cannot represent null", keyType))
		return false
	}
	return setMapKey(ctx, value, key, keyType)
}

// setMapKey sets a key into a map (set), handling interface types where
// the concrete type may need to be wrapped in a pointer to implement the interface.
func setMapKey(ctx *ReadContext, mapValue, key reflect.Value, keyType reflect.Type) bool {
	if !key.IsValid() {
		ctx.SetError(DeserializationError("set element reference is invalid"))
		return false
	}
	finalKey := key
	if !key.Type().AssignableTo(keyType) {
		if keyType.Kind() == reflect.Interface && key.Kind() != reflect.Ptr {
			ptrType := reflect.PtrTo(key.Type())
			if ptrType.AssignableTo(keyType) {
				ptr := reflect.New(key.Type())
				ptr.Elem().Set(key)
				finalKey = ptr
			}
		}
		if finalKey == key {
			ctx.SetError(DeserializationErrorf(
				"set element type %v is not assignable to %v", key.Type(), keyType))
			return false
		}
	}
	if !finalKey.Type().Comparable() {
		ctx.SetError(DeserializationErrorf("set element type %v is not comparable", finalKey.Type()))
		return false
	}
	mapValue.SetMapIndex(finalKey, emptyStructVal)
	return true
}

func (s setSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	if refMode != RefModeNone {
		refID, refErr := ctx.RefResolver().TryPreserveRefId(buf)
		if refErr != nil {
			ctx.SetError(FromError(refErr))
			return
		}
		if refID < int32(NotNullValueFlag) {
			// Reference found or null
			if refID != int32(NullFlag) {
				assignReadRef(ctx, refID, value)
			}
			return
		}
	}
	if readType {
		// Read and discard type ID for sets
		typeID := uint32(buf.ReadUint8(ctxErr))
		if ctx.HasError() {
			return
		}
		if typeID != uint32(SET) {
			ctx.SetError(DeserializationErrorf("set type mismatch: expected SET (%d), got %d", SET, typeID))
			return
		}
	}
	s.ReadData(ctx, value)
}

func (s setSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}
