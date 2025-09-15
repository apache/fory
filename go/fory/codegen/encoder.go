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

	// Write struct hash - even in schema evolution mode, structSerializer.Write writes hash
	fmt.Fprintf(buf, "\t// Write struct hash for schema compatibility\n")
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
			fmt.Fprintf(buf, "\t// Write non-referencable time.Time field\n")
			fmt.Fprintf(buf, "\tbuf.WriteInt8(-1) // NotNullValueFlag\n")
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

	// Handle basic types using reflection's actual writeBySerializer logic
	if basic, ok := field.Type.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.String:
			// String is referencable -> writeReferencableBySerializer -> RefValueFlag + stringSerializer.Write
			fmt.Fprintf(buf, "\t// Write string field: referencable=true -> writeReferencableBySerializer\n")
			fmt.Fprintf(buf, "\tbuf.WriteInt8(0) // RefValueFlag from writeReferencableBySerializer\n")
			fmt.Fprintf(buf, "\tfory.WriteString(buf, %s) // stringSerializer.Write\n", fieldAccess)
		default:
			// All other basic types are non-referencable -> writeNonReferencableBySerializer -> NotNullValueFlag + serializer.Write
			fmt.Fprintf(buf, "\t// Write %s field: referencable=false -> writeNonReferencableBySerializer\n", basic.String())
			fmt.Fprintf(buf, "\tbuf.WriteInt8(-1) // NotNullValueFlag from writeNonReferencableBySerializer\n")

			switch basic.Kind() {
			case types.Bool:
				fmt.Fprintf(buf, "\tbuf.WriteBool(%s) // boolSerializer.Write\n", fieldAccess)
			case types.Int8:
				fmt.Fprintf(buf, "\tbuf.WriteByte_(byte(%s)) // int8Serializer.Write\n", fieldAccess)
			case types.Int16:
				fmt.Fprintf(buf, "\tbuf.WriteInt16(int16(%s)) // int16Serializer.Write\n", fieldAccess)
			case types.Int32:
				fmt.Fprintf(buf, "\tbuf.WriteVarint32(%s) // int32Serializer.Write\n", fieldAccess)
			case types.Int:
				fmt.Fprintf(buf, "\tbuf.WriteInt64(int64(%s)) // intSerializer.Write\n", fieldAccess)
			case types.Int64:
				fmt.Fprintf(buf, "\tbuf.WriteVarint64(%s) // int64Serializer.Write\n", fieldAccess)
			case types.Uint8:
				fmt.Fprintf(buf, "\tbuf.WriteByte_(byte(%s)) // byteSerializer.Write\n", fieldAccess)
			case types.Uint16:
				fmt.Fprintf(buf, "\tbuf.WriteInt16(int16(%s)) // uint16Serializer.Write\n", fieldAccess)
			case types.Uint32:
				fmt.Fprintf(buf, "\tbuf.WriteInt32(int32(%s)) // uint32Serializer.Write\n", fieldAccess)
			case types.Uint, types.Uint64:
				fmt.Fprintf(buf, "\tbuf.WriteInt64(int64(%s)) // uint64Serializer.Write\n", fieldAccess)
			case types.Float32:
				fmt.Fprintf(buf, "\tbuf.WriteFloat32(%s) // float32Serializer.Write\n", fieldAccess)
			case types.Float64:
				fmt.Fprintf(buf, "\tbuf.WriteFloat64(%s) // float64Serializer.Write\n", fieldAccess)
			default:
				fmt.Fprintf(buf, "\t// TODO: unsupported basic type %s\n", basic.String())
			}
		}
		return nil
	}

	// Handle slice types (referencable) - use WriteReferencable to match reflection behavior
	if _, ok := field.Type.(*types.Slice); ok {
		// For struct fields, use WriteReferencable to ensure compatibility with ReadReferencable
		fmt.Fprintf(buf, "\t// Write slice field: use WriteReferencable to match reflection behavior\n")
		fmt.Fprintf(buf, "\tf.WriteReferencable(buf, reflect.ValueOf(%s))\n", fieldAccess)
		return nil
	}

	// Handle map types (referencable - use WriteReferencable to match reflection behavior)
	if _, ok := field.Type.(*types.Map); ok {
		// For struct fields, use WriteReferencable to ensure compatibility with ReadReferencable
		fmt.Fprintf(buf, "\t// Write map field: use WriteReferencable to match reflection behavior\n")
		fmt.Fprintf(buf, "\tf.WriteReferencable(buf, reflect.ValueOf(%s))\n", fieldAccess)
		return nil
	}

	// Handle struct types
	if _, ok := field.Type.Underlying().(*types.Struct); ok {
		fmt.Fprintf(buf, "\tf.WriteReferencable(buf, reflect.ValueOf(%s))\n", fieldAccess)
		return nil
	}

	fmt.Fprintf(buf, "\t// TODO: unsupported type %s\n", field.Type.String())
	return nil
}

