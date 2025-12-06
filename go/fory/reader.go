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
// ReadContext - Holds all state needed during deserialization
// ============================================================================

// ReadContext holds all state needed during deserialization.
type ReadContext struct {
	buffer           *ByteBuffer
	refReader        *RefReader
	trackRef         bool          // Cached flag to avoid indirection
	compatible       bool          // Schema evolution compatibility mode
	typeResolver     *TypeResolver // For complex type deserialization
	refResolver      *RefResolver  // For reference tracking (legacy)
	outOfBandBuffers []*ByteBuffer // Out-of-band buffers for deserialization
	outOfBandIndex   int           // Current index into out-of-band buffers
}

// NewReadContext creates a new read context
func NewReadContext(trackRef bool) *ReadContext {
	return &ReadContext{
		buffer:    NewByteBuffer(nil),
		refReader: NewRefReader(trackRef),
		trackRef:  trackRef,
	}
}

// Reset clears state for reuse (called before each Deserialize)
func (c *ReadContext) Reset() {
	c.refReader.Reset()
	c.outOfBandBuffers = nil
	c.outOfBandIndex = 0
	if c.refResolver != nil {
		c.refResolver.resetRead()
	}
	if c.typeResolver != nil {
		c.typeResolver.resetRead()
	}
}

// SetData sets new input data (for buffer reuse)
func (c *ReadContext) SetData(data []byte) {
	c.buffer = NewByteBuffer(data)
}

// Buffer returns the underlying buffer
func (c *ReadContext) Buffer() *ByteBuffer {
	return c.buffer
}

// TrackRef returns whether reference tracking is enabled
func (c *ReadContext) TrackRef() bool {
	return c.trackRef
}

// Compatible returns whether schema evolution compatibility mode is enabled
func (c *ReadContext) Compatible() bool {
	return c.compatible
}

// TypeResolver returns the type resolver
func (c *ReadContext) TypeResolver() *TypeResolver {
	return c.typeResolver
}

// RefResolver returns the reference resolver (legacy)
func (c *ReadContext) RefResolver() *RefResolver {
	return c.refResolver
}

// Inline primitive reads
func (c *ReadContext) RawBool() bool        { return c.buffer.ReadBool() }
func (c *ReadContext) RawInt8() int8        { return int8(c.buffer.ReadByte_()) }
func (c *ReadContext) RawInt16() int16      { return c.buffer.ReadInt16() }
func (c *ReadContext) RawInt32() int32      { return c.buffer.ReadInt32() }
func (c *ReadContext) RawInt64() int64      { return c.buffer.ReadInt64() }
func (c *ReadContext) RawFloat32() float32  { return c.buffer.ReadFloat32() }
func (c *ReadContext) RawFloat64() float64  { return c.buffer.ReadFloat64() }
func (c *ReadContext) ReadVarInt32() int32   { return c.buffer.ReadVarint32() }
func (c *ReadContext) ReadVarInt64() int64   { return c.buffer.ReadVarint64() }
func (c *ReadContext) ReadVarUint32() uint32 { return c.buffer.ReadVarUint32() }
func (c *ReadContext) ReadByte() byte        { return c.buffer.ReadByte_() }

func (c *ReadContext) RawString() string {
	length := c.buffer.ReadVarUint32()
	if length == 0 {
		return ""
	}
	data := c.buffer.ReadBinary(int(length))
	return string(data)
}

func (c *ReadContext) ReadBinary() []byte {
	length := c.buffer.ReadVarUint32()
	return c.buffer.ReadBinary(int(length))
}

func (c *ReadContext) ReadTypeId() TypeId {
	return c.buffer.ReadInt16()
}

