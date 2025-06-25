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
	"sort"
	"unicode"
	"unicode/utf8"
)

type structSerializer struct {
	typeTag    string
	type_      reflect.Type
	fieldsInfo structFieldsInfo
	structHash int32
}

func (s *structSerializer) TypeId() TypeId {
	return NAMED_STRUCT
}

func (s *structSerializer) NeedWriteRef() bool {
	return true
}

func (s *structSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	// TODO support fields back and forward compatible. need to serialize fields name too.
	if s.fieldsInfo == nil {
		if fieldsInfo, err := createStructFieldInfos(f, s.type_); err != nil {
			return err
		} else {
			s.fieldsInfo = fieldsInfo
		}
	}
	if s.structHash == 0 {
		if hash, err := computeStructHash(s.fieldsInfo, f.typeResolver); err != nil {
			return err
		} else {
			s.structHash = hash
		}
	}

	buf.WriteInt32(s.structHash)
	for _, fieldInfo_ := range s.fieldsInfo {
		fieldValue := value.Field(fieldInfo_.fieldIndex)
		if fieldInfo_.serializer != nil {
			err := writeBySerializer(f, buf, fieldValue, fieldInfo_.serializer, fieldInfo_.referencable)
			if err != nil {
				return err
			}
		} else {
			if err := f.WriteReferencable(buf, fieldValue); err != nil {
				return err
			}
		}
	}
	return nil
}

func (s *structSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	// struct value may be a value type if it's not a pointer, so we don't invoke `refResolver.Reference` here,
	// but invoke it in `ptrToStructSerializer` instead.
	if s.fieldsInfo == nil {
		if infos, err := createStructFieldInfos(f, s.type_); err != nil {
			return err
		} else {
			s.fieldsInfo = infos
		}
	}
	if s.structHash == 0 {
		if hash, err := computeStructHash(s.fieldsInfo, f.typeResolver); err != nil {
			return err
		} else {
			s.structHash = hash
		}
	}
	structHash := buf.ReadInt32()
	if structHash != s.structHash {
		return fmt.Errorf("hash %d is not consistent with %d for type %s",
			structHash, s.structHash, s.type_)
	}
	for _, fieldInfo_ := range s.fieldsInfo {
		fieldValue := value.Field(fieldInfo_.fieldIndex)
		fieldSerializer := fieldInfo_.serializer
		if fieldSerializer != nil {
			if err := readBySerializer(f, buf, fieldValue, fieldSerializer, fieldInfo_.referencable); err != nil {
				return err
			}
		} else {
			if err := f.ReadReferencable(buf, fieldValue); err != nil {
				return err
			}
		}
	}
	return nil
}

func createStructFieldInfos(f *Fory, type_ reflect.Type) (structFieldsInfo, error) {
	var fields structFieldsInfo
	for i := 0; i < type_.NumField(); i++ {
		field := type_.Field(i)
		firstRune, _ := utf8.DecodeRuneInString(field.Name)
		if unicode.IsLower(firstRune) {
			continue
		}
		fieldSerializer, _ := f.typeResolver.getSerializerByType(field.Type)
		f := fieldInfo{
			name:         SnakeCase(field.Name), // TODO field name to lower case
			field:        field,
			fieldIndex:   i,
			type_:        field.Type,
			referencable: nullable(field.Type),
			serializer:   fieldSerializer,
		}
		fields = append(fields, &f)
	}
	sort.Sort(fields)
	return fields, nil
}

type fieldInfo struct {
	name         string
	field        reflect.StructField
	fieldIndex   int
	type_        reflect.Type
	referencable bool
	// maybe be nil: for interface fields, we need to check whether the value is a Reference.
	serializer Serializer
}

type structFieldsInfo []*fieldInfo

func (x structFieldsInfo) Len() int { return len(x) }
func (x structFieldsInfo) Less(i, j int) bool {
	return x[i].name < x[j].name
}
func (x structFieldsInfo) Swap(i, j int) { x[i], x[j] = x[j], x[i] }

// ptrToStructSerializer serialize a *struct
type ptrToStructSerializer struct {
	type_ reflect.Type
	structSerializer
}

func (s *ptrToStructSerializer) TypeId() TypeId {
	return -NAMED_STRUCT
}

func (s *ptrToStructSerializer) NeedWriteRef() bool {
	return true
}

func (s *ptrToStructSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	return s.structSerializer.Write(f, buf, value.Elem())
}

func (s *ptrToStructSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	newValue := reflect.New(type_.Elem())
	value.Set(newValue)
	elem := newValue.Elem()
	f.refResolver.Reference(newValue)
	return s.structSerializer.Read(f, buf, type_.Elem(), elem)
}

func computeStructHash(fieldsInfo structFieldsInfo, typeResolver *typeResolver) (int32, error) {
	var hash int32 = 17
	for _, f := range fieldsInfo {
		if newHash, err := computeFieldHash(hash, f, typeResolver); err != nil {
			return 0, err
		} else {
			hash = newHash
		}
	}
	if hash == 0 {
		panic(fmt.Errorf("hash for type %v is 0", fieldsInfo))
	}
	return hash, nil
}

func computeFieldHash(hash int32, fieldInfo *fieldInfo, typeResolver *typeResolver) (int32, error) {
	ser := fieldInfo.serializer
	var id int32
	switch s := ser.(type) {
	case *structSerializer:
		id = computeStringHash(s.typeTag + s.type_.Name())

	case *ptrToStructSerializer:
		id = computeStringHash(s.typeTag + s.type_.Elem().Name())

	default:
		if s == nil {
			id = 0
		} else {
			tid := s.TypeId()
			if tid < 0 {
				id = -int32(tid)
			} else {
				id = int32(tid)
			}
		}
	}

	newHash := int64(hash)*31 + int64(id)
	for newHash >= MaxInt32 {
		newHash /= 7
	}
	return int32(newHash), nil
}
