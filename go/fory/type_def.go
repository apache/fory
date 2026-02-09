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
	"bytes"
	"compress/zlib"
	"fmt"
	"io"
	"reflect"
	"strings"

	"github.com/apache/fory/go/fory/meta"
)

const (
	META_SIZE_MASK       = 0xFF
	COMPRESS_META_FLAG   = 0b1 << 9
	HAS_FIELDS_META_FLAG = 0b1 << 8
	NUM_HASH_BITS        = 50
)

/*
TypeDef represents a transportable value object containing type information and field definitions.
typeDef are layout as following:
  - first 8 bytes: global header (50 bits hash + 1 bit compress flag + write fields meta + 8 bits meta size)
  - next 1 byte: meta header (2 bits reserved + 1 bit register by name flag + 5 bits num fields)
  - next variable bytes: type id (varint) or ns name + type name
  - next variable bytes: field definitions (see below)
*/
type TypeDef struct {
	typeId uint32
	// User type ID is stored as unsigned uint32; 0xffffffff means unset.
	userTypeId     uint32
	nsName         *MetaStringBytes
	typeName       *MetaStringBytes
	compressed     bool
	registerByName bool
	fieldDefs      []FieldDef
	encoded        []byte
	type_          reflect.Type
	cachedTypeInfo *TypeInfo
}

func NewTypeDef(typeId uint32, userTypeId uint32, nsName, typeName *MetaStringBytes, registerByName, compressed bool, fieldDefs []FieldDef) *TypeDef {
	return &TypeDef{
		typeId:         typeId,
		userTypeId:     userTypeId,
		nsName:         nsName,
		typeName:       typeName,
		compressed:     compressed,
		registerByName: registerByName,
		fieldDefs:      fieldDefs,
		encoded:        nil,
	}
}

// String returns a string representation of TypeDef for debugging
func (td *TypeDef) String() string {
	var nsStr, typeStr string
	if td.nsName != nil {
		// Try to decode the namespace; if it fails, show raw data
		decoder := meta.NewDecoder('.', '_')
		decoded, err := decoder.Decode(td.nsName.Data, td.nsName.Encoding)
		if err == nil {
			nsStr = decoded
		} else {
			nsStr = fmt.Sprintf("data=%v,enc=%v", td.nsName.Data, td.nsName.Encoding)
		}
	}
	if td.typeName != nil {
		// Try to decode the typename; if it fails, show raw data
		decoder := meta.NewDecoder('.', '_')
		decoded, err := decoder.Decode(td.typeName.Data, td.typeName.Encoding)
		if err == nil {
			typeStr = decoded
		} else {
			typeStr = fmt.Sprintf("data=%v,enc=%v", td.typeName.Data, td.typeName.Encoding)
		}
	}
	fieldStrs := make([]string, len(td.fieldDefs))
	for i, fd := range td.fieldDefs {
		fieldStrs[i] = fd.String()
	}
	return fmt.Sprintf("TypeDef{typeId=%d, userTypeId=%d, ns=%s, type=%s, registerByName=%v, compressed=%v, fields=[%s]}",
		td.typeId, td.userTypeId, nsStr, typeStr, td.registerByName, td.compressed, strings.Join(fieldStrs, ", "))
}

// ComputeDiff computes the diff between this (decoded/remote) TypeDef and a local TypeDef.
// Returns a string describing the differences, or empty string if identical.
func (td *TypeDef) ComputeDiff(localDef *TypeDef) string {
	if localDef == nil {
		return "Local TypeDef is nil (type not registered locally)"
	}

	var diff strings.Builder

	// Build field maps for comparison
	remoteFields := make(map[string]FieldDef)
	for _, fd := range td.fieldDefs {
		remoteFields[fieldKey(fd)] = fd
	}
	localFields := make(map[string]FieldDef)
	for _, fd := range localDef.fieldDefs {
		localFields[fieldKey(fd)] = fd
	}

	// Find fields only in remote
	for fieldKey, fd := range remoteFields {
		if _, exists := localFields[fieldKey]; !exists {
			diff.WriteString(fmt.Sprintf("  field '%s': only in remote, type=%s, nullable=%v\n",
				fieldLabel(fd), fieldTypeToString(fd.fieldType), fd.nullable))
		}
	}

	// Find fields only in local
	for fieldKey, fd := range localFields {
		if _, exists := remoteFields[fieldKey]; !exists {
			diff.WriteString(fmt.Sprintf("  field '%s': only in local, type=%s, nullable=%v\n",
				fieldLabel(fd), fieldTypeToString(fd.fieldType), fd.nullable))
		}
	}

	// Compare common fields
	for fieldKey, remoteField := range remoteFields {
		if localField, exists := localFields[fieldKey]; exists {
			// Compare field types
			remoteTypeStr := fieldTypeToString(remoteField.fieldType)
			localTypeStr := fieldTypeToString(localField.fieldType)
			if remoteTypeStr != localTypeStr {
				diff.WriteString(fmt.Sprintf("  field '%s': type mismatch, remote=%s, local=%s\n",
					fieldLabel(remoteField), remoteTypeStr, localTypeStr))
			}
			// Compare nullable
			if remoteField.nullable != localField.nullable {
				diff.WriteString(fmt.Sprintf("  field '%s': nullable mismatch, remote=%v, local=%v\n",
					fieldLabel(remoteField), remoteField.nullable, localField.nullable))
			}
		}
	}

	// Compare field order
	if len(td.fieldDefs) == len(localDef.fieldDefs) {
		orderDifferent := false
		for i := range td.fieldDefs {
			if fieldKey(td.fieldDefs[i]) != fieldKey(localDef.fieldDefs[i]) {
				orderDifferent = true
				break
			}
		}
		if orderDifferent {
			diff.WriteString("  field order differs:\n")
			diff.WriteString("    remote: [")
			for i, fd := range td.fieldDefs {
				if i > 0 {
					diff.WriteString(", ")
				}
				diff.WriteString(fieldLabel(fd))
			}
			diff.WriteString("]\n")
			diff.WriteString("    local:  [")
			for i, fd := range localDef.fieldDefs {
				if i > 0 {
					diff.WriteString(", ")
				}
				diff.WriteString(fieldLabel(fd))
			}
			diff.WriteString("]\n")
		}
	}

	return diff.String()
}

func fieldKey(fd FieldDef) string {
	if fd.tagID >= 0 {
		return fmt.Sprintf("id:%d", fd.tagID)
	}
	return "name:" + fd.name
}

func fieldLabel(fd FieldDef) string {
	if fd.tagID >= 0 {
		if fd.name != "" {
			return fmt.Sprintf("%s(id=%d)", fd.name, fd.tagID)
		}
		return fmt.Sprintf("id=%d", fd.tagID)
	}
	return fd.name
}

func (td *TypeDef) writeTypeDef(buffer *ByteBuffer, err *Error) {
	buffer.WriteBinary(td.encoded)
}

// buildTypeInfo constructs a TypeInfo from the TypeDef
func (td *TypeDef) buildTypeInfo() (TypeInfo, error) {
	return td.buildTypeInfoWithResolver(nil)
}

// buildTypeInfoWithResolver constructs a TypeInfo from the TypeDef, using registered serializer if available
func (td *TypeDef) buildTypeInfoWithResolver(resolver *TypeResolver) (TypeInfo, error) {
	type_ := td.type_

	var serializer Serializer
	// For extension types, use the registered serializer if available
	if type_ != nil && resolver != nil {
		if existingSerializer, ok := resolver.typeToSerializers[type_]; ok {
			// Only use registered serializer for extension types (not struct types)
			if _, isExt := existingSerializer.(*extensionSerializerAdapter); isExt {
				serializer = existingSerializer
			} else if ptrSer, isPtrSer := existingSerializer.(*ptrToValueSerializer); isPtrSer {
				if _, isExtInner := ptrSer.valueSerializer.(*extensionSerializerAdapter); isExtInner {
					serializer = existingSerializer
				}
			}
		}
	}
	// If no extension serializer, create struct serializer
	if serializer == nil {
		if type_ == nil {
			// Unknown struct type - use skipStructSerializer to skip data
			serializer = &skipStructSerializer{
				fieldDefs: td.fieldDefs,
			}
		} else {
			// Known struct type - use structSerializer with fieldDefs
			structSer := newStructSerializerFromTypeDef(type_, "", td.fieldDefs)
			structSer.userTypeID = td.userTypeId
			// Eagerly initialize the struct serializer with pre-computed field metadata
			if resolver != nil {
				if err := structSer.initialize(resolver); err != nil {
					return TypeInfo{}, err
				}
			}
			serializer = structSer
		}
	}

	info := TypeInfo{
		Type:         type_,
		TypeID:       td.typeId,
		UserTypeID:   td.userTypeId,
		Serializer:   serializer,
		PkgPathBytes: td.nsName,
		NameBytes:    td.typeName,
		IsDynamic:    type_ == nil, // Mark as dynamic if type is unknown
		TypeDef:      td,
	}
	return info, nil
}

