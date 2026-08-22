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
	"reflect"
	"sort"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	fory "github.com/apache/fory/go/fory"
)

var (
	goDateType     = reflect.TypeOf(fory.Date{})
	goTimeType     = reflect.TypeOf(time.Time{})
	goDurationType = reflect.TypeOf(time.Duration(0))
)

// InferSchema infers the row schema for a struct type or pointer to
// struct, including every exported field not ignored by its fory tag
// (`fory:"-"`, `fory:"ignore"`, or `fory:"ignore=true"`).
//
// Fields are sorted by their lowerCamel name and named by its
// snake_case form (UserName -> user_name), matching Java's schema
// inference so both languages derive identical schemas.
//
// Type mapping:
//   - bool, int8/16/32/64, float32/64: same-width row types, non-nullable;
//     int maps to int64
//   - string: string, nullable
//   - []byte: list<int8>, nullable, matching Java's byte[] (the row
//     format's binary type is reachable only through explicit schemas)
//   - fory.Date, time.Time, time.Duration: date32, timestamp, duration,
//     nullable (their Java carriers are objects)
//   - slices, maps, nested structs: list, map, and struct, nullable;
//     map values are always nullable, map keys never
//   - *T: the row type of T, nullable
//
// A nullable field whose Go carrier cannot hold nil (a scalar, string,
// temporal, or value struct) still decodes, but a null value for it is
// a decode error; use a pointer carrier when nulls must round-trip.
//
// Unsupported: unsigned integers, fixed-size arrays, nested pointers,
// pointers to slices or maps (two nil states, one null bit), map keys
// whose encoded fields do not determine Go equality (pointers,
// time.Time, structs with unexported or ignored fields), and recursive
// types.
func InferSchema(t reflect.Type) (*Schema, error) {
	if t == nil {
		return nil, fmt.Errorf("row: cannot infer a schema from a nil type")
	}
	layout, err := inferStructLayout(t, nil)
	if err != nil {
		return nil, err
	}
	return layout.schema, nil
}

// structLayout maps schema ordinals back to Go struct field indexes.
type structLayout struct {
	schema  *Schema
	indexes []int // schema ordinal -> reflect struct field index
}

func inferStructLayout(t reflect.Type, path []reflect.Type) (*structLayout, error) {
	if t.Kind() == reflect.Ptr {
		t = t.Elem()
	}
	if t.Kind() != reflect.Struct || t == goDateType || t == goTimeType {
		return nil, fmt.Errorf("row: schema inference expects a struct type, got %v", t)
	}
	if err := checkCycle(t, path); err != nil {
		return nil, err
	}
	path = append(path, t)

	type member struct {
		lowerCamel string
		goIndex    int
		fieldType  reflect.Type
	}
	var members []member
	for i := 0; i < t.NumField(); i++ {
		f := t.Field(i)
		if f.PkgPath != "" {
			continue
		}
		ignored, err := hasIgnoreTag(f.Tag.Get("fory"))
		if err != nil {
			return nil, fmt.Errorf("%w (field %s of %v)", err, f.Name, t)
		}
		if ignored {
			continue
		}
		members = append(members, member{lowerFirst(f.Name), i, f.Type})
	}
	// Java sorts by the lowerCamel member name before converting names
	// to snake_case; matching that order is required for schema and
	// row-layout compatibility.
	sort.Slice(members, func(a, b int) bool { return members[a].lowerCamel < members[b].lowerCamel })

	layout := &structLayout{indexes: make([]int, 0, len(members))}
	fields := make([]Field, 0, len(members))
	for _, m := range members {
		f, err := inferField(lowerCamelToLowerUnderscore(m.lowerCamel), m.fieldType, path)
		if err != nil {
			return nil, fmt.Errorf("%w (field %s of %v)", err, t.Field(m.goIndex).Name, t)
		}
		fields = append(fields, f)
		layout.indexes = append(layout.indexes, m.goIndex)
	}
	layout.schema = NewSchema(fields)
	return layout, nil
}

// checkCycle rejects a type already on the active inference path. Named
// slice and map types can recurse just like structs (type L []L), so
// every composite type is tracked, not only structs.
func checkCycle(t reflect.Type, path []reflect.Type) error {
	for _, seen := range path {
		if seen == t {
			return fmt.Errorf("row: circular reference through type %v", t)
		}
	}
	return nil
}

