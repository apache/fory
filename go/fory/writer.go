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
	compatible     bool // Schema evolution compatibility mode
	depth          int
	maxDepth       int
	typeResolver   *TypeResolver           // For complex type serialization
	refResolver    *RefResolver            // For reference tracking (legacy)
	bufferCallback func(BufferObject) bool // Callback for out-of-band buffers
	outOfBand      bool                    // Whether out-of-band serialization is enabled
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

// Inline primitive writes (compiler will inline these)
func (c *WriteContext) RawBool(v bool)        { c.buffer.WriteBool(v) }
func (c *WriteContext) RawInt8(v int8)        { c.buffer.WriteByte_(byte(v)) }
func (c *WriteContext) RawInt16(v int16)      { c.buffer.WriteInt16(v) }
func (c *WriteContext) RawInt32(v int32)      { c.buffer.WriteInt32(v) }
func (c *WriteContext) RawInt64(v int64)      { c.buffer.WriteInt64(v) }
func (c *WriteContext) RawFloat32(v float32)  { c.buffer.WriteFloat32(v) }
func (c *WriteContext) RawFloat64(v float64)  { c.buffer.WriteFloat64(v) }
func (c *WriteContext) WriteVarInt32(v int32)   { c.buffer.WriteVarint32(v) }
func (c *WriteContext) WriteVarInt64(v int64)   { c.buffer.WriteVarint64(v) }
func (c *WriteContext) WriteVarUint32(v uint32) { c.buffer.WriteVarUint32(v) }
func (c *WriteContext) WriteByte(v byte)        { c.buffer.WriteByte_(v) }
func (c *WriteContext) WriteBytes(v []byte)     { c.buffer.WriteBinary(v) }

func (c *WriteContext) RawString(v string) {
	c.buffer.WriteVarUint32(uint32(len(v)))
	if len(v) > 0 {
		c.buffer.WriteBinary(unsafe.Slice(unsafe.StringData(v), len(v)))
	}
}

func (c *WriteContext) WriteBinary(v []byte) {
	c.buffer.WriteVarUint32(uint32(len(v)))
	c.buffer.WriteBinary(v)
}

func (c *WriteContext) WriteTypeId(id TypeId) {
	c.buffer.WriteInt16(id)
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

// WriteLength writes a length value as varint
func (c *WriteContext) WriteLength(length int) error {
	if length > MaxInt32 || length < MinInt32 {
		return fmt.Errorf("length %d exceeds int32 range", length)
	}
	c.buffer.WriteVarInt32(int32(length))
	return nil
}

// ============================================================================
// Typed Write Methods - Write primitives with optional ref/type info
// ============================================================================

// WriteBool writes a bool with optional ref/type info
func (c *WriteContext) WriteBool(value bool, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(BOOL)
	}
	c.buffer.WriteBool(value)
	return nil
}

// WriteInt8 writes an int8 with optional ref/type info
func (c *WriteContext) WriteInt8(value int8, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(INT8)
	}
	c.buffer.WriteInt8(value)
	return nil
}

// WriteInt16 writes an int16 with optional ref/type info
func (c *WriteContext) WriteInt16(value int16, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(INT16)
	}
	c.buffer.WriteInt16(value)
	return nil
}

// WriteInt32 writes an int32 with optional ref/type info
func (c *WriteContext) WriteInt32(value int32, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(INT32)
	}
	c.buffer.WriteVarint32(value)
	return nil
}

// WriteInt64 writes an int64 with optional ref/type info
func (c *WriteContext) WriteInt64(value int64, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(INT64)
	}
	c.buffer.WriteVarint64(value)
	return nil
}

// WriteInt writes an int with optional ref/type info
// Platform-dependent: uses int32 on 32-bit systems, int64 on 64-bit systems
func (c *WriteContext) WriteInt(value int, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		if strconv.IntSize == 64 {
			c.WriteTypeId(INT64)
		} else {
			c.WriteTypeId(INT32)
		}
	}
	if strconv.IntSize == 64 {
		c.buffer.WriteVarint64(int64(value))
	} else {
		c.buffer.WriteVarint32(int32(value))
	}
	return nil
}

