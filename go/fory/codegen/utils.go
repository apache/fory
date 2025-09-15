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
	"go/types"
	"sort"
	"strings"
	"unicode"
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
	Name           string
	Fields         []*FieldInfo // Sorted fields for serialization
	OriginalFields []*FieldInfo // Original field order for guard generation
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
		// Recursively check if the element type is supported
		return isSupportedFieldType(slice.Elem())
	}

	// Check map types
	if mapType, ok := t.(*types.Map); ok {
		// Both key and value types must be supported
		// For now, restrict keys to basic comparable types
		if !isValidMapKeyType(mapType.Key()) {
			return false
		}
		return isSupportedFieldType(mapType.Elem())
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

	// Check basic types (excluding string and int per reflection behavior)
	if basic, ok := t.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool, types.Int8, types.Int16, types.Int32, types.Int64,
			types.Uint8, types.Uint16, types.Uint32, types.Uint, types.Uint64,
			types.Float32, types.Float64:
			return true
		// types.Int is excluded because it has negative TypeId (-6) in reflection
		}
	}

	// String is NOT primitive - it goes to final types group like in reflection
	return false
}

// getTypeID returns the Fory TypeID for a given type
func getTypeID(t types.Type) string {
	// Handle pointer types
	if ptr, ok := t.(*types.Pointer); ok {
		t = ptr.Elem()
	}

	// Check slice types
	if slice, ok := t.(*types.Slice); ok {
		elemTypeID := getTypeID(slice.Elem())
		return "LIST_" + elemTypeID
	}

	// Check map types
	if mapType, ok := t.(*types.Map); ok {
		keyTypeID := getTypeID(mapType.Key())
		valueTypeID := getTypeID(mapType.Elem())
		return "MAP_" + keyTypeID + "_" + valueTypeID
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
		case types.Int:
			return "INT" // int uses intSerializer with negative TypeId
		case types.Int64:
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
			return 8 // int is also 8 bytes for consistent serialization
		case types.String:
			return 999 // Variable size, sort last among primitives
		}
	}

	return 0
}

// getTypeIDValue returns numeric value for type ID for sorting
func getTypeIDValue(typeID string) int {
	// Map Fory TypeIDs to numeric values for sorting
	typeIDMap := map[string]int{
		"BOOL":         1,
		"INT8":         2,
		"INT16":        3,
		"INT32":        4,
		"INT64":        5,
		"UINT8":        6,
		"UINT16":       7,
		"UINT32":       8,
		"UINT64":       9,
		"FLOAT32":      10,
		"FLOAT64":      11,
		"STRING":       12,
		"TIMESTAMP":    20,
		"LOCAL_DATE":   21,
		"NAMED_STRUCT": 30,
	}

	// Handle LIST types
	if len(typeID) > 5 && typeID[:5] == "LIST_" {
		return 40 // List types sort after structs but before unknown
	}

	// Handle MAP types
	if len(typeID) > 4 && typeID[:4] == "MAP_" {
		return 50 // Map types sort after lists but before unknown
	}

	if val, ok := typeIDMap[typeID]; ok {
		return val
	}
	return 999
}

