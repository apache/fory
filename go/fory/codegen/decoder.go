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

// generateReadTyped generates the strongly-typed ReadData method
func generateReadTyped(buf *bytes.Buffer, s *StructInfo) error {
	fmt.Fprintf(buf, "// ReadTyped provides strongly-typed deserialization with no reflection overhead\n")
	fmt.Fprintf(buf, "func (g *%s_ForyGenSerializer) ReadTyped(ctx *fory.ReadContext, v *%s) error {\n", s.Name, s.Name)
	fmt.Fprintf(buf, "\tbuf := ctx.Buffer()\n")
	fmt.Fprintf(buf, "\terr := ctx.Err() // Get error pointer for deferred error checking\n\n")

	// ReadData and verify struct hash
	fmt.Fprintf(buf, "\t// ReadData and verify struct hash\n")
	fmt.Fprintf(buf, "\tif got := buf.ReadInt32(err); got != g.structHash {\n")
	fmt.Fprintf(buf, "\t\tif ctx.HasError() {\n")
	fmt.Fprintf(buf, "\t\t\treturn ctx.TakeError()\n")
	fmt.Fprintf(buf, "\t\t}\n")
	fmt.Fprintf(buf, "\t\treturn fory.HashMismatchError(got, g.structHash, \"%s\")\n", s.Name)
	fmt.Fprintf(buf, "\t}\n\n")

	// ReadData fields in sorted order
	fmt.Fprintf(buf, "\t// ReadData fields in same order as write\n")
	for _, field := range s.Fields {
		if err := generateFieldReadTyped(buf, field); err != nil {
			return err
		}
	}

	// Final error check for any accumulated errors
	fmt.Fprintf(buf, "\n\t// Final deferred error check\n")
	fmt.Fprintf(buf, "\tif ctx.HasError() {\n")
	fmt.Fprintf(buf, "\t\treturn ctx.TakeError()\n")
	fmt.Fprintf(buf, "\t}\n")
	fmt.Fprintf(buf, "\treturn nil\n")
	fmt.Fprintf(buf, "}\n\n")
	return nil
}

// generateReadInterface generates interface compatibility method (ReadData)
func generateReadInterface(buf *bytes.Buffer, s *StructInfo) error {
	// Generate ReadData method (reflect.Value-based API)
	fmt.Fprintf(buf, "// ReadData provides reflect.Value interface compatibility (implements fory.Serializer)\n")
	fmt.Fprintf(buf, "func (g *%s_ForyGenSerializer) ReadData(ctx *fory.ReadContext, value reflect.Value) {\n", s.Name)
	fmt.Fprintf(buf, "\tg.initHash(ctx.TypeResolver())\n")
	fmt.Fprintf(buf, "\t// Convert reflect.Value to concrete type and delegate to typed method\n")
	fmt.Fprintf(buf, "\tvar v *%s\n", s.Name)
	fmt.Fprintf(buf, "\tif value.Kind() == reflect.Ptr {\n")
	fmt.Fprintf(buf, "\t\tif value.IsNil() {\n")
	fmt.Fprintf(buf, "\t\t\t// For pointer types, allocate using value.Type().Elem()\n")
	fmt.Fprintf(buf, "\t\t\tvalue.Set(reflect.New(value.Type().Elem()))\n")
	fmt.Fprintf(buf, "\t\t}\n")
	fmt.Fprintf(buf, "\t\tv = value.Interface().(*%s)\n", s.Name)
	fmt.Fprintf(buf, "\t} else {\n")
	fmt.Fprintf(buf, "\t\t// value must be addressable for read\n")
	fmt.Fprintf(buf, "\t\tv = value.Addr().Interface().(*%s)\n", s.Name)
	fmt.Fprintf(buf, "\t}\n")
	fmt.Fprintf(buf, "\t// Delegate to strongly-typed method for maximum performance\n")
	fmt.Fprintf(buf, "\tif err := g.ReadTyped(ctx, v); err != nil {\n")
	fmt.Fprintf(buf, "\t\tctx.SetError(fory.FromError(err))\n")
	fmt.Fprintf(buf, "\t}\n")
	fmt.Fprintf(buf, "}\n\n")
	return nil
}

