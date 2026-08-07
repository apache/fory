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

package row

import (
	"fmt"
	"math"

	fory "github.com/apache/fory/go/fory"
	"github.com/apache/fory/go/fory/meta"
)

// Schema wire format, byte-compatible with Java SchemaEncoder:
//
//	| version (1 byte) | num_fields (var uint32 small7) | fields... |
//
// Field: | header (1 byte) | name size (varint, only if large) | name
// bytes | type info |. Header bits 0-1 hold the index into
// fieldNameEncodings, bits 2-5 hold nameSize-1 (15 means a varint with
// nameSize-15 follows), bit 6 is the nullable flag. Type info is the
// type id byte, plus precision+scale for DECIMAL, a recursive element
// field for LIST, recursive key and value fields for MAP, and a field
// count plus recursive fields for STRUCT.
const (
	schemaVersion          = 1
	fieldNameSizeThreshold = 15
	// Caps readField/readType recursion.
	maxSchemaNestingDepth = 64
)

// Header bits 0-1 store the INDEX into this table (matching Java
// SchemaEncoder.FIELD_NAME_ENCODINGS order), not the meta.Encoding
// wire values, which differ.
var fieldNameEncodings = [3]meta.Encoding{
	meta.UTF_8,
	meta.ALL_TO_LOWER_SPECIAL,
	meta.LOWER_UPPER_DIGIT_SPECIAL,
}

// Stateless after construction, safe for concurrent use.
var (
	fieldNameEncoder = meta.NewEncoder('$', '_')
	fieldNameDecoder = meta.NewDecoder('$', '_')
)

func encodingIndexOf(encoding meta.Encoding) int {
	for i, e := range fieldNameEncodings {
		if e == encoding {
			return i
		}
	}
	return -1
}

// SchemaToBytes serializes a schema to the cross-language wire format.
func SchemaToBytes(s *Schema) ([]byte, error) {
	buf := fory.NewByteBuffer(nil)
	if err := SchemaToBuffer(s, buf); err != nil {
		return nil, err
	}
	return buf.GetByteSlice(0, buf.WriterIndex()), nil
}

// SchemaToBuffer serializes a schema into an existing buffer.
func SchemaToBuffer(s *Schema, buf *fory.ByteBuffer) error {
	buf.WriteByte_(schemaVersion)
	buf.WriteVarUint32Small7(uint32(s.NumFields()))
	for _, f := range s.Fields() {
		if err := writeSchemaField(buf, f); err != nil {
			return err
		}
	}
	return nil
}

func writeSchemaField(buf *fory.ByteBuffer, f Field) error {
	if f.Name == "" {
		return fmt.Errorf("row: schema field with empty name")
	}
	encoding := fieldNameEncoder.ComputeEncodingWith(f.Name, fieldNameEncodings[:])
	metaString, err := fieldNameEncoder.EncodeWithEncoding(f.Name, encoding)
	if err != nil {
		return fmt.Errorf("row: encoding field name %q: %w", f.Name, err)
	}
	nameBytes := metaString.GetEncodedBytes()
	nameSize := len(nameBytes)
	encodingIndex := encodingIndexOf(metaString.GetEncoding())
	if encodingIndex < 0 {
		return fmt.Errorf("row: field name %q got unsupported encoding %d", f.Name, metaString.GetEncoding())
	}

	header := encodingIndex & 0x03
	bigSize := nameSize > fieldNameSizeThreshold
	if bigSize {
		header |= fieldNameSizeThreshold << 2
	} else {
		header |= (nameSize - 1) << 2
	}
	if f.Nullable {
		header |= 0x40
	}
	buf.WriteByte_(byte(header))
	if bigSize {
		buf.WriteVarUint32Small7(uint32(nameSize - fieldNameSizeThreshold))
	}
	buf.WriteBinary(nameBytes)
	return writeSchemaType(buf, f.Type)
}

