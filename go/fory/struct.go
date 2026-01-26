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
	"encoding/binary"
	"errors"
	"fmt"
	"math"
	"reflect"
	"sort"
	"unicode"
	"unicode/utf8"
	"unsafe"

	"github.com/spaolacci/murmur3"
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

type structSerializer struct {
	// Identity
	name       string
	type_      reflect.Type
	structHash int32

	// Pre-sorted and categorized fields (embedded for cache locality)
	fieldGroup FieldGroup

	// Original field list for hash computation and compatible mode
	fields    []FieldInfo // all fields in sorted order (before grouping)
	fieldDefs []FieldDef  // for type_def compatibility

	// Mode flags (set at init)
	isCompatibleMode bool // true when compatible=true
	typeDefDiffers   bool // true when compatible=true AND remote TypeDef != local (requires ordered read)

	// Initialization state
	initialized bool

	// Cached addressable value for non-addressable writes.
	tempValue *reflect.Value
}

// newStructSerializerFromTypeDef creates a new structSerializer with the given parameters.
// name can be empty and will be derived from type_.Name() if not provided.
// fieldDefs is from remote schema.
func newStructSerializerFromTypeDef(type_ reflect.Type, name string, fieldDefs []FieldDef) *structSerializer {
	if name == "" && type_ != nil {
		name = type_.Name()
	}
	return &structSerializer{
		type_:     type_,
		name:      name,
		fieldDefs: fieldDefs,
	}
}

