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
)

type primitiveListSerializer struct {
	type_      reflect.Type
	elemTypeID TypeId
}

func newPrimitiveListSerializer(type_ reflect.Type, elemTypeID TypeId) (Serializer, bool) {
	if type_.Kind() != reflect.Slice {
		return nil, false
	}
	elemType := type_.Elem()
	switch elemType.Kind() {
	case reflect.Bool:
		return primitiveListSerializer{type_: type_, elemTypeID: elemTypeID}, elemTypeID == BOOL
	case reflect.Int8:
		return primitiveListSerializer{type_: type_, elemTypeID: elemTypeID}, elemTypeID == INT8
	case reflect.Uint8:
		return primitiveListSerializer{type_: type_, elemTypeID: elemTypeID}, elemTypeID == UINT8
	case reflect.Int16:
		return primitiveListSerializer{type_: type_, elemTypeID: elemTypeID}, elemTypeID == INT16
	case reflect.Uint16:
		if elemType == float16Type {
			return primitiveListSerializer{type_: type_, elemTypeID: elemTypeID}, elemTypeID == FLOAT16
		}
		if elemType == bfloat16Type {
			return primitiveListSerializer{type_: type_, elemTypeID: elemTypeID}, elemTypeID == BFLOAT16
		}
		return primitiveListSerializer{type_: type_, elemTypeID: elemTypeID}, elemTypeID == UINT16
	case reflect.Int32:
		return primitiveListSerializer{type_: type_, elemTypeID: elemTypeID}, elemTypeID == INT32 || elemTypeID == VARINT32
	case reflect.Uint32:
		return primitiveListSerializer{type_: type_, elemTypeID: elemTypeID}, elemTypeID == UINT32 || elemTypeID == VAR_UINT32
	case reflect.Int64:
		return primitiveListSerializer{type_: type_, elemTypeID: elemTypeID}, elemTypeID == INT64 || elemTypeID == VARINT64 || elemTypeID == TAGGED_INT64
	case reflect.Uint64:
		return primitiveListSerializer{type_: type_, elemTypeID: elemTypeID}, elemTypeID == UINT64 || elemTypeID == VAR_UINT64 || elemTypeID == TAGGED_UINT64
	case reflect.Int:
		if reflect.TypeOf(int(0)).Size() == 8 {
			return primitiveListSerializer{type_: type_, elemTypeID: elemTypeID}, elemTypeID == INT64 || elemTypeID == VARINT64 || elemTypeID == TAGGED_INT64
		}
		return primitiveListSerializer{type_: type_, elemTypeID: elemTypeID}, elemTypeID == INT32 || elemTypeID == VARINT32
	case reflect.Uint:
		if reflect.TypeOf(uint(0)).Size() == 8 {
			return primitiveListSerializer{type_: type_, elemTypeID: elemTypeID}, elemTypeID == UINT64 || elemTypeID == VAR_UINT64 || elemTypeID == TAGGED_UINT64
		}
		return primitiveListSerializer{type_: type_, elemTypeID: elemTypeID}, elemTypeID == UINT32 || elemTypeID == VAR_UINT32
	case reflect.Float32:
		return primitiveListSerializer{type_: type_, elemTypeID: elemTypeID}, elemTypeID == FLOAT32
	case reflect.Float64:
		return primitiveListSerializer{type_: type_, elemTypeID: elemTypeID}, elemTypeID == FLOAT64
	default:
		return nil, false
	}
}

func (s primitiveListSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	s.writeDataWithGenerics(ctx, value, false)
}

func (s primitiveListSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeSliceRefAndType(ctx, refMode, writeType, value, LIST)
	if done || ctx.HasError() {
		return
	}
	s.writeDataWithGenerics(ctx, value, hasGenerics)
}

func (s primitiveListSerializer) writeDataWithGenerics(ctx *WriteContext, value reflect.Value, hasGenerics bool) {
	length := value.Len()
	buf := ctx.Buffer()
	buf.WriteVarUint32(uint32(length))
	if length == 0 {
		return
	}
	if hasGenerics {
		buf.WriteInt8(CollectionDeclSameType)
	} else {
		buf.WriteInt8(CollectionIsSameType)
		ctx.TypeResolver().WriteTypeInfo(buf, &TypeInfo{Type: s.type_.Elem(), TypeID: uint32(s.elemTypeID)}, ctx.Err())
	}
	s.writeValues(buf, value)
}

