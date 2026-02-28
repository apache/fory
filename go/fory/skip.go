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

// SkipFieldValue skips a field value in compatible mode when the field doesn't exist
// or is incompatible with the local type.
// Uses context error state for deferred error checking.
func SkipFieldValue(ctx *ReadContext, fieldDef FieldDef, readRefFlag bool) {
	SkipFieldValueWithTypeFlag(ctx, fieldDef, readRefFlag, false)
}

// SkipFieldValueWithTypeFlag skips a field value with explicit control over type info reading.
// readTypeInfo should be true if type info was written for this field (struct fields in compatible mode).
// Uses context error state for deferred error checking.
func SkipFieldValueWithTypeFlag(ctx *ReadContext, fieldDef FieldDef, readRefFlag bool, readTypeInfo bool) {
	err := ctx.Err()
	if readTypeInfo {
		// Type info was written for this field (struct fields in compatible mode)
		// Read ref flag first if needed
		if readRefFlag {
			refFlag := ctx.buffer.ReadInt8(err)
			if refFlag == NullFlag {
				return
			}
			if refFlag == RefFlag {
				// Reference to already-seen object, skip the reference index
				_ = ctx.buffer.ReadVarUint32(err)
				return
			}
			// RefValueFlag (0) or NotNullValueFlag (-1) means we need to read the actual object
		}

		// Read type info (typeID + meta_index)
		wroteTypeID := uint32(ctx.buffer.ReadUint8(err))
		internalID := TypeId(wroteTypeID)

		// Check if it's an EXT type first - EXT types don't have meta info like structs
		if internalID == EXT {
			typeInfo := ctx.TypeResolver().readTypeInfoWithTypeID(ctx.buffer, wroteTypeID, err)
			if typeInfo != nil && typeInfo.Serializer != nil {
				// Use the serializer to read and discard the value
				var dummy any
				dummyVal := reflect.ValueOf(&dummy).Elem()
				typeInfo.Serializer.Read(ctx, RefModeNone, false, false, dummyVal)
				return
			}
			// If no serializer is registered, we can't skip this type
			ctx.SetError(DeserializationErrorf("cannot skip EXT type %d: no serializer registered", wroteTypeID))
			return
		}

		// Check if it's a NAMED_EXT type - need to read type info to find serializer
		if internalID == NAMED_EXT {
			typeInfo := ctx.TypeResolver().readTypeInfoWithTypeID(ctx.buffer, wroteTypeID, err)
			if typeInfo.Serializer != nil {
				// Use the serializer to read and discard the value
				var dummy any
				dummyVal := reflect.ValueOf(&dummy).Elem()
				typeInfo.Serializer.Read(ctx, RefModeNone, false, false, dummyVal)
				return
			}
			ctx.SetError(DeserializationError("cannot skip NAMED_EXT type: no serializer found"))
			return
		}

		// Check if it's a struct type - need to read type info and skip struct data
		if internalID == COMPATIBLE_STRUCT || internalID == STRUCT ||
			internalID == NAMED_STRUCT || internalID == NAMED_COMPATIBLE_STRUCT {
			typeInfo := ctx.TypeResolver().readTypeInfoWithTypeID(ctx.buffer, wroteTypeID, err)
			// Now skip the struct data using the typeInfo from the written type
			skipStruct(ctx, typeInfo)
			return
		}

		if IsNamespacedType(internalID) {
			typeInfo := ctx.TypeResolver().readTypeInfoWithTypeID(ctx.buffer, wroteTypeID, err)
			// Now skip the struct data using the typeInfo from the written type
			skipStruct(ctx, typeInfo)
			return
		}
	}

	skipValue(ctx, fieldDef, readRefFlag, true, nil)
}

// isStructTypeId checks if a type ID represents a struct type
func isStructTypeId(id TypeId) bool {
	return id == STRUCT || id == NAMED_STRUCT ||
		id == COMPATIBLE_STRUCT || id == NAMED_COMPATIBLE_STRUCT
}

