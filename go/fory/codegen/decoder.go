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

	// Read and verify struct hash - even in schema evolution mode, structSerializer.Read reads hash
	fmt.Fprintf(buf, "\t// Read and verify struct hash for schema compatibility\n")
	fmt.Fprintf(buf, "\texpectedHash := int32(%d)\n", hash)
	fmt.Fprintf(buf, "\tactualHash := buf.ReadInt32()\n")
	fmt.Fprintf(buf, "\tif actualHash != expectedHash {\n")
	fmt.Fprintf(buf, "\t\treturn fmt.Errorf(\"struct hash mismatch: expected %%d, got %%d\", expectedHash, actualHash)\n")
	fmt.Fprintf(buf, "\t}\n\n")

	// Read fields in sorted order
	fmt.Fprintf(buf, "\t// Read fields in same order as write\n")

	// Track if refFlag has been declared to avoid duplicate declarations
	refFlagDeclared := false
	for _, field := range s.Fields {
		if err := generateFieldReadTypedWithFlag(buf, field, &refFlagDeclared); err != nil {
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
func generateFieldReadTypedWithFlag(buf *bytes.Buffer, field *FieldInfo, refFlagDeclared *bool) error {
	fmt.Fprintf(buf, "\t// Field: %s (%s)\n", field.GoName, field.Type.String())

	fieldAccess := fmt.Sprintf("v.%s", field.GoName)

	// Handle special named types first
	if named, ok := field.Type.(*types.Named); ok {
		typeStr := named.String()
		switch typeStr {
		case "time.Time":
			fmt.Fprintf(buf, "\t// Read non-referencable time.Time field\n")
			fmt.Fprintf(buf, "\tbuf.ReadInt8() // Read and discard NotNullValueFlag\n")
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

	// Handle basic types with correct decoding per xlang spec
	if basic, ok := field.Type.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.String:
			// String is final type with ref tracking per xlang spec
			fmt.Fprintf(buf, "\t// Read string field: final type with ref tracking per xlang spec\n")
			fmt.Fprintf(buf, "\tbuf.ReadInt8() // Read and discard RefValueFlag\n")
			fmt.Fprintf(buf, "\t%s = fory.ReadString(buf) // stringSerializer.Read\n", fieldAccess)
		default:
			// All other basic types are non-referencable -> readBySerializer -> read NotNullValueFlag + serializer.Read
			fmt.Fprintf(buf, "\t// Read %s field: referencable=false -> readBySerializer\n", basic.String())
			fmt.Fprintf(buf, "\tbuf.ReadInt8() // Read and discard NotNullValueFlag from writeNonReferencableBySerializer\n")

			switch basic.Kind() {
			case types.Bool:
				fmt.Fprintf(buf, "\t%s = buf.ReadBool() // boolSerializer.Read\n", fieldAccess)
			case types.Int8:
				fmt.Fprintf(buf, "\t%s = int8(buf.ReadByte_()) // int8Serializer.Read\n", fieldAccess)
			case types.Int16:
				fmt.Fprintf(buf, "\t%s = buf.ReadInt16() // int16Serializer.Read\n", fieldAccess)
			case types.Int32:
				fmt.Fprintf(buf, "\t%s = buf.ReadVarint32() // int32Serializer.Read\n", fieldAccess)
			case types.Int:
				fmt.Fprintf(buf, "\t%s = int(buf.ReadInt64()) // intSerializer.Read\n", fieldAccess)
			case types.Int64:
				fmt.Fprintf(buf, "\t%s = buf.ReadVarint64() // int64Serializer.Read\n", fieldAccess)
			case types.Uint8:
				fmt.Fprintf(buf, "\t%s = buf.ReadByte_() // byteSerializer.Read\n", fieldAccess)
			case types.Uint16:
				fmt.Fprintf(buf, "\t%s = uint16(buf.ReadInt16()) // uint16Serializer.Read\n", fieldAccess)
			case types.Uint32:
				fmt.Fprintf(buf, "\t%s = uint32(buf.ReadInt32()) // uint32Serializer.Read\n", fieldAccess)
			case types.Uint, types.Uint64:
				fmt.Fprintf(buf, "\t%s = uint64(buf.ReadInt64()) // uint64Serializer.Read\n", fieldAccess)
			case types.Float32:
				fmt.Fprintf(buf, "\t%s = buf.ReadFloat32() // float32Serializer.Read\n", fieldAccess)
			case types.Float64:
				fmt.Fprintf(buf, "\t%s = buf.ReadFloat64() // float64Serializer.Read\n", fieldAccess)
			default:
				fmt.Fprintf(buf, "\t// TODO: unsupported basic type %s\n", basic.String())
			}
		}
		return nil
	}

	// Handle slice types (referencable) - use ReadReferencable to match reflection behavior
	if _, ok := field.Type.(*types.Slice); ok {
		// Reflection uses f.ReadReferencable for slices without fieldInfo_.serializer
		fmt.Fprintf(buf, "\t// Read slice field: use ReadReferencable to match reflection behavior\n")
		fmt.Fprintf(buf, "\tf.ReadReferencable(buf, reflect.ValueOf(v).Elem().FieldByName(\"%s\"))\n", field.GoName)
		return nil
	}

	// Handle map types (referencable - use ReadReferencable to match reflection behavior)
	if _, ok := field.Type.(*types.Map); ok {
		// Use reflect to get an addressable field value
		fmt.Fprintf(buf, "\t// Read map field: use ReadReferencable to match reflection behavior\n")
		fmt.Fprintf(buf, "\tf.ReadReferencable(buf, reflect.ValueOf(v).Elem().FieldByName(\"%s\"))\n", field.GoName)
		return nil
	}

	// Handle struct types (referencable)
	if _, ok := field.Type.Underlying().(*types.Struct); ok {
		// Use reflect to get an addressable field value
		fmt.Fprintf(buf, "\tf.ReadReferencable(buf, reflect.ValueOf(v).Elem().FieldByName(\"%s\"))\n", field.GoName)
		return nil
	}

	fmt.Fprintf(buf, "\t// TODO: unsupported type %s\n", field.Type.String())
	return nil
}

// generateSliceRead generates code to deserialize a slice field
func generateSliceRead(buf *bytes.Buffer, fieldAccess string, slice *types.Slice) error {
	elemType := slice.Elem()

	fmt.Fprintf(buf, "\t// Read slice length\n")
	fmt.Fprintf(buf, "\tif sliceLength := buf.ReadInt32(); sliceLength < 0 {\n")
	fmt.Fprintf(buf, "\t\t%s = nil\n", fieldAccess)
	fmt.Fprintf(buf, "\t} else {\n")

	// Get the element type string for slice allocation
	elemTypeStr := getGoTypeString(elemType)
	fmt.Fprintf(buf, "\t\t// Allocate slice with correct capacity\n")
	fmt.Fprintf(buf, "\t\t%s = make([]%s, sliceLength)\n", fieldAccess, elemTypeStr)
	fmt.Fprintf(buf, "\t\t// Read slice elements\n")
	fmt.Fprintf(buf, "\t\tfor i := int32(0); i < sliceLength; i++ {\n")

	// Generate element reading code
	elemAccess := fmt.Sprintf("%s[i]", fieldAccess)
	if err := generateElementRead(buf, elemAccess, elemType); err != nil {
		return err
	}

	fmt.Fprintf(buf, "\t\t}\n")
	fmt.Fprintf(buf, "\t}\n")
	return nil
}

// generateElementRead generates code to read a single element of any supported type
func generateElementRead(buf *bytes.Buffer, elemAccess string, elemType types.Type) error {
	// Handle pointer types
	if _, ok := elemType.(*types.Pointer); ok {
		fmt.Fprintf(buf, "\t\t\t// Create addressable value for pointer type\n")
		fmt.Fprintf(buf, "\t\t\tptrValue := reflect.New(%s.Type().Elem())\n", elemAccess)
		fmt.Fprintf(buf, "\t\t\tf.ReadReferencable(buf, ptrValue.Elem())\n")
		fmt.Fprintf(buf, "\t\t\t%s = ptrValue.Interface().()\n", elemAccess)
		return nil
	}

	// Handle nested slice types
	if slice, ok := elemType.(*types.Slice); ok {
		return generateSliceRead(buf, elemAccess, slice)
	}

	// Handle special named types
	if named, ok := elemType.(*types.Named); ok {
		typeStr := named.String()
		switch typeStr {
		case "time.Time":
			fmt.Fprintf(buf, "\t\t\tusec := buf.ReadInt64()\n")
			fmt.Fprintf(buf, "\t\t\t%s = fory.CreateTimeFromUnixMicro(usec)\n", elemAccess)
			return nil
		case "github.com/apache/fory/go/fory.Date":
			fmt.Fprintf(buf, "\t\t\tdays := buf.ReadInt32()\n")
			fmt.Fprintf(buf, "\t\t\t// Handle zero date marker\n")
			fmt.Fprintf(buf, "\t\t\tif days == int32(-2147483648) {\n")
			fmt.Fprintf(buf, "\t\t\t\t%s = fory.Date{Year: 0, Month: 0, Day: 0}\n", elemAccess)
			fmt.Fprintf(buf, "\t\t\t} else {\n")
			fmt.Fprintf(buf, "\t\t\t\tdiff := time.Duration(days) * 24 * time.Hour\n")
			fmt.Fprintf(buf, "\t\t\t\tt := time.Date(1970, 1, 1, 0, 0, 0, 0, time.Local).Add(diff)\n")
			fmt.Fprintf(buf, "\t\t\t\t%s = fory.Date{Year: t.Year(), Month: t.Month(), Day: t.Day()}\n", elemAccess)
			fmt.Fprintf(buf, "\t\t\t}\n")
			return nil
		}
	}

	// Handle struct types
	if _, ok := elemType.Underlying().(*types.Struct); ok {
		fmt.Fprintf(buf, "\t\t\t// Create addressable value for struct type\n")
		fmt.Fprintf(buf, "\t\t\tstructValue := reflect.ValueOf(&%s).Elem()\n", elemAccess)
		fmt.Fprintf(buf, "\t\t\tf.ReadReferencable(buf, structValue)\n")
		return nil
	}

	// Handle basic types
	if basic, ok := elemType.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool:
			fmt.Fprintf(buf, "\t\t\t%s = buf.ReadBool()\n", elemAccess)
		case types.Int8:
			fmt.Fprintf(buf, "\t\t\t%s = buf.ReadInt8()\n", elemAccess)
		case types.Int16:
			fmt.Fprintf(buf, "\t\t\t%s = buf.ReadInt16()\n", elemAccess)
		case types.Int32:
			fmt.Fprintf(buf, "\t\t\t%s = buf.ReadVarint32()\n", elemAccess)
		case types.Int:
			fmt.Fprintf(buf, "\t\t\t%s = int(buf.ReadInt64())\n", elemAccess)
		case types.Int64:
			fmt.Fprintf(buf, "\t\t\t%s = buf.ReadInt64()\n", elemAccess)
		case types.Uint8:
			fmt.Fprintf(buf, "\t\t\t%s = buf.ReadByte_()\n", elemAccess)
		case types.Uint16:
			fmt.Fprintf(buf, "\t\t\t%s = uint16(buf.ReadInt16())\n", elemAccess)
		case types.Uint32:
			fmt.Fprintf(buf, "\t\t\t%s = uint32(buf.ReadInt32())\n", elemAccess)
		case types.Uint, types.Uint64:
			fmt.Fprintf(buf, "\t\t\t%s = uint64(buf.ReadInt64())\n", elemAccess)
		case types.Float32:
			fmt.Fprintf(buf, "\t\t\t%s = buf.ReadFloat32()\n", elemAccess)
		case types.Float64:
			fmt.Fprintf(buf, "\t\t\t%s = buf.ReadFloat64()\n", elemAccess)
		case types.String:
			fmt.Fprintf(buf, "\t\t\t%s = fory.ReadString(buf)\n", elemAccess)
		default:
			fmt.Fprintf(buf, "\t\t\t// TODO: unsupported basic type %s\n", basic.String())
		}
		return nil
	}

	fmt.Fprintf(buf, "\t\t\t// TODO: unsupported element type %s\n", elemType.String())
	return nil
}