func (s primitiveListSerializer) writeValues(buf *ByteBuffer, value reflect.Value) {
	switch s.type_.Elem().Kind() {
	case reflect.Bool:
		writeBoolListPayload(buf, primitiveListSliceView[bool](value))
	case reflect.Int8:
		writeInt8ListPayload(buf, primitiveListSliceView[int8](value))
	case reflect.Uint8:
		writeUint8ListPayload(buf, primitiveListSliceView[byte](value))
	case reflect.Int16:
		writeInt16ListPayload(buf, primitiveListSliceView[int16](value))
	case reflect.Uint16:
		writeUint16ListPayload(buf, primitiveListSliceView[uint16](value))
	case reflect.Int32:
		writeInt32ListPayload(buf, primitiveListSliceView[int32](value), s.elemTypeID)
	case reflect.Uint32:
		writeUint32ListPayload(buf, primitiveListSliceView[uint32](value), s.elemTypeID)
	case reflect.Int64:
		writeInt64ListPayload(buf, primitiveListSliceView[int64](value), s.elemTypeID)
	case reflect.Uint64:
		writeUint64ListPayload(buf, primitiveListSliceView[uint64](value), s.elemTypeID)
	case reflect.Int:
		writeIntListPayload(buf, primitiveListSliceView[int](value), s.elemTypeID)
	case reflect.Uint:
		writeUintListPayload(buf, primitiveListSliceView[uint](value), s.elemTypeID)
	case reflect.Float32:
		writeFloat32ListPayload(buf, primitiveListSliceView[float32](value))
	case reflect.Float64:
		writeFloat64ListPayload(buf, primitiveListSliceView[float64](value))
	}
}

func primitiveListSliceView[T any](value reflect.Value) []T {
	return unsafe.Slice((*T)(value.UnsafePointer()), value.Len())
}

func (s primitiveListSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done, typeID := readSliceRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	if readType && typeID != uint32(LIST) {
		ctx.SetError(DeserializationErrorf("slice type mismatch: expected LIST (%d), got %d", LIST, typeID))
		return
	}
	s.ReadData(ctx, value)
}

func (s primitiveListSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

func (s primitiveListSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	err := ctx.Err()
	length := ctx.ReadCollectionLength()
	if length == 0 {
		value.Set(reflect.MakeSlice(value.Type(), 0, 0))
		return
	}
	collectFlag := buf.ReadInt8(err)
	if (collectFlag & CollectionIsSameType) != 0 {
		if (collectFlag & CollectionIsDeclElementType) == 0 {
			ctx.TypeResolver().ReadTypeInfo(buf, err)
		}
	}
	if (collectFlag & CollectionTrackingRef) != 0 {
		ctx.SetError(DeserializationErrorf("primitive list does not support reference-tracked elements"))
		return
	}
	hasNull := (collectFlag & CollectionHasNull) != 0
	s.readValues(buf, err, value, length, hasNull)
}

func (s primitiveListSerializer) readValues(buf *ByteBuffer, err *Error, value reflect.Value, length int, hasNull bool) {
	switch s.type_.Elem().Kind() {
	case reflect.Bool:
		*(*[]bool)(value.Addr().UnsafePointer()) = readBoolListPayload(buf, err, length, hasNull)
	case reflect.Int8:
		*(*[]int8)(value.Addr().UnsafePointer()) = readInt8ListPayload(buf, err, length, hasNull)
	case reflect.Uint8:
		*(*[]byte)(value.Addr().UnsafePointer()) = readUint8ListPayload(buf, err, length, hasNull)
	case reflect.Int16:
		*(*[]int16)(value.Addr().UnsafePointer()) = readInt16ListPayload(buf, err, length, hasNull)
	case reflect.Uint16:
		*(*[]uint16)(value.Addr().UnsafePointer()) = readUint16ListPayload(buf, err, length, hasNull)
	case reflect.Int32:
		*(*[]int32)(value.Addr().UnsafePointer()) = readInt32ListPayload(buf, err, length, hasNull, s.elemTypeID)
	case reflect.Uint32:
		*(*[]uint32)(value.Addr().UnsafePointer()) = readUint32ListPayload(buf, err, length, hasNull, s.elemTypeID)
	case reflect.Int64:
		*(*[]int64)(value.Addr().UnsafePointer()) = readInt64ListPayload(buf, err, length, hasNull, s.elemTypeID)
	case reflect.Uint64:
		*(*[]uint64)(value.Addr().UnsafePointer()) = readUint64ListPayload(buf, err, length, hasNull, s.elemTypeID)
	case reflect.Int:
		*(*[]int)(value.Addr().UnsafePointer()) = readIntListPayload(buf, err, length, hasNull, s.elemTypeID)
	case reflect.Uint:
		*(*[]uint)(value.Addr().UnsafePointer()) = readUintListPayload(buf, err, length, hasNull, s.elemTypeID)
	case reflect.Float32:
		*(*[]float32)(value.Addr().UnsafePointer()) = readFloat32ListPayload(buf, err, length, hasNull)
	case reflect.Float64:
		*(*[]float64)(value.Addr().UnsafePointer()) = readFloat64ListPayload(buf, err, length, hasNull)
	}
}

func writeBoolListPayload(buf *ByteBuffer, value []bool) {
	if len(value) > 0 {
		buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), len(value)))
	}
}

