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
	"math/big"
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
		f := New(WithXlang(true), WithRefTracking(referenceTracking))
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
	f := New(WithXlang(true), WithRefTracking(true))
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
	f := New(WithXlang(true))
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
	f := New(WithXlang(true))

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
