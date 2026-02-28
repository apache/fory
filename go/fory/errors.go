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
	"os"
	"strings"
)

// ErrorKind represents categories of serialization errors for fast dispatch.
// Using an enum allows for efficient error checking on the hot path.
type ErrorKind uint8

const (
	// ErrKindOK indicates no error occurred
	ErrKindOK ErrorKind = iota
	// ErrKindBufferOutOfBound indicates a read/write beyond buffer bounds
	ErrKindBufferOutOfBound
	// ErrKindTypeMismatch indicates type ID mismatch during deserialization
	ErrKindTypeMismatch
	// ErrKindUnknownType indicates an unregistered or unknown type
	ErrKindUnknownType
	// ErrKindSerializationFailed indicates a general serialization failure
	ErrKindSerializationFailed
	// ErrKindDeserializationFailed indicates a general deserialization failure
	ErrKindDeserializationFailed
	// ErrKindMaxDepthExceeded indicates recursion depth limit exceeded
	ErrKindMaxDepthExceeded
	// ErrKindNilPointer indicates an unexpected nil pointer
	ErrKindNilPointer
	// ErrKindInvalidRefId indicates an invalid reference ID
	ErrKindInvalidRefId
	// ErrKindHashMismatch indicates struct hash mismatch
	ErrKindHashMismatch
	// ErrKindInvalidTag indicates invalid fory struct tag configuration
	ErrKindInvalidTag
	// ErrKindInvalidUTF16String indicates malformed UTF-16 string data
	ErrKindInvalidUTF16String
)

// Error is a lightweight error type optimized for hot path performance.
// It stores error details without allocating until Error() is called.
type Error struct {
	kind    ErrorKind
	message string // pre-formatted message or lazy format template
	// For buffer out of bound errors
	offset int
	need   int
	size   int
	// For type errors
	actualType   TypeId
	expectedType TypeId
	// For hash mismatch
	actualHash   int32
	expectedHash int32
	stack        []string
}

var panicOnError = parsePanicOnError()

func parsePanicOnError() bool {
	value, ok := os.LookupEnv("FORY_PANIC_ON_ERROR")
	if !ok {
		return false
	}
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "1", "true", "t", "yes", "y", "on":
		return true
	default:
		return false
	}
}

func panicIfEnabled(err Error) Error {
	if panicOnError && err.kind != ErrKindOK {
		panic(err.Error())
	}
	return err
}

// Ok returns true if no error occurred
func (e Error) Ok() bool {
	return e.kind == ErrKindOK
}

// HasError returns true if an error occurred
func (e Error) HasError() bool {
	return e.kind != ErrKindOK
}

// Kind returns the error kind for fast dispatch
func (e Error) Kind() ErrorKind {
	return e.kind
}

func (e Error) reverseStackString() string {
	if len(e.stack) == 0 {
		return ""
	}
	var b strings.Builder
	for i := len(e.stack) - 1; i >= 0; i-- {
		b.WriteString(e.stack[i])
	}
	return b.String()
}

// Error implements the error interface with lazy formatting
func (e Error) Error() string {
	stack := e.reverseStackString()
	switch e.kind {
	case ErrKindOK:
		return ""
	case ErrKindBufferOutOfBound:
		if e.message != "" {
			return e.message + stack
		}
		return fmt.Sprintf("buffer out of bound: offset=%d, need=%d, size=%d", e.offset, e.need, e.size) + stack
	case ErrKindTypeMismatch:
		if e.message != "" {
			return e.message + stack
		}
		return fmt.Sprintf("type mismatch: actual=%d, expected=%d", e.actualType, e.expectedType) + stack
	case ErrKindHashMismatch:
		if e.message != "" {
			return e.message + stack
		}
		return fmt.Sprintf("hash mismatch: actual=%d, expected=%d", e.actualHash, e.expectedHash) + stack
	default:
		if e.message != "" {
			return e.message + stack
		}
		return fmt.Sprintf("fory error: kind=%d", e.kind) + stack
	}
}

// BufferOutOfBoundError creates a buffer out of bound error
func BufferOutOfBoundError(offset, need, size int) Error {
	return panicIfEnabled(Error{
		kind:   ErrKindBufferOutOfBound,
		offset: offset,
		need:   need,
		size:   size,
	})
}

// TypeMismatchError creates a type mismatch error
func TypeMismatchError(actual, expected TypeId) Error {
	return panicIfEnabled(Error{
		kind:         ErrKindTypeMismatch,
		actualType:   actual,
		expectedType: expected,
	})
}

