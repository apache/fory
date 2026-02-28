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
	"encoding/binary"
	"fmt"
	"math"
	"reflect"
	"unsafe"
)

type structSerializer struct {
	// Identity
	name       string
	type_      reflect.Type
	structHash int32
	typeID     uint32
	userTypeID uint32

	// Pre-sorted and categorized fields (embedded for cache locality)
	fieldGroup FieldGroup

	// Original field list for hash computation and compatible mode
	fields    []FieldInfo // all fields in sorted order (before grouping)
	fieldDefs []FieldDef  // for type_def compatibility

	// Mode flags (set at init)
	isCompatibleMode bool // true when compatible=true
	typeDefDiffers   bool // true when compatible=true AND remote TypeDef != local (requires ordered read)

	// Initialization state
	initialized bool

	// Cached addressable value for non-addressable writes.
	tempValue *reflect.Value
}

// newStructSerializerFromTypeDef creates a new structSerializer with the given parameters.
// name can be empty and will be derived from type_.Name() if not provided.
// fieldDefs is from remote schema.
func newStructSerializerFromTypeDef(type_ reflect.Type, name string, fieldDefs []FieldDef) *structSerializer {
	if name == "" && type_ != nil {
		name = type_.Name()
	}
	return &structSerializer{
		type_:      type_,
		name:       name,
		userTypeID: invalidUserTypeID,
		fieldDefs:  fieldDefs,
	}
}

// newStructSerializer creates a new structSerializer with the given parameters.
// name can be empty and will be derived from type_.Name() if not provided.
// fieldDefs can be nil for local structs without remote schema.
func newStructSerializer(type_ reflect.Type, name string) *structSerializer {
	if name == "" && type_ != nil {
		name = type_.Name()
	}
	return &structSerializer{
		type_:      type_,
		name:       name,
		userTypeID: invalidUserTypeID,
	}
}

func (s *structSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	switch refMode {
	case RefModeTracking:
		if value.Kind() == reflect.Ptr && value.IsNil() {
			ctx.buffer.WriteInt8(NullFlag)
			return
		}
		refWritten, err := ctx.RefResolver().WriteRefOrNull(ctx.buffer, value)
		if err != nil {
			ctx.SetError(FromError(err))
			return
		}
		if refWritten {
			return
		}
	case RefModeNullOnly:
		if value.Kind() == reflect.Ptr && value.IsNil() {
			ctx.buffer.WriteInt8(NullFlag)
			return
		}
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		// Structs have dynamic type IDs, need to look up from TypeResolver
		typeInfo, err := ctx.TypeResolver().getTypeInfo(value, true)
		if err != nil {
			ctx.SetError(FromError(err))
			return
		}
		ctx.TypeResolver().WriteTypeInfo(ctx.buffer, typeInfo, ctx.Err())
	}
	s.WriteData(ctx, value)
}