// generateFieldReadTyped generates field reading code for the typed method
func generateFieldReadTyped(buf *bytes.Buffer, field *FieldInfo) error {
	fmt.Fprintf(buf, "\t// Field: %s (%s)\n", field.GoName, field.Type.String())

	fieldAccess := fmt.Sprintf("v.%s", field.GoName)
	if field.IsOptional {
		return generateOptionReadTyped(buf, field, fieldAccess)
	}

	// Handle special named types first
	// According to new spec, time types are "other internal types" and use ReadValue
	if named, ok := field.Type.(*types.Named); ok {
		typeStr := named.String()
		switch typeStr {
		case "time.Time", "github.com/apache/fory/go/fory.Date":
			// These types are "other internal types" in the new spec
			// They use: | null flag | value data | format
			fmt.Fprintf(buf, "\tctx.ReadValue(reflect.ValueOf(&%s).Elem(), fory.RefModeTracking, true)\n", fieldAccess)
			return nil
		}
	}

	// Handle pointer types
	if _, ok := field.Type.(*types.Pointer); ok {
		// For pointer types, use ReadValue
		fmt.Fprintf(buf, "\tctx.ReadValue(reflect.ValueOf(&%s).Elem(), fory.RefModeTracking, true)\n", fieldAccess)
		return nil
	}

	// Handle basic types
	// Note: primitive serializers read values directly without NotNullValueFlag check
	// Use error-aware methods for deferred error checking
	if basic, ok := field.Type.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool:
			fmt.Fprintf(buf, "\t%s = buf.ReadBool(err)\n", fieldAccess)
		case types.Int8:
			fmt.Fprintf(buf, "\t%s = buf.ReadInt8(err)\n", fieldAccess)
		case types.Int16:
			fmt.Fprintf(buf, "\t%s = buf.ReadInt16(err)\n", fieldAccess)
		case types.Int32:
			fmt.Fprintf(buf, "\t%s = buf.ReadVarint32(err)\n", fieldAccess)
		case types.Int, types.Int64:
			fmt.Fprintf(buf, "\t%s = buf.ReadVarint64(err)\n", fieldAccess)
		case types.Uint8:
			fmt.Fprintf(buf, "\t%s = buf.ReadByte(err)\n", fieldAccess)
		case types.Uint16:
			fmt.Fprintf(buf, "\t%s = uint16(buf.ReadInt16(err))\n", fieldAccess)
		case types.Uint32:
			fmt.Fprintf(buf, "\t%s = uint32(buf.ReadInt32(err))\n", fieldAccess)
		case types.Uint, types.Uint64:
			fmt.Fprintf(buf, "\t%s = uint64(buf.ReadInt64(err))\n", fieldAccess)
		case types.Float32:
			fmt.Fprintf(buf, "\t%s = buf.ReadFloat32(err)\n", fieldAccess)
		case types.Float64:
			fmt.Fprintf(buf, "\t%s = buf.ReadFloat64(err)\n", fieldAccess)
		case types.String:
			// In xlang mode, nullable=false is the default for struct fields.
			// With nullable=false, RefMode = RefModeNone, so no ref flag is written/read.
			// This matches reflection behavior in struct.go.
			fmt.Fprintf(buf, "\t%s = ctx.ReadString()\n", fieldAccess)
		default:
			fmt.Fprintf(buf, "\t// TODO: unsupported basic type %s\n", basic.String())
		}
		return nil
	}

	// Handle slice types
	if slice, ok := field.Type.(*types.Slice); ok {
		elemType := slice.Elem()
		// Check if element type is any (dynamic type)
		// Unwrap alias types (e.g., 'any' is an alias for 'interface{}')
		unwrappedElem := types.Unalias(elemType)
		if iface, ok := unwrappedElem.(*types.Interface); ok && iface.Empty() {
			// For []any, we need to manually implement the deserialization
			// to match our custom encoding.
			// In xlang mode, slices are NOT nullable by default.
			// In native Go mode, slices can be nil and need null flags.
			fmt.Fprintf(buf, "\t// Dynamic slice []any handling - manual deserialization\n")
			fmt.Fprintf(buf, "\t{\n")
			fmt.Fprintf(buf, "\t\tisXlang := ctx.TypeResolver().IsXlang()\n")
			fmt.Fprintf(buf, "\t\tif isXlang {\n")
			fmt.Fprintf(buf, "\t\t\t// xlang mode: slices are not nullable, read directly without null flag\n")
			fmt.Fprintf(buf, "\t\t\tsliceLen := int(buf.ReadVaruint32(err))\n")
			fmt.Fprintf(buf, "\t\t\tif sliceLen == 0 {\n")
			fmt.Fprintf(buf, "\t\t\t\t%s = make([]any, 0)\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t} else {\n")
			fmt.Fprintf(buf, "\t\t\t\t// ReadData collection flags (ignore for now)\n")
			fmt.Fprintf(buf, "\t\t\t\t_ = buf.ReadInt8(err)\n")
			fmt.Fprintf(buf, "\t\t\t\t// Create slice with proper capacity\n")
			fmt.Fprintf(buf, "\t\t\t\t%s = make([]any, sliceLen)\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t\t// ReadData each element using ReadValue\n")
			fmt.Fprintf(buf, "\t\t\t\tfor i := range %s {\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t\t\tctx.ReadValue(reflect.ValueOf(&%s[i]).Elem(), fory.RefModeTracking, true)\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t\t}\n")
			fmt.Fprintf(buf, "\t\t\t}\n")
			fmt.Fprintf(buf, "\t\t} else {\n")
			fmt.Fprintf(buf, "\t\t\t// Native Go mode: slices are nullable, read null flag\n")
			fmt.Fprintf(buf, "\t\t\tnullFlag := buf.ReadInt8(err)\n")
			fmt.Fprintf(buf, "\t\t\tif nullFlag == -3 {\n") // NullFlag
			fmt.Fprintf(buf, "\t\t\t\t%s = nil\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t} else {\n")
			fmt.Fprintf(buf, "\t\t\t\tsliceLen := int(buf.ReadVaruint32(err))\n")
			fmt.Fprintf(buf, "\t\t\t\tif sliceLen == 0 {\n")
			fmt.Fprintf(buf, "\t\t\t\t\t%s = make([]any, 0)\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t\t} else {\n")
			fmt.Fprintf(buf, "\t\t\t\t\t// ReadData collection flags (ignore for now)\n")
			fmt.Fprintf(buf, "\t\t\t\t\t_ = buf.ReadInt8(err)\n")
			fmt.Fprintf(buf, "\t\t\t\t\t// Create slice with proper capacity\n")
			fmt.Fprintf(buf, "\t\t\t\t\t%s = make([]any, sliceLen)\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t\t\t// ReadData each element using ReadValue\n")
			fmt.Fprintf(buf, "\t\t\t\t\tfor i := range %s {\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t\t\t\tctx.ReadValue(reflect.ValueOf(&%s[i]).Elem(), fory.RefModeTracking, true)\n", fieldAccess)
			fmt.Fprintf(buf, "\t\t\t\t\t}\n")
			fmt.Fprintf(buf, "\t\t\t\t}\n")
			fmt.Fprintf(buf, "\t\t\t}\n")
			fmt.Fprintf(buf, "\t\t}\n")
			fmt.Fprintf(buf, "\t}\n")
			return nil
		}
		// For static element types, use optimized inline generation
		if err := generateSliceReadInline(buf, slice, fieldAccess); err != nil {
			return err
		}
		return nil
	}

	// Handle map types
	if mapType, ok := field.Type.(*types.Map); ok {
		// For map types, we'll use manual deserialization following the chunk-based format
		if err := generateMapReadInline(buf, mapType, fieldAccess); err != nil {
			return err
		}
		return nil
	}

	// Handle interface types (including 'any' which is an alias for interface{})
	unwrappedType := types.Unalias(field.Type)
	if iface, ok := unwrappedType.(*types.Interface); ok {
		if iface.Empty() {
			// For any, use ReadValue for dynamic type handling
			fmt.Fprintf(buf, "\tctx.ReadValue(reflect.ValueOf(&%s).Elem(), fory.RefModeTracking, true)\n", fieldAccess)
			return nil
		}
	}

	// Handle struct types
	if _, ok := field.Type.Underlying().(*types.Struct); ok {
		fmt.Fprintf(buf, "\tctx.ReadValue(reflect.ValueOf(&%s).Elem(), fory.RefModeTracking, true)\n", fieldAccess)
		return nil
	}

	fmt.Fprintf(buf, "\t// TODO: unsupported type %s\n", field.Type.String())
	return nil
}

