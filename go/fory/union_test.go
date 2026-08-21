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
	"reflect"
	"testing"

	"github.com/stretchr/testify/require"
)

type concreteUnionA struct {
	caseID uint32
	value  any
}

func (concreteUnionA) ForyUnionMarker() {}

func (u concreteUnionA) ForyUnionGet() (uint32, any) {
	return u.caseID, u.value
}

func (u *concreteUnionA) ForyUnionSet(caseID uint32, value any) {
	u.caseID = caseID
	u.value = value
}

type concreteUnionB struct {
	caseID uint32
	value  any
}

func (concreteUnionB) ForyUnionMarker() {}

func (u concreteUnionB) ForyUnionGet() (uint32, any) {
	return u.caseID, u.value
}

func (u *concreteUnionB) ForyUnionSet(caseID uint32, value any) {
	u.caseID = caseID
	u.value = value
}

func newConcreteUnionFory(t *testing.T) (*Fory, *UnionSerializer) {
	t.Helper()
	fory := NewFory(WithXlang(false), WithCompatible(true), WithTrackRef(false))
	caseInfo := UnionCase{ID: 0, Type: reflect.TypeOf(int32(0)), TypeID: INT32}
	serializerA := NewUnionSerializer(caseInfo)
	serializerB := NewUnionSerializer(caseInfo)
	require.NoError(t, fory.RegisterUnionByName(concreteUnionA{}, "test.ConcreteUnionA", serializerA))
	require.NoError(t, fory.RegisterUnionByName(concreteUnionB{}, "test.ConcreteUnionB", serializerB))
	return fory, serializerA
}

func remoteUnionOwnerTypeDef(t *testing.T, fory *Fory) *TypeDef {
	t.Helper()
	return alternateNamedTypeDef(
		t, fory, reflect.TypeOf(concreteUnionB{}), "test", "ConcreteUnionB")
}

func readUnionOwner(t *testing.T, fory *Fory, serializer *UnionSerializer, body []byte) error {
	t.Helper()
	fory.readCtx.Reset()
	fory.readCtx.SetData(body)
	var target concreteUnionA
	serializer.Read(
		fory.readCtx, RefModeNone, true, false, reflect.ValueOf(&target).Elem())
	return fory.readCtx.CheckError()
}

func TestUnionConcreteTypeOwner(t *testing.T) {
	t.Run("shared reference", func(t *testing.T) {
		fory, serializer := newConcreteUnionFory(t)
		localB, err := fory.typeResolver.getTypeDef(reflect.TypeOf(concreteUnionB{}), true)
		require.NoError(t, err)
		headerErr := &Error{}
		header := NewByteBuffer(localB.encoded).ReadInt64(headerErr)
		require.NoError(t, headerErr.CheckError())

		readErr := &Error{}
		info := fory.typeResolver.readSharedTypeMeta(
			sharedTypeDefFrame(header, localB.encoded[8:]), nil, readErr)
		require.NoError(t, readErr.CheckError())
		require.Equal(t, reflect.TypeOf(concreteUnionB{}), info.Type)
		require.Empty(t, fory.typeResolver.defIdToTypeDef)
		require.Zero(t, fory.typeResolver.totalAcceptedSchemaVersions)

		wire := NewByteBuffer(nil)
		wire.WriteUint8(uint8(NAMED_UNION))
		wire.WriteVarUint32(1)
		err = readUnionOwner(t, fory, serializer, wire.Bytes())
		require.Error(t, err)
		require.Contains(t, err.Error(), "does not match declared type")
		require.Len(t, fory.MetaContext().readTypeInfos, 1)
		require.Empty(t, fory.typeResolver.defIdToTypeDef)
		require.Zero(t, fory.typeResolver.totalAcceptedSchemaVersions)
	})

	t.Run("cold miss", func(t *testing.T) {
		fory, serializer := newConcreteUnionFory(t)
		remoteTd := remoteUnionOwnerTypeDef(t, fory)
		headerErr := &Error{}
		header := NewByteBuffer(remoteTd.encoded).ReadInt64(headerErr)
		require.NoError(t, headerErr.CheckError())
		identity := typeDefIdentity(header)

		wire := NewByteBuffer(nil)
		wire.WriteUint8(uint8(NAMED_UNION))
		wire.WriteVarUint32(0)
		remoteTd.writeTypeDef(wire, &Error{})
		err := readUnionOwner(t, fory, serializer, wire.Bytes())
		require.Error(t, err)
		require.Contains(t, err.Error(), "does not match declared type")
		require.Empty(t, fory.MetaContext().readTypeInfos)
		require.NotContains(t, fory.typeResolver.defIdToTypeDef, identity)
		require.Zero(t, fory.typeResolver.totalAcceptedSchemaVersions)
	})

	t.Run("checked cache", func(t *testing.T) {
		fory, serializer := newConcreteUnionFory(t)
		remoteTd := remoteUnionOwnerTypeDef(t, fory)
		headerErr := &Error{}
		header := NewByteBuffer(remoteTd.encoded).ReadInt64(headerErr)
		require.NoError(t, headerErr.CheckError())
		identity := typeDefIdentity(header)

		require.NoError(t, readRemoteTypeDef(t, fory, remoteTd))
		cachedTd := fory.typeResolver.defIdToTypeDef[identity]
		require.NotNil(t, cachedTd)
		require.Equal(t, int64(1), fory.typeResolver.totalAcceptedSchemaVersions)
		fory.MetaContext().Reset()

		wire := NewByteBuffer(nil)
		wire.WriteUint8(uint8(NAMED_UNION))
		wire.WriteVarUint32(0)
		remoteTd.writeTypeDef(wire, &Error{})
		err := readUnionOwner(t, fory, serializer, wire.Bytes())
		require.Error(t, err)
		require.Contains(t, err.Error(), "does not match declared type")
		require.Empty(t, fory.MetaContext().readTypeInfos)
		require.Same(t, cachedTd, fory.typeResolver.defIdToTypeDef[identity])
		require.Equal(t, int64(1), fory.typeResolver.totalAcceptedSchemaVersions)
	})
}
