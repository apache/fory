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
	"strconv"
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

func buildPrimitiveMap[K comparable, V any](size int, entry func(int) (K, V)) map[K]V {
	result := make(map[K]V, size)
	for i := 0; i < size; i++ {
		key, value := entry(i)
		result[key] = value
	}
	return result
}

func requirePrimitiveMapRoundTrip[K comparable, V comparable](t *testing.T, value map[K]V) {
	t.Helper()
	f := NewFory(WithXlang(false), WithCompatible(false))
	data, err := Serialize(f, value)
	require.NoError(t, err)

	var result map[K]V
	require.NoError(t, Deserialize(f, data, &result))
	require.Equal(t, value, result)
}

func TestPrimitiveMapChunks(t *testing.T) {
	for _, size := range []int{MAX_CHUNK_SIZE + 1, MAX_CHUNK_SIZE*2 + 1} {
		t.Run(strconv.Itoa(size), func(t *testing.T) {
			t.Run("string_string", func(t *testing.T) {
				value := buildPrimitiveMap(size, func(i int) (string, string) {
					return strconv.Itoa(i), strconv.Itoa(i*3 + 1)
				})
				requirePrimitiveMapRoundTrip(t, value)
			})
			t.Run("string_int64", func(t *testing.T) {
				value := buildPrimitiveMap(size, func(i int) (string, int64) {
					return strconv.Itoa(i), int64(i*3 + 1)
				})
				requirePrimitiveMapRoundTrip(t, value)
			})
			t.Run("string_int32", func(t *testing.T) {
				value := buildPrimitiveMap(size, func(i int) (string, int32) {
					return strconv.Itoa(i), int32(i*3 + 1)
				})
				requirePrimitiveMapRoundTrip(t, value)
			})
			t.Run("string_int", func(t *testing.T) {
				value := buildPrimitiveMap(size, func(i int) (string, int) {
					return strconv.Itoa(i), i*3 + 1
				})
				requirePrimitiveMapRoundTrip(t, value)
			})
			t.Run("string_float64", func(t *testing.T) {
				value := buildPrimitiveMap(size, func(i int) (string, float64) {
					return strconv.Itoa(i), float64(i) + 0.25
				})
				requirePrimitiveMapRoundTrip(t, value)
			})
			t.Run("string_bool", func(t *testing.T) {
				value := buildPrimitiveMap(size, func(i int) (string, bool) {
					return strconv.Itoa(i), i%2 == 0
				})
				requirePrimitiveMapRoundTrip(t, value)
			})
			t.Run("int32_int32", func(t *testing.T) {
				value := buildPrimitiveMap(size, func(i int) (int32, int32) {
					return int32(i), int32(i*3 + 1)
				})
				requirePrimitiveMapRoundTrip(t, value)
			})
			t.Run("int64_int64", func(t *testing.T) {
				value := buildPrimitiveMap(size, func(i int) (int64, int64) {
					return int64(i), int64(i*3 + 1)
				})
				requirePrimitiveMapRoundTrip(t, value)
			})
			t.Run("int_int", func(t *testing.T) {
				value := buildPrimitiveMap(size, func(i int) (int, int) {
					return i, i*3 + 1
				})
				requirePrimitiveMapRoundTrip(t, value)
			})
		})
	}
}
