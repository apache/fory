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

import "reflect"

type TypeId = int16

const (
	// UNKNOWN Unknown/polymorphic type marker
	UNKNOWN = 0
	// BOOL Boolean as 1 bit LSB bit-packed ordering
	BOOL = 1
	// INT8 Signed 8-bit little-endian integer
	INT8 = 2
	// INT16 Signed 16-bit little-endian integer
	INT16 = 3
	// INT32 Signed 32-bit little-endian integer
	INT32 = 4
	// VARINT32 a 32-bit signed integer which uses fory var_int32 encoding
	VARINT32 = 5
	// INT64 Signed 64-bit little-endian integer
	INT64 = 6
	// VARINT64 a 64-bit signed integer which uses fory PVL encoding
	VARINT64 = 7
	// TAGGED_INT64 a 64-bit signed integer which uses fory hybrid encoding
	TAGGED_INT64 = 8
	// UINT8 Unsigned 8-bit little-endian integer
	UINT8 = 9
	// UINT16 Unsigned 16-bit little-endian integer
	UINT16 = 10
	// UINT32 Unsigned 32-bit little-endian integer
	UINT32 = 11
	// VAR_UINT32 a 32-bit unsigned integer which uses fory var_uint32 encoding
	VAR_UINT32 = 12
	// UINT64 Unsigned 64-bit little-endian integer
	UINT64 = 13
	// VAR_UINT64 a 64-bit unsigned integer which uses fory var_uint64 encoding
	VAR_UINT64 = 14
	// TAGGED_UINT64 a 64-bit unsigned integer which uses fory hybrid encoding
	TAGGED_UINT64 = 15
	// FLOAT16 2-byte floating point value
	FLOAT16 = 16
	// FLOAT32 4-byte floating point value
	FLOAT32 = 17
	// FLOAT64 8-byte floating point value
	FLOAT64 = 18
	// STRING UTF8 variable-length string as List<Char>
	STRING = 19
	// LIST A list of some logical data type
	LIST = 20
	// SET an unordered set of unique elements
	SET = 21
	// MAP Map a repeated struct logical type
	MAP = 22
	// ENUM a data type consisting of a set of named values
	ENUM = 23
	// NAMED_ENUM an enum whose value will be serialized as the registered name
	NAMED_ENUM = 24
	// STRUCT a morphic(final) type serialized by Fory Struct serializer
	STRUCT = 25
	// COMPATIBLE_STRUCT a morphic(final) type serialized by Fory compatible Struct serializer
	COMPATIBLE_STRUCT = 26
	// NAMED_STRUCT a struct whose type mapping will be encoded as a name
	NAMED_STRUCT = 27
	// NAMED_COMPATIBLE_STRUCT a compatible_struct whose type mapping will be encoded as a name
	NAMED_COMPATIBLE_STRUCT = 28
	// EXT a type which will be serialized by a customized serializer
	EXT = 29
	// NAMED_EXT an ext type whose type mapping will be encoded as a name
	NAMED_EXT = 30
	// UNION a union value whose schema identity is not embedded
	UNION = 31
	// TYPED_UNION a union value with embedded numeric union type ID
	TYPED_UNION = 32
	// NAMED_UNION a union value with embedded union type name/TypeDef
	NAMED_UNION = 33
	// NONE a null value with no data
	NONE = 34
	// DURATION Measure of elapsed time in either seconds milliseconds microseconds
	DURATION = 35
	// TIMESTAMP Exact timestamp encoded with seconds(int64) + nanos(uint32) since UNIX epoch
	TIMESTAMP = 36
	// DATE a naive date without timezone
	DATE = 37
	// DECIMAL Precision- and scale-based decimal type
	DECIMAL = 38
	// BINARY Variable-length bytes (no guarantee of UTF8-ness)
	BINARY = 39
	// ARRAY a multidimensional array which every sub-array can have different sizes but all have the same type
	ARRAY = 40
	// BOOL_ARRAY one dimensional bool array
	BOOL_ARRAY = 41
	// INT8_ARRAY one dimensional int8 array
	INT8_ARRAY = 42
	// INT16_ARRAY one dimensional int16 array
	INT16_ARRAY = 43
	// INT32_ARRAY one dimensional int32 array
	INT32_ARRAY = 44
	// INT64_ARRAY one dimensional int64 array
	INT64_ARRAY = 45
	// UINT8_ARRAY one dimensional uint8 array
	UINT8_ARRAY = 46
	// UINT16_ARRAY one dimensional uint16 array
	UINT16_ARRAY = 47
	// UINT32_ARRAY one dimensional uint32 array
	UINT32_ARRAY = 48
	// UINT64_ARRAY one dimensional uint64 array
	UINT64_ARRAY = 49
	// FLOAT16_ARRAY one dimensional float16 array
	FLOAT16_ARRAY = 50
	// FLOAT32_ARRAY one dimensional float32 array
	FLOAT32_ARRAY = 51
	// FLOAT64_ARRAY one dimensional float64 array
	FLOAT64_ARRAY = 52
)

