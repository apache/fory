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

// generateReadTyped generates the strongly-typed Read method
func generateReadTyped(buf *bytes.Buffer, s *StructInfo) error {
	hash := computeStructHash(s)

	fmt.Fprintf(buf, "// ReadTyped provides strongly-typed deserialization with no reflection overhead\n")
	fmt.Fprintf(buf, "func (g %s_ForyGenSerializer) ReadTyped(f *fory.Fory, buf *fory.ByteBuffer, v *%s) error {\n", s.Name, s.Name)

	// Read and verify struct hash
	fmt.Fprintf(buf, "\t// Read and verify struct hash\n")
	fmt.Fprintf(buf, "\tif got := buf.ReadInt32(); got != %d {\n", hash)
	fmt.Fprintf(buf, "\t\treturn fmt.Errorf(\"struct hash mismatch for %s: expected %d, got %%d\", got)\n", s.Name, hash)
	fmt.Fprintf(buf, "\t}\n\n")

	// Read fields in sorted order
	fmt.Fprintf(buf, "\t// Read fields in same order as write\n")
	for _, field := range s.Fields {
		if err := generateFieldReadTyped(buf, field); err != nil {
			return err
		}
	}

	fmt.Fprintf(buf, "\treturn nil\n")
	fmt.Fprintf(buf, "}\n\n")
	return nil
}

// generateReadInterface generates interface compatibility Read method
func generateReadInterface(buf *bytes.Buffer, s *StructInfo) error {
	fmt.Fprintf(buf, "// Read provides reflect.Value interface compatibility\n")
	fmt.Fprintf(buf, "func (g %s_ForyGenSerializer) Read(f *fory.Fory, buf *fory.ByteBuffer, type_ reflect.Type, value reflect.Value) error {\n", s.Name)
	fmt.Fprintf(buf, "\t// Convert reflect.Value to concrete type and delegate to typed method\n")
	fmt.Fprintf(buf, "\tvar v *%s\n", s.Name)
	fmt.Fprintf(buf, "\tif value.Kind() == reflect.Ptr {\n")
	fmt.Fprintf(buf, "\t\tif value.IsNil() {\n")
	fmt.Fprintf(buf, "\t\t\t// For pointer types, allocate using type_.Elem()\n")
	fmt.Fprintf(buf, "\t\t\tvalue.Set(reflect.New(type_.Elem()))\n")
	fmt.Fprintf(buf, "\t\t}\n")
	fmt.Fprintf(buf, "\t\tv = value.Interface().(*%s)\n", s.Name)
	fmt.Fprintf(buf, "\t} else {\n")
	fmt.Fprintf(buf, "\t\t// value must be addressable for read\n")
	fmt.Fprintf(buf, "\t\tv = value.Addr().Interface().(*%s)\n", s.Name)
	fmt.Fprintf(buf, "\t}\n")
	fmt.Fprintf(buf, "\t// Delegate to strongly-typed method for maximum performance\n")
	fmt.Fprintf(buf, "\treturn g.ReadTyped(f, buf, v)\n")
	fmt.Fprintf(buf, "}\n\n")
	return nil
}

