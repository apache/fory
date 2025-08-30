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
	"unicode/utf16"
)

// Encoding type constants
const (
	encodingLatin1  = iota // Latin1/ISO-8859-1 encoding
	encodingUTF16LE        // UTF-16 Little Endian encoding
	encodingUTF8           // UTF-8 encoding (default)
)

// writeString implements string serialization with automatic encoding detection
func writeString(buf *ByteBuffer, value string) error {
	// Check if string can be encoded as Latin1
	if isLatin1(value) {
		return writeLatin1(buf, value)
	}

	// Check if UTF-16LE encoding is more efficient
	if utf16Bytes, ok := tryUTF16LE(value); ok {
		return writeUTF16LE(buf, utf16Bytes)
	}

	// Default to UTF-8 encoding
	return writeUTF8(buf, value)
}

// readString implements string deserialization with encoding parsing
func readString(buf *ByteBuffer) string {
	header := buf.ReadVarUint64()
	size := header >> 2       // Extract string length (in characters)
	encoding := header & 0b11 // Extract encoding type

	switch encoding {
	case encodingLatin1:
		return readLatin1(buf, int(size))
	case encodingUTF16LE:
		return readUTF16LE(buf, int(size))
	case encodingUTF8:
		return readUTF8(buf, int(size))
	default:
		panic(fmt.Sprintf("invalid string encoding: %d", encoding))
	}
}

// Encoding detection helper functions
func isLatin1(s string) bool {
	// Check if all runes fit within Latin1 range (0-255)
	for _, r := range s {
		if r > 0xFF {
			return false
		}
	}
	return true
}

func tryUTF16LE(s string) ([]byte, bool) {
	runes := []rune(s)
	utf16Runes := utf16.Encode(runes)

	// Check for surrogate pairs (indicates complex Unicode)
	hasSurrogate := false
	for _, r := range utf16Runes {
		if r >= 0xD800 && r <= 0xDFFF {
			hasSurrogate = true
			break
		}
	}

	if hasSurrogate {
		return nil, false
	}

	// Convert to Little Endian byte order
	buf := make([]byte, 2*len(utf16Runes))
	for i, r := range utf16Runes {
		buf[2*i] = byte(r)        // Low byte
		buf[2*i+1] = byte(r >> 8) // High byte
	}
	return buf, true
}

// Specific encoding write methods
func writeLatin1(buf *ByteBuffer, s string) error {
	length := len(s)
	header := (uint64(length) << 2) | encodingLatin1 // Pack length and encoding

	buf.WriteVarUint64(header)
	buf.WriteBinary(unsafeGetBytes(s)) // Directly use underlying bytes (Latin1 chars are compatible with UTF-8 in Go)
	return nil
}

func writeUTF16LE(buf *ByteBuffer, data []byte) error {
	length := len(data) / 2 // Character count (2 bytes per char)
	header := (uint64(length) << 2) | encodingUTF16LE

	buf.WriteVarUint64(header)
	buf.WriteBinary(data)
	return nil
}

func writeUTF8(buf *ByteBuffer, s string) error {
	data := unsafeGetBytes(s)
	header := (uint64(len(data)) << 2) | encodingUTF8

	buf.WriteVarUint64(header)
	buf.WriteBinary(data)
	return nil
}

// Specific encoding read methods
func readLatin1(buf *ByteBuffer, size int) string {
	data := buf.ReadBinary(size)
	return string(data) // Go automatically handles Latin1 to UTF-8 conversion
}

func readUTF16LE(buf *ByteBuffer, charCount int) string {
	byteCount := charCount * 2
	data := buf.ReadBinary(byteCount)

	// Reconstruct UTF-16 code units
	u16s := make([]uint16, charCount)
	for i := 0; i < byteCount; i += 2 {
		u16s[i/2] = uint16(data[i]) | uint16(data[i+1])<<8
	}

	return string(utf16.Decode(u16s))
}

func readUTF8(buf *ByteBuffer, size int) string {
	data := buf.ReadBinary(size)
	return string(data) // Direct UTF-8 conversion
}

// WriteString provides public API for zero-reflection string serialization
// This method is specifically designed for code generation to avoid reflection overhead
func WriteString(buf *ByteBuffer, value string) error {
	return writeString(buf, value)
}

// ReadString provides public API for zero-reflection string deserialization
// This method is specifically designed for code generation to avoid reflection overhead
func ReadString(buf *ByteBuffer) string {
	return readString(buf)
}
