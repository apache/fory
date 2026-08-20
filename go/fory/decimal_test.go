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
	"math/big"
	"reflect"
	"testing"

	"github.com/stretchr/testify/require"
)

func mustDecimal(value string, scale int32) Decimal {
	unscaled, ok := new(big.Int).SetString(value, 10)
	if !ok {
		panic("invalid decimal test value: " + value)
	}
	return NewDecimal(unscaled, scale)
}

func decimalPayload(scale int32, magnitudeSize int) []byte {
	buffer := NewByteBuffer(nil)
	buffer.WriteByte_(XLangFlag)
	buffer.WriteInt8(NotNullValueFlag)
	buffer.WriteUint8(uint8(DECIMAL))
	buffer.WriteVarint32(scale)
	if magnitudeSize == 0 {
		buffer.WriteVarUint64(encodeDecimalZigZag64(1) << 1)
		return buffer.Bytes()
	}
	magnitude := make([]byte, magnitudeSize)
	magnitude[magnitudeSize-1] = 1
	meta := uint64(magnitudeSize) << 1
	buffer.WriteVarUint64((meta << 1) | 1)
	buffer.WriteBinary(magnitude)
	return buffer.Bytes()
}

func decimalMagnitude(size int) Decimal {
	magnitude := make([]byte, size)
	magnitude[0] = 1
	return NewDecimal(new(big.Int).SetBytes(magnitude), 0)
}

func TestDecimalRoundTrip(t *testing.T) {
	values := []Decimal{
		NewDecimal(big.NewInt(0), 0),
		NewDecimal(big.NewInt(0), 3),
		NewDecimal(big.NewInt(1), 0),
		NewDecimal(big.NewInt(-1), 0),
		NewDecimal(big.NewInt(12345), 2),
		NewDecimal(big.NewInt(MaxInt64), 0),
		NewDecimal(big.NewInt(MinInt64), 0),
		NewDecimal(new(big.Int).Add(big.NewInt(MaxInt64), big.NewInt(1)), 0),
		NewDecimal(new(big.Int).Sub(big.NewInt(MinInt64), big.NewInt(1)), 0),
		mustDecimal("123456789012345678901234567890123456789", 37),
		mustDecimal("-123456789012345678901234567890123456789", -17),
	}
	for _, referenceTracking := range []bool{false, true} {
		f := New(WithXlang(true), WithCompatible(false), WithRefTracking(referenceTracking))
		for _, value := range values {
			data, err := Serialize(f, value)
			require.NoError(t, err)
			var decoded Decimal
			err = Deserialize(f, data, &decoded)
			require.NoError(t, err)
			require.True(t, value.Equal(decoded), "expected %v, got %v", value, decoded)
		}
	}
}

func TestDecimalDynamicAnyRoundTrip(t *testing.T) {
	f := New(WithXlang(true), WithCompatible(false), WithRefTracking(true))
	value := mustDecimal("9223372036854775808", 4)
	payload := []any{"marker", value, []any{value, mustDecimal("-12345678901234567890", 2)}}
	data, err := Serialize(f, payload)
	require.NoError(t, err)

	var decoded []any
	err = Deserialize(f, data, &decoded)
	require.NoError(t, err)
	require.Len(t, decoded, 3)
	require.Equal(t, "marker", decoded[0])
	gotDecimal, ok := decoded[1].(Decimal)
	require.True(t, ok)
	require.True(t, value.Equal(gotDecimal))
	nested, ok := decoded[2].([]any)
	require.True(t, ok)
	require.Len(t, nested, 2)
	gotNested, ok := nested[0].(Decimal)
	require.True(t, ok)
	require.True(t, value.Equal(gotNested))
}

func TestDecimalWireEncoding(t *testing.T) {
	f := New(WithXlang(true), WithCompatible(false))
	data, err := Serialize(f, NewDecimal(big.NewInt(0), 2))
	require.NoError(t, err)

	buf := NewByteBuffer(data)
	require.Equal(t, byte(XLangFlag), buf.ReadByte(nil))
	require.Equal(t, int8(NotNullValueFlag), buf.ReadInt8(nil))
	require.Equal(t, uint8(DECIMAL), buf.ReadUint8(nil))
	require.Equal(t, int32(2), buf.ReadVarint32(nil))
	require.Equal(t, uint64(0), buf.ReadVarUint64(nil))

	data, err = Serialize(f, mustDecimal("9223372036854775808", 0))
	require.NoError(t, err)
	buf = NewByteBuffer(data)
	require.Equal(t, byte(XLangFlag), buf.ReadByte(nil))
	require.Equal(t, int8(NotNullValueFlag), buf.ReadInt8(nil))
	require.Equal(t, uint8(DECIMAL), buf.ReadUint8(nil))
	require.Equal(t, int32(0), buf.ReadVarint32(nil))
	require.Equal(t, uint64(1), buf.ReadVarUint64(nil)&1)
}