// generateFieldReadTyped generates field reading code for the typed method
func generateFieldReadTyped(buf *bytes.Buffer, field *FieldInfo) error {
	fmt.Fprintf(buf, "\t// Field: %s (%s)\n", field.GoName, field.Type.String())

	fieldAccess := fmt.Sprintf("v.%s", field.GoName)

	// Handle special named types first
	if named, ok := field.Type.(*types.Named); ok {
		typeStr := named.String()
		switch typeStr {
		case "time.Time":
			fmt.Fprintf(buf, "\tusec := buf.ReadInt64()\n")
			fmt.Fprintf(buf, "\t%s = fory.CreateTimeFromUnixMicro(usec)\n", fieldAccess)
			return nil
		case "github.com/apache/fory/go/fory.Date":
			fmt.Fprintf(buf, "\tdays := buf.ReadInt32()\n")
			fmt.Fprintf(buf, "\t// Handle zero date marker\n")
			fmt.Fprintf(buf, "\tif days == int32(-2147483648) {\n")
			fmt.Fprintf(buf, "\t\t%s = fory.Date{Year: 0, Month: 0, Day: 0}\n", fieldAccess)
			fmt.Fprintf(buf, "\t} else {\n")
			fmt.Fprintf(buf, "\t\tdiff := time.Duration(days) * 24 * time.Hour\n")
			fmt.Fprintf(buf, "\t\tt := time.Date(1970, 1, 1, 0, 0, 0, 0, time.Local).Add(diff)\n")
			fmt.Fprintf(buf, "\t\t%s = fory.Date{Year: t.Year(), Month: t.Month(), Day: t.Day()}\n", fieldAccess)
			fmt.Fprintf(buf, "\t}\n")
			return nil
		}
	}

	// Handle pointer types
	if _, ok := field.Type.(*types.Pointer); ok {
		// For pointer types, use ReadReferencable
		fmt.Fprintf(buf, "\tf.ReadReferencable(buf, reflect.ValueOf(&%s).Elem())\n", fieldAccess)
		return nil
	}

	// Handle basic types
	if basic, ok := field.Type.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool:
			fmt.Fprintf(buf, "\tif flag := buf.ReadInt8(); flag != -1 {\n")
			fmt.Fprintf(buf, "\t\treturn fmt.Errorf(\"expected NotNullValueFlag for field %s, got %%d\", flag)\n", field.GoName)
			fmt.Fprintf(buf, "\t}\n")
			fmt.Fprintf(buf, "\t%s = buf.ReadBool()\n", fieldAccess)
		case types.Int8:
			fmt.Fprintf(buf, "\t%s = buf.ReadInt8()\n", fieldAccess)
		case types.Int16:
			fmt.Fprintf(buf, "\t%s = buf.ReadInt16()\n", fieldAccess)
		case types.Int32:
			fmt.Fprintf(buf, "\tif flag := buf.ReadInt8(); flag != -1 {\n")
			fmt.Fprintf(buf, "\t\treturn fmt.Errorf(\"expected NotNullValueFlag for field %s, got %%d\", flag)\n", field.GoName)
			fmt.Fprintf(buf, "\t}\n")
			fmt.Fprintf(buf, "\t%s = buf.ReadVarint32()\n", fieldAccess)
		case types.Int, types.Int64:
			fmt.Fprintf(buf, "\tif flag := buf.ReadInt8(); flag != -1 {\n")
			fmt.Fprintf(buf, "\t\treturn fmt.Errorf(\"expected NotNullValueFlag for field %s, got %%d\", flag)\n", field.GoName)
			fmt.Fprintf(buf, "\t}\n")
			fmt.Fprintf(buf, "\t%s = buf.ReadVarint64()\n", fieldAccess)
		case types.Uint8:
			fmt.Fprintf(buf, "\t%s = buf.ReadByte_()\n", fieldAccess)
		case types.Uint16:
			fmt.Fprintf(buf, "\t%s = uint16(buf.ReadInt16())\n", fieldAccess)
		case types.Uint32:
			fmt.Fprintf(buf, "\t%s = uint32(buf.ReadInt32())\n", fieldAccess)
		case types.Uint, types.Uint64:
			fmt.Fprintf(buf, "\t%s = uint64(buf.ReadInt64())\n", fieldAccess)
		case types.Float32:
			fmt.Fprintf(buf, "\t%s = buf.ReadFloat32()\n", fieldAccess)
		case types.Float64:
			fmt.Fprintf(buf, "\tif flag := buf.ReadInt8(); flag != -1 {\n")
			fmt.Fprintf(buf, "\t\treturn fmt.Errorf(\"expected NotNullValueFlag for field %s, got %%d\", flag)\n", field.GoName)
			fmt.Fprintf(buf, "\t}\n")
			fmt.Fprintf(buf, "\t%s = buf.ReadFloat64()\n", fieldAccess)
		case types.String:
			fmt.Fprintf(buf, "\tif flag := buf.ReadInt8(); flag != 0 {\n")
			fmt.Fprintf(buf, "\t\treturn fmt.Errorf(\"expected RefValueFlag for field %s, got %%d\", flag)\n", field.GoName)
			fmt.Fprintf(buf, "\t}\n")
			fmt.Fprintf(buf, "\t%s = fory.ReadString(buf)\n", fieldAccess)
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
			// For []interface{}, we need to manually implement the deserialization
			// to match our custom encoding
			fmt.Fprintf(buf, "\t// Dynamic slice []interface{} handling - manual deserialization\n")
			fmt.Fprintf(buf, "\tif flag := buf.ReadInt8(); flag == -3 {\n")
			fmt.Fprintf(buf, "\t\t%s = nil // null slice\n", fieldAccess)
			fmt.Fprintf(buf, "\t} else if flag == 0 {\n")
			fmt.Fprintf(buf, "\t\t// Read slice length\n")
			fmt.Fprintf(buf, "\t\tsliceLen := buf.ReadVarUint32()\n")
			fmt.Fprintf(buf, "\t\t// Read collection flags (ignore for now)\n")
			fmt.Fprintf(buf, "\t\t_ = buf.ReadInt8()\n")
			fmt.Fprintf(buf, "\t\t// Create slice with proper capacity\n")
			fmt.Fprintf(buf, "\t\t%s = make([]interface{}, sliceLen)\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t// Read each element using ReadReferencable\n")
			fmt.Fprintf(buf, "\t\tfor i := range %s {\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\tf.ReadReferencable(buf, reflect.ValueOf(&%s[i]).Elem())\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t}\n")
			fmt.Fprintf(buf, "\t} else {\n")
			fmt.Fprintf(buf, "\t\treturn fmt.Errorf(\"expected RefValueFlag or NullFlag for dynamic slice field %s, got %%d\", flag)\n", field.GoName)
			fmt.Fprintf(buf, "\t}\n")
			return nil
		}
		// For static element types, use optimized inline generation
		if err := generateSliceReadInline(buf, slice, fieldAccess); err != nil {
			return err
		}
		return nil
	}

	// Handle interface types
	if iface, ok := field.Type.(*types.Interface); ok {
		if iface.Empty() {
			// For interface{}, use ReadReferencable for dynamic type handling
			fmt.Fprintf(buf, "\tf.ReadReferencable(buf, reflect.ValueOf(&%s).Elem())\n", fieldAccess)
			return nil
		}
	}

	// Handle struct types
	if _, ok := field.Type.Underlying().(*types.Struct); ok {
		fmt.Fprintf(buf, "\tf.ReadReferencable(buf, reflect.ValueOf(&%s).Elem())\n", fieldAccess)
		return nil
	}

	fmt.Fprintf(buf, "\t// TODO: unsupported type %s\n", field.Type.String())
	return nil
}

