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
	"testing"

	"github.com/stretchr/testify/require"
)

type zeroBodyValue struct {
	ID int
}

type zeroBodyCodec struct{}

func (zeroBodyCodec) WriteData(_ *WriteContext, _ reflect.Value) {}

func (zeroBodyCodec) ReadData(_ *ReadContext, value reflect.Value) {
	value.Set(reflect.Zero(value.Type()))
}

type progressingValue struct {
	Value int32
}

type partialProgressCodec struct {
	index int
}

func (s *partialProgressCodec) WriteData(ctx *WriteContext, _ reflect.Value) {
	if s.index&1 == 0 {
		ctx.Buffer().WriteByte(1)
	}
	s.index++
}

func (s *partialProgressCodec) ReadData(ctx *ReadContext, value reflect.Value) {
	if s.index&1 == 0 {
		ctx.Buffer().ReadByte(ctx.Err())
	}
	s.index++
	value.Set(reflect.Zero(value.Type()))
}

func newZeroBodyFory(items int64) *Fory {
	f := New(
		WithXlang(true),
		WithCompatible(false),
		WithMaxUnbackedContainerItems(items),
	)
	if err := f.RegisterExtensionByName(zeroBodyValue{}, "test.ZeroBodyValue", zeroBodyCodec{}); err != nil {
		panic(err)
	}
	return f
}

func TestUnbackedContainerConfig(t *testing.T) {
	require.Equal(t, int64(8192), New().config.MaxUnbackedContainerItems)
	require.Equal(t, int64(0), New(WithMaxUnbackedContainerItems(0)).config.MaxUnbackedContainerItems)
	require.Panics(t, func() { WithMaxUnbackedContainerItems(-1) })
}

func TestUnbackedCollectionBudget(t *testing.T) {
	writer := newZeroBodyFory(8192)
	rejected, err := writer.Serialize([]zeroBodyValue{{ID: 1}, {ID: 2}, {ID: 3}})
	require.NoError(t, err)
	rejected = bytes.Clone(rejected)
	accepted, err := writer.Serialize([]zeroBodyValue{{ID: 1}, {ID: 2}})
	require.NoError(t, err)
	accepted = bytes.Clone(accepted)

	reader := newZeroBodyFory(2)
	var out []zeroBodyValue
	require.Error(t, reader.Deserialize(rejected, &out))
	require.NoError(t, reader.Deserialize(accepted, &out))
	require.Len(t, out, 2)
}

func TestUnbackedCollectionTail(t *testing.T) {
	writer := newZeroBodyFory(8192)
	values := make([]zeroBodyValue, 1025)
	data, err := writer.Serialize(values)
	require.NoError(t, err)

	reader := newZeroBodyFory(1024)
	var out []zeroBodyValue
	require.Error(t, reader.Deserialize(data, &out))
}

func TestUnbackedSetAndMapBudget(t *testing.T) {
	writer := newZeroBodyFory(8192)
	setData, err := writer.Serialize(Set[zeroBodyValue]{
		{ID: 1}: {},
		{ID: 2}: {},
		{ID: 3}: {},
	})
	require.NoError(t, err)
	setData = bytes.Clone(setData)
	mapData, err := writer.Serialize(map[zeroBodyValue]zeroBodyValue{
		{ID: 1}: {ID: 4},
		{ID: 2}: {ID: 5},
		{ID: 3}: {ID: 6},
	})
	require.NoError(t, err)
	mapData = bytes.Clone(mapData)

	reader := newZeroBodyFory(2)
	var setOut Set[zeroBodyValue]
	require.Error(t, reader.Deserialize(setData, &setOut))
	var mapOut map[zeroBodyValue]zeroBodyValue
	require.Error(t, reader.Deserialize(mapData, &mapOut))
}

func TestGeneratedStructProgress(t *testing.T) {
	type emptyValue struct{}

	emptyFory := New(
		WithXlang(true),
		WithCompatible(true),
		WithMaxUnbackedContainerItems(2),
	)
	require.NoError(t, emptyFory.RegisterStructByName(emptyValue{}, "test.EmptyValue"))
	data, err := emptyFory.Serialize([]emptyValue{{}, {}, {}})
	require.NoError(t, err)
	var emptyOut []emptyValue
	require.Error(t, emptyFory.Deserialize(data, &emptyOut))

	progressingFory := New(
		WithXlang(true),
		WithCompatible(true),
		WithMaxUnbackedContainerItems(0),
	)
	require.NoError(t, progressingFory.RegisterStructByName(progressingValue{}, "test.ProgressingValue"))
	values := []progressingValue{{Value: 1}, {Value: 2}, {Value: 3}}
	data, err = progressingFory.Serialize(values)
	require.NoError(t, err)
	var progressingOut []progressingValue
	require.NoError(t, progressingFory.Deserialize(data, &progressingOut))
	require.Equal(t, values, progressingOut)
}

func TestSkipUnbackedContainers(t *testing.T) {
	f := New(WithMaxUnbackedContainerItems(2))

	collection := NewByteBuffer(nil)
	collection.WriteVarUint32(3)
	collection.WriteByte(CollectionDeclSameType)
	f.readCtx.SetData(collection.Bytes())
	f.readCtx.remainingUnbackedContainerItems = f.config.MaxUnbackedContainerItems
	skipCollection(f.readCtx, FieldDef{
		typeSpec: NewCollectionTypeSpec(LIST, NewSimpleTypeSpec(NONE)),
	})
	require.Error(t, f.readCtx.CheckError())
	f.resetReadState()

	mapBuffer := NewByteBuffer(nil)
	mapBuffer.WriteVarUint32(3)
	mapBuffer.WriteByte(KEY_DECL_TYPE | VALUE_DECL_TYPE)
	mapBuffer.WriteByte(3)
	f.readCtx.SetData(mapBuffer.Bytes())
	f.readCtx.remainingUnbackedContainerItems = f.config.MaxUnbackedContainerItems
	skipMap(f.readCtx, FieldDef{
		typeSpec: NewMapTypeSpec(MAP, NewSimpleTypeSpec(NONE), NewSimpleTypeSpec(NONE)),
	})
	require.Error(t, f.readCtx.CheckError())
}

func TestStreamLogicalProgress(t *testing.T) {
	writerCodec := &partialProgressCodec{}
	writer := New(WithCompatible(false))
	require.NoError(t, writer.RegisterExtensionByName(
		zeroBodyValue{}, "test.PartialProgressValue", writerCodec))
	data, err := writer.Serialize(make([]zeroBodyValue, 2048))
	require.NoError(t, err)
	data = append(bytes.Clone(data), 0x7f)

	readerCodec := &partialProgressCodec{}
	reader := New(
		WithCompatible(false),
		WithMaxUnbackedContainerItems(1023),
	)
	require.NoError(t, reader.RegisterExtensionByName(
		zeroBodyValue{}, "test.PartialProgressValue", readerCodec))
	stream := NewInputStreamWithBufferSize(bytes.NewReader(data), 32)
	var out []zeroBodyValue
	require.Error(t, reader.DeserializeFromStream(stream, &out))
}
