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

import "encoding/binary"

// MapWriter builds a Standard Row Format map byte slice from pre-built key and value arrays.
//
// Layout:
//
//	+------------------+------------------+------------------+
//	|  Keys Array Size |   Keys Array     |   Values Array   |
//	+------------------+------------------+------------------+
//	|     8 bytes      |   variable       |   variable       |
//
// The 8-byte prefix stores the byte length of the keys array as a little-endian uint64.
// Keys and values are encoded as standard row format arrays (fixed or variable-width).
// Matches Java BinaryMap layout: keysArraySize (8 bytes) + keysArray + valuesArray.
type MapWriter struct {
	keys   *ArrayWriter
	values *ArrayWriter
}

// NewMapWriter creates a MapWriter from pre-built keys and values ArrayWriters.
// Both writers must be fully populated before calling NewMapWriter.
func NewMapWriter(keys, values *ArrayWriter) *MapWriter {
	return &MapWriter{keys: keys, values: values}
}

// Bytes serializes the map as [keysSize uint64][keysBytes][valuesBytes].
// This avoids the placeholder/backfill write-ordering required by streaming writers
// because ArrayWriter.Bytes() is available before serialization begins.
func (m *MapWriter) Bytes() []byte {
	kb := m.keys.Bytes()
	vb := m.values.Bytes()
	buf := make([]byte, 8+len(kb)+len(vb))
	binary.LittleEndian.PutUint64(buf, uint64(len(kb)))
	copy(buf[8:], kb)
	copy(buf[8+len(kb):], vb)
	return buf
}

// ---------------------------------------------------------------------------

// MapReader provides random access into a Standard Row Format map byte slice.
type MapReader struct {
	buf      []byte
	keysSize int
}

// NewMapReader wraps a map byte slice produced by MapWriter.Bytes() or by an equivalent
// encoder in another language. Panics if buf is shorter than the 8-byte size prefix.
func NewMapReader(buf []byte) *MapReader {
	if len(buf) < 8 {
		panic("row: map buffer too small to contain keys-size prefix")
	}
	keysSize := int(binary.LittleEndian.Uint64(buf))
	return &MapReader{buf: buf, keysSize: keysSize}
}

// KeysFixedArray returns an ArrayReader for a fixed-width keys array (elemSize: 1,2,4,8).
func (m *MapReader) KeysFixedArray(elemSize int) *ArrayReader {
	return NewFixedArrayReader(m.buf[8:8+m.keysSize], elemSize)
}

// ValuesFixedArray returns an ArrayReader for a fixed-width values array (elemSize: 1,2,4,8).
func (m *MapReader) ValuesFixedArray(elemSize int) *ArrayReader {
	return NewFixedArrayReader(m.buf[8+m.keysSize:], elemSize)
}

// KeysVarArray returns an ArrayReader for a variable-width keys array (string/binary).
func (m *MapReader) KeysVarArray() *ArrayReader {
	return NewVarArrayReader(m.buf[8 : 8+m.keysSize])
}

// ValuesVarArray returns an ArrayReader for a variable-width values array (string/binary).
func (m *MapReader) ValuesVarArray() *ArrayReader {
	return NewVarArrayReader(m.buf[8+m.keysSize:])
}
