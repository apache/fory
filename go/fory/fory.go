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
	"fmt"
	"reflect"
	"sync"
)

// Option represents a configuration option for Fory instances.
// This follows the functional options pattern, allowing flexible configuration
// by passing variadic option functions to constructors like NewForyWithOptions.
type Option func(*Fory)

// WithCompatible sets the compatible mode for the Fory instance.
// When compatible=true, scoped meta sharing is automatically enabled.
func WithCompatible(compatible bool) Option {
	return func(f *Fory) {
		f.compatible = compatible
		if compatible {
			f.metaContext = NewMetaContext(true) // Enable scoped meta sharing
		} else {
			f.metaContext = nil
		}
	}
}

// WithRefTracking sets the reference tracking mode for the Fory instance
func WithRefTracking(refTracking bool) Option {
	return func(f *Fory) {
		f.refTracking = refTracking
	}
}

// WithScopedMetaShare sets the scoped meta share mode for the Fory instance.
// Note: Compatible mode automatically enables scoped meta sharing.
// This option is mainly used for fine-grained control when compatible mode is already enabled.
func WithScopedMetaShare(enabled bool) Option {
	return func(f *Fory) {
		if f.metaContext == nil {
			f.metaContext = NewMetaContext(enabled)
		} else {
			f.metaContext.SetScopedMetaShareEnabled(enabled)
		}
	}
}

func NewFory(refTracking bool) *Fory {
	return NewForyWithOptions(WithRefTracking(refTracking))
}

// NewForyWithOptions creates a Fory instance with configurable options
func NewForyWithOptions(options ...Option) *Fory {
	fory := &Fory{
		refResolver: nil,
		refTracking: false,
		language:    XLANG,
		buffer:      NewByteBuffer(nil),
		compatible:  false,
	}

	// Apply options
	for _, option := range options {
		option(fory)
	}

	// Create a new type resolver for this instance but copy generated serializers from global resolver
	fory.typeResolver = newTypeResolver(fory)

	// Initialize meta context if compatible mode is enabled
	if fory.compatible {
		fory.metaContext = NewMetaContext(true)
	}

	fory.refResolver = newRefResolver(fory.refTracking)
	return fory
}

var foryPool = sync.Pool{
	New: func() interface{} {
		return NewFory(true)
	},
}

func GetFory() *Fory {
	return foryPool.Get().(*Fory)
}

func PutFory(fory *Fory) {
	foryPool.Put(fory)
}

// Marshal returns the MessagePack encoding of v.
func Marshal(v interface{}) ([]byte, error) {
	fory := GetFory()
	err := fory.Serialize(nil, v, nil)
	data := fory.buffer.GetByteSlice(0, fory.buffer.writerIndex)
	PutFory(fory)
	if err != nil {
		return nil, err
	}
	return data, err
}

// Unmarshal decodes the fory-encoded data and stores the result
// in the value pointed to by v.
func Unmarshal(data []byte, v interface{}) error {
	fory := GetFory()
	err := fory.Deserialize(NewByteBuffer(data), v, nil)
	PutFory(fory)
	return err
}

// BufferCallback to check whether write buffer in band. If the callback returns false, the given buffer is
// out-of-band; otherwise the buffer is serialized in-band, i.e. inside the serialized stream.
type BufferCallback = func(o BufferObject) bool

type Language = uint8

const (
	XLANG Language = iota
	JAVA
	PYTHON
	CPP
	GO
	JAVASCRIPT
	RUST
	DART
)

const (
	isNilFlag byte = 1 << iota
	isLittleEndianFlag
	isCrossLanguageFlag
	isOutOfBandFlag
)

const (
	NilFlag          = 0
	LittleEndianFlag = 2
	XLangFlag        = 4
	CallBackFlag     = 8
)

const MAGIC_NUMBER int16 = 0x62D4

type Fory struct {
	typeResolver   *typeResolver
	refResolver    *RefResolver
	refTracking    bool
	language       Language
	bufferCallback BufferCallback
	peerLanguage   Language
	buffer         *ByteBuffer
	buffers        []*ByteBuffer
	compatible     bool
	metaContext    *MetaContext
}

func (f *Fory) RegisterTagType(tag string, v interface{}) error {
	return f.typeResolver.RegisterTypeTag(reflect.ValueOf(v), tag)
}

func (f *Fory) Marshal(v interface{}) ([]byte, error) {
	err := f.Serialize(nil, v, nil)
	if err != nil {
		return nil, err
	}
	return f.buffer.GetByteSlice(0, f.buffer.writerIndex), nil
}

