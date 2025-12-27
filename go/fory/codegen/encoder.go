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

	"github.com/apache/fory/go/fory"
)

// generateWriteTyped generates the strongly-typed WriteData method
func generateWriteTyped(buf *bytes.Buffer, s *StructInfo) error {
	fmt.Fprintf(buf, "// WriteTyped provides strongly-typed serialization with no reflection overhead\n")
	fmt.Fprintf(buf, "func (g *%s_ForyGenSerializer) WriteTyped(ctx *fory.WriteContext, v *%s) error {\n", s.Name, s.Name)
	fmt.Fprintf(buf, "\tbuf := ctx.Buffer()\n")

	// WriteData struct hash
	fmt.Fprintf(buf, "\t// WriteData struct hash for compatibility checking\n")
	fmt.Fprintf(buf, "\tbuf.WriteInt32(g.structHash)\n\n")

	// WriteData fields in sorted order
	fmt.Fprintf(buf, "\t// WriteData fields in sorted order\n")
	for _, field := range s.Fields {
		if err := generateFieldWriteTyped(buf, field); err != nil {
			return err
		}
	}

	fmt.Fprintf(buf, "\treturn nil\n")
	fmt.Fprintf(buf, "}\n\n")
	return nil
}

// generateWriteInterface generates interface compatibility method (WriteData)
func generateWriteInterface(buf *bytes.Buffer, s *StructInfo) error {
	// Generate WriteData method (reflect.Value-based API)
	fmt.Fprintf(buf, "// WriteData provides reflect.Value interface compatibility (implements fory.Serializer)\n")
	fmt.Fprintf(buf, "func (g *%s_ForyGenSerializer) WriteData(ctx *fory.WriteContext, value reflect.Value) {\n", s.Name)
	fmt.Fprintf(buf, "\tg.initHash(ctx.TypeResolver())\n")
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
	fmt.Fprintf(buf, "\tif err := g.WriteTyped(ctx, v); err != nil {\n")
	fmt.Fprintf(buf, "\t\tctx.SetError(fory.FromError(err))\n")
	fmt.Fprintf(buf, "\t}\n")
	fmt.Fprintf(buf, "}\n\n")
	return nil
}