// IsNamespacedType checks whether the given type ID is a namespace type
func IsNamespacedType(typeID TypeId) bool {
	switch typeID & 0xFF {
	case NAMED_EXT, NAMED_ENUM, NAMED_STRUCT, NAMED_COMPATIBLE_STRUCT, NAMED_UNION:
		return true
	default:
		return false
	}
}

// NeedsTypeMetaWrite checks whether a type needs additional type meta written after type ID
// This includes namespaced types and struct types that need meta share in compatible mode
func NeedsTypeMetaWrite(typeID TypeId) bool {
	internalID := typeID & 0xFF
	switch TypeId(internalID) {
	case NAMED_EXT, NAMED_ENUM, NAMED_STRUCT, NAMED_COMPATIBLE_STRUCT, NAMED_UNION, COMPATIBLE_STRUCT, STRUCT:
		return true
	default:
		return false
	}
}

func isPrimitiveType(typeID int16) bool {
	switch typeID {
	case BOOL,
		INT8,
		INT16,
		INT32,
		VARINT32,
		INT64,
		VARINT64,
		TAGGED_INT64,
		UINT8,
		UINT16,
		UINT32,
		VAR_UINT32,
		UINT64,
		VAR_UINT64,
		TAGGED_UINT64,
		FLOAT16,
		FLOAT32,
		FLOAT64:
		return true
	default:
		return false
	}
}

// NeedWriteRef returns whether a type with the given type ID needs reference tracking.
// Primitive types, strings, and time types don't need reference tracking.
// Collections, structs, and other complex types need reference tracking.
func NeedWriteRef(typeID TypeId) bool {
	switch typeID {
	case BOOL, INT8, INT16, INT32, INT64, VARINT32, VARINT64, TAGGED_INT64,
		FLOAT32, FLOAT64, FLOAT16,
		STRING, TIMESTAMP, DATE, DURATION, NONE:
		return false
	default:
		return true
	}
}

func isListType(typeID int16) bool {
	return typeID == LIST
}

func isSetType(typeID int16) bool {
	return typeID == SET
}

func isMapType(typeID int16) bool {
	return typeID == MAP
}

func isCollectionType(typeID int16) bool {
	return typeID == LIST || typeID == SET || typeID == MAP
}

func isPrimitiveArrayType(typeID int16) bool {
	switch typeID {
	case BOOL_ARRAY,
		INT8_ARRAY,
		INT16_ARRAY,
		INT32_ARRAY,
		INT64_ARRAY,
		UINT8_ARRAY,
		UINT16_ARRAY,
		UINT32_ARRAY,
		UINT64_ARRAY,
		FLOAT16_ARRAY,
		FLOAT32_ARRAY,
		FLOAT64_ARRAY:
		return true
	default:
		return false
	}
}

var primitiveTypeSizes = map[int16]int{
	BOOL:          1,
	INT8:          1,
	UINT8:         1,
	INT16:         2,
	UINT16:        2,
	FLOAT16:       2,
	INT32:         4,
	VARINT32:      4,
	UINT32:        4,
	VAR_UINT32:    4,
	FLOAT32:       4,
	INT64:         8,
	VARINT64:      8,
	TAGGED_INT64:  8,
	UINT64:        8,
	VAR_UINT64:    8,
	TAGGED_UINT64: 8,
	FLOAT64:       8,
}