// SkipAnyValue skips any value by reading its type info first, then skipping the data.
// This is used for polymorphic types where the actual type is unknown at compile time.
// Uses context error state for deferred error checking.
func SkipAnyValue(ctx *ReadContext, readRefFlag bool) {
	err := ctx.Err()
	// Handle ref flag first if needed
	if readRefFlag {
		refFlag := ctx.buffer.ReadInt8(err)
		if ctx.HasError() {
			return
		}
		if refFlag == NullFlag {
			return
		}
		if refFlag == RefFlag {
			// Reference to already-seen object, skip the reference index
			_ = ctx.buffer.ReadVarUint32(err)
			return
		}
		// RefValueFlag (0) or NotNullValueFlag (-1) means we need to read the actual object
	}

	// ReadData type_id first
	typeID := uint32(ctx.buffer.ReadUint8(err))
	if ctx.HasError() {
		return
	}
	internalID := TypeId(typeID)

	// For struct-like types, also read meta_index to get type_info
	var fieldDef FieldDef
	var typeInfo *TypeInfo

	switch internalID {
	case LIST, SET:
		fieldDef = FieldDef{
			fieldType: NewCollectionFieldType(TypeId(typeID), NewSimpleFieldType(UNKNOWN)),
			nullable:  true,
		}
	case MAP:
		fieldDef = FieldDef{
			fieldType: NewMapFieldType(TypeId(typeID), NewSimpleFieldType(UNKNOWN), NewSimpleFieldType(UNKNOWN)),
			nullable:  true,
		}
	case NAMED_UNION:
		resolver := ctx.TypeResolver()
		_, _ = resolver.metaStringResolver.ReadMetaStringBytes(ctx.buffer, err)
		if ctx.HasError() {
			return
		}
		_, _ = resolver.metaStringResolver.ReadMetaStringBytes(ctx.buffer, err)
		if ctx.HasError() {
			return
		}
		fieldDef = FieldDef{
			fieldType: NewSimpleFieldType(TypeId(typeID)),
			nullable:  true,
		}
	case COMPATIBLE_STRUCT, NAMED_COMPATIBLE_STRUCT, STRUCT, NAMED_STRUCT, EXT, TYPED_UNION:
		// Read type info using the shared meta reader when enabled.
		typeInfo = ctx.TypeResolver().readTypeInfoWithTypeID(ctx.buffer, typeID, err)
		if ctx.HasError() {
			return
		}
		fieldDef = FieldDef{
			fieldType: NewSimpleFieldType(TypeId(typeID)),
			nullable:  true,
		}
	default:
		if internalID == ENUM || internalID == STRUCT ||
			internalID == EXT || internalID == TYPED_UNION {
			ctx.buffer.ReadVarUint32(err)
			if ctx.HasError() {
				return
			}
		}
		fieldDef = FieldDef{
			fieldType: NewSimpleFieldType(TypeId(typeID)),
			nullable:  true,
		}
	}

	// Don't read ref flag again since we already handled it
	skipValue(ctx, fieldDef, false, false, typeInfo)
}

// readTypeInfoForSkip reads type info from buffer for struct types during skip.
// For DynamicFieldType fields, the buffer contains: typeID + meta info (meta index or namespace/typename).
// Uses context error state for deferred error checking.
func readTypeInfoForSkip(ctx *ReadContext, fieldTypeId TypeId) *TypeInfo {
	err := ctx.Err()
	// Read the actual typeID from buffer (Java writes typeID for struct fields)
	typeID := uint32(ctx.buffer.ReadUint8(err))
	if ctx.HasError() {
		return nil
	}
	// Use readTypeInfoWithTypeID which handles both namespaced and non-namespaced types correctly
	return ctx.TypeResolver().readTypeInfoWithTypeID(ctx.buffer, typeID, err)
}