func readBoolListPayload(buf *ByteBuffer, err *Error, length int, hasNull bool) []bool {
	result := make([]bool, length)
	if !hasNull {
		raw := buf.ReadBinary(length, err)
		copy(unsafe.Slice((*byte)(unsafe.Pointer(&result[0])), length), raw)
		return result
	}
	for i := 0; i < length; i++ {
		if buf.ReadInt8(err) != NullFlag {
			result[i] = buf.ReadBool(err)
		}
	}
	return result
}

func writeInt8ListPayload(buf *ByteBuffer, value []int8) {
	if len(value) > 0 {
		buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), len(value)))
	}
}

func readInt8ListPayload(buf *ByteBuffer, err *Error, length int, hasNull bool) []int8 {
	result := make([]int8, length)
	if !hasNull {
		raw := buf.ReadBinary(length, err)
		copy(unsafe.Slice((*byte)(unsafe.Pointer(&result[0])), length), raw)
		return result
	}
	for i := 0; i < length; i++ {
		if buf.ReadInt8(err) != NullFlag {
			result[i] = buf.ReadInt8(err)
		}
	}
	return result
}

func writeUint8ListPayload(buf *ByteBuffer, value []byte) {
	if len(value) > 0 {
		buf.WriteBinary(value)
	}
}

func readUint8ListPayload(buf *ByteBuffer, err *Error, length int, hasNull bool) []byte {
	result := make([]byte, length)
	if !hasNull {
		raw := buf.ReadBinary(length, err)
		copy(result, raw)
		return result
	}
	for i := 0; i < length; i++ {
		if buf.ReadInt8(err) != NullFlag {
			result[i] = buf.ReadUint8(err)
		}
	}
	return result
}

func writeInt16ListPayload(buf *ByteBuffer, value []int16) {
	size := len(value) * 2
	if len(value) > 0 {
		if isLittleEndian {
			buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
		} else {
			for _, v := range value {
				buf.WriteInt16(v)
			}
		}
	}
}

