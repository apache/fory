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
	"io"
	"reflect"
)

// InputStream supports robust sequential deserialization from a stream.
// It maintains the ByteBuffer and ReadContext state across multiple Deserialize calls,
// preventing data loss from prefetched buffers and preserving TypeResolver metadata
// (Meta Sharing) across object boundaries.
type InputStream struct {
	buffer *ByteBuffer
}

// NewInputStream creates a new InputStream that reads from the provided io.Reader.
// The InputStream owns the buffer and maintains state across sequential Deserialize calls.
func NewInputStream(r io.Reader) *InputStream {
	return NewInputStreamWithBufferSize(r, 0)
}

// NewInputStreamWithBufferSize creates a new InputStream with a specified minimum buffer size.
func NewInputStreamWithBufferSize(r io.Reader, bufferSize int) *InputStream {
	buf := NewByteBufferFromReader(r, bufferSize)
	return &InputStream{
		buffer: buf,
	}
}

// Shrink compacts the internal buffer, dropping already-read bytes to reclaim memory.
// It applies a heuristic to avoid tiny frequent compactions and reallocates the backing
// slice if the capacity becomes excessively large compared to the remaining data.
func (is *InputStream) Shrink() {
	b := is.buffer
	if b == nil {
		return
	}

	readPos := b.readerIndex
	// Best-effort policy: keep a 4096-byte floor to avoid tiny frequent compactions
	if readPos <= 4096 || readPos < b.bufferSize {
		return
	}

	remaining := b.writerIndex - readPos
	currentCapacity := cap(b.data)
	targetCapacity := currentCapacity

	if currentCapacity > b.bufferSize {
		if remaining == 0 {
			targetCapacity = b.bufferSize
		} else if remaining <= currentCapacity/4 {
			doubled := remaining * 2
			targetCapacity = doubled
			if targetCapacity < b.bufferSize {
				targetCapacity = b.bufferSize
			}
		}
	}

	if targetCapacity < currentCapacity {
		// Actually reclaim memory by copying to a new, smaller slice
		newData := make([]byte, remaining, targetCapacity)
		copy(newData, b.data[readPos:b.writerIndex])
		b.data = newData
		b.writerIndex = remaining
		b.readerIndex = 0
	} else if readPos > 0 {
		// Just compact without reallocating
		copy(b.data, b.data[readPos:b.writerIndex])
		b.writerIndex = remaining
		b.readerIndex = 0
		b.data = b.data[:remaining]
	}
}

// DeserializeFromStream reads the next object from the stream into the provided value.
// It uses a shared ReadContext for the lifetime of the InputStream, clearing
// temporary state between calls but preserving the buffer and TypeResolver state.
func (f *Fory) DeserializeFromStream(is *InputStream, v any) error {

	// We only reset the temporary read state (like refTracker and outOfBand buffers),
	// NOT the buffer or the type mapping, which must persist.
	defer func() {
		f.readCtx.refReader.Reset()
		f.readCtx.outOfBandBuffers = nil
		f.readCtx.outOfBandIndex = 0
		f.readCtx.err = Error{}
		if f.readCtx.refResolver != nil {
			f.readCtx.refResolver.resetRead()
		}
	}()

	// Temporarily swap buffer
	origBuffer := f.readCtx.buffer
	f.readCtx.buffer = is.buffer

	isNull := readHeader(f.readCtx)
	if f.readCtx.HasError() {
		f.readCtx.buffer = origBuffer
		return f.readCtx.TakeError()
	}

	if isNull {
		f.readCtx.buffer = origBuffer
		return nil
	}

	target := reflect.ValueOf(v).Elem()
	f.readCtx.ReadValue(target, RefModeTracking, true)
	if f.readCtx.HasError() {
		f.readCtx.buffer = origBuffer
		return f.readCtx.TakeError()
	}

	// Restore original buffer
	f.readCtx.buffer = origBuffer

	return nil
}

// DeserializeFromReader deserializes a single object from a stream.
// It is strictly stateless: the buffer and all read state are always reset before
// each call, discarding any prefetched data and type metadata.
// For sequential multi-object reads on the same stream, use NewInputStream instead.
func (f *Fory) DeserializeFromReader(r io.Reader, v any) error {
	defer f.resetReadState()
	// Always reset to enforce stateless semantics.
	f.readCtx.buffer.ResetWithReader(r, 0)

	isNull := readHeader(f.readCtx)
	if f.readCtx.HasError() {
		return f.readCtx.TakeError()
	}

	if isNull {
		return nil
	}

	target := reflect.ValueOf(v).Elem()
	f.readCtx.ReadValue(target, RefModeTracking, true)
	if f.readCtx.HasError() {
		return f.readCtx.TakeError()
	}

	return nil
}
