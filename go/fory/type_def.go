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
	"github.com/apache/fory/go/fory/meta"
	"github.com/spaolacci/murmur3"
	"reflect"
)

const (
	META_SIZE_MASK       = 0xFFF
	COMPRESS_META_FLAG   = 0b1 << 13
	HAS_FIELDS_META_FLAG = 0b1 << 12
	NUM_HASH_BITS        = 50
)

/*
TypeDef represents a transportable value object containing type information and field definitions.
typeDef are layout as following:
  - first 8 bytes: global header (50 bits hash + 1 bit compress flag + write fields meta + 12 bits meta size)
  - next 1 byte: meta header (2 bits reserved + 1 bit register by name flag + 5 bits num fields)
  - next variable bytes: type id (varint) or ns name + type name
  - next variable bytes: field definitions (see below)
*/
type TypeDef struct {
	typeId         TypeId
	nsName         *MetaStringBytes
	typeName       *MetaStringBytes
	compressed     bool
	registerByName bool
	fieldDefs      []FieldDef
	encoded        []byte
	type_          reflect.Type
}

func NewTypeDef(typeId TypeId, nsName, typeName *MetaStringBytes, registerByName, compressed bool, fieldDefs []FieldDef) *TypeDef {
	return &TypeDef{
		typeId:         typeId,
		nsName:         nsName,
		typeName:       typeName,
		compressed:     compressed,
		registerByName: registerByName,
		fieldDefs:      fieldDefs,
		encoded:        nil,
	}
}

func (td *TypeDef) writeTypeDef(buffer *ByteBuffer) {
	buffer.WriteBinary(td.encoded)
}

// buildTypeInfo constructs a TypeInfo from the TypeDef
func (td *TypeDef) buildTypeInfo() (TypeInfo, error) {
	info := TypeInfo{
		Type:         td.type_,
		TypeID:       int32(td.typeId),
		Serializer:   &structSerializer{type_: td.type_, fieldDefs: td.fieldDefs},
		PkgPathBytes: td.nsName,
		NameBytes:    td.typeName,
		IsDynamic:    td.typeId < 0,
	}
	return info, nil
}

func readTypeDef(fory *Fory, buffer *ByteBuffer, header int64) (*TypeDef, error) {
	return decodeTypeDef(fory, buffer, header)
}

func skipTypeDef(buffer *ByteBuffer, header int64) {
	sz := int(header & META_SIZE_MASK)
	if sz == META_SIZE_MASK {
		sz += int(buffer.ReadVarUint32())
	}
	buffer.IncreaseReaderIndex(sz)
}

// buildTypeDef constructs a TypeDef from a value
func buildTypeDef(fory *Fory, value reflect.Value) (*TypeDef, error) {
	fieldDefs, err := buildFieldDefs(fory, value)
	if err != nil {
		return nil, fmt.Errorf("failed to extract field infos: %w", err)
	}

	info, err := fory.typeResolver.getTypeInfo(value, true)
	if err != nil {
		return nil, fmt.Errorf("failed to get type info for value %v: %w", value, err)
	}
	typeId := TypeId(info.TypeID)
	registerByName := IsNamespacedType(typeId)
	typeDef := NewTypeDef(typeId, info.PkgPathBytes, info.NameBytes, registerByName, false, fieldDefs)

	// encoding the typeDef, and save the encoded bytes
	encoded, err := encodingTypeDef(fory.typeResolver, typeDef)
	if err != nil {
		return nil, fmt.Errorf("failed to encode class definition: %w", err)
	}

	typeDef.encoded = encoded
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
}