// generateSliceFieldWriteWithListProtocol generates code to serialize a slice field using LIST protocol
// This matches reflection's behavior for struct fields
func generateSliceFieldWriteWithListProtocol(buf *bytes.Buffer, fieldAccess string, sliceType *types.Slice) error {
	elemType := sliceType.Elem()

	fmt.Fprintf(buf, "\t// Write slice field using LIST protocol (matching reflection for struct fields)\n")
	fmt.Fprintf(buf, "\tif %s == nil {\n", fieldAccess)
	fmt.Fprintf(buf, "\t\tbuf.WriteInt8(-3) // NullFlag\n")
	fmt.Fprintf(buf, "\t} else {\n")
	fmt.Fprintf(buf, "\t\tbuf.WriteInt8(0) // RefValueFlag for referencable slice\n")
	fmt.Fprintf(buf, "\t\tbuf.WriteInt8(21) // LIST TypeId\n")

	// Write length and elements header
	fmt.Fprintf(buf, "\t\t// LIST format: length | elements_header | elements_data\n")
	fmt.Fprintf(buf, "\t\tbuf.WriteVarUint32(uint32(len(%s))) // list length\n", fieldAccess)

	fmt.Fprintf(buf, "\t\tif len(%s) > 0 {\n", fieldAccess)

	// Determine elements header based on element type
	if basic, ok := elemType.(*types.Basic); ok && basic.Kind() != types.String {
		// For non-string basic types: elements are not nullable, same type, known type
		fmt.Fprintf(buf, "\t\t\t// Elements header: 0x04 = not nullable, same type, not declared type\n")
		fmt.Fprintf(buf, "\t\t\tbuf.WriteInt8(0x04) // CollectionNotDeclElementType\n")

		// Write element type ID
		switch basic.Kind() {
		case types.Bool:
			fmt.Fprintf(buf, "\t\t\tbuf.WriteInt8(1) // BOOL TypeId\n")
		case types.Int32:
			fmt.Fprintf(buf, "\t\t\tbuf.WriteInt8(4) // INT32 TypeId\n")
		case types.Int64:
			fmt.Fprintf(buf, "\t\t\tbuf.WriteInt8(6) // INT64 TypeId\n")
		case types.Float32:
			fmt.Fprintf(buf, "\t\t\tbuf.WriteInt8(10) // FLOAT32 TypeId\n")
		case types.Float64:
			fmt.Fprintf(buf, "\t\t\tbuf.WriteInt8(11) // FLOAT64 TypeId\n")
		default:
			fmt.Fprintf(buf, "\t\t\t// TODO: handle other basic types\n")
		}

		// Write elements without null flags
		fmt.Fprintf(buf, "\t\t\t// Write elements (no null flags needed)\n")
		fmt.Fprintf(buf, "\t\t\tfor _, elem := range %s {\n", fieldAccess)
		switch basic.Kind() {
		case types.Bool:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteBool(elem)\n")
		case types.Int32:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVarint32(elem)\n")
		case types.Int64:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVarint64(elem)\n")
		case types.Float32:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteFloat32(elem)\n")
		case types.Float64:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteFloat64(elem)\n")
		default:
			fmt.Fprintf(buf, "\t\t\t\t// TODO: write element\n")
		}
		fmt.Fprintf(buf, "\t\t\t}\n")
	} else if basic, ok := elemType.(*types.Basic); ok && basic.Kind() == types.String {
		// For string: elements are referencable
		fmt.Fprintf(buf, "\t\t\t// Elements header: 0x05 = tracking refs, same type, not declared type\n")
		fmt.Fprintf(buf, "\t\t\tbuf.WriteInt8(0x05) // CollectionTrackingRef | CollectionNotDeclElementType\n")
		fmt.Fprintf(buf, "\t\t\tbuf.WriteInt8(12) // STRING TypeId\n")

		// Write string elements with ref tracking
		fmt.Fprintf(buf, "\t\t\tfor _, elem := range %s {\n", fieldAccess)
		fmt.Fprintf(buf, "\t\t\t\tbuf.WriteInt8(0) // RefValueFlag for string\n")
		fmt.Fprintf(buf, "\t\t\t\tfory.WriteString(buf, elem)\n")
		fmt.Fprintf(buf, "\t\t\t}\n")
	} else {
		// For other types, use generic approach
		fmt.Fprintf(buf, "\t\t\t// Generic LIST handling for complex types\n")
		fmt.Fprintf(buf, "\t\t\tf.WriteReferencable(buf, reflect.ValueOf(%s))\n", fieldAccess)
		fmt.Fprintf(buf, "\t\t}\n") // Close if len > 0
		fmt.Fprintf(buf, "\t}\n")   // Close else
		return nil
	}

	fmt.Fprintf(buf, "\t\t}\n") // Close if len > 0
	fmt.Fprintf(buf, "\t}\n")   // Close else
	return nil
}