// generateMapRead generates code to deserialize a map field
func generateMapRead(buf *bytes.Buffer, fieldAccess string, mapType *types.Map) error {
	keyType := mapType.Key()
	valueType := mapType.Elem()

	fmt.Fprintf(buf, "\t// Read map length\n")
	fmt.Fprintf(buf, "\tif mapLength := buf.ReadInt32(); mapLength < 0 {\n")
	fmt.Fprintf(buf, "\t\t%s = nil\n", fieldAccess)
	fmt.Fprintf(buf, "\t} else if mapLength == 0 {\n")
	fmt.Fprintf(buf, "\t\t// Create empty map\n")

	// Get the map type string for map allocation
	mapTypeStr := getGoTypeString(mapType)
	fmt.Fprintf(buf, "\t\t%s = make(%s)\n", fieldAccess, mapTypeStr)
	fmt.Fprintf(buf, "\t} else {\n")
	fmt.Fprintf(buf, "\t\t// Create map with capacity\n")
	fmt.Fprintf(buf, "\t\t%s = make(%s, mapLength)\n", fieldAccess, mapTypeStr)
	fmt.Fprintf(buf, "\t\t// Read key-value pairs\n")
	fmt.Fprintf(buf, "\t\tfor i := int32(0); i < mapLength; i++ {\n")

	// Generate key and value variable declarations
	keyTypeStr := getGoTypeString(keyType)
	valueTypeStr := getGoTypeString(valueType)
	fmt.Fprintf(buf, "\t\t\tvar key %s\n", keyTypeStr)
	fmt.Fprintf(buf, "\t\t\tvar value %s\n", valueTypeStr)

	// Generate key reading code
	if err := generateElementRead(buf, "key", keyType); err != nil {
		return err
	}

	// Generate value reading code
	if err := generateElementRead(buf, "value", valueType); err != nil {
		return err
	}

	// Assign to map
	fmt.Fprintf(buf, "\t\t\t%s[key] = value\n", fieldAccess)
	fmt.Fprintf(buf, "\t\t}\n")
	fmt.Fprintf(buf, "\t}\n")
	return nil
}

