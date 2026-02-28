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
	"errors"
	"fmt"
	"reflect"
	"sort"
	"unicode"
	"unicode/utf8"
)

// GetStructHash returns the struct hash for a given type using the provided TypeResolver.
// This is used by codegen serializers to get the hash at runtime.
func GetStructHash(type_ reflect.Type, resolver *TypeResolver) int32 {
	ser := newStructSerializer(type_, "")
	if err := ser.initialize(resolver); err != nil {
		panic(fmt.Errorf("failed to initialize struct serializer for hash computation: %v", err))
	}
	return ser.structHash
}

// initialize performs eager initialization of the struct serializer.
// This should be called at registration time to pre-compute all field metadata.
func (s *structSerializer) initialize(typeResolver *TypeResolver) error {
	if s.initialized {
		return nil
	}
	// Ensure type is set
	if s.type_ == nil {
		return errors.New("struct type not set")
	}
	// Normalize pointer types
	for s.type_.Kind() == reflect.Ptr {
		s.type_ = s.type_.Elem()
	}
	// Set compatible mode flag BEFORE field initialization
	// This is needed for groupFields to apply correct sorting
	s.isCompatibleMode = typeResolver.Compatible()
	// Build fields from type or fieldDefs
	if s.fieldDefs != nil {
		if err := s.initFieldsFromTypeDef(typeResolver); err != nil {
			return err
		}
	} else {
		if err := s.initFields(typeResolver); err != nil {
			return err
		}
	}
	// Compute struct hash
	s.structHash = s.computeHash()
	if s.tempValue == nil {
		tmp := reflect.New(s.type_).Elem()
		s.tempValue = &tmp
	}
	s.initialized = true
	return nil
}

func computeLocalNullable(typeResolver *TypeResolver, field reflect.StructField, foryTag ForyTag) bool {
	fieldType := field.Type
	optionalInfo, isOptional := getOptionalInfo(fieldType)
	if isOptional {
		fieldType = optionalInfo.valueType
	}
	typeId := typeResolver.getTypeIdByType(fieldType)
	isEnum := typeId == ENUM
	var nullableFlag bool
	if typeResolver.fory.config.IsXlang {
		nullableFlag = isOptional || field.Type.Kind() == reflect.Ptr
	} else {
		nullableFlag = isOptional || field.Type.Kind() == reflect.Ptr ||
			field.Type.Kind() == reflect.Slice ||
			field.Type.Kind() == reflect.Map ||
			field.Type.Kind() == reflect.Interface
	}
	if foryTag.NullableSet {
		nullableFlag = foryTag.Nullable
	}
	if isNonNullablePrimitiveKind(fieldType.Kind()) && !isEnum && !isOptional {
		nullableFlag = false
	}
	return nullableFlag
}

func primitiveTypeIdMatchesKind(typeId TypeId, kind reflect.Kind) bool {
	switch typeId {
	case BOOL:
		return kind == reflect.Bool
	case INT8:
		return kind == reflect.Int8
	case INT16:
		return kind == reflect.Int16
	case INT32, VARINT32:
		return kind == reflect.Int32 || kind == reflect.Int
	case INT64, VARINT64, TAGGED_INT64:
		return kind == reflect.Int64 || kind == reflect.Int
	case UINT8:
		return kind == reflect.Uint8
	case UINT16:
		return kind == reflect.Uint16
	case UINT32, VAR_UINT32:
		return kind == reflect.Uint32 || kind == reflect.Uint
	case UINT64, VAR_UINT64, TAGGED_UINT64:
		return kind == reflect.Uint64 || kind == reflect.Uint
	case FLOAT32:
		return kind == reflect.Float32
	case FLOAT64:
		return kind == reflect.Float64
	case STRING:
		return kind == reflect.String
	default:
		return false
	}
}

func applyNestedRefOverride(serializer Serializer, fieldType reflect.Type, foryTag ForyTag) Serializer {
	if !foryTag.NestedRefSet || !foryTag.NestedRefValid {
		return serializer
	}
	return applyNestedRefOverrideWithPath(serializer, fieldType, foryTag.NestedRef)
}

func applyNestedRefOverrideWithPath(serializer Serializer, fieldType reflect.Type, nestedRefs []bool) Serializer {
	if serializer == nil || len(nestedRefs) == 0 {
		return serializer
	}
	switch fieldType.Kind() {
	case reflect.Slice:
		if len(nestedRefs) < 1 {
			return serializer
		}
		if sliceSer, ok := serializer.(*sliceSerializer); ok {
			override := nestedRefs[0]
			newSer := *sliceSer
			newSer.referencable = newSer.referencable && override
			if len(nestedRefs) > 1 && newSer.elemSerializer != nil {
				newSer.elemSerializer = applyNestedRefOverrideWithPath(
					newSer.elemSerializer,
					fieldType.Elem(),
					nestedRefs[1:],
				)
			}
			return &newSer
		}
	case reflect.Map:
		if len(nestedRefs) < 2 {
			return serializer
		}
		keyOverride := nestedRefs[0]
		valueOverride := nestedRefs[1]
		if mapSer, ok := serializer.(*mapSerializer); ok {
			newSer := *mapSer
			newSer.keyReferencable = newSer.keyReferencable && keyOverride
			newSer.valueReferencable = newSer.valueReferencable && valueOverride
			if len(nestedRefs) > 2 && newSer.valueSerializer != nil {
				newSer.valueSerializer = applyNestedRefOverrideWithPath(
					newSer.valueSerializer,
					fieldType.Elem(),
					nestedRefs[2:],
				)
			}
			return &newSer
		}
		if mapSer, ok := serializer.(mapSerializer); ok {
			mapSer.keyReferencable = mapSer.keyReferencable && keyOverride
			mapSer.valueReferencable = mapSer.valueReferencable && valueOverride
			if len(nestedRefs) > 2 && mapSer.valueSerializer != nil {
				mapSer.valueSerializer = applyNestedRefOverrideWithPath(
					mapSer.valueSerializer,
					fieldType.Elem(),
					nestedRefs[2:],
				)
			}
			return mapSer
		}
	}
	return serializer
}

