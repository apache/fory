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
	"strings"

	"github.com/apache/fory/go/fory/meta"
)

const (
	META_SIZE_MASK     = 0xFF
	COMPRESS_META_FLAG = 0b1 << 8
	RESERVED_META_BITS = 0b111 << 9
	NUM_HASH_BITS      = 52
)

/*
TypeDef represents a transportable value object containing type information and field definitions.
typeDef are layout as following:
  - first 8 bytes: global header (52 bits metadata hash + 3 bits reserved + 1 bit compress flag + 8 bits meta size)
  - next 1 byte: kind header
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
				fieldLabel(fd), fieldTypeToString(fd.typeSpec), fd.nullable))
		}
	}

	// Find fields only in local
	for fieldKey, fd := range localFields {
		if _, exists := remoteFields[fieldKey]; !exists {
			diff.WriteString(fmt.Sprintf("  field '%s': only in local, type=%s, nullable=%v\n",
				fieldLabel(fd), fieldTypeToString(fd.typeSpec), fd.nullable))
		}
	}

	// Compare common fields
	for fieldKey, remoteField := range remoteFields {
		if localField, exists := localFields[fieldKey]; exists {
			// Compare field types
			remoteTypeStr := fieldTypeToString(remoteField.typeSpec)
			localTypeStr := fieldTypeToString(localField.typeSpec)
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
	if !isStructTypeId(TypeId(td.typeId)) {
		if type_ != nil && resolver != nil {
			var err error
			serializer, err = resolver.getSerializerByType(type_, false)
			if err != nil {
				return TypeInfo{}, err
			}
		}
		if serializer == nil && resolver != nil {
			serializer = resolver.getSerializerByTypeID(td.typeId)
		}
		if serializer == nil {
			return TypeInfo{}, fmt.Errorf("no serializer registered for TypeDef kind %d", td.typeId)
		}
	} else {
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
	// Header-cache hits intentionally treat the current body as opaque bytes and skip by the size in
	// the current header. Parsed TypeDefs are published to the cache only after successful body parse
	// and 52-bit metadata-hash validation; cache hits must not reparse or rehash that body.
	sz := int(header & META_SIZE_MASK)
	if sz == META_SIZE_MASK {
		sz += int(buffer.ReadVarUint32(err))
	}
	buffer.Skip(sz, err)
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
		extra := buffer.ReadVarUint32Small7(err)
		if err.HasError() {
			return "", err.TakeError()
		}
		if uint64(extra) > uint64(MaxInt-BIG_NAME_THRESHOLD) {
			return "", fmt.Errorf("invalid TypeDef namespace length")
		}
		size = int(extra) + BIG_NAME_THRESHOLD
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

	if size > buffer.remaining() {
		return "", fmt.Errorf("TypeDef namespace length %d exceeds remaining metadata %d", size, buffer.remaining())
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
		extra := buffer.ReadVarUint32Small7(err)
		if err.HasError() {
			return "", err.TakeError()
		}
		if uint64(extra) > uint64(MaxInt-BIG_NAME_THRESHOLD) {
			return "", fmt.Errorf("invalid TypeDef typename length")
		}
		size = int(extra) + BIG_NAME_THRESHOLD
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

	if size > buffer.remaining() {
		return "", fmt.Errorf("TypeDef typename length %d exceeds remaining metadata %d", size, buffer.remaining())
	}
	data := make([]byte, size)
	if _, err := buffer.Read(data); err != nil {
		return "", err
	}

	return typeNameDecoder.Decode(data, encoding)
}

// buildTypeDef constructs a TypeDef from a value
func buildTypeDef(fory *Fory, value reflect.Value) (*TypeDef, error) {
	infoPtr, err := fory.typeResolver.getTypeInfo(value, true)
	if err != nil {
		return nil, fmt.Errorf("failed to get type info for value %v: %w", value, err)
	}
	typeId := uint32(infoPtr.TypeID)
	var fieldDefs []FieldDef
	if isStructTypeId(TypeId(typeId)) {
		fieldDefs, err = buildFieldDefs(fory, value)
		if err != nil {
			return nil, fmt.Errorf("failed to extract field infos: %w", err)
		}
	}
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
  - next variable bytes: TypeSpec info
  - next variable bytes: field name or tag id
*/
type FieldDef struct {
	name         string
	nameEncoding meta.Encoding
	nullable     bool
	trackRef     bool
	typeSpec     *TypeSpec
	tagID        int // -1 = use field name, >=0 = use tag ID
}