func (td *TypeDef) getOrBuildTypeInfo(resolver *TypeResolver) (*TypeInfo, error) {
	if td.cachedTypeInfo != nil {
		return td.cachedTypeInfo, nil
	}
	info, err := td.buildTypeInfoWithResolver(resolver)
	if err != nil {
		return nil, err
	}
	td.cachedTypeInfo = &info
	return td.cachedTypeInfo, nil
}

func readTypeDef(fory *Fory, buffer *ByteBuffer, header int64, err *Error) *TypeDef {
	td, decodeErr := decodeTypeDef(fory, buffer, header)
	if decodeErr != nil {
		err.SetError(decodeErr)
		return nil
	}
	return td
}

func skipTypeDef(buffer *ByteBuffer, header int64, err *Error) {
	sz := int(header & META_SIZE_MASK)
	if sz == META_SIZE_MASK {
		sz += int(buffer.ReadVarUint32(err))
	}
	buffer.IncreaseReaderIndex(sz)
}

const BIG_NAME_THRESHOLD = 0b111111 // 6 bits for size when using 2 bits for encoding

// readPkgName reads package name from TypeDef (not the meta string format with dynamic IDs)
// Java format: 6 bits size | 2 bits encoding flags
// Package encodings: UTF_8=0, ALL_TO_LOWER_SPECIAL=1, LOWER_UPPER_DIGIT_SPECIAL=2
func readPkgName(buffer *ByteBuffer, namespaceDecoder *meta.Decoder, err *Error) (string, error) {
	header := int(buffer.ReadInt8(err)) & 0xff
	encodingFlags := header & 0b11 // 2 bits for encoding
	size := header >> 2            // 6 bits for size
	if size == BIG_NAME_THRESHOLD {
		size = int(buffer.ReadVarUint32Small7(err)) + BIG_NAME_THRESHOLD
	}

	var encoding meta.Encoding
	switch encodingFlags {
	case 0:
		encoding = meta.UTF_8
	case 1:
		encoding = meta.ALL_TO_LOWER_SPECIAL
	case 2:
		encoding = meta.LOWER_UPPER_DIGIT_SPECIAL
	default:
		return "", fmt.Errorf("invalid package encoding flags: %d", encodingFlags)
	}

	data := make([]byte, size)
	if _, err := buffer.Read(data); err != nil {
		return "", err
	}

	return namespaceDecoder.Decode(data, encoding)
}

// readTypeName reads type name from TypeDef (not the meta string format with dynamic IDs)
// Java format: 6 bits size | 2 bits encoding flags
// TypeName encodings: UTF_8=0, ALL_TO_LOWER_SPECIAL=1, LOWER_UPPER_DIGIT_SPECIAL=2, FIRST_TO_LOWER_SPECIAL=3
func readTypeName(buffer *ByteBuffer, typeNameDecoder *meta.Decoder, err *Error) (string, error) {
	header := int(buffer.ReadInt8(err)) & 0xff
	encodingFlags := header & 0b11 // 2 bits for encoding
	size := header >> 2            // 6 bits for size
	if size == BIG_NAME_THRESHOLD {
		size = int(buffer.ReadVarUint32Small7(err)) + BIG_NAME_THRESHOLD
	}

	var encoding meta.Encoding
	switch encodingFlags {
	case 0:
		encoding = meta.UTF_8
	case 1:
		encoding = meta.ALL_TO_LOWER_SPECIAL
	case 2:
		encoding = meta.LOWER_UPPER_DIGIT_SPECIAL
	case 3:
		encoding = meta.FIRST_TO_LOWER_SPECIAL
	default:
		return "", fmt.Errorf("invalid typename encoding flags: %d", encodingFlags)
	}

	data := make([]byte, size)
	if _, err := buffer.Read(data); err != nil {
		return "", err
	}

	return typeNameDecoder.Decode(data, encoding)
}

// buildTypeDef constructs a TypeDef from a value
func buildTypeDef(fory *Fory, value reflect.Value) (*TypeDef, error) {
	fieldDefs, err := buildFieldDefs(fory, value)
	if err != nil {
		return nil, fmt.Errorf("failed to extract field infos: %w", err)
	}

	infoPtr, err := fory.typeResolver.getTypeInfo(value, true)
	if err != nil {
		return nil, fmt.Errorf("failed to get type info for value %v: %w", value, err)
	}
	typeId := uint32(infoPtr.TypeID)
	registerByName := IsNamespacedType(TypeId(typeId))
	typeDef := NewTypeDef(typeId, infoPtr.UserTypeID, infoPtr.PkgPathBytes, infoPtr.NameBytes, registerByName, false, fieldDefs)

	// encoding the typeDef, and save the encoded bytes
	encoded, err := encodingTypeDef(fory.typeResolver, typeDef)
	if err != nil {
		return nil, fmt.Errorf("failed to encode class definition: %w", err)
	}

	typeDef.encoded = encoded
	if DebugOutputEnabled {
		fmt.Printf("[Go TypeDef BUILT] %s\n", typeDef.String())
	}
	return typeDef, nil
}

/*
FieldDef contains definition of a single field in a struct
field def layout as following:
  - first 1 byte: header (2 bits field name encoding + 4 bits size + nullability flag + ref tracking flag)
  - next variable bytes: FieldType info
  - next variable bytes: field name or tag id
*/
type FieldDef struct {
	name         string
	nameEncoding meta.Encoding
	nullable     bool
	trackingRef  bool
	fieldType    FieldType
	tagID        int // -1 = use field name, >=0 = use tag ID
}

// String returns a string representation of FieldDef for debugging
func (fd FieldDef) String() string {
	var fieldTypeStr string
	if fd.fieldType != nil {
		fieldTypeStr = fd.fieldType.String()
	} else {
		fieldTypeStr = "nil"
	}
	if fd.tagID >= 0 {
		return fmt.Sprintf("FieldDef{tagID=%d, nullable=%v, trackingRef=%v, fieldType=%s}",
			fd.tagID, fd.nullable, fd.trackingRef, fieldTypeStr)
	}
	return fmt.Sprintf("FieldDef{name=%s, nullable=%v, trackingRef=%v, fieldType=%s}",
		fd.name, fd.nullable, fd.trackingRef, fieldTypeStr)
}

// fieldTypeToString returns a detailed string representation of a FieldType
func fieldTypeToString(ft FieldType) string {
	if ft == nil {
		return "nil"
	}
	switch t := ft.(type) {
	case *CollectionFieldType:
		return fmt.Sprintf("CollectionFieldType{typeId=%d, elementType=%s}", t.TypeId(), fieldTypeToString(t.elementType))
	case *MapFieldType:
		return fmt.Sprintf("MapFieldType{typeId=%d, keyType=%s, valueType=%s}", t.TypeId(), fieldTypeToString(t.keyType), fieldTypeToString(t.valueType))
	case *SimpleFieldType:
		return fmt.Sprintf("SimpleFieldType{typeId=%d}", t.TypeId())
	case *DynamicFieldType:
		return fmt.Sprintf("DynamicFieldType{typeId=%d}", t.TypeId())
	default:
		return fmt.Sprintf("FieldType{typeId=%d}", ft.TypeId())
	}
}

