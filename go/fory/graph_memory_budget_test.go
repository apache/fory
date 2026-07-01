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
	"reflect"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

type budgetItem struct {
	A int32
}

type budgetSiblings struct {
	A []string
	B []string
}

func graphOwnerSizeOf[T any]() int64 {
	bytes := graphSizeOf[T]()
	if bytes == 0 {
		return 1
	}
	return bytes
}

func TestGraphMemoryBudgetConfig(t *testing.T) {
	require.Equal(t, int64(128*1024*1024), New().config.MaxGraphMemoryBytes)
	require.Equal(t, int64(123), New(WithMaxGraphMemoryBytes(123)).config.MaxGraphMemoryBytes)
	require.Equal(t, int64(0), New(WithMaxGraphMemoryBytes(0)).config.MaxGraphMemoryBytes)
	require.Equal(t, int64(-2), New(WithMaxGraphMemoryBytes(-2)).config.MaxGraphMemoryBytes)
}

func TestGraphMemoryBudgetFixedDefaultAndDisable(t *testing.T) {
	ctx := NewReadContext(false)
	ctx.initGraphMemoryBudget()
	require.False(t, ctx.HasError())
	require.Equal(t, int64(128*1024*1024), ctx.graphMemoryLimitBytes)
	require.True(t, ctx.ReserveGraphMemory(ctx.graphMemoryLimitBytes))
	require.False(t, ctx.ReserveGraphMemory(1))
	require.Contains(t, ctx.CheckError().Error(), "maxGraphMemoryBytes")

	ctx = NewReadContext(false)
	ctx.maxGraphMemoryBytes = 0
	ctx.initGraphMemoryBudget()
	require.False(t, ctx.HasError())
	require.Equal(t, int64(0), ctx.graphMemoryLimitBytes)
	require.True(t, ctx.ReserveGraphMemory(MaxInt64))
	require.False(t, ctx.HasError())

	ctx = NewReadContext(false)
	ctx.maxGraphMemoryBytes = 77
	ctx.initGraphMemoryBudget()
	require.False(t, ctx.HasError())
	require.Equal(t, int64(77), ctx.graphMemoryLimitBytes)
}

func TestGraphMemoryBudgetRootKindsShareDefault(t *testing.T) {
	writer := New(WithCompatible(false))
	values := make([]any, 12000)
	for i := range values {
		values[i] = []any{}
	}
	data, err := writer.Serialize(values)
	require.NoError(t, err)

	var fromBytes []any
	err = New(WithCompatible(false)).Deserialize(data, &fromBytes)
	require.NoError(t, err)
	require.Len(t, fromBytes, len(values))

	var fromStream []any
	err = New(WithCompatible(false)).DeserializeFromReader(bytes.NewReader(data), &fromStream)
	require.NoError(t, err)
	require.Len(t, fromStream, len(values))
}

func TestGraphMemoryBudgetBufferRoots(t *testing.T) {
	writer := New(WithCompatible(false))
	value := []string{"a", "b"}
	data, err := writer.Serialize(value)
	require.NoError(t, err)

	reader := New(WithCompatible(false))
	var fromCallback []string
	err = reader.DeserializeWithCallbackBuffers(NewByteBuffer(data), &fromCallback, nil)
	require.NoError(t, err)
	require.Equal(t, value, fromCallback)

	var fromBuffer []string
	err = reader.DeserializeFrom(NewByteBuffer(data), &fromBuffer)
	require.NoError(t, err)
	require.Equal(t, value, fromBuffer)
}

func TestGraphMemoryBudgetExplicitOverride(t *testing.T) {
	writer := New(WithCompatible(false))
	values := make([]any, 12000)
	for i := range values {
		values[i] = []any{}
	}
	data, err := writer.Serialize(values)
	require.NoError(t, err)

	var out []any
	err = New(WithCompatible(false), WithMaxGraphMemoryBytes(4*1024*1024)).Deserialize(data, &out)
	require.NoError(t, err)
	require.Len(t, out, len(values))
}

