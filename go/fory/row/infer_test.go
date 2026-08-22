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
	"reflect"
	"testing"
	"time"

	fory "github.com/apache/fory/go/fory"
	"github.com/stretchr/testify/require"
)

func TestLowerCamelToLowerUnderscore(t *testing.T) {
	cases := map[string]string{
		"id":        "id",
		"userName":  "user_name",
		"userID":    "user_i_d", // Java quirk, kept for parity
		"f2":        "f2",
		"aVeryLong": "a_very_long",
	}
	for in, want := range cases {
		require.Equal(t, want, lowerCamelToLowerUnderscore(in), in)
	}
}

// Fields sort by lowerCamel name before snake_case conversion,
// matching Java's Descriptor ordering.
func TestInferFieldOrder(t *testing.T) {
	type ordered struct {
		ZItem     int64
		AlphaBeta string
		M2        int32
	}
	s, err := InferSchema(reflect.TypeOf(ordered{}))
	require.NoError(t, err)
	require.Equal(t, 3, s.NumFields())
	require.Equal(t, "alpha_beta", s.Field(0).Name)
	require.Equal(t, "m2", s.Field(1).Name)
	require.Equal(t, "z_item", s.Field(2).Name)
}

func TestInferTypeMapping(t *testing.T) {
	type inner struct {
		X float64
	}
	type sample struct {
		B    bool
		I8   int8
		I16  int16
		I32  int32
		I64  int64
		I    int
		F32  float32
		F64  float64
		S    string
		Bin  []byte
		L    []int32
		M    map[string]int64
		In   inner
		PI32 *int32
		PIn  *inner
		D    fory.Date
		T    time.Time
		Dur  time.Duration
	}
	s, err := InferSchema(reflect.TypeOf(sample{}))
	require.NoError(t, err)

	byName := func(name string) Field { return s.Field(s.FieldIndex(name)) }

	require.Equal(t, BoolType{}, byName("b").Type)
	require.False(t, byName("b").Nullable)
	require.Equal(t, Int8Type{}, byName("i8").Type)
	require.Equal(t, Int16Type{}, byName("i16").Type)
	require.Equal(t, Int32Type{}, byName("i32").Type)
	require.Equal(t, Int64Type{}, byName("i64").Type)
	require.Equal(t, Int64Type{}, byName("i").Type, "int maps to int64")
	require.Equal(t, Float32Type{}, byName("f32").Type)
	require.Equal(t, Float64Type{}, byName("f64").Type)

	require.Equal(t, StringType{}, byName("s").Type)
	require.True(t, byName("s").Nullable)
	bin := byName("bin").Type.(*ListType)
	require.Equal(t, Int8Type{}, bin.Elem.Type, "[]byte is list<int8>, the Java byte[] model")
	require.False(t, bin.Elem.Nullable)
	require.True(t, byName("bin").Nullable)

	list := byName("l").Type.(*ListType)
	require.Equal(t, Int32Type{}, list.Elem.Type)
	require.False(t, list.Elem.Nullable, "value-type slice elements cannot be null")
	require.True(t, byName("l").Nullable)

	m := byName("m").Type.(*MapType)
	require.Equal(t, StringType{}, m.Key.Type)
	require.False(t, m.Key.Nullable)
	require.Equal(t, Int64Type{}, m.Value.Type)
	require.True(t, m.Value.Nullable, "map values are canonically nullable, as in Java and the schema parser")

	in := byName("in").Type.(*StructType)
	require.Equal(t, "x", in.Fields[0].Name)
	require.True(t, byName("in").Nullable)

	require.Equal(t, Int32Type{}, byName("p_i32").Type)
	require.True(t, byName("p_i32").Nullable, "pointer fields are nullable")
	require.True(t, byName("p_in").Nullable)

	require.Equal(t, Date32Type{}, byName("d").Type)
	require.Equal(t, TimestampType{}, byName("t").Type)
	require.Equal(t, DurationType{}, byName("dur").Type)
	for _, name := range []string{"d", "t", "dur"} {
		require.True(t, byName(name).Nullable, "%s: Java temporal carriers are nullable objects", name)
	}
}

// Tag grammar must match the core fory tag parser: "-", "ignore",
// ignore=true/1/yes skip (case-insensitive, whitespace around '='
// allowed); ignore=false/0/no keeps; other core keys are accepted and
// ignored; unknown keys, duplicate keys, and bad values are errors.
func TestInferSkipsIgnoredAndUnexported(t *testing.T) {
	type tagged struct {
		A       int64
		hidden  int64
		Skipped string `fory:"ignore"`
		Dash    string `fory:"-"`
		Yes     string `fory:"ignore = YES"`
		One     string `fory:"id=3, ignore=1"`
		Kept    int32  `fory:"ignore=false"`
		Kept2   int32  `fory:"nullable=true, ignore=no"`
		Kept3   int32  `fory:"type=map(key=string,value=int32)"`
	}
	s, err := InferSchema(reflect.TypeOf(tagged{}))
	require.NoError(t, err)
	require.Equal(t, 4, s.NumFields())
	require.Equal(t, "a", s.Field(0).Name)
	require.Equal(t, "kept", s.Field(1).Name)
	require.Equal(t, "kept2", s.Field(2).Name)
	require.Equal(t, "kept3", s.Field(3).Name)

	for _, bad := range []any{
		struct {
			A int64 `fory:"ignore=maybe"`
		}{},
		struct {
			A int64 `fory:"ignore, ignore"`
		}{},
		struct {
			A int64 `fory:"bogus=1"`
		}{},
	} {
		_, err := InferSchema(reflect.TypeOf(bad))
		require.Error(t, err, "%T", bad)
	}
}

func TestInferRejectsUnsupportedTypes(t *testing.T) {
	type node struct {
		Next *node
	}
	type recursiveList []recursiveList
	type recursiveMap map[string]recursiveMap
	type keyWithHidden struct {
		A int32
		b int32
	}
	cases := []any{
		struct{ U uint32 }{},
		struct{ C chan int }{},
		struct{ A [3]int32 }{},
		struct{ PP **int32 }{},
		struct{ PS *[]int32 }{},
		struct{ PM *map[string]int32 }{},
		struct{ PB *[]byte }{},
		struct{ M map[*string]int32 }{},
		struct{ M map[keyWithHidden]int32 }{},
		struct{ M map[time.Time]int32 }{},
		struct{ M map[[2]int32]int32 }{},
		node{},
		struct{ L recursiveList }{},
		struct{ M recursiveMap }{},
	}
	for _, c := range cases {
		_, err := InferSchema(reflect.TypeOf(c))
		require.Error(t, err, "%T", c)
	}
	_, err := InferSchema(reflect.TypeOf(42))
	require.Error(t, err)
	_, err = InferSchema(nil)
	require.Error(t, err)
}

// Map keys whose encoded fields fully determine Go equality are
// accepted, including value structs of such fields.
func TestInferAcceptsEqualityPreservingMapKeys(t *testing.T) {
	type point struct {
		X, Y int32
	}
	type keyed struct {
		ByDate  map[fory.Date]string
		ByPoint map[point]string
		ByDur   map[time.Duration]string
	}
	_, err := InferSchema(reflect.TypeOf(keyed{}))
	require.NoError(t, err)
}