// buildFieldDefs extracts field definitions from a struct value
func buildFieldDefs(fory *Fory, value reflect.Value) ([]FieldDef, error) {
	var fieldDefs []FieldDef

	type_ := value.Type()
	for i := 0; i < type_.NumField(); i++ {
		field := type_.Field(i)

		// Skip unexported fields
		if field.PkgPath != "" {
			continue
		}

		// Parse fory struct tag and check for ignore
		foryTag := parseForyTag(field)
		if foryTag.Ignore {
			continue // skip ignored fields
		}

		fieldValue := value.Field(i)
		fieldName := SnakeCase(field.Name)

		nameEncoding := fory.typeResolver.typeNameEncoder.ComputeEncodingWith(fieldName, fieldNameEncodings)

		fieldType := field.Type
		optionalInfo, isOptional := getOptionalInfo(fieldType)
		baseType := fieldType
		if isOptional {
			baseType = optionalInfo.valueType
		}

		ft, err := buildFieldType(fory, fieldValue)
		if err != nil {
			return nil, fmt.Errorf("failed to build field type for field %s: %w", fieldName, err)
		}

		// Apply encoding override from struct tags if set
		// This works for both direct types and pointer-wrapped types
		baseKind := baseType.Kind()
		// Handle pointer types - get the element kind
		if baseKind == reflect.Ptr {
			baseKind = baseType.Elem().Kind()
		}

		// Check if we need to override the TypeID based on compress/encoding tags
		var overrideTypeId TypeId = 0
		switch baseKind {
		case reflect.Uint32:
			if foryTag.CompressSet {
				if foryTag.Compress {
					overrideTypeId = VAR_UINT32
				} else {
					overrideTypeId = UINT32
				}
			}
		case reflect.Int32:
			if foryTag.CompressSet {
				if foryTag.Compress {
					overrideTypeId = VARINT32
				} else {
					overrideTypeId = INT32
				}
			}
		case reflect.Uint64:
			if foryTag.EncodingSet {
				switch foryTag.Encoding {
				case "fixed":
					overrideTypeId = UINT64
				case "varint":
					overrideTypeId = VAR_UINT64
				case "tagged":
					overrideTypeId = TAGGED_UINT64
				default:
					return nil, fmt.Errorf("field %s: invalid encoding value %q for uint64, must be 'fixed', 'varint', or 'tagged'", fieldName, foryTag.Encoding)
				}
			}
		case reflect.Int64:
			if foryTag.EncodingSet {
				switch foryTag.Encoding {
				case "fixed":
					overrideTypeId = INT64
				case "varint":
					overrideTypeId = VARINT64
				case "tagged":
					overrideTypeId = TAGGED_INT64
				default:
					return nil, fmt.Errorf("field %s: invalid encoding value %q for int64, must be 'fixed', 'varint', or 'tagged'", fieldName, foryTag.Encoding)
				}
			}
		}

		// Apply the override if one was determined
		if overrideTypeId != 0 {
			ft = NewSimpleFieldType(overrideTypeId)
		}
		if foryTag.TypeIDSet && foryTag.TypeIDValid {
			ft = NewSimpleFieldType(foryTag.TypeID)
		}

		// Determine nullable based on mode:
		// - In xlang mode: Per xlang spec, fields are NON-NULLABLE by default.
		//   Only pointer types are nullable by default.
		// - In native mode: Go's natural semantics apply - slice/map/interface can be nil,
		//   so they are nullable by default.
		// Can be overridden by explicit fory tag `fory:"nullable"`
		typeId := ft.TypeId()
		isEnumField := typeId == ENUM
		// Determine nullable based on mode
		// In xlang mode: only pointer types are nullable by default (per xlang spec)
		// In native mode: Go's natural semantics - all nil-able types are nullable
		// This ensures proper interoperability with Java/other languages in xlang mode.
		var nullableFlag bool
		if fory.config.IsXlang {
			// xlang mode: only pointer types are nullable by default per xlang spec
			// Slices and maps are NOT nullable - they serialize as empty when nil
			nullableFlag = isOptional || field.Type.Kind() == reflect.Ptr
		} else {
			// Native mode: Go's natural semantics - all nil-able types are nullable
			nullableFlag = isOptional || field.Type.Kind() == reflect.Ptr ||
				field.Type.Kind() == reflect.Slice ||
				field.Type.Kind() == reflect.Map ||
				field.Type.Kind() == reflect.Interface
		}
		// Override nullable flag if explicitly set in fory tag
		if foryTag.NullableSet && !isOptional {
			nullableFlag = foryTag.Nullable
		}
		// Primitives are never nullable, regardless of tag
		if isNonNullablePrimitiveKind(field.Type.Kind()) && !isEnumField {
			nullableFlag = false
		}

		// Calculate ref tracking - use tag override if explicitly set
		// In xlang mode, registered types (primitives, strings) don't use ref tracking
		// because they are value types, not reference types.
		trackingRef := fory.config.TrackRef
		if foryTag.RefSet {
			trackingRef = foryTag.Ref
		}
		if trackingRef && !NeedWriteRef(ft.TypeId()) {
			trackingRef = false
		}
		// Disable ref tracking for simple types (primitives, strings) in xlang mode.
		// Collection fields only write ref flags when explicitly tagged.
		if fory.config.IsXlang && trackingRef && isCollectionType(ft.TypeId()) && !foryTag.RefSet {
			trackingRef = false
		}

		fieldInfo := FieldDef{
			name:         fieldName,
			nameEncoding: nameEncoding,
			nullable:     nullableFlag,
			trackingRef:  trackingRef,
			fieldType:    ft,
			tagID:        foryTag.ID,
		}
		fieldDefs = append(fieldDefs, fieldInfo)
	}

	// Sort field definitions
	if len(fieldDefs) > 1 {
		// Extract serializers, names, typeIds, nullable info and tagIDs for sorting
		serializers := make([]Serializer, len(fieldDefs))
		fieldNames := make([]string, len(fieldDefs))
		typeIds := make([]TypeId, len(fieldDefs))
		nullables := make([]bool, len(fieldDefs))
		tagIDs := make([]int, len(fieldDefs))
		for i, fieldDef := range fieldDefs {
			serializer, err := getFieldTypeSerializer(fory, fieldDef.fieldType)
			if err != nil {
				// If we can't get serializer, use nil (will be handled by sortFields)
				serializers[i] = nil
			} else {
				serializers[i] = serializer
			}
			fieldNames[i] = fieldDef.name
			typeIds[i] = fieldDef.fieldType.TypeId()
			nullables[i] = fieldDef.nullable
			tagIDs[i] = fieldDef.tagID
		}

		// Use sortFields to match Java's field ordering
		// (primitives before boxed/nullable primitives, sorted by tag ID if available)
		_, sortedNames := sortFields(fory.typeResolver, fieldNames, serializers, typeIds, nullables, tagIDs)

		// Rebuild fieldInfos in the sorted order
		nameToFieldInfo := make(map[string]FieldDef)
		for _, fieldInfo := range fieldDefs {
			nameToFieldInfo[fieldInfo.name] = fieldInfo
		}

		sortedFieldInfos := make([]FieldDef, len(fieldDefs))
		for i, name := range sortedNames {
			sortedFieldInfos[i] = nameToFieldInfo[name]
		}

		fieldDefs = sortedFieldInfos
	}

	return fieldDefs, nil
}

// FieldType interface represents different field types, including object, collection, and map types
type FieldType interface {
	TypeId() TypeId
	UserTypeId() uint32
	String() string
	write(*ByteBuffer)
	writeWithFlags(*ByteBuffer, bool, bool)                  // writeWithFlags writes typeId with nullable/trackingRef flags
	getTypeInfo(*Fory) (TypeInfo, error)                     // some serializer need typeinfo as well
	getTypeInfoWithResolver(*TypeResolver) (TypeInfo, error) // version that uses typeResolver directly
}

// BaseFieldType provides common functionality for field types
type BaseFieldType struct {
	typeId     TypeId
	userTypeId uint32
}

func (b *BaseFieldType) TypeId() TypeId     { return b.typeId }
func (b *BaseFieldType) UserTypeId() uint32 { return b.userTypeId }
func (b *BaseFieldType) String() string {
	return fmt.Sprintf("FieldType{typeId=%d}", b.typeId)
}
func (b *BaseFieldType) write(buffer *ByteBuffer) {
	buffer.WriteUint8(uint8(b.typeId))
}

// writeWithFlags writes the typeId with nullable and trackingRef flags packed into the value.
// The format is: (typeId << 2) | (nullable ? 0b10 : 0) | (trackingRef ? 0b01 : 0)
func (b *BaseFieldType) writeWithFlags(buffer *ByteBuffer, nullable bool, trackingRef bool) {
	value := uint32(b.typeId) << 2
	if nullable {
		value |= 0b10
	}
	if trackingRef {
		value |= 0b01
	}
	buffer.WriteVarUint32Small7(value)
}

func getFieldTypeSerializer(fory *Fory, ft FieldType) (Serializer, error) {
	typeInfo, err := ft.getTypeInfo(fory)
	if err != nil {
		return nil, err
	}
	return typeInfo.Serializer, nil
}

