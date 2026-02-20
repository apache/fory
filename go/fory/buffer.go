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
	"io"
	"math"
	"unsafe"
)

type ByteBuffer struct {
	data        []byte // Most accessed field first for cache locality
	writerIndex int
	readerIndex int
	reader		io.Reader
	minCap		int
}

func NewByteBuffer(data []byte) *ByteBuffer {
	return &ByteBuffer{data: data}
}

// grow ensures there's space for n more bytes. Hot path is inlined.
//
//go:inline
func (b *ByteBuffer) grow(n int) {
	if b.writerIndex+n <= len(b.data) {
		return // Fast path - single comparison, easily inlined
	}
	b.growSlow(n)
}

// growSlow handles the cold path when buffer expansion is needed.
//
//go:noinline
func (b *ByteBuffer) growSlow(n int) {
	needed := b.writerIndex + n
	if needed <= cap(b.data) {
		b.data = b.data[:cap(b.data)]
	} else {
		newCap := 2 * needed
		newBuf := make([]byte, newCap)
		copy(newBuf, b.data[:b.writerIndex])
		b.data = newBuf
	}
}

//go:inline
func (b *ByteBuffer) WriteBool(value bool) {
	b.grow(1)
	// Branchless: directly convert bool to byte via unsafe
	b.data[b.writerIndex] = *(*byte)(unsafe.Pointer(&value))
	b.writerIndex++
}

//go:inline
func (b *ByteBuffer) WriteByte(value byte) error {
	b.grow(1)
	b.data[b.writerIndex] = value
	b.writerIndex++
	return nil
}

//go:inline
func (b *ByteBuffer) WriteByte_(value byte) {
	b.grow(1)
	b.data[b.writerIndex] = value
	b.writerIndex++
}

//go:inline
func (b *ByteBuffer) WriteInt8(value int8) {
	b.grow(1)
	b.data[b.writerIndex] = byte(value)
	b.writerIndex++
}

//go:inline
func (b *ByteBuffer) WriteUint8(value uint8) {
	b.grow(1)
	b.data[b.writerIndex] = value
	b.writerIndex++
}

//go:inline
func (b *ByteBuffer) WriteUint16(value uint16) {
	b.grow(2)
	binary.LittleEndian.PutUint16(b.data[b.writerIndex:], value)
	b.writerIndex += 2
}

//go:inline
func (b *ByteBuffer) WriteInt16(value int16) {
	b.grow(2)
	binary.LittleEndian.PutUint16(b.data[b.writerIndex:], uint16(value))
	b.writerIndex += 2
}

//go:inline
func (b *ByteBuffer) WriteUint32(value uint32) {
	b.grow(4)
	binary.LittleEndian.PutUint32(b.data[b.writerIndex:], value)
	b.writerIndex += 4
}

//go:inline
func (b *ByteBuffer) WriteInt32(value int32) {
	b.grow(4)
	binary.LittleEndian.PutUint32(b.data[b.writerIndex:], uint32(value))
	b.writerIndex += 4
}

func (b *ByteBuffer) WriteLength(value int) {
	b.grow(4)
	if value >= MaxInt32 {
		panic(fmt.Errorf("too long: %d", value))
	}
	b.WriteVarUint32(uint32(value))
}

func (b *ByteBuffer) ReadLength(err *Error) int {
	return int(b.ReadVarUint32(err))
}

//go:inline
func (b *ByteBuffer) WriteUint64(value uint64) {
	b.grow(8)
	binary.LittleEndian.PutUint64(b.data[b.writerIndex:], value)
	b.writerIndex += 8
}

//go:inline
func (b *ByteBuffer) WriteInt64(value int64) {
	b.grow(8)
	binary.LittleEndian.PutUint64(b.data[b.writerIndex:], uint64(value))
	b.writerIndex += 8
}

//go:inline
func (b *ByteBuffer) WriteFloat32(value float32) {
	b.grow(4)
	binary.LittleEndian.PutUint32(b.data[b.writerIndex:], Float32bits(value))
	b.writerIndex += 4
}

//go:inline
func (b *ByteBuffer) WriteFloat64(value float64) {
	b.grow(8)
	binary.LittleEndian.PutUint64(b.data[b.writerIndex:], Float64bits(value))
	b.writerIndex += 8
}

func (b *ByteBuffer) Write(p []byte) (n int, err error) {
	b.grow(len(p))
	l := copy(b.data[b.writerIndex:], p)
	b.writerIndex += len(p)
	return l, nil
}

func (b *ByteBuffer) WriteBinary(p []byte) {
	b.grow(len(p))
	l := copy(b.data[b.writerIndex:], p)
	if l != len(p) {
		panic(fmt.Errorf("should write %d bytes, but written %d bytes", len(p), l))
	}
	b.writerIndex += len(p)
}

// ReadBool reads a bool and sets error on bounds violation
//
//go:inline
func (b *ByteBuffer) ReadBool(err *Error) bool {
	if b.readerIndex+1 > len(b.data) {
		*err = BufferOutOfBoundError(b.readerIndex, 1, len(b.data))
		return false
	}
	v := b.data[b.readerIndex]
	b.readerIndex++
	return v != 0
}

// ReadByte reads a byte and sets error on bounds violation
//
//go:inline
func (b *ByteBuffer) ReadByte(err *Error) byte {
	if b.readerIndex+1 > len(b.data) {
		*err = BufferOutOfBoundError(b.readerIndex, 1, len(b.data))
		return 0
	}
	v := b.data[b.readerIndex]
	b.readerIndex++
	return v
}

// ReadInt8 reads an int8 and sets error on bounds violation
//
//go:inline
func (b *ByteBuffer) ReadInt8(err *Error) int8 {
	if b.readerIndex+1 > len(b.data) {
		*err = BufferOutOfBoundError(b.readerIndex, 1, len(b.data))
		return 0
	}
	v := int8(b.data[b.readerIndex])
	b.readerIndex++
	return v
}

// ReadInt16 reads an int16 and sets error on bounds violation
//
//go:inline
func (b *ByteBuffer) ReadInt16(err *Error) int16 {
	if b.readerIndex+2 > len(b.data) {
		*err = BufferOutOfBoundError(b.readerIndex, 2, len(b.data))
		return 0
	}
	v := int16(binary.LittleEndian.Uint16(b.data[b.readerIndex:]))
	b.readerIndex += 2
	return v
}

// ReadUint16 reads a uint16 and sets error on bounds violation
//
//go:inline
func (b *ByteBuffer) ReadUint16(err *Error) uint16 {
	if b.readerIndex+2 > len(b.data) {
		*err = BufferOutOfBoundError(b.readerIndex, 2, len(b.data))
		return 0
	}
	v := binary.LittleEndian.Uint16(b.data[b.readerIndex:])
	b.readerIndex += 2
	return v
}