func (f *Fory) Serialize(buf *ByteBuffer, v interface{}, callback BufferCallback) error {
	defer f.resetWrite()
	f.bufferCallback = callback
	buffer := buf
	if buffer == nil {
		buffer = f.buffer
		buffer.writerIndex = 0
	}
	if f.language == XLANG {
		buffer.WriteInt16(MAGIC_NUMBER)
	} else {
		return fmt.Errorf("%d language is not supported", f.language)
	}
	var bitmap byte = 0
	if isNil(reflect.ValueOf(v)) {
		bitmap |= NilFlag
	}
	if nativeEndian == binary.LittleEndian {
		bitmap |= LittleEndianFlag
	}
	// set reader as x_lang.
	if f.language == XLANG {
		bitmap |= XLangFlag
	} else {
		return fmt.Errorf("%d language is not supported", f.language)
	}
	if callback != nil {
		bitmap |= CallBackFlag
	}
	if err := buffer.WriteByte(bitmap); err != nil {
		return err
	}
	if f.language != XLANG {
		return fmt.Errorf("%d language is not supported", f.language)
	} else {
		if err := buffer.WriteByte(GO); err != nil {
			return err
		}
		if err := f.Write(buffer, v); err != nil {
			return err
		}
	}
	return nil
}

func (f *Fory) Write(buffer *ByteBuffer, v interface{}) (err error) {
	// fast path for common type
	switch v := v.(type) {
	case nil:
		buffer.WriteInt8(NullFlag)
	case bool:
		f.WriteBool(buffer, v)
	case float64:
		f.WriteFloat64(buffer, v)
	case float32:
		f.WriteFloat32(buffer, v)
	case byte: // uint8
		f.WriteByte_(buffer, v)
	default:
		err = f.WriteReferencable_(buffer, reflect.ValueOf(v))
	}
	return
}

func (f *Fory) WriteByte_(buffer *ByteBuffer, v interface{}) {
	buffer.WriteInt8(NotNullValueFlag)
	buffer.WriteInt8(UINT8)
	buffer.WriteByte_(v.(byte))
}

func (f *Fory) WriteInt16(buffer *ByteBuffer, v interface{}) {
	buffer.WriteInt8(NotNullValueFlag)
	buffer.WriteInt8(INT32)
	buffer.WriteInt32(v.(int32))
}

func (f *Fory) WriteBool(buffer *ByteBuffer, v interface{}) {
	buffer.WriteInt8(NotNullValueFlag)
	buffer.WriteInt8(BOOL)
	buffer.WriteBool(v.(bool))
}

func (f *Fory) WriteInt32(buffer *ByteBuffer, v interface{}) {
	buffer.WriteInt8(NotNullValueFlag)
	buffer.WriteInt8(INT32)
	buffer.WriteInt32(v.(int32))
}

func (f *Fory) WriteInt64(buffer *ByteBuffer, v interface{}) {
	buffer.WriteInt8(NotNullValueFlag)
	buffer.WriteInt8(INT64)
	buffer.WriteInt64(v.(int64))
}

func (f *Fory) WriteFloat32(buffer *ByteBuffer, v interface{}) {
	buffer.WriteInt8(NotNullValueFlag)
	buffer.WriteInt8(FLOAT)
	buffer.WriteFloat32(v.(float32))
}

func (f *Fory) WriteFloat64(buffer *ByteBuffer, v interface{}) {
	buffer.WriteInt8(NotNullValueFlag)
	buffer.WriteInt8(DOUBLE)
	buffer.WriteFloat64(v.(float64))
}

func (f *Fory) writeLength(buffer *ByteBuffer, value int) error {
	if value > MaxInt32 || value < MinInt32 {
		return fmt.Errorf("value %d exceed the int32 range", value)
	}
	buffer.WriteVarInt32(int32(value))
	return nil
}

func (f *Fory) readLength(buffer *ByteBuffer) int {
	return int(buffer.ReadVarInt32())
}

func (f *Fory) WriteReferencable_(buffer *ByteBuffer, value reflect.Value) error {
	metaOffset := buffer.writerIndex
	if f.compatible {
		buffer.WriteInt32(-1)
	}
	err := f.WriteReferencable(buffer, value)
	if err != nil {
		return err
	}
	if f.compatible && f.metaContext != nil && len(f.metaContext.writingTypeDefs) > 0 {
		buffer.PutInt32(metaOffset, int32(buffer.writerIndex-metaOffset-4))
		f.typeResolver.writeTypeDefs(buffer)
	}
	return nil
}

func (f *Fory) WriteReferencable(buffer *ByteBuffer, value reflect.Value) error {
	return f.writeReferencableBySerializer(buffer, value, nil)
}