// Note: generateSliceRead is no longer used since we use WriteReferencable/ReadReferencable for slice fields
// generateSliceRead generates code to deserialize a slice according to the list format
func generateSliceRead(buf *bytes.Buffer, sliceType *types.Slice, fieldAccess string) error {
	elemType := sliceType.Elem()

	// Use block scope to avoid variable redeclaration across multiple slice fields
	fmt.Fprintf(buf, "\t// Read slice %s\n", fieldAccess)
	fmt.Fprintf(buf, "\t{\n")
	fmt.Fprintf(buf, "\t\tsliceLen := int(buf.ReadVarUint32())\n")
	fmt.Fprintf(buf, "\t\tif sliceLen == 0 {\n")
	fmt.Fprintf(buf, "\t\t\t// Empty slice - matching reflection behavior where nil and empty are treated the same\n")
	fmt.Fprintf(buf, "\t\t\t%s = nil\n", fieldAccess)
	fmt.Fprintf(buf, "\t\t} else {\n")

	// Read collection flags for non-empty slice
	fmt.Fprintf(buf, "\t\t\t// Read collection flags\n")
	fmt.Fprintf(buf, "\t\t\tcollectFlag := buf.ReadInt8()\n")
	fmt.Fprintf(buf, "\t\t\t// Check if CollectionNotDeclElementType flag is set\n")
	fmt.Fprintf(buf, "\t\t\tif (collectFlag & 4) != 0 {\n")
	fmt.Fprintf(buf, "\t\t\t\t// Read element type ID (we expect it but don't need to validate it for codegen)\n")
	fmt.Fprintf(buf, "\t\t\t\t_ = buf.ReadVarInt32()\n")
	fmt.Fprintf(buf, "\t\t\t}\n")

	// Create slice
	fmt.Fprintf(buf, "\t\t\t%s = make(%s, sliceLen)\n", fieldAccess, sliceType.String())

	// Read elements
	fmt.Fprintf(buf, "\t\t\tfor i := 0; i < sliceLen; i++ {\n")

	// Generate element read code based on type
	elemAccess := fmt.Sprintf("%s[i]", fieldAccess)
	if err := generateSliceElementRead(buf, elemType, elemAccess); err != nil {
		return err
	}

	fmt.Fprintf(buf, "\t\t\t}\n")
	fmt.Fprintf(buf, "\t\t}\n")
	fmt.Fprintf(buf, "\t}\n")

	return nil
}

