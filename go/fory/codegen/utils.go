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

package codegen

import (
	"fmt"
	"go/types"
	"sort"
	"unicode"

	"github.com/apache/fory/go/fory"
)

// FieldInfo contains metadata about a struct field
type FieldInfo struct {
	GoName        string     // Original Go field name
	SnakeName     string     // snake_case field name for sorting
	Type          types.Type // Go type information
	Index         int        // Original field index in struct
	IsPrimitive   bool       // Whether it's a Fory primitive type
	IsPointer     bool       // Whether it's a pointer type
	IsOptional    bool       // Whether it's a fory optional.Optional[T]
	Nullable      bool       // Whether the field can be null (pointer types)
	TypeID        string     // Fory TypeID for sorting
	PrimitiveSize int        // Size for primitive type sorting
	OptionalElem  types.Type // Element type for optional.Optional[T]
}

// StructInfo contains metadata about a struct to generate code for
type StructInfo struct {
	Name   string
	Fields []*FieldInfo
}

// toSnakeCase converts CamelCase to snake_case
func toSnakeCase(s string) string {
	var result []rune
	for i, r := range s {
		if i > 0 && unicode.IsUpper(r) {
			result = append(result, '_')
		}
		result = append(result, unicode.ToLower(r))
	}
	return string(result)
}

func getOptionalElementType(t types.Type) (types.Type, bool) {
	t = types.Unalias(t)
	named, ok := t.(*types.Named)
	if !ok {
		return nil, false
	}
	obj := named.Obj()
	if obj == nil || obj.Name() != "Optional" {
		return nil, false
	}
	if obj.Pkg() == nil || obj.Pkg().Path() != "github.com/apache/fory/go/fory/optional" {
		return nil, false
	}
	typeArgs := named.TypeArgs()
	if typeArgs == nil || typeArgs.Len() != 1 {
		return nil, false
	}
	return typeArgs.At(0), true
}

// isSupportedFieldType checks if a field type is supported
func isSupportedFieldType(t types.Type) bool {
	// Unwrap alias types (e.g., 'any' is an alias for 'interface{}')
	t = types.Unalias(t)
	if elem, ok := getOptionalElementType(t); ok {
		t = elem
	}

	// Handle pointer types
	if ptr, ok := t.(*types.Pointer); ok {
		t = ptr.Elem()
	}

	// Check slice types
	if slice, ok := t.(*types.Slice); ok {
		// Check if element type is supported
		return isSupportedFieldType(slice.Elem())
	}

	// Check map types
	if mapType, ok := t.(*types.Map); ok {
		// Check if both key and value types are supported
		return isSupportedFieldType(mapType.Key()) && isSupportedFieldType(mapType.Elem())
	}

	// Check named types
	if named, ok := t.(*types.Named); ok {
		typeStr := named.String()
		switch typeStr {
		case "time.Time", "github.com/apache/fory/go/fory.Date":
			return true
		}
		// Check if it's another struct
		if _, ok := named.Underlying().(*types.Struct); ok {
			return true
		}
	}

	// Check interface types
	if iface, ok := t.(*types.Interface); ok {
		// Support empty any for dynamic types
		if iface.Empty() {
			return true
		}
	}

	// Check basic types
	if basic, ok := t.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool, types.Int8, types.Int16, types.Int32, types.Int, types.Int64,
			types.Uint8, types.Uint16, types.Uint32, types.Uint, types.Uint64,
			types.Float32, types.Float64, types.String:
			return true
		}
	}

	return false
}

// isPrimitiveType checks if a type is considered primitive in Fory
func isPrimitiveType(t types.Type) bool {
	// Unwrap alias types
	t = types.Unalias(t)
	if elem, ok := getOptionalElementType(t); ok {
		t = elem
	}

	// Handle pointer types
	if ptr, ok := t.(*types.Pointer); ok {
		t = ptr.Elem()
	}

	// Check basic types
	if basic, ok := t.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool, types.Int8, types.Int16, types.Int32, types.Int, types.Int64,
			types.Uint8, types.Uint16, types.Uint32, types.Uint, types.Uint64,
			types.Float32, types.Float64:
			return true
		}
	}

	// String is NOT considered primitive for sorting purposes (it goes to final group)
	// This matches reflection's behavior where STRING goes to final group, not boxed group

	return false
}

