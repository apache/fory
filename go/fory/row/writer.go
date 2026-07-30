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

package row

import (
	"encoding/binary"
	"fmt"
	"math"
	"time"

	fory "github.com/apache/fory/go/fory"
)

const maxArrayDataBytes = math.MaxInt32 - 15

// RowWriter writes one row into a ByteBuffer: null bitmap, fixed 8-byte
// field slots, then the variable data region. All slot and bitmap
// offsets are relative to the row base, so a nested RowWriter can share
// the parent's buffer and produce a self-contained row.
//
// Reset rebases the writer at the buffer's current writer index and must
// be called before writing each row. Writers are not goroutine-safe.
type RowWriter struct {
	schema      *Schema
	buf         *fory.ByteBuffer
	base        int
	numFields   int
	bitmapWidth int
	fixedSize   int
}

func NewRowWriter(schema *Schema) *RowWriter {
	return NewRowWriterWithBuffer(schema, fory.NewByteBuffer(nil))
}

// NewRowWriterWithBuffer shares an existing buffer, e.g. to write a
// nested struct row into its parent's variable data region.
func NewRowWriterWithBuffer(schema *Schema, buf *fory.ByteBuffer) *RowWriter {
	n := schema.NumFields()
	bitmapWidth := bitmapWidthInBytes(n)
	return &RowWriter{
		schema:      schema,
		buf:         buf,
		numFields:   n,
		bitmapWidth: bitmapWidth,
		fixedSize:   bitmapWidth + n*8,
	}
}

func (w *RowWriter) Schema() *Schema          { return w.schema }
func (w *RowWriter) Buffer() *fory.ByteBuffer { return w.buf }

// Reset rebases the writer at the current writer index and zeroes the
// whole fixed region. The buffer reuses dirty capacity after shrinking,
// so zeroing here is what makes null slots and padding deterministic;
// it also lets narrow fixed-width writes store just the value.
func (w *RowWriter) Reset() {
	w.base = w.buf.WriterIndex()
	w.buf.Reserve(w.fixedSize)
	data := w.buf.GetData()
	clear(data[w.base : w.base+w.fixedSize])
	w.buf.SetWriterIndex(w.base + w.fixedSize)
}

// Size returns the bytes written for this row so far.
func (w *RowWriter) Size() int { return w.buf.WriterIndex() - w.base }

// ToBytes returns a view of the row bytes; it stays valid only until the
// buffer is written to or reset again.
func (w *RowWriter) ToBytes() []byte {
	return w.buf.GetByteSlice(w.base, w.buf.WriterIndex())
}

func (w *RowWriter) slot(i int) int {
	if uint(i) >= uint(w.numFields) {
		panic(fmt.Sprintf("row: field index %d out of range [0, %d)", i, w.numFields))
	}
	return w.base + w.bitmapWidth + i*8
}

// SetNullAt marks field i null and zeroes its slot.
func (w *RowWriter) SetNullAt(i int) {
	slot := w.slot(i)
	data := w.buf.GetData()
	setBit(data[w.base:w.base+w.bitmapWidth], i)
	binary.LittleEndian.PutUint64(data[slot:], 0)
}

func (w *RowWriter) SetNotNullAt(i int) {
	w.slot(i) // bounds check
	clearBit(w.buf.GetData()[w.base:w.base+w.bitmapWidth], i)
}

func (w *RowWriter) WriteBool(i int, v bool) {
	slot := w.slot(i)
	if v {
		w.buf.GetData()[slot] = 1
	} else {
		w.buf.GetData()[slot] = 0
	}
}

func (w *RowWriter) WriteInt8(i int, v int8) {
	w.buf.GetData()[w.slot(i)] = byte(v)
}

func (w *RowWriter) WriteInt16(i int, v int16) {
	binary.LittleEndian.PutUint16(w.buf.GetData()[w.slot(i):], uint16(v))
}

func (w *RowWriter) WriteInt32(i int, v int32) {
	binary.LittleEndian.PutUint32(w.buf.GetData()[w.slot(i):], uint32(v))
}

func (w *RowWriter) WriteInt64(i int, v int64) {
	binary.LittleEndian.PutUint64(w.buf.GetData()[w.slot(i):], uint64(v))
}

func (w *RowWriter) WriteFloat32(i int, v float32) {
	binary.LittleEndian.PutUint32(w.buf.GetData()[w.slot(i):], math.Float32bits(v))
}

