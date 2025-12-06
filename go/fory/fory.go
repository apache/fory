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
	"encoding/binary"
	"errors"
	"fmt"
	"reflect"
	"strconv"
	"unsafe"
)

// ============================================================================
// Errors
// ============================================================================

// ErrMagicNumber indicates an invalid magic number in the data stream
var ErrMagicNumber = errors.New("fory: invalid magic number")

// ErrNoSerializer indicates no serializer is registered for a type
var ErrNoSerializer = errors.New("fory: no serializer registered for type")

// ============================================================================
// Constants
// ============================================================================

// Language constants for protocol header
const (
	LangXLANG uint8 = iota
	LangJAVA
	LangPYTHON
	LangCPP
	LangGO
	LangJAVASCRIPT
	LangRUST
	LangDART
)

// Protocol constants
const (
	MAGIC_NUMBER int16 = 0x62D4
)

// Bitmap flags for protocol header
const (
	NilFlag          = 0
	LittleEndianFlag = 2
	XLangFlag        = 4
	OutOfBandFlag    = 8
)

// Reference flags
const (
	NullFlag         int8 = -3
	RefFlag          int8 = -2
	NotNullValueFlag int8 = -1
	RefValueFlag     int8 = 0
)

// ============================================================================
// Config
// ============================================================================

// Config holds configuration options for Fory instances
type Config struct {
	TrackRef   bool
	MaxDepth   int
	IsXlang    bool
	Compatible bool // Schema evolution compatibility mode
}

// defaultConfig returns the default configuration
func defaultConfig() Config {
	return Config{
		TrackRef: true,
		MaxDepth: 100,
		IsXlang:  true,
	}
}

// Option is a function that configures a Fory instance
type Option func(*Fory)

// WithTrackRef sets reference tracking mode
func WithTrackRef(enabled bool) Option {
	return func(f *Fory) {
		f.config.TrackRef = enabled
	}
}

// WithRefTracking is deprecated, use WithTrackRef instead
func WithRefTracking(enabled bool) Option {
	return WithTrackRef(enabled)
}

// WithMaxDepth sets the maximum serialization depth
func WithMaxDepth(depth int) Option {
	return func(f *Fory) {
		f.config.MaxDepth = depth
	}
}

// WithXlang sets cross-language serialization mode
func WithXlang(enabled bool) Option {
	return func(f *Fory) {
		f.config.IsXlang = enabled
	}
}

// WithCompatible sets schema evolution compatibility mode
func WithCompatible(enabled bool) Option {
	return func(f *Fory) {
		f.config.Compatible = enabled
	}
}

// ============================================================================
// Fory - Main serialization instance
// ============================================================================

// MetaContext holds metadata for schema evolution and type sharing
type MetaContext struct {
	typeMap               map[reflect.Type]uint32
	writingTypeDefs       []*TypeDef
	readTypeInfos         []TypeInfo
	scopedMetaShareEnable bool
}

// IsScopedMetaShareEnabled returns whether scoped meta share is enabled
func (m *MetaContext) IsScopedMetaShareEnabled() bool {
	return m.scopedMetaShareEnable
}

// Reset clears the meta context for reuse
func (m *MetaContext) Reset() {
	m.typeMap = make(map[reflect.Type]uint32)
	m.writingTypeDefs = nil
	m.readTypeInfos = nil
}

// Fory is the main serialization instance.
// Note: Fory is NOT thread-safe. Use ThreadSafeFory for concurrent use.
type Fory struct {
	config      Config
	metaContext *MetaContext

	// Reusable contexts - avoid allocation on each Serialize/Deserialize call
	writeCtx *WriteContext
	readCtx  *ReadContext

	// Resolvers shared between contexts
	typeResolver *TypeResolver
	refResolver  *RefResolver
}

// MetaContext returns the meta context for schema evolution
func (f *Fory) MetaContext() *MetaContext {
	return f.metaContext
}