func TestDecimalRejectsNonCanonicalBigPayload(t *testing.T) {
	f := New(WithXlang(true), WithCompatible(false))

	buffer := NewByteBuffer(nil)
	buffer.WriteByte_(XLangFlag)
	buffer.WriteInt8(NotNullValueFlag)
	buffer.WriteUint8(uint8(DECIMAL))
	buffer.WriteVarint32(0)
	buffer.WriteVarUint64(1)
	data := buffer.GetByteSlice(0, buffer.writerIndex)
	var decoded Decimal
	err := Deserialize(f, data, &decoded)
	require.Error(t, err)
	require.Contains(t, err.Error(), "invalid decimal magnitude length")

	buffer = NewByteBuffer(nil)
	buffer.WriteByte_(XLangFlag)
	buffer.WriteInt8(NotNullValueFlag)
	buffer.WriteUint8(uint8(DECIMAL))
	buffer.WriteVarint32(0)
	buffer.WriteVarUint64((((uint64(2) << 1) | 0) << 1) | 1)
	buffer.WriteBinary([]byte{0x01, 0x00})
	data = buffer.GetByteSlice(0, buffer.writerIndex)
	err = Deserialize(f, data, &decoded)
	require.Error(t, err)
	require.Contains(t, err.Error(), "trailing zero byte")
}

func TestDecimalOOM(t *testing.T) {
	maliciousLength := uint64(2000000000)

	buffer := NewByteBuffer(nil)
	buffer.WriteByte_(XLangFlag)
	buffer.WriteInt8(NotNullValueFlag)
	buffer.WriteUint8(uint8(DECIMAL))
	buffer.WriteVarint32(0)

	meta := (maliciousLength << 1) | 0
	header := (meta << 1) | 1
	buffer.WriteVarUint64(header)

	data := buffer.Bytes()

	f := New(WithXlang(true), WithCompatible(false))

	var decoded Decimal
	err := f.DeserializeFromReader(bytes.NewReader(data), &decoded)
	require.Error(t, err)
}

func TestDecimalScaleLimit(t *testing.T) {
	tests := []struct {
		name  string
		scale int32
		valid bool
	}{
		{"below_min", -10_001, false},
		{"min", -10_000, true},
		{"max", 10_000, true},
		{"above_max", 10_001, false},
		{"int32_min", MinInt32, false},
		{"int32_max", MaxInt32, false},
	}
	writers := []struct {
		name  string
		write func(*Fory, Decimal) ([]byte, error)
	}{
		{"root", func(f *Fory, value Decimal) ([]byte, error) {
			return Serialize(f, value)
		}},
		{"dynamic", func(f *Fory, value Decimal) ([]byte, error) {
			return f.Serialize([]any{value})
		}},
	}

	for _, test := range tests {
		t.Run("read_"+test.name, func(t *testing.T) {
			f := New(WithXlang(true), WithCompatible(false))
			var decoded Decimal
			err := Deserialize(f, decimalPayload(test.scale, 0), &decoded)
			if !test.valid {
				require.Error(t, err)
				return
			}
			require.NoError(t, err)
			require.Equal(t, test.scale, decoded.Scale)
			require.Equal(t, int64(1), decoded.Unscaled.Int64())
		})
		for _, writer := range writers {
			t.Run("write_"+writer.name+"_"+test.name, func(t *testing.T) {
				f := New(WithXlang(true), WithCompatible(false))
				data, err := writer.write(f, NewDecimal(big.NewInt(1), test.scale))
				if !test.valid {
					require.Error(t, err)
					return
				}
				require.NoError(t, err)
				require.NotEmpty(t, data)
			})
		}
	}
}