func TestGraphMemoryBudgetEmptyAndCumulative(t *testing.T) {
	data, err := New(WithCompatible(false)).Serialize([]any{})
	require.NoError(t, err)
	var empty []any
	err = New(WithCompatible(false), WithMaxGraphMemoryBytes(1)).Deserialize(data, &empty)
	require.NoError(t, err)
	require.Empty(t, empty)

	writer := New(WithCompatible(false))
	require.NoError(t, writer.RegisterStructByName(budgetSiblings{}, "test.BudgetSiblings"))
	data, err = writer.Serialize(&budgetSiblings{A: []string{"a"}, B: []string{"b"}})
	require.NoError(t, err)
	reader := New(WithCompatible(false), WithMaxGraphMemoryBytes(stringElementBytes))
	require.NoError(t, reader.RegisterStructByName(budgetSiblings{}, "test.BudgetSiblings"))
	var out budgetSiblings
	err = reader.Deserialize(data, &out)
	require.Error(t, err)
	require.Contains(t, err.Error(), "maxGraphMemoryBytes")
	required := structGraphBytes(reflect.TypeOf(budgetSiblings{})) + 2*stringElementBytes
	reader = New(WithCompatible(false), WithMaxGraphMemoryBytes(required))
	require.NoError(t, reader.RegisterStructByName(budgetSiblings{}, "test.BudgetSiblings"))
	require.NoError(t, reader.Deserialize(data, &out))
	require.Equal(t, []string{"a"}, out.A)
	require.Equal(t, []string{"b"}, out.B)
}

func TestGraphMemoryBudgetMapAndOverflow(t *testing.T) {
	data, err := New().Serialize(map[string]string{"k": "v"})
	require.NoError(t, err)
	var out map[string]string
	oneEntryBudget := graphSizeOf[string]() + graphSizeOf[string]()
	err = New(WithMaxGraphMemoryBytes(oneEntryBudget-1)).Deserialize(data, &out)
	require.Error(t, err)
	require.Contains(t, err.Error(), "maxGraphMemoryBytes")

	ctx := NewReadContext(false)
	ctx.initGraphMemoryBudget()
	require.False(t, ctx.ReserveGraphMemory(-1))
	require.Contains(t, ctx.CheckError().Error(), "non-negative")
}

func TestGraphMemoryBudgetSlicesAndInlineValues(t *testing.T) {
	data, err := New().Serialize([]string{"a"})
	require.NoError(t, err)
	var stringsOut []string
	err = New(WithMaxGraphMemoryBytes(graphSizeOf[string]()-1)).Deserialize(data, &stringsOut)
	require.Error(t, err)
	require.Contains(t, err.Error(), "maxGraphMemoryBytes")

	writer := New()
	require.NoError(t, writer.RegisterStructByName(budgetItem{}, "test.BudgetItem"))
	data, err = writer.Serialize([]budgetItem{{A: 1}})
	require.NoError(t, err)
	reader := New(WithMaxGraphMemoryBytes(graphSizeOf[budgetItem]() - 1))
	require.NoError(t, reader.RegisterStructByName(budgetItem{}, "test.BudgetItem"))
	var items []budgetItem
	err = reader.Deserialize(data, &items)
	require.Error(t, err)
	require.Contains(t, err.Error(), "maxGraphMemoryBytes")
	reader = New(WithMaxGraphMemoryBytes(graphSizeOf[budgetItem]()))
	require.NoError(t, reader.RegisterStructByName(budgetItem{}, "test.BudgetItem"))
	require.NoError(t, reader.Deserialize(data, &items))
	require.Equal(t, []budgetItem{{A: 1}}, items)
}

