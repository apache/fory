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
	"testing"

	"github.com/stretchr/testify/require"
)

type invalidReadCountReader struct {
	counts []int
}

func (r *invalidReadCountReader) Read([]byte) (int, error) {
	count := r.counts[0]
	r.counts = r.counts[1:]
	return count, nil
}

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
// the contract) keeps those 8 bytes within the backing array.
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

func TestReadVarUint32RejectsOverflowFifthByte(t *testing.T) {
	for _, data := range [][]byte{
		{0x80, 0x80, 0x80, 0x80, 0x10},
		{0x80, 0x80, 0x80, 0x80, 0x10, 0, 0, 0},
	} {
		buf := NewByteBuffer(data)
		var err Error
		_ = buf.ReadVarUint32(&err)
		require.True(t, err.HasError(), "expected overflow error for %v", data)
	}
}

func TestStreamSkipDoesNotGrowToSkippedLength(t *testing.T) {
	err := &Error{}
	data := make([]byte, 1<<20)
	reader := bytes.NewReader(data)
	buf := NewByteBufferFromReader(reader, 16)

	buf.Skip(len(data), err)

	require.True(t, err.Ok())
	require.Zero(t, reader.Len())
	require.Equal(t, len(buf.data), buf.readerIndex)
	require.LessOrEqual(t, cap(buf.data), 16)
}

func TestStreamFillDoubleGrowsFromBufferedBytes(t *testing.T) {
	var err Error
	data := make([]byte, 100)
	buf := NewByteBufferFromReader(bytes.NewReader(data), 4)
	require.True(t, buf.fill(len(data), &err))
	require.True(t, err.Ok())
	require.Equal(t, len(data), cap(buf.data))

	err = Error{}
	truncated := make([]byte, 17)
	buf = NewByteBufferFromReader(bytes.NewReader(truncated), 4)
	require.False(t, buf.fill(100, &err))
	require.True(t, err.HasError())
	require.Less(t, cap(buf.data), 100)
	require.LessOrEqual(t, cap(buf.data), 32)
}

func TestStreamFillRejectsInvalidReaderCount(t *testing.T) {
	tests := []struct {
		name   string
		counts []int
		want   string
	}{
		{name: "negative", counts: []int{-1, 2}, want: "-1"},
		{name: "oversized", counts: []int{2}, want: "2"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			reader := &invalidReadCountReader{counts: append([]int(nil), tc.counts...)}
			buf := NewByteBufferFromReader(reader, 1)
			var err Error

			require.NotPanics(t, func() {
				require.False(t, buf.fill(1, &err))
			})
			require.Error(t, err.CheckError())
			require.Contains(t, err.Error(), "invalid byte count")
			require.Contains(t, err.Error(), tc.want)
			require.Zero(t, buf.ReaderIndex())
			require.Zero(t, buf.WriterIndex())
			require.Empty(t, buf.data)
		})
	}
}

func TestReadCollectionLengthDoesNotTreatElementsAsBytes(t *testing.T) {
	writer := NewByteBuffer(nil)
	writer.WriteLength(1024)

	ctx := NewReadContext(false)
	ctx.SetData(writer.Bytes())

	require.Equal(t, 1024, ctx.ReadCollectionLength())
	require.False(t, ctx.HasError())
}

func TestReadVarUint32Small7RejectsOverflowFifthByte(t *testing.T) {
	buf := NewByteBuffer([]byte{0x80, 0x80, 0x80, 0x80, 0x10})
	var err Error
	_ = buf.ReadVarUint32Small7(&err)
	require.True(t, err.HasError())
}

func TestReadVarUint32Small7StreamRejectsOverflowFifthByte(t *testing.T) {
	buf := NewByteBufferFromReader(bytes.NewReader([]byte{0x80, 0x80, 0x80, 0x80, 0x10}), 4)
	var err Error
	_ = buf.ReadVarUint32Small7(&err)
	require.True(t, err.HasError())
}

func TestVaruint36SixByteBoundary(t *testing.T) {
	const value = uint64(1<<35 | 0x1234567)

	encoded := NewByteBuffer(make([]byte, 16))
	encoded.WriteVaruint36Small(value)
	standard := NewByteBuffer(make([]byte, 16))
	standard.WriteVarUint64(value)
	require.Equal(t, standard.Bytes(), encoded.Bytes())
	require.Len(t, encoded.Bytes(), 6)
	require.NotZero(t, encoded.Bytes()[4]&0x80)

	fastData := make([]byte, 8)
	copy(fastData, encoded.Bytes())
	fast := NewByteBuffer(fastData)
	var fastErr Error
	require.Equal(t, value, fast.ReadVaruint36Small(&fastErr))
	require.True(t, fastErr.Ok())
	require.Equal(t, 6, fast.ReaderIndex())

	slow := NewByteBuffer(encoded.Bytes())
	var slowErr Error
	require.Equal(t, value, slow.ReadVaruint36Small(&slowErr))
	require.True(t, slowErr.Ok())
	require.Equal(t, 6, slow.ReaderIndex())
}

func TestUnalignedBufferReadPaths(t *testing.T) {
	tests := []struct {
		name  string
		value uint64
		write func(*ByteBuffer, uint64)
		read  func(*ByteBuffer) uint64
	}{
		{
			name:  "varuint36",
			value: 1<<35 | 0x12345,
			write: func(buf *ByteBuffer, value uint64) { buf.WriteVaruint36Small(value) },
			read:  func(buf *ByteBuffer) uint64 { return buf.ReadVaruint36Small(nil) },
		},
		{
			name:  "varuint64",
			value: 0xfedcba9876543210,
			write: func(buf *ByteBuffer, value uint64) { buf.WriteVarUint64(value) },
			read:  func(buf *ByteBuffer) uint64 { return buf.ReadVarUint64(nil) },
		},
		{
			name:  "tagged_uint64",
			value: 0x1122334455667788,
			write: func(buf *ByteBuffer, value uint64) { buf.WriteTaggedUint64(value) },
			read:  func(buf *ByteBuffer) uint64 { return buf.ReadTaggedUint64(nil) },
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			buf := NewByteBuffer(make([]byte, 32))
			buf.WriteByte_(0)
			tc.write(buf, tc.value)
			buf.SetReaderIndex(1)
			require.Equal(t, tc.value, tc.read(buf))
		})
	}
}