func (s *structSerializer) writeWithTypeInfo(ctx *WriteContext, refMode RefMode, value reflect.Value, typeInfo *TypeInfo) {
	switch refMode {
	case RefModeTracking:
		if value.Kind() == reflect.Ptr && value.IsNil() {
			ctx.buffer.WriteInt8(NullFlag)
			return
		}
		refWritten, err := ctx.RefResolver().WriteRefOrNull(ctx.buffer, value)
		if err != nil {
			ctx.SetError(FromError(err))
			return
		}
		if refWritten {
			return
		}
	case RefModeNullOnly:
		if value.Kind() == reflect.Ptr && value.IsNil() {
			ctx.buffer.WriteInt8(NullFlag)
			return
		}
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if typeInfo == nil {
		s.Write(ctx, refMode, true, false, value)
		return
	}
	ctx.TypeResolver().WriteTypeInfo(ctx.buffer, typeInfo, ctx.Err())
	s.WriteData(ctx, value)
}

func (s *structSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	// Early error check - skip all intermediate checks for normal path performance
	if ctx.HasError() {
		return
	}

	// Lazy initialization
	if !s.initialized {
		if err := s.initialize(ctx.TypeResolver()); err != nil {
			ctx.SetError(FromError(err))
			return
		}
	}

	buf := ctx.Buffer()

	// Dereference pointer if needed
	if value.Kind() == reflect.Ptr {
		if value.IsNil() {
			ctx.SetError(SerializationError("cannot write nil pointer"))
			return
		}
		value = value.Elem()
	}

	// In compatible mode with meta share, struct hash is not written
	if !ctx.Compatible() {
		buf.WriteInt32(s.structHash)
	}

	// Ensure value is addressable for unsafe access
	if !value.CanAddr() {
		reuseCache := s.tempValue != nil
		if ctx.RefResolver().refTracking && len(ctx.RefResolver().writtenObjects) > 0 {
			reuseCache = false
		}
		if reuseCache {
			tempValue := s.tempValue
			s.tempValue = nil
			defer func() {
				tempValue.SetZero()
				s.tempValue = tempValue
			}()
			addrValue := *tempValue
			addrValue.Set(value)
			value = addrValue
		} else {
			tmp := reflect.New(value.Type()).Elem()
			tmp.Set(value)
			value = tmp
		}
	}
	ptr := unsafe.Pointer(value.UnsafeAddr())

	// ==========================================================================
	// Phase 1: Fixed-size primitives (bool, int8, int16, float32, float64)
	// - Reserve once, inline unsafe writes with endian handling, update index once
	// - field.WriteOffset computed at init time
	// ==========================================================================
	if s.fieldGroup.FixedSize > 0 {
		buf.Reserve(s.fieldGroup.FixedSize)
		baseOffset := buf.WriterIndex()
		data := buf.GetData()

		for _, field := range s.fieldGroup.PrimitiveFixedFields {
			fieldPtr := unsafe.Add(ptr, field.Offset)
			bufOffset := baseOffset + int(field.WriteOffset)
			optInfo := optionalInfo{}
			if field.Kind == FieldKindOptional && field.Meta != nil {
				optInfo = field.Meta.OptionalInfo
			}
			switch field.DispatchId {
			case PrimitiveBoolDispatchId:
				v, ok := loadFieldValue[bool](field.Kind, fieldPtr, optInfo)
				if ok && v {
					data[bufOffset] = 1
				} else {
					data[bufOffset] = 0
				}
			case PrimitiveInt8DispatchId:
				v, ok := loadFieldValue[int8](field.Kind, fieldPtr, optInfo)
				if ok {
					data[bufOffset] = byte(v)
				} else {
					data[bufOffset] = 0
				}
			case PrimitiveUint8DispatchId:
				v, ok := loadFieldValue[uint8](field.Kind, fieldPtr, optInfo)
				if ok {
					data[bufOffset] = v
				} else {
					data[bufOffset] = 0
				}
			case PrimitiveInt16DispatchId:
				v, ok := loadFieldValue[int16](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				if isLittleEndian {
					*(*int16)(unsafe.Pointer(&data[bufOffset])) = v
				} else {
					binary.LittleEndian.PutUint16(data[bufOffset:], uint16(v))
				}
			case PrimitiveUint16DispatchId:
				v, ok := loadFieldValue[uint16](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				if isLittleEndian {
					*(*uint16)(unsafe.Pointer(&data[bufOffset])) = v
				} else {
					binary.LittleEndian.PutUint16(data[bufOffset:], v)
				}
			case PrimitiveInt32DispatchId:
				v, ok := loadFieldValue[int32](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				if isLittleEndian {
					*(*int32)(unsafe.Pointer(&data[bufOffset])) = v
				} else {
					binary.LittleEndian.PutUint32(data[bufOffset:], uint32(v))
				}
			case PrimitiveUint32DispatchId:
				v, ok := loadFieldValue[uint32](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				if isLittleEndian {
					*(*uint32)(unsafe.Pointer(&data[bufOffset])) = v
				} else {
					binary.LittleEndian.PutUint32(data[bufOffset:], v)
				}
			case PrimitiveInt64DispatchId:
				v, ok := loadFieldValue[int64](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				if isLittleEndian {
					*(*int64)(unsafe.Pointer(&data[bufOffset])) = v
				} else {
					binary.LittleEndian.PutUint64(data[bufOffset:], uint64(v))
				}
			case PrimitiveUint64DispatchId:
				v, ok := loadFieldValue[uint64](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				if isLittleEndian {
					*(*uint64)(unsafe.Pointer(&data[bufOffset])) = v
				} else {
					binary.LittleEndian.PutUint64(data[bufOffset:], v)
				}
			case PrimitiveFloat32DispatchId:
				v, ok := loadFieldValue[float32](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				if isLittleEndian {
					*(*float32)(unsafe.Pointer(&data[bufOffset])) = v
				} else {
					binary.LittleEndian.PutUint32(data[bufOffset:], math.Float32bits(v))
				}
			case PrimitiveFloat64DispatchId:
				v, ok := loadFieldValue[float64](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				if isLittleEndian {
					*(*float64)(unsafe.Pointer(&data[bufOffset])) = v
				} else {
					binary.LittleEndian.PutUint64(data[bufOffset:], math.Float64bits(v))
				}
			case PrimitiveFloat16DispatchId:
				v, ok := loadFieldValue[uint16](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				if isLittleEndian {
					*(*uint16)(unsafe.Pointer(&data[bufOffset])) = v
				} else {
					binary.LittleEndian.PutUint16(data[bufOffset:], v)
				}

			}
		}
		// Update writer index ONCE after all fixed fields
		buf.SetWriterIndex(baseOffset + s.fieldGroup.FixedSize)
	}

	// ==========================================================================
	// Phase 2: Varint primitives (int32, int64, int, uint32, uint64, uint, tagged int64/uint64)
	// - Reserve max size once, track offset locally, update writerIndex once at end
	// ==========================================================================
	if s.fieldGroup.MaxVarintSize > 0 {
		buf.Reserve(s.fieldGroup.MaxVarintSize)
		offset := buf.WriterIndex()

		for _, field := range s.fieldGroup.PrimitiveVarintFields {
			fieldPtr := unsafe.Add(ptr, field.Offset)
			optInfo := optionalInfo{}
			if field.Kind == FieldKindOptional && field.Meta != nil {
				optInfo = field.Meta.OptionalInfo
			}
			switch field.DispatchId {
			case PrimitiveVarint32DispatchId:
				v, ok := loadFieldValue[int32](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				offset += buf.UnsafePutVarInt32(offset, v)
			case PrimitiveVarint64DispatchId:
				v, ok := loadFieldValue[int64](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				offset += buf.UnsafePutVarInt64(offset, v)
			case PrimitiveIntDispatchId:
				v, ok := loadFieldValue[int](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				offset += buf.UnsafePutVarInt64(offset, int64(v))
			case PrimitiveVarUint32DispatchId:
				v, ok := loadFieldValue[uint32](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				offset += buf.UnsafePutVarUint32(offset, v)
			case PrimitiveVarUint64DispatchId:
				v, ok := loadFieldValue[uint64](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				offset += buf.UnsafePutVarUint64(offset, v)
			case PrimitiveUintDispatchId:
				v, ok := loadFieldValue[uint](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				offset += buf.UnsafePutVarUint64(offset, uint64(v))
			case PrimitiveTaggedInt64DispatchId:
				v, ok := loadFieldValue[int64](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				offset += buf.UnsafePutTaggedInt64(offset, v)
			case PrimitiveTaggedUint64DispatchId:
				v, ok := loadFieldValue[uint64](field.Kind, fieldPtr, optInfo)
				if !ok {
					v = 0
				}
				offset += buf.UnsafePutTaggedUint64(offset, v)
			}
		}
		// Update writer index ONCE after all varint fields
		buf.SetWriterIndex(offset)
	}

	// ==========================================================================
	// Phase 3: Remaining fields (strings, slices, maps, structs, enums)
	// - These require per-field handling (ref flags, type info, serializers)
	// - No intermediate error checks - trade error path performance for normal path
	// ==========================================================================
	for i := range s.fieldGroup.RemainingFields {
		s.writeRemainingField(ctx, ptr, &s.fieldGroup.RemainingFields[i], value)
	}
}

// writeRemainingField writes a non-primitive field (string, slice, map, struct, enum)
func (s *structSerializer) writeRemainingField(ctx *WriteContext, ptr unsafe.Pointer, field *FieldInfo, value reflect.Value) {
	buf := ctx.Buffer()
	if DebugOutputEnabled {
		fmt.Printf("[fory-debug] write field %s: fieldInfo=%v typeId=%v serializer=%T, buffer writerIndex=%d\n",
			field.Meta.Name, field.Meta.FieldDef, field.Meta.TypeId, field.Serializer, buf.writerIndex)
	}
	if field.Kind == FieldKindOptional {
		if ptr != nil {
			if writeOptionFast(ctx, field, unsafe.Add(ptr, field.Offset)) {
				return
			}
		}
		fieldValue := value.Field(field.Meta.FieldIndex)
		if field.Serializer != nil {
			if writeWithCachedTypeInfo(ctx, field, fieldValue) {
				return
			}
			field.Serializer.Write(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, fieldValue)
		} else {
			ctx.WriteValue(fieldValue, RefModeTracking, true)
		}
		return
	}
	// Fast path dispatch using pre-computed DispatchId
	// ptr must be valid (addressable value)
	if ptr != nil {
		fieldPtr := unsafe.Add(ptr, field.Offset)
		switch field.DispatchId {
		case StringDispatchId:
			// Check isPtr first for better branch prediction
			if field.Kind != FieldKindPointer {
				// Non-pointer string: always non-null, no ref tracking needed in fast path
				if field.RefMode == RefModeNone {
					ctx.WriteString(*(*string)(fieldPtr))
				} else {
					// RefModeNullOnly or RefModeTracking: write NotNull flag then string
					buf.WriteInt8(NotNullValueFlag)
					ctx.WriteString(*(*string)(fieldPtr))
				}
				return
			}
			// Pointer to string: can be nil, may need ref tracking
			if field.RefMode == RefModeTracking {
				break // Fall through to slow path for ref tracking
			}
			strPtr := *(**string)(fieldPtr)
			if strPtr == nil {
				if field.RefMode == RefModeNullOnly {
					buf.WriteInt8(NullFlag)
				} else {
					// RefModeNone: write empty string for nil pointer
					ctx.WriteString("")
				}
				return
			}
			// Non-nil pointer
			if field.RefMode == RefModeNullOnly {
				buf.WriteInt8(NotNullValueFlag)
			}
			ctx.WriteString(*strPtr)
			return
		case EnumDispatchId:
			// Enums don't track refs - always use fast path
			writeEnumField(ctx, field, value.Field(field.Meta.FieldIndex))
			return
		case StringSliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteStringSlice(*(*[]string)(fieldPtr), field.RefMode, false, true)
			return
		case BoolSliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteBoolSlice(*(*[]bool)(fieldPtr), field.RefMode, false)
			return
		case Int8SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteInt8Slice(*(*[]int8)(fieldPtr), field.RefMode, false)
			return
		case ByteSliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteByteSlice(*(*[]byte)(fieldPtr), field.RefMode, false)
			return
		case Int16SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteInt16Slice(*(*[]int16)(fieldPtr), field.RefMode, false)
			return
		case Int32SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteInt32Slice(*(*[]int32)(fieldPtr), field.RefMode, false)
			return
		case Int64SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteInt64Slice(*(*[]int64)(fieldPtr), field.RefMode, false)
			return
		case Uint16SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteUint16Slice(*(*[]uint16)(fieldPtr), field.RefMode, false)
			return
		case Uint32SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteUint32Slice(*(*[]uint32)(fieldPtr), field.RefMode, false)
			return
		case Uint64SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteUint64Slice(*(*[]uint64)(fieldPtr), field.RefMode, false)
			return
		case IntSliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteIntSlice(*(*[]int)(fieldPtr), field.RefMode, false)
			return
		case UintSliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteUintSlice(*(*[]uint)(fieldPtr), field.RefMode, false)
			return
		case Float32SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteFloat32Slice(*(*[]float32)(fieldPtr), field.RefMode, false)
			return
		case Float64SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			ctx.WriteFloat64Slice(*(*[]float64)(fieldPtr), field.RefMode, false)
			return
		case StringStringMapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			if field.Meta.HasGenerics && field.Serializer != nil {
				field.Serializer.Write(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, value.Field(field.Meta.FieldIndex))
				return
			}
			ctx.WriteStringStringMap(*(*map[string]string)(fieldPtr), field.RefMode, false)
			return
		case StringInt64MapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			if field.Meta.HasGenerics && field.Serializer != nil {
				field.Serializer.Write(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, value.Field(field.Meta.FieldIndex))
				return
			}
			ctx.WriteStringInt64Map(*(*map[string]int64)(fieldPtr), field.RefMode, false)
			return
		case StringInt32MapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			if field.Meta.HasGenerics && field.Serializer != nil {
				field.Serializer.Write(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, value.Field(field.Meta.FieldIndex))
				return
			}
			ctx.WriteStringInt32Map(*(*map[string]int32)(fieldPtr), field.RefMode, false)
			return
		case StringIntMapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			if field.Meta.HasGenerics && field.Serializer != nil {
				field.Serializer.Write(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, value.Field(field.Meta.FieldIndex))
				return
			}
			ctx.WriteStringIntMap(*(*map[string]int)(fieldPtr), field.RefMode, false)
			return
		case StringFloat64MapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			if field.Meta.HasGenerics && field.Serializer != nil {
				field.Serializer.Write(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, value.Field(field.Meta.FieldIndex))
				return
			}
			ctx.WriteStringFloat64Map(*(*map[string]float64)(fieldPtr), field.RefMode, false)
			return
		case StringBoolMapDispatchId:
			// map[string]bool is a regular map in Go - use MAP format
			// Note: fory.Set[T] uses struct{} values and has its own setSerializer
			if field.RefMode == RefModeTracking {
				break
			}
			if field.Meta.HasGenerics && field.Serializer != nil {
				field.Serializer.Write(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, value.Field(field.Meta.FieldIndex))
				return
			}
			ctx.WriteStringBoolMap(*(*map[string]bool)(fieldPtr), field.RefMode, false)
			return
		case IntIntMapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			if field.Meta.HasGenerics && field.Serializer != nil {
				field.Serializer.Write(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, value.Field(field.Meta.FieldIndex))
				return
			}
			ctx.WriteIntIntMap(*(*map[int]int)(fieldPtr), field.RefMode, false)
			return
		case NullableTaggedInt64DispatchId:
			// Nullable tagged INT64: write ref flag, then tagged encoding
			ptr := *(**int64)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteTaggedInt64(*ptr)
			return
		case NullableTaggedUint64DispatchId:
			// Nullable tagged UINT64: write ref flag, then tagged encoding
			ptr := *(**uint64)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteTaggedUint64(*ptr)
			return
		// Nullable fixed-size types
		case NullableBoolDispatchId:
			ptr := *(**bool)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteBool(*ptr)
			return
		case NullableInt8DispatchId:
			ptr := *(**int8)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteInt8(*ptr)
			return
		case NullableUint8DispatchId:
			ptr := *(**uint8)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteUint8(*ptr)
			return
		case NullableInt16DispatchId:
			ptr := *(**int16)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteInt16(*ptr)
			return
		case NullableUint16DispatchId:
			ptr := *(**uint16)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteUint16(*ptr)
			return
		case NullableInt32DispatchId:
			ptr := *(**int32)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteInt32(*ptr)
			return
		case NullableUint32DispatchId:
			ptr := *(**uint32)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteUint32(*ptr)
			return
		case NullableInt64DispatchId:
			ptr := *(**int64)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteInt64(*ptr)
			return
		case NullableUint64DispatchId:
			ptr := *(**uint64)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteUint64(*ptr)
			return
		case NullableFloat32DispatchId:
			ptr := *(**float32)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteFloat32(*ptr)
			return
		case NullableFloat64DispatchId:
			ptr := *(**float64)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteFloat64(*ptr)
			return
		// Nullable varint types
		case NullableVarint32DispatchId:
			ptr := *(**int32)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteVarint32(*ptr)
			return
		case NullableVarUint32DispatchId:
			ptr := *(**uint32)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteVarUint32(*ptr)
			return
		case NullableVarint64DispatchId:
			ptr := *(**int64)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteVarint64(*ptr)
			return
		case NullableVarUint64DispatchId:
			ptr := *(**uint64)(fieldPtr)
			if ptr == nil {
				buf.WriteInt8(NullFlag)
				return
			}
			buf.WriteInt8(NotNullValueFlag)
			buf.WriteVarUint64(*ptr)
			return
		}
	}

	if ptr == nil {
		ctx.SetError(SerializationError("cannot write struct field without addressable value"))
		return
	}

	if DebugOutputEnabled {
		fmt.Printf("[fory-debug] write normal field %s: fieldInfo=%v typeId=%v serializer=%T, buffer writerIndex=%d\n",
			field.Meta.Name, field.Meta.FieldDef, field.Meta.TypeId, field.Serializer, buf.writerIndex)
	}
	// Fall back to serializer for other types
	fieldValue := value.Field(field.Meta.FieldIndex)
	if field.Serializer != nil {
		if writeWithCachedTypeInfo(ctx, field, fieldValue) {
			return
		}
		field.Serializer.Write(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, fieldValue)
	} else {
		ctx.WriteValue(fieldValue, RefModeTracking, true)
	}
}

func writeWithCachedTypeInfo(ctx *WriteContext, field *FieldInfo, fieldValue reflect.Value) bool {
	if field.Serializer == nil || !field.Meta.WriteType || field.Meta.CachedTypeInfo == nil {
		return false
	}
	switch ser := field.Serializer.(type) {
	case *structSerializer:
		ser.writeWithTypeInfo(ctx, field.RefMode, fieldValue, field.Meta.CachedTypeInfo)
		return true
	case *ptrToValueSerializer:
		ser.writeWithTypeInfo(ctx, field.RefMode, fieldValue, field.Meta.CachedTypeInfo)
		return true
	default:
		return false
	}
}

func loadFieldValue[T any](kind FieldKind, fieldPtr unsafe.Pointer, opt optionalInfo) (T, bool) {
	var zero T
	switch kind {
	case FieldKindPointer:
		ptr := *(**T)(fieldPtr)
		if ptr == nil {
			return zero, false
		}
		return *ptr, true
	case FieldKindOptional:
		if !*(*bool)(unsafe.Add(fieldPtr, opt.hasOffset)) {
			return zero, false
		}
		return *(*T)(unsafe.Add(fieldPtr, opt.valueOffset)), true
	default:
		return *(*T)(fieldPtr), true
	}
}

func storeFieldValue[T any](kind FieldKind, fieldPtr unsafe.Pointer, opt optionalInfo, value T) {
	switch kind {
	case FieldKindPointer:
		ptr := *(**T)(fieldPtr)
		if ptr == nil {
			ptr = new(T)
			*(**T)(fieldPtr) = ptr
		}
		*ptr = value
	case FieldKindOptional:
		*(*bool)(unsafe.Add(fieldPtr, opt.hasOffset)) = true
		*(*T)(unsafe.Add(fieldPtr, opt.valueOffset)) = value
	default:
		*(*T)(fieldPtr) = value
	}
}

func clearFieldValue(kind FieldKind, fieldPtr unsafe.Pointer, opt optionalInfo) {
	switch kind {
	case FieldKindPointer:
		*(*unsafe.Pointer)(fieldPtr) = nil
	case FieldKindOptional:
		*(*bool)(unsafe.Add(fieldPtr, opt.hasOffset)) = false
	default:
	}
}

func writeOptionFast(ctx *WriteContext, field *FieldInfo, optPtr unsafe.Pointer) bool {
	buf := ctx.Buffer()
	has := *(*bool)(unsafe.Add(optPtr, field.Meta.OptionalInfo.hasOffset))
	valuePtr := unsafe.Add(optPtr, field.Meta.OptionalInfo.valueOffset)
	switch field.DispatchId {
	case StringDispatchId:
		if field.RefMode != RefModeNone {
			if !has {
				buf.WriteInt8(NullFlag)
				return true
			}
			buf.WriteInt8(NotNullValueFlag)
		} else if !has {
			ctx.WriteString("")
			return true
		}
		if has {
			ctx.WriteString(*(*string)(valuePtr))
		} else {
			ctx.WriteString("")
		}
		return true
	case NullableTaggedInt64DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteTaggedInt64(*(*int64)(valuePtr))
			} else {
				buf.WriteTaggedInt64(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteTaggedInt64(*(*int64)(valuePtr))
		return true
	case NullableTaggedUint64DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteTaggedUint64(*(*uint64)(valuePtr))
			} else {
				buf.WriteTaggedUint64(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteTaggedUint64(*(*uint64)(valuePtr))
		return true
	case NullableBoolDispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteBool(*(*bool)(valuePtr))
			} else {
				buf.WriteBool(false)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteBool(*(*bool)(valuePtr))
		return true
	case NullableInt8DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteInt8(*(*int8)(valuePtr))
			} else {
				buf.WriteInt8(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteInt8(*(*int8)(valuePtr))
		return true
	case NullableUint8DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteUint8(*(*uint8)(valuePtr))
			} else {
				buf.WriteUint8(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteUint8(*(*uint8)(valuePtr))
		return true
	case NullableInt16DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteInt16(*(*int16)(valuePtr))
			} else {
				buf.WriteInt16(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteInt16(*(*int16)(valuePtr))
		return true
	case NullableUint16DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteUint16(*(*uint16)(valuePtr))
			} else {
				buf.WriteUint16(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteUint16(*(*uint16)(valuePtr))
		return true
	case NullableInt32DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteInt32(*(*int32)(valuePtr))
			} else {
				buf.WriteInt32(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteInt32(*(*int32)(valuePtr))
		return true
	case NullableUint32DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteUint32(*(*uint32)(valuePtr))
			} else {
				buf.WriteUint32(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteUint32(*(*uint32)(valuePtr))
		return true
	case NullableInt64DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteInt64(*(*int64)(valuePtr))
			} else {
				buf.WriteInt64(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteInt64(*(*int64)(valuePtr))
		return true
	case NullableUint64DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteUint64(*(*uint64)(valuePtr))
			} else {
				buf.WriteUint64(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteUint64(*(*uint64)(valuePtr))
		return true
	case NullableFloat32DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteFloat32(*(*float32)(valuePtr))
			} else {
				buf.WriteFloat32(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteFloat32(*(*float32)(valuePtr))
		return true
	case NullableFloat64DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteFloat64(*(*float64)(valuePtr))
			} else {
				buf.WriteFloat64(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteFloat64(*(*float64)(valuePtr))
		return true
	case NullableVarint32DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteVarint32(*(*int32)(valuePtr))
			} else {
				buf.WriteVarint32(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteVarint32(*(*int32)(valuePtr))
		return true
	case NullableVarUint32DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteVarUint32(*(*uint32)(valuePtr))
			} else {
				buf.WriteVarUint32(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteVarUint32(*(*uint32)(valuePtr))
		return true
	case NullableVarint64DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteVarint64(*(*int64)(valuePtr))
			} else {
				buf.WriteVarint64(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteVarint64(*(*int64)(valuePtr))
		return true
	case NullableVarUint64DispatchId:
		if field.RefMode == RefModeNone {
			if has {
				buf.WriteVarUint64(*(*uint64)(valuePtr))
			} else {
				buf.WriteVarUint64(0)
			}
			return true
		}
		if !has {
			buf.WriteInt8(NullFlag)
			return true
		}
		buf.WriteInt8(NotNullValueFlag)
		buf.WriteVarUint64(*(*uint64)(valuePtr))
		return true
	case PrimitiveBoolDispatchId:
		if has {
			buf.WriteBool(*(*bool)(valuePtr))
		} else {
			buf.WriteBool(false)
		}
		return true
	case PrimitiveInt8DispatchId:
		if has {
			buf.WriteInt8(*(*int8)(valuePtr))
		} else {
			buf.WriteInt8(0)
		}
		return true
	case PrimitiveUint8DispatchId:
		if has {
			buf.WriteUint8(*(*uint8)(valuePtr))
		} else {
			buf.WriteUint8(0)
		}
		return true
	case PrimitiveInt16DispatchId:
		if has {
			buf.WriteInt16(*(*int16)(valuePtr))
		} else {
			buf.WriteInt16(0)
		}
		return true
	case PrimitiveUint16DispatchId:
		if has {
			buf.WriteUint16(*(*uint16)(valuePtr))
		} else {
			buf.WriteUint16(0)
		}
		return true
	case PrimitiveInt32DispatchId:
		if has {
			buf.WriteInt32(*(*int32)(valuePtr))
		} else {
			buf.WriteInt32(0)
		}
		return true
	case PrimitiveVarint32DispatchId:
		if has {
			buf.WriteVarint32(*(*int32)(valuePtr))
		} else {
			buf.WriteVarint32(0)
		}
		return true
	case PrimitiveInt64DispatchId:
		if has {
			buf.WriteInt64(*(*int64)(valuePtr))
		} else {
			buf.WriteInt64(0)
		}
		return true
	case PrimitiveVarint64DispatchId:
		if has {
			buf.WriteVarint64(*(*int64)(valuePtr))
		} else {
			buf.WriteVarint64(0)
		}
		return true
	case PrimitiveIntDispatchId:
		if has {
			buf.WriteVarint64(int64(*(*int)(valuePtr)))
		} else {
			buf.WriteVarint64(0)
		}
		return true
	case PrimitiveUint32DispatchId:
		if has {
			buf.WriteUint32(*(*uint32)(valuePtr))
		} else {
			buf.WriteUint32(0)
		}
		return true
	case PrimitiveVarUint32DispatchId:
		if has {
			buf.WriteVarUint32(*(*uint32)(valuePtr))
		} else {
			buf.WriteVarUint32(0)
		}
		return true
	case PrimitiveUint64DispatchId:
		if has {
			buf.WriteUint64(*(*uint64)(valuePtr))
		} else {
			buf.WriteUint64(0)
		}
		return true
	case PrimitiveVarUint64DispatchId:
		if has {
			buf.WriteVarUint64(*(*uint64)(valuePtr))
		} else {
			buf.WriteVarUint64(0)
		}
		return true
	case PrimitiveUintDispatchId:
		if has {
			buf.WriteVarUint64(uint64(*(*uint)(valuePtr)))
		} else {
			buf.WriteVarUint64(0)
		}
		return true
	case PrimitiveTaggedInt64DispatchId:
		if has {
			buf.WriteTaggedInt64(*(*int64)(valuePtr))
		} else {
			buf.WriteTaggedInt64(0)
		}
		return true
	case PrimitiveTaggedUint64DispatchId:
		if has {
			buf.WriteTaggedUint64(*(*uint64)(valuePtr))
		} else {
			buf.WriteTaggedUint64(0)
		}
		return true
	case PrimitiveFloat32DispatchId:
		if has {
			buf.WriteFloat32(*(*float32)(valuePtr))
		} else {
			buf.WriteFloat32(0)
		}
		return true
	case PrimitiveFloat64DispatchId:
		if has {
			buf.WriteFloat64(*(*float64)(valuePtr))
		} else {
			buf.WriteFloat64(0)
		}
		return true
	default:
		return false
	}
}

func (s *structSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
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
	if readType {
		if s.type_ != nil {
			serializer := ctx.TypeResolver().ReadTypeInfoForType(buf, s.type_, ctxErr)
			if ctxErr.HasError() {
				return
			}
			if serializer == nil {
				ctx.SetError(DeserializationError("unexpected type id for struct"))
				return
			}
			if structSer, ok := serializer.(*structSerializer); ok && len(structSer.fieldDefs) > 0 {
				structSer.ReadData(ctx, value)
				return
			}
			s.ReadData(ctx, value)
			return
		}
		// Fallback: read type info when expected type is unknown
		typeInfo := ctx.TypeResolver().ReadTypeInfo(buf, ctxErr)
		if ctxErr.HasError() {
			return
		}
		if typeInfo != nil {
			if structSer, ok := typeInfo.Serializer.(*structSerializer); ok && len(structSer.fieldDefs) > 0 {
				structSer.ReadData(ctx, value)
				return
			}
		}
	}
	s.ReadData(ctx, value)
}

func (s *structSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	// typeInfo is already read, don't read it again
	s.Read(ctx, refMode, false, false, value)
}

func (s *structSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	// Early error check - skip all intermediate checks for normal path performance
	if ctx.HasError() {
		return
	}

	// Lazy initialization
	if !s.initialized {
		if err := s.initialize(ctx.TypeResolver()); err != nil {
			ctx.SetError(FromError(err))
			return
		}
	}

	buf := ctx.Buffer()
	if value.Kind() == reflect.Ptr {
		if value.IsNil() {
			value.Set(reflect.New(value.Type().Elem()))
		}
		value = value.Elem()
	}

	// In compatible mode with meta share, struct hash is not written
	if !ctx.Compatible() {
		err := ctx.Err()
		structHash := buf.ReadInt32(err)
		if structHash != s.structHash {
			ctx.SetError(HashMismatchError(structHash, s.structHash, s.type_.String()))
			return
		}
	}

	// Fail fast if value is not addressable - we require unsafe pointer access
	if !value.CanAddr() {
		ctx.SetError(SerializationError("cannot deserialize struct " + s.type_.Name() + " into non-addressable value"))
		return
	}

	// Use ordered reading when TypeDef differs from local type (schema evolution)
	if s.typeDefDiffers {
		s.readFieldsInOrder(ctx, value)
		return
	}

	// ==========================================================================
	// Grouped reading for matching types (optimized path)
	// - Types match, so all fields exist locally (no FieldIndex < 0 checks)
	// - Use UnsafeGet at pre-computed offsets, update reader index once per phase
	// ==========================================================================
	ptr := unsafe.Pointer(value.UnsafeAddr())

	// Phase 1: Fixed-size primitives (inline unsafe reads with endian handling)
	if s.fieldGroup.FixedSize > 0 {
		baseOffset := buf.ReaderIndex()
		data := buf.GetData()

		for _, field := range s.fieldGroup.PrimitiveFixedFields {
			fieldPtr := unsafe.Add(ptr, field.Offset)
			bufOffset := baseOffset + int(field.WriteOffset)
			optInfo := optionalInfo{}
			if field.Kind == FieldKindOptional && field.Meta != nil {
				optInfo = field.Meta.OptionalInfo
			}
			switch field.DispatchId {
			case PrimitiveBoolDispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, data[bufOffset] != 0)
			case PrimitiveInt8DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, int8(data[bufOffset]))
			case PrimitiveUint8DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, data[bufOffset])
			case PrimitiveInt16DispatchId:
				var v int16
				if isLittleEndian {
					v = *(*int16)(unsafe.Pointer(&data[bufOffset]))
				} else {
					v = int16(binary.LittleEndian.Uint16(data[bufOffset:]))
				}
				storeFieldValue(field.Kind, fieldPtr, optInfo, v)
			case PrimitiveUint16DispatchId:
				var v uint16
				if isLittleEndian {
					v = *(*uint16)(unsafe.Pointer(&data[bufOffset]))
				} else {
					v = binary.LittleEndian.Uint16(data[bufOffset:])
				}
				storeFieldValue(field.Kind, fieldPtr, optInfo, v)
			case PrimitiveInt32DispatchId:
				var v int32
				if isLittleEndian {
					v = *(*int32)(unsafe.Pointer(&data[bufOffset]))
				} else {
					v = int32(binary.LittleEndian.Uint32(data[bufOffset:]))
				}
				storeFieldValue(field.Kind, fieldPtr, optInfo, v)
			case PrimitiveUint32DispatchId:
				var v uint32
				if isLittleEndian {
					v = *(*uint32)(unsafe.Pointer(&data[bufOffset]))
				} else {
					v = binary.LittleEndian.Uint32(data[bufOffset:])
				}
				storeFieldValue(field.Kind, fieldPtr, optInfo, v)
			case PrimitiveInt64DispatchId:
				var v int64
				if isLittleEndian {
					v = *(*int64)(unsafe.Pointer(&data[bufOffset]))
				} else {
					v = int64(binary.LittleEndian.Uint64(data[bufOffset:]))
				}
				storeFieldValue(field.Kind, fieldPtr, optInfo, v)
			case PrimitiveUint64DispatchId:
				var v uint64
				if isLittleEndian {
					v = *(*uint64)(unsafe.Pointer(&data[bufOffset]))
				} else {
					v = binary.LittleEndian.Uint64(data[bufOffset:])
				}
				storeFieldValue(field.Kind, fieldPtr, optInfo, v)
			case PrimitiveFloat32DispatchId:
				var v float32
				if isLittleEndian {
					v = *(*float32)(unsafe.Pointer(&data[bufOffset]))
				} else {
					v = math.Float32frombits(binary.LittleEndian.Uint32(data[bufOffset:]))
				}
				storeFieldValue(field.Kind, fieldPtr, optInfo, v)
			case PrimitiveFloat64DispatchId:
				var v float64
				if isLittleEndian {
					v = *(*float64)(unsafe.Pointer(&data[bufOffset]))
				} else {
					v = math.Float64frombits(binary.LittleEndian.Uint64(data[bufOffset:]))
				}
				storeFieldValue(field.Kind, fieldPtr, optInfo, v)
			case PrimitiveFloat16DispatchId:
				var v uint16
				if isLittleEndian {
					v = *(*uint16)(unsafe.Pointer(&data[bufOffset]))
				} else {
					v = binary.LittleEndian.Uint16(data[bufOffset:])
				}
				// Float16 is underlying uint16, so we can store it directly
				storeFieldValue(field.Kind, fieldPtr, optInfo, v)

			}
		}
		// Update reader index ONCE after all fixed fields
		buf.SetReaderIndex(baseOffset + s.fieldGroup.FixedSize)
	}

	// Phase 2: Varint primitives (must read sequentially - variable length)
	// Note: For tagged int64/uint64, we can't use unsafe reads because they need bounds checking
	if len(s.fieldGroup.PrimitiveVarintFields) > 0 {
		err := ctx.Err()
		if buf.remaining() >= s.fieldGroup.MaxVarintSize {
			for _, field := range s.fieldGroup.PrimitiveVarintFields {
				fieldPtr := unsafe.Add(ptr, field.Offset)
				optInfo := optionalInfo{}
				if field.Kind == FieldKindOptional && field.Meta != nil {
					optInfo = field.Meta.OptionalInfo
				}
				switch field.DispatchId {
				case PrimitiveVarint32DispatchId:
					storeFieldValue(field.Kind, fieldPtr, optInfo, buf.UnsafeReadVarint32())
				case PrimitiveVarint64DispatchId:
					storeFieldValue(field.Kind, fieldPtr, optInfo, buf.UnsafeReadVarint64())
				case PrimitiveIntDispatchId:
					storeFieldValue(field.Kind, fieldPtr, optInfo, int(buf.UnsafeReadVarint64()))
				case PrimitiveVarUint32DispatchId:
					storeFieldValue(field.Kind, fieldPtr, optInfo, buf.UnsafeReadVarUint32())
				case PrimitiveVarUint64DispatchId:
					storeFieldValue(field.Kind, fieldPtr, optInfo, buf.UnsafeReadVarUint64())
				case PrimitiveUintDispatchId:
					storeFieldValue(field.Kind, fieldPtr, optInfo, uint(buf.UnsafeReadVarUint64()))
				case PrimitiveTaggedInt64DispatchId:
					// Tagged INT64: use buffer's tagged decoding (4 bytes for small, 9 for large)
					storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadTaggedInt64(err))
				case PrimitiveTaggedUint64DispatchId:
					// Tagged UINT64: use buffer's tagged decoding (4 bytes for small, 9 for large)
					storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadTaggedUint64(err))
				}
			}
		} else {
			for _, field := range s.fieldGroup.PrimitiveVarintFields {
				fieldPtr := unsafe.Add(ptr, field.Offset)
				optInfo := optionalInfo{}
				if field.Kind == FieldKindOptional && field.Meta != nil {
					optInfo = field.Meta.OptionalInfo
				}
				switch field.DispatchId {
				case PrimitiveVarint32DispatchId:
					storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVarint32(err))
				case PrimitiveVarint64DispatchId:
					storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVarint64(err))
				case PrimitiveIntDispatchId:
					storeFieldValue(field.Kind, fieldPtr, optInfo, int(buf.ReadVarint64(err)))
				case PrimitiveVarUint32DispatchId:
					storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVarUint32(err))
				case PrimitiveVarUint64DispatchId:
					storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVarUint64(err))
				case PrimitiveUintDispatchId:
					storeFieldValue(field.Kind, fieldPtr, optInfo, uint(buf.ReadVarUint64(err)))
				case PrimitiveTaggedInt64DispatchId:
					// Tagged INT64: use buffer's tagged decoding (4 bytes for small, 9 for large)
					storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadTaggedInt64(err))
				case PrimitiveTaggedUint64DispatchId:
					// Tagged UINT64: use buffer's tagged decoding (4 bytes for small, 9 for large)
					storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadTaggedUint64(err))
				}
			}
		}
	}

	// Phase 3: Remaining fields (strings, slices, maps, structs, enums)
	// No intermediate error checks - trade error path performance for normal path
	for i := range s.fieldGroup.RemainingFields {
		s.readRemainingField(ctx, ptr, &s.fieldGroup.RemainingFields[i], value)
	}
	if ctx.HasError() {
		ctx.Err().stack = append(ctx.Err().stack, fmt.Sprintf(" [struct %s]", s.name))
	}
}

// readRemainingField reads a non-primitive field (string, slice, map, struct, enum)
func (s *structSerializer) readRemainingField(ctx *ReadContext, ptr unsafe.Pointer, field *FieldInfo, value reflect.Value) {
	buf := ctx.Buffer()
	if DebugOutputEnabled {
		fmt.Printf("[fory-debug] read remaining field %s: fieldInfo=%v typeId=%v, buffer readerIndex=%d\n",
			field.Meta.Name, field.Meta.FieldDef, field.Meta.TypeId, buf.readerIndex)
	}
	ctxErr := ctx.Err()
	if field.Kind == FieldKindOptional {
		if ptr != nil {
			if readOptionFast(ctx, field, unsafe.Add(ptr, field.Offset)) {
				return
			}
		}
		fieldValue := value.Field(field.Meta.FieldIndex)
		if field.Serializer != nil {
			field.Serializer.Read(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, fieldValue)
		} else {
			ctx.ReadValue(fieldValue, RefModeTracking, true)
		}
		return
	}
	// Fast path dispatch using pre-computed DispatchId
	// ptr must be valid (addressable value)
	if ptr != nil {
		fieldPtr := unsafe.Add(ptr, field.Offset)
		switch field.DispatchId {
		case StringDispatchId:
			// Check isPtr first for better branch prediction
			if field.Kind != FieldKindPointer {
				// Non-pointer string: no ref tracking needed in fast path
				if field.RefMode == RefModeNone {
					*(*string)(fieldPtr) = ctx.ReadString()
				} else {
					// RefModeNullOnly or RefModeTracking: read NotNull flag then string
					refFlag := buf.ReadInt8(ctxErr)
					if refFlag == NullFlag {
						*(*string)(fieldPtr) = ""
					} else {
						*(*string)(fieldPtr) = ctx.ReadString()
					}
				}
				return
			}
			// Pointer to string: can be nil, may need ref tracking
			if field.RefMode == RefModeTracking {
				break // Fall through to slow path for ref tracking
			}
			if field.RefMode == RefModeNullOnly {
				refFlag := buf.ReadInt8(ctxErr)
				if refFlag == NullFlag {
					// Leave as nil
					return
				}
			}
			// Allocate new string and store pointer
			str := ctx.ReadString()
			sp := new(string)
			*sp = str
			*(**string)(fieldPtr) = sp
			return
		case EnumDispatchId:
			// Enums don't track refs - always use fast path
			readEnumFieldUnsafe(ctx, field, fieldPtr)
			return
		case StringSliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]string)(fieldPtr) = ctx.ReadStringSlice(field.RefMode, false)
			return
		case BoolSliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]bool)(fieldPtr) = ctx.ReadBoolSlice(field.RefMode, false)
			return
		case Int8SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]int8)(fieldPtr) = ctx.ReadInt8Slice(field.RefMode, false)
			return
		case ByteSliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]byte)(fieldPtr) = ctx.ReadByteSlice(field.RefMode, false)
			return
		case Int16SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]int16)(fieldPtr) = ctx.ReadInt16Slice(field.RefMode, false)
			return
		case Int32SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]int32)(fieldPtr) = ctx.ReadInt32Slice(field.RefMode, false)
			return
		case Int64SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]int64)(fieldPtr) = ctx.ReadInt64Slice(field.RefMode, false)
			return
		case Uint16SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]uint16)(fieldPtr) = ctx.ReadUint16Slice(field.RefMode, false)
			return
		case Uint32SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]uint32)(fieldPtr) = ctx.ReadUint32Slice(field.RefMode, false)
			return
		case Uint64SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]uint64)(fieldPtr) = ctx.ReadUint64Slice(field.RefMode, false)
			return
		case IntSliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]int)(fieldPtr) = ctx.ReadIntSlice(field.RefMode, false)
			return
		case UintSliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]uint)(fieldPtr) = ctx.ReadUintSlice(field.RefMode, false)
			return
		case Float32SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]float32)(fieldPtr) = ctx.ReadFloat32Slice(field.RefMode, false)
			return
		case Float64SliceDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*[]float64)(fieldPtr) = ctx.ReadFloat64Slice(field.RefMode, false)
			return
		case StringStringMapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*map[string]string)(fieldPtr) = ctx.ReadStringStringMap(field.RefMode, false)
			return
		case StringInt64MapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*map[string]int64)(fieldPtr) = ctx.ReadStringInt64Map(field.RefMode, false)
			return
		case StringInt32MapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*map[string]int32)(fieldPtr) = ctx.ReadStringInt32Map(field.RefMode, false)
			return
		case StringIntMapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*map[string]int)(fieldPtr) = ctx.ReadStringIntMap(field.RefMode, false)
			return
		case StringFloat64MapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*map[string]float64)(fieldPtr) = ctx.ReadStringFloat64Map(field.RefMode, false)
			return
		case StringBoolMapDispatchId:
			// map[string]bool is a regular map in Go - use MAP format
			// Note: fory.Set[T] uses struct{} values and has its own setSerializer
			if field.RefMode == RefModeTracking {
				break
			}
			*(*map[string]bool)(fieldPtr) = ctx.ReadStringBoolMap(field.RefMode, false)
			return
		case IntIntMapDispatchId:
			if field.RefMode == RefModeTracking {
				break
			}
			*(*map[int]int)(fieldPtr) = ctx.ReadIntIntMap(field.RefMode, false)
			return
		case NullableTaggedInt64DispatchId:
			// Nullable tagged INT64: read ref flag, then tagged encoding
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				// Leave pointer as nil
				return
			}
			// Allocate new int64 and store pointer
			v := new(int64)
			*v = buf.ReadTaggedInt64(ctxErr)
			*(**int64)(fieldPtr) = v
			return
		case NullableTaggedUint64DispatchId:
			// Nullable tagged UINT64: read ref flag, then tagged encoding
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				// Leave pointer as nil
				return
			}
			// Allocate new uint64 and store pointer
			v := new(uint64)
			*v = buf.ReadTaggedUint64(ctxErr)
			*(**uint64)(fieldPtr) = v
			return
		// Nullable fixed-size types
		case NullableBoolDispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(bool)
			*v = buf.ReadBool(ctxErr)
			*(**bool)(fieldPtr) = v
			return
		case NullableInt8DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(int8)
			*v = buf.ReadInt8(ctxErr)
			*(**int8)(fieldPtr) = v
			return
		case NullableUint8DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(uint8)
			*v = buf.ReadUint8(ctxErr)
			*(**uint8)(fieldPtr) = v
			return
		case NullableInt16DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(int16)
			*v = buf.ReadInt16(ctxErr)
			*(**int16)(fieldPtr) = v
			return
		case NullableUint16DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(uint16)
			*v = buf.ReadUint16(ctxErr)
			*(**uint16)(fieldPtr) = v
			return
		case NullableInt32DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(int32)
			*v = buf.ReadInt32(ctxErr)
			*(**int32)(fieldPtr) = v
			return
		case NullableUint32DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(uint32)
			*v = buf.ReadUint32(ctxErr)
			*(**uint32)(fieldPtr) = v
			return
		case NullableInt64DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(int64)
			*v = buf.ReadInt64(ctxErr)
			*(**int64)(fieldPtr) = v
			return
		case NullableUint64DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(uint64)
			*v = buf.ReadUint64(ctxErr)
			*(**uint64)(fieldPtr) = v
			return
		case NullableFloat32DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(float32)
			*v = buf.ReadFloat32(ctxErr)
			*(**float32)(fieldPtr) = v
			return
		case NullableFloat64DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(float64)
			*v = buf.ReadFloat64(ctxErr)
			*(**float64)(fieldPtr) = v
			return
		// Nullable varint types
		case NullableVarint32DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(int32)
			*v = buf.ReadVarint32(ctxErr)
			*(**int32)(fieldPtr) = v
			return
		case NullableVarUint32DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(uint32)
			*v = buf.ReadVarUint32(ctxErr)
			*(**uint32)(fieldPtr) = v
			return
		case NullableVarint64DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(int64)
			*v = buf.ReadVarint64(ctxErr)
			*(**int64)(fieldPtr) = v
			return
		case NullableVarUint64DispatchId:
			refFlag := buf.ReadInt8(ctxErr)
			if refFlag == NullFlag {
				return
			}
			v := new(uint64)
			*v = buf.ReadVarUint64(ctxErr)
			*(**uint64)(fieldPtr) = v
			return
		}
	}
	// Slow path for RefModeTracking cases that break from the switch above
	fieldValue := value.Field(field.Meta.FieldIndex)
	if field.Serializer != nil {
		field.Serializer.Read(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, fieldValue)
	} else {
		ctx.ReadValue(fieldValue, RefModeTracking, true)
	}
}