// RegisterNamedType registers a named type for cross-language serialization
// type_ can be either a reflect.Type or an instance of the type
func (f *Fory) RegisterNamedType(type_ interface{}, typeName string) error {
	var t reflect.Type
	if rt, ok := type_.(reflect.Type); ok {
		t = rt
	} else {
		t = reflect.TypeOf(type_)
		if t.Kind() == reflect.Ptr {
			t = t.Elem()
		}
	}
	return f.typeResolver.RegisterNamedType(t, 0, "", typeName)
}

// NewForyWithOptions is an alias for New for backward compatibility
func NewForyWithOptions(opts ...Option) *Fory {
	return New(opts...)
}

// NewFory is an alias for New for backward compatibility
func NewFory(opts ...Option) *Fory {
	return New(opts...)
}

// Marshal serializes a value to bytes (instance method for backward compatibility)
func (f *Fory) Marshal(v interface{}) ([]byte, error) {
	return f.SerializeAny(v)
}

// Unmarshal deserializes bytes into the provided value (instance method for backward compatibility)
func (f *Fory) Unmarshal(data []byte, v interface{}) error {
	result, err := f.DeserializeAny(data)
	if err != nil {
		return err
	}
	if v == nil {
		return nil
	}
	// Set the result into the provided value
	rv := reflect.ValueOf(v)
	if rv.Kind() != reflect.Ptr || rv.IsNil() {
		return fmt.Errorf("v must be a non-nil pointer")
	}
	if result != nil {
		resultVal := reflect.ValueOf(result)
		targetType := rv.Elem().Type()

		if resultVal.Type().AssignableTo(targetType) {
			rv.Elem().Set(resultVal)
		} else if resultVal.Type().ConvertibleTo(targetType) {
			rv.Elem().Set(resultVal.Convert(targetType))
		} else if resultVal.Kind() == reflect.Ptr && resultVal.Type().Elem().AssignableTo(targetType) {
			// Handle case where result is *T but target is T: dereference the pointer
			rv.Elem().Set(resultVal.Elem())
		} else if resultVal.Kind() == reflect.Ptr && resultVal.Type().Elem().ConvertibleTo(targetType) {
			// Handle case where result is *T but target is convertible from T
			rv.Elem().Set(resultVal.Elem().Convert(targetType))
		} else if resultVal.Kind() == reflect.Slice && targetType.Kind() == reflect.Slice {
			// Handle []interface{} to []T conversion
			converted := convertSlice(resultVal, targetType)
			if converted.IsValid() {
				rv.Elem().Set(converted)
			}
		} else if resultVal.Kind() == reflect.Slice && targetType.Kind() == reflect.Array {
			// Handle []T to [N]T conversion (arrays are serialized as slices)
			converted := convertSliceToArray(resultVal, targetType)
			if converted.IsValid() {
				rv.Elem().Set(converted)
			}
		} else if resultVal.Kind() == reflect.Map && targetType.Kind() == reflect.Map {
			// Handle map[interface{}]interface{} to map[K]V conversion
			converted := convertMap(resultVal, targetType)
			if converted.IsValid() {
				rv.Elem().Set(converted)
			}
		}
	}
	return nil
}

