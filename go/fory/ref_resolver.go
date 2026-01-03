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
	"unsafe"
)

// ============================================================================
// RefMode - Controls reference handling behavior in serialization
// ============================================================================

// RefMode controls reference handling behavior in serialization/deserialization.
// It determines how null checks and reference tracking are performed.
type RefMode uint8

const (
	// RefModeNone - skip ref handling entirely.
	// The serializer should not read/write any ref/null flags.
	// Used for non-nullable primitives or when caller handles ref externally.
	RefModeNone RefMode = iota

	// RefModeNullOnly - only null check without reference tracking.
	// Write: NullFlag (-3) for nil, NotNullValueFlag (-1) for non-nil.
	// Read: Read flag and return early if null.
	// No circular reference tracking is performed.
	RefModeNullOnly

	// RefModeTracking - full reference tracking with circular reference support.
	// Write: Uses WriteRefOrNull which writes NullFlag, RefFlag+refId, or RefValueFlag.
	// Read: Uses TryPreserveRefId with reference resolution.
	RefModeTracking
)

// ============================================================================
// Reference flags
const (
	NullFlag         int8 = -3
	RefFlag          int8 = -2
	NotNullValueFlag int8 = -1
	RefValueFlag     int8 = 0
)

// ============================================================================
// RefResolver - Tracks references during serialization/deserialization
// ============================================================================

// RefResolver class is used to track objects that have already been read or written.
type RefResolver struct {
	refTracking    bool
	writtenObjects map[refKey]int32
	readObjects    []reflect.Value
	readRefIds     []int32
	readObject     reflect.Value // last read object which is not a reference
}

type refKey struct {
	pointer unsafe.Pointer
	length  int // for slice and *array only
}

// isReferencable determines if a type needs reference tracking based on Go type semantics
func isReferencable(t reflect.Type) bool {
	// Pointers, maps, slices, and interfaces need reference tracking
	kind := t.Kind()
	switch kind {
	case reflect.Ptr, reflect.Map, reflect.Slice, reflect.Interface:
		return true
	default:
		return false
	}
}

// isRefType determines if a type should track references.
// For xlang mode, only pointer to struct/ext, interface, slice, and map track refs.
// Arrays do NOT track refs in xlang mode (they are value types).
// For native Go mode, uses standard Go reference semantics.
//
// Note: Struct fields can provide tags to override ref tracking behavior for specific
// field types. However, if the value is not a struct field (e.g., top-level value,
// collection element), then this function's result is always used.
func isRefType(t reflect.Type, xlang bool) bool {
	kind := t.Kind()
	if xlang {
		switch kind {
		case reflect.Ptr:
			// Only pointer to struct tracks ref in xlang
			elemKind := t.Elem().Kind()
			return elemKind == reflect.Struct
		case reflect.Interface, reflect.Slice, reflect.Map:
			return true
		default:
			// Arrays and other types don't track refs in xlang
			return false
		}
	}
	// Native Go mode: pointers, maps, slices, and interfaces track refs
	return isReferencable(t)
}

func newRefResolver(refTracking bool) *RefResolver {
	refResolver := &RefResolver{
		refTracking:    refTracking,
		writtenObjects: map[refKey]int32{},
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
		elemValue := value.Elem()
		if elemValue.Kind() == reflect.Array {
			length = elemValue.Len()
		}
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
		// Struct values are value types, not reference types.
		// They should not participate in reference tracking.
		buffer.WriteInt8(NotNullValueFlag)
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
			buffer.WriteVaruint32(uint32(writtenId))
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
func (r *RefResolver) ReadRefOrNull(buffer *ByteBuffer, ctxErr *Error) int8 {
	refTag := buffer.ReadInt8(ctxErr)
	if !r.refTracking {
		return refTag
	}
	if refTag == RefFlag {
		// read ref id and get object from ref resolver
		refId := buffer.ReadVaruint32(ctxErr)
		r.readObject = r.GetReadObject(int32(refId))
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
	var ctxErr Error
	headFlag := buffer.ReadInt8(&ctxErr)
	if ctxErr.HasError() {
		return 0, ctxErr
	}
	if headFlag == RefFlag {
		// read ref id and get object from ref resolver
		refId := buffer.ReadVaruint32(&ctxErr)
		if ctxErr.HasError() {
			return 0, ctxErr
		}
		r.readObject = r.GetReadObject(int32(refId))
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
	if length == 0 {
		// No reference to track - this can happen for value types like arrays
		// that don't participate in reference tracking
		return
	}
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
	// Use clear() instead of allocating a new map to reduce allocations
	clear(r.writtenObjects)
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

// ============================================================================
// RefWriter - Handles reference tracking during serialization
// ============================================================================

// RefWriter handles reference tracking during serialization
type RefWriter struct {
	enabled bool
	refs    map[uintptr]int32
	nextId  int32
}

// NewRefWriter creates a new reference writer
func NewRefWriter(enabled bool) *RefWriter {
	return &RefWriter{
		enabled: enabled,
		refs:    make(map[uintptr]int32),
		nextId:  0,
	}
}

// Reset clears state for reuse
func (w *RefWriter) Reset() {
	clear(w.refs)
	w.nextId = 0
}

// TryWriteRef attempts to write a reference. Returns true if the value was already seen.
func (w *RefWriter) TryWriteRef(ctx *WriteContext, ptr uintptr) bool {
	if !w.enabled {
		return false
	}
	if refId, exists := w.refs[ptr]; exists {
		ctx.buffer.WriteInt8(RefFlag)
		ctx.buffer.WriteVaruint32(uint32(refId))
		return true
	}
	// First time seeing this reference
	w.refs[ptr] = w.nextId
	w.nextId++
	ctx.buffer.WriteInt8(RefValueFlag)
	return false
}

// WriteRefValue writes ref flag for a new value and registers it
func (w *RefWriter) WriteRefValue(ctx *WriteContext, ptr uintptr) {
	if w.enabled {
		w.refs[ptr] = w.nextId
		w.nextId++
		ctx.buffer.WriteInt8(RefValueFlag)
	} else {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
}

// ============================================================================
// RefReader - Handles reference tracking during deserialization
// ============================================================================

// RefReader handles reference tracking during deserialization
type RefReader struct {
	enabled bool
	refs    []any
}

// NewRefReader creates a new reference reader
func NewRefReader(enabled bool) *RefReader {
	return &RefReader{
		enabled: enabled,
		refs:    make([]any, 0, 16),
	}
}

// Reset clears state for reuse
func (r *RefReader) Reset() {
	r.refs = r.refs[:0]
}

// ReadRefFlag reads the reference flag and returns:
// - flag: the flag value
// - refId: the reference ID if flag is RefFlag
// - needRead: true if we need to read the actual data
func (r *RefReader) ReadRefFlag(ctx *ReadContext) (flag int8, refId int32, needRead bool) {
	flag = ctx.RawInt8()
	switch flag {
	case NullFlag:
		return flag, 0, false
	case RefFlag:
		refId = ctx.ReadVarint32()
		return flag, refId, false
	default: // RefValueFlag or NotNullValueFlag
		return flag, 0, true
	}
}

// Reference stores a reference for later retrieval
func (r *RefReader) Reference(value any) {
	if r.enabled {
		r.refs = append(r.refs, value)
	}
}

// GetRef retrieves a reference by ID
func (r *RefReader) GetRef(refId int32) any {
	if int(refId) < len(r.refs) {
		return r.refs[refId]
	}
	return nil
}