func inferField(name string, t reflect.Type, path []reflect.Type) (Field, error) {
	if t.Kind() == reflect.Ptr {
		switch t.Elem().Kind() {
		case reflect.Ptr:
			return Field{}, fmt.Errorf("row: nested pointer type %v is unsupported", t)
		case reflect.Slice, reflect.Map:
			// A nil pointer and a pointer to a nil container would share
			// one null bit, so the value could not round-trip.
			return Field{}, fmt.Errorf("row: pointer to slice or map type %v is unsupported; use %v", t, t.Elem())
		}
		inner, err := inferField(name, t.Elem(), path)
		if err != nil {
			return Field{}, err
		}
		inner.Nullable = true
		return inner, nil
	}
	switch t {
	case goDateType:
		return Field{Name: name, Type: Date32Type{}, Nullable: true}, nil
	case goTimeType:
		return Field{Name: name, Type: TimestampType{}, Nullable: true}, nil
	case goDurationType:
		return Field{Name: name, Type: DurationType{}, Nullable: true}, nil
	}
	switch t.Kind() {
	case reflect.Bool:
		return Field{Name: name, Type: BoolType{}}, nil
	case reflect.Int8:
		return Field{Name: name, Type: Int8Type{}}, nil
	case reflect.Int16:
		return Field{Name: name, Type: Int16Type{}}, nil
	case reflect.Int32:
		return Field{Name: name, Type: Int32Type{}}, nil
	case reflect.Int64, reflect.Int:
		// `int` maps to int64 so the width never depends on the platform.
		return Field{Name: name, Type: Int64Type{}}, nil
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Uintptr:
		return Field{}, fmt.Errorf("row: unsigned type %v is unsupported, use a signed type", t)
	case reflect.Float32:
		return Field{Name: name, Type: Float32Type{}}, nil
	case reflect.Float64:
		return Field{Name: name, Type: Float64Type{}}, nil
	case reflect.String:
		return Field{Name: name, Type: StringType{}, Nullable: true}, nil
	case reflect.Slice:
		if t.Elem().Kind() == reflect.Uint8 {
			// Java infers byte[] as list<int8>; the encoder copies the
			// bytes straight into the element region.
			elem := Field{Name: listItemName, Type: Int8Type{}}
			return Field{Name: name, Type: &ListType{Elem: elem}, Nullable: true}, nil
		}
		if err := checkCycle(t, path); err != nil {
			return Field{}, err
		}
		elem, err := inferField(listItemName, t.Elem(), append(path, t))
		if err != nil {
			return Field{}, err
		}
		return Field{Name: name, Type: &ListType{Elem: elem}, Nullable: true}, nil
	case reflect.Map:
		if err := checkCycle(t, path); err != nil {
			return Field{}, err
		}
		if err := validateMapKeyType(t.Key()); err != nil {
			return Field{}, err
		}
		path = append(path, t)
		key, err := inferField(mapKeyName, t.Key(), path)
		if err != nil {
			return Field{}, err
		}
		value, err := inferField(mapValueName, t.Elem(), path)
		if err != nil {
			return Field{}, err
		}
		// Canonical map children, matching Java MapType and the schema
		// parser: keys are never nullable, values always are.
		key.Nullable = false
		value.Nullable = true
		return Field{Name: name, Type: &MapType{Key: key, Value: value}, Nullable: true}, nil
	case reflect.Struct:
		layout, err := inferStructLayout(t, path)
		if err != nil {
			return Field{}, err
		}
		return Field{Name: name, Type: &StructType{Fields: layout.schema.Fields()}, Nullable: true}, nil
	default:
		return Field{}, fmt.Errorf("row: type %v is unsupported in row format", t)
	}
}

