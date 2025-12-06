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
)

type ByteBuffer struct {
	io.Writer
	io.Reader
	writerIndex int
	readerIndex int
	data        []byte
}

func NewByteBuffer(data []byte) *ByteBuffer {
	return &ByteBuffer{data: data}
}

func (b *ByteBuffer) grow(n int) {
	l := b.writerIndex
	if l+n < len(b.data) {
		return
	}
	if l+n < cap(b.data) {
		b.data = b.data[:cap(b.data)]
	} else {
		newBuf := make([]byte, 2*(l+n), 2*(l+n))
		copy(newBuf, b.data)
		b.data = newBuf
	}
}

func (b *ByteBuffer) WriteBool(value bool) {
	b.grow(1)
	if value {
		b.data[b.writerIndex] = 1
	} else {
		b.data[b.writerIndex] = 0
	}
	b.writerIndex++
}

func (b *ByteBuffer) WriteByte(value byte) error {
	b.grow(1)
	b.data[b.writerIndex] = value
	b.writerIndex++
	return nil
}

func (b *ByteBuffer) WriteByte_(value byte) {
	b.grow(1)
	b.data[b.writerIndex] = value
	b.writerIndex++
}

func (b *ByteBuffer) WriteInt8(value int8) {
	b.grow(1)
	b.data[b.writerIndex] = byte(value)
	b.writerIndex++
}

func (b *ByteBuffer) WriteUint8(value uint8) {
	b.grow(1)
	b.data[b.writerIndex] = value
	b.writerIndex++
}

func (b *ByteBuffer) WriteInt16(value int16) {
	b.grow(2)
	binary.LittleEndian.PutUint16(b.data[b.writerIndex:], uint16(value))
	b.writerIndex += 2
}

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
	b.WriteVarInt32(int32(value))
}

func (b *ByteBuffer) ReadLength() int {
	return int(b.ReadVarInt32())
}

func (b *ByteBuffer) WriteInt64(value int64) {
	b.grow(8)
	binary.LittleEndian.PutUint64(b.data[b.writerIndex:], uint64(value))
	b.writerIndex += 8
}

func (b *ByteBuffer) WriteFloat32(value float32) {
	b.grow(4)
	binary.LittleEndian.PutUint32(b.data[b.writerIndex:], Float32bits(value))
	b.writerIndex += 4
}

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

func (b *ByteBuffer) ReadBool() bool {
	v := b.data[b.readerIndex]
	b.readerIndex++
	if v == 0 {
		return false
	} else {
		return true
	}
}

func (b *ByteBuffer) ReadByte_() byte {
	v := b.data[b.readerIndex]
	b.readerIndex++
	return v
}

func (b *ByteBuffer) ReadByte() (byte, error) {
	v := b.data[b.readerIndex]
	b.readerIndex++
	return v, nil
}

func (b *ByteBuffer) ReadInt8() int8 {
	i := int8(b.data[b.readerIndex])
	b.readerIndex += 1
	return i
}

func (b *ByteBuffer) ReadInt16() int16 {
	i := int16(binary.LittleEndian.Uint16(b.data[b.readerIndex:]))
	b.readerIndex += 2
	return i
}

func (b *ByteBuffer) ReadUint32() uint32 {
	i := binary.LittleEndian.Uint32(b.data[b.readerIndex:])
	b.readerIndex += 4
	return i
}

func (b *ByteBuffer) ReadUint64() uint64 {
	i := binary.LittleEndian.Uint64(b.data[b.readerIndex:])
	b.readerIndex += 8
	return i
}

func (b *ByteBuffer) ReadInt32() int32 {
	return int32(b.ReadUint32())
}

func (b *ByteBuffer) ReadInt64() int64 {
	return int64(b.ReadUint64())
}

func (b *ByteBuffer) ReadFloat32() float32 {
	return Float32frombits(b.ReadUint32())
}

func (b *ByteBuffer) ReadFloat64() float64 {
	return Float64frombits(b.ReadUint64())
}

func (b *ByteBuffer) Read(p []byte) (n int, err error) {
	copied := copy(p, b.data[b.readerIndex:])
	b.readerIndex += copied
	return copied, nil
}

func (b *ByteBuffer) ReadBinary(length int) []byte {
	v := b.data[b.readerIndex : b.readerIndex+length]
	b.readerIndex += length
	return v
}

func (b *ByteBuffer) GetData() []byte {
	return b.data
}

func (b *ByteBuffer) GetByteSlice(start, end int) []byte {
	return b.data[start:end]
}

