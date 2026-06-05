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
	"math"
	"math/big"
	"reflect"
	"strconv"
	"strings"
	"unsafe"

	"github.com/apache/fory/go/fory/bfloat16"
	"github.com/apache/fory/go/fory/float16"
)

type compatibleScalarConversion struct {
	remoteTypeID TypeId
	localTypeID  TypeId
	localType    reflect.Type
}

type compatibleScalarValue struct {
	typeID   TypeId
	boolVal  bool
	string   string
	signed   int64
	unsigned uint64
	float64  float64
	float32  float32
	halfBits uint16
	decimal  Decimal
	negZero  bool
}

const maxCompatibleDecimalDigits int64 = 256
const maxCompatibleNumericTextLen = 320

var maxCompatibleDecimalMagnitude = new(big.Int).Exp(
	big.NewInt(10),
	big.NewInt(maxCompatibleDecimalDigits),
	nil,
)

func newCompatibleScalarConversion(remoteTypeID TypeId, localTypeID TypeId, localType reflect.Type) (*compatibleScalarConversion, bool) {
	if remoteTypeID == FLOAT8 || localTypeID == FLOAT8 {
		return nil, false
	}
	targetType := compatibleScalarValueType(localType)
	if targetType == nil || !compatibleScalarTargetMatches(localTypeID, targetType) {
		return nil, false
	}
	if !compatibleScalarType(remoteTypeID) || !compatibleScalarType(localTypeID) {
		return nil, false
	}
	if remoteTypeID == localTypeID {
		return &compatibleScalarConversion{remoteTypeID: remoteTypeID, localTypeID: localTypeID, localType: targetType}, true
	}
	if remoteTypeID == BOOL {
		return &compatibleScalarConversion{remoteTypeID: remoteTypeID, localTypeID: localTypeID, localType: targetType}, localTypeID == STRING || compatibleNumericType(localTypeID)
	}
	if localTypeID == BOOL {
		return &compatibleScalarConversion{remoteTypeID: remoteTypeID, localTypeID: localTypeID, localType: targetType}, remoteTypeID == STRING || compatibleNumericType(remoteTypeID)
	}
	if remoteTypeID == STRING {
		return &compatibleScalarConversion{remoteTypeID: remoteTypeID, localTypeID: localTypeID, localType: targetType}, compatibleNumericType(localTypeID)
	}
	if localTypeID == STRING {
		return &compatibleScalarConversion{remoteTypeID: remoteTypeID, localTypeID: localTypeID, localType: targetType}, compatibleNumericType(remoteTypeID)
	}
	return &compatibleScalarConversion{remoteTypeID: remoteTypeID, localTypeID: localTypeID, localType: targetType}, compatibleNumericType(remoteTypeID) && compatibleNumericType(localTypeID)
}

func compatibleScalarValueType(type_ reflect.Type) reflect.Type {
	if type_ == nil {
		return nil
	}
	if opt, ok := getOptionalInfo(type_); ok {
		type_ = opt.valueType
	}
	if type_.Kind() == reflect.Ptr {
		type_ = type_.Elem()
	}
	return type_
}

func compatibleScalarType(typeID TypeId) bool {
	return typeID == BOOL || typeID == STRING || compatibleNumericType(typeID)
}

func compatibleNumericType(typeID TypeId) bool {
	switch typeID {
	case INT8, INT16, INT32, VARINT32, INT64, VARINT64, TAGGED_INT64,
		UINT8, UINT16, UINT32, VAR_UINT32, UINT64, VAR_UINT64, TAGGED_UINT64,
		FLOAT16, BFLOAT16, FLOAT32, FLOAT64, DECIMAL:
		return true
	default:
		return false
	}
}

func compatibleScalarTargetMatches(typeID TypeId, target reflect.Type) bool {
	switch typeID {
	case BOOL:
		return target.Kind() == reflect.Bool
	case STRING:
		return target.Kind() == reflect.String
	case DECIMAL:
		return target == decimalType
	case FLOAT16:
		return target == float16Type
	case BFLOAT16:
		return target == bfloat16Type
	case FLOAT32:
		return target.Kind() == reflect.Float32
	case FLOAT64:
		return target.Kind() == reflect.Float64
	case INT8:
		return target.Kind() == reflect.Int8
	case INT16:
		return target.Kind() == reflect.Int16
	case INT32, VARINT32:
		return target.Kind() == reflect.Int32 || target.Kind() == reflect.Int
	case INT64, VARINT64, TAGGED_INT64:
		return target.Kind() == reflect.Int64 || target.Kind() == reflect.Int
	case UINT8:
		return target.Kind() == reflect.Uint8
	case UINT16:
		return target.Kind() == reflect.Uint16 && target != float16Type && target != bfloat16Type
	case UINT32, VAR_UINT32:
		return target.Kind() == reflect.Uint32 || target.Kind() == reflect.Uint
	case UINT64, VAR_UINT64, TAGGED_UINT64:
		return target.Kind() == reflect.Uint64 || target.Kind() == reflect.Uint
	default:
		return false
	}
}

