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

package threadsafe

import (
	"reflect"
	"runtime"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/apache/fory/go/fory"
	"github.com/stretchr/testify/require"
)

func TestRegistrationAfterGC(t *testing.T) {
	type Item struct{ Value int32 }
	for _, byName := range []bool{false, true} {
		name := "ID"
		if byName {
			name = "Name"
		}
		t.Run(name, func(t *testing.T) {
			var created atomic.Int32
			f := NewWithFactory(func() *fory.Fory {
				created.Add(1)
				return fory.New()
			})
			if byName {
				require.NoError(t, f.RegisterStructByName(Item{}, "threadsafe.Item"))
			} else {
				require.NoError(t, f.RegisterStruct(Item{}, 1))
			}
			runtime.GC()
			runtime.GC()
			value := Item{Value: 42}
			data, err := f.Serialize(&value)
			require.NoError(t, err)
			// The configured instance serves the first root, even after setup GC.
			require.Equal(t, int32(1), created.Load())

			// Two collections discard the sync.Pool primary and victim caches.
			runtime.GC()
			runtime.GC()
			var result Item
			require.NoError(t, f.Deserialize(data, &result))
			require.Equal(t, value, result)
			require.Greater(t, created.Load(), int32(1))

			before := created.Load()
			runtime.GC()
			runtime.GC()
			data, err = Serialize(f, &value)
			require.NoError(t, err)
			require.Greater(t, created.Load(), before)
			require.NoError(t, Deserialize(f, data, &result))
			require.Equal(t, value, result)
		})
	}
}

func TestRegistrationInstances(t *testing.T) {
	type Order struct{ ID int64 }
	type User struct {
		ID    int32
		Order *Order
	}
	type NamedItem struct{ Name string }
	type FactoryItem struct{ Name string }
	type State int32
	type Color int32
	var factoryCalls atomic.Int32
	var firstCreated *fory.Fory
	f := NewWithFactory(func() *fory.Fory {
		inner := fory.New()
		if factoryCalls.Add(1) == 1 {
			firstCreated = inner
		}
		if err := inner.RegisterStruct(FactoryItem{}, 100); err != nil {
			panic(err)
		}
		return inner
	})
	require.Zero(t, factoryCalls.Load())
	require.Error(t, f.RegisterStructByName(int32(0), "threadsafe.Invalid"))
	require.NoError(t, f.RegisterStruct(&User{}, 1))
	runtime.GC()
	runtime.GC()
	require.NoError(t, f.RegisterStruct(reflect.TypeOf(User{}), 1))
	require.Error(t, f.RegisterStruct(Order{}, 1))
	require.Error(t, f.RegisterStructByName(User{}, "threadsafe.Duplicate"))
	require.NoError(t, f.RegisterStruct(Order{}, 2))
	require.NoError(t, f.RegisterStructByName(NamedItem{}, "threadsafe.NamedItem"))
	require.NoError(t, f.RegisterEnum(State(0), 3))
	require.NoError(t, f.RegisterEnumByName(reflect.TypeOf(Color(0)), "threadsafe.Color"))
	// Ordinary registration does not rebuild the factory's instances each time.
	require.Equal(t, int32(1), factoryCalls.Load())

	// Hold both borrows to force an additional, independently configured instance.
	first := f.acquire()
	second := f.acquire()
	require.Same(t, firstCreated, first)
	require.NotSame(t, first, second)
	require.Equal(t, int32(2), factoryCalls.Load())
	for _, inner := range []*fory.Fory{first, second} {
		for _, value := range []any{&User{42, &Order{7}}, &Order{7}, &NamedItem{"name"}, &FactoryItem{"factory"}, State(1), Color(2)} {
			data, err := inner.Serialize(value)
			require.NoError(t, err)
			var result any
			require.NoError(t, inner.Deserialize(data, &result))
			require.Equal(t, value, result)
		}
	}
	f.release(first)
	f.release(second)

	var workers sync.WaitGroup
	for range 8 {
		workers.Add(1)
		go func() {
			defer workers.Done()
			value := User{42, &Order{7}}
			for range 20 {
				data, err := Serialize(f, &value)
				if err != nil {
					t.Error(err)
					return
				}
				var result User
				if err := Deserialize(f, data, &result); err != nil {
					t.Error(err)
					return
				}
				if result.ID != value.ID || result.Order == nil || *result.Order != *value.Order {
					t.Errorf("got %v, want %v", result, value)
					return
				}
			}
		}()
	}
	workers.Wait()
}

