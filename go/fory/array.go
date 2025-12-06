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

// Array serializers

type arraySerializer struct{}

func (s arraySerializer) TypeId() TypeId       { return -LIST }
func (s arraySerializer) NeedToWriteRef() bool { return true }

func (s arraySerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	length := value.Len()
	buf.WriteVarUint32(uint32(length))
	for i := 0; i < length; i++ {
		elem := value.Index(i)
		buf.WriteInt8(NotNullValueFlag)
		_ = elem
	}
	return nil
}

func (s arraySerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	length := int(buf.ReadVarUint32())
	for i := 0; i < length; i++ {
		_ = buf.ReadInt8()
	}
	return nil
}

// arrayConcreteValueSerializer serialize an array/*array
type arrayConcreteValueSerializer struct {
	type_          reflect.Type
	elemSerializer Serializer
	referencable   bool
}

func (s *arrayConcreteValueSerializer) TypeId() TypeId      { return -LIST }
func (s arrayConcreteValueSerializer) NeedToWriteRef() bool { return true }

func (s *arrayConcreteValueSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	length := value.Len()
	buf.WriteVarUint32(uint32(length))
	for i := 0; i < length; i++ {
		elem := value.Index(i)
		if s.referencable {
			if isNull(elem) {
				buf.WriteInt8(NullFlag)
				continue
			}
			buf.WriteInt8(NotNullValueFlag)
		}
		if err := s.elemSerializer.Write(ctx, elem); err != nil {
			return err
		}
	}
	return nil
}

func (s *arrayConcreteValueSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	length := int(buf.ReadVarUint32())
	for i := 0; i < length && i < value.Len(); i++ {
		if s.referencable {
			flag := buf.ReadInt8()
			if flag == NullFlag {
				continue
			}
		}
		elem := value.Index(i)
		if err := s.elemSerializer.Read(ctx, elem.Type(), elem); err != nil {
			return err
		}
	}
	return nil
}

type byteArraySerializer struct{}

func (s byteArraySerializer) TypeId() TypeId       { return -BINARY }
func (s byteArraySerializer) NeedToWriteRef() bool { return false }

func (s byteArraySerializer) Write(ctx *WriteContext, value reflect.Value) error {
	length := value.Len()
	ctx.buffer.WriteVarUint32(uint32(length))
	if value.CanAddr() {
		ctx.buffer.WriteBinary(value.Slice(0, length).Bytes())
	} else {
		data := make([]byte, length)
		for i := 0; i < length; i++ {
			data[i] = byte(value.Index(i).Uint())
		}
		ctx.buffer.WriteBinary(data)
	}
	return nil
}

func (s byteArraySerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	length := int(ctx.buffer.ReadVarUint32())
	data := make([]byte, length)
	ctx.buffer.Read(data)
	if value.CanSet() {
		for i := 0; i < length && i < value.Len(); i++ {
			value.Index(i).SetUint(uint64(data[i]))
		}
	}
	return nil
}