//go:noinline
func readCompatibleScalarField(ctx *ReadContext, field *FieldInfo, fieldPtr unsafe.Pointer) {
	err := ctx.Err()
	if field.RefMode != RefModeNone {
		flag := ctx.buffer.ReadInt8(err)
		if ctx.HasError() {
			return
		}
		switch flag {
		case NullFlag:
			optInfo := optionalInfo{}
			if field.Kind == FieldKindOptional {
				optInfo = field.Meta.OptionalInfo
			}
			clearFieldValue(field.Kind, fieldPtr, optInfo)
			return
		case NotNullValueFlag:
		default:
			compatibleScalarFail(ctx, "", field.Meta.CompatibleScalar.remoteTypeID, field.Meta.CompatibleScalar.localTypeID,
				"invalid compatible scalar null flag")
			return
		}
	}
	value := readCompatibleScalarValue(ctx, field.Meta.CompatibleScalar.remoteTypeID)
	if ctx.HasError() {
		return
	}
	storeCompatibleScalarValue(ctx, field, fieldPtr, value)
}

func readCompatibleScalarValue(ctx *ReadContext, typeID TypeId) compatibleScalarValue {
	err := ctx.Err()
	buf := ctx.buffer
	switch typeID {
	case BOOL:
		raw := buf.ReadUint8(err)
		if ctx.HasError() {
			return compatibleScalarValue{}
		}
		switch raw {
		case 0:
			return compatibleScalarValue{typeID: typeID, boolVal: false}
		case 1:
			return compatibleScalarValue{typeID: typeID, boolVal: true}
		default:
			compatibleScalarFail(ctx, "", typeID, UNKNOWN, "bool payload is not 0 or 1")
			return compatibleScalarValue{}
		}
	case STRING:
		return compatibleScalarValue{typeID: typeID, string: ctx.ReadString()}
	case INT8:
		return compatibleScalarValue{typeID: typeID, signed: int64(buf.ReadInt8(err))}
	case INT16:
		return compatibleScalarValue{typeID: typeID, signed: int64(buf.ReadInt16(err))}
	case INT32:
		return compatibleScalarValue{typeID: typeID, signed: int64(buf.ReadInt32(err))}
	case VARINT32:
		return compatibleScalarValue{typeID: typeID, signed: int64(buf.ReadVarint32(err))}
	case INT64:
		return compatibleScalarValue{typeID: typeID, signed: buf.ReadInt64(err)}
	case VARINT64:
		return compatibleScalarValue{typeID: typeID, signed: buf.ReadVarint64(err)}
	case TAGGED_INT64:
		return compatibleScalarValue{typeID: typeID, signed: buf.ReadTaggedInt64(err)}
	case UINT8:
		return compatibleScalarValue{typeID: typeID, unsigned: uint64(buf.ReadUint8(err))}
	case UINT16:
		return compatibleScalarValue{typeID: typeID, unsigned: uint64(buf.ReadUint16(err))}
	case UINT32:
		return compatibleScalarValue{typeID: typeID, unsigned: uint64(buf.ReadUint32(err))}
	case VAR_UINT32:
		return compatibleScalarValue{typeID: typeID, unsigned: uint64(buf.ReadVarUint32(err))}
	case UINT64:
		return compatibleScalarValue{typeID: typeID, unsigned: buf.ReadUint64(err)}
	case VAR_UINT64:
		return compatibleScalarValue{typeID: typeID, unsigned: buf.ReadVarUint64(err)}
	case TAGGED_UINT64:
		return compatibleScalarValue{typeID: typeID, unsigned: buf.ReadTaggedUint64(err)}
	case FLOAT16, BFLOAT16:
		bits := buf.ReadUint16(err)
		return compatibleScalarValue{typeID: typeID, halfBits: bits, negZero: bits == 0x8000}
	case FLOAT32:
		f := buf.ReadFloat32(err)
		return compatibleScalarValue{typeID: typeID, float64: float64(f), float32: f, negZero: math.Float32bits(f) == 0x80000000}
	case FLOAT64:
		f := buf.ReadFloat64(err)
		return compatibleScalarValue{typeID: typeID, float64: f, negZero: math.Float64bits(f) == 0x8000000000000000}
	case DECIMAL:
		scale, unscaled := readDecimalParts(ctx)
		if ctx.HasError() {
			return compatibleScalarValue{}
		}
		return compatibleScalarValue{typeID: typeID, decimal: NewDecimal(unscaled, scale)}
	default:
		compatibleScalarFail(ctx, "", typeID, UNKNOWN, "unsupported remote scalar type")
		return compatibleScalarValue{}
	}
}