// getTypeID returns the Fory TypeID for a given type
func getTypeID(t types.Type) string {
	// Unwrap alias types
	t = types.Unalias(t)
	if elem, ok := getOptionalElementType(t); ok {
		t = elem
	}

	// Handle pointer types
	if ptr, ok := t.(*types.Pointer); ok {
		t = ptr.Elem()
	}

	// Check slice types - distinguish primitive arrays from generic lists
	if slice, ok := t.(*types.Slice); ok {
		elemType := slice.Elem()
		// For pointer to primitive, unwrap the pointer
		if ptr, ok := elemType.(*types.Pointer); ok {
			elemType = ptr.Elem()
		}
		// Check if element is a primitive type (primitive arrays use specific typeIDs)
		if basic, ok := elemType.Underlying().(*types.Basic); ok {
			switch basic.Kind() {
			case types.Bool:
				return "BOOL_ARRAY"
			case types.Int8:
				return "INT8_ARRAY"
			case types.Int16:
				return "INT16_ARRAY"
			case types.Int32:
				return "INT32_ARRAY"
			case types.Int, types.Int64:
				return "INT64_ARRAY"
			case types.Uint8:
				return "UINT8_ARRAY"
			case types.Uint16:
				return "UINT16_ARRAY"
			case types.Uint32:
				return "UINT32_ARRAY"
			case types.Uint, types.Uint64:
				return "UINT64_ARRAY"
			case types.Float32:
				return "FLOAT32_ARRAY"
			case types.Float64:
				return "FLOAT64_ARRAY"
			}
		}
		// Non-primitive slices use LIST
		return "LIST"
	}

	// Check map types
	if _, ok := t.(*types.Map); ok {
		return "MAP"
	}

	// Check interface types
	if iface, ok := t.(*types.Interface); ok {
		if iface.Empty() {
			return "INTERFACE" // Use a placeholder for empty any
		}
	}

	// Check named types first
	if named, ok := t.(*types.Named); ok {
		typeStr := named.String()
		switch typeStr {
		case "time.Time":
			return "TIMESTAMP"
		case "github.com/apache/fory/go/fory.Date":
			return "DATE"
		}
		// Struct types
		if _, ok := named.Underlying().(*types.Struct); ok {
			return "NAMED_STRUCT"
		}
	}

	// Check basic types
	if basic, ok := t.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool:
			return "BOOL"
		case types.Int8:
			return "INT8"
		case types.Int16:
			return "INT16"
		case types.Int32:
			return "VARINT32"
		case types.Int, types.Int64:
			return "VARINT64"
		case types.Uint8:
			return "UINT8"
		case types.Uint16:
			return "UINT16"
		case types.Uint32:
			return "VAR_UINT32"
		case types.Uint, types.Uint64:
			return "VAR_UINT64"
		case types.Float32:
			return "FLOAT32"
		case types.Float64:
			return "FLOAT64"
		case types.String:
			return "STRING"
		}
	}

	return "UNKNOWN"
}

// getPrimitiveSize returns the byte size of a primitive type
func getPrimitiveSize(t types.Type) int {
	// Unwrap alias types
	t = types.Unalias(t)
	if elem, ok := getOptionalElementType(t); ok {
		t = elem
	}

	// Handle pointer types
	if ptr, ok := t.(*types.Pointer); ok {
		t = ptr.Elem()
	}

	if basic, ok := t.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool, types.Int8, types.Uint8:
			return 1
		case types.Int16, types.Uint16:
			return 2
		case types.Int32, types.Uint32, types.Float32:
			return 4
		case types.Int, types.Int64, types.Uint, types.Uint64, types.Float64:
			return 8
		case types.String:
			return 999 // Variable size, sort last among primitives
		}
	}

	return 0
}

