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

const (
	COLL_DEFAULT_FLAG         = 0b0000
	COLL_TRACKING_REF         = 0b0001
	COLL_HAS_NULL             = 0b0010
	COLL_IS_DECL_ELEMENT_TYPE = 0b0100
	COLL_IS_SAME_TYPE         = 0b1000

	NOT_NULL_VALUE_FLAG   = -1
	NOT_NULL_INT64_FLAG   = NOT_NULL_VALUE_FLAG&0b11111111 | (INT64 << 8)
	NOT_NULL_FLOAT64_FLAG = NOT_NULL_VALUE_FLAG&0b11111111 | (DOUBLE << 8)
	NOT_NULL_BOOL_FLAG    = NOT_NULL_VALUE_FLAG&0b11111111 | (BOOL << 8)
	NOT_NULL_STRING_FLAG  = NOT_NULL_VALUE_FLAG&0b11111111 | (STRING << 8)
)

// sliceSerializer provides the dynamic slice implementation(e.g. []interface{}) that inspects
// element values at runtime
type sliceSerializer struct {
	elemTypeInfo    TypeInfo
	elemSerializer  Serializer
	elemType        reflect.Type
	elemTrackingRef int8
}

func (s sliceSerializer) TypeId() TypeId {
	return LIST
}

func (s sliceSerializer) NeedWriteRef() bool {
	return true
}

func NewSliceSerializer(f *Fory, elemSerializer Serializer, elemType reflect.Type) sliceSerializer {
	s := sliceSerializer{
		elemSerializer: elemSerializer,
	}
	if elemSerializer == nil {
		s.elemType = nil
		s.elemTrackingRef = -1
	} else {
		s.elemType = elemType
		s.elemTypeInfo, _ = f.typeResolver.getTypeInfoByType(elemType)
		if elemSerializer.NeedWriteRef() {
			s.elemTrackingRef = 1
		} else {
			s.elemTrackingRef = 0
		}
	}
	return s
}

func (s sliceSerializer) writeHeader(f *Fory, buf *ByteBuffer, value reflect.Value) (int8, TypeInfo) {
	collectFlag := COLL_DEFAULT_FLAG
	elemType := s.elemType
	elemTypeInfo := s.elemTypeInfo
	hasNull, hasSameType := false, true
	if elemType == nil {
		for i := 0; i < value.Len(); i++ {
			v := value.Index(i)
			if !hasNull && isNull(value.Index(i)) {
				hasNull = true
				continue
			}
			if elemType == nil {
				elemType = v.Type()
				if elemType.Kind() == reflect.Interface {
					elemType = v.Elem().Type()
				}
			} else if hasSameType && !checkSameType(v, elemType) {
				hasSameType = false
			}
		}
		if hasSameType {
			collectFlag |= COLL_IS_SAME_TYPE
			elemTypeInfo, _ = f.typeResolver.getTypeInfoByType(elemType)
		}
	} else {
		collectFlag |= COLL_IS_DECL_ELEMENT_TYPE | COLL_IS_SAME_TYPE
		for i := 0; i < value.Len(); i++ {
			if isNull(value.Index(i)) {
				hasNull = true
				break
			}
		}
	}
	if hasNull {
		collectFlag |= COLL_HAS_NULL
	}
	if f.refTracking {
		if s.elemTrackingRef == 1 {
			collectFlag |= COLL_TRACKING_REF
		} else if s.elemTrackingRef == -1 {
			if !hasSameType || elemTypeInfo.Serializer.NeedWriteRef() {
				collectFlag |= COLL_TRACKING_REF
			}
		}
	}
	buf.WriteVarUint32(uint32(value.Len()))
	buf.WriteInt8(int8(collectFlag))
	if hasSameType && (collectFlag&COLL_IS_DECL_ELEMENT_TYPE == 0) {
		f.typeResolver.writeTypeInfo(buf, elemTypeInfo)
	}
	return int8(collectFlag), elemTypeInfo
}

func checkSameType(v reflect.Value, rep reflect.Type) bool {
	if v.Kind() == reflect.Interface {
		if v.IsNil() {
			return true
		}
		v = v.Elem()
	}
	if v.Kind() == reflect.Map {
		return false
	}
	if !v.IsValid() || rep == nil {
		return false
	}
	t := v.Type()
	if t.Kind() == reflect.Ptr || t.Kind() == reflect.Interface {
		if v.IsNil() {
			return false
		}
		t = t.Elem()
	}
	for rep.Kind() == reflect.Ptr || rep.Kind() == reflect.Interface {
		rep = rep.Elem()
	}

	ka, kb := t.Kind(), rep.Kind()
	if ka == reflect.Array && kb == reflect.Array {
		return t.Elem() == rep.Elem()
	}
	if ka == reflect.Slice && kb == reflect.Slice {
		return t.Elem() == rep.Elem()
	}
	if (ka == reflect.Array && kb == reflect.Slice) || (ka == reflect.Slice && kb == reflect.Array) {
		return false
	}
	return t == rep
}