func generateOptionReadTyped(buf *bytes.Buffer, field *FieldInfo, fieldAccess string) error {
	elemType := field.OptionalElem
	if elemType == nil {
		fmt.Fprintf(buf, "\tctx.ReadValue(reflect.ValueOf(&%s).Elem(), fory.RefModeTracking, true)\n", fieldAccess)
		return nil
	}
	fmt.Fprintf(buf, "\t{\n")
	if isReferencableType(elemType) {
		fmt.Fprintf(buf, "\t\tvar optValue %s\n", elemType.String())
		fmt.Fprintf(buf, "\t\tif ctx.TrackRef() {\n")
		fmt.Fprintf(buf, "\t\t\trefID, refErr := ctx.RefResolver().TryPreserveRefId(buf)\n")
		fmt.Fprintf(buf, "\t\t\tif refErr != nil {\n")
		fmt.Fprintf(buf, "\t\t\t\treturn refErr\n")
		fmt.Fprintf(buf, "\t\t\t}\n")
		fmt.Fprintf(buf, "\t\t\tif refID < int32(fory.NotNullValueFlag) {\n")
		fmt.Fprintf(buf, "\t\t\t\tif refID == int32(fory.NullFlag) {\n")
		fmt.Fprintf(buf, "\t\t\t\t\t%s = optional.None[%s]()\n", fieldAccess, elemType.String())
		fmt.Fprintf(buf, "\t\t\t\t\treturn nil\n")
		fmt.Fprintf(buf, "\t\t\t\t}\n")
		fmt.Fprintf(buf, "\t\t\t\tobj := ctx.RefResolver().GetReadObject(refID)\n")
		fmt.Fprintf(buf, "\t\t\t\tif obj.IsValid() {\n")
		fmt.Fprintf(buf, "\t\t\t\t\ttarget := reflect.ValueOf(&optValue).Elem()\n")
		fmt.Fprintf(buf, "\t\t\t\t\tif obj.Type().AssignableTo(target.Type()) {\n")
		fmt.Fprintf(buf, "\t\t\t\t\t\ttarget.Set(obj)\n")
		fmt.Fprintf(buf, "\t\t\t\t\t\t%s = optional.Some(optValue)\n", fieldAccess)
		fmt.Fprintf(buf, "\t\t\t\t\t\treturn nil\n")
		fmt.Fprintf(buf, "\t\t\t\t\t}\n")
		fmt.Fprintf(buf, "\t\t\t\t}\n")
		fmt.Fprintf(buf, "\t\t\t\t%s = optional.None[%s]()\n", fieldAccess, elemType.String())
		fmt.Fprintf(buf, "\t\t\t\treturn nil\n")
		fmt.Fprintf(buf, "\t\t\t}\n")
		if err := generateOptionValueRead(buf, elemType, "optValue"); err != nil {
			return err
		}
		fmt.Fprintf(buf, "\t\t\tif refID >= 0 {\n")
		fmt.Fprintf(buf, "\t\t\t\tctx.RefResolver().SetReadObject(refID, reflect.ValueOf(optValue))\n")
		fmt.Fprintf(buf, "\t\t\t}\n")
		fmt.Fprintf(buf, "\t\t\t%s = optional.Some(optValue)\n", fieldAccess)
		fmt.Fprintf(buf, "\t\t\treturn nil\n")
		fmt.Fprintf(buf, "\t\t}\n")
	}
	fmt.Fprintf(buf, "\t\tflag := buf.ReadInt8(err)\n")
	fmt.Fprintf(buf, "\t\tif flag == fory.NullFlag {\n")
	fmt.Fprintf(buf, "\t\t\t%s = optional.None[%s]()\n", fieldAccess, elemType.String())
	fmt.Fprintf(buf, "\t\t} else {\n")
	fmt.Fprintf(buf, "\t\t\tvar optValue %s\n", elemType.String())
	if err := generateOptionValueRead(buf, elemType, "optValue"); err != nil {
		return err
	}
	fmt.Fprintf(buf, "\t\t\t%s = optional.Some(optValue)\n", fieldAccess)
	fmt.Fprintf(buf, "\t\t}\n")
	fmt.Fprintf(buf, "\t}\n")
	return nil
}

func generateOptionValueRead(buf *bytes.Buffer, elemType types.Type, valueExpr string) error {
	// Handle special named types first
	if named, ok := elemType.(*types.Named); ok {
		typeStr := named.String()
		switch typeStr {
		case "time.Time", "github.com/apache/fory/go/fory.Date":
			fmt.Fprintf(buf, "\t\t\tctx.ReadValue(reflect.ValueOf(&%s).Elem(), fory.RefModeNone, true)\n", valueExpr)
			return nil
		}
		if _, ok := named.Underlying().(*types.Struct); ok {
			fmt.Fprintf(buf, "\t\t\tctx.ReadValue(reflect.ValueOf(&%s).Elem(), fory.RefModeNone, true)\n", valueExpr)
			return nil
		}
	}

	if basic, ok := elemType.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool:
			fmt.Fprintf(buf, "\t\t\t%s = buf.ReadBool(err)\n", valueExpr)
		case types.Int8:
			fmt.Fprintf(buf, "\t\t\t%s = buf.ReadInt8(err)\n", valueExpr)
		case types.Int16:
			fmt.Fprintf(buf, "\t\t\t%s = buf.ReadInt16(err)\n", valueExpr)
		case types.Int32:
			fmt.Fprintf(buf, "\t\t\t%s = buf.ReadVarint32(err)\n", valueExpr)
		case types.Int:
			fmt.Fprintf(buf, "\t\t\t%s = int(buf.ReadVarint64(err))\n", valueExpr)
		case types.Int64:
			fmt.Fprintf(buf, "\t\t\t%s = buf.ReadVarint64(err)\n", valueExpr)
		case types.Uint8:
			fmt.Fprintf(buf, "\t\t\t%s = buf.ReadByte(err)\n", valueExpr)
		case types.Uint16:
			fmt.Fprintf(buf, "\t\t\t%s = uint16(buf.ReadInt16(err))\n", valueExpr)
		case types.Uint32:
			fmt.Fprintf(buf, "\t\t\t%s = uint32(buf.ReadInt32(err))\n", valueExpr)
		case types.Uint:
			fmt.Fprintf(buf, "\t\t\t%s = uint(buf.ReadInt64(err))\n", valueExpr)
		case types.Uint64:
			fmt.Fprintf(buf, "\t\t\t%s = uint64(buf.ReadInt64(err))\n", valueExpr)
		case types.Float32:
			fmt.Fprintf(buf, "\t\t\t%s = buf.ReadFloat32(err)\n", valueExpr)
		case types.Float64:
			fmt.Fprintf(buf, "\t\t\t%s = buf.ReadFloat64(err)\n", valueExpr)
		case types.String:
			fmt.Fprintf(buf, "\t\t\t%s = ctx.ReadString()\n", valueExpr)
		default:
			fmt.Fprintf(buf, "\t\t\t// TODO: unsupported basic type %s\n", basic.String())
		}
		return nil
	}

	if slice, ok := elemType.(*types.Slice); ok {
		return generateSliceReadInlineNoNull(buf, slice, valueExpr)
	}
	if mapType, ok := elemType.(*types.Map); ok {
		return generateMapReadInlineNoNull(buf, mapType, valueExpr)
	}

	unwrappedType := types.Unalias(elemType)
	if iface, ok := unwrappedType.(*types.Interface); ok {
		if iface.Empty() {
			fmt.Fprintf(buf, "\t\t\tctx.ReadValue(reflect.ValueOf(&%s).Elem(), fory.RefModeNone, true)\n", valueExpr)
			return nil
		}
	}

	if _, ok := elemType.Underlying().(*types.Struct); ok {
		fmt.Fprintf(buf, "\t\t\tctx.ReadValue(reflect.ValueOf(&%s).Elem(), fory.RefModeNone, true)\n", valueExpr)
		return nil
	}

	fmt.Fprintf(buf, "\t\t\tctx.ReadValue(reflect.ValueOf(&%s).Elem(), fory.RefModeNone, true)\n", valueExpr)
	return nil
}

