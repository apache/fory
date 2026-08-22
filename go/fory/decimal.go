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
	"fmt"
	"math/big"
	"reflect"
)

// Decimal is the Go carrier for Fory DECIMAL values.
// It preserves the exact value unscaled * 10^-scale.
type Decimal struct {
	Unscaled big.Int
	Scale    int32
}

func NewDecimal(unscaled *big.Int, scale int32) Decimal {
	var copied big.Int
	if unscaled != nil {
		copied.Set(unscaled)
	}
	return Decimal{Unscaled: copied, Scale: scale}
}

func (d Decimal) Equal(other Decimal) bool {
	return d.Scale == other.Scale && d.Unscaled.Cmp(&other.Unscaled) == 0
}

func (d Decimal) String() string {
	return fmt.Sprintf("%se%d", d.Unscaled.String(), -d.Scale)
}

var (
	decimalReflectType = reflect.TypeFor[Decimal]()
	decimalLongMin     = big.NewInt(MinInt64)
	decimalLongMax     = big.NewInt(MaxInt64)
)

const maxDecimalMagnitudeBytes = 10_000
const maxDecimalScale int32 = 10_000

type decimalSerializer struct{}

func (s decimalSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	decimal := value.Interface().(Decimal)
	if !validateDecimal(ctx.Err(), decimal.Scale, &decimal.Unscaled) {
		return
	}
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(DECIMAL))
	}
	writeValidDecimalParts(ctx.buffer, decimal.Scale, &decimal.Unscaled)
}

func (s decimalSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	decimal := value.Interface().(Decimal)
	writeDecimalParts(ctx, decimal.Scale, &decimal.Unscaled)
}

func (s decimalSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	err := ctx.Err()
	if refMode != RefModeNone {
		if ctx.buffer.ReadInt8(err) == NullFlag {
			value.Set(reflect.Zero(value.Type()))
			return
		}
	}
	if readType && !ctx.readExpectedTypeID(DECIMAL) {
		return
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s decimalSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	scale, unscaled := readDecimalParts(ctx)
	if ctx.HasError() {
		return
	}
	value.Set(reflect.ValueOf(NewDecimal(unscaled, scale)))
}

func (s decimalSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

func writeDecimalParts(ctx *WriteContext, scale int32, unscaled *big.Int) {
	if !validateDecimal(ctx.Err(), scale, unscaled) {
		return
	}
	writeValidDecimalParts(ctx.buffer, scale, unscaled)
}

// Root writers call this before their protocol header so a rejected direct
// Decimal cannot append a partial root to a caller-owned buffer.
func validateRootDecimal(ctxErr *Error, value any) bool {
	switch decimal := value.(type) {
	case Decimal:
		return validateDecimal(ctxErr, decimal.Scale, &decimal.Unscaled)
	case *Decimal:
		return decimal == nil || validateDecimal(ctxErr, decimal.Scale, &decimal.Unscaled)
	default:
		return true
	}
}

func validateDecimal(ctxErr *Error, scale int32, unscaled *big.Int) bool {
	if scale < -maxDecimalScale || scale > maxDecimalScale {
		ctxErr.SetError(SerializationErrorf(
			"decimal scale %d exceeds supported range [%d, %d]",
			scale, -maxDecimalScale, maxDecimalScale))
		return false
	}
	if unscaled != nil && unscaled.BitLen() > maxDecimalMagnitudeBytes*8 {
		ctxErr.SetError(SerializationErrorf(
			"decimal magnitude exceeds %d bytes", maxDecimalMagnitudeBytes))
		return false
	}
	return true
}

func writeValidDecimalParts(buffer *ByteBuffer, scale int32, unscaled *big.Int) {
	if unscaled == nil {
		unscaled = new(big.Int)
	}
	small := canUseSmallDecimalEncoding(unscaled)
	buffer.WriteVarint32(scale)
	if small {
		smallValue := unscaled.Int64()
		header := encodeDecimalZigZag64(smallValue) << 1
		buffer.WriteVarUint64(header)
		return
	}

	abs := new(big.Int).Abs(unscaled)
	magnitudeBytes := abs.Bytes()
	if len(magnitudeBytes) == 0 {
		panic("decimal zero must use the small encoding")
	}
	reverseBytes(magnitudeBytes)
	meta := (uint64(len(magnitudeBytes)) << 1) | uint64(signBit(unscaled.Sign()))
	buffer.WriteVarUint64((meta << 1) | 1)
	buffer.WriteBinary(magnitudeBytes)
}

func readDecimalParts(ctx *ReadContext) (int32, *big.Int) {
	err := ctx.Err()
	scale := ctx.buffer.ReadVarint32(err)
	if ctx.HasError() {
		return 0, nil
	}
	if scale < -maxDecimalScale || scale > maxDecimalScale {
		ctx.SetError(DeserializationErrorf(
			"decimal scale %d exceeds supported range [%d, %d]",
			scale, -maxDecimalScale, maxDecimalScale))
		return 0, nil
	}
	header := ctx.buffer.ReadVarUint64(err)
	if ctx.HasError() {
		return 0, nil
	}
	if (header & 1) == 0 {
		return scale, big.NewInt(decodeDecimalZigZag64(header >> 1))
	}

	meta := header >> 1
	length := meta >> 1
	if length == 0 {
		ctx.SetError(DeserializationErrorf("invalid decimal magnitude length %d", length))
		return 0, nil
	}
	if length > uint64(MaxInt) {
		ctx.SetError(DeserializationErrorf("invalid decimal magnitude length %d", length))
		return 0, nil
	}
	if length > maxDecimalMagnitudeBytes {
		ctx.SetError(DeserializationErrorf(
			"decimal magnitude length %d exceeds limit %d",
			length, maxDecimalMagnitudeBytes))
		return 0, nil
	}
	magnitudeBytes := ctx.buffer.ReadBytes(int(length), err)
	if ctx.HasError() {
		return 0, nil
	}
	if magnitudeBytes[len(magnitudeBytes)-1] == 0 {
		ctx.SetError(DeserializationError("non-canonical decimal magnitude bytes: trailing zero byte"))
		return 0, nil
	}
	bigEndian := append([]byte(nil), magnitudeBytes...)
	reverseBytes(bigEndian)
	magnitude := new(big.Int).SetBytes(bigEndian)
	if magnitude.Sign() == 0 {
		ctx.SetError(DeserializationError("big decimal encoding must not represent zero"))
		return 0, nil
	}
	if (meta & 1) != 0 {
		magnitude.Neg(magnitude)
	}
	return scale, magnitude
}

func canUseSmallDecimalEncoding(value *big.Int) bool {
	if value == nil {
		return true
	}
	if value.Cmp(decimalLongMin) < 0 || value.Cmp(decimalLongMax) > 0 {
		return false
	}
	return (encodeDecimalZigZag64(value.Int64()) & (1 << 63)) == 0
}

func encodeDecimalZigZag64(value int64) uint64 {
	return uint64((value << 1) ^ (value >> 63))
}

func decodeDecimalZigZag64(value uint64) int64 {
	return int64((value >> 1) ^ uint64(-(int64(value & 1))))
}

func signBit(sign int) int {
	if sign < 0 {
		return 1
	}
	return 0
}

func reverseBytes(values []byte) {
	for i, j := 0, len(values)-1; i < j; i, j = i+1, j-1 {
		values[i], values[j] = values[j], values[i]
	}
}