func (s sliceSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	if value.Kind() == reflect.Interface {
		value = value.Elem()
	}
	if value.Len() == 0 {
		buf.WriteVarUint64(0)
		return nil
	}
	collectFlag, elemTypeInfo := s.writeHeader(f, buf, value)
	elemType, elemSerializer := elemTypeInfo.Type, elemTypeInfo.Serializer
	if collectFlag&COLL_IS_SAME_TYPE != 0 {
		if elemType.Kind() == reflect.String {
			s.writeString_(buf, value)
		} else if elemSerializer.TypeId() == VAR_INT64 {
			s.writeInt(buf, value)
		} else if elemType.Kind() == reflect.Bool {
			s.writeBool(buf, value)
		} else if elemSerializer.TypeId() == DOUBLE {
			s.writeFloat(buf, value)
		} else {
			if collectFlag&COLL_TRACKING_REF == 0 {
				err := s.writeSameTypeNoRef(f, buf, value, elemTypeInfo)
				if err != nil {
					return err
				}
			} else {
				err := s.writeSameTypeRef(f, buf, value, elemTypeInfo)
				if err != nil {
					return err
				}
			}
		}
	} else {
		for i := 0; i < value.Len(); i++ {
			v, typ := value.Index(i), value.Index(i).Type()
			if v.Kind() == reflect.Interface {
				v = v.Elem()
				typ = v.Type()
			}
			switch v.Kind() {
			case reflect.String:
				buf.WriteInt16(NOT_NULL_STRING_FLAG)
				writeString(buf, v.String())
				continue

			case reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
				buf.WriteInt16(NOT_NULL_INT64_FLAG)
				buf.WriteVarint64(v.Int())
				continue

			case reflect.Bool:
				buf.WriteInt16(NOT_NULL_BOOL_FLAG)
				buf.WriteBool(v.Bool())
				continue

			case reflect.Float32, reflect.Float64:
				buf.WriteInt16(NOT_NULL_FLOAT64_FLAG)
				buf.WriteFloat64(v.Convert(reflect.TypeOf(float64(0))).Float())
				continue
			}

			if ok, _ := f.refResolver.WriteRefOrNull(buf, v); !ok {
				vTypeInfo, err := f.typeResolver.getTypeInfoByType(typ)
				if err != nil {
					return err
				}
				f.typeResolver.writeTypeInfo(buf, vTypeInfo)
				vTypeInfo.Serializer.Write(f, buf, v)
			}
		}
	}
	return nil
}

func (s sliceSerializer) writeString_(buf *ByteBuffer, value reflect.Value) {
	for i := 0; i < value.Len(); i++ {
		x := value.Index(i)
		if x.Kind() == reflect.Interface && !x.IsNil() {
			x = x.Elem()
		}
		writeString(buf, x.String())
	}
}

func (s sliceSerializer) writeInt(buf *ByteBuffer, value reflect.Value) {
	for i := 0; i < value.Len(); i++ {
		x := value.Index(i)
		if x.Kind() == reflect.Interface && !x.IsNil() {
			x = x.Elem()
		}
		buf.WriteVarint64(x.Int())
	}
}

func (s sliceSerializer) writeBool(buf *ByteBuffer, value reflect.Value) {
	for i := 0; i < value.Len(); i++ {
		x := value.Index(i)
		if x.Kind() == reflect.Interface && !x.IsNil() {
			x = x.Elem()
		}
		buf.WriteBool(x.Bool())
	}
}

func (s sliceSerializer) writeFloat(buf *ByteBuffer, value reflect.Value) {
	for i := 0; i < value.Len(); i++ {
		x := value.Index(i)
		if x.Kind() == reflect.Interface && !x.IsNil() {
			x = x.Elem()
		}
		buf.WriteFloat64(x.Float())
	}
}

func (s sliceSerializer) writeSameTypeNoRef(f *Fory, buf *ByteBuffer, value reflect.Value, elemTypeInfo TypeInfo) error {
	for i := 0; i < value.Len(); i++ {
		err := elemTypeInfo.Serializer.Write(f, buf, value.Index(i))
		if err != nil {
			return err
		}
	}
	return nil
}