// Note: generateSliceRead is no longer used since we use WriteReferencable/ReadValue for slice fields
// generateSliceRead generates code to deserialize a slice according to the list format
func generateSliceRead(buf *bytes.Buffer, sliceType *types.Slice, fieldAccess string) error {
	elemType := sliceType.Elem()

	// Use block scope to avoid variable redeclaration across multiple slice fields
	fmt.Fprintf(buf, "\t// ReadData slice %s\n", fieldAccess)
	fmt.Fprintf(buf, "\t{\n")
	fmt.Fprintf(buf, "\t\tsliceLen := int(buf.ReadVaruint32())\n")
	fmt.Fprintf(buf, "\t\tif sliceLen == 0 {\n")
	fmt.Fprintf(buf, "\t\t\t// Empty slice - matching reflection behavior where nil and empty are treated the same\n")
	fmt.Fprintf(buf, "\t\t\t%s = nil\n", fieldAccess)
	fmt.Fprintf(buf, "\t\t} else {\n")

	// ReadData collection flags for non-empty slice
	fmt.Fprintf(buf, "\t\t\t// ReadData collection flags\n")
	fmt.Fprintf(buf, "\t\t\tcollectFlag := buf.ReadInt8()\n")
	fmt.Fprintf(buf, "\t\t\t// Check if CollectionIsDeclElementType flag is NOT set (meaning we need to read type ID)\n")
	fmt.Fprintf(buf, "\t\t\tif (collectFlag & 4) == 0 {\n")
	fmt.Fprintf(buf, "\t\t\t\t// ReadData element type ID (not declared, so we need to read it)\n")
	fmt.Fprintf(buf, "\t\t\t\t_ = buf.ReadVaruint32()\n")
	fmt.Fprintf(buf, "\t\t\t}\n")

	// Create slice
	fmt.Fprintf(buf, "\t\t\t%s = make(%s, sliceLen)\n", fieldAccess, sliceType.String())

	// ReadData elements - for declared type slices, use direct element reading without flags
	fmt.Fprintf(buf, "\t\t\tfor i := 0; i < sliceLen; i++ {\n")

	// Generate element read code - for typed slices, read directly via serializer
	elemAccess := fmt.Sprintf("%s[i]", fieldAccess)
	if err := generateSliceElementReadDirect(buf, elemType, elemAccess); err != nil {
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
			fmt.Fprintf(buf, "\t\t\t\t%s = ctx.ReadString()\n", elemAccess)
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
			fmt.Fprintf(buf, "\t\t\t\tseconds := buf.ReadInt64()\n")
			fmt.Fprintf(buf, "\t\t\t\tnanos := buf.ReadUint32()\n")
			fmt.Fprintf(buf, "\t\t\t\t%s = fory.CreateTimeFromUnixSecondsAndNanos(seconds, nanos)\n", elemAccess)
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
			fmt.Fprintf(buf, "\t\t\t\tctx.ReadValue(reflect.ValueOf(&%s).Elem(), fory.RefModeTracking, true)\n", elemAccess)
			return nil
		}
	}

	// Handle struct types
	if _, ok := elemType.Underlying().(*types.Struct); ok {
		fmt.Fprintf(buf, "\t\t\t\tctx.ReadValue(reflect.ValueOf(&%s).Elem(), fory.RefModeTracking, true)\n", elemAccess)
		return nil
	}

	fmt.Fprintf(buf, "\t\t\t\t// TODO: unsupported element type %s\n", elemType.String())
	return nil
}

// generateSliceReadInline generates inline slice deserialization code to match encoder behavior exactly
// Uses error-aware methods for deferred error checking
func generateSliceReadInline(buf *bytes.Buffer, sliceType *types.Slice, fieldAccess string) error {
	elemType := sliceType.Elem()

	// Check if element type is a primitive type that uses ARRAY protocol
	if isPrimitiveSliceElemType(elemType) {
		return generatePrimitiveSliceReadInline(buf, sliceType, fieldAccess)
	}

	// Check if element type is referencable (needs ref tracking)
	elemIsReferencable := isReferencableType(elemType)

	// In xlang mode, slices are NOT nullable by default (only pointer types are nullable).
	// In native Go mode, slices can be nil and need null flags.
	// Generate conditional code that respects the mode at runtime.

	// ReadData slice with conditional null flag - use block scope to avoid variable name conflicts
	fmt.Fprintf(buf, "\t{\n")
	fmt.Fprintf(buf, "\t\tisXlang := ctx.TypeResolver().IsXlang()\n")
	fmt.Fprintf(buf, "\t\tif isXlang {\n")
	fmt.Fprintf(buf, "\t\t\t// xlang mode: slices are not nullable, read directly without null flag\n")
	fmt.Fprintf(buf, "\t\t\tsliceLen := int(buf.ReadVaruint32(err))\n")
	fmt.Fprintf(buf, "\t\t\tif sliceLen == 0 {\n")
	fmt.Fprintf(buf, "\t\t\t\t%s = make(%s, 0)\n", fieldAccess, sliceType.String())
	fmt.Fprintf(buf, "\t\t\t} else {\n")
	// ReadData collection header in xlang mode
	if err := writeSliceReadElements(buf, sliceType, elemType, fieldAccess, elemIsReferencable, "\t\t\t\t"); err != nil {
		return err
	}
	fmt.Fprintf(buf, "\t\t\t}\n") // end else (sliceLen > 0)
	fmt.Fprintf(buf, "\t\t} else {\n")
	fmt.Fprintf(buf, "\t\t\t// Native Go mode: slices are nullable, read null flag\n")
	fmt.Fprintf(buf, "\t\t\tnullFlag := buf.ReadInt8(err)\n")
	fmt.Fprintf(buf, "\t\t\tif nullFlag == -3 {\n") // NullFlag
	fmt.Fprintf(buf, "\t\t\t\t%s = nil\n", fieldAccess)
	fmt.Fprintf(buf, "\t\t\t} else {\n")
	fmt.Fprintf(buf, "\t\t\t\tsliceLen := int(buf.ReadVaruint32(err))\n")
	fmt.Fprintf(buf, "\t\t\t\tif sliceLen == 0 {\n")
	fmt.Fprintf(buf, "\t\t\t\t\t%s = make(%s, 0)\n", fieldAccess, sliceType.String())
	fmt.Fprintf(buf, "\t\t\t\t} else {\n")
	// ReadData collection header in native mode
	if err := writeSliceReadElements(buf, sliceType, elemType, fieldAccess, elemIsReferencable, "\t\t\t\t\t"); err != nil {
		return err
	}
	fmt.Fprintf(buf, "\t\t\t\t}\n") // end else (sliceLen > 0)
	fmt.Fprintf(buf, "\t\t\t}\n")   // end else (not null)
	fmt.Fprintf(buf, "\t\t}\n")     // end else (native mode)
	fmt.Fprintf(buf, "\t}\n")       // end block scope

	return nil
}

