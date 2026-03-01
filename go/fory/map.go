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

// Map chunk header flags
const (
	TRACKING_KEY_REF   = 1 << 0 // 0b00000001
	KEY_HAS_NULL       = 1 << 1 // 0b00000010
	KEY_DECL_TYPE      = 1 << 2 // 0b00000100
	TRACKING_VALUE_REF = 1 << 3 // 0b00001000
	VALUE_HAS_NULL     = 1 << 4 // 0b00010000
	VALUE_DECL_TYPE    = 1 << 5 // 0b00100000
	MAX_CHUNK_SIZE     = 255
)

// Combined header constants for null entry cases
const (
	KV_NULL                               = KEY_HAS_NULL | VALUE_HAS_NULL
	NULL_KEY_VALUE_DECL_TYPE              = KEY_HAS_NULL | VALUE_DECL_TYPE
	NULL_KEY_VALUE_DECL_TYPE_TRACKING_REF = KEY_HAS_NULL | VALUE_DECL_TYPE | TRACKING_VALUE_REF
	NULL_VALUE_KEY_DECL_TYPE              = VALUE_HAS_NULL | KEY_DECL_TYPE
	NULL_VALUE_KEY_DECL_TYPE_TRACKING_REF = VALUE_HAS_NULL | KEY_DECL_TYPE | TRACKING_KEY_REF
)

type mapSerializer struct {
	type_             reflect.Type
	keySerializer     Serializer
	valueSerializer   Serializer
	keyReferencable   bool
	valueReferencable bool
	hasGenerics       bool // True when map is a struct field with declared key/value types
}