// initFields initializes fields from local struct type using TypeResolver
func (s *structSerializer) initFields(typeResolver *TypeResolver) error {
	// If we have fieldDefs from type_def (remote meta), use them
	if len(s.fieldDefs) > 0 {
		return s.initFieldsFromTypeDef(typeResolver)
	}

	// Otherwise initialize from local struct type
	type_ := s.type_
	var fields []FieldInfo
	var fieldNames []string
	var serializers []Serializer
	var typeIds []TypeId
	var nullables []bool
	var tagIDs []int

	for i := 0; i < type_.NumField(); i++ {
		field := type_.Field(i)
		firstRune, _ := utf8.DecodeRuneInString(field.Name)
		if unicode.IsLower(firstRune) {
			continue // skip unexported fields
		}

		// Parse fory struct tag and check for ignore
		foryTag := parseForyTag(field)
		if foryTag.Ignore {
			continue // skip ignored fields
		}

		fieldType := field.Type
		optionalInfo, isOptional := getOptionalInfo(fieldType)
		baseType := fieldType
		if isOptional {
			if err := validateOptionalValueType(optionalInfo.valueType); err != nil {
				return fmt.Errorf("field %s: %w", field.Name, err)
			}
			baseType = optionalInfo.valueType
		}
		fieldKind := FieldKindValue
		if isOptional {
			fieldKind = FieldKindOptional
		} else if fieldType.Kind() == reflect.Ptr {
			fieldKind = FieldKindPointer
		}
		var fieldSerializer Serializer
		// For any fields, don't get a serializer - use WriteValue/ReadValue instead
		// which will handle polymorphic types dynamically
		if fieldType.Kind() != reflect.Interface {
			// Get serializer for all non-interface field types
			fieldSerializer, _ = typeResolver.getSerializerByType(fieldType, true)
		}

		// Use TypeResolver helper methods for arrays and slices
		if fieldType.Kind() == reflect.Array && fieldType.Elem().Kind() != reflect.Interface {
			fieldSerializer, _ = typeResolver.GetArraySerializer(fieldType)
		} else if fieldType.Kind() == reflect.Slice && fieldType.Elem().Kind() != reflect.Interface {
			fieldSerializer, _ = typeResolver.GetSliceSerializer(fieldType)
		} else if fieldType.Kind() == reflect.Slice && fieldType.Elem().Kind() == reflect.Interface {
			// For struct fields with interface element types, use sliceDynSerializer
			fieldSerializer = mustNewSliceDynSerializer(fieldType.Elem())
		}
		fieldSerializer = applyNestedRefOverride(fieldSerializer, fieldType, foryTag)

		// Get TypeId for the serializer, fallback to deriving from kind
		fieldTypeId := typeResolver.getTypeIdByType(fieldType)
		if fieldTypeId == 0 {
			fieldTypeId = typeIdFromKind(fieldType)
		}

		// Override TypeId based on compress/encoding tags for integer types
		// This matches the logic in type_def.go:buildFieldDefs
		baseKind := baseType.Kind()
		if baseKind == reflect.Ptr {
			baseKind = baseType.Elem().Kind()
		}
		switch baseKind {
		case reflect.Uint32:
			if foryTag.CompressSet {
				if foryTag.Compress {
					fieldTypeId = VAR_UINT32
				} else {
					fieldTypeId = UINT32
				}
			}
		case reflect.Int32:
			if foryTag.CompressSet {
				if foryTag.Compress {
					fieldTypeId = VARINT32
				} else {
					fieldTypeId = INT32
				}
			}
		case reflect.Uint64:
			if foryTag.EncodingSet {
				switch foryTag.Encoding {
				case "fixed":
					fieldTypeId = UINT64
				case "varint":
					fieldTypeId = VAR_UINT64
				case "tagged":
					fieldTypeId = TAGGED_UINT64
				}
			}
		case reflect.Int64:
			if foryTag.EncodingSet {
				switch foryTag.Encoding {
				case "fixed":
					fieldTypeId = INT64
				case "varint":
					fieldTypeId = VARINT64
				case "tagged":
					fieldTypeId = TAGGED_INT64
				}
			}
		}

		if foryTag.TypeIDSet && foryTag.TypeIDValid {
			fieldTypeId = foryTag.TypeID
		}

		// Calculate nullable flag for serialization (wire format):
		// - In xlang mode: Per xlang spec, fields are NON-NULLABLE by default.
		//   Only pointer types are nullable by default.
		// - In native mode: Go's natural semantics apply - slice/map/interface can be nil,
		//   so they are nullable by default.
		// Can be overridden by explicit fory tag `fory:"nullable"`.
		isEnum := fieldTypeId == ENUM

		// Determine nullable based on mode
		// In xlang mode: only pointer types are nullable by default (per xlang spec)
		// In native mode: Go's natural semantics - all nil-able types are nullable
		// This ensures proper interoperability with Java/other languages in xlang mode.
		var nullableFlag bool
		if typeResolver.fory.config.IsXlang {
			// xlang mode: only pointer types are nullable by default per xlang spec
			// Slices and maps are NOT nullable - they serialize as empty when nil
			nullableFlag = isOptional || fieldType.Kind() == reflect.Ptr
		} else {
			// Native mode: Go's natural semantics - all nil-able types are nullable
			nullableFlag = isOptional || fieldType.Kind() == reflect.Ptr ||
				fieldType.Kind() == reflect.Slice ||
				fieldType.Kind() == reflect.Map ||
				fieldType.Kind() == reflect.Interface
		}
		if foryTag.NullableSet {
			// Override nullable flag if explicitly set in fory tag
			nullableFlag = foryTag.Nullable
		}
		// Primitives are never nullable, regardless of tag
		if isNonNullablePrimitiveKind(fieldType.Kind()) && !isEnum {
			nullableFlag = false
		}

		// Calculate ref tracking - use tag override if explicitly set
		trackRef := typeResolver.TrackRef()
		if foryTag.RefSet {
			trackRef = foryTag.Ref
		}
		trackingRef := trackRef
		if trackingRef && !NeedWriteRef(fieldTypeId) {
			trackingRef = false
		}
		// Align trackingRef with xlang rules for field ref flags:
		// - simple value types never write ref flags
		// - collection fields only write ref flags when explicitly tagged
		if typeResolver.fory.config.IsXlang && trackingRef && isCollectionType(fieldTypeId) && !foryTag.RefSet {
			trackingRef = false
		}

		// Pre-compute RefMode based on trackingRef and nullable.
		// When trackingRef is true, we must write ref flags even for non-nullable fields.
		refMode := RefModeNone
		if trackingRef {
			refMode = RefModeTracking
		} else if nullableFlag {
			refMode = RefModeNullOnly
		}
		// Pre-compute WriteType: true for struct fields in compatible mode
		writeType := typeResolver.Compatible() && isStructField(baseType)
		var cachedTypeInfo *TypeInfo
		if writeType {
			cachedType := baseType
			if cachedType.Kind() == reflect.Ptr {
				cachedType = cachedType.Elem()
			}
			cachedTypeInfo = typeResolver.getTypeInfoByType(cachedType)
		}

		// Pre-compute DispatchId, with special handling for enum fields and pointer-to-numeric
		dispatchId := getDispatchIdFromTypeId(fieldTypeId, nullableFlag)
		if dispatchId == UnknownDispatchId {
			dispatchType := baseType
			if dispatchType.Kind() == reflect.Ptr {
				dispatchType = dispatchType.Elem()
			}
			dispatchId = GetDispatchId(dispatchType)
		}
		if fieldSerializer != nil {
			if _, ok := fieldSerializer.(*enumSerializer); ok {
				dispatchId = EnumDispatchId
			} else if ptrSer, ok := fieldSerializer.(*ptrToValueSerializer); ok {
				if _, ok := ptrSer.valueSerializer.(*enumSerializer); ok {
					dispatchId = EnumDispatchId
				}
			}
		}
		if DebugOutputEnabled {
			fmt.Printf("[Go][fory-debug] initFields: field=%s type=%v dispatchId=%d refMode=%v nullableFlag=%v serializer=%T\n",
				SnakeCase(field.Name), fieldType, dispatchId, refMode, nullableFlag, fieldSerializer)
		}

		fieldInfo := FieldInfo{
			Offset:     field.Offset,
			DispatchId: dispatchId,
			RefMode:    refMode,
			Kind:       fieldKind,
			Serializer: fieldSerializer,
			Meta: &FieldMeta{
				Name:           SnakeCase(field.Name),
				Type:           fieldType,
				TypeId:         fieldTypeId,
				Nullable:       nullableFlag, // Use same logic as TypeDef's nullable flag for consistent ref handling
				FieldIndex:     i,
				WriteType:      writeType,
				CachedTypeInfo: cachedTypeInfo,
				HasGenerics:    isCollectionType(fieldTypeId), // Container fields have declared element types
				OptionalInfo:   optionalInfo,
				TagID:          foryTag.ID,
				HasForyTag:     foryTag.HasTag,
				TagRefSet:      foryTag.RefSet,
				TagRef:         foryTag.Ref,
				TagNullableSet: foryTag.NullableSet,
				TagNullable:    foryTag.Nullable,
			},
		}
		fields = append(fields, fieldInfo)
		fieldNames = append(fieldNames, fieldInfo.Meta.Name)
		serializers = append(serializers, fieldSerializer)
		typeIds = append(typeIds, fieldTypeId)
		nullables = append(nullables, nullableFlag)
		tagIDs = append(tagIDs, foryTag.ID)
	}

	// Sort fields according to specification using nullable info and tag IDs for consistent ordering
	serializers, fieldNames = sortFields(typeResolver, fieldNames, serializers, typeIds, nullables, tagIDs)
	order := make(map[string]int, len(fieldNames))
	for idx, name := range fieldNames {
		order[name] = idx
	}

	sort.SliceStable(fields, func(i, j int) bool {
		oi, okI := order[fields[i].Meta.Name]
		oj, okJ := order[fields[j].Meta.Name]
		switch {
		case okI && okJ:
			return oi < oj
		case okI:
			return true
		case okJ:
			return false
		default:
			return false
		}
	})

	s.fields = fields
	s.fieldGroup = GroupFields(s.fields)

	// Debug output for field order comparison with Java
	if s.type_ != nil {
		s.fieldGroup.DebugPrint(s.type_.Name())
	}

	return nil
}