func generateSliceReadInlineNoNull(buf *bytes.Buffer, sliceType *types.Slice, fieldAccess string) error {
	elemType := sliceType.Elem()
	indent := "\t\t\t"

	unwrappedElem := types.Unalias(elemType)
	if iface, ok := unwrappedElem.(*types.Interface); ok && iface.Empty() {
		fmt.Fprintf(buf, "%s// Dynamic slice []any handling - no null flag\n", indent)
		fmt.Fprintf(buf, "%ssliceLen := int(buf.ReadVaruint32(err))\n", indent)
		fmt.Fprintf(buf, "%sif sliceLen == 0 {\n", indent)
		fmt.Fprintf(buf, "%s\t%s = make([]any, 0)\n", indent, fieldAccess)
		fmt.Fprintf(buf, "%s} else {\n", indent)
		fmt.Fprintf(buf, "%s\t_ = buf.ReadInt8(err) // collection flags\n", indent)
		fmt.Fprintf(buf, "%s\t%s = make([]any, sliceLen)\n", indent, fieldAccess)
		fmt.Fprintf(buf, "%s\tfor i := range %s {\n", indent, fieldAccess)
		fmt.Fprintf(buf, "%s\t\tctx.ReadValue(reflect.ValueOf(&%s[i]).Elem(), fory.RefModeTracking, true)\n", indent, fieldAccess)
		fmt.Fprintf(buf, "%s\t}\n", indent)
		fmt.Fprintf(buf, "%s}\n", indent)
		return nil
	}

	if isPrimitiveSliceElemType(elemType) {
		return generatePrimitiveSliceReadInlineNoNull(buf, sliceType, fieldAccess, indent)
	}

	elemIsReferencable := isReferencableType(elemType)
	fmt.Fprintf(buf, "%ssliceLen := int(buf.ReadVaruint32(err))\n", indent)
	fmt.Fprintf(buf, "%sif sliceLen == 0 {\n", indent)
	fmt.Fprintf(buf, "%s\t%s = make(%s, 0)\n", indent, fieldAccess, sliceType.String())
	fmt.Fprintf(buf, "%s} else {\n", indent)
	if err := writeSliceReadElements(buf, sliceType, elemType, fieldAccess, elemIsReferencable, indent+"\t"); err != nil {
		return err
	}
	fmt.Fprintf(buf, "%s}\n", indent)
	return nil
}

func generatePrimitiveSliceReadInlineNoNull(buf *bytes.Buffer, sliceType *types.Slice, fieldAccess string, indent string) error {
	elemType := sliceType.Elem()
	basic := elemType.Underlying().(*types.Basic)
	switch basic.Kind() {
	case types.Bool:
		fmt.Fprintf(buf, "%s%s = fory.ReadBoolSlice(buf, err)\n", indent, fieldAccess)
	case types.Int8:
		fmt.Fprintf(buf, "%s%s = fory.ReadInt8Slice(buf, err)\n", indent, fieldAccess)
	case types.Uint8:
		fmt.Fprintf(buf, "%s%s = fory.ReadByteSlice(buf, err)\n", indent, fieldAccess)
	case types.Int16:
		fmt.Fprintf(buf, "%s%s = fory.ReadInt16Slice(buf, err)\n", indent, fieldAccess)
	case types.Int32:
		fmt.Fprintf(buf, "%s%s = fory.ReadInt32Slice(buf, err)\n", indent, fieldAccess)
	case types.Int64:
		fmt.Fprintf(buf, "%s%s = fory.ReadInt64Slice(buf, err)\n", indent, fieldAccess)
	case types.Float32:
		fmt.Fprintf(buf, "%s%s = fory.ReadFloat32Slice(buf, err)\n", indent, fieldAccess)
	case types.Float64:
		fmt.Fprintf(buf, "%s%s = fory.ReadFloat64Slice(buf, err)\n", indent, fieldAccess)
	default:
		return fmt.Errorf("unsupported primitive type for ARRAY protocol: %s", basic.String())
	}
	return nil
}

// writeSliceReadElements generates the element reading code for a slice with specified indentation
func writeSliceReadElements(buf *bytes.Buffer, sliceType *types.Slice, elemType types.Type, fieldAccess string, elemIsReferencable bool, indent string) error {
	// ReadData collection header
	fmt.Fprintf(buf, "%scollectFlag := buf.ReadInt8(err)\n", indent)
	fmt.Fprintf(buf, "%s// Check if CollectionIsDeclElementType is set (bit 2, value 4)\n", indent)
	fmt.Fprintf(buf, "%shasDeclType := (collectFlag & 4) != 0\n", indent)
	if elemIsReferencable {
		fmt.Fprintf(buf, "%s// Check if CollectionTrackingRef is set (bit 0, value 1)\n", indent)
		fmt.Fprintf(buf, "%strackRefs := (collectFlag & 1) != 0\n", indent)
	}

	// Create slice
	fmt.Fprintf(buf, "%s%s = make(%s, sliceLen)\n", indent, fieldAccess, sliceType.String())

	// ReadData elements based on whether CollectionIsDeclElementType is set
	fmt.Fprintf(buf, "%sif hasDeclType {\n", indent)
	fmt.Fprintf(buf, "%s\t// Elements are written directly without type IDs\n", indent)
	fmt.Fprintf(buf, "%s\tfor i := 0; i < sliceLen; i++ {\n", indent)
	if elemIsReferencable {
		fmt.Fprintf(buf, "%s\t\tif trackRefs {\n", indent)
		fmt.Fprintf(buf, "%s\t\t\t_ = buf.ReadInt8(err) // Read ref flag (NotNullValueFlag)\n", indent)
		fmt.Fprintf(buf, "%s\t\t}\n", indent)
	}
	if err := generateSliceElementReadDirectIndented(buf, elemType, fmt.Sprintf("%s[i]", fieldAccess), indent+"\t\t"); err != nil {
		return err
	}
	fmt.Fprintf(buf, "%s\t}\n", indent)
	fmt.Fprintf(buf, "%s} else {\n", indent)
	fmt.Fprintf(buf, "%s\t// Need to read type ID once if CollectionIsSameType is set\n", indent)
	fmt.Fprintf(buf, "%s\tif (collectFlag & 8) != 0 {\n", indent)
	fmt.Fprintf(buf, "%s\t\t// ReadData element type ID once for all elements\n", indent)
	fmt.Fprintf(buf, "%s\t\t_ = buf.ReadVaruint32(err)\n", indent)
	fmt.Fprintf(buf, "%s\t}\n", indent)
	fmt.Fprintf(buf, "%s\tfor i := 0; i < sliceLen; i++ {\n", indent)
	if elemIsReferencable {
		fmt.Fprintf(buf, "%s\t\tif trackRefs {\n", indent)
		fmt.Fprintf(buf, "%s\t\t\t_ = buf.ReadInt8(err) // Read ref flag (NotNullValueFlag)\n", indent)
		fmt.Fprintf(buf, "%s\t\t}\n", indent)
	}
	if err := generateSliceElementReadDirectIndented(buf, elemType, fmt.Sprintf("%s[i]", fieldAccess), indent+"\t\t"); err != nil {
		return err
	}
	fmt.Fprintf(buf, "%s\t}\n", indent)
	fmt.Fprintf(buf, "%s}\n", indent)

	return nil
}

// generatePrimitiveSliceReadInline generates inline deserialization code for primitive slices using ARRAY protocol
func generatePrimitiveSliceReadInline(buf *bytes.Buffer, sliceType *types.Slice, fieldAccess string) error {
	elemType := sliceType.Elem()
	basic := elemType.Underlying().(*types.Basic)

	// In xlang mode, slices are NOT nullable by default (only pointer types are nullable).
	// In native Go mode, slices can be nil and need null flags.
	// Generate conditional code that respects the mode at runtime.

	fmt.Fprintf(buf, "\t{\n")
	fmt.Fprintf(buf, "\t\tisXlang := ctx.TypeResolver().IsXlang()\n")
	fmt.Fprintf(buf, "\t\tif isXlang {\n")
	fmt.Fprintf(buf, "\t\t\t// xlang mode: slices are not nullable, read directly without null flag\n")

	// Read primitive slice in xlang mode
	if err := writePrimitiveSliceReadCall(buf, basic, fieldAccess, "\t\t\t"); err != nil {
		return err
	}

	fmt.Fprintf(buf, "\t\t} else {\n")
	fmt.Fprintf(buf, "\t\t\t// Native Go mode: slices are nullable, read null flag\n")
	fmt.Fprintf(buf, "\t\t\tnullFlag := buf.ReadInt8(err)\n")
	fmt.Fprintf(buf, "\t\t\tif nullFlag == -3 {\n") // NullFlag
	fmt.Fprintf(buf, "\t\t\t\t%s = nil\n", fieldAccess)
	fmt.Fprintf(buf, "\t\t\t} else {\n")

	// Read primitive slice in native mode
	if err := writePrimitiveSliceReadCall(buf, basic, fieldAccess, "\t\t\t\t"); err != nil {
		return err
	}

	fmt.Fprintf(buf, "\t\t\t}\n") // end else (not null)
	fmt.Fprintf(buf, "\t\t}\n")   // end else (native mode)
	fmt.Fprintf(buf, "\t}\n")     // end block scope
	return nil
}