// getTypeIDValue returns numeric value for type ID for sorting
// This uses the actual Fory TypeId constants for accuracy
func getTypeIDValue(typeID string) int {
	switch typeID {
	case "BOOL":
		return int(fory.BOOL) // 1
	case "INT8":
		return int(fory.INT8) // 2
	case "INT16":
		return int(fory.INT16) // 3
	case "INT32":
		return int(fory.INT32) // 4
	case "VARINT32":
		return int(fory.VARINT32) // 5
	case "INT64":
		return int(fory.INT64) // 6
	case "VARINT64":
		return int(fory.VARINT64) // 7
	case "UINT8":
		return int(fory.UINT8) // 9
	case "UINT16":
		return int(fory.UINT16) // 10
	case "UINT32":
		return int(fory.UINT32) // 11
	case "VAR_UINT32":
		return int(fory.VAR_UINT32) // 12
	case "UINT64":
		return int(fory.UINT64) // 13
	case "VAR_UINT64":
		return int(fory.VAR_UINT64) // 14
	case "FLOAT32":
		return int(fory.FLOAT32)
	case "FLOAT64":
		return int(fory.FLOAT64)
	case "STRING":
		return int(fory.STRING) // 9
	case "BINARY":
		return int(fory.BINARY) // 10
	case "LIST":
		return int(fory.LIST) // 20
	case "SET":
		return int(fory.SET) // 21
	case "MAP":
		return int(fory.MAP) // 22
	case "TIMESTAMP":
		return int(fory.TIMESTAMP) // 25
	case "DATE":
		return int(fory.DATE) // 26
	case "NAMED_STRUCT":
		return int(fory.NAMED_STRUCT) // 17
	// Primitive array types
	case "BOOL_ARRAY":
		return int(fory.BOOL_ARRAY) // 39
	case "INT8_ARRAY":
		return int(fory.INT8_ARRAY) // 40
	case "INT16_ARRAY":
		return int(fory.INT16_ARRAY) // 41
	case "INT32_ARRAY":
		return int(fory.INT32_ARRAY) // 42
	case "INT64_ARRAY":
		return int(fory.INT64_ARRAY) // 43
	case "UINT8_ARRAY":
		return int(fory.UINT8_ARRAY) // 44
	case "UINT16_ARRAY":
		return int(fory.UINT16_ARRAY) // 45
	case "UINT32_ARRAY":
		return int(fory.UINT32_ARRAY) // 46
	case "UINT64_ARRAY":
		return int(fory.UINT64_ARRAY) // 47
	case "FLOAT32_ARRAY":
		return int(fory.FLOAT32_ARRAY) // 49
	case "FLOAT64_ARRAY":
		return int(fory.FLOAT64_ARRAY) // 50
	default:
		return 999 // Unknown types sort last
	}
}

// sortFields sorts fields according to Fory protocol specification
// This matches the new field ordering specification for cross-language compatibility
func sortFields(fields []*FieldInfo) {
	sort.Slice(fields, func(i, j int) bool {
		f1, f2 := fields[i], fields[j]

		// Categorize fields into groups
		group1 := getFieldGroup(f1)
		group2 := getFieldGroup(f2)

		// Sort by group first
		if group1 != group2 {
			return group1 < group2
		}

		// Within same group, apply group-specific sorting
		switch group1 {
		case groupPrimitive:
			// Primitive fields: larger size first, smaller later, variable size last
			// When same size, sort by type id
			// When same size and type id, sort by snake case field name

			// Handle compression types (INT32/INT64/VARINT32/VARINT64 and unsigned variants)
			compressI := f1.TypeID == "INT32" || f1.TypeID == "INT64" ||
				f1.TypeID == "VARINT32" || f1.TypeID == "VARINT64" ||
				f1.TypeID == "UINT32" || f1.TypeID == "UINT64" ||
				f1.TypeID == "VAR_UINT32" || f1.TypeID == "VAR_UINT64"
			compressJ := f2.TypeID == "INT32" || f2.TypeID == "INT64" ||
				f2.TypeID == "VARINT32" || f2.TypeID == "VARINT64" ||
				f2.TypeID == "UINT32" || f2.TypeID == "UINT64" ||
				f2.TypeID == "VAR_UINT32" || f2.TypeID == "VAR_UINT64"

			if compressI != compressJ {
				return !compressI && compressJ // non-compress comes first
			}

			// Sort by size (descending)
			if f1.PrimitiveSize != f2.PrimitiveSize {
				return f1.PrimitiveSize > f2.PrimitiveSize
			}

			// Sort by type ID
			if f1.TypeID != f2.TypeID {
				return getTypeIDValue(f1.TypeID) < getTypeIDValue(f2.TypeID)
			}

			// Finally by name
			return f1.SnakeName < f2.SnakeName

		case groupInternalBuiltin, groupListSet, groupMap:
			// Built-in or collection types: sort by type id then name only.
			// Java does NOT sort by nullable flag for these types.
			if f1.TypeID != f2.TypeID {
				return getTypeIDValue(f1.TypeID) < getTypeIDValue(f2.TypeID)
			}
			return f1.SnakeName < f2.SnakeName

		case groupOther:
			// Other fields: sort by snake case field name only
			return f1.SnakeName < f2.SnakeName

		default:
			// Fallback: sort by name
			return f1.SnakeName < f2.SnakeName
		}
	})
}

