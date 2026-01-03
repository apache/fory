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
	// VAR_INT32 a 32-bit signed integer which uses fory var_int32 encoding
	VAR_INT32 = 5
	// INT64 Signed 64-bit little-endian integer
	INT64 = 6
	// VAR_INT64 a 64-bit signed integer which uses fory PVL encoding
	VAR_INT64 = 7
	// SLI_INT64 a 64-bit signed integer which uses fory SLI encoding
	SLI_INT64 = 8
	// HALF_FLOAT 2-byte floating point value
	HALF_FLOAT = 9
	// FLOAT 4-byte floating point value
	FLOAT = 10
	// DOUBLE 8-byte floating point value
	DOUBLE = 11
	// STRING UTF8 variable-length string as List<Char>
	STRING = 12
	// ENUM a data type consisting of a set of named values
	ENUM = 13
	// NAMED_ENUM an enum whose value will be serialized as the registered name
	NAMED_ENUM = 14
	// STRUCT a morphic(final) type serialized by Fory Struct serializer
	STRUCT = 15
	// COMPATIBLE_STRUCT a morphic(final) type serialized by Fory compatible Struct serializer
	COMPATIBLE_STRUCT = 16
	// NAMED_STRUCT a struct whose type mapping will be encoded as a name
	NAMED_STRUCT = 17
	// NAMED_COMPATIBLE_STRUCT a compatible_struct whose type mapping will be encoded as a name
	NAMED_COMPATIBLE_STRUCT = 18
	// EXT a type which will be serialized by a customized serializer
	EXT = 19
	// NAMED_EXT an ext type whose type mapping will be encoded as a name
	NAMED_EXT = 20
	// LIST A list of some logical data type
	LIST = 21
	// SET an unordered set of unique elements
	SET = 22
	// MAP Map a repeated struct logical type
	MAP = 23
	// DURATION Measure of elapsed time in either seconds milliseconds microseconds
	DURATION = 24
	// TIMESTAMP Exact timestamp encoded with int64 since UNIX epoch
	TIMESTAMP = 25
	// LOCAL_DATE a naive date without timezone
	LOCAL_DATE = 26
	// DECIMAL128 Precision- and scale-based decimal type with 128 bits.
	DECIMAL128 = 27
	// BINARY Variable-length bytes (no guarantee of UTF8-ness)
	BINARY = 28
	// ARRAY a multidimensional array which every sub-array can have different sizes but all have the same type
	ARRAY = 29
	// BOOL_ARRAY one dimensional bool array
	BOOL_ARRAY = 30
	// INT8_ARRAY one dimensional int8 array
	INT8_ARRAY = 31
	// INT16_ARRAY one dimensional int16 array
	INT16_ARRAY = 32
	// INT32_ARRAY one dimensional int32 array
	INT32_ARRAY = 33
	// INT64_ARRAY one dimensional int64 array
	INT64_ARRAY = 34
	// FLOAT16_ARRAY one dimensional half_float_16 array
	FLOAT16_ARRAY = 35
	// FLOAT32_ARRAY one dimensional float32 array
	FLOAT32_ARRAY = 36
	// FLOAT64_ARRAY one dimensional float64 array
	FLOAT64_ARRAY = 37
	// UNION a tagged union type that can hold one of several alternative types
	UNION = 38
	// NONE represents an empty/unit value with no data (e.g., for empty union alternatives)
	NONE = 39

	// UINT8 Unsigned 8-bit little-endian integer
	UINT8 = 64
	// UINT16 Unsigned 16-bit little-endian integer
	UINT16 = 65
	// UINT32 Unsigned 32-bit little-endian integer
	UINT32 = 66
	// UINT64 Unsigned 64-bit little-endian integer
	UINT64       = 67
	VAR_UINT32   = 68
	VAR_UINT64   = 69
	SLI_UINT64   = 70
	UINT16_ARRAY = 71
	UINT32_ARRAY = 72
	UINT64_ARRAY = 73
)