//go:noinline
func storeCompatibleScalarValue(ctx *ReadContext, field *FieldInfo, fieldPtr unsafe.Pointer, value compatibleScalarValue) {
	scalar := field.Meta.CompatibleScalar
	optInfo := optionalInfo{}
	if field.Kind == FieldKindOptional {
		optInfo = field.Meta.OptionalInfo
	}
	if scalar.remoteTypeID == scalar.localTypeID {
		storeCompatibleScalarIdentity(ctx, field, fieldPtr, optInfo, value)
		return
	}
	switch scalar.localTypeID {
	case BOOL:
		v, ok := compatibleValueToBool(value)
		if !ok {
			compatibleScalarFail(ctx, field.Meta.Name, value.typeID, scalar.localTypeID, "value is not exactly boolean")
			return
		}
		storeFieldValue(field.Kind, fieldPtr, optInfo, v)
	case STRING:
		v, ok := compatibleValueToString(value)
		if !ok {
			compatibleScalarFail(ctx, field.Meta.Name, value.typeID, scalar.localTypeID, "value has no finite canonical string form")
			return
		}
		storeFieldValue(field.Kind, fieldPtr, optInfo, v)
	case DECIMAL:
		v, ok := compatibleValueToDecimal(value)
		if !ok {
			compatibleScalarFail(ctx, field.Meta.Name, value.typeID, scalar.localTypeID, "value is not exactly representable as decimal")
			return
		}
		storeFieldValue(field.Kind, fieldPtr, optInfo, v)
	case FLOAT16, BFLOAT16:
		bits, ok := compatibleValueToHalf(value, scalar.localTypeID)
		if !ok {
			compatibleScalarFail(ctx, field.Meta.Name, value.typeID, scalar.localTypeID, "value is not exactly representable by target floating type")
			return
		}
		storeFieldValue(field.Kind, fieldPtr, optInfo, bits)
	case FLOAT32:
		v, ok := compatibleValueToFloat32(value)
		if !ok {
			compatibleScalarFail(ctx, field.Meta.Name, value.typeID, scalar.localTypeID, "value is not exactly representable by float32")
			return
		}
		storeFieldValue(field.Kind, fieldPtr, optInfo, v)
	case FLOAT64:
		v, ok := compatibleValueToFloat64(value)
		if !ok {
			compatibleScalarFail(ctx, field.Meta.Name, value.typeID, scalar.localTypeID, "value is not exactly representable by float64")
			return
		}
		storeFieldValue(field.Kind, fieldPtr, optInfo, v)
	default:
		if isSignedTypeID(scalar.localTypeID) {
			v, ok := compatibleValueToInt(value, scalar.localTypeID, scalar.localType.Kind())
			if !ok {
				compatibleScalarFail(ctx, field.Meta.Name, value.typeID, scalar.localTypeID, "value is not an in-range integral target value")
				return
			}
			storeCompatibleInt(field.Kind, fieldPtr, optInfo, scalar.localType.Kind(), v)
			return
		}
		v, ok := compatibleValueToUint(value, scalar.localTypeID, scalar.localType.Kind())
		if !ok {
			compatibleScalarFail(ctx, field.Meta.Name, value.typeID, scalar.localTypeID, "value is not an in-range unsigned integral target value")
			return
		}
		storeCompatibleUint(field.Kind, fieldPtr, optInfo, scalar.localType.Kind(), v)
	}
}

func storeCompatibleScalarIdentity(ctx *ReadContext, field *FieldInfo, fieldPtr unsafe.Pointer, optInfo optionalInfo, value compatibleScalarValue) {
	switch value.typeID {
	case BOOL:
		storeFieldValue(field.Kind, fieldPtr, optInfo, value.boolVal)
	case STRING:
		storeFieldValue(field.Kind, fieldPtr, optInfo, value.string)
	case INT8, INT16, INT32, VARINT32, INT64, VARINT64, TAGGED_INT64:
		storeCompatibleInt(field.Kind, fieldPtr, optInfo, field.Meta.CompatibleScalar.localType.Kind(), value.signed)
	case UINT8, UINT16, UINT32, VAR_UINT32, UINT64, VAR_UINT64, TAGGED_UINT64:
		storeCompatibleUint(field.Kind, fieldPtr, optInfo, field.Meta.CompatibleScalar.localType.Kind(), value.unsigned)
	case FLOAT16, BFLOAT16:
		storeFieldValue(field.Kind, fieldPtr, optInfo, value.halfBits)
	case FLOAT32:
		storeFieldValue(field.Kind, fieldPtr, optInfo, value.float32)
	case FLOAT64:
		storeFieldValue(field.Kind, fieldPtr, optInfo, value.float64)
	case DECIMAL:
		storeFieldValue(field.Kind, fieldPtr, optInfo, value.decimal)
	default:
		compatibleScalarFail(ctx, field.Meta.Name, value.typeID, field.Meta.CompatibleScalar.localTypeID, "unsupported identity scalar type")
	}
}

