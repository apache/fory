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

package row

import (
	"encoding/binary"
	"math"
)

// ArrayWriter builds a Standard Row Format array byte slice.
//
// Layout:
//
//	+------------------+------------------+------------------+
//	|  Element Count   |   Null Bitmap    |   Element Data   |
//	+------------------+------------------+------------------+
//	|     8 bytes      |     B bytes      |  (variable size) |
//
// For fixed-width elements (bool/int8/int16/int32/int64/float32/float64), element data is
// packed at natural width and the region is padded to an 8-byte boundary.
// For variable-width elements (string/binary), element data holds 8-byte (relOffset|size)
// slots followed by the variable-length data, exactly as in row field slots.
//
// The null bitmap starts at byte 8. Bit=1 means null, matching the row null bitmap convention.
type ArrayWriter struct {
	buf         []byte
	numElements int
	elemSize    int // natural width per element; always 8 for variable-width
	headerSize  int // 8 (count) + bitmap bytes
	isVar       bool
	varCursor   int // write position in the variable data region (variable-width only)
}

// NewFixedArrayWriter creates an ArrayWriter for fixed-width elements.
// elemSize must be 1 (bool/int8), 2 (int16), 4 (int32/float32), or 8 (int64/float64).
func NewFixedArrayWriter(numElements, elemSize int) *ArrayWriter {
	headerSize := ArrayHeaderSize(numElements)
	dataSize := PadTo8(numElements * elemSize)
	buf := make([]byte, headerSize+dataSize) // zeroed: count=0, bitmap=0, data=0
	binary.LittleEndian.PutUint64(buf, uint64(numElements))
	return &ArrayWriter{
		buf:         buf,
		numElements: numElements,
		elemSize:    elemSize,
		headerSize:  headerSize,
		isVar:       false,
	}
}

// NewVarArrayWriter creates an ArrayWriter for variable-width elements (string/binary).
// extraVarBytes is a size hint for the variable data region; the buffer grows as needed.
func NewVarArrayWriter(numElements, extraVarBytes int) *ArrayWriter {
	headerSize := ArrayHeaderSize(numElements)
	slotsSize := numElements * 8
	buf := make([]byte, headerSize+slotsSize+extraVarBytes)
	binary.LittleEndian.PutUint64(buf, uint64(numElements))
	return &ArrayWriter{
		buf:         buf,
		numElements: numElements,
		elemSize:    8,
		headerSize:  headerSize,
		isVar:       true,
		varCursor:   headerSize + slotsSize,
	}
}

// SetNull marks element idx as null (bit=1 in the null bitmap at byte offset 8).
func (w *ArrayWriter) SetNull(idx int) {
	w.buf[8+idx>>3] |= 1 << uint(idx&7)
}

func (w *ArrayWriter) elemOff(idx int) int {
	return w.headerSize + idx*w.elemSize
}

// WriteBool writes v into element idx's 1-byte slot.
func (w *ArrayWriter) WriteBool(idx int, v bool) {
	if v {
		w.buf[w.elemOff(idx)] = 1
	} else {
		w.buf[w.elemOff(idx)] = 0
	}
}

// WriteInt8 writes v into element idx's 1-byte slot.
func (w *ArrayWriter) WriteInt8(idx int, v int8) {
	w.buf[w.elemOff(idx)] = uint8(v)
}

// WriteInt16 writes v little-endian into element idx's 2-byte slot.
func (w *ArrayWriter) WriteInt16(idx int, v int16) {
	binary.LittleEndian.PutUint16(w.buf[w.elemOff(idx):], uint16(v))
}

// WriteInt32 writes v little-endian into element idx's 4-byte slot.
func (w *ArrayWriter) WriteInt32(idx int, v int32) {
	binary.LittleEndian.PutUint32(w.buf[w.elemOff(idx):], uint32(v))
}

// WriteInt64 writes v little-endian into element idx's 8-byte slot.
func (w *ArrayWriter) WriteInt64(idx int, v int64) {
	binary.LittleEndian.PutUint64(w.buf[w.elemOff(idx):], uint64(v))
}

// WriteFloat32 writes v as IEEE 754 single-precision into element idx's 4-byte slot.
func (w *ArrayWriter) WriteFloat32(idx int, v float32) {
	binary.LittleEndian.PutUint32(w.buf[w.elemOff(idx):], math.Float32bits(v))
}

// WriteFloat64 writes v as IEEE 754 double-precision into element idx's 8-byte slot.
func (w *ArrayWriter) WriteFloat64(idx int, v float64) {
	binary.LittleEndian.PutUint64(w.buf[w.elemOff(idx):], math.Float64bits(v))
}