// MaxInt31 is the maximum value that fits in 31 bits (used for TAGGED_UINT64 encoding)
const MaxInt31 uint64 = 0x7FFFFFFF // 2^31 - 1

// MinInt31 is the minimum value that fits in 31 bits (used for TAGGED_INT64 encoding)
const MinInt31 int64 = -0x40000000 // -2^30

// MaxInt31Signed is MaxInt31 as a signed int64 for TAGGED_INT64 encoding
const MaxInt31Signed int64 = 0x3FFFFFFF // 2^30 - 1

func getPrimitiveTypeSize(typeID int16) int {
	if sz, ok := primitiveTypeSizes[typeID]; ok {
		return sz
	}
	return -1
}

func isUserDefinedType(typeID int16) bool {
	id := int(typeID & 0xff)
	return id == STRUCT ||
		id == COMPATIBLE_STRUCT ||
		id == NAMED_STRUCT ||
		id == NAMED_COMPATIBLE_STRUCT ||
		id == EXT ||
		id == NAMED_EXT ||
		id == ENUM ||
		id == NAMED_ENUM ||
		id == TYPED_UNION ||
		id == NAMED_UNION
}

// ============================================================================
// DispatchId for switch-based fast path (avoids interface virtual method cost)
// ============================================================================

// DispatchId identifies concrete Go types for optimized serialization dispatch.
// Following Java's pattern with separate IDs for primitive (non-nullable) and boxed (nullable) types.
type DispatchId uint8

const (
	UnknownDispatchId DispatchId = iota

	// ========== VARINT PRIMITIVES (contiguous for efficient jump table) ==========
	// These are used in the hot varint serialization loop
	PrimitiveVarint32DispatchId     // 1 - int32 with varint encoding (most common)
	PrimitiveVarint64DispatchId     // 2 - int64 with varint encoding
	PrimitiveIntDispatchId          // 3 - Go-specific: native int
	PrimitiveVarUint32DispatchId    // 4 - uint32 with varint encoding
	PrimitiveVarUint64DispatchId    // 5 - uint64 with varint encoding
	PrimitiveUintDispatchId         // 6 - Go-specific: native uint
	PrimitiveTaggedInt64DispatchId  // 7 - int64 with tagged encoding
	PrimitiveTaggedUint64DispatchId // 8 - uint64 with tagged encoding

	// ========== FIXED-SIZE PRIMITIVES (contiguous for efficient jump table) ==========
	// These are used in the hot fixed-size serialization loop
	PrimitiveBoolDispatchId    // 9
	PrimitiveInt8DispatchId    // 10
	PrimitiveUint8DispatchId   // 11
	PrimitiveInt16DispatchId   // 12
	PrimitiveUint16DispatchId  // 13
	PrimitiveInt32DispatchId   // 14 - int32 with fixed encoding
	PrimitiveUint32DispatchId  // 15 - uint32 with fixed encoding
	PrimitiveInt64DispatchId   // 16 - int64 with fixed encoding
	PrimitiveUint64DispatchId  // 17 - uint64 with fixed encoding
	PrimitiveFloat32DispatchId // 18
	PrimitiveFloat64DispatchId // 19

	// ========== NULLABLE DISPATCH IDs ==========
	NullableBoolDispatchId
	NullableInt8DispatchId
	NullableInt16DispatchId
	NullableInt32DispatchId
	NullableVarint32DispatchId
	NullableInt64DispatchId
	NullableVarint64DispatchId
	NullableTaggedInt64DispatchId
	NullableFloat32DispatchId
	NullableFloat64DispatchId
	NullableUint8DispatchId
	NullableUint16DispatchId
	NullableUint32DispatchId
	NullableVarUint32DispatchId
	NullableUint64DispatchId
	NullableVarUint64DispatchId
	NullableTaggedUint64DispatchId
	NullableIntDispatchId  // Go-specific: *int
	NullableUintDispatchId // Go-specific: *uint

	// String dispatch ID
	StringDispatchId

	// Slice dispatch IDs
	ByteSliceDispatchId
	Int8SliceDispatchId
	Int16SliceDispatchId
	Int32SliceDispatchId
	Int64SliceDispatchId
	IntSliceDispatchId
	UintSliceDispatchId
	Float32SliceDispatchId
	Float64SliceDispatchId
	BoolSliceDispatchId
	StringSliceDispatchId

	// Map dispatch IDs
	StringStringMapDispatchId
	StringInt32MapDispatchId
	StringInt64MapDispatchId
	StringIntMapDispatchId
	StringFloat64MapDispatchId
	StringBoolMapDispatchId
	Int32Int32MapDispatchId
	Int64Int64MapDispatchId
	IntIntMapDispatchId

	// Enum dispatch ID
	EnumDispatchId // Enum types (both ENUM and NAMED_ENUM)
)