func readOptionFast(ctx *ReadContext, field *FieldInfo, optPtr unsafe.Pointer) bool {
	buf := ctx.Buffer()
	err := ctx.Err()
	hasPtr := (*bool)(unsafe.Add(optPtr, field.Meta.OptionalInfo.hasOffset))
	valuePtr := unsafe.Add(optPtr, field.Meta.OptionalInfo.valueOffset)
	switch field.DispatchId {
	case StringDispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*string)(valuePtr) = ""
				return true
			}
		}
		*hasPtr = true
		*(*string)(valuePtr) = ctx.ReadString()
		return true
	case NullableTaggedInt64DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*int64)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*int64)(valuePtr) = buf.ReadTaggedInt64(err)
		return true
	case NullableTaggedUint64DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*uint64)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*uint64)(valuePtr) = buf.ReadTaggedUint64(err)
		return true
	case NullableBoolDispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*bool)(valuePtr) = false
				return true
			}
		}
		*hasPtr = true
		*(*bool)(valuePtr) = buf.ReadBool(err)
		return true
	case NullableInt8DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*int8)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*int8)(valuePtr) = buf.ReadInt8(err)
		return true
	case NullableUint8DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*uint8)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*uint8)(valuePtr) = buf.ReadUint8(err)
		return true
	case NullableInt16DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*int16)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*int16)(valuePtr) = buf.ReadInt16(err)
		return true
	case NullableUint16DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*uint16)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*uint16)(valuePtr) = buf.ReadUint16(err)
		return true
	case NullableInt32DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*int32)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*int32)(valuePtr) = buf.ReadInt32(err)
		return true
	case NullableUint32DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*uint32)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*uint32)(valuePtr) = buf.ReadUint32(err)
		return true
	case NullableInt64DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*int64)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*int64)(valuePtr) = buf.ReadInt64(err)
		return true
	case NullableUint64DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*uint64)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*uint64)(valuePtr) = buf.ReadUint64(err)
		return true
	case NullableFloat32DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*float32)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*float32)(valuePtr) = buf.ReadFloat32(err)
		return true
	case NullableFloat64DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*float64)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*float64)(valuePtr) = buf.ReadFloat64(err)
		return true
	case NullableVarint32DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*int32)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*int32)(valuePtr) = buf.ReadVarint32(err)
		return true
	case NullableVarUint32DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*uint32)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*uint32)(valuePtr) = buf.ReadVarUint32(err)
		return true
	case NullableVarint64DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*int64)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*int64)(valuePtr) = buf.ReadVarint64(err)
		return true
	case NullableVarUint64DispatchId:
		if field.RefMode != RefModeNone {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				*hasPtr = false
				*(*uint64)(valuePtr) = 0
				return true
			}
		}
		*hasPtr = true
		*(*uint64)(valuePtr) = buf.ReadVarUint64(err)
		return true
	case PrimitiveBoolDispatchId:
		*hasPtr = true
		*(*bool)(valuePtr) = buf.ReadBool(err)
		return true
	case PrimitiveInt8DispatchId:
		*hasPtr = true
		*(*int8)(valuePtr) = buf.ReadInt8(err)
		return true
	case PrimitiveUint8DispatchId:
		*hasPtr = true
		*(*uint8)(valuePtr) = buf.ReadUint8(err)
		return true
	case PrimitiveInt16DispatchId:
		*hasPtr = true
		*(*int16)(valuePtr) = buf.ReadInt16(err)
		return true
	case PrimitiveUint16DispatchId:
		*hasPtr = true
		*(*uint16)(valuePtr) = buf.ReadUint16(err)
		return true
	case PrimitiveInt32DispatchId:
		*hasPtr = true
		*(*int32)(valuePtr) = buf.ReadInt32(err)
		return true
	case PrimitiveVarint32DispatchId:
		*hasPtr = true
		*(*int32)(valuePtr) = buf.ReadVarint32(err)
		return true
	case PrimitiveInt64DispatchId:
		*hasPtr = true
		*(*int64)(valuePtr) = buf.ReadInt64(err)
		return true
	case PrimitiveVarint64DispatchId:
		*hasPtr = true
		*(*int64)(valuePtr) = buf.ReadVarint64(err)
		return true
	case PrimitiveIntDispatchId:
		*hasPtr = true
		*(*int)(valuePtr) = int(buf.ReadVarint64(err))
		return true
	case PrimitiveUint32DispatchId:
		*hasPtr = true
		*(*uint32)(valuePtr) = buf.ReadUint32(err)
		return true
	case PrimitiveVarUint32DispatchId:
		*hasPtr = true
		*(*uint32)(valuePtr) = buf.ReadVarUint32(err)
		return true
	case PrimitiveUint64DispatchId:
		*hasPtr = true
		*(*uint64)(valuePtr) = buf.ReadUint64(err)
		return true
	case PrimitiveVarUint64DispatchId:
		*hasPtr = true
		*(*uint64)(valuePtr) = buf.ReadVarUint64(err)
		return true
	case PrimitiveUintDispatchId:
		*hasPtr = true
		*(*uint)(valuePtr) = uint(buf.ReadVarUint64(err))
		return true
	case PrimitiveTaggedInt64DispatchId:
		*hasPtr = true
		*(*int64)(valuePtr) = buf.ReadTaggedInt64(err)
		return true
	case PrimitiveTaggedUint64DispatchId:
		*hasPtr = true
		*(*uint64)(valuePtr) = buf.ReadTaggedUint64(err)
		return true
	case PrimitiveFloat32DispatchId:
		*hasPtr = true
		*(*float32)(valuePtr) = buf.ReadFloat32(err)
		return true
	case PrimitiveFloat64DispatchId:
		*hasPtr = true
		*(*float64)(valuePtr) = buf.ReadFloat64(err)
		return true
	default:
		return false
	}
}