// sortFields sorts fields according to Fory protocol (matches reflection behavior)
func sortFields(fields []*FieldInfo) {
	// Complex grouping and sorting to match reflection's struct.go sortFields logic
	var primitives, finals, others, collections, maps []*FieldInfo
	
	for _, field := range fields {
		switch {
		case isReflectionPrimitive(field.TypeID):
			primitives = append(primitives, field)
		case field.TypeID == "STRING" || field.TypeID == "TIMESTAMP": // Final types
			finals = append(finals, field)
		case field.TypeID == "LIST" || strings.HasPrefix(field.TypeID, "LIST_"):
			collections = append(collections, field)
		case field.TypeID == "MAP" || strings.HasPrefix(field.TypeID, "MAP_"):
			maps = append(maps, field)
		default:
			others = append(others, field) // INT, UINT8 等非primitive类型
		}
	}
	
	// Sort primitives: compression type -> size desc -> name asc (matching struct.go logic)
	sort.Slice(primitives, func(i, j int) bool {
		f1, f2 := primitives[i], primitives[j]
		
		// Check compression types (INT32, INT64 are compressible)
		compressI := f1.TypeID == "INT32" || f1.TypeID == "INT64"
		compressJ := f2.TypeID == "INT32" || f2.TypeID == "INT64"
		if compressI != compressJ {
			return !compressI && compressJ // non-compress first
		}
		
		// Then by size descending
		if f1.PrimitiveSize != f2.PrimitiveSize {
			return f1.PrimitiveSize > f2.PrimitiveSize
		}
		
		// Finally by name ascending
		return f1.SnakeName < f2.SnakeName
	})
	
	// Sort other groups by real Fory typeId then name (matching reflection logic)
	sortByTypeAndName := func(slice []*FieldInfo) {
		sort.Slice(slice, func(i, j int) bool {
			f1, f2 := slice[i], slice[j]
			if f1.TypeID != f2.TypeID {
				// Use actual Fory TypeID values for sorting (matching reflection)
				typeId1 := getRealForyTypeID(f1.TypeID)
				typeId2 := getRealForyTypeID(f2.TypeID)
				return typeId1 < typeId2
			}
			return f1.SnakeName < f2.SnakeName
		})
	}
	
	sortByTypeAndName(finals)
	sortByTypeAndName(others) 
	sortByTypeAndName(collections)
	sortByTypeAndName(maps)
	
	// Combine in the correct order (matching reflection struct.go)
	result := make([]*FieldInfo, 0, len(fields))
	result = append(result, primitives...)
	result = append(result, finals...)
	result = append(result, others...)
	result = append(result, collections...)
	result = append(result, maps...)
	
	// Copy back to original slice
	copy(fields, result)
}

// isReflectionPrimitive matches type.go isPrimitiveType exactly
func isReflectionPrimitive(typeID string) bool {
	switch typeID {
	case "BOOL", "INT8", "INT16", "INT32", "INT64", "FLOAT32", "FLOAT64":
		return true
	default:
		return false // INT and UINT8 are NOT primitives in reflection!
	}
}

// getRealForyTypeID returns the actual Fory TypeID value for sorting (matching reflection)
func getRealForyTypeID(typeID string) int16 {
	switch typeID {
	case "BOOL":
		return 1
	case "INT8":
		return 2
	case "INT16":
		return 3
	case "INT32":
		return 4
	case "INT64":
		return 6
	case "INT":
		return -6 // intSerializer.TypeId() = -INT64 = -6
	case "UINT8":
		return 100
	case "UINT16":
		return 101
	case "UINT32":
		return 102
	case "UINT64":
		return 103
	case "FLOAT32":
		return 10
	case "FLOAT64":
		return 11
	case "STRING":
		return 12
	case "TIMESTAMP":
		return 25
	case "NAMED_STRUCT":
		return 17
	default:
		return 999
	}
}

// computeStructHash computes a hash for struct schema compatibility
// This matches the algorithm used in fory/struct.go computeStructHash
func computeStructHash(s *StructInfo) int32 {
	var hash int32 = 17
	
	// Process fields in same order as reflection path (sorted)
	for _, field := range s.Fields {
		fieldId := getFieldHashId(field)
		newHash := int64(hash)*31 + int64(fieldId)
		
		// Same overflow handling as reflection path
		for newHash >= 2147483647 { // MaxInt32
			newHash /= 7
		}
		
		hash = int32(newHash)
	}
	
	if hash == 0 {
		// Fallback to avoid zero hash (matches reflection logic)
		hash = 1
	}
	
	return hash
}

