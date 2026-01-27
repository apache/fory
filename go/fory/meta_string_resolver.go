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
	"bytes"
	"encoding/binary"
	"fmt"
	"github.com/apache/fory/go/fory/meta"
)

// Constants for string handling
const (
	SmallStringThreshold         = 16 // Maximum length for "small" strings
	DefaultDynamicWriteMetaStrID = -1 // Default ID for dynamic strings
)

type Encoding int8

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

type MetaStringResolver struct {
	dynamicWriteStringID     int16                                 // Counter for dynamic string IDs
	dynamicWrittenEnumString []*MetaStringBytes                    // Cache of written strings
	dynamicIDToEnumString    []*MetaStringBytes                    // Cache of read strings by ID
	hashToMetaStrBytes       map[int64]*MetaStringBytes            // Large string lookup
	smallHashToMetaStrBytes  map[pair]*MetaStringBytes             // Small string lookup
	enumStrSet               map[*MetaStringBytes]struct{}         // String set for deduplication
	metaStrToMetaStrBytes    map[*meta.MetaString]*MetaStringBytes // Conversion cache
}

func NewMetaStringResolver() *MetaStringResolver {
	return &MetaStringResolver{
		hashToMetaStrBytes:      make(map[int64]*MetaStringBytes),
		smallHashToMetaStrBytes: make(map[pair]*MetaStringBytes),
		enumStrSet:              make(map[*MetaStringBytes]struct{}),
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
		buf.WriteVaruint32Small7(header)

		// Small strings store encoding in header
		if m.Length <= SmallStringThreshold {
			buf.WriteByte(byte(m.Encoding))
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
		buf.WriteVaruint32Small7(header)
	}
}

// ReadMetaStringBytes reads a string from buffer, handling dynamic references
func (r *MetaStringResolver) ReadMetaStringBytes(buf *ByteBuffer, ctxErr *Error) (*MetaStringBytes, error) {
	// ReadData header containing length/reference info (uses Varuint32Small7 to match Java)
	header := buf.ReadVaruint32Small7(ctxErr)
	if ctxErr.HasError() {
		return nil, *ctxErr
	}

	length := int16(header >> 1)
	if header&1 != 0 {
		index := int(length) - 1
		if index >= len(r.dynamicIDToEnumString) {
			return nil, fmt.Errorf("invalid dynamic index: %d", index)
		}
		return r.dynamicIDToEnumString[index], nil
	}

	var (
		hashcode int64
		key      pair
		data     []byte
		encoding Encoding
	)

	// Small string optimization
	if length <= SmallStringThreshold {
		// ReadData encoding and data
		encByte := buf.ReadByte(ctxErr)
		encoding = Encoding(encByte)

		data = make([]byte, length)
		_, err := buf.Read(data)
		if err != nil {
			return nil, err
		}

		// Compute composite hash key
		if length <= 8 {
			key[0] = bytesToInt64(data)
		} else {
			err := binary.Read(bytes.NewReader(data[:8]), binary.LittleEndian, &key[0])
			if err != nil {
				return nil, err
			}
			key[1] = bytesToInt64(data[8:])
		}
		hashcode = ((key[0]*31 + key[1]) >> 8 << 8) | int64(encoding)
	} else {
		// Large string handling
		err := binary.Read(buf, binary.LittleEndian, &hashcode)
		if err != nil {
			return nil, err
		}
		encoding = Encoding(hashcode & 0xFF)
		data = make([]byte, length)
		_, err = buf.Read(data)
		if err != nil {
			return nil, err
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

	// Create and cache new string instance
	m := NewMetaStringBytes(data, hashcode)
	if length <= SmallStringThreshold {
		r.smallHashToMetaStrBytes[key] = m
	} else {
		r.hashToMetaStrBytes[hashcode] = m
	}
	r.enumStrSet[m] = struct{}{}
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

	if length <= SmallStringThreshold {
		// Small string: use direct bytes as hash components
		var v1, v2 int64
		if length <= 8 {
			v1 = bytesToInt64(data)
		} else {
			binary.Read(bytes.NewReader(data[:8]), binary.LittleEndian, &v1)
			v2 = bytesToInt64(data[8:])
		}
		hashcode = ((v1*31 + v2) >> 8 << 8) | int64(metastr.GetEncoding())
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

	if length <= SmallStringThreshold {
		// Small string: use direct bytes as hash components
		var v1, v2 int64
		if length <= 8 {
			v1 = bytesToInt64(data)
		} else {
			binary.Read(bytes.NewReader(data[:8]), binary.LittleEndian, &v1)
			v2 = bytesToInt64(data[8:])
		}
		hashcode = ((v1*31 + v2) >> 8 << 8) | int64(encoding)
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
func writeVaruint32(buf *ByteBuffer, v uint32) error {
	for v >= 0x80 {
		buf.WriteByte(byte(v) | 0x80)
		v >>= 7
	}
	buf.WriteByte(byte(v))
	return nil
}

func readVaruint32E(buf *ByteBuffer, ctxErr *Error) uint32 {
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