func (s sliceSerializer) writeSameTypeRef(f *Fory, buf *ByteBuffer, value reflect.Value, elemTypeInfo TypeInfo) error {
	for i := 0; i < value.Len(); i++ {
		if ok, err := f.refResolver.WriteRefOrNull(buf, value.Index(i)); !ok {
			elemTypeInfo.Serializer.Write(f, buf, value.Index(i))
		} else if err != nil {
			return err
		}
	}
	return nil
}

func (s sliceSerializer) Read(f *Fory, buf *ByteBuffer, typ reflect.Type, value reflect.Value) error {
	length := int(buf.ReadVarUint32())
	if length == 0 {
		switch typ.Kind() {
		case reflect.Slice:
			value.Set(reflect.MakeSlice(typ, 0, 0))
		case reflect.Interface:
			ifaceElem := reflect.TypeOf((*interface{})(nil)).Elem()
			value.Set(reflect.MakeSlice(reflect.SliceOf(ifaceElem), 0, 0))
			value = value.Elem()
		default:
			return fmt.Errorf("sliceSerializer expects slice/interface; got %v", typ)
		}
		return nil
	}
	collectFlag := buf.ReadInt8()
	var elemTypeInfo TypeInfo
	var dst reflect.Value
	var err error
	outer := value
	switch typ.Kind() {
	case reflect.Slice:
		dst = reflect.MakeSlice(typ, length, length)
		value.Set(dst)
	case reflect.Interface:
		ifaceElem := reflect.TypeOf((*interface{})(nil)).Elem()
		dst = reflect.MakeSlice(reflect.SliceOf(ifaceElem), length, length)
		value.Set(dst)
		value = value.Elem()
	default:
		return fmt.Errorf("sliceSerializer expects slice/interface; got %v", typ)
	}
	if err != nil {
		return err
	}
	f.refResolver.Reference(value)
	typeId, refID := int32(-1), int32(-1)
	if collectFlag&COLL_IS_SAME_TYPE != 0 {
		if collectFlag&COLL_IS_DECL_ELEMENT_TYPE == 0 {
			elemTypeInfo, err = f.typeResolver.readTypeInfo(buf)
			if err != nil {
				return err
			}
			if outer.Kind() == reflect.Interface {
				T := elemTypeInfo.Type
				out := reflect.MakeSlice(reflect.SliceOf(T), length, length)
				outer.Set(out)
				value = outer.Elem()
			}
		} else {
			elemTypeInfo = s.elemTypeInfo
		}
		if collectFlag&COLL_HAS_NULL == 0 {
			typeId = elemTypeInfo.TypeID
			if typeId == STRING {
				s.readString_(buf, length, value)
				return nil
			} else if typeId == VAR_INT64 {
				s.readInt(buf, length, value)
				return nil
			} else if typeId == BOOL {
				s.readBool(buf, length, value)
				return nil
			} else if typeId == DOUBLE {
				s.readFloat(buf, length, value)
				return nil
			}
		}
		if collectFlag&COLL_TRACKING_REF == 0 {
			err = s.readSameTypeNoRef(f, buf, length, value, elemTypeInfo)
			if err != nil {
				return err
			}
		} else {
			err = s.readSameTypeRef(f, buf, length, value, elemTypeInfo)
			if err != nil {
				return err
			}
		}
	} else {
		for i := 0; i < length; i++ {
			refID, err = f.refResolver.TryPreserveRefId(buf)
			if err != nil {
				return err
			}
			slot := value.Index(i)

			if int8(refID) < NOT_NULL_VALUE_FLAG {
				slot.Set(f.refResolver.GetCurrentReadObject())
				continue
			}

			typeInfo, err := f.typeResolver.readTypeInfo(buf)
			if err != nil {
				return fmt.Errorf("read typeinfo: %w", err)
			}
			typeID := typeInfo.TypeID
			var (
				v   interface{}
				rve reflect.Value
			)
			switch typeID {
			case STRING:
				s := readString(buf)
				v = s

			case VAR_INT32, VAR_INT64:
				i64 := buf.ReadVarint64()
				v = i64

			case BOOL:
				b := buf.ReadBool()
				v = b

			case DOUBLE, FLOAT:
				f64 := buf.ReadFloat64()
				v = f64

			default:
				tgtType := typeInfo.Type
				if tgtType == nil {
					tgtType = slot.Type()
				} else if tgtType == interfaceSliceType || isPrimitiveArrayType(int16(typeID)) {
					tgtType = reflect.TypeOf((*interface{})(nil)).Elem()
				}
				concrete := reflect.New(tgtType).Elem()
				if err := typeInfo.Serializer.Read(f, buf, tgtType, concrete); err != nil {
					return err
				}
				rve = concrete
			}

			if rve.IsValid() {
				slot.Set(rve)
				f.refResolver.SetReadObject(refID, rve)
				continue
			}

			switch x := v.(type) {
			case string:
				if slot.Kind() == reflect.Interface {
					slot.Set(reflect.ValueOf(x))
				} else {
					slot.SetString(x)
				}
				f.refResolver.SetReadObject(refID, slot)

			case int64:
				if slot.Kind() == reflect.Interface {
					slot.Set(reflect.ValueOf(x))
				} else {
					switch slot.Kind() {
					case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
						slot.SetInt(x)
					default:
						return fmt.Errorf("cannot assign int64 to %v", slot.Type())
					}
				}
				f.refResolver.SetReadObject(refID, slot)

			case bool:
				if slot.Kind() == reflect.Interface {
					slot.Set(reflect.ValueOf(x))
				} else {
					if slot.Kind() != reflect.Bool {
						return fmt.Errorf("cannot assign bool to %v", slot.Type())
					}
					slot.SetBool(x)
				}
				f.refResolver.SetReadObject(refID, slot)

			case float64:
				if slot.Kind() == reflect.Interface {
					slot.Set(reflect.ValueOf(x))
				} else {
					switch slot.Kind() {
					case reflect.Float32, reflect.Float64:
						slot.SetFloat(x)
					default:
						return fmt.Errorf("cannot assign float64 to %v", slot.Type())
					}
				}
				f.refResolver.SetReadObject(refID, slot)

			default:
				return fmt.Errorf("unhandled primitive %T", v)
			}
		}
	}
	return nil
}

