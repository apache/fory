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
// struct, including every exported field not tagged `fory:"ignore"`.
//
// Fields are sorted by their lowerCamel name and named by its
// snake_case form (UserName -> user_name), matching Java's schema
// inference so both languages derive identical schemas.
//
// Type mapping:
//   - bool, int8/16/32/64, float32/64: same-width row types; int maps to int64
//   - string, []byte: string and binary, nullable
//   - slices, maps, nested structs: list, map, and struct, nullable
//   - *T: the row type of T, nullable
//   - fory.Date, time.Time, time.Duration: date32, timestamp, duration
//
// Unsigned integers, fixed-size arrays, nested pointers, and pointer
// map keys are unsupported and return an error.
func InferSchema(t reflect.Type) (*Schema, error) {
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
	for _, seen := range path {
		if seen == t {
			return nil, fmt.Errorf("row: circular reference through type %v", t)
		}
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

func inferField(name string, t reflect.Type, path []reflect.Type) (Field, error) {
	if t.Kind() == reflect.Ptr {
		if t.Elem().Kind() == reflect.Ptr {
			return Field{}, fmt.Errorf("row: nested pointer type %v is unsupported", t)
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
		return Field{Name: name, Type: Date32Type{}}, nil
	case goTimeType:
		return Field{Name: name, Type: TimestampType{}}, nil
	case goDurationType:
		return Field{Name: name, Type: DurationType{}}, nil
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
			return Field{Name: name, Type: BinaryType{}, Nullable: true}, nil
		}
		elem, err := inferField(listItemName, t.Elem(), path)
		if err != nil {
			return Field{}, err
		}
		return Field{Name: name, Type: &ListType{Elem: elem}, Nullable: true}, nil
	case reflect.Map:
		if t.Key().Kind() == reflect.Ptr {
			return Field{}, fmt.Errorf("row: pointer map key type %v is unsupported", t)
		}
		key, err := inferField(mapKeyName, t.Key(), path)
		if err != nil {
			return Field{}, err
		}
		key.Nullable = false
		value, err := inferField(mapValueName, t.Elem(), path)
		if err != nil {
			return Field{}, err
		}
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

// hasIgnoreTag mirrors the ignore semantics of the core fory tag
// parser (parseFieldTag in field_spec.go): a whole tag of "-" or an
// ignore/ignore=true part skips the field, ignore=false keeps it, and
// any other ignore value is an error. Other tag keys are not used by
// the row format.
func hasIgnoreTag(tag string) (bool, error) {
	if tag == "-" {
		return true, nil
	}
	ignore := false
	for _, part := range strings.Split(tag, ",") {
		part = strings.TrimSpace(part)
		if part == "ignore" {
			ignore = true
		} else if strings.HasPrefix(part, "ignore=") {
			switch strings.TrimPrefix(part, "ignore=") {
			case "true":
				ignore = true
			case "false":
				ignore = false
			default:
				return false, fmt.Errorf("row: invalid ignore value in fory tag %q", tag)
			}
		}
	}
	return ignore, nil
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
