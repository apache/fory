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
	"fmt"
	"math"
)

// RowReader provides O(1) random-access reads from a Standard Row Format byte slice.
// Fields can be read independently without deserializing the entire row.
type RowReader struct {
	buf       []byte
	numFields int
}

// NewRowReader wraps buf for reading a row with numFields fields.
// Panics if numFields is negative or buf is smaller than the fixed region.
// Per ADR-4: validate once at construction; individual read methods then assume validity.
func NewRowReader(buf []byte, numFields int) *RowReader {
	if numFields < 0 {
		panic("row: numFields must be non-negative")
	}
	if need := FixedRegionSize(numFields); len(buf) < need {
		panic(fmt.Sprintf("row: buffer too small: need %d bytes for %d fields, got %d",
			need, numFields, len(buf)))
	}
	return &RowReader{buf: buf, numFields: numFields}
}

// IsNull returns true if fieldIdx is null.
// Per spec (confirmed by Java BitUtils.isSet): bit=1 means null.
func (r *RowReader) IsNull(fieldIdx int) bool {
	return (r.buf[fieldIdx>>3]>>uint(fieldIdx&7))&1 == 1
}

// readPrimSlot reads the full 8-byte slot for fieldIdx as a uint64.
func (r *RowReader) readPrimSlot(fieldIdx int) uint64 {
	return binary.LittleEndian.Uint64(r.buf[SlotOffset(fieldIdx, r.numFields):])
}

// ReadBool reads the bool stored in fieldIdx's slot (0x00 = false, any other = true).
func (r *RowReader) ReadBool(fieldIdx int) bool {
	return r.buf[SlotOffset(fieldIdx, r.numFields)] != 0
}

// ReadInt8 reads the int8 stored in fieldIdx's slot.
func (r *RowReader) ReadInt8(fieldIdx int) int8 {
	return int8(r.buf[SlotOffset(fieldIdx, r.numFields)])
}

// ReadInt16 reads the int16 stored in fieldIdx's slot (little-endian).
func (r *RowReader) ReadInt16(fieldIdx int) int16 {
	return int16(binary.LittleEndian.Uint16(r.buf[SlotOffset(fieldIdx, r.numFields):]))
}

// ReadInt32 reads the int32 stored in fieldIdx's slot (little-endian).
func (r *RowReader) ReadInt32(fieldIdx int) int32 {
	return int32(binary.LittleEndian.Uint32(r.buf[SlotOffset(fieldIdx, r.numFields):]))
}

// ReadInt64 reads the int64 in fieldIdx's 8-byte slot (little-endian).
func (r *RowReader) ReadInt64(fieldIdx int) int64 {
	return int64(r.readPrimSlot(fieldIdx))
}

// ReadFloat32 reads the IEEE 754 single-precision float stored in fieldIdx's slot.
func (r *RowReader) ReadFloat32(fieldIdx int) float32 {
	return math.Float32frombits(binary.LittleEndian.Uint32(r.buf[SlotOffset(fieldIdx, r.numFields):]))
}

// ReadFloat64 reads the IEEE 754 double-precision float stored in fieldIdx's slot.
func (r *RowReader) ReadFloat64(fieldIdx int) float64 {
	return math.Float64frombits(r.readPrimSlot(fieldIdx))
}

// ReadDate32 reads days-since-Unix-epoch (int32) from fieldIdx's slot.
func (r *RowReader) ReadDate32(fieldIdx int) int32 {
	return int32(binary.LittleEndian.Uint32(r.buf[SlotOffset(fieldIdx, r.numFields):]))
}

// ReadTimestamp reads microseconds-since-Unix-epoch (int64) from fieldIdx's slot.
func (r *RowReader) ReadTimestamp(fieldIdx int) int64 {
	return int64(r.readPrimSlot(fieldIdx))
}

// ReadDuration reads a duration in microseconds (int64) from fieldIdx's slot.
func (r *RowReader) ReadDuration(fieldIdx int) int64 {
	return int64(r.readPrimSlot(fieldIdx))
}

// readVarSlot decodes the (relativeOffset, size) pair from fieldIdx's slot.
func (r *RowReader) readVarSlot(fieldIdx int) (relOffset, size int) {
	slotVal := r.readPrimSlot(fieldIdx)
	return int(slotVal >> 32), int(slotVal & 0xFFFFFFFF)
}

// ReadString reads the variable-length UTF-8 string for fieldIdx (O(1) slot lookup).
func (r *RowReader) ReadString(fieldIdx int) string {
	relOffset, size := r.readVarSlot(fieldIdx)
	return string(r.buf[relOffset : relOffset+size])
}

// ReadBinary reads the raw bytes for fieldIdx (O(1) slot lookup).
// The returned slice shares the underlying buffer; copy if longer lifetime is needed.
func (r *RowReader) ReadBinary(fieldIdx int) []byte {
	relOffset, size := r.readVarSlot(fieldIdx)
	return r.buf[relOffset : relOffset+size]
}

// ReadFixedArray returns an ArrayReader for the fixed-width array stored in fieldIdx.
// elemSize must match the width used when writing (1, 2, 4, or 8).
func (r *RowReader) ReadFixedArray(fieldIdx, elemSize int) *ArrayReader {
	relOffset, size := r.readVarSlot(fieldIdx)
	return NewFixedArrayReader(r.buf[relOffset:relOffset+size], elemSize)
}

// ReadVarArray returns an ArrayReader for the variable-width array (string/binary) stored in fieldIdx.
func (r *RowReader) ReadVarArray(fieldIdx int) *ArrayReader {
	relOffset, size := r.readVarSlot(fieldIdx)
	return NewVarArrayReader(r.buf[relOffset : relOffset+size])
}

// ReadNestedRow returns a RowReader for the nested row stored in fieldIdx.
// numFields must match the field count of the nested schema.
func (r *RowReader) ReadNestedRow(fieldIdx, numFields int) *RowReader {
	relOffset, size := r.readVarSlot(fieldIdx)
	return NewRowReader(r.buf[relOffset:relOffset+size], numFields)
}

// ReadMap returns a MapReader for the map stored in fieldIdx.
func (r *RowReader) ReadMap(fieldIdx int) *MapReader {
	relOffset, size := r.readVarSlot(fieldIdx)
	return NewMapReader(r.buf[relOffset : relOffset+size])
}
