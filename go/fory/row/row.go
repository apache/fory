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

// Row is a zero-copy reader over one row's bytes. Getters read directly
// from the underlying data without deserializing other fields; nested
// values are sub-slice views, never copies (except String).
//
// Row and its nested views require trusted, schema-matched bytes: the
// row format is a trusted in-memory format and these views do not
// validate the row graph. Fixed-width getters return the zero value for
// null fields; use IsNullAt to distinguish. Variable-width getters
// return nil (or "") for null fields. Out-of-range indexes and
// malformed bytes panic.
type Row struct {
	fields      []Field
	data        []byte
	bitmapWidth int
}

func NewRow(schema *Schema, data []byte) *Row {
	return newRow(schema.Fields(), data)
}

func newRow(fields []Field, data []byte) *Row {
	return &Row{fields: fields, data: data, bitmapWidth: bitmapWidthInBytes(len(fields))}
}

func (r *Row) NumFields() int { return len(r.fields) }
func (r *Row) SizeBytes() int { return len(r.data) }

// Data returns the underlying row bytes; callers must not modify them.
func (r *Row) Data() []byte { return r.data }

func (r *Row) IsNullAt(i int) bool {
	if uint(i) >= uint(len(r.fields)) {
		panic(fmt.Sprintf("row: field index %d out of range [0, %d)", i, len(r.fields)))
	}
	return getBit(r.data, i)
}

func (r *Row) slot(i int) int { return r.bitmapWidth + i*8 }

func (r *Row) Bool(i int) bool {
	return !r.IsNullAt(i) && r.data[r.slot(i)] != 0
}

func (r *Row) Int8(i int) int8 {
	if r.IsNullAt(i) {
		return 0
	}
	return int8(r.data[r.slot(i)])
}

func (r *Row) Int16(i int) int16 {
	if r.IsNullAt(i) {
		return 0
	}
	return int16(binary.LittleEndian.Uint16(r.data[r.slot(i):]))
}

func (r *Row) Int32(i int) int32 {
	if r.IsNullAt(i) {
		return 0
	}
	return int32(binary.LittleEndian.Uint32(r.data[r.slot(i):]))
}

func (r *Row) Int64(i int) int64 {
	if r.IsNullAt(i) {
		return 0
	}
	return int64(binary.LittleEndian.Uint64(r.data[r.slot(i):]))
}

func (r *Row) Float32(i int) float32 {
	if r.IsNullAt(i) {
		return 0
	}
	return math.Float32frombits(binary.LittleEndian.Uint32(r.data[r.slot(i):]))
}

func (r *Row) Float64(i int) float64 {
	if r.IsNullAt(i) {
		return 0
	}
	return math.Float64frombits(binary.LittleEndian.Uint64(r.data[r.slot(i):]))
}

func (r *Row) Date(i int) fory.Date {
	if r.IsNullAt(i) {
		return fory.Date{}
	}
	return dateFromDays(int32(binary.LittleEndian.Uint32(r.data[r.slot(i):])))
}

func (r *Row) Timestamp(i int) time.Time {
	if r.IsNullAt(i) {
		return time.Time{}
	}
	return time.UnixMicro(r.Int64(i))
}

func (r *Row) Duration(i int) time.Duration {
	return durationFromMicros(r.Int64(i))
}

// varData returns the value bytes of variable-width field i, or nil if
// the field is null. An empty value returns an empty non-nil slice.
func (r *Row) varData(i int) []byte {
	if r.IsNullAt(i) {
		return nil
	}
	offset, size := decodeOffsetAndSize(binary.LittleEndian.Uint64(r.data[r.slot(i):]))
	return boundedSlice(r.data, offset, size)
}

// Binary returns a zero-copy view of field i's bytes.
func (r *Row) Binary(i int) []byte { return r.varData(i) }

func (r *Row) String(i int) string { return string(r.varData(i)) }

func (r *Row) Struct(i int) *Row {
	data := r.varData(i)
	if data == nil {
		return nil
	}
	structType := r.fields[i].Type.(*StructType)
	return newRow(structType.Fields, data)
}