func getFieldTypeSerializerWithResolver(resolver *TypeResolver, ft FieldType) (Serializer, error) {
	typeInfo, err := ft.getTypeInfoWithResolver(resolver)
	if err != nil {
		return nil, err
	}
	return typeInfo.Serializer, nil
}

func (b *BaseFieldType) getTypeInfo(fory *Fory) (TypeInfo, error) {
	if isUserDefinedType(b.typeId) {
		return TypeInfo{}, nil
	}
	info, err := fory.typeResolver.getTypeInfoById(uint32(b.typeId))
	if err != nil {
		return TypeInfo{}, err
	}
	if info == nil {
		return TypeInfo{}, nil
	}
	return *info, nil
}

func (b *BaseFieldType) getTypeInfoWithResolver(resolver *TypeResolver) (TypeInfo, error) {
	if isUserDefinedType(b.typeId) {
		return TypeInfo{}, nil
	}
	info, err := resolver.getTypeInfoById(uint32(b.typeId))
	if err != nil {
		return TypeInfo{}, err
	}
	if info == nil {
		return TypeInfo{}, nil
	}
	return *info, nil
}

// readFieldType reads field type info from the buffer according to the TypeId
// This is called for top-level field types where flags are NOT embedded in the type ID
func readFieldType(buffer *ByteBuffer, err *Error) (FieldType, error) {
	typeId := buffer.ReadUint8(err)
	internalTypeId := TypeId(typeId)

	switch internalTypeId {
	case LIST, SET:
		// For nested types, flags ARE embedded in the type ID
		elementType, etErr := readFieldTypeWithFlags(buffer, err)
		if etErr != nil {
			return nil, fmt.Errorf("failed to read element type: %w", etErr)
		}
		return NewCollectionFieldType(TypeId(typeId), elementType), nil
	case MAP:
		// For nested types, flags ARE embedded in the type ID
		keyType, ktErr := readFieldTypeWithFlags(buffer, err)
		if ktErr != nil {
			return nil, fmt.Errorf("failed to read key type: %w", ktErr)
		}
		valueType, vtErr := readFieldTypeWithFlags(buffer, err)
		if vtErr != nil {
			return nil, fmt.Errorf("failed to read value type: %w", vtErr)
		}
		return NewMapFieldType(TypeId(typeId), keyType, valueType), nil
	case UNKNOWN, EXT, STRUCT, NAMED_STRUCT, COMPATIBLE_STRUCT, NAMED_COMPATIBLE_STRUCT:
		ft := NewDynamicFieldType(TypeId(typeId))
		return ft, nil
	}
	ft := NewSimpleFieldType(TypeId(typeId))
	return ft, nil
}

// readFieldTypeWithFlags reads field type info where flags are embedded in the type ID
// Format: (typeId << 2) | (nullable ? 0b10 : 0) | (trackingRef ? 0b1 : 0)
func readFieldTypeWithFlags(buffer *ByteBuffer, err *Error) (FieldType, error) {
	rawValue := buffer.ReadVarUint32Small7(err)
	// Extract flags (lower 2 bits)
	// trackingRef := (rawValue & 0b1) != 0  // Not used currently
	// nullable := (rawValue & 0b10) != 0    // Not used currently
	typeId := rawValue >> 2
	internalTypeId := TypeId(typeId)

	switch internalTypeId {
	case LIST, SET:
		elementType, etErr := readFieldTypeWithFlags(buffer, err)
		if etErr != nil {
			return nil, fmt.Errorf("failed to read element type: %w", etErr)
		}
		return NewCollectionFieldType(TypeId(typeId), elementType), nil
	case MAP:
		keyType, ktErr := readFieldTypeWithFlags(buffer, err)
		if ktErr != nil {
			return nil, fmt.Errorf("failed to read key type: %w", ktErr)
		}
		valueType, vtErr := readFieldTypeWithFlags(buffer, err)
		if vtErr != nil {
			return nil, fmt.Errorf("failed to read value type: %w", vtErr)
		}
		return NewMapFieldType(TypeId(typeId), keyType, valueType), nil
	case UNKNOWN, EXT, STRUCT, NAMED_STRUCT, COMPATIBLE_STRUCT, NAMED_COMPATIBLE_STRUCT:
		ft := NewDynamicFieldType(TypeId(typeId))
		return ft, nil
	}
	ft := NewSimpleFieldType(TypeId(typeId))
	return ft, nil
}

// CollectionFieldType represents collection types like List, Slice
type CollectionFieldType struct {
	BaseFieldType
	elementType FieldType
}

func NewCollectionFieldType(typeId TypeId, elementType FieldType) *CollectionFieldType {
	return &CollectionFieldType{
		BaseFieldType: BaseFieldType{typeId: typeId, userTypeId: invalidUserTypeID},
		elementType:   elementType,
	}
}

func (c *CollectionFieldType) String() string {
	return fmt.Sprintf("CollectionFieldType{typeId=%d, elementType=%s}", c.typeId, c.elementType.String())
}

func (c *CollectionFieldType) write(buffer *ByteBuffer) {
	c.BaseFieldType.write(buffer)
	// Element types in collections are written with flags (nullable=true, trackingRef=false)
	// This matches Java's CollectionFieldType behavior
	c.elementType.writeWithFlags(buffer, true, false)
}

func (c *CollectionFieldType) writeWithFlags(buffer *ByteBuffer, nullable bool, trackingRef bool) {
	c.BaseFieldType.writeWithFlags(buffer, nullable, trackingRef)
	// Element types in collections are written with flags (nullable=true, trackingRef=false)
	c.elementType.writeWithFlags(buffer, true, false)
}

func (c *CollectionFieldType) getTypeInfo(f *Fory) (TypeInfo, error) {
	elemInfo, err := c.elementType.getTypeInfo(f)
	if err != nil {
		return TypeInfo{}, err
	}

	// For SET type, fory.Set[T] is defined as map[T]struct{} (empty struct value type)
	if c.typeId == SET {
		setType := reflect.MapOf(elemInfo.Type, reflect.TypeOf(struct{}{}))
		setSerializer, serErr := f.GetTypeResolver().GetSetSerializer(setType)
		if serErr != nil {
			return TypeInfo{}, serErr
		}
		return TypeInfo{Type: setType, Serializer: setSerializer}, nil
	}

	// For LIST type, use slice
	collectionType := reflect.SliceOf(elemInfo.Type)
	// Use TypeResolver helper to get the appropriate slice serializer
	sliceSerializer, serErr := f.GetTypeResolver().GetSliceSerializer(collectionType)
	if serErr != nil {
		return TypeInfo{}, serErr
	}
	return TypeInfo{Type: collectionType, Serializer: sliceSerializer}, nil
}

func (c *CollectionFieldType) getTypeInfoWithResolver(resolver *TypeResolver) (TypeInfo, error) {
	elemInfo, err := c.elementType.getTypeInfoWithResolver(resolver)
	if err != nil {
		return TypeInfo{}, err
	}

	// For SET type, fory.Set[T] is defined as map[T]struct{} (empty struct value type)
	if c.typeId == SET {
		setType := reflect.MapOf(elemInfo.Type, reflect.TypeOf(struct{}{}))
		setSerializer, serErr := resolver.GetSetSerializer(setType)
		if serErr != nil {
			return TypeInfo{}, serErr
		}
		return TypeInfo{Type: setType, Serializer: setSerializer}, nil
	}

	// For LIST type, use slice
	collectionType := reflect.SliceOf(elemInfo.Type)
	// Use TypeResolver helper to get the appropriate slice serializer
	sliceSerializer, serErr := resolver.GetSliceSerializer(collectionType)
	if serErr != nil {
		return TypeInfo{}, serErr
	}
	return TypeInfo{Type: collectionType, Serializer: sliceSerializer}, nil
}

// MapFieldType represents map types
type MapFieldType struct {
	BaseFieldType
	keyType   FieldType
	valueType FieldType
}

func NewMapFieldType(typeId TypeId, keyType, valueType FieldType) *MapFieldType {
	return &MapFieldType{
		BaseFieldType: BaseFieldType{typeId: typeId, userTypeId: invalidUserTypeID},
		keyType:       keyType,
		valueType:     valueType,
	}
}

func (m *MapFieldType) String() string {
	return fmt.Sprintf("MapFieldType{typeId=%d, keyType=%s, valueType=%s}", m.typeId, m.keyType.String(), m.valueType.String())
}