// ReadUint32 reads a uint32 and sets error on bounds violation
//
//go:inline
func (b *ByteBuffer) ReadUint32(err *Error) uint32 {
	if b.readerIndex+4 > len(b.data) {
		*err = BufferOutOfBoundError(b.readerIndex, 4, len(b.data))
		return 0
	}
	i := binary.LittleEndian.Uint32(b.data[b.readerIndex:])
	b.readerIndex += 4
	return i
}

// ReadUint64 reads a uint64 and sets error on bounds violation
//
//go:inline
func (b *ByteBuffer) ReadUint64(err *Error) uint64 {
	if b.readerIndex+8 > len(b.data) {
		*err = BufferOutOfBoundError(b.readerIndex, 8, len(b.data))
		return 0
	}
	i := binary.LittleEndian.Uint64(b.data[b.readerIndex:])
	b.readerIndex += 8
	return i
}

// ReadInt32 reads an int32 and sets error on bounds violation
//
//go:inline
func (b *ByteBuffer) ReadInt32(err *Error) int32 {
	return int32(b.ReadUint32(err))
}

// ReadInt64 reads an int64 and sets error on bounds violation
//
//go:inline
func (b *ByteBuffer) ReadInt64(err *Error) int64 {
	return int64(b.ReadUint64(err))
}

// ReadFloat32 reads a float32 and sets error on bounds violation
//
//go:inline
func (b *ByteBuffer) ReadFloat32(err *Error) float32 {
	return Float32frombits(b.ReadUint32(err))
}

// ReadFloat64 reads a float64 and sets error on bounds violation
//
//go:inline
func (b *ByteBuffer) ReadFloat64(err *Error) float64 {
	return Float64frombits(b.ReadUint64(err))
}

func (b *ByteBuffer) Read(p []byte) (n int, err error) {
	copied := copy(p, b.data[b.readerIndex:])
	b.readerIndex += copied
	return copied, nil
}

// ReadBinary reads n bytes and sets error on bounds violation
func (b *ByteBuffer) ReadBinary(length int, err *Error) []byte {
	if b.readerIndex+length > len(b.data) {
		*err = BufferOutOfBoundError(b.readerIndex, length, len(b.data))
		return nil
	}
	v := b.data[b.readerIndex : b.readerIndex+length]
	b.readerIndex += length
	return v
}

//go:inline
func (b *ByteBuffer) GetData() []byte {
	return b.data
}

//go:inline
func (b *ByteBuffer) GetByteSlice(start, end int) []byte {
	return b.data[start:end]
}

func (b *ByteBuffer) Slice(start, length int) *ByteBuffer {
	return NewByteBuffer(b.data[start : start+length])
}

//go:inline
func (b *ByteBuffer) WriterIndex() int {
	return b.writerIndex
}

// Bytes returns all written bytes from the buffer (from 0 to writerIndex).
//
//go:inline
func (b *ByteBuffer) Bytes() []byte {
	return b.GetByteSlice(0, b.writerIndex)
}

//go:inline
func (b *ByteBuffer) SetWriterIndex(index int) {
	b.writerIndex = index
}

//go:inline
func (b *ByteBuffer) ReaderIndex() int {
	return b.readerIndex
}

//go:inline
func (b *ByteBuffer) SetReaderIndex(index int) {
	b.readerIndex = index
}

func (b *ByteBuffer) Reset() {
	b.readerIndex = 0
	b.writerIndex = 0
	// Keep the underlying buffer if it's reasonable sized to reduce allocations
	// Only nil it out if we want to release memory
	if cap(b.data) > 64*1024 {
		b.data = nil
	}
	// Note: We keep b.data as-is (with its current length) to avoid issues
	// with grow() needing to expand the slice on first write
}

// Reserve ensures buffer has at least n bytes available for writing from current position.
// Call this before multiple unsafe writes to avoid repeated grow() calls.
func (b *ByteBuffer) Reserve(n int) {
	needed := b.writerIndex + n
	if needed <= len(b.data) {
		return // Already have enough space
	}
	// Need to expand - calculate new size
	if needed <= cap(b.data) {
		b.data = b.data[:cap(b.data)]
	} else {
		newCap := 2 * needed
		newBuf := make([]byte, newCap, newCap)
		copy(newBuf, b.data)
		b.data = newBuf
	}
}

// UnsafeWriteVarint32 writes a varint32 without grow check.
// Caller must have called Reserve(5) beforehand.
//
//go:inline
func (b *ByteBuffer) UnsafeWriteVarint32(value int32) int8 {
	u := uint32((value << 1) ^ (value >> 31))
	return b.UnsafeWriteVarUint32(u)
}

// UnsafeWriteVarUint32 writes a VarUint32 without grow check.
// Caller must have called Reserve(8) beforehand (8 for bulk uint64 write).
func (b *ByteBuffer) UnsafeWriteVarUint32(value uint32) int8 {
	if value>>7 == 0 {
		b.data[b.writerIndex] = byte(value)
		b.writerIndex++
		return 1
	}
	if value>>14 == 0 {
		// Bulk write 2 bytes
		encoded := uint16((value&0x7F)|0x80) | uint16(value>>7)<<8
		if isLittleEndian {
			*(*uint16)(unsafe.Pointer(&b.data[b.writerIndex])) = encoded
		} else {
			b.data[b.writerIndex] = byte(encoded)
			b.data[b.writerIndex+1] = byte(encoded >> 8)
		}
		b.writerIndex += 2
		return 2
	}
	if value>>21 == 0 {
		// Bulk write 4 bytes (only first 3 are valid varint data)
		encoded := uint32((value&0x7F)|0x80) |
			uint32(((value>>7)&0x7F)|0x80)<<8 |
			uint32(value>>14)<<16
		if isLittleEndian {
			*(*uint32)(unsafe.Pointer(&b.data[b.writerIndex])) = encoded
		} else {
			b.data[b.writerIndex] = byte(encoded)
			b.data[b.writerIndex+1] = byte(encoded >> 8)
			b.data[b.writerIndex+2] = byte(encoded >> 16)
		}
		b.writerIndex += 3
		return 3
	}
	if value>>28 == 0 {
		// Bulk write 4 bytes
		encoded := uint32((value&0x7F)|0x80) |
			uint32(((value>>7)&0x7F)|0x80)<<8 |
			uint32(((value>>14)&0x7F)|0x80)<<16 |
			uint32(value>>21)<<24
		if isLittleEndian {
			*(*uint32)(unsafe.Pointer(&b.data[b.writerIndex])) = encoded
		} else {
			binary.LittleEndian.PutUint32(b.data[b.writerIndex:], encoded)
		}
		b.writerIndex += 4
		return 4
	}
	// Bulk write 8 bytes (only first 5 are valid varint data)
	encoded := uint64((value&0x7F)|0x80) |
		uint64(((value>>7)&0x7F)|0x80)<<8 |
		uint64(((value>>14)&0x7F)|0x80)<<16 |
		uint64(((value>>21)&0x7F)|0x80)<<24 |
		uint64(value>>28)<<32
	if isLittleEndian {
		*(*uint64)(unsafe.Pointer(&b.data[b.writerIndex])) = encoded
	} else {
		binary.LittleEndian.PutUint64(b.data[b.writerIndex:], encoded)
	}
	b.writerIndex += 5
	return 5
}

