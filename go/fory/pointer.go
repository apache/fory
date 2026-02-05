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

// ptrToValueSerializer serializes a pointer to a concrete (non-interface) value
type ptrToValueSerializer struct {
	valueSerializer Serializer
}

// ptrToInterfaceSerializer serializes a pointer to an interface value
type ptrToInterfaceSerializer struct {
}

// ============================================================================
// ptrToValueSerializer - pointer to concrete type
// ============================================================================

func (s *ptrToValueSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	elemValue := value.Elem()
	s.valueSerializer.WriteData(ctx, elemValue)
}

func (s *ptrToValueSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	switch refMode {
	case RefModeTracking:
		if value.IsNil() {
			ctx.Buffer().WriteInt8(NullFlag)
			return
		}
		refWritten, err := ctx.RefResolver().WriteRefOrNull(ctx.Buffer(), value)
		if err != nil {
			ctx.SetError(FromError(err))
			return
		}
		if refWritten {
			return
		}
	case RefModeNullOnly:
		if value.IsNil() {
			ctx.Buffer().WriteInt8(NullFlag)
			return
		}
		ctx.Buffer().WriteInt8(NotNullValueFlag)
	case RefModeNone:
		// For RefModeNone with nullable=false, we should NOT write any null flag.
		// The xlang protocol expects the value data directly without any header.
		// If the pointer is nil, write a zero/default value for the underlying type.
		// This can happen in schema evolution when a field is missing from the remote data.
		if value.IsNil() {
			// Create a zero value for the underlying type and write it
			zeroValue := reflect.New(value.Type().Elem()).Elem()
			if writeType {
				typeInfo, err := ctx.TypeResolver().getTypeInfo(zeroValue, true)
				if err != nil {
					ctx.SetError(FromError(err))
					return
				}
				ctx.TypeResolver().WriteTypeInfo(ctx.Buffer(), typeInfo, ctx.Err())
			}
			s.valueSerializer.WriteData(ctx, zeroValue)
			return
		}
		// Do NOT write any flag - just continue to write the value data
	}
	if writeType {
		// Always use TypeResolver to get the correct TypeID from registered TypeInfo
		// This ensures compatible mode uses NAMED_COMPATIBLE_STRUCT instead of NAMED_STRUCT
		typeInfo, err := ctx.TypeResolver().getTypeInfo(value.Elem(), true)
		if err != nil {
			ctx.SetError(FromError(err))
			return
		}
		ctx.TypeResolver().WriteTypeInfo(ctx.Buffer(), typeInfo, ctx.Err())
	}
	s.WriteData(ctx, value)
}

func (s *ptrToValueSerializer) writeWithTypeInfo(ctx *WriteContext, refMode RefMode, value reflect.Value, typeInfo *TypeInfo) {
	switch refMode {
	case RefModeTracking:
		if value.IsNil() {
			ctx.Buffer().WriteInt8(NullFlag)
			return
		}
		refWritten, err := ctx.RefResolver().WriteRefOrNull(ctx.Buffer(), value)
		if err != nil {
			ctx.SetError(FromError(err))
			return
		}
		if refWritten {
			return
		}
	case RefModeNullOnly:
		if value.IsNil() {
			ctx.Buffer().WriteInt8(NullFlag)
			return
		}
		ctx.Buffer().WriteInt8(NotNullValueFlag)
	case RefModeNone:
		if value.IsNil() {
			zeroValue := reflect.New(value.Type().Elem()).Elem()
			if typeInfo == nil {
				s.Write(ctx, refMode, true, false, value)
				return
			}
			ctx.TypeResolver().WriteTypeInfo(ctx.Buffer(), typeInfo, ctx.Err())
			s.valueSerializer.WriteData(ctx, zeroValue)
			return
		}
	}
	if typeInfo == nil {
		s.Write(ctx, refMode, true, false, value)
		return
	}
	ctx.TypeResolver().WriteTypeInfo(ctx.Buffer(), typeInfo, ctx.Err())
	s.WriteData(ctx, value)
}

func (s *ptrToValueSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	// Check if value is already allocated (for circular reference handling)
	var newVal reflect.Value
	if value.IsNil() {
		// Allocate new value
		newVal = reflect.New(value.Type().Elem())
		value.Set(newVal)
	} else {
		// Value already allocated (circular reference case)
		newVal = value
	}

	// Register the pointer for reference tracking BEFORE reading data
	// This allows circular references to work correctly
	ctx.RefResolver().Reference(value)

	s.valueSerializer.ReadData(ctx, newVal.Elem())
}

func (s *ptrToValueSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
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
			// Reference found
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
	case RefModeNone:
		// For RefModeNone with nullable=false, no null flag was written.
		// Just continue to read the value data directly.
	}
	if readType {
		// Read type info - in compatible mode this contains the serializer with fieldDefs
		typeID := uint32(buf.ReadUint8(ctxErr))
		if ctx.HasError() {
			return
		}
		internalTypeID := TypeId(typeID)
		// Check if this is a struct type that needs type meta reading
		if IsNamespacedType(internalTypeID) || internalTypeID == COMPATIBLE_STRUCT || internalTypeID == STRUCT {
			typeInfo := ctx.TypeResolver().readTypeInfoWithTypeID(buf, typeID, ctxErr)
			// Use the serializer from TypeInfo which has the remote field definitions
			if structSer, ok := typeInfo.Serializer.(*structSerializer); ok && len(structSer.fieldDefs) > 0 {
				// Allocate the pointer value if needed
				if value.IsNil() {
					value.Set(reflect.New(value.Type().Elem()))
				}
				ctx.RefResolver().Reference(value)
				structSer.ReadData(ctx, value.Elem())
				return
			}
		}
	}

	s.ReadData(ctx, value)
}

func (s *ptrToValueSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// ============================================================================
// ptrToInterfaceSerializer - pointer to interface type
// ============================================================================

func (s *ptrToInterfaceSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	// Get the concrete element that the interface pointer points to
	elemValue := value.Elem()

	// Use WriteValue to handle the polymorphic interface value with ref tracking and type info
	ctx.WriteValue(elemValue, RefModeTracking, true)
}

func (s *ptrToInterfaceSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	switch refMode {
	case RefModeTracking:
		if value.IsNil() {
			ctx.Buffer().WriteInt8(NullFlag)
			return
		}
		refWritten, err := ctx.RefResolver().WriteRefOrNull(ctx.Buffer(), value)
		if err != nil {
			ctx.SetError(FromError(err))
			return
		}
		if refWritten {
			return
		}
	case RefModeNullOnly:
		if value.IsNil() {
			ctx.Buffer().WriteInt8(NullFlag)
			return
		}
		ctx.Buffer().WriteInt8(NotNullValueFlag)
	}

	// For interface pointers, we don't write type info here
	// WriteData will call WriteValue which handles the type info for the concrete value
	s.WriteData(ctx, value)
}

func (s *ptrToInterfaceSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	// Create a new interface pointer
	newVal := reflect.New(value.Type().Elem())

	// Use ReadValue to handle the polymorphic interface value with ref tracking and type info
	ctx.ReadValue(newVal.Elem(), RefModeTracking, true)
	if ctx.HasError() {
		return
	}

	value.Set(newVal)
}

func (s *ptrToInterfaceSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
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
			// Reference found
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

func (s *ptrToInterfaceSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}
