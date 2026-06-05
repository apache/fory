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
	"math"
	"math/big"
	"reflect"
	"strings"
	"testing"

	"github.com/apache/fory/go/fory/optional"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type scalarBool struct {
	Value bool
}

type scalarString struct {
	Value string
}

type scalarStringPtr struct {
	Value *string
}

type scalarOptionalString struct {
	Value optional.Optional[string]
}

type scalarOptionalBool struct {
	Value optional.Optional[bool]
}

type scalarInt8 struct {
	Value int8
}

type scalarInt32 struct {
	Value int32
}

type scalarTrackingRefInt32 struct {
	Value int32 `fory:"ref"`
}

type scalarInt64 struct {
	Value int64
}

type scalarFloat32 struct {
	Value float32
}

type scalarFloat64 struct {
	Value float64
}

type scalarDecimal struct {
	Value Decimal
}

func TestCompatibleScalarConversions(t *testing.T) {
	cases := []compatibilityCase{
		{
			name:      "BoolToString",
			tag:       "ScalarValue",
			writeType: scalarBool{},
			readType:  scalarString{},
			input:     scalarBool{Value: true},
			assertFunc: func(t *testing.T, input any, output any) {
				assert.Equal(t, "true", output.(scalarString).Value)
			},
		},
		{
			name:      "StringToBool",
			tag:       "ScalarValue",
			writeType: scalarString{},
			readType:  scalarBool{},
			input:     scalarString{Value: "1"},
			assertFunc: func(t *testing.T, input any, output any) {
				assert.True(t, output.(scalarBool).Value)
			},
		},
		{
			name:      "BoolToNumber",
			tag:       "ScalarValue",
			writeType: scalarBool{},
			readType:  scalarInt32{},
			input:     scalarBool{Value: true},
			assertFunc: func(t *testing.T, input any, output any) {
				assert.Equal(t, int32(1), output.(scalarInt32).Value)
			},
		},
		{
			name:      "NumberToBool",
			tag:       "ScalarValue",
			writeType: scalarInt32{},
			readType:  scalarBool{},
			input:     scalarInt32{Value: 0},
			assertFunc: func(t *testing.T, input any, output any) {
				assert.False(t, output.(scalarBool).Value)
			},
		},
		{
			name:                 "InvalidNumberToBool",
			tag:                  "ScalarValue",
			writeType:            scalarInt32{},
			readType:             scalarBool{},
			input:                scalarInt32{Value: 2},
			unmarshalErrContains: "compatible scalar conversion failed",
		},
		{
			name:      "LosslessIntegerNarrowing",
			tag:       "ScalarValue",
			writeType: scalarInt64{},
			readType:  scalarInt8{},
			input:     scalarInt64{Value: 127},
			assertFunc: func(t *testing.T, input any, output any) {
				assert.Equal(t, int8(127), output.(scalarInt8).Value)
			},
		},
		{
			name:                 "LossyIntegerNarrowingFails",
			tag:                  "ScalarValue",
			writeType:            scalarInt64{},
			readType:             scalarInt8{},
			input:                scalarInt64{Value: 128},
			unmarshalErrContains: "compatible scalar conversion failed",
		},
		{
			name:      "ExactFloatNarrowing",
			tag:       "ScalarValue",
			writeType: scalarFloat64{},
			readType:  scalarFloat32{},
			input:     scalarFloat64{Value: 1.25},
			assertFunc: func(t *testing.T, input any, output any) {
				assert.Equal(t, float32(1.25), output.(scalarFloat32).Value)
			},
		},
		{
			name:                 "InexactFloatNarrowingFails",
			tag:                  "ScalarValue",
			writeType:            scalarFloat64{},
			readType:             scalarFloat32{},
			input:                scalarFloat64{Value: 0.1},
			unmarshalErrContains: "compatible scalar conversion failed",
		},
		{
			name:      "StringToNumber",
			tag:       "ScalarValue",
			writeType: scalarString{},
			readType:  scalarInt32{},
			input:     scalarString{Value: "1e2"},
			assertFunc: func(t *testing.T, input any, output any) {
				assert.Equal(t, int32(100), output.(scalarInt32).Value)
			},
		},
		{
			name:      "StringNegativeZeroToFloat",
			tag:       "ScalarValue",
			writeType: scalarString{},
			readType:  scalarFloat64{},
			input:     scalarString{Value: "-0.0"},
			assertFunc: func(t *testing.T, input any, output any) {
				assert.True(t, math.Signbit(output.(scalarFloat64).Value))
			},
		},
		{
			name:                 "InvalidNumericStringGrammar",
			tag:                  "ScalarValue",
			writeType:            scalarString{},
			readType:             scalarInt32{},
			input:                scalarString{Value: "+1"},
			unmarshalErrContains: "compatible scalar conversion failed",
		},
		{
			name:                 "InexactNumericStringFails",
			tag:                  "ScalarValue",
			writeType:            scalarString{},
			readType:             scalarFloat32{},
			input:                scalarString{Value: "0.1"},
			unmarshalErrContains: "compatible scalar conversion failed",
		},
		{
			name:      "IntegerToString",
			tag:       "ScalarValue",
			writeType: scalarInt32{},
			readType:  scalarString{},
			input:     scalarInt32{Value: -42},
			assertFunc: func(t *testing.T, input any, output any) {
				assert.Equal(t, "-42", output.(scalarString).Value)
			},
		},
		{
			name:      "FloatToString",
			tag:       "ScalarValue",
			writeType: scalarFloat32{},
			readType:  scalarString{},
			input:     scalarFloat32{Value: 0.5},
			assertFunc: func(t *testing.T, input any, output any) {
				assert.Equal(t, "0.5", output.(scalarString).Value)
			},
		},
		{
			name:      "NegativeZeroFloatToString",
			tag:       "ScalarValue",
			writeType: scalarFloat64{},
			readType:  scalarString{},
			input:     scalarFloat64{Value: math.Copysign(0, -1)},
			assertFunc: func(t *testing.T, input any, output any) {
				assert.Equal(t, "-0.0", output.(scalarString).Value)
			},
		},
		{
			name:                 "NonFiniteFloatToStringFails",
			tag:                  "ScalarValue",
			writeType:            scalarFloat64{},
			readType:             scalarString{},
			input:                scalarFloat64{Value: math.Inf(1)},
			unmarshalErrContains: "compatible scalar conversion failed",
		},
		{
			name:      "DecimalToBool",
			tag:       "ScalarValue",
			writeType: scalarDecimal{},
			readType:  scalarBool{},
			input:     scalarDecimal{Value: NewDecimal(big.NewInt(10), 1)},
			assertFunc: func(t *testing.T, input any, output any) {
				assert.True(t, output.(scalarBool).Value)
			},
		},
		{
			name:      "DecimalToString",
			tag:       "ScalarValue",
			writeType: scalarDecimal{},
			readType:  scalarString{},
			input:     scalarDecimal{Value: NewDecimal(big.NewInt(1230), 3)},
			assertFunc: func(t *testing.T, input any, output any) {
				assert.Equal(t, "1.23", output.(scalarString).Value)
			},
		},
		{
			name:      "StringToDecimal",
			tag:       "ScalarValue",
			writeType: scalarString{},
			readType:  scalarDecimal{},
			input:     scalarString{Value: "1.2300"},
			assertFunc: func(t *testing.T, input any, output any) {
				assert.True(t, NewDecimal(big.NewInt(123), 2).Equal(output.(scalarDecimal).Value))
			},
		},
		{
			name:      "DigitBoundDecimalString",
			tag:       "ScalarValue",
			writeType: scalarString{},
			readType:  scalarDecimal{},
			input:     scalarString{Value: strings.Repeat("1", 256)},
			assertFunc: func(t *testing.T, input any, output any) {
				got := output.(scalarDecimal).Value
				assert.Equal(t, strings.Repeat("1", 256), got.Unscaled.String())
				assert.Equal(t, int32(0), got.Scale)
			},
		},
		{
			name:      "ExponentBoundDecimalString",
			tag:       "ScalarValue",
			writeType: scalarString{},
			readType:  scalarDecimal{},
			input:     scalarString{Value: "1e255"},
			assertFunc: func(t *testing.T, input any, output any) {
				got := output.(scalarDecimal).Value
				assert.Equal(t, 256, len(got.Unscaled.String()))
				assert.Equal(t, int32(0), got.Scale)
			},
		},
		{
			name:                 "TooManyDigitsDecimalStringFails",
			tag:                  "ScalarValue",
			writeType:            scalarString{},
			readType:             scalarDecimal{},
			input:                scalarString{Value: strings.Repeat("1", 257)},
			unmarshalErrContains: "compatible scalar conversion failed",
		},
		{
			name:                 "RawLengthDecimalStringFails",
			tag:                  "ScalarValue",
			writeType:            scalarString{},
			readType:             scalarDecimal{},
			input:                scalarString{Value: "0." + strings.Repeat("0", 319)},
			unmarshalErrContains: "compatible scalar conversion failed",
		},
		{
			name:                 "HugeExponentStringFails",
			tag:                  "ScalarValue",
			writeType:            scalarString{},
			readType:             scalarDecimal{},
			input:                scalarString{Value: "1e1000000"},
			unmarshalErrContains: "compatible scalar conversion failed",
		},
		{
			name:                 "ExponentExpansionStringFails",
			tag:                  "ScalarValue",
			writeType:            scalarString{},
			readType:             scalarDecimal{},
			input:                scalarString{Value: "1e256"},
			unmarshalErrContains: "compatible scalar conversion failed",
		},
		{
			name:                 "NegativeScaleDecimalStringFails",
			tag:                  "ScalarValue",
			writeType:            scalarDecimal{},
			readType:             scalarString{},
			input:                scalarDecimal{Value: NewDecimal(big.NewInt(1), -256)},
			unmarshalErrContains: "compatible scalar conversion failed",
		},
		{
			name:                 "FractionalDecimalToIntegerFails",
			tag:                  "ScalarValue",
			writeType:            scalarDecimal{},
			readType:             scalarInt32{},
			input:                scalarDecimal{Value: NewDecimal(big.NewInt(5), 1)},
			unmarshalErrContains: "compatible scalar conversion failed",
		},
		{
			name:      "PointerStringToBool",
			tag:       "ScalarValue",
			writeType: scalarStringPtr{},
			readType:  scalarBool{},
			input:     scalarStringPtr{Value: ptr("true")},
			assertFunc: func(t *testing.T, input any, output any) {
				assert.True(t, output.(scalarBool).Value)
			},
		},
		{
			name:      "OptionalStringToBool",
			tag:       "ScalarValue",
			writeType: scalarOptionalString{},
			readType:  scalarBool{},
			input:     scalarOptionalString{Value: optional.String("false")},
			assertFunc: func(t *testing.T, input any, output any) {
				assert.False(t, output.(scalarBool).Value)
			},
		},
		{
			name:      "StringToOptionalBool",
			tag:       "ScalarValue",
			writeType: scalarString{},
			readType:  scalarOptionalBool{},
			input:     scalarString{Value: "true"},
			assertFunc: func(t *testing.T, input any, output any) {
				got := output.(scalarOptionalBool).Value
				require.True(t, got.IsSome())
				assert.True(t, got.Unwrap())
			},
		},
		{
			name:      "SameSchemaStringPreserved",
			tag:       "ScalarValue",
			writeType: scalarString{},
			readType:  scalarString{},
			input:     scalarString{Value: "not a numeric literal"},
			assertFunc: func(t *testing.T, input any, output any) {
				assert.Equal(t, "not a numeric literal", output.(scalarString).Value)
			},
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			runCompatibilityCase(t, tc)
		})
	}
}