// UnsafeWriteByte writes a single byte without grow check.
//
//go:inline
func (b *ByteBuffer) UnsafeWriteByte(v byte) {
	b.data[b.writerIndex] = v
	b.writerIndex++
}

// UnsafeWriteInt16 writes an int16 without grow check.
//
//go:inline
func (b *ByteBuffer) UnsafeWriteInt16(v int16) {
	if isLittleEndian {
		*(*int16)(unsafe.Pointer(&b.data[b.writerIndex])) = v
	} else {
		binary.LittleEndian.PutUint16(b.data[b.writerIndex:], uint16(v))
	}
	b.writerIndex += 2
}

// UnsafeWriteInt32 writes an int32 without grow check.
//
//go:inline
func (b *ByteBuffer) UnsafeWriteInt32(v int32) {
	if isLittleEndian {
		*(*int32)(unsafe.Pointer(&b.data[b.writerIndex])) = v
	} else {
		binary.LittleEndian.PutUint32(b.data[b.writerIndex:], uint32(v))
	}
	b.writerIndex += 4
}

// UnsafeWriteInt64 writes an int64 without grow check.
//
//go:inline
func (b *ByteBuffer) UnsafeWriteInt64(v int64) {
	if isLittleEndian {
		*(*int64)(unsafe.Pointer(&b.data[b.writerIndex])) = v
	} else {
		binary.LittleEndian.PutUint64(b.data[b.writerIndex:], uint64(v))
	}
	b.writerIndex += 8
}

// UnsafeReadInt32 reads an int32 without bounds check.
//
//go:inline
func (b *ByteBuffer) UnsafeReadInt32() int32 {
	var v int32
	if isLittleEndian {
		v = *(*int32)(unsafe.Pointer(&b.data[b.readerIndex]))
	} else {
		v = int32(binary.LittleEndian.Uint32(b.data[b.readerIndex:]))
	}
	b.readerIndex += 4
	return v
}

// UnsafeReadInt64 reads an int64 without bounds check.
//
//go:inline
func (b *ByteBuffer) UnsafeReadInt64() int64 {
	var v int64
	if isLittleEndian {
		v = *(*int64)(unsafe.Pointer(&b.data[b.readerIndex]))
	} else {
		v = int64(binary.LittleEndian.Uint64(b.data[b.readerIndex:]))
	}
	b.readerIndex += 8
	return v
}

// UnsafeReadUint32 reads a uint32 without bounds check.
//
//go:inline
func (b *ByteBuffer) UnsafeReadUint32() uint32 {
	var v uint32
	if isLittleEndian {
		v = *(*uint32)(unsafe.Pointer(&b.data[b.readerIndex]))
	} else {
		v = binary.LittleEndian.Uint32(b.data[b.readerIndex:])
	}
	b.readerIndex += 4
	return v
}

// UnsafeReadUint64 reads a uint64 without bounds check.
//
//go:inline
func (b *ByteBuffer) UnsafeReadUint64() uint64 {
	var v uint64
	if isLittleEndian {
		v = *(*uint64)(unsafe.Pointer(&b.data[b.readerIndex]))
	} else {
		v = binary.LittleEndian.Uint64(b.data[b.readerIndex:])
	}
	b.readerIndex += 8
	return v
}

// UnsafeWriteFloat32 writes a float32 without grow check.
//
//go:inline
func (b *ByteBuffer) UnsafeWriteFloat32(v float32) {
	if isLittleEndian {
		*(*float32)(unsafe.Pointer(&b.data[b.writerIndex])) = v
	} else {
		binary.LittleEndian.PutUint32(b.data[b.writerIndex:], math.Float32bits(v))
	}
	b.writerIndex += 4
}

// UnsafeWriteFloat64 writes a float64 without grow check.
//
//go:inline
func (b *ByteBuffer) UnsafeWriteFloat64(v float64) {
	if isLittleEndian {
		*(*float64)(unsafe.Pointer(&b.data[b.writerIndex])) = v
	} else {
		binary.LittleEndian.PutUint64(b.data[b.writerIndex:], math.Float64bits(v))
	}
	b.writerIndex += 8
}

// UnsafeWriteBool writes a bool without grow check.
//
//go:inline
func (b *ByteBuffer) UnsafeWriteBool(v bool) {
	// Branchless: directly convert bool to byte via unsafe
	b.data[b.writerIndex] = *(*byte)(unsafe.Pointer(&v))
	b.writerIndex++
}

// UnsafePutVarInt32 writes a zigzag-encoded varint32 at the given offset without advancing writerIndex.
// Caller must have called Reserve() to ensure capacity.
// Returns the number of bytes written (1-5).
//
//go:inline
func (b *ByteBuffer) UnsafePutVarInt32(offset int, value int32) int {
	u := uint32((value << 1) ^ (value >> 31))
	return b.UnsafePutVarUint32(offset, u)
}

