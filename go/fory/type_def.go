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

	"github.com/apache/fory/go/fory/meta"
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

	info, err := fory.typeResolver.getTypeInfo(value, value.Type(), true)
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
			trackingRef:  fory.refTracking,
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
	getTypeInfo(*Fory) (TypeInfo, error) // some serializer need typeinfo as well
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

func (b *BaseFieldType) getTypeInfo(fory *Fory) (TypeInfo, error) {
	info, err := fory.typeResolver.getTypeInfoById(b.typeId)
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
	case EXTENSION, STRUCT, NAMED_STRUCT, COMPATIBLE_STRUCT, NAMED_COMPATIBLE_STRUCT:
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
	sliceSerializer := NewSliceSerializer(f, elemInfo.Serializer, elemInfo.Type)
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

// DynamicFieldType represents a field type that is determined at runtime, like EXTENSION or STRUCT
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

// buildFieldType builds field type from reflect.Type, handling collection, map recursively
func buildFieldType(fory *Fory, fieldValue reflect.Value) (FieldType, error) {
	fieldType := fieldValue.Type()

	// Handle slice and array types
	if fieldType.Kind() == reflect.Slice || fieldType.Kind() == reflect.Array {
		// Create a zero value of the element type for recursive processing
		elemType := fieldType.Elem()
		elemValue := reflect.Zero(elemType)

		elementFieldType, err := buildFieldType(fory, elemValue)
		if err != nil {
			return nil, fmt.Errorf("failed to build element field type: %w", err)
		}

		return NewCollectionFieldType(LIST, elementFieldType), nil
	}

	// Handle map types
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

	// For all other types, get the type ID and treat as ObjectFieldType
	var typeId TypeId
	typeInfo, err := fory.typeResolver.getTypeInfo(fieldValue, fieldType, true)
	if err != nil {
		return nil, err
	}
	typeId = TypeId(typeInfo.TypeID)

	if typeId == EXTENSION || typeId == STRUCT || typeId == NAMED_STRUCT ||
		typeId == COMPATIBLE_STRUCT || typeId == NAMED_COMPATIBLE_STRUCT {
		return NewDynamicFieldType(typeId), nil
	}

	return NewSimpleFieldType(typeId), nil
}