func compatibleValueToBool(value compatibleScalarValue) (bool, bool) {
	if value.typeID == STRING {
		switch value.string {
		case "0", "false":
			return false, true
		case "1", "true":
			return true, true
		default:
			return false, false
		}
	}
	rat, ok := compatibleValueRat(value)
	if !ok {
		return false, false
	}
	if rat.Sign() == 0 {
		return false, true
	}
	if rat.Cmp(big.NewRat(1, 1)) == 0 {
		return true, true
	}
	return false, false
}

func compatibleValueToString(value compatibleScalarValue) (string, bool) {
	if value.typeID == BOOL {
		if value.boolVal {
			return "true", true
		}
		return "false", true
	}
	switch value.typeID {
	case INT8, INT16, INT32, VARINT32, INT64, VARINT64, TAGGED_INT64:
		return strconv.FormatInt(value.signed, 10), true
	case UINT8, UINT16, UINT32, VAR_UINT32, UINT64, VAR_UINT64, TAGGED_UINT64:
		return strconv.FormatUint(value.unsigned, 10), true
	case FLOAT16:
		f := float16.Float16FromBits(value.halfBits)
		if !f.IsFinite() {
			return "", false
		}
		return finiteFloatRatString(exactRatFromFloat32(f.Float32()), f.Signbit() && f.IsZero())
	case BFLOAT16:
		f := bfloat16.BFloat16FromBits(value.halfBits).Float32()
		if math.IsNaN(float64(f)) || math.IsInf(float64(f), 0) {
			return "", false
		}
		return finiteFloatRatString(exactRatFromFloat32(f), math.Float32bits(f) == 0x80000000)
	case FLOAT32:
		f := float32(value.float64)
		if math.IsNaN(float64(f)) || math.IsInf(float64(f), 0) {
			return "", false
		}
		return finiteFloatRatString(exactRatFromFloat32(f), value.negZero)
	case FLOAT64:
		if math.IsNaN(value.float64) || math.IsInf(value.float64, 0) {
			return "", false
		}
		return finiteFloatRatString(exactRatFromFloat64(value.float64), value.negZero)
	case DECIMAL:
		return canonicalDecimalString(value.decimal)
	default:
		return "", false
	}
}

func compatibleValueToDecimal(value compatibleScalarValue) (Decimal, bool) {
	if value.typeID == BOOL {
		if value.boolVal {
			return NewDecimal(big.NewInt(1), 0), true
		}
		return NewDecimal(big.NewInt(0), 0), true
	}
	if value.typeID == STRING {
		rat, _, ok := parseCompatibleNumericLiteral(value.string)
		if !ok {
			return Decimal{}, false
		}
		return canonicalDecimalFromRat(rat)
	}
	rat, ok := compatibleValueRat(value)
	if !ok {
		return Decimal{}, false
	}
	return canonicalDecimalFromRat(rat)
}

func compatibleValueToInt(value compatibleScalarValue, target TypeId, kind reflect.Kind) (int64, bool) {
	if value.typeID == BOOL {
		if value.boolVal {
			return 1, true
		}
		return 0, true
	}
	rat, ok := compatibleFiniteRat(value)
	if !ok {
		return 0, false
	}
	if !rat.IsInt() {
		return 0, false
	}
	i := rat.Num()
	min, max := signedRange(target, kind)
	if i.Cmp(big.NewInt(min)) < 0 || i.Cmp(big.NewInt(max)) > 0 {
		return 0, false
	}
	return i.Int64(), true
}

func compatibleValueToUint(value compatibleScalarValue, target TypeId, kind reflect.Kind) (uint64, bool) {
	if value.typeID == BOOL {
		if value.boolVal {
			return 1, true
		}
		return 0, true
	}
	rat, ok := compatibleFiniteRat(value)
	if !ok || !rat.IsInt() {
		return 0, false
	}
	i := rat.Num()
	if i.Sign() < 0 {
		return 0, false
	}
	max := unsignedMax(target, kind)
	if i.Cmp(new(big.Int).SetUint64(max)) > 0 {
		return 0, false
	}
	return i.Uint64(), true
}

func compatibleValueToFloat32(value compatibleScalarValue) (float32, bool) {
	if value.typeID == BOOL {
		if value.boolVal {
			return 1, true
		}
		return 0, true
	}
	if f, ok := compatibleNonFiniteFloat32(value); ok {
		return f, true
	}
	rat, negZero, ok := compatibleFiniteRatWithZero(value)
	if !ok {
		return 0, false
	}
	f, exact := rat.Float32()
	if !exact {
		return 0, false
	}
	if negZero && f == 0 {
		f = math.Float32frombits(0x80000000)
	}
	return f, true
}

