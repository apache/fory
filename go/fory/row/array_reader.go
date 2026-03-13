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
	return (r.buf[8+(idx>>3)]>>uint(idx&7))&1 == 1
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
