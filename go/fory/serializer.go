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
	"time"
)

type Serializer interface {
	TypeId() TypeId
	Write(f *Fory, buf *ByteBuffer, value reflect.Value) error
	Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error
	NeedWriteRef() bool
}

type boolSerializer struct {
}

func (s boolSerializer) TypeId() TypeId {
	return BOOL
}

func (s boolSerializer) NeedWriteRef() bool {
	return false
}

func (s boolSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	buf.WriteBool(value.Bool())
	return nil
}

func (s boolSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	value.Set(reflect.ValueOf(buf.ReadBool()))
	return nil
}

type int8Serializer struct {
}

func (s int8Serializer) TypeId() TypeId {
	return INT8
}

func (s int8Serializer) NeedWriteRef() bool {
	return false
}

func (s int8Serializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	buf.WriteByte_(byte(value.Int()))
	return nil
}

func (s int8Serializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	value.Set(reflect.ValueOf(int8(buf.ReadByte_())))
	return nil
}

type byteSerializer struct {
}

func (s byteSerializer) TypeId() TypeId {
	return UINT8
}

func (s byteSerializer) NeedWriteRef() bool {
	return false
}

func (s byteSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	buf.WriteByte_(byte(value.Uint()))
	return nil
}

func (s byteSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	// In java, this will be deserialized to an int value to hold unsigned byte range.
	value.Set(reflect.ValueOf(buf.ReadByte_()))
	return nil
}

type int16Serializer struct {
}

func (s int16Serializer) TypeId() TypeId {
	return INT16
}

func (s int16Serializer) NeedWriteRef() bool {
	return false
}

func (s int16Serializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	buf.WriteInt16(int16(value.Int()))
	return nil
}

func (s int16Serializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	value.Set(reflect.ValueOf(buf.ReadInt16()))
	return nil
}

type int32Serializer struct {
}

func (s int32Serializer) TypeId() TypeId {
	return INT32
}

func (s int32Serializer) NeedWriteRef() bool {
	return false
}

func (s int32Serializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	buf.WriteVarint32(int32(value.Int()))
	return nil
}

func (s int32Serializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	value.Set(reflect.ValueOf(buf.ReadVarint32()))
	return nil
}

type int64Serializer struct {
}

func (s int64Serializer) TypeId() TypeId {
	return INT64
}

func (s int64Serializer) NeedWriteRef() bool {
	return false
}

func (s int64Serializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	buf.WriteVarint64(value.Int())
	return nil
}

func (s int64Serializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	value.Set(reflect.ValueOf(buf.ReadVarint64()))
	return nil
}

type intSerializer struct {
}

func (s intSerializer) TypeId() TypeId {
	return -INT64
}

func (s intSerializer) NeedWriteRef() bool {
	return false
}

func (s intSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	buf.WriteInt64(value.Int())
	return nil
}

func (s intSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	v := buf.ReadInt64()
	if v > MaxInt || v < MinInt {
		return fmt.Errorf("int64 %d exceed int range", v)
	}
	value.Set(reflect.ValueOf(int(v)))
	return nil
}

type float32Serializer struct {
}

func (s float32Serializer) TypeId() TypeId {
	return FLOAT
}

func (s float32Serializer) NeedWriteRef() bool {
	return false
}

func (s float32Serializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	buf.WriteFloat32(float32(value.Float()))
	return nil
}

func (s float32Serializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	value.Set(reflect.ValueOf(buf.ReadFloat32()))
	return nil
}

type float64Serializer struct {
}

func (s float64Serializer) TypeId() TypeId {
	return DOUBLE
}

func (s float64Serializer) NeedWriteRef() bool {
	return false
}

func (s float64Serializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	buf.WriteFloat64(value.Float())
	return nil
}

func (s float64Serializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	value.Set(reflect.ValueOf(buf.ReadFloat64()))
	return nil
}

type stringSerializer struct {
}

func (s stringSerializer) TypeId() TypeId {
	return STRING
}

func (s stringSerializer) NeedWriteRef() bool {
	return true
}

func (s stringSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	return writeString(buf, value.Interface().(string))
}

func (s stringSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	value.Set(reflect.ValueOf(readString(buf)))
	return nil
}

// ptrToStringSerializer serializes a pointer to string. Reference are considered based on pointer instead of
// string value.
type ptrToStringSerializer struct {
}

func (s ptrToStringSerializer) TypeId() TypeId {
	return -STRING
}

func (s ptrToStringSerializer) NeedWriteRef() bool {
	return true
}

