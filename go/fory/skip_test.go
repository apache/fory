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
	"testing"

	"github.com/stretchr/testify/require"
)

func TestSkipEnumConsumesSmall7Ordinal(t *testing.T) {
	f := New(WithXlang(true), WithCompatible(false))
	buf := NewByteBuffer(nil)
	buf.WriteVarUint32Small7(128)
	buf.WriteByte(0x7f)

	f.readCtx.SetData(buf.Bytes())
	skipValue(
		f.readCtx,
		FieldDef{typeSpec: NewSimpleTypeSpec(ENUM), nullable: true},
		false,
		false,
		nil,
	)
	require.NoError(t, f.readCtx.CheckError())
	require.Equal(t, 2, f.readCtx.Buffer().ReaderIndex())
	require.Equal(t, byte(0x7f), f.readCtx.Buffer().ReadByte(f.readCtx.Err()))
}

func TestSkipPrimitiveConsumesExactEncoding(t *testing.T) {
	tests := []struct {
		name   string
		typeID TypeId
		write  func(*ByteBuffer)
	}{
		{
			name:   "int32",
			typeID: INT32,
			write:  func(buf *ByteBuffer) { buf.WriteInt32(0x01020304) },
		},
		{
			name:   "varint32",
			typeID: VARINT32,
			write:  func(buf *ByteBuffer) { buf.WriteVarint32(300) },
		},
		{
			name:   "int64",
			typeID: INT64,
			write:  func(buf *ByteBuffer) { buf.WriteInt64(0x0102030405060708) },
		},
		{
			name:   "varint64",
			typeID: VARINT64,
			write:  func(buf *ByteBuffer) { buf.WriteVarint64(1 << 35) },
		},
		{
			name:   "tagged_int64_small",
			typeID: TAGGED_INT64,
			write:  func(buf *ByteBuffer) { buf.WriteTaggedInt64(1073741823) },
		},
		{
			name:   "tagged_int64_large",
			typeID: TAGGED_INT64,
			write:  func(buf *ByteBuffer) { buf.WriteTaggedInt64(1 << 40) },
		},
		{
			name:   "tagged_uint64_small",
			typeID: TAGGED_UINT64,
			write:  func(buf *ByteBuffer) { buf.WriteTaggedUint64(0x7fffffff) },
		},
		{
			name:   "tagged_uint64_large",
			typeID: TAGGED_UINT64,
			write:  func(buf *ByteBuffer) { buf.WriteTaggedUint64(1 << 40) },
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := New(WithXlang(true), WithCompatible(false))
			buf := NewByteBuffer(nil)
			tc.write(buf)
			wantIndex := buf.WriterIndex()
			buf.WriteByte(0x7f)

			f.readCtx.SetData(buf.Bytes())
			skipValue(
				f.readCtx,
				FieldDef{typeSpec: NewSimpleTypeSpec(tc.typeID), nullable: true},
				false,
				false,
				nil,
			)
			require.NoError(t, f.readCtx.CheckError())
			require.Equal(t, wantIndex, f.readCtx.Buffer().ReaderIndex())
			require.Equal(t, byte(0x7f), f.readCtx.Buffer().ReadByte(f.readCtx.Err()))
		})
	}
}

func TestSkipMapRejectsInvalidChunkSize(t *testing.T) {
	f := New(WithXlang(true), WithCompatible(false))
	buf := NewByteBuffer(nil)
	buf.WriteLength(1)
	buf.WriteByte(KEY_DECL_TYPE | VALUE_DECL_TYPE)
	buf.WriteByte(2)

	f.readCtx.SetData(buf.Bytes())
	skipMap(
		f.readCtx,
		FieldDef{
			typeSpec: NewMapTypeSpec(MAP, NewSimpleTypeSpec(INT32), NewSimpleTypeSpec(INT32)),
			nullable: true,
		},
	)
	require.Error(t, f.readCtx.CheckError())
}

func TestSkipTrackedValueReservesRefId(t *testing.T) {
	f := New(WithXlang(true), WithCompatible(true), WithTrackRef(true))
	buf := NewByteBuffer(nil)
	buf.WriteInt8(RefValueFlag)
	buf.WriteLength(0)

	f.readCtx.SetData(buf.Bytes())
	skipValue(
		f.readCtx,
		FieldDef{
			typeSpec: NewCollectionTypeSpec(LIST, NewSimpleTypeSpec(STRING)),
			trackRef: true,
		},
		true,
		false,
		nil,
	)
	require.NoError(t, f.readCtx.CheckError())

	resolver := f.readCtx.RefResolver()
	require.Len(t, resolver.readObjects, 1)
	require.Empty(t, resolver.readRefIds)

	nextRefId, err := resolver.PreserveRefId()
	require.NoError(t, err)
	require.Equal(t, int32(1), nextRefId)
}

func TestSkipCollectionConsumesNullElementFlag(t *testing.T) {
	tests := []struct {
		name   string
		header byte
	}{
		{name: "same_type_declared", header: CollectionDeclSameType | CollectionHasNull},
		{name: "element_type_per_value", header: CollectionHasNull},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := New(WithXlang(true), WithCompatible(false))
			buf := NewByteBuffer(nil)
			buf.WriteLength(1)
			buf.WriteByte(tc.header)
			buf.WriteInt8(NullFlag)
			buf.WriteByte(0x7f)

			f.readCtx.SetData(buf.Bytes())
			skipCollection(
				f.readCtx,
				FieldDef{
					typeSpec: NewCollectionTypeSpec(LIST, NewSimpleTypeSpec(INT32)),
					nullable: true,
				},
			)
			require.NoError(t, f.readCtx.CheckError())
			require.Equal(t, byte(0x7f), f.readCtx.Buffer().ReadByte(f.readCtx.Err()))
		})
	}
}