// generateSliceElementRead generates code to read a single slice element
func generateSliceElementRead(buf *bytes.Buffer, elemType types.Type, elemAccess string) error {
	// Handle basic types
	if basic, ok := elemType.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadBool()\n", elemAccess)
		case types.Int8:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadInt8()\n", elemAccess)
		case types.Int16:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadInt16()\n", elemAccess)
		case types.Int32:
			fmt.Fprintf(buf, "\t\t\t\tif flag := buf.ReadInt8(); flag != -1 {\n")
			fmt.Fprintf(buf, "\t\t\t\t\treturn fmt.Errorf(\"expected NotNullValueFlag for slice element, got %%d\", flag)\n")
			fmt.Fprintf(buf, "\t\t\t\t}\n")
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadVarint32()\n", elemAccess)
		case types.Int, types.Int64:
			fmt.Fprintf(buf, "\t\t\t\tif flag := buf.ReadInt8(); flag != -1 {\n")
			fmt.Fprintf(buf, "\t\t\t\t\treturn fmt.Errorf(\"expected NotNullValueFlag for slice element, got %%d\", flag)\n")
			fmt.Fprintf(buf, "\t\t\t\t}\n")
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadVarint64()\n", elemAccess)
		case types.Uint8:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadByte_()\n", elemAccess)
		case types.Uint16:
			fmt.Fprintf(buf, "\t\t\t\t%s = uint16(buf.ReadInt16())\n", elemAccess)
		case types.Uint32:
			fmt.Fprintf(buf, "\t\t\t\t%s = uint32(buf.ReadInt32())\n", elemAccess)
		case types.Uint, types.Uint64:
			fmt.Fprintf(buf, "\t\t\t\t%s = uint64(buf.ReadInt64())\n", elemAccess)
		case types.Float32:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadFloat32()\n", elemAccess)
		case types.Float64:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadFloat64()\n", elemAccess)
		case types.String:
			fmt.Fprintf(buf, "\t\t\t\tif flag := buf.ReadInt8(); flag != 0 {\n")
			fmt.Fprintf(buf, "\t\t\t\t\treturn fmt.Errorf(\"expected RefValueFlag for string element, got %%d\", flag)\n")
			fmt.Fprintf(buf, "\t\t\t\t}\n")
			fmt.Fprintf(buf, "\t\t\t\t%s = fory.ReadString(buf)\n", elemAccess)
		default:
			fmt.Fprintf(buf, "\t\t\t\t// TODO: unsupported basic type %s\n", basic.String())
		}
		return nil
	}

	// Handle named types
	if named, ok := elemType.(*types.Named); ok {
		typeStr := named.String()
		switch typeStr {
		case "time.Time":
			fmt.Fprintf(buf, "\t\t\t\tusec := buf.ReadInt64()\n")
			fmt.Fprintf(buf, "\t\t\t\t%s = fory.CreateTimeFromUnixMicro(usec)\n", elemAccess)
			return nil
		case "github.com/apache/fory/go/fory.Date":
			fmt.Fprintf(buf, "\t\t\t\tdays := buf.ReadInt32()\n")
			fmt.Fprintf(buf, "\t\t\t\t// Handle zero date marker\n")
			fmt.Fprintf(buf, "\t\t\t\tif days == int32(-2147483648) {\n")
			fmt.Fprintf(buf, "\t\t\t\t\t%s = fory.Date{Year: 0, Month: 0, Day: 0}\n", elemAccess)
			fmt.Fprintf(buf, "\t\t\t\t} else {\n")
			fmt.Fprintf(buf, "\t\t\t\t\tdiff := time.Duration(days) * 24 * time.Hour\n")
			fmt.Fprintf(buf, "\t\t\t\t\tt := time.Date(1970, 1, 1, 0, 0, 0, 0, time.Local).Add(diff)\n")
			fmt.Fprintf(buf, "\t\t\t\t\t%s = fory.Date{Year: t.Year(), Month: t.Month(), Day: t.Day()}\n", elemAccess)
			fmt.Fprintf(buf, "\t\t\t\t}\n")
			return nil
		}
		// Check if it's a struct
		if _, ok := named.Underlying().(*types.Struct); ok {
			fmt.Fprintf(buf, "\t\t\t\tf.ReadReferencable(buf, reflect.ValueOf(&%s).Elem())\n", elemAccess)
			return nil
		}
	}

	// Handle struct types
	if _, ok := elemType.Underlying().(*types.Struct); ok {
		fmt.Fprintf(buf, "\t\t\t\tf.ReadReferencable(buf, reflect.ValueOf(&%s).Elem())\n", elemAccess)
		return nil
	}

	fmt.Fprintf(buf, "\t\t\t\t// TODO: unsupported element type %s\n", elemType.String())
	return nil
}