// readFieldsInOrder reads fields in the order they appear in s.fields (TypeDef order)
// This is used in compatible mode where Java writes fields in TypeDef order
// Precondition: value.CanAddr() must be true (checked by caller)
func (s *structSerializer) readFieldsInOrder(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	ptr := unsafe.Pointer(value.UnsafeAddr())
	err := ctx.Err()
	readField := func(field *FieldInfo) {
		if field.Meta.FieldIndex < 0 {
			s.skipField(ctx, field)
			return
		}

		// Fast path for fixed-size primitive types (no ref flag from remote schema)
		if isFixedSizePrimitive(field.DispatchId) {
			fieldPtr := unsafe.Add(ptr, field.Offset)
			optInfo := optionalInfo{}
			if field.Kind == FieldKindOptional {
				optInfo = field.Meta.OptionalInfo
			}
			switch field.DispatchId {
			case PrimitiveBoolDispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadBool(err))
			case PrimitiveInt8DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadInt8(err))
			case PrimitiveUint8DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, uint8(buf.ReadInt8(err)))
			case PrimitiveInt16DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadInt16(err))
			case PrimitiveUint16DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadUint16(err))
			case PrimitiveInt32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadInt32(err))
			case PrimitiveUint32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadUint32(err))
			case PrimitiveInt64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadInt64(err))
			case PrimitiveUint64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadUint64(err))
			case PrimitiveFloat32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadFloat32(err))
			case PrimitiveFloat64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadFloat64(err))
			}
			return
		}

		// Fast path for varint primitive types (no ref flag from remote schema)
		if isVarintPrimitive(field.DispatchId) && !fieldHasNonPrimitiveSerializer(field) {
			fieldPtr := unsafe.Add(ptr, field.Offset)
			optInfo := optionalInfo{}
			if field.Kind == FieldKindOptional {
				optInfo = field.Meta.OptionalInfo
			}
			switch field.DispatchId {
			case PrimitiveVarint32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVarint32(err))
			case PrimitiveVarint64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVarint64(err))
			case PrimitiveVarUint32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVarUint32(err))
			case PrimitiveVarUint64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVarUint64(err))
			case PrimitiveTaggedInt64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadTaggedInt64(err))
			case PrimitiveTaggedUint64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadTaggedUint64(err))
			case PrimitiveIntDispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, int(buf.ReadVarint64(err)))
			case PrimitiveUintDispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, uint(buf.ReadVarUint64(err)))
			}
			return
		}

		fieldPtr := unsafe.Add(ptr, field.Offset)
		optInfo := optionalInfo{}
		if field.Kind == FieldKindOptional {
			optInfo = field.Meta.OptionalInfo
		}

		// Handle nullable fixed-size primitives (read ref flag + fixed bytes)
		// These have Nullable=true but use fixed encoding, not varint
		if isNullableFixedSizePrimitive(field.DispatchId) {
			refFlag := buf.ReadInt8(err)
			if refFlag == NullFlag {
				clearFieldValue(field.Kind, fieldPtr, optInfo)
				return
			}
			// Read fixed-size value based on dispatch ID
			switch field.DispatchId {
			case NullableBoolDispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadBool(err))
			case NullableInt8DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadInt8(err))
			case NullableUint8DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, uint8(buf.ReadInt8(err)))
			case NullableInt16DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadInt16(err))
			case NullableUint16DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadUint16(err))
			case NullableInt32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadInt32(err))
			case NullableUint32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadUint32(err))
			case NullableInt64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadInt64(err))
			case NullableUint64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadUint64(err))
			case NullableFloat32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadFloat32(err))
			case NullableFloat64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadFloat64(err))
			}
			return
		}

		// Handle nullable varint primitives (read ref flag + varint)
		if isNullableVarintPrimitive(field.DispatchId) {
			refFlag := buf.ReadInt8(err)
			if refFlag == NullFlag {
				clearFieldValue(field.Kind, fieldPtr, optInfo)
				return
			}
			// Read varint value based on dispatch ID
			switch field.DispatchId {
			case NullableVarint32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVarint32(err))
			case NullableVarint64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVarint64(err))
			case NullableVarUint32DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVarUint32(err))
			case NullableVarUint64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadVarUint64(err))
			case NullableTaggedInt64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadTaggedInt64(err))
			case NullableTaggedUint64DispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, buf.ReadTaggedUint64(err))
			case NullableIntDispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, int(buf.ReadVarint64(err)))
			case NullableUintDispatchId:
				storeFieldValue(field.Kind, fieldPtr, optInfo, uint(buf.ReadVarUint64(err)))
			}
			return
		}
		if isEnumField(field) {
			readEnumFieldUnsafe(ctx, field, fieldPtr)
			return
		}

		// Slow path for non-primitives (all need ref flag per xlang spec)
		fieldValue := value.Field(field.Meta.FieldIndex)
		if field.Serializer != nil {
			// Use pre-computed RefMode and WriteType from field initialization
			field.Serializer.Read(ctx, field.RefMode, field.Meta.WriteType, field.Meta.HasGenerics, fieldValue)
		} else {
			ctx.ReadValue(fieldValue, RefModeTracking, true)
		}
	}

	for i := range s.fields {
		field := &s.fields[i]
		readField(field)
		if ctx.HasError() {
			return
		}
	}
}