// generateFieldWriteTyped generates field writing code for the typed method
func generateFieldWriteTyped(buf *bytes.Buffer, field *FieldInfo) error {
	fmt.Fprintf(buf, "\t// Field: %s (%s)\n", field.GoName, field.Type.String())

	fieldAccess := fmt.Sprintf("v.%s", field.GoName)

	// Handle special named types first
	// According to new spec, time types are "other internal types" and need WriteValue
	if named, ok := field.Type.(*types.Named); ok {
		typeStr := named.String()
		switch typeStr {
		case "time.Time", "github.com/apache/fory/go/fory.Date":
			// These types are "other internal types" in the new spec
			// They use: | null flag | value data | format
			fmt.Fprintf(buf, "\tctx.WriteValue(reflect.ValueOf(%s))\n", fieldAccess)
			return nil
		}
	}

	// Handle pointer types
	if _, ok := field.Type.(*types.Pointer); ok {
		// For all pointer types, use WriteValue
		fmt.Fprintf(buf, "\tctx.WriteValue(reflect.ValueOf(%s))\n", fieldAccess)
		return nil
	}

	// Handle basic types
	// Note: primitive serializers write values directly without NotNullValueFlag
	if basic, ok := field.Type.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool:
			fmt.Fprintf(buf, "\tbuf.WriteBool(%s)\n", fieldAccess)
		case types.Int8:
			fmt.Fprintf(buf, "\tbuf.WriteByte_(byte(%s))\n", fieldAccess)
		case types.Int16:
			fmt.Fprintf(buf, "\tbuf.WriteInt16(%s)\n", fieldAccess)
		case types.Int32:
			fmt.Fprintf(buf, "\tbuf.WriteVarint32(%s)\n", fieldAccess)
		case types.Int, types.Int64:
			fmt.Fprintf(buf, "\tbuf.WriteVarint64(%s)\n", fieldAccess)
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
			// In xlang mode, nullable=false is the default for struct fields.
			// With nullable=false, RefMode = RefModeNone, so no ref flag is written.
			// This matches reflection behavior in struct.go where refMode is calculated as:
			// refMode := RefModeNone; if trackRef && nullableFlag { refMode = RefModeTracking }
			// Since codegen follows xlang defaults (nullable=false), we don't write ref flags.
			fmt.Fprintf(buf, "\tctx.WriteString(%s)\n", fieldAccess)
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
			// because WriteValue produces incorrect length encoding.
			// In xlang mode, nullable=false is the default, so no null/ref flag is written.
			// A nil slice is treated as empty (length=0).
			fmt.Fprintf(buf, "\t// Dynamic slice []interface{} handling - manual serialization\n")
			fmt.Fprintf(buf, "\t{\n")
			fmt.Fprintf(buf, "\t\tsliceLen := 0\n")
			fmt.Fprintf(buf, "\t\tif %s != nil {\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\tsliceLen = len(%s)\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t}\n")
			fmt.Fprintf(buf, "\t\tbuf.WriteVaruint32(uint32(sliceLen))\n")
			fmt.Fprintf(buf, "\t\tif sliceLen > 0 {\n")
			fmt.Fprintf(buf, "\t\t\t// WriteData collection flags for dynamic slice []interface{}\n")
			fmt.Fprintf(buf, "\t\t\t// Only CollectionTrackingRef is set (no declared type, may have different types)\n")
			fmt.Fprintf(buf, "\t\t\tbuf.WriteInt8(1) // CollectionTrackingRef only\n")
			fmt.Fprintf(buf, "\t\t\t// WriteData each element using WriteValue\n")
			fmt.Fprintf(buf, "\t\t\tfor _, elem := range %s {\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t\tctx.WriteValue(reflect.ValueOf(elem))\n")
			fmt.Fprintf(buf, "\t\t\t}\n")
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

	// Handle map types
	if mapType, ok := field.Type.(*types.Map); ok {
		// For map types, we'll use manual serialization following the chunk-based format
		if err := generateMapWriteInline(buf, mapType, fieldAccess); err != nil {
			return err
		}
		return nil
	}

	// Handle interface types
	if iface, ok := field.Type.(*types.Interface); ok {
		if iface.Empty() {
			// For interface{}, use WriteValue for dynamic type handling
			fmt.Fprintf(buf, "\tctx.WriteValue(reflect.ValueOf(%s))\n", fieldAccess)
			return nil
		}
	}

	// Handle struct types
	if _, ok := field.Type.Underlying().(*types.Struct); ok {
		fmt.Fprintf(buf, "\tctx.WriteValue(reflect.ValueOf(%s))\n", fieldAccess)
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
			fmt.Fprintf(buf, "\t\tbuf.WriteVaruint32(%d) // BOOL\n", fory.BOOL)
		case types.Int8:
			fmt.Fprintf(buf, "\t\tbuf.WriteVaruint32(%d) // INT8\n", fory.INT8)
		case types.Int16:
			fmt.Fprintf(buf, "\t\tbuf.WriteVaruint32(%d) // INT16\n", fory.INT16)
		case types.Int32:
			fmt.Fprintf(buf, "\t\tbuf.WriteVaruint32(%d) // INT32\n", fory.INT32)
		case types.Int, types.Int64:
			fmt.Fprintf(buf, "\t\tbuf.WriteVaruint32(%d) // INT64\n", fory.INT64)
		case types.Uint8:
			fmt.Fprintf(buf, "\t\tbuf.WriteVaruint32(%d) // UINT8\n", fory.UINT8)
		case types.Uint16:
			fmt.Fprintf(buf, "\t\tbuf.WriteVaruint32(%d) // UINT16\n", fory.UINT16)
		case types.Uint32:
			fmt.Fprintf(buf, "\t\tbuf.WriteVaruint32(%d) // UINT32\n", fory.UINT32)
		case types.Uint, types.Uint64:
			fmt.Fprintf(buf, "\t\tbuf.WriteVaruint32(%d) // UINT64\n", fory.UINT64)
		case types.Float32:
			fmt.Fprintf(buf, "\t\tbuf.WriteVaruint32(%d) // FLOAT\n", fory.FLOAT)
		case types.Float64:
			fmt.Fprintf(buf, "\t\tbuf.WriteVaruint32(%d) // DOUBLE\n", fory.DOUBLE)
		case types.String:
			fmt.Fprintf(buf, "\t\tbuf.WriteVaruint32(%d) // STRING\n", fory.STRING)
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
			fmt.Fprintf(buf, "\t\tbuf.WriteVaruint32(%d) // TIMESTAMP\n", fory.TIMESTAMP)
			return nil
		case "github.com/apache/fory/go/fory.Date":
			fmt.Fprintf(buf, "\t\tbuf.WriteVaruint32(%d) // LOCAL_DATE\n", fory.LOCAL_DATE)
			return nil
		}
		// Check if it's a struct
		if _, ok := named.Underlying().(*types.Struct); ok {
			fmt.Fprintf(buf, "\t\tbuf.WriteVaruint32(%d) // NAMED_STRUCT\n", fory.NAMED_STRUCT)
			return nil
		}
	}

	// Handle struct types
	if _, ok := elemType.Underlying().(*types.Struct); ok {
		fmt.Fprintf(buf, "\t\tbuf.WriteVaruint32(%d) // NAMED_STRUCT\n", fory.NAMED_STRUCT)
		return nil
	}

	return fmt.Errorf("unsupported element type for type ID: %s", elemType.String())
}