func compatibleValueToFloat64(value compatibleScalarValue) (float64, bool) {
	if value.typeID == BOOL {
		if value.boolVal {
			return 1, true
		}
		return 0, true
	}
	if f, ok := compatibleNonFiniteFloat64(value); ok {
		return f, true
	}
	rat, negZero, ok := compatibleFiniteRatWithZero(value)
	if !ok {
		return 0, false
	}
	f, exact := rat.Float64()
	if !exact {
		return 0, false
	}
	if negZero && f == 0 {
		f = math.Float64frombits(0x8000000000000000)
	}
	return f, true
}

func compatibleValueToHalf(value compatibleScalarValue, target TypeId) (uint16, bool) {
	if value.typeID == BOOL {
		if value.boolVal {
			if target == FLOAT16 {
				return float16.One.Bits(), true
			}
			return bfloat16.BFloat16FromFloat32(1).Bits(), true
		}
		return 0, true
	}
	if bits, ok := compatibleNonFiniteHalf(value, target); ok {
		return bits, true
	}
	rat, negZero, ok := compatibleFiniteRatWithZero(value)
	if !ok {
		return 0, false
	}
	f, exact := rat.Float32()
	if !exact {
		return 0, false
	}
	if negZero && f == 0 {
		f = math.Float32frombits(0x80000000)
	}
	if target == FLOAT16 {
		half := float16.Float16FromFloat32(f)
		if half.Signbit() != math.Signbit(float64(f)) && half.IsZero() {
			return 0, false
		}
		if exactRatFromFloat32(half.Float32()).Cmp(rat) != 0 {
			return 0, false
		}
		return half.Bits(), true
	}
	half := bfloat16.BFloat16FromFloat32(f)
	if math.Float32bits(half.Float32()) != math.Float32bits(f) {
		return 0, false
	}
	return half.Bits(), true
}

func compatibleFiniteRat(value compatibleScalarValue) (*big.Rat, bool) {
	rat, _, ok := compatibleFiniteRatWithZero(value)
	return rat, ok
}

func compatibleFiniteRatWithZero(value compatibleScalarValue) (*big.Rat, bool, bool) {
	if value.typeID == STRING {
		return parseCompatibleNumericLiteral(value.string)
	}
	rat, ok := compatibleValueRat(value)
	return rat, value.negZero, ok
}

func compatibleValueRat(value compatibleScalarValue) (*big.Rat, bool) {
	switch value.typeID {
	case INT8, INT16, INT32, VARINT32, INT64, VARINT64, TAGGED_INT64:
		return new(big.Rat).SetInt64(value.signed), true
	case UINT8, UINT16, UINT32, VAR_UINT32, UINT64, VAR_UINT64, TAGGED_UINT64:
		return new(big.Rat).SetInt(new(big.Int).SetUint64(value.unsigned)), true
	case FLOAT16:
		f := float16.Float16FromBits(value.halfBits)
		if !f.IsFinite() {
			return nil, false
		}
		return exactRatFromFloat32(f.Float32()), true
	case BFLOAT16:
		f := bfloat16.BFloat16FromBits(value.halfBits).Float32()
		if math.IsNaN(float64(f)) || math.IsInf(float64(f), 0) {
			return nil, false
		}
		return exactRatFromFloat32(f), true
	case FLOAT32:
		f := float32(value.float64)
		if math.IsNaN(float64(f)) || math.IsInf(float64(f), 0) {
			return nil, false
		}
		return exactRatFromFloat32(f), true
	case FLOAT64:
		if math.IsNaN(value.float64) || math.IsInf(value.float64, 0) {
			return nil, false
		}
		return exactRatFromFloat64(value.float64), true
	case DECIMAL:
		return decimalRat(value.decimal)
	default:
		return nil, false
	}
}

func compatibleNonFiniteFloat32(value compatibleScalarValue) (float32, bool) {
	f, ok := compatibleNonFiniteFloat64(value)
	if !ok {
		return 0, false
	}
	if math.IsInf(f, 1) {
		return float32(math.Inf(1)), true
	}
	if math.IsInf(f, -1) {
		return float32(math.Inf(-1)), true
	}
	return 0, false
}

func compatibleNonFiniteFloat64(value compatibleScalarValue) (float64, bool) {
	switch value.typeID {
	case FLOAT16:
		f := float16.Float16FromBits(value.halfBits)
		if f.IsNaN() {
			return 0, false
		}
		if f.IsInf(1) {
			return math.Inf(1), true
		}
		if f.IsInf(-1) {
			return math.Inf(-1), true
		}
	case BFLOAT16:
		f := float64(bfloat16.BFloat16FromBits(value.halfBits).Float32())
		if math.IsNaN(f) {
			return 0, false
		}
		if math.IsInf(f, 0) {
			return f, true
		}
	case FLOAT32, FLOAT64:
		if math.IsNaN(value.float64) {
			return 0, false
		}
		if math.IsInf(value.float64, 0) {
			return value.float64, true
		}
	}
	return 0, false
}

