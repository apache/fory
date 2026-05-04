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

func getListDispatchId(type_ reflect.Type) DispatchId {
	if type_.Kind() == reflect.Slice && type_.Elem().Kind() == reflect.String {
		return StringSliceDispatchId
	}
	return UnknownDispatchId
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

		fieldSpec, err := parseFieldSpec(field, typeResolver.fory.config.IsXlang, typeResolver.TrackRef())
		if err != nil {
			return err
		}
		fieldSpec.Type = bindResolvedTypeSpec(typeResolver, field.Type, fieldSpec.Type)
		if fieldSpec.Ignore {
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
		fieldSerializer, err := serializerForTypeSpec(typeResolver, fieldType, fieldSpec.Type)
		if err != nil && fieldType.Kind() != reflect.Interface {
			return fmt.Errorf("field %s: %w", field.Name, err)
		}
		fieldTypeId := fieldSpec.Type.TypeId()
		nullableFlag := fieldDeclaredNullable(fieldSpec)
		trackRef := fieldDeclaredTrackRef(fieldSpec, typeResolver.fory.config.IsXlang, typeResolver.TrackRef())

		// Pre-compute RefMode based on TrackRef and nullable.
		// When TrackRef is true, we must write ref flags even for non-nullable fields.
		refMode := RefModeNone
		if trackRef {
			refMode = RefModeTracking
		} else if nullableFlag {
			refMode = RefModeNullOnly
		}
		// Pre-compute WriteType: true for struct fields in compatible mode
		writeType := typeResolver.Compatible() && isStructFieldType(fieldSpec.Type)
		var cachedTypeInfo *TypeInfo
		if writeType {
			cachedType := baseType
			if cachedType.Kind() == reflect.Ptr {
				cachedType = cachedType.Elem()
			}
			cachedTypeInfo = typeResolver.getTypeInfoByType(cachedType)
		}

		// Pre-compute DispatchId, with special handling for enum fields and pointer-to-numeric.
		// Declared LIST fields must use their declared serializer even when the local
		// Go carrier is a primitive slice; primitive array specs keep the slice fast path.
		dispatchId := getDispatchIdFromTypeId(fieldTypeId, nullableFlag)
		if dispatchId == UnknownDispatchId {
			dispatchType := baseType
			if dispatchType.Kind() == reflect.Ptr {
				dispatchType = dispatchType.Elem()
			}
			if fieldTypeId == LIST {
				dispatchId = getListDispatchId(dispatchType)
			} else {
				dispatchId = GetDispatchId(dispatchType)
			}
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
				Name:           fieldSpec.Name,
				Type:           fieldType,
				TypeId:         fieldTypeId,
				Nullable:       nullableFlag,
				FieldIndex:     i,
				WriteType:      writeType,
				CachedTypeInfo: cachedTypeInfo,
				HasGenerics:    isCollectionType(fieldTypeId),
				OptionalInfo:   optionalInfo,
				Spec:           &fieldSpec,
				TypeSpec:       fieldSpec.Type,
			},
		}
		fields = append(fields, fieldInfo)
		fieldNames = append(fieldNames, fieldInfo.Meta.Name)
		serializers = append(serializers, fieldSerializer)
		typeIds = append(typeIds, fieldTypeId)
		nullables = append(nullables, nullableFlag)
		tagIDs = append(tagIDs, fieldSpec.TagID)
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
	emptyInterfaceType := reflect.TypeOf((*any)(nil)).Elem()
	if type_ == nil {
		// Type is not known - we'll create an any placeholder
		// This happens when deserializing unknown types in compatible mode
		// For now, we'll create fields that discard all data
		var fields []FieldInfo
		for _, def := range s.fieldDefs {
			fieldSerializer, _ := getFieldTypeSerializerWithResolver(typeResolver, def.typeSpec)
			remoteTypeInfo, _ := def.typeSpec.getTypeInfoWithResolver(typeResolver)
			remoteType := remoteTypeInfo.Type
			if remoteType == nil {
				remoteType = emptyInterfaceType
			}
			fieldTypeId := def.typeSpec.TypeId()
			refMode := RefModeNone
			if def.trackRef {
				refMode = RefModeTracking
			} else if def.nullable {
				refMode = RefModeNullOnly
			}
			writeType := typeResolver.Compatible() && isStructField(remoteType)
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
					TypeSpec:    def.typeSpec,
				},
			}
			fields = append(fields, fieldInfo)
		}
		s.fields = fields
		s.fieldGroup = GroupFields(s.fields)
		s.typeDefDiffers = true // Unknown type, must use ordered reading
		return nil
	}

	type localFieldBinding struct {
		index  int
		offset uintptr
		goType reflect.Type
		name   string
		spec   FieldSpec
	}

	fieldNameToBinding := make(map[string]localFieldBinding)
	localNullableByIndex := make(map[int]bool)
	localTrackRefByIndex := make(map[int]bool)
	localSpecByIndex := make(map[int]*TypeSpec)
	fieldTagIDToBinding := make(map[int]localFieldBinding)
	for i := 0; i < type_.NumField(); i++ {
		field := type_.Field(i)
		if field.PkgPath != "" {
			continue
		}
		fieldSpec, err := parseFieldSpec(field, typeResolver.fory.config.IsXlang, typeResolver.TrackRef())
		if err != nil {
			return err
		}
		fieldSpec.Type = bindResolvedTypeSpec(typeResolver, field.Type, fieldSpec.Type)
		if fieldSpec.Ignore {
			continue
		}
		binding := localFieldBinding{
			index:  i,
			offset: field.Offset,
			goType: field.Type,
			name:   fieldSpec.Name,
			spec:   fieldSpec,
		}
		fieldNameToBinding[fieldSpec.Name] = binding
		localNullableByIndex[i] = fieldDeclaredNullable(fieldSpec)
		localTrackRefByIndex[i] = fieldDeclaredTrackRef(fieldSpec, typeResolver.fory.config.IsXlang, typeResolver.TrackRef())
		localSpecByIndex[i] = fieldSpec.Type
		if fieldSpec.TagID >= 0 {
			fieldTagIDToBinding[fieldSpec.TagID] = binding
		}
	}

	var fields []FieldInfo

	for _, def := range s.fieldDefs {
		fieldSerializer, err := getFieldTypeSerializerWithResolver(typeResolver, def.typeSpec)
		if err != nil || fieldSerializer == nil {
			remoteTypeInfo, _ := def.typeSpec.getTypeInfoWithResolver(typeResolver)
			if remoteTypeInfo.Type != nil {
				fieldSerializer, _ = typeResolver.getSerializerByType(remoteTypeInfo.Type, true)
			}
		}

		remoteTypeInfo, _ := def.typeSpec.getTypeInfoWithResolver(typeResolver)
		remoteType := remoteTypeInfo.Type
		typeLookupFailed := remoteType == nil || remoteType == emptyInterfaceType
		if remoteType == nil {
			remoteType = emptyInterfaceType
		}

		isStructLikeField := isStructFieldType(def.typeSpec)

		fieldIndex := -1
		var offset uintptr
		var fieldType reflect.Type
		var localFieldName string
		var localType reflect.Type
		var localFieldSpec *FieldSpec
		var exists bool

		if def.tagID >= 0 {
			if binding, ok := fieldTagIDToBinding[def.tagID]; ok {
				exists = true
				fieldIndex = binding.index
				localType = binding.goType
				offset = binding.offset
				localFieldName = binding.name
				bindingSpec := binding.spec
				localFieldSpec = &bindingSpec
			}
		}

		if !exists && def.name != "" {
			if binding, ok := fieldNameToBinding[def.name]; ok {
				exists = true
				fieldIndex = binding.index
				localType = binding.goType
				offset = binding.offset
				localFieldName = binding.name
				bindingSpec := binding.spec
				localFieldSpec = &bindingSpec
			}
		}

		if exists {
			shouldRead := false
			isPolymorphicField := def.typeSpec.TypeId() == UNKNOWN
			defTypeId := def.typeSpec.TypeId()
			internalDefTypeId := defTypeId
			isEnumField := internalDefTypeId == ENUM
			if !isEnumField && fieldSerializer != nil {
				_, isEnumField = fieldSerializer.(*enumSerializer)
			}
			if isPolymorphicField && localType.Kind() == reflect.Interface {
				shouldRead = true
				fieldType = localType
			} else if typeLookupFailed && isEnumField {
				localKind := localType.Kind()
				elemKind := localKind
				if localKind == reflect.Ptr {
					elemKind = localType.Elem().Kind()
				}
				if isNumericKind(elemKind) {
					shouldRead = true
					fieldType = localType
					baseType := localType
					if localKind == reflect.Ptr {
						baseType = localType.Elem()
					}
					fieldSerializer, _ = typeResolver.getSerializerByType(baseType, true)
				}
			} else if typeLookupFailed && isStructLikeField {
				localKind := localType.Kind()
				if localKind == reflect.Ptr {
					localKind = localType.Elem().Kind()
				}
				if localKind == reflect.Struct || localKind == reflect.Interface {
					shouldRead = true
					fieldType = localType
				}
			} else if typeLookupFailed && (internalDefTypeId == UNION || internalDefTypeId == TYPED_UNION || internalDefTypeId == NAMED_UNION) {
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
				localTypeId := typeIdFromKind(localType)
				if TypeId(localTypeId&0xFF) == internalDefTypeId {
					shouldRead = true
					fieldType = localType
				}
			} else if typeLookupFailed && defTypeId == LIST {
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
				shouldRead = true
				fieldType = localType
			} else if !typeLookupFailed && typesCompatible(localType, remoteType) {
				shouldRead = true
				fieldType = localType
			}

			if shouldRead {
				if localType != nil {
					localSerializer, localErr := serializerForTypeSpec(typeResolver, localType, def.typeSpec)
					if localErr == nil && localSerializer != nil {
						fieldSerializer = localSerializer
					}
				}
				if typeLookupFailed && isStructLikeField && fieldSerializer == nil {
					fieldSerializer, _ = typeResolver.getSerializerByType(localType, true)
				}
				if typeLookupFailed && (defTypeId == LIST || defTypeId == SET) && fieldSerializer == nil {
					if localType.Kind() == reflect.Slice && localType.Elem().Kind() == reflect.Interface {
						fieldSerializer = mustNewSliceDynSerializer(localType.Elem())
					}
				}
				if fieldSerializer == nil {
					fieldSerializer, _ = typeResolver.getSerializerByType(localType, true)
				}
				if defTypeId == SET && isSetReflectType(localType) && fieldSerializer == nil {
					fieldSerializer, _ = typeResolver.getSerializerByType(localType, true)
				}
				if localType.Kind() == reflect.Ptr && localType.Elem() == remoteType {
					fieldSerializer, _ = typeResolver.getSerializerByType(localType, true)
				}
				if isEnumField && localType.Kind() == reflect.Ptr {
					baseType := localType.Elem()
					fieldSerializer, _ = typeResolver.getSerializerByType(baseType, true)
					if DebugOutputEnabled {
						fmt.Printf("[fory-debug] pointer enum field %s: localType=%v baseType=%v serializer=%T\n",
							def.name, localType, baseType, fieldSerializer)
					}
				}
				if localType.Kind() == reflect.Array && isPrimitiveArrayType(TypeId(defTypeId)) {
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
				fieldType = remoteType
				fieldIndex = -1
				offset = 0
			}
		} else {
			fieldType = remoteType
		}

		if fieldType == nil {
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
			fieldSerializer, _ = typeResolver.getSerializerByType(fieldType, true)
		}

		fieldTypeId := def.typeSpec.TypeId()
		refMode := RefModeNone
		if def.trackRef {
			refMode = RefModeTracking
		} else if def.nullable {
			refMode = RefModeNullOnly
		}
		writeType := typeResolver.Compatible() && isStructField(baseType)
		var cachedTypeInfo *TypeInfo
		if writeType {
			cachedType := baseType
			if cachedType.Kind() == reflect.Ptr {
				cachedType = cachedType.Elem()
			}
			cachedTypeInfo = typeResolver.getTypeInfoByType(cachedType)
		}

		var dispatchId DispatchId
		localKind := fieldType.Kind()
		baseKind := localKind
		if isOptional {
			baseKind = baseType.Kind()
		}
		localIsPtr := localKind == reflect.Ptr
		localIsPrimitive := isPrimitiveDispatchKind(baseKind) || (localIsPtr && isPrimitiveDispatchKind(fieldType.Elem().Kind()))

		if fieldTypeId == LIST {
			dispatchType := baseType
			if dispatchType.Kind() == reflect.Ptr {
				dispatchType = dispatchType.Elem()
			}
			dispatchId = getListDispatchId(dispatchType)
		} else if localIsPrimitive {
			if def.nullable {
				dispatchId = getDispatchIdFromTypeId(fieldTypeId, true)
			} else {
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

		fieldName := def.name
		if localFieldName != "" {
			fieldName = localFieldName
		}
		var metaSpec *FieldSpec
		if localFieldSpec != nil {
			specCopy := *localFieldSpec
			metaSpec = &specCopy
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
				Spec:           metaSpec,
				TypeSpec:       def.typeSpec,
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
			fmt.Printf("[Go]   [%d] %s -> typeId=%d, nullable=%v\n", i, def.name, def.typeSpec.TypeId(), def.nullable)
		}
		s.fieldGroup.DebugPrint(s.type_.Name())
	}

	s.typeDefDiffers = false
	for i, field := range fields {
		if field.Meta.FieldIndex < 0 {
			if DebugOutputEnabled && s.type_ != nil {
				fmt.Printf("[Go][fory-debug] [%s] typeDefDiffers: missing local field for remote def idx=%d name=%q tagID=%d typeId=%d\n",
					s.name, i, s.fieldDefs[i].name, s.fieldDefs[i].tagID, s.fieldDefs[i].typeSpec.TypeId())
			}
			s.typeDefDiffers = true
			break
		}
		if i < len(s.fieldDefs) && field.Meta.FieldIndex >= 0 {
			localSpec := localSpecByIndex[field.Meta.FieldIndex]
			if !fieldSpecEqualForDiff(s.fieldDefs[i].typeSpec, s.fieldDefs[i].nullable, s.fieldDefs[i].trackRef, localSpec, localNullableByIndex[field.Meta.FieldIndex], localTrackRefByIndex[field.Meta.FieldIndex]) {
				if DebugOutputEnabled && s.type_ != nil {
					fmt.Printf("[Go][fory-debug] [%s] typeDefDiffers: semantic mismatch idx=%d name=%q tagID=%d remote=%s local=%s\n",
						s.name, i, s.fieldDefs[i].name, s.fieldDefs[i].tagID, s.fieldDefs[i].typeSpec, localSpec)
				}
				s.typeDefDiffers = true
				break
			}
			if s.fieldDefs[i].nullable != localNullableByIndex[field.Meta.FieldIndex] {
				if DebugOutputEnabled && s.type_ != nil {
					fmt.Printf("[Go][fory-debug] [%s] typeDefDiffers: nullable mismatch idx=%d name=%q tagID=%d remote=%v local=%v\n",
						s.name, i, s.fieldDefs[i].name, s.fieldDefs[i].tagID, s.fieldDefs[i].nullable, localNullableByIndex[field.Meta.FieldIndex])
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

func fieldSpecEqualForDiff(remoteSpec *TypeSpec, remoteNullable bool, remoteTrackRef bool, localSpec *TypeSpec, localNullable bool, localTrackRef bool) bool {
	if remoteSpec == nil || localSpec == nil {
		return remoteSpec == localSpec
	}
	remote := remoteSpec.Clone()
	local := localSpec.typeDefProjection(false)
	remote.Nullable = remoteNullable
	remote.TrackRef = remoteTrackRef
	local.Nullable = localNullable
	local.TrackRef = localTrackRef
	return remote.EqualForDiff(local)
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
	// bytes and array<uint8> share a byte payload shape and can be assigned in compatible mode.
	if (remoteTypeId == UINT8_ARRAY || remoteTypeId == BINARY) &&
		(localTypeId == UINT8_ARRAY || localTypeId == BINARY) {
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
		tagID := TagIDUseFieldName
		explicitRef := false
		if field.Meta.Spec != nil {
			tagID = field.Meta.Spec.TagID
			if field.Meta.Spec.NullableSet && field.Kind != FieldKindOptional {
				nullable = field.Meta.Spec.Nullable
			}
			explicitRef = field.Meta.Spec.RefSet && field.Meta.Spec.Ref
		} else {
			if field.Meta.FieldDef.tagID >= 0 {
				tagID = field.Meta.FieldDef.tagID
			}
			nullable = field.Meta.FieldDef.nullable
			explicitRef = field.Meta.FieldDef.trackRef
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
			FieldID:   tagID,
			FieldName: SnakeCase(field.Meta.Name),
			TypeSpec: func() *TypeSpec {
				if field.Meta.Spec == nil || field.Meta.Spec.Type == nil {
					return nil
				}
				projected := field.Meta.Spec.Type.Clone()
				projected.Nullable = nullable
				projected.TrackRef = explicitRef
				return projected
			}(),
			TypeID: typeId,
			// Ref is based on explicit tag annotation only, NOT runtime ref_tracking config
			// This allows fingerprint to be computed at compile time for C++/Rust
			Ref:      explicitRef,
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
