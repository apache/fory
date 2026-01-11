---
title: Overview
sidebar_position: 0
id: go_index
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

Apache Fory Go is a high-performance, cross-language serialization library for Go. It provides automatic object graph serialization with support for circular references, polymorphism, and cross-language compatibility.

## Why Fory Go?

- **High Performance**: Optimized serialization with zero-copy buffer management and inline hot paths
- **Cross-Language**: Seamless data exchange with Java, Python, C++, Rust, and JavaScript
- **Automatic Serialization**: No IDL definitions or schema compilation required
- **Reference Tracking**: Built-in support for circular references and shared objects
- **Type Safety**: Strong typing with compile-time verification (optional codegen)
- **Schema Evolution**: Compatible mode for forward/backward compatibility
- **Thread-Safe Option**: Pool-based thread-safe wrapper for concurrent use

## Quick Start

### Installation

```bash
go get github.com/apache/fory/go/fory
```

### Basic Usage

```go
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

    // Register struct for cross-language serialization
    if err := f.RegisterByName(User{}, "example.User"); err != nil {
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
    // Output: Deserialized: {ID:1 Name:Alice Age:30}
}
```

## Architecture

Fory Go provides two serialization paths:

### Reflection-Based (Default)

The default path uses Go's reflection to inspect types at runtime. This works out-of-the-box with any struct:

```go
f := fory.New()
data, _ := f.Serialize(myStruct)
```

### Code Generation (Experimental)

For performance-critical paths, Fory provides optional ahead-of-time code generation that eliminates reflection overhead. See the [Code Generation](codegen) guide for details.

## Configuration

Fory Go uses a functional options pattern for configuration:

```go
f := fory.New(
    fory.WithTrackRef(true),      // Enable reference tracking
    fory.WithCompatible(true),    // Enable schema evolution
    fory.WithMaxDepth(200),       // Set max nesting depth
)
```

See [Configuration](configuration) for all available options.

## Supported Types

Fory Go supports a wide range of types:

- **Primitives**: `bool`, `int8`-`int64`, `uint8`-`uint64`, `float32`, `float64`, `string`
- **Collections**: slices, maps, sets
- **Time**: `time.Time`, `time.Duration`
- **Pointers**: pointer types with automatic nil handling
- **Structs**: any struct with exported fields

See [Supported Types](supported-types) for the complete type mapping.

## Cross-Language Serialization

Fory Go is fully compatible with other Fory implementations. Data serialized in Go can be deserialized in Java, Python, C++, Rust, or JavaScript:

```go
// Go serialization
f := fory.New()
f.RegisterByName(User{}, "example.User")
data, _ := f.Serialize(&User{ID: 1, Name: "Alice"})
// 'data' can be deserialized by Java, Python, etc.
```

See [Cross-Language Serialization](cross-language) for type mapping and compatibility details.

## Documentation

| Topic                                      | Description                            |
| ------------------------------------------ | -------------------------------------- |
| [Configuration](configuration)             | Options and settings                   |
| [Basic Serialization](basic-serialization) | Core APIs and usage patterns           |
| [Type Registration](type-registration)     | Registering types for serialization    |
| [Supported Types](supported-types)         | Complete type support reference        |
| [References](references)                   | Circular references and shared objects |
| [Struct Tags](struct-tags)                 | Field-level configuration              |
| [Schema Evolution](schema-evolution)       | Forward/backward compatibility         |
| [Cross-Language](cross-language)           | Multi-language serialization           |
| [Code Generation](codegen)                 | Experimental AOT code generation       |
| [Thread Safety](thread-safety)             | Concurrent usage patterns              |
| [Troubleshooting](troubleshooting)         | Common issues and solutions            |

## Related Resources

- [Xlang Serialization Specification](/docs/specification/xlang_serialization_spec)
- [Cross-Language Type Mapping](/docs/specification/xlang_type_mapping)
- [GitHub Repository](https://github.com/apache/fory)