// skipCollection skips a collection (list/set) value
// Uses context error state for deferred error checking.
func skipCollection(ctx *ReadContext, fieldDef FieldDef) {
	err := ctx.Err()
	length := ctx.buffer.ReadVarUint32(err)
	if ctx.HasError() || length == 0 {
		return
	}

	header := ctx.buffer.ReadByte(err)
	if ctx.HasError() {
		return
	}

	hasNull := (header & CollectionHasNull) != 0
	isSameType := (header & CollectionIsSameType) != 0
	trackingRef := (header & CollectionTrackingRef) != 0
	isDeclared := (header & CollectionIsDeclElementType) != 0

	var elemDef FieldDef
	var elemTypeInfo *TypeInfo
	if isSameType && !isDeclared {
		// ReadData element type info - first read the typeID from buffer
		typeID := uint32(ctx.buffer.ReadUint8(err))
		if ctx.HasError() {
			return
		}
		elemTypeInfo = ctx.TypeResolver().readTypeInfoWithTypeID(ctx.buffer, typeID, err)
		elemDef = FieldDef{
			fieldType: NewSimpleFieldType(TypeId(elemTypeInfo.TypeID)),
			nullable:  hasNull,
		}
	} else if isDeclared {
		// Use declared element type from the collection's field type
		if collType, ok := fieldDef.fieldType.(*CollectionFieldType); ok && collType.elementType != nil {
			elemDef = FieldDef{
				fieldType: collType.elementType,
				nullable:  hasNull,
			}
		} else {
			// Fallback: use unknown type
			elemDef = FieldDef{
				fieldType: NewSimpleFieldType(UNKNOWN),
				nullable:  true,
			}
		}
	} else {
		// Not same type - each element has its own type info, use unknown
		elemDef = FieldDef{
			fieldType: NewSimpleFieldType(UNKNOWN),
			nullable:  true,
		}
	}

	ctx.depth++
	if ctx.depth > ctx.maxDepth {
		ctx.SetError(MaxDepthExceededError(ctx.depth))
		return
	}
	defer ctx.decDepth()

	for i := uint32(0); i < length; i++ {
		// Read ref flag if collection has ref tracking enabled
		skipValue(ctx, elemDef, trackingRef, false, elemTypeInfo)
		if ctx.HasError() {
			return
		}
	}
}