func (s sliceSerializer) readString_(buf *ByteBuffer, length int, value reflect.Value) {
	for i := 0; i < length; i++ {
		s := readString(buf)
		value.Index(i).Set(reflect.ValueOf(s))
	}
}

func (s sliceSerializer) readInt(buf *ByteBuffer, length int, value reflect.Value) {
	for i := 0; i < length; i++ {
		s := buf.ReadVarint64()
		value.Index(i).Set(reflect.ValueOf(s))
	}
}

func (s sliceSerializer) readBool(buf *ByteBuffer, length int, value reflect.Value) {
	for i := 0; i < length; i++ {
		s := buf.ReadBool()
		value.Index(i).Set(reflect.ValueOf(s))
	}
}

func (s sliceSerializer) readFloat(buf *ByteBuffer, length int, value reflect.Value) {
	for i := 0; i < length; i++ {
		s := buf.ReadFloat64()
		value.Index(i).Set(reflect.ValueOf(s))
	}
}

func (s sliceSerializer) readSameTypeNoRef(f *Fory, buf *ByteBuffer, length int, value reflect.Value, elemTypeInfo TypeInfo) error {
	for i := 0; i < length; i++ {
		elemSerializer := elemTypeInfo.Serializer
		slot := value.Index(i)
		slotType := slot.Type()
		if elemSerializer == nil {
			elemInfo, _ := f.typeResolver.getTypeInfoByType(slotType)
			elemSerializer = elemInfo.Serializer
		}
		err := elemSerializer.Read(f, buf, elemTypeInfo.Type, value.Index(i))
		if err != nil {
			return err
		}
	}
	return nil
}

func (s sliceSerializer) readSameTypeRef(f *Fory, buf *ByteBuffer, length int, value reflect.Value, elemTypeInfo TypeInfo) error {
	for i := 0; i < length; i++ {
		refId, err := f.refResolver.TryPreserveRefId(buf)
		if err != nil {
			return err
		}
		if int8(refId) < NOT_NULL_VALUE_FLAG {
			value.Index(i).Set(f.refResolver.GetCurrentReadObject())
		} else {
			slot := value.Index(i)
			slotType := slot.Type()
			elem := reflect.New(slotType).Elem()
			elemSerializer := elemTypeInfo.Serializer
			if elemSerializer == nil {
				elemInfo, _ := f.typeResolver.getTypeInfoByType(slotType)
				elemSerializer = elemInfo.Serializer
			}
			if err = elemSerializer.Read(f, buf, slotType, elem); err != nil {
				return err
			}
			slot.Set(elem)
			f.refResolver.SetReadObject(refId, elem)
		}
	}
	return nil
}

// Helper function to check if a value is null/nil
func isNull(v reflect.Value) bool {
	switch v.Kind() {
	case reflect.Ptr, reflect.Interface, reflect.Slice, reflect.Map, reflect.Func:
		return v.IsNil() // Check if reference types are nil
	default:
		return false // Value types are never null
	}
}

