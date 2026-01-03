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
	"strconv"
	"unsafe"
)

// ============================================================================
// WriteContext - Holds all state needed during serialization
// ============================================================================

// WriteContext holds all state needed during serialization.
// It replaces passing multiple parameters to every method.
type WriteContext struct {
	buffer         *ByteBuffer
	refWriter      *RefWriter
	trackRef       bool // Cached flag to avoid indirection
	xlang          bool // Cross-language serialization mode
	compatible     bool // Schema evolution compatibility mode
	depth          int
	maxDepth       int
	typeResolver   *TypeResolver           // For complex type serialization
	refResolver    *RefResolver            // For reference tracking (legacy)
	bufferCallback func(BufferObject) bool // Callback for out-of-band buffers
	outOfBand      bool                    // Whether out-of-band serialization is enabled
	err            Error                   // Accumulated error state for deferred checking
}

// IsXlang returns whether cross-language serialization mode is enabled
func (c *WriteContext) IsXlang() bool {
	return c.xlang
}

// NewWriteContext creates a new write context
func NewWriteContext(trackRef bool, maxDepth int) *WriteContext {
	return &WriteContext{
		buffer:    NewByteBuffer(nil),
		refWriter: NewRefWriter(trackRef),
		trackRef:  trackRef,
		maxDepth:  maxDepth,
	}
}

// Reset clears state for reuse (called before each Serialize)
func (c *WriteContext) Reset() {
	c.buffer.Reset()
	c.refWriter.Reset()
	c.depth = 0
	c.err = Error{} // Clear error state
	if c.refResolver != nil {
		c.refResolver.resetWrite()
	}
	if c.typeResolver != nil {
		c.typeResolver.resetWrite()
	}
}

// ResetState clears internal state but NOT the buffer.
// Use this when streaming multiple values to an external buffer.
func (c *WriteContext) ResetState() {
	c.refWriter.Reset()
	c.depth = 0
	c.bufferCallback = nil
	c.outOfBand = false
	if c.refResolver != nil {
		c.refResolver.resetWrite()
	}
	if c.typeResolver != nil {
		c.typeResolver.resetWrite()
	}
}

// Buffer returns the underlying buffer
func (c *WriteContext) Buffer() *ByteBuffer {
	return c.buffer
}

// TrackRef returns whether reference tracking is enabled
func (c *WriteContext) TrackRef() bool {
	return c.trackRef
}

// Compatible returns whether schema evolution compatibility mode is enabled
func (c *WriteContext) Compatible() bool {
	return c.compatible
}

// TypeResolver returns the type resolver
func (c *WriteContext) TypeResolver() *TypeResolver {
	return c.typeResolver
}

// RefResolver returns the reference resolver (legacy)
func (c *WriteContext) RefResolver() *RefResolver {
	return c.refResolver
}

// ============================================================================
// Error State Methods - For deferred error checking pattern
// ============================================================================

// HasError returns true if an error has occurred
func (c *WriteContext) HasError() bool {
	return c.err.HasError()
}

// Err returns a pointer to the accumulated error
func (c *WriteContext) Err() *Error {
	return &c.err
}

// SetError sets the error state if no error has occurred yet (first error wins)
func (c *WriteContext) SetError(e Error) {
	if c.err.Ok() {
		c.err = e
	}
}

// TakeError returns the current error and resets the error state
func (c *WriteContext) TakeError() Error {
	e := c.err
	c.err = Error{}
	return e
}

// CheckError checks if an error has occurred and returns it as a standard error
func (c *WriteContext) CheckError() error {
	if c.err.HasError() {
		return c.TakeError()
	}
	return nil
}

// Inline primitive writes (compiler will inline these)
func (c *WriteContext) RawBool(v bool)          { c.buffer.WriteBool(v) }
func (c *WriteContext) RawInt8(v int8)          { c.buffer.WriteByte_(byte(v)) }
func (c *WriteContext) RawInt16(v int16)        { c.buffer.WriteInt16(v) }
func (c *WriteContext) RawInt32(v int32)        { c.buffer.WriteInt32(v) }
func (c *WriteContext) RawInt64(v int64)        { c.buffer.WriteInt64(v) }
func (c *WriteContext) RawFloat32(v float32)    { c.buffer.WriteFloat32(v) }
func (c *WriteContext) RawFloat64(v float64)    { c.buffer.WriteFloat64(v) }
func (c *WriteContext) WriteVarint32(v int32)   { c.buffer.WriteVarint32(v) }
func (c *WriteContext) WriteVarint64(v int64)   { c.buffer.WriteVarint64(v) }
func (c *WriteContext) WriteVaruint32(v uint32) { c.buffer.WriteVaruint32(v) }
func (c *WriteContext) WriteByte(v byte)        { c.buffer.WriteByte_(v) }
func (c *WriteContext) WriteBytes(v []byte)     { c.buffer.WriteBinary(v) }