// generateSliceFieldRead generates code to deserialize a slice field in LIST format (for struct fields)
func generateSliceFieldRead(buf *bytes.Buffer, fieldAccess string, slice *types.Slice, refFlagDeclared *bool) error {
	elemType := slice.Elem()

	fmt.Fprintf(buf, "\t// Read slice field in LIST format per xlang spec\n")

	// Handle refFlag declaration (only declare once per function)
	if *refFlagDeclared {
		fmt.Fprintf(buf, "\trefFlag = buf.ReadInt8()\n")
	} else {
		fmt.Fprintf(buf, "\trefFlag := buf.ReadInt8()\n")
		*refFlagDeclared = true
	}

	fmt.Fprintf(buf, "\tif refFlag == -3 { // NullFlag\n")
	fmt.Fprintf(buf, "\t\t%s = nil\n", fieldAccess)
	fmt.Fprintf(buf, "\t} else {\n")
	fmt.Fprintf(buf, "\t\t// LIST format: length | elements_header | elements_data\n")
	fmt.Fprintf(buf, "\t\tlength := int(buf.ReadVarUint32()) // list length as varint per spec\n")

	// Allocate slice
	fmt.Fprintf(buf, "\t\t// Allocate slice\n")
	fmt.Fprintf(buf, "\t\t%s = make(%s, length)\n", fieldAccess, slice.String())

	// Only read header and elements if slice is not empty
	fmt.Fprintf(buf, "\t\t// Only read header and elements if slice is not empty\n")
	fmt.Fprintf(buf, "\t\tif length > 0 {\n")
	fmt.Fprintf(buf, "\t\t\t_ = buf.ReadInt8() // Read and discard elements header (0x0D)\n")

	// Read elements based on element type
	if basic, ok := elemType.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Int32:
			fmt.Fprintf(buf, "\t\t\tfor i := 0; i < length; i++ {\n")
			fmt.Fprintf(buf, "\t\t\t\tbuf.ReadInt8() // Read and discard NotNullValueFlag\n")
			fmt.Fprintf(buf, "\t\t\t\tbuf.ReadInt8() // Read and discard INT32 TypeId\n")
			fmt.Fprintf(buf, "\t\t\t\t%s[i] = buf.ReadVarint32() // varint32 for int32 elements\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t}\n")
		case types.Bool:
			fmt.Fprintf(buf, "\t\t\tfor i := 0; i < length; i++ {\n")
			fmt.Fprintf(buf, "\t\t\t\tbuf.ReadInt8() // Read and discard NotNullValueFlag\n")
			fmt.Fprintf(buf, "\t\t\t\tbuf.ReadInt8() // Read and discard BOOL TypeId\n")
			fmt.Fprintf(buf, "\t\t\t\t%s[i] = buf.ReadBool() // bool elements\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t}\n")
		case types.String:
			fmt.Fprintf(buf, "\t\t\tfor i := 0; i < length; i++ {\n")
			fmt.Fprintf(buf, "\t\t\t\tbuf.ReadInt8() // Read and discard NotNullValueFlag\n")
			fmt.Fprintf(buf, "\t\t\t\tbuf.ReadInt8() // Read and discard STRING TypeId\n")
			fmt.Fprintf(buf, "\t\t\t\t%s[i] = fory.ReadString(buf) // string elements\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t}\n")
		default:
			// For other primitive types, use generic approach
			fmt.Fprintf(buf, "\t\t\tfor i := 0; i < length; i++ {\n")
			fmt.Fprintf(buf, "\t\t\t\t// TODO: Read other primitive type element\n")
			fmt.Fprintf(buf, "\t\t\t\tf.ReadReferencable(buf, reflect.ValueOf(&%s[i]))\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t}\n")
		}
	} else {
		// For non-primitive types, use generic approach
		fmt.Fprintf(buf, "\t\t\tfor i := 0; i < length; i++ {\n")
		fmt.Fprintf(buf, "\t\t\t\tf.ReadReferencable(buf, reflect.ValueOf(&%s[i]))\n", fieldAccess)
		fmt.Fprintf(buf, "\t\t\t}\n")
	}

	fmt.Fprintf(buf, "\t\t}\n") // End of if length > 0
	fmt.Fprintf(buf, "\t}\n")   // End of else (not nil)
	return nil
}