func (m *MapFieldType) write(buffer *ByteBuffer) {
	m.BaseFieldType.write(buffer)
	// Key and value types in maps are written with flags (nullable=true, trackingRef=false)
	// This matches Java's MapFieldType behavior
	m.keyType.writeWithFlags(buffer, true, false)
	m.valueType.writeWithFlags(buffer, true, false)
}

func (m *MapFieldType) writeWithFlags(buffer *ByteBuffer, nullable bool, trackingRef bool) {
	m.BaseFieldType.writeWithFlags(buffer, nullable, trackingRef)
	// Key and value types in maps are written with flags (nullable=true, trackingRef=false)
	m.keyType.writeWithFlags(buffer, true, false)
	m.valueType.writeWithFlags(buffer, true, false)
}

func (m *MapFieldType) getTypeInfo(f *Fory) (TypeInfo, error) {
	keyInfo, err := m.keyType.getTypeInfo(f)
	if err != nil {
		return TypeInfo{}, err
	}
	valueInfo, err := m.valueType.getTypeInfo(f)
	if err != nil {
		return TypeInfo{}, err
	}
	var mapType reflect.Type
	if keyInfo.Type != nil && valueInfo.Type != nil {
		mapType = reflect.MapOf(keyInfo.Type, valueInfo.Type)
	}
	keyReferencable := true
	if keyInfo.Type != nil {
		keyReferencable = isRefType(keyInfo.Type, f.config.IsXlang)
	}
	valueReferencable := true
	if valueInfo.Type != nil {
		valueReferencable = isRefType(valueInfo.Type, f.config.IsXlang)
	}
	mapSerializer := &mapSerializer{
		keySerializer:     keyInfo.Serializer,
		valueSerializer:   valueInfo.Serializer,
		keyReferencable:   keyReferencable,
		valueReferencable: valueReferencable,
		hasGenerics:       true,
	}
	return TypeInfo{Type: mapType, Serializer: mapSerializer}, nil
}

func (m *MapFieldType) getTypeInfoWithResolver(resolver *TypeResolver) (TypeInfo, error) {
	keyInfo, err := m.keyType.getTypeInfoWithResolver(resolver)
	if err != nil {
		return TypeInfo{}, err
	}
	valueInfo, err := m.valueType.getTypeInfoWithResolver(resolver)
	if err != nil {
		return TypeInfo{}, err
	}
	var mapType reflect.Type
	if keyInfo.Type != nil && valueInfo.Type != nil {
		mapType = reflect.MapOf(keyInfo.Type, valueInfo.Type)
	}
	keyReferencable := true
	if keyInfo.Type != nil {
		keyReferencable = isRefType(keyInfo.Type, resolver.isXlang)
	}
	valueReferencable := true
	if valueInfo.Type != nil {
		valueReferencable = isRefType(valueInfo.Type, resolver.isXlang)
	}
	mapSerializer := &mapSerializer{
		keySerializer:     keyInfo.Serializer,
		valueSerializer:   valueInfo.Serializer,
		keyReferencable:   keyReferencable,
		valueReferencable: valueReferencable,
		hasGenerics:       true,
	}
	return TypeInfo{Type: mapType, Serializer: mapSerializer}, nil
}

// SimpleFieldType represents object field types that aren't collection/map types
type SimpleFieldType struct {
	BaseFieldType
}

func NewSimpleFieldType(typeId TypeId) *SimpleFieldType {
	return &SimpleFieldType{
		BaseFieldType: BaseFieldType{
			typeId:     typeId,
			userTypeId: invalidUserTypeID,
		},
	}
}

func (s *SimpleFieldType) String() string {
	return fmt.Sprintf("SimpleFieldType{typeId=%d}", s.typeId)
}

// DynamicFieldType represents a field type that is determined at runtime, like EXT or STRUCT
type DynamicFieldType struct {
	BaseFieldType
}

func NewDynamicFieldType(typeId TypeId) *DynamicFieldType {
	return &DynamicFieldType{
		BaseFieldType: BaseFieldType{
			typeId:     typeId,
			userTypeId: invalidUserTypeID,
		},
	}
}

func (d *DynamicFieldType) String() string {
	return fmt.Sprintf("DynamicFieldType{typeId=%d}", d.typeId)
}

func (d *DynamicFieldType) getTypeInfo(fory *Fory) (TypeInfo, error) {
	// leave empty for runtime resolution, we not know the actual type here
	return TypeInfo{Type: reflect.TypeOf((*any)(nil)).Elem(), Serializer: nil}, nil
}

func (d *DynamicFieldType) getTypeInfoWithResolver(resolver *TypeResolver) (TypeInfo, error) {
	// Try to resolve the actual type from the resolver
	typeId := d.typeId

	// First try direct lookup
	info, err := resolver.getTypeInfoById(uint32(typeId))
	if err == nil && info != nil {
		return *info, nil
	}

	// Fallback to any for unknown types
	return TypeInfo{Type: reflect.TypeOf((*any)(nil)).Elem(), Serializer: nil}, nil
}

// buildFieldType builds field type from reflect.Type, handling collection, map recursively
func buildFieldType(fory *Fory, fieldValue reflect.Value) (FieldType, error) {
	fieldType := fieldValue.Type()
	if info, ok := getOptionalInfo(fieldType); ok {
		fieldType = info.valueType
		fieldValue = reflect.Zero(fieldType)
	}
	if isUnionType(fieldType) {
		return NewSimpleFieldType(UNION), nil
	}
	// Handle Interface type, we can't determine the actual type here, so leave it as dynamic type
	if fieldType.Kind() == reflect.Interface {
		return NewDynamicFieldType(UNKNOWN), nil
	}

	// Handle slice and array types BEFORE getTypeInfo to avoid anonymous type errors
	// For primitive element types, use primitive array type IDs (INT16_ARRAY, etc.)
	// For non-primitive elements, use collection format (LIST with element type)
	if fieldType.Kind() == reflect.Slice || fieldType.Kind() == reflect.Array {
		elemType := fieldType.Elem()

		// Check if element is a primitive type that maps to a primitive array type ID
		// Both slices and fixed-size arrays with primitive elements use primitive array format
		// This matches typeIdFromKind in field_info.go for consistent field sorting
		switch elemType.Kind() {
		case reflect.Bool:
			return NewSimpleFieldType(BOOL_ARRAY), nil
		case reflect.Int8:
			return NewSimpleFieldType(INT8_ARRAY), nil
		case reflect.Uint8:
			return NewSimpleFieldType(BINARY), nil
		case reflect.Int16:
			return NewSimpleFieldType(INT16_ARRAY), nil
		case reflect.Uint16:
			return NewSimpleFieldType(UINT16_ARRAY), nil
		case reflect.Int32:
			return NewSimpleFieldType(INT32_ARRAY), nil
		case reflect.Uint32:
			return NewSimpleFieldType(UINT32_ARRAY), nil
		case reflect.Int64, reflect.Int:
			return NewSimpleFieldType(INT64_ARRAY), nil
		case reflect.Uint64, reflect.Uint:
			return NewSimpleFieldType(UINT64_ARRAY), nil
		case reflect.Float32:
			return NewSimpleFieldType(FLOAT32_ARRAY), nil
		case reflect.Float64:
			return NewSimpleFieldType(FLOAT64_ARRAY), nil
		}

		// For non-primitive elements, use collection format (LIST with element type)
		elemValue := reflect.Zero(elemType)
		elementFieldType, err := buildFieldType(fory, elemValue)
		if err != nil {
			return nil, fmt.Errorf("failed to build element field type: %w", err)
		}

		return NewCollectionFieldType(LIST, elementFieldType), nil
	}

	// Handle map types BEFORE getTypeInfo to avoid anonymous type errors
	if fieldType.Kind() == reflect.Map {
		keyType := fieldType.Key()

		// fory.Set[T] is defined as map[T]struct{} (empty struct value type)
		if isSetReflectType(fieldType) {
			keyValue := reflect.Zero(keyType)
			keyFieldType, err := buildFieldType(fory, keyValue)
			if err != nil {
				return nil, fmt.Errorf("failed to build element field type for set: %w", err)
			}
			return NewCollectionFieldType(SET, keyFieldType), nil
		}

		// Regular map type
		valueType := fieldType.Elem()
		keyValue := reflect.Zero(keyType)
		valueValue := reflect.Zero(valueType)

		keyFieldType, err := buildFieldType(fory, keyValue)
		if err != nil {
			return nil, fmt.Errorf("failed to build key field type: %w", err)
		}

		valueFieldType, err := buildFieldType(fory, valueValue)
		if err != nil {
			return nil, fmt.Errorf("failed to build value field type: %w", err)
		}

		return NewMapFieldType(MAP, keyFieldType, valueFieldType), nil
	}

	// Now get type info for other types (primitives, structs, etc.)
	var typeId TypeId
	typeInfo, err := fory.typeResolver.getTypeInfo(fieldValue, true)
	if err != nil {
		return nil, err
	}
	typeId = TypeId(typeInfo.TypeID)
	if typeId == NAMED_ENUM {
		typeId = ENUM
	}
	if typeId == NAMED_UNION || typeId == TYPED_UNION {
		typeId = UNION
	}

	if isUserDefinedType(typeId) {
		switch typeId {
		case UNION, ENUM:
			ft := NewSimpleFieldType(typeId)
			return ft, nil
		}
		ft := NewDynamicFieldType(typeId)
		return ft, nil
	}

	ft := NewSimpleFieldType(typeId)
	return ft, nil
}