func TestCompatibleScalarRejectsInvalidBoolPayload(t *testing.T) {
	f := NewForyWithOptions(WithXlang(true), WithCompatible(true))
	f.readCtx.SetData([]byte{2})
	_ = readCompatibleScalarValue(f.readCtx, BOOL)
	err := f.readCtx.CheckError()
	require.Error(t, err)
	assert.Contains(t, err.Error(), "bool payload is not 0 or 1")
}

func TestCompatibleScalarRejectsRefValueFlag(t *testing.T) {
	writer := NewForyWithOptions(WithXlang(true), WithCompatible(true))
	require.NoError(t, writer.RegisterStructByName(scalarOptionalString{}, "ScalarValue"))
	data, err := writer.Marshal(&scalarOptionalString{Value: optional.String("1")})
	require.NoError(t, err)

	notNullFlag := NotNullValueFlag
	refValueFlag := RefValueFlag
	flagOffset := bytes.LastIndexByte(data, byte(notNullFlag))
	require.NotEqual(t, -1, flagOffset)
	data[flagOffset] = byte(refValueFlag)

	reader := NewForyWithOptions(WithXlang(true), WithCompatible(true))
	require.NoError(t, reader.RegisterStructByName(scalarBool{}, "ScalarValue"))
	var out scalarBool
	err = reader.Unmarshal(data, &out)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "invalid compatible scalar null flag")
}

