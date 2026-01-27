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
	"strings"
	"unsafe"
)

const optionalPkgPath = "github.com/apache/fory/go/fory/optional"

// optionalInfo describes the Optional[T] layout for fast access.
type optionalInfo struct {
	valueType   reflect.Type
	valueOffset uintptr
	hasOffset   uintptr
}

func getOptionalInfo(type_ reflect.Type) (optionalInfo, bool) {
	if type_ == nil {
		return optionalInfo{}, false
	}
	if type_.Kind() == reflect.Ptr {
		return optionalInfo{}, false
	}
	if type_.Kind() != reflect.Struct {
		return optionalInfo{}, false
	}
	if type_.PkgPath() != optionalPkgPath {
		return optionalInfo{}, false
	}
	name := type_.Name()
	if name != "Optional" && !strings.HasPrefix(name, "Optional[") {
		return optionalInfo{}, false
	}
	valueField, ok := type_.FieldByName("value")
	if !ok {
		return optionalInfo{}, false
	}
	hasField, ok := type_.FieldByName("has")
	if !ok || hasField.Type.Kind() != reflect.Bool {
		return optionalInfo{}, false
	}
	return optionalInfo{
		valueType:   valueField.Type,
		valueOffset: valueField.Offset,
		hasOffset:   hasField.Offset,
	}, true
}

func validateOptionalValueType(valueType reflect.Type) error {
	if valueType == nil {
		return fmt.Errorf("optional value type is nil")
	}
	switch valueType.Kind() {
	case reflect.Struct:
		return fmt.Errorf("optional.Optional[%s] is not supported for struct values", valueType.String())
	case reflect.Slice, reflect.Map:
		return fmt.Errorf("optional.Optional[%s] is not supported for slice/map values", valueType.String())
	case reflect.Ptr:
		elem := valueType.Elem()
		if elem.Kind() == reflect.Slice || elem.Kind() == reflect.Map {
			return fmt.Errorf("optional.Optional[%s] is not supported for slice/map values", valueType.String())
		}
	}
	return nil
}

func isOptionalType(type_ reflect.Type) bool {
	_, ok := getOptionalInfo(type_)
	return ok
}

func unwrapOptionalType(type_ reflect.Type) (reflect.Type, bool) {
	info, ok := getOptionalInfo(type_)
	if !ok {
		return type_, false
	}
	return info.valueType, true
}

func optionalHasValue(value reflect.Value, info optionalInfo) bool {
	if value.Kind() == reflect.Ptr {
		if value.IsNil() {
			return false
		}
		value = value.Elem()
	}
	if value.CanAddr() {
		hasPtr := (*bool)(unsafe.Add(unsafe.Pointer(value.UnsafeAddr()), info.hasOffset))
		return *hasPtr
	}
	field := value.FieldByName("has")
	if !field.IsValid() {
		return false
	}
	return field.Bool()
}

// optionalSerializer handles Optional[T] values by writing null flags and delegating to the element serializer.
type optionalSerializer struct {
	optionalType    reflect.Type
	valueType       reflect.Type
	valueOffset     uintptr
	hasOffset       uintptr
	valueSerializer Serializer
}

func newOptionalSerializer(optionalType reflect.Type, info optionalInfo, valueSerializer Serializer) *optionalSerializer {
	return &optionalSerializer{
		optionalType:    optionalType,
		valueType:       info.valueType,
		valueOffset:     info.valueOffset,
		hasOffset:       info.hasOffset,
		valueSerializer: valueSerializer,
	}
}

func (s *optionalSerializer) unwrap(value reflect.Value) reflect.Value {
	if value.Kind() == reflect.Ptr {
		if value.IsNil() {
			return reflect.Value{}
		}
		return value.Elem()
	}
	return value
}

func (s *optionalSerializer) has(value reflect.Value) bool {
	value = s.unwrap(value)
	if !value.IsValid() {
		return false
	}
	if value.CanAddr() {
		hasPtr := (*bool)(unsafe.Add(unsafe.Pointer(value.UnsafeAddr()), s.hasOffset))
		return *hasPtr
	}
	field := value.FieldByName("has")
	if !field.IsValid() {
		return false
	}
	return field.Bool()
}

func (s *optionalSerializer) valueField(value reflect.Value) reflect.Value {
	value = s.unwrap(value)
	if !value.IsValid() {
		return reflect.New(s.valueType).Elem()
	}
	if value.CanAddr() {
		ptr := unsafe.Add(unsafe.Pointer(value.UnsafeAddr()), s.valueOffset)
		return reflect.NewAt(s.valueType, ptr).Elem()
	}
	field := value.FieldByName("value")
	if field.IsValid() {
		return field
	}
	return reflect.New(s.valueType).Elem()
}