// writePrimitiveSliceReadCall writes the helper function call for reading a primitive slice
func writePrimitiveSliceReadCall(buf *bytes.Buffer, basic *types.Basic, fieldAccess string, indent string) error {
	switch basic.Kind() {
	case types.Bool:
		fmt.Fprintf(buf, "%s%s = fory.ReadBoolSlice(buf, err)\n", indent, fieldAccess)
	case types.Int8:
		fmt.Fprintf(buf, "%s%s = fory.ReadInt8Slice(buf, err)\n", indent, fieldAccess)
	case types.Uint8:
		fmt.Fprintf(buf, "%ssizeBytes := buf.ReadLength(err)\n", indent)
		fmt.Fprintf(buf, "%s%s = make([]uint8, sizeBytes)\n", indent, fieldAccess)
		fmt.Fprintf(buf, "%sif sizeBytes > 0 {\n", indent)
		fmt.Fprintf(buf, "%s\traw := buf.ReadBinary(sizeBytes, err)\n", indent)
		fmt.Fprintf(buf, "%s\tif raw != nil {\n", indent)
		fmt.Fprintf(buf, "%s\t\tcopy(%s, raw)\n", indent, fieldAccess)
		fmt.Fprintf(buf, "%s\t}\n", indent)
		fmt.Fprintf(buf, "%s}\n", indent)
	case types.Int16:
		fmt.Fprintf(buf, "%s%s = fory.ReadInt16Slice(buf, err)\n", indent, fieldAccess)
	case types.Int32:
		fmt.Fprintf(buf, "%s%s = fory.ReadInt32Slice(buf, err)\n", indent, fieldAccess)
	case types.Int64:
		fmt.Fprintf(buf, "%s%s = fory.ReadInt64Slice(buf, err)\n", indent, fieldAccess)
	case types.Float32:
		fmt.Fprintf(buf, "%s%s = fory.ReadFloat32Slice(buf, err)\n", indent, fieldAccess)
	case types.Float64:
		fmt.Fprintf(buf, "%s%s = fory.ReadFloat64Slice(buf, err)\n", indent, fieldAccess)
	default:
		return fmt.Errorf("unsupported primitive type for ARRAY protocol read: %s", basic.String())
	}
	return nil
}

// generateElementTypeIDReadInline generates element type ID verification
func generateElementTypeIDReadInline(buf *bytes.Buffer, elemType types.Type) error {
	// Handle basic types - verify the expected type ID
	if basic, ok := elemType.Underlying().(*types.Basic); ok {
		var expectedTypeID int
		switch basic.Kind() {
		case types.Bool:
			expectedTypeID = int(fory.BOOL)
		case types.Int8:
			expectedTypeID = int(fory.INT8)
		case types.Int16:
			expectedTypeID = int(fory.INT16)
		case types.Int32:
			expectedTypeID = int(fory.VARINT32)
		case types.Int, types.Int64:
			expectedTypeID = int(fory.VARINT64)
		case types.Uint8:
			expectedTypeID = int(fory.UINT8)
		case types.Uint16:
			expectedTypeID = int(fory.UINT16)
		case types.Uint32:
			expectedTypeID = int(fory.VAR_UINT32)
		case types.Uint, types.Uint64:
			expectedTypeID = int(fory.VAR_UINT64)
		case types.Float32:
			expectedTypeID = int(fory.FLOAT32)
		case types.Float64:
			expectedTypeID = int(fory.FLOAT64)
		case types.String:
			expectedTypeID = int(fory.STRING)
		default:
			return fmt.Errorf("unsupported basic type for element type ID read: %s", basic.String())
		}

		fmt.Fprintf(buf, "\t\t\t\t// ReadData and verify element type ID\n")
		fmt.Fprintf(buf, "\t\t\t\tif typeID := buf.ReadVaruint32(); typeID != %d {\n", expectedTypeID)
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
			fmt.Fprintf(buf, "\t\t\t\t%s = ctx.ReadString()\n", elemAccess)
		default:
			return fmt.Errorf("unsupported basic type for element read: %s", basic.String())
		}
		return nil
	}

	// Handle interface types (including 'any' which is an alias for interface{})
	unwrappedElem := types.Unalias(elemType)
	if iface, ok := unwrappedElem.(*types.Interface); ok {
		if iface.Empty() {
			// For any elements, use ReadValue for dynamic type handling
			fmt.Fprintf(buf, "\t\t\t\tctx.ReadValue(reflect.ValueOf(&%s).Elem(), fory.RefModeTracking, true)\n", elemAccess)
			return nil
		}
	}

	return fmt.Errorf("unsupported element type for read: %s", elemType.String())
}

// generateSliceElementReadDirect generates code to read slice elements directly via their serializers
// This is used for typed slices with CollectionIsDeclElementType where no flags/type IDs are written per element
// Uses error-aware methods for deferred error checking
func generateSliceElementReadDirect(buf *bytes.Buffer, elemType types.Type, elemAccess string) error {
	// Handle basic types - read directly using their serializers (no flags)
	// Use error-aware methods for deferred error checking
	if basic, ok := elemType.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadBool(err)\n", elemAccess)
		case types.Int8:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadInt8(err)\n", elemAccess)
		case types.Int16:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadInt16(err)\n", elemAccess)
		case types.Int32:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadVarint32(err)\n", elemAccess)
		case types.Int, types.Int64:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadVarint64(err)\n", elemAccess)
		case types.Uint8:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadByte(err)\n", elemAccess)
		case types.Uint16:
			fmt.Fprintf(buf, "\t\t\t\t%s = uint16(buf.ReadInt16(err))\n", elemAccess)
		case types.Uint32:
			fmt.Fprintf(buf, "\t\t\t\t%s = uint32(buf.ReadInt32(err))\n", elemAccess)
		case types.Uint, types.Uint64:
			fmt.Fprintf(buf, "\t\t\t\t%s = uint64(buf.ReadInt64(err))\n", elemAccess)
		case types.Float32:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadFloat32(err)\n", elemAccess)
		case types.Float64:
			fmt.Fprintf(buf, "\t\t\t\t%s = buf.ReadFloat64(err)\n", elemAccess)
		case types.String:
			// String serializer reads directly without flags
			fmt.Fprintf(buf, "\t\t\t\t%s = ctx.ReadString()\n", elemAccess)
		default:
			return fmt.Errorf("unsupported basic type for direct element read: %s", basic.String())
		}
		return nil
	}

	return fmt.Errorf("unsupported element type for direct read: %s", elemType.String())
}