func compatibleNonFiniteHalf(value compatibleScalarValue, target TypeId) (uint16, bool) {
	f, ok := compatibleNonFiniteFloat64(value)
	if !ok {
		return 0, false
	}
	if target == FLOAT16 {
		if math.IsInf(f, 1) {
			return float16.Inf.Bits(), true
		}
		return float16.NegInf.Bits(), true
	}
	if math.IsInf(f, 1) {
		return bfloat16.BFloat16FromFloat32(float32(math.Inf(1))).Bits(), true
	}
	return bfloat16.BFloat16FromFloat32(float32(math.Inf(-1))).Bits(), true
}

func parseCompatibleNumericLiteral(s string) (*big.Rat, bool, bool) {
	if len(s) == 0 || len(s) > maxCompatibleNumericTextLen {
		return nil, false, false
	}

	pos := 0
	neg := s[pos] == '-'
	if neg {
		pos++
		if pos == len(s) {
			return nil, false, false
		}
	}

	intStart := pos
	significantDigits := int64(0)
	seenNonZero := false
	if s[pos] == '0' {
		countCompatibleDigit(s[pos], &seenNonZero, &significantDigits)
		pos++
		if pos < len(s) && isASCIIDigit(s[pos]) {
			return nil, false, false
		}
	} else if s[pos] >= '1' && s[pos] <= '9' {
		for pos < len(s) && isASCIIDigit(s[pos]) {
			countCompatibleDigit(s[pos], &seenNonZero, &significantDigits)
			pos++
		}
	} else {
		return nil, false, false
	}
	intEnd := pos

	fracStart := pos
	fracEnd := pos
	if pos < len(s) && s[pos] == '.' {
		pos++
		fracStart = pos
		for pos < len(s) && isASCIIDigit(s[pos]) {
			countCompatibleDigit(s[pos], &seenNonZero, &significantDigits)
			pos++
		}
		if pos == fracStart {
			return nil, false, false
		}
		fracEnd = pos
	}

	exponent := int64(0)
	if pos < len(s) && (s[pos] == 'e' || s[pos] == 'E') {
		pos++
		expNegative := false
		if pos < len(s) && s[pos] == '-' {
			expNegative = true
			pos++
		}
		if pos == len(s) {
			return nil, false, false
		}
		if s[pos] == '0' {
			pos++
			if pos < len(s) && isASCIIDigit(s[pos]) {
				return nil, false, false
			}
		} else if s[pos] >= '1' && s[pos] <= '9' {
			for pos < len(s) && isASCIIDigit(s[pos]) {
				exponent = exponent*10 + int64(s[pos]-'0')
				if exponent > maxCompatibleDecimalDigits {
					return nil, false, false
				}
				pos++
			}
		} else {
			return nil, false, false
		}
		if expNegative {
			exponent = -exponent
		}
	}
	if pos != len(s) || significantDigits > maxCompatibleDecimalDigits {
		return nil, false, false
	}
	scale := int64(fracEnd-fracStart) - exponent
	if !compatibleDecimalShape(significantDigits, scale) {
		return nil, false, false
	}

	body := s[intStart:intEnd]
	if fracEnd > fracStart {
		body += s[fracStart:fracEnd]
	}
	unscaled, ok := new(big.Int).SetString(body, 10)
	if !ok {
		return nil, false, false
	}
	negZero := neg && unscaled.Sign() == 0
	if neg && unscaled.Sign() != 0 {
		unscaled.Neg(unscaled)
	}
	if scale <= 0 {
		if scale < 0 {
			extraDigits := -scale
			if !compatibleDecimalShape(int64(decimalDigitCount(unscaled)), scale) || extraDigits > maxCompatibleDecimalDigits {
				return nil, false, false
			}
			unscaled.Mul(unscaled, pow10Int(-scale))
		}
		return new(big.Rat).SetInt(unscaled), negZero, true
	}
	ten := big.NewInt(10)
	zero := big.NewInt(0)
	rem := new(big.Int)
	for scale > 0 {
		q := new(big.Int)
		q.QuoRem(unscaled, ten, rem)
		if rem.Cmp(zero) != 0 {
			break
		}
		unscaled = q
		scale--
	}
	if scale > maxCompatibleDecimalDigits ||
		decimalDigitCount(unscaled) > int(maxCompatibleDecimalDigits) {
		return nil, false, false
	}
	return new(big.Rat).SetFrac(unscaled, pow10Int(scale)), negZero, true
}

