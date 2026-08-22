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

package fory

import (
	"reflect"
)

// Serializer is the unified interface for all serialization.
// It provides reflect.Value-based API for efficient serialization.
// All methods use centralized error handling via context - errors are set on
// the context and checked at strategic points (entry/exit of complex types).
type Serializer interface {
	// Write is the entry point for serialization.
	//
	// This method orchestrates the complete serialization process, handling reference tracking,
	// type information, and delegating to WriteData for the actual data serialization.
	//
	// Unlike Java's unified ref tracking approach, Go uses per-serializer ref/type handling.
	// Map, Slice, Interface, and Pointer types each have different ref tracking requirements,
	// so each serializer controls how to write ref/type info. This allows more efficient
	// serialization by avoiding unnecessary ref checks for value types and enabling
	// type-specific optimizations.
	//
	// Parameters:
	//   - refMode: controls reference/null handling behavior:
	//     - RefModeNone: skip ref handling entirely
	//     - RefModeNullOnly: only write null flag (NullFlag or NotNullValueFlag)
	//     - RefModeTracking: full reference tracking with WriteRefOrNull
	//   - writeType: when true, writes type information; when false, skips it
	//   - hasGenerics: when true, indicates element types are known from TypeDef (struct field context),
	//     so container serializers can skip writing element type info
	//
	// Errors are set on the context via ctx.SetError() and should be checked
	// at appropriate boundaries using ctx.HasError() or ctx.CheckError().
	Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value)

	// WriteData serializes using reflect.Value.
	// Does NOT write ref/type info - caller handles that.
	// Errors are set on the context via ctx.SetError().
	WriteData(ctx *WriteContext, value reflect.Value)

	// Read is the entry point for deserialization.
	//
	// This method orchestrates the complete deserialization process, handling reference tracking,
	// type information validation, and delegating to ReadData for the actual data deserialization.
	//
	// Unlike Java's unified ref tracking approach, Go uses per-serializer ref/type handling.
	// Map, Slice, Interface, and Pointer types each have different ref tracking requirements,
	// so each serializer controls how to read ref/type info. This allows more efficient
	// deserialization by avoiding unnecessary ref checks for value types and enabling
	// type-specific optimizations.
	//
	// Parameters:
	//   - refMode: controls reference/null handling behavior:
	//     - RefModeNone: skip ref handling entirely
	//     - RefModeNullOnly: only read null flag
	//     - RefModeTracking: full reference tracking with TryPreserveRefId
	//   - readType: when true, reads type information from buffer; when false, skips it
	//   - hasGenerics: when true, indicates element types are known from TypeDef (struct field context),
	//     so container serializers can skip reading element type info
	//
	// Errors are set on the context via ctx.SetError() and should be checked
	// at appropriate boundaries using ctx.HasError() or ctx.CheckError().
	Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value)

	// ReadData deserializes directly into the provided reflect.Value.
	// Does NOT read ref/type info - caller handles that.
	// For non-trivial types (slices, maps), implementations should reuse existing capacity when possible.
	// This method should ONLY be used by collection serializers for nested element deserialization.
	// For general deserialization, use ReadFull instead.
	// Errors are set on the context via ctx.SetError().
	ReadData(ctx *ReadContext, value reflect.Value)

	// ReadWithTypeInfo deserializes with pre-read type information.
	//
	// This method is used when type information has already been read from the buffer
	// and needs to be passed to the deserialization logic. This is common in polymorphic
	// deserialization scenarios where the runtime type differs from the static type.
	//
	// Parameters:
	//   - refMode: controls reference/null handling behavior:
	//     - RefModeNone: skip ref handling entirely
	//     - RefModeNullOnly: only read null flag
	//     - RefModeTracking: full reference tracking with TryPreserveRefId
	//   - typeInfo: pre-read type information; do NOT read type info again from buffer
	//
	// Important: do NOT read type info from the buffer in this method. The typeInfo
	// parameter contains the already-read type metadata. Reading it again will cause
	// buffer position errors.
	// Errors are set on the context via ctx.SetError().
	ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value)
}

