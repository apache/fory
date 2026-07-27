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
	"github.com/stretchr/testify/require"
	"testing"

	"github.com/apache/fory/go/fory/meta"
)

// TestMetaStringResolverNegativeIndexPanic reproduces the CRITICAL security bug
// in MetaStringResolver where a header of 1 triggers a -1 index panic.
func TestMetaStringResolverNegativeIndexPanic(t *testing.T) {
	resolver := NewMetaStringResolver()
	buffer := NewByteBuffer(nil)

	// header = 1 means (header & 1 != 0) is true (it's a reference)
	// and length = header >> 1 = 0.
	// index = length - 1 = -1.
	buffer.WriteVarUint32Small7(1)
	buffer.SetReaderIndex(0)

	var ctxErr Error
	// This should NOT panic. The fix handles the negative index.
	require.NotPanics(t, func() {
		result := resolver.ReadMetaStringBytes(buffer, &ctxErr)
		if !ctxErr.HasError() {
			t.Errorf("Expected error for negative index, got nil")
		}
		require.Nil(t, result)
	}, "MetaStringResolver should not panic on negative index")
}

// TestMetaStringResolverBoundaryRegression verifies that the smallest valid
// dynamic index (resulting in index 0) still resolves correctly.
func TestMetaStringResolverBoundaryRegression(t *testing.T) {
	resolver := NewMetaStringResolver()

	// Add a string to the cache so len is 1
	m := NewMetaStringBytes([]byte("test"), 123)
	resolver.dynamicIDToEnumString = append(resolver.dynamicIDToEnumString, m)

	// Craft a header that points to index 0
	// header = (index + 1) << 1 | 1
	// For index 0: (0 + 1) << 1 | 1 = 3
	buffer := NewByteBuffer(nil)
	buffer.WriteVarUint32Small7(3)
	buffer.SetReaderIndex(0)

	var ctxErr Error
	result := resolver.ReadMetaStringBytes(buffer, &ctxErr)
	require.False(t, ctxErr.HasError())
	require.Equal(t, m, result, "Should correctly resolve the first dynamic string (index 0)")
}

func TestMetaStringResolverRejectsLargeBodyHashMismatch(t *testing.T) {
	resolver := NewMetaStringResolver()
	buffer := NewByteBuffer(nil)
	data := []byte("0123456789abcdefg")

	buffer.WriteVarUint32Small7(uint32(len(data)) << 1)
	buffer.WriteInt64(int64(meta.UTF_8))
	buffer.Write(data)
	buffer.SetReaderIndex(0)

	var ctxErr Error
	require.Nil(t, resolver.ReadMetaStringBytes(buffer, &ctxErr))
	require.True(t, ctxErr.HasError())
	require.Empty(t, resolver.hashToMetaStrBytes)
	require.Empty(t, resolver.dynamicIDToEnumString)
}

func TestMetaStringResolverRejectsOversizedLengthBeforeAllocation(t *testing.T) {
	resolver := NewMetaStringResolver()
	buffer := NewByteBuffer(nil)
	buffer.WriteVarUint32Small7(uint32(MaxInt16+1) << 1)
	buffer.SetReaderIndex(0)

	var ctxErr Error
	require.Nil(t, resolver.ReadMetaStringBytes(buffer, &ctxErr))
	require.True(t, ctxErr.HasError())
	require.Contains(t, ctxErr.Error(), "meta string length")
	require.Empty(t, resolver.hashToMetaStrBytes)
	require.Empty(t, resolver.smallHashToMetaStrBytes)
	require.Empty(t, resolver.dynamicIDToEnumString)
}