// writeVarElem appends data to the variable region and stores (relOffset<<32)|size in
// element idx's 8-byte slot. relOffset is from byte 0 of the array buffer.
func (w *ArrayWriter) writeVarElem(idx int, data []byte) {
	size := len(data)
	padded := PadTo8(size)

	needed := w.varCursor + padded
	if needed > len(w.buf) {
		newBuf := make([]byte, needed*2)
		copy(newBuf, w.buf)
		w.buf = newBuf
	}

	for i := w.varCursor + size; i < w.varCursor+padded; i++ {
		w.buf[i] = 0
	}
	copy(w.buf[w.varCursor:], data)

	slotOff := w.headerSize + idx*8
	binary.LittleEndian.PutUint64(
		w.buf[slotOff:],
		uint64(w.varCursor)<<32|uint64(size),
	)
	w.varCursor += padded
}

// WriteString writes s (UTF-8) as element idx in the variable-width array.
func (w *ArrayWriter) WriteString(idx int, s string) {
	w.writeVarElem(idx, []byte(s))
}

// WriteBinary writes raw bytes as element idx in the variable-width array.
func (w *ArrayWriter) WriteBinary(idx int, v []byte) {
	w.writeVarElem(idx, v)
}

// Bytes returns the complete serialized array byte slice.
func (w *ArrayWriter) Bytes() []byte {
	if w.isVar {
		return w.buf[:w.varCursor]
	}
	return w.buf
}

// ---------------------------------------------------------------------------

// ArrayReader provides random-access reads from a Standard Row Format array byte slice.
// Use NewFixedArrayReader for fixed-width element types and NewVarArrayReader for
// string/binary element types.
type ArrayReader struct {
	buf        []byte
	numElems   int
	elemSize   int
	headerSize int
	isVar      bool
}

// NewFixedArrayReader wraps buf for reading a fixed-width element array.
// elemSize must match the width used when writing (1, 2, 4, or 8).
func NewFixedArrayReader(buf []byte, elemSize int) *ArrayReader {
	n := int(binary.LittleEndian.Uint64(buf))
	return &ArrayReader{
		buf:        buf,
		numElems:   n,
		elemSize:   elemSize,
		headerSize: ArrayHeaderSize(n),
		isVar:      false,
	}
}

// NewVarArrayReader wraps buf for reading a variable-width element array (string/binary).
func NewVarArrayReader(buf []byte) *ArrayReader {
	n := int(binary.LittleEndian.Uint64(buf))
	return &ArrayReader{
		buf:        buf,
		numElems:   n,
		elemSize:   8,
		headerSize: ArrayHeaderSize(n),
		isVar:      true,
	}
}

// Len returns the number of elements.
func (r *ArrayReader) Len() int { return r.numElems }

// IsNull returns true if element idx is null (bit=1 in the bitmap at byte offset 8).
func (r *ArrayReader) IsNull(idx int) bool {
	return (r.buf[8+idx>>3]>>uint(idx&7))&1 == 1
}

func (r *ArrayReader) elemOff(idx int) int {
	return r.headerSize + idx*r.elemSize
}

// ReadBool reads the bool stored at element idx.
func (r *ArrayReader) ReadBool(idx int) bool {
	return r.buf[r.elemOff(idx)] != 0
}

// ReadInt8 reads the int8 stored at element idx.
func (r *ArrayReader) ReadInt8(idx int) int8 {
	return int8(r.buf[r.elemOff(idx)])
}

// ReadInt16 reads the int16 stored at element idx (little-endian).
func (r *ArrayReader) ReadInt16(idx int) int16 {
	return int16(binary.LittleEndian.Uint16(r.buf[r.elemOff(idx):]))
}

// ReadInt32 reads the int32 stored at element idx (little-endian).
func (r *ArrayReader) ReadInt32(idx int) int32 {
	return int32(binary.LittleEndian.Uint32(r.buf[r.elemOff(idx):]))
}

// ReadInt64 reads the int64 stored at element idx (little-endian).
func (r *ArrayReader) ReadInt64(idx int) int64 {
	return int64(binary.LittleEndian.Uint64(r.buf[r.elemOff(idx):]))
}

// ReadFloat32 reads the IEEE 754 float32 stored at element idx.
func (r *ArrayReader) ReadFloat32(idx int) float32 {
	return math.Float32frombits(binary.LittleEndian.Uint32(r.buf[r.elemOff(idx):]))
}

// ReadFloat64 reads the IEEE 754 float64 stored at element idx.
func (r *ArrayReader) ReadFloat64(idx int) float64 {
	return math.Float64frombits(binary.LittleEndian.Uint64(r.buf[r.elemOff(idx):]))
}

func (r *ArrayReader) readVarElem(idx int) (relOffset, size int) {
	v := binary.LittleEndian.Uint64(r.buf[r.headerSize+idx*8:])
	return int(v >> 32), int(v & 0xFFFFFFFF)
}

// ReadString reads the variable-length string at element idx.
func (r *ArrayReader) ReadString(idx int) string {
	relOffset, size := r.readVarElem(idx)
	return string(r.buf[relOffset : relOffset+size])
}

// ReadBinary reads the raw bytes at element idx.
// The returned slice shares the underlying buffer; copy if a longer lifetime is needed.
func (r *ArrayReader) ReadBinary(idx int) []byte {
	relOffset, size := r.readVarElem(idx)
	return r.buf[relOffset : relOffset+size]
}
