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
