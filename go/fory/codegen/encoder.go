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
	"bytes"
	"fmt"
	"go/types"
)

// generateWriteTyped generates the strongly-typed Write method
func generateWriteTyped(buf *bytes.Buffer, s *StructInfo) error {
	hash := computeStructHash(s)

	fmt.Fprintf(buf, "// WriteTyped provides strongly-typed serialization with no reflection overhead\n")
	fmt.Fprintf(buf, "func (g %s_ForyGenSerializer) WriteTyped(f *fory.Fory, buf *fory.ByteBuffer, v *%s) error {\n", s.Name, s.Name)

	// Write struct hash
	fmt.Fprintf(buf, "\t// Write precomputed struct hash for compatibility checking\n")
	fmt.Fprintf(buf, "\tbuf.WriteInt32(%d) // hash of %s structure\n\n", hash, s.Name)

	// Write fields in sorted order
	fmt.Fprintf(buf, "\t// Write fields in sorted order\n")
	for _, field := range s.Fields {
		if err := generateFieldWriteTyped(buf, field); err != nil {
			return err
		}
	}

	fmt.Fprintf(buf, "\treturn nil\n")
	fmt.Fprintf(buf, "}\n\n")
	return nil
}

// generateWriteInterface generates interface compatibility Write method
func generateWriteInterface(buf *bytes.Buffer, s *StructInfo) error {
	fmt.Fprintf(buf, "// Write provides reflect.Value interface compatibility\n")
	fmt.Fprintf(buf, "func (g %s_ForyGenSerializer) Write(f *fory.Fory, buf *fory.ByteBuffer, value reflect.Value) error {\n", s.Name)
	fmt.Fprintf(buf, "\t// Convert reflect.Value to concrete type and delegate to typed method\n")
	fmt.Fprintf(buf, "\tvar v *%s\n", s.Name)
	fmt.Fprintf(buf, "\tif value.Kind() == reflect.Ptr {\n")
	fmt.Fprintf(buf, "\t\tv = value.Interface().(*%s)\n", s.Name)
	fmt.Fprintf(buf, "\t} else {\n")
	fmt.Fprintf(buf, "\t\t// Create a copy to get a pointer\n")
	fmt.Fprintf(buf, "\t\ttemp := value.Interface().(%s)\n", s.Name)
	fmt.Fprintf(buf, "\t\tv = &temp\n")
	fmt.Fprintf(buf, "\t}\n")
	fmt.Fprintf(buf, "\t// Delegate to strongly-typed method for maximum performance\n")
	fmt.Fprintf(buf, "\treturn g.WriteTyped(f, buf, v)\n")
	fmt.Fprintf(buf, "}\n\n")
	return nil
}