// WriteFloat32 writes a float32 with optional ref/type info
func (c *WriteContext) WriteFloat32(value float32, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(FLOAT)
	}
	c.buffer.WriteFloat32(value)
	return nil
}

// WriteFloat64 writes a float64 with optional ref/type info
func (c *WriteContext) WriteFloat64(value float64, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(DOUBLE)
	}
	c.buffer.WriteFloat64(value)
	return nil
}

// WriteString writes a string with optional ref/type info
func (c *WriteContext) WriteString(value string, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(STRING)
	}
	c.buffer.WriteVarUint32(uint32(len(value)))
	if len(value) > 0 {
		c.buffer.WriteBinary(unsafe.Slice(unsafe.StringData(value), len(value)))
	}
	return nil
}

// WriteBoolSlice writes []bool with ref/type info
func (c *WriteContext) WriteBoolSlice(value []bool, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(BOOL_ARRAY)
	}
	return writeBoolSlice(c.buffer, value)
}

// WriteInt8Slice writes []int8 with ref/type info
func (c *WriteContext) WriteInt8Slice(value []int8, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(INT8_ARRAY)
	}
	return writeInt8Slice(c.buffer, value)
}

// WriteInt16Slice writes []int16 with ref/type info
func (c *WriteContext) WriteInt16Slice(value []int16, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(INT16_ARRAY)
	}
	return writeInt16Slice(c.buffer, value)
}

// WriteInt32Slice writes []int32 with ref/type info
func (c *WriteContext) WriteInt32Slice(value []int32, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(INT32_ARRAY)
	}
	return writeInt32Slice(c.buffer, value)
}

// WriteInt64Slice writes []int64 with ref/type info
func (c *WriteContext) WriteInt64Slice(value []int64, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(INT64_ARRAY)
	}
	return writeInt64Slice(c.buffer, value)
}

// WriteIntSlice writes []int with ref/type info
func (c *WriteContext) WriteIntSlice(value []int, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		if strconv.IntSize == 64 {
			c.WriteTypeId(INT64_ARRAY)
		} else {
			c.WriteTypeId(INT32_ARRAY)
		}
	}
	return writeIntSlice(c.buffer, value)
}

// WriteFloat32Slice writes []float32 with ref/type info
func (c *WriteContext) WriteFloat32Slice(value []float32, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(FLOAT32_ARRAY)
	}
	return writeFloat32Slice(c.buffer, value)
}

// WriteFloat64Slice writes []float64 with ref/type info
func (c *WriteContext) WriteFloat64Slice(value []float64, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(FLOAT64_ARRAY)
	}
	return writeFloat64Slice(c.buffer, value)
}

// WriteByteSlice writes []byte with ref/type info
func (c *WriteContext) WriteByteSlice(value []byte, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(BINARY)
	}
	c.buffer.WriteBool(true) // in-band
	c.buffer.WriteLength(len(value))
	c.buffer.WriteBinary(value)
	return nil
}

// WriteStringStringMap writes map[string]string with ref/type info
func (c *WriteContext) WriteStringStringMap(value map[string]string, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(MAP)
	}
	writeMapStringString(c.buffer, value)
	return nil
}

// WriteStringInt64Map writes map[string]int64 with ref/type info
func (c *WriteContext) WriteStringInt64Map(value map[string]int64, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(MAP)
	}
	writeMapStringInt64(c.buffer, value)
	return nil
}

// WriteStringIntMap writes map[string]int with ref/type info
func (c *WriteContext) WriteStringIntMap(value map[string]int, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(MAP)
	}
	writeMapStringInt(c.buffer, value)
	return nil
}

// WriteStringFloat64Map writes map[string]float64 with ref/type info
func (c *WriteContext) WriteStringFloat64Map(value map[string]float64, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(MAP)
	}
	writeMapStringFloat64(c.buffer, value)
	return nil
}

