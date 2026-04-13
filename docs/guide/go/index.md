---
title: Go Serialization Guide
sidebar_position: 0
id: index
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

- **High Performance**: Fast serialization and optimized binary protocols
- **Cross-Language**: Seamless data exchange with Java, Python, C++, Rust, and JavaScript
- **Automatic Serialization**: No IDL definitions or schema compilation required
- **Reference Tracking**: Built-in support for circular references and shared objects
- **Type Safety**: Strong typing with schema-aware serializers
- **Schema Evolution**: Compatible mode for forward/backward compatibility
- **Thread-Safe Option**: Pool-based thread-safe wrapper for concurrent use

## Quick Start

### Installation

**Requirements**: Go 1.24 or later

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
    // Output: Deserialized: {ID:1 Name:Alice Age:30}
}
```

## Default Serialization Path

Fory Go works out-of-the-box with ordinary Go structs. The standard runtime path caches type
metadata and keeps hot serialization paths optimized, so most applications can use it directly
without any extra build step:

```go
f := fory.New()
data, _ := f.Serialize(myStruct)
```

## Configuration

Fory Go uses a functional options pattern for configuration:

```go
f := fory.New(
    fory.WithTrackRef(true),      // Enable reference tracking
    fory.WithCompatible(true),    // Enable schema evolution
    fory.WithMaxDepth(20),       // Set max nesting depth
)
```

See [Configuration](configuration.md) for all available options.

## Supported Types

Fory Go supports a wide range of types:

- **Primitives**: `bool`, `int8`-`int64`, `uint8`-`uint64`, `float32`, `float64`, `string`
- **Collections**: slices, maps, sets
- **Time**: `time.Time`, `time.Duration`
- **Pointers**: pointer types with automatic nil handling
- **Structs**: any struct with exported fields

See [Supported Types](supported-types.md) for the complete type mapping.

## Cross-Language Serialization

Fory Go is fully compatible with other Fory implementations. Data serialized in Go can be deserialized in Java, Python, C++, Rust, or JavaScript:

```go
// Go serialization
f := fory.New()
f.RegisterStruct(User{}, 1)
data, _ := f.Serialize(&User{ID: 1, Name: "Alice"})
// 'data' can be deserialized by Java, Python, etc.
```

See [Cross-Language Serialization](cross-language.md) for type mapping and compatibility details.

## Documentation

| Topic                                         | Description                            |
| --------------------------------------------- | -------------------------------------- |
| [Configuration](configuration.md)             | Options and settings                   |
| [Basic Serialization](basic-serialization.md) | Core APIs and usage patterns           |
| [Type Registration](type-registration.md)     | Registering types for serialization    |
| [Supported Types](supported-types.md)         | Complete type support reference        |
| [References](references.md)                   | Circular references and shared objects |
| [Struct Tags](struct-tags.md)                 | Field-level configuration              |
| [Schema Evolution](schema-evolution.md)       | Forward/backward compatibility         |
| [Cross-Language](cross-language.md)           | Multi-language serialization           |
| [Thread Safety](thread-safety.md)             | Concurrent usage patterns              |
| [Troubleshooting](troubleshooting.md)         | Common issues and solutions            |

## Related Resources

- [Xlang Serialization Specification](../../specification/xlang_serialization_spec.md)
- [Cross-Language Type Mapping](../../specification/xlang_type_mapping.md)
- [GitHub Repository](https://github.com/apache/fory)
