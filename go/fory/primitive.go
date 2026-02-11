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

// ============================================================================
// Primitive Serializers - implement unified Serializer interface
// ============================================================================

// boolSerializer handles bool type
type boolSerializer struct{}

var globalBoolSerializer = boolSerializer{}

func (s boolSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	ctx.buffer.WriteBool(value.Bool())
}

func (s boolSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics // not used for primitive types
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(BOOL))
	}
	s.WriteData(ctx, value)
}

func (s boolSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	value.SetBool(ctx.buffer.ReadBool(err))
}

func (s boolSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics // not used for primitive types
	err := ctx.Err()
	if refMode != RefModeNone {
		if ctx.buffer.ReadInt8(err) == NullFlag {
			return
		}
	}
	if readType {
		_ = ctx.buffer.ReadUint8(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s boolSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	// typeInfo is already read, don't read it again
	s.Read(ctx, refMode, false, false, value)
}

// int8Serializer handles int8 type
type int8Serializer struct{}

var globalInt8Serializer = int8Serializer{}

func (s int8Serializer) WriteData(ctx *WriteContext, value reflect.Value) {
	ctx.buffer.WriteInt8(int8(value.Int()))
}

func (s int8Serializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(INT8))
	}
	s.WriteData(ctx, value)
}

func (s int8Serializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	value.SetInt(int64(ctx.buffer.ReadInt8(err)))
}