// initFieldsFromTypeDef initializes fields from remote fieldDefs using typeResolver
func (s *structSerializer) initFieldsFromTypeDef(typeResolver *TypeResolver) error {
	type_ := s.type_
	if type_ == nil {
		// Type is not known - we'll create an any placeholder
		// This happens when deserializing unknown types in compatible mode
		// For now, we'll create fields that discard all data
		var fields []FieldInfo
		for _, def := range s.fieldDefs {
			fieldSerializer, _ := getFieldTypeSerializerWithResolver(typeResolver, def.fieldType)
			remoteTypeInfo, _ := def.fieldType.getTypeInfoWithResolver(typeResolver)
			remoteType := remoteTypeInfo.Type
			if remoteType == nil {
				remoteType = reflect.TypeOf((*any)(nil)).Elem()
			}
			// Get TypeId from FieldType's TypeId method
			fieldTypeId := def.fieldType.TypeId()
			// Pre-compute RefMode based on trackRef and FieldDef flags
			refMode := RefModeNone
			if def.trackingRef {
				refMode = RefModeTracking
			} else if def.nullable {
				refMode = RefModeNullOnly
			}
			// Pre-compute WriteType: true for struct fields in compatible mode
			writeType := typeResolver.Compatible() && isStructField(remoteType)

			// Pre-compute DispatchId, with special handling for enum fields
			dispatchId := GetDispatchId(remoteType)
			if fieldSerializer != nil {
				if _, ok := fieldSerializer.(*enumSerializer); ok {
					dispatchId = EnumDispatchId
				} else if ptrSer, ok := fieldSerializer.(*ptrToValueSerializer); ok {
					if _, ok := ptrSer.valueSerializer.(*enumSerializer); ok {
						dispatchId = EnumDispatchId
					}
				}
			}

			fieldInfo := FieldInfo{
				Offset:     0,
				DispatchId: dispatchId,
				RefMode:    refMode,
				Kind:       FieldKindValue,
				Serializer: fieldSerializer,
				Meta: &FieldMeta{
					Name:        def.name,
					Type:        remoteType,
					TypeId:      fieldTypeId,
					Nullable:    def.nullable, // Use remote nullable flag
					FieldIndex:  -1,           // Mark as non-existent field to discard data
					FieldDef:    def,          // Save original FieldDef for skipping
					WriteType:   writeType,
					HasGenerics: isCollectionType(fieldTypeId), // Container fields have declared element types
				},
			}
			fields = append(fields, fieldInfo)
		}
		s.fields = fields
		s.fieldGroup = GroupFields(s.fields)
		s.typeDefDiffers = true // Unknown type, must use ordered reading
		return nil
	}

	// Build maps from field names and tag IDs to struct field indices
	fieldNameToIndex := make(map[string]int)
	fieldNameToOffset := make(map[string]uintptr)
	fieldNameToType := make(map[string]reflect.Type)
	localNullableByIndex := make(map[int]bool)
	fieldTagIDToIndex := make(map[int]int)         // tag ID -> struct field index
	fieldTagIDToOffset := make(map[int]uintptr)    // tag ID -> field offset
	fieldTagIDToType := make(map[int]reflect.Type) // tag ID -> field type
	fieldTagIDToName := make(map[int]string)       // tag ID -> snake_case field name
	for i := 0; i < type_.NumField(); i++ {
		field := type_.Field(i)

		// Parse fory tag and skip ignored fields
		foryTag := parseForyTag(field)
		if foryTag.Ignore {
			continue
		}

		name := SnakeCase(field.Name)
		fieldNameToIndex[name] = i
		fieldNameToOffset[name] = field.Offset
		fieldNameToType[name] = field.Type
		localNullableByIndex[i] = computeLocalNullable(typeResolver, field, foryTag)

		// Also index by tag ID if present
		if foryTag.ID >= 0 {
			fieldTagIDToIndex[foryTag.ID] = i
			fieldTagIDToOffset[foryTag.ID] = field.Offset
			fieldTagIDToType[foryTag.ID] = field.Type
			fieldTagIDToName[foryTag.ID] = name
		}
	}

	var fields []FieldInfo

	for _, def := range s.fieldDefs {
		fieldSerializer, err := getFieldTypeSerializerWithResolver(typeResolver, def.fieldType)
		if err != nil || fieldSerializer == nil {
			// If we can't get serializer from typeID, try to get it from the Go type
			// This can happen when the type isn't registered in typeIDToTypeInfo
			remoteTypeInfo, _ := def.fieldType.getTypeInfoWithResolver(typeResolver)
			if remoteTypeInfo.Type != nil {
				fieldSerializer, _ = typeResolver.getSerializerByType(remoteTypeInfo.Type, true)
			}
		}

		// Get the remote type from fieldDef
		remoteTypeInfo, _ := def.fieldType.getTypeInfoWithResolver(typeResolver)
		remoteType := remoteTypeInfo.Type
		// Track if type lookup failed - we'll need to skip such fields
		// Note: DynamicFieldType.getTypeInfoWithResolver returns any (not nil) when lookup fails
		emptyInterfaceType := reflect.TypeOf((*any)(nil)).Elem()
		typeLookupFailed := remoteType == nil || remoteType == emptyInterfaceType
		if remoteType == nil {
			remoteType = emptyInterfaceType
		}

		// For struct-like fields, even if TypeDef lookup fails, we can try to read
		// the field because type resolution happens at read time from the buffer.
		// The type name might map to a different local type.
		isStructLikeField := isStructFieldType(def.fieldType)

		// Try to find corresponding local field
		// First try to match by tag ID (if remote def uses tag ID)
		// Then fall back to matching by field name
		fieldIndex := -1
		var offset uintptr
		var fieldType reflect.Type
		var localFieldName string
		var localType reflect.Type
		var exists bool

		if def.tagID >= 0 {
			// Try to match by tag ID
			if idx, ok := fieldTagIDToIndex[def.tagID]; ok {
				exists = true
				fieldIndex = idx // Will be overwritten if types are compatible
				localType = fieldTagIDToType[def.tagID]
				offset = fieldTagIDToOffset[def.tagID]
				localFieldName = fieldTagIDToName[def.tagID]
			}
		}

		// Fall back to name-based matching if tag ID match failed
		if !exists && def.name != "" {
			if _, ok := fieldNameToIndex[def.name]; ok {
				exists = true
				localType = fieldNameToType[def.name]
				offset = fieldNameToOffset[def.name]
				localFieldName = def.name
			}
		}

		if exists {
			idx := fieldNameToIndex[localFieldName]
			if def.tagID >= 0 {
				idx = fieldTagIDToIndex[def.tagID]
			}
			// Check if types are compatible
			// For primitive types: skip if types don't match
			// For struct-like types: allow read even if TypeDef lookup failed,
			// because runtime type resolution by name might work
			shouldRead := false
			isPolymorphicField := def.fieldType.TypeId() == UNKNOWN
			defTypeId := def.fieldType.TypeId()
			// Check if field is an enum - either by type ID or by serializer type
			internalDefTypeId := defTypeId
			isEnumField := internalDefTypeId == ENUM
			if !isEnumField && fieldSerializer != nil {
				_, isEnumField = fieldSerializer.(*enumSerializer)
			}
			if isPolymorphicField && localType.Kind() == reflect.Interface {
				// For polymorphic (UNKNOWN) fields with any local type,
				// allow reading - the actual type will be determined at runtime
				shouldRead = true
				fieldType = localType
			} else if typeLookupFailed && isEnumField {
				// For enum fields with failed TypeDef lookup, check if local field is a numeric type
				// (Go enums are int-based)
				// Also handle pointer enum fields (*EnumType)
				localKind := localType.Kind()
				elemKind := localKind
				if localKind == reflect.Ptr {
					elemKind = localType.Elem().Kind()
				}
				if isNumericKind(elemKind) {
					shouldRead = true
					fieldType = localType
					// Get the serializer for the base type (the enum type, not the pointer)
					baseType := localType
					if localKind == reflect.Ptr {
						baseType = localType.Elem()
					}
					fieldSerializer, _ = typeResolver.getSerializerByType(baseType, true)
				}
			} else if typeLookupFailed && isStructLikeField {
				// For struct fields with failed TypeDef lookup, check if local field can hold a struct
				localKind := localType.Kind()
				if localKind == reflect.Ptr {
					localKind = localType.Elem().Kind()
				}
				if localKind == reflect.Struct || localKind == reflect.Interface {
					shouldRead = true
					fieldType = localType // Use local type for struct fields
				}
			} else if typeLookupFailed && (internalDefTypeId == UNION || internalDefTypeId == TYPED_UNION || internalDefTypeId == NAMED_UNION) {
				// For union fields with failed type lookup (named unions aren't in typeIDToTypeInfo),
				// allow reading if the local type is a union.
				if isUnionType(localType) {
					shouldRead = true
					fieldType = localType
				}
			} else if typeLookupFailed && isPrimitiveType(TypeId(internalDefTypeId)) {
				baseLocal := localType
				if optInfo, ok := getOptionalInfo(baseLocal); ok {
					baseLocal = optInfo.valueType
				}
				if baseLocal.Kind() == reflect.Ptr {
					baseLocal = baseLocal.Elem()
				}
				if primitiveTypeIdMatchesKind(internalDefTypeId, baseLocal.Kind()) {
					shouldRead = true
					fieldType = localType
				}
			} else if typeLookupFailed && isPrimitiveArrayType(TypeId(internalDefTypeId)) {
				// Primitive arrays/slices use array type IDs but may not be registered in typeIDToTypeInfo.
				// Allow reading using the local slice/array type when the type IDs match.
				localTypeId := typeIdFromKind(localType)
				if TypeId(localTypeId&0xFF) == internalDefTypeId {
					shouldRead = true
					fieldType = localType
				}
			} else if typeLookupFailed && defTypeId == LIST {
				// For list fields with failed type lookup (e.g., named struct element types),
				// allow reading using the local slice type.
				if localType.Kind() == reflect.Slice {
					elemKind := localType.Elem().Kind()
					if elemKind == reflect.Interface ||
						elemKind == reflect.Struct ||
						(elemKind == reflect.Ptr && localType.Elem().Elem().Kind() == reflect.Struct) {
						shouldRead = true
						fieldType = localType
					}
				}
			} else if typeLookupFailed && defTypeId == MAP {
				// For map fields with failed type lookup (e.g., named struct key/value types),
				// allow reading using the local map type.
				if localType.Kind() == reflect.Map {
					keyKind := localType.Key().Kind()
					valueKind := localType.Elem().Kind()
					if keyKind == reflect.Interface ||
						keyKind == reflect.Struct ||
						(keyKind == reflect.Ptr && localType.Key().Elem().Kind() == reflect.Struct) ||
						valueKind == reflect.Interface ||
						valueKind == reflect.Struct ||
						(valueKind == reflect.Ptr && localType.Elem().Elem().Kind() == reflect.Struct) {
						shouldRead = true
						fieldType = localType
					}
				}
			} else if typeLookupFailed && defTypeId == SET {
				if isSetReflectType(localType) {
					shouldRead = true
					fieldType = localType
				}
			} else if defTypeId == SET && isSetReflectType(localType) {
				// Both remote and local are Set types, allow reading
				shouldRead = true
				fieldType = localType
			} else if !typeLookupFailed && typesCompatible(localType, remoteType) {
				shouldRead = true
				fieldType = localType
			}

			if shouldRead {
				fieldIndex = idx
				// offset was already set above when matching by tag ID or field name
				// For struct-like fields with failed type lookup, get the serializer for the local type
				if typeLookupFailed && isStructLikeField && fieldSerializer == nil {
					fieldSerializer, _ = typeResolver.getSerializerByType(localType, true)
				}
				// For collection fields with interface element types, use sliceDynSerializer
				if typeLookupFailed && (defTypeId == LIST || defTypeId == SET) && fieldSerializer == nil {
					if localType.Kind() == reflect.Slice && localType.Elem().Kind() == reflect.Interface {
						fieldSerializer = mustNewSliceDynSerializer(localType.Elem())
					}
				}
				// If serializer is still nil, fall back to local type serializer.
				if fieldSerializer == nil {
					fieldSerializer, _ = typeResolver.getSerializerByType(localType, true)
				}
				// For Set fields (fory.Set[T] = map[T]struct{}), get the setSerializer
				if defTypeId == SET && isSetReflectType(localType) && fieldSerializer == nil {
					fieldSerializer, _ = typeResolver.getSerializerByType(localType, true)
				}
				// If local type is *T and remote type is T, we need the serializer for *T
				// This handles Java's Integer/Long (nullable boxed types) mapping to Go's *int32/*int64
				if localType.Kind() == reflect.Ptr && localType.Elem() == remoteType {
					fieldSerializer, _ = typeResolver.getSerializerByType(localType, true)
				}
				// For pointer enum fields (*EnumType), get the serializer for the base enum type
				// The struct read/write code will handle pointer dereferencing
				if isEnumField && localType.Kind() == reflect.Ptr {
					baseType := localType.Elem()
					fieldSerializer, _ = typeResolver.getSerializerByType(baseType, true)
					if DebugOutputEnabled {
						fmt.Printf("[fory-debug] pointer enum field %s: localType=%v baseType=%v serializer=%T\n",
							def.name, localType, baseType, fieldSerializer)
					}
				}
				// For array fields, use array serializers (not slice serializers) even if typeID maps to slice serializer
				// The typeID (INT16_ARRAY, etc.) is shared between arrays and slices, but we need the correct
				// serializer based on the actual Go type
				if localType.Kind() == reflect.Array {
					elemType := localType.Elem()
					switch elemType.Kind() {
					case reflect.Bool:
						fieldSerializer = boolArraySerializer{arrayType: localType}
					case reflect.Int8:
						fieldSerializer = int8ArraySerializer{arrayType: localType}
					case reflect.Int16:
						fieldSerializer = int16ArraySerializer{arrayType: localType}
					case reflect.Int32:
						fieldSerializer = int32ArraySerializer{arrayType: localType}
					case reflect.Int64:
						fieldSerializer = int64ArraySerializer{arrayType: localType}
					case reflect.Uint8:
						fieldSerializer = uint8ArraySerializer{arrayType: localType}
					case reflect.Float32:
						fieldSerializer = float32ArraySerializer{arrayType: localType}
					case reflect.Float64:
						fieldSerializer = float64ArraySerializer{arrayType: localType}
					case reflect.Int:
						if reflect.TypeOf(int(0)).Size() == 8 {
							fieldSerializer = int64ArraySerializer{arrayType: localType}
						} else {
							fieldSerializer = int32ArraySerializer{arrayType: localType}
						}
					}
				}
			} else {
				// Types are incompatible or unknown - use remote type but mark field as not settable
				fieldType = remoteType
				fieldIndex = -1
				offset = 0 // Don't set offset for incompatible fields
			}
		} else {
			// Field doesn't exist locally, use type from fieldDef
			fieldType = remoteType
		}

		optionalInfo, isOptional := getOptionalInfo(fieldType)
		baseType := fieldType
		if isOptional {
			if err := validateOptionalValueType(optionalInfo.valueType); err != nil {
				return fmt.Errorf("field %s: %w", def.name, err)
			}
			baseType = optionalInfo.valueType
		}
		fieldKind := FieldKindValue
		if isOptional {
			fieldKind = FieldKindOptional
		} else if fieldType.Kind() == reflect.Ptr {
			fieldKind = FieldKindPointer
		}
		if fieldKind == FieldKindOptional {
			// Use the Optional serializer for local Optional[T] fields.
			// The serializer resolved from remote type IDs is for the element type.
			fieldSerializer, _ = typeResolver.getSerializerByType(fieldType, true)
		}

		// Get TypeId from FieldType's TypeId method
		fieldTypeId := def.fieldType.TypeId()
		// Pre-compute RefMode based on FieldDef flags (trackingRef and nullable)
		refMode := RefModeNone
		if def.trackingRef {
			refMode = RefModeTracking
		} else if def.nullable {
			refMode = RefModeNullOnly
		}
		// Pre-compute WriteType: true for struct fields in compatible mode
		writeType := typeResolver.Compatible() && isStructField(baseType)
		var cachedTypeInfo *TypeInfo
		if writeType {
			cachedType := baseType
			if cachedType.Kind() == reflect.Ptr {
				cachedType = cachedType.Elem()
			}
			cachedTypeInfo = typeResolver.getTypeInfoByType(cachedType)
		}

		// Pre-compute DispatchId, with special handling for pointer-to-numeric and enum fields
		// IMPORTANT: For compatible mode reading, we must use the REMOTE nullable flag
		// to determine DispatchId, because Java wrote data with its nullable semantics.
		var dispatchId DispatchId
		localKind := fieldType.Kind()
		baseKind := localKind
		if isOptional {
			baseKind = baseType.Kind()
		}
		localIsPtr := localKind == reflect.Ptr
		localIsPrimitive := isPrimitiveDispatchKind(baseKind) || (localIsPtr && isPrimitiveDispatchKind(fieldType.Elem().Kind()))

		if localIsPrimitive {
			if def.nullable {
				// Remote is nullable - use nullable DispatchId
				dispatchId = getDispatchIdFromTypeId(fieldTypeId, true)
			} else {
				// Remote is NOT nullable - use primitive DispatchId
				dispatchId = getDispatchIdFromTypeId(fieldTypeId, false)
				if dispatchId == UnknownDispatchId {
					dispatchType := baseType
					if dispatchType.Kind() == reflect.Ptr {
						dispatchType = dispatchType.Elem()
					}
					dispatchId = GetDispatchId(dispatchType)
				}
			}
		} else {
			dispatchType := baseType
			if dispatchType.Kind() == reflect.Ptr {
				dispatchType = dispatchType.Elem()
			}
			dispatchId = GetDispatchId(dispatchType)
		}
		if fieldSerializer != nil {
			if _, ok := fieldSerializer.(*enumSerializer); ok {
				dispatchId = EnumDispatchId
			} else if ptrSer, ok := fieldSerializer.(*ptrToValueSerializer); ok {
				if _, ok := ptrSer.valueSerializer.(*enumSerializer); ok {
					dispatchId = EnumDispatchId
				}
			}
		}

		// Determine field name: use local field name if matched, otherwise use def.name
		fieldName := def.name
		if localFieldName != "" {
			fieldName = localFieldName
		}

		fieldInfo := FieldInfo{
			Offset:     offset,
			DispatchId: dispatchId,
			RefMode:    refMode,
			Kind:       fieldKind,
			Serializer: fieldSerializer,
			Meta: &FieldMeta{
				Name:           fieldName,
				Type:           fieldType,
				TypeId:         fieldTypeId,
				Nullable:       def.nullable, // Use remote nullable flag
				FieldIndex:     fieldIndex,
				FieldDef:       def, // Save original FieldDef for skipping
				WriteType:      writeType,
				CachedTypeInfo: cachedTypeInfo,
				HasGenerics:    isCollectionType(fieldTypeId), // Container fields have declared element types
				OptionalInfo:   optionalInfo,
				TagID:          def.tagID,
				HasForyTag:     def.tagID >= 0,
			},
		}
		fields = append(fields, fieldInfo)
	}

	s.fields = fields
	s.fieldGroup = GroupFields(s.fields)

	// Debug output for field order comparison with Java MetaSharedSerializer
	if DebugOutputEnabled && s.type_ != nil {
		fmt.Printf("[Go] Remote TypeDef order (%d fields):\n", len(s.fieldDefs))
		for i, def := range s.fieldDefs {
			fmt.Printf("[Go]   [%d] %s -> typeId=%d, nullable=%v\n", i, def.name, def.fieldType.TypeId(), def.nullable)
		}
		s.fieldGroup.DebugPrint(s.type_.Name())
	}

	// Compute typeDefDiffers: true if any field doesn't exist locally, has type mismatch,
	// or has nullable mismatch (which affects field ordering)
	// When typeDefDiffers is false, we can use grouped reading for better performance
	s.typeDefDiffers = false
	for i, field := range fields {
		if field.Meta.FieldIndex < 0 {
			// Field exists in remote TypeDef but not locally
			if DebugOutputEnabled && s.type_ != nil {
				fmt.Printf("[Go][fory-debug] [%s] typeDefDiffers: missing local field for remote def idx=%d name=%q tagID=%d typeId=%d\n",
					s.name, i, s.fieldDefs[i].name, s.fieldDefs[i].tagID, s.fieldDefs[i].fieldType.TypeId())
			}
			s.typeDefDiffers = true
			break
		}
		// Check if nullable flag differs between remote and local
		// Remote nullable is stored in fieldDefs[i].nullable
		// Local nullable is determined by whether the Go field is a pointer type
		if i < len(s.fieldDefs) && field.Meta.FieldIndex >= 0 {
			remoteNullable := s.fieldDefs[i].nullable
			// Check if local Go field is nullable based on local field definitions
			localNullable := localNullableByIndex[field.Meta.FieldIndex]
			if remoteNullable != localNullable {
				if DebugOutputEnabled && s.type_ != nil {
					fmt.Printf("[Go][fory-debug] [%s] typeDefDiffers: nullable mismatch idx=%d name=%q tagID=%d remote=%v local=%v\n",
						s.name, i, s.fieldDefs[i].name, s.fieldDefs[i].tagID, remoteNullable, localNullable)
				}
				s.typeDefDiffers = true
				break
			}
			remoteTypeId := TypeId(s.fieldDefs[i].fieldType.TypeId())
			localTypeId := typeResolver.getTypeIdByType(field.Meta.Type)
			if localTypeId == 0 {
				localTypeId = typeIdFromKind(field.Meta.Type)
			}
			localTypeId = TypeId(localTypeId)
			if !typeIdEqualForDiff(remoteTypeId, localTypeId) {
				if DebugOutputEnabled && s.type_ != nil {
					fmt.Printf("[Go][fory-debug] [%s] typeDefDiffers: type ID mismatch idx=%d name=%q tagID=%d remote=%d local=%d\n",
						s.name, i, s.fieldDefs[i].name, s.fieldDefs[i].tagID, remoteTypeId, localTypeId)
				}
				s.typeDefDiffers = true
				break
			}
		}
	}

	if DebugOutputEnabled && s.type_ != nil {
		fmt.Printf("[Go] typeDefDiffers=%v for %s\n", s.typeDefDiffers, s.type_.Name())
	}

	return nil
}