// Field group constants for sorting
// This matches reflection's field ordering in field_info.go:
// primitives → internal built-in → list/set → map → other
const (
	groupPrimitive       = 0 // primitive and nullable primitive fields
	groupInternalBuiltin = 1 // built-in types (STRING/BINARY/arrays/etc.) sorted by typeId then name
	groupListSet         = 2 // LIST/SET sorted by typeId then name
	groupMap             = 3 // MAP sorted by typeId then name
	groupOther           = 4 // structs, enums, and unknown types - sorted by name
)

// getFieldGroup categorizes a field into its sorting group
func getFieldGroup(field *FieldInfo) int {
	// Primitive fields (including nullable primitives)
	// types: bool/int8/int16/int32/varint32/int64/varint64/sliint64/float16/float32/float64
	if field.IsPrimitive {
		return groupPrimitive
	}
	typeID := field.TypeID
	if typeID == "LIST" || typeID == "SET" {
		return groupListSet
	}
	if typeID == "MAP" {
		return groupMap
	}
	// Unknown or user-defined types go to "other"
	switch typeID {
	case "UNKNOWN",
		"NAMED_STRUCT",
		"NAMED_COMPATIBLE_STRUCT",
		"STRUCT",
		"COMPATIBLE_STRUCT",
		"EXT",
		"NAMED_EXT",
		"ENUM",
		"NAMED_ENUM",
		"INTERFACE":
		return groupOther
	}
	// Everything else is a built-in type (STRING/BINARY/arrays/TIMESTAMP/etc.)
	return groupInternalBuiltin
}

// getStructNames extracts struct names from StructInfo slice
func getStructNames(structs []*StructInfo) []string {
	names := make([]string, len(structs))
	for i, s := range structs {
		names[i] = s.Name
	}
	return names
}

// analyzeField analyzes a struct field and creates FieldInfo
func analyzeField(field *types.Var, index int) (*FieldInfo, error) {
	fieldType := field.Type()
	goName := field.Name()
	snakeName := toSnakeCase(goName)

	// Check if field type is supported
	if !isSupportedFieldType(fieldType) {
		return nil, nil // Skip unsupported types
	}

	optionalElem, isOptional := getOptionalElementType(fieldType)
	if isOptional && optionalElem != nil {
		if ptr, ok := optionalElem.(*types.Pointer); ok {
			switch ptr.Elem().Underlying().(type) {
			case *types.Slice, *types.Map:
				return nil, fmt.Errorf("field %s: optional.Optional is not allowed for slice/map", goName)
			}
		} else {
			switch optionalElem.Underlying().(type) {
			case *types.Struct, *types.Slice, *types.Map:
				return nil, fmt.Errorf("field %s: optional.Optional is not allowed for struct/slice/map", goName)
			}
		}
	}

	// Analyze type information
	isPrimitive := isPrimitiveType(fieldType)
	isPointer := false
	typeID := getTypeID(fieldType)
	primitiveSize := getPrimitiveSize(fieldType)

	// Handle pointer types
	if ptr, ok := fieldType.(*types.Pointer); ok {
		isPointer = true
		fieldType = ptr.Elem()
		isPrimitive = isPrimitiveType(fieldType)
		typeID = getTypeID(fieldType)
		primitiveSize = getPrimitiveSize(fieldType)
	}

	return &FieldInfo{
		GoName:        goName,
		SnakeName:     snakeName,
		Type:          field.Type(),
		Index:         index,
		IsPrimitive:   isPrimitive,
		IsPointer:     isPointer,
		IsOptional:    isOptional,
		Nullable:      isPointer || isOptional, // Pointer and optional types are nullable in xlang mode
		TypeID:        typeID,
		PrimitiveSize: primitiveSize,
		OptionalElem:  optionalElem,
	}, nil
}