// UnknownTypeError creates an unknown type error
func UnknownTypeError(typeId TypeId) Error {
	return panicIfEnabled(Error{
		kind:       ErrKindUnknownType,
		actualType: typeId,
		message:    fmt.Sprintf("unknown type: typeId=%d", typeId),
	})
}

// HashMismatchError creates a struct hash mismatch error
func HashMismatchError(actual, expected int32, typeName string) Error {
	return panicIfEnabled(Error{
		kind:         ErrKindHashMismatch,
		actualHash:   actual,
		expectedHash: expected,
		message:      fmt.Sprintf("hash %d is not consistent with %d for type %s", actual, expected, typeName),
	})
}

// SerializationError creates a general serialization error
func SerializationError(msg string) Error {
	return panicIfEnabled(Error{
		kind:    ErrKindSerializationFailed,
		message: msg,
	})
}

// SerializationErrorf creates a formatted serialization error
func SerializationErrorf(format string, args ...any) Error {
	return panicIfEnabled(Error{
		kind:    ErrKindSerializationFailed,
		message: fmt.Sprintf(format, args...),
	})
}

// DeserializationError creates a general deserialization error
func DeserializationError(msg string) Error {
	return panicIfEnabled(Error{
		kind:    ErrKindDeserializationFailed,
		message: msg,
	})
}

// DeserializationErrorf creates a formatted deserialization error
func DeserializationErrorf(format string, args ...any) Error {
	return panicIfEnabled(Error{
		kind:    ErrKindDeserializationFailed,
		message: fmt.Sprintf(format, args...),
	})
}

// MaxDepthExceededError creates a max depth exceeded error
func MaxDepthExceededError(depth int) Error {
	return panicIfEnabled(Error{
		kind:    ErrKindMaxDepthExceeded,
		message: fmt.Sprintf("max depth exceeded: depth=%d", depth),
	})
}

// NilPointerError creates a nil pointer error
func NilPointerError(msg string) Error {
	return panicIfEnabled(Error{
		kind:    ErrKindNilPointer,
		message: msg,
	})
}

// InvalidRefIdError creates an invalid reference ID error
func InvalidRefIdError(refId int32) Error {
	return panicIfEnabled(Error{
		kind:    ErrKindInvalidRefId,
		message: fmt.Sprintf("invalid reference id: %d", refId),
	})
}

// InvalidTagError creates an invalid fory struct tag error
func InvalidTagError(msg string) Error {
	return panicIfEnabled(Error{
		kind:    ErrKindInvalidTag,
		message: msg,
	})
}

// InvalidTagErrorf creates a formatted invalid fory struct tag error
func InvalidTagErrorf(format string, args ...any) Error {
	return panicIfEnabled(Error{
		kind:    ErrKindInvalidTag,
		message: fmt.Sprintf(format, args...),
	})
}

// InvalidUTF16StringError creates an invalid UTF-16 string error
func InvalidUTF16StringError(byteCount int) Error {
	return panicIfEnabled(Error{
		kind:    ErrKindInvalidUTF16String,
		message: fmt.Sprintf("invalid UTF-16 string byte count %d: must be even", byteCount),
	})
}

// WrapError wraps a standard error into a fory Error
func WrapError(err error, kind ErrorKind) Error {
	if err == nil {
		return Error{kind: ErrKindOK}
	}
	return panicIfEnabled(Error{
		kind:    kind,
		message: err.Error(),
	})
}

// FromError converts a standard error to a fory Error
// If err is already a fory Error, it returns it as-is
// Otherwise wraps it as a deserialization error
func FromError(err error) Error {
	if err == nil {
		return Error{kind: ErrKindOK}
	}
	if e, ok := err.(Error); ok {
		return e
	}
	return panicIfEnabled(Error{
		kind:    ErrKindDeserializationFailed,
		message: err.Error(),
	})
}

// Pointer receiver methods for *Error (used for error accumulation)

// SetError sets the error if no error has occurred yet (first-error-wins)
func (e *Error) SetError(err error) {
	if e == nil || e.kind != ErrKindOK {
		return
	}
	if foryErr, ok := err.(Error); ok {
		*e = foryErr
	} else if err != nil {
		*e = Error{
			kind:    ErrKindDeserializationFailed,
			message: err.Error(),
		}
	}
}

// TakeError returns the error and clears it
func (e *Error) TakeError() error {
	if e == nil || e.kind == ErrKindOK {
		return nil
	}
	result := *e
	*e = Error{kind: ErrKindOK}
	return result
}

// CheckError returns the error if one occurred, nil otherwise
func (e *Error) CheckError() error {
	if e == nil || e.kind == ErrKindOK {
		return nil
	}
	return *e
}