// convertSlice converts a []interface{} slice to the target slice type
func convertSlice(src reflect.Value, targetType reflect.Type) reflect.Value {
	if src.Kind() != reflect.Slice {
		return reflect.Value{}
	}

	length := src.Len()
	result := reflect.MakeSlice(targetType, length, length)
	elemType := targetType.Elem()

	for i := 0; i < length; i++ {
		srcElem := src.Index(i)
		dstElem := result.Index(i)

		// Get the actual value from interface
		if srcElem.Kind() == reflect.Interface {
			srcElem = srcElem.Elem()
		}

		if !srcElem.IsValid() {
			continue // nil element
		}

		srcType := srcElem.Type()

		if srcType.AssignableTo(elemType) {
			dstElem.Set(srcElem)
		} else if srcType.ConvertibleTo(elemType) {
			dstElem.Set(srcElem.Convert(elemType))
		} else if elemType.Kind() == reflect.Ptr && srcType.AssignableTo(elemType.Elem()) {
			// Target is *T, source is T - need to create pointer
			ptr := reflect.New(elemType.Elem())
			ptr.Elem().Set(srcElem)
			dstElem.Set(ptr)
		} else if elemType.Kind() == reflect.Ptr && srcType.ConvertibleTo(elemType.Elem()) {
			ptr := reflect.New(elemType.Elem())
			ptr.Elem().Set(srcElem.Convert(elemType.Elem()))
			dstElem.Set(ptr)
		}
	}

	return result
}

// convertSliceToArray converts a slice to a fixed-size array
func convertSliceToArray(src reflect.Value, targetType reflect.Type) reflect.Value {
	if src.Kind() != reflect.Slice || targetType.Kind() != reflect.Array {
		return reflect.Value{}
	}

	arrayLen := targetType.Len()
	sliceLen := src.Len()
	copyLen := sliceLen
	if copyLen > arrayLen {
		copyLen = arrayLen
	}

	result := reflect.New(targetType).Elem()
	elemType := targetType.Elem()

	for i := 0; i < copyLen; i++ {
		srcElem := src.Index(i)
		dstElem := result.Index(i)

		// Get the actual value from interface
		if srcElem.Kind() == reflect.Interface {
			srcElem = srcElem.Elem()
		}

		if !srcElem.IsValid() {
			continue // nil element
		}

		srcType := srcElem.Type()

		if srcType.AssignableTo(elemType) {
			dstElem.Set(srcElem)
		} else if srcType.ConvertibleTo(elemType) {
			dstElem.Set(srcElem.Convert(elemType))
		} else if elemType.Kind() == reflect.Ptr && srcType.AssignableTo(elemType.Elem()) {
			// Target is *T, source is T - need to create pointer
			ptr := reflect.New(elemType.Elem())
			ptr.Elem().Set(srcElem)
			dstElem.Set(ptr)
		} else if elemType.Kind() == reflect.Ptr && srcType.ConvertibleTo(elemType.Elem()) {
			ptr := reflect.New(elemType.Elem())
			ptr.Elem().Set(srcElem.Convert(elemType.Elem()))
			dstElem.Set(ptr)
		}
	}

	return result
}

// convertMap converts a map[interface{}]interface{} to the target map type
func convertMap(src reflect.Value, targetType reflect.Type) reflect.Value {
	if src.Kind() != reflect.Map {
		return reflect.Value{}
	}

	result := reflect.MakeMap(targetType)
	keyType := targetType.Key()
	valType := targetType.Elem()

	iter := src.MapRange()
	for iter.Next() {
		srcKey := iter.Key()
		srcVal := iter.Value()

		// Get the actual value from interface
		if srcKey.Kind() == reflect.Interface {
			srcKey = srcKey.Elem()
		}
		if srcVal.Kind() == reflect.Interface {
			srcVal = srcVal.Elem()
		}

		// Convert key
		var dstKey reflect.Value
		if !srcKey.IsValid() {
			dstKey = reflect.Zero(keyType)
		} else if srcKey.Type().AssignableTo(keyType) {
			dstKey = srcKey
		} else if srcKey.Type().ConvertibleTo(keyType) {
			dstKey = srcKey.Convert(keyType)
		} else {
			continue // Skip incompatible key
		}

		// Convert value
		var dstVal reflect.Value
		if !srcVal.IsValid() {
			dstVal = reflect.Zero(valType)
		} else if srcVal.Type().AssignableTo(valType) {
			dstVal = srcVal
		} else if srcVal.Type().ConvertibleTo(valType) {
			dstVal = srcVal.Convert(valType)
		} else {
			continue // Skip incompatible value
		}

		result.SetMapIndex(dstKey, dstVal)
	}

	return result
}