// newStructSerializer creates a new structSerializer with the given parameters.
// name can be empty and will be derived from type_.Name() if not provided.
// fieldDefs can be nil for local structs without remote schema.
func newStructSerializer(type_ reflect.Type, name string) *structSerializer {
	if name == "" && type_ != nil {
		name = type_.Name()
	}
	return &structSerializer{
		type_: type_,
		name:  name,
	}
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
	internalId := TypeId(typeId & 0xFF)
	isEnum := internalId == ENUM || internalId == NAMED_ENUM
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
	if isNonNullablePrimitiveKind(fieldType.Kind()) && !isEnum {
		nullableFlag = false
	}
	return nullableFlag
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
		internalId := fieldTypeId & 0xFF
		isEnum := internalId == ENUM || internalId == NAMED_ENUM

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

		// Pre-compute RefMode based on (possibly overridden) trackRef and nullable
		// For pointer-to-struct fields, enable ref tracking when trackRef is enabled,
		// regardless of nullable flag. This is necessary to detect circular references.
		refMode := RefModeNone
		isStructPointer := fieldType.Kind() == reflect.Ptr && fieldType.Elem().Kind() == reflect.Struct
		if trackRef && (nullableFlag || isStructPointer) {
			refMode = RefModeTracking
		} else if nullableFlag {
			refMode = RefModeNullOnly
		}
		// Pre-compute WriteType: true for struct fields in compatible mode
		writeType := typeResolver.Compatible() && isStructField(baseType)

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
		if DebugOutputEnabled() {
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
			// The type ID may be a composite value with namespace bits, so check the low 8 bits
			internalDefTypeId := defTypeId & 0xFF
			isEnumField := internalDefTypeId == NAMED_ENUM || internalDefTypeId == ENUM
			if !isEnumField && fieldSerializer != nil {
				_, isEnumField = fieldSerializer.(*enumSerializer)
			}
			if isPolymorphicField && localType.Kind() == reflect.Interface {
				// For polymorphic (UNKNOWN) fields with any local type,
				// allow reading - the actual type will be determined at runtime
				shouldRead = true
				fieldType = localType
			} else if typeLookupFailed && isEnumField {
				// For enum fields with failed TypeDef lookup (NAMED_ENUM stores by namespace/typename, not typeId),
				// check if local field is a numeric type (Go enums are int-based)
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
			} else if typeLookupFailed && (defTypeId == LIST || defTypeId == SET) {
				// For collection fields with failed type lookup (e.g., List<Animal> with interface element type),
				// check if local type is a slice with interface element type (e.g., []Animal)
				// The type lookup fails because sliceSerializer doesn't support interface elements
				if localType.Kind() == reflect.Slice && localType.Elem().Kind() == reflect.Interface {
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
					if DebugOutputEnabled() {
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
				Name:         fieldName,
				Type:         fieldType,
				TypeId:       fieldTypeId,
				Nullable:     def.nullable, // Use remote nullable flag
				FieldIndex:   fieldIndex,
				FieldDef:     def, // Save original FieldDef for skipping
				WriteType:    writeType,
				HasGenerics:  isCollectionType(fieldTypeId), // Container fields have declared element types
				OptionalInfo: optionalInfo,
				TagID:        def.tagID,
				HasForyTag:   def.tagID >= 0,
			},
		}
		fields = append(fields, fieldInfo)
	}

	s.fields = fields
	s.fieldGroup = GroupFields(s.fields)

	// Debug output for field order comparison with Java MetaSharedSerializer
	if DebugOutputEnabled() && s.type_ != nil {
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
				s.typeDefDiffers = true
				break
			}
		}
	}

	if DebugOutputEnabled() && s.type_ != nil {
		fmt.Printf("[Go] typeDefDiffers=%v for %s\n", s.typeDefDiffers, s.type_.Name())
	}

	return nil
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
			internalId := TypeId(typeId & 0xFF)
			if internalId == TYPED_UNION || internalId == NAMED_UNION || internalId == UNION {
				typeId = UNION
			}
			// For user-defined types (struct, ext types), use UNKNOWN in fingerprint
			// This matches Java's behavior where user-defined types return UNKNOWN
			// to ensure consistent fingerprint computation across languages
			if isUserDefinedType(int16(typeId)) {
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
				if !isPrimitiveArrayType(int16(typeId)) && typeId != BINARY {
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
	h1, _ := murmur3.Sum128WithSeed(data, 47)
	hash := int32(h1 & 0xFFFFFFFF)

	if DebugOutputEnabled() {
		fmt.Printf("[Go][fory-debug] struct %v version fingerprint=\"%s\" version hash=%d\n", s.type_, hashString, hash)
	}

	if hash == 0 {
		panic(fmt.Errorf("hash for type %v is 0", s.type_))
	}
	return hash
}

func (s *structSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	switch refMode {
	case RefModeTracking:
		if value.Kind() == reflect.Ptr && value.IsNil() {
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
	case RefModeNullOnly:
		if value.Kind() == reflect.Ptr && value.IsNil() {
			ctx.buffer.WriteInt8(NullFlag)
			return
		}
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		// Structs have dynamic type IDs, need to look up from TypeResolver
		typeInfo, err := ctx.TypeResolver().getTypeInfo(value, true)
		if err != nil {
			ctx.SetError(FromError(err))
			return
		}
		ctx.TypeResolver().WriteTypeInfo(ctx.buffer, typeInfo, ctx.Err())
	}
	s.WriteData(ctx, value)
}

func (s *structSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	// Early error check - skip all intermediate checks for normal path performance
	if ctx.HasError() {
		return
	}

	// Lazy initialization
	if !s.initialized {
		if err := s.initialize(ctx.TypeResolver()); err != nil {
			ctx.SetError(FromError(err))
			return
		}
	}

	buf := ctx.Buffer()

	// Dereference pointer if needed
	if value.Kind() == reflect.Ptr {
		if value.IsNil() {
			ctx.SetError(SerializationError("cannot write nil pointer"))
			return
		}
		value = value.Elem()
	}

	// In compatible mode with meta share, struct hash is not written
	if !ctx.Compatible() {
		buf.WriteInt32(s.structHash)
	}

	// Ensure value is addressable for unsafe access
	if !value.CanAddr() {
		reuseCache := s.tempValue != nil
		if ctx.RefResolver().refTracking && len(ctx.RefResolver().writtenObjects) > 0 {
			reuseCache = false
		}
		if reuseCache {
			tempValue := s.tempValue
			s.tempValue = nil
			defer func() {
				tempValue.SetZero()
				s.tempValue = tempValue
			}()
			addrValue := *tempValue
			addrValue.Set(value)
			value = addrValue
		} else {
			tmp := reflect.New(value.Type()).Elem()
			tmp.Set(value)
			value = tmp
		}
	}
	ptr := unsafe.Pointer(value.UnsafeAddr())

	// ==========================================================================
	// Phase 1: Fixed-size primitives (bool, int8, int16, float32, float64)
	// - Reserve once, inline unsafe writes with endian handling, update index once
	// - field.WriteOffset computed at init time
	// ==========================================================================
	if s.fieldGroup.FixedSize > 0 {
		buf.Reserve(s.fieldGroup.FixedSize)
		baseOffset := buf.WriterIndex()
		data := buf.GetData()

		for _, field := range s.fieldGroup.PrimitiveFixedFields {
			fieldPtr := unsafe.Add(ptr, field.Offset)
			bufOffset := baseOffset + int(field.WriteOffset)
			optInfo := optionalInfo{}
			if field.Kind == FieldKindOptional && field.Meta != nil {
				optInfo = field.Meta.OptionalInfo
			}
			switch field.DispatchId {
			case PrimitiveBoolDispatchId:
				v, ok := loadFieldValue[bool](field.Kind, fieldPtr, optInfo)
				if ok && v {
					data[bufOffset] = 1
				} else {
					data[bufOffset] = 0
				}
			case PrimitiveInt8DispatchId:
				v, ok := loadFieldValue[int8](field.Kind, fieldPtr, optInfo)
				if ok {
					data[bufOffset] = byte(v)
				} else {
					data[bufOffset] = 0
				}
			case PrimitiveUint8DispatchId:
				v, ok := loadFieldValue[uint8](field.Kind, fieldPtr, optInfo)
				if ok {
					data[bufOffset] = v
				} else {
					data[bufOffset] = 0
				}
			case PrimitiveInt16DispatchId:
				v, ok := loadFieldValue[int16](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				if isLittleEndian {
					*(*int16)(unsafe.Pointer(&data[bufOffset])) = v
				} else {
					binary.LittleEndian.PutUint16(data[bufOffset:], uint16(v))
				}
			case PrimitiveUint16DispatchId:
				v, ok := loadFieldValue[uint16](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				if isLittleEndian {
					*(*uint16)(unsafe.Pointer(&data[bufOffset])) = v
				} else {
					binary.LittleEndian.PutUint16(data[bufOffset:], v)
				}
			case PrimitiveInt32DispatchId:
				v, ok := loadFieldValue[int32](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				if isLittleEndian {
					*(*int32)(unsafe.Pointer(&data[bufOffset])) = v
				} else {
					binary.LittleEndian.PutUint32(data[bufOffset:], uint32(v))
				}
			case PrimitiveUint32DispatchId:
				v, ok := loadFieldValue[uint32](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				if isLittleEndian {
					*(*uint32)(unsafe.Pointer(&data[bufOffset])) = v
				} else {
					binary.LittleEndian.PutUint32(data[bufOffset:], v)
				}
			case PrimitiveInt64DispatchId:
				v, ok := loadFieldValue[int64](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				if isLittleEndian {
					*(*int64)(unsafe.Pointer(&data[bufOffset])) = v
				} else {
					binary.LittleEndian.PutUint64(data[bufOffset:], uint64(v))
				}
			case PrimitiveUint64DispatchId:
				v, ok := loadFieldValue[uint64](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				if isLittleEndian {
					*(*uint64)(unsafe.Pointer(&data[bufOffset])) = v
				} else {
					binary.LittleEndian.PutUint64(data[bufOffset:], v)
				}
			case PrimitiveFloat32DispatchId:
				v, ok := loadFieldValue[float32](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				if isLittleEndian {
					*(*float32)(unsafe.Pointer(&data[bufOffset])) = v
				} else {
					binary.LittleEndian.PutUint32(data[bufOffset:], math.Float32bits(v))
				}
			case PrimitiveFloat64DispatchId:
				v, ok := loadFieldValue[float64](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				if isLittleEndian {
					*(*float64)(unsafe.Pointer(&data[bufOffset])) = v
				} else {
					binary.LittleEndian.PutUint64(data[bufOffset:], math.Float64bits(v))
				}
			}
		}
		// Update writer index ONCE after all fixed fields
		buf.SetWriterIndex(baseOffset + s.fieldGroup.FixedSize)
	}

	// ==========================================================================
	// Phase 2: Varint primitives (int32, int64, int, uint32, uint64, uint, tagged int64/uint64)
	// - Reserve max size once, track offset locally, update writerIndex once at end
	// ==========================================================================
	if s.fieldGroup.MaxVarintSize > 0 {
		buf.Reserve(s.fieldGroup.MaxVarintSize)
		offset := buf.WriterIndex()

		for _, field := range s.fieldGroup.PrimitiveVarintFields {
			fieldPtr := unsafe.Add(ptr, field.Offset)
			optInfo := optionalInfo{}
			if field.Kind == FieldKindOptional && field.Meta != nil {
				optInfo = field.Meta.OptionalInfo
			}
			switch field.DispatchId {
			case PrimitiveVarint32DispatchId:
				v, ok := loadFieldValue[int32](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				offset += buf.UnsafePutVarInt32(offset, v)
			case PrimitiveVarint64DispatchId:
				v, ok := loadFieldValue[int64](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				offset += buf.UnsafePutVarInt64(offset, v)
			case PrimitiveIntDispatchId:
				v, ok := loadFieldValue[int](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				offset += buf.UnsafePutVarInt64(offset, int64(v))
			case PrimitiveVarUint32DispatchId:
				v, ok := loadFieldValue[uint32](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				offset += buf.UnsafePutVaruint32(offset, v)
			case PrimitiveVarUint64DispatchId:
				v, ok := loadFieldValue[uint64](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				offset += buf.UnsafePutVaruint64(offset, v)
			case PrimitiveUintDispatchId:
				v, ok := loadFieldValue[uint](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				offset += buf.UnsafePutVaruint64(offset, uint64(v))
			case PrimitiveTaggedInt64DispatchId:
				v, ok := loadFieldValue[int64](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				offset += buf.UnsafePutTaggedInt64(offset, v)
			case PrimitiveTaggedUint64DispatchId:
				v, ok := loadFieldValue[uint64](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				offset += buf.UnsafePutTaggedUint64(offset, v)
			}
		}
		// Update writer index ONCE after all varint fields
		buf.SetWriterIndex(offset)
	}

	// ==========================================================================
	// Phase 3: Remaining fields (strings, slices, maps, structs, enums)
	// - These require per-field handling (ref flags, type info, serializers)
	// - No intermediate error checks - trade error path performance for normal path
	// ==========================================================================
	for i := range s.fieldGroup.RemainingFields {
		s.writeRemainingField(ctx, ptr, &s.fieldGroup.RemainingFields[i], value)
	}
}

// writeRemainingField writes a non-primitive field (string, slice, map, struct, enum)
func (s *structSerializer) writeRemainingField(ctx *WriteContext, ptr unsafe.Pointer, field *FieldInfo, value reflect.Value) {
	buf := ctx.Buffer()
	if field.Kind == FieldKindOptional {
		if ptr != nil {
			if writeOptionFast(ctx, field, unsafe.Add(ptr, field.Offset)) {
				return
			}
		}
		fieldValue := value.Field(field.Meta.FieldIndex)
		if field.Serializer != nil {
			field.Serializer.Write(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, fieldValue)
		} else {
			ctx.WriteValue(fieldValue, RefModeTracking, true)
		}
		return
	}
	// Fast path dispatch using pre-computed DispatchId
	// ptr must be valid (addressable value)
	if ptr != nil {
		fieldPtr := unsafe.Add(ptr, field.Offset)
		switch field.DispatchId {
		case StringDispatchId:
			// Check isPtr first for better branch prediction
			if field.Kind != FieldKindPointer {
				// Non-pointer string: always non-null, no ref tracking needed in fast path
				if field.RefMode == RefModeNone {
					ctx.WriteString(*(*string)(fieldPtr))
				} else {
					// RefModeNullOnly or RefModeTracking: write NotNull flag then string
					buf.WriteInt8(NotNullValueFlag)
					ctx.WriteString(*(*string)(fieldPtr))
				}
				return
			}
			// Pointer to string: can be nil, may need ref tracking
			if field.RefMode == RefModeTracking {
				break // Fall through to slow path for ref tracking
			}
			strPtr := *(**string)(fieldPtr)
			if strPtr == nil {
				if field.RefMode == RefModeNullOnly {
					buf.WriteInt8(NullFlag)
				} else {
					// RefModeNone: write empty string for nil pointer
					ctx.WriteString("")
				}
				return
			}
			// Non-nil pointer
			if field.RefMode == RefModeNullOnly {
				buf.WriteInt8(NotNullValueFlag)
			}
			ctx.WriteString(*strPtr)
			return
		case EnumDispatchId:
			// Enums don't track refs - always use fast path
			writeEnumField(ctx, field, value.Field(field.Meta.FieldIndex))
			return
		case StringSliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteStringSlice(*(*[]string)(fieldPtr), field.RefMode, false, true)
			return
		case BoolSliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteBoolSlice(*(*[]bool)(fieldPtr), field.RefMode, false)
			return
		case Int8SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteInt8Slice(*(*[]int8)(fieldPtr), field.RefMode, false)
			return
		case ByteSliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteByteSlice(*(*[]byte)(fieldPtr), field.RefMode, false)
			return
		case Int16SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteInt16Slice(*(*[]int16)(fieldPtr), field.RefMode, false)
			return
		case Int32SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteInt32Slice(*(*[]int32)(fieldPtr), field.RefMode, false)
			return
		case Int64SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteInt64Slice(*(*[]int64)(fieldPtr), field.RefMode, false)
			return
		case IntSliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteIntSlice(*(*[]int)(fieldPtr), field.RefMode, false)
			return
		case UintSliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteUintSlice(*(*[]uint)(fieldPtr), field.RefMode, false)
			return
		case Float32SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteFloat32Slice(*(*[]float32)(fieldPtr), field.RefMode, false)
			return
		case Float64SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteFloat64Slice(*(*[]float64)(fieldPtr), field.RefMode, false)
			return
		case StringStringMapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			if field.Meta.HasGenerics && field.Serializer != nil {
				field.Serializer.Write(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, value.Field(field.Meta.FieldIndex))
				return
			}
			ctx.WriteStringStringMap(*(*map[string]string)(fieldPtr), field.RefMode, false)
			return
		case StringInt64MapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			if field.Meta.HasGenerics && field.Serializer != nil {
				field.Serializer.Write(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, value.Field(field.Meta.FieldIndex))
				return
			}
			ctx.WriteStringInt64Map(*(*map[string]int64)(fieldPtr), field.RefMode, false)
			return
		case StringInt32MapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			if field.Meta.HasGenerics && field.Serializer != nil {
				field.Serializer.Write(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, value.Field(field.Meta.FieldIndex))
				return
			}
			ctx.WriteStringInt32Map(*(*map[string]int32)(fieldPtr), field.RefMode, false)
			return
		case StringIntMapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			if field.Meta.HasGenerics && field.Serializer != nil {
				field.Serializer.Write(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, value.Field(field.Meta.FieldIndex))
				return
			}
			ctx.WriteStringIntMap(*(*map[string]int)(fieldPtr), field.RefMode, false)
			return
		case StringFloat64MapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			if field.Meta.HasGenerics && field.Serializer != nil {
				field.Serializer.Write(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, value.Field(field.Meta.FieldIndex))
				return
			}
			ctx.WriteStringFloat64Map(*(*map[string]float64)(fieldPtr), field.RefMode, false)
			return
		case StringBoolMapDispatchId:
			// map[string]bool is a regular map in Go - use MAP format
			// Note: fory.Set[T] uses struct{} values and has its own setSerializer
			if field.RefMode == RefModeTracking {
				break
			}
			if field.Meta.HasGenerics && field.Serializer != nil {
				field.Serializer.Write(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, value.Field(field.Meta.FieldIndex))
				return
			}
			ctx.WriteStringBoolMap(*(*map[string]bool)(fieldPtr), field.RefMode, false)
			return
		case IntIntMapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			if field.Meta.HasGenerics && field.Serializer != nil {
				field.Serializer.Write(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, value.Field(field.Meta.FieldIndex))
				return
			}
			ctx.WriteIntIntMap(*(*map[int]int)(fieldPtr), field.RefMode, false)
			return
		case NullableTaggedInt64DispatchId:
			// Nullable tagged INT64: write ref flag, then tagged encoding
			ptr := *(**int64)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteTaggedInt64(*ptr)
			return
		case NullableTaggedUint64DispatchId:
			// Nullable tagged UINT64: write ref flag, then tagged encoding
			ptr := *(**uint64)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteTaggedUint64(*ptr)
			return
		// Nullable fixed-size types
		case NullableBoolDispatchId:
			ptr := *(**bool)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteBool(*ptr)
			return
		case NullableInt8DispatchId:
			ptr := *(**int8)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteInt8(*ptr)
			return
		case NullableUint8DispatchId:
			ptr := *(**uint8)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteUint8(*ptr)
			return
		case NullableInt16DispatchId:
			ptr := *(**int16)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteInt16(*ptr)
			return
		case NullableUint16DispatchId:
			ptr := *(**uint16)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteUint16(*ptr)
			return
		case NullableInt32DispatchId:
			ptr := *(**int32)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteInt32(*ptr)
			return
		case NullableUint32DispatchId:
			ptr := *(**uint32)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteUint32(*ptr)
			return
		case NullableInt64DispatchId:
			ptr := *(**int64)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteInt64(*ptr)
			return
		case NullableUint64DispatchId:
			ptr := *(**uint64)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteUint64(*ptr)
			return
		case NullableFloat32DispatchId:
			ptr := *(**float32)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteFloat32(*ptr)
			return
		case NullableFloat64DispatchId:
			ptr := *(**float64)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteFloat64(*ptr)
			return
		// Nullable varint types
		case NullableVarint32DispatchId:
			ptr := *(**int32)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteVarint32(*ptr)
			return
		case NullableVarUint32DispatchId:
			ptr := *(**uint32)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteVaruint32(*ptr)
			return
		case NullableVarint64DispatchId:
			ptr := *(**int64)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteVarint64(*ptr)
			return
		case NullableVarUint64DispatchId:
			ptr := *(**uint64)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteVaruint64(*ptr)
			return
		}
	}

	if ptr == nil {
		ctx.SetError(SerializationError("cannot write struct field without addressable value"))
		return
	}

	// Fall back to serializer for other types
	fieldValue := value.Field(field.Meta.FieldIndex)
	if field.Serializer != nil {
		field.Serializer.Write(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, fieldValue)
	} else {
		ctx.WriteValue(fieldValue, RefModeTracking, true)
	}
}

func loadFieldValue[T any](kind FieldKind, fieldPtr unsafe.Pointer, opt optionalInfo) (T, bool) {
	var zero T
	switch kind {
	case FieldKindPointer:
		ptr := *(**T)(fieldPtr)
		if ptr == nil {
			return zero, false
		}
		return *ptr, true
	case FieldKindOptional:
		if !*(*bool)(unsafe.Add(fieldPtr, opt.hasOffset)) {
			return zero, false
		}
		return *(*T)(unsafe.Add(fieldPtr, opt.valueOffset)), true
	default:
		return *(*T)(fieldPtr), true
	}
}

func storeFieldValue[T any](kind FieldKind, fieldPtr unsafe.Pointer, opt optionalInfo, value T) {
	switch kind {
	case FieldKindPointer:
		ptr := *(**T)(fieldPtr)
		if ptr == nil {
			ptr = new(T)
			*(**T)(fieldPtr) = ptr
		}
		*ptr = value
	case FieldKindOptional:
		*(*bool)(unsafe.Add(fieldPtr, opt.hasOffset)) = true
		*(*T)(unsafe.Add(fieldPtr, opt.valueOffset)) = value
	default:
		*(*T)(fieldPtr) = value
	}
}

func clearFieldValue(kind FieldKind, fieldPtr unsafe.Pointer, opt optionalInfo) {
	switch kind {
	case FieldKindPointer:
		*(*unsafe.Pointer)(fieldPtr) = nil
	case FieldKindOptional:
		*(*bool)(unsafe.Add(fieldPtr, opt.hasOffset)) = false
	default:
	}
}

func writeOptionFast(ctx *WriteContext, field *FieldInfo, optPtr unsafe.Pointer) bool {
	buf := ctx.Buffer()
	has := *(*bool)(unsafe.Add(optPtr, field.Meta.OptionalInfo.hasOffset))
	valuePtr := unsafe.Add(optPtr, field.Meta.OptionalInfo.valueOffset)
	switch field.DispatchId {
	case StringDispatchId:
		if field.RefMode != RefModeNone {
			if !has {
				buf.WriteInt8(NullFlag)
				return true
			}
			buf.WriteInt8(NotNullValueFlag)
		} else if !has {
			ctx.WriteString("")
			return true
		}
		if has {
			ctx.WriteString(*(*string)(valuePtr))
		} else {
			ctx.WriteString("")
		}
		return true
	case NullableTaggedInt64DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteTaggedInt64(*(*int64)(valuePtr))
			} else {
				buf.WriteTaggedInt64(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteTaggedInt64(*(*int64)(valuePtr))
		return true
	case NullableTaggedUint64DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteTaggedUint64(*(*uint64)(valuePtr))
			} else {
				buf.WriteTaggedUint64(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteTaggedUint64(*(*uint64)(valuePtr))
		return true
	case NullableBoolDispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteBool(*(*bool)(valuePtr))
			} else {
				buf.WriteBool(false)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteBool(*(*bool)(valuePtr))
		return true
	case NullableInt8DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteInt8(*(*int8)(valuePtr))
			} else {
				buf.WriteInt8(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteInt8(*(*int8)(valuePtr))
		return true
	case NullableUint8DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteUint8(*(*uint8)(valuePtr))
			} else {
				buf.WriteUint8(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteUint8(*(*uint8)(valuePtr))
		return true
	case NullableInt16DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteInt16(*(*int16)(valuePtr))
			} else {
				buf.WriteInt16(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteInt16(*(*int16)(valuePtr))
		return true
	case NullableUint16DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteUint16(*(*uint16)(valuePtr))
			} else {
				buf.WriteUint16(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteUint16(*(*uint16)(valuePtr))
		return true
	case NullableInt32DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteInt32(*(*int32)(valuePtr))
			} else {
				buf.WriteInt32(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteInt32(*(*int32)(valuePtr))
		return true
	case NullableUint32DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteUint32(*(*uint32)(valuePtr))
			} else {
				buf.WriteUint32(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteUint32(*(*uint32)(valuePtr))
		return true
	case NullableInt64DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteInt64(*(*int64)(valuePtr))
			} else {
				buf.WriteInt64(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteInt64(*(*int64)(valuePtr))
		return true
	case NullableUint64DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteUint64(*(*uint64)(valuePtr))
			} else {
				buf.WriteUint64(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteUint64(*(*uint64)(valuePtr))
		return true
	case NullableFloat32DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteFloat32(*(*float32)(valuePtr))
			} else {
				buf.WriteFloat32(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteFloat32(*(*float32)(valuePtr))
		return true
	case NullableFloat64DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteFloat64(*(*float64)(valuePtr))
			} else {
				buf.WriteFloat64(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteFloat64(*(*float64)(valuePtr))
		return true
	case NullableVarint32DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteVarint32(*(*int32)(valuePtr))
			} else {
				buf.WriteVarint32(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteVarint32(*(*int32)(valuePtr))
		return true
	case NullableVarUint32DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteVaruint32(*(*uint32)(valuePtr))
			} else {
				buf.WriteVaruint32(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteVaruint32(*(*uint32)(valuePtr))
		return true
	case NullableVarint64DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteVarint64(*(*int64)(valuePtr))
			} else {
				buf.WriteVarint64(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteVarint64(*(*int64)(valuePtr))
		return true
	case NullableVarUint64DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteVaruint64(*(*uint64)(valuePtr))
			} else {
				buf.WriteVaruint64(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteVaruint64(*(*uint64)(valuePtr))
		return true
	case PrimitiveBoolDispatchId:
		if has {
			buf.WriteBool(*(*bool)(valuePtr))
		} else {
			buf.WriteBool(false)
		}
		return true
	case PrimitiveInt8DispatchId:
		if has {
			buf.WriteInt8(*(*int8)(valuePtr))
		} else {
			buf.WriteInt8(0)
		}
		return true
	case PrimitiveUint8DispatchId:
		if has {
			buf.WriteUint8(*(*uint8)(valuePtr))
		} else {
			buf.WriteUint8(0)
		}
		return true
	case PrimitiveInt16DispatchId:
		if has {
			buf.WriteInt16(*(*int16)(valuePtr))
		} else {
			buf.WriteInt16(0)
		}
		return true
	case PrimitiveUint16DispatchId:
		if has {
			buf.WriteUint16(*(*uint16)(valuePtr))
		} else {
			buf.WriteUint16(0)
		}
		return true
	case PrimitiveInt32DispatchId:
		if has {
			buf.WriteInt32(*(*int32)(valuePtr))
		} else {
			buf.WriteInt32(0)
		}
		return true
	case PrimitiveVarint32DispatchId:
		if has {
			buf.WriteVarint32(*(*int32)(valuePtr))
		} else {
			buf.WriteVarint32(0)
		}
		return true
	case PrimitiveInt64DispatchId:
		if has {
			buf.WriteInt64(*(*int64)(valuePtr))
		} else {
			buf.WriteInt64(0)
		}
		return true
	case PrimitiveVarint64DispatchId:
		if has {
			buf.WriteVarint64(*(*int64)(valuePtr))
		} else {
			buf.WriteVarint64(0)
		}
		return true
	case PrimitiveIntDispatchId:
		if has {
			buf.WriteVarint64(int64(*(*int)(valuePtr)))
		} else {
			buf.WriteVarint64(0)
		}
		return true
	case PrimitiveUint32DispatchId:
		if has {
			buf.WriteUint32(*(*uint32)(valuePtr))
		} else {
			buf.WriteUint32(0)
		}
		return true
	case PrimitiveVarUint32DispatchId:
		if has {
			buf.WriteVaruint32(*(*uint32)(valuePtr))
		} else {
			buf.WriteVaruint32(0)
		}
		return true
	case PrimitiveUint64DispatchId:
		if has {
			buf.WriteUint64(*(*uint64)(valuePtr))
		} else {
			buf.WriteUint64(0)
		}
		return true
	case PrimitiveVarUint64DispatchId:
		if has {
			buf.WriteVaruint64(*(*uint64)(valuePtr))
		} else {
			buf.WriteVaruint64(0)
		}
		return true
	case PrimitiveUintDispatchId:
		if has {
			buf.WriteVaruint64(uint64(*(*uint)(valuePtr)))
		} else {
			buf.WriteVaruint64(0)
		}
		return true
	case PrimitiveTaggedInt64DispatchId:
		if has {
			buf.WriteTaggedInt64(*(*int64)(valuePtr))
		} else {
			buf.WriteTaggedInt64(0)
		}
		return true
	case PrimitiveTaggedUint64DispatchId:
		if has {
			buf.WriteTaggedUint64(*(*uint64)(valuePtr))
		} else {
			buf.WriteTaggedUint64(0)
		}
		return true
	case PrimitiveFloat32DispatchId:
		if has {
			buf.WriteFloat32(*(*float32)(valuePtr))
		} else {
			buf.WriteFloat32(0)
		}
		return true
	case PrimitiveFloat64DispatchId:
		if has {
			buf.WriteFloat64(*(*float64)(valuePtr))
		} else {
			buf.WriteFloat64(0)
		}
		return true
	default:
		return false
	}
}

func (s *structSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	switch refMode {
	case RefModeTracking:
		refID, refErr := ctx.RefResolver().TryPreserveRefId(buf)
		if refErr != nil {
			ctx.SetError(FromError(refErr))
			return
		}
		if refID < int32(NotNullValueFlag) {
			// Reference found
			obj := ctx.RefResolver().GetReadObject(refID)
			if obj.IsValid() {
				value.Set(obj)
			}
			return
		}
	case RefModeNullOnly:
		flag := buf.ReadInt8(ctxErr)
		if flag == NullFlag {
			return
		}
	}
	if readType {
		// Read type info - in compatible mode this returns the serializer with remote fieldDefs
		typeID := buf.ReadVaruint32Small7(ctxErr)
		internalTypeID := TypeId(typeID & 0xFF)
		// Check if this is a struct type that needs type meta reading
		if IsNamespacedType(TypeId(typeID)) || internalTypeID == COMPATIBLE_STRUCT || internalTypeID == STRUCT {
			// For struct types in compatible mode, use the serializer from TypeInfo
			typeInfo := ctx.TypeResolver().readTypeInfoWithTypeID(buf, typeID, ctxErr)
			// Use the serializer from TypeInfo which has the remote field definitions
			if structSer, ok := typeInfo.Serializer.(*structSerializer); ok && len(structSer.fieldDefs) > 0 {
				structSer.ReadData(ctx, value)
				return
			}
		}
	}
	s.ReadData(ctx, value)
}

func (s *structSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	// typeInfo is already read, don't read it again
	s.Read(ctx, refMode, false, false, value)
}

func (s *structSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	// Early error check - skip all intermediate checks for normal path performance
	if ctx.HasError() {
		return
	}

	// Lazy initialization
	if !s.initialized {
		if err := s.initialize(ctx.TypeResolver()); err != nil {
			ctx.SetError(FromError(err))
			return
		}
	}

	buf := ctx.Buffer()
	if value.Kind() == reflect.Ptr {
		if value.IsNil() {
			value.Set(reflect.New(value.Type().Elem()))
		}
		value = value.Elem()
	}

	// In compatible mode with meta share, struct hash is not written
	if !ctx.Compatible() {
		err := ctx.Err()
		structHash := buf.ReadInt32(err)
		if structHash != s.structHash {
			ctx.SetError(HashMismatchError(structHash, s.structHash, s.type_.String()))
			return
		}
	}

	// Fail fast if value is not addressable - we require unsafe pointer access
	if !value.CanAddr() {
		ctx.SetError(SerializationError("cannot deserialize struct " + s.type_.Name() + " into non-addressable value"))
		return
	}

	// Use ordered reading when TypeDef differs from local type (schema evolution)
	if s.typeDefDiffers {
		s.readFieldsInOrder(ctx, value)
		return
	}

	// ==========================================================================
	// Grouped reading for matching types (optimized path)
	// - Types match, so all fields exist locally (no FieldIndex < 0 checks)
	// - Use UnsafeGet at pre-computed offsets, update reader index once per phase
	// ==========================================================================
	ptr := unsafe.Pointer(value.UnsafeAddr())

	// Phase 1: Fixed-size primitives (inline unsafe reads with endian handling)
	if s.fieldGroup.FixedSize > 0 {
		baseOffset := buf.ReaderIndex()
		data := buf.GetData()

		for _, field := range s.fieldGroup.PrimitiveFixedFields {
			fieldPtr := unsafe.Add(ptr, field.Offset)
			bufOffset := baseOffset + int(field.WriteOffset)
			optInfo := optionalInfo{}
			if field.Kind == FieldKindOptional && field.Meta != nil {
				optInfo = field.Meta.OptionalInfo
			}
			switch field.DispatchId {
			case PrimitiveBoolDispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, data[bufOffset] != 0)
			case PrimitiveInt8DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, int8(data[bufOffset]))
			case PrimitiveUint8DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, data[bufOffset])
			case PrimitiveInt16DispatchId:
				var v int16
				if isLittleEndian {
					v = *(*int16)(unsafe.Pointer(&data[bufOffset]))
				} else {
					v = int16(binary.LittleEndian.Uint16(data[bufOffset:]))
				}
				storeFieldValue(field.Kind, fieldPtr, optInfo, v)
			case PrimitiveUint16DispatchId:
				var v uint16
				if isLittleEndian {
					v = *(*uint16)(unsafe.Pointer(&data[bufOffset]))
				} else {
					v = binary.LittleEndian.Uint16(data[bufOffset:])
				}
				storeFieldValue(field.Kind, fieldPtr, optInfo, v)
			case PrimitiveInt32DispatchId:
				var v int32
				if isLittleEndian {
					v = *(*int32)(unsafe.Pointer(&data[bufOffset]))
				} else {
					v = int32(binary.LittleEndian.Uint32(data[bufOffset:]))
				}
				storeFieldValue(field.Kind, fieldPtr, optInfo, v)
			case PrimitiveUint32DispatchId:
				var v uint32
				if isLittleEndian {
					v = *(*uint32)(unsafe.Pointer(&data[bufOffset]))
				} else {
					v = binary.LittleEndian.Uint32(data[bufOffset:])
				}
				storeFieldValue(field.Kind, fieldPtr, optInfo, v)
			case PrimitiveInt64DispatchId:
				var v int64
				if isLittleEndian {
					v = *(*int64)(unsafe.Pointer(&data[bufOffset]))
				} else {
					v = int64(binary.LittleEndian.Uint64(data[bufOffset:]))
				}
				storeFieldValue(field.Kind, fieldPtr, optInfo, v)
			case PrimitiveUint64DispatchId:
				var v uint64
				if isLittleEndian {
					v = *(*uint64)(unsafe.Pointer(&data[bufOffset]))
				} else {
					v = binary.LittleEndian.Uint64(data[bufOffset:])
				}
				storeFieldValue(field.Kind, fieldPtr, optInfo, v)
			case PrimitiveFloat32DispatchId:
				var v float32
				if isLittleEndian {
					v = *(*float32)(unsafe.Pointer(&data[bufOffset]))
				} else {
					v = math.Float32frombits(binary.LittleEndian.Uint32(data[bufOffset:]))
				}
				storeFieldValue(field.Kind, fieldPtr, optInfo, v)
			case PrimitiveFloat64DispatchId:
				var v float64
				if isLittleEndian {
					v = *(*float64)(unsafe.Pointer(&data[bufOffset]))
				} else {
					v = math.Float64frombits(binary.LittleEndian.Uint64(data[bufOffset:]))
				}
				storeFieldValue(field.Kind, fieldPtr, optInfo, v)
			}
		}
		// Update reader index ONCE after all fixed fields
		buf.SetReaderIndex(baseOffset + s.fieldGroup.FixedSize)
	}

	// Phase 2: Varint primitives (must read sequentially - variable length)
	// Note: For tagged int64/uint64, we can't use unsafe reads because they need bounds checking
	if len(s.fieldGroup.PrimitiveVarintFields) > 0 {
		err := ctx.Err()
		for _, field := range s.fieldGroup.PrimitiveVarintFields {
			fieldPtr := unsafe.Add(ptr, field.Offset)
			optInfo := optionalInfo{}
			if field.Kind == FieldKindOptional && field.Meta != nil {
				optInfo = field.Meta.OptionalInfo
			}
			switch field.DispatchId {
			case PrimitiveVarint32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVarint32(err))
			case PrimitiveVarint64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVarint64(err))
			case PrimitiveIntDispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, int(buf.ReadVarint64(err)))
			case PrimitiveVarUint32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVaruint32(err))
			case PrimitiveVarUint64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVaruint64(err))
			case PrimitiveUintDispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, uint(buf.ReadVaruint64(err)))
			case PrimitiveTaggedInt64DispatchId:
				// Tagged INT64: use buffer's tagged decoding (4 bytes for small, 9 for large)
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadTaggedInt64(err))
			case PrimitiveTaggedUint64DispatchId:
				// Tagged UINT64: use buffer's tagged decoding (4 bytes for small, 9 for large)
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadTaggedUint64(err))
			}
		}
	}

	// Phase 3: Remaining fields (strings, slices, maps, structs, enums)
	// No intermediate error checks - trade error path performance for normal path
	for i := range s.fieldGroup.RemainingFields {
		s.readRemainingField(ctx, ptr, &s.fieldGroup.RemainingFields[i], value)
	}
}

// readRemainingField reads a non-primitive field (string, slice, map, struct, enum)
func (s *structSerializer) readRemainingField(ctx *ReadContext, ptr unsafe.Pointer, field *FieldInfo, value reflect.Value) {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	if field.Kind == FieldKindOptional {
		if ptr != nil {
			if readOptionFast(ctx, field, unsafe.Add(ptr, field.Offset)) {
				return
			}
		}
		fieldValue := value.Field(field.Meta.FieldIndex)
		if field.Serializer != nil {
			field.Serializer.Read(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, fieldValue)
		} else {
			ctx.ReadValue(fieldValue, RefModeTracking, true)
		}
		return
	}
	// Fast path dispatch using pre-computed DispatchId
	// ptr must be valid (addressable value)
	if ptr != nil {
		fieldPtr := unsafe.Add(ptr, field.Offset)
		switch field.DispatchId {
		case StringDispatchId:
			// Check isPtr first for better branch prediction
			if field.Kind != FieldKindPointer {
				// Non-pointer string: no ref tracking needed in fast path
				if field.RefMode == RefModeNone {
					*(*string)(fieldPtr) = ctx.ReadString()
				} else {
					// RefModeNullOnly or RefModeTracking: read NotNull flag then string
					refFlag := buf.ReadInt8(ctxErr)
					if refFlag == NullFlag {
						*(*string)(fieldPtr) = ""
					} else {
						*(*string)(fieldPtr) = ctx.ReadString()
					}
				}
				return
			}
			// Pointer to string: can be nil, may need ref tracking
			if field.RefMode == RefModeTracking {
				break // Fall through to slow path for ref tracking
			}
			if field.RefMode == RefModeNullOnly {
				refFlag := buf.ReadInt8(ctxErr)
				if refFlag == NullFlag {
					// Leave as nil
					return
				}
			}
			// Allocate new string and store pointer
			str := ctx.ReadString()
			sp := new(string)
			*sp = str
			*(**string)(fieldPtr) = sp
			return
		case EnumDispatchId:
			// Enums don't track refs - always use fast path
			readEnumFieldUnsafe(ctx, field, fieldPtr)
			return
		case StringSliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]string)(fieldPtr) = ctx.ReadStringSlice(field.RefMode, false)
			return
		case BoolSliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]bool)(fieldPtr) = ctx.ReadBoolSlice(field.RefMode, false)
			return
		case Int8SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]int8)(fieldPtr) = ctx.ReadInt8Slice(field.RefMode, false)
			return
		case ByteSliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]byte)(fieldPtr) = ctx.ReadByteSlice(field.RefMode, false)
			return
		case Int16SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]int16)(fieldPtr) = ctx.ReadInt16Slice(field.RefMode, false)
			return
		case Int32SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]int32)(fieldPtr) = ctx.ReadInt32Slice(field.RefMode, false)
			return
		case Int64SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]int64)(fieldPtr) = ctx.ReadInt64Slice(field.RefMode, false)
			return
		case IntSliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]int)(fieldPtr) = ctx.ReadIntSlice(field.RefMode, false)
			return
		case UintSliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]uint)(fieldPtr) = ctx.ReadUintSlice(field.RefMode, false)
			return
		case Float32SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]float32)(fieldPtr) = ctx.ReadFloat32Slice(field.RefMode, false)
			return
		case Float64SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]float64)(fieldPtr) = ctx.ReadFloat64Slice(field.RefMode, false)
			return
		case StringStringMapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*map[string]string)(fieldPtr) = ctx.ReadStringStringMap(field.RefMode, false)
			return
		case StringInt64MapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*map[string]int64)(fieldPtr) = ctx.ReadStringInt64Map(field.RefMode, false)
			return
		case StringInt32MapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*map[string]int32)(fieldPtr) = ctx.ReadStringInt32Map(field.RefMode, false)
			return
		case StringIntMapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*map[string]int)(fieldPtr) = ctx.ReadStringIntMap(field.RefMode, false)
			return
		case StringFloat64MapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*map[string]float64)(fieldPtr) = ctx.ReadStringFloat64Map(field.RefMode, false)
			return
		case StringBoolMapDispatchId:
			// map[string]bool is a regular map in Go - use MAP format
			// Note: fory.Set[T] uses struct{} values and has its own setSerializer
			if field.RefMode == RefModeTracking {
				break
			}
			*(*map[string]bool)(fieldPtr) = ctx.ReadStringBoolMap(field.RefMode, false)
			return
		case IntIntMapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*map[int]int)(fieldPtr) = ctx.ReadIntIntMap(field.RefMode, false)
			return
		case NullableTaggedInt64DispatchId:
			// Nullable tagged INT64: read ref flag, then tagged encoding
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				// Leave pointer as nil
				return
			}
			// Allocate new int64 and store pointer
			v := new(int64)
			*v = buf.ReadTaggedInt64(ctxErr)
			*(**int64)(fieldPtr) = v
			return
		case NullableTaggedUint64DispatchId:
			// Nullable tagged UINT64: read ref flag, then tagged encoding
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				// Leave pointer as nil
				return
			}
			// Allocate new uint64 and store pointer
			v := new(uint64)
			*v = buf.ReadTaggedUint64(ctxErr)
			*(**uint64)(fieldPtr) = v
			return
		// Nullable fixed-size types
		case NullableBoolDispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(bool)
			*v = buf.ReadBool(ctxErr)
			*(**bool)(fieldPtr) = v
			return
		case NullableInt8DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(int8)
			*v = buf.ReadInt8(ctxErr)
			*(**int8)(fieldPtr) = v
			return
		case NullableUint8DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(uint8)
			*v = buf.ReadUint8(ctxErr)
			*(**uint8)(fieldPtr) = v
			return
		case NullableInt16DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(int16)
			*v = buf.ReadInt16(ctxErr)
			*(**int16)(fieldPtr) = v
			return
		case NullableUint16DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(uint16)
			*v = buf.ReadUint16(ctxErr)
			*(**uint16)(fieldPtr) = v
			return
		case NullableInt32DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(int32)
			*v = buf.ReadInt32(ctxErr)
			*(**int32)(fieldPtr) = v
			return
		case NullableUint32DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(uint32)
			*v = buf.ReadUint32(ctxErr)
			*(**uint32)(fieldPtr) = v
			return
		case NullableInt64DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(int64)
			*v = buf.ReadInt64(ctxErr)
			*(**int64)(fieldPtr) = v
			return
		case NullableUint64DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(uint64)
			*v = buf.ReadUint64(ctxErr)
			*(**uint64)(fieldPtr) = v
			return
		case NullableFloat32DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(float32)
			*v = buf.ReadFloat32(ctxErr)
			*(**float32)(fieldPtr) = v
			return
		case NullableFloat64DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(float64)
			*v = buf.ReadFloat64(ctxErr)
			*(**float64)(fieldPtr) = v
			return
		// Nullable varint types
		case NullableVarint32DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(int32)
			*v = buf.ReadVarint32(ctxErr)
			*(**int32)(fieldPtr) = v
			return
		case NullableVarUint32DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(uint32)
			*v = buf.ReadVaruint32(ctxErr)
			*(**uint32)(fieldPtr) = v
			return
		case NullableVarint64DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(int64)
			*v = buf.ReadVarint64(ctxErr)
			*(**int64)(fieldPtr) = v
			return
		case NullableVarUint64DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(uint64)
			*v = buf.ReadVaruint64(ctxErr)
			*(**uint64)(fieldPtr) = v
			return
		}
	}

	// Slow path for RefModeTracking cases that break from the switch above
	fieldValue := value.Field(field.Meta.FieldIndex)
	if field.Serializer != nil {
		field.Serializer.Read(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, fieldValue)
	} else {
		ctx.ReadValue(fieldValue, RefModeTracking, true)
	}
}