func (w *RowWriter) WriteFloat64(i int, v float64) {
	binary.LittleEndian.PutUint64(w.buf.GetData()[w.slot(i):], math.Float64bits(v))
}

func (w *RowWriter) WriteDate(i int, d fory.Date) error {
	days, err := fory.DateToEpochDay(d)
	if err != nil {
		return err
	}
	if days < math.MinInt32 || days > math.MaxInt32 {
		return fmt.Errorf("row: date %v out of date32 range", d)
	}
	w.WriteInt32(i, int32(days))
	return nil
}

func (w *RowWriter) WriteTimestamp(i int, t time.Time) {
	w.WriteInt64(i, t.UnixMicro())
}

func (w *RowWriter) WriteDuration(i int, d time.Duration) {
	w.WriteInt64(i, d.Microseconds())
}

func (w *RowWriter) WriteString(i int, s string) {
	start := appendStringRegion(w.buf, s)
	w.SetOffsetAndSize(i, start, len(s))
}

func (w *RowWriter) WriteBytes(i int, b []byte) {
	start := appendBytesRegion(w.buf, b)
	w.SetOffsetAndSize(i, start, len(b))
}

// SetOffsetAndSize patches field i's slot with the row-relative offset
// and byte size of a value already appended to the variable data region.
// Use it after writing a nested struct, array, or map at absStart.
func (w *RowWriter) SetOffsetAndSize(i, absStart, size int) {
	rel := absStart - w.base
	binary.LittleEndian.PutUint64(w.buf.GetData()[w.slot(i):], uint64(rel)<<32|uint64(uint32(size)))
}

// ArrayWriter writes one array: an 8-byte element count, a null bitmap,
// then elements at their natural width (variable-width elements use
// 8-byte offset+size slots). Offsets are relative to the array base.
type ArrayWriter struct {
	elem        Field
	buf         *fory.ByteBuffer
	base        int
	elemSize    int
	numElements int
	headerBytes int
}

func NewArrayWriter(elem Field, buf *fory.ByteBuffer) *ArrayWriter {
	elemSize := elem.Type.ByteWidth()
	if elemSize < 0 {
		elemSize = 8
	}
	return &ArrayWriter{elem: elem, buf: buf, elemSize: elemSize}
}

func (w *ArrayWriter) Buffer() *fory.ByteBuffer { return w.buf }

// Reset rebases the writer at the current writer index and writes the
// array header plus a zeroed element region for numElements elements.
func (w *ArrayWriter) Reset(numElements int) error {
	if numElements < 0 {
		return fmt.Errorf("row: negative array length %d", numElements)
	}
	dataBytes := int64(numElements) * int64(w.elemSize)
	if dataBytes > maxArrayDataBytes {
		return fmt.Errorf("row: array of %d elements exceeds maximum size", numElements)
	}
	headerBytes := 8 + bitmapWidthInBytes(numElements)
	total := headerBytes + roundToWord(int(dataBytes))
	base := w.buf.WriterIndex()
	w.buf.Reserve(total)
	data := w.buf.GetData()
	binary.LittleEndian.PutUint64(data[base:], uint64(numElements))
	clear(data[base+8 : base+total])
	w.buf.SetWriterIndex(base + total)
	w.base, w.numElements, w.headerBytes = base, numElements, headerBytes
	return nil
}

func (w *ArrayWriter) Size() int { return w.buf.WriterIndex() - w.base }

func (w *ArrayWriter) ToBytes() []byte {
	return w.buf.GetByteSlice(w.base, w.buf.WriterIndex())
}

func (w *ArrayWriter) slot(i int) int {
	if uint(i) >= uint(w.numElements) {
		panic(fmt.Sprintf("row: array index %d out of range [0, %d)", i, w.numElements))
	}
	return w.base + w.headerBytes + i*w.elemSize
}

// SetNullAt marks element i null and re-zeroes its element bytes.
func (w *ArrayWriter) SetNullAt(i int) {
	slot := w.slot(i)
	data := w.buf.GetData()
	setBit(data[w.base+8:w.base+w.headerBytes], i)
	clear(data[slot : slot+w.elemSize])
}

func (w *ArrayWriter) WriteBool(i int, v bool) {
	slot := w.slot(i)
	if v {
		w.buf.GetData()[slot] = 1
	} else {
		w.buf.GetData()[slot] = 0
	}
}

