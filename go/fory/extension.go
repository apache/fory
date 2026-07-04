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

// extensionSerializerAdapter wraps an ExtensionSerializer to implement the full Serializer interface.
// This adapter handles reference tracking, type info writing/reading, and delegates the actual
// data serialization to the user-provided ExtensionSerializer.
type extensionSerializerAdapter struct {
	type_      reflect.Type
	typeTag    string
	userSerial ExtensionSerializer
}

func (s *extensionSerializerAdapter) GetType() reflect.Type { return s.type_ }

func (s *extensionSerializerAdapter) WriteData(ctx *WriteContext, value reflect.Value) {
	// Delegate to user's serializer
	s.userSerial.WriteData(ctx, value)
}

func (s *extensionSerializerAdapter) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics // not used for extension serializers
	buf := ctx.Buffer()
	switch refMode {
	case RefModeTracking:
		refWritten, err := ctx.RefResolver().WriteRefOrNull(buf, value)
		if err != nil {
			ctx.SetError(FromError(err))
			return
		}
		if refWritten {
			return
		}
	case RefModeNullOnly:
		if isNil(value) {
			buf.WriteInt8(NullFlag)
			return
		}
		buf.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		typeInfo, err := ctx.TypeResolver().GetTypeInfo(value, true)
		if err != nil {
			ctx.SetError(FromError(err))
			return
		}
		ctx.TypeResolver().WriteTypeInfo(buf, typeInfo, ctx.Err())
	}
	s.WriteData(ctx, value)
}

func (s *extensionSerializerAdapter) ReadData(ctx *ReadContext, value reflect.Value) {
	// Delegate to user's serializer
	s.userSerial.ReadData(ctx, value)
}

func (s *extensionSerializerAdapter) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics // not used for extension serializers
	buf := ctx.Buffer()
	ctxErr := ctx.Err()
	switch refMode {
	case RefModeTracking:
		refID, refErr := ctx.RefResolver().TryPreserveRefId(buf)
		if refErr != nil {
			ctx.SetError(FromError(refErr))
			return
		}
		if refID < int32(NotNullValueFlag) {
			obj := ctx.RefResolver().GetReadObject(refID)
			if obj.IsValid() {
				value.Set(obj)
			}
			return
		}
	case RefModeNullOnly:
		flag := buf.ReadInt8(ctxErr)
		if flag == NullFlag {
			return
		}
	}
	s.ReadData(ctx, value)
}

func (s *extensionSerializerAdapter) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}