// Serialize serializes a value to buffer (for streaming/cross-language use).
// The third parameter is an optional callback for buffer objects (can be nil).
// If callback is provided, it will be called for each BufferObject during serialization.
// Return true from callback to write in-band, false for out-of-band.
func (f *Fory) Serialize(buffer *ByteBuffer, v interface{}, callback func(BufferObject) bool) error {
	// Reset internal state but NOT the buffer - caller manages buffer state
	// This allows streaming multiple values to the same buffer
	f.writeCtx.ResetState()
	f.writeCtx.buffer = buffer

	// Set up buffer callback for out-of-band serialization
	if callback != nil {
		f.writeCtx.bufferCallback = callback
		f.writeCtx.outOfBand = true
	}

	// Write protocol header
	writeHeader(f.writeCtx, f.config)

	// Serialize the value
	if err := f.writeCtx.WriteValue(reflect.ValueOf(v)); err != nil {
		return err
	}

	return nil
}

// Deserialize deserializes from buffer into the provided value (for streaming/cross-language use).
// The third parameter is optional external buffers for out-of-band data (can be nil).
func (f *Fory) Deserialize(buffer *ByteBuffer, v interface{}, buffers []*ByteBuffer) error {
	// Reset context and use the provided buffer
	f.readCtx.Reset()
	f.readCtx.buffer = buffer

	// Set up out-of-band buffers if provided
	if buffers != nil {
		f.readCtx.outOfBandBuffers = buffers
	}

	// Read and validate header
	if err := readHeader(f.readCtx); err != nil {
		return err
	}

	// Deserialize the value
	var result interface{}
	if err := f.readCtx.ReadValue(reflect.ValueOf(&result).Elem()); err != nil {
		return err
	}

	// Set the result into the provided value
	if v != nil {
		rv := reflect.ValueOf(v)
		if rv.Kind() == reflect.Ptr && !rv.IsNil() && result != nil {
			resultVal := reflect.ValueOf(result)
			if resultVal.Type().AssignableTo(rv.Elem().Type()) {
				rv.Elem().Set(resultVal)
			} else if resultVal.Type().ConvertibleTo(rv.Elem().Type()) {
				rv.Elem().Set(resultVal.Convert(rv.Elem().Type()))
			}
		}
	}

	return nil
}

// RegisterByNamespace registers a type with namespace for cross-language serialization
func (f *Fory) RegisterByNamespace(type_ interface{}, namespace, typeName string) error {
	var t reflect.Type
	if rt, ok := type_.(reflect.Type); ok {
		t = rt
	} else {
		t = reflect.TypeOf(type_)
		if t.Kind() == reflect.Ptr {
			t = t.Elem()
		}
	}
	return f.typeResolver.RegisterNamedType(t, 0, namespace, typeName)
}

// New creates a new Fory instance with the given options
func New(opts ...Option) *Fory {
	f := &Fory{
		config: defaultConfig(),
	}

	// Apply options
	for _, opt := range opts {
		opt(f)
	}

	// Initialize meta context if compatible mode is enabled
	if f.config.Compatible {
		f.metaContext = &MetaContext{
			typeMap:               make(map[reflect.Type]uint32),
			writingTypeDefs:       make([]*TypeDef, 0),
			readTypeInfos:         make([]TypeInfo, 0),
			scopedMetaShareEnable: true,
		}
	}

	// Initialize resolvers
	f.typeResolver = newTypeResolver(f)
	f.refResolver = newRefResolver(f.config.TrackRef)

	// Initialize reusable contexts with resolvers
	f.writeCtx = NewWriteContext(f.config.TrackRef, f.config.MaxDepth)
	f.writeCtx.typeResolver = f.typeResolver
	f.writeCtx.refResolver = f.refResolver
	f.writeCtx.compatible = f.config.Compatible

	f.readCtx = NewReadContext(f.config.TrackRef)
	f.readCtx.typeResolver = f.typeResolver
	f.readCtx.refResolver = f.refResolver
	f.readCtx.compatible = f.config.Compatible

	return f
}