// IsNamespacedType checks whether the given type ID is a namespace type
func IsNamespacedType(typeID TypeId) bool {
	switch typeID & 0xFF {
	case NAMED_EXT, NAMED_ENUM, NAMED_STRUCT, NAMED_COMPATIBLE_STRUCT:
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
	case NAMED_EXT, NAMED_ENUM, NAMED_STRUCT, NAMED_COMPATIBLE_STRUCT, COMPATIBLE_STRUCT, STRUCT:
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
		INT64,
		FLOAT,
		DOUBLE:
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
	case BOOL, INT8, INT16, INT32, INT64, VAR_INT32, VAR_INT64, SLI_INT64,
		FLOAT, DOUBLE, HALF_FLOAT,
		STRING, TIMESTAMP, LOCAL_DATE, DURATION:
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
		FLOAT32_ARRAY,
		FLOAT64_ARRAY:
		return true
	default:
		return false
	}
}

var primitiveTypeSizes = map[int16]int{
	BOOL:      1,
	INT8:      1,
	INT16:     2,
	INT32:     4,
	VAR_INT32: 4,
	INT64:     8,
	VAR_INT64: 8,
	FLOAT:     4,
	DOUBLE:    8,
}

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
		id == NAMED_ENUM
}

// ============================================================================
// StaticTypeId for switch-based fast path (avoids interface virtual method cost)
// ============================================================================

// StaticTypeId identifies concrete Go types for optimized serialization dispatch
type StaticTypeId uint8

const (
	ConcreteTypeOther StaticTypeId = iota
	ConcreteTypeBool
	ConcreteTypeInt8
	ConcreteTypeInt16
	ConcreteTypeInt32
	ConcreteTypeInt64
	ConcreteTypeInt
	ConcreteTypeFloat32
	ConcreteTypeFloat64
	ConcreteTypeString
	ConcreteTypeByteSlice
	ConcreteTypeInt8Slice
	ConcreteTypeInt16Slice
	ConcreteTypeInt32Slice
	ConcreteTypeInt64Slice
	ConcreteTypeIntSlice
	ConcreteTypeUintSlice
	ConcreteTypeFloat32Slice
	ConcreteTypeFloat64Slice
	ConcreteTypeBoolSlice
	ConcreteTypeStringSlice
	ConcreteTypeStringStringMap
	ConcreteTypeStringInt32Map
	ConcreteTypeStringInt64Map
	ConcreteTypeStringIntMap
	ConcreteTypeStringFloat64Map
	ConcreteTypeStringBoolMap
	ConcreteTypeInt32Int32Map
	ConcreteTypeInt64Int64Map
	ConcreteTypeIntIntMap
	ConcreteTypeEnum // Enum types (both ENUM and NAMED_ENUM)
)

// GetStaticTypeId returns the StaticTypeId for a reflect.Type
func GetStaticTypeId(t reflect.Type) StaticTypeId {
	switch t.Kind() {
	case reflect.Bool:
		return ConcreteTypeBool
	case reflect.Int8:
		return ConcreteTypeInt8
	case reflect.Int16:
		return ConcreteTypeInt16
	case reflect.Int32:
		return ConcreteTypeInt32
	case reflect.Int64:
		return ConcreteTypeInt64
	case reflect.Int:
		return ConcreteTypeInt
	case reflect.Float32:
		return ConcreteTypeFloat32
	case reflect.Float64:
		return ConcreteTypeFloat64
	case reflect.String:
		return ConcreteTypeString
	case reflect.Slice:
		// Check for specific slice types
		switch t.Elem().Kind() {
		case reflect.Uint8:
			return ConcreteTypeByteSlice
		case reflect.Int8:
			return ConcreteTypeInt8Slice
		case reflect.Int16:
			return ConcreteTypeInt16Slice
		case reflect.Int32:
			return ConcreteTypeInt32Slice
		case reflect.Int64:
			return ConcreteTypeInt64Slice
		case reflect.Int:
			return ConcreteTypeIntSlice
		case reflect.Uint:
			return ConcreteTypeUintSlice
		case reflect.Float32:
			return ConcreteTypeFloat32Slice
		case reflect.Float64:
			return ConcreteTypeFloat64Slice
		case reflect.Bool:
			return ConcreteTypeBoolSlice
		case reflect.String:
			return ConcreteTypeStringSlice
		}
		return ConcreteTypeOther
	case reflect.Map:
		// Check for specific common map types
		if t.Key().Kind() == reflect.String {
			switch t.Elem().Kind() {
			case reflect.String:
				return ConcreteTypeStringStringMap
			case reflect.Int64:
				return ConcreteTypeStringInt64Map
			case reflect.Int:
				return ConcreteTypeStringIntMap
			case reflect.Float64:
				return ConcreteTypeStringFloat64Map
			case reflect.Bool:
				return ConcreteTypeStringBoolMap
			}
		} else if t.Key().Kind() == reflect.Int32 && t.Elem().Kind() == reflect.Int32 {
			return ConcreteTypeInt32Int32Map
		} else if t.Key().Kind() == reflect.Int64 && t.Elem().Kind() == reflect.Int64 {
			return ConcreteTypeInt64Int64Map
		} else if t.Key().Kind() == reflect.Int && t.Elem().Kind() == reflect.Int {
			return ConcreteTypeIntIntMap
		}
		return ConcreteTypeOther
	default:
		return ConcreteTypeOther
	}
}