// skipMap skips a map value
// Uses context error state for deferred error checking.
func skipMap(ctx *ReadContext, fieldDef FieldDef) {
	bufErr := ctx.Err()
	length := ctx.buffer.ReadVarUint32(bufErr)
	if ctx.HasError() || length == 0 {
		return
	}

	// Extract key/value types from MapFieldType if available
	// When KEY_DECL_TYPE/VALUE_DECL_TYPE flags are set, the type info is NOT written
	// to the buffer, so we must use the declared types from the FieldDef
	var declaredKeyDef, declaredValueDef FieldDef
	if mapFieldType, ok := fieldDef.fieldType.(*MapFieldType); ok {
		declaredKeyDef = FieldDef{
			fieldType: mapFieldType.keyType,
			nullable:  true,
		}
		declaredValueDef = FieldDef{
			fieldType: mapFieldType.valueType,
			nullable:  true,
		}
	} else {
		// Fallback to unknown types if MapFieldType is not available
		declaredKeyDef = FieldDef{
			fieldType: NewSimpleFieldType(UNKNOWN),
			nullable:  true,
		}
		declaredValueDef = FieldDef{
			fieldType: NewSimpleFieldType(UNKNOWN),
			nullable:  true,
		}
	}

	var lenCounter uint32
	for lenCounter < length {
		header := ctx.buffer.ReadByte(bufErr)
		if ctx.HasError() {
			return
		}

		// Both null
		if (header&KEY_HAS_NULL) != 0 && (header&VALUE_HAS_NULL) != 0 {
			lenCounter++
			continue
		}

		// Only key is null
		if (header & KEY_HAS_NULL) != 0 {
			valueDeclared := (header & VALUE_DECL_TYPE) != 0
			var valueDef FieldDef
			var valueTypeInfo *TypeInfo
			if !valueDeclared {
				typeID := uint32(ctx.buffer.ReadUint8(bufErr))
				if ctx.HasError() {
					return
				}
				valueTypeInfo = ctx.TypeResolver().readTypeInfoWithTypeID(ctx.buffer, typeID, bufErr)
				valueDef = FieldDef{
					fieldType: NewSimpleFieldType(TypeId(valueTypeInfo.TypeID)),
					nullable:  true,
				}
			} else {
				valueDef = declaredValueDef
			}
			ctx.depth++
			if ctx.depth > ctx.maxDepth {
				ctx.SetError(MaxDepthExceededError(ctx.depth))
				return
			}
			skipValue(ctx, valueDef, false, false, valueTypeInfo)
			ctx.decDepth()
			if ctx.HasError() {
				return
			}
			lenCounter++
			continue
		}

		// Only value is null
		if (header & VALUE_HAS_NULL) != 0 {
			keyDeclared := (header & KEY_DECL_TYPE) != 0
			var keyDef FieldDef
			var keyTypeInfo *TypeInfo
			if !keyDeclared {
				typeID := uint32(ctx.buffer.ReadUint8(bufErr))
				if ctx.HasError() {
					return
				}
				keyTypeInfo = ctx.TypeResolver().readTypeInfoWithTypeID(ctx.buffer, typeID, bufErr)
				keyDef = FieldDef{
					fieldType: NewSimpleFieldType(TypeId(keyTypeInfo.TypeID)),
					nullable:  true,
				}
			} else {
				keyDef = declaredKeyDef
			}
			ctx.depth++
			if ctx.depth > ctx.maxDepth {
				ctx.SetError(MaxDepthExceededError(ctx.depth))
				return
			}
			skipValue(ctx, keyDef, false, false, keyTypeInfo)
			ctx.decDepth()
			if ctx.HasError() {
				return
			}
			lenCounter++
			continue
		}

		// Both key and value are non-null
		chunkSize := ctx.buffer.ReadByte(bufErr)
		if ctx.HasError() {
			return
		}

		keyDeclared := (header & KEY_DECL_TYPE) != 0
		valueDeclared := (header & VALUE_DECL_TYPE) != 0

		var keyDef, valueDef FieldDef
		var keyTypeInfo, valueTypeInfo *TypeInfo
		if !keyDeclared {
			typeID := uint32(ctx.buffer.ReadUint8(bufErr))
			if ctx.HasError() {
				return
			}
			keyTypeInfo = ctx.TypeResolver().readTypeInfoWithTypeID(ctx.buffer, typeID, bufErr)
			keyDef = FieldDef{
				fieldType: NewSimpleFieldType(TypeId(keyTypeInfo.TypeID)),
				nullable:  true,
			}
		} else {
			keyDef = declaredKeyDef
		}

		if !valueDeclared {
			typeID := uint32(ctx.buffer.ReadUint8(bufErr))
			if ctx.HasError() {
				return
			}
			valueTypeInfo = ctx.TypeResolver().readTypeInfoWithTypeID(ctx.buffer, typeID, bufErr)
			valueDef = FieldDef{
				fieldType: NewSimpleFieldType(TypeId(valueTypeInfo.TypeID)),
				nullable:  true,
			}
		} else {
			valueDef = declaredValueDef
		}

		// Check if ref tracking is enabled for keys and values
		keyTrackingRef := (header & TRACKING_KEY_REF) != 0
		valueTrackingRef := (header & TRACKING_VALUE_REF) != 0

		ctx.depth++
		if ctx.depth > ctx.maxDepth {
			ctx.SetError(MaxDepthExceededError(ctx.depth))
			return
		}
		for i := byte(0); i < chunkSize; i++ {
			skipValue(ctx, keyDef, keyTrackingRef, false, keyTypeInfo)
			if ctx.HasError() {
				ctx.decDepth()
				return
			}
			skipValue(ctx, valueDef, valueTrackingRef, false, valueTypeInfo)
			if ctx.HasError() {
				ctx.decDepth()
				return
			}
		}
		ctx.decDepth()
		lenCounter += uint32(chunkSize)
	}
}

// skipStruct skips a struct value using TypeInfo
// Uses context error state for deferred error checking.
func skipStruct(ctx *ReadContext, info *TypeInfo) {
	if ctx.HasError() {
		return
	}

	// Get fieldDefs from the serializer
	var fieldDefs []FieldDef
	if info.Serializer != nil {
		if ss, ok := info.Serializer.(*structSerializer); ok && ss.fieldDefs != nil {
			fieldDefs = ss.fieldDefs
		} else if sss, ok := info.Serializer.(*skipStructSerializer); ok && sss.fieldDefs != nil {
			fieldDefs = sss.fieldDefs
		}
	}

	// If we couldn't get fieldDefs from serializer, try getTypeDef as fallback
	if fieldDefs == nil {
		typeDef, tdErr := ctx.TypeResolver().getTypeDef(info.Type, false)
		if tdErr != nil {
			ctx.SetError(FromError(fmt.Errorf("cannot skip struct without field definitions: %w", tdErr)))
			return
		}
		fieldDefs = typeDef.fieldDefs
	}

	ctx.depth++
	if ctx.depth > ctx.maxDepth {
		ctx.SetError(MaxDepthExceededError(ctx.depth))
		return
	}
	defer ctx.decDepth()

	for _, fieldDef := range fieldDefs {
		// Use FieldDef's trackingRef and nullable to determine if ref flag was written by Java
		// Java writes ref flag based on its FieldDef, not based on type rules
		readRefFlag := fieldDef.trackingRef || fieldDef.nullable
		// For struct-like fields (struct, ext), type info is written in the buffer
		readTypeInfo := isStructFieldType(fieldDef.fieldType)
		SkipFieldValueWithTypeFlag(ctx, fieldDef, readRefFlag, readTypeInfo)
		if ctx.HasError() {
			return
		}
	}
}

