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
	"errors"
	"fmt"
	"reflect"
	"strconv"
	"strings"
	"unsafe"
)

// ============================================================================
// Errors
// ============================================================================

// ErrNoSerializer indicates no serializer is registered for a type
var ErrNoSerializer = errors.New("fory: no serializer registered for type")

// ============================================================================
// Constants
// ============================================================================

// Bitmap flags for protocol header
const (
	IsNilFlag     = 1 << 0
	XLangFlag     = 1 << 1
	OutOfBandFlag = 1 << 2
)

// ============================================================================
// Config
// ============================================================================

// Config holds configuration options for Fory instances
type Config struct {
	TrackRef          bool
	MaxDepth          int
	IsXlang           bool
	Compatible        bool // Schema evolution compatibility mode
	MaxBinarySize     int  // Maximum byte length for a single deserialized binary payload (0 = no limit)
	MaxCollectionSize int  // Maximum element count for a single deserialized collection or map (0 = no limit)
}

// defaultConfig returns the default configuration
func defaultConfig() Config {
	return Config{
		TrackRef:          false,
		MaxDepth:          20,
		IsXlang:           false,
		MaxBinarySize:     64 * 1024 * 1024, // 64 MB
		MaxCollectionSize: 1_000_000,
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

// WithMaxBinarySize sets the maximum byte length for a single deserialized binary payload.
func WithMaxBinarySize(n int) Option {
	return func(f *Fory) {
		f.config.MaxBinarySize = n
	}
}

// WithMaxCollectionSize sets the maximum element count for a single deserialized collection or map.
func WithMaxCollectionSize(n int) Option {
	return func(f *Fory) {
		f.config.MaxCollectionSize = n
	}
}

// ============================================================================
// Fory - Main serialization instance
// ============================================================================

// Fory is the main serialization instance.
// Note: Fory is NOT thread-safe. Use ThreadSafeFory for concurrent use.
type Fory struct {
	config      Config
	metaContext *MetaContext

	// Reusable contexts - avoid allocation on each SerializeWithCallback/DeserializeWithCallbackBuffers call
	writeCtx *WriteContext
	readCtx  *ReadContext

	// Resolvers shared between contexts
	typeResolver *TypeResolver
	refResolver  *RefResolver
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
			typeMap:               make(map[uintptr]uint32),
			readTypeInfos:         make([]*TypeInfo, 0),
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
	f.writeCtx.xlang = f.config.IsXlang

	f.readCtx = NewReadContext(f.config.TrackRef)
	f.readCtx.typeResolver = f.typeResolver
	f.readCtx.refResolver = f.refResolver
	f.readCtx.compatible = f.config.Compatible
	f.readCtx.xlang = f.config.IsXlang
	f.readCtx.maxCollectionSize = f.config.MaxCollectionSize

	return f
}

// MetaContext returns the meta context for schema evolution
func (f *Fory) MetaContext() *MetaContext {
	return f.metaContext
}

// GetTypeResolver returns the type resolver for this Fory instance
func (f *Fory) GetTypeResolver() *TypeResolver {
	return f.typeResolver
}

// NewForyWithOptions is an alias for New for backward compatibility
func NewForyWithOptions(opts ...Option) *Fory {
	return New(opts...)
}

// NewFory is an alias for New for backward compatibility
func NewFory(opts ...Option) *Fory {
	return New(opts...)
}

func validateUserTypeID(typeID uint32) error {
	if typeID > maxUserTypeID {
		return fmt.Errorf("typeID must be in range [0, 0xfffffffe], got %d", typeID)
	}
	return nil
}

// RegisterStruct registers a struct type with a numeric ID for cross-language serialization.
// This is compatible with Java's fory.register(Class, int) method.
// type_ can be either a reflect.Type or an instance of the type
// typeID should be the user type ID in the range 0-0xfffffffe (0xffffffff is reserved for "unset").
// Note: For enum types, use RegisterEnum instead.
func (f *Fory) RegisterStruct(type_ any, typeID uint32) error {
	if err := validateUserTypeID(typeID); err != nil {
		return err
	}
	var t reflect.Type
	if rt, ok := type_.(reflect.Type); ok {
		t = rt
	} else {
		t = reflect.TypeOf(type_)
		if t.Kind() == reflect.Ptr {
			t = t.Elem()
		}
	}

	// Only struct types are supported via RegisterStruct
	// For enums, use RegisterEnum
	if t.Kind() != reflect.Struct {
		return fmt.Errorf("RegisterStruct only supports struct types; for enum types use RegisterEnum. Got: %v", t.Kind())
	}

	// Determine the internal type ID based on config
	var internalTypeID TypeId
	internalTypeID = f.typeResolver.structTypeID(t, false)

	return f.typeResolver.RegisterStruct(t, internalTypeID, typeID)
}

// RegisterUnion registers a union type with a numeric ID for cross-language serialization.
// type_ can be either a reflect.Type or an instance of the union type.
// typeID should be the user type ID in the range 0-0xfffffffe (0xffffffff is reserved for "unset").
// serializer must implement union payload encoding/decoding.
func (f *Fory) RegisterUnion(type_ any, typeID uint32, serializer Serializer) error {
	if serializer == nil {
		return fmt.Errorf("RegisterUnion requires a non-nil serializer")
	}
	if err := validateUserTypeID(typeID); err != nil {
		return err
	}
	var t reflect.Type
	if rt, ok := type_.(reflect.Type); ok {
		t = rt
	} else {
		t = reflect.TypeOf(type_)
		if t.Kind() == reflect.Ptr {
			t = t.Elem()
		}
	}
	if t.Kind() != reflect.Struct {
		return fmt.Errorf("RegisterUnion only supports struct types; got: %v", t.Kind())
	}
	return f.typeResolver.RegisterUnion(t, typeID, serializer)
}

// RegisterNamedUnion registers a union type with a namespace + type name for cross-language serialization.
// type_ can be either a reflect.Type or an instance of the union type.
// serializer must implement union payload encoding/decoding.
func (f *Fory) RegisterNamedUnion(type_ any, typeName string, serializer Serializer) error {
	if serializer == nil {
		return fmt.Errorf("RegisterNamedUnion requires a non-nil serializer")
	}
	var t reflect.Type
	if rt, ok := type_.(reflect.Type); ok {
		t = rt
	} else {
		t = reflect.TypeOf(type_)
		if t.Kind() == reflect.Ptr {
			t = t.Elem()
		}
	}
	if t.Kind() != reflect.Struct {
		return fmt.Errorf("RegisterNamedUnion only supports struct types; got: %v", t.Kind())
	}
	namespace := ""
	name := typeName
	if lastDot := strings.LastIndex(typeName, "."); lastDot >= 0 {
		namespace = typeName[:lastDot]
		name = typeName[lastDot+1:]
	}
	return f.typeResolver.RegisterNamedUnion(t, namespace, name, serializer)
}

// RegisterNamedStruct registers a named struct type for cross-language serialization
// type_ can be either a reflect.Type or an instance of the type
// typeName can include a namespace prefix separated by "." (e.g., "example.Foo")
// Note: For enum types, use RegisterNamedEnum instead.
func (f *Fory) RegisterNamedStruct(type_ any, typeName string) error {
	var t reflect.Type
	if rt, ok := type_.(reflect.Type); ok {
		t = rt
	} else {
		t = reflect.TypeOf(type_)
		if t.Kind() == reflect.Ptr {
			t = t.Elem()
		}
	}
	if t.Kind() != reflect.Struct {
		return fmt.Errorf("RegisterNamedStruct only supports struct types; for enum types use RegisterNamedEnum. Got: %v", t.Kind())
	}
	// Split typeName by last "." to extract namespace and type name
	namespace := ""
	name := typeName
	if lastDot := strings.LastIndex(typeName, "."); lastDot >= 0 {
		namespace = typeName[:lastDot]
		name = typeName[lastDot+1:]
	}
	return f.typeResolver.RegisterNamedStruct(t, 0, namespace, name)
}

// RegisterEnum registers an enum type with a numeric ID for cross-language serialization.
// In Go, enums are typically defined as int-based types (e.g., type Color int32).
// This method creates an enum serializer that writes/reads the enum value as VarUint32Small7.
// type_ can be either a reflect.Type or an instance of the enum type
// typeID should be the user type ID in the range 0-0xfffffffe (0xffffffff is reserved for "unset").
func (f *Fory) RegisterEnum(type_ any, typeID uint32) error {
	if err := validateUserTypeID(typeID); err != nil {
		return err
	}
	var t reflect.Type
	if rt, ok := type_.(reflect.Type); ok {
		t = rt
	} else {
		t = reflect.TypeOf(type_)
		if t.Kind() == reflect.Ptr {
			t = t.Elem()
		}
	}

	// Verify it's a numeric type (Go enums are int-based)
	switch t.Kind() {
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
		reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		// OK
	default:
		return fmt.Errorf("RegisterEnum only supports numeric types (Go enums); got: %v", t.Kind())
	}

	return f.typeResolver.RegisterEnum(t, typeID)
}

// RegisterNamedEnum registers an enum type with a name for cross-language serialization.
// In Go, enums are typically defined as int-based types (e.g., type Color int32).
// type_ can be either a reflect.Type or an instance of the enum type
// typeName can include a namespace prefix separated by "." (e.g., "example.Color")
func (f *Fory) RegisterNamedEnum(type_ any, typeName string) error {
	var t reflect.Type
	if rt, ok := type_.(reflect.Type); ok {
		t = rt
	} else {
		t = reflect.TypeOf(type_)
		if t.Kind() == reflect.Ptr {
			t = t.Elem()
		}
	}

	// Verify it's a numeric type (Go enums are int-based)
	switch t.Kind() {
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
		reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		// OK
	default:
		return fmt.Errorf("RegisterNamedEnum only supports numeric types (Go enums); got: %v", t.Kind())
	}

	// Split typeName by last "." to extract namespace and type name
	namespace := ""
	name := typeName
	if lastDot := strings.LastIndex(typeName, "."); lastDot >= 0 {
		namespace = typeName[:lastDot]
		name = typeName[lastDot+1:]
	}
	return f.typeResolver.RegisterNamedEnum(t, namespace, name)
}

// RegisterExtension registers a type as an extension type with a numeric ID.
// Extension types use a custom serializer provided by the user.
// typeID should be the user type ID in the range 0-0xfffffffe (0xffffffff is reserved for "unset").
func (f *Fory) RegisterExtension(type_ any, typeID uint32, serializer ExtensionSerializer) error {
	if err := validateUserTypeID(typeID); err != nil {
		return err
	}
	var t reflect.Type
	if rt, ok := type_.(reflect.Type); ok {
		t = rt
	} else {
		t = reflect.TypeOf(type_)
		if t.Kind() == reflect.Ptr {
			t = t.Elem()
		}
	}
	return f.typeResolver.RegisterExtension(t, typeID, serializer)
}

// RegisterNamedExtension registers a type as an extension type (NAMED_EXT) for cross-language serialization.
// Extension types use a custom serializer provided by the user.
// This is used for types with custom serializers in cross-language serialization.
//
// Example:
//
//	type MyExtSerializer struct{}
//
//	func (s *MyExtSerializer) Write(buf *ByteBuffer, value any) error {
//	    myExt := value.(MyExt)
//	    buf.WriteVarint32(myExt.Id)
//	    return nil
//	}
//
//	func (s *MyExtSerializer) Read(buf *ByteBuffer) (any, error) {
//	    id := buf.ReadVarint32(err)
//	    return MyExt{Id: id}, nil
//	}
//
//	// Register with custom serializer
//	f.RegisterNamedExtension(MyExt{}, "my_ext", &MyExtSerializer{})
func (f *Fory) RegisterNamedExtension(type_ any, typeName string, serializer ExtensionSerializer) error {
	var t reflect.Type
	if rt, ok := type_.(reflect.Type); ok {
		t = rt
	} else {
		t = reflect.TypeOf(type_)
		if t.Kind() == reflect.Ptr {
			t = t.Elem()
		}
	}
	return f.typeResolver.RegisterNamedExtension(t, "", typeName, serializer)
}

// Reset clears internal state for reuse
func (f *Fory) Reset() {
	f.writeCtx.Reset()
	f.readCtx.Reset()
}

// ============================================================================
// Serialization Method API
// ============================================================================

// Serialize serializes polymorphic values where concrete type is unknown.
// Uses runtime type dispatch to find the appropriate serializer.
//
// IMPORTANT: The returned byte slice is a zero-copy view of the internal buffer.
// It will be invalidated on the next Serialize/Marshal call on this Fory instance.
// If you need to retain the data, clone it:
//
//	data, _ := f.Serialize(value)
//	safeCopy := bytes.Clone(data)
//
// For thread-safe usage, use threadsafe.Fory which copies the data internally.
func (f *Fory) Serialize(value any) ([]byte, error) {
	defer f.resetWriteState()
	// Check if value is nil interface OR a nil pointer/slice/map/etc.
	// In Go, `*int32(nil)` wrapped in `any` is NOT equal to `nil`, but we need to serialize it as null.
	if isNilValue(value) {
		// Use Java-compatible null format: 3 bytes (magic + bitmap with isNilFlag)
		writeNullHeader(f.writeCtx)
		return f.writeCtx.buffer.GetByteSlice(0, f.writeCtx.buffer.writerIndex), nil
	}
	// WriteData protocol header
	writeHeader(f.writeCtx, f.config)

	// Serialize the value - TypeMeta is written inline using streaming protocol
	reflValue := reflect.ValueOf(value)
	// if it's struct kind, must be pointer to struct, otherwise error
	if reflValue.Kind() == reflect.Struct {
		reflType := reflValue.Type()
		if reflType != dateReflectType && reflType != timeReflectType {
			return nil, fmt.Errorf("Serialize struct %s directly is disallowed, use pointer to struct (*%s) instead",
				reflValue.Type(), reflValue.Type())
		}
	}
	f.writeCtx.WriteValue(reflValue, RefModeTracking, true)
	if f.writeCtx.HasError() {
		return nil, f.writeCtx.TakeError()
	}

	return f.writeCtx.buffer.GetByteSlice(0, f.writeCtx.buffer.writerIndex), nil
}

// Deserialize deserializes data directly into the provided target value.
// The target must be a pointer to the value to deserialize into.
func (f *Fory) Deserialize(data []byte, v any) error {
	defer f.resetReadState()
	f.readCtx.SetData(data)

	isNull := readHeader(f.readCtx)
	if f.readCtx.HasError() {
		return f.readCtx.TakeError()
	}

	// Check if the serialized object is null
	if isNull {
		return nil
	}

	// Deserialize the value - TypeMeta is read inline using streaming protocol
	target := reflect.ValueOf(v).Elem()
	f.readCtx.ReadValue(target, RefModeTracking, true)
	if f.readCtx.HasError() {
		return f.readCtx.TakeError()
	}

	return nil
}

// resetReadState resets read context state without allocation
func (f *Fory) resetReadState() {
	f.readCtx.Reset()
	if f.metaContext != nil {
		f.metaContext.Reset()
	}
}

// resetWriteState resets write context state without allocation
func (f *Fory) resetWriteState() {
	f.writeCtx.Reset()
	if f.metaContext != nil {
		f.metaContext.Reset()
	}
}

// SerializeTo serializes a value and appends the bytes to the provided buffer.
// This is useful when you need to write multiple serialized values to the same buffer.
// Returns error if serialization fails.
func (f *Fory) SerializeTo(buf *ByteBuffer, value any) error {
	// Handle nil values
	if isNilValue(value) {
		// Use Java-compatible null format: 1 byte (bitmap with isNilFlag)
		buf.WriteByte_(IsNilFlag)
		return nil
	}

	defer f.resetWriteState()

	// Temporarily swap buffer
	origBuffer := f.writeCtx.buffer
	f.writeCtx.buffer = buf

	// Write protocol header
	writeHeader(f.writeCtx, f.config)

	// Fast path for pointer-to-struct types (bypasses ptrToValueSerializer wrapper)
	rv := reflect.ValueOf(value)
	if rv.Kind() == reflect.Ptr && !rv.IsNil() && rv.Elem().Kind() == reflect.Struct && !f.config.TrackRef {
		// Get TypeInfo using fast pointer cache
		elemValue := rv.Elem()
		typeInfo, err := f.typeResolver.getTypeInfo(rv, true)
		if err == nil && typeInfo != nil && typeInfo.Serializer != nil {
			// Write not-null flag and type ID directly
			buf.WriteInt8(NotNullValueFlag)
			f.typeResolver.WriteTypeInfo(buf, typeInfo, f.writeCtx.Err())
			// Call the underlying struct serializer's WriteData directly
			if ptrSer, ok := typeInfo.Serializer.(*ptrToValueSerializer); ok {
				ptrSer.valueSerializer.WriteData(f.writeCtx, elemValue)
			} else {
				typeInfo.Serializer.WriteData(f.writeCtx, elemValue)
			}
			if f.writeCtx.HasError() {
				f.writeCtx.buffer = origBuffer
				return f.writeCtx.TakeError()
			}
			f.writeCtx.buffer = origBuffer
			return nil
		}
	}

	// Standard path - TypeMeta is written inline using streaming protocol
	f.writeCtx.WriteValue(rv, RefModeTracking, true)
	if f.writeCtx.HasError() {
		f.writeCtx.buffer = origBuffer
		return f.writeCtx.TakeError()
	}

	// Restore original buffer
	f.writeCtx.buffer = origBuffer
	return nil
}

// DeserializeFrom deserializes data from an existing buffer directly into the provided target value.
// The buffer's reader index is advanced as data is read.
// This is useful when reading multiple serialized values from the same buffer.
func (f *Fory) DeserializeFrom(buf *ByteBuffer, v any) error {
	// Reset contexts for each independent serialized object
	defer f.resetReadState()

	// Temporarily swap buffer
	origBuffer := f.readCtx.buffer
	f.readCtx.buffer = buf

	isNull := readHeader(f.readCtx)
	if f.readCtx.HasError() {
		f.readCtx.buffer = origBuffer
		return f.readCtx.TakeError()
	}

	// Check if the serialized object is null
	if isNull {
		f.readCtx.buffer = origBuffer
		return nil
	}

	// Deserialize the value - TypeMeta is read inline using streaming protocol
	target := reflect.ValueOf(v).Elem()
	f.readCtx.ReadValue(target, RefModeTracking, true)
	if f.readCtx.HasError() {
		f.readCtx.buffer = origBuffer
		return f.readCtx.TakeError()
	}

	// Restore original buffer
	f.readCtx.buffer = origBuffer

	return nil
}

// Marshal serializes a value to bytes.
//
// IMPORTANT: The returned byte slice is a zero-copy view of the internal buffer.
// It will be invalidated on the next Serialize/Marshal call on this Fory instance.
// If you need to retain the data, clone it:
//
//	data, _ := f.Marshal(value)
//	safeCopy := bytes.Clone(data)
//
// For thread-safe usage, use threadsafe.Fory which copies the data internally.
func (f *Fory) Marshal(v any) ([]byte, error) {
	return f.Serialize(v)
}

// Unmarshal deserializes bytes into the provided value.
func (f *Fory) Unmarshal(data []byte, v any) error {
	return f.Deserialize(data, v)
}

// SerializeWithCallback serializes a value to buffer (for streaming/cross-language use).
// The third parameter is an optional callback for buffer objects (can be nil).
// If callback is provided, it will be called for each BufferObject during serialization.
// Return true from callback to write in-band, false for out-of-band.
func (f *Fory) SerializeWithCallback(buffer *ByteBuffer, v any, callback func(BufferObject) bool) error {
	buf := f.writeCtx.buffer
	defer func() {
		// Reset internal state but NOT the buffer - caller manages buffer state
		// This allows streaming multiple values to the same buffer
		f.writeCtx.ResetState()
		f.writeCtx.buffer = buf
		if f.metaContext != nil {
			f.metaContext.Reset()
		}
		// Set up buffer callback for out-of-band serialization
		if callback != nil {
			f.writeCtx.bufferCallback = nil
			f.writeCtx.outOfBand = false
		}
	}()
	f.writeCtx.buffer = buffer
	if f.metaContext != nil {
		f.metaContext.Reset()
	}
	// Set up buffer callback for out-of-band serialization
	if callback != nil {
		f.writeCtx.bufferCallback = callback
		f.writeCtx.outOfBand = true
	}

	// WriteData protocol header
	writeHeader(f.writeCtx, f.config)

	// Serialize the value - TypeMeta is written inline using streaming protocol
	f.writeCtx.WriteValue(reflect.ValueOf(v), RefModeTracking, true)
	if f.writeCtx.HasError() {
		return f.writeCtx.TakeError()
	}

	return nil
}

// DeserializeWithCallbackBuffers deserializes from buffer into the provided value (for streaming/cross-language use).
// The third parameter is optional external buffers for out-of-band data (can be nil).
func (f *Fory) DeserializeWithCallbackBuffers(buffer *ByteBuffer, v any, buffers []*ByteBuffer) error {
	// Reset context and use the provided buffer
	f.readCtx.buffer = buffer
	defer func() {
		f.readCtx.Reset()
		if f.metaContext != nil {
			f.metaContext.Reset()
		}
		f.readCtx.buffer = nil
		f.readCtx.outOfBandBuffers = nil
	}()
	// Set up out-of-band buffers if provided
	if buffers != nil {
		f.readCtx.outOfBandBuffers = buffers
	}

	// ReadData and validate header
	isNull := readHeader(f.readCtx)
	if f.readCtx.HasError() {
		return f.readCtx.TakeError()
	}

	// Check if the serialized object is null
	if isNull {
		// v must be a pointer so we can set it to nil
		rv := reflect.ValueOf(v)
		if rv.Kind() == reflect.Ptr && !rv.IsNil() {
			rv.Elem().Set(reflect.Zero(rv.Elem().Type()))
		}
		return nil
	}

	// v must be a pointer so we can deserialize into it
	if v == nil {
		return fmt.Errorf("v cannot be nil")
	}
	rv := reflect.ValueOf(v)
	if rv.Kind() != reflect.Ptr {
		return fmt.Errorf("v must be a pointer, got %v", rv.Kind())
	}
	if rv.IsNil() {
		return fmt.Errorf("v must be a non-nil pointer")
	}

	// Deserialize the value - TypeMeta is read inline using streaming protocol
	f.readCtx.ReadValue(rv.Elem(), RefModeTracking, true)
	if f.readCtx.HasError() {
		return f.readCtx.TakeError()
	}

	return nil
}

// serializeReflectValue serializes a reflect.Value directly, avoiding boxing overhead.
// This is used by Serialize[T] fallback path to avoid struct copy.
// For structs, the value must be a pointer to struct, not struct value.
func (f *Fory) serializeReflectValue(value reflect.Value) ([]byte, error) {
	// Check that structs are passed as pointers
	if value.Kind() == reflect.Struct {
		return nil, fmt.Errorf("cannot serialize struct %s directly, use pointer to struct (*%s) instead", value.Type(), value.Type())
	}

	// Serialize the value - TypeMeta is written inline using streaming protocol
	f.writeCtx.WriteValue(value, RefModeTracking, true)
	if f.writeCtx.HasError() {
		return nil, f.writeCtx.TakeError()
	}

	return f.writeCtx.buffer.GetByteSlice(0, f.writeCtx.buffer.writerIndex), nil
}

// ============================================================================
// Protocol Header
// ============================================================================

// writeHeader writes the Fory protocol header
func writeHeader(ctx *WriteContext, config Config) {
	var bitmap byte = 0
	if config.IsXlang {
		bitmap |= XLangFlag
	}
	if ctx.outOfBand {
		bitmap |= OutOfBandFlag
	}
	ctx.buffer.WriteByte_(bitmap)
}

// isNilValue checks if a value is nil, including nil pointers wrapped in any
// In Go, `*int32(nil)` wrapped in `any` is NOT equal to `nil`, but we need to treat it as null.
func isNilValue(value any) bool {
	if value == nil {
		return true
	}
	rv := reflect.ValueOf(value)
	switch rv.Kind() {
	case reflect.Ptr, reflect.Slice, reflect.Map, reflect.Chan, reflect.Func, reflect.Interface:
		return rv.IsNil()
	}
	return false
}

// writeNullHeader writes a null object header (1 byte: bitmap with isNilFlag)
// This is compatible with Java's null serialization format
func writeNullHeader(ctx *WriteContext) {
	ctx.buffer.WriteByte_(IsNilFlag) // bitmap with only isNilFlag set
}

// Special return value indicating null object in readHeader
// Using math.MinInt32 to avoid conflict with -1 which is used for "no meta offset"
const NullObjectMetaOffset int32 = -0x7FFFFFFF

// readHeader reads and validates the Fory protocol header
// Returns true if the serialized object is null
// Sets error on ctx if header is invalid (use ctx.HasError() to check)
func readHeader(ctx *ReadContext) bool {
	err := ctx.Err()
	bitmap := ctx.buffer.ReadByte(err)
	if ctx.HasError() {
		return false
	}

	// Check if this is a null object - only bitmap with isNilFlag was written
	if (bitmap & IsNilFlag) != 0 {
		return true // is null
	}

	return false // not null
}

// ============================================================================
// Generic Serialization API
// ============================================================================

// Serialize - type T inferred, serializer auto-resolved.
// The serializer handles its own ref/type info writing internally.
// Falls back to reflection-based serialization for unregistered types.
// Note: For structs, T must be a pointer to struct (*MyStruct), not struct value.
//
// IMPORTANT: The returned byte slice is a zero-copy view of the internal buffer.
// It will be invalidated on the next Serialize call on this Fory instance.
// If you need to retain the data, clone it:
//
//	data, _ := fory.Serialize(f, value)
//	safeCopy := bytes.Clone(data)
//
// For thread-safe usage, use threadsafe.Serialize which copies the data internally.
func Serialize[T any](f *Fory, value T) ([]byte, error) {
	defer f.resetWriteState()
	// WriteData protocol header
	writeHeader(f.writeCtx, f.config)

	// Fast path: type switch for common types (Go compiler can optimize this)
	v := any(value)
	var err error
	switch val := v.(type) {
	case bool:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(BOOL)
		f.writeCtx.buffer.WriteBool(val)
	case int8:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(INT8)
		f.writeCtx.buffer.WriteInt8(val)
	case int16:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(INT16)
		f.writeCtx.buffer.WriteInt16(val)
	case int32:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(INT32)
		f.writeCtx.buffer.WriteVarint32(val)
	case int64:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(INT64)
		f.writeCtx.buffer.WriteVarint64(val)
	case int:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		if strconv.IntSize == 64 {
			f.writeCtx.WriteTypeId(INT64)
			f.writeCtx.buffer.WriteVarint64(int64(val))
		} else {
			f.writeCtx.WriteTypeId(INT32)
			f.writeCtx.buffer.WriteVarint32(int32(val))
		}
	case float32:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(FLOAT32)
		f.writeCtx.buffer.WriteFloat32(val)
	case float64:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(FLOAT64)
		f.writeCtx.buffer.WriteFloat64(val)
	case string:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(STRING)
		f.writeCtx.buffer.WriteVarUint32(uint32(len(val)))
		if len(val) > 0 {
			f.writeCtx.buffer.WriteBinary(unsafe.Slice(unsafe.StringData(val), len(val)))
		}
	case []byte:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(BINARY)
		f.writeCtx.buffer.WriteLength(len(val))
		f.writeCtx.buffer.WriteBinary(val)
	case []int8:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(INT8_ARRAY)
		WriteInt8Slice(f.writeCtx.buffer, val)
	case []int16:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(INT16_ARRAY)
		WriteInt16Slice(f.writeCtx.buffer, val)
	case []int32:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(INT32_ARRAY)
		WriteInt32Slice(f.writeCtx.buffer, val)
	case []int64:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(INT64_ARRAY)
		WriteInt64Slice(f.writeCtx.buffer, val)
	case []int:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		if strconv.IntSize == 64 {
			f.writeCtx.WriteTypeId(INT64_ARRAY)
		} else {
			f.writeCtx.WriteTypeId(INT32_ARRAY)
		}
		WriteIntSlice(f.writeCtx.buffer, val)
	case []float32:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(FLOAT32_ARRAY)
		WriteFloat32Slice(f.writeCtx.buffer, val)
	case []float64:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(FLOAT64_ARRAY)
		WriteFloat64Slice(f.writeCtx.buffer, val)
	case []bool:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(BOOL_ARRAY)
		WriteBoolSlice(f.writeCtx.buffer, val)
	case map[string]string:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(MAP)
		writeMapStringString(f.writeCtx.buffer, val, false)
	case map[string]int64:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(MAP)
		writeMapStringInt64(f.writeCtx.buffer, val, false)
	case map[string]int32:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(MAP)
		writeMapStringInt32(f.writeCtx.buffer, val, false)
	case map[string]int:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(MAP)
		writeMapStringInt(f.writeCtx.buffer, val, false)
	case map[string]float64:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(MAP)
		writeMapStringFloat64(f.writeCtx.buffer, val, false)
	case map[string]bool:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(MAP)
		writeMapStringBool(f.writeCtx.buffer, val, false)
	case map[int32]int32:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(MAP)
		writeMapInt32Int32(f.writeCtx.buffer, val, false)
	case map[int64]int64:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(MAP)
		writeMapInt64Int64(f.writeCtx.buffer, val, false)
	case map[int]int:
		f.writeCtx.buffer.WriteInt8(NotNullValueFlag)
		f.writeCtx.WriteTypeId(MAP)
		writeMapIntInt(f.writeCtx.buffer, val, false)
	default:
		// Fall back to reflection-based serialization
		return f.serializeReflectValue(reflect.ValueOf(v))
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

	// ReadData and validate header
	isNull := readHeader(f.readCtx)
	if f.readCtx.HasError() {
		return f.readCtx.TakeError()
	}

	// Check if the serialized object is null
	if isNull {
		var zero T
		*target = zero
		return nil
	}

	// Fast path: type switch for common types (Go compiler can optimize this)
	// For primitives, read null flag, skip type ID, then read value from buffer
	buf := f.readCtx.buffer
	err := f.readCtx.Err()
	switch t := any(target).(type) {
	case *bool:
		_ = buf.ReadInt8(err)  // null flag
		_ = buf.ReadUint8(err) // type ID
		*t = buf.ReadBool(err)
		return f.readCtx.CheckError()
	case *int8:
		_ = buf.ReadInt8(err)
		_ = buf.ReadUint8(err)
		*t = buf.ReadInt8(err)
		return f.readCtx.CheckError()
	case *int16:
		_ = buf.ReadInt8(err)
		_ = buf.ReadUint8(err)
		*t = buf.ReadInt16(err)
		return f.readCtx.CheckError()
	case *int32:
		_ = buf.ReadInt8(err)
		_ = buf.ReadUint8(err)
		*t = buf.ReadVarint32(err)
		return f.readCtx.CheckError()
	case *int64:
		_ = buf.ReadInt8(err)
		_ = buf.ReadUint8(err)
		*t = buf.ReadVarint64(err)
		return f.readCtx.CheckError()
	case *int:
		_ = buf.ReadInt8(err)
		_ = buf.ReadUint8(err)
		*t = int(buf.ReadVarint64(err))
		return f.readCtx.CheckError()
	case *float32:
		_ = buf.ReadInt8(err)
		_ = buf.ReadUint8(err)
		*t = buf.ReadFloat32(err)
		return f.readCtx.CheckError()
	case *float64:
		_ = buf.ReadInt8(err)
		_ = buf.ReadUint8(err)
		*t = buf.ReadFloat64(err)
		return f.readCtx.CheckError()
	case *string:
		_ = buf.ReadInt8(err)  // null flag
		_ = buf.ReadUint8(err) // type ID
		*t = f.readCtx.ReadString()
		return f.readCtx.CheckError()
	case *[]byte:
		*t = f.readCtx.ReadByteSlice(RefModeNullOnly, true)
		return f.readCtx.CheckError()
	case *[]int8:
		*t = f.readCtx.ReadInt8Slice(RefModeNullOnly, true)
		return f.readCtx.CheckError()
	case *[]int16:
		*t = f.readCtx.ReadInt16Slice(RefModeNullOnly, true)
		return f.readCtx.CheckError()
	case *[]int32:
		*t = f.readCtx.ReadInt32Slice(RefModeNullOnly, true)
		return f.readCtx.CheckError()
	case *[]int64:
		*t = f.readCtx.ReadInt64Slice(RefModeNullOnly, true)
		return f.readCtx.CheckError()
	case *[]int:
		*t = f.readCtx.ReadIntSlice(RefModeNullOnly, true)
		return f.readCtx.CheckError()
	case *[]float32:
		*t = f.readCtx.ReadFloat32Slice(RefModeNullOnly, true)
		return f.readCtx.CheckError()
	case *[]float64:
		*t = f.readCtx.ReadFloat64Slice(RefModeNullOnly, true)
		return f.readCtx.CheckError()
	case *[]bool:
		*t = f.readCtx.ReadBoolSlice(RefModeNullOnly, true)
		return f.readCtx.CheckError()
	case *map[string]string:
		*t = f.readCtx.ReadStringStringMap(RefModeNullOnly, true)
		return nil
	case *map[string]int64:
		*t = f.readCtx.ReadStringInt64Map(RefModeNullOnly, true)
		return nil
	case *map[string]int32:
		*t = f.readCtx.ReadStringInt32Map(RefModeNullOnly, true)
		return nil
	case *map[string]int:
		*t = f.readCtx.ReadStringIntMap(RefModeNullOnly, true)
		return nil
	case *map[string]float64:
		*t = f.readCtx.ReadStringFloat64Map(RefModeNullOnly, true)
		return nil
	case *map[string]bool:
		*t = f.readCtx.ReadStringBoolMap(RefModeNullOnly, true)
		return nil
	case *map[int32]int32:
		*t = f.readCtx.ReadInt32Int32Map(RefModeNullOnly, true)
		return nil
	case *map[int64]int64:
		*t = f.readCtx.ReadInt64Int64Map(RefModeNullOnly, true)
		return nil
	case *map[int]int:
		*t = f.readCtx.ReadIntIntMap(RefModeNullOnly, true)
		return nil
	default:
		// Slow path: use serializer-based deserialization
		targetVal := reflect.ValueOf(target).Elem()
		targetType := targetVal.Type()

		// Get serializer for the target type
		serializer, err := f.typeResolver.getSerializerByType(targetType, false)
		if err != nil {
			return fmt.Errorf("failed to get serializer for type %v: %w", targetType, err)
		}

		// Use Read to deserialize directly into target
		serializer.Read(f.readCtx, RefModeTracking, true, false, targetVal)
		return f.readCtx.CheckError()
	}
}