// generateFieldWriteTyped generates field writing code for the typed method
func generateFieldWriteTyped(buf *bytes.Buffer, field *FieldInfo) error {
	fmt.Fprintf(buf, "\t// Field: %s (%s)\n", field.GoName, field.Type.String())

	fieldAccess := fmt.Sprintf("v.%s", field.GoName)

	// Handle special named types first
	if named, ok := field.Type.(*types.Named); ok {
		typeStr := named.String()
		switch typeStr {
		case "time.Time":
			fmt.Fprintf(buf, "\tbuf.WriteInt64(fory.GetUnixMicro(%s))\n", fieldAccess)
			return nil
		case "github.com/apache/fory/go/fory.Date":
			fmt.Fprintf(buf, "\t// Handle zero date specially\n")
			fmt.Fprintf(buf, "\tif %s.Year == 0 && %s.Month == 0 && %s.Day == 0 {\n", fieldAccess, fieldAccess, fieldAccess)
			fmt.Fprintf(buf, "\t\tbuf.WriteInt32(int32(-2147483648)) // Special marker for zero date\n")
			fmt.Fprintf(buf, "\t} else {\n")
			fmt.Fprintf(buf, "\t\tdiff := time.Date(%s.Year, %s.Month, %s.Day, 0, 0, 0, 0, time.Local).Sub(time.Date(1970, 1, 1, 0, 0, 0, 0, time.Local))\n", fieldAccess, fieldAccess, fieldAccess)
			fmt.Fprintf(buf, "\t\tbuf.WriteInt32(int32(diff.Hours() / 24))\n")
			fmt.Fprintf(buf, "\t}\n")
			return nil
		}
	}

	// Handle pointer types
	if _, ok := field.Type.(*types.Pointer); ok {
		// For all pointer types, use WriteReferencable
		fmt.Fprintf(buf, "\tf.WriteReferencable(buf, reflect.ValueOf(%s))\n", fieldAccess)
		return nil
	}

	// Handle basic types
	if basic, ok := field.Type.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool:
			fmt.Fprintf(buf, "\tbuf.WriteInt8(-1) // NotNullValueFlag\n")
			fmt.Fprintf(buf, "\tbuf.WriteBool(%s)\n", fieldAccess)
		case types.Int8:
			fmt.Fprintf(buf, "\tbuf.WriteInt8(-1) // NotNullValueFlag\n")
			fmt.Fprintf(buf, "\tbuf.WriteInt8(%s)\n", fieldAccess)
		case types.Int16:
			fmt.Fprintf(buf, "\tbuf.WriteInt8(-1) // NotNullValueFlag\n")
			fmt.Fprintf(buf, "\tbuf.WriteInt16(%s)\n", fieldAccess)
		case types.Int32:
			fmt.Fprintf(buf, "\tbuf.WriteInt8(-1) // NotNullValueFlag\n")
			fmt.Fprintf(buf, "\tbuf.WriteVarint32(%s)\n", fieldAccess)
		case types.Int, types.Int64:
			fmt.Fprintf(buf, "\tbuf.WriteInt8(-1) // NotNullValueFlag\n")
			fmt.Fprintf(buf, "\tbuf.WriteVarint64(%s)\n", fieldAccess)
		case types.Uint8:
			fmt.Fprintf(buf, "\tbuf.WriteInt8(-1) // NotNullValueFlag\n")
			fmt.Fprintf(buf, "\tbuf.WriteByte_(%s)\n", fieldAccess)
		case types.Uint16:
			fmt.Fprintf(buf, "\tbuf.WriteInt8(-1) // NotNullValueFlag\n")
			fmt.Fprintf(buf, "\tbuf.WriteInt16(int16(%s))\n", fieldAccess)
		case types.Uint32:
			fmt.Fprintf(buf, "\tbuf.WriteInt8(-1) // NotNullValueFlag\n")
			fmt.Fprintf(buf, "\tbuf.WriteInt32(int32(%s))\n", fieldAccess)
		case types.Uint, types.Uint64:
			fmt.Fprintf(buf, "\tbuf.WriteInt8(-1) // NotNullValueFlag\n")
			fmt.Fprintf(buf, "\tbuf.WriteInt64(int64(%s))\n", fieldAccess)
		case types.Float32:
			fmt.Fprintf(buf, "\tbuf.WriteInt8(-1) // NotNullValueFlag\n")
			fmt.Fprintf(buf, "\tbuf.WriteFloat32(%s)\n", fieldAccess)
		case types.Float64:
			fmt.Fprintf(buf, "\tbuf.WriteInt8(-1) // NotNullValueFlag\n")
			fmt.Fprintf(buf, "\tbuf.WriteFloat64(%s)\n", fieldAccess)
		case types.String:
			fmt.Fprintf(buf, "\tbuf.WriteInt8(0) // RefValueFlag\n")
			fmt.Fprintf(buf, "\tfory.WriteString(buf, %s)\n", fieldAccess)
		default:
			fmt.Fprintf(buf, "\t// TODO: unsupported basic type %s\n", basic.String())
		}
		return nil
	}

	// Handle slice types
	if slice, ok := field.Type.(*types.Slice); ok {
		elemType := slice.Elem()
		// Check if element type is interface{} (dynamic type)
		if iface, ok := elemType.(*types.Interface); ok && iface.Empty() {
			// For []interface{}, we need to manually implement the serialization
			// because WriteReferencable produces incorrect length encoding
			fmt.Fprintf(buf, "\t// Dynamic slice []interface{} handling - manual serialization\n")
			fmt.Fprintf(buf, "\tif %s == nil {\n", fieldAccess)
			fmt.Fprintf(buf, "\t\tbuf.WriteInt8(-3) // null value flag\n")
			fmt.Fprintf(buf, "\t} else {\n")
			fmt.Fprintf(buf, "\t\t// Write reference flag for the slice itself\n")
			fmt.Fprintf(buf, "\t\tbuf.WriteInt8(0) // RefValueFlag\n")
			fmt.Fprintf(buf, "\t\t// Write slice length\n")
			fmt.Fprintf(buf, "\t\tbuf.WriteVarUint32(uint32(len(%s)))\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t// Write collection flags (13 = NotDeclElementType + NotSameType + TrackingRef for dynamic slices)\n")
			fmt.Fprintf(buf, "\t\t// Always write collection flags with tracking ref enabled (13)\n")
			fmt.Fprintf(buf, "\t\t// This matches the reflection implementation which uses NewFory(true)\n")
			fmt.Fprintf(buf, "\t\tbuf.WriteInt8(13) // 12 + 1 (CollectionTrackingRef)\n")
			fmt.Fprintf(buf, "\t\t// Write each element using WriteReferencable\n")
			fmt.Fprintf(buf, "\t\tfor _, elem := range %s {\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\tf.WriteReferencable(buf, reflect.ValueOf(elem))\n")
			fmt.Fprintf(buf, "\t\t}\n")
			fmt.Fprintf(buf, "\t}\n")
			return nil
		}
		// For static element types, use optimized inline generation
		if err := generateSliceWriteInline(buf, slice, fieldAccess); err != nil {
			return err
		}
		return nil
	}

	// Handle interface types
	if iface, ok := field.Type.(*types.Interface); ok {
		if iface.Empty() {
			// For interface{}, use WriteReferencable for dynamic type handling
			fmt.Fprintf(buf, "\tf.WriteReferencable(buf, reflect.ValueOf(%s))\n", fieldAccess)
			return nil
		}
	}

	// Handle struct types
	if _, ok := field.Type.Underlying().(*types.Struct); ok {
		fmt.Fprintf(buf, "\tf.WriteReferencable(buf, reflect.ValueOf(%s))\n", fieldAccess)
		return nil
	}

	fmt.Fprintf(buf, "\t// TODO: unsupported type %s\n", field.Type.String())
	return nil
}

