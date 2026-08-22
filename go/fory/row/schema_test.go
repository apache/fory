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
	"testing"

	fory "github.com/apache/fory/go/fory"
	"github.com/stretchr/testify/require"
)

// Type ids are serialized into the cross-language schema bytes, so they
// are pinned to their literal values here.
func TestTypeIDs(t *testing.T) {
	cases := []struct {
		dataType DataType
		id       fory.TypeId
	}{
		{BoolType{}, 1},
		{Int8Type{}, 2},
		{Int16Type{}, 3},
		{Int32Type{}, 4},
		{Int64Type{}, 6},
		{Float16Type{}, 17},
		{Float32Type{}, 19},
		{Float64Type{}, 20},
		{StringType{}, 21},
		{List(Int32Type{}), 22},
		{Map(StringType{}, Int64Type{}), 24},
		{Struct(nil), 27},
		{DurationType{}, 37},
		{TimestampType{}, 38},
		{Date32Type{}, 39},
		{DecimalType{Precision: 10, Scale: 2}, 40},
		{BinaryType{}, 41},
	}
	for _, c := range cases {
		require.Equal(t, c.id, c.dataType.TypeID(), "%s", c.dataType)
	}
}

func TestByteWidths(t *testing.T) {
	cases := []struct {
		dataType DataType
		width    int
	}{
		{BoolType{}, 1},
		{Int8Type{}, 1},
		{Int16Type{}, 2},
		{Int32Type{}, 4},
		{Int64Type{}, 8},
		{Float16Type{}, 2},
		{Float32Type{}, 4},
		{Float64Type{}, 8},
		{Date32Type{}, 4},
		{TimestampType{}, 8},
		{DurationType{}, 8},
		{StringType{}, -1},
		{BinaryType{}, -1},
		{DecimalType{}, -1},
		{List(Int32Type{}), -1},
		{Map(StringType{}, Int64Type{}), -1},
		{Struct(nil), -1},
	}
	for _, c := range cases {
		require.Equal(t, c.width, c.dataType.ByteWidth(), "%s", c.dataType)
	}
}

func TestCompositeFactories(t *testing.T) {
	list := List(StringType{})
	require.Equal(t, "item", list.Elem.Name)
	require.True(t, list.Elem.Nullable)

	m := Map(StringType{}, Int32Type{})
	require.Equal(t, "key", m.Key.Name)
	require.False(t, m.Key.Nullable, "map keys must be non-nullable")
	require.Equal(t, "value", m.Value.Name)
	require.True(t, m.Value.Nullable)

	st := Struct([]Field{NewField("a", Int32Type{}, false)})
	require.Len(t, st.Fields, 1)
	require.Equal(t, "a", st.Fields[0].Name)
}

func TestSchemaLookup(t *testing.T) {
	s := NewSchema([]Field{
		NewField("id", Int64Type{}, false),
		NewField("name", StringType{}, true),
	})
	require.Equal(t, 2, s.NumFields())
	require.Equal(t, "id", s.Field(0).Name)
	require.Equal(t, 1, s.FieldIndex("name"))
	require.Equal(t, -1, s.FieldIndex("missing"))

	// Duplicate names resolve to the last occurrence, as in Java.
	dup := NewSchema([]Field{
		NewField("x", Int32Type{}, false),
		NewField("x", StringType{}, true),
	})
	require.Equal(t, 1, dup.FieldIndex("x"))
}

// Schemas and struct types own a copy of their fields, so later
// mutation of the caller's slice cannot desynchronize name lookups.
func TestSchemaCopiesFields(t *testing.T) {
	fields := []Field{NewField("a", Int32Type{}, false)}
	s := NewSchema(fields)
	st := Struct(fields)
	fields[0].Name = "changed"
	require.Equal(t, "a", s.Field(0).Name)
	require.Equal(t, 0, s.FieldIndex("a"))
	require.Equal(t, "a", st.Fields[0].Name)
}

func TestSchemaEqual(t *testing.T) {
	nested := func() *Schema {
		return NewSchema([]Field{
			NewField("id", Int64Type{}, false),
			NewField("tags", List(StringType{}), true),
			NewField("attrs", Map(StringType{}, Int32Type{}), true),
			NewField("inner", Struct([]Field{NewField("x", Float64Type{}, false)}), true),
			NewField("price", DecimalType{Precision: 10, Scale: 2}, true),
		})
	}
	require.True(t, nested().Equal(nested()))

	base := NewSchema([]Field{NewField("a", Int32Type{}, false)})
	require.False(t, base.Equal(nil))
	require.False(t, base.Equal(NewSchema([]Field{NewField("b", Int32Type{}, false)})))
	require.False(t, base.Equal(NewSchema([]Field{NewField("a", Int64Type{}, false)})))
	require.False(t, base.Equal(NewSchema([]Field{NewField("a", Int32Type{}, true)})))
	require.False(t, base.Equal(NewSchema(nil)))

	// Composite mismatches must compare structurally, not by pointer.
	require.False(t, NewSchema([]Field{NewField("l", List(Int32Type{}), true)}).
		Equal(NewSchema([]Field{NewField("l", List(Int64Type{}), true)})))
	require.False(t, NewSchema([]Field{NewField("d", DecimalType{Precision: 10, Scale: 2}, true)}).
		Equal(NewSchema([]Field{NewField("d", DecimalType{Precision: 12, Scale: 2}, true)})))
}