func TestCompatibleScalarSameTypeNullableStrictRead(t *testing.T) {
	writer := NewForyWithOptions(WithXlang(true), WithCompatible(true))
	require.NoError(t, writer.RegisterStructByName(scalarOptionalBool{}, "ScalarValue"))
	data, err := writer.Marshal(&scalarOptionalBool{Value: optional.Some(true)})
	require.NoError(t, err)

	reader := NewForyWithOptions(WithXlang(true), WithCompatible(true))
	require.NoError(t, reader.RegisterStructByName(scalarBool{}, "ScalarValue"))

	notNullFlag := NotNullValueFlag
	flagOffset := bytes.LastIndexByte(data, byte(notNullFlag))
	require.NotEqual(t, -1, flagOffset)
	badFlag := append([]byte(nil), data...)
	badFlag[flagOffset] = byte(RefValueFlag)
	var out scalarBool
	err = reader.Unmarshal(badFlag, &out)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "invalid compatible scalar null flag")

	badPayload := append([]byte(nil), data...)
	badPayload[len(badPayload)-1] = 2
	err = reader.Unmarshal(badPayload, &out)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "bool payload")
}

func TestCompatibleScalarTrackingRefMismatch(t *testing.T) {
	f := NewForyWithOptions(WithXlang(true), WithCompatible(true))
	remoteDef := FieldDef{
		name:     "value",
		nullable: false,
		trackRef: true,
		typeSpec: NewSimpleTypeSpec(BOOL),
		tagID:    -1,
	}
	ser := newStructSerializerFromTypeDef(reflect.TypeOf(scalarBool{}), "ScalarTrackingRefMismatch", []FieldDef{remoteDef})
	require.NoError(t, ser.initialize(f.typeResolver))
	require.Len(t, ser.fields, 1)
	assert.Equal(t, -1, ser.fields[0].Meta.FieldIndex)
	assert.Nil(t, ser.fields[0].Meta.CompatibleScalar)
	assert.True(t, ser.typeDefDiffers)

	remoteDef.trackRef = false
	ser = newStructSerializerFromTypeDef(reflect.TypeOf(scalarBool{}), "ScalarTrackingRefMatch", []FieldDef{remoteDef})
	require.NoError(t, ser.initialize(f.typeResolver))
	require.Len(t, ser.fields, 1)
	assert.Equal(t, 0, ser.fields[0].Meta.FieldIndex)
	assert.Nil(t, ser.fields[0].Meta.CompatibleScalar)
	assert.False(t, ser.typeDefDiffers)

	remoteDef.trackRef = true
	remoteDef.nullable = true
	ser = newStructSerializerFromTypeDef(reflect.TypeOf(scalarBool{}), "ScalarTrackingRefNullableRemote", []FieldDef{remoteDef})
	require.NoError(t, ser.initialize(f.typeResolver))
	require.Len(t, ser.fields, 1)
	assert.Equal(t, -1, ser.fields[0].Meta.FieldIndex)
	assert.Nil(t, ser.fields[0].Meta.CompatibleScalar)
	assert.True(t, ser.typeDefDiffers)

	remoteDef.trackRef = true
	remoteDef.nullable = false
	remoteDef.typeSpec = NewSimpleTypeSpec(INT32)
	ser = newStructSerializerFromTypeDef(reflect.TypeOf(scalarTrackingRefInt32{}), "ScalarTrackingRefTypeChange", []FieldDef{remoteDef})
	require.NoError(t, ser.initialize(f.typeResolver))
	require.Len(t, ser.fields, 1)
	assert.Equal(t, -1, ser.fields[0].Meta.FieldIndex)
	assert.Nil(t, ser.fields[0].Meta.CompatibleScalar)
	assert.True(t, ser.typeDefDiffers)
}