// WriteStringBoolMap writes map[string]bool with ref/type info
func (c *WriteContext) WriteStringBoolMap(value map[string]bool, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(MAP)
	}
	writeMapStringBool(c.buffer, value)
	return nil
}

// WriteInt32Int32Map writes map[int32]int32 with ref/type info
func (c *WriteContext) WriteInt32Int32Map(value map[int32]int32, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(MAP)
	}
	writeMapInt32Int32(c.buffer, value)
	return nil
}

// WriteInt64Int64Map writes map[int64]int64 with ref/type info
func (c *WriteContext) WriteInt64Int64Map(value map[int64]int64, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(MAP)
	}
	writeMapInt64Int64(c.buffer, value)
	return nil
}

// WriteIntIntMap writes map[int]int with ref/type info
func (c *WriteContext) WriteIntIntMap(value map[int]int, writeRefInfo, writeTypeInfo bool) error {
	if writeRefInfo {
		c.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeTypeInfo {
		c.WriteTypeId(MAP)
	}
	writeMapIntInt(c.buffer, value)
	return nil
}

// WriteBufferObject writes a buffer object
// If a buffer callback is set and returns false, the buffer is written out-of-band
func (c *WriteContext) WriteBufferObject(bufferObject BufferObject) error {
	// Check if we should write this buffer out-of-band
	inBand := true
	if c.bufferCallback != nil {
		inBand = c.bufferCallback(bufferObject)
	}

	c.buffer.WriteBool(inBand)
	if inBand {
		// Write the buffer data in-band
		size := bufferObject.TotalBytes()
		c.buffer.WriteLength(size)
		writerIndex := c.buffer.writerIndex
		c.buffer.grow(size)
		bufferObject.WriteTo(c.buffer.Slice(writerIndex, size))
		c.buffer.writerIndex += size
		if size > MaxInt32 {
			return fmt.Errorf("length %d exceeds max int32", size)
		}
	}
	// If out-of-band, we just write false (already done above) and the data is handled externally
	return nil
}

// WriteValue writes a polymorphic value with reference tracking and type info.
// This is used when the concrete type is not known at compile time.
func (c *WriteContext) WriteValue(value reflect.Value) error {
	return c.writeReferencable(value)
}

// writeReferencable writes a value with reference tracking
func (c *WriteContext) writeReferencable(value reflect.Value) error {
	return c.writeReferencableBySerializer(value, nil)
}

// writeReferencableBySerializer writes a value with reference tracking using a specific serializer
func (c *WriteContext) writeReferencableBySerializer(value reflect.Value, serializer Serializer) error {
	if refWritten, err := c.refResolver.WriteRefOrNull(c.buffer, value); err == nil && !refWritten {
		// check ptr
		if value.Kind() == reflect.Ptr {
			switch value.Elem().Kind() {
			case reflect.Ptr, reflect.Map, reflect.Slice, reflect.Interface:
				return fmt.Errorf("pointer to reference type %s is not supported", value.Type())
			}
		}
		return c.writeValue(value, serializer)
	} else {
		return err
	}
}

// writeValue writes a value using the type resolver
func (c *WriteContext) writeValue(value reflect.Value, serializer Serializer) error {
	// Handle interface values by getting their concrete element
	if value.Kind() == reflect.Interface {
		value = value.Elem()
	}

	// For array types, pre-convert the value
	if value.Kind() == reflect.Array {
		length := value.Len()
		sliceType := reflect.SliceOf(value.Type().Elem())
		slice := reflect.MakeSlice(sliceType, length, length)
		reflect.Copy(slice, value)
		value = slice
	}

	if serializer != nil {
		return serializer.Write(c, value)
	}

	// Get type information for the value
	typeInfo, err := c.typeResolver.getTypeInfo(value, true)
	if err != nil {
		return fmt.Errorf("cannot get typeinfo for value %v: %v", value, err)
	}
	err = c.typeResolver.writeTypeInfo(c.buffer, typeInfo)
	if err != nil {
		return fmt.Errorf("cannot write typeinfo for value %v: %v", value, err)
	}
	serializer = typeInfo.Serializer
	return serializer.Write(c, value)
}