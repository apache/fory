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
	"reflect"
	"time"

	fory "github.com/apache/fory/go/fory"
)

// Encoder converts values of struct type T to and from row bytes using
// a codec tree compiled once with reflection at construction time. The
// schema is inferred per InferSchema.
//
// The row format is a trusted in-memory format: FromRow and Decode
// expect trusted, schema-matched bytes produced by a row writer for the
// same schema. Malformed input surfaces as an error rather than a
// panic, but no allocation or work limits are enforced against hostile
// bytes. An Encoder owns a reusable write buffer and is NOT
// goroutine-safe; create one Encoder per goroutine.
type Encoder[T any] struct {
	codec *structCodec
	hash  int64
	buf   *fory.ByteBuffer
}

func NewEncoder[T any]() (*Encoder[T], error) {
	t := reflect.TypeOf((*T)(nil)).Elem()
	if t.Kind() != reflect.Struct {
		return nil, fmt.Errorf("row: Encoder requires a struct type, got %v", t)
	}
	buf := fory.NewByteBuffer(nil)
	codec, err := newStructCodec(t, buf)
	if err != nil {
		return nil, err
	}
	return &Encoder[T]{codec: codec, hash: ComputeSchemaHash(codec.schema), buf: buf}, nil
}

func (e *Encoder[T]) Schema() *Schema { return e.codec.schema }

// SchemaHash returns the cross-language type-shape fingerprint of the
// schema: a fold over the recursive type ids only. It does not cover
// field names, nullability, decimal parameters, or the order of
// same-typed fields, so Encode/Decode can detect only type-shape
// mismatches between writer and reader.
func (e *Encoder[T]) SchemaHash() int64 { return e.hash }

// ToRow serializes v to row bytes (no framing, just the row).
func (e *Encoder[T]) ToRow(v *T) ([]byte, error) {
	if v == nil {
		return nil, errNilValue
	}
	e.buf.SetWriterIndex(0)
	return e.finishRow(v)
}

// Encode frames the row for cross-language exchange: an int64
// little-endian schema hash followed by the row bytes, matching the
// Java and Python row encoders.
func (e *Encoder[T]) Encode(v *T) ([]byte, error) {
	if v == nil {
		return nil, errNilValue
	}
	// The hash is written into the reusable buffer ahead of the row so
	// the framed output needs a single copy.
	e.buf.SetWriterIndex(0)
	e.buf.Reserve(8)
	binary.LittleEndian.PutUint64(e.buf.GetData()[:8], uint64(e.hash))
	e.buf.SetWriterIndex(8)
	return e.finishRow(v)
}

// finishRow writes v at the buffer's current writer index and returns
// an owned copy of everything written so far.
func (e *Encoder[T]) finishRow(v *T) ([]byte, error) {
	if err := e.codec.writeStruct(reflect.ValueOf(v).Elem()); err != nil {
		return nil, err
	}
	out := make([]byte, e.buf.WriterIndex())
	copy(out, e.buf.GetData()[:e.buf.WriterIndex()])
	return out, nil
}

// FromRow deserializes row bytes produced with this schema.
func (e *Encoder[T]) FromRow(data []byte) (T, error) {
	var out T
	err := e.FromRowInto(data, &out)
	return out, err
}

func (e *Encoder[T]) FromRowInto(data []byte, out *T) (err error) {
	if out == nil {
		return fmt.Errorf("row: cannot decode into a nil target")
	}
	defer func() {
		if p := recover(); p != nil {
			err = fmt.Errorf("row: malformed row data: %v", p)
		}
	}()
	return e.codec.readStruct(NewRow(e.codec.schema, data), reflect.ValueOf(out).Elem())
}

// Decode verifies the schema type-shape hash and deserializes the row.
func (e *Encoder[T]) Decode(data []byte) (T, error) {
	var out T
	if len(data) < 8 {
		return out, fmt.Errorf("row: encoded data of %d bytes is too short for the schema hash", len(data))
	}
	hash := int64(binary.LittleEndian.Uint64(data))
	if hash != e.hash {
		return out, fmt.Errorf("row: schema type-shape hash mismatch: data has %d, encoder expects %d", hash, e.hash)
	}
	err := e.FromRowInto(data[8:], &out)
	return out, err
}