// skipField skips a field that doesn't exist or is incompatible
// Uses context error state for deferred error checking.
func (s *structSerializer) skipField(ctx *ReadContext, field *FieldInfo) {
	if field.Meta.FieldDef.name != "" || field.Meta.FieldDef.tagID >= 0 {
		if DebugOutputEnabled {
			fmt.Printf("[Go][fory-debug] skipField name=%s typeId=%d fieldType=%s\n",
				field.Meta.FieldDef.name,
				field.Meta.FieldDef.fieldType.TypeId(),
				fieldTypeToString(field.Meta.FieldDef.fieldType))
		}
		fieldDefIsStructType := isStructFieldType(field.Meta.FieldDef.fieldType)
		// Use FieldDef's trackingRef and nullable to determine if ref flag was written by Java
		// Java writes ref flag based on its FieldDef, not Go's field type
		readRefFlag := field.Meta.FieldDef.trackingRef || field.Meta.FieldDef.nullable
		SkipFieldValueWithTypeFlag(ctx, field.Meta.FieldDef, readRefFlag, ctx.Compatible() && fieldDefIsStructType)
		return
	}
	// No FieldDef available, read into temp value
	tempValue := reflect.New(field.Meta.Type).Elem()
	if field.Serializer != nil {
		readType := ctx.Compatible() && isStructField(field.Meta.Type)
		refMode := RefModeNone
		if field.Meta.Nullable {
			refMode = RefModeTracking
		}
		field.Serializer.Read(ctx, refMode, readType, false, tempValue)
	} else {
		ctx.ReadValue(tempValue, RefModeTracking, true)
	}
}