func (f *Fory) writeReferencableBySerializer(buffer *ByteBuffer, value reflect.Value, serializer Serializer) error {
	if refWritten, err := f.refResolver.WriteRefOrNull(buffer, value); err == nil && !refWritten {
		// check ptr
		if value.Kind() == reflect.Ptr {
			switch value.Elem().Kind() {
			case reflect.Ptr, reflect.Map, reflect.Slice, reflect.Interface:
				return fmt.Errorf("pointer to reference type %s is not supported", value.Type())
			}
		}
		return f.writeValue(buffer, value, serializer)
	} else {
		return err
	}
}

func (f *Fory) writeNonReferencableBySerializer(
	buffer *ByteBuffer, value reflect.Value, serializer Serializer) error {
	buffer.WriteInt8(NotNullValueFlag)
	return f.writeValue(buffer, value, serializer)
}

func (f *Fory) writeValue(buffer *ByteBuffer, value reflect.Value, serializer Serializer) (err error) {
	// Handle interface values by getting their concrete element
	if value.Kind() == reflect.Interface {
		value = value.Elem()
	}

	// For array types, pre-convert the value
	// so the corresponding slice serializer can be reused
	if value.Kind() == reflect.Array {
		length := value.Len()
		sliceType := reflect.SliceOf(value.Type().Elem())
		slice := reflect.MakeSlice(sliceType, length, length)
		reflect.Copy(slice, value)
		value = slice
	}

	if serializer != nil {
		return serializer.Write(f, buffer, value)
	}

	// Get type information for the value
	typeInfo, err := f.typeResolver.getTypeInfo(value, true)
	if err != nil {
		return fmt.Errorf("cannot get typeinfo for value %v: %v", value, err)
	}
	err = f.typeResolver.writeTypeInfo(buffer, typeInfo)
	if err != nil {
		return fmt.Errorf("cannot write typeinfo for value %v: %v", value, err)
	}
	serializer = typeInfo.Serializer
	// Serialize the actual value using the serializer
	return serializer.Write(f, buffer, value)
}

func (f *Fory) WriteBufferObject(buffer *ByteBuffer, bufferObject BufferObject) error {
	if f.bufferCallback == nil || f.bufferCallback(bufferObject) {
		buffer.WriteBool(true)
		size := bufferObject.TotalBytes()
		// writer length
		buffer.WriteLength(size)
		writerIndex := buffer.writerIndex
		buffer.grow(size)
		bufferObject.WriteTo(buffer.Slice(writerIndex, size))
		buffer.writerIndex += size
		if size > MaxInt32 {
			return fmt.Errorf("length %d exceed max int32", size)
		}
	} else {
		buffer.WriteBool(false)
	}
	return nil
}

func (f *Fory) Unmarshal(data []byte, v interface{}) error {
	return f.Deserialize(&ByteBuffer{data: data}, v, nil)
}

func (f *Fory) Deserialize(buf *ByteBuffer, v interface{}, buffers []*ByteBuffer) error {
	defer f.resetRead()
	if f.language == XLANG {
		magicNumber := buf.ReadInt16()
		if magicNumber != MAGIC_NUMBER {
			return fmt.Errorf(
				"the fory xlang serialization must start with magic number 0x%x. "+
					"Please check whether the serialization is based on the xlang protocol and the data didn't corrupt",
				MAGIC_NUMBER)
		}
	} else {
		return fmt.Errorf("%d language is not supported", f.language)
	}
	var bitmap = buf.ReadByte_()
	if bitmap&NilFlag != NilFlag {
		return nil
	}
	isLittleEndian := bitmap&LittleEndianFlag == LittleEndianFlag
	if !isLittleEndian {
		return fmt.Errorf("big endian is not supported for now, please ensure peer machine is little endian")
	}
	isXLangFlag := bitmap&XLangFlag == XLangFlag
	if isXLangFlag {
		f.peerLanguage = buf.ReadByte_()
	} else {
		f.peerLanguage = GO
	}
	isCallBackFlag := bitmap&CallBackFlag == CallBackFlag
	if isCallBackFlag {
		if buffers == nil {
			return fmt.Errorf("buffers shouldn't be null when the serialized stream is " +
				"produced with buffer_callback not null")
		}
		f.buffers = buffers
	} else {
		if buffers != nil {
			return fmt.Errorf("buffers should be null when the serialized stream is " +
				"produced with buffer_callback null")
		}
	}

	if f.compatible {
		typeDefOffset := buf.ReadInt32()
		if typeDefOffset >= 0 {
			save := buf.readerIndex
			buf.SetReaderIndex(save + int(typeDefOffset))
			if err := f.typeResolver.readTypeDefs(buf); err != nil {
				return fmt.Errorf("failed to read typeDefs: %w", err)
			}
			buf.SetReaderIndex(save)
		}
	}

	if isXLangFlag {
		return f.ReadReferencable(buf, reflect.ValueOf(v).Elem())
	} else {
		return fmt.Errorf("native serialization for golang is not supported currently")
	}
}