// sliceConcreteValueSerializer serialize a slice whose elem is not an interface or pointer to interface
type sliceConcreteValueSerializer struct {
	type_          reflect.Type
	elemSerializer Serializer
	referencable   bool
}

func (s *sliceConcreteValueSerializer) TypeId() TypeId {
	return -LIST
}

func (s *sliceConcreteValueSerializer) NeedWriteRef() bool {
	return true
}

func (s *sliceConcreteValueSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	length := value.Len()
	if err := f.writeLength(buf, length); err != nil {
		return err
	}

	var prevType reflect.Type
	for i := 0; i < length; i++ {
		elem := value.Index(i)
		elemType := elem.Type()

		var elemSerializer Serializer
		if i == 0 || elemType != prevType {
			elemSerializer = nil
		} else {
			elemSerializer = s.elemSerializer
		}

		if err := writeBySerializer(f, buf, elem, elemSerializer, s.referencable); err != nil {
			return err
		}

		prevType = elemType
	}
	return nil
}

func (s *sliceConcreteValueSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	length := f.readLength(buf)
	if value.Cap() < length {
		value.Set(reflect.MakeSlice(value.Type(), length, length))
	} else if value.Len() < length {
		value.Set(value.Slice(0, length))
	}
	f.refResolver.Reference(value)
	var prevType reflect.Type
	for i := 0; i < length; i++ {

		elem := value.Index(i)
		elemType := elem.Type()

		var elemSerializer Serializer
		if i == 0 || elemType != prevType {
			elemSerializer = nil
		} else {
			elemSerializer = s.elemSerializer
		}
		if err := readBySerializer(f, buf, value.Index(i), elemSerializer, s.referencable); err != nil {
			return err
		}
	}
	return nil
}

type byteSliceSerializer struct {
}

func (s byteSliceSerializer) TypeId() TypeId {
	return BINARY
}

func (s byteSliceSerializer) NeedWriteRef() bool {
	return true
}

func (s byteSliceSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	if err := f.WriteBufferObject(buf, &ByteSliceBufferObject{value.Interface().([]byte)}); err != nil {
		return err
	}
	return nil
}

func (s byteSliceSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	object, err := f.ReadBufferObject(buf)
	if err != nil {
		return err
	}
	raw := object.GetData()
	value.Set(reflect.ValueOf(raw))
	return nil
}

type ByteSliceBufferObject struct {
	data []byte
}

func (o *ByteSliceBufferObject) TotalBytes() int {
	return len(o.data)
}

func (o *ByteSliceBufferObject) WriteTo(buf *ByteBuffer) {
	buf.WriteBinary(o.data)
}

func (o *ByteSliceBufferObject) ToBuffer() *ByteBuffer {
	return NewByteBuffer(o.data)
}

// Legacy slice serializers - kept for backward compatibility but not used for xlang
type boolSliceSerializer struct {
}

func (s boolSliceSerializer) TypeId() TypeId {
	return BOOL_ARRAY // Use legacy type ID to avoid conflicts
}

func (s boolSliceSerializer) NeedWriteRef() bool {
	return true
}

func (s boolSliceSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	v := value.Interface().([]bool)
	size := len(v)
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(v))
	}
	buf.WriteLength(size)
	for _, elem := range v {
		buf.WriteBool(elem)
	}
	return nil
}

func (s boolSliceSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	length := buf.ReadLength()
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	for i := 0; i < length; i++ {
		r.Index(i).SetBool(buf.ReadBool())
	}
	value.Set(r)
	return nil
}

type int16SliceSerializer struct {
}

func (s int16SliceSerializer) TypeId() TypeId {
	return INT16_ARRAY
}

func (s int16SliceSerializer) NeedWriteRef() bool {
	return true
}

func (s int16SliceSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	v := value.Interface().([]int16)
	size := len(v) * 2
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(v))
	}
	buf.WriteLength(size)
	for _, elem := range v {
		buf.WriteInt16(elem)
	}
	return nil
}

func (s int16SliceSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	length := buf.ReadLength() / 2
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	for i := 0; i < length; i++ {
		r.Index(i).SetInt(int64(buf.ReadInt16()))
	}
	value.Set(r)
	return nil
}

type int32SliceSerializer struct {
}

func (s int32SliceSerializer) TypeId() TypeId {
	return INT32_ARRAY
}

func (s int32SliceSerializer) NeedWriteRef() bool {
	return true
}

func (s int32SliceSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	v := value.Interface().([]int32)
	size := len(v) * 4
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(v))
	}
	buf.WriteLength(size)
	for _, elem := range v {
		buf.WriteInt32(elem)
	}
	return nil
}