// UnsafePutVarUint32 writes an unsigned VarUint32 at the given offset without advancing writerIndex.
// Caller must have called Reserve(8) to ensure capacity (8 for bulk uint64 write).
// Returns the number of bytes written (1-5).
func (b *ByteBuffer) UnsafePutVarUint32(offset int, value uint32) int {
	if value>>7 == 0 {
		b.data[offset] = byte(value)
		return 1
	}
	if value>>14 == 0 {
		encoded := uint16((value&0x7F)|0x80) | uint16(value>>7)<<8
		if isLittleEndian {
			*(*uint16)(unsafe.Pointer(&b.data[offset])) = encoded
		} else {
			b.data[offset] = byte(encoded)
			b.data[offset+1] = byte(encoded >> 8)
		}
		return 2
	}
	if value>>21 == 0 {
		encoded := uint32((value&0x7F)|0x80) |
			uint32(((value>>7)&0x7F)|0x80)<<8 |
			uint32(value>>14)<<16
		if isLittleEndian {
			*(*uint32)(unsafe.Pointer(&b.data[offset])) = encoded
		} else {
			b.data[offset] = byte(encoded)
			b.data[offset+1] = byte(encoded >> 8)
			b.data[offset+2] = byte(encoded >> 16)
		}
		return 3
	}
	if value>>28 == 0 {
		encoded := uint32((value&0x7F)|0x80) |
			uint32(((value>>7)&0x7F)|0x80)<<8 |
			uint32(((value>>14)&0x7F)|0x80)<<16 |
			uint32(value>>21)<<24
		if isLittleEndian {
			*(*uint32)(unsafe.Pointer(&b.data[offset])) = encoded
		} else {
			binary.LittleEndian.PutUint32(b.data[offset:], encoded)
		}
		return 4
	}
	encoded := uint64((value&0x7F)|0x80) |
		uint64(((value>>7)&0x7F)|0x80)<<8 |
		uint64(((value>>14)&0x7F)|0x80)<<16 |
		uint64(((value>>21)&0x7F)|0x80)<<24 |
		uint64(value>>28)<<32
	if isLittleEndian {
		*(*uint64)(unsafe.Pointer(&b.data[offset])) = encoded
	} else {
		binary.LittleEndian.PutUint64(b.data[offset:], encoded)
	}
	return 5
}

// UnsafePutVarInt64 writes a zigzag-encoded varint64 at the given offset without advancing writerIndex.
// Caller must have called Reserve() to ensure capacity.
// Returns the number of bytes written (1-9).
//
//go:inline
func (b *ByteBuffer) UnsafePutVarInt64(offset int, value int64) int {
	u := uint64((value << 1) ^ (value >> 63))
	return b.UnsafePutVarUint64(offset, u)
}

// UnsafePutVarUint64 writes an unsigned VarUint64 at the given offset without advancing writerIndex.
// Caller must have called Reserve(16) to ensure capacity (for bulk writes).
// Returns the number of bytes written (1-9).
func (b *ByteBuffer) UnsafePutVarUint64(offset int, value uint64) int {
	if value>>7 == 0 {
		b.data[offset] = byte(value)
		return 1
	}
	if value>>14 == 0 {
		encoded := uint16((value&0x7F)|0x80) | uint16(value>>7)<<8
		if isLittleEndian {
			*(*uint16)(unsafe.Pointer(&b.data[offset])) = encoded
		} else {
			b.data[offset] = byte(encoded)
			b.data[offset+1] = byte(encoded >> 8)
		}
		return 2
	}
	if value>>21 == 0 {
		encoded := uint32((value&0x7F)|0x80) |
			uint32(((value>>7)&0x7F)|0x80)<<8 |
			uint32(value>>14)<<16
		if isLittleEndian {
			*(*uint32)(unsafe.Pointer(&b.data[offset])) = encoded
		} else {
			b.data[offset] = byte(encoded)
			b.data[offset+1] = byte(encoded >> 8)
			b.data[offset+2] = byte(encoded >> 16)
		}
		return 3
	}
	if value>>28 == 0 {
		encoded := uint32((value&0x7F)|0x80) |
			uint32(((value>>7)&0x7F)|0x80)<<8 |
			uint32(((value>>14)&0x7F)|0x80)<<16 |
			uint32(value>>21)<<24
		if isLittleEndian {
			*(*uint32)(unsafe.Pointer(&b.data[offset])) = encoded
		} else {
			binary.LittleEndian.PutUint32(b.data[offset:], encoded)
		}
		return 4
	}
	if value>>35 == 0 {
		encoded := uint64((value&0x7F)|0x80) |
			uint64(((value>>7)&0x7F)|0x80)<<8 |
			uint64(((value>>14)&0x7F)|0x80)<<16 |
			uint64(((value>>21)&0x7F)|0x80)<<24 |
			uint64(value>>28)<<32
		if isLittleEndian {
			*(*uint64)(unsafe.Pointer(&b.data[offset])) = encoded
		} else {
			binary.LittleEndian.PutUint64(b.data[offset:], encoded)
		}
		return 5
	}
	if value>>42 == 0 {
		encoded := uint64((value&0x7F)|0x80) |
			uint64(((value>>7)&0x7F)|0x80)<<8 |
			uint64(((value>>14)&0x7F)|0x80)<<16 |
			uint64(((value>>21)&0x7F)|0x80)<<24 |
			uint64(((value>>28)&0x7F)|0x80)<<32 |
			uint64(value>>35)<<40
		if isLittleEndian {
			*(*uint64)(unsafe.Pointer(&b.data[offset])) = encoded
		} else {
			binary.LittleEndian.PutUint64(b.data[offset:], encoded)
		}
		return 6
	}
	if value>>49 == 0 {
		encoded := uint64((value&0x7F)|0x80) |
			uint64(((value>>7)&0x7F)|0x80)<<8 |
			uint64(((value>>14)&0x7F)|0x80)<<16 |
			uint64(((value>>21)&0x7F)|0x80)<<24 |
			uint64(((value>>28)&0x7F)|0x80)<<32 |
			uint64(((value>>35)&0x7F)|0x80)<<40 |
			uint64(value>>42)<<48
		if isLittleEndian {
			*(*uint64)(unsafe.Pointer(&b.data[offset])) = encoded
		} else {
			binary.LittleEndian.PutUint64(b.data[offset:], encoded)
		}
		return 7
	}
	if value>>56 == 0 {
		encoded := uint64((value&0x7F)|0x80) |
			uint64(((value>>7)&0x7F)|0x80)<<8 |
			uint64(((value>>14)&0x7F)|0x80)<<16 |
			uint64(((value>>21)&0x7F)|0x80)<<24 |
			uint64(((value>>28)&0x7F)|0x80)<<32 |
			uint64(((value>>35)&0x7F)|0x80)<<40 |
			uint64(((value>>42)&0x7F)|0x80)<<48 |
			uint64(value>>49)<<56
		if isLittleEndian {
			*(*uint64)(unsafe.Pointer(&b.data[offset])) = encoded
		} else {
			binary.LittleEndian.PutUint64(b.data[offset:], encoded)
		}
		return 8
	}
	// 9 bytes needed
	encoded := uint64((value&0x7F)|0x80) |
		uint64(((value>>7)&0x7F)|0x80)<<8 |
		uint64(((value>>14)&0x7F)|0x80)<<16 |
		uint64(((value>>21)&0x7F)|0x80)<<24 |
		uint64(((value>>28)&0x7F)|0x80)<<32 |
		uint64(((value>>35)&0x7F)|0x80)<<40 |
		uint64(((value>>42)&0x7F)|0x80)<<48 |
		uint64(((value>>49)&0x7F)|0x80)<<56
	if isLittleEndian {
		*(*uint64)(unsafe.Pointer(&b.data[offset])) = encoded
	} else {
		binary.LittleEndian.PutUint64(b.data[offset:], encoded)
	}

	b.data[offset+8] = byte(value >> 56)
	return 9
}