func writeSchemaType(buf *fory.ByteBuffer, dataType DataType) error {
	buf.WriteByte_(byte(dataType.TypeID()))
	switch t := dataType.(type) {
	case DecimalType:
		buf.WriteByte_(byte(t.Precision))
		buf.WriteByte_(byte(t.Scale))
	case *ListType:
		return writeSchemaField(buf, t.Elem)
	case *MapType:
		if err := writeSchemaField(buf, t.Key); err != nil {
			return err
		}
		return writeSchemaField(buf, t.Value)
	case *StructType:
		buf.WriteVarUint32Small7(uint32(len(t.Fields)))
		for _, f := range t.Fields {
			if err := writeSchemaField(buf, f); err != nil {
				return err
			}
		}
	}
	return nil
}

// SchemaFromBytes deserializes a schema from the cross-language wire
// format. It is safe on untrusted input: declared sizes are checked
// against remaining bytes before any allocation.
func SchemaFromBytes(data []byte) (*Schema, error) {
	r := &schemaReader{buf: fory.NewByteBuffer(data), size: len(data)}
	version := r.buf.ReadUint8(&r.err)
	if err := r.err.CheckError(); err != nil {
		return nil, err
	}
	if version != schemaVersion {
		return nil, fmt.Errorf("row: unsupported schema version %d, expected %d", version, schemaVersion)
	}
	numFields := int(r.buf.ReadVarUint32Small7(&r.err))
	if err := r.err.CheckError(); err != nil {
		return nil, err
	}
	// Every field costs at least one byte, so a declared count larger
	// than the remaining input is corrupt; checking before the
	// allocation below keeps attacker-declared counts harmless.
	if numFields > r.remaining() {
		return nil, fmt.Errorf("row: schema declares %d fields but only %d bytes remain", numFields, r.remaining())
	}
	fields := make([]Field, 0, numFields)
	for i := 0; i < numFields; i++ {
		f, err := r.readField()
		if err != nil {
			return nil, err
		}
		fields = append(fields, f)
	}
	return NewSchema(fields), nil
}

type schemaReader struct {
	buf   *fory.ByteBuffer
	size  int
	depth int
	err   fory.Error
}

func (r *schemaReader) remaining() int { return r.size - r.buf.ReaderIndex() }

func (r *schemaReader) readField() (Field, error) {
	if r.depth >= maxSchemaNestingDepth {
		return Field{}, fmt.Errorf("row: schema nesting exceeds %d levels", maxSchemaNestingDepth)
	}
	r.depth++
	defer func() { r.depth-- }()
	header := int(r.buf.ReadUint8(&r.err))
	if err := r.err.CheckError(); err != nil {
		return Field{}, err
	}
	encodingIndex := header & 0x03
	if encodingIndex >= len(fieldNameEncodings) {
		return Field{}, fmt.Errorf("row: invalid field name encoding index %d", encodingIndex)
	}
	nameSizeMinus1 := (header >> 2) & 0x0F
	nullable := header&0x40 != 0

	var nameSize int
	if nameSizeMinus1 == fieldNameSizeThreshold {
		nameSize = int(r.buf.ReadVarUint32Small7(&r.err)) + fieldNameSizeThreshold
	} else {
		nameSize = nameSizeMinus1 + 1
	}
	if err := r.err.CheckError(); err != nil {
		return Field{}, err
	}
	if nameSize > r.remaining() {
		return Field{}, fmt.Errorf("row: field name of %d bytes exceeds %d remaining", nameSize, r.remaining())
	}
	nameBytes := r.buf.ReadBinary(nameSize, &r.err)
	if err := r.err.CheckError(); err != nil {
		return Field{}, err
	}
	name, err := fieldNameDecoder.Decode(nameBytes, fieldNameEncodings[encodingIndex])
	if err != nil {
		return Field{}, fmt.Errorf("row: decoding field name: %w", err)
	}
	dataType, err := r.readType()
	if err != nil {
		return Field{}, err
	}
	return Field{Name: name, Type: dataType, Nullable: nullable}, nil
}