// ExtensionSerializer is a simplified interface for user-implemented extension serializers.
// Users implement this interface to provide custom serialization logic for types
// registered via RegisterExtensionByName.
//
// Unlike the full Serializer interface, ExtensionSerializer only requires implementing
// the core data serialization logic - reference tracking, type info, and protocol
// details are handled automatically by Fory.
//
// Example:
//
//	type MyExtSerializer struct{}
//
//	func (s *MyExtSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
//	    myExt := value.Interface().(MyExt)
//	    ctx.Buffer().WriteVarint32(myExt.Id)
//	}
//
//	func (s *MyExtSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
//	    id := ctx.Buffer().ReadVarint32(ctx.Err())
//	    value.Set(reflect.ValueOf(MyExt{Id: id}))
//	}
//
//	// Register with custom serializer
//	f.RegisterExtensionByName(MyExt{}, "my_ext", &MyExtSerializer{})
type ExtensionSerializer interface {
	// WriteData serializes the value's data to the buffer.
	// Only write the data fields - don't write ref flags or type info.
	// Errors should be set on the context via ctx.SetError().
	WriteData(ctx *WriteContext, value reflect.Value)

	// ReadData deserializes the value's data from the buffer into the provided value.
	// Only read the data fields - don't read ref flags or type info.
	// Errors should be set on the context via ctx.SetError().
	ReadData(ctx *ReadContext, value reflect.Value)
}

func serializerReadDataAlwaysAdvances(serializer Serializer) bool {
	switch s := serializer.(type) {
	case boolSerializer, int8Serializer, byteSerializer, uint16Serializer,
		uint32Serializer, uint64Serializer, uintSerializer, int16Serializer,
		int32Serializer, int64Serializer, intSerializer, float32Serializer,
		float64Serializer, float16Serializer, bfloat16Serializer,
		stringSerializer, ptrToStringSerializer, decimalSerializer,
		dateSerializer, timeSerializer, durationSerializer, *enumSerializer,
		*sliceSerializer, *sliceDynSerializer, setSerializer, mapSerializer,
		*mapSerializer, *arrayConcreteValueSerializer, *arrayDynSerializer,
		byteArraySerializer, primitiveListSerializer,
		compatiblePrimitiveListToArraySerializer, boolSliceSerializer,
		int8SliceSerializer, int16SliceSerializer, int32SliceSerializer,
		int64SliceSerializer, byteSliceSerializer, uint16SliceSerializer,
		uint32SliceSerializer, uint64SliceSerializer, float32SliceSerializer,
		float64SliceSerializer, float16SliceSerializer, bfloat16SliceSerializer,
		intSliceSerializer, uintSliceSerializer, stringSliceSerializer,
		boolArraySerializer, int8ArraySerializer, int16ArraySerializer,
		int32ArraySerializer, int64ArraySerializer, uint8ArraySerializer,
		uint16ArraySerializer, uint32ArraySerializer, uint64ArraySerializer,
		float32ArraySerializer, float64ArraySerializer, float16ArraySerializer,
		bfloat16ArraySerializer, stringStringMapSerializer,
		stringInt64MapSerializer, stringIntMapSerializer,
		stringFloat64MapSerializer, stringBoolMapSerializer,
		int32Int32MapSerializer, int64Int64MapSerializer, intIntMapSerializer,
		encodedByteSliceSerializer, encodedInt32Serializer,
		encodedUint32Serializer, encodedInt64Serializer, encodedUint64Serializer,
		*UnionSerializer, *ptrToInterfaceSerializer:
		return true
	case *ptrToValueSerializer:
		return serializerReadDataAlwaysAdvances(s.valueSerializer)
	case *optionalSerializer:
		return serializerReadDataAlwaysAdvances(s.valueSerializer)
	case interfaceScalarSerializer:
		return serializerReadDataAlwaysAdvances(s.serializer)
	case *structSerializer:
		return s.readDataAlwaysAdvances
	case *skipStructSerializer:
		return skipStructReadDataAlwaysAdvances(s.fieldDefs)
	default:
		return false
	}
}

func typeIDReadDataAlwaysAdvances(typeID TypeId) bool {
	switch typeID {
	case NONE, UNKNOWN, STRUCT, COMPATIBLE_STRUCT, NAMED_STRUCT,
		NAMED_COMPATIBLE_STRUCT, EXT, NAMED_EXT:
		return false
	default:
		return true
	}
}

func skipStructReadDataAlwaysAdvances(fields []FieldDef) bool {
	for _, field := range fields {
		if field.nullable || field.trackRef || isStructFieldType(field.typeSpec) ||
			(field.typeSpec != nil && typeIDReadDataAlwaysAdvances(field.typeSpec.TypeId())) {
			return true
		}
	}
	return false
}