// generateSliceElementReadDirectIndented generates code to read slice elements directly with custom indentation
func generateSliceElementReadDirectIndented(buf *bytes.Buffer, elemType types.Type, elemAccess string, indent string) error {
	if basic, ok := elemType.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Bool:
			fmt.Fprintf(buf, "%s%s = buf.ReadBool(err)\n", indent, elemAccess)
		case types.Int8:
			fmt.Fprintf(buf, "%s%s = buf.ReadInt8(err)\n", indent, elemAccess)
		case types.Int16:
			fmt.Fprintf(buf, "%s%s = buf.ReadInt16(err)\n", indent, elemAccess)
		case types.Int32:
			fmt.Fprintf(buf, "%s%s = buf.ReadVarint32(err)\n", indent, elemAccess)
		case types.Int, types.Int64:
			fmt.Fprintf(buf, "%s%s = buf.ReadVarint64(err)\n", indent, elemAccess)
		case types.Uint8:
			fmt.Fprintf(buf, "%s%s = buf.ReadByte(err)\n", indent, elemAccess)
		case types.Uint16:
			fmt.Fprintf(buf, "%s%s = uint16(buf.ReadInt16(err))\n", indent, elemAccess)
		case types.Uint32:
			fmt.Fprintf(buf, "%s%s = uint32(buf.ReadInt32(err))\n", indent, elemAccess)
		case types.Uint, types.Uint64:
			fmt.Fprintf(buf, "%s%s = uint64(buf.ReadInt64(err))\n", indent, elemAccess)
		case types.Float32:
			fmt.Fprintf(buf, "%s%s = buf.ReadFloat32(err)\n", indent, elemAccess)
		case types.Float64:
			fmt.Fprintf(buf, "%s%s = buf.ReadFloat64(err)\n", indent, elemAccess)
		case types.String:
			fmt.Fprintf(buf, "%s%s = ctx.ReadString()\n", indent, elemAccess)
		default:
			return fmt.Errorf("unsupported basic type for direct element read: %s", basic.String())
		}
		return nil
	}

	return fmt.Errorf("unsupported element type for direct read: %s", elemType.String())
}

// generateMapReadInline generates inline map deserialization code following the chunk-based format
// Uses error-aware methods for deferred error checking
func generateMapReadInline(buf *bytes.Buffer, mapType *types.Map, fieldAccess string) error {
	keyType := mapType.Key()
	valueType := mapType.Elem()

	// Check if key or value types are any (unwrap aliases like 'any')
	keyIsInterface := false
	valueIsInterface := false
	unwrappedKey := types.Unalias(keyType)
	unwrappedValue := types.Unalias(valueType)
	if iface, ok := unwrappedKey.(*types.Interface); ok && iface.Empty() {
		keyIsInterface = true
	}
	if iface, ok := unwrappedValue.(*types.Interface); ok && iface.Empty() {
		valueIsInterface = true
	}

	// In xlang mode, maps are NOT nullable by default (only pointer types are nullable).
	// In native Go mode, maps can be nil and need null flags.
	// Generate conditional code that respects the mode at runtime.

	// ReadData map with conditional null flag
	fmt.Fprintf(buf, "\t{\n")
	fmt.Fprintf(buf, "\t\tisXlang := ctx.TypeResolver().IsXlang()\n")
	fmt.Fprintf(buf, "\t\tif isXlang {\n")
	fmt.Fprintf(buf, "\t\t\t// xlang mode: maps are not nullable, read directly without null flag\n")
	fmt.Fprintf(buf, "\t\t\tmapLen := int(buf.ReadVaruint32(err))\n")
	fmt.Fprintf(buf, "\t\t\tif mapLen == 0 {\n")
	fmt.Fprintf(buf, "\t\t\t\t%s = make(%s)\n", fieldAccess, mapType.String())
	fmt.Fprintf(buf, "\t\t\t} else {\n")
	// Read map chunks in xlang mode
	if err := writeMapReadChunks(buf, mapType, fieldAccess, keyType, valueType, keyIsInterface, valueIsInterface, "\t\t\t\t"); err != nil {
		return err
	}
	fmt.Fprintf(buf, "\t\t\t}\n") // end else (mapLen > 0)
	fmt.Fprintf(buf, "\t\t} else {\n")
	fmt.Fprintf(buf, "\t\t\t// Native Go mode: maps are nullable, read null flag\n")
	fmt.Fprintf(buf, "\t\t\tnullFlag := buf.ReadInt8(err)\n")
	fmt.Fprintf(buf, "\t\t\tif nullFlag == -3 {\n") // NullFlag
	fmt.Fprintf(buf, "\t\t\t\t%s = nil\n", fieldAccess)
	fmt.Fprintf(buf, "\t\t\t} else {\n")
	fmt.Fprintf(buf, "\t\t\t\tmapLen := int(buf.ReadVaruint32(err))\n")
	fmt.Fprintf(buf, "\t\t\t\tif mapLen == 0 {\n")
	fmt.Fprintf(buf, "\t\t\t\t\t%s = make(%s)\n", fieldAccess, mapType.String())
	fmt.Fprintf(buf, "\t\t\t\t} else {\n")
	// Read map chunks in native mode
	if err := writeMapReadChunks(buf, mapType, fieldAccess, keyType, valueType, keyIsInterface, valueIsInterface, "\t\t\t\t\t"); err != nil {
		return err
	}
	fmt.Fprintf(buf, "\t\t\t\t}\n") // end else (mapLen > 0)
	fmt.Fprintf(buf, "\t\t\t}\n")   // end else (not null)
	fmt.Fprintf(buf, "\t\t}\n")     // end else (native mode)
	fmt.Fprintf(buf, "\t}\n")       // end block scope

	return nil
}

func generateMapReadInlineNoNull(buf *bytes.Buffer, mapType *types.Map, fieldAccess string) error {
	keyType := mapType.Key()
	valueType := mapType.Elem()

	keyIsInterface := false
	valueIsInterface := false
	unwrappedKey := types.Unalias(keyType)
	unwrappedValue := types.Unalias(valueType)
	if iface, ok := unwrappedKey.(*types.Interface); ok && iface.Empty() {
		keyIsInterface = true
	}
	if iface, ok := unwrappedValue.(*types.Interface); ok && iface.Empty() {
		valueIsInterface = true
	}

	indent := "\t\t\t"
	fmt.Fprintf(buf, "%smapLen := int(buf.ReadVaruint32(err))\n", indent)
	fmt.Fprintf(buf, "%sif mapLen == 0 {\n", indent)
	fmt.Fprintf(buf, "%s\t%s = make(%s)\n", indent, fieldAccess, mapType.String())
	fmt.Fprintf(buf, "%s} else {\n", indent)
	if err := writeMapReadChunks(buf, mapType, fieldAccess, keyType, valueType, keyIsInterface, valueIsInterface, indent+"\t"); err != nil {
		return err
	}
	fmt.Fprintf(buf, "%s}\n", indent)
	return nil
}