// writeEnumField writes an enum field respecting the field's RefMode.
// Java writes enum ordinals as unsigned VarUint32Small7, not signed zigzag.
// RefMode determines whether null flag is written, regardless of whether the local type is a pointer.
// This is important for compatible mode where remote TypeDef's nullable flag controls the wire format.
func writeEnumField(ctx *WriteContext, field *FieldInfo, fieldValue reflect.Value) {
	buf := ctx.Buffer()
	isPointer := field.Kind == FieldKindPointer

	// Write null flag based on RefMode only (not based on whether local type is pointer)
	if field.RefMode != RefModeNone {
		if isPointer && fieldValue.IsNil() {
			buf.WriteInt8(NullFlag)
			return
		}
		buf.WriteInt8(NotNullValueFlag)
	}

	// Get the actual value to serialize
	targetValue := fieldValue
	if isPointer {
		if fieldValue.IsNil() {
			// RefModeNone but nil pointer - this is a protocol error in schema-consistent mode
			// Write zero value as fallback
			targetValue = reflect.Zero(field.Meta.Type.Elem())
		} else {
			targetValue = fieldValue.Elem()
		}
	}
	// For pointer enum fields, the serializer is ptrToValueSerializer wrapping enumSerializer.
	// We need to call the inner enumSerializer directly with the dereferenced value.
	if ptrSer, ok := field.Serializer.(*ptrToValueSerializer); ok {
		ptrSer.valueSerializer.WriteData(ctx, targetValue)
	} else {
		field.Serializer.WriteData(ctx, targetValue)
	}
}