// generateElementTypeIDWrite generates code to write the element type ID for slice serialization
func generateElementTypeIDWrite(buf *bytes.Buffer, elemType types.Type) error {
	// Handle basic types
	if basic, ok := elemType.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool:
			fmt.Fprintf(buf, "\t\tbuf.WriteVarInt32(1) // BOOL\n")
		case types.Int8:
			fmt.Fprintf(buf, "\t\tbuf.WriteVarInt32(2) // INT8\n")
		case types.Int16:
			fmt.Fprintf(buf, "\t\tbuf.WriteVarInt32(3) // INT16\n")
		case types.Int32:
			fmt.Fprintf(buf, "\t\tbuf.WriteVarInt32(4) // INT32\n")
		case types.Int, types.Int64:
			fmt.Fprintf(buf, "\t\tbuf.WriteVarInt32(6) // INT64\n")
		case types.Uint8:
			fmt.Fprintf(buf, "\t\tbuf.WriteVarInt32(100) // UINT8\n")
		case types.Uint16:
			fmt.Fprintf(buf, "\t\tbuf.WriteVarInt32(101) // UINT16\n")
		case types.Uint32:
			fmt.Fprintf(buf, "\t\tbuf.WriteVarInt32(102) // UINT32\n")
		case types.Uint, types.Uint64:
			fmt.Fprintf(buf, "\t\tbuf.WriteVarInt32(103) // UINT64\n")
		case types.Float32:
			fmt.Fprintf(buf, "\t\tbuf.WriteVarInt32(10) // FLOAT\n")
		case types.Float64:
			fmt.Fprintf(buf, "\t\tbuf.WriteVarInt32(11) // DOUBLE\n")
		case types.String:
			fmt.Fprintf(buf, "\t\tbuf.WriteVarInt32(12) // STRING\n")
		default:
			return fmt.Errorf("unsupported basic type for element type ID: %s", basic.String())
		}
		return nil
	}

	// Handle named types
	if named, ok := elemType.(*types.Named); ok {
		typeStr := named.String()
		switch typeStr {
		case "time.Time":
			fmt.Fprintf(buf, "\t\tbuf.WriteVarInt32(25) // TIMESTAMP\n")
			return nil
		case "github.com/apache/fory/go/fory.Date":
			fmt.Fprintf(buf, "\t\tbuf.WriteVarInt32(26) // LOCAL_DATE\n")
			return nil
		}
		// Check if it's a struct
		if _, ok := named.Underlying().(*types.Struct); ok {
			fmt.Fprintf(buf, "\t\tbuf.WriteVarInt32(17) // NAMED_STRUCT\n")
			return nil
		}
	}

	// Handle struct types
	if _, ok := elemType.Underlying().(*types.Struct); ok {
		fmt.Fprintf(buf, "\t\tbuf.WriteVarInt32(17) // NAMED_STRUCT\n")
		return nil
	}

	return fmt.Errorf("unsupported element type for type ID: %s", elemType.String())
}

