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
)

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
func encodingTypeDef(typeResolver *typeResolver, typeDef *TypeDef) ([]byte, error) {
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
func writeFieldDefs(typeResolver *typeResolver, buffer *ByteBuffer, fieldInfos []FieldDef) error {
	for _, field := range fieldInfos {
		if err := writeFieldDef(typeResolver, buffer, field); err != nil {
			return fmt.Errorf("failed to write field def for field %s: %w", field.name, err)
		}
	}
	return nil
}

// writeFieldDef writes a single field's definition
func writeFieldDef(typeResolver *typeResolver, buffer *ByteBuffer, field FieldDef) error {
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
