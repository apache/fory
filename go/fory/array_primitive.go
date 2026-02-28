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
	"unsafe"

	"github.com/apache/fory/go/fory/bfloat16"
	"github.com/apache/fory/go/fory/float16"
)

// ============================================================================
// boolArraySerializer - optimized [N]bool serialization
// ============================================================================

type boolArraySerializer struct {
	arrayType reflect.Type
}

func (s boolArraySerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	buf := ctx.Buffer()
	length := value.Len()
	buf.WriteLength(length)
	if length > 0 {
		if value.CanAddr() {
			// Fast path: direct memory copy - bool is 1 byte in Go
			ptr := value.Addr().UnsafePointer()
			buf.WriteBinary(unsafe.Slice((*byte)(ptr), length))
		} else {
			// Slow path for non-addressable arrays
			for i := 0; i < length; i++ {
				if value.Index(i).Bool() {
					buf.WriteByte(1)
				} else {
					buf.WriteByte(0)
				}
			}
		}
	}
}

func (s boolArraySerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	writeArrayRefAndType(ctx, refMode, writeType, value, BOOL_ARRAY)
	if ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s boolArraySerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	err := ctx.Err()
	length := buf.ReadLength(err)
	if ctx.HasError() {
		return
	}
	if length != value.Type().Len() {
		ctx.SetError(DeserializationErrorf("array length %d does not match type %v", length, value.Type()))
		return
	}
	if length > 0 {
		// Direct memory copy - bool is 1 byte in Go
		ptr := value.Addr().UnsafePointer()
		raw := buf.ReadBinary(length, err)
		copy(unsafe.Slice((*byte)(ptr), length), raw)
	}
}