func (s int32SliceSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	length := buf.ReadLength() / 4
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	for i := 0; i < length; i++ {
		r.Index(i).SetInt(int64(buf.ReadInt32()))
	}
	value.Set(r)
	return nil
}

type int64SliceSerializer struct {
}

func (s int64SliceSerializer) TypeId() TypeId {
	return INT64_ARRAY
}

func (s int64SliceSerializer) NeedWriteRef() bool {
	return true
}

func (s int64SliceSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	v := value.Interface().([]int64)
	size := len(v) * 8
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(v))
	}
	buf.WriteLength(size)
	for _, elem := range v {
		buf.WriteInt64(elem)
	}
	return nil
}

func (s int64SliceSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	length := buf.ReadLength() / 8
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	for i := 0; i < length; i++ {
		r.Index(i).SetInt(buf.ReadInt64())
	}
	value.Set(r)
	return nil
}

type float32SliceSerializer struct {
}

func (s float32SliceSerializer) TypeId() TypeId {
	return FLOAT32_ARRAY
}

func (s float32SliceSerializer) NeedWriteRef() bool {
	return true
}

func (s float32SliceSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	v := value.Interface().([]float32)
	size := len(v) * 4
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(v))
	}
	buf.WriteLength(size)
	for _, elem := range v {
		buf.WriteFloat32(elem)
	}
	return nil
}

func (s float32SliceSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	length := buf.ReadLength() / 4
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	for i := 0; i < length; i++ {
		r.Index(i).SetFloat(float64(buf.ReadFloat32()))
	}
	value.Set(r)
	return nil
}

type float64SliceSerializer struct {
}

func (s float64SliceSerializer) TypeId() TypeId {
	return FLOAT64_ARRAY
}

func (s float64SliceSerializer) NeedWriteRef() bool {
	return true
}

func (s float64SliceSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	v := value.Interface().([]float64)
	size := len(v) * 8
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", len(v))
	}
	buf.WriteLength(size)
	for _, elem := range v {
		buf.WriteFloat64(elem)
	}
	return nil
}

func (s float64SliceSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	length := buf.ReadLength() / 8
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}
	for i := 0; i < length; i++ {
		r.Index(i).SetFloat(buf.ReadFloat64())
	}
	value.Set(r)
	return nil
}

type stringSliceSerializer struct {
	strSerializer stringSerializer
}

func (s stringSliceSerializer) TypeId() TypeId {
	return FORY_STRING_ARRAY
}

func (s stringSliceSerializer) NeedWriteRef() bool {
	return true
}

func (s stringSliceSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	v := value.Interface().([]string)
	err := f.writeLength(buf, len(v))
	if err != nil {
		return err
	}
	for _, str := range v {
		if refWritten, err := f.refResolver.WriteRefOrNull(buf, reflect.ValueOf(str)); err == nil {
			if !refWritten {
				if err := writeString(buf, str); err != nil {
					return err
				}
			}
		} else {
			return err
		}
	}
	return nil
}

func (s stringSliceSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) (err error) {
	length := f.readLength(buf)
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		r = reflect.MakeSlice(type_, length, length)
	case reflect.Array:
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()
	default:
		return fmt.Errorf("unsupported kind %v, want slice/array", type_.Kind())
	}

	elemTyp := type_.Elem()
	set := func(i int, s string) {
		if elemTyp.Kind() == reflect.String {
			r.Index(i).SetString(s)
		} else {
			r.Index(i).Set(reflect.ValueOf(s).Convert(elemTyp))
		}
	}

	for i := 0; i < length; i++ {
		refFlag := f.refResolver.ReadRefOrNull(buf)
		if refFlag == RefValueFlag || refFlag == NotNullValueFlag {
			var nextReadRefId int32
			if refFlag == RefValueFlag {
				var err error
				nextReadRefId, err = f.refResolver.PreserveRefId()
				if err != nil {
					return err
				}
			}
			elem := readString(buf)
			if f.refTracking && refFlag == RefValueFlag {
				// If value is not nil(reflect), then value is a pointer to some variable, we can update the `value`,
				// then record `value` in the reference resolver.
				f.refResolver.SetReadObject(nextReadRefId, reflect.ValueOf(elem))
			}
			set(i, elem)
		} else if refFlag == NullFlag {
			set(i, "")
		} else { // RefNoneFlag
			set(i, f.refResolver.GetCurrentReadObject().Interface().(string))
		}
	}
	value.Set(r)
	return nil
}

type boolArraySerializer struct {
}

func (s boolArraySerializer) TypeId() TypeId {
	return BOOL_ARRAY
}

func (s boolArraySerializer) NeedWriteRef() bool {
	return true
}

