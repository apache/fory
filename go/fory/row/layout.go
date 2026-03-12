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

// Package row implements the Apache Fory Standard Row Format.
//
// Layout for a row with N fields:
//
//	+----------------+------------------+------------------+------------------+
//	|  Null Bitmap   |  Field 0 Slot    |  Field 1 Slot    |  Variable Data   |
//	+----------------+------------------+------------------+------------------+
//	|  B bytes       |     8 bytes      |     8 bytes      |  Variable size   |
//
// B = ((N + 63) / 64) * 8  (8-byte aligned)
//
// Null bitmap: bit=1 means the field is null, bit=0 means not null.
// Fixed-width fields: value stored directly in slot (little-endian, zero-padded).
// Variable-width fields: slot holds (relativeOffset << 32) | size (little-endian).
//
//	relativeOffset is measured from the row's base address (startOffset).
package row

// BitmapSize returns the null bitmap size in bytes for a row with numFields fields.
// Formula from spec: ((numFields + 63) / 64) * 8
func BitmapSize(numFields int) int {
	return ((numFields + 63) / 64) * 8
}

// FixedRegionSize returns the total byte length of the bitmap plus all 8-byte field slots.
func FixedRegionSize(numFields int) int {
	return BitmapSize(numFields) + numFields*8
}

// SlotOffset returns the byte offset of fieldIdx's 8-byte slot within the row buffer.
// Mirrors Java: baseOffset + bitmapWidthInBytes + (ordinal << 3)
func SlotOffset(fieldIdx, numFields int) int {
	return BitmapSize(numFields) + fieldIdx*8
}

// PadTo8 rounds n up to the nearest 8-byte boundary.
func PadTo8(n int) int {
	return (n + 7) &^ 7
}

// ArrayHeaderSize returns the byte size of an array header for numElements elements.
// Layout: [8-byte count][null bitmap]
// Formula from spec: 8 + ((numElements + 63) / 64) * 8
func ArrayHeaderSize(numElements int) int {
	return 8 + BitmapSize(numElements)
}