// buildFieldDefs extracts field definitions from a struct value
func buildFieldDefs(fory *Fory, value reflect.Value) ([]FieldDef, error) {
	var fieldDefs []FieldDef

	type_ := value.Type()
	for i := 0; i < type_.NumField(); i++ {
		field := type_.Field(i)
		fieldValue := value.Field(i)

		var fieldInfo FieldDef
		fieldName := SnakeCase(field.Name)

		nameEncoding := fory.typeResolver.typeNameEncoder.ComputeEncodingWith(fieldName, fieldNameEncodings)

		ft, err := buildFieldType(fory, fieldValue)
		if err != nil {
			return nil, fmt.Errorf("failed to build field type for field %s: %w", fieldName, err)
		}
		fieldInfo = FieldDef{
			name:         fieldName,
			nameEncoding: nameEncoding,
			nullable:     nullable(field.Type),
			trackingRef:  fory.config.TrackRef,
			fieldType:    ft,
		}
		fieldDefs = append(fieldDefs, fieldInfo)
	}

	// Sort field definitions
	if len(fieldDefs) > 1 {
		// Extract serializers and names for sorting
		serializers := make([]Serializer, len(fieldDefs))
		fieldNames := make([]string, len(fieldDefs))
		for i, fieldDef := range fieldDefs {
			serializer, err := getFieldTypeSerializer(fory, fieldDef.fieldType)
			if err != nil {
				// If we can't get serializer, use nil (will be handled by sortFields)
				serializers[i] = nil
			} else {
				serializers[i] = serializer
			}
			fieldNames[i] = fieldDef.name
		}

		// Use existing sortFields function to get optimal order
		_, sortedNames := sortFields(fory.typeResolver, fieldNames, serializers)

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
	write(*ByteBuffer)
	getTypeInfo(*Fory) (TypeInfo, error)                     // some serializer need typeinfo as well
	getTypeInfoWithResolver(*TypeResolver) (TypeInfo, error) // version that uses typeResolver directly
}

// BaseFieldType provides common functionality for field types
type BaseFieldType struct {
	typeId TypeId
}

func (b *BaseFieldType) TypeId() TypeId { return b.typeId }
func (b *BaseFieldType) write(buffer *ByteBuffer) {
	buffer.WriteVarUint32Small7(uint32(b.typeId))
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
	info, err := fory.typeResolver.getTypeInfoById(b.typeId)
	if err != nil {
		return TypeInfo{}, err
	}
	return info, nil
}

func (b *BaseFieldType) getTypeInfoWithResolver(resolver *TypeResolver) (TypeInfo, error) {
	info, err := resolver.getTypeInfoById(b.typeId)
	if err != nil {
		return TypeInfo{}, err
	}
	return info, nil
}

// readFieldType reads field type info from the buffer according to the TypeId
func readFieldType(buffer *ByteBuffer) (FieldType, error) {
	typeId := buffer.ReadVarUint32Small7()

	switch typeId {
	case LIST, SET:
		// Read element type recursively
		elementType, err := readFieldType(buffer)
		if err != nil {
			return nil, fmt.Errorf("failed to read element type: %w", err)
		}
		return NewCollectionFieldType(TypeId(typeId), elementType), nil
	case MAP:
		// Read key type recursively
		keyType, err := readFieldType(buffer)
		if err != nil {
			return nil, fmt.Errorf("failed to read key type: %w", err)
		}
		// Read value type recursively
		valueType, err := readFieldType(buffer)
		if err != nil {
			return nil, fmt.Errorf("failed to read value type: %w", err)
		}
		return NewMapFieldType(TypeId(typeId), keyType, valueType), nil
	case UNKNOWN, EXT, STRUCT, NAMED_STRUCT, COMPATIBLE_STRUCT, NAMED_COMPATIBLE_STRUCT:
		return NewDynamicFieldType(TypeId(typeId)), nil
	}
	return NewSimpleFieldType(TypeId(typeId)), nil
}

// CollectionFieldType represents collection types like List, Slice
type CollectionFieldType struct {
	BaseFieldType
	elementType FieldType
}

func NewCollectionFieldType(typeId TypeId, elementType FieldType) *CollectionFieldType {
	return &CollectionFieldType{
		BaseFieldType: BaseFieldType{typeId: typeId},
		elementType:   elementType,
	}
}

func (c *CollectionFieldType) write(buffer *ByteBuffer) {
	c.BaseFieldType.write(buffer)
	c.elementType.write(buffer)
}

func (c *CollectionFieldType) getTypeInfo(f *Fory) (TypeInfo, error) {
	elemInfo, err := c.elementType.getTypeInfo(f)
	elementType := elemInfo.Type
	collectionType := reflect.SliceOf(elementType)
	if err != nil {
		return TypeInfo{}, err
	}
	sliceSerializer := &sliceSerializer{elemInfo: elemInfo, declaredType: elemInfo.Type}
	return TypeInfo{Type: collectionType, Serializer: sliceSerializer}, nil
}

func (c *CollectionFieldType) getTypeInfoWithResolver(resolver *TypeResolver) (TypeInfo, error) {
	elemInfo, err := c.elementType.getTypeInfoWithResolver(resolver)
	elementType := elemInfo.Type
	collectionType := reflect.SliceOf(elementType)
	if err != nil {
		return TypeInfo{}, err
	}
	sliceSerializer := &sliceSerializer{elemInfo: elemInfo, declaredType: elemInfo.Type}
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
		BaseFieldType: BaseFieldType{typeId: typeId},
		keyType:       keyType,
		valueType:     valueType,
	}
}