// generateSliceWriteInline generates inline slice serialization code to match reflection behavior exactly
func generateSliceWriteInline(buf *bytes.Buffer, sliceType *types.Slice, fieldAccess string) error {
	elemType := sliceType.Elem()

	// Check if element type is a primitive type that uses ARRAY protocol
	if isPrimitiveSliceElemType(elemType) {
		return generatePrimitiveSliceWriteInline(buf, sliceType, fieldAccess)
	}

	// Check if element type is referencable (needs ref tracking)
	elemIsReferencable := isReferencableType(elemType)

	// In xlang mode, nullable=false is the default for struct fields.
	// With nullable=false, RefMode = RefModeNone, so no ref flag is written.
	// This matches reflection behavior in struct.go where:
	// refMode := RefModeNone; if trackRef && nullableFlag { refMode = RefModeTracking }
	// Since codegen follows xlang defaults (nullable=false), we don't write ref flags.

	// WriteData slice length - use block scope to avoid variable name conflicts
	fmt.Fprintf(buf, "\t{\n")
	fmt.Fprintf(buf, "\t\tsliceLen := 0\n")
	fmt.Fprintf(buf, "\t\tif %s != nil {\n", fieldAccess)
	fmt.Fprintf(buf, "\t\t\tsliceLen = len(%s)\n", fieldAccess)
	fmt.Fprintf(buf, "\t\t}\n")
	fmt.Fprintf(buf, "\t\tbuf.WriteVaruint32(uint32(sliceLen))\n")

	// WriteData collection header and elements for non-empty slice
	fmt.Fprintf(buf, "\t\tif sliceLen > 0 {\n")

	// For codegen, follow reflection's behavior for struct fields:
	// Set both CollectionIsSameType and CollectionIsDeclElementType
	// Add CollectionTrackingRef when ref tracking is enabled AND element is referencable
	// This matches sliceConcreteValueSerializer.WriteData which adds CollectionTrackingRef for referencable elements
	fmt.Fprintf(buf, "\t\t\tcollectFlag := 12 // CollectionIsSameType | CollectionIsDeclElementType\n")
	if elemIsReferencable {
		fmt.Fprintf(buf, "\t\t\tif ctx.TrackRef() {\n")
		fmt.Fprintf(buf, "\t\t\t\tcollectFlag |= 1 // CollectionTrackingRef for referencable element type\n")
		fmt.Fprintf(buf, "\t\t\t}\n")
	}
	fmt.Fprintf(buf, "\t\t\tbuf.WriteInt8(int8(collectFlag))\n")

	// Element type ID is NOT written when CollectionIsDeclElementType is set
	// The reader knows the element type from the field type

	// WriteData elements - with ref flags if element is referencable and tracking is enabled
	fmt.Fprintf(buf, "\t\t\tfor _, elem := range %s {\n", fieldAccess)
	if elemIsReferencable {
		// For referencable elements (like strings), need to write ref flag when tracking
		fmt.Fprintf(buf, "\t\t\t\tif ctx.TrackRef() {\n")
		fmt.Fprintf(buf, "\t\t\t\t\tbuf.WriteInt8(-1) // NotNullValueFlag for element\n")
		fmt.Fprintf(buf, "\t\t\t\t}\n")
	}
	if err := generateSliceElementWriteInline(buf, elemType, "elem"); err != nil {
		return err
	}

	fmt.Fprintf(buf, "\t\t\t}\n")
	fmt.Fprintf(buf, "\t\t}\n")
	fmt.Fprintf(buf, "\t}\n")

	return nil
}

