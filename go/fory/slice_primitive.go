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
	"strconv"
	"unsafe"

	"github.com/apache/fory/go/fory/bfloat16"
	"github.com/apache/fory/go/fory/float16"
)

// isNilSlice checks if a value is a nil slice. Safe to call on any value type.
// Returns false for arrays and other non-slice types.
func isNilSlice(v reflect.Value) bool {
	return v.Kind() == reflect.Slice && v.IsNil()
}

// ============================================================================
// byteSliceSerializer - optimized []byte serialization
// ============================================================================

type byteSliceSerializer struct{}

func (s byteSliceSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	v := value.Interface().([]byte)
	buf := ctx.Buffer()
	buf.WriteLength(len(v))
	if len(v) > 0 {
		buf.WriteBinary(v)
	}
}

func (s byteSliceSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeSliceRefAndType(ctx, refMode, writeType, value, BINARY)
	if done || ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s byteSliceSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done, typeId := readSliceRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	if readType && typeId != uint32(BINARY) && typeId != uint32(UINT8_ARRAY) {
		ctx.SetError(DeserializationErrorf("slice type mismatch: expected BINARY (%d) or UINT8_ARRAY (%d), got %d", BINARY, UINT8_ARRAY, typeId))
		return
	}
	s.ReadData(ctx, value)
}