// GetDispatchId returns the DispatchId for a reflect.Type.
// For int32/int64/uint32/uint64, returns varint dispatch IDs by default since that's
// the default encoding in xlang serialization (VARINT32, VARINT64, VAR_UINT32, VAR_UINT64).
func GetDispatchId(t reflect.Type) DispatchId {
	switch t.Kind() {
	case reflect.Bool:
		return PrimitiveBoolDispatchId
	case reflect.Int8:
		return PrimitiveInt8DispatchId
	case reflect.Int16:
		return PrimitiveInt16DispatchId
	case reflect.Int32:
		// Default to varint encoding (VARINT32) for xlang compatibility
		return PrimitiveVarint32DispatchId
	case reflect.Int64:
		// Default to varint encoding (VARINT64) for xlang compatibility
		return PrimitiveVarint64DispatchId
	case reflect.Int:
		return PrimitiveIntDispatchId
	case reflect.Uint8:
		return PrimitiveUint8DispatchId
	case reflect.Uint16:
		return PrimitiveUint16DispatchId
	case reflect.Uint32:
		// Default to varint encoding (VAR_UINT32) for xlang compatibility
		return PrimitiveVarUint32DispatchId
	case reflect.Uint64:
		// Default to varint encoding (VAR_UINT64) for xlang compatibility
		return PrimitiveVarUint64DispatchId
	case reflect.Uint:
		return PrimitiveUintDispatchId
	case reflect.Float32:
		return PrimitiveFloat32DispatchId
	case reflect.Float64:
		return PrimitiveFloat64DispatchId
	case reflect.String:
		return StringDispatchId
	case reflect.Slice:
		// Check for specific slice types
		switch t.Elem().Kind() {
		case reflect.Uint8:
			return ByteSliceDispatchId
		case reflect.Int8:
			return Int8SliceDispatchId
		case reflect.Int16:
			return Int16SliceDispatchId
		case reflect.Int32:
			return Int32SliceDispatchId
		case reflect.Int64:
			return Int64SliceDispatchId
		case reflect.Int:
			return IntSliceDispatchId
		case reflect.Uint:
			return UintSliceDispatchId
		case reflect.Float32:
			return Float32SliceDispatchId
		case reflect.Float64:
			return Float64SliceDispatchId
		case reflect.Bool:
			return BoolSliceDispatchId
		case reflect.String:
			return StringSliceDispatchId
		}
		return UnknownDispatchId
	case reflect.Map:
		// Check for specific common map types
		if t.Key().Kind() == reflect.String {
			switch t.Elem().Kind() {
			case reflect.String:
				return StringStringMapDispatchId
			case reflect.Int64:
				return StringInt64MapDispatchId
			case reflect.Int:
				return StringIntMapDispatchId
			case reflect.Float64:
				return StringFloat64MapDispatchId
			case reflect.Bool:
				return StringBoolMapDispatchId
			}
		} else if t.Key().Kind() == reflect.Int32 && t.Elem().Kind() == reflect.Int32 {
			return Int32Int32MapDispatchId
		} else if t.Key().Kind() == reflect.Int64 && t.Elem().Kind() == reflect.Int64 {
			return Int64Int64MapDispatchId
		} else if t.Key().Kind() == reflect.Int && t.Elem().Kind() == reflect.Int {
			return IntIntMapDispatchId
		}
		return UnknownDispatchId
	default:
		return UnknownDispatchId
	}
}