// GetConcreteTypeIdAndTypeId returns both StaticTypeId and TypeId for a reflect.Type
func GetConcreteTypeIdAndTypeId(t reflect.Type) (StaticTypeId, TypeId) {
	switch t.Kind() {
	case reflect.Bool:
		return ConcreteTypeBool, BOOL
	case reflect.Int8:
		return ConcreteTypeInt8, INT8
	case reflect.Int16:
		return ConcreteTypeInt16, INT16
	case reflect.Int32:
		return ConcreteTypeInt32, INT32
	case reflect.Int64:
		return ConcreteTypeInt64, INT64
	case reflect.Float32:
		return ConcreteTypeFloat32, FLOAT
	case reflect.Float64:
		return ConcreteTypeFloat64, DOUBLE
	case reflect.String:
		return ConcreteTypeString, STRING
	default:
		return ConcreteTypeOther, 0
	}
}

// IsPrimitiveTypeId checks if a type ID is a primitive type
func IsPrimitiveTypeId(typeId TypeId) bool {
	switch typeId {
	case BOOL, INT8, INT16, INT32, INT64, FLOAT, DOUBLE, STRING:
		return true
	default:
		return false
	}
}

// isFixedSizePrimitive returns true for non-nullable fixed-size primitives
func isFixedSizePrimitive(staticId StaticTypeId, referencable bool) bool {
	if referencable {
		return false
	}
	switch staticId {
	case ConcreteTypeBool, ConcreteTypeInt8, ConcreteTypeInt16,
		ConcreteTypeFloat32, ConcreteTypeFloat64:
		return true
	default:
		return false
	}
}

// isVarintPrimitive returns true for non-nullable varint primitives
func isVarintPrimitive(staticId StaticTypeId, referencable bool) bool {
	if referencable {
		return false
	}
	switch staticId {
	case ConcreteTypeInt32, ConcreteTypeInt64, ConcreteTypeInt:
		return true
	default:
		return false
	}
}

// isPrimitiveStaticId returns true if the staticId represents a primitive type
func isPrimitiveStaticId(staticId StaticTypeId) bool {
	switch staticId {
	case ConcreteTypeBool, ConcreteTypeInt8, ConcreteTypeInt16, ConcreteTypeInt32,
		ConcreteTypeInt64, ConcreteTypeInt, ConcreteTypeFloat32, ConcreteTypeFloat64:
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

// getFixedSizeByStaticId returns byte size for fixed primitives (0 if not fixed)
func getFixedSizeByStaticId(staticId StaticTypeId) int {
	switch staticId {
	case ConcreteTypeBool, ConcreteTypeInt8:
		return 1
	case ConcreteTypeInt16:
		return 2
	case ConcreteTypeFloat32:
		return 4
	case ConcreteTypeFloat64:
		return 8
	default:
		return 0
	}
}

// getVarintMaxSizeByStaticId returns max byte size for varint primitives (0 if not varint)
func getVarintMaxSizeByStaticId(staticId StaticTypeId) int {
	switch staticId {
	case ConcreteTypeInt32:
		return 5
	case ConcreteTypeInt64, ConcreteTypeInt:
		return 10
	default:
		return 0
	}
}