//go:inline
func (b *ByteBuffer) PutInt32(index int, value int32) {
	b.grow(4)
	binary.LittleEndian.PutUint32(b.data[index:], uint32(value))
}

// WriteVarUint32 writes a 1-5 byte positive int (no zigzag encoding), returns the number of bytes written.
// Use this for lengths, type IDs, and other non-negative values.
//
//go:inline
func (b *ByteBuffer) WriteVarUint32(value uint32) int8 {
	b.grow(8) // 8 bytes for bulk uint64 write in worst case
	return b.UnsafeWriteVarUint32(value)
}

type BufferObject interface {
	TotalBytes() int
	WriteTo(buf *ByteBuffer)
	ToBuffer() *ByteBuffer
}

// WriteVarint64 writes the zig-zag encoded varint (compatible with Java's writeVarint64).
//
//go:inline
func (b *ByteBuffer) WriteVarint64(value int64) {
	u := uint64((value << 1) ^ (value >> 63))
	b.WriteVarUint64(u)
}

// WriteVarUint64 writes to unsigned varint (up to 9 bytes)
func (b *ByteBuffer) WriteVarUint64(value uint64) {
	b.grow(9)
	offset := b.writerIndex
	data := b.data[offset : offset+9]

	for i := 0; i < 8; i++ {
		if value < 0x80 {
			data[i] = byte(value)
			b.writerIndex += i + 1
			return
		}
		data[i] = byte(value&0x7F) | 0x80
		value >>= 7
	}
	data[8] = byte(value)
	b.writerIndex += 9
}

// WriteVaruint36Small writes a varint optimized for small values (up to 36 bits)
// Used for string headers: (length << 2) | encoding
func (b *ByteBuffer) WriteVaruint36Small(value uint64) {
	b.grow(5)
	offset := b.writerIndex
	data := b.data[offset:]

	if value < 0x80 {
		data[0] = byte(value)
		b.writerIndex += 1
	} else if value < 0x4000 {
		data[0] = byte(value&0x7F) | 0x80
		data[1] = byte(value >> 7)
		b.writerIndex += 2
	} else if value < 0x200000 {
		data[0] = byte(value&0x7F) | 0x80
		data[1] = byte((value>>7)&0x7F) | 0x80
		data[2] = byte(value >> 14)
		b.writerIndex += 3
	} else if value < 0x10000000 {
		data[0] = byte(value&0x7F) | 0x80
		data[1] = byte((value>>7)&0x7F) | 0x80
		data[2] = byte((value>>14)&0x7F) | 0x80
		data[3] = byte(value >> 21)
		b.writerIndex += 4
	} else {
		data[0] = byte(value&0x7F) | 0x80
		data[1] = byte((value>>7)&0x7F) | 0x80
		data[2] = byte((value>>14)&0x7F) | 0x80
		data[3] = byte((value>>21)&0x7F) | 0x80
		data[4] = byte(value >> 28)
		b.writerIndex += 5
	}
}

// ReadVaruint36Small reads a varint optimized for small values (up to 36 bits)
// Used for string headers: (length << 2) | encoding
//
//go:inline
func (b *ByteBuffer) ReadVaruint36Small(err *Error) uint64 {
	if b.remaining() >= 8 {
		return b.readVaruint36SmallFast()
	}
	return b.readVaruint36SmallSlow(err)
}

//go:inline
func (b *ByteBuffer) readVaruint36SmallFast() uint64 {
	// Single instruction load using unsafe pointer cast (little-endian only)
	// On big-endian systems, use binary.LittleEndian which the compiler optimizes
	var bulk uint64
	if isLittleEndian {
		bulk = *(*uint64)(unsafe.Pointer(&b.data[b.readerIndex]))
	} else {
		bulk = binary.LittleEndian.Uint64(b.data[b.readerIndex:])
	}

	result := bulk & 0x7F
	readLen := 1

	if (bulk & 0x80) != 0 {
		readLen = 2
		result |= (bulk >> 1) & 0x3F80
		if (bulk & 0x8000) != 0 {
			readLen = 3
			result |= (bulk >> 2) & 0x1FC000
			if (bulk & 0x800000) != 0 {
				readLen = 4
				result |= (bulk >> 3) & 0xFE00000
				if (bulk & 0x80000000) != 0 {
					readLen = 5
					result |= (bulk >> 4) & 0xFF0000000
				}
			}
		}
	}
	b.readerIndex += readLen
	return result
}

func (b *ByteBuffer) readVaruint36SmallSlow(err *Error) uint64 {
	var result uint64
	var shift uint

	for b.readerIndex < len(b.data) {
		byteVal := b.data[b.readerIndex]
		b.readerIndex++
		result |= uint64(byteVal&0x7F) << shift
		if (byteVal & 0x80) == 0 {
			return result
		}
		shift += 7
		if shift >= 36 {
			*err = DeserializationError("varuint36small overflow")
			return 0
		}
	}
	*err = BufferOutOfBoundError(b.readerIndex, 1, len(b.data))
	return 0
}

// ReadVarint64 reads the varint encoded with zig-zag (compatible with Java's readVarint64).
//
//go:inline
func (b *ByteBuffer) ReadVarint64(err *Error) int64 {
	u := b.ReadVarUint64(err)
	v := int64(u >> 1)
	if u&1 != 0 {
		v = ^v
	}
	return v
}

// WriteTaggedInt64 writes int64 using tagged encoding.
// If value is in [-1073741824, 1073741823], encode as 4 bytes: ((value as i32) << 1).
// Otherwise write as 9 bytes: 0b1 | little-endian 8 bytes i64.
func (b *ByteBuffer) WriteTaggedInt64(value int64) {
	const halfMinIntValue int64 = -1073741824 // INT32_MIN / 2
	const halfMaxIntValue int64 = 1073741823  // INT32_MAX / 2
	if value >= halfMinIntValue && value <= halfMaxIntValue {
		b.WriteInt32(int32(value) << 1)
	} else {
		b.grow(9)
		b.data[b.writerIndex] = 0b1
		if isLittleEndian {
			*(*int64)(unsafe.Pointer(&b.data[b.writerIndex+1])) = value
		} else {
			binary.LittleEndian.PutUint64(b.data[b.writerIndex+1:], uint64(value))
		}
		b.writerIndex += 9
	}
}

