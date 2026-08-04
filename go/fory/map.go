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
	type_ reflect.Type
	// Compatible interface maps retain the concrete child types selected by the
	// enclosing schema; declared chunks omit TypeInfo and must materialize these.
	declaredKeyType   reflect.Type
	declaredValueType reflect.Type
	// These charge concrete value storage retained behind interface map slots;
	// keyBytes and valueBytes below account for the slots themselves.
	declaredKeyBytes   int
	declaredValueBytes int
	keySerializer      Serializer
	valueSerializer    Serializer
	keyReferencable    bool
	valueReferencable  bool
	hasGenerics        bool // True when map is a struct field with declared key/value types
	keyBytes           int
	valueBytes         int
	maxLength          int64
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
			keyNull := isNull(entryKey)
			valueNull := isNull(entryVal)

			if !keyNull && !valueNull {
				break // Proceed to regular chunk
			}

			if keyNull && valueNull {
				buf.WriteInt8(KV_NULL)
			} else if valueNull {
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
	ctxErr := ctx.Err()

	if s.hasGenerics && s.keySerializer != nil {
		if s.keyReferencable && trackRef {
			buf.WriteInt8(NULL_VALUE_KEY_DECL_TYPE_TRACKING_REF)
			s.keySerializer.Write(ctx, RefModeTracking, false, true, key)
		} else {
			buf.WriteInt8(NULL_VALUE_KEY_DECL_TYPE)
			writeSerializerData(ctx, s.keySerializer, true, key)
		}
		return
	}

	// Polymorphic key
	keyTypeInfo, err := getTypeInfoForValue(key, resolver)
	if err != nil {
		ctxErr.SetError(err)
		return
	}

	header := int8(VALUE_HAS_NULL)
	writeKeyRef := trackRef && s.keyReferencable && keyTypeInfo.NeedWriteRef
	if writeKeyRef {
		header |= TRACKING_KEY_REF
	}
	buf.WriteInt8(header)
	// A polymorphic null chunk uses complete-field order: reference envelope,
	// TypeInfo for a new value, then the value body.
	if writeKeyRef {
		refWritten, err := ctx.RefResolver().WriteRefOrNull(buf, key)
		if err != nil {
			ctxErr.SetError(err)
			return
		}
		if refWritten {
			return
		}
	}
	resolver.WriteTypeInfo(buf, keyTypeInfo, ctxErr)
	if ctxErr.HasError() {
		return
	}
	keyTypeInfo.Serializer.WriteData(ctx, key)
}