func countCompatibleDigit(digit byte, seenNonZero *bool, significantDigits *int64) {
	if digit != '0' || *seenNonZero {
		*seenNonZero = true
		(*significantDigits)++
	}
}

func compatibleDecimalShape(significantDigits int64, scale int64) bool {
	if scale > maxCompatibleDecimalDigits {
		return false
	}
	if scale < 0 && significantDigits+(-scale) > maxCompatibleDecimalDigits {
		return false
	}
	return true
}

func isASCIIDigit(b byte) bool {
	return b >= '0' && b <= '9'
}

func exactRatFromFloat32(f float32) *big.Rat {
	rat := new(big.Rat)
	rat.SetFloat64(float64(f))
	return rat
}

func exactRatFromFloat64(f float64) *big.Rat {
	rat := new(big.Rat)
	rat.SetFloat64(f)
	return rat
}

func decimalRat(decimal Decimal) (*big.Rat, bool) {
	unscaled := new(big.Int).Set(&decimal.Unscaled)
	if decimal.Scale == 0 {
		if decimalDigitCount(unscaled) > int(maxCompatibleDecimalDigits) {
			return nil, false
		}
		return new(big.Rat).SetInt(unscaled), true
	}
	if decimal.Scale < 0 {
		extraDigits := int64(-decimal.Scale)
		if extraDigits > maxCompatibleDecimalDigits ||
			int64(decimalDigitCount(unscaled))+extraDigits > maxCompatibleDecimalDigits {
			return nil, false
		}
		unscaled.Mul(unscaled, pow10Int(extraDigits))
		return new(big.Rat).SetInt(unscaled), true
	}
	scale := int64(decimal.Scale)
	ten := big.NewInt(10)
	zero := big.NewInt(0)
	rem := new(big.Int)
	for scale > 0 {
		q := new(big.Int)
		q.QuoRem(unscaled, ten, rem)
		if rem.Cmp(zero) != 0 {
			break
		}
		unscaled = q
		scale--
	}
	if scale > maxCompatibleDecimalDigits ||
		decimalDigitCount(unscaled) > int(maxCompatibleDecimalDigits) {
		return nil, false
	}
	return new(big.Rat).SetFrac(unscaled, pow10Int(scale)), true
}

func canonicalDecimalFromRat(rat *big.Rat) (Decimal, bool) {
	num := new(big.Int).Set(rat.Num())
	den := new(big.Int).Set(rat.Denom())
	twos := factorCount(den, 2)
	fives := factorCount(den, 5)
	if den.Cmp(big.NewInt(1)) != 0 {
		return Decimal{}, false
	}
	scale := twos
	if fives > scale {
		scale = fives
	}
	if scale > int64(math.MaxInt32) || scale > maxCompatibleDecimalDigits {
		return Decimal{}, false
	}
	if twos < scale {
		num.Mul(num, new(big.Int).Exp(big.NewInt(2), big.NewInt(scale-twos), nil))
	}
	if fives < scale {
		num.Mul(num, new(big.Int).Exp(big.NewInt(5), big.NewInt(scale-fives), nil))
	}
	ten := big.NewInt(10)
	zero := big.NewInt(0)
	rem := new(big.Int)
	for scale > 0 {
		q := new(big.Int)
		q.QuoRem(num, ten, rem)
		if rem.Cmp(zero) != 0 {
			break
		}
		num = q
		scale--
	}
	if num.Sign() == 0 {
		scale = 0
	}
	if decimalDigitCount(num) > int(maxCompatibleDecimalDigits) {
		return Decimal{}, false
	}
	return NewDecimal(num, int32(scale)), true
}

func factorCount(value *big.Int, factor int64) int64 {
	divisor := big.NewInt(factor)
	zero := big.NewInt(0)
	rem := new(big.Int)
	count := int64(0)
	for value.Cmp(big.NewInt(1)) > 0 {
		q := new(big.Int)
		q.QuoRem(value, divisor, rem)
		if rem.Cmp(zero) != 0 {
			break
		}
		value.Set(q)
		count++
	}
	return count
}

func canonicalDecimalString(decimal Decimal) (string, bool) {
	rat, ok := decimalRat(decimal)
	if !ok {
		return "", false
	}
	normalized, ok := canonicalDecimalFromRat(rat)
	if !ok {
		return "", false
	}
	unscaled := normalized.Unscaled.String()
	if normalized.Scale == 0 {
		return unscaled, true
	}
	neg := strings.HasPrefix(unscaled, "-")
	if neg {
		unscaled = unscaled[1:]
	}
	scale := int(normalized.Scale)
	var text string
	if len(unscaled) <= scale {
		text = "0." + strings.Repeat("0", scale-len(unscaled)) + unscaled
	} else {
		cut := len(unscaled) - scale
		text = unscaled[:cut] + "." + unscaled[cut:]
	}
	text = strings.TrimRight(text, "0")
	text = strings.TrimRight(text, ".")
	if neg && text != "0" {
		text = "-" + text
	}
	return text, true
}