const (
	SmallNumFieldsThreshold = 31
	REGISTER_BY_NAME_FLAG   = 0b1 << 5
	FieldNameSizeThreshold  = 15
)

// Field name encoding flags (2 bits in header)
const (
	FieldNameEncodingUTF8              = 0 // UTF_8 encoding
	FieldNameEncodingAllToLowerSpecial = 1 // ALL_TO_LOWER_SPECIAL encoding
	FieldNameEncodingLowerUpperDigit   = 2 // LOWER_UPPER_DIGIT_SPECIAL encoding
	FieldNameEncodingTagID             = 3 // Use tag ID instead of field name
)

// Encoding `UTF_8/ALL_TO_LOWER_SPECIAL/LOWER_UPPER_DIGIT_SPECIAL` for fieldName
// Note: TAG_ID (0b11) is a special encoding that uses tag ID instead of field name
var fieldNameEncodings = []meta.Encoding{
	meta.UTF_8,
	meta.ALL_TO_LOWER_SPECIAL,
	meta.LOWER_UPPER_DIGIT_SPECIAL,
}

func getFieldNameEncodingIndex(encoding meta.Encoding) int {
	for i, enc := range fieldNameEncodings {
		if enc == encoding {
			return i
		}
	}
	return 0 // Default to UTF_8 if not found
}

/*
encodingTypeDef encodes a TypeDef into binary format according to the specification
typeDef are layout as following:
- first 8 bytes: global header (50 bits hash + 1 bit compress flag + write fields meta + 8 bits meta size)
- next 1 byte: meta header (2 bits reserved + 1 bit register by name flag + 5 bits num fields)
- next variable bytes: type id (varint) or ns name + type name
- next variable bytes: field defs (see below)
*/
// writeSimpleName writes namespace using simple format (for TypeDef)
// Format: 1 byte header (6 bits size | 2 bits encoding flags) + data bytes
// This matches Java's format in ClassDefEncoder.writeName()
func writeSimpleName(buffer *ByteBuffer, metaBytes *MetaStringBytes, encoder *meta.Encoder) error {
	if metaBytes == nil || len(metaBytes.Data) == 0 {
		// WriteData header for empty namespace
		buffer.WriteByte(0)
		return nil
	}

	data := metaBytes.Data
	encoding := metaBytes.Encoding

	// Get encoding flags (0-2) - Java uses 2 bits for package encoding:
	// 0=UTF_8, 1=ALL_TO_LOWER_SPECIAL, 2=LOWER_UPPER_DIGIT_SPECIAL
	var encodingFlags byte
	switch encoding {
	case meta.UTF_8:
		encodingFlags = 0
	case meta.ALL_TO_LOWER_SPECIAL:
		encodingFlags = 1
	case meta.LOWER_UPPER_DIGIT_SPECIAL:
		encodingFlags = 2
	default:
		return fmt.Errorf("unsupported namespace encoding: %v", encoding)
	}

	size := len(data)
	if size >= BIG_NAME_THRESHOLD {
		// Size doesn't fit in 6 bits, write BIG_NAME_THRESHOLD and then varuint
		header := byte((BIG_NAME_THRESHOLD << 2) | int(encodingFlags))
		buffer.WriteByte(header)
		buffer.WriteVarUint32Small7(uint32(size - BIG_NAME_THRESHOLD))
	} else {
		// Size fits in 6 bits (6 bits for size, 2 bits for encoding)
		header := byte((size << 2) | int(encodingFlags))
		buffer.WriteByte(header)
	}

	buffer.Write(data)
	return nil
}

// writeSimpleTypeName writes typename using simple format (for TypeDef)
// Format: 1 byte header (6 bits size | 2 bits encoding flags) + data bytes
// This matches Java's format in ClassDefEncoder.writeName()
func writeSimpleTypeName(buffer *ByteBuffer, metaBytes *MetaStringBytes, encoder *meta.Encoder) error {
	if metaBytes == nil || len(metaBytes.Data) == 0 {
		// WriteData header for empty typename (shouldn't happen)
		buffer.WriteByte(0)
		return nil
	}

	data := metaBytes.Data
	encoding := metaBytes.Encoding

	// Get encoding flags (0-3) - Java uses 2 bits for typename encoding:
	// 0=UTF_8, 1=ALL_TO_LOWER_SPECIAL, 2=LOWER_UPPER_DIGIT_SPECIAL, 3=FIRST_TO_LOWER_SPECIAL
	var encodingFlags byte
	switch encoding {
	case meta.UTF_8:
		encodingFlags = 0
	case meta.ALL_TO_LOWER_SPECIAL:
		encodingFlags = 1
	case meta.LOWER_UPPER_DIGIT_SPECIAL:
		encodingFlags = 2
	case meta.FIRST_TO_LOWER_SPECIAL:
		encodingFlags = 3
	default:
		return fmt.Errorf("unsupported typename encoding: %v", encoding)
	}

	size := len(data)
	if size >= BIG_NAME_THRESHOLD {
		// Size doesn't fit in 6 bits, write BIG_NAME_THRESHOLD and then varuint
		header := byte((BIG_NAME_THRESHOLD << 2) | int(encodingFlags))
		buffer.WriteByte(header)
		buffer.WriteVarUint32Small7(uint32(size - BIG_NAME_THRESHOLD))
	} else {
		// Size fits in 6 bits (6 bits for size, 2 bits for encoding)
		header := byte((size << 2) | int(encodingFlags))
		buffer.WriteByte(header)
	}

	buffer.Write(data)
	return nil
}

func encodingTypeDef(typeResolver *TypeResolver, typeDef *TypeDef) ([]byte, error) {
	buffer := NewByteBuffer(nil)

	if err := writeMetaHeader(buffer, typeDef); err != nil {
		return nil, fmt.Errorf("failed to write meta header: %w", err)
	}

	if typeDef.registerByName {
		// WriteData namespace and typename using simple format (NOT meta string format)
		// Simple format: 1 byte header (6 bits size | 2 bits encoding) + data bytes
		if err := writeSimpleName(buffer, typeDef.nsName, typeResolver.namespaceEncoder); err != nil {
			return nil, fmt.Errorf("failed to write namespace: %w", err)
		}
		if err := writeSimpleTypeName(buffer, typeDef.typeName, typeResolver.typeNameEncoder); err != nil {
			return nil, fmt.Errorf("failed to write typename: %w", err)
		}
	} else {
		buffer.WriteUint8(uint8(typeDef.typeId))
		if typeDef.userTypeId == invalidUserTypeID {
			return nil, fmt.Errorf("missing user type ID for typeID %d", typeDef.typeId)
		}
		buffer.WriteVarUint32(typeDef.userTypeId)
	}

	if err := writeFieldDefs(typeResolver, buffer, typeDef.fieldDefs); err != nil {
		return nil, fmt.Errorf("failed to write fields def: %w", err)
	}

	result, err := prependGlobalHeader(buffer, false, len(typeDef.fieldDefs) > 0)
	if err != nil {
		return nil, fmt.Errorf("failed to write global binary header: %w", err)
	}

	return result.GetByteSlice(0, result.WriterIndex()), nil
}