// isPrimitiveSliceElemType checks if the element type is a primitive type that uses ARRAY protocol
func isPrimitiveSliceElemType(elemType types.Type) bool {
	if basic, ok := elemType.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool, types.Int8, types.Int16, types.Int32, types.Int64,
			types.Uint8, types.Float32, types.Float64:
			return true
		}
	}
	return false
}

// generatePrimitiveSliceWriteInline generates inline serialization code for primitive slices using ARRAY protocol
func generatePrimitiveSliceWriteInline(buf *bytes.Buffer, sliceType *types.Slice, fieldAccess string) error {
	elemType := sliceType.Elem()
	basic := elemType.Underlying().(*types.Basic)

	// In xlang mode, nullable=false is the default for struct fields.
	// With nullable=false, RefMode = RefModeNone, so no ref flag is written.
	// This matches reflection behavior in struct.go.

	// Call the exported helper function for each primitive type
	switch basic.Kind() {
	case types.Bool:
		fmt.Fprintf(buf, "\tfory.WriteBoolSlice(buf, %s)\n", fieldAccess)
	case types.Int8:
		fmt.Fprintf(buf, "\tfory.WriteInt8Slice(buf, %s)\n", fieldAccess)
	case types.Uint8:
		fmt.Fprintf(buf, "\tbuf.WriteLength(len(%s))\n", fieldAccess)
		fmt.Fprintf(buf, "\tif len(%s) > 0 {\n", fieldAccess)
		fmt.Fprintf(buf, "\t\tbuf.WriteBinary(%s)\n", fieldAccess)
		fmt.Fprintf(buf, "\t}\n")
	case types.Int16:
		fmt.Fprintf(buf, "\tfory.WriteInt16Slice(buf, %s)\n", fieldAccess)
	case types.Int32:
		fmt.Fprintf(buf, "\tfory.WriteInt32Slice(buf, %s)\n", fieldAccess)
	case types.Int64:
		fmt.Fprintf(buf, "\tfory.WriteInt64Slice(buf, %s)\n", fieldAccess)
	case types.Float32:
		fmt.Fprintf(buf, "\tfory.WriteFloat32Slice(buf, %s)\n", fieldAccess)
	case types.Float64:
		fmt.Fprintf(buf, "\tfory.WriteFloat64Slice(buf, %s)\n", fieldAccess)
	default:
		return fmt.Errorf("unsupported primitive type for ARRAY protocol: %s", basic.String())
	}

	return nil
}

