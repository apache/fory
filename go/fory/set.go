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
}

func (s setSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	s.writeDataWithGenerics(ctx, value, false)
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
	s.writeDataWithGenerics(ctx, value, hasGenerics)
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

	// Check elements to detect types
	// Initialize element type information from first non-null element
	if len(keys) > 0 {
		firstElem := UnwrapReflectValue(keys[0])
		if isNull(firstElem) {
			hasNull = true
		} else {
			// Get type info for first element to use as reference
			elemTypeInfo, _ = ctx.TypeResolver().getTypeInfo(firstElem, true)
		}
	}

	// Iterate through elements to check for nulls and type consistency
	for _, key := range keys {
		key = UnwrapReflectValue(key)
		if isNull(key) {
			hasNull = true
			continue
		}

		// Compare each element's type with the reference type
		currentTypeInfo, _ := ctx.TypeResolver().getTypeInfo(key, true)
		var elemTypeID, currentTypeID uint32
		if elemTypeInfo != nil {
			elemTypeID = elemTypeInfo.TypeID
		}
		if currentTypeInfo != nil {
			currentTypeID = currentTypeInfo.TypeID
		}
		if currentTypeID != elemTypeID {
			hasSameType = false
		}
	}

	// Set collection flags based on findings
	if hasNull {
		collectFlag |= CollectionHasNull // Mark if collection contains null values
	}
	if hasSameType {
		collectFlag |= CollectionIsSameType // Mark if elements have same type
	}
	// When hasGenerics is true, element type is declared from schema (known at compile time)
	// so we don't need to write the element type ID
	if hasGenerics {
		collectFlag |= CollectionIsDeclElementType
	}

	// Enable reference tracking if configured
	if ctx.TrackRef() {
		collectFlag |= CollectionTrackingRef
	}

	// WriteData metadata to buffer
	buf.WriteVarUint32(uint32(len(keys))) // Collection size
	buf.WriteInt8(int8(collectFlag))      // Collection flags

	// WriteData element type ID only if:
	// 1. All elements have same type (IS_SAME_TYPE is set)
	// 2. Element type is NOT declared from schema (IS_DECL_ELEMENT_TYPE is NOT set)
	if hasSameType && !hasGenerics && elemTypeInfo != nil {
		ctx.TypeResolver().WriteTypeInfo(buf, elemTypeInfo, ctx.Err())
	}

	return byte(collectFlag), elemTypeInfo
}

// writeSameType efficiently serializes a collection where all elements share the same type
func (s setSerializer) writeSameType(ctx *WriteContext, buf *ByteBuffer, keys []reflect.Value, typeInfo *TypeInfo, flag byte) {
	if typeInfo == nil {
		return
	}
	serializer := typeInfo.Serializer
	trackRefs := (flag & CollectionTrackingRef) != 0 // Check if reference tracking is enabled

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
				ctx.SetError(FromError(err))
				return
			}
			if !refWritten {
				// WriteData actual value if not a reference
				serializer.WriteData(ctx, key)
				if ctx.HasError() {
					return
				}
			}
		} else {
			// Directly write value without reference tracking
			serializer.WriteData(ctx, key)
			if ctx.HasError() {
				return
			}
		}
	}
}

// writeDifferentTypes handles serialization of collections with mixed element types
func (s setSerializer) writeDifferentTypes(ctx *WriteContext, buf *ByteBuffer, keys []reflect.Value, flag byte) {
	trackRefs := (flag & CollectionTrackingRef) != 0
	hasNull := (flag & CollectionHasNull) != 0

	for _, key := range keys {
		key = UnwrapReflectValue(key)
		if isNull(key) {
			buf.WriteInt8(NullFlag) // WriteData null marker
			continue
		}

		// Get type info for each element (since types vary)
		typeInfo, _ := ctx.TypeResolver().getTypeInfo(key, true)

		if trackRefs {
			// Write ref flag, type ID, and data
			refWritten, err := ctx.RefResolver().WriteRefOrNull(buf, key)
			if err != nil {
				ctx.SetError(FromError(err))
				return
			}
			ctx.TypeResolver().WriteTypeInfo(buf, typeInfo, ctx.Err())
			if !refWritten {
				typeInfo.Serializer.WriteData(ctx, key)
				if ctx.HasError() {
					return
				}
			}
		} else if hasNull {
			// No ref tracking but may have nulls - write NotNullValueFlag before type + data
			buf.WriteInt8(NotNullValueFlag)
			ctx.TypeResolver().WriteTypeInfo(buf, typeInfo, ctx.Err())
			typeInfo.Serializer.WriteData(ctx, key)
			if ctx.HasError() {
				return
			}
		} else {
			// No ref tracking and no nulls - write type + data directly
			ctx.TypeResolver().WriteTypeInfo(buf, typeInfo, ctx.Err())
			typeInfo.Serializer.WriteData(ctx, key)
			if ctx.HasError() {
				return
			}
		}
	}
}

