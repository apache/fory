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
// This matches the reflection-based sorting exactly for cross-language compatibility
func sortFields(fields []*FieldInfo) {
	sort.Slice(fields, func(i, j int) bool {
		f1, f2 := fields[i], fields[j]

		// Group primitives first (matching reflection's boxed group)
		if f1.IsPrimitive && !f2.IsPrimitive {
			return true
		}
		if !f1.IsPrimitive && f2.IsPrimitive {
			return false
		}

		if f1.IsPrimitive && f2.IsPrimitive {
			// Match reflection's boxed sorting logic exactly
			// First: handle compression types (INT32/INT64/VAR_INT32/VAR_INT64)
			compressI := f1.TypeID == "INT32" || f1.TypeID == "INT64" ||
				f1.TypeID == "VAR_INT32" || f1.TypeID == "VAR_INT64"
			compressJ := f2.TypeID == "INT32" || f2.TypeID == "INT64" ||
				f2.TypeID == "VAR_INT32" || f2.TypeID == "VAR_INT64"

			if compressI != compressJ {
				return !compressI && compressJ // non-compress comes first
			}

			// Then: by size (descending)
			if f1.PrimitiveSize != f2.PrimitiveSize {
				return f1.PrimitiveSize > f2.PrimitiveSize
			}

			// Finally: by name (ascending)
			return f1.SnakeName < f2.SnakeName
		}

		// For non-primitives: STRING comes in final group, others in others group
		// All sorted by type ID, then by name (matching reflection)
		if f1.TypeID != f2.TypeID {
			return getTypeIDValue(f1.TypeID) < getTypeIDValue(f2.TypeID)
		}
		return f1.SnakeName < f2.SnakeName
	})
}

// computeStructHash computes a hash for struct schema compatibility
// This implementation aligns with the reflection-based hash calculation
func computeStructHash(s *StructInfo) int32 {
	// Use the same iterative algorithm as reflection
	var hash int32 = 17

	// Process fields in the same order as reflection
	for _, field := range s.Fields {
		id := getFieldHashID(field)

		// Same algorithm as reflection: hash = hash * 31 + id
		newHash := int64(hash)*31 + int64(id)

		// Same overflow handling as reflection
		const MaxInt32 = 2147483647
		for newHash >= MaxInt32 {
			newHash /= 7
		}
		hash = int32(newHash)
	}

	if hash == 0 {
		// Same panic condition as reflection
		panic(fmt.Errorf("hash for type %v is 0", s.Name))
	}

	return hash
}

// getFieldHashID computes the field ID for hash calculation, matching reflection logic exactly
func getFieldHashID(field *FieldInfo) int32 {
	// Map Go types to Fory TypeIds (exactly matching reflection)
	var tid int16

	switch field.TypeID {
	case "BOOL":
		tid = fory.BOOL
	case "INT8":
		tid = fory.INT8
	case "INT16":
		tid = fory.INT16
	case "INT32":
		tid = fory.INT32
	case "INT64":
		tid = fory.INT64
	case "UINT8":
		tid = fory.UINT8
	case "UINT16":
		tid = fory.UINT16
	case "UINT32":
		tid = fory.UINT32
	case "UINT64":
		tid = fory.UINT64
	case "FLOAT32":
		tid = fory.FLOAT
	case "FLOAT64":
		tid = fory.DOUBLE
	case "STRING":
		tid = fory.STRING
	case "TIMESTAMP":
		tid = fory.TIMESTAMP
	case "LOCAL_DATE":
		tid = fory.LOCAL_DATE
	case "NAMED_STRUCT":
		tid = fory.NAMED_STRUCT
	case "LIST":
		tid = fory.LIST
	case "MAP":
		tid = fory.MAP
	default:
		tid = 0 // Unknown type
	}

	// Same logic as reflection: handle negative TypeIds
	if tid < 0 {
		return -int32(tid)
	}
	return int32(tid)
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
