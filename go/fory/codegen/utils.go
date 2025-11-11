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
	"strings"
	"unicode"

	"github.com/apache/fory/go/fory"
	"github.com/spaolacci/murmur3"
)

// FieldInfo contains metadata about a struct field
type FieldInfo struct {
	GoName        string     // Original Go field name
	SnakeName     string     // snake_case field name for sorting
	Type          types.Type // Go type information
	Index         int        // Original field index in struct
	IsPrimitive   bool       // Whether it's a Fory primitive type
	IsPointer     bool       // Whether it's a pointer type
	TypeID        string     // Fory TypeID for sorting
	PrimitiveSize int        // Size for primitive type sorting
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

// isSupportedFieldType checks if a field type is supported
func isSupportedFieldType(t types.Type) bool {
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
		// Support empty interface{} for dynamic types
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
	// Handle pointer types
	if ptr, ok := t.(*types.Pointer); ok {
		t = ptr.Elem()
	}

	// Check slice types
	if _, ok := t.(*types.Slice); ok {
		return "LIST"
	}

	// Check map types
	if _, ok := t.(*types.Map); ok {
		return "MAP"
	}

	// Check interface types
	if iface, ok := t.(*types.Interface); ok {
		if iface.Empty() {
			return "INTERFACE" // Use a placeholder for empty interface{}
		}
	}

	// Check named types first
	if named, ok := t.(*types.Named); ok {
		typeStr := named.String()
		switch typeStr {
		case "time.Time":
			return "TIMESTAMP"
		case "github.com/apache/fory/go/fory.Date":
			return "LOCAL_DATE"
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
			return "INT32"
		case types.Int, types.Int64:
			return "INT64"
		case types.Uint8:
			return "UINT8"
		case types.Uint16:
			return "UINT16"
		case types.Uint32:
			return "UINT32"
		case types.Uint, types.Uint64:
			return "UINT64"
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
	case "INT64":
		return int(fory.INT64) // 6
	case "UINT8":
		return int(fory.UINT8) // 100
	case "UINT16":
		return int(fory.UINT16) // 101
	case "UINT32":
		return int(fory.UINT32) // 102
	case "UINT64":
		return int(fory.UINT64) // 103
	case "FLOAT32":
		return int(fory.FLOAT) // 10
	case "FLOAT64":
		return int(fory.DOUBLE) // 11
	case "STRING":
		return int(fory.STRING) // 12
	case "TIMESTAMP":
		return int(fory.TIMESTAMP) // 25
	case "LOCAL_DATE":
		return int(fory.LOCAL_DATE) // 26
	case "NAMED_STRUCT":
		return int(fory.NAMED_STRUCT) // 17
	case "LIST":
		return int(fory.LIST) // 21
	case "MAP":
		return int(fory.MAP) // 23
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

			// Handle compression types (INT32/INT64/VAR_INT32/VAR_INT64)
			compressI := f1.TypeID == "INT32" || f1.TypeID == "INT64" ||
				f1.TypeID == "VAR_INT32" || f1.TypeID == "VAR_INT64"
			compressJ := f2.TypeID == "INT32" || f2.TypeID == "INT64" ||
				f2.TypeID == "VAR_INT32" || f2.TypeID == "VAR_INT64"

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

		case groupOtherInternalType:
			// Other internal type fields: sort by type id then snake case field name
			if f1.TypeID != f2.TypeID {
				return getTypeIDValue(f1.TypeID) < getTypeIDValue(f2.TypeID)
			}
			return f1.SnakeName < f2.SnakeName

		case groupList, groupSet, groupMap, groupOther:
			// List/Set/Map/Other fields: sort by snake case field name only
			return f1.SnakeName < f2.SnakeName

		default:
			// Fallback: sort by name
			return f1.SnakeName < f2.SnakeName
		}
	})
}

// Field group constants for sorting
const (
	groupPrimitive         = 0 // primitive and nullable primitive fields
	groupOtherInternalType = 1 // other internal type fields (string, timestamp, etc.)
	groupList              = 2 // list fields
	groupSet               = 3 // set fields
	groupMap               = 4 // map fields
	groupOther             = 5 // other fields
)

// getFieldGroup categorizes a field into its sorting group
func getFieldGroup(field *FieldInfo) int {
	typeID := field.TypeID

	// Primitive fields (including nullable primitives)
	// types: bool/int8/int16/int32/varint32/int64/varint64/sliint64/float16/float32/float64
	if field.IsPrimitive {
		return groupPrimitive
	}

	// List fields
	if typeID == "LIST" {
		return groupList
	}

	// Set fields
	if typeID == "SET" {
		return groupSet
	}

	// Map fields
	if typeID == "MAP" {
		return groupMap
	}

	// Other internal type fields
	// These are fory internal types that are not primitives/lists/sets/maps
	// Examples: STRING, TIMESTAMP, LOCAL_DATE, NAMED_STRUCT, etc.
	internalTypes := map[string]bool{
		"STRING":       true,
		"TIMESTAMP":    true,
		"LOCAL_DATE":   true,
		"NAMED_STRUCT": true,
		"STRUCT":       true,
		"BINARY":       true,
		"ENUM":         true,
		"NAMED_ENUM":   true,
		"EXT":          true,
		"NAMED_EXT":    true,
		"INTERFACE":    true, // for interface{} types
	}

	if internalTypes[typeID] {
		return groupOtherInternalType
	}

	// Everything else goes to "other fields"
	return groupOther
}