func readOptionFast(ctx *ReadContext, field *FieldInfo, optPtr unsafe.Pointer) bool {
	buf := ctx.Buffer()
	err := ctx.Err()
	hasPtr := (*bool)(unsafe.Add(optPtr, field.Meta.OptionalInfo.hasOffset))
	valuePtr := unsafe.Add(optPtr, field.Meta.OptionalInfo.valueOffset)
	switch field.DispatchId {
	case StringDispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*string)(valuePtr) = ""
				return true
			}
		}
		*hasPtr = true
		*(*string)(valuePtr) = ctx.ReadString()
		return true
	case NullableTaggedInt64DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*int64)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*int64)(valuePtr) = buf.ReadTaggedInt64(err)
		return true
	case NullableTaggedUint64DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*uint64)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*uint64)(valuePtr) = buf.ReadTaggedUint64(err)
		return true
	case NullableBoolDispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*bool)(valuePtr) = false
				return true
			}
		}
		*hasPtr = true
		*(*bool)(valuePtr) = buf.ReadBool(err)
		return true
	case NullableInt8DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*int8)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*int8)(valuePtr) = buf.ReadInt8(err)
		return true
	case NullableUint8DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*uint8)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*uint8)(valuePtr) = buf.ReadUint8(err)
		return true
	case NullableInt16DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*int16)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*int16)(valuePtr) = buf.ReadInt16(err)
		return true
	case NullableUint16DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*uint16)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*uint16)(valuePtr) = buf.ReadUint16(err)
		return true
	case NullableInt32DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*int32)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*int32)(valuePtr) = buf.ReadInt32(err)
		return true
	case NullableUint32DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*uint32)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*uint32)(valuePtr) = buf.ReadUint32(err)
		return true
	case NullableInt64DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*int64)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*int64)(valuePtr) = buf.ReadInt64(err)
		return true
	case NullableUint64DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*uint64)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*uint64)(valuePtr) = buf.ReadUint64(err)
		return true
	case NullableFloat32DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*float32)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*float32)(valuePtr) = buf.ReadFloat32(err)
		return true
	case NullableFloat64DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*float64)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*float64)(valuePtr) = buf.ReadFloat64(err)
		return true
	case NullableVarint32DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*int32)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*int32)(valuePtr) = buf.ReadVarint32(err)
		return true
	case NullableVarUint32DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*uint32)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*uint32)(valuePtr) = buf.ReadVaruint32(err)
		return true
	case NullableVarint64DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*int64)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*int64)(valuePtr) = buf.ReadVarint64(err)
		return true
	case NullableVarUint64DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*uint64)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*uint64)(valuePtr) = buf.ReadVaruint64(err)
		return true
	case PrimitiveBoolDispatchId:
		*hasPtr = true
		*(*bool)(valuePtr) = buf.ReadBool(err)
		return true
	case PrimitiveInt8DispatchId:
		*hasPtr = true
		*(*int8)(valuePtr) = buf.ReadInt8(err)
		return true
	case PrimitiveUint8DispatchId:
		*hasPtr = true
		*(*uint8)(valuePtr) = buf.ReadUint8(err)
		return true
	case PrimitiveInt16DispatchId:
		*hasPtr = true
		*(*int16)(valuePtr) = buf.ReadInt16(err)
		return true
	case PrimitiveUint16DispatchId:
		*hasPtr = true
		*(*uint16)(valuePtr) = buf.ReadUint16(err)
		return true
	case PrimitiveInt32DispatchId:
		*hasPtr = true
		*(*int32)(valuePtr) = buf.ReadInt32(err)
		return true
	case PrimitiveVarint32DispatchId:
		*hasPtr = true
		*(*int32)(valuePtr) = buf.ReadVarint32(err)
		return true
	case PrimitiveInt64DispatchId:
		*hasPtr = true
		*(*int64)(valuePtr) = buf.ReadInt64(err)
		return true
	case PrimitiveVarint64DispatchId:
		*hasPtr = true
		*(*int64)(valuePtr) = buf.ReadVarint64(err)
		return true
	case PrimitiveIntDispatchId:
		*hasPtr = true
		*(*int)(valuePtr) = int(buf.ReadVarint64(err))
		return true
	case PrimitiveUint32DispatchId:
		*hasPtr = true
		*(*uint32)(valuePtr) = buf.ReadUint32(err)
		return true
	case PrimitiveVarUint32DispatchId:
		*hasPtr = true
		*(*uint32)(valuePtr) = buf.ReadVaruint32(err)
		return true
	case PrimitiveUint64DispatchId:
		*hasPtr = true
		*(*uint64)(valuePtr) = buf.ReadUint64(err)
		return true
	case PrimitiveVarUint64DispatchId:
		*hasPtr = true
		*(*uint64)(valuePtr) = buf.ReadVaruint64(err)
		return true
	case PrimitiveUintDispatchId:
		*hasPtr = true
		*(*uint)(valuePtr) = uint(buf.ReadVaruint64(err))
		return true
	case PrimitiveTaggedInt64DispatchId:
		*hasPtr = true
		*(*int64)(valuePtr) = buf.ReadTaggedInt64(err)
		return true
	case PrimitiveTaggedUint64DispatchId:
		*hasPtr = true
		*(*uint64)(valuePtr) = buf.ReadTaggedUint64(err)
		return true
	case PrimitiveFloat32DispatchId:
		*hasPtr = true
		*(*float32)(valuePtr) = buf.ReadFloat32(err)
		return true
	case PrimitiveFloat64DispatchId:
		*hasPtr = true
		*(*float64)(valuePtr) = buf.ReadFloat64(err)
		return true
	default:
		return false
	}
}