var errNilValue = fmt.Errorf("row: cannot encode a nil value")

func nullIntoValueError(goType reflect.Type) error {
	return fmt.Errorf("row: null value for non-nullable Go type %v; use a pointer carrier to receive nulls", goType)
}

// slotWriter is the shared write surface of RowWriter and ArrayWriter,
// letting one value codec serve struct fields and array elements.
type slotWriter interface {
	SetNullAt(i int)
	WriteBool(i int, v bool)
	WriteInt8(i int, v int8)
	WriteInt16(i int, v int16)
	WriteInt32(i int, v int32)
	WriteInt64(i int, v int64)
	WriteFloat32(i int, v float32)
	WriteFloat64(i int, v float64)
	WriteDate(i int, d fory.Date) error
	WriteTimestamp(i int, t time.Time) error
	WriteDuration(i int, d time.Duration)
	WriteString(i int, s string) error
	WriteBytes(i int, b []byte) error
	SetOffsetAndSize(i, absStart, size int) error
}

// valueReader is the shared read surface of Row and ArrayData.
type valueReader interface {
	IsNullAt(i int) bool
	Bool(i int) bool
	Int8(i int) int8
	Int16(i int) int16
	Int32(i int) int32
	Int64(i int) int64
	Float32(i int) float32
	Float64(i int) float64
	Date(i int) fory.Date
	Timestamp(i int) time.Time
	Duration(i int) time.Duration
	String(i int) string
	Binary(i int) []byte
	Struct(i int) *Row
	Array(i int) *ArrayData
	Map(i int) *MapData
}

// valueCodec reads or writes one value at slot/element i. Write
// functions handle nil for nilable Go types. Read functions overwrite
// the target even when the value is null so reused targets stay clean,
// and reject null for Go carriers that cannot represent it.
type valueCodec struct {
	write func(w slotWriter, i int, v reflect.Value) error
	read  func(g valueReader, i int, v reflect.Value) error
}

// structCodec writes and reads one struct level; nested structs hold
// their own child codec with a child RowWriter sharing the buffer.
type structCodec struct {
	schema *Schema
	writer *RowWriter
	fields []fieldCodec
}

type fieldCodec struct {
	goIndex int
	codec   valueCodec
}

func newStructCodec(t reflect.Type, buf *fory.ByteBuffer) (*structCodec, error) {
	layout, err := inferStructLayout(t, nil)
	if err != nil {
		return nil, err
	}
	sc := &structCodec{
		schema: layout.schema,
		writer: NewRowWriterWithBuffer(layout.schema, buf),
		fields: make([]fieldCodec, 0, layout.schema.NumFields()),
	}
	for ordinal, goIndex := range layout.indexes {
		codec, err := newValueCodec(t.Field(goIndex).Type, layout.schema.Field(ordinal).Type, buf)
		if err != nil {
			return nil, err
		}
		sc.fields = append(sc.fields, fieldCodec{goIndex: goIndex, codec: codec})
	}
	return sc, nil
}

func (sc *structCodec) writeStruct(v reflect.Value) error {
	sc.writer.Reset()
	for ordinal := range sc.fields {
		fc := &sc.fields[ordinal]
		if err := fc.codec.write(sc.writer, ordinal, v.Field(fc.goIndex)); err != nil {
			return err
		}
	}
	return nil
}

func (sc *structCodec) readStruct(r *Row, v reflect.Value) error {
	for ordinal := range sc.fields {
		fc := &sc.fields[ordinal]
		if err := fc.codec.read(r, ordinal, v.Field(fc.goIndex)); err != nil {
			return err
		}
	}
	return nil
}