// generateArrayFieldWrite generates code to serialize a basic type slice using array protocol
func generateArrayFieldWrite(buf *bytes.Buffer, fieldAccess string, elemType *types.Basic) error {
	fmt.Fprintf(buf, "\t// Write basic type slice using array protocol\n")
	fmt.Fprintf(buf, "\tif %s == nil {\n", fieldAccess)
	fmt.Fprintf(buf, "\t\tbuf.WriteInt8(-3) // NullFlag\n")
	fmt.Fprintf(buf, "\t} else {\n")
	fmt.Fprintf(buf, "\t\tbuf.WriteInt8(0) // RefValueFlag for referencable array\n")

	// Write type ID for the array type
	switch elemType.Kind() {
	case types.Bool:
		fmt.Fprintf(buf, "\t\tbuf.WriteInt8(31) // BOOL_ARRAY TypeId\n")
		fmt.Fprintf(buf, "\t\t// Array protocol: length (element count) + elements\n")
		fmt.Fprintf(buf, "\t\tbuf.WriteLength(len(%s)) // element count for bool array\n", fieldAccess)
		fmt.Fprintf(buf, "\t\tfor _, elem := range %s {\n", fieldAccess)
		fmt.Fprintf(buf, "\t\t\tbuf.WriteBool(elem)\n")
		fmt.Fprintf(buf, "\t\t}\n")
	case types.Int32:
		fmt.Fprintf(buf, "\t\tbuf.WriteInt8(33) // INT32_ARRAY TypeId\n")
		fmt.Fprintf(buf, "\t\t// Array protocol: length (byte size) + elements\n")
		fmt.Fprintf(buf, "\t\tbuf.WriteLength(len(%s) * 4) // byte size for int32 array\n", fieldAccess)
		fmt.Fprintf(buf, "\t\tfor _, elem := range %s {\n", fieldAccess)
		fmt.Fprintf(buf, "\t\t\tbuf.WriteInt32(elem)\n")
		fmt.Fprintf(buf, "\t\t}\n")
	case types.Int64:
		fmt.Fprintf(buf, "\t\tbuf.WriteInt8(34) // INT64_ARRAY TypeId\n")
		fmt.Fprintf(buf, "\t\t// Array protocol: length (byte size) + elements\n")
		fmt.Fprintf(buf, "\t\tbuf.WriteLength(len(%s) * 8) // byte size for int64 array\n", fieldAccess)
		fmt.Fprintf(buf, "\t\tfor _, elem := range %s {\n", fieldAccess)
		fmt.Fprintf(buf, "\t\t\tbuf.WriteInt64(elem)\n")
		fmt.Fprintf(buf, "\t\t}\n")
	case types.Int16:
		fmt.Fprintf(buf, "\t\tbuf.WriteInt8(32) // INT16_ARRAY TypeId\n")
		fmt.Fprintf(buf, "\t\t// Array protocol: length (byte size) + elements\n")
		fmt.Fprintf(buf, "\t\tbuf.WriteLength(len(%s) * 2) // byte size for int16 array\n", fieldAccess)
		fmt.Fprintf(buf, "\t\tfor _, elem := range %s {\n", fieldAccess)
		fmt.Fprintf(buf, "\t\t\tbuf.WriteInt16(elem)\n")
		fmt.Fprintf(buf, "\t\t}\n")
	case types.Float32:
		fmt.Fprintf(buf, "\t\tbuf.WriteInt8(35) // FLOAT32_ARRAY TypeId\n")
		fmt.Fprintf(buf, "\t\t// Array protocol: length (byte size) + elements\n")
		fmt.Fprintf(buf, "\t\tbuf.WriteLength(len(%s) * 4) // byte size for float32 array\n", fieldAccess)
		fmt.Fprintf(buf, "\t\tfor _, elem := range %s {\n", fieldAccess)
		fmt.Fprintf(buf, "\t\t\tbuf.WriteFloat32(elem)\n")
		fmt.Fprintf(buf, "\t\t}\n")
	case types.Float64:
		fmt.Fprintf(buf, "\t\tbuf.WriteInt8(36) // FLOAT64_ARRAY TypeId\n")
		fmt.Fprintf(buf, "\t\t// Array protocol: length (byte size) + elements\n")
		fmt.Fprintf(buf, "\t\tbuf.WriteLength(len(%s) * 8) // byte size for float64 array\n", fieldAccess)
		fmt.Fprintf(buf, "\t\tfor _, elem := range %s {\n", fieldAccess)
		fmt.Fprintf(buf, "\t\t\tbuf.WriteFloat64(elem)\n")
		fmt.Fprintf(buf, "\t\t}\n")
	default:
		// For other basic types (like string), fall back to LIST protocol
		fmt.Fprintf(buf, "\t\t// String and other non-numeric types use LIST protocol\n")
		fmt.Fprintf(buf, "\t\tf.WriteReferencable(buf, reflect.ValueOf(%s))\n", fieldAccess)
		fmt.Fprintf(buf, "\t}\n") // Close the else block
		return nil
	}

	fmt.Fprintf(buf, "\t}\n") // Close the else block
	return nil
}