// String returns a string representation of FieldDef for debugging
func (fd FieldDef) String() string {
	var typeSpecStr string
	if fd.typeSpec != nil {
		typeSpecStr = fd.typeSpec.String()
	} else {
		typeSpecStr = "nil"
	}
	if fd.tagID >= 0 {
		return fmt.Sprintf("FieldDef{tagID=%d, nullable=%v, trackRef=%v, typeSpec=%s}",
			fd.tagID, fd.nullable, fd.trackRef, typeSpecStr)
	}
	return fmt.Sprintf("FieldDef{name=%s, nullable=%v, trackRef=%v, typeSpec=%s}",
		fd.name, fd.nullable, fd.trackRef, typeSpecStr)
}

func fieldTypeToString(spec *TypeSpec) string {
	if spec == nil {
		return "nil"
	}
	return spec.String()
}

// buildFieldDefs extracts field definitions from a struct value
func buildFieldDefs(fory *Fory, value reflect.Value) ([]FieldDef, error) {
	type fieldDefEntry struct {
		fieldDef   FieldDef
		serializer Serializer
	}

	var entries []fieldDefEntry

	type_ := value.Type()
	for i := 0; i < type_.NumField(); i++ {
		field := type_.Field(i)

		// Skip unexported fields
		if field.PkgPath != "" {
			continue
		}

		fieldSpec, err := parseFieldSpec(field, fory.config.IsXlang, fory.config.TrackRef)
		if err != nil {
			return nil, err
		}
		fieldSpec.Type = bindResolvedTypeSpec(fory.typeResolver, field.Type, fieldSpec.Type)
		if fieldSpec.Ignore {
			continue // skip ignored fields
		}

		nameEncoding := fory.typeResolver.typeNameEncoder.ComputeEncodingWith(fieldSpec.Name, fieldNameEncodings)
		fieldDef := FieldDef{
			name:         fieldSpec.Name,
			nameEncoding: nameEncoding,
			nullable:     fieldDeclaredNullable(fieldSpec),
			trackRef:     fieldDeclaredTrackRef(fieldSpec, fory.config.IsXlang, fory.config.TrackRef),
			typeSpec:     fieldSpec.Type.typeDefProjection(false),
			tagID:        fieldSpec.TagID,
		}
		serializer, serErr := serializerForTypeSpec(fory.typeResolver, field.Type, fieldSpec.Type)
		if serErr != nil {
			serializer = nil
		}
		entries = append(entries, fieldDefEntry{fieldDef: fieldDef, serializer: serializer})
	}

	fieldDefs := make([]FieldDef, len(entries))
	for i, entry := range entries {
		fieldDefs[i] = entry.fieldDef
	}

	// Sort field definitions
	if len(fieldDefs) > 1 {
		// Extract serializers, names, typeIds, nullable info and tagIDs for sorting
		serializers := make([]Serializer, len(fieldDefs))
		fieldNames := make([]string, len(fieldDefs))
		typeIds := make([]TypeId, len(fieldDefs))
		nullables := make([]bool, len(fieldDefs))
		tagIDs := make([]int, len(fieldDefs))
		for i, entry := range entries {
			serializers[i] = entry.serializer
			fieldNames[i] = entry.fieldDef.name
			typeIds[i] = entry.fieldDef.typeSpec.TypeId()
			nullables[i] = entry.fieldDef.nullable
			tagIDs[i] = entry.fieldDef.tagID
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

func getFieldTypeSerializer(fory *Fory, spec *TypeSpec) (Serializer, error) {
	typeInfo, err := spec.getTypeInfo(fory)
	if err != nil {
		return nil, err
	}
	return typeInfo.Serializer, nil
}

func getFieldTypeSerializerWithResolver(resolver *TypeResolver, spec *TypeSpec) (Serializer, error) {
	typeInfo, err := spec.getTypeInfoWithResolver(resolver)
	if err != nil {
		return nil, err
	}
	return typeInfo.Serializer, nil
}

// readFieldType reads recursive TypeSpec metadata from the buffer according to the TypeId.
// Top-level field types do not carry nullable/ref bits in the encoded type id.
func readFieldType(buffer *ByteBuffer, depth int, maxDepth int, err *Error) (*TypeSpec, error) {
	if depth > maxDepth {
		return nil, fmt.Errorf("schema type definition exceeds maximum nesting depth")
	}
	typeID := TypeId(buffer.ReadUint8(err))
	spec, specErr := newTypeSpecForTypeID(typeID)
	if specErr != nil {
		return nil, specErr
	}
	switch typeID {
	case LIST, SET:
		elementType, etErr := readFieldTypeWithFlags(buffer, depth+1, maxDepth, err)
		if etErr != nil {
			return nil, fmt.Errorf("failed to read element type: %w", etErr)
		}
		spec.Element = elementType
		spec.elementType = elementType
	case MAP:
		keyType, ktErr := readFieldTypeWithFlags(buffer, depth+1, maxDepth, err)
		if ktErr != nil {
			return nil, fmt.Errorf("failed to read key type: %w", ktErr)
		}
		valueType, vtErr := readFieldTypeWithFlags(buffer, depth+1, maxDepth, err)
		if vtErr != nil {
			return nil, fmt.Errorf("failed to read value type: %w", vtErr)
		}
		spec.Key = keyType
		spec.keyType = keyType
		spec.Value = valueType
		spec.valueType = valueType
	}
	return spec, nil
}

// readFieldTypeWithFlags reads field type info where flags are embedded in the type ID.
// Format: (type_id << 2) | (nullable << 1) | tracking_ref.
func readFieldTypeWithFlags(buffer *ByteBuffer, depth int, maxDepth int, err *Error) (*TypeSpec, error) {
	if depth > maxDepth {
		return nil, fmt.Errorf("schema type definition exceeds maximum nesting depth")
	}
	rawValue := buffer.ReadVarUint32Small7(err)
	typeID := TypeId(rawValue >> 2)
	spec, readErr := newTypeSpecForTypeID(typeID)
	if readErr != nil {
		return nil, readErr
	}
	spec.Nullable = (rawValue & 0b10) != 0
	spec.TrackRef = (rawValue & 0b1) != 0
	switch typeID {
	case LIST, SET:
		elementType, etErr := readFieldTypeWithFlags(buffer, depth+1, maxDepth, err)
		if etErr != nil {
			return nil, fmt.Errorf("failed to read element type: %w", etErr)
		}
		spec.Element = elementType
		spec.elementType = elementType
	case MAP:
		keyType, ktErr := readFieldTypeWithFlags(buffer, depth+1, maxDepth, err)
		if ktErr != nil {
			return nil, fmt.Errorf("failed to read key type: %w", ktErr)
		}
		valueType, vtErr := readFieldTypeWithFlags(buffer, depth+1, maxDepth, err)
		if vtErr != nil {
			return nil, fmt.Errorf("failed to read value type: %w", vtErr)
		}
		spec.Key = keyType
		spec.keyType = keyType
		spec.Value = valueType
		spec.valueType = valueType
	}
	return spec, nil
}

func newTypeSpecForTypeID(typeID TypeId) (*TypeSpec, error) {
	switch typeID {
	case LIST, SET:
		return NewCollectionTypeSpec(typeID, nil), nil
	case MAP:
		return NewMapTypeSpec(typeID, nil, nil), nil
	default:
		return NewSimpleTypeSpec(typeID), nil
	}
}

const (
	SmallNumFieldsThreshold = 31
	RegisterByNameFlag      = 0b0010_0000
	CompatibleTypeDefFlag   = 0b0100_0000
	StructTypeDefFlag       = 0b1000_0000
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
- first 8 bytes: global header (52 bits metadata hash + 3 bits reserved + 1 bit compress flag + 8 bits meta size)
- next 1 byte: kind header
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
		if typeDef.userTypeId == invalidUserTypeID {
			return nil, fmt.Errorf("missing user type ID for typeID %d", typeDef.typeId)
		}
		buffer.WriteVarUint32(typeDef.userTypeId)
	}

	if isStructTypeId(TypeId(typeDef.typeId)) {
		if err := writeFieldDefs(typeResolver, buffer, typeDef.fieldDefs); err != nil {
			return nil, fmt.Errorf("failed to write fields def: %w", err)
		}
	} else if len(typeDef.fieldDefs) != 0 {
		return nil, fmt.Errorf("non-struct TypeDef %d cannot carry field metadata", typeDef.typeId)
	}

	result, err := prependGlobalHeader(buffer, false)
	if err != nil {
		return nil, fmt.Errorf("failed to write global binary header: %w", err)
	}

	return result.GetByteSlice(0, result.WriterIndex()), nil
}

// prependGlobalHeader writes the 8-byte global header
func prependGlobalHeader(buffer *ByteBuffer, isCompressed bool) (*ByteBuffer, error) {
	metaSize := buffer.WriterIndex()
	headerLowBits := uint64(metaSize)
	if metaSize >= META_SIZE_MASK {
		headerLowBits = META_SIZE_MASK
	}
	if isCompressed {
		headerLowBits |= COMPRESS_META_FLAG
	}
	header := typeDefHeaderHash(buffer.GetByteSlice(0, metaSize), headerLowBits) | headerLowBits

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
	offset := buffer.writerIndex
	if err := buffer.WriteByte(0xFF); err != nil {
		return err
	}
	fieldInfos := typeDef.fieldDefs
	typeID := TypeId(typeDef.typeId)
	var header int
	if isStructTypeId(typeID) {
		fieldCount := len(fieldInfos)
		inlineFieldCount := fieldCount
		if inlineFieldCount > SmallNumFieldsThreshold {
			inlineFieldCount = SmallNumFieldsThreshold
		}
		header = StructTypeDefFlag | inlineFieldCount
		if typeID == COMPATIBLE_STRUCT || typeID == NAMED_COMPATIBLE_STRUCT {
			header |= CompatibleTypeDefFlag
		}
		if fieldCount >= SmallNumFieldsThreshold {
			buffer.WriteVarUint32(uint32(fieldCount - SmallNumFieldsThreshold))
		}
		if typeDef.registerByName {
			header |= RegisterByNameFlag
		}
	} else {
		if len(fieldInfos) != 0 {
			return fmt.Errorf("non-struct TypeDef %d cannot carry field metadata", typeDef.typeId)
		}
		kindCode, err := xlangNonStructKindCode(typeID)
		if err != nil {
			return err
		}
		header = kindCode
	}

	buffer.PutUint8(offset, uint8(header))
	return nil
}

func xlangNonStructKindCode(typeID TypeId) (int, error) {
	switch typeID {
	case ENUM:
		return 0, nil
	case NAMED_ENUM:
		return 1, nil
	case EXT:
		return 2, nil
	case NAMED_EXT:
		return 3, nil
	case TYPED_UNION:
		return 4, nil
	case NAMED_UNION:
		return 5, nil
	default:
		return 0, fmt.Errorf("unsupported TypeDef kind %d", typeID)
	}
}

func xlangNonStructTypeID(kindCode int) (TypeId, error) {
	switch kindCode {
	case 0:
		return ENUM, nil
	case 1:
		return NAMED_ENUM, nil
	case 2:
		return EXT, nil
	case 3:
		return NAMED_EXT, nil
	case 4:
		return TYPED_UNION, nil
	case 5:
		return NAMED_UNION, nil
	default:
		return UNKNOWN, fmt.Errorf("unsupported TypeDef kind code %d", kindCode)
	}
}

// writeFieldDefs writes field definitions according to the specification
// field def layout as following:
//   - first 1 byte: header (2 bits field name encoding + 4 bits size + nullability flag + ref tracking flag)
//   - next variable bytes: TypeSpec info
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
	if field.trackRef {
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
		field.typeSpec.write(buffer)
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
		field.typeSpec.write(buffer)

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
  - first 8 bytes: global header (52 bits metadata hash + 3 bits reserved + 1 bit compress flag + 8 bits meta size)
  - next 1 byte: kind header
  - next variable bytes: type id (varint) or ns name + type name
  - next variable bytes: field definitions (see below)
*/
func decodeTypeDef(fory *Fory, buffer *ByteBuffer, header int64) (*TypeDef, error) {
	// ReadData 8-byte global header
	var bufErr Error
	globalHeader := header
	if (globalHeader & RESERVED_META_BITS) != 0 {
		return nil, fmt.Errorf("invalid TypeDef global header")
	}
	isCompressed := (globalHeader & COMPRESS_META_FLAG) != 0
	if isCompressed {
		return nil, fmt.Errorf("compressed xlang TypeDef is not supported")
	}
	metaSizeBits := int(globalHeader & META_SIZE_MASK)
	metaSize := metaSizeBits
	extraMetaSize := 0
	if metaSizeBits == META_SIZE_MASK {
		extra := buffer.ReadVarUint32(&bufErr)
		if bufErr.HasError() {
			return nil, bufErr.TakeError()
		}
		if uint64(extra) > uint64(MaxInt-metaSize) {
			return nil, fmt.Errorf("invalid TypeDef metadata size")
		}
		extraMetaSize = int(extra)
		metaSize += extraMetaSize
	}
	// Store the encoded bytes for the TypeDef (including meta header and metadata)
	encodedMeta := buffer.ReadBinary(metaSize, &bufErr)
	if bufErr.HasError() {
		return nil, bufErr.TakeError()
	}
	metaBuffer := NewByteBuffer(encodedMeta)
	var metaErr Error

	// ReadData 1-byte meta header
	metaHeaderByte := metaBuffer.ReadByte(&metaErr)
	isStruct := (metaHeaderByte & StructTypeDefFlag) != 0
	fieldCount := 0
	registeredByName := false

	// ReadData name or type ID according to the registerByName flag
	var typeId uint32
	userTypeId := invalidUserTypeID
	var nsBytes, nameBytes *MetaStringBytes
	var type_ reflect.Type
	if isStruct {
		registeredByName = (metaHeaderByte & RegisterByNameFlag) != 0
		fieldCount = int(metaHeaderByte & SmallNumFieldsThreshold)
		if fieldCount == SmallNumFieldsThreshold {
			fieldCount += int(metaBuffer.ReadVarUint32(&metaErr))
		}
		if metaErr.HasError() {
			return nil, metaErr.TakeError()
		}
		if fieldCount > fory.config.MaxTypeFields || fieldCount > metaBuffer.remaining() {
			return nil, fmt.Errorf("field count exceeds maximum allowed limit or available buffer size")
		}
		if registeredByName {
			if (metaHeaderByte & CompatibleTypeDefFlag) != 0 {
				typeId = uint32(NAMED_COMPATIBLE_STRUCT)
			} else {
				typeId = uint32(NAMED_STRUCT)
			}
		} else if (metaHeaderByte & CompatibleTypeDefFlag) != 0 {
			typeId = uint32(COMPATIBLE_STRUCT)
		} else {
			typeId = uint32(STRUCT)
		}
	} else {
		if (metaHeaderByte & 0b0111_0000) != 0 {
			return nil, fmt.Errorf("invalid TypeDef kind header")
		}
		kindType, err := xlangNonStructTypeID(int(metaHeaderByte & 0b1111))
		if err != nil {
			return nil, err
		}
		typeId = uint32(kindType)
		registeredByName = IsNamespacedType(kindType)
	}
	if registeredByName {
		// ReadData namespace and type name for namespaced types
		// NOTE: TypeDefs use simple name format, not meta string format with dynamic IDs
		// Format: 1 byte header (6 bits size | 2 bits encoding flags) + data bytes
		// ReadData namespace
		nsHeader := int(metaBuffer.ReadInt8(&metaErr)) & 0xff
		nsEncodingFlags := nsHeader & 0b11 // 2 bits for encoding
		nsSize := nsHeader >> 2            // 6 bits for size
		if nsSize == BIG_NAME_THRESHOLD {
			extra := metaBuffer.ReadVarUint32Small7(&metaErr)
			if metaErr.HasError() {
				return nil, metaErr.TakeError()
			}
			if uint64(extra) > uint64(MaxInt-BIG_NAME_THRESHOLD) {
				return nil, fmt.Errorf("invalid TypeDef namespace length")
			}
			nsSize = int(extra) + BIG_NAME_THRESHOLD
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
		if nsSize > metaBuffer.remaining() {
			return nil, fmt.Errorf("TypeDef namespace length %d exceeds remaining metadata %d", nsSize, metaBuffer.remaining())
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
			extra := metaBuffer.ReadVarUint32Small7(&metaErr)
			if metaErr.HasError() {
				return nil, metaErr.TakeError()
			}
			if uint64(extra) > uint64(MaxInt-BIG_NAME_THRESHOLD) {
				return nil, fmt.Errorf("invalid TypeDef typename length")
			}
			typeSize = int(extra) + BIG_NAME_THRESHOLD
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
		if typeSize > metaBuffer.remaining() {
			return nil, fmt.Errorf("TypeDef typename length %d exceeds remaining metadata %d", typeSize, metaBuffer.remaining())
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
				if len(fory.typeResolver.nsTypeToTypeInfo) < maxCachedNamedTypeInfos {
					fory.typeResolver.nsTypeToTypeInfo[nsTypeKey{nsBytes.Hashcode, nameBytes.Hashcode}] = info
				}
			}
		}
		if exists {
			// TypeDef is always for value types, but nsTypeToTypeInfo may have pointer type
			// if pointer type was registered after value type. Normalize to value type.
			type_ = info.Type
			if type_.Kind() == reflect.Ptr {
				type_ = type_.Elem()
			}
			if uint32(info.TypeID) != typeId {
				return nil, fmt.Errorf("TypeDef kind does not match registered type metadata")
			}
			userTypeId = info.UserTypeID
		} else {
			type_ = nil
		}
	} else {
		userTypeId = metaBuffer.ReadVarUint32(&metaErr)
		if info, exists := fory.typeResolver.userTypeIdToTypeInfo[userTypeId]; exists {
			if uint32(info.TypeID) != typeId {
				return nil, fmt.Errorf("TypeDef kind does not match registered type metadata")
			}
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
	for i := 0; i < fieldCount; i++ {
		fieldInfo, err := readFieldDef(fory.typeResolver, metaBuffer)
		if err != nil {
			return nil, fmt.Errorf("failed to read field def %d: %w", i, err)
		}
		fieldInfos[i] = fieldInfo
	}
	if !isStruct && len(fieldInfos) != 0 {
		return nil, fmt.Errorf("non-struct TypeDef cannot carry field metadata")
	}
	if metaErr.HasError() {
		return nil, metaErr.TakeError()
	}
	if remaining := metaBuffer.remaining(); remaining != 0 {
		return nil, fmt.Errorf("TypeDef metadata body has %d trailing bytes", remaining)
	}
	if err := validateParsedTypeDefHash(globalHeader, metaSizeBits, extraMetaSize, encodedMeta); err != nil {
		return nil, err
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

func typeDefHeaderHash(data []byte, headerLowBits uint64) uint64 {
	hashInput := make([]byte, len(data)+2)
	copy(hashInput, data)
	hashInput[len(data)] = byte(headerLowBits)
	hashInput[len(data)+1] = byte(headerLowBits >> 8)
	hash := int64(Murmur3Sum64WithSeed(hashInput, 47) << (64 - NUM_HASH_BITS))
	if hash < 0 {
		hash = -hash
	}
	hashMask := ^uint64(0)
	hashMask <<= uint(64 - NUM_HASH_BITS)
	return uint64(hash) & hashMask
}

func validateParsedTypeDefHash(header int64, metaSizeBits, extraMetaSize int, encoded []byte) error {
	size := metaSizeBits
	if size == META_SIZE_MASK {
		size += extraMetaSize
	}
	if len(encoded) != size {
		return fmt.Errorf("invalid TypeDef encoded size")
	}
	hashMask := ^uint64(0)
	hashMask <<= uint(64 - NUM_HASH_BITS)
	expectedHeaderHash := typeDefHeaderHash(encoded, uint64(header)&^hashMask)
	actualHeaderHash := uint64(header) & hashMask
	if expectedHeaderHash != actualHeaderHash {
		return fmt.Errorf("invalid TypeDef metadata hash")
	}
	return nil
}

/*
readFieldDef reads a single field's definition from the buffer
field def layout as following:
  - first 1 byte: header (2 bits field name encoding + 4 bits size + nullability flag + ref tracking flag)
  - next variable bytes: TypeSpec info
  - next variable bytes: field name or tag id
*/
func readFieldDef(typeResolver *TypeResolver, buffer *ByteBuffer) (FieldDef, error) {
	var bufErr Error
	maxDepth := defaultConfig().MaxDepth
	if typeResolver != nil && typeResolver.fory != nil {
		maxDepth = typeResolver.fory.config.MaxDepth
	}

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
			extra := buffer.ReadVarUint32(&bufErr)
			if bufErr.HasError() {
				return FieldDef{}, bufErr.TakeError()
			}
			if uint64(extra) > uint64(MaxInt-FieldNameSizeThreshold) {
				return FieldDef{}, fmt.Errorf("invalid TypeDef field tag ID")
			}
			tagID = FieldNameSizeThreshold + int(extra)
		}

		// Read field type
		ft, err := readFieldType(buffer, 0, maxDepth, &bufErr)
		if err != nil {
			return FieldDef{}, err
		}
		if bufErr.HasError() {
			return FieldDef{}, bufErr.TakeError()
		}

		return FieldDef{
			name:         "", // No field name when using tag ID
			nameEncoding: meta.UTF_8,
			typeSpec:     ft,
			nullable:     isNullable,
			trackRef:     refTracking,
			tagID:        tagID,
		}, nil
	}

	// Use field name encoding
	nameEncoding := fieldNameEncodings[nameEncodingFlag]
	nameLen := sizeBits
	if nameLen == 0x0F {
		extra := buffer.ReadVarUint32(&bufErr)
		if bufErr.HasError() {
			return FieldDef{}, bufErr.TakeError()
		}
		if uint64(extra) > uint64(MaxInt-FieldNameSizeThreshold) {
			return FieldDef{}, fmt.Errorf("invalid TypeDef field name length")
		}
		nameLen = FieldNameSizeThreshold + int(extra)
	} else {
		nameLen++ // Adjust for 1-based encoding
	}

	// Read field type
	ft, err := readFieldType(buffer, 0, maxDepth, &bufErr)
	if err != nil {
		return FieldDef{}, err
	}
	if bufErr.HasError() {
		return FieldDef{}, bufErr.TakeError()
	}

	// Read field name based on encoding
	if nameLen > buffer.remaining() {
		return FieldDef{}, fmt.Errorf("TypeDef field name length %d exceeds remaining metadata %d", nameLen, buffer.remaining())
	}
	nameBytes := buffer.ReadBinary(nameLen, &bufErr)
	if bufErr.HasError() {
		return FieldDef{}, bufErr.TakeError()
	}
	fieldName, err := typeResolver.typeNameDecoder.Decode(nameBytes, nameEncoding)
	if err != nil {
		return FieldDef{}, fmt.Errorf("failed to decode field name: %w", err)
	}

	return FieldDef{
		name:         fieldName,
		nameEncoding: nameEncoding,
		typeSpec:     ft,
		nullable:     isNullable,
		trackRef:     refTracking,
		tagID:        TagIDUseFieldName, // -1 indicates using field name
	}, nil
}