// IsPrimitiveTypeId checks if a type ID is a primitive type
func IsPrimitiveTypeId(typeId TypeId) bool {
	switch typeId {
	case BOOL, INT8, INT16, INT32, VARINT32, INT64, VARINT64, TAGGED_INT64,
		UINT8, UINT16, UINT32, VAR_UINT32, UINT64, VAR_UINT64, TAGGED_UINT64,
		FLOAT16, FLOAT32, FLOAT64, STRING:
		return true
	default:
		return false
	}
}

// isFixedSizePrimitive returns true for fixed-size primitives.
// Includes INT32/UINT32/INT64/UINT64 (fixed encoding), NOT VARINT32/VAR_UINT32 etc.
func isFixedSizePrimitive(dispatchId DispatchId) bool {
	switch dispatchId {
	case PrimitiveBoolDispatchId, PrimitiveInt8DispatchId, PrimitiveUint8DispatchId,
		PrimitiveInt16DispatchId, PrimitiveUint16DispatchId,
		PrimitiveInt32DispatchId, PrimitiveUint32DispatchId,
		PrimitiveInt64DispatchId, PrimitiveUint64DispatchId,
		PrimitiveFloat32DispatchId, PrimitiveFloat64DispatchId:
		return true
	default:
		return false
	}
}

// isNullableFixedSizePrimitive returns true for nullable fixed-size primitive dispatch IDs.
// These are pointer types that use fixed encoding and have a ref flag.
func isNullableFixedSizePrimitive(dispatchId DispatchId) bool {
	switch dispatchId {
	case NullableBoolDispatchId, NullableInt8DispatchId, NullableUint8DispatchId,
		NullableInt16DispatchId, NullableUint16DispatchId,
		NullableInt32DispatchId, NullableUint32DispatchId,
		NullableInt64DispatchId, NullableUint64DispatchId,
		NullableFloat32DispatchId, NullableFloat64DispatchId:
		return true
	default:
		return false
	}
}

// isNullableVarintPrimitive returns true for nullable varint primitive dispatch IDs.
// These are pointer types that use varint encoding and have a ref flag.
func isNullableVarintPrimitive(dispatchId DispatchId) bool {
	switch dispatchId {
	case NullableVarint32DispatchId, NullableVarint64DispatchId,
		NullableVarUint32DispatchId, NullableVarUint64DispatchId,
		NullableTaggedInt64DispatchId, NullableTaggedUint64DispatchId,
		NullableIntDispatchId, NullableUintDispatchId:
		return true
	default:
		return false
	}
}

// isVarintPrimitive returns true for varint primitives.
// Includes VARINT32/VAR_UINT32/VARINT64/VAR_UINT64 (variable encoding), NOT INT32/UINT32 etc.
func isVarintPrimitive(dispatchId DispatchId) bool {
	switch dispatchId {
	case PrimitiveVarint32DispatchId, PrimitiveVarint64DispatchId,
		PrimitiveVarUint32DispatchId, PrimitiveVarUint64DispatchId,
		PrimitiveTaggedInt64DispatchId, PrimitiveTaggedUint64DispatchId,
		PrimitiveIntDispatchId, PrimitiveUintDispatchId:
		return true
	default:
		return false
	}
}

// isPrimitiveDispatchId returns true if the dispatchId represents a primitive type
func isPrimitiveDispatchId(dispatchId DispatchId) bool {
	switch dispatchId {
	case PrimitiveBoolDispatchId, PrimitiveInt8DispatchId, PrimitiveInt16DispatchId, PrimitiveInt32DispatchId,
		PrimitiveInt64DispatchId, PrimitiveIntDispatchId, PrimitiveUint8DispatchId, PrimitiveUint16DispatchId,
		PrimitiveUint32DispatchId, PrimitiveUint64DispatchId, PrimitiveUintDispatchId,
		PrimitiveFloat32DispatchId, PrimitiveFloat64DispatchId:
		return true
	default:
		return false
	}
}

// isNumericKind returns true for numeric types (Go enums are typically int-based)
func isNumericKind(kind reflect.Kind) bool {
	switch kind {
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
		reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		return true
	default:
		return false
	}
}

func isPrimitiveDispatchKind(kind reflect.Kind) bool {
	switch kind {
	case reflect.Bool, reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
		reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64,
		reflect.Float32, reflect.Float64:
		return true
	default:
		return false
	}
}