// Reset clears internal state for reuse
func (f *Fory) Reset() {
	f.writeCtx.Reset()
	f.readCtx.Reset()
}

// ============================================================================
// Generic Serialization API
// ============================================================================

// Serialize - type T inferred, serializer auto-resolved.
// The serializer handles its own ref/type info writing internally.
// Falls back to reflection-based serialization for unregistered types.
// Note: Fory instance is NOT thread-safe. Use ThreadSafeFory for concurrent use.
func Serialize[T any](f *Fory, value *T) ([]byte, error) {
	// Reuse context, just reset state
	f.writeCtx.Reset()

	// Write protocol header
	writeHeader(f.writeCtx, f.config)

	// Fast path: type switch for common types (Go compiler can optimize this)
	// Using *T avoids interface heap allocation and struct copy
	// Store boxed value once and reuse in default case
	v := any(value)
	var err error
	switch val := v.(type) {
	case *bool:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(BOOL)
		f.writeCtx.buffer.WriteBool(*val)
	case *int8:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(INT8)
		f.writeCtx.buffer.WriteInt8(*val)
	case *int16:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(INT16)
		f.writeCtx.buffer.WriteInt16(*val)
	case *int32:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(INT32)
		f.writeCtx.buffer.WriteVarint32(*val)
	case *int64:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(INT64)
		f.writeCtx.buffer.WriteVarint64(*val)
	case *int:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		if strconv.IntSize == 64 {
			f.writeCtx.WriteTypeId(INT64)
			f.writeCtx.buffer.WriteVarint64(int64(*val))
		} else {
			f.writeCtx.WriteTypeId(INT32)
			f.writeCtx.buffer.WriteVarint32(int32(*val))
		}
	case *float32:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(FLOAT)
		f.writeCtx.buffer.WriteFloat32(*val)
	case *float64:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(DOUBLE)
		f.writeCtx.buffer.WriteFloat64(*val)
	case *string:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(STRING)
		f.writeCtx.buffer.WriteVarUint32(uint32(len(*val)))
		if len(*val) > 0 {
			f.writeCtx.buffer.WriteBinary(unsafe.Slice(unsafe.StringData(*val), len(*val)))
		}
	case *[]byte:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(BINARY)
		f.writeCtx.buffer.WriteBool(true) // in-band
		f.writeCtx.buffer.WriteLength(len(*val))
		f.writeCtx.buffer.WriteBinary(*val)
	case *[]int8:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(INT8_ARRAY)
		err = writeInt8Slice(f.writeCtx.buffer, *val)
	case *[]int16:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(INT16_ARRAY)
		err = writeInt16Slice(f.writeCtx.buffer, *val)
	case *[]int32:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(INT32_ARRAY)
		err = writeInt32Slice(f.writeCtx.buffer, *val)
	case *[]int64:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(INT64_ARRAY)
		err = writeInt64Slice(f.writeCtx.buffer, *val)
	case *[]int:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		if strconv.IntSize == 64 {
			f.writeCtx.WriteTypeId(INT64_ARRAY)
		} else {
			f.writeCtx.WriteTypeId(INT32_ARRAY)
		}
		err = writeIntSlice(f.writeCtx.buffer, *val)
	case *[]float32:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(FLOAT32_ARRAY)
		err = writeFloat32Slice(f.writeCtx.buffer, *val)
	case *[]float64:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(FLOAT64_ARRAY)
		err = writeFloat64Slice(f.writeCtx.buffer, *val)
	case *[]bool:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(BOOL_ARRAY)
		err = writeBoolSlice(f.writeCtx.buffer, *val)
	case *map[string]string:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(MAP)
		writeMapStringString(f.writeCtx.buffer, *val)
	case *map[string]int64:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(MAP)
		writeMapStringInt64(f.writeCtx.buffer, *val)
	case *map[string]int:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(MAP)
		writeMapStringInt(f.writeCtx.buffer, *val)
	case *map[string]float64:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(MAP)
		writeMapStringFloat64(f.writeCtx.buffer, *val)
	case *map[string]bool:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(MAP)
		writeMapStringBool(f.writeCtx.buffer, *val)
	case *map[int32]int32:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(MAP)
		writeMapInt32Int32(f.writeCtx.buffer, *val)
	case *map[int64]int64:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(MAP)
		writeMapInt64Int64(f.writeCtx.buffer, *val)
	case *map[int]int:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(MAP)
		writeMapIntInt(f.writeCtx.buffer, *val)
	default:
		// Fall back to reflection-based serialization
		// Reuse v (already boxed) and .Elem() to get underlying value without copy
		return f.serializeReflectValue(reflect.ValueOf(v).Elem())
	}

	if err != nil {
		return nil, err
	}

	// Return copy of buffer data
	return f.writeCtx.buffer.GetByteSlice(0, f.writeCtx.buffer.writerIndex), nil
}

