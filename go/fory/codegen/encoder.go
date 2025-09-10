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
			fmt.Fprintf(buf, "\tbuf.WriteBool(%s)\n", fieldAccess)
		case types.Int8:
			fmt.Fprintf(buf, "\tbuf.WriteInt8(%s)\n", fieldAccess)
		case types.Int16:
			fmt.Fprintf(buf, "\tbuf.WriteInt16(%s)\n", fieldAccess)
		case types.Int32:
			fmt.Fprintf(buf, "\tbuf.WriteInt32(%s)\n", fieldAccess)
		case types.Int:
			fmt.Fprintf(buf, "\tbuf.WriteInt64(int64(%s))\n", fieldAccess)
		case types.Int64:
			fmt.Fprintf(buf, "\tbuf.WriteInt64(%s)\n", fieldAccess)
		case types.Uint8:
			fmt.Fprintf(buf, "\tbuf.WriteByte_(%s)\n", fieldAccess)
		case types.Uint16:
			fmt.Fprintf(buf, "\tbuf.WriteInt16(int16(%s))\n", fieldAccess)
		case types.Uint32:
			fmt.Fprintf(buf, "\tbuf.WriteInt32(int32(%s))\n", fieldAccess)
		case types.Uint, types.Uint64:
			fmt.Fprintf(buf, "\tbuf.WriteInt64(int64(%s))\n", fieldAccess)
		case types.Float32:
			fmt.Fprintf(buf, "\tbuf.WriteFloat32(%s)\n", fieldAccess)
		case types.Float64:
			fmt.Fprintf(buf, "\tbuf.WriteFloat64(%s)\n", fieldAccess)
		case types.String:
			fmt.Fprintf(buf, "\tfory.WriteString(buf, %s)\n", fieldAccess)
		default:
			fmt.Fprintf(buf, "\t// TODO: unsupported basic type %s\n", basic.String())
		}
		return nil
	}

	// Handle slice types
	if slice, ok := field.Type.(*types.Slice); ok {
		return generateSliceWrite(buf, fieldAccess, slice)
	}

	// Handle map types
	if mapType, ok := field.Type.(*types.Map); ok {
		return generateMapWrite(buf, fieldAccess, mapType)
	}

	// Handle struct types
	if _, ok := field.Type.Underlying().(*types.Struct); ok {
		fmt.Fprintf(buf, "\tf.WriteReferencable(buf, reflect.ValueOf(%s))\n", fieldAccess)
		return nil
	}

	fmt.Fprintf(buf, "\t// TODO: unsupported type %s\n", field.Type.String())
	return nil
}

// generateSliceWrite generates code to serialize a slice field
func generateSliceWrite(buf *bytes.Buffer, fieldAccess string, slice *types.Slice) error {
	elemType := slice.Elem()

	fmt.Fprintf(buf, "\t// Write slice length\n")
	fmt.Fprintf(buf, "\tbuf.WriteInt32(int32(len(%s)))\n", fieldAccess)
	fmt.Fprintf(buf, "\t// Write slice elements\n")
	fmt.Fprintf(buf, "\tfor _, elem := range %s {\n", fieldAccess)

	// Generate element writing code based on element type
	if err := generateElementWrite(buf, "elem", elemType); err != nil {
		return err
	}

	fmt.Fprintf(buf, "\t}\n")
	return nil
}