// generateSliceReadInline generates inline slice deserialization code to match encoder behavior exactly
func generateSliceReadInline(buf *bytes.Buffer, sliceType *types.Slice, fieldAccess string) error {
	elemType := sliceType.Elem()

	// Read RefValueFlag first (slice is referencable)
	fmt.Fprintf(buf, "\tif flag := buf.ReadInt8(); flag != 0 {\n")
	fmt.Fprintf(buf, "\t\treturn fmt.Errorf(\"expected RefValueFlag for slice field, got %%d\", flag)\n")
	fmt.Fprintf(buf, "\t}\n")

	// Read slice length - use block scope to avoid variable name conflicts
	fmt.Fprintf(buf, "\t{\n")
	fmt.Fprintf(buf, "\t\tsliceLen := int(buf.ReadVarUint32())\n")
	fmt.Fprintf(buf, "\t\tif sliceLen == 0 {\n")
	fmt.Fprintf(buf, "\t\t\t%s = nil\n", fieldAccess)
	fmt.Fprintf(buf, "\t\t} else {\n")

	// Read collection header
	fmt.Fprintf(buf, "\t\t\tcollectFlag := buf.ReadInt8()\n")
	fmt.Fprintf(buf, "\t\t\t// We expect 12 (no ref tracking) or 13 (with ref tracking)\n")
	fmt.Fprintf(buf, "\t\t\tif collectFlag != 12 && collectFlag != 13 {\n")
	fmt.Fprintf(buf, "\t\t\t\treturn fmt.Errorf(\"unexpected collection flag: %%d\", collectFlag)\n")
	fmt.Fprintf(buf, "\t\t\t}\n")

	// Create slice
	fmt.Fprintf(buf, "\t\t\t%s = make(%s, sliceLen)\n", fieldAccess, sliceType.String())

	// Read elements
	fmt.Fprintf(buf, "\t\t\tfor i := 0; i < sliceLen; i++ {\n")

	// For each element, read NotNullValueFlag + TypeID + Value
	fmt.Fprintf(buf, "\t\t\t\t// Read element NotNullValueFlag\n")
	fmt.Fprintf(buf, "\t\t\t\tif flag := buf.ReadInt8(); flag != -1 {\n")
	fmt.Fprintf(buf, "\t\t\t\t\treturn fmt.Errorf(\"expected NotNullValueFlag for element, got %%d\", flag)\n")
	fmt.Fprintf(buf, "\t\t\t\t}\n")

	// Read and verify element type ID
	if err := generateElementTypeIDReadInline(buf, elemType); err != nil {
		return err
	}

	// Read element value
	if err := generateSliceElementReadInline(buf, elemType, fmt.Sprintf("%s[i]", fieldAccess)); err != nil {
		return err
	}

	fmt.Fprintf(buf, "\t\t\t}\n")
	fmt.Fprintf(buf, "\t\t}\n")
	fmt.Fprintf(buf, "\t}\n")

	return nil
}