// Deserialize deserializes data directly into the provided target.
// Takes pointer to avoid interface heap allocation and enable direct writes.
// For primitive types, this writes directly to the target pointer.
// For slices, it reuses existing capacity when possible.
// For structs, it reads directly into the struct fields.
// Note: Fory instance is NOT thread-safe. Use ThreadSafeFory for concurrent use.
func Deserialize[T any](f *Fory, data []byte, target *T) error {
	// Reuse context, reset and set new data
	f.readCtx.Reset()
	f.readCtx.SetData(data)

	// Read and validate header
	if err := readHeader(f.readCtx); err != nil {
		return err
	}

	// Fast path: type switch for common types (Go compiler can optimize this)
	switch t := any(target).(type) {
	case *bool:
		return f.readCtx.ReadBoolInto(t, true, true)
	case *int8:
		return f.readCtx.ReadInt8Into(t, true, true)
	case *int16:
		return f.readCtx.ReadInt16Into(t, true, true)
	case *int32:
		return f.readCtx.ReadInt32Into(t, true, true)
	case *int64:
		return f.readCtx.ReadInt64Into(t, true, true)
	case *int:
		return f.readCtx.ReadIntInto(t, true, true)
	case *float32:
		return f.readCtx.ReadFloat32Into(t, true, true)
	case *float64:
		return f.readCtx.ReadFloat64Into(t, true, true)
	case *string:
		return f.readCtx.ReadStringInto(t, true, true)
	case *[]byte:
		return f.readCtx.ReadByteSliceInto(t, true, true)
	case *[]int8:
		return f.readCtx.ReadInt8SliceInto(t, true, true)
	case *[]int16:
		return f.readCtx.ReadInt16SliceInto(t, true, true)
	case *[]int32:
		return f.readCtx.ReadInt32SliceInto(t, true, true)
	case *[]int64:
		return f.readCtx.ReadInt64SliceInto(t, true, true)
	case *[]int:
		return f.readCtx.ReadIntSliceInto(t, true, true)
	case *[]float32:
		return f.readCtx.ReadFloat32SliceInto(t, true, true)
	case *[]float64:
		return f.readCtx.ReadFloat64SliceInto(t, true, true)
	case *[]bool:
		return f.readCtx.ReadBoolSliceInto(t, true, true)
	case *map[string]string:
		return f.readCtx.ReadStringStringMapInto(t, true, true)
	case *map[string]int64:
		return f.readCtx.ReadStringInt64MapInto(t, true, true)
	case *map[string]int:
		return f.readCtx.ReadStringIntMapInto(t, true, true)
	case *map[string]float64:
		return f.readCtx.ReadStringFloat64MapInto(t, true, true)
	case *map[string]bool:
		return f.readCtx.ReadStringBoolMapInto(t, true, true)
	case *map[int32]int32:
		return f.readCtx.ReadInt32Int32MapInto(t, true, true)
	case *map[int64]int64:
		return f.readCtx.ReadInt64Int64MapInto(t, true, true)
	case *map[int]int:
		return f.readCtx.ReadIntIntMapInto(t, true, true)
	}

	// Slow path: use reflection-based deserialization
	f.readCtx.Reset()
	result, err := f.DeserializeAny(data)
	if err != nil {
		return err
	}
	if result != nil {
		resultVal := reflect.ValueOf(result)
		targetVal := reflect.ValueOf(target).Elem()
		targetType := targetVal.Type()

		if resultVal.Type().AssignableTo(targetType) {
			targetVal.Set(resultVal)
		} else if resultVal.Type().ConvertibleTo(targetType) {
			targetVal.Set(resultVal.Convert(targetType))
		} else if resultVal.Kind() == reflect.Ptr && resultVal.Type().Elem().AssignableTo(targetType) {
			targetVal.Set(resultVal.Elem())
		} else if resultVal.Kind() == reflect.Ptr && resultVal.Type().Elem().ConvertibleTo(targetType) {
			targetVal.Set(resultVal.Elem().Convert(targetType))
		} else if targetType.Kind() == reflect.Map && resultVal.Kind() == reflect.Map {
			// Handle map[interface{}]interface{} to map[K]V conversion
			converted := convertMap(resultVal, targetType)
			if converted.IsValid() {
				targetVal.Set(converted)
			}
		} else if targetType.Kind() == reflect.Slice && resultVal.Kind() == reflect.Slice {
			// Handle []interface{} to []T conversion
			converted := convertSlice(resultVal, targetType)
			if converted.IsValid() {
				targetVal.Set(converted)
			}
		} else if targetType.Kind() == reflect.Struct && resultVal.Kind() == reflect.Ptr {
			elemVal := resultVal.Elem()
			if elemVal.Type() == targetType {
				targetVal.Set(elemVal)
			}
		}
	}
	return nil
}

