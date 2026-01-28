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

// UnionGetter exposes union case metadata for generic serialization.
// Generated union types implement this method.
type UnionGetter interface {
	ForyUnionGet() (uint32, any)
}

// UnionSetter allows setting union case data during deserialization.
// Generated union types implement this method on pointer receivers.
type UnionSetter interface {
	ForyUnionSet(caseId uint32, value any)
}

// UnionCase describes a single union alternative.
type UnionCase struct {
	ID     uint32
	Type   reflect.Type
	TypeID TypeId
}

type unionCaseInfo struct {
	id            uint32
	type_         reflect.Type
	typeID        TypeId
	serializer    Serializer
	needsOverride bool
}

// UnionSerializer is a generic serializer for generated Go unions.
// It relies on union case metadata provided at registration time.
type UnionSerializer struct {
	cases       []unionCaseInfo
	caseByID    map[uint32]*unionCaseInfo
	initialized bool
	initErr     error
}

// NewUnionSerializer creates a generic union serializer for the provided cases.
func NewUnionSerializer(cases ...UnionCase) *UnionSerializer {
	caseInfos := make([]unionCaseInfo, len(cases))
	caseByID := make(map[uint32]*unionCaseInfo, len(cases))
	var initErr error
	for i, c := range cases {
		if c.Type == nil {
			initErr = fmt.Errorf("union case %d has nil type", c.ID)
			continue
		}
		caseInfos[i] = unionCaseInfo{
			id:     c.ID,
			type_:  c.Type,
			typeID: c.TypeID,
		}
		if _, ok := caseByID[c.ID]; ok && initErr == nil {
			initErr = fmt.Errorf("duplicate union case id %d", c.ID)
		}
		caseByID[c.ID] = &caseInfos[i]
	}
	return &UnionSerializer{
		cases:    caseInfos,
		caseByID: caseByID,
		initErr:  initErr,
	}
}

func (s *UnionSerializer) initialize(typeResolver *TypeResolver) error {
	if s.initialized {
		return s.initErr
	}
	if s.initErr != nil {
		return s.initErr
	}
	for i := range s.cases {
		info := &s.cases[i]
		serializer, err := typeResolver.getSerializerByType(info.type_, false)
		if err != nil {
			return err
		}
		info.serializer = serializer
		defaultTypeId := typeResolver.getTypeIdByType(info.type_)
		if defaultTypeId == 0 {
			base := info.type_
			if base.Kind() == reflect.Ptr {
				base = base.Elem()
			}
			defaultTypeId = typeIdFromKind(base)
		}
		info.needsOverride = needsUnionEncodingOverride(info.type_, info.typeID, defaultTypeId)
	}
	s.initialized = true
	return nil
}