func newValueCodec(goType reflect.Type, dataType DataType, buf *fory.ByteBuffer) (valueCodec, error) {
	if goType.Kind() == reflect.Ptr {
		elemType := goType.Elem()
		inner, err := newValueCodec(elemType, dataType, buf)
		if err != nil {
			return valueCodec{}, err
		}
		return valueCodec{
			write: func(w slotWriter, i int, v reflect.Value) error {
				if v.IsNil() {
					w.SetNullAt(i)
					return nil
				}
				return inner.write(w, i, v.Elem())
			},
			read: func(g valueReader, i int, v reflect.Value) error {
				if g.IsNullAt(i) {
					v.Set(reflect.Zero(goType))
					return nil
				}
				p := reflect.New(elemType)
				if err := inner.read(g, i, p.Elem()); err != nil {
					return err
				}
				v.Set(p)
				return nil
			},
		}, nil
	}

	switch dt := dataType.(type) {
	case BoolType:
		return scalarCodec(goType,
			func(w slotWriter, i int, v reflect.Value) error { w.WriteBool(i, v.Bool()); return nil },
			func(g valueReader, i int, v reflect.Value) error { v.SetBool(g.Bool(i)); return nil }), nil
	case Int8Type:
		return scalarCodec(goType,
			func(w slotWriter, i int, v reflect.Value) error { w.WriteInt8(i, int8(v.Int())); return nil },
			func(g valueReader, i int, v reflect.Value) error { v.SetInt(int64(g.Int8(i))); return nil }), nil
	case Int16Type:
		return scalarCodec(goType,
			func(w slotWriter, i int, v reflect.Value) error { w.WriteInt16(i, int16(v.Int())); return nil },
			func(g valueReader, i int, v reflect.Value) error { v.SetInt(int64(g.Int16(i))); return nil }), nil
	case Int32Type:
		return scalarCodec(goType,
			func(w slotWriter, i int, v reflect.Value) error { w.WriteInt32(i, int32(v.Int())); return nil },
			func(g valueReader, i int, v reflect.Value) error { v.SetInt(int64(g.Int32(i))); return nil }), nil
	case Int64Type:
		return scalarCodec(goType,
			func(w slotWriter, i int, v reflect.Value) error { w.WriteInt64(i, v.Int()); return nil },
			func(g valueReader, i int, v reflect.Value) error {
				x := g.Int64(i)
				// `int` is 32 bits wide on some platforms.
				if v.OverflowInt(x) {
					return fmt.Errorf("row: value %d overflows %v", x, goType)
				}
				v.SetInt(x)
				return nil
			}), nil
	case Float32Type:
		return scalarCodec(goType,
			func(w slotWriter, i int, v reflect.Value) error { w.WriteFloat32(i, float32(v.Float())); return nil },
			func(g valueReader, i int, v reflect.Value) error { v.SetFloat(float64(g.Float32(i))); return nil }), nil
	case Float64Type:
		return scalarCodec(goType,
			func(w slotWriter, i int, v reflect.Value) error { w.WriteFloat64(i, v.Float()); return nil },
			func(g valueReader, i int, v reflect.Value) error { v.SetFloat(g.Float64(i)); return nil }), nil
	case Date32Type:
		return scalarCodec(goType,
			func(w slotWriter, i int, v reflect.Value) error { return w.WriteDate(i, v.Interface().(fory.Date)) },
			func(g valueReader, i int, v reflect.Value) error { v.Set(reflect.ValueOf(g.Date(i))); return nil }), nil
	case TimestampType:
		return scalarCodec(goType,
			func(w slotWriter, i int, v reflect.Value) error {
				return w.WriteTimestamp(i, v.Interface().(time.Time))
			},
			func(g valueReader, i int, v reflect.Value) error { v.Set(reflect.ValueOf(g.Timestamp(i))); return nil }), nil
	case DurationType:
		return scalarCodec(goType,
			func(w slotWriter, i int, v reflect.Value) error {
				w.WriteDuration(i, time.Duration(v.Int()))
				return nil
			},
			func(g valueReader, i int, v reflect.Value) error { v.SetInt(int64(g.Duration(i))); return nil }), nil
	case StringType:
		return scalarCodec(goType,
			func(w slotWriter, i int, v reflect.Value) error { return w.WriteString(i, v.String()) },
			func(g valueReader, i int, v reflect.Value) error { v.SetString(g.String(i)); return nil }), nil
	case *ListType:
		if goType.Elem().Kind() == reflect.Uint8 {
			return newByteListCodec(goType, dt, buf), nil
		}
		return newListCodec(goType, dt, buf)
	case *MapType:
		return newMapCodec(goType, dt, buf)
	case *StructType:
		child, err := newStructCodec(goType, buf)
		if err != nil {
			return valueCodec{}, err
		}
		return valueCodec{
			write: func(w slotWriter, i int, v reflect.Value) error {
				start := child.writer.buf.WriterIndex()
				if err := child.writeStruct(v); err != nil {
					return err
				}
				return w.SetOffsetAndSize(i, start, child.writer.buf.WriterIndex()-start)
			},
			read: func(g valueReader, i int, v reflect.Value) error {
				nested := g.Struct(i)
				if nested == nil {
					return nullIntoValueError(goType)
				}
				return child.readStruct(nested, v)
			},
		}, nil
	default:
		return valueCodec{}, fmt.Errorf("row: data type %s is not supported by the encoder", dataType)
	}
}