// readFieldsInOrder reads fields in the order they appear in s.fields (TypeDef order)
// This is used in compatible mode where Java writes fields in TypeDef order
// Precondition: value.CanAddr() must be true (checked by caller)
func (s *structSerializer) readFieldsInOrder(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	ptr := unsafe.Pointer(value.UnsafeAddr())
	err := ctx.Err()
	readField := func(field *FieldInfo) {
		if field.Meta.FieldIndex < 0 {
			s.skipField(ctx, field)
			return
		}
		if field.Kind == FieldKindOptional {
			fieldValue := value.Field(field.Meta.FieldIndex)
			if field.Serializer != nil {
				field.Serializer.Read(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, fieldValue)
			} else {
				ctx.ReadValue(fieldValue, RefModeTracking, true)
			}
			return
		}

		// Fast path for fixed-size primitive types (no ref flag from remote schema)
		if isFixedSizePrimitive(field.DispatchId) {
			fieldPtr := unsafe.Add(ptr, field.Offset)
			optInfo := optionalInfo{}
			if field.Kind == FieldKindOptional {
				optInfo = field.Meta.OptionalInfo
			}
			switch field.DispatchId {
			case PrimitiveBoolDispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadBool(err))
			case PrimitiveInt8DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadInt8(err))
			case PrimitiveUint8DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, uint8(buf.ReadInt8(err)))
			case PrimitiveInt16DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadInt16(err))
			case PrimitiveUint16DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadUint16(err))
			case PrimitiveInt32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadInt32(err))
			case PrimitiveUint32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadUint32(err))
			case PrimitiveInt64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadInt64(err))
			case PrimitiveUint64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadUint64(err))
			case PrimitiveFloat32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadFloat32(err))
			case PrimitiveFloat64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadFloat64(err))
			}
			return
		}

		// Fast path for varint primitive types (no ref flag from remote schema)
		if isVarintPrimitive(field.DispatchId) && !fieldHasNonPrimitiveSerializer(field) {
			fieldPtr := unsafe.Add(ptr, field.Offset)
			optInfo := optionalInfo{}
			if field.Kind == FieldKindOptional {
				optInfo = field.Meta.OptionalInfo
			}
			switch field.DispatchId {
			case PrimitiveVarint32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVarint32(err))
			case PrimitiveVarint64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVarint64(err))
			case PrimitiveVarUint32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVaruint32(err))
			case PrimitiveVarUint64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVaruint64(err))
			case PrimitiveTaggedInt64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadTaggedInt64(err))
			case PrimitiveTaggedUint64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadTaggedUint64(err))
			case PrimitiveIntDispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, int(buf.ReadVarint64(err)))
			case PrimitiveUintDispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, uint(buf.ReadVaruint64(err)))
			}
			return
		}

		fieldPtr := unsafe.Add(ptr, field.Offset)
		optInfo := optionalInfo{}
		if field.Kind == FieldKindOptional {
			optInfo = field.Meta.OptionalInfo
		}

		// Handle nullable fixed-size primitives (read ref flag + fixed bytes)
		// These have Nullable=true but use fixed encoding, not varint
		if isNullableFixedSizePrimitive(field.DispatchId) {
			refFlag := buf.ReadInt8(err)
			if refFlag == NullFlag {
				clearFieldValue(field.Kind, fieldPtr, optInfo)
				return
			}
			// Read fixed-size value based on dispatch ID
			switch field.DispatchId {
			case NullableBoolDispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadBool(err))
			case NullableInt8DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadInt8(err))
			case NullableUint8DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, uint8(buf.ReadInt8(err)))
			case NullableInt16DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadInt16(err))
			case NullableUint16DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadUint16(err))
			case NullableInt32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadInt32(err))
			case NullableUint32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadUint32(err))
			case NullableInt64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadInt64(err))
			case NullableUint64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadUint64(err))
			case NullableFloat32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadFloat32(err))
			case NullableFloat64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadFloat64(err))
			}
			return
		}

		// Handle nullable varint primitives (read ref flag + varint)
		if isNullableVarintPrimitive(field.DispatchId) {
			refFlag := buf.ReadInt8(err)
			if refFlag == NullFlag {
				clearFieldValue(field.Kind, fieldPtr, optInfo)
				return
			}
			// Read varint value based on dispatch ID
			switch field.DispatchId {
			case NullableVarint32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVarint32(err))
			case NullableVarint64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVarint64(err))
			case NullableVarUint32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVaruint32(err))
			case NullableVarUint64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVaruint64(err))
			case NullableTaggedInt64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadTaggedInt64(err))
			case NullableTaggedUint64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadTaggedUint64(err))
			case NullableIntDispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, int(buf.ReadVarint64(err)))
			case NullableUintDispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, uint(buf.ReadVaruint64(err)))
			}
			return
		}
		if isEnumField(field) {
			readEnumFieldUnsafe(ctx, field, fieldPtr)
			return
		}

		// Slow path for non-primitives (all need ref flag per xlang spec)
		fieldValue := value.Field(field.Meta.FieldIndex)
		if field.Serializer != nil {
			// Use pre-computed RefMode and WriteType from field initialization
			field.Serializer.Read(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, fieldValue)
		} else {
			ctx.ReadValue(fieldValue, RefModeTracking, true)
		}
	}

	for i := range s.fields {
		field := &s.fields[i]
		readField(field)
		if ctx.HasError() {
			return
		}
	}
}

