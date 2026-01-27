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

import "testing"

var benchVaruint64Values = []uint64{
	0,
	1,
	127,
	128,
	16384,
	1 << 20,
	1 << 40,
	1<<63 - 1,
	^uint64(0),
}

var benchVaruint64SmallValues = []uint64{
	0,
	1,
	2,
	3,
	7,
	15,
	31,
	63,
	127,
}

var benchVaruint64MidValues = []uint64{
	128,
	129,
	16383,
	16384,
	1<<20 - 1,
	1 << 20,
	1<<27 - 1,
	1 << 27,
	1<<34 - 1,
	1 << 34,
}

var benchVaruint64LargeValues = []uint64{
	1<<40 - 1,
	1 << 40,
	1<<55 - 1,
	1 << 55,
	1<<63 - 1,
	^uint64(0),
}

var benchVaruint32SmallValues = []uint32{
	0,
	1,
	2,
	3,
	7,
	15,
	31,
	63,
	127,
}

var benchVaruint32MidValues = []uint32{
	128,
	129,
	16383,
	16384,
	1<<20 - 1,
	1 << 20,
	1<<27 - 1,
	1 << 27,
}

var benchVaruint32LargeValues = []uint32{
	1<<29 - 1,
	1 << 29,
	1<<31 - 1,
	^uint32(0),
}

var benchVaruint36SmallValues = []uint64{
	0,
	1,
	127,
	128,
	16383,
	16384,
	1<<20 - 1,
	1 << 20,
}

var benchVaruint36MidValues = []uint64{
	1<<27 - 1,
	1 << 27,
	1<<34 - 1,
	1 << 34,
}

var benchVaruint36LargeValues = []uint64{
	1<<35 - 1,
	1<<35 + 123,
	1<<36 - 1,
}

func writeVaruint32Loop(buf *ByteBuffer, value uint32) int8 {
	buf.grow(5)
	offset := buf.writerIndex
	data := buf.data[offset : offset+5]
	i := 0
	for value >= 0x80 {
		data[i] = byte(value&0x7F) | 0x80
		value >>= 7
		i++
	}
	data[i] = byte(value)
	i++
	buf.writerIndex += i
	return int8(i)
}

func writeVaruint32Unrolled(buf *ByteBuffer, value uint32) int8 {
	buf.grow(5)
	return buf.UnsafeWriteVaruint32(value)
}

func writeVaruint36SmallLoop(buf *ByteBuffer, value uint64) {
	buf.grow(5)
	offset := buf.writerIndex
	data := buf.data[offset : offset+5]
	i := 0
	for i < 4 && value >= 0x80 {
		data[i] = byte(value&0x7F) | 0x80
		value >>= 7
		i++
	}
	if i < 4 {
		data[i] = byte(value)
		buf.writerIndex += i + 1
		return
	}
	data[4] = byte(value)
	buf.writerIndex += 5
}

func writeVaruint36SmallUnrolled(buf *ByteBuffer, value uint64) {
	buf.WriteVaruint36Small(value)
}

func BenchmarkWriteVaruint64Loop(b *testing.B) {
	buf := NewByteBuffer(make([]byte, 0, 1024))
	values := benchVaruint64Values
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.writerIndex = 0
		buf.WriteVaruint64(values[i%len(values)])
	}
}

func BenchmarkWriteVaruint64LoopSmall(b *testing.B) {
	buf := NewByteBuffer(make([]byte, 0, 1024))
	values := benchVaruint64SmallValues
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.writerIndex = 0
		buf.WriteVaruint64(values[i%len(values)])
	}
}

func BenchmarkWriteVaruint64LoopMid(b *testing.B) {
	buf := NewByteBuffer(make([]byte, 0, 1024))
	values := benchVaruint64MidValues
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.writerIndex = 0
		buf.WriteVaruint64(values[i%len(values)])
	}
}