// validateMapKeyType accepts only key types whose encoded fields fully
// determine Go equality, so distinct keys never encode identically and
// decoding never collapses entries: scalars, strings, and structs made
// only of such fields with nothing unexported or ignored. time.Time is
// rejected because its Location is not encoded.
func validateMapKeyType(t reflect.Type) error {
	switch t.Kind() {
	case reflect.Bool, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64, reflect.Int,
		reflect.Float32, reflect.Float64, reflect.String:
		return nil
	case reflect.Struct:
		if t == goTimeType {
			return fmt.Errorf("row: map key type %v is unsupported: its Location is not encoded", t)
		}
		for i := 0; i < t.NumField(); i++ {
			f := t.Field(i)
			if f.PkgPath != "" {
				return fmt.Errorf("row: map key type %v has unexported field %s that would not be encoded", t, f.Name)
			}
			ignored, err := hasIgnoreTag(f.Tag.Get("fory"))
			if err != nil {
				return err
			}
			if ignored {
				return fmt.Errorf("row: map key type %v has ignored field %s that would not be encoded", t, f.Name)
			}
			if err := validateMapKeyType(f.Type); err != nil {
				return err
			}
		}
		return nil
	default:
		return fmt.Errorf("row: map key type %v cannot preserve Go equality in row format", t)
	}
}

// Keys accepted by the core fory tag parser; the row format acts only
// on "ignore" but mirrors the grammar so a tag valid for object-graph
// serialization is valid here and vice versa.
var foryTagKeys = map[string]bool{
	"id": true, "nullable": true, "ref": true, "ignore": true, "encoding": true, "type": true,
}

// hasIgnoreTag mirrors the core fory tag grammar (parseFieldTag in
// field_spec.go): a whole tag of "-" ignores the field; otherwise parts
// split on top-level commas, keys and values are trimmed around '=',
// duplicate keys are errors, and ignore accepts the strict boolean forms
// true/1/yes and false/0/no (case-insensitive), defaulting to true.
func hasIgnoreTag(tag string) (bool, error) {
	if tag == "-" {
		return true, nil
	}
	ignore := false
	seen := map[string]bool{}
	for _, part := range splitTopLevel(tag) {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		key, value, hasValue := part, "", false
		if idx := indexTopLevel(part, '='); idx >= 0 {
			key, value, hasValue = strings.TrimSpace(part[:idx]), strings.TrimSpace(part[idx+1:]), true
		}
		if !foryTagKeys[key] {
			return false, fmt.Errorf("row: unknown fory tag key %q", key)
		}
		if seen[key] {
			return false, fmt.Errorf("row: duplicate fory tag key %q", key)
		}
		seen[key] = true
		if key != "ignore" {
			continue
		}
		if !hasValue {
			ignore = true
			continue
		}
		switch strings.ToLower(value) {
		case "true", "1", "yes":
			ignore = true
		case "false", "0", "no":
			ignore = false
		default:
			return false, fmt.Errorf("row: invalid ignore value %q in fory tag", value)
		}
	}
	return ignore, nil
}

// splitTopLevel splits on commas outside parentheses, so nested type
// hints such as type=map(key=string,value=int32) stay intact.
func splitTopLevel(input string) []string {
	var parts []string
	depth, start := 0, 0
	for i := 0; i < len(input); i++ {
		switch input[i] {
		case '(':
			depth++
		case ')':
			if depth > 0 {
				depth--
			}
		case ',':
			if depth == 0 {
				parts = append(parts, input[start:i])
				start = i + 1
			}
		}
	}
	return append(parts, input[start:])
}

func indexTopLevel(input string, target byte) int {
	depth := 0
	for i := 0; i < len(input); i++ {
		switch input[i] {
		case '(':
			depth++
		case ')':
			if depth > 0 {
				depth--
			}
		default:
			if depth == 0 && input[i] == target {
				return i
			}
		}
	}
	return -1
}

func lowerFirst(s string) string {
	r, size := utf8.DecodeRuneInString(s)
	if !unicode.IsUpper(r) {
		return s
	}
	return string(unicode.ToLower(r)) + s[size:]
}

// lowerCamelToLowerUnderscore ports Java StringUtils: every uppercase
// letter becomes '_' plus its lowercase, so userID becomes user_i_d.
func lowerCamelToLowerUnderscore(s string) string {
	var b strings.Builder
	from := 0
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c >= 'A' && c <= 'Z' {
			b.WriteString(s[from:i])
			b.WriteByte('_')
			b.WriteByte(c + ('a' - 'A'))
			from = i + 1
		}
	}
	if from == 0 {
		return s
	}
	if from < len(s) {
		b.WriteString(s[from:])
	}
	return b.String()
}