func typeIdEqualForDiff(remoteTypeId TypeId, localTypeId TypeId) bool {
	if remoteTypeId == localTypeId {
		return true
	}
	if remoteTypeId == UNION && (localTypeId == TYPED_UNION || localTypeId == NAMED_UNION) {
		return true
	}
	if localTypeId == UNION && (remoteTypeId == TYPED_UNION || remoteTypeId == NAMED_UNION) {
		return true
	}
	// Treat byte array encodings as compatible for diffing.
	if (remoteTypeId == INT8_ARRAY || remoteTypeId == UINT8_ARRAY || remoteTypeId == BINARY) &&
		(localTypeId == INT8_ARRAY || localTypeId == UINT8_ARRAY || localTypeId == BINARY) {
		return true
	}
	return false
}

func (s *structSerializer) computeHash() int32 {
	// Build FieldFingerprintInfo for each field
	fields := make([]FieldFingerprintInfo, 0, len(s.fields))
	for _, field := range s.fields {
		var typeId TypeId
		isEnumField := false
		if field.Serializer == nil {
			typeId = UNKNOWN
		} else {
			typeId = field.Meta.TypeId
			// Check if this is an enum serializer (directly or wrapped in ptrToValueSerializer)
			if _, ok := field.Serializer.(*enumSerializer); ok {
				isEnumField = true
				typeId = UNKNOWN
			} else if ptrSer, ok := field.Serializer.(*ptrToValueSerializer); ok {
				if _, ok := ptrSer.valueSerializer.(*enumSerializer); ok {
					isEnumField = true
					typeId = UNKNOWN
				}
			}
			// Unions use UNION type ID in fingerprints, regardless of typed/named variants.
			if typeId == TYPED_UNION || typeId == NAMED_UNION || typeId == UNION {
				typeId = UNION
			}
			// For user-defined types (struct, ext types), use UNKNOWN in fingerprint
			// This matches Java's behavior where user-defined types return UNKNOWN
			// to ensure consistent fingerprint computation across languages
			if isUserDefinedType(typeId) {
				typeId = UNKNOWN
			}
			fieldTypeForHash := field.Meta.Type
			if field.Kind == FieldKindOptional {
				fieldTypeForHash = field.Meta.OptionalInfo.valueType
			}
			// For fixed-size arrays with primitive elements, use primitive array type IDs
			if fieldTypeForHash.Kind() == reflect.Array {
				elemKind := fieldTypeForHash.Elem().Kind()
				switch elemKind {
				case reflect.Int8:
					typeId = INT8_ARRAY
				case reflect.Uint8:
					typeId = UINT8_ARRAY
				case reflect.Int16:
					typeId = INT16_ARRAY
				case reflect.Uint16:
					typeId = UINT16_ARRAY
				case reflect.Int32:
					typeId = INT32_ARRAY
				case reflect.Uint32:
					typeId = UINT32_ARRAY
				case reflect.Int64:
					typeId = INT64_ARRAY
				case reflect.Uint64, reflect.Uint:
					typeId = UINT64_ARRAY
				case reflect.Float32:
					typeId = FLOAT32_ARRAY
				case reflect.Float64:
					typeId = FLOAT64_ARRAY
				default:
					typeId = LIST
				}
			} else if fieldTypeForHash.Kind() == reflect.Slice {
				if !isPrimitiveArrayType(TypeId(typeId)) && typeId != BINARY {
					typeId = LIST
				}
			} else if fieldTypeForHash.Kind() == reflect.Map {
				// fory.Set[T] is defined as map[T]struct{} - check for struct{} elem type
				if isSetReflectType(fieldTypeForHash) {
					typeId = SET
				} else {
					typeId = MAP
				}
			}
		}

		// Determine nullable flag for xlang compatibility:
		// - Default: false for ALL fields (xlang default - aligned with all languages)
		// - Primitives are always non-nullable
		// - Can be overridden by explicit fory tag
		nullable := field.Kind == FieldKindOptional // Optional fields are nullable by default
		if field.Meta.TagNullableSet {
			// Use explicit tag value if set
			nullable = field.Meta.TagNullable
		}
		// Primitives are never nullable, regardless of tag
		fieldTypeForNullable := field.Meta.Type
		if field.Kind == FieldKindOptional {
			fieldTypeForNullable = field.Meta.OptionalInfo.valueType
		}
		if field.Kind != FieldKindOptional && isNonNullablePrimitiveKind(fieldTypeForNullable.Kind()) && !isEnumField {
			nullable = false
		}

		fields = append(fields, FieldFingerprintInfo{
			FieldID:   field.Meta.TagID,
			FieldName: SnakeCase(field.Meta.Name),
			TypeID:    typeId,
			// Ref is based on explicit tag annotation only, NOT runtime ref_tracking config
			// This allows fingerprint to be computed at compile time for C++/Rust
			Ref:      field.Meta.TagRefSet && field.Meta.TagRef,
			Nullable: nullable,
		})
	}

	hashString := ComputeStructFingerprint(fields)
	data := []byte(hashString)
	h1, _ := Murmur3Sum128WithSeed(data, 47)
	hash := int32(h1 & 0xFFFFFFFF)

	if DebugOutputEnabled {
		fmt.Printf("[Go][fory-debug] struct %v version fingerprint=\"%s\" version hash=%d\n", s.type_, hashString, hash)
	}

	if hash == 0 {
		panic(fmt.Errorf("hash for type %v is 0", s.type_))
	}
	return hash
}