// computeStructHash computes a hash for struct schema compatibility
// This implementation follows the new xlang serialization spec:
// 1. Sort fields by fields sort algorithm (already done in s.Fields)
// 2. Build string: snake_case(field_name),$type_id,$nullable;
// 3. For "other fields", use TypeId::UNKNOWN
// 4. Convert to UTF8 bytes
// 5. Compute murmurhash3_x64_128, use first 32 bits
func computeStructHash(s *StructInfo) int32 {
	var hashString strings.Builder

	// Iterate through sorted fields
	for _, field := range s.Fields {
		// Append snake_case field name
		hashString.WriteString(field.SnakeName)
		hashString.WriteString(",")

		// Append type_id
		typeID := getTypeIDForHash(field)
		hashString.WriteString(fmt.Sprintf("%d", typeID))
		hashString.WriteString(",")

		// Append nullable (1 if nullable, 0 otherwise)
		// nullable is determined by field type (matching reflection's nullable() function)
		nullable := 0
		if isNullableType(field.Type) {
			nullable = 1
		}
		hashString.WriteString(fmt.Sprintf("%d", nullable))
		hashString.WriteString(";")
	}

	// Convert to UTF8 bytes
	hashBytes := []byte(hashString.String())

	// Compute murmurhash3_x64_128 with seed 47, and use first 32 bits
	// This matches the reflection implementation
	h1, _ := murmur3.Sum128WithSeed(hashBytes, 47)
	hash := int32(h1 & 0xFFFFFFFF)

	if hash == 0 {
		panic(fmt.Errorf("hash for type %v is 0", s.Name))
	}

	return hash
}

// isNullableType checks if a type is nullable (referencable)
// This matches the reflection implementation's nullable() function
func isNullableType(t types.Type) bool {
	// Check pointer, slice, map, interface directly
	switch t.(type) {
	case *types.Pointer, *types.Slice, *types.Map, *types.Interface, *types.Array:
		return true
	}

	// Check basic types (String is nullable)
	if basic, ok := t.Underlying().(*types.Basic); ok {
		return basic.Kind() == types.String
	}

	// For named types (e.g., time.Time, fory.Date), check underlying type
	// Struct types are not nullable unless they're pointers
	return false
}

// getTypeIDForHash returns the TypeId for hash calculation according to new spec
// For "other fields" (groupOther), returns UNKNOWN (63)
func getTypeIDForHash(field *FieldInfo) int16 {
	// Determine field group
	group := getFieldGroup(field)

	// For "other fields", use UNKNOWN
	if group == groupOther {
		return fory.UNKNOWN
	}

	// For struct fields declared with concrete slice types,
	// use typeID = LIST uniformly for hash calculation to align cross-language behavior
	// This matches the reflection implementation
	if field.TypeID == "LIST" {
		return fory.LIST
	}

	// Map field TypeID string to Fory TypeId value
	switch field.TypeID {
	case "BOOL":
		return fory.BOOL
	case "INT8":
		return fory.INT8
	case "INT16":
		return fory.INT16
	case "INT32":
		return fory.INT32
	case "INT64":
		return fory.INT64
	case "UINT8":
		return fory.UINT8
	case "UINT16":
		return fory.UINT16
	case "UINT32":
		return fory.UINT32
	case "UINT64":
		return fory.UINT64
	case "FLOAT32":
		return fory.FLOAT
	case "FLOAT64":
		return fory.DOUBLE
	case "STRING":
		return fory.STRING
	case "TIMESTAMP":
		return fory.TIMESTAMP
	case "LOCAL_DATE":
		return fory.LOCAL_DATE
	case "NAMED_STRUCT":
		return fory.NAMED_STRUCT
	case "STRUCT":
		return fory.STRUCT
	case "SET":
		return fory.SET
	case "MAP":
		return fory.MAP
	case "BINARY":
		return fory.BINARY
	case "ENUM":
		return fory.ENUM
	case "NAMED_ENUM":
		return fory.NAMED_ENUM
	case "EXT":
		return fory.EXT
	case "NAMED_EXT":
		return fory.NAMED_EXT
	case "INTERFACE":
		return fory.UNKNOWN // interface{} treated as UNKNOWN
	default:
		return fory.UNKNOWN
	}
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
		TypeID:        typeID,
		PrimitiveSize: primitiveSize,
	}, nil
}