func (r *Row) Array(i int) *ArrayData {
	data := r.varData(i)
	if data == nil {
		return nil
	}
	listType := r.fields[i].Type.(*ListType)
	return NewArrayData(listType.Elem, data)
}

func (r *Row) Map(i int) *MapData {
	data := r.varData(i)
	if data == nil {
		return nil
	}
	return NewMapData(r.fields[i].Type.(*MapType), data)
}

// ArrayData is a zero-copy reader over one array's bytes, with the same
// null and bounds semantics as Row.
type ArrayData struct {
	elem        Field
	data        []byte
	numElements int
	elemSize    int
	headerBytes int
}

func NewArrayData(elem Field, data []byte) *ArrayData {
	// Narrow the wire count only when it is far below int range, so
	// header and size arithmetic cannot overflow on any platform; an
	// absurd count becomes -1, which validateBounds and every index
	// check reject.
	numElements := -1
	if count := binary.LittleEndian.Uint64(data); count <= uint64(math.MaxInt/8) {
		numElements = int(count)
	}
	elemSize := elem.Type.ByteWidth()
	if elemSize < 0 {
		elemSize = 8
	}
	return &ArrayData{
		elem:        elem,
		data:        data,
		numElements: numElements,
		elemSize:    elemSize,
		headerBytes: 8 + bitmapWidthInBytes(numElements),
	}
}

func (a *ArrayData) NumElements() int { return a.numElements }
func (a *ArrayData) SizeBytes() int   { return len(a.data) }

// validateBounds rejects arrays whose declared element count is not
// covered by the available bytes, so decoders can check before
// allocating from an attacker-declared count.
func (a *ArrayData) validateBounds() error {
	// The coarse count check involves no arithmetic, so it cannot be
	// defeated by overflow: every element occupies at least one data
	// byte. It also bounds numElements low enough that headerBytes
	// (computed in NewArrayData) and the product below are exact.
	if a.numElements < 0 || a.numElements > len(a.data) {
		return fmt.Errorf("row: array declares %d elements but holds only %d bytes", a.numElements, len(a.data))
	}
	need := int64(a.headerBytes) + int64(a.numElements)*int64(a.elemSize)
	if need > int64(len(a.data)) {
		return fmt.Errorf("row: array declares %d elements needing %d bytes but holds only %d", a.numElements, need, len(a.data))
	}
	return nil
}

func (a *ArrayData) IsNullAt(i int) bool {
	if uint(i) >= uint(a.numElements) {
		panic(fmt.Sprintf("row: array index %d out of range [0, %d)", i, a.numElements))
	}
	return getBit(a.data[8:a.headerBytes], i)
}

func (a *ArrayData) slot(i int) int { return a.headerBytes + i*a.elemSize }

func (a *ArrayData) Bool(i int) bool {
	return !a.IsNullAt(i) && a.data[a.slot(i)] != 0
}

func (a *ArrayData) Int8(i int) int8 {
	if a.IsNullAt(i) {
		return 0
	}
	return int8(a.data[a.slot(i)])
}

func (a *ArrayData) Int16(i int) int16 {
	if a.IsNullAt(i) {
		return 0
	}
	return int16(binary.LittleEndian.Uint16(a.data[a.slot(i):]))
}

func (a *ArrayData) Int32(i int) int32 {
	if a.IsNullAt(i) {
		return 0
	}
	return int32(binary.LittleEndian.Uint32(a.data[a.slot(i):]))
}

func (a *ArrayData) Int64(i int) int64 {
	if a.IsNullAt(i) {
		return 0
	}
	return int64(binary.LittleEndian.Uint64(a.data[a.slot(i):]))
}

func (a *ArrayData) Float32(i int) float32 {
	if a.IsNullAt(i) {
		return 0
	}
	return math.Float32frombits(binary.LittleEndian.Uint32(a.data[a.slot(i):]))
}

func (a *ArrayData) Float64(i int) float64 {
	if a.IsNullAt(i) {
		return 0
	}
	return math.Float64frombits(binary.LittleEndian.Uint64(a.data[a.slot(i):]))
}