func (s int8Serializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics
	err := ctx.Err()
	if refMode != RefModeNone {
		if ctx.buffer.ReadInt8(err) == NullFlag {
			return
		}
	}
	if readType {
		_ = ctx.buffer.ReadUint8(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s int8Serializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// byteSerializer handles byte/uint8 type
type byteSerializer struct{}

var globalByteSerializer = byteSerializer{}

func (s byteSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	ctx.buffer.WriteUint8(uint8(value.Uint()))
}

func (s byteSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(UINT8))
	}
	s.WriteData(ctx, value)
}

func (s byteSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	value.SetUint(uint64(ctx.buffer.ReadUint8(err)))
}

func (s byteSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics
	err := ctx.Err()
	if refMode != RefModeNone {
		if ctx.buffer.ReadInt8(err) == NullFlag {
			return
		}
	}
	if readType {
		_ = ctx.buffer.ReadUint8(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s byteSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// uint16Serializer handles uint16 type
type uint16Serializer struct{}

var globalUint16Serializer = uint16Serializer{}

func (s uint16Serializer) WriteData(ctx *WriteContext, value reflect.Value) {
	ctx.buffer.WriteUint16(uint16(value.Uint()))
}

func (s uint16Serializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(UINT16))
	}
	s.WriteData(ctx, value)
}

func (s uint16Serializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	value.SetUint(uint64(ctx.buffer.ReadUint16(err)))
}

func (s uint16Serializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics
	err := ctx.Err()
	if refMode != RefModeNone {
		if ctx.buffer.ReadInt8(err) == NullFlag {
			return
		}
	}
	if readType {
		_ = ctx.buffer.ReadUint8(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s uint16Serializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// uint32Serializer handles uint32 type with variable-length encoding (VAR_UINT32)
type uint32Serializer struct{}

var globalUint32Serializer = uint32Serializer{}

func (s uint32Serializer) WriteData(ctx *WriteContext, value reflect.Value) {
	ctx.buffer.WriteVarUint32(uint32(value.Uint()))
}

func (s uint32Serializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(VAR_UINT32))
	}
	s.WriteData(ctx, value)
}

func (s uint32Serializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	value.SetUint(uint64(ctx.buffer.ReadVarUint32(err)))
}

func (s uint32Serializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics
	err := ctx.Err()
	if refMode != RefModeNone {
		if ctx.buffer.ReadInt8(err) == NullFlag {
			return
		}
	}
	if readType {
		_ = ctx.buffer.ReadUint8(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s uint32Serializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// uint64Serializer handles uint64 type with variable-length encoding (VAR_UINT64)
type uint64Serializer struct{}

var globalUint64Serializer = uint64Serializer{}

func (s uint64Serializer) WriteData(ctx *WriteContext, value reflect.Value) {
	ctx.buffer.WriteVarUint64(value.Uint())
}

func (s uint64Serializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(VAR_UINT64))
	}
	s.WriteData(ctx, value)
}

func (s uint64Serializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	value.SetUint(ctx.buffer.ReadVarUint64(err))
}

func (s uint64Serializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics
	err := ctx.Err()
	if refMode != RefModeNone {
		if ctx.buffer.ReadInt8(err) == NullFlag {
			return
		}
	}
	if readType {
		_ = ctx.buffer.ReadUint8(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s uint64Serializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// uintSerializer handles uint type with variable-length encoding (VAR_UINT64)
type uintSerializer struct{}

var globalUintSerializer = uintSerializer{}

func (s uintSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	ctx.buffer.WriteVarUint64(value.Uint())
}

func (s uintSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(VAR_UINT64))
	}
	s.WriteData(ctx, value)
}

func (s uintSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	value.SetUint(ctx.buffer.ReadVarUint64(err))
}

func (s uintSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	err := ctx.Err()
	if refMode != RefModeNone {
		if ctx.buffer.ReadInt8(err) == NullFlag {
			return
		}
	}
	if readType {
		_ = ctx.buffer.ReadUint8(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s uintSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// int16Serializer handles int16 type
type int16Serializer struct{}

var globalInt16Serializer = int16Serializer{}

func (s int16Serializer) WriteData(ctx *WriteContext, value reflect.Value) {
	ctx.buffer.WriteInt16(int16(value.Int()))
}

func (s int16Serializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(INT16))
	}
	s.WriteData(ctx, value)
}

func (s int16Serializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	value.SetInt(int64(ctx.buffer.ReadInt16(err)))
}

func (s int16Serializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	err := ctx.Err()
	if refMode != RefModeNone {
		if ctx.buffer.ReadInt8(err) == NullFlag {
			return
		}
	}
	if readType {
		_ = ctx.buffer.ReadUint8(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s int16Serializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// int32Serializer handles int32 type
type int32Serializer struct{}

var globalInt32Serializer = int32Serializer{}

func (s int32Serializer) WriteData(ctx *WriteContext, value reflect.Value) {
	ctx.buffer.WriteVarint32(int32(value.Int()))
}

func (s int32Serializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(INT32))
	}
	s.WriteData(ctx, value)
}

func (s int32Serializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	value.SetInt(int64(ctx.buffer.ReadVarint32(err)))
}

func (s int32Serializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	err := ctx.Err()
	if refMode != RefModeNone {
		if ctx.buffer.ReadInt8(err) == NullFlag {
			return
		}
	}
	if readType {
		_ = ctx.buffer.ReadUint8(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s int32Serializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// int64Serializer handles int64 type
type int64Serializer struct{}

var globalInt64Serializer = int64Serializer{}

func (s int64Serializer) WriteData(ctx *WriteContext, value reflect.Value) {
	ctx.buffer.WriteVarint64(value.Int())
}

func (s int64Serializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(INT64))
	}
	s.WriteData(ctx, value)
}

func (s int64Serializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	value.SetInt(ctx.buffer.ReadVarint64(err))
}

func (s int64Serializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	err := ctx.Err()
	if refMode != RefModeNone {
		if ctx.buffer.ReadInt8(err) == NullFlag {
			return
		}
	}
	if readType {
		_ = ctx.buffer.ReadUint8(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s int64Serializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// intSerializer handles int type
type intSerializer struct{}

func (s intSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	ctx.buffer.WriteVarint64(value.Int())
}

func (s intSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(INT64))
	}
	s.WriteData(ctx, value)
}

func (s intSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	value.SetInt(ctx.buffer.ReadVarint64(err))
}

func (s intSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	err := ctx.Err()
	if refMode != RefModeNone {
		if ctx.buffer.ReadInt8(err) == NullFlag {
			return
		}
	}
	if readType {
		_ = ctx.buffer.ReadUint8(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s intSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// float32Serializer handles float32 type
type float32Serializer struct{}

var globalFloat32Serializer = float32Serializer{}

func (s float32Serializer) WriteData(ctx *WriteContext, value reflect.Value) {
	ctx.buffer.WriteFloat32(float32(value.Float()))
}

func (s float32Serializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(FLOAT32))
	}
	s.WriteData(ctx, value)
}

func (s float32Serializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	value.SetFloat(float64(ctx.buffer.ReadFloat32(err)))
}

func (s float32Serializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	err := ctx.Err()
	if refMode != RefModeNone {
		if ctx.buffer.ReadInt8(err) == NullFlag {
			return
		}
	}
	if readType {
		_ = ctx.buffer.ReadUint8(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s float32Serializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// float64Serializer handles float64 type
type float64Serializer struct{}

var globalFloat64Serializer = float64Serializer{}

func (s float64Serializer) WriteData(ctx *WriteContext, value reflect.Value) {
	ctx.buffer.WriteFloat64(value.Float())
}

func (s float64Serializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(FLOAT64))
	}
	s.WriteData(ctx, value)
}

func (s float64Serializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	value.SetFloat(ctx.buffer.ReadFloat64(err))
}

func (s float64Serializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	err := ctx.Err()
	if refMode != RefModeNone {
		if ctx.buffer.ReadInt8(err) == NullFlag {
			return
		}
	}
	if readType {
		_ = ctx.buffer.ReadUint8(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s float64Serializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// ============================================================================
// float16Serializer - optimized float16 serialization
// ============================================================================

// float16Serializer handles float16 type
type float16Serializer struct{}

var globalFloat16Serializer = float16Serializer{}

func (s float16Serializer) WriteData(ctx *WriteContext, value reflect.Value) {
	// Value is effectively uint16 (alias)
	// We can use WriteUint16, but we check if it is indeed float16 compatible
	// The value comes from reflection, likely an interface or concrete type
	// Since Float16 is uint16, value.Uint() works.
	ctx.buffer.WriteUint16(uint16(value.Uint()))
}

func (s float16Serializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(FLOAT16))
	}
	s.WriteData(ctx, value)
}

func (s float16Serializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	// Read uint16 bits
	bits := ctx.buffer.ReadUint16(err)
	if ctx.HasError() {
		return
	}
	// Set the value. Since Float16 is uint16, SetUint works.
	value.SetUint(uint64(bits))
}

func (s float16Serializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	err := ctx.Err()
	if refMode != RefModeNone {
		if ctx.buffer.ReadInt8(err) == NullFlag {
			return
		}
	}
	if readType {
		_ = ctx.buffer.ReadUint8(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s float16Serializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// ============================================================================
// bfloat16Serializer - optimized bfloat16 serialization
// ============================================================================

// bfloat16Serializer handles bfloat16 type
type bfloat16Serializer struct{}

var globalBFloat16Serializer = bfloat16Serializer{}

func (s bfloat16Serializer) WriteData(ctx *WriteContext, value reflect.Value) {
	// Value is effectively uint16 (alias)
	ctx.buffer.WriteUint16(uint16(value.Uint()))
}

func (s bfloat16Serializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(BFLOAT16))
	}
	s.WriteData(ctx, value)
}

func (s bfloat16Serializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	bits := ctx.buffer.ReadUint16(err)
	if ctx.HasError() {
		return
	}
	value.SetUint(uint64(bits))
}

func (s bfloat16Serializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	err := ctx.Err()
	if refMode != RefModeNone {
		if ctx.buffer.ReadInt8(err) == NullFlag {
			return
		}
	}
	if readType {
		_ = ctx.buffer.ReadUint8(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s bfloat16Serializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}
