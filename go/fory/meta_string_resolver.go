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
	"github.com/apache/fory/go/fory/meta"
)

// Constants for string handling
const (
	SmallStringThreshold         = 16 // Maximum length for "small" strings
	DefaultDynamicWriteMetaStrID = -1 // Default ID for dynamic strings
	maxCachedMetaStrings         = 8192
	smallMetaStringEncodingBits  = 4
)

type MetaStringBytes struct {
	Data                 []byte
	Length               int16
	Encoding             meta.Encoding
	Hashcode             int64
	DynamicWriteStringID int16
}

func NewMetaStringBytes(data []byte, hashcode int64) *MetaStringBytes {
	return &MetaStringBytes{
		Data:                 data,
		Length:               int16(len(data)),
		Hashcode:             hashcode,
		Encoding:             meta.Encoding(hashcode & 0xFF),
		DynamicWriteStringID: DefaultDynamicWriteMetaStrID,
	}
}

func (a *MetaStringBytes) Equals(b *MetaStringBytes) bool {
	return a.Hashcode == b.Hashcode
}

func (a *MetaStringBytes) Hash() int64 {
	return a.Hashcode
}

type pair [2]int64

// Mirrors Java's small MetaString read cache key: two packed byte words plus one
// compact length/encoding byte. The packed words are zero-padded and are not
// exact byte identity by themselves.
type smallMetaStringKey struct {
	v1         int64
	v2         int64
	compactKey byte
}

type MetaStringResolver struct {
	dynamicWriteStringID     int16                                   // Counter for dynamic string IDs
	dynamicWrittenEnumString []*MetaStringBytes                      // Cache of written strings
	dynamicIDToEnumString    []*MetaStringBytes                      // Cache of read strings by ID
	hashToMetaStrBytes       map[int64]*MetaStringBytes              // Large string lookup
	smallHashToMetaStrBytes  map[smallMetaStringKey]*MetaStringBytes // Small string lookup
	metaStrToMetaStrBytes    map[*meta.MetaString]*MetaStringBytes   // Conversion cache
}

var emptyMetaStringBytes = NewMetaStringBytes([]byte{}, 256)

func NewMetaStringResolver() *MetaStringResolver {
	return &MetaStringResolver{
		hashToMetaStrBytes:      make(map[int64]*MetaStringBytes),
		smallHashToMetaStrBytes: make(map[smallMetaStringKey]*MetaStringBytes),
		metaStrToMetaStrBytes:   make(map[*meta.MetaString]*MetaStringBytes),
	}
}

func (r *MetaStringResolver) WriteMetaStringBytes(buf *ByteBuffer, m *MetaStringBytes, err *Error) {
	if m.DynamicWriteStringID == DefaultDynamicWriteMetaStrID {
		// First occurrence: write full string data
		m.DynamicWriteStringID = r.dynamicWriteStringID
		r.dynamicWriteStringID++
		r.dynamicWrittenEnumString = append(r.dynamicWrittenEnumString, m)

		// WriteData header with length and encoding info
		header := uint32(m.Length) << 1
		buf.WriteVarUint32Small7(header)

		// Small strings store encoding in header
		if m.Length <= SmallStringThreshold {
			if m.Length != 0 {
				buf.WriteByte(byte(m.Encoding))
			}
		} else {
			// Large strings include full hash
			binErr := binary.Write(buf, binary.LittleEndian, m.Hashcode)
			if binErr != nil {
				err.SetError(binErr)
				return
			}
		}
		buf.Write(m.Data)
	} else {
		// Subsequent occurrence: write reference ID only
		header := uint32((m.DynamicWriteStringID+1)<<1) | 1
		buf.WriteVarUint32Small7(header)
	}
}

