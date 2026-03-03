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

// StreamReader supports robust sequential deserialization from a stream.
// It maintains the ByteBuffer and ReadContext state across multiple Deserialize calls,
// preventing data loss from prefetched buffers and preserving TypeResolver metadata
// (Meta Sharing) across object boundaries.
type StreamReader struct {
	fory   *Fory
	reader io.Reader
	buffer *ByteBuffer
}

// NewStreamReader creates a new StreamReader that reads from the provided io.Reader.
// The StreamReader owns the buffer and maintains state across sequential Deserialize calls.
func (f *Fory) NewStreamReader(r io.Reader) *StreamReader {
	return f.NewStreamReaderWithMinCap(r, 0)
}

// NewStreamReaderWithMinCap creates a new StreamReader with a specified minimum buffer capacity.
func (f *Fory) NewStreamReaderWithMinCap(r io.Reader, minCap int) *StreamReader {
	buf := NewByteBufferFromReader(r, minCap)
	return &StreamReader{
		fory:   f,
		reader: r,
		buffer: buf,
	}
}

// Deserialize reads the next object from the stream into the provided value.
// It uses a shared ReadContext for the lifetime of the StreamReader, clearing
// temporary state between calls but preserving the buffer and TypeResolver state.
func (sr *StreamReader) Deserialize(v any) error {
	f := sr.fory

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
	f.readCtx.buffer = sr.buffer

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

// For Sequential Streaming use NewStreamReader instead of DeserializeFromReader.
// DeserializeFromReader deserializes a single object from a stream but will discard prefetched data
// and type metadata after the call.
func (f *Fory) DeserializeFromReader(r io.Reader, v any) error {
	defer f.resetReadState()
	if f.readCtx.buffer.reader != r {
		f.readCtx.buffer.ResetWithReader(r, 0)
	}

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