// SerializeAny serializes polymorphic values where concrete type is unknown.
// Uses runtime type dispatch to find the appropriate serializer.
func (f *Fory) SerializeAny(value any) ([]byte, error) {
	if value == nil {
		f.writeCtx.Reset()
		if f.metaContext != nil {
			f.metaContext.Reset()
		}
		writeHeader(f.writeCtx, f.config)
		f.writeCtx.buffer.WriteInt8(NullFlag)
		return f.writeCtx.buffer.GetByteSlice(0, f.writeCtx.buffer.writerIndex), nil
	}

	f.writeCtx.Reset()
	if f.metaContext != nil {
		f.metaContext.Reset()
	}

	// In compatible mode with meta share, we need two-phase serialization:
	// 1. Serialize data to temp buffer (collects type definitions)
	// 2. Write: header + type defs + data
	if f.config.Compatible && f.metaContext != nil {
		// Phase 1: Serialize data to a temporary buffer
		tempBuffer := NewByteBuffer(nil)
		origBuffer := f.writeCtx.buffer
		f.writeCtx.buffer = tempBuffer

		if err := f.writeCtx.WriteValue(reflect.ValueOf(value)); err != nil {
			f.writeCtx.buffer = origBuffer
			return nil, err
		}

		// Phase 2: Write header + type defs + data to the final buffer
		f.writeCtx.buffer = origBuffer
		writeHeader(f.writeCtx, f.config)

		// Write type definitions that were collected during phase 1
		f.typeResolver.writeTypeDefs(f.writeCtx.buffer)

		// Append the serialized data
		f.writeCtx.buffer.WriteBinary(tempBuffer.GetByteSlice(0, tempBuffer.writerIndex))

		return f.writeCtx.buffer.GetByteSlice(0, f.writeCtx.buffer.writerIndex), nil
	}

	// Non-compatible mode: single-phase serialization
	writeHeader(f.writeCtx, f.config)

	// Use WriteValue to serialize the value through the typeResolver
	if err := f.writeCtx.WriteValue(reflect.ValueOf(value)); err != nil {
		return nil, err
	}

	return f.writeCtx.buffer.GetByteSlice(0, f.writeCtx.buffer.writerIndex), nil
}