func (m *MapFieldType) write(buffer *ByteBuffer) {
	m.BaseFieldType.write(buffer)
	m.keyType.write(buffer)
	m.valueType.write(buffer)
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
	mapSerializer := &mapSerializer{
		keySerializer:   keyInfo.Serializer,
		valueSerializer: valueInfo.Serializer,
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
	mapSerializer := &mapSerializer{
		keySerializer:   keyInfo.Serializer,
		valueSerializer: valueInfo.Serializer,
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
			typeId: typeId,
		},
	}
}

// DynamicFieldType represents a field type that is determined at runtime, like EXT or STRUCT
type DynamicFieldType struct {
	BaseFieldType
}

func NewDynamicFieldType(typeId TypeId) *DynamicFieldType {
	return &DynamicFieldType{
		BaseFieldType: BaseFieldType{
			typeId: typeId,
		},
	}
}

func (d *DynamicFieldType) getTypeInfo(fory *Fory) (TypeInfo, error) {
	// leave empty for runtime resolution, we not know the actual type here
	return TypeInfo{Type: reflect.TypeOf((*interface{})(nil)).Elem(), Serializer: nil}, nil
}

func (d *DynamicFieldType) getTypeInfoWithResolver(resolver *TypeResolver) (TypeInfo, error) {
	// leave empty for runtime resolution, we not know the actual type here
	return TypeInfo{Type: reflect.TypeOf((*interface{})(nil)).Elem(), Serializer: nil}, nil
}

