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

// ptrToValueSerializer serializes a pointer to a concrete value
type ptrToValueSerializer struct {
	valueSerializer Serializer
}

func (s *ptrToValueSerializer) TypeId() TypeId {
	if id := s.valueSerializer.TypeId(); id < 0 {
		return id
	}
	return -s.valueSerializer.TypeId()
}

func (s *ptrToValueSerializer) NeedToWriteRef() bool { return true }

func (s *ptrToValueSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	elemValue := value.Elem()

	// In compatible mode, write typeInfo for struct types so TypeDefs are collected
	if ctx.Compatible() && s.valueSerializer.TypeId() == NAMED_STRUCT {
		typeInfo, err := ctx.TypeResolver().getTypeInfo(elemValue, true)
		if err != nil {
			return err
		}
		if err := ctx.TypeResolver().writeTypeInfo(ctx.Buffer(), typeInfo); err != nil {
			return err
		}
	}

	return s.valueSerializer.Write(ctx, elemValue)
}

func (s *ptrToValueSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	// Allocate new value and read into it
	newVal := reflect.New(type_.Elem())

	// In compatible mode, read typeInfo for struct types
	if ctx.Compatible() && s.valueSerializer.TypeId() == NAMED_STRUCT {
		// Read typeInfo (typeId + metaIndex) to consume the bytes written by Write
		_, err := ctx.TypeResolver().readTypeInfo(ctx.Buffer(), newVal.Elem())
		if err != nil {
			return err
		}
	}

	if err := s.valueSerializer.Read(ctx, type_.Elem(), newVal.Elem()); err != nil {
		return err
	}
	value.Set(newVal)
	return nil
}