// generateSliceWriteInline generates inline slice serialization code to match reflection behavior exactly
func generateSliceWriteInline(buf *bytes.Buffer, sliceType *types.Slice, fieldAccess string) error {
	elemType := sliceType.Elem()

	// Write RefValueFlag first (slice is referencable)
	fmt.Fprintf(buf, "\tbuf.WriteInt8(0) // RefValueFlag for slice\n")

	// Write slice length - use block scope to avoid variable name conflicts
	fmt.Fprintf(buf, "\t{\n")
	fmt.Fprintf(buf, "\t\tsliceLen := 0\n")
	fmt.Fprintf(buf, "\t\tif %s != nil {\n", fieldAccess)
	fmt.Fprintf(buf, "\t\t\tsliceLen = len(%s)\n", fieldAccess)
	fmt.Fprintf(buf, "\t\t}\n")
	fmt.Fprintf(buf, "\t\tbuf.WriteVarUint32(uint32(sliceLen))\n")

	// Write collection header and elements for non-empty slice
	fmt.Fprintf(buf, "\t\tif sliceLen > 0 {\n")

	// For codegen, follow reflection's behavior exactly:
	// Set CollectionNotDeclElementType (0b0100 = 4) and CollectionNotSameType (0b1000 = 8)
	// Add CollectionTrackingRef (0b0001 = 1) when reference tracking is enabled
	fmt.Fprintf(buf, "\t\t\tcollectFlag := 12 // CollectionNotDeclElementType + CollectionNotSameType\n")
	fmt.Fprintf(buf, "\t\t\t// Access private field f.refTracking using reflection to match behavior\n")
	fmt.Fprintf(buf, "\t\t\tforyValue := reflect.ValueOf(f).Elem()\n")
	fmt.Fprintf(buf, "\t\t\trefTrackingField := foryValue.FieldByName(\"refTracking\")\n")
	fmt.Fprintf(buf, "\t\t\tif refTrackingField.IsValid() && refTrackingField.Bool() {\n")
	fmt.Fprintf(buf, "\t\t\t\tcollectFlag |= 1 // Add CollectionTrackingRef\n")
	fmt.Fprintf(buf, "\t\t\t}\n")
	fmt.Fprintf(buf, "\t\t\tbuf.WriteInt8(int8(collectFlag))\n")

	// For each element, write type info + value (because CollectionNotSameType is set)
	fmt.Fprintf(buf, "\t\t\tfor _, elem := range %s {\n", fieldAccess)
	fmt.Fprintf(buf, "\t\t\t\tbuf.WriteInt8(-1) // NotNullValueFlag\n")

	// Write element type ID
	if err := generateElementTypeIDWriteInline(buf, elemType); err != nil {
		return err
	}

	// Write element value
	if err := generateSliceElementWriteInline(buf, elemType, "elem"); err != nil {
		return err
	}

	fmt.Fprintf(buf, "\t\t\t}\n")
	fmt.Fprintf(buf, "\t\t}\n")
	fmt.Fprintf(buf, "\t}\n")

	return nil
}