func readInt16ListPayload(buf *ByteBuffer, err *Error, length int, hasNull bool) []int16 {
	result := make([]int16, length)
	if !hasNull {
		size := length * 2
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
	for i := 0; i < length; i++ {
		if buf.ReadInt8(err) != NullFlag {
			result[i] = buf.ReadInt16(err)
		}
	}
	return result
}

func writeUint16ListPayload(buf *ByteBuffer, value []uint16) {
	size := len(value) * 2
	if len(value) > 0 {
		if isLittleEndian {
			buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
		} else {
			for _, v := range value {
				buf.WriteInt16(int16(v))
			}
		}
	}
}

func readUint16ListPayload(buf *ByteBuffer, err *Error, length int, hasNull bool) []uint16 {
	result := make([]uint16, length)
	if !hasNull {
		size := length * 2
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
	for i := 0; i < length; i++ {
		if buf.ReadInt8(err) != NullFlag {
			result[i] = uint16(buf.ReadInt16(err))
		}
	}
	return result
}

func writeInt32ListPayload(buf *ByteBuffer, value []int32, typeID TypeId) {
	if typeID == INT32 {
		writeInt32FixedListPayload(buf, value)
		return
	}
	for _, v := range value {
		buf.WriteVarint32(v)
	}
}

func writeInt32FixedListPayload(buf *ByteBuffer, value []int32) {
	size := len(value) * 4
	if len(value) > 0 {
		if isLittleEndian {
			buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
		} else {
			for _, v := range value {
				buf.WriteInt32(v)
			}
		}
	}
}

func readInt32ListPayload(buf *ByteBuffer, err *Error, length int, hasNull bool, typeID TypeId) []int32 {
	result := make([]int32, length)
	if !hasNull && typeID == INT32 {
		size := length * 4
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
	for i := 0; i < length; i++ {
		if hasNull && buf.ReadInt8(err) == NullFlag {
			continue
		}
		if typeID == INT32 {
			result[i] = buf.ReadInt32(err)
		} else {
			result[i] = buf.ReadVarint32(err)
		}
	}
	return result
}

func writeUint32ListPayload(buf *ByteBuffer, value []uint32, typeID TypeId) {
	if typeID == UINT32 {
		writeUint32FixedListPayload(buf, value)
		return
	}
	for _, v := range value {
		buf.WriteVarUint32(v)
	}
}

func writeUint32FixedListPayload(buf *ByteBuffer, value []uint32) {
	size := len(value) * 4
	if len(value) > 0 {
		if isLittleEndian {
			buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
		} else {
			for _, v := range value {
				buf.WriteInt32(int32(v))
			}
		}
	}
}

func readUint32ListPayload(buf *ByteBuffer, err *Error, length int, hasNull bool, typeID TypeId) []uint32 {
	result := make([]uint32, length)
	if !hasNull && typeID == UINT32 {
		size := length * 4
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
	for i := 0; i < length; i++ {
		if hasNull && buf.ReadInt8(err) == NullFlag {
			continue
		}
		if typeID == UINT32 {
			result[i] = uint32(buf.ReadInt32(err))
		} else {
			result[i] = buf.ReadVarUint32(err)
		}
	}
	return result
}

func writeInt64ListPayload(buf *ByteBuffer, value []int64, typeID TypeId) {
	switch typeID {
	case INT64:
		writeInt64FixedListPayload(buf, value)
	case TAGGED_INT64:
		for _, v := range value {
			buf.WriteTaggedInt64(v)
		}
	default:
		for _, v := range value {
			buf.WriteVarint64(v)
		}
	}
}

func writeInt64FixedListPayload(buf *ByteBuffer, value []int64) {
	size := len(value) * 8
	if len(value) > 0 {
		if isLittleEndian {
			buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
		} else {
			for _, v := range value {
				buf.WriteInt64(v)
			}
		}
	}
}

func readInt64ListPayload(buf *ByteBuffer, err *Error, length int, hasNull bool, typeID TypeId) []int64 {
	result := make([]int64, length)
	if !hasNull && typeID == INT64 {
		size := length * 8
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
	for i := 0; i < length; i++ {
		if hasNull && buf.ReadInt8(err) == NullFlag {
			continue
		}
		switch typeID {
		case INT64:
			result[i] = buf.ReadInt64(err)
		case TAGGED_INT64:
			result[i] = buf.ReadTaggedInt64(err)
		default:
			result[i] = buf.ReadVarint64(err)
		}
	}
	return result
}

func writeUint64ListPayload(buf *ByteBuffer, value []uint64, typeID TypeId) {
	switch typeID {
	case UINT64:
		writeUint64FixedListPayload(buf, value)
	case TAGGED_UINT64:
		for _, v := range value {
			buf.WriteTaggedUint64(v)
		}
	default:
		for _, v := range value {
			buf.WriteVarUint64(v)
		}
	}
}

func writeUint64FixedListPayload(buf *ByteBuffer, value []uint64) {
	size := len(value) * 8
	if len(value) > 0 {
		if isLittleEndian {
			buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
		} else {
			for _, v := range value {
				buf.WriteInt64(int64(v))
			}
		}
	}
}

func readUint64ListPayload(buf *ByteBuffer, err *Error, length int, hasNull bool, typeID TypeId) []uint64 {
	result := make([]uint64, length)
	if !hasNull && typeID == UINT64 {
		size := length * 8
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
	for i := 0; i < length; i++ {
		if hasNull && buf.ReadInt8(err) == NullFlag {
			continue
		}
		switch typeID {
		case UINT64:
			result[i] = uint64(buf.ReadInt64(err))
		case TAGGED_UINT64:
			result[i] = buf.ReadTaggedUint64(err)
		default:
			result[i] = buf.ReadVarUint64(err)
		}
	}
	return result
}

func writeIntListPayload(buf *ByteBuffer, value []int, typeID TypeId) {
	if reflect.TypeOf(int(0)).Size() == 8 {
		asInt64 := unsafe.Slice((*int64)(unsafe.Pointer(&value[0])), len(value))
		writeInt64ListPayload(buf, asInt64, typeID)
		return
	}
	for _, v := range value {
		if typeID == INT32 {
			buf.WriteInt32(int32(v))
		} else {
			buf.WriteVarint32(int32(v))
		}
	}
}

func readIntListPayload(buf *ByteBuffer, err *Error, length int, hasNull bool, typeID TypeId) []int {
	result := make([]int, length)
	if reflect.TypeOf(int(0)).Size() == 8 {
		values := readInt64ListPayload(buf, err, length, hasNull, typeID)
		copy(unsafe.Slice((*int64)(unsafe.Pointer(&result[0])), length), values)
		return result
	}
	for i := 0; i < length; i++ {
		if hasNull && buf.ReadInt8(err) == NullFlag {
			continue
		}
		if typeID == INT32 {
			result[i] = int(buf.ReadInt32(err))
		} else {
			result[i] = int(buf.ReadVarint32(err))
		}
	}
	return result
}

func writeUintListPayload(buf *ByteBuffer, value []uint, typeID TypeId) {
	if reflect.TypeOf(uint(0)).Size() == 8 {
		asUint64 := unsafe.Slice((*uint64)(unsafe.Pointer(&value[0])), len(value))
		writeUint64ListPayload(buf, asUint64, typeID)
		return
	}
	for _, v := range value {
		if typeID == UINT32 {
			buf.WriteInt32(int32(v))
		} else {
			buf.WriteVarUint32(uint32(v))
		}
	}
}

func readUintListPayload(buf *ByteBuffer, err *Error, length int, hasNull bool, typeID TypeId) []uint {
	result := make([]uint, length)
	if reflect.TypeOf(uint(0)).Size() == 8 {
		values := readUint64ListPayload(buf, err, length, hasNull, typeID)
		copy(unsafe.Slice((*uint64)(unsafe.Pointer(&result[0])), length), values)
		return result
	}
	for i := 0; i < length; i++ {
		if hasNull && buf.ReadInt8(err) == NullFlag {
			continue
		}
		if typeID == UINT32 {
			result[i] = uint(buf.ReadInt32(err))
		} else {
			result[i] = uint(buf.ReadVarUint32(err))
		}
	}
	return result
}

func writeFloat32ListPayload(buf *ByteBuffer, value []float32) {
	size := len(value) * 4
	if len(value) > 0 {
		if isLittleEndian {
			buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
		} else {
			for _, v := range value {
				buf.WriteFloat32(v)
			}
		}
	}
}

func readFloat32ListPayload(buf *ByteBuffer, err *Error, length int, hasNull bool) []float32 {
	result := make([]float32, length)
	if !hasNull {
		size := length * 4
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
	for i := 0; i < length; i++ {
		if buf.ReadInt8(err) != NullFlag {
			result[i] = buf.ReadFloat32(err)
		}
	}
	return result
}

func writeFloat64ListPayload(buf *ByteBuffer, value []float64) {
	size := len(value) * 8
	if len(value) > 0 {
		if isLittleEndian {
			buf.WriteBinary(unsafe.Slice((*byte)(unsafe.Pointer(&value[0])), size))
		} else {
			for _, v := range value {
				buf.WriteFloat64(v)
			}
		}
	}
}

func readFloat64ListPayload(buf *ByteBuffer, err *Error, length int, hasNull bool) []float64 {
	result := make([]float64, length)
	if !hasNull {
		size := length * 8
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
	for i := 0; i < length; i++ {
		if buf.ReadInt8(err) != NullFlag {
			result[i] = buf.ReadFloat64(err)
		}
	}
	return result
}
