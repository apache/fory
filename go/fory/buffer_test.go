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

func TestVarint(t *testing.T) {
	err := &Error{}
	for i := 1; i <= 32; i++ {
		buf := NewByteBuffer(nil)
		for j := 0; j < i; j++ {
			buf.WriteByte_(1) // make address unaligned.
			buf.ReadByte(err)
		}
		// Zigzag encoding doubles positive values: zigzag(n) = n * 2 for positive n
		// So boundary values for positive numbers are:
		// 1 byte: 0-63 (zigzag 0-126, fits in 7 bits)
		// 2 bytes: 64-8191 (zigzag 128-16382, fits in 14 bits)
		// 3 bytes: 8192-1048575 (zigzag fits in 21 bits)
		// 4 bytes: 1048576-134217727 (zigzag fits in 28 bits)
		// 5 bytes: 134217728+ (zigzag fits in 32 bits)
		checkVarint(t, buf, 1, 1)
		checkVarint(t, buf, 63, 1)        // max 1-byte positive: zigzag(63)=126
		checkVarint(t, buf, 64, 2)        // min 2-byte positive: zigzag(64)=128
		checkVarint(t, buf, 8191, 2)      // max 2-byte positive: zigzag(8191)=16382
		checkVarint(t, buf, 8192, 3)      // min 3-byte positive: zigzag(8192)=16384
		checkVarint(t, buf, 1048575, 3)   // max 3-byte positive
		checkVarint(t, buf, 1048576, 4)   // min 4-byte positive
		checkVarint(t, buf, 134217727, 4) // max 4-byte positive
		checkVarint(t, buf, 134217728, 5) // min 5-byte positive
		checkVarint(t, buf, MaxInt32, 5)
		checkVarintWrite(t, buf, -1)
		checkVarintWrite(t, buf, -1<<6)
		checkVarintWrite(t, buf, -1<<7)
		checkVarintWrite(t, buf, -1<<13)
		checkVarintWrite(t, buf, -1<<14)
		checkVarintWrite(t, buf, -1<<20)
		checkVarintWrite(t, buf, -1<<21)
		checkVarintWrite(t, buf, -1<<27)
		checkVarintWrite(t, buf, -1<<28)
		checkVarintWrite(t, buf, MinInt8)
		checkVarintWrite(t, buf, MinInt16)
		checkVarintWrite(t, buf, MinInt32)
	}
}

func checkVarint(t *testing.T, buf *ByteBuffer, value int32, bytesWritten int8) {
	err := &Error{}
	require.Equal(t, buf.WriterIndex(), buf.ReaderIndex())
	actualBytesWritten := buf.WriteVarint32(value)
	require.Equal(t, bytesWritten, actualBytesWritten)
	varInt := buf.ReadVarint32(err)
	require.Equal(t, buf.ReaderIndex(), buf.WriterIndex())
	require.Equal(t, value, varInt)
}

func checkVarintWrite(t *testing.T, buf *ByteBuffer, value int32) {
	err := &Error{}
	require.Equal(t, buf.WriterIndex(), buf.ReaderIndex())
	buf.WriteVarint32(value)
	varInt := buf.ReadVarint32(err)
	require.Equal(t, buf.ReaderIndex(), buf.WriterIndex())
	require.Equal(t, value, varInt)
}

// TestUnsafePutVarUint32PhysicalWriteWidth verifies that UnsafePutVarUint32 performs
// an 8-byte physical write for 5-byte varints and that Reserve(8) (as required by
// buffer.go:661) keeps those 8 bytes within the backing array.
func TestUnsafePutVarUint32PhysicalWriteWidth(t *testing.T) {
	const sentinelByte = byte(0xAB)
	const totalCap = 16
	backing := make([]byte, totalCap, totalCap)

	// Fill [8, totalCap) with sentinels; [0, 8) is the reserved window.
	for i := 8; i < totalCap; i++ {
		backing[i] = sentinelByte
	}

	// Expose 8 bytes of len, matching Reserve(8) contract.
	buf := NewByteBuffer(backing[:8])

	// Reserve(8) should return immediately as len(data) is already 8.
	buf.Reserve(8)

	// Encode value >= 2^28 (5 varint bytes) which triggers 8-byte bulk write.
	written := buf.UnsafePutVarUint32(0, 1<<28)
	require.Equal(t, 5, written, "expected 5 logical bytes written")

	// Verify bytes [8, totalCap) remain untouched by the 8-byte bulk write.
	for i := 8; i < totalCap; i++ {
		require.Equal(t, sentinelByte, backing[i],
			"byte at index %d is outside the 8-byte reserved window and must not be written", i)
	}
}