// skipField skips a field that doesn't exist or is incompatible
// Uses context error state for deferred error checking.
func (s *structSerializer) skipField(ctx *ReadContext, field *FieldInfo) {
	if field.Meta.FieldDef.name != "" {
		if DebugOutputEnabled() {
			fmt.Printf("[Go][fory-debug] skipField name=%s typeId=%d fieldType=%s\n",
				field.Meta.FieldDef.name,
				field.Meta.FieldDef.fieldType.TypeId(),
				fieldTypeToString(field.Meta.FieldDef.fieldType))
		}
		fieldDefIsStructType := isStructFieldType(field.Meta.FieldDef.fieldType)
		// Use FieldDef's trackingRef and nullable to determine if ref flag was written by Java
		// Java writes ref flag based on its FieldDef, not Go's field type
		readRefFlag := field.Meta.FieldDef.trackingRef || field.Meta.FieldDef.nullable
		SkipFieldValueWithTypeFlag(ctx, field.Meta.FieldDef, readRefFlag, ctx.Compatible() && fieldDefIsStructType)
		return
	}
	// No FieldDef available, read into temp value
	tempValue := reflect.New(field.Meta.Type).Elem()
	if field.Serializer != nil {
		readType := ctx.Compatible() && isStructField(field.Meta.Type)
		refMode := RefModeNone
		if field.Meta.Nullable {
			refMode = RefModeTracking
		}
		field.Serializer.Read(ctx, refMode, readType, false, tempValue)
	} else {
		ctx.ReadValue(tempValue, RefModeTracking, true)
	}
}