func TestDecimalMagnitudeLimit(t *testing.T) {
	tests := []struct {
		name  string
		size  int
		valid bool
	}{
		{"max", 10_000, true},
		{"above_max", 10_001, false},
	}
	writers := []struct {
		name  string
		write func(*Fory, Decimal) ([]byte, error)
	}{
		{"root", func(f *Fory, value Decimal) ([]byte, error) {
			return Serialize(f, value)
		}},
		{"dynamic", func(f *Fory, value Decimal) ([]byte, error) {
			return f.Serialize([]any{value})
		}},
	}

	for _, test := range tests {
		t.Run("read_"+test.name, func(t *testing.T) {
			f := New(WithXlang(true), WithCompatible(false))
			var decoded Decimal
			err := Deserialize(f, decimalPayload(0, test.size), &decoded)
			if !test.valid {
				require.Error(t, err)
				return
			}
			require.NoError(t, err)
			require.Equal(t, test.size, len(decoded.Unscaled.Bytes()))
		})
		for _, writer := range writers {
			t.Run("write_"+writer.name+"_"+test.name, func(t *testing.T) {
				f := New(WithXlang(true), WithCompatible(false))
				data, err := writer.write(f, decimalMagnitude(test.size))
				if !test.valid {
					require.Error(t, err)
					return
				}
				require.NoError(t, err)
				require.NotEmpty(t, data)
			})
		}
	}
}

func TestDecimalWriteFailureState(t *testing.T) {
	oversized := decimalMagnitude(maxDecimalMagnitudeBytes + 1)
	ctx := NewWriteContext(false, 1)
	ctx.Buffer().WriteByte_(0x7f)
	before := bytes.Clone(ctx.Buffer().Bytes())
	beforeIndex := ctx.Buffer().WriterIndex()

	writeDecimalParts(ctx, oversized.Scale, &oversized.Unscaled)

	require.Error(t, ctx.CheckError())
	require.Equal(t, beforeIndex, ctx.Buffer().WriterIndex())
	require.Equal(t, before, ctx.Buffer().Bytes())

	ctx = NewWriteContext(false, 1)
	ctx.Buffer().WriteByte_(0x7f)
	before = bytes.Clone(ctx.Buffer().Bytes())
	beforeIndex = ctx.Buffer().WriterIndex()

	decimalSerializer{}.Write(
		ctx, RefModeTracking, true, false, reflect.ValueOf(oversized))

	require.Error(t, ctx.CheckError())
	require.Equal(t, beforeIndex, ctx.Buffer().WriterIndex())
	require.Equal(t, before, ctx.Buffer().Bytes())

	f := New(WithXlang(true), WithCompatible(false))
	_, err := Serialize(f, oversized)
	require.Error(t, err)

	expected := NewDecimal(big.NewInt(7), 2)
	data, err := Serialize(f, expected)
	require.NoError(t, err)
	var decoded Decimal
	require.NoError(t, Deserialize(f, data, &decoded))
	require.True(t, expected.Equal(decoded))
}

func TestDecimalRootPrevalidation(t *testing.T) {
	writers := []struct {
		name  string
		write func(*Fory, *ByteBuffer, Decimal) error
	}{
		{
			name: "serialize_to",
			write: func(f *Fory, buffer *ByteBuffer, value Decimal) error {
				return f.SerializeTo(buffer, value)
			},
		},
		{
			name: "callback",
			write: func(f *Fory, buffer *ByteBuffer, value Decimal) error {
				return f.SerializeWithCallback(buffer, value, nil)
			},
		},
	}
	values := []struct {
		name  string
		value Decimal
		valid bool
	}{
		{"max_magnitude", decimalMagnitude(maxDecimalMagnitudeBytes), true},
		{"oversized_magnitude", decimalMagnitude(maxDecimalMagnitudeBytes + 1), false},
		{"min_scale", NewDecimal(big.NewInt(1), -maxDecimalScale), true},
		{"below_min_scale", NewDecimal(big.NewInt(1), -maxDecimalScale-1), false},
		{"max_scale", NewDecimal(big.NewInt(1), maxDecimalScale), true},
		{"above_max_scale", NewDecimal(big.NewInt(1), maxDecimalScale+1), false},
	}

	for _, writer := range writers {
		for _, value := range values {
			t.Run(writer.name+"_"+value.name, func(t *testing.T) {
				f := New(WithXlang(true), WithCompatible(false))
				buffer := NewByteBuffer(nil)
				buffer.WriteBinary([]byte{0x7f, 0x42})
				before := bytes.Clone(buffer.Bytes())
				beforeIndex := buffer.WriterIndex()

				err := writer.write(f, buffer, value.value)
				if value.valid {
					require.NoError(t, err)
					require.Greater(t, buffer.WriterIndex(), beforeIndex)
					return
				}
				require.Error(t, err)
				require.Equal(t, beforeIndex, buffer.WriterIndex())
				require.Equal(t, before, buffer.Bytes())
			})
		}
	}
}