// generateSliceFieldWrite generates code to serialize a slice field in LIST format (for struct fields)
func generateSliceFieldWrite(buf *bytes.Buffer, fieldAccess string, slice *types.Slice) error {
	elemType := slice.Elem()

	fmt.Fprintf(buf, "\t// Write slice field in LIST format per xlang spec\n")
	fmt.Fprintf(buf, "\tif %s == nil {\n", fieldAccess)
	fmt.Fprintf(buf, "\t\tbuf.WriteInt8(-3) // NullFlag\n")
	fmt.Fprintf(buf, "\t} else {\n")
	fmt.Fprintf(buf, "\t\tbuf.WriteInt8(0) // REF_VALUE_FLAG for referencable slice\n")

	// LIST format per spec: length | elements_header | elements_data
	fmt.Fprintf(buf, "\t\t// LIST format: length | elements_header | elements_data\n")
	fmt.Fprintf(buf, "\t\tbuf.WriteVarUint32(uint32(len(%s))) // list length as varint per spec\n", fieldAccess)

	// Only write elements header and data if slice is not empty
	if basic, ok := elemType.Underlying().(*types.Basic); ok {
		fmt.Fprintf(buf, "\t\t// Only write header and elements if slice is not empty\n")
		fmt.Fprintf(buf, "\t\tif len(%s) > 0 {\n", fieldAccess)
		fmt.Fprintf(buf, "\t\t\tbuf.WriteInt8(0x0D) // elements header: observed from reflection\n")

		// Write elements directly for primitive types
		switch basic.Kind() {
		case types.Int32:
			fmt.Fprintf(buf, "\t\t\tfor _, elem := range %s {\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteInt8(-1) // NotNullValueFlag\n")
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteInt8(4) // INT32 TypeId\n")
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVarint32(elem) // varint32 for int32 elements\n")
			fmt.Fprintf(buf, "\t\t\t}\n")
		case types.Bool:
			fmt.Fprintf(buf, "\t\t\tfor _, elem := range %s {\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteInt8(-1) // NotNullValueFlag\n")
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteInt8(1) // BOOL TypeId\n")
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteBool(elem) // bool elements\n")
			fmt.Fprintf(buf, "\t\t\t}\n")
		case types.String:
			fmt.Fprintf(buf, "\t\t\tfor _, elem := range %s {\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteInt8(-1) // NotNullValueFlag\n")
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteInt8(12) // STRING TypeId\n")
			fmt.Fprintf(buf, "\t\t\t\tfory.WriteString(buf, elem) // string elements\n")
			fmt.Fprintf(buf, "\t\t\t}\n")
		case types.Int8:
			fmt.Fprintf(buf, "\t\t\tfor _, elem := range %s {\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteInt8(elem) // int8 elements\n")
			fmt.Fprintf(buf, "\t\t\t}\n")
		case types.Int16:
			fmt.Fprintf(buf, "\t\t\tfor _, elem := range %s {\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteInt16(elem) // int16 elements\n")
			fmt.Fprintf(buf, "\t\t\t}\n")
		case types.Int64:
			fmt.Fprintf(buf, "\t\t\tfor _, elem := range %s {\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteInt64(elem) // int64 elements\n")
			fmt.Fprintf(buf, "\t\t\t}\n")
		case types.Float32:
			fmt.Fprintf(buf, "\t\t\tfor _, elem := range %s {\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteFloat32(elem) // float32 elements\n")
			fmt.Fprintf(buf, "\t\t\t}\n")
		case types.Float64:
			fmt.Fprintf(buf, "\t\t\tfor _, elem := range %s {\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteFloat64(elem) // float64 elements\n")
			fmt.Fprintf(buf, "\t\t\t}\n")
		case types.Uint8:
			fmt.Fprintf(buf, "\t\t\tfor _, elem := range %s {\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteByte_(elem) // uint8 elements\n")
			fmt.Fprintf(buf, "\t\t\t}\n")
		default:
			// For other primitive types, use generic approach
			fmt.Fprintf(buf, "\t\t\tfor _, elem := range %s {\n", fieldAccess)
			if err := generateElementWrite(buf, "elem", elemType); err != nil {
				return err
			}
			fmt.Fprintf(buf, "\t\t\t}\n")
		}
		fmt.Fprintf(buf, "\t\t}\n") // End of if len() > 0
	} else {
		// For non-primitive types, may need different header flags
		fmt.Fprintf(buf, "\t\tif len(%s) > 0 {\n", fieldAccess)
		fmt.Fprintf(buf, "\t\t\tbuf.WriteInt8(0) // elements header: assuming same type for now\n")
		fmt.Fprintf(buf, "\t\t\tfor _, elem := range %s {\n", fieldAccess)
		if err := generateElementWrite(buf, "elem", elemType); err != nil {
			return err
		}
		fmt.Fprintf(buf, "\t\t\t}\n")
		fmt.Fprintf(buf, "\t\t}\n") // End of if len() > 0
	}

	fmt.Fprintf(buf, "\t}\n") // End of else (not nil)
	return nil
}

// generateSliceWrite generates code to serialize a slice field (legacy/direct slice serialization)
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
			fmt.Fprintf(buf, "\t\tbuf.WriteVarint32(%s)\n", elemAccess)
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