// readFast reads a value using fast path based on StaticTypeId
func (c *ReadContext) readFast(ptr unsafe.Pointer, ct StaticTypeId) {
	switch ct {
	case ConcreteTypeBool:
		*(*bool)(ptr) = c.buffer.ReadBool()
	case ConcreteTypeInt8:
		*(*int8)(ptr) = int8(c.buffer.ReadByte_())
	case ConcreteTypeInt16:
		*(*int16)(ptr) = c.buffer.ReadInt16()
	case ConcreteTypeInt32:
		*(*int32)(ptr) = c.buffer.ReadVarint32()
	case ConcreteTypeInt:
		if strconv.IntSize == 64 {
			*(*int)(ptr) = int(c.buffer.ReadVarint64())
		} else {
			*(*int)(ptr) = int(c.buffer.ReadVarint32())
		}
	case ConcreteTypeInt64:
		*(*int64)(ptr) = c.buffer.ReadVarint64()
	case ConcreteTypeFloat32:
		*(*float32)(ptr) = c.buffer.ReadFloat32()
	case ConcreteTypeFloat64:
		*(*float64)(ptr) = c.buffer.ReadFloat64()
	case ConcreteTypeString:
		*(*string)(ptr) = readString(c.buffer)
	}
}

// ReadAndValidateTypeId reads type ID and validates it matches expected
func (c *ReadContext) ReadAndValidateTypeId(expected TypeId) error {
	actual := c.ReadTypeId()
	if actual != expected {
		return ErrTypeMismatch
	}
	return nil
}

// ReadLength reads a length value as varint
func (c *ReadContext) ReadLength() int {
	return int(c.buffer.ReadVarInt32())
}

// ============================================================================
// Typed Read Methods - Read primitives with optional ref/type info
// ============================================================================

// ReadBool reads a bool with optional ref/type info
func (c *ReadContext) ReadBool(readRefInfo, readTypeInfo bool) (bool, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return c.buffer.ReadBool(), nil
}

// ReadBoolInto reads a bool into target with optional ref/type info
func (c *ReadContext) ReadBoolInto(target *bool, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	*target = c.buffer.ReadBool()
	return nil
}

// ReadInt8 reads an int8 with optional ref/type info
func (c *ReadContext) ReadInt8(readRefInfo, readTypeInfo bool) (int8, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return c.buffer.ReadInt8(), nil
}

// ReadInt8Into reads an int8 into target with optional ref/type info
func (c *ReadContext) ReadInt8Into(target *int8, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	*target = c.buffer.ReadInt8()
	return nil
}

// ReadInt16 reads an int16 with optional ref/type info
func (c *ReadContext) ReadInt16(readRefInfo, readTypeInfo bool) (int16, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return c.buffer.ReadInt16(), nil
}

// ReadInt16Into reads an int16 into target with optional ref/type info
func (c *ReadContext) ReadInt16Into(target *int16, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	*target = c.buffer.ReadInt16()
	return nil
}

// ReadInt32 reads an int32 with optional ref/type info
func (c *ReadContext) ReadInt32(readRefInfo, readTypeInfo bool) (int32, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return c.buffer.ReadVarint32(), nil
}

// ReadInt32Into reads an int32 into target with optional ref/type info
func (c *ReadContext) ReadInt32Into(target *int32, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	*target = c.buffer.ReadVarint32()
	return nil
}

// ReadInt64 reads an int64 with optional ref/type info
func (c *ReadContext) ReadInt64(readRefInfo, readTypeInfo bool) (int64, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return c.buffer.ReadVarint64(), nil
}

// ReadInt64Into reads an int64 into target with optional ref/type info
func (c *ReadContext) ReadInt64Into(target *int64, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	*target = c.buffer.ReadVarint64()
	return nil
}

// ReadIntInto reads an int into target with optional ref/type info
func (c *ReadContext) ReadIntInto(target *int, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	*target = int(c.buffer.ReadVarint64())
	return nil
}

// ReadFloat32 reads a float32 with optional ref/type info
func (c *ReadContext) ReadFloat32(readRefInfo, readTypeInfo bool) (float32, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return c.buffer.ReadFloat32(), nil
}

// ReadFloat32Into reads a float32 into target with optional ref/type info
func (c *ReadContext) ReadFloat32Into(target *float32, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	*target = c.buffer.ReadFloat32()
	return nil
}

// ReadFloat64 reads a float64 with optional ref/type info
func (c *ReadContext) ReadFloat64(readRefInfo, readTypeInfo bool) (float64, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return c.buffer.ReadFloat64(), nil
}

