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
	"unicode/utf16"
)

// Encoding type constants
const (
	encodingLatin1  = iota // Latin1/ISO-8859-1 encoding
	encodingUTF16LE        // UTF-16 Little Endian encoding
	encodingUTF8           // UTF-8 encoding (default)
)

// writeString writes a string to buffer using xlang encoding
// Optimized to combine header and data writes with single grow() call
func writeString(buf *ByteBuffer, value string) {
	data := unsafeGetBytes(value)
	dataLen := len(data)
	header := (uint64(dataLen) << 2) | encodingUTF8

	// Reserve space for header (max 5 bytes) + data in one call
	buf.Reserve(5 + dataLen)
	// Write header inline
	buf.WriteVaruint36Small(header)
	// Write data inline without grow check
	if dataLen > 0 {
		copy(buf.data[buf.writerIndex:], data)
		buf.writerIndex += dataLen
	}
}

// readString reads a string from buffer using xlang encoding
func readString(buf *ByteBuffer, err *Error, maxBytes int) string {
	header := buf.ReadVaruint36Small(err)
	size := header >> 2       // Extract byte count
	encoding := header & 0b11 // Extract encoding type

	if maxBytes > 0 && int(size) > maxBytes {
        err.SetError(DeserializationErrorf(
            "fory: string byte length %d exceeds limit %d", int(size), maxBytes))
        return ""
    }

	switch encoding {
	case encodingLatin1:
		return readLatin1(buf, int(size), err)
	case encodingUTF16LE:
		// For UTF16LE, size is byte count, need to convert to char count
		return readUTF16LE(buf, int(size), err)
	case encodingUTF8:
		return readUTF8(buf, int(size), err)
	default:
		err.SetError(fmt.Errorf("invalid string encoding: %d", encoding))
		return ""
	}
}

// Specific encoding read methods
func readLatin1(buf *ByteBuffer, size int, err *Error) string {
	data := buf.ReadBinary(size, err)
	// Latin1 bytes need to be converted to UTF-8
	// Each Latin1 byte is a single Unicode code point (0-255)
	runes := make([]rune, size)
	for i, b := range data {
		runes[i] = rune(b)
	}
	return string(runes)
}

func readUTF16LE(buf *ByteBuffer, byteCount int, err *Error) string {
	if byteCount&1 != 0 {
		err.SetError(InvalidUTF16StringError(byteCount))
		return ""
	}

	data := buf.ReadBinary(byteCount, err)
	if err.HasError() {
		return ""
	}

	// Reconstruct UTF-16 code units
	charCount := byteCount / 2
	u16s := make([]uint16, charCount)
	for i := 0; i < byteCount; i += 2 {
		u16s[i/2] = uint16(data[i]) | uint16(data[i+1])<<8
	}

	return string(utf16.Decode(u16s))
}

func readUTF8(buf *ByteBuffer, size int, err *Error) string {
	data := buf.ReadBinary(size, err)
	return string(data) // Direct UTF-8 conversion
}

// ============================================================================
// String Serializers - implement unified Serializer interface
// ============================================================================

// stringSerializer handles string type
type stringSerializer struct{}

var globalStringSerializer = stringSerializer{}

func (s stringSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	writeString(ctx.buffer, value.String())
}

func (s stringSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	if refMode != RefModeNone {
		// String is non-primitive, needs ref flag
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(STRING))
	}
	s.WriteData(ctx, value)
}

func (s stringSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	str := readString(ctx.buffer, err, ctx.maxStringBytes)
	if ctx.HasError() {
		return
	}
	value.SetString(str)
}

func (s stringSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	err := ctx.Err()
	if refMode != RefModeNone {
		// String is non-primitive, needs ref flag
		refFlag := ctx.buffer.ReadInt8(err)
		if refFlag == NullFlag {
			value.SetString("")
			return
		}
	}
	if readType {
		_ = ctx.buffer.ReadUint8(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s stringSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// ptrToStringSerializer serializes a pointer to string
type ptrToStringSerializer struct{}

func (s ptrToStringSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	if refMode != RefModeNone {
		if value.IsNil() {
			ctx.buffer.WriteInt8(NullFlag)
			return
		}
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(STRING))
	}
	s.WriteData(ctx, value)
}

func (s ptrToStringSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	str := value.Interface().(*string)
	writeString(ctx.buffer, *str)
}

func (s ptrToStringSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	err := ctx.Err()
	if refMode != RefModeNone {
		if ctx.buffer.ReadInt8(err) == NullFlag {
			return
		}
	}
	if readType {
		_ = ctx.buffer.ReadUint8(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s ptrToStringSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	str := readString(ctx.buffer, err, ctx.maxStringBytes)
	if ctx.HasError() {
		return
	}
	ptr := new(string)
	*ptr = str
	value.Set(reflect.ValueOf(ptr))
}

func (s ptrToStringSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}