func BenchmarkWriteVaruint64LoopLarge(b *testing.B) {
	buf := NewByteBuffer(make([]byte, 0, 1024))
	values := benchVaruint64LargeValues
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.writerIndex = 0
		buf.WriteVaruint64(values[i%len(values)])
	}
}

func BenchmarkWriteVaruint32LoopSmall(b *testing.B) {
	buf := NewByteBuffer(make([]byte, 0, 1024))
	values := benchVaruint32SmallValues
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.writerIndex = 0
		writeVaruint32Loop(buf, values[i%len(values)])
	}
}

func BenchmarkWriteVaruint32UnrolledSmall(b *testing.B) {
	buf := NewByteBuffer(make([]byte, 0, 1024))
	values := benchVaruint32SmallValues
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.writerIndex = 0
		writeVaruint32Unrolled(buf, values[i%len(values)])
	}
}

func BenchmarkWriteVaruint32LoopMid(b *testing.B) {
	buf := NewByteBuffer(make([]byte, 0, 1024))
	values := benchVaruint32MidValues
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.writerIndex = 0
		writeVaruint32Loop(buf, values[i%len(values)])
	}
}

func BenchmarkWriteVaruint32UnrolledMid(b *testing.B) {
	buf := NewByteBuffer(make([]byte, 0, 1024))
	values := benchVaruint32MidValues
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.writerIndex = 0
		writeVaruint32Unrolled(buf, values[i%len(values)])
	}
}

func BenchmarkWriteVaruint32LoopLarge(b *testing.B) {
	buf := NewByteBuffer(make([]byte, 0, 1024))
	values := benchVaruint32LargeValues
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.writerIndex = 0
		writeVaruint32Loop(buf, values[i%len(values)])
	}
}

func BenchmarkWriteVaruint32UnrolledLarge(b *testing.B) {
	buf := NewByteBuffer(make([]byte, 0, 1024))
	values := benchVaruint32LargeValues
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.writerIndex = 0
		writeVaruint32Unrolled(buf, values[i%len(values)])
	}
}

func BenchmarkWriteVaruint36SmallLoopSmall(b *testing.B) {
	buf := NewByteBuffer(make([]byte, 0, 1024))
	values := benchVaruint36SmallValues
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.writerIndex = 0
		writeVaruint36SmallLoop(buf, values[i%len(values)])
	}
}

func BenchmarkWriteVaruint36SmallUnrolledSmall(b *testing.B) {
	buf := NewByteBuffer(make([]byte, 0, 1024))
	values := benchVaruint36SmallValues
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.writerIndex = 0
		writeVaruint36SmallUnrolled(buf, values[i%len(values)])
	}
}

func BenchmarkWriteVaruint36SmallLoopMid(b *testing.B) {
	buf := NewByteBuffer(make([]byte, 0, 1024))
	values := benchVaruint36MidValues
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.writerIndex = 0
		writeVaruint36SmallLoop(buf, values[i%len(values)])
	}
}

func BenchmarkWriteVaruint36SmallUnrolledMid(b *testing.B) {
	buf := NewByteBuffer(make([]byte, 0, 1024))
	values := benchVaruint36MidValues
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.writerIndex = 0
		writeVaruint36SmallUnrolled(buf, values[i%len(values)])
	}
}

func BenchmarkWriteVaruint36SmallLoopLarge(b *testing.B) {
	buf := NewByteBuffer(make([]byte, 0, 1024))
	values := benchVaruint36LargeValues
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.writerIndex = 0
		writeVaruint36SmallLoop(buf, values[i%len(values)])
	}
}

func BenchmarkWriteVaruint36SmallUnrolledLarge(b *testing.B) {
	buf := NewByteBuffer(make([]byte, 0, 1024))
	values := benchVaruint36LargeValues
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.writerIndex = 0
		writeVaruint36SmallUnrolled(buf, values[i%len(values)])
	}
}