// serializeReflectValue serializes a reflect.Value directly, avoiding boxing overhead.
// This is used by Serialize[T] fallback path to avoid struct copy.
func (f *Fory) serializeReflectValue(value reflect.Value) ([]byte, error) {
	f.writeCtx.Reset()
	if f.metaContext != nil {
		f.metaContext.Reset()
	}

	// In compatible mode with meta share, we need two-phase serialization
	if f.config.Compatible && f.metaContext != nil {
		tempBuffer := NewByteBuffer(nil)
		origBuffer := f.writeCtx.buffer
		f.writeCtx.buffer = tempBuffer

		if err := f.writeCtx.WriteValue(value); err != nil {
			f.writeCtx.buffer = origBuffer
			return nil, err
		}

		f.writeCtx.buffer = origBuffer
		writeHeader(f.writeCtx, f.config)
		f.typeResolver.writeTypeDefs(f.writeCtx.buffer)
		f.writeCtx.buffer.WriteBinary(tempBuffer.GetByteSlice(0, tempBuffer.writerIndex))

		return f.writeCtx.buffer.GetByteSlice(0, f.writeCtx.buffer.writerIndex), nil
	}

	// Non-compatible mode: single-phase serialization
	writeHeader(f.writeCtx, f.config)
	if err := f.writeCtx.WriteValue(value); err != nil {
		return nil, err
	}

	return f.writeCtx.buffer.GetByteSlice(0, f.writeCtx.buffer.writerIndex), nil
}

// DeserializeAny deserializes polymorphic values.
// Returns the concrete type as `any`.
func (f *Fory) DeserializeAny(data []byte) (any, error) {
	f.readCtx.Reset()
	if f.metaContext != nil {
		f.metaContext.Reset()
	}
	f.readCtx.SetData(data)

	if err := readHeader(f.readCtx); err != nil {
		return nil, err
	}

	// In compatible mode with meta share, read type definitions first
	if f.config.Compatible && f.metaContext != nil {
		if err := f.typeResolver.readTypeDefs(f.readCtx.buffer); err != nil {
			return nil, fmt.Errorf("failed to read type definitions: %w", err)
		}
	}

	// Use ReadValue to deserialize through the typeResolver
	var result interface{}
	if err := f.readCtx.ReadValue(reflect.ValueOf(&result).Elem()); err != nil {
		return nil, err
	}

	return result, nil
}

// ============================================================================
// Protocol Header
// ============================================================================

// writeHeader writes the Fory protocol header
func writeHeader(ctx *WriteContext, config Config) {
	ctx.buffer.WriteInt16(MAGIC_NUMBER)

	var bitmap byte = 0
	if nativeEndian == binary.LittleEndian {
		bitmap |= LittleEndianFlag
	}
	if config.IsXlang {
		bitmap |= XLangFlag
	}
	if ctx.outOfBand {
		bitmap |= OutOfBandFlag
	}
	ctx.buffer.WriteByte_(bitmap)
	ctx.buffer.WriteByte_(LangGO)
}

// readHeader reads and validates the Fory protocol header
func readHeader(ctx *ReadContext) error {
	magicNumber := ctx.buffer.ReadInt16()
	if magicNumber != MAGIC_NUMBER {
		return ErrMagicNumber
	}
	_ = ctx.buffer.ReadByte_() // bitmap
	_ = ctx.buffer.ReadByte_() // language
	return nil
}