// getDispatchIdFromTypeId converts a TypeId to a DispatchId based on nullability.
// This follows Java's DispatchId.xlangTypeIdToDispatchId pattern.
func getDispatchIdFromTypeId(typeId TypeId, nullable bool) DispatchId {
	if nullable {
		// Nullable (nullable) types
		switch typeId {
		case BOOL:
			return NullableBoolDispatchId
		case INT8:
			return NullableInt8DispatchId
		case INT16:
			return NullableInt16DispatchId
		case INT32:
			return NullableInt32DispatchId
		case VARINT32:
			return NullableVarint32DispatchId
		case INT64:
			return NullableInt64DispatchId
		case VARINT64:
			return NullableVarint64DispatchId
		case TAGGED_INT64:
			return NullableTaggedInt64DispatchId
		case FLOAT32:
			return NullableFloat32DispatchId
		case FLOAT64:
			return NullableFloat64DispatchId
		case UINT8:
			return NullableUint8DispatchId
		case UINT16:
			return NullableUint16DispatchId
		case UINT32:
			return NullableUint32DispatchId
		case VAR_UINT32:
			return NullableVarUint32DispatchId
		case UINT64:
			return NullableUint64DispatchId
		case VAR_UINT64:
			return NullableVarUint64DispatchId
		case TAGGED_UINT64:
			return NullableTaggedUint64DispatchId
		case STRING:
			return StringDispatchId
		default:
			return UnknownDispatchId
		}
	} else {
		// Primitive (non-nullable) types
		switch typeId {
		case BOOL:
			return PrimitiveBoolDispatchId
		case INT8:
			return PrimitiveInt8DispatchId
		case INT16:
			return PrimitiveInt16DispatchId
		case INT32:
			return PrimitiveInt32DispatchId
		case VARINT32:
			return PrimitiveVarint32DispatchId
		case INT64:
			return PrimitiveInt64DispatchId
		case VARINT64:
			return PrimitiveVarint64DispatchId
		case TAGGED_INT64:
			return PrimitiveTaggedInt64DispatchId
		case FLOAT32:
			return PrimitiveFloat32DispatchId
		case FLOAT64:
			return PrimitiveFloat64DispatchId
		case UINT8:
			return PrimitiveUint8DispatchId
		case UINT16:
			return PrimitiveUint16DispatchId
		case UINT32:
			return PrimitiveUint32DispatchId
		case VAR_UINT32:
			return PrimitiveVarUint32DispatchId
		case UINT64:
			return PrimitiveUint64DispatchId
		case VAR_UINT64:
			return PrimitiveVarUint64DispatchId
		case TAGGED_UINT64:
			return PrimitiveTaggedUint64DispatchId
		case STRING:
			return StringDispatchId
		default:
			return UnknownDispatchId
		}
	}
}

// IsPrimitiveDispatchId returns true if the dispatch ID is for a primitive (non-nullable) type
func IsPrimitiveDispatchId(id DispatchId) bool {
	return id >= PrimitiveBoolDispatchId && id <= PrimitiveUintDispatchId
}

// IsNullablePrimitiveDispatchId returns true if the dispatch ID is for a nullable primitive type
func IsNullablePrimitiveDispatchId(id DispatchId) bool {
	return id >= NullableBoolDispatchId && id <= NullableUintDispatchId
}

// getFixedSizeByDispatchId returns byte size for fixed primitives (0 if not fixed)
func getFixedSizeByDispatchId(dispatchId DispatchId) int {
	switch dispatchId {
	case PrimitiveBoolDispatchId, PrimitiveInt8DispatchId, PrimitiveUint8DispatchId:
		return 1
	case PrimitiveInt16DispatchId, PrimitiveUint16DispatchId:
		return 2
	case PrimitiveInt32DispatchId, PrimitiveUint32DispatchId, PrimitiveFloat32DispatchId:
		return 4
	case PrimitiveInt64DispatchId, PrimitiveUint64DispatchId, PrimitiveFloat64DispatchId:
		return 8
	default:
		return 0
	}
}

