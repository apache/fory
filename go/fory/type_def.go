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
	fieldInfos     []FieldInfo
	encoded        []byte
}

func NewTypeDef(typeId TypeId, nsName, typeName *MetaStringBytes, registerByName, compressed bool, fieldInfos []FieldInfo) *TypeDef {
	return &TypeDef{
		typeId:         typeId,
		nsName:         nsName,
		typeName:       typeName,
		compressed:     compressed,
		registerByName: registerByName,
		fieldInfos:     fieldInfos,
		encoded:        nil,
	}
}

func (td *TypeDef) writeTypeDef(buffer *ByteBuffer) {
	buffer.WriteBinary(td.encoded)
}

func readTypeDef(fory *Fory, buffer *ByteBuffer) (*TypeDef, error) {
	return decodeTypeDef(fory, buffer)
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
	fieldInfos, err := buildFieldInfos(fory, value)
	if err != nil {
		return nil, fmt.Errorf("failed to extract field infos: %w", err)
	}

	info, err := fory.typeResolver.getTypeInfo(value, true)
	if err != nil {
		return nil, fmt.Errorf("failed to get type info for value %v: %w", value, err)
	}
	typeId := TypeId(info.TypeID)
	registerByName := IsNamespacedType(typeId)
	typeDef := NewTypeDef(typeId, info.PkgPathBytes, info.NameBytes, registerByName, false, fieldInfos)

	// encoding the typeDef, and save the encoded bytes
	encoded, err := encodingTypeDef(fory.typeResolver, typeDef)
	if err != nil {
		return nil, fmt.Errorf("failed to encode class definition: %w", err)
	}

	typeDef.encoded = encoded
	return typeDef, nil
}

/*
FieldInfo contains information about a single field in a struct
field info layout as following:
  - first 1 byte: header (2 bits field name encoding + 4 bits size + nullability flag + ref tracking flag)
  - next variable bytes: FieldType info
  - next variable bytes: field name or tag id
*/
type FieldInfo struct {
	name         string
	nameEncoding meta.Encoding
	nullable     bool
	trackingRef  bool
	fieldType    FieldType
}

// buildFieldInfos extracts field information from a struct value
func buildFieldInfos(fory *Fory, value reflect.Value) ([]FieldInfo, error) {
	var fieldInfos []FieldInfo

	typ := value.Type()
	for i := 0; i < typ.NumField(); i++ {
		field := typ.Field(i)
		fieldValue := value.Field(i)

		var fieldInfo FieldInfo
		fieldName := field.Name

		nameEncoding := fory.typeResolver.typeNameEncoder.ComputeEncodingWith(fieldName, fieldNameEncodings)

		ft, err := buildFieldType(fory, fieldValue)
		if err != nil {
			return nil, fmt.Errorf("failed to build field type for field %s: %w", fieldName, err)
		}
		fieldInfo = FieldInfo{
			name:         fieldName,
			nameEncoding: nameEncoding,
			nullable:     nullable(field.Type),
			trackingRef:  fory.referenceTracking,
			fieldType:    ft,
		}
		fieldInfos = append(fieldInfos, fieldInfo)
	}
	return fieldInfos, nil
}

// FieldType interface represents different field types, including object, collection, and map types
type FieldType interface {
	TypeId() TypeId
	write(*ByteBuffer)
}

// BaseFieldType provides common functionality for field types
type BaseFieldType struct {
	typeId TypeId
}

func (b *BaseFieldType) TypeId() TypeId { return b.typeId }
func (b *BaseFieldType) write(buffer *ByteBuffer) {
	buffer.WriteVarUint32Small7(uint32(b.typeId))
}

// readFieldInfo reads field type info from the buffer according to the TypeId
func readFieldType(buffer *ByteBuffer) (FieldType, error) {
	typeId := buffer.ReadVarUint32Small7()
	if typeId == LIST || typeId == SET {
		panic("not implement yet")
	} else if typeId == MAP {
		panic("not implement yet")
	}

	return NewObjectFieldType(TypeId(typeId)), nil
}

// CollectionFieldType represents collection types like List, Set
type CollectionFieldType struct {
	BaseFieldType
	elementType FieldType
}

// MapFieldType represents map types
type MapFieldType struct {
	BaseFieldType
	keyType   FieldType
	valueType FieldType
}

// ObjectFieldType represents object field types that aren't registered or collection/map types
type ObjectFieldType struct {
	BaseFieldType
}

func NewObjectFieldType(typeId TypeId) *ObjectFieldType {
	return &ObjectFieldType{
		BaseFieldType: BaseFieldType{
			typeId: typeId,
		},
	}
}

// todo: implement buildFieldType for collection and map types
// buildFieldType builds field type from reflect.Type, handling collection, map and object types
func buildFieldType(fory *Fory, fieldValue reflect.Value) (FieldType, error) {
	fieldType := fieldValue.Type()

	var typeId TypeId
	typeInfo, err := fory.typeResolver.getTypeInfo(fieldValue, true)
	if err != nil {
		return nil, err
	}
	typeId = TypeId(typeInfo.TypeID)

	if fieldType.Kind() == reflect.Slice || fieldType.Kind() == reflect.Array || fieldType.Kind() == SET {
		panic("not implement yet")
	}

	if fieldType.Kind() == reflect.Map {
		panic("not implement yet")
	}

	// For all other types, treat as ObjectFieldType
	return NewObjectFieldType(typeId), nil
}