// buildFieldType builds field type from reflect.Type, handling collection, map recursively
func buildFieldType(fory *Fory, fieldValue reflect.Value) (FieldType, error) {
	fieldType := fieldValue.Type()
	// Handle Interface type, we can't determine the actual type here, so leave it as dynamic type
	if fieldType.Kind() == reflect.Interface {
		return NewDynamicFieldType(UNKNOWN), nil
	}

	var typeId TypeId
	typeInfo, err := fory.typeResolver.getTypeInfo(fieldValue, true)
	if err != nil {
		return nil, err
	}
	typeId = TypeId(typeInfo.TypeID)
	if typeId < 0 {
		typeId = -typeId // restore pointer type id
	}

	// Handle slice and array types by reflect.Kind
	// For fixed-size arrays with primitive elements, use primitive array type IDs (INT16_ARRAY, etc.)
	// For slices and arrays with non-primitive elements, use collection format
	if fieldType.Kind() == reflect.Slice || fieldType.Kind() == reflect.Array {
		elemType := fieldType.Elem()

		// Check if element is a primitive type that maps to a primitive array type ID
		// Only fixed-size arrays use primitive array format; slices always use LIST
		if fieldType.Kind() == reflect.Array {
			switch elemType.Kind() {
			case reflect.Int8:
				return NewSimpleFieldType(INT8_ARRAY), nil
			case reflect.Int16:
				return NewSimpleFieldType(INT16_ARRAY), nil
			case reflect.Int32:
				return NewSimpleFieldType(INT32_ARRAY), nil
			case reflect.Int64:
				return NewSimpleFieldType(INT64_ARRAY), nil
			case reflect.Float32:
				return NewSimpleFieldType(FLOAT32_ARRAY), nil
			case reflect.Float64:
				return NewSimpleFieldType(FLOAT64_ARRAY), nil
			}
		}

		// For slices and non-primitive arrays, use collection format
		elemValue := reflect.Zero(elemType)
		elementFieldType, err := buildFieldType(fory, elemValue)
		if err != nil {
			return nil, fmt.Errorf("failed to build element field type: %w", err)
		}

		return NewCollectionFieldType(LIST, elementFieldType), nil
	}

	// Handle map types by reflect.Kind for consistency
	if fieldType.Kind() == reflect.Map {
		// Create zero values for key and value types
		keyType := fieldType.Key()
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

	if isUserDefinedType(typeId) {
		return NewDynamicFieldType(typeId), nil
	}

	return NewSimpleFieldType(typeId), nil
}

const (
	SmallNumFieldsThreshold = 31
	REGISTER_BY_NAME_FLAG   = 0b1 << 5
	FieldNameSizeThreshold  = 15
)

// Encoding `UTF8/ALL_TO_LOWER_SPECIAL/LOWER_UPPER_DIGIT_SPECIAL/TAG_ID` for fieldName
var fieldNameEncodings = []meta.Encoding{
	meta.UTF_8,
	meta.ALL_TO_LOWER_SPECIAL,
	meta.LOWER_UPPER_DIGIT_SPECIAL,
	// todo: add support for TAG_ID encoding
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
- first 8 bytes: global header (50 bits hash + 1 bit compress flag + write fields meta + 12 bits meta size)
- next 1 byte: meta header (2 bits reserved + 1 bit register by name flag + 5 bits num fields)
- next variable bytes: type id (varint) or ns name + type name
- next variable bytes: field defs (see below)
*/
func encodingTypeDef(typeResolver *TypeResolver, typeDef *TypeDef) ([]byte, error) {
	buffer := NewByteBuffer(nil)

	if err := writeMetaHeader(buffer, typeDef); err != nil {
		return nil, fmt.Errorf("failed to write meta header: %w", err)
	}

	if typeDef.registerByName {
		if err := typeResolver.metaStringResolver.WriteMetaStringBytes(buffer, typeDef.nsName); err != nil {
			return nil, err
		}
		if err := typeResolver.metaStringResolver.WriteMetaStringBytes(buffer, typeDef.typeName); err != nil {
			return nil, err
		}
	} else {
		buffer.WriteVarInt32(int32(typeDef.typeId))
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

	hashValue := murmur3.Sum64WithSeed(buffer.GetByteSlice(0, metaSize), 47)
	header |= hashValue << (64 - NUM_HASH_BITS)

	if hasFieldsMeta {
		header |= HAS_FIELDS_META_FLAG
	}

	if isCompressed {
		header |= COMPRESS_META_FLAG
	}

	if metaSize < META_SIZE_MASK {
		header |= uint64(metaSize) & 0xFFF
	} else {
		header |= 0xFFF // Set to max value, actual size will follow
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
	// Write field header
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
	// store index of encoding in the 2 highest bits
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

	// todo: support tag id
	// write field name
	if _, err := buffer.Write(metaString.GetEncodedBytes()); err != nil {
		return err
	}
	return nil
}

/*
decodeTypeDef decodes a TypeDef from the buffer
typeDef are layout as following:
  - first 8 bytes: global header (50 bits hash + 1 bit compress flag + write fields meta + 12 bits meta size)
  - next 1 byte: meta header (2 bits reserved + 1 bit register by name flag + 5 bits num fields)
  - next variable bytes: type id (varint) or ns name + type name
  - next variable bytes: field definitions (see below)
*/
func decodeTypeDef(fory *Fory, buffer *ByteBuffer, header int64) (*TypeDef, error) {
	// Read 8-byte global header
	globalHeader := header
	hasFieldsMeta := (globalHeader & HAS_FIELDS_META_FLAG) != 0
	isCompressed := (globalHeader & COMPRESS_META_FLAG) != 0
	metaSize := int(globalHeader & META_SIZE_MASK)
	if metaSize == META_SIZE_MASK {
		metaSize += int(buffer.ReadVarUint32())
	}

	// Store the encoded bytes for the TypeDef (including meta header and metadata)
	// todo: handle compression if is_compressed is true
	if isCompressed {
	}
	encoded := buffer.ReadBinary(metaSize)
	metaBuffer := NewByteBuffer(encoded)

	// Read 1-byte meta header
	metaHeaderByte, err := metaBuffer.ReadByte()
	if err != nil {
		return nil, err
	}
	// Extract field count from lower 5 bits
	fieldCount := int(metaHeaderByte & SmallNumFieldsThreshold)
	if fieldCount == SmallNumFieldsThreshold {
		fieldCount += int(metaBuffer.ReadVarUint32())
	}
	registeredByName := (metaHeaderByte & REGISTER_BY_NAME_FLAG) != 0

	// Read name or type ID according to the registerByName flag
	var typeId TypeId
	var nsBytes, nameBytes *MetaStringBytes
	var type_ reflect.Type
	if registeredByName {
		// Read namespace and type name for namespaced types
		readingNsBytes, err := fory.typeResolver.metaStringResolver.ReadMetaStringBytes(metaBuffer)
		if err != nil {
			return nil, fmt.Errorf("failed to read package path: %w", err)
		}
		nsBytes = readingNsBytes
		readingNameBytes, err := fory.typeResolver.metaStringResolver.ReadMetaStringBytes(metaBuffer)
		if err != nil {
			return nil, fmt.Errorf("failed to read type name: %w", err)
		}
		nameBytes = readingNameBytes
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
				typeId = TypeId(info.TypeID)
			} else {
				typeId = TypeId(info.TypeID)
			}
		} else {
			// Type not registered - use NAMED_STRUCT as default typeId
			// The type_ will remain nil and will be set from field definitions later
			typeId = NAMED_STRUCT
			type_ = nil
		}
	} else {
		typeId = TypeId(metaBuffer.ReadVarInt32())
		if info, err := fory.typeResolver.getTypeInfoById(typeId); err != nil {
			return nil, fmt.Errorf("failed to get type info by id %d: %w", typeId, err)
		} else {
			type_ = info.Type
		}
	}

	// Read fields information
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

	// Create TypeDef
	typeDef := NewTypeDef(typeId, nsBytes, nameBytes, registeredByName, isCompressed, fieldInfos)
	typeDef.encoded = encoded
	typeDef.type_ = type_

	return typeDef, nil
}

/*
readFieldDef reads a single field's definition from the buffer
field def layout as following:
  - first 1 byte: header (2 bits field name encoding + 4 bits size + nullability flag + ref tracking flag)
  - next variable bytes: FieldType info
  - next variable bytes: field name or tag id
*/
func readFieldDef(typeResolver *TypeResolver, buffer *ByteBuffer) (FieldDef, error) {
	// Read field header
	headerByte, err := buffer.ReadByte()
	if err != nil {
		return FieldDef{}, fmt.Errorf("failed to read field header: %w", err)
	}

	// Resolve the header
	nameEncodingFlag := (headerByte >> 6) & 0b11
	nameEncoding := fieldNameEncodings[nameEncodingFlag]
	nameLen := int((headerByte >> 2) & 0x0F)
	refTracking := (headerByte & 0b1) != 0
	isNullable := (headerByte & 0b10) != 0
	if nameLen == 0x0F {
		nameLen = FieldNameSizeThreshold + int(buffer.ReadVarUint32())
	} else {
		nameLen++ // Adjust for 1-based encoding
	}

	// reading field type
	ft, err := readFieldType(buffer)
	if err != nil {
		return FieldDef{}, err
	}

	// Reading field name based on encoding
	nameBytes := buffer.ReadBinary(nameLen)
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
	}, nil
}
