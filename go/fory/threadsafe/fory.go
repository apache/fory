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

// Package threadsafe provides a thread-safe wrapper around Fory using sync.Pool.
package threadsafe

import (
	"sync"

	"github.com/apache/fory/go/fory"
)

// Fory is a thread-safe wrapper around fory.Fory using sync.Pool.
// It provides the same API as fory.Fory but is safe for concurrent use.
type Fory struct {
	pool sync.Pool
}

// New creates a new thread-safe Fory instance
func New(opts ...fory.Option) *Fory {
	f := &Fory{}
	f.pool = sync.Pool{
		New: func() any {
			return fory.New(opts...)
		},
	}
	return f
}

func (f *Fory) acquire() *fory.Fory {
	return f.pool.Get().(*fory.Fory)
}

func (f *Fory) release(inner *fory.Fory) {
	inner.Reset()
	f.pool.Put(inner)
}

// ============================================================================
// Non-generic methods
// ============================================================================

// Serialize serializes a value using a pooled Fory instance
func (f *Fory) Serialize(v interface{}) ([]byte, error) {
	inner := f.acquire()
	defer f.release(inner)
	return inner.Marshal(v)
}

// Deserialize deserializes data into the provided value using a pooled Fory instance
func (f *Fory) Deserialize(data []byte, v interface{}) error {
	inner := f.acquire()
	defer f.release(inner)
	return inner.Unmarshal(data, v)
}

// SerializeAny serializes polymorphic values where concrete type is unknown
func (f *Fory) SerializeAny(value any) ([]byte, error) {
	inner := f.acquire()
	defer f.release(inner)
	return inner.SerializeAny(value)
}

// DeserializeAny deserializes polymorphic values
func (f *Fory) DeserializeAny(data []byte) (any, error) {
	inner := f.acquire()
	defer f.release(inner)
	return inner.DeserializeAny(data)
}

// RegisterNamedType registers a named type for cross-language serialization
func (f *Fory) RegisterNamedType(type_ interface{}, typeName string) error {
	inner := f.acquire()
	defer f.release(inner)
	return inner.RegisterNamedType(type_, typeName)
}

// ============================================================================
// Generic package-level functions
// ============================================================================

// Serialize serializes a value with type T inferred, thread-safe.
// Takes pointer to avoid interface heap allocation and struct copy.
func Serialize[T any](f *Fory, value *T) ([]byte, error) {
	inner := f.acquire()
	defer f.release(inner)
	return fory.Serialize(inner, value)
}

// Deserialize deserializes data directly into the provided target, thread-safe.
// Takes pointer to avoid interface heap allocation and enable direct writes.
func Deserialize[T any](f *Fory, data []byte, target *T) error {
	inner := f.acquire()
	defer f.release(inner)
	return fory.Deserialize(inner, data, target)
}

// ============================================================================
// Global convenience functions
// ============================================================================

// Global thread-safe Fory instance for convenience
var globalFory = New()

// Marshal serializes a value using the global thread-safe instance.
// Takes pointer to avoid interface heap allocation and struct copy.
func Marshal[T any](value *T) ([]byte, error) {
	return Serialize(globalFory, value)
}

// Unmarshal deserializes data into the provided target using the global thread-safe instance.
// Takes pointer to avoid interface heap allocation and enable direct writes.
func Unmarshal[T any](data []byte, target *T) error {
	return Deserialize(globalFory, data, target)
}

// UnmarshalTo deserializes data into the provided pointer using the global thread-safe instance.
// This is for non-generic use cases.
func UnmarshalTo(data []byte, v interface{}) error {
	return globalFory.Deserialize(data, v)
}