func (b *ByteBuffer) Slice(start, length int) *ByteBuffer {
	return NewByteBuffer(b.data[start : start+length])
}

func (b *ByteBuffer) WriterIndex() int {
	return b.writerIndex
}

func (b *ByteBuffer) SetWriterIndex(index int) {
	b.writerIndex = index
}

func (b *ByteBuffer) ReaderIndex() int {
	return b.readerIndex
}

func (b *ByteBuffer) SetReaderIndex(index int) {
	b.readerIndex = index
}

func (b *ByteBuffer) Reset() {
	b.readerIndex = 0
	b.writerIndex = 0
	b.data = nil
}

func (b *ByteBuffer) PutInt32(index int, value int32) {
	b.grow(4)
	binary.LittleEndian.PutUint32(b.data[index:], uint32(value))
}

// WriteVarInt32 WriteVarUint writes a 1-5 byte int, returns the number of bytes written.
func (b *ByteBuffer) WriteVarInt32(value int32) int8 {
	if value>>7 == 0 {
		b.grow(1)
		b.data[b.writerIndex] = byte(value)
		b.writerIndex++
		return 1
	}
	if value>>14 == 0 {
		b.grow(2)
		b.data[b.writerIndex] = byte((value & 0x7F) | 0x80)
		b.data[b.writerIndex+1] = byte(value >> 7)
		b.writerIndex += 2
		return 2
	}
	if value>>21 == 0 {
		b.grow(3)
		b.data[b.writerIndex] = byte((value & 0x7F) | 0x80)
		b.data[b.writerIndex+1] = byte(value>>7 | 0x80)
		b.data[b.writerIndex+2] = byte(value >> 14)
		b.writerIndex += 3
		return 3
	}
	if value>>28 == 0 {
		b.grow(4)
		b.data[b.writerIndex] = byte((value & 0x7F) | 0x80)
		b.data[b.writerIndex+1] = byte(value>>7 | 0x80)
		b.data[b.writerIndex+2] = byte(value>>14 | 0x80)
		b.data[b.writerIndex+3] = byte(value >> 21)
		b.writerIndex += 4
		return 4
	}
	b.grow(5)
	b.data[b.writerIndex] = byte((value & 0x7F) | 0x80)
	b.data[b.writerIndex+1] = byte(value>>7 | 0x80)
	b.data[b.writerIndex+2] = byte(value>>14 | 0x80)
	b.data[b.writerIndex+3] = byte(value>>21 | 0x80)
	b.data[b.writerIndex+4] = byte(value >> 28)
	b.writerIndex += 5
	return 5
}

// ReadVarInt32 reads the 1-5 byte int part of a varint.
func (b *ByteBuffer) ReadVarInt32() int32 {
	readerIndex := b.readerIndex
	byte_ := int32(b.data[readerIndex])
	readerIndex++
	result := byte_ & 0x7F
	if (byte_ & 0x80) != 0 {
		byte_ = int32(b.data[readerIndex])
		readerIndex++
		result |= (byte_ & 0x7F) << 7
		if (byte_ & 0x80) != 0 {
			byte_ = int32(b.data[readerIndex])
			readerIndex++
			result |= (byte_ & 0x7F) << 14
			if (byte_ & 0x80) != 0 {
				byte_ = int32(b.data[readerIndex])
				readerIndex++
				result |= (byte_ & 0x7F) << 21
				if (byte_ & 0x80) != 0 {
					byte_ = int32(b.data[readerIndex])
					readerIndex++
					result |= (byte_ & 0x7F) << 28
				}
			}
		}
	}
	b.readerIndex = readerIndex
	return result
}

type BufferObject interface {
	TotalBytes() int
	WriteTo(buf *ByteBuffer)
	ToBuffer() *ByteBuffer
}

// WriteVarint64 writes the zig-zag encoded varint
func (b *ByteBuffer) WriteVarint64(value int64) {
	u := uint64((value << 1) ^ (value >> 63))
	b.WriteVarUint64(u)
}

// WriteVarUint64 writes to unsigned varint (up to 9 bytes)
func (b *ByteBuffer) WriteVarUint64(value uint64) {
	b.grow(9)
	offset := b.writerIndex
	data := b.data[offset : offset+9]

	i := 0
	for ; i < 8; i++ {
		data[i] = byte(value & 0x7F)
		value >>= 7
		if value == 0 {
			i++
			break
		}
		data[i] |= 0x80
	}
	if i == 8 {
		data[8] = byte(value)
		i = 9
	}
	b.writerIndex += i
}