// ReadMetaStringBytes reads a string from buffer, handling dynamic references
func (r *MetaStringResolver) ReadMetaStringBytes(buf *ByteBuffer, ctxErr *Error) (*MetaStringBytes, error) {
	// ReadData header containing length/reference info (uses VarUint32Small7 to match Java)
	header := buf.ReadVarUint32Small7(ctxErr)
	if ctxErr.HasError() {
		return nil, *ctxErr
	}

	lengthValue := header >> 1
	if header&1 != 0 {
		if lengthValue == 0 || uint64(lengthValue) > uint64(MaxInt) {
			return nil, fmt.Errorf("invalid dynamic index: %d", lengthValue)
		}
		index := int(lengthValue) - 1
		if index < 0 || index >= len(r.dynamicIDToEnumString) {
			return nil, fmt.Errorf("invalid dynamic index: %d", index)
		}
		return r.dynamicIDToEnumString[index], nil
	}
	if lengthValue > uint32(MaxInt16) {
		return nil, fmt.Errorf("meta string length %d exceeds maximum supported length %d", lengthValue, MaxInt16)
	}
	length := int(lengthValue)

	var (
		hashcode int64
		key      smallMetaStringKey
		data     []byte
		encoding meta.Encoding
	)

	// Small string optimization
	if length <= SmallStringThreshold {
		if length == 0 {
			r.dynamicIDToEnumString = append(r.dynamicIDToEnumString, emptyMetaStringBytes)
			return emptyMetaStringBytes, nil
		}
		encByte := buf.ReadByte(ctxErr)
		var encErr error
		encoding, encErr = meta.EncodingFromByte(encByte)
		if encErr != nil {
			return nil, encErr
		}

		if !buf.CheckReadable(length, ctxErr) {
			return nil, *ctxErr
		}
		data = make([]byte, length)
		_, err := buf.Read(data)
		if err != nil {
			return nil, err
		}

		words := smallMetaStringWords(data)
		key = smallMetaStringKey{
			v1:         words[0],
			v2:         words[1],
			compactKey: byte(((length - 1) << smallMetaStringEncodingBits) | int(encoding)),
		}
		hashcode = computeSmallMetaStringHash(words, length, encoding)
	} else {
		// Large string handling
		err := binary.Read(buf, binary.LittleEndian, &hashcode)
		if err != nil {
			return nil, err
		}
		var encErr error
		encoding, encErr = meta.EncodingFromByte(byte(hashcode & 0xFF))
		if encErr != nil {
			return nil, encErr
		}
		if !buf.CheckReadable(length, ctxErr) {
			return nil, *ctxErr
		}
		data = make([]byte, length)
		_, err = buf.Read(data)
		if err != nil {
			return nil, err
		}
		canonicalHashcode := ComputeMetaStringHash(data, encoding)
		if canonicalHashcode != hashcode {
			return nil, fmt.Errorf("meta string body hash mismatch")
		}
	}

	// Check string caches for existing instance
	if length <= SmallStringThreshold {
		if m, ok := r.smallHashToMetaStrBytes[key]; ok {
			r.dynamicIDToEnumString = append(r.dynamicIDToEnumString, m)
			return m, nil
		}
	} else {
		if m, ok := r.hashToMetaStrBytes[hashcode]; ok {
			r.dynamicIDToEnumString = append(r.dynamicIDToEnumString, m)
			return m, nil
		}
	}

	// Cache only after the current body has been parsed and, for large bodies, hash-validated.
	// Header-keyed hits stay on the fast path; forged headers cannot poison the shared cache.
	m := NewMetaStringBytes(data, hashcode)
	if length <= SmallStringThreshold {
		if len(r.smallHashToMetaStrBytes) < maxCachedMetaStrings {
			r.smallHashToMetaStrBytes[key] = m
		}
	} else {
		if len(r.hashToMetaStrBytes) < maxCachedMetaStrings {
			r.hashToMetaStrBytes[hashcode] = m
		}
	}
	r.dynamicIDToEnumString = append(r.dynamicIDToEnumString, m)

	return m, nil
}

// GetMetaStrBytes converts MetaString to optimized MetaStringBytes
func (r *MetaStringResolver) GetMetaStrBytes(metastr *meta.MetaString) *MetaStringBytes {
	// Check cache first
	if m, exists := r.metaStrToMetaStrBytes[metastr]; exists {
		return m
	}

	// Compute hash based on string size
	var hashcode int64
	data := metastr.GetEncodedBytes()
	length := len(data)

	if length == 0 {
		r.metaStrToMetaStrBytes[metastr] = emptyMetaStringBytes
		return emptyMetaStringBytes
	}
	if length <= SmallStringThreshold {
		// Small string: use direct bytes as hash components
		words := smallMetaStringWords(data)
		hashcode = computeSmallMetaStringHash(words, length, metastr.GetEncoding())
	} else {
		// Large string: use MurmurHash3
		h64 := Murmur3Sum64WithSeed(data, 47)
		hashcode = int64((h64 >> 8) << 8)
		hashcode |= int64(metastr.GetEncoding())
	}

	// Create and cache new instance
	m := NewMetaStringBytes(data, hashcode)
	r.metaStrToMetaStrBytes[metastr] = m
	return m
}

// ComputeMetaStringHash computes the hashcode for meta string bytes
func ComputeMetaStringHash(data []byte, encoding meta.Encoding) int64 {
	length := len(data)
	var hashcode int64

	if length == 0 {
		hashcode = 256
		hashcode |= int64(encoding)
	} else if length <= SmallStringThreshold {
		// Small string: use direct bytes as hash components
		words := smallMetaStringWords(data)
		hashcode = computeSmallMetaStringHash(words, length, encoding)
	} else {
		// Large string: use MurmurHash3
		h64 := Murmur3Sum64WithSeed(data, 47)
		hashcode = int64((h64 >> 8) << 8)
		hashcode |= int64(encoding)
	}
	return hashcode
}

func (r *MetaStringResolver) ResetRead() {
	r.dynamicIDToEnumString = nil
}

func (r *MetaStringResolver) ResetWrite() {
	r.dynamicWriteStringID = 0
	for _, m := range r.dynamicWrittenEnumString {
		m.DynamicWriteStringID = DefaultDynamicWriteMetaStrID
	}
	r.dynamicWrittenEnumString = nil
}

// Helper functions
func WriteVarUint32(buf *ByteBuffer, v uint32) error {
	for v >= 0x80 {
		buf.WriteByte(byte(v) | 0x80)
		v >>= 7
	}
	buf.WriteByte(byte(v))
	return nil
}

func readVarUint32E(buf *ByteBuffer, ctxErr *Error) uint32 {
	var x uint32
	var s uint
	for {
		b := buf.ReadByte(ctxErr)
		if ctxErr.HasError() {
			return 0
		}
		x |= uint32(b&0x7F) << s
		if b < 0x80 {
			break
		}
		s += 7
	}
	return x
}

func bytesToInt64(b []byte) int64 {
	var v int64
	for i := range b {
		v |= int64(b[i]) << (8 * i)
	}
	return v
}

func smallMetaStringWords(data []byte) pair {
	if len(data) <= 8 {
		return pair{bytesToInt64(data), 0}
	}
	return pair{int64(binary.LittleEndian.Uint64(data[:8])), bytesToInt64(data[8:])}
}

func computeSmallMetaStringHash(words pair, length int, encoding meta.Encoding) int64 {
	hash := uint64(words[0]*31+words[1]) ^ (uint64(length) << 56)
	return int64((hash & 0xffffffffffffff00) | uint64(encoding))
}
