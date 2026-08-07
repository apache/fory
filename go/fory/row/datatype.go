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
	"strings"

	fory "github.com/apache/fory/go/fory"
)

// DataType describes the row-format type of a field.
//
// Type ids are the cross-language Fory type ids shared with the Java,
// C++, and Python row-format implementations; they appear verbatim in
// the serialized schema wire format.
type DataType interface {
	TypeID() fory.TypeId
	// ByteWidth returns the fixed storage width in bytes, or -1 for
	// variable-width types stored through an offset+size slot.
	ByteWidth() int
	String() string
}

// Cross-language child field names for matching Java DataTypes.
const (
	listItemName = "item"
	mapKeyName   = "key"
	mapValueName = "value"
)

type BoolType struct{}

func (BoolType) TypeID() fory.TypeId { return fory.BOOL }
func (BoolType) ByteWidth() int      { return 1 }
func (BoolType) String() string      { return "bool" }

type Int8Type struct{}

func (Int8Type) TypeID() fory.TypeId { return fory.INT8 }
func (Int8Type) ByteWidth() int      { return 1 }
func (Int8Type) String() string      { return "int8" }

type Int16Type struct{}

func (Int16Type) TypeID() fory.TypeId { return fory.INT16 }
func (Int16Type) ByteWidth() int      { return 2 }
func (Int16Type) String() string      { return "int16" }

type Int32Type struct{}

func (Int32Type) TypeID() fory.TypeId { return fory.INT32 }
func (Int32Type) ByteWidth() int      { return 4 }
func (Int32Type) String() string      { return "int32" }

type Int64Type struct{}

func (Int64Type) TypeID() fory.TypeId { return fory.INT64 }
func (Int64Type) ByteWidth() int      { return 8 }
func (Int64Type) String() string      { return "int64" }

// Float16Type exists so schemas received from other languages parse,
// the Go writer, reader, and encoder do not support it yet.
type Float16Type struct{}

func (Float16Type) TypeID() fory.TypeId { return fory.FLOAT16 }
func (Float16Type) ByteWidth() int      { return 2 }
func (Float16Type) String() string      { return "float16" }

type Float32Type struct{}

func (Float32Type) TypeID() fory.TypeId { return fory.FLOAT32 }
func (Float32Type) ByteWidth() int      { return 4 }
func (Float32Type) String() string      { return "float32" }

type Float64Type struct{}

func (Float64Type) TypeID() fory.TypeId { return fory.FLOAT64 }
func (Float64Type) ByteWidth() int      { return 8 }
func (Float64Type) String() string      { return "float64" }

// StringType values are UTF-8 bytes in the variable data region,
// stored as (offset + size) in the fixed slot region.
type StringType struct{}

func (StringType) TypeID() fory.TypeId { return fory.STRING }
func (StringType) ByteWidth() int      { return -1 }
func (StringType) String() string      { return "string" }

type BinaryType struct{}

func (BinaryType) TypeID() fory.TypeId { return fory.BINARY }
func (BinaryType) ByteWidth() int      { return -1 }
func (BinaryType) String() string      { return "binary" }

// Date32Type values are days since the Unix epoch (int32).
type Date32Type struct{}

func (Date32Type) TypeID() fory.TypeId { return fory.DATE }
func (Date32Type) ByteWidth() int      { return 4 }
func (Date32Type) String() string      { return "date32" }

// TimestampType values are microseconds since the Unix epoch (int64).
type TimestampType struct{}

func (TimestampType) TypeID() fory.TypeId { return fory.TIMESTAMP }
func (TimestampType) ByteWidth() int      { return 8 }
func (TimestampType) String() string      { return "timestamp" }

// DurationType values are microseconds (int64).
type DurationType struct{}

func (DurationType) TypeID() fory.TypeId { return fory.DURATION }
func (DurationType) ByteWidth() int      { return 8 }
func (DurationType) String() string      { return "duration" }

// DecimalType exists so schemas received from other languages parse; the
// Go writer, reader, and encoder do not support it yet. Use it as a
// value, not a pointer, so schema equality compares precision and scale.
type DecimalType struct {
	Precision int
	Scale     int
}

func (DecimalType) TypeID() fory.TypeId { return fory.DECIMAL }
func (DecimalType) ByteWidth() int      { return -1 }
func (t DecimalType) String() string {
	return fmt.Sprintf("decimal(%d, %d)", t.Precision, t.Scale)
}

// ListType is a variable-length sequence of Elem values.
type ListType struct {
	Elem Field
}

func (*ListType) TypeID() fory.TypeId { return fory.LIST }
func (*ListType) ByteWidth() int      { return -1 }
func (t *ListType) String() string    { return "list<" + t.Elem.Type.String() + ">" }