// ReadVarint64 reads the varint encoded with zig-zag
func (b *ByteBuffer) ReadVarint64() int64 {
	u := b.ReadVarUint64()
	v := int64(u >> 1)
	if u&1 != 0 {
		v = ^v
	}
	return v
}

// ReadVarUint64 reads unsigned varint
func (b *ByteBuffer) ReadVarUint64() uint64 {
	if b.remaining() >= 9 {
		return b.readVarUint64Fast()
	}
	return b.readVarUint64Slow()
}

// Fast path (when the remaining bytes are sufficient)
func (b *ByteBuffer) readVarUint64Fast() uint64 {
	data := b.data[b.readerIndex:]
	var result uint64
	var readLength int

	b0 := data[0]
	result = uint64(b0 & 0x7F)
	if b0 < 0x80 {
		readLength = 1
	} else {
		b1 := data[1]
		result |= uint64(b1&0x7F) << 7
		if b1 < 0x80 {
			readLength = 2
		} else {
			b2 := data[2]
			result |= uint64(b2&0x7F) << 14
			if b2 < 0x80 {
				readLength = 3
			} else {
				b3 := data[3]
				result |= uint64(b3&0x7F) << 21
				if b3 < 0x80 {
					readLength = 4
				} else {
					b4 := data[4]
					result |= uint64(b4&0x7F) << 28
					if b4 < 0x80 {
						readLength = 5
					} else {
						b5 := data[5]
						result |= uint64(b5&0x7F) << 35
						if b5 < 0x80 {
							readLength = 6
						} else {
							b6 := data[6]
							result |= uint64(b6&0x7F) << 42
							if b6 < 0x80 {
								readLength = 7
							} else {
								b7 := data[7]
								result |= uint64(b7&0x7F) << 49
								if b7 < 0x80 {
									readLength = 8
								} else {
									b8 := data[8]
									result |= uint64(b8) << 56
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
func (b *ByteBuffer) readVarUint64Slow() uint64 {
	var result uint64
	var shift uint
	for {
		byteVal := b.ReadUint8()
		result |= (uint64(byteVal) & 0x7F) << shift
		if byteVal < 0x80 {
			break
		}
		shift += 7
		if shift >= 64 {
			panic("varuint64 overflow")
		}
	}
	return result
}

// Auxiliary function
func (b *ByteBuffer) remaining() int {
	return len(b.data) - b.readerIndex
}

func (b *ByteBuffer) ReadUint8() uint8 {
	if b.readerIndex >= len(b.data) {
		panic("buffer underflow")
	}
	v := b.data[b.readerIndex]
	b.readerIndex++
	return v
}

func (b *ByteBuffer) WriteVarint32(value int32) {
	u := uint32((value << 1) ^ (value >> 31))
	b.WriteVarUint32(u)
}

func (b *ByteBuffer) WriteVarUint32(value uint32) {
	b.grow(5)
	offset := b.writerIndex
	data := b.data[offset : offset+5]

	i := 0
	for ; i < 4; i++ {
		data[i] = byte(value & 0x7F)
		value >>= 7
		if value == 0 {
			i++
			break
		}
		data[i] |= 0x80
	}
	if i == 4 {
		data[4] = byte(value)
		i = 5
	}
	b.writerIndex += i
}

func (b *ByteBuffer) ReadVarint32() int32 {
	u := b.ReadVarUint32()
	v := int32(u >> 1)
	if u&1 != 0 {
		v = ^v
	}
	return v
}

func (b *ByteBuffer) ReadVarUint32() uint32 {
	if b.remaining() >= 5 {
		return b.readVarUint32Fast()
	}
	return b.readVarUint32Slow()
}

// Fast path reading (when the remaining bytes are sufficient)
func (b *ByteBuffer) readVarUint32Fast() uint32 {
	data := b.data[b.readerIndex:]
	var result uint32
	var readLength int

	b0 := data[0]
	result = uint32(b0 & 0x7F)
	if b0 < 0x80 {
		readLength = 1
	} else {
		b1 := data[1]
		result |= uint32(b1&0x7F) << 7
		if b1 < 0x80 {
			readLength = 2
		} else {
			b2 := data[2]
			result |= uint32(b2&0x7F) << 14
			if b2 < 0x80 {
				readLength = 3
			} else {
				b3 := data[3]
				result |= uint32(b3&0x7F) << 21
				if b3 < 0x80 {
					readLength = 4
				} else {
					b4 := data[4]
					result |= uint32(b4&0x7F) << 28
					readLength = 5
				}
			}
		}
	}
	b.readerIndex += readLength
	return result
}

// Slow path reading (processing byte by byte)
func (b *ByteBuffer) readVarUint32Slow() uint32 {
	var result uint32
	var shift uint
	for {
		byteVal := b.ReadUint8()
		result |= (uint32(byteVal) & 0x7F) << shift
		if byteVal < 0x80 {
			break
		}
		shift += 7
		if shift >= 28 {
			panic("varuint32 overflow")
		}
	}
	return result
}

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
	d := b.continuePutVarInt36(idx, encoded, uint64(value))
	b.writerIndex += d
	return d
}

func (b *ByteBuffer) continuePutVarInt36(index int, encoded, value uint64) int {
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

func (b *ByteBuffer) unsafePutInt32(index int, v int32) {
	binary.LittleEndian.PutUint32(b.data[index:], uint32(v))
}

func (b *ByteBuffer) unsafePutInt64(index int, v uint64) {
	binary.LittleEndian.PutUint64(b.data[index:], v)
}

// ByteBuffer methods for variable-length integers
func (b *ByteBuffer) ReadVarUint32Small7() int {
	readIdx := b.readerIndex
	if len(b.data)-readIdx > 0 {
		v := b.data[readIdx]
		readIdx++
		if v&0x80 == 0 {
			b.readerIndex = readIdx
			return int(v)
		}
	}
	return b.readVarUint32Small14()
}

func (b *ByteBuffer) readVarUint32Small14() int {
	readIdx := b.readerIndex
	if len(b.data)-readIdx >= 5 {
		four := b.unsafeGetInt32(readIdx)
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
	return int(b.readVarUint36Slow())
}

func (b *ByteBuffer) continueReadVarUint32(readIdx, bulkRead, value int) int {
	readIdx++
	value |= (bulkRead >> 2) & 0x1fc000
	if bulkRead&0x800000 != 0 {
		readIdx++
		value |= (bulkRead >> 3) & 0xfe00000
		if bulkRead&0x80000000 != 0 {
			v := b.data[readIdx]
			readIdx++
			value |= int(v&0x7F) << 28
		}
	}
	b.readerIndex = readIdx
	return value
}

func (b *ByteBuffer) readVarUint36Slow() uint64 {
	// unrolled loop
	b0, _ := b.ReadByte()
	result := uint64(b0 & 0x7F)
	if b0&0x80 != 0 {
		b1, _ := b.ReadByte()
		result |= uint64(b1&0x7F) << 7
		if b1&0x80 != 0 {
			b2, _ := b.ReadByte()
			result |= uint64(b2&0x7F) << 14
			if b2&0x80 != 0 {
				b3, _ := b.ReadByte()
				result |= uint64(b3&0x7F) << 21
				if b3&0x80 != 0 {
					b4, _ := b.ReadByte()
					result |= uint64(b4) << 28
				}
			}
		}
	}
	return result
}

// unsafeGetInt32 reads little-endian int32 at index
func (b *ByteBuffer) unsafeGetInt32(idx int) int {
	return int(int32(binary.LittleEndian.Uint32(b.data[idx:])))
}

// IncreaseReaderIndex advances readerIndex
func (b *ByteBuffer) IncreaseReaderIndex(n int) {
	b.readerIndex += n
}

// ReadBytesAsInt64 reads up to 8 bytes and returns as uint64
// fast path using underlying 64-bit read
func (b *ByteBuffer) ReadBytesAsInt64(length int) uint64 {
	readerIdx := b.readerIndex
	remaining := len(b.data) - readerIdx
	if remaining >= length {
		// fast: read full 8 bytes then mask
		v := binary.LittleEndian.Uint64(b.data[readerIdx:])
		b.readerIndex = readerIdx + length
		// mask off unused high bytes
		mask := uint64(0xffffffffffffffff) >> uint((8-length)*8)
		return v & mask
	}
	return b.slowReadBytesAsInt64(remaining, length)
}

func (b *ByteBuffer) slowReadBytesAsInt64(remaining, length int) uint64 {
	// fill buffer omitted: assume data available
	readerIdx := b.readerIndex
	b.readerIndex = readerIdx + length
	var result uint64
	for i := 0; i < length; i++ {
		result |= uint64(b.data[readerIdx+i]&0xff) << (i * 8)
	}
	return result
}

// ReadBytes reads n bytes
func (b *ByteBuffer) ReadBytes(n int) []byte {
	p := b.data[b.readerIndex : b.readerIndex+n]
	b.readerIndex += n
	return p
}