// scalarCodec wraps a codec for a Go carrier that cannot hold nil, so a
// null value on read is an error instead of a silent zero value.
func scalarCodec(goType reflect.Type,
	write func(w slotWriter, i int, v reflect.Value) error,
	read func(g valueReader, i int, v reflect.Value) error) valueCodec {
	return valueCodec{
		write: write,
		read: func(g valueReader, i int, v reflect.Value) error {
			if g.IsNullAt(i) {
				return nullIntoValueError(goType)
			}
			return read(g, i, v)
		},
	}
}

// newByteListCodec handles []byte as list<int8>, the Java byte[] model:
// the bytes are copied straight into and out of the int8 element region
// with no per-element reflection.
func newByteListCodec(goType reflect.Type, listType *ListType, buf *fory.ByteBuffer) valueCodec {
	writer := NewArrayWriter(listType.Elem, buf)
	return valueCodec{
		write: func(w slotWriter, i int, v reflect.Value) error {
			if v.IsNil() {
				w.SetNullAt(i)
				return nil
			}
			b := v.Bytes()
			start := buf.WriterIndex()
			if err := writer.Reset(len(b)); err != nil {
				return err
			}
			copy(buf.GetData()[writer.base+writer.headerBytes:], b)
			return w.SetOffsetAndSize(i, start, buf.WriterIndex()-start)
		},
		read: func(g valueReader, i int, v reflect.Value) error {
			arr := g.Array(i)
			if arr == nil {
				v.Set(reflect.Zero(goType))
				return nil
			}
			if err := arr.validateBounds(); err != nil {
				return err
			}
			n := arr.NumElements()
			for j := 0; j < n; j++ {
				if arr.IsNullAt(j) {
					return nullIntoValueError(goType.Elem())
				}
			}
			// Copy so the decoded struct never aliases the input.
			owned := make([]byte, n)
			copy(owned, arr.data[arr.headerBytes:arr.headerBytes+n])
			v.SetBytes(owned)
			return nil
		},
	}
}

func newListCodec(goType reflect.Type, listType *ListType, buf *fory.ByteBuffer) (valueCodec, error) {
	elemCodec, err := newValueCodec(goType.Elem(), listType.Elem.Type, buf)
	if err != nil {
		return valueCodec{}, err
	}
	writer := NewArrayWriter(listType.Elem, buf)
	return valueCodec{
		write: func(w slotWriter, i int, v reflect.Value) error {
			if v.IsNil() {
				w.SetNullAt(i)
				return nil
			}
			n := v.Len()
			start := buf.WriterIndex()
			if err := writer.Reset(n); err != nil {
				return err
			}
			for j := 0; j < n; j++ {
				if err := elemCodec.write(writer, j, v.Index(j)); err != nil {
					return err
				}
			}
			return w.SetOffsetAndSize(i, start, buf.WriterIndex()-start)
		},
		read: func(g valueReader, i int, v reflect.Value) error {
			arr := g.Array(i)
			if arr == nil {
				v.Set(reflect.Zero(goType))
				return nil
			}
			if err := arr.validateBounds(); err != nil {
				return err
			}
			n := arr.NumElements()
			s := reflect.MakeSlice(goType, n, n)
			for j := 0; j < n; j++ {
				if err := elemCodec.read(arr, j, s.Index(j)); err != nil {
					return err
				}
			}
			v.Set(s)
			return nil
		},
	}, nil
}