func (c *WriteContext) RawString(v string) {
	c.buffer.WriteVaruint32(uint32(len(v)))
	if len(v) > 0 {
		c.buffer.WriteBinary(unsafe.Slice(unsafe.StringData(v), len(v)))
	}
}

func (c *WriteContext) WriteBinary(v []byte) {
	c.buffer.WriteVaruint32(uint32(len(v)))
	c.buffer.WriteBinary(v)
}

func (c *WriteContext) WriteTypeId(id TypeId) {
	// Use Varuint32Small7 encoding to match Java's xlang serialization
	c.buffer.WriteVaruint32Small7(uint32(id))
}

// writeFast writes a value using fast path based on StaticTypeId
func (c *WriteContext) writeFast(ptr unsafe.Pointer, ct StaticTypeId) {
	switch ct {
	case ConcreteTypeBool:
		c.buffer.WriteBool(*(*bool)(ptr))
	case ConcreteTypeInt8:
		c.buffer.WriteByte_(*(*byte)(ptr))
	case ConcreteTypeInt16:
		c.buffer.WriteInt16(*(*int16)(ptr))
	case ConcreteTypeInt32:
		c.buffer.WriteVarint32(*(*int32)(ptr))
	case ConcreteTypeInt:
		if strconv.IntSize == 64 {
			c.buffer.WriteVarint64(int64(*(*int)(ptr)))
		} else {
			c.buffer.WriteVarint32(int32(*(*int)(ptr)))
		}
	case ConcreteTypeInt64:
		c.buffer.WriteVarint64(*(*int64)(ptr))
	case ConcreteTypeFloat32:
		c.buffer.WriteFloat32(*(*float32)(ptr))
	case ConcreteTypeFloat64:
		c.buffer.WriteFloat64(*(*float64)(ptr))
	case ConcreteTypeString:
		writeString(c.buffer, *(*string)(ptr))
	}
}

// WriteLength writes a length value as varint (non-negative values)
func (c *WriteContext) WriteLength(length int) {
	if length > MaxInt32 || length < MinInt32 {
		c.SetError(SerializationErrorf("length %d exceeds int32 range", length))
		return
	}
	c.buffer.WriteVaruint32(uint32(length))
}

// ============================================================================
// Typed Write Methods - Fastpath for codegen
// For primitive numeric types, use ctx.Buffer().WriteXXX()
// For strings, use ctx.WriteString()
// For slices/maps, use these methods which handle ref tracking
// ============================================================================

// WriteString writes a string value (caller handles nullable/type meta)
func (c *WriteContext) WriteString(value string) {
	writeString(c.buffer, value)
}

