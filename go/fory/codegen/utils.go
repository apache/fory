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
	"crypto/md5"
	"encoding/binary"
	"go/types"
	"sort"
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

	// Check basic types
	if basic, ok := t.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool, types.Int8, types.Int16, types.Int32, types.Int, types.Int64,
			types.Uint8, types.Uint16, types.Uint32, types.Uint, types.Uint64,
			types.Float32, types.Float64:
			return true
		}
	}

	// String is also considered primitive in Fory context but nullable
	if basic, ok := t.Underlying().(*types.Basic); ok && basic.Kind() == types.String {
		return true
	}

	return false
}

// getTypeID returns the Fory TypeID for a given type
func getTypeID(t types.Type) string {
	// Handle pointer types
	if ptr, ok := t.(*types.Pointer); ok {
		t = ptr.Elem()
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

	if val, ok := typeIDMap[typeID]; ok {
		return val
	}
	return 999
}

// sortFields sorts fields according to Fory protocol
func sortFields(fields []*FieldInfo) {
	sort.Slice(fields, func(i, j int) bool {
		f1, f2 := fields[i], fields[j]

		// Group primitives first
		if f1.IsPrimitive && !f2.IsPrimitive {
			return true
		}
		if !f1.IsPrimitive && f2.IsPrimitive {
			return false
		}

		if f1.IsPrimitive && f2.IsPrimitive {
			// Sort primitives by size (descending), then by type ID, then by name
			if f1.PrimitiveSize != f2.PrimitiveSize {
				return f1.PrimitiveSize > f2.PrimitiveSize
			}
			if f1.TypeID != f2.TypeID {
				return getTypeIDValue(f1.TypeID) < getTypeIDValue(f2.TypeID)
			}
			return f1.SnakeName < f2.SnakeName
		}

		// Sort non-primitives by type ID, then by name
		if f1.TypeID != f2.TypeID {
			return getTypeIDValue(f1.TypeID) < getTypeIDValue(f2.TypeID)
		}
		return f1.SnakeName < f2.SnakeName
	})
}

// computeStructHash computes a hash for struct schema compatibility
func computeStructHash(s *StructInfo) int32 {
	h := md5.New()

	// Write struct name
	h.Write([]byte(s.Name))

	// Write sorted field information
	for _, field := range s.Fields {
		h.Write([]byte(field.SnakeName))
		h.Write([]byte(field.TypeID))
		// Add primitive size for better differentiation
		if field.IsPrimitive {
			sizeBytes := make([]byte, 4)
			binary.LittleEndian.PutUint32(sizeBytes, uint32(field.PrimitiveSize))
			h.Write(sizeBytes)
		}
	}

	hashBytes := h.Sum(nil)
	// Take first 4 bytes as int32
	return int32(binary.LittleEndian.Uint32(hashBytes[:4]))
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