// generateElementTypeIDReadInline generates element type ID verification
func generateElementTypeIDReadInline(buf *bytes.Buffer, elemType types.Type) error {
	// Handle basic types - verify the expected type ID
	if basic, ok := elemType.Underlying().(*types.Basic); ok {
		var expectedTypeID int
		switch basic.Kind() {
		case types.Bool:
			expectedTypeID = 1
		case types.Int8:
			expectedTypeID = 2
		case types.Int16:
			expectedTypeID = 3
		case types.Int32:
			expectedTypeID = 4
		case types.Int, types.Int64:
			expectedTypeID = 6
		case types.Uint8:
			expectedTypeID = 100
		case types.Uint16:
			expectedTypeID = 101
		case types.Uint32:
			expectedTypeID = 102
		case types.Uint, types.Uint64:
			expectedTypeID = 103
		case types.Float32:
			expectedTypeID = 10
		case types.Float64:
			expectedTypeID = 11
		case types.String:
			expectedTypeID = 12
		default:
			return fmt.Errorf("unsupported basic type for element type ID read: %s", basic.String())
		}

		fmt.Fprintf(buf, "\t\t\t\t// Read and verify element type ID\n")
		fmt.Fprintf(buf, "\t\t\t\tif typeID := buf.ReadVarInt32(); typeID != %d {\n", expectedTypeID)
		fmt.Fprintf(buf, "\t\t\t\t\treturn fmt.Errorf(\"expected element type ID %d, got %%d\", typeID)\n", expectedTypeID)
		fmt.Fprintf(buf, "\t\t\t\t}\n")

		return nil
	}
	return fmt.Errorf("unsupported element type for type ID read: %s", elemType.String())
}

// generateSliceElementReadInline generates code to read a single slice element value
func generateSliceElementReadInline(buf *bytes.Buffer, elemType types.Type, elemAccess string) error {
	// Handle basic types - read the actual value (type ID already verified above)
	if basic, ok := elemType.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadBool()\n", elemAccess)
		case types.Int8:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadInt8()\n", elemAccess)
		case types.Int16:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadInt16()\n", elemAccess)
		case types.Int32:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadVarint32()\n", elemAccess)
		case types.Int, types.Int64:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadVarint64()\n", elemAccess)
		case types.Uint8:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadByte_()\n", elemAccess)
		case types.Uint16:
			fmt.Fprintf(buf, "\t\t\t\t%s = uint16(buf.ReadInt16())\n", elemAccess)
		case types.Uint32:
			fmt.Fprintf(buf, "\t\t\t\t%s = uint32(buf.ReadInt32())\n", elemAccess)
		case types.Uint, types.Uint64:
			fmt.Fprintf(buf, "\t\t\t\t%s = uint64(buf.ReadInt64())\n", elemAccess)
		case types.Float32:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadFloat32()\n", elemAccess)
		case types.Float64:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadFloat64()\n", elemAccess)
		case types.String:
			fmt.Fprintf(buf, "\t\t\t\t%s = fory.ReadString(buf)\n", elemAccess)
		default:
			return fmt.Errorf("unsupported basic type for element read: %s", basic.String())
		}
		return nil
	}

	// Handle interface types
	if iface, ok := elemType.(*types.Interface); ok {
		if iface.Empty() {
			// For interface{} elements, use ReadReferencable for dynamic type handling
			fmt.Fprintf(buf, "\t\t\t\tf.ReadReferencable(buf, reflect.ValueOf(&%s).Elem())\n", elemAccess)
			return nil
		}
	}

	return fmt.Errorf("unsupported element type for read: %s", elemType.String())
}