// Write handles ref tracking and type writing, then delegates to WriteData
func (s mapSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	if writeMapRefAndType(ctx, refMode, writeType, value) || ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

// WriteData serializes map data using chunk protocol
func (s mapSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	buf := ctx.Buffer()
	value = unwrapInterface(value)
	length := value.Len()
	buf.WriteVarUint32(uint32(length))
	if length == 0 {
		return
	}

	iter := value.MapRange()
	if !iter.Next() {
		return
	}

	typeResolver := ctx.TypeResolver()
	trackRef := ctx.TrackRef()
	entryKey := unwrapInterface(iter.Key())
	entryVal := unwrapInterface(iter.Value())
	hasNext := true

	for hasNext {
		// Phase 1: Handle null entries (single-item chunks)
		for {
			keyValid := entryKey.IsValid()
			valValid := entryVal.IsValid()

			if keyValid && valValid {
				break // Proceed to regular chunk
			}

			if !keyValid && !valValid {
				buf.WriteInt8(KV_NULL)
			} else if !valValid {
				s.writeNullValueEntry(ctx, entryKey, typeResolver, trackRef)
			} else {
				s.writeNullKeyEntry(ctx, entryVal, typeResolver, trackRef)
			}

			if ctx.HasError() {
				return
			}

			if iter.Next() {
				entryKey = unwrapInterface(iter.Key())
				entryVal = unwrapInterface(iter.Value())
			} else {
				return
			}
		}

		// Phase 2: Write regular chunk with same-type entries
		hasNext = s.writeChunk(ctx, iter, &entryKey, &entryVal, typeResolver, trackRef)
		if ctx.HasError() {
			return
		}
	}
}

// writeNullValueEntry writes a single entry where the value is null
func (s mapSerializer) writeNullValueEntry(ctx *WriteContext, key reflect.Value, resolver *TypeResolver, trackRef bool) {
	buf := ctx.Buffer()

	if s.hasGenerics && s.keySerializer != nil {
		if s.keyReferencable && trackRef {
			buf.WriteInt8(NULL_VALUE_KEY_DECL_TYPE_TRACKING_REF)
			s.keySerializer.Write(ctx, RefModeTracking, false, false, key)
		} else {
			buf.WriteInt8(NULL_VALUE_KEY_DECL_TYPE)
			s.keySerializer.WriteData(ctx, key)
		}
		return
	}

	// Polymorphic key
	keyTypeInfo, err := getTypeInfoForValue(key, resolver)
	if err != nil {
		ctx.SetError(FromError(err))
		return
	}

	header := int8(VALUE_HAS_NULL)
	writeKeyRef := trackRef && s.keyReferencable && keyTypeInfo.NeedWriteRef
	if writeKeyRef {
		header |= TRACKING_KEY_REF
	}
	buf.WriteInt8(header)
	resolver.WriteTypeInfo(buf, keyTypeInfo, ctx.Err())

	refMode := RefModeNone
	if writeKeyRef {
		refMode = RefModeTracking
	}
	keyTypeInfo.Serializer.Write(ctx, refMode, false, false, key)
}

// writeNullKeyEntry writes a single entry where the key is null
func (s mapSerializer) writeNullKeyEntry(ctx *WriteContext, value reflect.Value, resolver *TypeResolver, trackRef bool) {
	buf := ctx.Buffer()

	if s.hasGenerics && s.valueSerializer != nil {
		if s.valueReferencable && trackRef {
			buf.WriteInt8(NULL_KEY_VALUE_DECL_TYPE_TRACKING_REF)
			s.valueSerializer.Write(ctx, RefModeTracking, false, false, value)
		} else {
			buf.WriteInt8(NULL_KEY_VALUE_DECL_TYPE)
			s.valueSerializer.WriteData(ctx, value)
		}
		return
	}

	// Polymorphic value
	valueTypeInfo, err := getTypeInfoForValue(value, resolver)
	if err != nil {
		ctx.SetError(FromError(err))
		return
	}

	header := int8(KEY_HAS_NULL)
	writeValueRef := trackRef && s.valueReferencable && valueTypeInfo.NeedWriteRef
	if writeValueRef {
		header |= TRACKING_VALUE_REF
	}
	buf.WriteInt8(header)
	resolver.WriteTypeInfo(buf, valueTypeInfo, ctx.Err())

	refMode := RefModeNone
	if writeValueRef {
		refMode = RefModeTracking
	}
	valueTypeInfo.Serializer.Write(ctx, refMode, false, false, value)
}

// writeChunk writes a chunk of entries with the same key/value types
func (s mapSerializer) writeChunk(ctx *WriteContext, iter *reflect.MapIter, entryKey, entryVal *reflect.Value, resolver *TypeResolver, trackRef bool) bool {
	buf := ctx.Buffer()
	keyType := (*entryKey).Type()
	valueType := (*entryVal).Type()

	// Reserve space: header (1 byte) + size (1 byte)
	headerOffset := buf.writerIndex
	buf.WriteInt16(-1)

	header := 0
	var keySer, valSer Serializer
	keyWriteRef := s.keyReferencable
	valueWriteRef := s.valueReferencable

	// Determine key serializer and write type info if needed
	if s.hasGenerics && s.keySerializer != nil {
		header |= KEY_DECL_TYPE
		keySer = s.keySerializer
	} else {
		keyTypeInfo, _ := getTypeInfoForValue(*entryKey, resolver)
		resolver.WriteTypeInfo(buf, keyTypeInfo, ctx.Err())
		keySer = keyTypeInfo.Serializer
		keyWriteRef = s.keyReferencable && keyTypeInfo.NeedWriteRef
	}

	// Determine value serializer and write type info if needed
	if s.hasGenerics && s.valueSerializer != nil {
		header |= VALUE_DECL_TYPE
		valSer = s.valueSerializer
	} else {
		valueTypeInfo, _ := getTypeInfoForValue(*entryVal, resolver)
		resolver.WriteTypeInfo(buf, valueTypeInfo, ctx.Err())
		valSer = valueTypeInfo.Serializer
		valueWriteRef = s.valueReferencable && valueTypeInfo.NeedWriteRef
	}

	// Set ref tracking flags
	keyRefMode := RefModeNone
	if keyWriteRef && trackRef {
		header |= TRACKING_KEY_REF
		keyRefMode = RefModeTracking
	}
	valueRefMode := RefModeNone
	if valueWriteRef && trackRef {
		header |= TRACKING_VALUE_REF
		valueRefMode = RefModeTracking
	}

	buf.PutUint8(headerOffset, uint8(header))

	// Write entries with same type
	chunkSize := 0
	for chunkSize < MAX_CHUNK_SIZE {
		k := *entryKey
		v := *entryVal

		// Break if null or type changed
		if !k.IsValid() || !v.IsValid() || k.Type() != keyType || v.Type() != valueType {
			break
		}

		keySer.Write(ctx, keyRefMode, false, false, k)
		if ctx.HasError() {
			return false
		}
		valSer.Write(ctx, valueRefMode, false, false, v)
		if ctx.HasError() {
			return false
		}
		chunkSize++

		if iter.Next() {
			*entryKey = unwrapInterface(iter.Key())
			*entryVal = unwrapInterface(iter.Value())
		} else {
			buf.PutUint8(headerOffset+1, uint8(chunkSize))
			return false
		}
	}

	buf.PutUint8(headerOffset+1, uint8(chunkSize))
	return true
}

// Read handles ref tracking and type reading, then delegates to ReadData
func (s mapSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	if readMapRefAndType(ctx, refMode, readType, value) || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

// ReadData deserializes map data using chunk protocol
func (s mapSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	refResolver := ctx.RefResolver()
	typeResolver := ctx.TypeResolver()
	type_ := value.Type()

	// Initialize map
	if value.IsNil() {
		mapType := type_
		// For any maps without declared types, use map[any]any
		if !s.hasGenerics && type_.Key().Kind() == reflect.Interface && type_.Elem().Kind() == reflect.Interface {
			iface := reflect.TypeOf((*any)(nil)).Elem()
			mapType = reflect.MapOf(iface, iface)
		}
		value.Set(reflect.MakeMap(mapType))
	}
	refResolver.Reference(value)

	size := int(buf.ReadVarUint32(ctxErr))
	ctx.checkCollectionSize(size)
	if size == 0 || ctx.HasError() {
		return
	}

	chunkHeader := buf.ReadUint8(ctxErr)
	if ctx.HasError() {
		return
	}

	keyType := type_.Key()
	valueType := type_.Elem()

	for size > 0 {
		// Phase 1: Handle null entries
		for {
			keyHasNull := (chunkHeader & KEY_HAS_NULL) != 0
			valueHasNull := (chunkHeader & VALUE_HAS_NULL) != 0

			if !keyHasNull && !valueHasNull {
				break // Proceed to regular chunk
			}

			if keyHasNull && valueHasNull {
				value.SetMapIndex(reflect.Zero(keyType), reflect.Zero(valueType))
			} else if valueHasNull {
				k := s.readNullValueEntry(ctx, chunkHeader, keyType, typeResolver, refResolver)
				if ctx.HasError() {
					return
				}
				value.SetMapIndex(k, reflect.Zero(valueType))
			} else {
				v := s.readNullKeyEntry(ctx, chunkHeader, valueType, typeResolver, refResolver)
				if ctx.HasError() {
					return
				}
				value.SetMapIndex(reflect.Zero(keyType), v)
			}

			size--
			if size == 0 {
				return
			}
			chunkHeader = buf.ReadUint8(ctxErr)
			if ctx.HasError() {
				return
			}
		}

		// Phase 2: Read regular chunk
		size = s.readChunk(ctx, value, chunkHeader, size, keyType, valueType, typeResolver)
		if ctx.HasError() {
			return
		}

		if size > 0 {
			chunkHeader = buf.ReadUint8(ctxErr)
			if ctx.HasError() {
				return
			}
		}
	}
}

// readNullValueEntry reads an entry where value is null, returns the key
func (s mapSerializer) readNullValueEntry(ctx *ReadContext, header uint8, keyType reflect.Type, resolver *TypeResolver, refResolver *RefResolver) reflect.Value {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	keyDeclared := (header & KEY_DECL_TYPE) != 0
	trackKeyRef := (header & TRACKING_KEY_REF) != 0

	return s.readSingleValue(ctx, buf, ctxErr, keyDeclared, trackKeyRef, keyType, s.keySerializer, resolver, refResolver)
}

// readNullKeyEntry reads an entry where key is null, returns the value
func (s mapSerializer) readNullKeyEntry(ctx *ReadContext, header uint8, valueType reflect.Type, resolver *TypeResolver, refResolver *RefResolver) reflect.Value {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	valueDeclared := (header & VALUE_DECL_TYPE) != 0
	trackValueRef := (header & TRACKING_VALUE_REF) != 0

	return s.readSingleValue(ctx, buf, ctxErr, valueDeclared, trackValueRef, valueType, s.valueSerializer, resolver, refResolver)
}

// readSingleValue reads a single key or value with proper ref/type handling
func (s mapSerializer) readSingleValue(ctx *ReadContext, buf *ByteBuffer, ctxErr *Error, isDeclared, trackRef bool, staticType reflect.Type, declaredSer Serializer, resolver *TypeResolver, refResolver *RefResolver) reflect.Value {
	// When ref tracking AND not declared, ref flag comes before type info
	if trackRef && !isDeclared {
		refID, err := refResolver.TryPreserveRefId(buf)
		if err != nil {
			ctx.SetError(FromError(err))
			return reflect.Value{}
		}
		if refID < int32(NotNullValueFlag) {
			return refResolver.GetReadObject(refID)
		}

		// Read type info and data
		ti := resolver.ReadTypeInfo(buf, ctxErr)
		if ctxErr.HasError() {
			return reflect.Value{}
		}

		ser := ti.Serializer
		valType := ti.Type
		if valType == nil {
			valType = staticType
		}
		valType, ser = wrapMapSerializerIfNeeded(staticType, valType, ser)
		v := reflect.New(valType).Elem()
		ser.ReadData(ctx, v)
		if ctx.HasError() {
			return reflect.Value{}
		}
		if _, ok := ser.(*ptrToValueSerializer); !ok {
			refResolver.Reference(v)
		}
		return v
	}

	// Read type info if not declared
	var typeInfo *TypeInfo
	var ser Serializer
	valType := staticType

	if !isDeclared {
		typeInfo = resolver.ReadTypeInfo(buf, ctxErr)
		if ctxErr.HasError() {
			return reflect.Value{}
		}
		ser = typeInfo.Serializer
		valType = typeInfo.Type
		valType, ser = wrapMapSerializerIfNeeded(staticType, valType, ser)
	} else {
		ser = declaredSer
		if ser == nil {
			ser, _ = resolver.getSerializerByType(staticType, false)
		}
	}

	if valType == nil {
		valType = staticType
	}
	v := reflect.New(valType).Elem()

	refMode := RefModeNone
	if trackRef {
		refMode = RefModeTracking
	}

	if typeInfo != nil {
		ser.ReadWithTypeInfo(ctx, refMode, typeInfo, v)
	} else {
		ser.Read(ctx, refMode, false, false, v)
	}

	return v
}

// readChunk reads a chunk of entries, returns remaining size
func (s mapSerializer) readChunk(ctx *ReadContext, mapVal reflect.Value, header uint8, size int, keyType, valueType reflect.Type, resolver *TypeResolver) int {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()

	trackKeyRef := (header & TRACKING_KEY_REF) != 0
	trackValRef := (header & TRACKING_VALUE_REF) != 0
	keyDeclType := (header & KEY_DECL_TYPE) != 0
	valDeclType := (header & VALUE_DECL_TYPE) != 0
	declaredKeyType := keyType
	declaredValueType := valueType

	chunkSize := int(buf.ReadUint8(ctxErr))
	if ctx.HasError() {
		return 0
	}

	// Read type info if not declared
	var keyTypeInfo, valueTypeInfo *TypeInfo
	var keySer, valSer Serializer

	if !keyDeclType {
		keyTypeInfo = resolver.ReadTypeInfo(buf, ctxErr)
		if ctxErr.HasError() {
			return 0
		}
		keySer = keyTypeInfo.Serializer
		keyType = keyTypeInfo.Type
		keyType, keySer = wrapMapSerializerIfNeeded(declaredKeyType, keyType, keySer)
	} else {
		keySer = s.keySerializer
		if keySer == nil {
			keySer, _ = resolver.getSerializerByType(keyType, false)
		}
	}

	if !valDeclType {
		valueTypeInfo = resolver.ReadTypeInfo(buf, ctxErr)
		if ctxErr.HasError() {
			return 0
		}
		valSer = valueTypeInfo.Serializer
		valueType = valueTypeInfo.Type
		valueType, valSer = wrapMapSerializerIfNeeded(declaredValueType, valueType, valSer)
	} else {
		valSer = s.valueSerializer
		if valSer == nil {
			valSer, _ = resolver.getSerializerByType(valueType, false)
		}
	}

	keyRefMode := RefModeNone
	if trackKeyRef {
		keyRefMode = RefModeTracking
	}
	valRefMode := RefModeNone
	if trackValRef {
		valRefMode = RefModeTracking
	}

	for i := 0; i < chunkSize; i++ {
		k := reflect.New(keyType).Elem()
		if keyTypeInfo != nil {
			keySer.ReadWithTypeInfo(ctx, keyRefMode, keyTypeInfo, k)
		} else {
			keySer.Read(ctx, keyRefMode, false, false, k)
		}
		if ctx.HasError() {
			return 0
		}

		v := reflect.New(valueType).Elem()
		if valueTypeInfo != nil {
			valSer.ReadWithTypeInfo(ctx, valRefMode, valueTypeInfo, v)
		} else {
			valSer.Read(ctx, valRefMode, false, false, v)
		}
		if ctx.HasError() {
			return 0
		}

		setMapValue(mapVal, unwrapInterface(k), unwrapInterface(v))
		size--
	}

	return size
}

func (s mapSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// Helper functions

// writeMapRefAndType handles reference and type writing for maps.
// Returns true if value was already written (nil or ref).
func writeMapRefAndType(ctx *WriteContext, refMode RefMode, writeType bool, value reflect.Value) bool {
	switch refMode {
	case RefModeTracking:
		if value.IsNil() {
			ctx.buffer.WriteInt8(NullFlag)
			return true
		}
		refWritten, err := ctx.RefResolver().WriteRefOrNull(ctx.buffer, value)
		if err != nil {
			ctx.SetError(FromError(err))
			return false
		}
		if refWritten {
			return true
		}
	case RefModeNullOnly:
		if value.IsNil() {
			ctx.buffer.WriteInt8(NullFlag)
			return true
		}
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(MAP))
	}
	return false
}

// readMapRefAndType handles reference and type reading for maps.
// Returns true if a reference was resolved.
func readMapRefAndType(ctx *ReadContext, refMode RefMode, readType bool, value reflect.Value) bool {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	switch refMode {
	case RefModeTracking:
		refID, err := ctx.RefResolver().TryPreserveRefId(buf)
		if err != nil {
			ctx.SetError(FromError(err))
			return false
		}
		if refID < int32(NotNullValueFlag) {
			obj := ctx.RefResolver().GetReadObject(refID)
			if obj.IsValid() {
				value.Set(obj)
			}
			return true
		}
	case RefModeNullOnly:
		flag := buf.ReadInt8(ctxErr)
		if flag == NullFlag {
			return true
		}
	}
	if readType {
		buf.ReadUint8(ctxErr)
	}
	return false
}

func unwrapInterface(v reflect.Value) reflect.Value {
	// For map serialization, we need to unwrap interfaces including nil ones
	// A nil interface should become an invalid (zero) Value for proper null detection
	if v.Kind() == reflect.Interface {
		return v.Elem()
	}
	return v
}

func wrapMapSerializerIfNeeded(declaredType, actualType reflect.Type, serializer Serializer) (reflect.Type, Serializer) {
	if declaredType == nil || actualType == nil || serializer == nil {
		return actualType, serializer
	}
	if declaredType.Kind() == reflect.Ptr {
		if actualType.Kind() == reflect.Ptr {
			return actualType, serializer
		}
		return reflect.PtrTo(actualType), &ptrToValueSerializer{valueSerializer: serializer}
	}
	if declaredType.Kind() == reflect.Interface {
		if actualType.AssignableTo(declaredType) {
			return actualType, serializer
		}
		if actualType.Kind() != reflect.Ptr {
			ptrType := reflect.PtrTo(actualType)
			if ptrType.AssignableTo(declaredType) {
				return ptrType, &ptrToValueSerializer{valueSerializer: serializer}
			}
		}
	}
	return actualType, serializer
}

// UnwrapReflectValue is exported for use by other packages
func UnwrapReflectValue(v reflect.Value) reflect.Value {
	return unwrapInterface(v)
}

func getTypeInfoForValue(v reflect.Value, resolver *TypeResolver) (*TypeInfo, error) {
	if v.Kind() == reflect.Interface && !v.IsNil() {
		elem := v.Elem()
		if !elem.IsValid() {
			return nil, fmt.Errorf("invalid interface value")
		}
		return resolver.getTypeInfo(elem, true)
	}
	return resolver.getTypeInfo(v, true)
}

// setMapValue sets a key-value pair into a map, handling interface types
func setMapValue(mapVal, key, value reflect.Value) {
	mapKeyType := mapVal.Type().Key()
	mapValueType := mapVal.Type().Elem()

	finalKey := key
	if mapKeyType.Kind() == reflect.Interface && !key.Type().AssignableTo(mapKeyType) {
		ptr := reflect.New(key.Type())
		ptr.Elem().Set(key)
		finalKey = ptr
	}

	finalValue := value
	if mapValueType.Kind() == reflect.Interface && !value.Type().AssignableTo(mapValueType) {
		ptr := reflect.New(value.Type())
		ptr.Elem().Set(value)
		finalValue = ptr
	}

	mapVal.SetMapIndex(finalKey, finalValue)
}