func (s *optionalSerializer) setHas(value reflect.Value, has bool) {
	value = s.unwrap(value)
	if !value.IsValid() {
		return
	}
	if value.CanAddr() {
		hasPtr := (*bool)(unsafe.Add(unsafe.Pointer(value.UnsafeAddr()), s.hasOffset))
		*hasPtr = has
		return
	}
	field := value.FieldByName("has")
	if field.IsValid() && field.CanSet() {
		field.SetBool(has)
	}
}

func (s *optionalSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	if !s.has(value) {
		s.writeNull(ctx, refMode, writeType)
		return
	}
	valueField := s.valueField(value)
	s.writeValue(ctx, refMode, writeType, valueField)
}

func (s *optionalSerializer) writeNull(ctx *WriteContext, refMode RefMode, writeType bool) {
	switch refMode {
	case RefModeTracking, RefModeNullOnly:
		ctx.Buffer().WriteInt8(NullFlag)
		return
	case RefModeNone:
		// For RefModeNone, write zero value data without any flag.
		zero := reflect.New(s.valueType).Elem()
		if writeType {
			info, err := ctx.TypeResolver().getTypeInfo(zero, true)
			if err != nil {
				ctx.SetError(FromError(err))
				return
			}
			ctx.TypeResolver().WriteTypeInfo(ctx.Buffer(), info, ctx.Err())
		}
		s.valueSerializer.WriteData(ctx, zero)
	}
}

func (s *optionalSerializer) writeValue(ctx *WriteContext, refMode RefMode, writeType bool, valueField reflect.Value) {
	switch refMode {
	case RefModeTracking:
		refWritten, err := ctx.RefResolver().WriteRefOrNull(ctx.Buffer(), valueField)
		if err != nil {
			ctx.SetError(FromError(err))
			return
		}
		if refWritten {
			return
		}
	case RefModeNullOnly:
		ctx.Buffer().WriteInt8(NotNullValueFlag)
	case RefModeNone:
		// No ref/null flag written.
	}
	if writeType {
		info, err := ctx.TypeResolver().getTypeInfo(valueField, true)
		if err != nil {
			ctx.SetError(FromError(err))
			return
		}
		ctx.TypeResolver().WriteTypeInfo(ctx.Buffer(), info, ctx.Err())
	}
	s.valueSerializer.WriteData(ctx, valueField)
}

func (s *optionalSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	// WriteData assumes the value is present and writes data only (no null/ref flags).
	valueField := s.valueField(value)
	s.valueSerializer.WriteData(ctx, valueField)
}

func (s *optionalSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	buf := ctx.Buffer()
	switch refMode {
	case RefModeTracking:
		refID, refErr := ctx.RefResolver().TryPreserveRefId(buf)
		if refErr != nil {
			ctx.SetError(FromError(refErr))
			return
		}
		if refID < int32(NotNullValueFlag) {
			if refID == int32(NullFlag) {
				s.setHas(value, false)
				return
			}
			refObj := ctx.RefResolver().GetReadObject(refID)
			if refObj.IsValid() {
				valueField := s.valueField(value)
				if refObj.Type().AssignableTo(valueField.Type()) {
					valueField.Set(refObj)
					s.setHas(value, true)
					return
				}
			}
		}
	case RefModeNullOnly:
		flag := buf.ReadInt8(ctx.Err())
		if flag == NullFlag {
			s.setHas(value, false)
			return
		}
	case RefModeNone:
		// No null flag.
	}
	if readType {
		typeID := buf.ReadVaruint32Small7(ctx.Err())
		if ctx.HasError() {
			return
		}
		internalTypeID := TypeId(typeID & 0xFF)
		if IsNamespacedType(TypeId(typeID)) || internalTypeID == COMPATIBLE_STRUCT || internalTypeID == STRUCT {
			typeInfo := ctx.TypeResolver().readTypeInfoWithTypeID(buf, typeID, ctx.Err())
			if structSer, ok := typeInfo.Serializer.(*structSerializer); ok && len(structSer.fieldDefs) > 0 {
				valueField := s.valueField(value)
				s.setHas(value, true)
				structSer.ReadData(ctx, valueField)
				return
			}
		}
	}
	s.ReadData(ctx, value)
}

func (s *optionalSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	valueField := s.valueField(value)
	s.setHas(value, true)
	s.valueSerializer.ReadData(ctx, valueField)
}

func (s *optionalSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}