func setEnumValue(ctx *ReadContext, ptr unsafe.Pointer, kind reflect.Kind, ordinal uint32) bool {
	switch kind {
	case reflect.Int:
		*(*int)(ptr) = int(ordinal)
	case reflect.Int8:
		*(*int8)(ptr) = int8(ordinal)
	case reflect.Int16:
		*(*int16)(ptr) = int16(ordinal)
	case reflect.Int32:
		*(*int32)(ptr) = int32(ordinal)
	case reflect.Int64:
		*(*int64)(ptr) = int64(ordinal)
	case reflect.Uint:
		*(*uint)(ptr) = uint(ordinal)
	case reflect.Uint8:
		*(*uint8)(ptr) = uint8(ordinal)
	case reflect.Uint16:
		*(*uint16)(ptr) = uint16(ordinal)
	case reflect.Uint32:
		*(*uint32)(ptr) = ordinal
	case reflect.Uint64:
		*(*uint64)(ptr) = uint64(ordinal)
	default:
		ctx.SetError(DeserializationErrorf("enum serializer: unsupported kind %v", kind))
		return false
	}
	return true
}

// readEnumFieldUnsafe reads an enum field respecting the field's RefMode.
// RefMode determines whether null flag is read, regardless of whether the local type is a pointer.
// This is important for compatible mode where remote TypeDef's nullable flag controls the wire format.
// Uses context error state for deferred error checking.
func readEnumFieldUnsafe(ctx *ReadContext, field *FieldInfo, fieldPtr unsafe.Pointer) {
	buf := ctx.Buffer()
	isPointer := field.Kind == FieldKindPointer

	// Read null flag based on RefMode only (not based on whether local type is pointer)
	if field.RefMode != RefModeNone {
		nullFlag := buf.ReadInt8(ctx.Err())
		if nullFlag == NullFlag {
			if isPointer {
				*(*unsafe.Pointer)(fieldPtr) = nil
			} else {
				setEnumValue(ctx, fieldPtr, field.Meta.Type.Kind(), 0)
			}
			return
		}
	}

	ordinal := buf.ReadVarUint32Small7(ctx.Err())
	if ctx.HasError() {
		return
	}

	if isPointer {
		elemType := field.Meta.Type.Elem()
		newVal := reflect.New(elemType)
		elemPtr := unsafe.Pointer(newVal.Pointer())
		if !setEnumValue(ctx, elemPtr, elemType.Kind(), ordinal) {
			return
		}
		*(*unsafe.Pointer)(fieldPtr) = elemPtr
		return
	}

	setEnumValue(ctx, fieldPtr, field.Meta.Type.Kind(), ordinal)
}

// skipStructSerializer is a serializer that skips unknown struct data
// It reads and discards field data based on fieldDefs from remote TypeDef
type skipStructSerializer struct {
	fieldDefs []FieldDef
}

func (s *skipStructSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	ctx.SetError(SerializationError("skipStructSerializer does not support WriteData - unknown struct type"))
}

func (s *skipStructSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	ctx.SetError(SerializationError("skipStructSerializer does not support Write - unknown struct type"))
}

func (s *skipStructSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	// Skip all fields based on fieldDefs from remote TypeDef
	for _, fieldDef := range s.fieldDefs {
		isStructType := isStructFieldType(fieldDef.fieldType)
		// Use trackingRef from FieldDef for ref flag decision
		SkipFieldValueWithTypeFlag(ctx, fieldDef, fieldDef.trackingRef, ctx.Compatible() && isStructType)
		if ctx.HasError() {
			return
		}
	}
}

func (s *skipStructSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
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
			// Reference found, nothing to skip
			return
		}
	case RefModeNullOnly:
		flag := buf.ReadInt8(ctxErr)
		if flag == NullFlag {
			return
		}
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s *skipStructSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	// typeInfo is already read, don't read it again - just skip data
	s.Read(ctx, refMode, false, false, value)
}
