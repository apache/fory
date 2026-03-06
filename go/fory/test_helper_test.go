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
	"testing"
)

// oneByteReader returns data byte by byte to ensure aggressively that all
// `fill()` boundaries, loops, and buffering conditions are tested.
type oneByteReader struct {
	data []byte
	pos  int
}

func (r *oneByteReader) Read(p []byte) (n int, err error) {
	if r.pos >= len(r.data) {
		return 0, io.EOF
	}
	if len(p) == 0 {
		return 0, nil
	}
	p[0] = r.data[r.pos]
	r.pos++
	return 1, nil
}

// testDeserialize is a testing helper that performs standard in-memory
// deserialization and additionally wraps the payload in a slow one-byte
// stream reader to verify that stream decoding handles fragmented reads correctly.
func testDeserialize(t *testing.T, f *Fory, data []byte, v any) error {
	t.Helper()

	// 1. First, deserialize from bytes (the fast path)
	err := f.Deserialize(data, v)
	if err != nil {
		return err
	}

	// 2. Deserialize from oneByteReader (the slow stream path)
	// We create a new instance of the same target type to ensure clean state
	vType := reflect.TypeOf(v)
	if vType == nil || vType.Kind() != reflect.Ptr {
		t.Fatalf("testDeserialize requires a pointer to a value, got %v", vType)
	}

	freshV := reflect.New(vType.Elem()).Interface()

	stream := &oneByteReader{data: data, pos: 0}

	// Create a new stream reader. The stream context handles boundaries and compactions.
	streamReader := NewInputStream(stream)
	err = f.DeserializeFromStream(streamReader, freshV)
	if err != nil {
		t.Fatalf("Stream deserialization via OneByteStream failed: %v", err)
	}

	// 3. Compare the two results
	if !reflect.DeepEqual(v, freshV) {
		t.Fatalf("Stream deserialization mismatched byte deserialization.\nExpected: %+v\nGot:      %+v", v, freshV)
	}

	// Returns the original error from standard deserialization
	return err
}