func (s boolArraySerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	if value.Kind() == reflect.Interface {
		value = value.Elem()
	}
	size := value.Len()
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", size)
	}
	buf.WriteLength(size)
	for i := 0; i < size; i++ {
		buf.WriteBool(value.Index(i).Bool())
	}
	return nil
}

func (s boolArraySerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	length := buf.ReadLength()
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		if type_.Elem().Kind() != reflect.Bool {
			return fmt.Errorf("element kind must be bool, got %v", type_.Elem())
		}
		r = reflect.MakeSlice(type_, length, length)

	case reflect.Array:
		if type_.Elem().Kind() != reflect.Bool {
			return fmt.Errorf("element kind must be bool, got %v", type_.Elem())
		}
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()

	default:
		return fmt.Errorf("unsupported kind %v, want slice/array/interface", type_.Kind())
	}

	for i := 0; i < length; i++ {
		b := buf.ReadBool()
		r.Index(i).SetBool(b)
	}

	value.Set(r)
	return nil
}

type int8ArraySerializer struct {
}

func (s int8ArraySerializer) TypeId() TypeId {
	return INT8_ARRAY
}

func (s int8ArraySerializer) NeedWriteRef() bool {
	return true
}

func (s int8ArraySerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	if value.Kind() == reflect.Interface {
		value = value.Elem()
	}
	size := value.Len()
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", size)
	}
	buf.WriteLength(size)
	for i := 0; i < size; i++ {
		buf.WriteByte_(byte(value.Index(i).Int()))
	}
	return nil
}

func (s int8ArraySerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	length := buf.ReadLength()
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		if type_.Elem().Kind() != reflect.Int8 {
			return fmt.Errorf("element kind must be bool, got %v", type_.Elem())
		}
		r = reflect.MakeSlice(type_, length, length)

	case reflect.Array:
		if type_.Elem().Kind() != reflect.Int8 {
			return fmt.Errorf("element kind must be bool, got %v", type_.Elem())
		}
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()

	default:
		return fmt.Errorf("unsupported kind %v, want slice/array/interface", type_.Kind())
	}

	for i := 0; i < length; i++ {
		b, _ := buf.ReadByte()
		r.Index(i).SetInt(int64(b))
	}

	value.Set(r)
	return nil
}

type int16ArraySerializer struct {
}

func (s int16ArraySerializer) TypeId() TypeId {
	return INT16_ARRAY
}

func (s int16ArraySerializer) NeedWriteRef() bool {
	return true
}

func (s int16ArraySerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	if value.Kind() == reflect.Interface {
		value = value.Elem()
	}
	size := value.Len() * 2
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", value.Len())
	}
	buf.WriteLength(size)
	for i := 0; i < value.Len(); i++ {
		buf.WriteInt16(int16(value.Index(i).Int()))
	}
	return nil
}

func (s int16ArraySerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	length := buf.ReadLength() / 2
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		if type_.Elem().Kind() != reflect.Int16 {
			return fmt.Errorf("element kind must be Int16, got %v", type_.Elem())
		}
		r = reflect.MakeSlice(type_, length, length)

	case reflect.Array:
		if type_.Elem().Kind() != reflect.Int16 {
			return fmt.Errorf("element kind must be Int16, got %v", type_.Elem())
		}
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()

	default:
		return fmt.Errorf("unsupported kind %v, want slice/array/interface", type_.Kind())
	}

	for i := 0; i < length; i++ {
		b := buf.ReadInt16()
		r.Index(i).SetInt(int64(b))
	}
	value.Set(r)
	return nil
}

type int32ArraySerializer struct {
}

func (s int32ArraySerializer) TypeId() TypeId {
	return INT32_ARRAY
}

func (s int32ArraySerializer) NeedWriteRef() bool {
	return true
}

func (s int32ArraySerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	if value.Kind() == reflect.Interface {
		value = value.Elem()
	}
	size := value.Len() * 4
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", value.Len())
	}
	buf.WriteLength(size)
	for i := 0; i < value.Len(); i++ {
		buf.WriteInt32(int32(value.Index(i).Int()))
	}
	return nil
}

func (s int32ArraySerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	length := buf.ReadLength() / 4
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		if type_.Elem().Kind() != reflect.Int32 {
			return fmt.Errorf("element kind must be int32, got %v", type_.Elem())
		}
		r = reflect.MakeSlice(type_, length, length)

	case reflect.Array:
		if type_.Elem().Kind() != reflect.Int32 {
			return fmt.Errorf("element kind must be int32, got %v", type_.Elem())
		}
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()

	default:
		return fmt.Errorf("unsupported kind %v, want slice/array/interface", type_.Kind())
	}

	for i := 0; i < length; i++ {
		b := buf.ReadInt32()
		r.Index(i).SetInt(int64(b))
	}
	value.Set(r)
	return nil
}

