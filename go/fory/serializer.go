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

// Serializer is the unified interface for all serialization.
// It provides reflect.Value-based API for efficient serialization.
type Serializer interface {
	// Write serializes using reflect.Value.
	// Does NOT write ref/type info - caller handles that.
	Write(ctx *WriteContext, value reflect.Value) error

	// Read deserializes directly into the provided reflect.Value.
	// Does NOT read ref/type info - caller handles that.
	// For non-trivial types (slices, maps), implementations should reuse existing capacity when possible.
	Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error

	// TypeId returns the Fory protocol type ID
	TypeId() TypeId

	// NeedToWriteRef returns true if this type needs reference tracking
	NeedToWriteRef() bool
}

// Helper functions for serializer dispatch

func writeBySerializer(ctx *WriteContext, value reflect.Value, serializer Serializer, referencable bool) error {
	buf := ctx.Buffer()
	if referencable {
		if isNull(value) {
			buf.WriteInt8(NullFlag)
			return nil
		}
		// Check for reference
		refWritten, err := ctx.RefResolver().WriteRefOrNull(buf, value)
		if err != nil {
			return err
		}
		if refWritten {
			return nil
		}
	}
	// If no serializer provided, look it up from typeResolver and write type info
	if serializer == nil {
		typeInfo, err := ctx.TypeResolver().getTypeInfo(value, true)
		if err != nil {
			return err
		}
		// Write type info for dynamic types (so reader can look up the serializer)
		if err := ctx.TypeResolver().writeTypeInfo(buf, typeInfo); err != nil {
			return err
		}
		serializer = typeInfo.Serializer
	}
	return serializer.Write(ctx, value)
}

func readBySerializer(ctx *ReadContext, value reflect.Value, serializer Serializer, referencable bool) error {
	buf := ctx.Buffer()
	if referencable {
		refID, err := ctx.RefResolver().TryPreserveRefId(buf)
		if err != nil {
			return err
		}
		if int8(refID) < NotNullValueFlag {
			// Reference found
			obj := ctx.RefResolver().GetReadObject(refID)
			if obj.IsValid() {
				value.Set(obj)
			}
			return nil
		}
	}
	return serializer.Read(ctx, value.Type(), value)
}