func (f *Fory) ReadReferencable(buffer *ByteBuffer, value reflect.Value) error {
	return f.readReferencableBySerializer(buffer, value, nil)
}

func (f *Fory) readReferencableBySerializer(buf *ByteBuffer, value reflect.Value, serializer Serializer) (err error) {
	// dynamic-with-refroute or unknown serializer
	if serializer == nil || serializer.NeedWriteRef() {
		refId, err := f.refResolver.TryPreserveRefId(buf)
		if err != nil {
			return fmt.Errorf("failed to preserve refID: %w", err)
		}
		// first read
		if refId >= int32(NotNullValueFlag) {
			// deserialize non-ref (may read typeinfo or use provided serializer)
			if err := f.readData(buf, value, serializer); err != nil {
				return fmt.Errorf("failed to read data: %w", err)
			}
			// record in resolver
			f.refResolver.SetReadObject(refId, value)
			return nil
		}
		// back-reference or null
		if refId == int32(NullFlag) {
			value.Set(reflect.Zero(value.Type()))
			return nil
		}
		prev := f.refResolver.GetReadObject(refId)
		value.Set(prev)
		return nil
	}

	// static path: no references
	headFlag := buf.ReadInt8()
	if headFlag == NullFlag {
		value.Set(reflect.Zero(value.Type()))
		return nil
	}
	// directly read without altering serializer
	return serializer.Read(f, buf, value.Type(), value)
}

func (f *Fory) readData(buffer *ByteBuffer, value reflect.Value, serializer Serializer) (err error) {
	if serializer == nil {
		typeInfo, err := f.typeResolver.readTypeInfo(buffer)
		if err != nil {
			return fmt.Errorf("read typeinfo failed: %w", err)
		}
		serializer = typeInfo.Serializer
		var concrete reflect.Value
		var type_ reflect.Type
		/*
		   Added logic to distinguish between:
		   1. Deserialization into a specified interface type,
		      which matches the behavior in the original tests.
		   2. Deserialization into a user-defined concrete type.
		*/
		switch {
		case value.Kind() == reflect.Interface,
			!value.CanSet():
			concrete = reflect.New(typeInfo.Type).Elem()
			type_ = typeInfo.Type
		default:
			concrete = value
			type_ = concrete.Type()
		}
		if err := serializer.Read(f, buffer, type_, concrete); err != nil {
			return err
		}
		value.Set(concrete)
		return nil
	}
	return serializer.Read(f, buffer, value.Type(), value)
}

func (f *Fory) ReadBufferObject(buffer *ByteBuffer) (*ByteBuffer, error) {
	isInBand := buffer.ReadBool()
	// TODO(chaokunyang) We need a way to wrap out-of-band buffer into byte slice without copy.
	// See more at `https://github.com/golang/go/wiki/cgo#turning-c-arrays-into-go-slices`
	if isInBand {
		size := buffer.ReadLength()
		buf := buffer.Slice(buffer.readerIndex, size)
		buffer.readerIndex += size
		return buf, nil
	} else {
		if f.buffers == nil {
			return nil, fmt.Errorf("buffers shouldn't be nil when met a out-of-band buffer")
		}
		buf := f.buffers[0]
		f.buffers = f.buffers[1:]
		return buf, nil
	}
}

func (f *Fory) Reset() {
	f.resetWrite()
	f.resetRead()
}

func (f *Fory) resetWrite() {
	f.typeResolver.resetWrite()
	f.refResolver.resetWrite()
	if f.metaContext != nil {
		if f.metaContext.IsScopedMetaShareEnabled() {
			f.metaContext.resetWrite()
		} else {
			f.metaContext = nil
		}
	}
}

func (f *Fory) resetRead() {
	f.typeResolver.resetRead()
	f.refResolver.resetRead()
	if f.metaContext != nil {
		if f.metaContext.IsScopedMetaShareEnabled() {
			f.metaContext.resetRead()
		} else {
			f.metaContext = nil
		}
	}
}

// methods for configure fory.

func (f *Fory) SetLanguage(language Language) {
	f.language = language
}

func (f *Fory) SetRefTracking(refTracking bool) {
	f.refTracking = refTracking
}