// ReadFloat64Into reads a float64 into target with optional ref/type info
func (c *ReadContext) ReadFloat64Into(target *float64, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	*target = c.buffer.ReadFloat64()
	return nil
}

// ReadString reads a string with optional ref/type info
func (c *ReadContext) ReadString(readRefInfo, readTypeInfo bool) (string, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	length := c.buffer.ReadVarUint32()
	if length == 0 {
		return "", nil
	}
	data := c.buffer.ReadBinary(int(length))
	return string(data), nil
}

// ReadStringInto reads a string into target with optional ref/type info
func (c *ReadContext) ReadStringInto(target *string, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	length := c.buffer.ReadVarUint32()
	if length == 0 {
		*target = ""
		return nil
	}
	data := c.buffer.ReadBinary(int(length))
	*target = string(data)
	return nil
}

// ReadBoolSlice reads []bool with optional ref/type info
func (c *ReadContext) ReadBoolSlice(readRefInfo, readTypeInfo bool) ([]bool, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readBoolSlice(c.buffer)
}

// ReadBoolSliceInto reads []bool into target, reusing capacity when possible
func (c *ReadContext) ReadBoolSliceInto(target *[]bool, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readBoolSliceInto(c.buffer, target)
}

// ReadInt8Slice reads []int8 with optional ref/type info
func (c *ReadContext) ReadInt8Slice(readRefInfo, readTypeInfo bool) ([]int8, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readInt8Slice(c.buffer)
}

// ReadInt8SliceInto reads []int8 into target, reusing capacity when possible
func (c *ReadContext) ReadInt8SliceInto(target *[]int8, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readInt8SliceInto(c.buffer, target)
}

// ReadInt16Slice reads []int16 with optional ref/type info
func (c *ReadContext) ReadInt16Slice(readRefInfo, readTypeInfo bool) ([]int16, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readInt16Slice(c.buffer)
}

// ReadInt16SliceInto reads []int16 into target, reusing capacity when possible
func (c *ReadContext) ReadInt16SliceInto(target *[]int16, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readInt16SliceInto(c.buffer, target)
}

// ReadInt32Slice reads []int32 with optional ref/type info
func (c *ReadContext) ReadInt32Slice(readRefInfo, readTypeInfo bool) ([]int32, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readInt32Slice(c.buffer)
}

// ReadInt32SliceInto reads []int32 into target, reusing capacity when possible
func (c *ReadContext) ReadInt32SliceInto(target *[]int32, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readInt32SliceInto(c.buffer, target)
}

// ReadInt64Slice reads []int64 with optional ref/type info
func (c *ReadContext) ReadInt64Slice(readRefInfo, readTypeInfo bool) ([]int64, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readInt64Slice(c.buffer)
}

// ReadInt64SliceInto reads []int64 into target, reusing capacity when possible
func (c *ReadContext) ReadInt64SliceInto(target *[]int64, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readInt64SliceInto(c.buffer, target)
}

// ReadIntSlice reads []int with optional ref/type info
func (c *ReadContext) ReadIntSlice(readRefInfo, readTypeInfo bool) ([]int, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readIntSlice(c.buffer)
}

// ReadIntSliceInto reads []int into target, reusing capacity when possible
func (c *ReadContext) ReadIntSliceInto(target *[]int, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readIntSliceInto(c.buffer, target)
}

// ReadFloat32Slice reads []float32 with optional ref/type info
func (c *ReadContext) ReadFloat32Slice(readRefInfo, readTypeInfo bool) ([]float32, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readFloat32Slice(c.buffer)
}

// ReadFloat32SliceInto reads []float32 into target, reusing capacity when possible
func (c *ReadContext) ReadFloat32SliceInto(target *[]float32, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readFloat32SliceInto(c.buffer, target)
}

// ReadFloat64Slice reads []float64 with optional ref/type info
func (c *ReadContext) ReadFloat64Slice(readRefInfo, readTypeInfo bool) ([]float64, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readFloat64Slice(c.buffer)
}

// ReadFloat64SliceInto reads []float64 into target, reusing capacity when possible
func (c *ReadContext) ReadFloat64SliceInto(target *[]float64, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readFloat64SliceInto(c.buffer, target)
}