func (s boolArraySerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readArrayRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s boolArraySerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// ============================================================================
// int8ArraySerializer - optimized [N]int8 serialization
// ============================================================================

type int8ArraySerializer struct {
	arrayType reflect.Type
}

func (s int8ArraySerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	buf := ctx.Buffer()
	length := value.Len()
	buf.WriteLength(length)
	if length > 0 {
		if value.CanAddr() {
			// Fast path: direct memory copy - int8 is 1 byte
			ptr := value.Addr().UnsafePointer()
			buf.WriteBinary(unsafe.Slice((*byte)(ptr), length))
		} else {
			// Slow path for non-addressable arrays
			for i := 0; i < length; i++ {
				buf.WriteInt8(int8(value.Index(i).Int()))
			}
		}
	}
}

func (s int8ArraySerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	writeArrayRefAndType(ctx, refMode, writeType, value, INT8_ARRAY)
	if ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s int8ArraySerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	err := ctx.Err()
	length := buf.ReadLength(err)
	if ctx.HasError() {
		return
	}
	if length != value.Type().Len() {
		ctx.SetError(DeserializationErrorf("array length %d does not match type %v", length, value.Type()))
		return
	}
	if length > 0 {
		// Direct memory copy - int8 is 1 byte
		ptr := value.Addr().UnsafePointer()
		raw := buf.ReadBinary(length, err)
		copy(unsafe.Slice((*byte)(ptr), length), raw)
	}
}

func (s int8ArraySerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readArrayRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s int8ArraySerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// ============================================================================
// int16ArraySerializer - optimized [N]int16 serialization
// ============================================================================

type int16ArraySerializer struct {
	arrayType reflect.Type
}

func (s int16ArraySerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	buf := ctx.Buffer()
	length := value.Len()
	size := length * 2
	buf.WriteLength(size)
	if length > 0 {
		if value.CanAddr() && isLittleEndian {
			// Fast path: direct memory copy - little-endian only
			ptr := value.Addr().UnsafePointer()
			buf.WriteBinary(unsafe.Slice((*byte)(ptr), size))
		} else {
			// Slow path for non-addressable arrays or big-endian
			for i := 0; i < length; i++ {
				buf.WriteInt16(int16(value.Index(i).Int()))
			}
		}
	}
}

func (s int16ArraySerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	writeArrayRefAndType(ctx, refMode, writeType, value, INT16_ARRAY)
	if ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s int16ArraySerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	err := ctx.Err()
	size := buf.ReadLength(err)
	length := size / 2
	if ctx.HasError() {
		return
	}
	if length != value.Type().Len() {
		ctx.SetError(DeserializationErrorf("array length %d does not match type %v", length, value.Type()))
		return
	}
	if length > 0 {
		if isLittleEndian {
			ptr := value.Addr().UnsafePointer()
			raw := buf.ReadBinary(size, err)
			copy(unsafe.Slice((*byte)(ptr), size), raw)
		} else {
			for i := 0; i < length; i++ {
				value.Index(i).SetInt(int64(buf.ReadInt16(err)))
			}
		}
	}
}

func (s int16ArraySerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readArrayRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s int16ArraySerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// ============================================================================
// int32ArraySerializer - optimized [N]int32 serialization
// ============================================================================

type int32ArraySerializer struct {
	arrayType reflect.Type
}

func (s int32ArraySerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	buf := ctx.Buffer()
	length := value.Len()
	size := length * 4
	buf.WriteLength(size)
	if length > 0 {
		if value.CanAddr() && isLittleEndian {
			// Fast path: direct memory copy - little-endian only
			ptr := value.Addr().UnsafePointer()
			buf.WriteBinary(unsafe.Slice((*byte)(ptr), size))
		} else {
			// Slow path for non-addressable arrays or big-endian
			for i := 0; i < length; i++ {
				buf.WriteInt32(int32(value.Index(i).Int()))
			}
		}
	}
}

func (s int32ArraySerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	writeArrayRefAndType(ctx, refMode, writeType, value, INT32_ARRAY)
	if ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s int32ArraySerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	err := ctx.Err()
	size := buf.ReadLength(err)
	length := size / 4
	if ctx.HasError() {
		return
	}
	if length != value.Type().Len() {
		ctx.SetError(DeserializationErrorf("array length %d does not match type %v", length, value.Type()))
		return
	}
	if length > 0 {
		if isLittleEndian {
			ptr := value.Addr().UnsafePointer()
			raw := buf.ReadBinary(size, err)
			copy(unsafe.Slice((*byte)(ptr), size), raw)
		} else {
			for i := 0; i < length; i++ {
				value.Index(i).SetInt(int64(buf.ReadInt32(err)))
			}
		}
	}
}

func (s int32ArraySerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readArrayRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s int32ArraySerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// ============================================================================
// int64ArraySerializer - optimized [N]int64 serialization
// ============================================================================

type int64ArraySerializer struct {
	arrayType reflect.Type
}

func (s int64ArraySerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	buf := ctx.Buffer()
	length := value.Len()
	size := length * 8
	buf.WriteLength(size)
	if length > 0 {
		if value.CanAddr() && isLittleEndian {
			// Fast path: direct memory copy - little-endian only
			ptr := value.Addr().UnsafePointer()
			buf.WriteBinary(unsafe.Slice((*byte)(ptr), size))
		} else {
			// Slow path for non-addressable arrays or big-endian
			for i := 0; i < length; i++ {
				buf.WriteInt64(value.Index(i).Int())
			}
		}
	}
}

func (s int64ArraySerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	writeArrayRefAndType(ctx, refMode, writeType, value, INT64_ARRAY)
	if ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s int64ArraySerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	err := ctx.Err()
	size := buf.ReadLength(err)
	length := size / 8
	if ctx.HasError() {
		return
	}
	if length != value.Type().Len() {
		ctx.SetError(DeserializationErrorf("array length %d does not match type %v", length, value.Type()))
		return
	}
	if length > 0 {
		if isLittleEndian {
			ptr := value.Addr().UnsafePointer()
			raw := buf.ReadBinary(size, err)
			copy(unsafe.Slice((*byte)(ptr), size), raw)
		} else {
			for i := 0; i < length; i++ {
				value.Index(i).SetInt(buf.ReadInt64(err))
			}
		}
	}
}

func (s int64ArraySerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readArrayRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s int64ArraySerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// ============================================================================
// float32ArraySerializer - optimized [N]float32 serialization
// ============================================================================

type float32ArraySerializer struct {
	arrayType reflect.Type
}

func (s float32ArraySerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	buf := ctx.Buffer()
	length := value.Len()
	size := length * 4
	buf.WriteLength(size)
	if length > 0 {
		if value.CanAddr() && isLittleEndian {
			// Fast path: direct memory copy - little-endian only
			ptr := value.Addr().UnsafePointer()
			buf.WriteBinary(unsafe.Slice((*byte)(ptr), size))
		} else {
			// Slow path for non-addressable arrays or big-endian
			for i := 0; i < length; i++ {
				buf.WriteFloat32(float32(value.Index(i).Float()))
			}
		}
	}
}

func (s float32ArraySerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	writeArrayRefAndType(ctx, refMode, writeType, value, FLOAT32_ARRAY)
	if ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s float32ArraySerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	err := ctx.Err()
	size := buf.ReadLength(err)
	length := size / 4
	if ctx.HasError() {
		return
	}
	if length != value.Type().Len() {
		ctx.SetError(DeserializationErrorf("array length %d does not match type %v", length, value.Type()))
		return
	}
	if length > 0 {
		if isLittleEndian {
			ptr := value.Addr().UnsafePointer()
			raw := buf.ReadBinary(size, err)
			copy(unsafe.Slice((*byte)(ptr), size), raw)
		} else {
			for i := 0; i < length; i++ {
				value.Index(i).SetFloat(float64(buf.ReadFloat32(err)))
			}
		}
	}
}

func (s float32ArraySerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readArrayRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s float32ArraySerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// ============================================================================
// float64ArraySerializer - optimized [N]float64 serialization
// ============================================================================

type float64ArraySerializer struct {
	arrayType reflect.Type
}

func (s float64ArraySerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	buf := ctx.Buffer()
	length := value.Len()
	size := length * 8
	buf.WriteLength(size)
	if length > 0 {
		if value.CanAddr() && isLittleEndian {
			// Fast path: direct memory copy - little-endian only
			ptr := value.Addr().UnsafePointer()
			buf.WriteBinary(unsafe.Slice((*byte)(ptr), size))
		} else {
			// Slow path for non-addressable arrays or big-endian
			for i := 0; i < length; i++ {
				buf.WriteFloat64(value.Index(i).Float())
			}
		}
	}
}

func (s float64ArraySerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	writeArrayRefAndType(ctx, refMode, writeType, value, FLOAT64_ARRAY)
	if ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s float64ArraySerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	err := ctx.Err()
	size := buf.ReadLength(err)
	length := size / 8
	if ctx.HasError() {
		return
	}
	if length != value.Type().Len() {
		ctx.SetError(DeserializationErrorf("array length %d does not match type %v", length, value.Type()))
		return
	}
	if length > 0 {
		if isLittleEndian {
			ptr := value.Addr().UnsafePointer()
			raw := buf.ReadBinary(size, err)
			copy(unsafe.Slice((*byte)(ptr), size), raw)
		} else {
			for i := 0; i < length; i++ {
				value.Index(i).SetFloat(buf.ReadFloat64(err))
			}
		}
	}
}

func (s float64ArraySerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readArrayRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s float64ArraySerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// ============================================================================
// uint8ArraySerializer - optimized [N]uint8 (byte) serialization
// ============================================================================

type uint8ArraySerializer struct {
	arrayType reflect.Type
}

func (s uint8ArraySerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	buf := ctx.Buffer()
	length := value.Len()
	buf.WriteLength(length)
	if length > 0 {
		if value.CanAddr() {
			// Fast path: direct memory copy - uint8 is 1 byte
			ptr := value.Addr().UnsafePointer()
			buf.WriteBinary(unsafe.Slice((*byte)(ptr), length))
		} else {
			// Slow path for non-addressable arrays
			for i := 0; i < length; i++ {
				buf.WriteByte(byte(value.Index(i).Uint()))
			}
		}
	}
}

func (s uint8ArraySerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	writeArrayRefAndType(ctx, refMode, writeType, value, BINARY)
	if ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s uint8ArraySerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	err := ctx.Err()
	length := buf.ReadLength(err)
	if ctx.HasError() {
		return
	}
	if length != value.Type().Len() {
		ctx.SetError(DeserializationErrorf("array length %d does not match type %v", length, value.Type()))
		return
	}
	if length > 0 {
		// Direct memory copy - uint8 is 1 byte
		ptr := value.Addr().UnsafePointer()
		raw := buf.ReadBinary(length, err)
		copy(unsafe.Slice((*byte)(ptr), length), raw)
	}
}

func (s uint8ArraySerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readArrayRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s uint8ArraySerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// ============================================================================
// uint16ArraySerializer - optimized [N]uint16 serialization
// ============================================================================

type uint16ArraySerializer struct {
	arrayType reflect.Type
}

func (s uint16ArraySerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	buf := ctx.Buffer()
	length := value.Len()
	size := length * 2
	buf.WriteLength(size)
	if length > 0 {
		if value.CanAddr() && isLittleEndian {
			// Fast path: direct memory copy - little-endian only
			ptr := value.Addr().UnsafePointer()
			buf.WriteBinary(unsafe.Slice((*byte)(ptr), size))
		} else {
			// Slow path for non-addressable arrays or big-endian
			for i := 0; i < length; i++ {
				// Cast to int16 to match INT16_ARRAY wire format (bits are preserved)
				buf.WriteInt16(int16(value.Index(i).Uint()))
			}
		}
	}
}

func (s uint16ArraySerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	writeArrayRefAndType(ctx, refMode, writeType, value, UINT16_ARRAY)
	if ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s uint16ArraySerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	err := ctx.Err()
	size := buf.ReadLength(err)
	length := size / 2
	if ctx.HasError() {
		return
	}
	if length != value.Type().Len() {
		ctx.SetError(DeserializationErrorf("array length %d does not match type %v", length, value.Type()))
		return
	}
	if length > 0 {
		if isLittleEndian {
			ptr := value.Addr().UnsafePointer()
			raw := buf.ReadBinary(size, err)
			copy(unsafe.Slice((*byte)(ptr), size), raw)
		} else {
			for i := 0; i < length; i++ {
				value.Index(i).SetUint(uint64(uint16(buf.ReadInt16(err))))
			}
		}
	}
}

func (s uint16ArraySerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readArrayRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s uint16ArraySerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// ============================================================================
// uint32ArraySerializer - optimized [N]uint32 serialization
// ============================================================================

type uint32ArraySerializer struct {
	arrayType reflect.Type
}

func (s uint32ArraySerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	buf := ctx.Buffer()
	length := value.Len()
	size := length * 4
	buf.WriteLength(size)
	if length > 0 {
		if value.CanAddr() && isLittleEndian {
			ptr := value.Addr().UnsafePointer()
			buf.WriteBinary(unsafe.Slice((*byte)(ptr), size))
		} else {
			for i := 0; i < length; i++ {
				// Cast to int32 (bits preserved)
				buf.WriteInt32(int32(value.Index(i).Uint()))
			}
		}
	}
}

func (s uint32ArraySerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	writeArrayRefAndType(ctx, refMode, writeType, value, UINT32_ARRAY)
	if ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s uint32ArraySerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	err := ctx.Err()
	size := buf.ReadLength(err)
	length := size / 4
	if ctx.HasError() {
		return
	}
	if length != value.Type().Len() {
		ctx.SetError(DeserializationErrorf("array length %d does not match type %v", length, value.Type()))
		return
	}
	if length > 0 {
		if isLittleEndian {
			ptr := value.Addr().UnsafePointer()
			raw := buf.ReadBinary(size, err)
			copy(unsafe.Slice((*byte)(ptr), size), raw)
		} else {
			for i := 0; i < length; i++ {
				value.Index(i).SetUint(uint64(uint32(buf.ReadInt32(err))))
			}
		}
	}
}

func (s uint32ArraySerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readArrayRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s uint32ArraySerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// ============================================================================
// uint64ArraySerializer - optimized [N]uint64 serialization
// ============================================================================

type uint64ArraySerializer struct {
	arrayType reflect.Type
}

func (s uint64ArraySerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	buf := ctx.Buffer()
	length := value.Len()
	size := length * 8
	buf.WriteLength(size)
	if length > 0 {
		if value.CanAddr() && isLittleEndian {
			ptr := value.Addr().UnsafePointer()
			buf.WriteBinary(unsafe.Slice((*byte)(ptr), size))
		} else {
			for i := 0; i < length; i++ {
				buf.WriteInt64(int64(value.Index(i).Uint()))
			}
		}
	}
}

func (s uint64ArraySerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	writeArrayRefAndType(ctx, refMode, writeType, value, UINT64_ARRAY)
	if ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s uint64ArraySerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	err := ctx.Err()
	size := buf.ReadLength(err)
	length := size / 8
	if ctx.HasError() {
		return
	}
	if length != value.Type().Len() {
		ctx.SetError(DeserializationErrorf("array length %d does not match type %v", length, value.Type()))
		return
	}
	if length > 0 {
		if isLittleEndian {
			ptr := value.Addr().UnsafePointer()
			raw := buf.ReadBinary(size, err)
			copy(unsafe.Slice((*byte)(ptr), size), raw)
		} else {
			for i := 0; i < length; i++ {
				value.Index(i).SetUint(uint64(buf.ReadInt64(err)))
			}
		}
	}
}

func (s uint64ArraySerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readArrayRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s uint64ArraySerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// ============================================================================
// float16ArraySerializer - optimized [N]float16.Float16 serialization
// ============================================================================

type float16ArraySerializer struct {
	arrayType reflect.Type
}

func (s float16ArraySerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	buf := ctx.Buffer()
	length := value.Len()
	size := length * 2
	buf.WriteLength(size)
	if length > 0 {
		if value.CanAddr() && isLittleEndian {
			ptr := value.Addr().UnsafePointer()
			buf.WriteBinary(unsafe.Slice((*byte)(ptr), size))
		} else {
			for i := 0; i < length; i++ {
				// We can't easily cast the whole array if not addressable/little-endian
				// So we iterate.
				// value.Index(i) is Float16, we cast to uint16
				val := value.Index(i).Interface().(float16.Float16)
				buf.WriteUint16(val.Bits())
			}
		}
	}
}

func (s float16ArraySerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	writeArrayRefAndType(ctx, refMode, writeType, value, FLOAT16_ARRAY)
	if ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s float16ArraySerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	size := buf.ReadLength(ctxErr)
	length := size / 2
	if ctx.HasError() {
		return
	}
	if length != value.Type().Len() {
		ctx.SetError(DeserializationErrorf("array length %d does not match type %v", length, value.Type()))
		return
	}

	if length > 0 {
		if isLittleEndian {
			ptr := value.Addr().UnsafePointer()
			raw := buf.ReadBinary(size, ctxErr)
			copy(unsafe.Slice((*byte)(ptr), size), raw)
		} else {
			for i := 0; i < length; i++ {
				value.Index(i).Set(reflect.ValueOf(float16.Float16FromBits(buf.ReadUint16(ctxErr))))
			}
		}
	}
}

func (s float16ArraySerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readArrayRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s float16ArraySerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// ============================================================================
// bfloat16ArraySerializer - optimized [N]bfloat16.BFloat16 serialization
// ============================================================================

type bfloat16ArraySerializer struct {
	arrayType reflect.Type
}

func (s bfloat16ArraySerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	buf := ctx.Buffer()
	length := value.Len()
	size := length * 2
	buf.WriteLength(size)
	if length > 0 {
		if value.CanAddr() && isLittleEndian {
			ptr := value.Addr().UnsafePointer()
			buf.WriteBinary(unsafe.Slice((*byte)(ptr), size))
		} else {
			for i := 0; i < length; i++ {
				// We can't easily cast the whole array if not addressable/little-endian
				// So we iterate.
				val := value.Index(i).Interface().(bfloat16.BFloat16)
				buf.WriteUint16(val.Bits())
			}
		}
	}
}

func (s bfloat16ArraySerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	writeArrayRefAndType(ctx, refMode, writeType, value, BFLOAT16_ARRAY)
	if ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s bfloat16ArraySerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	size := buf.ReadLength(ctxErr)
	length := size / 2
	if ctx.HasError() {
		return
	}
	if length != value.Type().Len() {
		ctx.SetError(DeserializationErrorf("array length %d does not match type %v", length, value.Type()))
		return
	}

	if length > 0 {
		if isLittleEndian {
			ptr := value.Addr().UnsafePointer()
			raw := buf.ReadBinary(size, ctxErr)
			copy(unsafe.Slice((*byte)(ptr), size), raw)
		} else {
			for i := 0; i < length; i++ {
				value.Index(i).Set(reflect.ValueOf(bfloat16.BFloat16FromBits(buf.ReadUint16(ctxErr))))
			}
		}
	}
}

func (s bfloat16ArraySerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readArrayRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s bfloat16ArraySerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}
