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
