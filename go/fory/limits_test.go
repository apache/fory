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
	"strings"
	"testing"
)

// TestSizeGuardrails_SliceExceedsLimit verifies that deserializing a slice
// whose element count exceeds MaxCollectionSize returns an error.
func TestSizeGuardrails_SliceExceedsLimit(t *testing.T) {
	// Serialize a string slice with 5 elements using no limit
	f1 := New(WithXlang(true), WithMaxCollectionSize(0))
	data := []string{"a", "b", "c", "d", "e"}
	bytes, err := f1.Marshal(data)
	if err != nil {
		t.Fatalf("serialize failed: %v", err)
	}

	// Deserialize with a limit of 3 — should fail
	f2 := New(WithXlang(true), WithMaxCollectionSize(3))
	var result any
	err2 := f2.Unmarshal(bytes, &result)
	if err2 == nil {
		t.Fatal("expected error when collection size exceeds limit, got nil")
	}
	if !strings.Contains(err2.Error(), "exceeds limit") {
		t.Fatalf("expected 'exceeds limit' error, got: %v", err2)
	}
}

// TestSizeGuardrails_SliceWithinLimit verifies that a slice within limits
// deserializes successfully.
func TestSizeGuardrails_SliceWithinLimit(t *testing.T) {
	f := New(WithXlang(true), WithMaxCollectionSize(100))
	data := []int32{1, 2, 3}
	bytes, err := f.Marshal(data)
	if err != nil {
		t.Fatalf("serialize failed: %v", err)
	}
	var result any
	err = f.Unmarshal(bytes, &result)
	if err != nil {
		t.Fatalf("deserialize should succeed within limit: %v", err)
	}
}

// TestSizeGuardrails_MapExceedsLimit verifies that deserializing a map
// whose entry count exceeds MaxCollectionSize returns an error.
func TestSizeGuardrails_MapExceedsLimit(t *testing.T) {
	f1 := New(WithXlang(true), WithMaxCollectionSize(0))
	m := map[string]string{"a": "1", "b": "2", "c": "3", "d": "4", "e": "5"}
	bytes, err := f1.Marshal(m)
	if err != nil {
		t.Fatalf("serialize failed: %v", err)
	}

	f2 := New(WithXlang(true), WithMaxCollectionSize(2))
	var result any
	err2 := f2.Unmarshal(bytes, &result)
	if err2 == nil {
		t.Fatal("expected error when map size exceeds limit, got nil")
	}
	if !strings.Contains(err2.Error(), "exceeds limit") {
		t.Fatalf("expected 'exceeds limit' error, got: %v", err2)
	}
}

// TestSizeGuardrails_MapWithinLimit verifies that a map within limits
// deserializes successfully.
func TestSizeGuardrails_MapWithinLimit(t *testing.T) {
	f := New(WithXlang(true), WithMaxCollectionSize(100))
	m := map[string]string{"a": "1", "b": "2"}
	bytes, err := f.Marshal(m)
	if err != nil {
		t.Fatalf("serialize failed: %v", err)
	}
	var result any
	err = f.Unmarshal(bytes, &result)
	if err != nil {
		t.Fatalf("deserialize should succeed within limit: %v", err)
	}
}

// TestSizeGuardrails_DefaultConfig verifies that default limits are set.
func TestSizeGuardrails_DefaultConfig(t *testing.T) {
	f := New()
	if f.config.MaxBinarySize != 64*1024*1024 {
		t.Fatalf("expected default MaxBinarySize=64MB, got %d", f.config.MaxBinarySize)
	}
	if f.config.MaxCollectionSize != 1_000_000 {
		t.Fatalf("expected default MaxCollectionSize=1000000, got %d", f.config.MaxCollectionSize)
	}
}

// TestSizeGuardrails_NoLimitWhenZero verifies that 0 means unlimited.
func TestSizeGuardrails_NoLimitWhenZero(t *testing.T) {
	f := New(WithXlang(true), WithMaxCollectionSize(0))
	data := []int32{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}
	bytes, err := f.Marshal(data)
	if err != nil {
		t.Fatalf("serialize failed: %v", err)
	}
	var result any
	err = f.Unmarshal(bytes, &result)
	if err != nil {
		t.Fatalf("deserialize with no limit should succeed: %v", err)
	}
}
