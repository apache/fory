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

// ============================================================================
// Primitive Serializers - implement unified Serializer interface
// ============================================================================

// boolSerializer handles bool type
type boolSerializer struct{}

var globalBoolSerializer = boolSerializer{}

func (s boolSerializer) TypeId() TypeId       { return BOOL }
func (s boolSerializer) NeedToWriteRef() bool { return false }

func (s boolSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	ctx.buffer.WriteBool(value.Bool())
	return nil
}

func (s boolSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	value.SetBool(ctx.buffer.ReadBool())
	return nil
}

// int8Serializer handles int8 type
type int8Serializer struct{}

var globalInt8Serializer = int8Serializer{}

func (s int8Serializer) TypeId() TypeId       { return INT8 }
func (s int8Serializer) NeedToWriteRef() bool { return false }

func (s int8Serializer) Write(ctx *WriteContext, value reflect.Value) error {
	ctx.buffer.WriteInt8(int8(value.Int()))
	return nil
}

func (s int8Serializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	value.SetInt(int64(ctx.buffer.ReadInt8()))
	return nil
}

// byteSerializer handles byte/uint8 type
type byteSerializer struct{}

func (s byteSerializer) TypeId() TypeId       { return UINT8 }
func (s byteSerializer) NeedToWriteRef() bool { return false }

func (s byteSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	ctx.buffer.WriteByte_(byte(value.Uint()))
	return nil
}

func (s byteSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	value.SetUint(uint64(ctx.buffer.ReadByte_()))
	return nil
}

// int16Serializer handles int16 type
type int16Serializer struct{}

var globalInt16Serializer = int16Serializer{}

func (s int16Serializer) TypeId() TypeId       { return INT16 }
func (s int16Serializer) NeedToWriteRef() bool { return false }

func (s int16Serializer) Write(ctx *WriteContext, value reflect.Value) error {
	ctx.buffer.WriteInt16(int16(value.Int()))
	return nil
}

func (s int16Serializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	value.SetInt(int64(ctx.buffer.ReadInt16()))
	return nil
}

// int32Serializer handles int32 type
type int32Serializer struct{}

var globalInt32Serializer = int32Serializer{}

func (s int32Serializer) TypeId() TypeId       { return INT32 }
func (s int32Serializer) NeedToWriteRef() bool { return false }

func (s int32Serializer) Write(ctx *WriteContext, value reflect.Value) error {
	ctx.buffer.WriteVarint32(int32(value.Int()))
	return nil
}

func (s int32Serializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	value.SetInt(int64(ctx.buffer.ReadVarint32()))
	return nil
}

// int64Serializer handles int64 type
type int64Serializer struct{}

var globalInt64Serializer = int64Serializer{}

func (s int64Serializer) TypeId() TypeId       { return INT64 }
func (s int64Serializer) NeedToWriteRef() bool { return false }

func (s int64Serializer) Write(ctx *WriteContext, value reflect.Value) error {
	ctx.buffer.WriteVarint64(value.Int())
	return nil
}

func (s int64Serializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	value.SetInt(ctx.buffer.ReadVarint64())
	return nil
}

// intSerializer handles int type
type intSerializer struct{}

func (s intSerializer) TypeId() TypeId       { return -INT64 }
func (s intSerializer) NeedToWriteRef() bool { return false }

func (s intSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	ctx.buffer.WriteVarint64(value.Int())
	return nil
}

func (s intSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	value.SetInt(ctx.buffer.ReadVarint64())
	return nil
}

// float32Serializer handles float32 type
type float32Serializer struct{}

var globalFloat32Serializer = float32Serializer{}

func (s float32Serializer) TypeId() TypeId       { return FLOAT }
func (s float32Serializer) NeedToWriteRef() bool { return false }

func (s float32Serializer) Write(ctx *WriteContext, value reflect.Value) error {
	ctx.buffer.WriteFloat32(float32(value.Float()))
	return nil
}

func (s float32Serializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	value.SetFloat(float64(ctx.buffer.ReadFloat32()))
	return nil
}

// float64Serializer handles float64 type
type float64Serializer struct{}

var globalFloat64Serializer = float64Serializer{}

func (s float64Serializer) TypeId() TypeId       { return DOUBLE }
func (s float64Serializer) NeedToWriteRef() bool { return false }

func (s float64Serializer) Write(ctx *WriteContext, value reflect.Value) error {
	ctx.buffer.WriteFloat64(value.Float())
	return nil
}

func (s float64Serializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	value.SetFloat(ctx.buffer.ReadFloat64())
	return nil
}
