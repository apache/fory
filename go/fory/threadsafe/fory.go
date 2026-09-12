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

// Package threadsafe provides a thread-safe wrapper around Fory.
package threadsafe

import (
	"fmt"
	"reflect"
	"sync"
	"sync/atomic"

	"github.com/apache/fory/go/fory"
)

// Fory is a thread-safe wrapper around fory.Fory using sync.Pool.
// Struct and enum registration use the same API as fory.Fory and must finish
// before the first serialization or deserialization operation.
type Fory struct {
	pool           sync.Pool
	registrationMu sync.Mutex
	started        atomic.Bool
	first          *fory.Fory
	callbacks      []func(*fory.Fory) error
}

// New creates a new thread-safe Fory instance.
func New(opts ...fory.Option) *Fory {
	return NewWithFactory(func() *fory.Fory {
		return fory.New(opts...)
	})
}

// NewWithFactory creates a thread-safe Fory using a custom factory.
// The factory must return a fresh, identically configured Fory instance on every
// call, with any custom registrations completed before returning. It may be
// called concurrently when additional instances are needed.
func NewWithFactory(factory func() *fory.Fory) *Fory {
	if factory == nil {
		panic("threadsafe.NewWithFactory requires a non-nil factory")
	}
	f := &Fory{}
	f.pool.New = func() any {
		inner := factory()
		if inner == nil {
			panic("threadsafe.NewWithFactory factory returned nil")
		}
		// Like Java ThreadLocalFory's factoryCallback, registrations initialize
		// every new instance, including replacements for entries discarded by GC.
		// Setup holds registrationMu; root operations freeze callbacks before Get.
		for _, callback := range f.callbacks {
			if err := callback(inner); err != nil {
				panic(fmt.Errorf("threadsafe factory registration failed: %w", err))
			}
		}
		return inner
	}
	return f
}

func (f *Fory) acquire() *fory.Fory {
	if !f.started.Load() {
		if inner := f.freezeRegistrations(); inner != nil {
			return inner
		}
	}
	return f.pool.Get().(*fory.Fory)
}

//go:noinline
func (f *Fory) freezeRegistrations() *fory.Fory {
	f.registrationMu.Lock()
	defer f.registrationMu.Unlock()
	// The first root directly borrows the instance configured during setup.
	// Freeze even if that root fails, and let sync.Pool own its reuse afterward.
	inner := f.first
	f.first = nil
	f.started.Store(true)
	return inner
}

func (f *Fory) release(inner *fory.Fory) {
	inner.Reset()
	f.pool.Put(inner)
}

// ============================================================================
// Non-generic methods
// ============================================================================

// Serialize serializes a value using a pooled Fory instance
func (f *Fory) Serialize(v any) ([]byte, error) {
	inner := f.acquire()
	data, err := inner.Serialize(v)
	if err != nil {
		f.release(inner)
		return nil, err
	}
	// Copy the data before releasing since the buffer will be reused
	result := make([]byte, len(data))
	copy(result, data)
	f.release(inner)
	return result, nil
}

// Deserialize deserializes data into the provided value using a pooled Fory instance
func (f *Fory) Deserialize(data []byte, v any) error {
	inner := f.acquire()
	defer f.release(inner)
	return inner.Deserialize(data, v)
}

func (f *Fory) registerCallback(registration func(*fory.Fory) error) error {
	f.registrationMu.Lock()
	defer f.registrationMu.Unlock()
	if f.started.Load() {
		return fmt.Errorf("types must be registered before the first serialization or deserialization")
	}
	// Registration cannot accumulate on arbitrary pool.Get results: even
	// serialized callers can borrow different entries, and GC can discard them.
	// Configure the first real instance before it enters the pool instead.
	if f.first == nil {
		f.first = f.pool.New().(*fory.Fory)
	}
	if err := registration(f.first); err != nil {
		return err
	}
	f.callbacks = append(f.callbacks, registration)
	return nil
}

// Keep only type metadata in callbacks, rather than retaining caller objects.
func registrationType(type_ any) reflect.Type {
	if typ, ok := type_.(reflect.Type); ok {
		return typ
	}
	typ := reflect.TypeOf(type_)
	if typ != nil && typ.Kind() == reflect.Ptr {
		typ = typ.Elem()
	}
	return typ
}

// RegisterStruct registers a struct type with a numeric ID in every pooled instance.
// Registration must complete before the first serialization or deserialization,
// including a failed operation. Arguments follow fory.Fory.RegisterStruct.
func (f *Fory) RegisterStruct(type_ any, typeID uint32) error {
	typ := registrationType(type_)
	return f.registerCallback(func(inner *fory.Fory) error {
		return inner.RegisterStruct(typ, typeID)
	})
}

// RegisterStructByName registers a struct type by name in every pooled instance.
// Registration must complete before the first serialization or deserialization,
// including a failed operation. Arguments follow fory.Fory.RegisterStructByName.
func (f *Fory) RegisterStructByName(type_ any, name string) error {
	typ := registrationType(type_)
	return f.registerCallback(func(inner *fory.Fory) error {
		return inner.RegisterStructByName(typ, name)
	})
}

// RegisterEnum registers an enum type with a numeric ID in every pooled instance.
// Registration must complete before the first serialization or deserialization,
// including a failed operation. Arguments follow fory.Fory.RegisterEnum.
func (f *Fory) RegisterEnum(type_ any, typeID uint32) error {
	typ := registrationType(type_)
	return f.registerCallback(func(inner *fory.Fory) error {
		return inner.RegisterEnum(typ, typeID)
	})
}

// RegisterEnumByName registers an enum type by name in every pooled instance.
// Registration must complete before the first serialization or deserialization,
// including a failed operation. Arguments follow fory.Fory.RegisterEnumByName.
func (f *Fory) RegisterEnumByName(type_ any, name string) error {
	typ := registrationType(type_)
	return f.registerCallback(func(inner *fory.Fory) error {
		return inner.RegisterEnumByName(typ, name)
	})
}

// ============================================================================
// Generic package-level functions
// ============================================================================

// Serialize serializes a value with type T inferred, thread-safe.
// Takes pointer to avoid interface heap allocation and struct copy.
func Serialize[T any](f *Fory, value *T) ([]byte, error) {
	inner := f.acquire()
	data, err := fory.Serialize(inner, value)
	if err != nil {
		f.release(inner)
		return nil, err
	}
	// Copy the data before releasing since the buffer will be reused
	result := make([]byte, len(data))
	copy(result, data)
	f.release(inner)
	return result, nil
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
func UnmarshalTo(data []byte, v any) error {
	return globalFory.Deserialize(data, v)
}