// WriteBoolSlice writes []bool with ref/type info
func (c *WriteContext) WriteBoolSlice(value []bool, refMode RefMode, writeTypeInfo bool) {
	if refMode != RefModeNone {
		if value == nil {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(BOOL_ARRAY)
	}
	WriteBoolSlice(c.buffer, value)
}

// WriteInt8Slice writes []int8 with ref/type info
func (c *WriteContext) WriteInt8Slice(value []int8, refMode RefMode, writeTypeInfo bool) {
	if refMode != RefModeNone {
		if value == nil {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(INT8_ARRAY)
	}
	WriteInt8Slice(c.buffer, value)
}

// WriteInt16Slice writes []int16 with ref/type info
func (c *WriteContext) WriteInt16Slice(value []int16, refMode RefMode, writeTypeInfo bool) {
	if refMode != RefModeNone {
		if value == nil {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(INT16_ARRAY)
	}
	WriteInt16Slice(c.buffer, value)
}

// WriteInt32Slice writes []int32 with ref/type info
func (c *WriteContext) WriteInt32Slice(value []int32, refMode RefMode, writeTypeInfo bool) {
	if refMode != RefModeNone {
		if value == nil {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(INT32_ARRAY)
	}
	WriteInt32Slice(c.buffer, value)
}

// WriteInt64Slice writes []int64 with ref/type info
func (c *WriteContext) WriteInt64Slice(value []int64, refMode RefMode, writeTypeInfo bool) {
	if refMode != RefModeNone {
		if value == nil {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(INT64_ARRAY)
	}
	WriteInt64Slice(c.buffer, value)
}

// WriteIntSlice writes []int with ref/type info
func (c *WriteContext) WriteIntSlice(value []int, refMode RefMode, writeTypeInfo bool) {
	if refMode != RefModeNone {
		if value == nil {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		if strconv.IntSize == 64 {
			c.WriteTypeId(INT64_ARRAY)
		} else {
			c.WriteTypeId(INT32_ARRAY)
		}
	}
	WriteIntSlice(c.buffer, value)
}

// WriteUintSlice writes []uint with ref/type info
func (c *WriteContext) WriteUintSlice(value []uint, refMode RefMode, writeTypeInfo bool) {
	if refMode != RefModeNone {
		if value == nil {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		if strconv.IntSize == 64 {
			c.WriteTypeId(UINT64_ARRAY)
		} else {
			c.WriteTypeId(UINT32_ARRAY)
		}
	}
	WriteUintSlice(c.buffer, value)
}

// WriteFloat32Slice writes []float32 with ref/type info
func (c *WriteContext) WriteFloat32Slice(value []float32, refMode RefMode, writeTypeInfo bool) {
	if refMode != RefModeNone {
		if value == nil {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(FLOAT32_ARRAY)
	}
	WriteFloat32Slice(c.buffer, value)
}

// WriteFloat64Slice writes []float64 with ref/type info
func (c *WriteContext) WriteFloat64Slice(value []float64, refMode RefMode, writeTypeInfo bool) {
	if refMode != RefModeNone {
		if value == nil {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(FLOAT64_ARRAY)
	}
	WriteFloat64Slice(c.buffer, value)
}

// WriteByteSlice writes []byte with ref/type info
func (c *WriteContext) WriteByteSlice(value []byte, refMode RefMode, writeTypeInfo bool) {
	if refMode != RefModeNone {
		if value == nil {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(BINARY)
	}
	c.buffer.WriteBool(true) // in-band
	c.buffer.WriteLength(len(value))
	c.buffer.WriteBinary(value)
}

// WriteStringSlice writes []string with ref/type info using LIST protocol.
// hasGenerics indicates whether element type is known from TypeDef/generics (struct field context).
func (c *WriteContext) WriteStringSlice(value []string, refMode RefMode, writeTypeInfo bool, hasGenerics bool) {
	if refMode != RefModeNone {
		if value == nil {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(LIST)
	}
	WriteStringSlice(c.buffer, value, hasGenerics)
}

// WriteStringStringMap writes map[string]string with ref/type info
func (c *WriteContext) WriteStringStringMap(value map[string]string, refMode RefMode, writeTypeInfo bool) {
	if refMode != RefModeNone {
		if value == nil {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(MAP)
	}
	writeMapStringString(c.buffer, value, false)
}

// WriteStringInt64Map writes map[string]int64 with ref/type info
func (c *WriteContext) WriteStringInt64Map(value map[string]int64, refMode RefMode, writeTypeInfo bool) {
	if refMode != RefModeNone {
		if value == nil {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(MAP)
	}
	writeMapStringInt64(c.buffer, value, false)
}

// WriteStringInt32Map writes map[string]int32 with ref/type info
func (c *WriteContext) WriteStringInt32Map(value map[string]int32, refMode RefMode, writeTypeInfo bool) {
	if refMode != RefModeNone {
		if value == nil {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(MAP)
	}
	writeMapStringInt32(c.buffer, value, false)
}

// WriteStringIntMap writes map[string]int with ref/type info
func (c *WriteContext) WriteStringIntMap(value map[string]int, refMode RefMode, writeTypeInfo bool) {
	if refMode != RefModeNone {
		if value == nil {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(MAP)
	}
	writeMapStringInt(c.buffer, value, false)
}

// WriteStringFloat64Map writes map[string]float64 with ref/type info
func (c *WriteContext) WriteStringFloat64Map(value map[string]float64, refMode RefMode, writeTypeInfo bool) {
	if refMode != RefModeNone {
		if value == nil {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(MAP)
	}
	writeMapStringFloat64(c.buffer, value, false)
}

// WriteStringBoolMap writes map[string]bool with ref/type info
func (c *WriteContext) WriteStringBoolMap(value map[string]bool, refMode RefMode, writeTypeInfo bool) {
	if refMode != RefModeNone {
		if value == nil {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(MAP)
	}
	writeMapStringBool(c.buffer, value, false)
}

// WriteInt32Int32Map writes map[int32]int32 with ref/type info
func (c *WriteContext) WriteInt32Int32Map(value map[int32]int32, refMode RefMode, writeTypeInfo bool) {
	if refMode != RefModeNone {
		if value == nil {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(MAP)
	}
	writeMapInt32Int32(c.buffer, value, false)
}

// WriteInt64Int64Map writes map[int64]int64 with ref/type info
func (c *WriteContext) WriteInt64Int64Map(value map[int64]int64, refMode RefMode, writeTypeInfo bool) {
	if refMode != RefModeNone {
		if value == nil {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(MAP)
	}
	writeMapInt64Int64(c.buffer, value, false)
}

// WriteIntIntMap writes map[int]int with ref/type info
func (c *WriteContext) WriteIntIntMap(value map[int]int, refMode RefMode, writeTypeInfo bool) {
	if refMode != RefModeNone {
		if value == nil {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(MAP)
	}
	writeMapIntInt(c.buffer, value, false)
}

// WriteBufferObject writes a buffer object
// If a buffer callback is set and returns false, the buffer is written out-of-band
func (c *WriteContext) WriteBufferObject(bufferObject BufferObject) {
	// Check if we should write this buffer out-of-band
	inBand := true
	if c.bufferCallback != nil {
		inBand = c.bufferCallback(bufferObject)
	}

	c.buffer.WriteBool(inBand)
	if inBand {
		// WriteData the buffer data in-band
		size := bufferObject.TotalBytes()
		c.buffer.WriteLength(size)
		writerIndex := c.buffer.writerIndex
		c.buffer.grow(size)
		bufferObject.WriteTo(c.buffer.Slice(writerIndex, size))
		c.buffer.writerIndex += size
		if size > MaxInt32 {
			c.SetError(SerializationErrorf("length %d exceeds max int32", size))
		}
	}
	// If out-of-band, we just write false (already done above) and the data is handled externally
}

// WriteValue writes a polymorphic value with configurable reference tracking and type info.
// This is used when the concrete type is not known at compile time.
// Parameters:
//   - refMode: controls reference tracking behavior (RefModeNone, RefModeTracking, RefModeNullOnly)
//   - writeType: if true, writes type info before the value
func (c *WriteContext) WriteValue(value reflect.Value, refMode RefMode, writeType bool) {
	// Handle interface values by getting their concrete element
	if value.Kind() == reflect.Interface {
		if !value.IsValid() || value.IsNil() {
			c.buffer.WriteInt8(NullFlag)
			return
		}
		value = value.Elem()
	}

	// Handle invalid values (nil interface)
	if !value.IsValid() {
		c.buffer.WriteInt8(NullFlag)
		return
	}

	// Check for pointer to reference type (not supported)
	if value.Kind() == reflect.Ptr {
		switch value.Elem().Kind() {
		case reflect.Ptr, reflect.Map, reflect.Slice, reflect.Interface:
			c.SetError(SerializationErrorf("pointer to reference type %s is not supported", value.Type()))
			return
		}
	}

	// For array types, pre-convert the value to slice
	if value.Kind() == reflect.Array {
		length := value.Len()
		sliceType := reflect.SliceOf(value.Type().Elem())
		slice := reflect.MakeSlice(sliceType, length, length)
		reflect.Copy(slice, value)
		value = slice
	}

	// Get type information and serializer for the value
	typeInfo, err := c.typeResolver.getTypeInfo(value, true)
	if err != nil {
		c.SetError(SerializationErrorf("cannot get typeinfo for value %v: %v", value, err))
		return
	}

	// Use serializer's Write method which handles ref tracking and type info internally
	typeInfo.Serializer.Write(c, refMode, writeType, false, value)
}