// generateMapWriteInline generates inline map serialization code following the chunk-based format
func generateMapWriteInline(buf *bytes.Buffer, mapType *types.Map, fieldAccess string) error {
	keyType := mapType.Key()
	valueType := mapType.Elem()

	// Check if key or value types are interface{}
	keyIsInterface := false
	valueIsInterface := false
	if iface, ok := keyType.(*types.Interface); ok && iface.Empty() {
		keyIsInterface = true
	}
	if iface, ok := valueType.(*types.Interface); ok && iface.Empty() {
		valueIsInterface = true
	}

	// In xlang mode, nullable=false is the default for struct fields.
	// With nullable=false, RefMode = RefModeNone, so no ref flag is written.
	// This matches reflection behavior in struct.go.

	// WriteData map length
	fmt.Fprintf(buf, "\t{\n")
	fmt.Fprintf(buf, "\t\tmapLen := 0\n")
	fmt.Fprintf(buf, "\t\tif %s != nil {\n", fieldAccess)
	fmt.Fprintf(buf, "\t\t\tmapLen = len(%s)\n", fieldAccess)
	fmt.Fprintf(buf, "\t\t}\n")
	fmt.Fprintf(buf, "\t\tbuf.WriteVaruint32(uint32(mapLen))\n")

	// WriteData chunks for non-empty map
	fmt.Fprintf(buf, "\t\tif mapLen > 0 {\n")

	// Calculate KV header based on types
	fmt.Fprintf(buf, "\t\t\t// Calculate KV header flags\n")
	fmt.Fprintf(buf, "\t\t\tkvHeader := uint8(0)\n")

	// Check if ref tracking is enabled
	fmt.Fprintf(buf, "\t\t\tisRefTracking := ctx.TrackRef()\n")
	fmt.Fprintf(buf, "\t\t\t_ = isRefTracking // Mark as used to avoid warning\n")

	// Set header flags based on type properties
	if !keyIsInterface {
		// For concrete key types, check if they're referencable
		if isReferencableType(keyType) {
			fmt.Fprintf(buf, "\t\t\tif isRefTracking {\n")
			fmt.Fprintf(buf, "\t\t\t\tkvHeader |= 0x1 // track key ref\n")
			fmt.Fprintf(buf, "\t\t\t}\n")
		}
	} else {
		// For interface{} keys, always set not declared type flag
		fmt.Fprintf(buf, "\t\t\tkvHeader |= 0x4 // key type not declared\n")
	}

	if !valueIsInterface {
		// For concrete value types, check if they're referencable
		if isReferencableType(valueType) {
			fmt.Fprintf(buf, "\t\t\tif isRefTracking {\n")
			fmt.Fprintf(buf, "\t\t\t\tkvHeader |= 0x8 // track value ref\n")
			fmt.Fprintf(buf, "\t\t\t}\n")
		}
	} else {
		// For interface{} values, always set not declared type flag
		fmt.Fprintf(buf, "\t\t\tkvHeader |= 0x20 // value type not declared\n")
	}

	// WriteData map elements in chunks
	fmt.Fprintf(buf, "\t\t\tchunkSize := 0\n")
	fmt.Fprintf(buf, "\t\t\t_ = buf.WriterIndex() // chunkHeaderOffset\n")
	fmt.Fprintf(buf, "\t\t\tbuf.WriteInt8(int8(kvHeader)) // KV header\n")
	fmt.Fprintf(buf, "\t\t\tchunkSizeOffset := buf.WriterIndex()\n")
	fmt.Fprintf(buf, "\t\t\tbuf.WriteInt8(0) // placeholder for chunk size\n")

	fmt.Fprintf(buf, "\t\t\tfor mapKey, mapValue := range %s {\n", fieldAccess)

	// WriteData key
	if keyIsInterface {
		fmt.Fprintf(buf, "\t\t\t\tctx.WriteValue(reflect.ValueOf(mapKey))\n")
	} else {
		if err := generateMapKeyWrite(buf, keyType, "mapKey"); err != nil {
			return err
		}
	}

	// WriteData value
	if valueIsInterface {
		fmt.Fprintf(buf, "\t\t\t\tctx.WriteValue(reflect.ValueOf(mapValue))\n")
	} else {
		if err := generateMapValueWrite(buf, valueType, "mapValue"); err != nil {
			return err
		}
	}

	fmt.Fprintf(buf, "\t\t\t\tchunkSize++\n")
	fmt.Fprintf(buf, "\t\t\t\tif chunkSize >= 255 {\n")
	fmt.Fprintf(buf, "\t\t\t\t\t// WriteData chunk size and start new chunk\n")
	fmt.Fprintf(buf, "\t\t\t\t\tbuf.PutUint8(chunkSizeOffset, uint8(chunkSize))\n")
	fmt.Fprintf(buf, "\t\t\t\t\tif len(%s) > chunkSize {\n", fieldAccess)
	fmt.Fprintf(buf, "\t\t\t\t\t\tchunkSize = 0\n")
	fmt.Fprintf(buf, "\t\t\t\t\t\t_ = buf.WriterIndex() // chunkHeaderOffset\n")
	fmt.Fprintf(buf, "\t\t\t\t\t\tbuf.WriteInt8(int8(kvHeader)) // KV header\n")
	fmt.Fprintf(buf, "\t\t\t\t\t\tchunkSizeOffset = buf.WriterIndex()\n")
	fmt.Fprintf(buf, "\t\t\t\t\t\tbuf.WriteInt8(0) // placeholder for chunk size\n")
	fmt.Fprintf(buf, "\t\t\t\t\t}\n")
	fmt.Fprintf(buf, "\t\t\t\t}\n")

	fmt.Fprintf(buf, "\t\t\t}\n") // end for loop

	// WriteData final chunk size
	fmt.Fprintf(buf, "\t\t\tif chunkSize > 0 {\n")
	fmt.Fprintf(buf, "\t\t\t\tbuf.PutUint8(chunkSizeOffset, uint8(chunkSize))\n")
	fmt.Fprintf(buf, "\t\t\t}\n")

	fmt.Fprintf(buf, "\t\t}\n") // end if mapLen > 0
	fmt.Fprintf(buf, "\t}\n")   // end block scope

	return nil
}