func (s ptrToStringSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {

	if value.Kind() != reflect.Ptr || value.IsNil() {
		return fmt.Errorf("expected non-nil string pointer, got %v", value.Type())
	}
	str := value.Elem().Interface().(string)
	return writeString(buf, str)
}

func (s ptrToStringSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {

	str := readString(buf)
	value.Set(reflect.ValueOf(&str))
	return nil
}

func readStringBytes(buf *ByteBuffer) []byte {
	return buf.ReadBinary(int(buf.ReadVarInt32()))
}

type arraySerializer struct {
}

func (s arraySerializer) TypeId() TypeId {
	return -LIST
}

func (s arraySerializer) NeedWriteRef() bool {
	return true
}

func (s arraySerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	length := value.Len()
	if err := f.writeLength(buf, length); err != nil {
		return err
	}
	for i := 0; i < length; i++ {
		if err := f.WriteReferencable(buf, value.Index(i)); err != nil {
			return err
		}
	}
	return nil
}
func (s arraySerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	length := f.readLength(buf)
	for i := 0; i < length; i++ {
		elem := value.Index(i)
		if err := f.ReadReferencable(buf, elem); err != nil {
			return err
		}
	}
	return nil
}

// arrayConcreteValueSerializer serialize an array/*array
type arrayConcreteValueSerializer struct {
	type_          reflect.Type
	elemSerializer Serializer
	referencable   bool
}

func (s *arrayConcreteValueSerializer) TypeId() TypeId {
	return -LIST
}

func (s arrayConcreteValueSerializer) NeedWriteRef() bool {
	return true
}

func (s *arrayConcreteValueSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	length := value.Len()
	if err := f.writeLength(buf, length); err != nil {
		return err
	}
	for i := 0; i < length; i++ {
		if err := writeBySerializer(f, buf, value.Index(i), s.elemSerializer, s.referencable); err != nil {
			return err
		}
	}
	return nil
}

func (s *arrayConcreteValueSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	length := buf.ReadLength()
	for i := 0; i < length; i++ {
		if err := readBySerializer(f, buf, value.Index(i), s.elemSerializer, s.referencable); err != nil {
			return err
		}
	}
	return nil
}

type byteArraySerializer struct {
}

func (s byteArraySerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	length := value.Len()
	if err := f.writeLength(buf, length); err != nil {
		return err
	}
	if value.CanAddr() {
		bytes := value.Slice(0, length).Bytes()
		buf.WriteBinary(bytes)
		return nil
	}
	buf.grow(length)
	reflect.Copy(reflect.ValueOf(buf.data[buf.writerIndex:]), value)
	buf.writerIndex += length
	return nil
}

func (s byteArraySerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	length := buf.ReadInt32()
	if int(length) != value.Len() {
		return fmt.Errorf("%s has len %d, but fory has len elements %d", value.Type(), value.Len(), length)
	}
	_, err := buf.Read(value.Slice(0, int(length)).Bytes())
	return err
}

func (s byteArraySerializer) TypeId() TypeId {
	return -BINARY
}

func (s byteArraySerializer) NeedWriteRef() bool {
	return false
}

// Date represents an imprecise date.
type Date struct {
	Year  int        // Year. E.g., 2009.
	Month time.Month // Month is 1 - 12. 0 means unspecified.
	Day   int
}

type dateSerializer struct {
}

func (s dateSerializer) TypeId() TypeId {
	return LOCAL_DATE
}

func (s dateSerializer) NeedWriteRef() bool {
	return true
}

func (s dateSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	date := value.Interface().(Date)
	diff := time.Date(date.Year, date.Month, date.Day, 0, 0, 0, 0, time.Local).Sub(
		time.Date(1970, 1, 1, 0, 0, 0, 0, time.Local))
	buf.WriteInt32(int32(diff.Hours() / 24))
	return nil
}

func (s dateSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	diff := time.Duration(buf.ReadInt32()) * 24 * time.Hour
	date := time.Date(1970, 1, 1, 0, 0, 0, 0, time.Local).Add(diff)
	value.Set(reflect.ValueOf(Date{date.Year(), date.Month(), date.Day()}))
	return nil
}

type timeSerializer struct {
}

func (s timeSerializer) TypeId() TypeId {
	return TIMESTAMP
}

func (s timeSerializer) NeedWriteRef() bool {
	return true
}

func (s timeSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	buf.WriteInt64(GetUnixMicro(value.Interface().(time.Time)))
	return nil
}

func (s timeSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	value.Set(reflect.ValueOf(CreateTimeFromUnixMicro(buf.ReadInt64())))
	return nil
}

// ptrToValueSerializer serialize a ptr which point to a concrete value.
// Pointer to interface are not allowed in fory.
type ptrToValueSerializer struct {
	valueSerializer Serializer
}

func (s *ptrToValueSerializer) TypeId() TypeId {
	if id := s.valueSerializer.TypeId(); id < 0 {
		return id
	} else {
		return -id
	}
}

func (s *ptrToValueSerializer) NeedWriteRef() bool {
	return true
}

func (s *ptrToValueSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	return s.valueSerializer.Write(f, buf, value.Elem())
}

func (s *ptrToValueSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	newValue := reflect.New(type_.Elem())
	value.Set(newValue)
	return s.valueSerializer.Read(f, buf, type_.Elem(), newValue.Elem())
}

func writeBySerializer(f *Fory, buf *ByteBuffer, value reflect.Value, serializer Serializer, referencable bool) error {
	if referencable {
		return f.writeReferencableBySerializer(buf, value, serializer)
	} else {
		return f.writeNonReferencableBySerializer(buf, value, serializer)
	}
}

func readBySerializer(f *Fory, buf *ByteBuffer, value reflect.Value, serializer Serializer, referencable bool) error {
	if referencable {
		return f.readReferencableBySerializer(buf, value, serializer)
	} else {
		if flag := buf.ReadInt8(); flag != NotNullValueFlag {
			return fmt.Errorf("data incisistency: should be a byte value `%d` here but got `%d`",
				NotNullValueFlag, flag)
		} else {
			return f.readData(buf, value, serializer)
		}
	}
}

// TODO(chaokunyang) support custom serialization

type Marshaller interface {
	ExtId() int16
	MarshalFory(f *Fory, buf *ByteBuffer) error
	UnmarshalFory(f *Fory, buf *ByteBuffer) error
}