func (w *ArrayWriter) WriteInt8(i int, v int8) {
	w.buf.GetData()[w.slot(i)] = byte(v)
}

func (w *ArrayWriter) WriteInt16(i int, v int16) {
	binary.LittleEndian.PutUint16(w.buf.GetData()[w.slot(i):], uint16(v))
}

func (w *ArrayWriter) WriteInt32(i int, v int32) {
	binary.LittleEndian.PutUint32(w.buf.GetData()[w.slot(i):], uint32(v))
}

func (w *ArrayWriter) WriteInt64(i int, v int64) {
	binary.LittleEndian.PutUint64(w.buf.GetData()[w.slot(i):], uint64(v))
}

func (w *ArrayWriter) WriteFloat32(i int, v float32) {
	binary.LittleEndian.PutUint32(w.buf.GetData()[w.slot(i):], math.Float32bits(v))
}

func (w *ArrayWriter) WriteFloat64(i int, v float64) {
	binary.LittleEndian.PutUint64(w.buf.GetData()[w.slot(i):], math.Float64bits(v))
}

func (w *ArrayWriter) WriteDate(i int, d fory.Date) error {
	days, err := fory.DateToEpochDay(d)
	if err != nil {
		return err
	}
	if days < math.MinInt32 || days > math.MaxInt32 {
		return fmt.Errorf("row: date %v out of date32 range", d)
	}
	w.WriteInt32(i, int32(days))
	return nil
}

func (w *ArrayWriter) WriteTimestamp(i int, t time.Time) {
	w.WriteInt64(i, t.UnixMicro())
}

func (w *ArrayWriter) WriteDuration(i int, d time.Duration) {
	w.WriteInt64(i, d.Microseconds())
}

func (w *ArrayWriter) WriteString(i int, s string) {
	start := appendStringRegion(w.buf, s)
	w.SetOffsetAndSize(i, start, len(s))
}

func (w *ArrayWriter) WriteBytes(i int, b []byte) {
	start := appendBytesRegion(w.buf, b)
	w.SetOffsetAndSize(i, start, len(b))
}

// SetOffsetAndSize patches element i's slot with the array-relative
// offset and byte size of a value already appended after the array.
func (w *ArrayWriter) SetOffsetAndSize(i, absStart, size int) {
	rel := absStart - w.base
	binary.LittleEndian.PutUint64(w.buf.GetData()[w.slot(i):], uint64(rel)<<32|uint64(uint32(size)))
}

// MapWriter writes one map: an 8-byte keys-array size, the keys array,
// then the values array. Write flow: Reset, write the keys with an
// ArrayWriter, FinishKeys, write the values with another ArrayWriter.
type MapWriter struct {
	buf  *fory.ByteBuffer
	base int
}

func NewMapWriter(buf *fory.ByteBuffer) *MapWriter {
	return &MapWriter{buf: buf}
}

// Reset rebases the writer at the current writer index and reserves the
// keys-array size word, which FinishKeys patches later.
func (w *MapWriter) Reset() {
	w.base = w.buf.WriterIndex()
	w.buf.Reserve(8)
	data := w.buf.GetData()
	clear(data[w.base : w.base+8])
	w.buf.SetWriterIndex(w.base + 8)
}

// FinishKeys records the keys array size; call it after the keys array
// is fully written and before starting the values array.
func (w *MapWriter) FinishKeys() {
	keysSize := w.buf.WriterIndex() - w.base - 8
	binary.LittleEndian.PutUint64(w.buf.GetData()[w.base:], uint64(keysSize))
}

func (w *MapWriter) Size() int { return w.buf.WriterIndex() - w.base }

// appendBytesRegion appends b to the variable data region, zero-padded
// to an 8-byte boundary, and returns its buffer offset.
func appendBytesRegion(buf *fory.ByteBuffer, b []byte) int {
	n := len(b)
	rounded := roundToWord(n)
	start := buf.WriterIndex()
	buf.Reserve(rounded)
	data := buf.GetData()
	clear(data[start+n : start+rounded])
	copy(data[start:], b)
	buf.SetWriterIndex(start + rounded)
	return start
}

func appendStringRegion(buf *fory.ByteBuffer, s string) int {
	n := len(s)
	rounded := roundToWord(n)
	start := buf.WriterIndex()
	buf.Reserve(rounded)
	data := buf.GetData()
	clear(data[start+n : start+rounded])
	copy(data[start:], s)
	buf.SetWriterIndex(start + rounded)
	return start
}
