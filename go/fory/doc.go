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

/*
Package fory provides high-performance, cross-language serialization for Go.

Fory is a blazingly fast multi-language serialization framework that enables
seamless data exchange between Go, Java, Python, C++, Rust, and JavaScript.
It supports automatic object graph serialization with circular references,
polymorphism, and schema evolution.

# Requirements

Go 1.24 or later is required.

# Installation

	go get github.com/apache/fory/go/fory

# Quick Start

Create a Fory instance, register your types, and serialize:

	package main

	import (
		"fmt"
		"github.com/apache/fory/go/fory"
	)

	type User struct {
		ID   int64
		Name string
		Age  int32
	}

	func main() {
		// Create a Fory instance
		f := fory.New()

		// Register struct with a type ID
		if err := f.RegisterStruct(User{}, 1); err != nil {
			panic(err)
		}

		// Serialize
		user := &User{ID: 1, Name: "Alice", Age: 30}
		data, err := f.Serialize(user)
		if err != nil {
			panic(err)
		}

		// Deserialize
		var result User
		if err := f.Deserialize(data, &result); err != nil {
			panic(err)
		}

		fmt.Printf("Deserialized: %+v\n", result)
	}

# Configuration Options

Fory uses a functional options pattern for configuration:

	// Default configuration
	f := fory.New()

	// With options
	f := fory.New(
		fory.WithTrackRef(true),     // Enable reference tracking for circular references
		fory.WithCompatible(true),   // Enable schema evolution
		fory.WithMaxDepth(30),       // Set maximum nesting depth
		fory.WithXlang(true),        // Enable cross-language mode
	)

Configuration defaults:

  - TrackRef: false (reference tracking disabled)
  - MaxDepth: 20 (maximum nesting depth)
  - IsXlang: false (cross-language mode disabled)
  - Compatible: false (schema evolution disabled)

# Type Registration

Types must be registered before serialization. Registration can be done by ID
(compact, faster) or by name (flexible):

	// Register by ID (recommended for performance)
	f.RegisterStruct(User{}, 1)
	f.RegisterStruct(Order{}, 2)

	// Register by name (more flexible)
	f.RegisterNamedStruct(User{}, "example.User")

	// Register enum types
	f.RegisterEnum(Status(0), 3)

All struct types in the object graph must be registered, including nested types.

# Supported Types

Fory Go supports a wide range of types:

Primitives:
  - bool, int8, int16, int32, int64, int
  - uint8, uint16, uint32, uint64
  - float32, float64
  - string

Collections:
  - Slices: []T for any serializable type T
  - Maps: map[K]V for supported key/value types
  - Sets: fory.Set[T] (backed by map[T]struct{})

Time types:
  - time.Time (nanosecond precision)
  - time.Duration

Structs:
  - Any struct with exported fields
  - Nested structs (must be registered)
  - Pointer fields with nil handling

Binary data:
  - []byte

# Struct Tags

Fory supports struct tags for field-level configuration:

	type Document struct {
		ID       int64  `fory:"id=0"`                  // Field ID for compact encoding
		Title    string `fory:"id=1"`                  // Field IDs must be unique
		Author   *User  `fory:"id=2,ref"`              // Enable reference tracking
		Password string `fory:"-"`                     // Exclude from serialization
		Count    int64  `fory:"encoding=fixed"`        // Fixed-length encoding
		Data     *Data  `fory:"nullable"`              // Allow nil values
	}

Available tags:
  - id=N: Assign numeric field ID for compact encoding
  - "-": Exclude field from serialization
  - ref: Enable reference tracking for pointer/slice/map fields
  - nullable: Write null flag for pointer fields
  - encoding=varint|fixed|tagged: Control numeric encoding

# Reference Tracking

Enable reference tracking to handle circular references and shared objects:

	f := fory.New(fory.WithTrackRef(true))

	type Node struct {
		Value int32
		Next  *Node `fory:"ref"`  // Enable per-field tracking
	}

	// Create circular list
	n1 := &Node{Value: 1}
	n2 := &Node{Value: 2}
	n1.Next = n2
	n2.Next = n1  // Circular reference

	data, _ := f.Serialize(n1)  // Handles circular reference correctly

When reference tracking is enabled:
  - Objects appearing multiple times are serialized once
  - Circular references are handled correctly
  - Per-field `fory:"ref"` tags take effect

# Schema Evolution

Enable compatible mode for forward/backward compatibility:

	f := fory.New(fory.WithCompatible(true))

	// V1: original struct
	type UserV1 struct {
		ID   int64
		Name string
	}

	// V2: added Email field
	type UserV2 struct {
		ID    int64
		Name  string
		Email string  // New field - receives zero value from V1 data
	}

Compatible mode supports:
  - Adding new fields (receive zero values from old data)
  - Removing fields (skipped during deserialization)
  - Reordering fields (matched by name)

# Cross-Language Serialization

Enable cross-language mode for interoperability with Java, Python, C++, Rust,
and JavaScript:

	f := fory.New(fory.WithXlang(true))
	f.RegisterStruct(User{}, 1)  // Use same ID across all languages

	data, _ := f.Serialize(&User{ID: 1, Name: "Alice"})
	// 'data' can be deserialized by Java, Python, etc.

For cross-language compatibility:
  - Use consistent type IDs across all languages
  - Fields are matched by snake_case name conversion
  - See https://fory.apache.org/docs/specification/xlang_type_mapping for type mappings

# Thread Safety

The default Fory instance is NOT thread-safe. For concurrent use, use the
threadsafe package:

	import "github.com/apache/fory/go/fory/threadsafe"

	// Create thread-safe instance
	f := threadsafe.New(
		fory.WithTrackRef(true),
		fory.WithCompatible(true),
	)

	// Safe for concurrent use
	go func() {
		data, _ := f.Serialize(value1)
	}()
	go func() {
		data, _ := f.Serialize(value2)
	}()

The thread-safe wrapper:
  - Uses sync.Pool for efficient instance reuse
  - Automatically copies serialized data before returning
  - Accepts the same configuration options as fory.New()

# Buffer Management

The default Fory instance reuses its internal buffer for zero-copy performance:

	f := fory.New()
	data1, _ := f.Serialize(value1)
	// WARNING: data1 becomes invalid after next Serialize call!
	data2, _ := f.Serialize(value2)

To keep the data, copy it:

	safeCopy := make([]byte, len(data1))
	copy(safeCopy, data1)

The thread-safe wrapper automatically copies data, avoiding this concern.

# Code Generation (Experimental)

For performance-critical paths, Fory provides optional ahead-of-time code
generation that eliminates reflection overhead:

	//fory:generate
	type User struct {
		ID   int64
		Name string
	}

	//go:generate fory -pkg .

Run code generation:

	go generate ./...

Generated code integrates transparently - Fory automatically uses generated
serializers when available.

# Error Handling

Always check errors from serialization operations:

	data, err := f.Serialize(value)
	if err != nil {
		// Handle error
	}

	err = f.Deserialize(data, &result)
	if err != nil {
		// Handle error
	}

Common error kinds:
  - ErrKindBufferOutOfBound: Read/write beyond buffer bounds
  - ErrKindTypeMismatch: Type ID mismatch during deserialization
  - ErrKindUnknownType: Unknown type encountered
  - ErrKindMaxDepthExceeded: Recursion depth limit exceeded
  - ErrKindHashMismatch: Struct hash mismatch (schema changed)
  - ErrKindInvalidUTF16String: Malformed UTF-16 string payload

# Best Practices

1. Reuse Fory instances: Creating a Fory instance involves initialization
overhead. Create once and reuse.

2. Use thread-safe wrapper for concurrency: Never share a non-thread-safe
Fory instance across goroutines.

3. Enable reference tracking only when needed: It adds overhead for tracking
object identity.

4. Copy serialized data if keeping it: With the default Fory, the returned
byte slice is invalidated on the next operation.

5. Register all types at startup: Before any concurrent operations.

6. Use compatible mode for evolving schemas: Enable when struct definitions
may change between service versions.

# More Information

For comprehensive documentation, see https://fory.apache.org/docs/guide/go/

Related specifications:
  - Xlang Serialization: https://fory.apache.org/docs/specification/fory_xlang_serialization_spec
  - Type Mapping: https://fory.apache.org/docs/specification/xlang_type_mapping
*/
package fory