// ReadByteSlice reads []byte with optional ref/type info
func (c *ReadContext) ReadByteSlice(readRefInfo, readTypeInfo bool) ([]byte, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	isInBand := c.buffer.ReadBool()
	if !isInBand {
		return nil, fmt.Errorf("out-of-band byte slice not supported in fast path")
	}
	size := c.buffer.ReadLength()
	return c.buffer.ReadBinary(size), nil
}

// ReadByteSliceInto reads []byte into target, reusing capacity when possible
func (c *ReadContext) ReadByteSliceInto(target *[]byte, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	isInBand := c.buffer.ReadBool()
	if !isInBand {
		return fmt.Errorf("out-of-band byte slice not supported in fast path")
	}
	size := c.buffer.ReadLength()
	data := c.buffer.ReadBinary(size)
	if cap(*target) >= size {
		*target = (*target)[:size]
		copy(*target, data)
	} else {
		*target = data
	}
	return nil
}

// ReadStringStringMap reads map[string]string with optional ref/type info
func (c *ReadContext) ReadStringStringMap(readRefInfo, readTypeInfo bool) (map[string]string, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readMapStringString(c.buffer), nil
}

// ReadStringStringMapInto reads map[string]string into target
func (c *ReadContext) ReadStringStringMapInto(target *map[string]string, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	*target = readMapStringString(c.buffer)
	return nil
}

// ReadStringInt64Map reads map[string]int64 with optional ref/type info
func (c *ReadContext) ReadStringInt64Map(readRefInfo, readTypeInfo bool) (map[string]int64, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readMapStringInt64(c.buffer), nil
}

// ReadStringInt64MapInto reads map[string]int64 into target
func (c *ReadContext) ReadStringInt64MapInto(target *map[string]int64, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	*target = readMapStringInt64(c.buffer)
	return nil
}

// ReadStringIntMap reads map[string]int with optional ref/type info
func (c *ReadContext) ReadStringIntMap(readRefInfo, readTypeInfo bool) (map[string]int, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readMapStringInt(c.buffer), nil
}

// ReadStringIntMapInto reads map[string]int into target
func (c *ReadContext) ReadStringIntMapInto(target *map[string]int, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	*target = readMapStringInt(c.buffer)
	return nil
}

// ReadStringFloat64Map reads map[string]float64 with optional ref/type info
func (c *ReadContext) ReadStringFloat64Map(readRefInfo, readTypeInfo bool) (map[string]float64, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readMapStringFloat64(c.buffer), nil
}

// ReadStringFloat64MapInto reads map[string]float64 into target
func (c *ReadContext) ReadStringFloat64MapInto(target *map[string]float64, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	*target = readMapStringFloat64(c.buffer)
	return nil
}

// ReadStringBoolMap reads map[string]bool with optional ref/type info
func (c *ReadContext) ReadStringBoolMap(readRefInfo, readTypeInfo bool) (map[string]bool, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readMapStringBool(c.buffer), nil
}

// ReadStringBoolMapInto reads map[string]bool into target
func (c *ReadContext) ReadStringBoolMapInto(target *map[string]bool, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	*target = readMapStringBool(c.buffer)
	return nil
}

// ReadInt32Int32Map reads map[int32]int32 with optional ref/type info
func (c *ReadContext) ReadInt32Int32Map(readRefInfo, readTypeInfo bool) (map[int32]int32, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readMapInt32Int32(c.buffer), nil
}

// ReadInt32Int32MapInto reads map[int32]int32 into target
func (c *ReadContext) ReadInt32Int32MapInto(target *map[int32]int32, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	*target = readMapInt32Int32(c.buffer)
	return nil
}

// ReadInt64Int64Map reads map[int64]int64 with optional ref/type info
func (c *ReadContext) ReadInt64Int64Map(readRefInfo, readTypeInfo bool) (map[int64]int64, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readMapInt64Int64(c.buffer), nil
}

// ReadInt64Int64MapInto reads map[int64]int64 into target
func (c *ReadContext) ReadInt64Int64MapInto(target *map[int64]int64, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	*target = readMapInt64Int64(c.buffer)
	return nil
}