func TestGraphMemoryBudgetStructOwners(t *testing.T) {
	writer := New(WithCompatible(false))
	require.NoError(t, writer.RegisterStructByName(budgetItem{}, "test.BudgetItem"))
	data, err := writer.Serialize(&budgetItem{A: 7})
	require.NoError(t, err)

	required := graphOwnerSizeOf[budgetItem]()
	reader := New(WithCompatible(false), WithMaxGraphMemoryBytes(required-1))
	require.NoError(t, reader.RegisterStructByName(budgetItem{}, "test.BudgetItem"))
	var out *budgetItem
	err = reader.Deserialize(data, &out)
	require.Error(t, err)
	require.Contains(t, err.Error(), "maxGraphMemoryBytes")

	reader = New(WithCompatible(false), WithMaxGraphMemoryBytes(required))
	require.NoError(t, reader.RegisterStructByName(budgetItem{}, "test.BudgetItem"))
	require.NoError(t, reader.Deserialize(data, &out))
	require.Equal(t, int32(7), out.A)

	reader = New(WithCompatible(false), WithMaxGraphMemoryBytes(required-1))
	require.NoError(t, reader.RegisterStructByName(budgetItem{}, "test.BudgetItem"))
	var outValue budgetItem
	err = reader.Deserialize(data, &outValue)
	require.Error(t, err)
	require.Contains(t, err.Error(), "maxGraphMemoryBytes")

	reader = New(WithCompatible(false), WithMaxGraphMemoryBytes(required))
	require.NoError(t, reader.RegisterStructByName(budgetItem{}, "test.BudgetItem"))
	require.NoError(t, reader.Deserialize(data, &outValue))
	require.Equal(t, int32(7), outValue.A)

	reader = New(WithCompatible(false), WithMaxGraphMemoryBytes(required-1))
	require.NoError(t, reader.RegisterStructByName(budgetItem{}, "test.BudgetItem"))
	err = reader.DeserializeFromReader(bytes.NewReader(data), &outValue)
	require.Error(t, err)
	require.Contains(t, err.Error(), "maxGraphMemoryBytes")

	reader = New(WithCompatible(false), WithMaxGraphMemoryBytes(required))
	require.NoError(t, reader.RegisterStructByName(budgetItem{}, "test.BudgetItem"))
	require.NoError(t, reader.DeserializeFromReader(bytes.NewReader(data), &outValue))
	require.Equal(t, int32(7), outValue.A)

	reader = New(WithCompatible(false), WithMaxGraphMemoryBytes(required-1))
	require.NoError(t, reader.RegisterStructByName(budgetItem{}, "test.BudgetItem"))
	err = reader.DeserializeFromStream(NewInputStream(bytes.NewReader(data)), &outValue)
	require.Error(t, err)
	require.Contains(t, err.Error(), "maxGraphMemoryBytes")

	reader = New(WithCompatible(false), WithMaxGraphMemoryBytes(required))
	require.NoError(t, reader.RegisterStructByName(budgetItem{}, "test.BudgetItem"))
	require.NoError(t, reader.DeserializeFromStream(NewInputStream(bytes.NewReader(data)), &outValue))
	require.Equal(t, int32(7), outValue.A)
}

func TestGraphMemoryBudgetSkipsDenseOwners(t *testing.T) {
	f := New(WithMaxGraphMemoryBytes(1))

	stringData, err := New().Serialize(strings.Repeat("x", 128))
	require.NoError(t, err)
	var s string
	require.NoError(t, f.Deserialize(stringData, &s))
	require.Len(t, s, 128)

	bytesData, err := New().Serialize([]byte{1, 2, 3, 4})
	require.NoError(t, err)
	var b []byte
	require.NoError(t, f.Deserialize(bytesData, &b))
	require.Equal(t, []byte{1, 2, 3, 4}, b)

	intsData, err := New().Serialize([]int32{1, 2, 3, 4})
	require.NoError(t, err)
	var ints []int32
	require.NoError(t, f.Deserialize(intsData, &ints))
	require.Equal(t, []int32{1, 2, 3, 4}, ints)
}

func TestGraphMemoryBudgetPreservesByteChecks(t *testing.T) {
	buf := NewByteBuffer(nil)
	buf.WriteByte_(XLangFlag)
	buf.WriteInt8(NotNullValueFlag)
	buf.WriteUint8(uint8(LIST))
	buf.WriteLength(1024)
	buf.WriteInt8(int8(CollectionIsSameType))
	buf.WriteUint8(uint8(STRING))

	var stringsOut []string
	err := New(WithMaxGraphMemoryBytes(8*1024*1024)).Deserialize(buf.Bytes(), &stringsOut)
	require.Error(t, err)
	require.Contains(t, err.Error(), "buffer out of bound")

	buf = NewByteBuffer(nil)
	buf.WriteByte_(XLangFlag)
	buf.WriteInt8(NotNullValueFlag)
	buf.WriteUint8(uint8(INT32_ARRAY))
	buf.WriteLength(4096)

	var ints []int32
	err = New(WithMaxGraphMemoryBytes(8*1024*1024)).Deserialize(buf.Bytes(), &ints)
	require.Error(t, err)
	require.Contains(t, err.Error(), "buffer out of bound")
}