// ReadTaggedInt64 reads int64 using tagged encoding.
// If bit 0 is 0, return value >> 1 (arithmetic shift).
// Otherwise, skip flag byte and read 8 bytes as int64.
func (b *ByteBuffer) ReadTaggedInt64(err *Error) int64 {
	if b.readerIndex+4 > len(b.data) {
		*err = BufferOutOfBoundError(b.readerIndex, 4, len(b.data))
		return 0
	}
	var i int32
	if isLittleEndian {
		i = *(*int32)(unsafe.Pointer(&b.data[b.readerIndex]))
	} else {
		i = int32(binary.LittleEndian.Uint32(b.data[b.readerIndex:]))
	}
	if (i & 0b1) != 0b1 {
		b.readerIndex += 4
		return int64(i >> 1) // arithmetic right shift
	}
	if b.readerIndex+9 > len(b.data) {
		*err = BufferOutOfBoundError(b.readerIndex, 9, len(b.data))
		return 0
	}
	var value int64
	if isLittleEndian {
		value = *(*int64)(unsafe.Pointer(&b.data[b.readerIndex+1]))
	} else {
		value = int64(binary.LittleEndian.Uint64(b.data[b.readerIndex+1:]))
	}
	b.readerIndex += 9
	return value
}

// WriteTaggedUint64 writes uint64 using tagged encoding.
// If value is in [0, 0x7fffffff], encode as 4 bytes: ((value as u32) << 1).
// Otherwise write as 9 bytes: 0b1 | little-endian 8 bytes u64.
func (b *ByteBuffer) WriteTaggedUint64(value uint64) {
	const maxSmallValue uint64 = 0x7fffffff // INT32_MAX as u64
	if value <= maxSmallValue {
		b.WriteInt32(int32(value) << 1)
	} else {
		b.grow(9)
		b.data[b.writerIndex] = 0b1
		if isLittleEndian {
			*(*uint64)(unsafe.Pointer(&b.data[b.writerIndex+1])) = value
		} else {
			binary.LittleEndian.PutUint64(b.data[b.writerIndex+1:], value)
		}
		b.writerIndex += 9
	}
}

// ReadTaggedUint64 reads uint64 using tagged encoding.
// If bit 0 is 0, return value >> 1.
// Otherwise, skip flag byte and read 8 bytes as uint64.
func (b *ByteBuffer) ReadTaggedUint64(err *Error) uint64 {
	if b.readerIndex+4 > len(b.data) {
		*err = BufferOutOfBoundError(b.readerIndex, 4, len(b.data))
		return 0
	}
	var i uint32
	if isLittleEndian {
		i = *(*uint32)(unsafe.Pointer(&b.data[b.readerIndex]))
	} else {
		i = binary.LittleEndian.Uint32(b.data[b.readerIndex:])
	}
	if (i & 0b1) != 0b1 {
		b.readerIndex += 4
		return uint64(i >> 1)
	}
	if b.readerIndex+9 > len(b.data) {
		*err = BufferOutOfBoundError(b.readerIndex, 9, len(b.data))
		return 0
	}
	var value uint64
	if isLittleEndian {
		value = *(*uint64)(unsafe.Pointer(&b.data[b.readerIndex+1]))
	} else {
		value = binary.LittleEndian.Uint64(b.data[b.readerIndex+1:])
	}
	b.readerIndex += 9
	return value
}

// ReadVarUint64 reads unsigned varint
//
//go:inline
func (b *ByteBuffer) ReadVarUint64(err *Error) uint64 {
	if b.remaining() >= 9 {
		return b.readVarUint64Fast()
	}
	return b.readVarUint64Slow(err)
}

// Fast path (when the remaining bytes are sufficient)
//
//go:inline
func (b *ByteBuffer) readVarUint64Fast() uint64 {
	// Single instruction load using unsafe pointer cast (little-endian only)
	var bulk uint64
	if isLittleEndian {
		bulk = *(*uint64)(unsafe.Pointer(&b.data[b.readerIndex]))
	} else {
		bulk = binary.LittleEndian.Uint64(b.data[b.readerIndex:])
	}

	result := bulk & 0x7F
	readLength := 1

	if (bulk & 0x80) != 0 {
		result |= (bulk >> 1) & 0x3F80
		readLength = 2
		if (bulk & 0x8000) != 0 {
			result |= (bulk >> 2) & 0x1FC000
			readLength = 3
			if (bulk & 0x800000) != 0 {
				result |= (bulk >> 3) & 0xFE00000
				readLength = 4
				if (bulk & 0x80000000) != 0 {
					result |= (bulk >> 4) & 0x7F0000000
					readLength = 5
					if (bulk & 0x8000000000) != 0 {
						result |= (bulk >> 5) & 0x3F800000000
						readLength = 6
						if (bulk & 0x800000000000) != 0 {
							result |= (bulk >> 6) & 0x1FC0000000000
							readLength = 7
							if (bulk & 0x80000000000000) != 0 {
								result |= (bulk >> 7) & 0xFE000000000000
								readLength = 8
								if (bulk & 0x8000000000000000) != 0 {
									// Need 9th byte (full 8 bits)
									b9 := b.data[b.readerIndex+8]
									result |= uint64(b9) << 56
									readLength = 9
								}
							}
						}
					}
				}
			}
		}
	}
	b.readerIndex += readLength
	return result
}

// Slow path (read byte by byte)
func (b *ByteBuffer) readVarUint64Slow(err *Error) uint64 {
	var result uint64
	var shift uint
	for i := 0; i < 8; i++ {
		if b.readerIndex >= len(b.data) {
			*err = BufferOutOfBoundError(b.readerIndex, 1, len(b.data))
			return 0
		}
		byteVal := b.data[b.readerIndex]
		b.readerIndex++
		result |= (uint64(byteVal) & 0x7F) << shift
		if byteVal < 0x80 {
			return result
		}
		shift += 7
	}
	if b.readerIndex >= len(b.data) {
		*err = BufferOutOfBoundError(b.readerIndex, 1, len(b.data))
		return 0
	}
	byteVal := b.data[b.readerIndex]
	b.readerIndex++
	return result | (uint64(byteVal) << 56)
}

// Auxiliary function
//
//go:inline
func (b *ByteBuffer) remaining() int {
	return len(b.data) - b.readerIndex
}

// ReadUint8 reads a uint8 and sets error on bounds violation
//
//go:inline
func (b *ByteBuffer) ReadUint8(err *Error) uint8 {
	if b.readerIndex >= len(b.data) {
		*err = BufferOutOfBoundError(b.readerIndex, 1, len(b.data))
		return 0
	}
	v := b.data[b.readerIndex]
	b.readerIndex++
	return v
}

// WriteVarint32 writes a signed int32 using zigzag encoding (compatible with Java's writeVarint32).
//
//go:inline
func (b *ByteBuffer) WriteVarint32(value int32) int8 {
	u := uint32((value << 1) ^ (value >> 31))
	return b.WriteVarUint32(u)
}