// prependGlobalHeader writes the 8-byte global header
func prependGlobalHeader(buffer *ByteBuffer, isCompressed bool, hasFieldsMeta bool) (*ByteBuffer, error) {
	var header uint64
	metaSize := buffer.WriterIndex()

	hashValue := Murmur3Sum64WithSeed(buffer.GetByteSlice(0, metaSize), 47)
	header |= hashValue << (64 - NUM_HASH_BITS)

	if hasFieldsMeta {
		header |= HAS_FIELDS_META_FLAG
	}

	if isCompressed {
		header |= COMPRESS_META_FLAG
	}

	if metaSize < META_SIZE_MASK {
		header |= uint64(metaSize) & META_SIZE_MASK
	} else {
		header |= META_SIZE_MASK // Set to max value, actual size will follow
	}

	result := NewByteBuffer(make([]byte, metaSize+8))
	result.WriteInt64(int64(header))

	if metaSize >= META_SIZE_MASK {
		result.WriteVarUint32(uint32(metaSize - META_SIZE_MASK))
	}
	result.WriteBinary(buffer.GetByteSlice(0, metaSize))

	return result, nil
}

// writeMetaHeader writes the 1-byte meta header
func writeMetaHeader(buffer *ByteBuffer, typeDef *TypeDef) error {
	// 2 bits reserved + 1 bit register by name flag + 5 bits num fields
	offset := buffer.writerIndex
	if err := buffer.WriteByte(0xFF); err != nil {
		return err
	}
	fieldInfos := typeDef.fieldDefs
	header := len(fieldInfos)
	if header > SmallNumFieldsThreshold {
		header = SmallNumFieldsThreshold
		buffer.WriteVarUint32(uint32(len(fieldInfos) - SmallNumFieldsThreshold))
	}
	if typeDef.registerByName {
		header |= REGISTER_BY_NAME_FLAG
	}

	buffer.PutUint8(offset, uint8(header))
	return nil
}

// writeFieldDefs writes field definitions according to the specification
// field def layout as following:
//   - first 1 byte: header (2 bits field name encoding + 4 bits size + nullability flag + ref tracking flag)
//   - next variable bytes: FieldType info
//   - next variable bytes: field name or tag id
func writeFieldDefs(typeResolver *TypeResolver, buffer *ByteBuffer, fieldDefs []FieldDef) error {
	for _, field := range fieldDefs {
		if err := writeFieldDef(typeResolver, buffer, field); err != nil {
			return fmt.Errorf("failed to write field def for field %s: %w", field.name, err)
		}
	}
	return nil
}

// writeFieldDef writes a single field's definition
func writeFieldDef(typeResolver *TypeResolver, buffer *ByteBuffer, field FieldDef) error {
	// WriteData field header
	// 2 bits field name encoding + 4 bits size + nullability flag + ref tracking flag
	offset := buffer.writerIndex
	if err := buffer.WriteByte(0xFF); err != nil {
		return err
	}
	var header uint8
	if field.trackingRef {
		header |= 0b1
	}
	if field.nullable {
		header |= 0b10
	}

	if field.tagID >= 0 {
		// Use TAG_ID encoding (encoding flag = 3)
		header |= FieldNameEncodingTagID << 6
		// For tag ID, we encode the tag ID value in the size bits (4 bits)
		// If tagID < 15, encode directly in header; otherwise use varint
		if field.tagID < FieldNameSizeThreshold {
			header |= uint8(field.tagID&0x0F) << 2
		} else {
			header |= 0x0F << 2 // Max value, actual tag ID will follow
		}
		buffer.PutUint8(offset, header)

		// Write extra varint for large tag IDs
		if field.tagID >= FieldNameSizeThreshold {
			buffer.WriteVarUint32(uint32(field.tagID - FieldNameSizeThreshold))
		}

		// Write field type
		field.fieldType.write(buffer)
	} else {
		// Use field name encoding
		encodingFlag := byte(getFieldNameEncodingIndex(field.nameEncoding))
		header |= encodingFlag << 6
		metaString, err := typeResolver.typeNameEncoder.EncodeWithEncoding(field.name, field.nameEncoding)
		if err != nil {
			return err
		}
		nameLen := len(metaString.GetEncodedBytes())
		if nameLen < FieldNameSizeThreshold {
			header |= uint8((nameLen-1)&0x0F) << 2 // 1-based encoding
		} else {
			header |= 0x0F << 2 // Max value, actual length will follow
			buffer.WriteVarUint32(uint32(nameLen - FieldNameSizeThreshold))
		}
		buffer.PutUint8(offset, header)

		// Write field type
		field.fieldType.write(buffer)

		// Write field name
		if _, err := buffer.Write(metaString.GetEncodedBytes()); err != nil {
			return err
		}
	}
	return nil
}

