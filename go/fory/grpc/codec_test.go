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

package forygrpc

import (
	"testing"

	"github.com/apache/fory/go/fory"
	"google.golang.org/grpc/mem"
)

// testMessage is a simple struct registered with Fory for use across all codec tests.
type testMessage struct {
	Name  string
	Value int32
}

// newTestFory creates a Fory instance with testMessage registered under type ID 200.
func newTestFory(t *testing.T) *fory.Fory {
	t.Helper()
	f := fory.New(fory.WithXlang(false))
	if err := f.RegisterStruct(testMessage{}, 200); err != nil {
		t.Fatalf("RegisterStruct: %v", err)
	}
	return f
}

// TestRoundTrip verifies that a message marshaled by CodecV2 can be fully
// recovered by Unmarshal with all fields intact.
func TestRoundTrip(t *testing.T) {
	codec := CodecV2{Fory: newTestFory(t)}
	original := &testMessage{Name: "hello", Value: 42}

	buf, err := codec.Marshal(original)
	if err != nil {
		t.Fatalf("Marshal: %v", err)
	}

	got := &testMessage{}
	if err := codec.Unmarshal(buf, got); err != nil {
		t.Fatalf("Unmarshal: %v", err)
	}

	if got.Name != original.Name || got.Value != original.Value {
		t.Errorf("round-trip mismatch: got %+v, want %+v", got, original)
	}
}

// TestUnmarshalError verifies that Unmarshal returns an error on corrupt input
// rather than silently producing a zero-value result.
func TestUnmarshalError(t *testing.T) {
	codec := CodecV2{Fory: newTestFory(t)}
	garbage := []byte{0xFF, 0xFE, 0x00, 0x01}
	data := mem.BufferSlice{mem.NewBuffer(&garbage, nil)}
	if err := codec.Unmarshal(data, &testMessage{}); err == nil {
		t.Error("expected error unmarshaling corrupt data, got nil")
	}
}

// TestName verifies the codec identifier matches the value used in grpc.ForceCodecV2 calls.
// TestMarshalBufferReuse verifies that consecutive Marshal calls return
// independent byte slices. Fory reuses its internal write buffer across
// calls, so the codec must copy the bytes before returning them to gRPC,
// which may buffer multiple frames before transmitting.
func TestMarshalBufferReuse(t *testing.T) {
	codec := CodecV2{Fory: newTestFory(t)}
	msgs := []*testMessage{
		{Name: "first", Value: 1},
		{Name: "second", Value: 2},
		{Name: "third", Value: 3},
	}
	bufs := make([]mem.BufferSlice, len(msgs))
	for i, m := range msgs {
		buf, err := codec.Marshal(m)
		if err != nil {
			t.Fatalf("Marshal[%d]: %v", i, err)
		}
		bufs[i] = buf
	}
	for i, buf := range bufs {
		got := &testMessage{}
		if err := codec.Unmarshal(buf, got); err != nil {
			t.Fatalf("Unmarshal[%d]: %v", i, err)
		}
		if *got != *msgs[i] {
			t.Errorf("buf[%d]: got %+v, want %+v", i, got, msgs[i])
		}
	}
}

func TestName(t *testing.T) {
	if name := (CodecV2{}).Name(); name != "fory" {
		t.Errorf("Name() = %q, want %q", name, "fory")
	}
}
