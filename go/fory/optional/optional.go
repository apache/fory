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

package optional

// Optional represents an immutable optional value without pointer indirection.
// Optional is intended for scalar values. Do not wrap structs; prefer *Struct
// (or Optional[*Struct] if you need explicit optional semantics).
type Optional[T any] struct {
	value T
	has   bool
}

// Some returns an Optional containing a value.
func Some[T any](v T) Optional[T] {
	return Optional[T]{value: v, has: true}
}

// None returns an empty Optional.
func None[T any]() Optional[T] {
	return Optional[T]{}
}

// IsSome reports whether the optional contains a value.
func (o Optional[T]) IsSome() bool { return o.has }

// IsNone reports whether the optional is empty.
func (o Optional[T]) IsNone() bool { return !o.has }

// Expect returns the contained value or panics with the provided message.
func (o Optional[T]) Expect(message string) T {
	if o.has {
		return o.value
	}
	panic(message)
}

// Unwrap returns the contained value or panics.
func (o Optional[T]) Unwrap() T {
	if o.has {
		return o.value
	}
	panic("optional: unwrap on None")
}

// UnwrapOr returns the contained value or a default.
func (o Optional[T]) UnwrapOr(defaultValue T) T {
	if o.has {
		return o.value
	}
	return defaultValue
}

// UnwrapOrDefault returns the contained value or the zero value.
func (o Optional[T]) UnwrapOrDefault() T {
	if o.has {
		return o.value
	}
	var zero T
	return zero
}

// UnwrapOrElse returns the contained value or computes a default.
func (o Optional[T]) UnwrapOrElse(defaultFn func() T) T {
	if o.has {
		return o.value
	}
	return defaultFn()
}

// OkOr returns the contained value or the provided error.
func (o Optional[T]) OkOr(err error) (T, error) {
	if o.has {
		return o.value, nil
	}
	var zero T
	return zero, err
}

// Or returns the option if it is Some, otherwise returns other.
func (o Optional[T]) Or(other Optional[T]) Optional[T] {
	if o.has {
		return o
	}
	return other
}

// OrElse returns the option if it is Some, otherwise returns the result of f.
func (o Optional[T]) OrElse(f func() Optional[T]) Optional[T] {
	if o.has {
		return o
	}
	return f()
}

// ValueOrZero returns the contained value or the zero value.
func (o Optional[T]) ValueOrZero() T {
	return o.UnwrapOrDefault()
}

// Filter returns None if the predicate returns false.
func (o Optional[T]) Filter(predicate func(T) bool) Optional[T] {
	if o.has && predicate(o.value) {
		return o
	}
	return None[T]()
}

// Int8 wraps an int8 value in Optional.
func Int8(v int8) Optional[int8] { return Some(v) }

// Int16 wraps an int16 value in Optional.
func Int16(v int16) Optional[int16] { return Some(v) }

// Int32 wraps an int32 value in Optional.
func Int32(v int32) Optional[int32] { return Some(v) }

// Int64 wraps an int64 value in Optional.
func Int64(v int64) Optional[int64] { return Some(v) }

// Int wraps an int value in Optional.
func Int(v int) Optional[int] { return Some(v) }

// Uint8 wraps a uint8 value in Optional.
func Uint8(v uint8) Optional[uint8] { return Some(v) }

// Uint16 wraps a uint16 value in Optional.
func Uint16(v uint16) Optional[uint16] { return Some(v) }

// Uint32 wraps a uint32 value in Optional.
func Uint32(v uint32) Optional[uint32] { return Some(v) }

// Uint64 wraps a uint64 value in Optional.
func Uint64(v uint64) Optional[uint64] { return Some(v) }

// Uint wraps a uint value in Optional.
func Uint(v uint) Optional[uint] { return Some(v) }

// String wraps a string value in Optional.
func String(v string) Optional[string] { return Some(v) }

// Bool wraps a bool value in Optional.
func Bool(v bool) Optional[bool] { return Some(v) }
