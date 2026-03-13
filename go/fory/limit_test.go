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
	"github.com/stretchr/testify/require"
	"testing"
)

func TestMaxCollectionSizeGuardrail(t *testing.T) {
	// 1. Test slice exceeding limit
	t.Run("Slice exceeds MaxCollectionSize", func(t *testing.T) {
		config := WithMaxCollectionSize(2)
		f := NewFory(config)

		slice := []string{"a", "b", "c"}
		fBase := NewFory()
		bytes, _ := fBase.Serialize(slice)

		var decoded []string
		err := f.Deserialize(bytes, &decoded)
		require.Error(t, err)
		require.Contains(t, err.Error(), "max collection size exceeded: size=3, limit=2")
	})

	// 2. Test map exceeding limit
	t.Run("Map exceeds MaxCollectionSize", func(t *testing.T) {
		config := WithMaxCollectionSize(2)
		f := NewFory(config)

		m := map[int32]int32{1: 1, 2: 2, 3: 3}
		fBase := NewFory()
		bytes, _ := fBase.Serialize(m)

		var decoded map[int32]int32
		err := f.Deserialize(bytes, &decoded)
		require.Error(t, err)
		require.Contains(t, err.Error(), "max collection size exceeded: size=3, limit=2")
	})

	// 3. Test string is not affected by MaxCollectionSize
	t.Run("String unaffected by MaxCollectionSize", func(t *testing.T) {
		config := WithMaxCollectionSize(2)
		f := NewFory(config)

		str := "hello world" // length 11
		bytes, err := f.Serialize(str)
		require.NoError(t, err)

		var decoded string
		err = f.Deserialize(bytes, &decoded)
		require.NoError(t, err)
		require.Equal(t, str, decoded)
	})
}

func TestMaxBinarySizeGuardrail(t *testing.T) {
	// 1. Test binary (byte slice) exceeding limit
	t.Run("Byte slice exceeds MaxBinarySize", func(t *testing.T) {
		config := WithMaxBinarySize(5)
		f := NewFory(config)

		// We can serialize a byte slice using standard serializer, then decode with the f instance
		slice := []byte{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}
		fBase := NewFory()
		bytes, _ := fBase.Serialize(slice)

		var decoded []byte
		err := f.Deserialize(bytes, &decoded)
		require.Error(t, err)
		require.Contains(t, err.Error(), "max binary size exceeded: size=10, limit=5")
	})

	// 2. Test string is not affected by MaxBinarySize
	t.Run("String unaffected by MaxBinarySize", func(t *testing.T) {
		config := WithMaxBinarySize(2)
		f := NewFory(config)

		str := "hello world" // length 11
		bytes, err := f.Serialize(str)
		require.NoError(t, err)

		var decoded string
		err = f.Deserialize(bytes, &decoded)
		require.NoError(t, err)
		require.Equal(t, str, decoded)
	})
}