type int64ArraySerializer struct {
}

func (s int64ArraySerializer) TypeId() TypeId {
	return INT64_ARRAY
}

func (s int64ArraySerializer) NeedWriteRef() bool {
	return true
}

func (s int64ArraySerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	if value.Kind() == reflect.Interface {
		value = value.Elem()
	}
	size := value.Len() * 8
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", value.Len())
	}
	buf.WriteLength(size)
	for i := 0; i < value.Len(); i++ {
		buf.WriteInt64(value.Index(i).Int())
	}
	return nil
}

func (s int64ArraySerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	length := buf.ReadLength() / 8
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		if type_.Elem().Kind() != reflect.Int64 {
			return fmt.Errorf("element kind must be int64, got %v", type_.Elem())
		}
		r = reflect.MakeSlice(type_, length, length)

	case reflect.Array:
		if type_.Elem().Kind() != reflect.Int64 {
			return fmt.Errorf("element kind must be int64, got %v", type_.Elem())
		}
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()

	default:
		return fmt.Errorf("unsupported kind %v, want slice/array/interface", type_.Kind())
	}

	for i := 0; i < length; i++ {
		b := buf.ReadInt64()
		r.Index(i).SetInt(int64(b))
	}
	value.Set(r)
	return nil
}

type float32ArraySerializer struct {
}

func (s float32ArraySerializer) TypeId() TypeId {
	return FLOAT32_ARRAY
}

func (s float32ArraySerializer) NeedWriteRef() bool {
	return true
}

func (s float32ArraySerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	if value.Kind() == reflect.Interface {
		value = value.Elem()
	}
	size := value.Len() * 4
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", value.Len())
	}
	buf.WriteLength(size)
	for i := 0; i < value.Len(); i++ {
		buf.WriteFloat32(float32(value.Index(i).Float()))
	}
	return nil
}

func (s float32ArraySerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	length := buf.ReadLength() / 4
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		if type_.Elem().Kind() != reflect.Float32 {
			return fmt.Errorf("element kind must be float32, got %v", type_.Elem())
		}
		r = reflect.MakeSlice(type_, length, length)

	case reflect.Array:
		if type_.Elem().Kind() != reflect.Float32 {
			return fmt.Errorf("element kind must be float32, got %v", type_.Elem())
		}
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()

	default:
		return fmt.Errorf("unsupported kind %v, want slice/array/interface", type_.Kind())
	}

	for i := 0; i < length; i++ {
		r.Index(i).SetFloat(float64(buf.ReadFloat32()))
	}
	value.Set(r)
	return nil
}

type float64ArraySerializer struct {
}

func (s float64ArraySerializer) TypeId() TypeId {
	return FLOAT64_ARRAY
}

func (s float64ArraySerializer) NeedWriteRef() bool {
	return true
}

func (s float64ArraySerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	if value.Kind() == reflect.Interface {
		value = value.Elem()
	}
	size := value.Len() * 8
	if size >= MaxInt32 {
		return fmt.Errorf("too long slice: %d", value.Len())
	}
	buf.WriteLength(size)
	for i := 0; i < value.Len(); i++ {
		buf.WriteFloat64(value.Index(i).Float())
	}
	return nil
}

func (s float64ArraySerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	length := buf.ReadLength() / 8
	var r reflect.Value
	switch type_.Kind() {
	case reflect.Slice:
		if type_.Elem().Kind() != reflect.Float64 {
			return fmt.Errorf("element kind must be float64, got %v", type_.Elem())
		}
		r = reflect.MakeSlice(type_, length, length)

	case reflect.Array:
		if type_.Elem().Kind() != reflect.Float64 {
			return fmt.Errorf("element kind must be float64, got %v", type_.Elem())
		}
		if length != type_.Len() {
			return fmt.Errorf("length %d does not match array type %v", length, type_)
		}
		r = reflect.New(type_).Elem()

	default:
		return fmt.Errorf("unsupported kind %v, want slice/array/interface", type_.Kind())
	}

	for i := 0; i < length; i++ {
		r.Index(i).SetFloat(buf.ReadFloat64())
	}
	value.Set(r)
	return nil
}

// those types will be serialized by `sliceConcreteValueSerializer`, which correspond to List types in java/python

type Int8Slice []int8
type Int16Slice []int16
type Int32Slice []int32
type Int64Slice []int64
type Float32Slice []float64
type Float64Slice []float64
