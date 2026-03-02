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
	"io"
	"testing"
)

type StreamTestStruct struct {
	ID   int32
	Name string
	Data []byte
}

func TestStreamDeserialization(t *testing.T) {
	f := New()
	f.RegisterStruct(&StreamTestStruct{}, 100)

	original := &StreamTestStruct{
		ID:   42,
		Name: "Stream Test",
		Data: []byte{1, 2, 3, 4, 5},
	}

	data, err := f.Serialize(original)
	if err != nil {
		t.Fatalf("Serialize failed: %v", err)
	}

	// 1. Test normal reader
	reader := bytes.NewReader(data)
	var decoded StreamTestStruct
	err = f.DeserializeFromReader(reader, &decoded)
	if err != nil {
		t.Fatalf("DeserializeFromReader failed: %v", err)
	}

	if decoded.ID != original.ID || decoded.Name != original.Name || !bytes.Equal(decoded.Data, original.Data) {
		t.Errorf("Decoded value mismatch. Got: %+v, Want: %+v", decoded, original)
	}
}

// slowReader returns data byte by byte to test fill() logic and compaction
type slowReader struct {
	data []byte
	pos  int
}

func (r *slowReader) Read(p []byte) (n int, err error) {
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

func TestStreamDeserializationSlow(t *testing.T) {
	f := New()
	f.RegisterStruct(&StreamTestStruct{}, 100)

	original := &StreamTestStruct{
		ID:   42,
		Name: "Slow Stream Test with a reasonably long string and some data to trigger multiple fills",
		Data: bytes.Repeat([]byte{0xAA}, 100),
	}

	data, err := f.Serialize(original)
	if err != nil {
		t.Fatalf("Serialize failed: %v", err)
	}

	// Test with slow reader and small minCap to force compaction/growth
	reader := &slowReader{data: data}
	var decoded StreamTestStruct
	// Use small minCap (16) to force frequent fills and compactions
	f.readCtx.buffer.ResetWithReader(reader, 16)

	err = f.DeserializeFromReader(reader, &decoded)
	if err != nil {
		t.Fatalf("DeserializeFromReader (slow) failed: %v", err)
	}

	if decoded.ID != original.ID || decoded.Name != original.Name || !bytes.Equal(decoded.Data, original.Data) {
		t.Errorf("Decoded value mismatch (slow). Got: %+v, Want: %+v", decoded, original)
	}
}

func TestStreamDeserializationEOF(t *testing.T) {
	f := New()
	f.RegisterStruct(&StreamTestStruct{}, 100)

	original := &StreamTestStruct{
		ID:   42,
		Name: "EOF Test",
	}

	data, err := f.Serialize(original)
	if err != nil {
		t.Fatalf("Serialize failed: %v", err)
	}

	// Truncate data to cause unexpected EOF during reading Name
	truncated := data[:len(data)-2]
	reader := bytes.NewReader(truncated)
	var decoded StreamTestStruct
	err = f.DeserializeFromReader(reader, &decoded)
	if err == nil {
		t.Fatal("Expected error on truncated stream, got nil")
	}

	// Ideally it should be a BufferOutOfBoundError
	if _, ok := err.(Error); !ok {
		t.Errorf("Expected fory.Error, got %T: %v", err, err)
	}
}

func TestStreamReaderSequential(t *testing.T) {
	f := New()
	// Register type in compatible mode to test Meta Sharing across sequential reads
	f.config.Compatible = true
	f.RegisterStruct(&StreamTestStruct{}, 100)

	msg1 := &StreamTestStruct{ID: 1, Name: "Msg 1", Data: []byte{1, 1}}
	msg2 := &StreamTestStruct{ID: 2, Name: "Msg 2", Data: []byte{2, 2}}
	msg3 := &StreamTestStruct{ID: 3, Name: "Msg 3", Data: []byte{3, 3}}

	var buf bytes.Buffer

	// Serialize sequentially into one stream
	data1, _ := f.Serialize(msg1)
	buf.Write(data1)
	data2, _ := f.Serialize(msg2)
	buf.Write(data2)
	data3, _ := f.Serialize(msg3)
	buf.Write(data3)

	fDec := New()
	fDec.config.Compatible = true
	fDec.RegisterStruct(&StreamTestStruct{}, 100)

	// Create a StreamReader
	sr := fDec.NewStreamReader(&buf)

	// Deserialize sequentially
	var out1, out2, out3 StreamTestStruct

	err := sr.Deserialize(&out1)
	if err != nil {
		t.Fatalf("Deserialize 1 failed: %v", err)
	}
	if out1.ID != msg1.ID || out1.Name != msg1.Name || !bytes.Equal(out1.Data, msg1.Data) {
		t.Errorf("Msg 1 mismatch. Got: %+v, Want: %+v", out1, msg1)
	}

	err = sr.Deserialize(&out2)
	if err != nil {
		t.Fatalf("Deserialize 2 failed: %v", err)
	}
	if out2.ID != msg2.ID || out2.Name != msg2.Name || !bytes.Equal(out2.Data, msg2.Data) {
		t.Errorf("Msg 2 mismatch. Got: %+v, Want: %+v", out2, msg2)
	}

	err = sr.Deserialize(&out3)
	if err != nil {
		t.Fatalf("Deserialize 3 failed: %v", err)
	}
	if out3.ID != msg3.ID || out3.Name != msg3.Name || !bytes.Equal(out3.Data, msg3.Data) {
		t.Errorf("Msg 3 mismatch. Got: %+v, Want: %+v", out3, msg3)
	}
}