func TestMetaStringResolverSmallCacheKeyIncludesLengthAndEncoding(t *testing.T) {
	resolver := NewMetaStringResolver()

	oneByte := NewByteBuffer(nil)
	oneByte.WriteVarUint32Small7(1 << 1)
	oneByte.WriteByte(byte(meta.UTF_8))
	oneByte.WriteByte(1)
	oneByte.SetReaderIndex(0)
	var oneErr Error
	first := resolver.ReadMetaStringBytes(oneByte, &oneErr)
	require.False(t, oneErr.HasError())
	require.Equal(t, []byte{1}, first.Data)

	twoBytes := NewByteBuffer(nil)
	twoBytes.WriteVarUint32Small7(2 << 1)
	twoBytes.WriteByte(byte(meta.UTF_8))
	twoBytes.Write([]byte{1, 0})
	twoBytes.SetReaderIndex(0)
	var twoErr Error
	second := resolver.ReadMetaStringBytes(twoBytes, &twoErr)
	require.False(t, twoErr.HasError())
	require.Equal(t, []byte{1, 0}, second.Data)
	require.NotSame(t, first, second)

	differentEncoding := NewByteBuffer(nil)
	differentEncoding.WriteVarUint32Small7(1 << 1)
	differentEncoding.WriteByte(byte(meta.LOWER_SPECIAL))
	differentEncoding.WriteByte(1)
	differentEncoding.SetReaderIndex(0)
	var encodingErr Error
	third := resolver.ReadMetaStringBytes(differentEncoding, &encodingErr)
	require.False(t, encodingErr.HasError())
	require.Equal(t, []byte{1}, third.Data)
	require.Equal(t, meta.LOWER_SPECIAL, third.Encoding)
	require.NotSame(t, first, third)
	require.Len(t, resolver.smallHashToMetaStrBytes, 3)
}

func TestComputeMetaStringHashIncludesSmallLength(t *testing.T) {
	require.NotEqual(
		t,
		ComputeMetaStringHash([]byte{1}, meta.UTF_8),
		ComputeMetaStringHash([]byte{1, 0}, meta.UTF_8),
	)
	require.NotEqual(
		t,
		ComputeMetaStringHash([]byte{1}, meta.UTF_8),
		ComputeMetaStringHash([]byte{1}, meta.LOWER_SPECIAL),
	)
}

func TestMetaStringResolverReadCachesAreCapped(t *testing.T) {
	resolver := NewMetaStringResolver()
	smallKey := smallMetaStringKey{v1: 1, v2: 0, compactKey: byte(meta.UTF_8)}
	for i := 0; i < maxCachedMetaStrings; i++ {
		resolver.smallHashToMetaStrBytes[smallMetaStringKey{
			v1:         int64(i + 2),
			v2:         0,
			compactKey: byte(meta.UTF_8),
		}] =
			NewMetaStringBytes([]byte{byte(i)}, int64(i+2)<<8)
		resolver.hashToMetaStrBytes[int64(i+2)<<8] =
			NewMetaStringBytes([]byte("0123456789abcdefg"), int64(i+2)<<8)
	}

	smallBuffer := NewByteBuffer(nil)
	smallBuffer.WriteVarUint32Small7(1 << 1)
	smallBuffer.WriteByte(byte(meta.UTF_8))
	smallBuffer.WriteByte(1)
	smallBuffer.SetReaderIndex(0)
	var smallErr Error
	resolver.ReadMetaStringBytes(smallBuffer, &smallErr)
	require.False(t, smallErr.HasError())
	require.Len(t, resolver.smallHashToMetaStrBytes, maxCachedMetaStrings)
	require.NotContains(t, resolver.smallHashToMetaStrBytes, smallKey)

	largeData := []byte("0123456789abcdefg")
	largeHash := ComputeMetaStringHash(largeData, meta.UTF_8)
	largeBuffer := NewByteBuffer(nil)
	largeBuffer.WriteVarUint32Small7(uint32(len(largeData)) << 1)
	require.NoError(t, binary.Write(largeBuffer, binary.LittleEndian, largeHash))
	largeBuffer.Write(largeData)
	largeBuffer.SetReaderIndex(0)
	var largeErr Error
	resolver.ReadMetaStringBytes(largeBuffer, &largeErr)
	require.False(t, largeErr.HasError())
	require.Len(t, resolver.hashToMetaStrBytes, maxCachedMetaStrings)
	require.NotContains(t, resolver.hashToMetaStrBytes, largeHash)
}
