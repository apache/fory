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
	"testing"

	"github.com/stretchr/testify/require"
)

func TestReadUTF16LE_EvenByteCount(t *testing.T) {
	// Test normal case: even byte count (valid UTF-16)
	// "Hi" in UTF-16LE: 'H'=0x0048, 'i'=0x0069
	// Little-endian bytes: 48 00 69 00
	data := []byte{0x48, 0x00, 0x69, 0x00}
	buf := NewByteBuffer(data)
	err := &Error{}

	result := readUTF16LE(buf, 4, err)

	require.False(t, err.HasError())
	require.Equal(t, "Hi", result)
}

func TestReadUTF16LE_OddByteCount(t *testing.T) {
	// Test edge case: odd byte count (malformed UTF-16 data).
	// This should return a typed decode error rather than silently truncating.
	data := []byte{0x48, 0x00, 0x69, 0x00, 0xFF}
	buf := NewByteBuffer(data)
	err := &Error{}

	result := readUTF16LE(buf, 5, err)

	require.True(t, err.HasError())
	require.Equal(t, ErrKindInvalidUTF16String, err.Kind())
	require.Equal(t, "", result)
}

func TestReadUTF16LE_SingleByte(t *testing.T) {
	// Test edge case: single byte (no complete UTF-16 code units)
	data := []byte{0x48}
	buf := NewByteBuffer(data)
	err := &Error{}

	result := readUTF16LE(buf, 1, err)

	require.True(t, err.HasError())
	require.Equal(t, ErrKindInvalidUTF16String, err.Kind())
	require.Equal(t, "", result)
}

func TestReadUTF16LE_EmptyBuffer(t *testing.T) {
	// Test edge case: zero bytes
	data := []byte{}
	buf := NewByteBuffer(data)
	err := &Error{}

	result := readUTF16LE(buf, 0, err)

	require.False(t, err.HasError())
	require.Equal(t, "", result)
}

func TestReadUTF16LE_SurrogatePair(t *testing.T) {
	// Test UTF-16 surrogate pair for emoji: 🎉 (U+1F389)
	// UTF-16: D83C DF89 (surrogate pair)
	// Little-endian bytes: 3C D8 89 DF
	data := []byte{0x3C, 0xD8, 0x89, 0xDF}
	buf := NewByteBuffer(data)
	err := &Error{}

	result := readUTF16LE(buf, 4, err)

	require.False(t, err.HasError())
	require.Equal(t, "🎉", result)
}
