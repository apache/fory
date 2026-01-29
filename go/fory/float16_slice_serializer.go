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

// Ensure interface implementation
var _ Serializer = float16SliceSerializer{}