// writeEnumField writes an enum field respecting the field's RefMode.
// Java writes enum ordinals as unsigned Varuint32Small7, not signed zigzag.
// RefMode determines whether null flag is written, regardless of whether the local type is a pointer.
// This is important for compatible mode where remote TypeDef's nullable flag controls the wire format.
func writeEnumField(ctx *WriteContext, field *FieldInfo, fieldValue reflect.Value) {
	buf := ctx.Buffer()
	isPointer := field.Kind == FieldKindPointer

	// Write null flag based on RefMode only (not based on whether local type is pointer)
	if field.RefMode != RefModeNone {
		if isPointer && fieldValue.IsNil() {
			buf.WriteInt8(NullFlag)
			return
		}
		buf.WriteInt8(NotNullValueFlag)
	}

	// Get the actual value to serialize
	targetValue := fieldValue
	if isPointer {
		if fieldValue.IsNil() {
			// RefModeNone but nil pointer - this is a protocol error in schema-consistent mode
			// Write zero value as fallback
			targetValue = reflect.Zero(field.Meta.Type.Elem())
		} else {
			targetValue = fieldValue.Elem()
		}
	}

	// For pointer enum fields, the serializer is ptrToValueSerializer wrapping enumSerializer.
	// We need to call the inner enumSerializer directly with the dereferenced value.
	if ptrSer, ok := field.Serializer.(*ptrToValueSerializer); ok {
		ptrSer.valueSerializer.WriteData(ctx, targetValue)
	} else {
		field.Serializer.WriteData(ctx, targetValue)
	}
}