// getVarintMaxSizeByDispatchId returns max byte size for varint primitives (0 if not varint)
func getVarintMaxSizeByDispatchId(dispatchId DispatchId) int {
	switch dispatchId {
	case PrimitiveVarint32DispatchId, PrimitiveVarUint32DispatchId:
		return 5
	case PrimitiveVarint64DispatchId, PrimitiveVarUint64DispatchId, PrimitiveIntDispatchId, PrimitiveUintDispatchId:
		return 10
	case PrimitiveTaggedInt64DispatchId, PrimitiveTaggedUint64DispatchId:
		return 9
	default:
		return 0
	}
}

// getEncodingFromTypeId returns the encoding string ("fixed", "varint", "tagged") from a TypeId.
func getEncodingFromTypeId(typeId TypeId) string {
	internalId := typeId & 0xFF
	switch TypeId(internalId) {
	case INT32, INT64, UINT32, UINT64:
		return "fixed"
	case VARINT32, VARINT64, VAR_UINT32, VAR_UINT64:
		return "varint"
	case TAGGED_INT64, TAGGED_UINT64:
		return "tagged"
	default:
		return "varint" // default encoding
	}
}

// isPrimitiveFixedDispatchId returns true if the dispatch ID is for a non-nullable fixed-size primitive.
// Note: int32/int64/uint32/uint64 are NOT included here because they default to varint encoding.
// Only types that are always fixed-size are included (bool, int8/uint8, int16/uint16, float32/float64).
// Fixed int32/int64/uint32/uint64 encodings (INT32, INT64, UINT32, UINT64) use their specific dispatch IDs.
func isPrimitiveFixedDispatchId(id DispatchId) bool {
	switch id {
	case PrimitiveBoolDispatchId, PrimitiveInt8DispatchId, PrimitiveUint8DispatchId,
		PrimitiveInt16DispatchId, PrimitiveUint16DispatchId,
		// Fixed-size int32/int64/uint32/uint64 - only when explicitly specified via TypeId
		PrimitiveInt32DispatchId, PrimitiveUint32DispatchId,
		PrimitiveInt64DispatchId, PrimitiveUint64DispatchId,
		PrimitiveFloat32DispatchId, PrimitiveFloat64DispatchId:
		return true
	default:
		return false
	}
}

// getFixedSizeByPrimitiveDispatchId returns byte size for fixed primitives based on dispatch ID
func getFixedSizeByPrimitiveDispatchId(id DispatchId) int {
	switch id {
	case PrimitiveBoolDispatchId, PrimitiveInt8DispatchId, PrimitiveUint8DispatchId:
		return 1
	case PrimitiveInt16DispatchId, PrimitiveUint16DispatchId:
		return 2
	case PrimitiveInt32DispatchId, PrimitiveUint32DispatchId, PrimitiveFloat32DispatchId:
		return 4
	case PrimitiveInt64DispatchId, PrimitiveUint64DispatchId, PrimitiveFloat64DispatchId:
		return 8
	default:
		return 0
	}
}

// isPrimitiveVarintDispatchId returns true if the dispatch ID is for a non-nullable varint primitive
func isPrimitiveVarintDispatchId(id DispatchId) bool {
	switch id {
	case PrimitiveVarint32DispatchId, PrimitiveVarint64DispatchId, PrimitiveTaggedInt64DispatchId,
		PrimitiveVarUint32DispatchId, PrimitiveVarUint64DispatchId, PrimitiveTaggedUint64DispatchId,
		PrimitiveIntDispatchId, PrimitiveUintDispatchId:
		return true
	default:
		return false
	}
}

// getVarintMaxSizeByPrimitiveDispatchId returns max byte size for varint primitives based on dispatch ID
func getVarintMaxSizeByPrimitiveDispatchId(id DispatchId) int {
	switch id {
	case PrimitiveVarint32DispatchId, PrimitiveVarUint32DispatchId:
		return 5
	case PrimitiveVarint64DispatchId, PrimitiveVarUint64DispatchId, PrimitiveIntDispatchId, PrimitiveUintDispatchId:
		return 10
	case PrimitiveTaggedInt64DispatchId, PrimitiveTaggedUint64DispatchId:
		return 12 // 4 byte tag + 8 byte value
	default:
		return 0
	}
}
