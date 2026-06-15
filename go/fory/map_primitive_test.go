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

func TestPrimitiveMapReaderRejectsInvalidChunkSize(t *testing.T) {
	f := NewFory(WithXlang(false), WithCompatible(false))
	buf := NewByteBuffer(nil)
	buf.WriteLength(1)
	buf.WriteUint8(KEY_DECL_TYPE | VALUE_DECL_TYPE)
	buf.WriteUint8(2)

	f.readCtx.SetData(buf.Bytes())
	_ = f.readCtx.ReadStringStringMap(RefModeNone, false)
	require.Error(t, f.readCtx.CheckError())
}

func TestPrimitiveMapReaderRejectsUnexpectedTypeInfo(t *testing.T) {
	f := NewFory(WithXlang(false), WithCompatible(false))
	buf := NewByteBuffer(nil)
	buf.WriteLength(1)
	buf.WriteUint8(0)
	buf.WriteUint8(1)
	buf.WriteUint8(uint8(STRING))
	buf.WriteUint8(uint8(BOOL))

	f.readCtx.SetData(buf.Bytes())
	_ = f.readCtx.ReadStringStringMap(RefModeNone, false)
	require.Error(t, f.readCtx.CheckError())
}

func TestPrimitiveMapReaderRejectsNullChunks(t *testing.T) {
	f := NewFory(WithXlang(false), WithCompatible(false))
	buf := NewByteBuffer(nil)
	buf.WriteLength(1)
	buf.WriteUint8(KEY_HAS_NULL)

	f.readCtx.SetData(buf.Bytes())
	_ = f.readCtx.ReadStringStringMap(RefModeNone, false)
	require.Error(t, f.readCtx.CheckError())
}