func (s byteSliceSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

func (s byteSliceSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	length := buf.ReadLength(ctxErr)
	ctx.checkCollectionSize(length)
	if ctx.HasError() {
		return
	}
	ptr := (*[]byte)(value.Addr().UnsafePointer())
	if length == 0 {
		*ptr = make([]byte, 0)
		return
	}
	result := make([]byte, length)
	raw := buf.ReadBinary(length, ctxErr)
	copy(result, raw)
	*ptr = result
}

type ByteSliceBufferObject struct {
	data []byte
}

func (o *ByteSliceBufferObject) TotalBytes() int {
	return len(o.data)
}

func (o *ByteSliceBufferObject) WriteTo(buf *ByteBuffer) {
	buf.WriteBinary(o.data)
}

func (o *ByteSliceBufferObject) ToBuffer() *ByteBuffer {
	return NewByteBuffer(o.data)
}

// ============================================================================
// boolSliceSerializer - optimized []bool serialization
// ============================================================================

type boolSliceSerializer struct{}

func (s boolSliceSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	WriteBoolSlice(ctx.Buffer(), value.Interface().([]bool))
}

func (s boolSliceSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeSliceRefAndType(ctx, refMode, writeType, value, BOOL_ARRAY)
	if done || ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s boolSliceSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done, typeId := readSliceRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	if readType && typeId != uint32(BOOL_ARRAY) {
		ctx.SetError(DeserializationErrorf("slice type mismatch: expected BOOL_ARRAY (%d), got %d", BOOL_ARRAY, typeId))
		return
	}
	s.ReadData(ctx, value)
}

func (s boolSliceSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

func (s boolSliceSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	*(*[]bool)(value.Addr().UnsafePointer()) = ReadBoolSlice(ctx.Buffer(), ctx.Err())
}

// ============================================================================
// int8SliceSerializer - optimized []int8 serialization
// ============================================================================

type int8SliceSerializer struct{}

func (s int8SliceSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	WriteInt8Slice(ctx.Buffer(), value.Interface().([]int8))
}

func (s int8SliceSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeSliceRefAndType(ctx, refMode, writeType, value, INT8_ARRAY)
	if done || ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s int8SliceSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done, typeId := readSliceRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	if readType && typeId != uint32(INT8_ARRAY) {
		ctx.SetError(DeserializationErrorf("slice type mismatch: expected INT8_ARRAY (%d), got %d", INT8_ARRAY, typeId))
		return
	}
	s.ReadData(ctx, value)
}

func (s int8SliceSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

func (s int8SliceSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	*(*[]int8)(value.Addr().UnsafePointer()) = ReadInt8Slice(ctx.Buffer(), ctx.Err())
}

// ============================================================================
// int16SliceSerializer - optimized []int16 serialization
// ============================================================================

type int16SliceSerializer struct{}

func (s int16SliceSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	WriteInt16Slice(ctx.Buffer(), value.Interface().([]int16))
}

func (s int16SliceSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeSliceRefAndType(ctx, refMode, writeType, value, INT16_ARRAY)
	if done || ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s int16SliceSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done, typeId := readSliceRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	if readType && typeId != uint32(INT16_ARRAY) {
		ctx.SetError(DeserializationErrorf("slice type mismatch: expected INT16_ARRAY (%d), got %d", INT16_ARRAY, typeId))
		return
	}
	s.ReadData(ctx, value)
}

func (s int16SliceSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

func (s int16SliceSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	*(*[]int16)(value.Addr().UnsafePointer()) = ReadInt16Slice(ctx.Buffer(), ctx.Err())
}

// ============================================================================
// int32SliceSerializer - optimized []int32 serialization
// ============================================================================

type int32SliceSerializer struct{}

func (s int32SliceSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	WriteInt32Slice(ctx.Buffer(), value.Interface().([]int32))
}

func (s int32SliceSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeSliceRefAndType(ctx, refMode, writeType, value, INT32_ARRAY)
	if done || ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s int32SliceSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done, typeId := readSliceRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	if readType && typeId != uint32(INT32_ARRAY) {
		ctx.SetError(DeserializationErrorf("slice type mismatch: expected INT32_ARRAY (%d), got %d", INT32_ARRAY, typeId))
		return
	}
	s.ReadData(ctx, value)
}

func (s int32SliceSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

func (s int32SliceSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	*(*[]int32)(value.Addr().UnsafePointer()) = ReadInt32Slice(ctx.Buffer(), ctx.Err())
}

// ============================================================================
// int64SliceSerializer - optimized []int64 serialization
// ============================================================================

type int64SliceSerializer struct{}

func (s int64SliceSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	WriteInt64Slice(ctx.Buffer(), value.Interface().([]int64))
}

func (s int64SliceSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeSliceRefAndType(ctx, refMode, writeType, value, INT64_ARRAY)
	if done || ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s int64SliceSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done, typeId := readSliceRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	if readType && typeId != uint32(INT64_ARRAY) {
		ctx.SetError(DeserializationErrorf("slice type mismatch: expected INT64_ARRAY (%d), got %d", INT64_ARRAY, typeId))
		return
	}
	s.ReadData(ctx, value)
}

func (s int64SliceSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

func (s int64SliceSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	*(*[]int64)(value.Addr().UnsafePointer()) = ReadInt64Slice(ctx.Buffer(), ctx.Err())
}

// ============================================================================
// uint16SliceSerializer - optimized []uint16 serialization
// ============================================================================

type uint16SliceSerializer struct{}

func (s uint16SliceSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	WriteUint16Slice(ctx.Buffer(), value.Interface().([]uint16))
}

func (s uint16SliceSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeSliceRefAndType(ctx, refMode, writeType, value, UINT16_ARRAY)
	if done || ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s uint16SliceSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done, typeId := readSliceRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	if readType && typeId != uint32(UINT16_ARRAY) {
		ctx.SetError(DeserializationErrorf("slice type mismatch: expected UINT16_ARRAY (%d), got %d", UINT16_ARRAY, typeId))
		return
	}
	s.ReadData(ctx, value)
}

func (s uint16SliceSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

func (s uint16SliceSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	*(*[]uint16)(value.Addr().UnsafePointer()) = ReadUint16Slice(ctx.Buffer(), ctx.Err())
}

// ============================================================================
// uint32SliceSerializer - optimized []uint32 serialization
// ============================================================================

type uint32SliceSerializer struct{}

func (s uint32SliceSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	WriteUint32Slice(ctx.Buffer(), value.Interface().([]uint32))
}

func (s uint32SliceSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeSliceRefAndType(ctx, refMode, writeType, value, UINT32_ARRAY)
	if done || ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s uint32SliceSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done, typeId := readSliceRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	if readType && typeId != uint32(UINT32_ARRAY) {
		ctx.SetError(DeserializationErrorf("slice type mismatch: expected UINT32_ARRAY (%d), got %d", UINT32_ARRAY, typeId))
		return
	}
	s.ReadData(ctx, value)
}

func (s uint32SliceSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

func (s uint32SliceSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	*(*[]uint32)(value.Addr().UnsafePointer()) = ReadUint32Slice(ctx.Buffer(), ctx.Err())
}

// ============================================================================
// uint64SliceSerializer - optimized []uint64 serialization
// ============================================================================

type uint64SliceSerializer struct{}

func (s uint64SliceSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	WriteUint64Slice(ctx.Buffer(), value.Interface().([]uint64))
}

func (s uint64SliceSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeSliceRefAndType(ctx, refMode, writeType, value, UINT64_ARRAY)
	if done || ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s uint64SliceSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done, typeId := readSliceRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	if readType && typeId != uint32(UINT64_ARRAY) {
		ctx.SetError(DeserializationErrorf("slice type mismatch: expected UINT64_ARRAY (%d), got %d", UINT64_ARRAY, typeId))
		return
	}
	s.ReadData(ctx, value)
}

func (s uint64SliceSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

func (s uint64SliceSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	*(*[]uint64)(value.Addr().UnsafePointer()) = ReadUint64Slice(ctx.Buffer(), ctx.Err())
}

// ============================================================================
// float32SliceSerializer - optimized []float32 serialization
// ============================================================================

type float32SliceSerializer struct{}

func (s float32SliceSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	WriteFloat32Slice(ctx.Buffer(), value.Interface().([]float32))
}

func (s float32SliceSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeSliceRefAndType(ctx, refMode, writeType, value, FLOAT32_ARRAY)
	if done || ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s float32SliceSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done, typeId := readSliceRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	if readType && typeId != uint32(FLOAT32_ARRAY) {
		ctx.SetError(DeserializationErrorf("slice type mismatch: expected FLOAT32_ARRAY (%d), got %d", FLOAT32_ARRAY, typeId))
		return
	}
	s.ReadData(ctx, value)
}

func (s float32SliceSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

func (s float32SliceSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	*(*[]float32)(value.Addr().UnsafePointer()) = ReadFloat32Slice(ctx.Buffer(), ctx.Err())
}

// ============================================================================
// float64SliceSerializer - optimized []float64 serialization
// ============================================================================

type float64SliceSerializer struct{}

func (s float64SliceSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	WriteFloat64Slice(ctx.Buffer(), value.Interface().([]float64))
}

func (s float64SliceSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeSliceRefAndType(ctx, refMode, writeType, value, FLOAT64_ARRAY)
	if done || ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s float64SliceSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done, typeId := readSliceRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	if readType && typeId != uint32(FLOAT64_ARRAY) {
		ctx.SetError(DeserializationErrorf("slice type mismatch: expected FLOAT64_ARRAY (%d), got %d", FLOAT64_ARRAY, typeId))
		return
	}
	s.ReadData(ctx, value)
}

func (s float64SliceSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

func (s float64SliceSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	*(*[]float64)(value.Addr().UnsafePointer()) = ReadFloat64Slice(ctx.Buffer(), ctx.Err())
}

// ============================================================================
// intSliceSerializer - optimized []int serialization
// ============================================================================

type intSliceSerializer struct{}

func (s intSliceSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	WriteIntSlice(ctx.Buffer(), value.Interface().([]int))
}

func (s intSliceSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	var typeId TypeId = INT32_ARRAY
	if strconv.IntSize == 64 {
		typeId = INT64_ARRAY
	}
	done := writeSliceRefAndType(ctx, refMode, writeType, value, typeId)
	if done || ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s intSliceSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done, typeId := readSliceRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	if readType {
		var expected TypeId = INT32_ARRAY
		if strconv.IntSize == 64 {
			expected = INT64_ARRAY
		}
		if typeId != uint32(expected) {
			ctx.SetError(DeserializationErrorf("slice type mismatch: expected %d, got %d", expected, typeId))
			return
		}
	}
	s.ReadData(ctx, value)
}

func (s intSliceSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

func (s intSliceSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	*(*[]int)(value.Addr().UnsafePointer()) = ReadIntSlice(ctx.Buffer(), ctx.Err())
}

// ============================================================================
// uintSliceSerializer - optimized []uint serialization
// This serializer only supports pure Go mode (xlang=false) because uint has
// platform-dependent size which doesn't have a direct cross-language equivalent.
// ============================================================================

type uintSliceSerializer struct{}

func (s uintSliceSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	WriteUintSlice(ctx.Buffer(), value.Interface().([]uint))
}

func (s uintSliceSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	var typeId TypeId = INT32_ARRAY
	if strconv.IntSize == 64 {
		typeId = INT64_ARRAY
	}
	done := writeSliceRefAndType(ctx, refMode, writeType, value, typeId)
	if done || ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s uintSliceSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done, typeId := readSliceRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	if readType {
		var expected TypeId = INT32_ARRAY
		if strconv.IntSize == 64 {
			expected = INT64_ARRAY
		}
		if typeId != uint32(expected) {
			ctx.SetError(DeserializationErrorf("slice type mismatch: expected %d, got %d", expected, typeId))
			return
		}
	}
	s.ReadData(ctx, value)
}

func (s uintSliceSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

func (s uintSliceSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	*(*[]uint)(value.Addr().UnsafePointer()) = ReadUintSlice(ctx.Buffer(), ctx.Err())
}

// ============================================================================
// stringSliceSerializer - optimized []string serialization
// ============================================================================

type stringSliceSerializer struct{}

func (s stringSliceSerializer) writeDataWithGenerics(ctx *WriteContext, value reflect.Value, hasGenerics bool) {
	v := value.Interface().([]string)
	buf := ctx.Buffer()
	length := len(v)
	buf.WriteVarUint32(uint32(length))
	if length == 0 {
		return
	}
	// Write collection flags
	// Note: Strings don't need reference tracking per xlang spec (NeedWriteRef(STRING) = false)
	if hasGenerics {
		// When element type is known from TypeDef, use CollectionDeclSameType and skip element type info
		buf.WriteInt8(int8(CollectionDeclSameType))
	} else {
		// When element type is not known, write CollectionIsSameType and element type info
		buf.WriteInt8(int8(CollectionIsSameType))
		buf.WriteUint8(uint8(STRING))
	}

	// Write elements directly (no ref flag for strings)
	for i := 0; i < length; i++ {
		writeString(buf, v[i])
	}
}

func (s stringSliceSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	s.writeDataWithGenerics(ctx, value, false)
}

func (s stringSliceSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeSliceRefAndType(ctx, refMode, writeType, value, LIST)
	if done || ctx.HasError() {
		return
	}
	s.writeDataWithGenerics(ctx, value, hasGenerics)
}

func (s stringSliceSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done, typeId := readSliceRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	if readType && typeId != uint32(LIST) {
		ctx.SetError(DeserializationErrorf("slice type mismatch: expected LIST (%d), got %d", LIST, typeId))
		return
	}
	s.ReadData(ctx, value)
}

func (s stringSliceSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

func (s stringSliceSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	length := int(buf.ReadVarUint32(ctxErr))
	ctx.checkCollectionSize(length)
	if ctx.HasError() {
		return
	}
	ptr := (*[]string)(value.Addr().UnsafePointer())
	if length == 0 {
		*ptr = make([]string, 0)
		return
	}

	// Read collection flags
	collectFlag := buf.ReadInt8(ctxErr)

	// Read element type info if present (when CollectionIsSameType but not CollectionIsDeclElementType)
	if (collectFlag&CollectionIsSameType) != 0 && (collectFlag&CollectionIsDeclElementType) == 0 {
		_ = buf.ReadUint8(ctxErr) // Read and discard type ID (we know it's STRING)
	}

	result := make([]string, length)

	// Check if remote sent with ref tracking (handle both cases for compatibility)
	trackRefs := (collectFlag & CollectionTrackingRef) != 0

	// Read elements
	for i := 0; i < length; i++ {
		if trackRefs {
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				continue // null string, leave as zero value
			}
		}
		result[i] = readString(buf, ctxErr)
	}
	*ptr = result
}

// ============================================================================
// Exported helper functions for primitive slice serialization (ARRAY protocol)
// These functions write: size_bytes + binary_data
// They are used by struct serializers and generated code
// ============================================================================

// WriteByteSlice writes []byte to buffer using ARRAY protocol
//
//go:inline
func WriteByteSlice(buf *ByteBuffer, value []byte) {
	buf.WriteLength(len(value))
	if len(value) > 0 {
		buf.WriteBinary(value)
	}
}

// ReadByteSlice reads []byte from buffer using ARRAY protocol
//
//go:inline
func ReadByteSlice(buf *ByteBuffer, err *Error) []byte {
	size := buf.ReadLength(err)
	if size == 0 {
		return make([]byte, 0)
	}
	result := make([]byte, size)
	raw := buf.ReadBinary(size, err)
	copy(result, raw)
	return result
}

// WriteBoolSlice writes []bool to buffer using ARRAY protocol
//
//go:inline
func WriteBoolSlice(buf *ByteBuffer, value []bool) {
	size := len(value)
	buf.WriteLength(size)
	if size > 0 {
		buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
	}
}

// ReadBoolSlice reads []bool from buffer using ARRAY protocol
//
//go:inline
func ReadBoolSlice(buf *ByteBuffer, err *Error) []bool {
	size := buf.ReadLength(err)
	if size == 0 {
		return make([]bool, 0)
	}
	result := make([]bool, size)
	raw := buf.ReadBinary(size, err)
	copy(unsafe.Slice((*byte)(unsafe.Pointer(&result[0])), size), raw)
	return result
}

// WriteInt8Slice writes []int8 to buffer using ARRAY protocol
//
//go:inline
func WriteInt8Slice(buf *ByteBuffer, value []int8) {
	size := len(value)
	buf.WriteLength(size)
	if size > 0 {
		buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
	}
}

// ReadInt8Slice reads []int8 from buffer using ARRAY protocol
//
//go:inline
func ReadInt8Slice(buf *ByteBuffer, err *Error) []int8 {
	size := buf.ReadLength(err)
	if size == 0 {
		return make([]int8, 0)
	}
	result := make([]int8, size)
	raw := buf.ReadBinary(size, err)
	copy(unsafe.Slice((*byte)(unsafe.Pointer(&result[0])), size), raw)
	return result
}

// WriteInt16Slice writes []int16 to buffer using ARRAY protocol
//
//go:inline
func WriteInt16Slice(buf *ByteBuffer, value []int16) {
	size := len(value) * 2
	buf.WriteLength(size)
	if len(value) > 0 {
		if isLittleEndian {
			buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
		} else {
			for i := 0; i < len(value); i++ {
				buf.WriteInt16(value[i])
			}
		}
	}
}

// ReadInt16Slice reads []int16 from buffer using ARRAY protocol
//
//go:inline
func ReadInt16Slice(buf *ByteBuffer, err *Error) []int16 {
	size := buf.ReadLength(err)
	length := size / 2
	if length == 0 {
		return make([]int16, 0)
	}
	result := make([]int16, length)
	if isLittleEndian {
		raw := buf.ReadBinary(size, err)
		copy(unsafe.Slice((*byte)(unsafe.Pointer(&result[0])), size), raw)
	} else {
		for i := 0; i < length; i++ {
			result[i] = buf.ReadInt16(err)
		}
	}
	return result
}

// WriteInt32Slice writes []int32 to buffer using ARRAY protocol
//
//go:inline
func WriteInt32Slice(buf *ByteBuffer, value []int32) {
	size := len(value) * 4
	buf.WriteLength(size)
	if len(value) > 0 {
		if isLittleEndian {
			buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
		} else {
			for i := 0; i < len(value); i++ {
				buf.WriteInt32(value[i])
			}
		}
	}
}

// ReadInt32Slice reads []int32 from buffer using ARRAY protocol
//
//go:inline
func ReadInt32Slice(buf *ByteBuffer, err *Error) []int32 {
	size := buf.ReadLength(err)
	length := size / 4
	if length == 0 {
		return make([]int32, 0)
	}
	result := make([]int32, length)
	if isLittleEndian {
		raw := buf.ReadBinary(size, err)
		copy(unsafe.Slice((*byte)(unsafe.Pointer(&result[0])), size), raw)
	} else {
		for i := 0; i < length; i++ {
			result[i] = buf.ReadInt32(err)
		}
	}
	return result
}

// WriteInt64Slice writes []int64 to buffer using ARRAY protocol
//
//go:inline
func WriteInt64Slice(buf *ByteBuffer, value []int64) {
	size := len(value) * 8
	buf.WriteLength(size)
	if len(value) > 0 {
		if isLittleEndian {
			buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
		} else {
			for i := 0; i < len(value); i++ {
				buf.WriteInt64(value[i])
			}
		}
	}
}

// ReadInt64Slice reads []int64 from buffer using ARRAY protocol
//
//go:inline
func ReadInt64Slice(buf *ByteBuffer, err *Error) []int64 {
	size := buf.ReadLength(err)
	length := size / 8
	if length == 0 {
		return make([]int64, 0)
	}
	result := make([]int64, length)
	if isLittleEndian {
		raw := buf.ReadBinary(size, err)
		copy(unsafe.Slice((*byte)(unsafe.Pointer(&result[0])), size), raw)
	} else {
		for i := 0; i < length; i++ {
			result[i] = buf.ReadInt64(err)
		}
	}
	return result
}

// WriteUint16Slice writes []uint16 to buffer using ARRAY protocol
//
//go:inline
func WriteUint16Slice(buf *ByteBuffer, value []uint16) {
	size := len(value) * 2
	buf.WriteLength(size)
	if len(value) > 0 {
		if isLittleEndian {
			buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
		} else {
			for i := 0; i < len(value); i++ {
				buf.WriteInt16(int16(value[i]))
			}
		}
	}
}

// ReadUint16Slice reads []uint16 from buffer using ARRAY protocol
//
//go:inline
func ReadUint16Slice(buf *ByteBuffer, err *Error) []uint16 {
	size := buf.ReadLength(err)
	length := size / 2
	if length == 0 {
		return make([]uint16, 0)
	}
	result := make([]uint16, length)
	if isLittleEndian {
		raw := buf.ReadBinary(size, err)
		copy(unsafe.Slice((*byte)(unsafe.Pointer(&result[0])), size), raw)
	} else {
		for i := 0; i < length; i++ {
			result[i] = uint16(buf.ReadInt16(err))
		}
	}
	return result
}

// WriteUint32Slice writes []uint32 to buffer using ARRAY protocol
//
//go:inline
func WriteUint32Slice(buf *ByteBuffer, value []uint32) {
	size := len(value) * 4
	buf.WriteLength(size)
	if len(value) > 0 {
		if isLittleEndian {
			buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
		} else {
			for i := 0; i < len(value); i++ {
				buf.WriteInt32(int32(value[i]))
			}
		}
	}
}

// ReadUint32Slice reads []uint32 from buffer using ARRAY protocol
//
//go:inline
func ReadUint32Slice(buf *ByteBuffer, err *Error) []uint32 {
	size := buf.ReadLength(err)
	length := size / 4
	if length == 0 {
		return make([]uint32, 0)
	}
	result := make([]uint32, length)
	if isLittleEndian {
		raw := buf.ReadBinary(size, err)
		copy(unsafe.Slice((*byte)(unsafe.Pointer(&result[0])), size), raw)
	} else {
		for i := 0; i < length; i++ {
			result[i] = uint32(buf.ReadInt32(err))
		}
	}
	return result
}

// WriteUint64Slice writes []uint64 to buffer using ARRAY protocol
//
//go:inline
func WriteUint64Slice(buf *ByteBuffer, value []uint64) {
	size := len(value) * 8
	buf.WriteLength(size)
	if len(value) > 0 {
		if isLittleEndian {
			buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
		} else {
			for i := 0; i < len(value); i++ {
				buf.WriteInt64(int64(value[i]))
			}
		}
	}
}

// ReadUint64Slice reads []uint64 from buffer using ARRAY protocol
//
//go:inline
func ReadUint64Slice(buf *ByteBuffer, err *Error) []uint64 {
	size := buf.ReadLength(err)
	length := size / 8
	if length == 0 {
		return make([]uint64, 0)
	}
	result := make([]uint64, length)
	if isLittleEndian {
		raw := buf.ReadBinary(size, err)
		copy(unsafe.Slice((*byte)(unsafe.Pointer(&result[0])), size), raw)
	} else {
		for i := 0; i < length; i++ {
			result[i] = uint64(buf.ReadInt64(err))
		}
	}
	return result
}

// WriteFloat32Slice writes []float32 to buffer using ARRAY protocol
//
//go:inline
func WriteFloat32Slice(buf *ByteBuffer, value []float32) {
	size := len(value) * 4
	buf.WriteLength(size)
	if len(value) > 0 {
		if isLittleEndian {
			buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
		} else {
			for i := 0; i < len(value); i++ {
				buf.WriteFloat32(value[i])
			}
		}
	}
}

// ReadFloat32Slice reads []float32 from buffer using ARRAY protocol
//
//go:inline
func ReadFloat32Slice(buf *ByteBuffer, err *Error) []float32 {
	size := buf.ReadLength(err)
	length := size / 4
	if length == 0 {
		return make([]float32, 0)
	}
	result := make([]float32, length)
	if isLittleEndian {
		raw := buf.ReadBinary(size, err)
		copy(unsafe.Slice((*byte)(unsafe.Pointer(&result[0])), size), raw)
	} else {
		for i := 0; i < length; i++ {
			result[i] = buf.ReadFloat32(err)
		}
	}
	return result
}

// WriteFloat64Slice writes []float64 to buffer using ARRAY protocol
//
//go:inline
func WriteFloat64Slice(buf *ByteBuffer, value []float64) {
	size := len(value) * 8
	buf.WriteLength(size)
	if len(value) > 0 {
		if isLittleEndian {
			buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
		} else {
			for i := 0; i < len(value); i++ {
				buf.WriteFloat64(value[i])
			}
		}
	}
}

// ReadFloat64Slice reads []float64 from buffer using ARRAY protocol
//
//go:inline
func ReadFloat64Slice(buf *ByteBuffer, err *Error) []float64 {
	size := buf.ReadLength(err)
	length := size / 8
	if length == 0 {
		return make([]float64, 0)
	}
	result := make([]float64, length)
	if isLittleEndian {
		raw := buf.ReadBinary(size, err)
		copy(unsafe.Slice((*byte)(unsafe.Pointer(&result[0])), size), raw)
	} else {
		for i := 0; i < length; i++ {
			result[i] = buf.ReadFloat64(err)
		}
	}
	return result
}

// ============================================================================
// float16SliceSerializer - optimized []float16.Float16 serialization
// ============================================================================

type float16SliceSerializer struct{}

func (s float16SliceSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	// Cast to []float16.Float16
	v := value.Interface().([]float16.Float16)
	buf := ctx.Buffer()
	length := len(v)
	size := length * 2
	buf.WriteLength(size)
	if length > 0 {
		// Float16 is uint16 underneath, so we can cast slice pointer
		ptr := unsafe.Pointer(&v[0])
		if isLittleEndian {
			buf.WriteBinary(unsafe.Slice((*byte)(ptr), size))
		} else {
			// Big-endian architectures need explicit byte swapping
			for i := 0; i < length; i++ {
				// We can just write as uint16, WriteUint16 handles endianness for us
				// Float16.Bits() returns uint16
				buf.WriteUint16(v[i].Bits())
			}
		}
	}
}

func (s float16SliceSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeSliceRefAndType(ctx, refMode, writeType, value, FLOAT16_ARRAY)
	if done || ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s float16SliceSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done, typeId := readSliceRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	if readType && typeId != uint32(FLOAT16_ARRAY) {
		ctx.SetError(DeserializationErrorf("slice type mismatch: expected FLOAT16_ARRAY (%d), got %d", FLOAT16_ARRAY, typeId))
		return
	}
	s.ReadData(ctx, value)
}

func (s float16SliceSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

func (s float16SliceSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	size := buf.ReadLength(ctxErr)
	length := size / 2
	if ctx.HasError() {
		return
	}

	// Ensure capacity
	ptr := (*[]float16.Float16)(value.Addr().UnsafePointer())
	if length == 0 {
		*ptr = make([]float16.Float16, 0)
		return
	}

	result := make([]float16.Float16, length)

	if isLittleEndian {
		raw := buf.ReadBinary(size, ctxErr)
		// unsafe copy
		targetPtr := unsafe.Pointer(&result[0])
		copy(unsafe.Slice((*byte)(targetPtr), size), raw)
	} else {
		for i := 0; i < length; i++ {
			// ReadUint16 handles endianness
			result[i] = float16.Float16FromBits(buf.ReadUint16(ctxErr))
		}
	}
	*ptr = result
}

// WriteIntSlice writes []int to buffer using ARRAY protocol
//
//go:inline
func WriteIntSlice(buf *ByteBuffer, value []int) {
	if strconv.IntSize == 64 {
		size := len(value) * 8
		buf.WriteLength(size)
		if len(value) > 0 {
			if isLittleEndian {
				buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
			} else {
				for i := 0; i < len(value); i++ {
					buf.WriteInt64(int64(value[i]))
				}
			}
		}
	} else {
		size := len(value) * 4
		buf.WriteLength(size)
		if len(value) > 0 {
			if isLittleEndian {
				buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
			} else {
				for i := 0; i < len(value); i++ {
					buf.WriteInt32(int32(value[i]))
				}
			}
		}
	}
}

// ReadIntSlice reads []int from buffer using ARRAY protocol
//
//go:inline
func ReadIntSlice(buf *ByteBuffer, err *Error) []int {
	size := buf.ReadLength(err)
	if strconv.IntSize == 64 {
		length := size / 8
		if length == 0 {
			return make([]int, 0)
		}
		result := make([]int, length)
		if isLittleEndian {
			raw := buf.ReadBinary(size, err)
			copy(unsafe.Slice((*byte)(unsafe.Pointer(&result[0])), size), raw)
		} else {
			for i := 0; i < length; i++ {
				result[i] = int(buf.ReadInt64(err))
			}
		}
		return result
	} else {
		length := size / 4
		if length == 0 {
			return make([]int, 0)
		}
		result := make([]int, length)
		if isLittleEndian {
			raw := buf.ReadBinary(size, err)
			copy(unsafe.Slice((*byte)(unsafe.Pointer(&result[0])), size), raw)
		} else {
			for i := 0; i < length; i++ {
				result[i] = int(buf.ReadInt32(err))
			}
		}
		return result
	}
}

// WriteUintSlice writes []uint to buffer using ARRAY protocol
//
//go:inline
func WriteUintSlice(buf *ByteBuffer, value []uint) {
	if strconv.IntSize == 64 {
		size := len(value) * 8
		buf.WriteLength(size)
		if len(value) > 0 {
			if isLittleEndian {
				buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
			} else {
				for i := 0; i < len(value); i++ {
					buf.WriteInt64(int64(value[i]))
				}
			}
		}
	} else {
		size := len(value) * 4
		buf.WriteLength(size)
		if len(value) > 0 {
			if isLittleEndian {
				buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
			} else {
				for i := 0; i < len(value); i++ {
					buf.WriteInt32(int32(value[i]))
				}
			}
		}
	}
}

// ReadUintSlice reads []uint from buffer using ARRAY protocol
//
//go:inline
func ReadUintSlice(buf *ByteBuffer, err *Error) []uint {
	size := buf.ReadLength(err)
	if strconv.IntSize == 64 {
		length := size / 8
		if length == 0 {
			return make([]uint, 0)
		}
		result := make([]uint, length)
		if isLittleEndian {
			raw := buf.ReadBinary(size, err)
			copy(unsafe.Slice((*byte)(unsafe.Pointer(&result[0])), size), raw)
		} else {
			for i := 0; i < length; i++ {
				result[i] = uint(buf.ReadInt64(err))
			}
		}
		return result
	} else {
		length := size / 4
		if length == 0 {
			return make([]uint, 0)
		}
		result := make([]uint, length)
		if isLittleEndian {
			raw := buf.ReadBinary(size, err)
			copy(unsafe.Slice((*byte)(unsafe.Pointer(&result[0])), size), raw)
		} else {
			for i := 0; i < length; i++ {
				result[i] = uint(buf.ReadInt32(err))
			}
		}
		return result
	}
}

// WriteStringSlice writes []string to buffer using LIST protocol.
// When hasGenerics is true (element type known from TypeDef/generics), uses IS_DECL_ELEMENT_TYPE
// and doesn't write element type ID. When false, writes element type ID.
//
//go:inline
func WriteStringSlice(buf *ByteBuffer, value []string, hasGenerics bool) {
	length := len(value)
	buf.WriteVarUint32(uint32(length))
	if length > 0 {
		// Use CollectionDeclSameType when element type is known from TypeDef/generics
		// This matches Java's writeNullabilityHeader behavior for monomorphic types
		if hasGenerics {
			buf.WriteInt8(int8(CollectionDeclSameType))
		} else {
			buf.WriteInt8(int8(CollectionIsSameType))
			buf.WriteUint8(uint8(STRING))
		}
		for i := 0; i < length; i++ {
			writeString(buf, value[i])
		}
	}
}

// ReadStringSlice reads []string from buffer using LIST protocol
//
//go:inline
func ReadStringSlice(buf *ByteBuffer, err *Error) []string {
	length := int(buf.ReadVarUint32(err))
	if length == 0 {
		return make([]string, 0)
	}
	collectFlag := buf.ReadInt8(err)
	if (collectFlag&CollectionIsSameType) != 0 && (collectFlag&CollectionIsDeclElementType) == 0 {
		_ = buf.ReadUint8(err) // Read and discard element type ID
	}
	result := make([]string, length)
	trackRefs := (collectFlag & CollectionTrackingRef) != 0
	hasNull := (collectFlag & CollectionHasNull) != 0
	for i := 0; i < length; i++ {
		if trackRefs || hasNull {
			rf := buf.ReadInt8(err)
			if rf == NullFlag {
				continue
			}
		}
		result[i] = readString(buf, err)
	}
	return result
}

// ============================================================================
// bfloat16SliceSerializer - optimized []bfloat16.BFloat16 serialization
// ============================================================================

type bfloat16SliceSerializer struct{}

func (s bfloat16SliceSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	// Cast to []bfloat16.BFloat16
	v := value.Interface().([]bfloat16.BFloat16)
	buf := ctx.Buffer()
	length := len(v)
	size := length * 2
	buf.WriteLength(size)
	if length > 0 {
		ptr := unsafe.Pointer(&v[0])
		if isLittleEndian {
			buf.WriteBinary(unsafe.Slice((*byte)(ptr), size))
		} else {
			for i := 0; i < length; i++ {
				buf.WriteUint16(v[i].Bits())
			}
		}
	}
}

func (s bfloat16SliceSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeSliceRefAndType(ctx, refMode, writeType, value, BFLOAT16_ARRAY)
	if done || ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s bfloat16SliceSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done, typeId := readSliceRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	if readType && typeId != uint32(BFLOAT16_ARRAY) {
		ctx.SetError(DeserializationErrorf("slice type mismatch: expected BFLOAT16_ARRAY (%d), got %d", BFLOAT16_ARRAY, typeId))
		return
	}
	s.ReadData(ctx, value)
}

func (s bfloat16SliceSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

func (s bfloat16SliceSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	size := buf.ReadLength(ctxErr)
	length := size / 2
	if ctx.HasError() {
		return
	}

	ptr := (*[]bfloat16.BFloat16)(value.Addr().UnsafePointer())
	if length == 0 {
		*ptr = make([]bfloat16.BFloat16, 0)
		return
	}

	result := make([]bfloat16.BFloat16, length)

	if isLittleEndian {
		raw := buf.ReadBinary(size, ctxErr)
		targetPtr := unsafe.Pointer(&result[0])
		copy(unsafe.Slice((*byte)(targetPtr), size), raw)
	} else {
		for i := 0; i < length; i++ {
			result[i] = bfloat16.BFloat16FromBits(buf.ReadUint16(ctxErr))
		}
	}
	*ptr = result
}
