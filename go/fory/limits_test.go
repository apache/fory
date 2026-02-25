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
	"fmt"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

// ============================================================================
// MaxStringBytes
// ============================================================================

func TestMaxStringBytesBlocksOversizedString(t *testing.T) {
	f := NewFory(WithXlang(true), WithMaxStringBytes(5))

	long := strings.Repeat("a", 20) // 20 bytes > limit 5
	data, err := f.Marshal(long)
	require.NoError(t, err) // write path has no limit

	var result string
	err = f.Unmarshal(data, &result)
	require.Error(t, err, "expected error: string exceeds MaxStringBytes")
}

func TestMaxStringBytesAllowsExactLimit(t *testing.T) {
	f := NewFory(WithXlang(true), WithMaxStringBytes(5))

	s := "hello" // exactly 5 bytes — must NOT be rejected (> not >=)
	data, err := f.Marshal(s)
	require.NoError(t, err)

	var result string
	require.NoError(t, f.Unmarshal(data, &result))
	require.Equal(t, s, result)
}

func TestMaxStringBytesAllowsWithinLimit(t *testing.T) {
	f := NewFory(WithXlang(true), WithMaxStringBytes(10))

	s := "hi" // 2 bytes, well within limit
	data, err := f.Marshal(s)
	require.NoError(t, err)

	var result string
	require.NoError(t, f.Unmarshal(data, &result))
	require.Equal(t, s, result)
}

func TestMaxStringBytesZeroMeansNoLimit(t *testing.T) {
	f := NewFory(WithXlang(true)) // default 0 = no limit

	long := strings.Repeat("x", 100_000)
	data, err := f.Marshal(long)
	require.NoError(t, err)

	var result string
	require.NoError(t, f.Unmarshal(data, &result))
	require.Equal(t, long, result)
}

// ============================================================================
// MaxCollectionSize
// ============================================================================

func TestMaxCollectionSizeBlocksOversizedSlice(t *testing.T) {
	f := NewFory(WithXlang(true), WithMaxCollectionSize(3))

	s := []string{"a", "b", "c", "d", "e"} // 5 elements > limit 3
	data, err := f.Marshal(s)
	require.NoError(t, err)

	var result []string
	err = f.Unmarshal(data, &result)
	require.Error(t, err, "expected error: slice exceeds MaxCollectionSize")
}

func TestMaxCollectionSizeAllowsWithinLimit(t *testing.T) {
	f := NewFory(WithXlang(true), WithMaxCollectionSize(5))

	s := []string{"a", "b", "c"} // 3 elements, within limit 5
	data, err := f.Marshal(s)
	require.NoError(t, err)

	var result []string
	require.NoError(t, f.Unmarshal(data, &result))
	require.Equal(t, s, result)
}

func TestMaxCollectionSizeAllowsExactLimit(t *testing.T) {
	f := NewFory(WithXlang(true), WithMaxCollectionSize(3))

	s := []string{"a", "b", "c"} // exactly 3 — must NOT be rejected
	data, err := f.Marshal(s)
	require.NoError(t, err)

	var result []string
	require.NoError(t, f.Unmarshal(data, &result))
	require.Equal(t, s, result)
}

func TestMaxCollectionSizeZeroMeansNoLimit(t *testing.T) {
	f := NewFory(WithXlang(true)) // default 0 = no limit

	s := make([]int32, 10_000)
	data, err := f.Marshal(s)
	require.NoError(t, err)

	var result []int32
	require.NoError(t, f.Unmarshal(data, &result))
	require.Equal(t, len(s), len(result))
}

// ============================================================================
// MaxMapSize
// ============================================================================

func TestMaxMapSizeBlocksOversizedMap(t *testing.T) {
	f := NewFory(WithXlang(true), WithMaxMapSize(2))

	m := map[string]string{"k1": "v1", "k2": "v2", "k3": "v3"} // 3 entries > limit 2
	data, err := f.Marshal(m)
	require.NoError(t, err)

	var result map[string]string
	err = f.Unmarshal(data, &result)
	require.Error(t, err, "expected error: map exceeds MaxMapSize")
}

func TestMaxMapSizeAllowsWithinLimit(t *testing.T) {
	f := NewFory(WithXlang(true), WithMaxMapSize(5))

	m := map[string]string{"k1": "v1", "k2": "v2"}
	data, err := f.Marshal(m)
	require.NoError(t, err)

	var result map[string]string
	require.NoError(t, f.Unmarshal(data, &result))
	require.Equal(t, m, result)
}

func TestMaxMapSizeAllowsExactLimit(t *testing.T) {
	f := NewFory(WithXlang(true), WithMaxMapSize(2))

	m := map[string]string{"k1": "v1", "k2": "v2"} // exactly 2 — must NOT be rejected
	data, err := f.Marshal(m)
	require.NoError(t, err)

	var result map[string]string
	require.NoError(t, f.Unmarshal(data, &result))
	require.Equal(t, m, result)
}

func TestMaxMapSizeZeroMeansNoLimit(t *testing.T) {
	f := NewFory(WithXlang(true)) // default 0 = no limit

	m := make(map[string]string, 1000)
	for i := 0; i < 1000; i++ {
		m[fmt.Sprintf("k%d", i)] = "v"
	}
	data, err := f.Marshal(m)
	require.NoError(t, err)

	var result map[string]string
	require.NoError(t, f.Unmarshal(data, &result))
	require.Equal(t, 1000, len(result))
}

// ============================================================================
// Combined limits
// ============================================================================

func TestCombinedLimitsStringInsideSlice(t *testing.T) {
	// Slice size is within limit, but one element string is too long
	f := NewFory(WithXlang(true), WithMaxCollectionSize(10), WithMaxStringBytes(3))

	s := []string{"ab", "cd", "this-is-too-long"} // third element 16 bytes > limit 3
	data, err := f.Marshal(s)
	require.NoError(t, err)

	var result []string
	err = f.Unmarshal(data, &result)
	require.Error(t, err)
}

func TestCombinedLimitsCollectionFiresBeforeString(t *testing.T) {
	// Collection limit fires before any string element is read
	f := NewFory(WithXlang(true), WithMaxCollectionSize(2), WithMaxStringBytes(1000))

	s := []string{"a", "b", "c", "d"} // 4 elements > collection limit 2
	data, err := f.Marshal(s)
	require.NoError(t, err)

	var result []string
	err = f.Unmarshal(data, &result)
	require.Error(t, err)
}

func TestCombinedLimitsAllWithinBounds(t *testing.T) {
	// All limits set, all values within bounds — must succeed end-to-end
	f := NewFory(WithXlang(true),
		WithMaxStringBytes(20),
		WithMaxCollectionSize(10),
		WithMaxMapSize(10),
	)

	s := []string{"hello", "world"}
	data, err := f.Marshal(s)
	require.NoError(t, err)
	var sliceResult []string
	require.NoError(t, f.Unmarshal(data, &sliceResult))
	require.Equal(t, s, sliceResult)

	m := map[string]string{"k1": "v1", "k2": "v2"}
	data, err = f.Marshal(m)
	require.NoError(t, err)
	var mapResult map[string]string
	require.NoError(t, f.Unmarshal(data, &mapResult))
	require.Equal(t, m, mapResult)
}