func (r *schemaReader) readType() (DataType, error) {
	typeID := fory.TypeId(r.buf.ReadUint8(&r.err))
	if err := r.err.CheckError(); err != nil {
		return nil, err
	}
	switch typeID {
	case fory.BOOL:
		return BoolType{}, nil
	case fory.INT8:
		return Int8Type{}, nil
	case fory.INT16:
		return Int16Type{}, nil
	case fory.INT32:
		return Int32Type{}, nil
	case fory.INT64:
		return Int64Type{}, nil
	case fory.FLOAT16:
		return Float16Type{}, nil
	case fory.FLOAT32:
		return Float32Type{}, nil
	case fory.FLOAT64:
		return Float64Type{}, nil
	case fory.STRING:
		return StringType{}, nil
	case fory.BINARY:
		return BinaryType{}, nil
	case fory.DURATION:
		return DurationType{}, nil
	case fory.TIMESTAMP:
		return TimestampType{}, nil
	case fory.DATE:
		return Date32Type{}, nil
	case fory.DECIMAL:
		precision := int(r.buf.ReadUint8(&r.err))
		scale := int(r.buf.ReadUint8(&r.err))
		if err := r.err.CheckError(); err != nil {
			return nil, err
		}
		return DecimalType{Precision: precision, Scale: scale}, nil
	case fory.LIST:
		elem, err := r.readField()
		if err != nil {
			return nil, err
		}
		return &ListType{Elem: elem}, nil
	case fory.MAP:
		keyField, err := r.readField()
		if err != nil {
			return nil, err
		}
		valueField, err := r.readField()
		if err != nil {
			return nil, err
		}
		// Java rebuilds maps from the child types only, restoring the
		// canonical key/value names and nullability.
		return Map(keyField.Type, valueField.Type), nil
	case fory.STRUCT:
		numFields := int(r.buf.ReadVarUint32Small7(&r.err))
		if err := r.err.CheckError(); err != nil {
			return nil, err
		}
		if numFields > r.remaining() {
			return nil, fmt.Errorf("row: struct declares %d fields but only %d bytes remain", numFields, r.remaining())
		}
		fields := make([]Field, 0, numFields)
		for i := 0; i < numFields; i++ {
			f, err := r.readField()
			if err != nil {
				return nil, err
			}
			fields = append(fields, f)
		}
		return &StructType{Fields: fields}, nil
	default:
		return nil, fmt.Errorf("row: unknown type id %d in schema", typeID)
	}
}

// ComputeSchemaHash computes the cross-language schema hash, matching
// Java DataTypes.computeSchemaHash and the Python equivalent: fold
// hash*31 + typeId over fields, recursing into list elements, map keys
// and values, and struct fields; on signed-64 overflow, arithmetic-
// shift the hash right by 2 and retry.
func ComputeSchemaHash(s *Schema) int64 {
	hash := int64(17)
	for _, f := range s.Fields() {
		hash = computeFieldHash(hash, f)
	}
	return hash
}

func computeFieldHash(hash int64, f Field) int64 {
	typeID := int64(f.Type.TypeID())
	for {
		if hash <= math.MaxInt64/31 && hash >= math.MinInt64/31 {
			product := hash * 31
			if product <= math.MaxInt64-typeID {
				hash = product + typeID
				break
			}
		}
		hash >>= 2
	}
	switch t := f.Type.(type) {
	case *ListType:
		hash = computeFieldHash(hash, t.Elem)
	case *MapType:
		hash = computeFieldHash(hash, t.Key)
		hash = computeFieldHash(hash, t.Value)
	case *StructType:
		for _, child := range t.Fields {
			hash = computeFieldHash(hash, child)
		}
	}
	return hash
}