// MapType stores keys and values as two adjacent arrays.
type MapType struct {
	Key   Field
	Value Field
}

func (*MapType) TypeID() fory.TypeId { return fory.MAP }
func (*MapType) ByteWidth() int      { return -1 }
func (t *MapType) String() string {
	return "map<" + t.Key.Type.String() + ", " + t.Value.Type.String() + ">"
}

// StructType is a nested row with its own bitmap, slots, and variable
// data region.
type StructType struct {
	Fields []Field
}

func (*StructType) TypeID() fory.TypeId { return fory.STRUCT }
func (*StructType) ByteWidth() int      { return -1 }
func (t *StructType) String() string {
	var sb strings.Builder
	sb.WriteString("struct<")
	for i, f := range t.Fields {
		if i > 0 {
			sb.WriteString(", ")
		}
		sb.WriteString(f.Name)
		sb.WriteString(": ")
		sb.WriteString(f.Type.String())
	}
	sb.WriteString(">")
	return sb.String()
}

// Field is a named, optionally nullable slot in a schema or composite type.
type Field struct {
	Name     string
	Type     DataType
	Nullable bool
}

func NewField(name string, dataType DataType, nullable bool) Field {
	return Field{Name: name, Type: dataType, Nullable: nullable}
}

func (f Field) Equal(o Field) bool {
	return f.Name == o.Name && f.Nullable == o.Nullable && dataTypeEqual(f.Type, o.Type)
}

// List returns a list type with the cross-language element field name
// "item" and nullable elements. Build ListType directly for
// non-nullable elements.
func List(elem DataType) *ListType {
	return &ListType{Elem: Field{Name: listItemName, Type: elem, Nullable: true}}
}

// Map returns a map type with the cross-language field names
// "key"/"value". Keys are always non-nullable.
func Map(key, value DataType) *MapType {
	return &MapType{
		Key:   Field{Name: mapKeyName, Type: key, Nullable: false},
		Value: Field{Name: mapValueName, Type: value, Nullable: true},
	}
}

func Struct(fields []Field) *StructType {
	return &StructType{Fields: fields}
}

func dataTypeEqual(a, b DataType) bool {
	switch at := a.(type) {
	case *ListType:
		bt, ok := b.(*ListType)
		return ok && at.Elem.Equal(bt.Elem)
	case *MapType:
		bt, ok := b.(*MapType)
		return ok && at.Key.Equal(bt.Key) && at.Value.Equal(bt.Value)
	case *StructType:
		bt, ok := b.(*StructType)
		if !ok || len(at.Fields) != len(bt.Fields) {
			return false
		}
		for i := range at.Fields {
			if !at.Fields[i].Equal(bt.Fields[i]) {
				return false
			}
		}
		return true
	default:
		// Primitive types are empty structs and DecimalType is a
		// comparable value, so interface equality is exact.
		return a == b
	}
}

// Schema describes the fields of a top-level row.
type Schema struct {
	fields []Field
	byName map[string]int
}

// NewSchema builds a schema from fields in their schema-declared order,
// which fixes the field slot layout. For duplicate names, FieldIndex
// resolves to the first occurrence.
func NewSchema(fields []Field) *Schema {
	byName := make(map[string]int, len(fields))
	for i, f := range fields {
		if _, ok := byName[f.Name]; !ok {
			byName[f.Name] = i
		}
	}
	return &Schema{fields: fields, byName: byName}
}

func (s *Schema) NumFields() int { return len(s.fields) }

func (s *Schema) Field(i int) Field { return s.fields[i] }

// Fields returns the backing field slice; callers must not modify it.
func (s *Schema) Fields() []Field { return s.fields }

// FieldIndex returns the ordinal of the named field, or -1 if absent.
func (s *Schema) FieldIndex(name string) int {
	if i, ok := s.byName[name]; ok {
		return i
	}
	return -1
}

func (s *Schema) Equal(o *Schema) bool {
	if s == o {
		return true
	}
	if o == nil || len(s.fields) != len(o.fields) {
		return false
	}
	for i := range s.fields {
		if !s.fields[i].Equal(o.fields[i]) {
			return false
		}
	}
	return true
}

func (s *Schema) String() string {
	var sb strings.Builder
	sb.WriteString("schema<")
	for i, f := range s.fields {
		if i > 0 {
			sb.WriteString(", ")
		}
		sb.WriteString(f.Name)
		sb.WriteString(": ")
		sb.WriteString(f.Type.String())
	}
	sb.WriteString(">")
	return sb.String()
}
