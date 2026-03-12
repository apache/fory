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
// ReadContext - Holds all state needed during deserialization
// ============================================================================

// ReadContext holds all state needed during deserialization.
type ReadContext struct {
	buffer           *ByteBuffer
	refReader        *RefReader
	trackRef         bool          // Cached flag to avoid indirection
	xlang            bool          // Cross-language serialization mode
	compatible       bool          // Schema evolution compatibility mode
	typeResolver     *TypeResolver // For complex type deserialization
	refResolver      *RefResolver  // For reference tracking (legacy)
	outOfBandBuffers []*ByteBuffer // Out-of-band buffers for deserialization
	outOfBandIndex   int           // Current index into out-of-band buffers
	depth            int           // Current nesting depth for cycle detection
	maxDepth         int           // Maximum allowed nesting depth
	err              Error         // Accumulated error state for deferred checking
	lastTypePtr      uintptr
	lastTypeInfo     *TypeInfo
}

// IsXlang returns whether cross-language serialization mode is enabled
func (c *ReadContext) IsXlang() bool {
	return c.xlang
}

// NewReadContext creates a new read context
func NewReadContext(trackRef bool) *ReadContext {
	return &ReadContext{
		buffer:    NewByteBuffer(nil),
		refReader: NewRefReader(trackRef),
		trackRef:  trackRef,
		maxDepth:  128, // Default maximum nesting depth
	}
}

// Reset clears state for reuse (called before each Deserialize)
func (c *ReadContext) Reset() {
	c.refReader.Reset()
	c.outOfBandBuffers = nil
	c.outOfBandIndex = 0
	c.err = Error{} // Clear error state
	if c.refResolver != nil {
		c.refResolver.resetRead()
	}
	if c.typeResolver != nil {
		c.typeResolver.resetRead()
	}
}