func newMapCodec(goType reflect.Type, mapType *MapType, buf *fory.ByteBuffer) (valueCodec, error) {
	keyType, valueType := goType.Key(), goType.Elem()
	keyCodec, err := newValueCodec(keyType, mapType.Key.Type, buf)
	if err != nil {
		return valueCodec{}, err
	}
	valueCodecImpl, err := newValueCodec(valueType, mapType.Value.Type, buf)
	if err != nil {
		return valueCodec{}, err
	}
	mapWriter := NewMapWriter(buf)
	keysWriter := NewArrayWriter(mapType.Key, buf)
	valuesWriter := NewArrayWriter(mapType.Value, buf)
	// Scratch values reused across entries: the encoder is single-use
	// per goroutine and a map site is never re-entered recursively.
	keyScratch := reflect.New(keyType).Elem()
	readKey := reflect.New(keyType).Elem()
	readValue := reflect.New(valueType).Elem()
	values := reflect.MakeSlice(reflect.SliceOf(valueType), 0, 0)
	return valueCodec{
		write: func(w slotWriter, i int, v reflect.Value) error {
			if v.IsNil() {
				w.SetNullAt(i)
				return nil
			}
			n := v.Len()
			start := buf.WriterIndex()
			mapWriter.Reset()
			if err := keysWriter.Reset(n); err != nil {
				return err
			}
			// Keys are written during the single map iteration while
			// values are captured into one typed slice, so each entry
			// costs no reflect.Value allocation. Go map iteration order
			// changes between iterations, which is why both halves
			// cannot simply be iterated twice.
			if values.Cap() < n {
				values = reflect.MakeSlice(reflect.SliceOf(valueType), n, n)
			}
			values = values.Slice(0, n)
			j := 0
			for iter := v.MapRange(); iter.Next(); j++ {
				if j >= n {
					return fmt.Errorf("row: map changed size during encoding")
				}
				keyScratch.SetIterKey(iter)
				if err := keyCodec.write(keysWriter, j, keyScratch); err != nil {
					return err
				}
				values.Index(j).SetIterValue(iter)
			}
			if j != n {
				return fmt.Errorf("row: map changed size during encoding")
			}
			mapWriter.FinishKeys()
			if err := valuesWriter.Reset(n); err != nil {
				return err
			}
			for j := 0; j < n; j++ {
				if err := valueCodecImpl.write(valuesWriter, j, values.Index(j)); err != nil {
					return err
				}
			}
			values.Clear() // drop references to the caller's values
			return w.SetOffsetAndSize(i, start, buf.WriterIndex()-start)
		},
		read: func(g valueReader, i int, v reflect.Value) error {
			md := g.Map(i)
			if md == nil {
				v.Set(reflect.Zero(goType))
				return nil
			}
			keys, vals := md.Keys(), md.Values()
			if err := keys.validateBounds(); err != nil {
				return err
			}
			if err := vals.validateBounds(); err != nil {
				return err
			}
			n := keys.NumElements()
			if vals.NumElements() != n {
				return fmt.Errorf("row: map has %d keys but %d values", n, vals.NumElements())
			}
			m := reflect.MakeMapWithSize(goType, n)
			for j := 0; j < n; j++ {
				if err := keyCodec.read(keys, j, readKey); err != nil {
					return err
				}
				if err := valueCodecImpl.read(vals, j, readValue); err != nil {
					return err
				}
				m.SetMapIndex(readKey, readValue)
			}
			v.Set(m)
			return nil
		},
	}, nil
}
