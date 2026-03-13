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

// RowWriter builds a Standard Row Format byte slice.
// Call NewRowWriter, then WriteInt64/WriteString/SetNull for each field, then Bytes().
type RowWriter struct {
	buf       []byte
	numFields int
	varCursor int // next write position in the variable-length data region
}

// NewRowWriter allocates a zeroed buffer and positions varCursor after the fixed region.
// extraVarBytes is a capacity hint for variable-length data; the buffer grows automatically.
func NewRowWriter(numFields int, extraVarBytes int) *RowWriter {
	fixed := FixedRegionSize(numFields)
	buf := make([]byte, fixed+extraVarBytes) // Go runtime zeroes this
	return &RowWriter{
		buf:       buf,
		numFields: numFields,
		varCursor: fixed,
	}
}

// SetNull marks fieldIdx as null by setting its bit in the null bitmap.
// Per spec (and confirmed by Java BitUtils.set): bit=1 means null.
func (w *RowWriter) SetNull(fieldIdx int) {
	w.buf[fieldIdx>>3] |= 1 << uint(fieldIdx&7)
}

// writePrimSlot writes a uint64 into fieldIdx's 8-byte slot (little-endian).
// All fixed-width types funnel through here; upper bytes are zeroed by the initial allocation.
func (w *RowWriter) writePrimSlot(fieldIdx int, v uint64) {
	binary.LittleEndian.PutUint64(w.buf[SlotOffset(fieldIdx, w.numFields):], v)
}

// WriteBool stores v as 0x00 or 0x01 in the 8-byte slot (spec: 1-byte encoding, zero-padded).
func (w *RowWriter) WriteBool(fieldIdx int, v bool) {
	if v {
		w.writePrimSlot(fieldIdx, 1)
	} else {
		w.writePrimSlot(fieldIdx, 0)
	}
}

// WriteInt8 stores v two's-complement in the 8-byte slot (zero-padded).
func (w *RowWriter) WriteInt8(fieldIdx int, v int8) {
	w.writePrimSlot(fieldIdx, uint64(uint8(v)))
}

// WriteInt16 stores v little-endian in the 8-byte slot (zero-padded).
func (w *RowWriter) WriteInt16(fieldIdx int, v int16) {
	w.writePrimSlot(fieldIdx, uint64(uint16(v)))
}

// WriteInt32 stores v little-endian in the 8-byte slot (zero-padded).
func (w *RowWriter) WriteInt32(fieldIdx int, v int32) {
	w.writePrimSlot(fieldIdx, uint64(uint32(v)))
}

// WriteInt64 stores v little-endian in the 8-byte slot.
func (w *RowWriter) WriteInt64(fieldIdx int, v int64) {
	w.writePrimSlot(fieldIdx, uint64(v))
}

// WriteFloat32 stores v as IEEE 754 single-precision in the 8-byte slot (zero-padded).
func (w *RowWriter) WriteFloat32(fieldIdx int, v float32) {
	w.writePrimSlot(fieldIdx, uint64(math.Float32bits(v)))
}

// WriteFloat64 stores v as IEEE 754 double-precision in the 8-byte slot.
func (w *RowWriter) WriteFloat64(fieldIdx int, v float64) {
	w.writePrimSlot(fieldIdx, math.Float64bits(v))
}

// WriteDate32 stores days-since-Unix-epoch (int32) in the 8-byte slot (zero-padded).
func (w *RowWriter) WriteDate32(fieldIdx int, daysSinceEpoch int32) {
	w.writePrimSlot(fieldIdx, uint64(uint32(daysSinceEpoch)))
}

// WriteTimestamp stores microseconds-since-Unix-epoch (int64) in the 8-byte slot.
func (w *RowWriter) WriteTimestamp(fieldIdx int, microsSinceEpoch int64) {
	w.writePrimSlot(fieldIdx, uint64(microsSinceEpoch))
}

// WriteDuration stores a duration in microseconds (int64) in the 8-byte slot.
func (w *RowWriter) WriteDuration(fieldIdx int, micros int64) {
	w.writePrimSlot(fieldIdx, uint64(micros))
}

// writeVarSlot copies data into the variable-length region, pads to 8 bytes,
// and stores (relativeOffset<<32)|size in fieldIdx's slot.
//
// Matches Java BinaryWriter.writeUnaligned order:
//  1. Zero the padding tail first
//  2. Copy data bytes on top
//  3. Write slot with relativeOffset and size
//  4. Advance varCursor by the padded size
func (w *RowWriter) writeVarSlot(fieldIdx int, data []byte) {
	size := len(data)
	padded := PadTo8(size)

	needed := w.varCursor + padded
	if needed > len(w.buf) {
		newBuf := make([]byte, needed*2)
		copy(newBuf, w.buf)
		w.buf = newBuf
	}

	// Zero padding tail (matches Java zeroOutPaddingBytes).
	for i := w.varCursor + size; i < w.varCursor+padded; i++ {
		w.buf[i] = 0
	}
	copy(w.buf[w.varCursor:], data)

	relOffset := w.varCursor
	binary.LittleEndian.PutUint64(
		w.buf[SlotOffset(fieldIdx, w.numFields):],
		uint64(relOffset)<<32|uint64(size),
	)
	w.varCursor += padded
}

// WriteString writes s (UTF-8) into the variable-length region.
func (w *RowWriter) WriteString(fieldIdx int, s string) {
	w.writeVarSlot(fieldIdx, []byte(s))
}

// WriteBinary writes raw bytes into the variable-length region.
// Uses the same (relativeOffset<<32)|size slot encoding as WriteString.
func (w *RowWriter) WriteBinary(fieldIdx int, v []byte) {
	w.writeVarSlot(fieldIdx, v)
}

// WriteArray writes the serialized array into the variable-length region of the row
// and stores (relativeOffset<<32)|size in fieldIdx's slot.
func (w *RowWriter) WriteArray(fieldIdx int, aw *ArrayWriter) {
	w.writeVarSlot(fieldIdx, aw.Bytes())
}

// WriteNestedRow serializes child and embeds its bytes in the variable-length region.
// Per ADR-3: the child's buffer is copied into the parent's variable region.
func (w *RowWriter) WriteNestedRow(fieldIdx int, child *RowWriter) {
	w.writeVarSlot(fieldIdx, child.Bytes())
}

// WriteMap writes the serialized map into the variable-length region of the row.
func (w *RowWriter) WriteMap(fieldIdx int, mw *MapWriter) {
	w.writeVarSlot(fieldIdx, mw.Bytes())
}

// Bytes returns the complete serialized row. The slice is valid until the next write.
func (w *RowWriter) Bytes() []byte {
	return w.buf[:w.varCursor]
}