// SetData sets new input data (for buffer reuse)
// Reuses existing buffer to avoid allocation
func (c *ReadContext) SetData(data []byte) {
	if c.buffer == nil {
		c.buffer = NewByteBuffer(data)
	} else {
		c.buffer.data = data
		c.buffer.readerIndex = 0
		c.buffer.writerIndex = len(data)
		c.buffer.reader = nil
	}
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

// ============================================================================
// Error State Methods - For deferred error checking pattern
// ============================================================================

// HasError returns true if an error has occurred
func (c *ReadContext) HasError() bool {
	return c.err.HasError()
}

// Err returns a pointer to the accumulated error for passing to buffer methods
func (c *ReadContext) Err() *Error {
	return &c.err
}

// SetError sets the error state if no error has occurred yet (first error wins)
func (c *ReadContext) SetError(e Error) {
	if c.err.Ok() {
		c.err = e
	}
}

// TakeError returns the current error and resets the error state
func (c *ReadContext) TakeError() Error {
	e := c.err
	c.err = Error{}
	return e
}

// CheckError checks if an error has occurred and returns it as a standard error
// This is used at strategic points for deferred error checking
func (c *ReadContext) CheckError() error {
	if c.err.HasError() {
		return c.TakeError()
	}
	return nil
}

// Inline primitive reads
func (c *ReadContext) RawBool() bool         { return c.buffer.ReadBool(c.Err()) }
func (c *ReadContext) RawInt8() int8         { return int8(c.buffer.ReadByte(c.Err())) }
func (c *ReadContext) RawInt16() int16       { return c.buffer.ReadInt16(c.Err()) }
func (c *ReadContext) RawInt32() int32       { return c.buffer.ReadInt32(c.Err()) }
func (c *ReadContext) RawInt64() int64       { return c.buffer.ReadInt64(c.Err()) }
func (c *ReadContext) RawFloat32() float32   { return c.buffer.ReadFloat32(c.Err()) }
func (c *ReadContext) RawFloat64() float64   { return c.buffer.ReadFloat64(c.Err()) }
func (c *ReadContext) ReadVarint32() int32   { return c.buffer.ReadVarint32(c.Err()) }
func (c *ReadContext) ReadVarint64() int64   { return c.buffer.ReadVarint64(c.Err()) }
func (c *ReadContext) ReadVarUint32() uint32 { return c.buffer.ReadVarUint32(c.Err()) }
func (c *ReadContext) ReadByte() byte        { return c.buffer.ReadByte(c.Err()) }

func (c *ReadContext) RawString() string {
	err := c.Err()
	length := c.buffer.ReadVarUint32(err)
	if length == 0 {
		return ""
	}
	data := c.buffer.ReadBinary(int(length), err)
	return string(data)
}

func (c *ReadContext) ReadBinary() []byte {
	err := c.Err()
	length := c.buffer.ReadVarUint32(err)
	return c.buffer.ReadBinary(int(length), err)
}

func (c *ReadContext) ReadTypeId() TypeId {
	return TypeId(c.buffer.ReadUint8(c.Err()))
}

func (c *ReadContext) getTypeInfoByType(type_ reflect.Type) *TypeInfo {
	if type_ == nil {
		return nil
	}
	typePtr := typePointer(type_)
	if typePtr == c.lastTypePtr && c.lastTypeInfo != nil {
		return c.lastTypeInfo
	}
	info := c.typeResolver.getTypeInfoByType(type_)
	if info != nil {
		c.lastTypePtr = typePtr
		c.lastTypeInfo = info
	}
	return info
}

// readFast reads a value using fast path based on DispatchId
func (c *ReadContext) readFast(ptr unsafe.Pointer, ct DispatchId) {
	err := c.Err()
	switch ct {
	case PrimitiveBoolDispatchId:
		*(*bool)(ptr) = c.buffer.ReadBool(err)
	case PrimitiveInt8DispatchId:
		*(*int8)(ptr) = int8(c.buffer.ReadByte(err))
	case PrimitiveInt16DispatchId:
		*(*int16)(ptr) = c.buffer.ReadInt16(err)
	case PrimitiveInt32DispatchId:
		*(*int32)(ptr) = c.buffer.ReadVarint32(err)
	case PrimitiveIntDispatchId:
		if strconv.IntSize == 64 {
			*(*int)(ptr) = int(c.buffer.ReadVarint64(err))
		} else {
			*(*int)(ptr) = int(c.buffer.ReadVarint32(err))
		}
	case PrimitiveInt64DispatchId:
		*(*int64)(ptr) = c.buffer.ReadVarint64(err)
	case PrimitiveFloat32DispatchId:
		*(*float32)(ptr) = c.buffer.ReadFloat32(err)
	case PrimitiveFloat64DispatchId:
		*(*float64)(ptr) = c.buffer.ReadFloat64(err)
	case PrimitiveFloat16DispatchId:
		*(*uint16)(ptr) = c.buffer.ReadUint16(err)
	case StringDispatchId:
		*(*string)(ptr) = readString(c.buffer, err)
	}
}

// ReadAndValidateTypeId reads type ID and validates it matches expected
func (c *ReadContext) ReadAndValidateTypeId(expected TypeId) {
	actual := c.ReadTypeId()
	if actual != expected {
		c.SetError(TypeMismatchError(actual, expected))
	}
}

// ReadLength reads a length value as varint (non-negative values)
func (c *ReadContext) ReadLength() int {
	err := c.Err()
	return int(c.buffer.ReadVarUint32(err))
}

// ============================================================================
// Typed Read Methods - Fastpath for codegen
// For primitive numeric types, use ctx.Buffer().ReadXXX()
// For strings, use ctx.ReadString()
// For slices/maps, use these methods which handle ref tracking
// ============================================================================

// ReadString reads a string value (caller handles nullable/type meta)
func (c *ReadContext) ReadString() string {
	return readString(c.buffer, c.Err())
}

// ReadBoolSlice reads []bool with ref/type info
func (c *ReadContext) ReadBoolSlice(refMode RefMode, readType bool) []bool {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return ReadBoolSlice(c.buffer, err)
}

// ReadInt8Slice reads []int8 with optional ref/type info
func (c *ReadContext) ReadInt8Slice(refMode RefMode, readType bool) []int8 {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return ReadInt8Slice(c.buffer, err)
}

// ReadInt16Slice reads []int16 with optional ref/type info
func (c *ReadContext) ReadInt16Slice(refMode RefMode, readType bool) []int16 {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return ReadInt16Slice(c.buffer, err)
}

// ReadInt32Slice reads []int32 with optional ref/type info
func (c *ReadContext) ReadInt32Slice(refMode RefMode, readType bool) []int32 {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return ReadInt32Slice(c.buffer, err)
}

// ReadInt64Slice reads []int64 with optional ref/type info
func (c *ReadContext) ReadInt64Slice(refMode RefMode, readType bool) []int64 {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return ReadInt64Slice(c.buffer, err)
}

// ReadUint16Slice reads []uint16 with optional ref/type info
func (c *ReadContext) ReadUint16Slice(refMode RefMode, readType bool) []uint16 {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return ReadUint16Slice(c.buffer, err)
}

// ReadUint32Slice reads []uint32 with optional ref/type info
func (c *ReadContext) ReadUint32Slice(refMode RefMode, readType bool) []uint32 {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return ReadUint32Slice(c.buffer, err)
}

// ReadUint64Slice reads []uint64 with optional ref/type info
func (c *ReadContext) ReadUint64Slice(refMode RefMode, readType bool) []uint64 {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return ReadUint64Slice(c.buffer, err)
}

// ReadIntSlice reads []int with optional ref/type info
func (c *ReadContext) ReadIntSlice(refMode RefMode, readType bool) []int {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return ReadIntSlice(c.buffer, err)
}

// ReadUintSlice reads []uint with optional ref/type info
func (c *ReadContext) ReadUintSlice(refMode RefMode, readType bool) []uint {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return ReadUintSlice(c.buffer, err)
}

// ReadFloat32Slice reads []float32 with optional ref/type info
func (c *ReadContext) ReadFloat32Slice(refMode RefMode, readType bool) []float32 {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return ReadFloat32Slice(c.buffer, err)
}

// ReadFloat64Slice reads []float64 with optional ref/type info
func (c *ReadContext) ReadFloat64Slice(refMode RefMode, readType bool) []float64 {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return ReadFloat64Slice(c.buffer, err)
}

// ReadByteSlice reads []byte with optional ref/type info
func (c *ReadContext) ReadByteSlice(refMode RefMode, readType bool) []byte {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	size := c.buffer.ReadLength(err)
	return c.buffer.ReadBinary(size, err)
}

// ReadStringSlice reads []string with optional ref/type info using LIST protocol
func (c *ReadContext) ReadStringSlice(refMode RefMode, readType bool) []string {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return ReadStringSlice(c.buffer, err)
}

// ReadStringStringMap reads map[string]string with optional ref/type info
func (c *ReadContext) ReadStringStringMap(refMode RefMode, readType bool) map[string]string {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return readMapStringString(c.buffer, err)
}

// ReadStringInt64Map reads map[string]int64 with optional ref/type info
func (c *ReadContext) ReadStringInt64Map(refMode RefMode, readType bool) map[string]int64 {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return readMapStringInt64(c.buffer, err)
}

// ReadStringInt32Map reads map[string]int32 with optional ref/type info
func (c *ReadContext) ReadStringInt32Map(refMode RefMode, readType bool) map[string]int32 {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return readMapStringInt32(c.buffer, err)
}

// ReadStringIntMap reads map[string]int with optional ref/type info
func (c *ReadContext) ReadStringIntMap(refMode RefMode, readType bool) map[string]int {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return readMapStringInt(c.buffer, err)
}

// ReadStringFloat64Map reads map[string]float64 with optional ref/type info
func (c *ReadContext) ReadStringFloat64Map(refMode RefMode, readType bool) map[string]float64 {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return readMapStringFloat64(c.buffer, err)
}

// ReadStringBoolMap reads map[string]bool with optional ref/type info
func (c *ReadContext) ReadStringBoolMap(refMode RefMode, readType bool) map[string]bool {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return readMapStringBool(c.buffer, err)
}

// ReadInt32Int32Map reads map[int32]int32 with optional ref/type info
func (c *ReadContext) ReadInt32Int32Map(refMode RefMode, readType bool) map[int32]int32 {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return readMapInt32Int32(c.buffer, err)
}

// ReadInt64Int64Map reads map[int64]int64 with optional ref/type info
func (c *ReadContext) ReadInt64Int64Map(refMode RefMode, readType bool) map[int64]int64 {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return readMapInt64Int64(c.buffer, err)
}

// ReadIntIntMap reads map[int]int with optional ref/type info
func (c *ReadContext) ReadIntIntMap(refMode RefMode, readType bool) map[int]int {
	err := c.Err()
	if refMode != RefModeNone {
		if c.buffer.ReadInt8(err) == NullFlag {
			return nil
		}
	}
	if readType {
		_ = c.buffer.ReadUint8(err)
	}
	return readMapIntInt(c.buffer, err)
}

// ReadBufferObject reads a buffer object
func (c *ReadContext) ReadBufferObject() *ByteBuffer {
	err := c.Err()
	isInBand := c.buffer.ReadBool(err)
	if isInBand {
		size := c.buffer.ReadLength(err)
		buf := c.buffer.Slice(c.buffer.readerIndex, size)
		c.buffer.readerIndex += size
		return buf
	}
	// Out-of-band: get the next buffer from the out-of-band buffers list
	if c.outOfBandBuffers == nil || c.outOfBandIndex >= len(c.outOfBandBuffers) {
		c.SetError(DeserializationErrorf("out-of-band buffer expected but not available at index %d", c.outOfBandIndex))
		return nil
	}
	buf := c.outOfBandBuffers[c.outOfBandIndex]
	c.outOfBandIndex++
	return buf
}

// incDepth increments the nesting depth and checks for overflow
func (c *ReadContext) incDepth() {
	c.depth++
	if c.depth > c.maxDepth {
		c.SetError(MaxDepthExceededError(c.maxDepth))
	}
}

// decDepth decrements the nesting depth
func (c *ReadContext) decDepth() {
	c.depth--
}

// ReadValue reads a polymorphic value with configurable reference tracking and type info reading.
// Parameters:
//   - refMode: controls reference tracking behavior (RefModeNone, RefModeTracking, RefModeNullOnly)
//   - readType: if true, reads type info from the buffer
func (c *ReadContext) ReadValue(value reflect.Value, refMode RefMode, readType bool) {
	if !value.IsValid() {
		c.SetError(DeserializationError("invalid reflect.Value"))
		return
	}

	// Handle array targets (arrays are serialized as slices)
	if value.Type().Kind() == reflect.Array {
		c.ReadArrayValue(value, refMode, readType)
		return
	}

	// For any types, we need to read the actual type from the buffer first
	if value.Type().Kind() == reflect.Interface {
		// Handle ref tracking based on refMode
		var refID int32 = int32(NotNullValueFlag)
		if refMode == RefModeTracking {
			var err error
			refID, err = c.RefResolver().TryPreserveRefId(c.buffer)
			if err != nil {
				c.SetError(FromError(err))
				return
			}
			if refID < int32(NotNullValueFlag) {
				// Reference found
				obj := c.RefResolver().GetReadObject(refID)
				if obj.IsValid() {
					value.Set(obj)
				}
				return
			}
		} else if refMode == RefModeNullOnly {
			flag := c.buffer.ReadInt8(c.Err())
			if flag == NullFlag {
				return
			}
		}

		// Read type info to determine the actual type
		if !readType {
			c.SetError(DeserializationError("cannot read any without type info"))
			return
		}
		ctxErr := c.Err()
		typeInfo := c.typeResolver.ReadTypeInfo(c.buffer, ctxErr)
		if ctxErr.HasError() {
			return
		}

		// Create a new instance of the actual type
		actualType := typeInfo.Type
		if actualType == nil {
			// Unknown type - skip the data using the serializer (skipStructSerializer)
			if typeInfo.Serializer != nil {
				typeInfo.Serializer.ReadData(c, reflect.Value{})
			}
			// Leave interface value as nil for unknown types
			return
		}

		// Create a new instance
		var newValue reflect.Value
		var valueToSet reflect.Value
		internalTypeID := TypeId(typeInfo.TypeID)

		// For named struct types, create a pointer type to support circular references.
		// In Java/xlang serialization, objects are always by reference, so when deserializing
		// into any, we need to use pointers to maintain reference semantics.
		isNamedStruct := actualType.Kind() == reflect.Struct &&
			(internalTypeID == NAMED_STRUCT || internalTypeID == NAMED_COMPATIBLE_STRUCT ||
				internalTypeID == COMPATIBLE_STRUCT || internalTypeID == STRUCT)

		if actualType.Kind() == reflect.Ptr {
			// For pointer types, create a pointer directly
			// The serializer's ReadData will handle allocating and reading the element
			newValue = reflect.New(actualType).Elem()
			valueToSet = newValue
		} else if isNamedStruct {
			// For named struct types, create a pointer to support circular references
			// Create *A instead of A
			newValue = reflect.New(actualType)
			valueToSet = newValue
		} else {
			newValue = reflect.New(actualType).Elem()
			valueToSet = newValue
		}

		// For named structs, register the pointer BEFORE reading data
		// This is critical for circular references to work correctly
		if isNamedStruct && refMode == RefModeTracking && refID >= int32(NotNullValueFlag) {
			c.RefResolver().SetReadObject(refID, newValue)
		}

		// For named structs, read into the pointer's element
		var readTarget reflect.Value
		if isNamedStruct {
			readTarget = newValue.Elem()
		} else {
			readTarget = newValue
		}

		typeInfo.Serializer.ReadData(c, readTarget)
		if c.HasError() {
			return
		}

		// Register reference after reading data for non-struct types
		if !isNamedStruct && refMode == RefModeTracking && refID >= int32(NotNullValueFlag) {
			c.RefResolver().SetReadObject(refID, newValue)
		}

		// Set the interface value
		value.Set(valueToSet)
		return
	}

	if typeInfo := c.getTypeInfoByType(value.Type()); typeInfo != nil && typeInfo.Serializer != nil {
		typeInfo.Serializer.Read(c, refMode, readType, false, value)
		return
	}

	// For struct types, use optimized ReadStruct path when using full ref tracking and type info.
	// Unions use a custom serializer and must bypass ReadStruct.
	valueType := value.Type()
	if refMode == RefModeTracking && readType && !c.typeResolver.IsUnionType(valueType) {
		if valueType.Kind() == reflect.Struct {
			c.ReadStruct(value)
			return
		}
		if valueType.Kind() == reflect.Ptr && valueType.Elem().Kind() == reflect.Struct {
			c.ReadStruct(value)
			return
		}
	}

	// Get serializer for the value's type
	serializer, err := c.typeResolver.getSerializerByType(valueType, false)
	if err != nil {
		c.SetError(DeserializationErrorf("failed to get serializer for type %v: %v", valueType, err))
		return
	}

	// Read handles ref tracking and type info internally
	serializer.Read(c, refMode, readType, false, value)
}

// ReadStruct reads a struct value with optimized type resolution.
// This method uses ReadTypeInfoForType to avoid expensive type resolution via map lookups
// when the expected type is already known.
func (c *ReadContext) ReadStruct(value reflect.Value) {
	if !value.IsValid() {
		c.SetError(DeserializationError("invalid reflect.Value"))
		return
	}

	// Handle pointer to struct
	valueType := value.Type()
	isPtr := valueType.Kind() == reflect.Ptr
	var structType reflect.Type
	if isPtr {
		structType = valueType.Elem()
	} else {
		structType = valueType
	}

	// Read ref flag
	refID, err := c.RefResolver().TryPreserveRefId(c.buffer)
	if err != nil {
		c.SetError(FromError(err))
		return
	}

	// Handle null
	if refID == int32(NullFlag) {
		if isPtr {
			value.Set(reflect.Zero(valueType))
		}
		return
	}

	// Handle reference to existing object
	if refID < int32(NotNullValueFlag) {
		obj := c.RefResolver().GetReadObject(refID)
		if obj.IsValid() {
			value.Set(obj)
		}
		return
	}

	// Use ReadTypeInfoForType to get serializer directly by type - avoids map lookups
	ctxErr := c.Err()
	serializer := c.typeResolver.ReadTypeInfoForType(c.buffer, structType, ctxErr)
	if ctxErr.HasError() || serializer == nil {
		if !ctxErr.HasError() {
			c.SetError(DeserializationErrorf("no serializer registered for struct %v", structType))
		}
		return
	}

	// Allocate pointer if needed
	var readTarget reflect.Value
	if isPtr {
		if value.IsNil() {
			value.Set(reflect.New(structType))
		}
		readTarget = value.Elem()
		// Register reference before reading (for circular references)
		c.RefResolver().SetReadObject(refID, value)
	} else {
		readTarget = value
		// For non-pointer structs, register a pointer to enable circular ref resolution
		if value.CanAddr() {
			c.RefResolver().SetReadObject(refID, value.Addr())
		}
	}

	// Read struct data directly
	serializer.ReadData(c, readTarget)
}

// ReadInto reads a value using a specific serializer with optional ref/type info
func (c *ReadContext) ReadInto(value reflect.Value, serializer Serializer, refMode RefMode, readTypeInfo bool) {
	if !value.IsValid() {
		c.SetError(DeserializationError("invalid reflect.Value"))
		return
	}
	if serializer == nil {
		c.SetError(DeserializationError("serializer cannot be nil"))
		return
	}

	serializer.Read(c, refMode, readTypeInfo, false, value)
}

// ReadArrayValue handles array targets with configurable ref mode and type reading.
// Arrays are serialized as slices in xlang protocol.
func (c *ReadContext) ReadArrayValue(target reflect.Value, refMode RefMode, readType bool) {
	var refID int32 = int32(NotNullValueFlag)

	// Handle ref tracking based on refMode
	if refMode == RefModeTracking {
		var err error
		refID, err = c.RefResolver().TryPreserveRefId(c.buffer)
		if err != nil {
			c.SetError(FromError(err))
			return
		}
		if refID < int32(NotNullValueFlag) {
			// Reference to existing object
			obj := c.RefResolver().GetReadObject(refID)
			if obj.IsValid() {
				reflect.Copy(target, obj)
			}
			return
		}
	} else if refMode == RefModeNullOnly {
		flag := c.buffer.ReadInt8(c.Err())
		if flag == NullFlag {
			return
		}
	}

	// Read type ID if requested (will be slice type in stream)
	if readType {
		c.buffer.ReadUint8(c.Err())
	}

	// Get slice serializer to read the data
	sliceType := reflect.SliceOf(target.Type().Elem())
	serializer, err := c.typeResolver.getSerializerByType(sliceType, false)
	if err != nil {
		c.SetError(DeserializationErrorf("failed to get serializer for slice type %v: %v", sliceType, err))
		return
	}

	// Create addressable temporary slice using reflect.New
	tempSlicePtr := reflect.New(sliceType)
	tempSlice := tempSlicePtr.Elem()
	tempSlice.Set(reflect.MakeSlice(sliceType, target.Len(), target.Len()))

	// Use ReadData to read slice data (ref/type already handled)
	serializer.ReadData(c, tempSlice)
	if c.HasError() {
		return
	}

	// Verify length matches
	if tempSlice.Len() != target.Len() {
		c.SetError(DeserializationErrorf("array length mismatch: got %d, want %d", tempSlice.Len(), target.Len()))
		return
	}

	// Copy to array
	reflect.Copy(target, tempSlice)

	// Register for circular refs
	if refMode == RefModeTracking && refID >= int32(NotNullValueFlag) {
		c.RefResolver().SetReadObject(refID, target)
	}
}