// writeMapReadChunks generates the map chunk reading code with specified indentation
func writeMapReadChunks(buf *bytes.Buffer, mapType *types.Map, fieldAccess string, keyType, valueType types.Type, keyIsInterface, valueIsInterface bool, indent string) error {
	fmt.Fprintf(buf, "%s%s = make(%s, mapLen)\n", indent, fieldAccess, mapType.String())
	fmt.Fprintf(buf, "%smapSize := mapLen\n", indent)

	// ReadData chunks
	fmt.Fprintf(buf, "%sfor mapSize > 0 {\n", indent)
	fmt.Fprintf(buf, "%s\t// ReadData KV header\n", indent)
	fmt.Fprintf(buf, "%s\tkvHeader := buf.ReadByte(err)\n", indent)
	fmt.Fprintf(buf, "%s\tchunkSize := int(buf.ReadByte(err))\n", indent)

	// Parse header flags
	fmt.Fprintf(buf, "%s\ttrackKeyRef := (kvHeader & 0x1) != 0\n", indent)
	fmt.Fprintf(buf, "%s\tkeyNotDeclared := (kvHeader & 0x4) != 0\n", indent)
	fmt.Fprintf(buf, "%s\ttrackValueRef := (kvHeader & 0x8) != 0\n", indent)
	fmt.Fprintf(buf, "%s\tvalueNotDeclared := (kvHeader & 0x20) != 0\n", indent)
	fmt.Fprintf(buf, "%s\t_ = trackKeyRef\n", indent)
	fmt.Fprintf(buf, "%s\t_ = keyNotDeclared\n", indent)
	fmt.Fprintf(buf, "%s\t_ = trackValueRef\n", indent)
	fmt.Fprintf(buf, "%s\t_ = valueNotDeclared\n", indent)

	// ReadData key-value pairs in this chunk
	fmt.Fprintf(buf, "%s\tfor i := 0; i < chunkSize; i++ {\n", indent)

	// ReadData key
	if keyIsInterface {
		fmt.Fprintf(buf, "%s\t\tvar mapKey any\n", indent)
		fmt.Fprintf(buf, "%s\t\tctx.ReadValue(reflect.ValueOf(&mapKey).Elem(), fory.RefModeTracking, true)\n", indent)
	} else {
		keyVarType := getGoTypeString(keyType)
		fmt.Fprintf(buf, "%s\t\tvar mapKey %s\n", indent, keyVarType)
		if err := generateMapKeyReadIndented(buf, keyType, "mapKey", indent+"\t\t"); err != nil {
			return err
		}
	}

	// ReadData value
	if valueIsInterface {
		fmt.Fprintf(buf, "%s\t\tvar mapValue any\n", indent)
		fmt.Fprintf(buf, "%s\t\tctx.ReadValue(reflect.ValueOf(&mapValue).Elem(), fory.RefModeTracking, true)\n", indent)
	} else {
		valueVarType := getGoTypeString(valueType)
		fmt.Fprintf(buf, "%s\t\tvar mapValue %s\n", indent, valueVarType)
		if err := generateMapValueReadIndented(buf, valueType, "mapValue", indent+"\t\t"); err != nil {
			return err
		}
	}

	// Set key-value pair in map
	fmt.Fprintf(buf, "%s\t\t%s[mapKey] = mapValue\n", indent, fieldAccess)

	fmt.Fprintf(buf, "%s\t}\n", indent) // end chunk loop
	fmt.Fprintf(buf, "%s\tmapSize -= chunkSize\n", indent)
	fmt.Fprintf(buf, "%s}\n", indent) // end mapSize > 0 loop

	return nil
}

// getGoTypeString returns the Go type string for a types.Type
func getGoTypeString(t types.Type) string {
	// Handle basic types
	if basic, ok := t.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Int:
			return "int"
		case types.String:
			return "string"
		default:
			return t.String()
		}
	}
	return t.String()
}

// generateMapKeyRead generates code to read a map key
// Uses error-aware methods for deferred error checking
func generateMapKeyRead(buf *bytes.Buffer, keyType types.Type, varName string) error {
	// For basic types, match reflection's serializer behavior
	if basic, ok := keyType.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Int:
			// intSerializer uses ReadInt64, not ReadVarint64
			fmt.Fprintf(buf, "\t\t\t\t\t%s = int(buf.ReadInt64(err))\n", varName)
		case types.String:
			// stringSerializer.NeedWriteRef() = false, read directly
			fmt.Fprintf(buf, "\t\t\t\t\t%s = ctx.ReadString()\n", varName)
		default:
			return fmt.Errorf("unsupported map key type: %v", keyType)
		}
		return nil
	}

	// For other types, use ReadValue
	fmt.Fprintf(buf, "\t\t\t\t\tctx.ReadValue(reflect.ValueOf(&%s).Elem(), fory.RefModeTracking, true)\n", varName)
	return nil
}

// generateMapValueRead generates code to read a map value
// Uses error-aware methods for deferred error checking
func generateMapValueRead(buf *bytes.Buffer, valueType types.Type, varName string) error {
	// For basic types, match reflection's serializer behavior
	if basic, ok := valueType.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Int:
			// intSerializer uses ReadInt64, not ReadVarint64
			fmt.Fprintf(buf, "\t\t\t\t\t%s = int(buf.ReadInt64(err))\n", varName)
		case types.String:
			// stringSerializer.NeedWriteRef() = false, read directly
			fmt.Fprintf(buf, "\t\t\t\t\t%s = ctx.ReadString()\n", varName)
		default:
			return fmt.Errorf("unsupported map value type: %v", valueType)
		}
		return nil
	}

	// For other types, use ReadValue
	fmt.Fprintf(buf, "\t\t\t\t\tctx.ReadValue(reflect.ValueOf(&%s).Elem(), fory.RefModeTracking, true)\n", varName)
	return nil
}

// generateMapKeyReadIndented generates code to read a map key with custom indentation
func generateMapKeyReadIndented(buf *bytes.Buffer, keyType types.Type, varName string, indent string) error {
	if basic, ok := keyType.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Int:
			fmt.Fprintf(buf, "%s%s = int(buf.ReadInt64(err))\n", indent, varName)
		case types.String:
			fmt.Fprintf(buf, "%s%s = ctx.ReadString()\n", indent, varName)
		default:
			return fmt.Errorf("unsupported map key type: %v", keyType)
		}
		return nil
	}
	fmt.Fprintf(buf, "%sctx.ReadValue(reflect.ValueOf(&%s).Elem(), fory.RefModeTracking, true)\n", indent, varName)
	return nil
}

// generateMapValueReadIndented generates code to read a map value with custom indentation
func generateMapValueReadIndented(buf *bytes.Buffer, valueType types.Type, varName string, indent string) error {
	if basic, ok := valueType.Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Int:
			fmt.Fprintf(buf, "%s%s = int(buf.ReadInt64(err))\n", indent, varName)
		case types.String:
			fmt.Fprintf(buf, "%s%s = ctx.ReadString()\n", indent, varName)
		default:
			return fmt.Errorf("unsupported map value type: %v", valueType)
		}
		return nil
	}
	fmt.Fprintf(buf, "%sctx.ReadValue(reflect.ValueOf(&%s).Elem(), fory.RefModeTracking, true)\n", indent, varName)
	return nil
}
