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
	"fmt"
	"reflect"
)

func writeSerializerData(ctx *WriteContext, serializer Serializer, hasGenerics bool, value reflect.Value) {
	if hasGenerics && serializerNeedsGenericDispatch(serializer) {
		serializer.Write(ctx, RefModeNone, false, true, value)
		return
	}
	serializer.WriteData(ctx, value)
}

func readSerializerData(ctx *ReadContext, serializer Serializer, hasGenerics bool, value reflect.Value) {
	if hasGenerics && serializerNeedsGenericDispatch(serializer) {
		serializer.Read(ctx, RefModeNone, false, true, value)
		return
	}
	serializer.ReadData(ctx, value)
}

func serializerNeedsGenericDispatch(serializer Serializer) bool {
	switch serializer.(type) {
	case *sliceSerializer,
		primitiveListSerializer,
		sliceDynSerializer,
		setSerializer,
		mapSerializer,
		stringSliceSerializer,
		stringStringMapSerializer,
		stringInt64MapSerializer,
		stringIntMapSerializer,
		stringFloat64MapSerializer,
		stringBoolMapSerializer,
		int32Int32MapSerializer,
		int64Int64MapSerializer,
		intIntMapSerializer:
		return true
	default:
		return false
	}
}

func newDeclaredSliceSerializer(type_ reflect.Type, elemSerializer Serializer, referencable bool) (*sliceSerializer, error) {
	elem := type_.Elem()
	if elem.Kind() == reflect.Interface {
		return nil, fmt.Errorf("slice serializer does not support interface element type: %v", type_)
	}
	if elem.Kind() == reflect.Ptr && elem.Elem().Kind() == reflect.Interface {
		return nil, fmt.Errorf("slice serializer does not support pointer to interface element type: %v", type_)
	}
	return &sliceSerializer{
		type_:          type_,
		elemSerializer: elemSerializer,
		referencable:   referencable,
	}, nil
}

type encodedByteSliceSerializer struct {
	typeID TypeId
}

func (s encodedByteSliceSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	byteSliceSerializer{}.WriteData(ctx, value)
}

func (s encodedByteSliceSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics
	done := writeSliceRefAndType(ctx, refMode, writeType, value, s.typeID)
	if done || ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s encodedByteSliceSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	byteSliceSerializer{}.ReadData(ctx, value)
}

func (s encodedByteSliceSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics
	done, typeID := readSliceRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	if readType && typeID != uint32(BINARY) && typeID != uint32(UINT8_ARRAY) {
		ctx.SetError(DeserializationErrorf("slice type mismatch: expected BINARY (%d) or UINT8_ARRAY (%d), got %d", BINARY, UINT8_ARRAY, typeID))
		return
	}
	s.ReadData(ctx, value)
}

func (s encodedByteSliceSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

type encodedInt32Serializer struct {
	typeID TypeId
}

func (s encodedInt32Serializer) WriteData(ctx *WriteContext, value reflect.Value) {
	switch s.typeID {
	case INT32:
		ctx.buffer.WriteInt32(int32(value.Int()))
	default:
		ctx.buffer.WriteVarint32(int32(value.Int()))
	}
}

func (s encodedInt32Serializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(s.typeID))
	}
	s.WriteData(ctx, value)
}

func (s encodedInt32Serializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	switch s.typeID {
	case INT32:
		value.SetInt(int64(ctx.buffer.ReadInt32(err)))
	default:
		value.SetInt(int64(ctx.buffer.ReadVarint32(err)))
	}
}

func (s encodedInt32Serializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
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

func (s encodedInt32Serializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

type encodedUint32Serializer struct {
	typeID TypeId
}

func (s encodedUint32Serializer) WriteData(ctx *WriteContext, value reflect.Value) {
	switch s.typeID {
	case UINT32:
		ctx.buffer.WriteUint32(uint32(value.Uint()))
	default:
		ctx.buffer.WriteVarUint32(uint32(value.Uint()))
	}
}

func (s encodedUint32Serializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(s.typeID))
	}
	s.WriteData(ctx, value)
}

func (s encodedUint32Serializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	switch s.typeID {
	case UINT32:
		value.SetUint(uint64(ctx.buffer.ReadUint32(err)))
	default:
		value.SetUint(uint64(ctx.buffer.ReadVarUint32(err)))
	}
}

func (s encodedUint32Serializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
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

func (s encodedUint32Serializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

type encodedInt64Serializer struct {
	typeID TypeId
}

func (s encodedInt64Serializer) WriteData(ctx *WriteContext, value reflect.Value) {
	switch s.typeID {
	case INT64:
		ctx.buffer.WriteInt64(value.Int())
	case TAGGED_INT64:
		ctx.buffer.WriteTaggedInt64(value.Int())
	default:
		ctx.buffer.WriteVarint64(value.Int())
	}
}

func (s encodedInt64Serializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(s.typeID))
	}
	s.WriteData(ctx, value)
}

func (s encodedInt64Serializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	switch s.typeID {
	case INT64:
		value.SetInt(ctx.buffer.ReadInt64(err))
	case TAGGED_INT64:
		value.SetInt(ctx.buffer.ReadTaggedInt64(err))
	default:
		value.SetInt(ctx.buffer.ReadVarint64(err))
	}
}

func (s encodedInt64Serializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
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

func (s encodedInt64Serializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

type encodedUint64Serializer struct {
	typeID TypeId
}

func (s encodedUint64Serializer) WriteData(ctx *WriteContext, value reflect.Value) {
	switch s.typeID {
	case UINT64:
		ctx.buffer.WriteUint64(value.Uint())
	case TAGGED_UINT64:
		ctx.buffer.WriteTaggedUint64(value.Uint())
	default:
		ctx.buffer.WriteVarUint64(value.Uint())
	}
}

func (s encodedUint64Serializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(s.typeID))
	}
	s.WriteData(ctx, value)
}

func (s encodedUint64Serializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	switch s.typeID {
	case UINT64:
		value.SetUint(ctx.buffer.ReadUint64(err))
	case TAGGED_UINT64:
		value.SetUint(ctx.buffer.ReadTaggedUint64(err))
	default:
		value.SetUint(ctx.buffer.ReadVarUint64(err))
	}
}

func (s encodedUint64Serializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
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

func (s encodedUint64Serializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

func serializerForEncodedScalar(goType reflect.Type, typeID TypeId) (Serializer, bool, error) {
	if goType == byteSliceType && (typeID == BINARY || typeID == UINT8_ARRAY) {
		return encodedByteSliceSerializer{typeID: typeID}, true, nil
	}
	switch typeID {
	case INT32, VARINT32:
		return encodedInt32Serializer{typeID: typeID}, true, nil
	case UINT32, VAR_UINT32:
		return encodedUint32Serializer{typeID: typeID}, true, nil
	case INT64, VARINT64, TAGGED_INT64:
		return encodedInt64Serializer{typeID: typeID}, true, nil
	case UINT64, VAR_UINT64, TAGGED_UINT64:
		return encodedUint64Serializer{typeID: typeID}, true, nil
	default:
		_ = goType
		return nil, false, nil
	}
}