func (a *ArrayData) Date(i int) fory.Date {
	if a.IsNullAt(i) {
		return fory.Date{}
	}
	return dateFromDays(int32(binary.LittleEndian.Uint32(a.data[a.slot(i):])))
}

func (a *ArrayData) Timestamp(i int) time.Time {
	if a.IsNullAt(i) {
		return time.Time{}
	}
	return time.UnixMicro(a.Int64(i))
}

func (a *ArrayData) Duration(i int) time.Duration {
	return durationFromMicros(a.Int64(i))
}

func (a *ArrayData) varData(i int) []byte {
	if a.IsNullAt(i) {
		return nil
	}
	offset, size := decodeOffsetAndSize(binary.LittleEndian.Uint64(a.data[a.slot(i):]))
	return boundedSlice(a.data, offset, size)
}

// Binary returns a zero-copy view of element i's bytes.
func (a *ArrayData) Binary(i int) []byte { return a.varData(i) }

func (a *ArrayData) String(i int) string { return string(a.varData(i)) }

func (a *ArrayData) Struct(i int) *Row {
	data := a.varData(i)
	if data == nil {
		return nil
	}
	return newRow(a.elem.Type.(*StructType).Fields, data)
}

func (a *ArrayData) Array(i int) *ArrayData {
	data := a.varData(i)
	if data == nil {
		return nil
	}
	return NewArrayData(a.elem.Type.(*ListType).Elem, data)
}

func (a *ArrayData) Map(i int) *MapData {
	data := a.varData(i)
	if data == nil {
		return nil
	}
	return NewMapData(a.elem.Type.(*MapType), data)
}

// MapData is a zero-copy reader over one map's bytes: the keys array
// and values array views share the map's underlying data.
type MapData struct {
	keys   *ArrayData
	values *ArrayData
}

func NewMapData(mapType *MapType, data []byte) *MapData {
	keysSize64 := binary.LittleEndian.Uint64(data)
	if keysSize64 > uint64(len(data)) {
		panic(fmt.Sprintf("row: map keys array of %d bytes exceeds the %d-byte map region", keysSize64, len(data)))
	}
	keysSize := int(keysSize64)
	keysData := boundedSlice(data, 8, keysSize)
	valuesData := boundedSlice(data, 8+keysSize, len(data)-8-keysSize)
	return &MapData{
		keys:   NewArrayData(mapType.Key, keysData),
		values: NewArrayData(mapType.Value, valuesData),
	}
}

// boundedSlice checks declared bounds against the data LENGTH
// and caps the view so nested
// readers cannot reach outside it either. Out-of-bounds panics are
// converted to errors by the encoder's decode entry points.
func boundedSlice(data []byte, offset, size int) []byte {
	end := offset + size
	if offset < 0 || size < 0 || end > len(data) {
		panic(fmt.Sprintf("row: value bytes [%d:%d] exceed the enclosing %d-byte region", offset, end, len(data)))
	}
	return data[offset:end:end]
}

func (m *MapData) NumElements() int   { return m.keys.numElements }
func (m *MapData) Keys() *ArrayData   { return m.keys }
func (m *MapData) Values() *ArrayData { return m.values }

func decodeOffsetAndSize(slotValue uint64) (offset, size int) {
	return int(slotValue >> 32), int(uint32(slotValue))
}

// dateFromDays converts int32 epoch days to a Date. The int32 range
// always yields an in-range year, so the conversion cannot fail.
func dateFromDays(days int32) fory.Date {
	d, _ := fory.DateFromEpochDay(int64(days))
	return d
}

// durationFromMicros converts wire microseconds to a time.Duration,
// which counts nanoseconds; values the multiplication would wrap are
// rejected instead of silently changing sign.
func durationFromMicros(micros int64) time.Duration {
	if micros > math.MaxInt64/1000 || micros < math.MinInt64/1000 {
		panic(fmt.Sprintf("row: duration of %d microseconds overflows time.Duration", micros))
	}
	return time.Duration(micros) * time.Microsecond
}
