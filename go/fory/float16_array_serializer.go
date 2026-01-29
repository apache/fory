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

	"github.com/apache/fory/go/fory/float16"
)

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

// Ensure interface implementation
var _ Serializer = float16ArraySerializer{}