/*
decodeTypeDef decodes a TypeDef from the buffer
typeDef are layout as following:
  - first 8 bytes: global header (50 bits hash + 1 bit compress flag + write fields meta + 8 bits meta size)
  - next 1 byte: meta header (2 bits reserved + 1 bit register by name flag + 5 bits num fields)
  - next variable bytes: type id (varint) or ns name + type name
  - next variable bytes: field definitions (see below)
*/
func decodeTypeDef(fory *Fory, buffer *ByteBuffer, header int64) (*TypeDef, error) {
	// ReadData 8-byte global header
	var bufErr Error
	globalHeader := header
	hasFieldsMeta := (globalHeader & HAS_FIELDS_META_FLAG) != 0
	isCompressed := (globalHeader & COMPRESS_META_FLAG) != 0
	metaSizeBits := int(globalHeader & META_SIZE_MASK)
	metaSize := metaSizeBits
	extraMetaSize := 0
	if metaSizeBits == META_SIZE_MASK {
		extraMetaSize = int(buffer.ReadVarUint32(&bufErr))
		metaSize += extraMetaSize
	}

	// Store the encoded bytes for the TypeDef (including meta header and metadata)
	encodedMeta := buffer.ReadBinary(metaSize, &bufErr)
	if bufErr.HasError() {
		return nil, bufErr.TakeError()
	}
	decodedMeta := encodedMeta
	if isCompressed {
		decodedMetaBytes, err := decompressMeta(encodedMeta)
		if err != nil {
			return nil, err
		}
		decodedMeta = decodedMetaBytes
	}
	metaBuffer := NewByteBuffer(decodedMeta)
	var metaErr Error

	// ReadData 1-byte meta header
	metaHeaderByte := metaBuffer.ReadByte(&metaErr)
	// Extract field count from lower 5 bits
	fieldCount := int(metaHeaderByte & SmallNumFieldsThreshold)
	if fieldCount == SmallNumFieldsThreshold {
		fieldCount += int(metaBuffer.ReadVarUint32(&metaErr))
	}
	registeredByName := (metaHeaderByte & REGISTER_BY_NAME_FLAG) != 0

	// ReadData name or type ID according to the registerByName flag
	var typeId uint32
	userTypeId := invalidUserTypeID
	var nsBytes, nameBytes *MetaStringBytes
	var type_ reflect.Type
	if registeredByName {
		// ReadData namespace and type name for namespaced types
		// NOTE: TypeDefs use simple name format, not meta string format with dynamic IDs
		// Format: 1 byte header (6 bits size | 2 bits encoding flags) + data bytes
		// ReadData namespace
		nsHeader := int(metaBuffer.ReadInt8(&metaErr)) & 0xff
		nsEncodingFlags := nsHeader & 0b11 // 2 bits for encoding
		nsSize := nsHeader >> 2            // 6 bits for size
		if nsSize == BIG_NAME_THRESHOLD {
			nsSize = int(metaBuffer.ReadVarUint32Small7(&metaErr)) + BIG_NAME_THRESHOLD
		}

		// Java pkg encoding: 0=UTF_8, 1=ALL_TO_LOWER_SPECIAL, 2=LOWER_UPPER_DIGIT_SPECIAL
		var nsEncoding meta.Encoding
		switch nsEncodingFlags {
		case 0:
			nsEncoding = meta.UTF_8
		case 1:
			nsEncoding = meta.ALL_TO_LOWER_SPECIAL
		case 2:
			nsEncoding = meta.LOWER_UPPER_DIGIT_SPECIAL
		default:
			return nil, fmt.Errorf("invalid package encoding flags: %d", nsEncodingFlags)
		}
		nsData := make([]byte, nsSize)
		if _, err := metaBuffer.Read(nsData); err != nil {
			return nil, fmt.Errorf("failed to read namespace data: %w", err)
		}

		// ReadData typename
		// Format: 1 byte header (6 bits size | 2 bits encoding flags) + data bytes
		typeHeader := int(metaBuffer.ReadInt8(&metaErr)) & 0xff
		typeEncodingFlags := typeHeader & 0b11 // 2 bits for encoding
		typeSize := typeHeader >> 2            // 6 bits for size
		if typeSize == BIG_NAME_THRESHOLD {
			typeSize = int(metaBuffer.ReadVarUint32Small7(&metaErr)) + BIG_NAME_THRESHOLD
		}

		// Java typename encoding: 0=UTF_8, 1=ALL_TO_LOWER_SPECIAL, 2=LOWER_UPPER_DIGIT_SPECIAL, 3=FIRST_TO_LOWER_SPECIAL
		var typeEncoding meta.Encoding
		switch typeEncodingFlags {
		case 0:
			typeEncoding = meta.UTF_8
		case 1:
			typeEncoding = meta.ALL_TO_LOWER_SPECIAL
		case 2:
			typeEncoding = meta.LOWER_UPPER_DIGIT_SPECIAL
		case 3:
			typeEncoding = meta.FIRST_TO_LOWER_SPECIAL
		default:
			return nil, fmt.Errorf("invalid typename encoding flags: %d", typeEncodingFlags)
		}
		typeData := make([]byte, typeSize)
		if _, err := metaBuffer.Read(typeData); err != nil {
			return nil, fmt.Errorf("failed to read typename data: %w", err)
		}

		// Create MetaStringBytes directly from the read data
		// Compute hash for namespace
		nsHash := ComputeMetaStringHash(nsData, nsEncoding)
		nsBytes = &MetaStringBytes{
			Data:     nsData,
			Encoding: nsEncoding,
			Hashcode: nsHash,
		}

		// Compute hash for typename
		typeHash := ComputeMetaStringHash(typeData, typeEncoding)
		nameBytes = &MetaStringBytes{
			Data:     typeData,
			Encoding: typeEncoding,
			Hashcode: typeHash,
		}

		info, exists := fory.typeResolver.nsTypeToTypeInfo[nsTypeKey{nsBytes.Hashcode, nameBytes.Hashcode}]
		if !exists {
			// Try fallback: decode strings and look up by name
			ns, _ := fory.typeResolver.namespaceDecoder.Decode(nsBytes.Data, nsBytes.Encoding)
			typeName, _ := fory.typeResolver.typeNameDecoder.Decode(nameBytes.Data, nameBytes.Encoding)
			nameKey := [2]string{ns, typeName}

			if fallbackInfo, fallbackExists := fory.typeResolver.namedTypeToTypeInfo[nameKey]; fallbackExists {
				info = fallbackInfo
				exists = true
				fory.typeResolver.nsTypeToTypeInfo[nsTypeKey{nsBytes.Hashcode, nameBytes.Hashcode}] = info
			}
		}
		if exists {
			// TypeDef is always for value types, but nsTypeToTypeInfo may have pointer type
			// if pointer type was registered after value type. Normalize to value type.
			type_ = info.Type
			if type_.Kind() == reflect.Ptr {
				type_ = type_.Elem()
			}
			typeId = uint32(info.TypeID)
			userTypeId = info.UserTypeID
		} else {
			// Type not registered - use NAMED_STRUCT as default typeId
			// The type_ will remain nil and will be set from field definitions later
			typeId = uint32(NAMED_STRUCT)
			type_ = nil
		}
	} else {
		typeId = uint32(metaBuffer.ReadUint8(&metaErr))
		userTypeId = metaBuffer.ReadVarUint32(&metaErr)
		if info, exists := fory.typeResolver.userTypeIdToTypeInfo[userTypeId]; exists {
			type_ = info.Type
		} else if info, exists := fory.typeResolver.typeIDToTypeInfo[typeId]; exists {
			type_ = info.Type
		}
		if type_ == nil {
			// Type not registered - will be built from field definitions
			type_ = nil
		}
	}

	// ReadData fields information
	fieldInfos := make([]FieldDef, fieldCount)
	if hasFieldsMeta {
		for i := 0; i < fieldCount; i++ {
			fieldInfo, err := readFieldDef(fory.typeResolver, metaBuffer)
			if err != nil {
				return nil, fmt.Errorf("failed to read field def %d: %w", i, err)
			}
			fieldInfos[i] = fieldInfo
		}
	}

	encoded := buildTypeDefEncoded(globalHeader, metaSizeBits, extraMetaSize, encodedMeta)

	// Create TypeDef
	typeDef := NewTypeDef(typeId, userTypeId, nsBytes, nameBytes, registeredByName, isCompressed, fieldInfos)
	typeDef.encoded = encoded
	typeDef.type_ = type_

	if DebugOutputEnabled {
		fmt.Printf("[Go TypeDef DECODED] %s\n", typeDef.String())
		// Compute and print diff with local TypeDef
		if type_ != nil {
			localDef, err := fory.typeResolver.getTypeDef(type_, true)
			if err == nil && localDef != nil {
				diff := typeDef.ComputeDiff(localDef)
				typeName := type_.String()
				if diff != "" {
					fmt.Printf("[Go TypeDef DIFF] %s:\n%s", typeName, diff)
				} else {
					fmt.Printf("[Go TypeDef DIFF] %s: identical\n", typeName)
				}
			}
		}
	}
	return typeDef, nil
}

func buildTypeDefEncoded(header int64, metaSizeBits, extraMetaSize int, metaBytes []byte) []byte {
	capacity := 8 + len(metaBytes) + 5
	buffer := NewByteBuffer(make([]byte, 0, capacity))
	buffer.WriteInt64(header)
	if metaSizeBits == META_SIZE_MASK {
		buffer.WriteVarUint32(uint32(extraMetaSize))
	}
	buffer.WriteBinary(metaBytes)
	return buffer.Bytes()
}

func decompressMeta(encoded []byte) ([]byte, error) {
	reader, err := zlib.NewReader(bytes.NewReader(encoded))
	if err != nil {
		return nil, fmt.Errorf("failed to create meta decompressor: %w", err)
	}
	defer reader.Close()
	decoded, err := io.ReadAll(reader)
	if err != nil {
		return nil, fmt.Errorf("failed to decompress meta: %w", err)
	}
	return decoded, nil
}

/*
readFieldDef reads a single field's definition from the buffer
field def layout as following:
  - first 1 byte: header (2 bits field name encoding + 4 bits size + nullability flag + ref tracking flag)
  - next variable bytes: FieldType info
  - next variable bytes: field name or tag id
*/
func readFieldDef(typeResolver *TypeResolver, buffer *ByteBuffer) (FieldDef, error) {
	var bufErr Error
	// ReadData field header
	headerByte := buffer.ReadByte(&bufErr)
	if bufErr.HasError() {
		return FieldDef{}, fmt.Errorf("failed to read field header: %w", bufErr.CheckError())
	}

	// Resolve the header
	nameEncodingFlag := int((headerByte >> 6) & 0b11)
	sizeBits := int((headerByte >> 2) & 0x0F)
	refTracking := (headerByte & 0b1) != 0
	isNullable := (headerByte & 0b10) != 0

	// Check if using TAG_ID encoding
	if nameEncodingFlag == FieldNameEncodingTagID {
		// Read tag ID
		tagID := sizeBits
		if sizeBits == 0x0F {
			tagID = FieldNameSizeThreshold + int(buffer.ReadVarUint32(&bufErr))
		}

		// Read field type
		ft, err := readFieldType(buffer, &bufErr)
		if err != nil {
			return FieldDef{}, err
		}

		return FieldDef{
			name:         "", // No field name when using tag ID
			nameEncoding: meta.UTF_8,
			fieldType:    ft,
			nullable:     isNullable,
			trackingRef:  refTracking,
			tagID:        tagID,
		}, nil
	}

	// Use field name encoding
	nameEncoding := fieldNameEncodings[nameEncodingFlag]
	nameLen := sizeBits
	if nameLen == 0x0F {
		nameLen = FieldNameSizeThreshold + int(buffer.ReadVarUint32(&bufErr))
	} else {
		nameLen++ // Adjust for 1-based encoding
	}

	// Read field type
	ft, err := readFieldType(buffer, &bufErr)
	if err != nil {
		return FieldDef{}, err
	}

	// Read field name based on encoding
	nameBytes := buffer.ReadBinary(nameLen, &bufErr)
	fieldName, err := typeResolver.typeNameDecoder.Decode(nameBytes, nameEncoding)
	if err != nil {
		return FieldDef{}, fmt.Errorf("failed to decode field name: %w", err)
	}

	return FieldDef{
		name:         fieldName,
		nameEncoding: nameEncoding,
		fieldType:    ft,
		nullable:     isNullable,
		trackingRef:  refTracking,
		tagID:        TagIDUseFieldName, // -1 indicates using field name
	}, nil
}