func setEnumValue(ctx *ReadContext, ptr unsafe.Pointer, kind reflect.Kind, ordinal uint32) bool {
	switch kind {
	case reflect.Int:
		*(*int)(ptr) = int(ordinal)
	case reflect.Int8:
		*(*int8)(ptr) = int8(ordinal)
	case reflect.Int16:
		*(*int16)(ptr) = int16(ordinal)
	case reflect.Int32:
		*(*int32)(ptr) = int32(ordinal)
	case reflect.Int64:
		*(*int64)(ptr) = int64(ordinal)
	case reflect.Uint:
		*(*uint)(ptr) = uint(ordinal)
	case reflect.Uint8:
		*(*uint8)(ptr) = uint8(ordinal)
	case reflect.Uint16:
		*(*uint16)(ptr) = uint16(ordinal)
	case reflect.Uint32:
		*(*uint32)(ptr) = ordinal
	case reflect.Uint64:
		*(*uint64)(ptr) = uint64(ordinal)
	default:
		ctx.SetError(DeserializationErrorf("enum serializer: unsupported kind %v", kind))
		return false
	}
	return true
}

// readEnumFieldUnsafe reads an enum field respecting the field's RefMode.
// RefMode determines whether null flag is read, regardless of whether the local type is a pointer.
// This is important for compatible mode where remote TypeDef's nullable flag controls the wire format.
// Uses context error state for deferred error checking.
func readEnumFieldUnsafe(ctx *ReadContext, field *FieldInfo, fieldPtr unsafe.Pointer) {
	buf := ctx.Buffer()
	isPointer := field.Kind == FieldKindPointer

	// Read null flag based on RefMode only (not based on whether local type is pointer)
	if field.RefMode != RefModeNone {
		nullFlag := buf.ReadInt8(ctx.Err())
		if nullFlag == NullFlag {
			if isPointer {
				*(*unsafe.Pointer)(fieldPtr) = nil
			} else {
				setEnumValue(ctx, fieldPtr, field.Meta.Type.Kind(), 0)
			}
			return
		}
	}

	ordinal := buf.ReadVaruint32Small7(ctx.Err())
	if ctx.HasError() {
		return
	}

	if isPointer {
		elemType := field.Meta.Type.Elem()
		newVal := reflect.New(elemType)
		elemPtr := unsafe.Pointer(newVal.Pointer())
		if !setEnumValue(ctx, elemPtr, elemType.Kind(), ordinal) {
			return
		}
		*(*unsafe.Pointer)(fieldPtr) = elemPtr
		return
	}

	setEnumValue(ctx, fieldPtr, field.Meta.Type.Kind(), ordinal)
}