func TestConcurrentRegistration(t *testing.T) {
	type User struct{ ID int32 }
	type Order struct{ ID int64 }
	type State int32
	type Color int32
	var created atomic.Int32
	f := NewWithFactory(func() *fory.Fory {
		created.Add(1)
		return fory.New()
	})
	register := []func() error{
		func() error { return f.RegisterStruct(User{}, 1) },
		func() error { return f.RegisterStructByName(Order{}, "threadsafe.Order") },
		func() error { return f.RegisterEnum(State(0), 2) },
		func() error { return f.RegisterEnumByName(Color(0), "threadsafe.Color") },
	}
	var workers sync.WaitGroup
	for _, call := range register {
		workers.Add(1)
		go func() {
			defer workers.Done()
			if err := call(); err != nil {
				t.Error(err)
			}
		}()
	}
	workers.Wait()
	require.Equal(t, int32(1), created.Load())
	first := f.acquire()
	second := f.acquire()
	for _, inner := range []*fory.Fory{first, second} {
		for _, value := range []any{&User{42}, &Order{7}, State(1), Color(2)} {
			data, err := inner.Serialize(value)
			require.NoError(t, err)
			var result any
			require.NoError(t, inner.Deserialize(data, &result))
			require.Equal(t, value, result)
		}
	}
	f.release(first)
	f.release(second)
}

func TestRegistrationAtFirstRoot(t *testing.T) {
	type User struct{ ID int32 }
	type Order struct{ ID int64 }
	for range 20 {
		f := New()
		require.NoError(t, f.RegisterStruct(User{}, 1))
		start := make(chan struct{})
		registered := make(chan error, 1)
		serialized := make(chan error, 1)
		go func() {
			<-start
			registered <- f.RegisterStruct(Order{}, 2)
		}()
		go func() {
			<-start
			_, err := f.Serialize(&User{42})
			serialized <- err
		}()
		close(start)
		registrationErr := <-registered
		require.NoError(t, <-serialized)
		first := f.acquire()
		second := f.acquire()
		for _, inner := range []*fory.Fory{first, second} {
			_, err := inner.Serialize(&Order{7})
			if registrationErr == nil {
				require.NoError(t, err)
			} else {
				require.Error(t, err)
			}
		}
		f.release(first)
		f.release(second)
	}
}

func TestRegistrationFreeze(t *testing.T) {
	type Item struct{ Value int32 }
	value := int32(42)
	data, err := fory.New().Serialize(value)
	require.NoError(t, err)
	tests := []struct {
		name string
		fail bool
		call func(*Fory) error
	}{
		{"Serialize", false, func(f *Fory) error { _, err := f.Serialize(value); return err }},
		{"SerializeError", true, func(f *Fory) error { _, err := f.Serialize(Item{}); return err }},
		{"GenericSerialize", false, func(f *Fory) error { _, err := Serialize(f, &value); return err }},
		{"GenericSerializeError", true, func(f *Fory) error { _, err := Serialize(f, &Item{}); return err }},
		{"Deserialize", false, func(f *Fory) error { return f.Deserialize(data, new(int32)) }},
		{"DeserializeError", true, func(f *Fory) error { return f.Deserialize(nil, new(int32)) }},
		{"GenericDeserialize", false, func(f *Fory) error { return Deserialize(f, data, new(int32)) }},
		{"GenericDeserializeError", true, func(f *Fory) error { return Deserialize(f, nil, new(int32)) }},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			f := New()
			err := test.call(f)
			if test.fail {
				require.Error(t, err)
			} else {
				require.NoError(t, err)
			}
			require.Error(t, f.RegisterStruct(Item{}, 1))
			require.Error(t, f.RegisterStructByName(Item{}, "threadsafe.Item"))
			type State int32
			require.Error(t, f.RegisterEnum(State(0), 2))
			require.Error(t, f.RegisterEnumByName(State(0), "threadsafe.State"))
		})
	}
}