// generateElementWrite generates code to write a single element of any supported type
func generateElementWrite(buf *bytes.Buffer, elemAccess string, elemType types.Type) error {
	// Handle pointer types
	if _, ok := elemType.(*types.Pointer); ok {
		fmt.Fprintf(buf, "\t\tf.WriteReferencable(buf, reflect.ValueOf(%s))\n", elemAccess)
		return nil
	}

	// Handle nested slice types
	if slice, ok := elemType.(*types.Slice); ok {
		return generateSliceWrite(buf, elemAccess, slice)
	}

	// Handle special named types
	if named, ok := elemType.(*types.Named); ok {
		typeStr := named.String()
		switch typeStr {
		case "time.Time":
			fmt.Fprintf(buf, "\t\tbuf.WriteInt64(fory.GetUnixMicro(%s))\n", elemAccess)
			return nil
		case "github.com/apache/fory/go/fory.Date":
			fmt.Fprintf(buf, "\t\t// Handle zero date specially\n")
			fmt.Fprintf(buf, "\t\tif %s.Year == 0 && %s.Month == 0 && %s.Day == 0 {\n", elemAccess, elemAccess, elemAccess)
			fmt.Fprintf(buf, "\t\t\tbuf.WriteInt32(int32(-2147483648)) // Special marker for zero date\n")
			fmt.Fprintf(buf, "\t\t} else {\n")
			fmt.Fprintf(buf, "\t\t\tdiff := time.Date(%s.Year, %s.Month, %s.Day, 0, 0, 0, 0, time.Local).Sub(time.Date(1970, 1, 1, 0, 0, 0, 0, time.Local))\n", elemAccess, elemAccess, elemAccess)
			fmt.Fprintf(buf, "\t\t\tbuf.WriteInt32(int32(diff.Hours() / 24))\n")
			fmt.Fprintf(buf, "\t\t}\n")
			return nil
		}
	}

	// Handle struct types
	if _, ok := elemType.Underlying().(*types.Struct); ok {
		fmt.Fprintf(buf, "\t\tf.WriteReferencable(buf, reflect.ValueOf(%s))\n", elemAccess)
		return nil
	}

	// Handle basic types
	if basic, ok := elemType.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool:
			fmt.Fprintf(buf, "\t\tbuf.WriteBool(%s)\n", elemAccess)
		case types.Int8:
			fmt.Fprintf(buf, "\t\tbuf.WriteInt8(%s)\n", elemAccess)
		case types.Int16:
			fmt.Fprintf(buf, "\t\tbuf.WriteInt16(%s)\n", elemAccess)
		case types.Int32:
			fmt.Fprintf(buf, "\t\tbuf.WriteInt32(%s)\n", elemAccess)
		case types.Int:
			fmt.Fprintf(buf, "\t\tbuf.WriteInt64(int64(%s))\n", elemAccess)
		case types.Int64:
			fmt.Fprintf(buf, "\t\tbuf.WriteInt64(%s)\n", elemAccess)
		case types.Uint8:
			fmt.Fprintf(buf, "\t\tbuf.WriteByte_(%s)\n", elemAccess)
		case types.Uint16:
			fmt.Fprintf(buf, "\t\tbuf.WriteInt16(int16(%s))\n", elemAccess)
		case types.Uint32:
			fmt.Fprintf(buf, "\t\tbuf.WriteInt32(int32(%s))\n", elemAccess)
		case types.Uint, types.Uint64:
			fmt.Fprintf(buf, "\t\tbuf.WriteInt64(int64(%s))\n", elemAccess)
		case types.Float32:
			fmt.Fprintf(buf, "\t\tbuf.WriteFloat32(%s)\n", elemAccess)
		case types.Float64:
			fmt.Fprintf(buf, "\t\tbuf.WriteFloat64(%s)\n", elemAccess)
		case types.String:
			fmt.Fprintf(buf, "\t\tfory.WriteString(buf, %s)\n", elemAccess)
		default:
			fmt.Fprintf(buf, "\t\t// TODO: unsupported basic type %s\n", basic.String())
		}
		return nil
	}

	fmt.Fprintf(buf, "\t\t// TODO: unsupported element type %s\n", elemType.String())
	return nil
}

// generateMapWrite generates code to serialize a map field
func generateMapWrite(buf *bytes.Buffer, fieldAccess string, mapType *types.Map) error {
	keyType := mapType.Key()
	valueType := mapType.Elem()

	fmt.Fprintf(buf, "\t// Write map length\n")
	fmt.Fprintf(buf, "\tbuf.WriteInt32(int32(len(%s)))\n", fieldAccess)
	fmt.Fprintf(buf, "\t// Write map entries (order not guaranteed for performance)\n")
	fmt.Fprintf(buf, "\tfor key, value := range %s {\n", fieldAccess)

	// Generate key writing code
	if err := generateElementWrite(buf, "key", keyType); err != nil {
		return err
	}

	// Generate value writing code
	if err := generateElementWrite(buf, "value", valueType); err != nil {
		return err
	}

	fmt.Fprintf(buf, "\t}\n")
	return nil
}