// Write is the entry point for union serialization.
func (s *UnionSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics
	switch refMode {
	case RefModeTracking:
		if !value.IsValid() || (value.Kind() == reflect.Ptr && value.IsNil()) {
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
		if !value.IsValid() || (value.Kind() == reflect.Ptr && value.IsNil()) {
			ctx.Buffer().WriteInt8(NullFlag)
			return
		}
		ctx.Buffer().WriteInt8(NotNullValueFlag)
	}
	if writeType {
		typeInfo, err := ctx.TypeResolver().GetTypeInfo(value, true)
		if err != nil {
			ctx.SetError(SerializationErrorf("failed to get type info for union: %v", err))
			return
		}
		ctx.TypeResolver().WriteTypeInfo(ctx.Buffer(), typeInfo, ctx.Err())
	}
	s.WriteData(ctx, value)
}

// WriteData serializes union payload (case_id + case_value).
func (s *UnionSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	if ctx.HasError() {
		return
	}
	if err := s.initialize(ctx.TypeResolver()); err != nil {
		ctx.SetError(SerializationErrorf("union serializer init failed: %v", err))
		return
	}

	caseID, caseValue, err := getUnionCaseValue(value)
	if err != nil {
		ctx.SetError(SerializationErrorf("union value access failed: %v", err))
		return
	}
	info, ok := s.caseByID[caseID]
	if !ok {
		ctx.SetError(SerializationErrorf("unknown union case id: %d", caseID))
		return
	}

	ctx.Buffer().WriteVaruint32(uint32(caseID))
	if info.needsOverride {
		writeUnionOverrideValue(ctx, info, reflect.ValueOf(caseValue))
	} else {
		ctx.WriteValue(reflect.ValueOf(caseValue), RefModeTracking, true)
	}
}

// Read is the entry point for union deserialization.
func (s *UnionSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	_ = hasGenerics
	err := ctx.Err()
	switch refMode {
	case RefModeTracking:
		refID, refErr := ctx.RefResolver().TryPreserveRefId(ctx.Buffer())
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
		flag := ctx.Buffer().ReadInt8(err)
		if flag == NullFlag {
			return
		}
	}
	if readType {
		ctx.TypeResolver().ReadTypeInfo(ctx.Buffer(), err)
	}
	s.ReadData(ctx, value)
}

// ReadData deserializes union payload (case_id + case_value).
func (s *UnionSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	if ctx.HasError() {
		return
	}
	if err := s.initialize(ctx.TypeResolver()); err != nil {
		ctx.SetError(DeserializationErrorf("union serializer init failed: %v", err))
		return
	}
	err := ctx.Err()
	caseID := ctx.Buffer().ReadVaruint32(err)
	if ctx.HasError() {
		return
	}
	info, ok := s.caseByID[caseID]
	if !ok {
		SkipAnyValue(ctx, true)
		if ctx.HasError() {
			return
		}
		ctx.SetError(DeserializationErrorf("unknown union case id: %d", caseID))
		return
	}

	var caseValue any
	if info.needsOverride {
		val, ok := readUnionOverrideValue(ctx, info)
		if !ok {
			return
		}
		caseValue = val
	} else {
		v := reflect.New(info.type_).Elem()
		ctx.ReadValue(v, RefModeTracking, true)
		if ctx.HasError() {
			return
		}
		caseValue = v.Interface()
	}

	setter, setterErr := getUnionSetter(value)
	if setterErr != nil {
		ctx.SetError(DeserializationErrorf("union setter access failed: %v", setterErr))
		return
	}
	setter.ForyUnionSet(caseID, caseValue)
}

// ReadWithTypeInfo deserializes with pre-read type info.
func (s *UnionSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

func getUnionCaseValue(value reflect.Value) (uint32, any, error) {
	if !value.IsValid() {
		return 0, nil, fmt.Errorf("invalid reflect.Value")
	}
	if value.Kind() == reflect.Interface {
		if value.IsNil() {
			return 0, nil, fmt.Errorf("nil interface value")
		}
		value = value.Elem()
	}
	if value.Kind() == reflect.Ptr {
		if value.IsNil() {
			return 0, nil, fmt.Errorf("nil union pointer")
		}
		if uv, ok := value.Interface().(UnionGetter); ok {
			caseID, caseValue := uv.ForyUnionGet()
			return caseID, caseValue, nil
		}
		value = value.Elem()
	}
	if uv, ok := value.Interface().(UnionGetter); ok {
		caseID, caseValue := uv.ForyUnionGet()
		return caseID, caseValue, nil
	}
	return 0, nil, fmt.Errorf("type %v does not implement fory.UnionGetter", value.Type())
}

func getUnionSetter(value reflect.Value) (UnionSetter, error) {
	if !value.IsValid() {
		return nil, fmt.Errorf("invalid reflect.Value")
	}
	if value.Kind() == reflect.Interface {
		if value.IsNil() {
			return nil, fmt.Errorf("nil interface value")
		}
		value = value.Elem()
	}
	if value.Kind() == reflect.Ptr {
		if value.IsNil() {
			value.Set(reflect.New(value.Type().Elem()))
		}
		if setter, ok := value.Interface().(UnionSetter); ok {
			return setter, nil
		}
	}
	if value.Kind() == reflect.Struct {
		if !value.CanAddr() {
			return nil, fmt.Errorf("union value is not addressable")
		}
		if setter, ok := value.Addr().Interface().(UnionSetter); ok {
			return setter, nil
		}
	}
	return nil, fmt.Errorf("type %v does not implement fory.UnionSetter", value.Type())
}

func needsUnionEncodingOverride(caseType reflect.Type, caseTypeID TypeId, defaultTypeID TypeId) bool {
	if caseTypeID == defaultTypeID {
		return false
	}
	base := caseType
	if base.Kind() == reflect.Ptr {
		base = base.Elem()
	}
	switch base.Kind() {
	case reflect.Int32, reflect.Uint32, reflect.Int64, reflect.Uint64:
		if isNumericEncodingTypeID(caseTypeID) {
			if defaultTypeID == 0 || isNumericEncodingTypeID(defaultTypeID) {
				return true
			}
		}
	}
	return false
}

func isNumericEncodingTypeID(typeID TypeId) bool {
	switch typeID {
	case INT32, VARINT32,
		UINT32, VAR_UINT32,
		INT64, VARINT64, TAGGED_INT64,
		UINT64, VAR_UINT64, TAGGED_UINT64:
		return true
	default:
		return false
	}
}

func writeUnionOverrideValue(ctx *WriteContext, info *unionCaseInfo, value reflect.Value) {
	if value.Kind() == reflect.Interface {
		if !value.IsValid() || value.IsNil() {
			ctx.Buffer().WriteInt8(NullFlag)
			return
		}
		value = value.Elem()
	}
	if !value.IsValid() {
		ctx.Buffer().WriteInt8(NullFlag)
		return
	}
	if value.Kind() == reflect.Ptr {
		if value.IsNil() {
			ctx.Buffer().WriteInt8(NullFlag)
			return
		}
		if isRefKind(value.Elem().Kind()) {
			ctx.SetError(SerializationErrorf("pointer to reference type %s is not allowed", value.Type()))
			return
		}
	}
	refWritten, err := ctx.RefResolver().WriteRefOrNull(ctx.Buffer(), value)
	if err != nil {
		ctx.SetError(FromError(err))
		return
	}
	if refWritten {
		return
	}
	ctx.Buffer().WriteVaruint32Small7(uint32(info.typeID))
	if value.Kind() == reflect.Ptr {
		value = value.Elem()
	}
	writeNumericValueByTypeID(ctx, info.typeID, value)
}

func readUnionOverrideValue(ctx *ReadContext, info *unionCaseInfo) (any, bool) {
	buf := ctx.Buffer()
	refID, refErr := ctx.RefResolver().TryPreserveRefId(buf)
	if refErr != nil {
		ctx.SetError(FromError(refErr))
		return nil, false
	}
	if refID < int32(NotNullValueFlag) {
		obj := ctx.RefResolver().GetReadObject(refID)
		if obj.IsValid() {
			return obj.Interface(), true
		}
		return nil, true
	}

	typeID := TypeId(buf.ReadVaruint32Small7(ctx.Err()))
	if ctx.HasError() {
		return nil, false
	}
	if typeID != info.typeID {
		ctx.SetError(TypeMismatchError(typeID, info.typeID))
		return nil, false
	}

	value, ok := readNumericValueByTypeID(ctx, info.type_, info.typeID)
	if !ok {
		return nil, false
	}
	if value.Kind() == reflect.Ptr {
		ctx.RefResolver().Reference(value)
	}
	return value.Interface(), true
}

func writeNumericValueByTypeID(ctx *WriteContext, typeID TypeId, value reflect.Value) {
	buf := ctx.Buffer()
	switch typeID {
	case INT32:
		buf.WriteInt32(int32(value.Int()))
	case VARINT32:
		buf.WriteVarint32(int32(value.Int()))
	case UINT32:
		buf.WriteUint32(uint32(value.Uint()))
	case VAR_UINT32:
		buf.WriteVaruint32(uint32(value.Uint()))
	case INT64:
		buf.WriteInt64(value.Int())
	case VARINT64:
		buf.WriteVarint64(value.Int())
	case TAGGED_INT64:
		buf.WriteTaggedInt64(value.Int())
	case UINT64:
		buf.WriteUint64(value.Uint())
	case VAR_UINT64:
		buf.WriteVaruint64(value.Uint())
	case TAGGED_UINT64:
		buf.WriteTaggedUint64(value.Uint())
	default:
		ctx.SetError(SerializationErrorf("unsupported union numeric type id: %d", typeID))
	}
}

func readNumericValueByTypeID(ctx *ReadContext, targetType reflect.Type, typeID TypeId) (reflect.Value, bool) {
	buf := ctx.Buffer()
	base := targetType
	isPtr := false
	if base.Kind() == reflect.Ptr {
		isPtr = true
		base = base.Elem()
	}
	var v reflect.Value
	switch base.Kind() {
	case reflect.Int32:
		var value int32
		switch typeID {
		case INT32:
			value = buf.ReadInt32(ctx.Err())
		case VARINT32:
			value = buf.ReadVarint32(ctx.Err())
		default:
			ctx.SetError(DeserializationErrorf("unsupported union int32 type id: %d", typeID))
			return reflect.Value{}, false
		}
		if ctx.HasError() {
			return reflect.Value{}, false
		}
		v = reflect.New(base).Elem()
		v.SetInt(int64(value))
	case reflect.Uint32:
		var value uint32
		switch typeID {
		case UINT32:
			value = buf.ReadUint32(ctx.Err())
		case VAR_UINT32:
			value = buf.ReadVaruint32(ctx.Err())
		default:
			ctx.SetError(DeserializationErrorf("unsupported union uint32 type id: %d", typeID))
			return reflect.Value{}, false
		}
		if ctx.HasError() {
			return reflect.Value{}, false
		}
		v = reflect.New(base).Elem()
		v.SetUint(uint64(value))
	case reflect.Int64:
		var value int64
		switch typeID {
		case INT64:
			value = buf.ReadInt64(ctx.Err())
		case VARINT64:
			value = buf.ReadVarint64(ctx.Err())
		case TAGGED_INT64:
			value = buf.ReadTaggedInt64(ctx.Err())
		default:
			ctx.SetError(DeserializationErrorf("unsupported union int64 type id: %d", typeID))
			return reflect.Value{}, false
		}
		if ctx.HasError() {
			return reflect.Value{}, false
		}
		v = reflect.New(base).Elem()
		v.SetInt(value)
	case reflect.Uint64:
		var value uint64
		switch typeID {
		case UINT64:
			value = buf.ReadUint64(ctx.Err())
		case VAR_UINT64:
			value = buf.ReadVaruint64(ctx.Err())
		case TAGGED_UINT64:
			value = buf.ReadTaggedUint64(ctx.Err())
		default:
			ctx.SetError(DeserializationErrorf("unsupported union uint64 type id: %d", typeID))
			return reflect.Value{}, false
		}
		if ctx.HasError() {
			return reflect.Value{}, false
		}
		v = reflect.New(base).Elem()
		v.SetUint(value)
	default:
		ctx.SetError(DeserializationErrorf("unsupported union numeric kind: %v", base.Kind()))
		return reflect.Value{}, false
	}

	if !isPtr {
		return v, true
	}
	ptr := reflect.New(base)
	ptr.Elem().Set(v)
	return ptr, true
}

func isRefKind(kind reflect.Kind) bool {
	switch kind {
	case reflect.Ptr, reflect.Map, reflect.Slice, reflect.Interface:
		return true
	default:
		return false
	}
}