// getFieldHashId computes field ID for hash calculation (matches reflection logic)
func getFieldHashId(field *FieldInfo) int32 {
	// Apply struct.go line 374-376 logic: ALL slices use LIST(21) for hash calculation
	if isSliceType(field.Type) {
		return 21 // LIST - uniform for all slice types per struct.go line 374-376
	}
	
	// Check for map types - all maps use MAP(23) for hash calculation
	if isMapType(field.Type) {
		return 23 // MAP - uniform for all map types
	}
	
	// Map TypeID strings to numeric values (matches TypeId constants in fory)
	typeIdMap := map[string]int32{
		"BOOL":    1,    // BOOL
		"INT8":    2,    // INT8  
		"INT16":   3,    // INT16
		"INT32":   4,    // INT32
		"INT64":   6,    // INT64
		"INT":     6,    // intSerializer uses positive INT64 for hash calculation
		"UINT8":   100,  // UINT8
		"UINT16":  101,  // UINT16
		"UINT32":  102,  // UINT32
		"UINT64":  103,  // UINT64
		"FLOAT32": 10,   // FLOAT
		"FLOAT64": 11,   // DOUBLE
		"STRING":  12,   // STRING
		"LIST":    21,   // LIST
		"MAP":     23,   // MAP
		"NAMED_STRUCT": 17, // NAMED_STRUCT
		"TIMESTAMP": 25, // TIMESTAMP for time.Time
	}
	
	if id, ok := typeIdMap[field.TypeID]; ok {
		// Handle negative type IDs for pointer types
		if field.IsPointer && field.TypeID == "NAMED_STRUCT" {
			return -id
		}
		return id
	}
	
	// Default fallback
	return 0
}

// isSliceType checks if the type is a slice type
func isSliceType(t types.Type) bool {
	_, ok := t.(*types.Slice)
	return ok
}

// isMapType checks if the type is a map type
func isMapType(t types.Type) bool {
	_, ok := t.(*types.Map)
	return ok
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

// isValidMapKeyType checks if a type can be used as a map key
func isValidMapKeyType(t types.Type) bool {
	// Handle pointer types
	if ptr, ok := t.(*types.Pointer); ok {
		t = ptr.Elem()
	}

	// Check basic types that are comparable
	if basic, ok := t.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool, types.Int8, types.Int16, types.Int32, types.Int, types.Int64,
			types.Uint8, types.Uint16, types.Uint32, types.Uint, types.Uint64,
			types.Float32, types.Float64, types.String:
			return true
		}
	}

	// For now, only allow basic types as map keys for simplicity
	return false
}

// getGoTypeString returns the Go type string for a given types.Type
func getGoTypeString(t types.Type) string {
	// Handle pointer types
	if ptr, ok := t.(*types.Pointer); ok {
		return "*" + getGoTypeString(ptr.Elem())
	}

	// Handle slice types
	if slice, ok := t.(*types.Slice); ok {
		return "[]" + getGoTypeString(slice.Elem())
	}

	// Handle map types
	if mapType, ok := t.(*types.Map); ok {
		return "map[" + getGoTypeString(mapType.Key()) + "]" + getGoTypeString(mapType.Elem())
	}

	// Handle named types
	if named, ok := t.(*types.Named); ok {
		return named.String()
	}

	// Handle basic types
	if basic, ok := t.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool:
			return "bool"
		case types.Int:
			return "int"
		case types.Int8:
			return "int8"
		case types.Int16:
			return "int16"
		case types.Int32:
			return "int32"
		case types.Int64:
			return "int64"
		case types.Uint:
			return "uint"
		case types.Uint8:
			return "uint8"
		case types.Uint16:
			return "uint16"
		case types.Uint32:
			return "uint32"
		case types.Uint64:
			return "uint64"
		case types.Float32:
			return "float32"
		case types.Float64:
			return "float64"
		case types.String:
			return "string"
		}
	}

	// Fallback to string representation
	return t.String()
}