// generateElementTypeIDWriteInline generates element type ID write with specific indentation
func generateElementTypeIDWriteInline(buf *bytes.Buffer, elemType types.Type) error {
	// Handle basic types
	if basic, ok := elemType.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVarInt32(1) // BOOL\n")
		case types.Int8:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVarInt32(2) // INT8\n")
		case types.Int16:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVarInt32(3) // INT16\n")
		case types.Int32:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVarInt32(4) // INT32\n")
		case types.Int, types.Int64:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVarInt32(6) // INT64\n")
		case types.Uint8:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVarInt32(100) // UINT8\n")
		case types.Uint16:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVarInt32(101) // UINT16\n")
		case types.Uint32:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVarInt32(102) // UINT32\n")
		case types.Uint, types.Uint64:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVarInt32(103) // UINT64\n")
		case types.Float32:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVarInt32(10) // FLOAT\n")
		case types.Float64:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVarInt32(11) // DOUBLE\n")
		case types.String:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVarInt32(12) // STRING\n")
		default:
			return fmt.Errorf("unsupported basic type for element type ID: %s", basic.String())
		}
		return nil
	}
	return fmt.Errorf("unsupported element type for type ID: %s", elemType.String())
}

// generateSliceElementWriteInline generates code to write a single slice element value
func generateSliceElementWriteInline(buf *bytes.Buffer, elemType types.Type, elemAccess string) error {
	// Handle basic types - write the actual value without type info (type already written above)
	if basic, ok := elemType.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteBool(%s)\n", elemAccess)
		case types.Int8:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteInt8(%s)\n", elemAccess)
		case types.Int16:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteInt16(%s)\n", elemAccess)
		case types.Int32:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVarint32(%s)\n", elemAccess)
		case types.Int, types.Int64:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVarint64(%s)\n", elemAccess)
		case types.Uint8:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteByte_(%s)\n", elemAccess)
		case types.Uint16:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteInt16(int16(%s))\n", elemAccess)
		case types.Uint32:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteInt32(int32(%s))\n", elemAccess)
		case types.Uint, types.Uint64:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteInt64(int64(%s))\n", elemAccess)
		case types.Float32:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteFloat32(%s)\n", elemAccess)
		case types.Float64:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteFloat64(%s)\n", elemAccess)
		case types.String:
			fmt.Fprintf(buf, "\t\t\t\tfory.WriteString(buf, %s)\n", elemAccess)
		default:
			return fmt.Errorf("unsupported basic type for element write: %s", basic.String())
		}
		return nil
	}

	// Handle interface types
	if iface, ok := elemType.(*types.Interface); ok {
		if iface.Empty() {
			// For interface{} elements, use WriteReferencable for dynamic type handling
			fmt.Fprintf(buf, "\t\t\t\tf.WriteReferencable(buf, reflect.ValueOf(%s))\n", elemAccess)
			return nil
		}
	}

	return fmt.Errorf("unsupported element type for write: %s", elemType.String())
}