// TestFory tests the thread-safe Fory wrapper
func TestFory(t *testing.T) {
	f := New(fory.WithXlang(false), fory.WithRefTracking(true), fory.WithCompatible(false))

	t.Run("BasicSerialization", func(t *testing.T) {
		data, err := f.Serialize(int32(42))
		require.NoError(t, err)

		var result int32
		err = f.Deserialize(data, &result)
		require.NoError(t, err)
		require.Equal(t, int32(42), result)
	})

	t.Run("GenericSerialization", func(t *testing.T) {
		val := "hello world"
		data, err := Serialize(f, &val)
		require.NoError(t, err)

		var result string
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.Equal(t, "hello world", result)
	})

	t.Run("ConcurrentAccess", func(t *testing.T) {
		done := make(chan bool, 10)
		for i := 0; i < 10; i++ {
			go func(val int32) {
				data, err := f.Serialize(val)
				require.NoError(t, err)

				var result int32
				err = f.Deserialize(data, &result)
				require.NoError(t, err)
				require.Equal(t, val, result)
				done <- true
			}(int32(i))
		}
		for i := 0; i < 10; i++ {
			<-done
		}
	})

	t.Run("ConcurrentGenericAccess", func(t *testing.T) {
		done := make(chan bool, 10)
		for i := 0; i < 10; i++ {
			go func(val int64) {
				data, err := Serialize(f, &val)
				require.NoError(t, err)

				var result int64
				err = Deserialize(f, data, &result)
				require.NoError(t, err)
				require.Equal(t, val, result)
				done <- true
			}(int64(i * 1000))
		}
		for i := 0; i < 10; i++ {
			<-done
		}
	})
}

// TestSerializeAny tests the Serialize/Deserialize methods
func TestSerializeAny(t *testing.T) {
	f := New(fory.WithXlang(false), fory.WithRefTracking(true), fory.WithCompatible(false))

	t.Run("Primitives", func(t *testing.T) {
		data, err := f.Serialize(int32(42))
		require.NoError(t, err)

		var result int32
		err = f.Deserialize(data, &result)
		require.NoError(t, err)
		require.Equal(t, int32(42), result)
	})

	t.Run("String", func(t *testing.T) {
		data, err := f.Serialize("hello")
		require.NoError(t, err)

		var result string
		err = f.Deserialize(data, &result)
		require.NoError(t, err)
		require.Equal(t, "hello", result)
	})
}

// TestDeserialize tests the Deserialize generic function
func TestDeserialize(t *testing.T) {
	f := New(fory.WithXlang(false), fory.WithRefTracking(true), fory.WithCompatible(false))

	t.Run("Int32", func(t *testing.T) {
		val := int32(42)
		data, err := Serialize(f, &val)
		require.NoError(t, err)

		var result int32
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.Equal(t, int32(42), result)
	})

	t.Run("String", func(t *testing.T) {
		val := "hello"
		data, err := Serialize(f, &val)
		require.NoError(t, err)

		var result string
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.Equal(t, "hello", result)
	})

	t.Run("Slice", func(t *testing.T) {
		f := New(fory.WithXlang(false), fory.WithRefTracking(true), fory.WithCompatible(false))
		// Serialize a struct containing the slice since *[]T is not supported
		type SliceWrapper struct {
			Items []int32
		}
		require.NoError(t, f.RegisterStructByName(SliceWrapper{}, "threadsafe.SliceWrapper"))
		original := SliceWrapper{Items: []int32{1, 2, 3, 4, 5}}
		data, err := Serialize(f, &original)
		require.NoError(t, err)

		var result SliceWrapper
		err = Deserialize(f, data, &result)
		require.NoError(t, err)
		require.Equal(t, original.Items, result.Items)
	})
}

// TestGlobalFunctions tests the global convenience functions
func TestGlobalFunctions(t *testing.T) {
	t.Run("Marshal", func(t *testing.T) {
		val := int32(42)
		data, err := Marshal(&val)
		require.NoError(t, err)

		var result int32
		err = Unmarshal(data, &result)
		require.NoError(t, err)
		require.Equal(t, int32(42), result)
	})

	t.Run("UnmarshalTo", func(t *testing.T) {
		// Use non-generic Serialize for compatibility with non-generic UnmarshalTo
		data, err := globalFory.Serialize("hello")
		require.NoError(t, err)

		var result string
		err = UnmarshalTo(data, &result)
		require.NoError(t, err)
		require.Equal(t, "hello", result)
	})
}