// skipStructSerializer is a serializer that skips unknown struct data
// It reads and discards field data based on fieldDefs from remote TypeDef
type skipStructSerializer struct {
	fieldDefs []FieldDef
}

func (s *skipStructSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	ctx.SetError(SerializationError("skipStructSerializer does not support WriteData - unknown struct type"))
}

func (s *skipStructSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	ctx.SetError(SerializationError("skipStructSerializer does not support Write - unknown struct type"))
}

func (s *skipStructSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	// Skip all fields based on fieldDefs from remote TypeDef
	for _, fieldDef := range s.fieldDefs {
		isStructType := isStructFieldType(fieldDef.fieldType)
		// Use trackingRef from FieldDef for ref flag decision
		SkipFieldValueWithTypeFlag(ctx, fieldDef, fieldDef.trackingRef, ctx.Compatible() && isStructType)
		if ctx.HasError() {
			return
		}
	}
}

func (s *skipStructSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	switch refMode {
	case RefModeTracking:
		refID, refErr := ctx.RefResolver().TryPreserveRefId(buf)
		if refErr != nil {
			ctx.SetError(FromError(refErr))
			return
		}
		if refID < int32(NotNullValueFlag) {
			// Reference found, nothing to skip
			return
		}
	case RefModeNullOnly:
		flag := buf.ReadInt8(ctxErr)
		if flag == NullFlag {
			return
		}
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s *skipStructSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	// typeInfo is already read, don't read it again - just skip data
	s.Read(ctx, refMode, false, false, value)
}