// writeNullKeyEntry writes a single entry where the key is null
func (s mapSerializer) writeNullKeyEntry(ctx *WriteContext, value reflect.Value, resolver *TypeResolver, trackRef bool) {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()

	if s.hasGenerics && s.valueSerializer != nil {
		if s.valueReferencable && trackRef {
			buf.WriteInt8(NULL_KEY_VALUE_DECL_TYPE_TRACKING_REF)
			s.valueSerializer.Write(ctx, RefModeTracking, false, true, value)
		} else {
			buf.WriteInt8(NULL_KEY_VALUE_DECL_TYPE)
			writeSerializerData(ctx, s.valueSerializer, true, value)
		}
		return
	}

	// Polymorphic value
	valueTypeInfo, err := getTypeInfoForValue(value, resolver)
	if err != nil {
		ctxErr.SetError(err)
		return
	}

	header := int8(KEY_HAS_NULL)
	writeValueRef := trackRef && s.valueReferencable && valueTypeInfo.NeedWriteRef
	if writeValueRef {
		header |= TRACKING_VALUE_REF
	}
	buf.WriteInt8(header)
	// A polymorphic null chunk uses complete-field order: reference envelope,
	// TypeInfo for a new value, then the value body.
	if writeValueRef {
		refWritten, err := ctx.RefResolver().WriteRefOrNull(buf, value)
		if err != nil {
			ctxErr.SetError(err)
			return
		}
		if refWritten {
			return
		}
	}
	resolver.WriteTypeInfo(buf, valueTypeInfo, ctxErr)
	if ctxErr.HasError() {
		return
	}
	valueTypeInfo.Serializer.WriteData(ctx, value)
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
		if isNull(k) || isNull(v) || k.Type() != keyType || v.Type() != valueType {
			break
		}

		keySer.Write(ctx, keyRefMode, false, (header&KEY_DECL_TYPE) != 0, k)
		if ctx.HasError() {
			return false
		}
		valSer.Write(ctx, valueRefMode, false, (header&VALUE_DECL_TYPE) != 0, v)
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
	if ctx.HasError() || !ctx.enterDepth() {
		return
	}
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	refResolver := ctx.RefResolver()
	typeResolver := ctx.TypeResolver()
	type_ := value.Type()

	size := ctx.ReadCollectionLength()
	if ctx.HasError() {
		return
	}
	mapType := type_
	// For any maps without declared types, use map[any]any.
	if !s.hasGenerics && type_.Key().Kind() == reflect.Interface && type_.Elem().Kind() == reflect.Interface {
		iface := reflect.TypeOf((*any)(nil)).Elem()
		mapType = reflect.MapOf(iface, iface)
	}
	keyBytes := s.keyBytes
	valueBytes := s.valueBytes
	elemBytes := keyBytes + valueBytes
	maxLength := s.maxLength
	if elemBytes < keyBytes {
		ctx.SetError(DeserializationErrorf("map entry size overflows: key=%d value=%d", keyBytes, valueBytes))
		return
	}
	if size < 0 {
		ctx.SetError(DeserializationErrorf("negative graph element count: %d", size))
		return
	}
	if int64(size) > maxLength {
		ctx.SetError(DeserializationErrorf("graph memory estimate overflows: length=%d elementBytes=%d", size, elemBytes))
		return
	}
	if !ctx.ReserveGraphMemory(int64(graphMapOwnerBytes) + int64(size)*int64(elemBytes)) {
		return
	}
	if size == 0 {
		if value.IsNil() {
			value.Set(reflect.MakeMap(mapType))
		}
		refResolver.Reference(value)
		ctx.decDepth()
		return
	}

	chunkHeader := buf.ReadUint8(ctxErr)
	if ctx.HasError() {
		return
	}
	// The first chunk header can back one entry. The remaining allocation must be
	// covered by unread bytes or the root's compact-container allowance.
	if !checkUnbackedContainerAllocation(ctx, size-1) {
		return
	}
	if value.IsNil() {
		value.Set(reflect.MakeMapWithSize(mapType, size))
	}
	refResolver.Reference(value)

	keyType := type_.Key()
	valueType := type_.Elem()

	for size > 0 {
		// Phase 1: Handle null entries
		for {
			keyHasNull := (chunkHeader & KEY_HAS_NULL) != 0
			valueHasNull := (chunkHeader & VALUE_HAS_NULL) != 0
			var nullKey reflect.Value
			var nullValue reflect.Value
			if keyHasNull {
				nullKey = reflect.Zero(keyType)
				if !isNull(nullKey) {
					ctxErr.SetError(DeserializationErrorf(
						"map key type %v cannot represent null", keyType))
					return
				}
			}
			if valueHasNull {
				nullValue = reflect.Zero(valueType)
				if !isNull(nullValue) {
					ctxErr.SetError(DeserializationErrorf(
						"map value type %v cannot represent null", valueType))
					return
				}
			}

			if !keyHasNull && !valueHasNull {
				break // Proceed to regular chunk
			}

			if keyHasNull && valueHasNull {
				if !setMapValue(ctx, value, nullKey, nullValue) {
					return
				}
			} else if valueHasNull {
				k := s.readNullValueEntry(ctx, chunkHeader, keyType, typeResolver, refResolver)
				if ctx.HasError() {
					return
				}
				if !setMapValue(ctx, value, unwrapInterface(k), nullValue) {
					return
				}
			} else {
				v := s.readNullKeyEntry(ctx, chunkHeader, valueType, typeResolver, refResolver)
				if ctx.HasError() {
					return
				}
				if !setMapValue(ctx, value, nullKey, unwrapInterface(v)) {
					return
				}
			}

			size--
			if size == 0 {
				ctx.decDepth()
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
	ctx.decDepth()
}

// readNullValueEntry reads an entry where value is null, returns the key
func (s mapSerializer) readNullValueEntry(ctx *ReadContext, header uint8, keyType reflect.Type, resolver *TypeResolver, refResolver *RefResolver) reflect.Value {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	keyDeclared := (header & KEY_DECL_TYPE) != 0
	trackKeyRef := (header & TRACKING_KEY_REF) != 0
	if keyDeclared {
		if s.declaredKeyType != nil && s.keySerializer != nil &&
			keyType.Kind() == reflect.Interface && s.declaredKeyType.Kind() == reflect.Struct {
			if _, pointerOwner := s.keySerializer.(*ptrToValueSerializer); !pointerOwner &&
				!reserveMapBox(ctx, int64(s.declaredKeyBytes), trackKeyRef) {
				return reflect.Value{}
			}
		}
		keyType = s.declaredKeyType
	}

	return s.readSingleValue(ctx, buf, ctxErr, keyDeclared, trackKeyRef, keyType, s.keySerializer, resolver, refResolver)
}

// readNullKeyEntry reads an entry where key is null, returns the value
func (s mapSerializer) readNullKeyEntry(ctx *ReadContext, header uint8, valueType reflect.Type, resolver *TypeResolver, refResolver *RefResolver) reflect.Value {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	valueDeclared := (header & VALUE_DECL_TYPE) != 0
	trackValueRef := (header & TRACKING_VALUE_REF) != 0
	if valueDeclared {
		if s.declaredValueType != nil && s.valueSerializer != nil &&
			valueType.Kind() == reflect.Interface && s.declaredValueType.Kind() == reflect.Struct {
			if _, pointerOwner := s.valueSerializer.(*ptrToValueSerializer); !pointerOwner &&
				!reserveMapBox(ctx, int64(s.declaredValueBytes), trackValueRef) {
				return reflect.Value{}
			}
		}
		valueType = s.declaredValueType
	}

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
			if refID == int32(NullFlag) {
				ctx.SetError(DeserializationError("map keys cannot be null"))
				return reflect.Value{}
			}
			value := refResolver.GetReadObject(refID)
			if !value.IsValid() {
				ctx.SetError(InvalidRefIdError(refID))
				return reflect.Value{}
			}
			if !value.Type().AssignableTo(staticType) {
				ctx.SetError(DeserializationErrorf(
					"map reference type %v is not assignable to %v", value.Type(), staticType))
				return reflect.Value{}
			}
			return value
		}

		// Read type info and data
		ti := resolver.ReadTypeInfo(buf, ctxErr)
		if ctxErr.HasError() {
			return reflect.Value{}
		}

		ser := ti.Serializer
		valType := ti.Type
		valType, ser = wrapMapSerializerIfNeeded(ctx, staticType, valType, ser, ti.ValueBytes)
		if ctx.HasError() {
			return reflect.Value{}
		}
		if staticType.Kind() == reflect.Interface && valType.Kind() == reflect.Struct {
			if _, pointerOwner := ser.(*ptrToValueSerializer); !pointerOwner {
				valueBytes := ti.ValueBytes
				if valueBytes == 0 {
					if structSer, ok := ser.(*structSerializer); ok {
						valueBytes = structSer.valueBytes
					}
				}
				if valueBytes > 0 && !ctx.ReserveGraphMemory(int64(valueBytes)) {
					return reflect.Value{}
				}
			}
		}
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
		valType, ser = wrapMapSerializerIfNeeded(
			ctx, staticType, valType, ser, typeInfo.ValueBytes)
		if ctx.HasError() {
			return reflect.Value{}
		}
		if staticType.Kind() == reflect.Interface && valType.Kind() == reflect.Struct {
			if _, pointerOwner := ser.(*ptrToValueSerializer); !pointerOwner {
				valueBytes := typeInfo.ValueBytes
				if valueBytes == 0 {
					if structSer, ok := ser.(*structSerializer); ok {
						valueBytes = structSer.valueBytes
					}
				}
				if valueBytes > 0 && !ctx.ReserveGraphMemory(int64(valueBytes)) {
					return reflect.Value{}
				}
			}
		}
	} else {
		ser = declaredSer
		if ser == nil {
			ctxErr.SetError(DeserializationError("declared map entry serializer is unavailable"))
			return reflect.Value{}
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
		ser.Read(ctx, refMode, false, isDeclared, v)
	}

	return v
}

// readChunk reads a chunk of entries, returns remaining size
func (s mapSerializer) readChunk(ctx *ReadContext, mapVal reflect.Value, header uint8, size int, keyType, valueType reflect.Type, resolver *TypeResolver) int {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()

	// IMPORTANT: map readers must follow the key/value ref bits written on the
	// wire, even when local field metadata would choose a different ref policy.
	// Shared xlang tests depend on reading remote-written ref metadata first and
	// only then writing a new local payload. DO NOT REMOVE this comment.
	trackKeyRef := (header & TRACKING_KEY_REF) != 0
	trackValRef := (header & TRACKING_VALUE_REF) != 0
	keyDeclType := (header & KEY_DECL_TYPE) != 0
	valDeclType := (header & VALUE_DECL_TYPE) != 0
	targetKeyType := keyType
	targetValueType := valueType

	chunkSize := int(buf.ReadUint8(ctxErr))
	if ctx.HasError() {
		return 0
	}
	if chunkSize == 0 || chunkSize > size {
		setInvalidMapChunkSize(ctx, uint64(chunkSize), uint64(size))
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
		keyType, keySer = wrapMapSerializerIfNeeded(
			ctx, targetKeyType, keyType, keySer, keyTypeInfo.ValueBytes)
		if ctx.HasError() {
			return 0
		}
	} else {
		keySer = s.keySerializer
		if keySer == nil {
			ctxErr.SetError(DeserializationError("declared map key serializer is unavailable"))
			return 0
		}
		keyType = s.declaredKeyType
	}

	if !valDeclType {
		valueTypeInfo = resolver.ReadTypeInfo(buf, ctxErr)
		if ctxErr.HasError() {
			return 0
		}
		valSer = valueTypeInfo.Serializer
		valueType = valueTypeInfo.Type
		valueType, valSer = wrapMapSerializerIfNeeded(
			ctx, targetValueType, valueType, valSer, valueTypeInfo.ValueBytes)
		if ctx.HasError() {
			return 0
		}
	} else {
		valSer = s.valueSerializer
		if valSer == nil {
			ctxErr.SetError(DeserializationError("declared map value serializer is unavailable"))
			return 0
		}
		valueType = s.declaredValueType
	}

	keyRefMode := RefModeNone
	if trackKeyRef {
		keyRefMode = RefModeTracking
	}
	valRefMode := RefModeNone
	if trackValRef {
		valRefMode = RefModeTracking
	}
	keyBoxBytes := int64(0)
	if targetKeyType.Kind() == reflect.Interface && keyType.Kind() == reflect.Struct {
		if _, pointerOwner := keySer.(*ptrToValueSerializer); !pointerOwner {
			if keyTypeInfo != nil && keyTypeInfo.ValueBytes > 0 {
				keyBoxBytes = int64(keyTypeInfo.ValueBytes)
			} else if keyDeclType {
				keyBoxBytes = int64(s.declaredKeyBytes)
			} else if structSer, ok := keySer.(*structSerializer); ok {
				keyBoxBytes = int64(structSer.valueBytes)
			}
		}
	}
	valueBoxBytes := int64(0)
	if targetValueType.Kind() == reflect.Interface && valueType.Kind() == reflect.Struct {
		if _, pointerOwner := valSer.(*ptrToValueSerializer); !pointerOwner {
			if valueTypeInfo != nil && valueTypeInfo.ValueBytes > 0 {
				valueBoxBytes = int64(valueTypeInfo.ValueBytes)
			} else if valDeclType {
				valueBoxBytes = int64(s.declaredValueBytes)
			} else if structSer, ok := valSer.(*structSerializer); ok {
				valueBoxBytes = int64(structSer.valueBytes)
			}
		}
	}
	entryReadAlwaysAdvances := trackKeyRef || trackValRef ||
		serializerReadDataAlwaysAdvances(keySer) ||
		serializerReadDataAlwaysAdvances(valSer)
	var checkpoint uint64
	if !entryReadAlwaysAdvances {
		checkpoint = buf.logicalReaderIndex()
	}

	for i := 0; i < chunkSize; i++ {
		if !reserveMapBox(ctx, keyBoxBytes, trackKeyRef) {
			return 0
		}
		k := reflect.New(keyType).Elem()
		if keyTypeInfo != nil {
			keySer.ReadWithTypeInfo(ctx, keyRefMode, keyTypeInfo, k)
		} else {
			keySer.Read(ctx, keyRefMode, false, keyDeclType, k)
		}
		if ctx.HasError() {
			return 0
		}

		if !reserveMapBox(ctx, valueBoxBytes, trackValRef) {
			return 0
		}
		v := reflect.New(valueType).Elem()
		if valueTypeInfo != nil {
			valSer.ReadWithTypeInfo(ctx, valRefMode, valueTypeInfo, v)
		} else {
			valSer.Read(ctx, valRefMode, false, valDeclType, v)
		}
		if ctx.HasError() {
			return 0
		}

		if !setMapValue(ctx, mapVal, unwrapInterface(k), unwrapInterface(v)) {
			return 0
		}
		size--
	}
	if !entryReadAlwaysAdvances && !ctx.settleUnbackedContainerItems(chunkSize, checkpoint) {
		return 0
	}

	return size
}

//go:noinline
func setInvalidMapChunkSize(ctx *ReadContext, chunkSize, remaining uint64) {
	ctx.SetError(DeserializationErrorf(
		"invalid map chunk size %d for remaining length %d", chunkSize, remaining))
}

func reserveMapBox(ctx *ReadContext, bytes int64, trackRef bool) bool {
	if bytes == 0 {
		return true
	}
	if trackRef {
		// Only a new non-null value materializes a box. Peek before allocation so
		// nulls and back-references neither allocate nor consume graph budget.
		if !ctx.Buffer().CheckReadable(1, ctx.Err()) {
			return false
		}
		flag := int8(ctx.Buffer().data[ctx.Buffer().readerIndex])
		if flag != RefValueFlag && flag != NotNullValueFlag {
			return true
		}
	}
	return ctx.ReserveGraphMemory(bytes)
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
			if refID != int32(NullFlag) {
				assignReadRef(ctx, refID, value)
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
		if !ctx.readExpectedTypeID(MAP) {
			return false
		}
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

func wrapMapSerializerIfNeeded(
	ctx *ReadContext, declaredType, actualType reflect.Type, serializer Serializer, valueBytes int,
) (reflect.Type, Serializer) {
	if declaredType == nil || actualType == nil || serializer == nil {
		ctx.SetError(DeserializationErrorf(
			"wire type %v cannot be materialized as %v", actualType, declaredType))
		return nil, nil
	}
	if valueBytes == 0 {
		if structSer, ok := serializer.(*structSerializer); ok {
			valueBytes = structSer.valueBytes
		}
	}
	if actualType.Kind() == reflect.Ptr && actualType.Elem() == declaredType {
		if ptrSer, ok := serializer.(*ptrToValueSerializer); ok {
			return declaredType, ptrSer.valueSerializer
		}
	}
	if actualType.AssignableTo(declaredType) {
		return actualType, serializer
	}
	if declaredType.Kind() == reflect.Ptr {
		if actualType.Kind() != reflect.Ptr {
			ptrType := reflect.PtrTo(actualType)
			if ptrType.AssignableTo(declaredType) {
				return ptrType, &ptrToValueSerializer{valueSerializer: serializer, valueBytes: valueBytes}
			}
		}
	} else if declaredType.Kind() == reflect.Interface {
		if actualType.Kind() != reflect.Ptr {
			ptrType := reflect.PtrTo(actualType)
			if ptrType.AssignableTo(declaredType) {
				return ptrType, &ptrToValueSerializer{valueSerializer: serializer, valueBytes: valueBytes}
			}
		}
	}
	ctx.SetError(DeserializationErrorf(
		"wire type %v is not assignable to declared type %v", actualType, declaredType))
	return nil, nil
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
		return resolver.GetTypeInfo(elem, true)
	}
	return resolver.GetTypeInfo(v, true)
}

// setMapValue sets a key-value pair into a map, handling interface types
func setMapValue(ctx *ReadContext, mapVal, key, value reflect.Value) bool {
	if !key.IsValid() {
		ctx.SetError(DeserializationError("map keys cannot be null"))
		return false
	}
	if !value.IsValid() {
		ctx.SetError(DeserializationError("map value is invalid"))
		return false
	}
	mapKeyType := mapVal.Type().Key()
	mapValueType := mapVal.Type().Elem()

	finalKey := key
	if !key.Type().AssignableTo(mapKeyType) {
		if mapKeyType.Kind() == reflect.Interface && key.Kind() != reflect.Ptr {
			ptrType := reflect.PtrTo(key.Type())
			if ptrType.AssignableTo(mapKeyType) {
				ptr := reflect.New(key.Type())
				ptr.Elem().Set(key)
				finalKey = ptr
			}
		}
		if finalKey == key {
			ctx.SetError(DeserializationErrorf(
				"map key type %v is not assignable to %v", key.Type(), mapKeyType))
			return false
		}
	}
	if !finalKey.Type().Comparable() {
		ctx.SetError(DeserializationErrorf("map key type %v is not comparable", finalKey.Type()))
		return false
	}

	finalValue := value
	if !value.Type().AssignableTo(mapValueType) {
		if mapValueType.Kind() == reflect.Interface && value.Kind() != reflect.Ptr {
			ptrType := reflect.PtrTo(value.Type())
			if ptrType.AssignableTo(mapValueType) {
				ptr := reflect.New(value.Type())
				ptr.Elem().Set(value)
				finalValue = ptr
			}
		}
		if finalValue == value {
			ctx.SetError(DeserializationErrorf(
				"map value type %v is not assignable to %v", value.Type(), mapValueType))
			return false
		}
	}

	mapVal.SetMapIndex(finalKey, finalValue)
	return true
}
