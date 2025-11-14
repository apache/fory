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

func TestVarInt(t *testing.T) {
	for i := 1; i <= 32; i++ {
		buf := NewByteBuffer(nil)
		for j := 0; j < i; j++ {
			buf.WriteByte_(1) // make address unaligned.
			buf.ReadByte_()
		}
		checkVarInt(t, buf, 1, 1)
		checkVarInt(t, buf, 1<<6, 1)
		checkVarInt(t, buf, 1<<7, 2)
		checkVarInt(t, buf, 1<<13, 2)
		checkVarInt(t, buf, 1<<14, 3)
		checkVarInt(t, buf, 1<<20, 3)
		checkVarInt(t, buf, 1<<21, 4)
		checkVarInt(t, buf, 1<<27, 4)
		checkVarInt(t, buf, 1<<28, 5)
		checkVarInt(t, buf, MaxInt32, 5)
		checkVarIntWrite(t, buf, -1)
		checkVarIntWrite(t, buf, -1<<6)
		checkVarIntWrite(t, buf, -1<<7)
		checkVarIntWrite(t, buf, -1<<13)
		checkVarIntWrite(t, buf, -1<<14)
		checkVarIntWrite(t, buf, -1<<20)
		checkVarIntWrite(t, buf, -1<<21)
		checkVarIntWrite(t, buf, -1<<27)
		checkVarIntWrite(t, buf, -1<<28)
		checkVarIntWrite(t, buf, MinInt8)
		checkVarIntWrite(t, buf, MinInt16)
		checkVarIntWrite(t, buf, MinInt32)
	}
}

func checkVarInt(t *testing.T, buf *ByteBuffer, value int32, bytesWritten int8) {
	require.Equal(t, buf.WriterIndex(), buf.ReaderIndex())
	actualBytesWritten := buf.WriteVarInt32(value)
	require.Equal(t, bytesWritten, actualBytesWritten)
	varInt := buf.ReadVarInt32()
	require.Equal(t, buf.ReaderIndex(), buf.WriterIndex())
	require.Equal(t, value, varInt)
}

func checkVarIntWrite(t *testing.T, buf *ByteBuffer, value int32) {
	require.Equal(t, buf.WriterIndex(), buf.ReaderIndex())
	buf.WriteVarInt32(value)
	varInt := buf.ReadVarInt32()
	require.Equal(t, buf.ReaderIndex(), buf.WriterIndex())
	require.Equal(t, value, varInt)
}
