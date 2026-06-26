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

func TestContainerMemoryBudgetConfig(t *testing.T) {
	require.Equal(t, int64(-1), New().config.MaxContainerMemoryBytes)
	require.Equal(t, int64(123), New(WithMaxContainerMemoryBytes(123)).config.MaxContainerMemoryBytes)
	require.Panics(t, func() { New(WithMaxContainerMemoryBytes(0)) })
	require.Panics(t, func() { New(WithMaxContainerMemoryBytes(-2)) })
}

func TestContainerMemoryBudgetAutoLimits(t *testing.T) {
	ctx := NewReadContext(false)
	ctx.initContainerMemoryBudget(10, false)
	require.False(t, ctx.HasError())
	require.Equal(t, int64(10)*knownRootBudgetMultiplier+knownRootBudgetSlackBytes, ctx.containerMemoryLimitBytes)
	require.True(t, ctx.ReserveContainerMemory(ctx.containerMemoryLimitBytes))
	require.False(t, ctx.ReserveContainerMemory(1))
	require.Contains(t, ctx.CheckError().Error(), "maxContainerMemoryBytes")

	ctx = NewReadContext(false)
	ctx.initContainerMemoryBudget(10, true)
	require.False(t, ctx.HasError())
	require.Equal(t, streamRootBudgetBytes, ctx.containerMemoryLimitBytes)
	require.True(t, ctx.ReserveContainerMemory(streamRootBudgetBytes))
	require.False(t, ctx.ReserveContainerMemory(1))
	require.Contains(t, ctx.CheckError().Error(), "maxContainerMemoryBytes")

	ctx = NewReadContext(false)
	ctx.maxContainerMemoryBytes = 77
	ctx.initContainerMemoryBudget(10, true)
	require.False(t, ctx.HasError())
	require.Equal(t, int64(77), ctx.containerMemoryLimitBytes)
}

func TestContainerMemoryBudgetKnownVsStreamRoot(t *testing.T) {
	writer := New(WithCompatible(false))
	values := make([]any, 12000)
	for i := range values {
		values[i] = []any{}
	}
	data, err := writer.Serialize(values)
	require.NoError(t, err)

	var fromBytes []any
	err = New(WithCompatible(false)).Deserialize(data, &fromBytes)
	require.Error(t, err)
	require.Contains(t, err.Error(), "maxContainerMemoryBytes")

	var fromStream []any
	err = New(WithCompatible(false)).DeserializeFromReader(bytes.NewReader(data), &fromStream)
	require.NoError(t, err)
	require.Len(t, fromStream, len(values))
}

func TestContainerMemoryBudgetExplicitOverride(t *testing.T) {
	writer := New(WithCompatible(false))
	values := make([]any, 12000)
	for i := range values {
		values[i] = []any{}
	}
	data, err := writer.Serialize(values)
	require.NoError(t, err)

	var out []any
	err = New(WithCompatible(false), WithMaxContainerMemoryBytes(4*1024*1024)).Deserialize(data, &out)
	require.NoError(t, err)
	require.Len(t, out, len(values))
}

func TestContainerMemoryBudgetEmptyAndCumulative(t *testing.T) {
	data, err := New(WithCompatible(false)).Serialize([]any{})
	require.NoError(t, err)
	var empty []any
	err = New(WithCompatible(false), WithMaxContainerMemoryBytes(sliceObjectBytes-1)).Deserialize(data, &empty)
	require.Error(t, err)
	require.Contains(t, err.Error(), "maxContainerMemoryBytes")

	writer := New(WithCompatible(false))
	require.NoError(t, writer.RegisterStructByName(budgetSiblings{}, "test.BudgetSiblings"))
	data, err = writer.Serialize(&budgetSiblings{A: []string{}, B: []string{}})
	require.NoError(t, err)
	reader := New(WithCompatible(false), WithMaxContainerMemoryBytes(sliceObjectBytes))
	require.NoError(t, reader.RegisterStructByName(budgetSiblings{}, "test.BudgetSiblings"))
	var out budgetSiblings
	err = reader.Deserialize(data, &out)
	require.Error(t, err)
	require.Contains(t, err.Error(), "maxContainerMemoryBytes")
}

func TestContainerMemoryBudgetMapAndOverflow(t *testing.T) {
	data, err := New().Serialize(map[string]string{"k": "v"})
	require.NoError(t, err)
	var out map[string]string
	oneEntryBudget := mapObjectBytes +
		2*referenceSlotBytes +
		mapEntryOverheadBytes + referenceSlotBytes +
		containerSizeOf[string]() + containerSizeOf[string]()
	err = New(WithMaxContainerMemoryBytes(oneEntryBudget-1)).Deserialize(data, &out)
	require.Error(t, err)
	require.Contains(t, err.Error(), "maxContainerMemoryBytes")

	ctx := NewReadContext(false)
	ctx.initContainerMemoryBudget(0, true)
	require.False(t, ctx.ReserveMapMemory(MaxInt, MaxInt64, 1))
	require.Contains(t, ctx.CheckError().Error(), "overflows")
}

func TestContainerMemoryBudgetSlicesAndInlineValues(t *testing.T) {
	data, err := New().Serialize([]string{"a"})
	require.NoError(t, err)
	var stringsOut []string
	err = New(WithMaxContainerMemoryBytes(sliceObjectBytes+containerSizeOf[string]()-1)).Deserialize(data, &stringsOut)
	require.Error(t, err)
	require.Contains(t, err.Error(), "maxContainerMemoryBytes")

	writer := New()
	require.NoError(t, writer.RegisterStructByName(budgetItem{}, "test.BudgetItem"))
	data, err = writer.Serialize([]budgetItem{{A: 1}})
	require.NoError(t, err)
	reader := New(WithMaxContainerMemoryBytes(sliceObjectBytes + containerSizeOf[budgetItem]() - 1))
	require.NoError(t, reader.RegisterStructByName(budgetItem{}, "test.BudgetItem"))
	var items []budgetItem
	err = reader.Deserialize(data, &items)
	require.Error(t, err)
	require.Contains(t, err.Error(), "maxContainerMemoryBytes")
}

func TestContainerMemoryBudgetSkipsDenseOwners(t *testing.T) {
	f := New(WithMaxContainerMemoryBytes(1))

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

func TestContainerMemoryBudgetPreservesByteChecks(t *testing.T) {
	buf := NewByteBuffer(nil)
	buf.WriteByte_(XLangFlag)
	buf.WriteInt8(NotNullValueFlag)
	buf.WriteUint8(uint8(LIST))
	buf.WriteLength(1024)
	buf.WriteInt8(int8(CollectionIsSameType))
	buf.WriteUint8(uint8(STRING))

	var stringsOut []string
	err := New(WithMaxContainerMemoryBytes(8*1024*1024)).Deserialize(buf.Bytes(), &stringsOut)
	require.Error(t, err)
	require.Contains(t, err.Error(), "buffer out of bound")

	buf = NewByteBuffer(nil)
	buf.WriteByte_(XLangFlag)
	buf.WriteInt8(NotNullValueFlag)
	buf.WriteUint8(uint8(INT32_ARRAY))
	buf.WriteLength(4096)

	var ints []int32
	err = New(WithMaxContainerMemoryBytes(8*1024*1024)).Deserialize(buf.Bytes(), &ints)
	require.Error(t, err)
	require.Contains(t, err.Error(), "buffer out of bound")
}
