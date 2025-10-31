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
	"encoding/json"
	"fmt"
	"reflect"
	"unsafe"
)

const (
	NullFlag int8 = -3
	// RefFlag indicates that object is a not-null value.
	// We don't use another byte to indicate REF, so that we can save one byte.
	RefFlag int8 = -2
	// NotNullValueFlag indicates that the object is a non-null value.
	NotNullValueFlag int8 = -1
	// RefValueFlag indicates that the object is a referencable and first read.
	RefValueFlag int8 = 0
)

// RefResolver class is used to track objects that have already been read or written.
type RefResolver struct {
	refTracking    bool
	writtenObjects map[refKey]int32
	readObjects    []reflect.Value
	readRefIds     []int32
	readObject     reflect.Value // last read object which is not a reference

	// basicValueCache caches boxed struct pointers keyed by their JSON representation to support reference tracking
	basicValueCache map[interface{}]reflect.Value
}

type refKey struct {
	pointer unsafe.Pointer
	length  int // for slice and *array only
}

func newRefResolver(refTracking bool) *RefResolver {
	refResolver := &RefResolver{
		refTracking:     refTracking,
		writtenObjects:  map[refKey]int32{},
		basicValueCache: map[interface{}]reflect.Value{},
	}
	return refResolver
}

// WriteRefOrNull write reference and tag for the value if the value has been written previously,
// write null/not-null tag otherwise. Returns true if no bytes need to be written for the object.
// See https://go101.org/article/value-part.html for internal structure definitions of common types.
// Note that for slice and substring, if the start addr or length are different, we take two objects as
// different references.
func (r *RefResolver) WriteRefOrNull(buffer *ByteBuffer, value reflect.Value) (refWritten bool, err error) {
	if !r.refTracking || value.Kind() == reflect.String {
		if isNil(value) {
			buffer.WriteInt8(NullFlag)
			return true, nil
		} else {
			buffer.WriteInt8(NotNullValueFlag)
			return false, nil
		}
	}
	length := 0
	isNil := false
	kind := value.Kind()
	// reference types such as channel/function are not handled here and will be handled by typeResolver.
	switch kind {
	case reflect.Ptr:
		isNil = value.IsNil()
	case reflect.Map:
		isNil = value.IsNil()
	case reflect.Slice:
		isNil = value.IsNil()
		length = value.Len()
	case reflect.Interface:
		value = value.Elem()
		return r.WriteRefOrNull(buffer, value)
	case reflect.Invalid:
		isNil = true
	case reflect.Struct:
		// Adds reference tracking for struct types
		raw, _ := json.Marshal(value.Interface())
		key := string(raw)
		boxed, ok := r.basicValueCache[key]
		if !ok {
			boxed = reflect.New(value.Type())
			boxed.Elem().Set(value)
			r.basicValueCache[key] = boxed
		}
		ptr := unsafe.Pointer(boxed.Pointer())
		refKey := refKey{pointer: ptr, length: 0}
		if writtenId, ok := r.writtenObjects[refKey]; ok {
			buffer.WriteInt8(RefFlag)
			buffer.WriteVarInt32(writtenId)
			return true, nil
		}
		newWriteRefId := len(r.writtenObjects)
		if newWriteRefId >= MaxInt32 {
			return false, fmt.Errorf("too many objects execced %d to serialize", MaxInt32)
		}
		r.writtenObjects[refKey] = int32(newWriteRefId)
		buffer.WriteInt8(RefValueFlag)
		return false, nil
	default:
		// The object is being written for the first time.
		buffer.WriteInt8(NotNullValueFlag)
		return false, nil
	}
	if isNil {
		buffer.WriteInt8(NullFlag)
		return true, nil
	} else {
		refKey := refKey{pointer: unsafe.Pointer(value.Pointer()), length: length}
		if writtenId, ok := r.writtenObjects[refKey]; ok {
			// The obj has been written previously.
			buffer.WriteInt8(RefFlag)
			buffer.WriteVarInt32(writtenId)
			return true, nil
		} else {
			// The id should be consistent with `nextReadRefId`
			newWriteRefId := len(r.writtenObjects)
			if newWriteRefId >= MaxInt32 {
				return false, fmt.Errorf("too many objects execced %d to serialize", MaxInt32)
			}
			r.writtenObjects[refKey] = int32(newWriteRefId)
			buffer.WriteInt8(RefValueFlag)
			return false, nil
		}
	}
}