func finiteFloatRatString(rat *big.Rat, negZero bool) (string, bool) {
	if negZero {
		return "-0.0", true
	}
	decimal, ok := canonicalDecimalFromRat(rat)
	if !ok {
		return "", false
	}
	text, ok := canonicalDecimalString(decimal)
	if !ok {
		return "", false
	}
	if !strings.Contains(text, ".") {
		text += ".0"
	}
	return text, true
}

func pow10Int(exp int64) *big.Int {
	return new(big.Int).Exp(big.NewInt(10), big.NewInt(exp), nil)
}

func decimalDigitCount(value *big.Int) int {
	magnitude := new(big.Int).Abs(value)
	if magnitude.Cmp(maxCompatibleDecimalMagnitude) >= 0 {
		return int(maxCompatibleDecimalDigits) + 1
	}
	return len(magnitude.String())
}

func signedRange(typeID TypeId, kind reflect.Kind) (int64, int64) {
	switch typeID {
	case INT8:
		return MinInt8, MaxInt8
	case INT16:
		return MinInt16, MaxInt16
	case INT32, VARINT32:
		return MinInt32, MaxInt32
	default:
		if kind == reflect.Int {
			return MinInt, MaxInt
		}
		return MinInt64, MaxInt64
	}
}

func unsignedMax(typeID TypeId, kind reflect.Kind) uint64 {
	switch typeID {
	case UINT8:
		return MaxUint8
	case UINT16:
		return MaxUint16
	case UINT32, VAR_UINT32:
		return MaxUint32
	default:
		if kind == reflect.Uint {
			return MaxUint
		}
		return MaxUint64
	}
}

func isSignedTypeID(typeID TypeId) bool {
	switch typeID {
	case INT8, INT16, INT32, VARINT32, INT64, VARINT64, TAGGED_INT64:
		return true
	default:
		return false
	}
}

func storeCompatibleInt(kind FieldKind, fieldPtr unsafe.Pointer, optInfo optionalInfo, targetKind reflect.Kind, value int64) {
	switch targetKind {
	case reflect.Int8:
		storeFieldValue(kind, fieldPtr, optInfo, int8(value))
	case reflect.Int16:
		storeFieldValue(kind, fieldPtr, optInfo, int16(value))
	case reflect.Int32:
		storeFieldValue(kind, fieldPtr, optInfo, int32(value))
	case reflect.Int:
		storeFieldValue(kind, fieldPtr, optInfo, int(value))
	default:
		storeFieldValue(kind, fieldPtr, optInfo, value)
	}
}

func storeCompatibleUint(kind FieldKind, fieldPtr unsafe.Pointer, optInfo optionalInfo, targetKind reflect.Kind, value uint64) {
	switch targetKind {
	case reflect.Uint8:
		storeFieldValue(kind, fieldPtr, optInfo, uint8(value))
	case reflect.Uint16:
		storeFieldValue(kind, fieldPtr, optInfo, uint16(value))
	case reflect.Uint32:
		storeFieldValue(kind, fieldPtr, optInfo, uint32(value))
	case reflect.Uint:
		storeFieldValue(kind, fieldPtr, optInfo, uint(value))
	default:
		storeFieldValue(kind, fieldPtr, optInfo, value)
	}
}

//go:noinline
func compatibleScalarFail(ctx *ReadContext, field string, remote TypeId, local TypeId, reason string) {
	ctx.SetError(DeserializationErrorf("compatible scalar conversion failed for field %q: remote %s to local %s: %s",
		field, compatibleScalarTypeName(remote), compatibleScalarTypeName(local), reason))
}

func compatibleScalarTypeName(typeID TypeId) string {
	switch typeID {
	case BOOL:
		return "bool"
	case INT8:
		return "int8"
	case INT16:
		return "int16"
	case INT32:
		return "int32"
	case VARINT32:
		return "varint32"
	case INT64:
		return "int64"
	case VARINT64:
		return "varint64"
	case TAGGED_INT64:
		return "tagged_int64"
	case UINT8:
		return "uint8"
	case UINT16:
		return "uint16"
	case UINT32:
		return "uint32"
	case VAR_UINT32:
		return "var_uint32"
	case UINT64:
		return "uint64"
	case VAR_UINT64:
		return "var_uint64"
	case TAGGED_UINT64:
		return "tagged_uint64"
	case FLOAT16:
		return "float16"
	case BFLOAT16:
		return "bfloat16"
	case FLOAT32:
		return "float32"
	case FLOAT64:
		return "float64"
	case STRING:
		return "string"
	case DECIMAL:
		return "decimal"
	default:
		return "unknown"
	}
}