// ReadVarint32 reads a signed int32 using zigzag decoding (compatible with Java's readVarint32).
//
//go:inline
func (b *ByteBuffer) ReadVarint32(err *Error) int32 {
	u := b.ReadVarUint32(err)
	v := int32(u >> 1)
	if u&1 != 0 {
		v = ^v
	}
	return v
}

// UnsafeReadVarint32 reads a varint32 without bounds checking.
// Caller must ensure remaining() >= 5 before calling.
//
//go:inline
func (b *ByteBuffer) UnsafeReadVarint32() int32 {
	u := b.readVarUint32Fast()
	v := int32(u >> 1)
	if u&1 != 0 {
		v = ^v
	}
	return v
}

// UnsafeReadVarint64 reads a varint64 without bounds checking.
// Caller must ensure remaining() >= 10 before calling.
//
//go:inline
func (b *ByteBuffer) UnsafeReadVarint64() int64 {
	u := b.readVarUint64Fast()
	v := int64(u >> 1)
	if u&1 != 0 {
		v = ^v
	}
	return v
}

// UnsafeReadVarUint32 reads a VarUint32 without bounds checking.
// Caller must ensure remaining() >= 5 before calling.
//
//go:inline
func (b *ByteBuffer) UnsafeReadVarUint32() uint32 {
	return b.readVarUint32Fast()
}

// UnsafeReadVarUint64 reads a VarUint64 without bounds checking.
// Caller must ensure remaining() >= 10 before calling.
//
//go:inline
func (b *ByteBuffer) UnsafeReadVarUint64() uint64 {
	return b.readVarUint64Fast()
}

// ReadVarUint32 reads a VarUint32 and sets error on bounds violation
//
//go:inline
func (b *ByteBuffer) ReadVarUint32(err *Error) uint32 {
	if b.remaining() >= 8 { // Need 8 bytes for bulk uint64 read in fast path
		return b.readVarUint32Fast()
	}
	return b.readVarUint32Slow(err)
}

// Fast path reading (when the remaining bytes are sufficient)
//
//go:inline
func (b *ByteBuffer) readVarUint32Fast() uint32 {
	// Single instruction load using unsafe pointer cast (little-endian only)
	// On big-endian systems, use binary.LittleEndian which the compiler optimizes
	var bulk uint64
	if isLittleEndian {
		bulk = *(*uint64)(unsafe.Pointer(&b.data[b.readerIndex]))
	} else {
		bulk = binary.LittleEndian.Uint64(b.data[b.readerIndex:])
	}

	result := uint32(bulk & 0x7F)
	readLength := 1

	if (bulk & 0x80) != 0 {
		result |= uint32((bulk >> 1) & 0x3F80)
		readLength = 2
		if (bulk & 0x8000) != 0 {
			result |= uint32((bulk >> 2) & 0x1FC000)
			readLength = 3
			if (bulk & 0x800000) != 0 {
				result |= uint32((bulk >> 3) & 0xFE00000)
				readLength = 4
				if (bulk & 0x80000000) != 0 {
					result |= uint32((bulk >> 4) & 0xF0000000)
					readLength = 5
				}
			}
		}
	}
	b.readerIndex += readLength
	return result
}

// Slow path reading (processing byte by byte)
func (b *ByteBuffer) readVarUint32Slow(err *Error) uint32 {
	var result uint32
	var shift uint
	for {
		if b.readerIndex >= len(b.data) {
			*err = BufferOutOfBoundError(b.readerIndex, 1, len(b.data))
			return 0
		}
		byteVal := b.data[b.readerIndex]
		b.readerIndex++
		result |= (uint32(byteVal) & 0x7F) << shift
		if byteVal < 0x80 {
			break
		}
		shift += 7
		if shift >= 35 {
			*err = DeserializationError("VarUint32 overflow")
			return 0
		}
	}
	return result
}

//go:inline
func (b *ByteBuffer) PutUint8(writerIndex int, value uint8) {
	b.data[writerIndex] = byte(value)
}

// WriteVarUint32Small7 writes a uint32 in variable-length small-7 format
func (b *ByteBuffer) WriteVarUint32Small7(value uint32) int {
	b.grow(8)
	if value>>7 == 0 {
		b.data[b.writerIndex] = byte(value)
		b.writerIndex++
		return 1
	}
	return b.continueWriteVarUint32Small7(value)
}

func (b *ByteBuffer) continueWriteVarUint32Small7(value uint32) int {
	encoded := uint64(value & 0x7F)
	encoded |= uint64((value&0x3f80)<<1) | 0x80
	idx := b.writerIndex
	if value>>14 == 0 {
		b.unsafePutInt32(idx, int32(encoded))
		b.writerIndex += 2
		return 2
	}
	d := b.continuePutVarint36(idx, encoded, uint64(value))
	b.writerIndex += d
	return d
}

func (b *ByteBuffer) continuePutVarint36(index int, encoded, value uint64) int {
	// bits 14
	encoded |= ((value & 0x1fc000) << 2) | 0x8000
	if value>>21 == 0 {
		b.unsafePutInt32(index, int32(encoded))
		return 3
	}
	// bits 21
	encoded |= ((value & 0xfe00000) << 3) | 0x800000
	if value>>28 == 0 {
		b.unsafePutInt32(index, int32(encoded))
		return 4
	}
	// bits 28
	encoded |= ((value & 0xff0000000) << 4) | 0x80000000
	b.unsafePutInt64(index, encoded)
	return 5
}

//go:inline
func (b *ByteBuffer) unsafePutInt32(index int, v int32) {
	binary.LittleEndian.PutUint32(b.data[index:], uint32(v))
}

//go:inline
func (b *ByteBuffer) unsafePutInt64(index int, v uint64) {
	binary.LittleEndian.PutUint64(b.data[index:], v)
}

// UnsafePutUint32 writes a uint32 at the given offset without advancing writerIndex.
// Caller must have called Reserve() to ensure capacity.
// Returns the number of bytes written (4).
//
//go:inline
func (b *ByteBuffer) UnsafePutUint32(offset int, value uint32) int {
	binary.LittleEndian.PutUint32(b.data[offset:], value)
	return 4
}

// UnsafePutUint64 writes a uint64 at the given offset without advancing writerIndex.
// Caller must have called Reserve() to ensure capacity.
// Returns the number of bytes written (8).
//
//go:inline
func (b *ByteBuffer) UnsafePutUint64(offset int, value uint64) int {
	binary.LittleEndian.PutUint64(b.data[offset:], value)
	return 8
}