// isReferencableType checks if a type is referencable (needs reference tracking)
func isReferencableType(t types.Type) bool {
	// Handle pointer types
	if _, ok := t.(*types.Pointer); ok {
		return true
	}

	// Basic types and their underlying types
	if basic, ok := t.Underlying().(*types.Basic); ok {
		return basic.Kind() == types.String
	}

	// Slices, maps, and interfaces are referencable
	switch t.Underlying().(type) {
	case *types.Slice, *types.Map, *types.Interface:
		return true
	}

	// Structs are referencable
	if _, ok := t.Underlying().(*types.Struct); ok {
		return true
	}

	return false
}

// generateMapKeyWrite generates code to write a map key
func generateMapKeyWrite(buf *bytes.Buffer, keyType types.Type, varName string) error {
	// For basic types, match reflection's serializer behavior
	if basic, ok := keyType.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Int:
			// intSerializer uses WriteInt64, not WriteVarint64
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteInt64(int64(%s))\n", varName)
		case types.String:
			// stringSerializer.NeedWriteRef() = false, write directly
			fmt.Fprintf(buf, "\t\t\t\tctx.WriteString(%s)\n", varName)
		default:
			return fmt.Errorf("unsupported map key type: %v", keyType)
		}
		return nil
	}

	// For other types, use WriteValue
	fmt.Fprintf(buf, "\t\t\t\tctx.WriteValue(reflect.ValueOf(%s))\n", varName)
	return nil
}

// generateMapValueWrite generates code to write a map value
func generateMapValueWrite(buf *bytes.Buffer, valueType types.Type, varName string) error {
	// For basic types, match reflection's serializer behavior
	if basic, ok := valueType.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Int:
			// intSerializer uses WriteInt64, not WriteVarint64
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteInt64(int64(%s))\n", varName)
		case types.String:
			// stringSerializer.NeedWriteRef() = false, write directly
			fmt.Fprintf(buf, "\t\t\t\tctx.WriteString(%s)\n", varName)
		default:
			return fmt.Errorf("unsupported map value type: %v", valueType)
		}
		return nil
	}

	// For other types, use WriteValue
	fmt.Fprintf(buf, "\t\t\t\tctx.WriteValue(reflect.ValueOf(%s))\n", varName)
	return nil
}

// generateElementTypeIDWriteInline generates element type ID write with specific indentation
func generateElementTypeIDWriteInline(buf *bytes.Buffer, elemType types.Type) error {
	// Handle basic types
	if basic, ok := elemType.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVaruint32(%d) // BOOL\n", fory.BOOL)
		case types.Int8:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVaruint32(%d) // INT8\n", fory.INT8)
		case types.Int16:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVaruint32(%d) // INT16\n", fory.INT16)
		case types.Int32:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVaruint32(%d) // INT32\n", fory.INT32)
		case types.Int, types.Int64:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVaruint32(%d) // INT64\n", fory.INT64)
		case types.Uint8:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVaruint32(%d) // UINT8\n", fory.UINT8)
		case types.Uint16:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVaruint32(%d) // UINT16\n", fory.UINT16)
		case types.Uint32:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVaruint32(%d) // UINT32\n", fory.UINT32)
		case types.Uint, types.Uint64:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVaruint32(%d) // UINT64\n", fory.UINT64)
		case types.Float32:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVaruint32(%d) // FLOAT\n", fory.FLOAT)
		case types.Float64:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVaruint32(%d) // DOUBLE\n", fory.DOUBLE)
		case types.String:
			fmt.Fprintf(buf, "\t\t\t\tbuf.WriteVaruint32(%d) // STRING\n", fory.STRING)
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
			fmt.Fprintf(buf, "\t\t\t\tctx.WriteString(%s)\n", elemAccess)
		default:
			return fmt.Errorf("unsupported basic type for element write: %s", basic.String())
		}
		return nil
	}

	// Handle interface types
	if iface, ok := elemType.(*types.Interface); ok {
		if iface.Empty() {
			// For interface{} elements, use WriteValue for dynamic type handling
			fmt.Fprintf(buf, "\t\t\t\tctx.WriteValue(reflect.ValueOf(%s))\n", elemAccess)
			return nil
		}
	}

	return fmt.Errorf("unsupported element type for write: %s", elemType.String())
}