// ReadRefOrNull returns RefFlag if a ref to a previously read object
// was read. Returns NullFlag if the object is null. Returns RefValueFlag if the object is not
// null and ref tracking is not enabled or the object is first read.
func (r *RefResolver) ReadRefOrNull(buffer *ByteBuffer) int8 {
	refTag := buffer.ReadInt8()
	if !r.refTracking {
		return refTag
	}
	if refTag == RefFlag {
		// read ref id and get object from ref resolver
		refId := buffer.ReadVarInt32()
		r.readObject = r.GetReadObject(refId)
		return RefFlag
	} else {
		r.readObject = reflect.Value{}
	}
	return refTag
}

// PreserveRefId preserve a ref id, which is used by Reference / SetReadObject to
// set up reference for object that is first deserialized.
// Returns a ref id or -1 if reference is not enabled.
func (r *RefResolver) PreserveRefId() (int32, error) {
	if !r.refTracking {
		return -1, nil
	}
	nextReadRefId_ := len(r.readObjects)
	if nextReadRefId_ > MaxInt32 {
		return 0, fmt.Errorf("referencable objects exceeds max int32")
	}
	nextReadRefId := int32(nextReadRefId_)
	r.readObjects = append(r.readObjects, reflect.Value{})
	r.readRefIds = append(r.readRefIds, nextReadRefId)
	return nextReadRefId, nil
}

func (r *RefResolver) TryPreserveRefId(buffer *ByteBuffer) (int32, error) {
	headFlag := buffer.ReadInt8()
	if headFlag == RefFlag {
		// read ref id and get object from ref resolver
		refId := buffer.ReadVarInt32()
		r.readObject = r.GetReadObject(refId)
	} else {
		r.readObject = reflect.Value{}
		if headFlag == RefValueFlag {
			return r.PreserveRefId()
		}
	}
	// `headFlag` except `REF_FLAG` can be used as stub ref id because we use
	// `refId >= NOT_NULL_VALUE_FLAG` to read data.
	return int32(headFlag), nil
}

// Reference tracking references relationship. Call this method immediately after composited object such as
// object array/map/collection/bean is created so that circular reference can be deserialized correctly.
func (r *RefResolver) Reference(value reflect.Value) {
	if !r.refTracking {
		return
	}
	length := len(r.readRefIds)
	refId := r.readRefIds[length-1]
	r.readRefIds = r.readRefIds[:length-1]
	r.SetReadObject(refId, value)
}

// GetReadObject returns the object for the specified id.
func (r *RefResolver) GetReadObject(refId int32) reflect.Value {
	if !r.refTracking {
		return reflect.Value{}
	}
	if refId < 0 {
		return r.readObject
	}
	return r.readObjects[refId]
}

func (r *RefResolver) GetCurrentReadObject() reflect.Value {
	return r.readObject
}

// SetReadObject sets the id for an object that has been read.
// id: The id from {@link #NextReadRefId}.
// object: the object that has been read
func (r *RefResolver) SetReadObject(refId int32, value reflect.Value) {
	if !r.refTracking {
		return
	}
	if refId >= 0 {
		r.readObjects[refId] = value
	}
}

func (r *RefResolver) reset() {
	r.resetRead()
	r.resetWrite()
}

func (r *RefResolver) resetRead() {
	if !r.refTracking {
		return
	}
	r.readObjects = nil
	r.readRefIds = nil
	r.readObject = reflect.Value{}
}

func (r *RefResolver) resetWrite() {
	if len(r.writtenObjects) > 0 {
		r.writtenObjects = map[refKey]int32{}
	}
}

func nullable(type_ reflect.Type) bool {
	// Since we can't get value type from interface type, so we return true for interface type
	switch type_.Kind() {
	case reflect.Chan, reflect.Func, reflect.Map, reflect.Ptr, reflect.Slice, reflect.Interface, reflect.String, reflect.Array:
		return true
	}
	return false
}

func isNil(value reflect.Value) bool {
	switch value.Kind() {
	case reflect.Chan, reflect.Func, reflect.Map, reflect.Ptr, reflect.Slice:
		return value.IsNil()
	case reflect.Interface:
		if value.IsValid() {
			return value.IsNil() || isNil(value.Elem())
		} else {
			return true
		}
	case reflect.Invalid:
		return true
	}
	return false
}
