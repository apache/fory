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
			fmt.Fprintf(buf, "\t%s = buf.ReadBool()\n", fieldAccess)
		case types.Int8:
			fmt.Fprintf(buf, "\t%s = buf.ReadInt8()\n", fieldAccess)
		case types.Int16:
			fmt.Fprintf(buf, "\t%s = buf.ReadInt16()\n", fieldAccess)
		case types.Int32:
			fmt.Fprintf(buf, "\t%s = buf.ReadInt32()\n", fieldAccess)
		case types.Int, types.Int64:
			fmt.Fprintf(buf, "\t%s = buf.ReadInt64()\n", fieldAccess)
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
			fmt.Fprintf(buf, "\t%s = buf.ReadFloat64()\n", fieldAccess)
		case types.String:
			fmt.Fprintf(buf, "\t%s = fory.ReadString(buf)\n", fieldAccess)
		default:
			fmt.Fprintf(buf, "\t// TODO: unsupported basic type %s\n", basic.String())
		}
		return nil
	}

	// Handle struct types
	if _, ok := field.Type.Underlying().(*types.Struct); ok {
		fmt.Fprintf(buf, "\tf.ReadReferencable(buf, reflect.ValueOf(&%s).Elem())\n", fieldAccess)
		return nil
	}

	fmt.Fprintf(buf, "\t// TODO: unsupported type %s\n", field.Type.String())
	return nil
}