// UnsafePutInt8 writes 1 byte at the given offset without bound checking.
// Caller must have ensured capacity.
// Returns the number of bytes written (1).
//
//go:inline
func (b *ByteBuffer) UnsafePutInt8(offset int, value int8) int {
	b.data[offset] = byte(value)
	return 1
}

// UnsafePutInt64 writes an int64 in little-endian format at the given offset without bound checking.
// Caller must have ensured capacity.
// Returns the number of bytes written (8).
//
//go:inline
func (b *ByteBuffer) UnsafePutInt64(offset int, value int64) int {
	binary.LittleEndian.PutUint64(b.data[offset:], uint64(value))
	return 8
}

// UnsafePutTaggedInt64 writes int64 using tagged encoding at the given offset.
// Caller must have ensured capacity (9 bytes max).
// Returns the number of bytes written (4 or 9).
//
//go:inline
func (b *ByteBuffer) UnsafePutTaggedInt64(offset int, value int64) int {
	const halfMinIntValue int64 = -1073741824 // INT32_MIN / 2
	const halfMaxIntValue int64 = 1073741823  // INT32_MAX / 2
	if value >= halfMinIntValue && value <= halfMaxIntValue {
		binary.LittleEndian.PutUint32(b.data[offset:], uint32(int32(value)<<1))
		return 4
	}
	b.data[offset] = 0b1
	if isLittleEndian {
		*(*int64)(unsafe.Pointer(&b.data[offset+1])) = value
	} else {
		binary.LittleEndian.PutUint64(b.data[offset+1:], uint64(value))
	}
	return 9
}

// UnsafePutTaggedUint64 writes uint64 using tagged encoding at the given offset.
// Caller must have ensured capacity (9 bytes max).
// Returns the number of bytes written (4 or 9).
//
//go:inline
func (b *ByteBuffer) UnsafePutTaggedUint64(offset int, value uint64) int {
	const maxSmallValue uint64 = 0x7fffffff // INT32_MAX as u64
	if value <= maxSmallValue {
		binary.LittleEndian.PutUint32(b.data[offset:], uint32(value)<<1)
		return 4
	}
	b.data[offset] = 0b1
	if isLittleEndian {
		*(*uint64)(unsafe.Pointer(&b.data[offset+1])) = value
	} else {
		binary.LittleEndian.PutUint64(b.data[offset+1:], value)
	}
	return 9
}

// ReadVarUint32Small7 reads a VarUint32 in small-7 format with error checking
func (b *ByteBuffer) ReadVarUint32Small7(err *Error) uint32 {
	if b.readerIndex >= len(b.data) {
		*err = BufferOutOfBoundError(b.readerIndex, 1, len(b.data))
		return 0
	}
	readIdx := b.readerIndex
	v := b.data[readIdx]
	readIdx++
	if v&0x80 == 0 {
		b.readerIndex = readIdx
		return uint32(v)
	}
	return b.readVarUint32Small14(err)
}

func (b *ByteBuffer) readVarUint32Small14(err *Error) uint32 {
	readIdx := b.readerIndex
	if len(b.data)-readIdx >= 5 {
		four := binary.LittleEndian.Uint32(b.data[readIdx:])
		readIdx++
		value := four & 0x7F
		if four&0x80 != 0 {
			readIdx++
			value |= (four >> 1) & 0x3f80
			if four&0x8000 != 0 {
				return b.continueReadVarUint32(readIdx, four, value)
			}
		}
		b.readerIndex = readIdx
		return value
	}
	return uint32(b.readVaruint36Slow(err))
}

func (b *ByteBuffer) continueReadVarUint32(readIdx int, bulkRead, value uint32) uint32 {
	readIdx++
	value |= (bulkRead >> 2) & 0x1fc000
	if bulkRead&0x800000 != 0 {
		readIdx++
		value |= (bulkRead >> 3) & 0xfe00000
		if bulkRead&0x80000000 != 0 {
			v := b.data[readIdx]
			readIdx++
			value |= uint32(v&0x7F) << 28
		}
	}
	b.readerIndex = readIdx
	return value
}

func (b *ByteBuffer) readVaruint36Slow(err *Error) uint64 {
	if b.readerIndex >= len(b.data) {
		*err = BufferOutOfBoundError(b.readerIndex, 1, len(b.data))
		return 0
	}
	b0 := b.data[b.readerIndex]
	b.readerIndex++
	result := uint64(b0 & 0x7F)
	if b0&0x80 != 0 {
		if b.readerIndex >= len(b.data) {
			*err = BufferOutOfBoundError(b.readerIndex, 1, len(b.data))
			return 0
		}
		b1 := b.data[b.readerIndex]
		b.readerIndex++
		result |= uint64(b1&0x7F) << 7
		if b1&0x80 != 0 {
			if b.readerIndex >= len(b.data) {
				*err = BufferOutOfBoundError(b.readerIndex, 1, len(b.data))
				return 0
			}
			b2 := b.data[b.readerIndex]
			b.readerIndex++
			result |= uint64(b2&0x7F) << 14
			if b2&0x80 != 0 {
				if b.readerIndex >= len(b.data) {
					*err = BufferOutOfBoundError(b.readerIndex, 1, len(b.data))
					return 0
				}
				b3 := b.data[b.readerIndex]
				b.readerIndex++
				result |= uint64(b3&0x7F) << 21
				if b3&0x80 != 0 {
					if b.readerIndex >= len(b.data) {
						*err = BufferOutOfBoundError(b.readerIndex, 1, len(b.data))
						return 0
					}
					b4 := b.data[b.readerIndex]
					b.readerIndex++
					result |= uint64(b4) << 28
				}
			}
		}
	}
	return result
}

// unsafeGetInt32 reads little-endian int32 at index
//
//go:inline
func (b *ByteBuffer) unsafeGetInt32(idx int) int {
	return int(int32(binary.LittleEndian.Uint32(b.data[idx:])))
}

// IncreaseReaderIndex advances readerIndex
//
//go:inline
func (b *ByteBuffer) IncreaseReaderIndex(n int) {
	b.readerIndex += n
}

// ReadBytes reads n bytes and sets error on bounds violation
func (b *ByteBuffer) ReadBytes(n int, err *Error) []byte {
	if b.readerIndex+n > len(b.data) {
		*err = BufferOutOfBoundError(b.readerIndex, n, len(b.data))
		return nil
	}
	p := b.data[b.readerIndex : b.readerIndex+n]
	b.readerIndex += n
	return p
}

// Skip skips n bytes and sets error on bounds violation
func (b *ByteBuffer) Skip(length int, err *Error) {
	if b.readerIndex+length > len(b.data) {
		*err = BufferOutOfBoundError(b.readerIndex, length, len(b.data))
		return
	}
	b.readerIndex += length
}