// skipValue is the main dispatcher for skipping values based on their type
// Uses context error state for deferred error checking.
func skipValue(ctx *ReadContext, fieldDef FieldDef, readRefFlag bool, isField bool, typeInfo *TypeInfo) {
	err := ctx.Err()
	if readRefFlag {
		refFlag := ctx.buffer.ReadInt8(err)
		if ctx.HasError() {
			return
		}
		if refFlag == NullFlag {
			return
		}
		if refFlag == RefFlag {
			// Reference to already-seen object, skip the reference index
			_ = ctx.buffer.ReadVarUint32(err)
			return
		}
		// RefValueFlag (0) or NotNullValueFlag (-1) means we need to read the actual object
	}

	typeIDNum := uint32(fieldDef.fieldType.TypeId())

	internalID := TypeId(typeIDNum)
	// Handle struct-like types
	if internalID == COMPATIBLE_STRUCT || internalID == STRUCT ||
		internalID == NAMED_STRUCT || internalID == NAMED_COMPATIBLE_STRUCT ||
		internalID == UNKNOWN {
		// If type_info is provided (from SkipAnyValue), use skipStruct directly
		if typeInfo != nil {
			skipStruct(ctx, typeInfo)
			return
		}
		// Otherwise we need to read type info
		ti := ctx.TypeResolver().readTypeInfoWithTypeID(ctx.buffer, typeIDNum, err)
		skipStruct(ctx, ti)
		return
	}
	if internalID == ENUM {
		// Enum values are encoded as ordinal only (VarUint32Small7) for xlang.
		_ = ctx.buffer.ReadUint8(err)
		return
	}
	if internalID == EXT || internalID == NAMED_EXT || internalID == TYPED_UNION || internalID == NAMED_UNION {
		typeInfo := ctx.TypeResolver().readTypeInfoWithTypeID(ctx.buffer, typeIDNum, err)
		if typeInfo != nil && typeInfo.Serializer != nil {
			// Use the serializer to read and discard the value
			var dummy any
			dummyVal := reflect.ValueOf(&dummy).Elem()
			typeInfo.Serializer.Read(ctx, RefModeNone, false, false, dummyVal)
			return
		}
		ctx.SetError(DeserializationErrorf("cannot skip type %d: no serializer registered", typeIDNum))
		return
	}

	// Match on built-in types
	switch TypeId(typeIDNum) {
	// Boolean type
	case BOOL:
		_ = ctx.buffer.ReadByte(err)

	// Integer types
	case INT8:
		_ = ctx.buffer.ReadInt8(err)
	case INT16:
		_ = ctx.buffer.ReadInt16(err)
	case INT32:
		_ = ctx.buffer.ReadUint8(err)
	case VARINT32:
		_ = ctx.buffer.ReadUint8(err)
	case INT64, VARINT64, TAGGED_INT64:
		_ = ctx.buffer.ReadVarint64(err)

	// Floating point types
	case BFLOAT16, FLOAT16:
		_ = ctx.buffer.ReadUint16(err)
	case FLOAT32:
		_ = ctx.buffer.ReadFloat32(err)
	case FLOAT64:
		_ = ctx.buffer.ReadFloat64(err)

	// String types
	case STRING:
		// String format: VarUint64 header (size << 2 | encoding) + data bytes
		header := ctx.buffer.ReadVarUint64(err)
		if ctx.HasError() {
			return
		}
		size := header >> 2
		encoding := header & 0b11
		switch encoding {
		case 0: // Latin1 - 1 byte per char
			_ = ctx.buffer.ReadBinary(int(size), err)
		case 1: // UTF-16LE - 2 bytes per char
			_ = ctx.buffer.ReadBinary(int(size*2), err)
		case 2: // UTF-8 - variable, but size is byte count
			_ = ctx.buffer.ReadBinary(int(size), err)
		}
	case BINARY:
		length := ctx.buffer.ReadVarUint32(err)
		if ctx.HasError() {
			return
		}
		_ = ctx.buffer.ReadBinary(int(length), err)
	case BOOL_ARRAY, INT8_ARRAY, UINT8_ARRAY:
		length := ctx.buffer.ReadLength(err)
		if ctx.HasError() {
			return
		}
		_ = ctx.buffer.ReadBinary(length, err)
	case INT16_ARRAY, UINT16_ARRAY, FLOAT16_ARRAY, BFLOAT16_ARRAY:
		length := ctx.buffer.ReadLength(err)
		if ctx.HasError() {
			return
		}
		_ = ctx.buffer.ReadBinary(length*2, err)
	case INT32_ARRAY, UINT32_ARRAY, FLOAT32_ARRAY:
		length := ctx.buffer.ReadLength(err)
		if ctx.HasError() {
			return
		}
		_ = ctx.buffer.ReadBinary(length*4, err)
	case INT64_ARRAY, UINT64_ARRAY, FLOAT64_ARRAY:
		length := ctx.buffer.ReadLength(err)
		if ctx.HasError() {
			return
		}
		_ = ctx.buffer.ReadBinary(length*8, err)

	// Date/Time types
	case DATE:
		_ = ctx.buffer.ReadUint8(err)
	case TIMESTAMP:
		_ = ctx.buffer.ReadInt64(err)
		_ = ctx.buffer.ReadUint32(err)

	// Container types
	case LIST, SET:
		skipCollection(ctx, fieldDef)
	case MAP:
		skipMap(ctx, fieldDef)

	case UNION, TYPED_UNION, NAMED_UNION:
		_ = ctx.buffer.ReadVarUint32(err) // case_id
		if ctx.HasError() {
			return
		}
		SkipAnyValue(ctx, true)

	case NONE:
		return

	// Struct types
	case COMPATIBLE_STRUCT, NAMED_COMPATIBLE_STRUCT, STRUCT, NAMED_STRUCT:
		if typeInfo != nil {
			skipStruct(ctx, typeInfo)
			return
		}
		// For DynamicFieldType fields, type info is written in the buffer - read it first
		ti := readTypeInfoForSkip(ctx, TypeId(typeIDNum))
		if ctx.HasError() {
			return
		}
		skipStruct(ctx, ti)

	// Enum types
	case ENUM:
		_ = ctx.buffer.ReadVarUint32(err)

	// Unsigned integer types
	case UINT8:
		_ = ctx.buffer.ReadByte(err)
	case UINT16:
		_ = ctx.buffer.ReadUint16(err)
	case UINT32:
		_ = ctx.buffer.ReadUint32(err)
	case VAR_UINT32:
		_ = ctx.buffer.ReadVarUint32(err)
	case UINT64:
		_ = ctx.buffer.ReadUint64(err)
	case VAR_UINT64:
		_ = ctx.buffer.ReadVarUint64(err)
	case TAGGED_UINT64:
		firstInt32 := ctx.buffer.ReadInt32(err)
		if (firstInt32 & 1) != 0 {
			// 9-byte encoding
			_ = ctx.buffer.ReadUint64(err)
		}
		// Otherwise it's 4-byte encoding, already read

	// Unknown (polymorphic) type - read type info and skip dynamically
	case UNKNOWN:
		// UNKNOWN (0) is used for polymorphic types in cross-language serialization
		// We need to read the actual type info to know how to skip
		SkipAnyValue(ctx, false)

	// Named extension types - not yet supported
	case NAMED_EXT:
		ctx.SetError(DeserializationErrorf("unsupported type for skip: NAMED_EXT (%d)", typeIDNum))

	// Unsupported types
	default:
		ctx.SetError(DeserializationErrorf("unsupported type for skip: %d", typeIDNum))
	}
}
