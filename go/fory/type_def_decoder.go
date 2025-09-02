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
)

/*
decodeTypeDef decodes a TypeDef from the buffer
typeDef are layout as following:
  - first 8 bytes: global header (50 bits hash + 1 bit compress flag + write fields meta + 12 bits meta size)
  - next 1 byte: meta header (2 bits reserved + 1 bit register by name flag + 5 bits num fields)
  - next variable bytes: type id (varint) or ns name + type name
  - next variable bytes: field definitions (see below)
*/
func decodeTypeDef(fory *Fory, buffer *ByteBuffer) (*TypeDef, error) {
	// Read 8-byte global header
	globalHeader := uint64(buffer.ReadInt64())
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
			return nil, fmt.Errorf("type not registered")
		}
		typeId = TypeId(info.TypeID)
	} else {
		typeId = TypeId(metaBuffer.ReadVarInt32())
	}

	// Read fields information
	fieldInfos := make([]FieldInfo, fieldCount)
	if hasFieldsMeta {
		for i := 0; i < fieldCount; i++ {
			fieldInfo, err := readFieldInfo(fory.typeResolver, metaBuffer)
			if err != nil {
				return nil, fmt.Errorf("failed to read field info %d: %w", i, err)
			}
			fieldInfos[i] = fieldInfo
		}
	}

	// Create TypeDef
	typeDef := NewTypeDef(typeId, nsBytes, nameBytes, registeredByName, isCompressed, fieldInfos)
	typeDef.encoded = encoded

	return typeDef, nil
}

/*
readFieldInfo reads a single field's information from the buffer
field info layout as following:
  - first 1 byte: header (2 bits field name encoding + 4 bits size + nullability flag + ref tracking flag)
  - next variable bytes: FieldType info
  - next variable bytes: field name or tag id
*/
func readFieldInfo(typeResolver *typeResolver, buffer *ByteBuffer) (FieldInfo, error) {
	// Read field header
	headerByte, err := buffer.ReadByte()
	if err != nil {
		return FieldInfo{}, fmt.Errorf("failed to read field header: %w", err)
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
		return FieldInfo{}, err
	}

	// Reading field name based on encoding
	nameBytes := buffer.ReadBinary(nameLen)
	fieldName, err := typeResolver.typeNameDecoder.Decode(nameBytes, nameEncoding)
	if err != nil {
		return FieldInfo{}, fmt.Errorf("failed to decode field name: %w", err)
	}

	return FieldInfo{
		name:         fieldName,
		nameEncoding: nameEncoding,
		fieldType:    ft,
		nullable:     isNullable,
		trackingRef:  refTracking,
	}, nil
}