// ReadIntIntMap reads map[int]int with optional ref/type info
func (c *ReadContext) ReadIntIntMap(readRefInfo, readTypeInfo bool) (map[int]int, error) {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	return readMapIntInt(c.buffer), nil
}

// ReadIntIntMapInto reads map[int]int into target
func (c *ReadContext) ReadIntIntMapInto(target *map[int]int, readRefInfo, readTypeInfo bool) error {
	if readRefInfo {
		_ = c.buffer.ReadInt8()
	}
	if readTypeInfo {
		_ = c.buffer.ReadInt16()
	}
	*target = readMapIntInt(c.buffer)
	return nil
}

// ReadBufferObject reads a buffer object
func (c *ReadContext) ReadBufferObject() (*ByteBuffer, error) {
	isInBand := c.buffer.ReadBool()
	if isInBand {
		size := c.buffer.ReadLength()
		buf := c.buffer.Slice(c.buffer.readerIndex, size)
		c.buffer.readerIndex += size
		return buf, nil
	}
	// Out-of-band: get the next buffer from the out-of-band buffers list
	if c.outOfBandBuffers == nil || c.outOfBandIndex >= len(c.outOfBandBuffers) {
		return nil, fmt.Errorf("out-of-band buffer expected but not available at index %d", c.outOfBandIndex)
	}
	buf := c.outOfBandBuffers[c.outOfBandIndex]
	c.outOfBandIndex++
	return buf, nil
}

// ReadValue reads a polymorphic value with reference tracking
func (c *ReadContext) ReadValue(value reflect.Value) error {
	return c.readReferencable(value)
}

// readReferencable reads a value with reference tracking
func (c *ReadContext) readReferencable(value reflect.Value) error {
	return c.readReferencableBySerializer(value, nil)
}

// readReferencableBySerializer reads a value with reference tracking using a specific serializer
func (c *ReadContext) readReferencableBySerializer(value reflect.Value, serializer Serializer) error {
	// dynamic-with-refroute or unknown serializer
	if serializer == nil || serializer.NeedToWriteRef() {
		refId, err := c.refResolver.TryPreserveRefId(c.buffer)
		if err != nil {
			return fmt.Errorf("failed to preserve refID: %w", err)
		}
		// first read
		if refId >= int32(NotNullValueFlag) {
			// deserialize non-ref (may read typeinfo or use provided serializer)
			if err := c.readData(value, serializer); err != nil {
				return fmt.Errorf("failed to read data: %w", err)
			}
			// record in resolver
			c.refResolver.SetReadObject(refId, value)
			return nil
		}
		// back-reference or null
		if refId == int32(NullFlag) {
			value.Set(reflect.Zero(value.Type()))
			return nil
		}
		prev := c.refResolver.GetReadObject(refId)
		value.Set(prev)
		return nil
	}

	// static path: no references
	headFlag := c.buffer.ReadInt8()
	if headFlag == NullFlag {
		value.Set(reflect.Zero(value.Type()))
		return nil
	}
	// directly read without altering serializer
	return serializer.Read(c, value.Type(), value)
}

// readData reads value data using the type resolver
func (c *ReadContext) readData(value reflect.Value, serializer Serializer) error {
	if serializer == nil {
		typeInfo, err := c.typeResolver.readTypeInfo(c.buffer, value)
		if err != nil {
			return fmt.Errorf("read typeinfo failed: %w", err)
		}
		serializer = typeInfo.Serializer

		var concrete reflect.Value
		var type_ reflect.Type
		switch {
		case value.Kind() == reflect.Interface,
			!value.CanSet():
			concrete = reflect.New(typeInfo.Type).Elem()
			type_ = typeInfo.Type
		default:
			concrete = value
			type_ = concrete.Type()
			// For slice types with concrete element types, prefer type-specific serializer
			if type_.Kind() == reflect.Slice && !isDynamicType(type_.Elem()) {
				if typeSpecific, err := c.typeResolver.getSerializerByType(type_, false); err == nil && typeSpecific != nil {
					serializer = typeSpecific
				}
			}
		}
		if err := serializer.Read(c, type_, concrete); err != nil {
			return err
		}
		value.Set(concrete)
		return nil
	}
	return serializer.Read(c, value.Type(), value)
}