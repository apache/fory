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

	"github.com/apache/fory/go/fory/float16"
)

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
		ctx.buffer.WriteVaruint32Small7(uint32(FLOAT16))
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
		_ = ctx.buffer.ReadVaruint32Small7(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s float16Serializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// Ensure interface implementation
var _ Serializer = float16Serializer{}

// Cast to/from calls for helper access if needed (though mostly reflection driven)
func castToFloat16(v any) float16.Float16 {
	if f, ok := v.(float16.Float16); ok {
		return f
	}
	return 0
}