// Read deserializes a set from the buffer into the provided reflect.Value
func (s setSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	err := ctx.Err()
	type_ := value.Type()
	// ReadData collection length from buffer
	length := int(buf.ReadVarUint32(err))
	if length == 0 {
		// Initialize empty set if length is 0
		value.Set(reflect.MakeMap(type_))
		return
	}

	// ReadData collection flags that indicate special characteristics
	collectFlag := buf.ReadInt8(err)
	var elemTypeInfo *TypeInfo

	// If all elements are same type, get element type info
	if (collectFlag & CollectionIsSameType) != 0 {
		if (collectFlag & CollectionIsDeclElementType) != 0 {
			// Element type is declared in schema, derive from Go type's key type
			keyType := type_.Key()
			elemTypeInfo, _ = ctx.TypeResolver().getTypeInfo(reflect.New(keyType).Elem(), true)
		} else {
			// Element type is not declared, read from buffer
			elemTypeInfo = ctx.TypeResolver().ReadTypeInfo(buf, err)
		}
	}

	// Initialize set if nil
	if value.IsNil() {
		value.Set(reflect.MakeMap(type_))
	}
	// Register reference for tracking (handles circular references)
	ctx.RefResolver().Reference(value)

	// Choose appropriate deserialization path based on type consistency
	if (collectFlag & CollectionIsSameType) != 0 {
		s.readSameType(ctx, buf, value, elemTypeInfo, collectFlag, length)
		return
	}
	s.readDifferentTypes(ctx, buf, value, length, collectFlag)
}

// readSameType handles deserialization of sets where all elements share the same type
func (s setSerializer) readSameType(ctx *ReadContext, buf *ByteBuffer, value reflect.Value, typeInfo *TypeInfo, flag int8, length int) {
	// Determine if reference tracking is enabled
	trackRefs := (flag & CollectionTrackingRef) != 0
	serializer := typeInfo.Serializer

	for i := 0; i < length; i++ {
		var refID int32
		if trackRefs {
			// Handle reference tracking if enabled
			refID, _ = ctx.RefResolver().TryPreserveRefId(buf)
			if refID < int32(NotNullValueFlag) {
				// Use existing reference if available
				elem := ctx.RefResolver().GetReadObject(refID)
				value.SetMapIndex(reflect.ValueOf(elem), emptyStructVal)
				continue
			}
		}

		// Create new element and deserialize from buffer
		elem := reflect.New(typeInfo.Type).Elem()
		serializer.ReadData(ctx, elem)
		if ctx.HasError() {
			return
		}
		// Register new reference if tracking
		if trackRefs {
			ctx.RefResolver().SetReadObject(refID, elem)
		}
		// Add element to set
		value.SetMapIndex(elem, emptyStructVal)
	}
}

// readDifferentTypes handles deserialization of sets with mixed element types
func (s setSerializer) readDifferentTypes(ctx *ReadContext, buf *ByteBuffer, value reflect.Value, length int, flag int8) {
	trackRefs := (flag & CollectionTrackingRef) != 0
	hasNull := (flag & CollectionHasNull) != 0
	keyType := value.Type().Key()
	ctxErr := ctx.Err()

	for i := 0; i < length; i++ {
		if trackRefs {
			// Read with ref tracking: refFlag + typeId + data
			refID, refErr := ctx.RefResolver().TryPreserveRefId(buf)
			if refErr != nil {
				ctx.SetError(FromError(refErr))
				return
			}
			if refID == int32(NullFlag) {
				// Null element - skip for sets
				continue
			}
			if refID < int32(NotNullValueFlag) {
				// Use existing reference if available
				elem := ctx.RefResolver().GetReadObject(refID)
				value.SetMapIndex(elem, emptyStructVal)
				continue
			}
			// Read type info (handles namespaced types, meta sharing, etc.)
			typeInfo := ctx.TypeResolver().ReadTypeInfo(buf, ctxErr)
			if ctxErr.HasError() {
				return
			}
			// Create new element and deserialize from buffer
			elem := reflect.New(typeInfo.Type).Elem()
			typeInfo.Serializer.ReadData(ctx, elem)
			if ctx.HasError() {
				return
			}
			ctx.RefResolver().SetReadObject(refID, elem)
			setMapKey(value, elem, keyType)
		} else if hasNull {
			// No ref tracking but may have nulls: headFlag + typeId + data (or just NullFlag)
			headFlag := buf.ReadInt8(ctxErr)
			if headFlag == NullFlag {
				// Null element - skip for sets
				continue
			}
			// headFlag should be NotNullValueFlag, read type info
			typeInfo := ctx.TypeResolver().ReadTypeInfo(buf, ctxErr)
			if ctxErr.HasError() {
				return
			}
			elem := reflect.New(typeInfo.Type).Elem()
			typeInfo.Serializer.ReadData(ctx, elem)
			if ctx.HasError() {
				return
			}
			setMapKey(value, elem, keyType)
		} else {
			// No ref tracking and no nulls: typeId + data directly
			typeInfo := ctx.TypeResolver().ReadTypeInfo(buf, ctxErr)
			if ctxErr.HasError() {
				return
			}
			elem := reflect.New(typeInfo.Type).Elem()
			typeInfo.Serializer.ReadData(ctx, elem)
			if ctx.HasError() {
				return
			}
			setMapKey(value, elem, keyType)
		}
	}
}

// setMapKey sets a key into a map (set), handling interface types where
// the concrete type may need to be wrapped in a pointer to implement the interface.
func setMapKey(mapValue, key reflect.Value, keyType reflect.Type) {
	if keyType.Kind() == reflect.Interface {
		// Check if key is directly assignable to the interface
		if key.Type().AssignableTo(keyType) {
			mapValue.SetMapIndex(key, emptyStructVal)
		} else {
			// Try pointer - common case where interface has pointer receivers
			ptr := reflect.New(key.Type())
			ptr.Elem().Set(key)
			mapValue.SetMapIndex(ptr, emptyStructVal)
		}
	} else {
		mapValue.SetMapIndex(key, emptyStructVal)
	}
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
			obj := ctx.RefResolver().GetReadObject(refID)
			if obj.IsValid() {
				value.Set(obj)
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
