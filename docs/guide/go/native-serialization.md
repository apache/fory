---
title: Native Serialization
sidebar_position: 3
id: native_serialization
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

Go native serialization is the Go-only wire mode selected with `fory.WithXlang(false)`. Use it
when every writer and reader is a Go service and the payload should follow Go's type system instead
of the portable xlang type system.

Use [Xlang Serialization](xlang-serialization.md), the default Go mode, when bytes must be read by
Java, Python, C++, Rust, JavaScript/TypeScript, C#, Swift, Dart, Scala, Kotlin,
or another non-Go Fory runtime.

## When To Use Native Serialization

Use native serialization when:

- A payload is produced and consumed only by Go applications.
- The data model uses Go-specific behavior such as native `int`/`uint`, nil slices, nil maps,
  pointers, interfaces, or Go-only dynamic values.
- You need schema-consistent Go payloads with the smallest same-schema metadata surface.
- You want compatible schema evolution for Go-only rolling deployments without committing to a
  cross-language type mapping.
- You are using reflection or code-generated serializers for Go structs that never leave Go.

## Create a Native Runtime

```go
package main

import "github.com/apache/fory/go/fory"

type Order struct {
    ID     int64
    Amount float64
}

func main() {
    f := fory.New(fory.WithXlang(false))
    if err := f.RegisterStruct(Order{}, 100); err != nil {
        panic(err)
    }

    data, err := f.Serialize(&Order{ID: 1, Amount: 42.5})
    if err != nil {
        panic(err)
    }

    var decoded Order
    if err := f.Deserialize(data, &decoded); err != nil {
        panic(err)
    }
}
```

Reuse a configured `Fory` instance. The default instance owns reusable buffers and is not
thread-safe; use the thread-safe wrapper for concurrent goroutines.

```go
import (
    "github.com/apache/fory/go/fory"
    "github.com/apache/fory/go/fory/threadsafe"
)

f := threadsafe.New(fory.WithXlang(false), fory.WithTrackRef(true))
_ = f.RegisterStruct(Order{}, 100)
```

## Schema Evolution

Native serialization defaults to schema-consistent mode. Writer and reader structs should match
when `WithCompatible(true)` is not set.

Enable compatible mode when Go-only services roll independently:

```go
writer := fory.New(fory.WithXlang(false), fory.WithCompatible(true))
reader := fory.New(fory.WithXlang(false), fory.WithCompatible(true))
```

Compatible mode writes schema metadata so readers can tolerate added, removed, or reordered fields
when field names or explicit field IDs remain compatible. See [Schema Evolution](schema-evolution.md).

## Registration

Register structs before serializing them. Prefer explicit numeric IDs for long-lived payloads:

```go
_ = f.RegisterStruct(Order{}, 100)
_ = f.RegisterStruct(LineItem{}, 101)
```

Name-based registration is useful when ID coordination is harder:

```go
_ = f.RegisterStructByName(Order{}, "example.Order")
```

If you register without stable IDs, every writer and reader must make the same registration choices.

## Go Object Surface

Native serialization keeps Go data on the Go runtime path:

- Primitive numeric types, including Go-native `int` and `uint`.
- Structs with exported fields.
- Slices, arrays, maps, and Fory sets.
- Pointers and nil values, including nil slices and maps.
- Interfaces and dynamic values when registered serializers can resolve their concrete types.
- Time values such as `time.Time` and `time.Duration`.
- Reflection-based and code-generated serializers.

Use [Supported Types](supported-types.md) for the full type surface and xlang mapping details.

## References And Pointers

Enable reference tracking for shared object identity or cycles:

```go
f := fory.New(fory.WithXlang(false), fory.WithTrackRef(true))

type Node struct {
    Value int32
    Next  *Node `fory:"ref"`
}
```

Disable reference tracking for value-shaped data. It is faster and smaller, but repeated pointers
deserialize as independent values and cyclic graphs are unsupported.

## Buffer Ownership

The default `Fory` instance reuses its internal buffer. Copy serialized bytes if they must outlive
the next serialization call:

```go
data, _ := f.Serialize(value)
stable := append([]byte(nil), data...)
```

The thread-safe wrapper copies bytes before returning them. For high-throughput single-threaded
code, serialize into a caller-owned `ByteBuffer`:

```go
buf := fory.NewByteBuffer(nil)
err := f.SerializeTo(buf, value)
data := buf.GetByteSlice(0, buf.WriterIndex())
_ = err
_ = data
```

## Performance Guidelines

- Reuse `Fory` or the thread-safe wrapper instead of constructing a runtime per request.
- Keep schema-consistent mode for lockstep Go services; enable compatible mode only when schema
  evolution is needed.
- Register structs with explicit numeric IDs.
- Disable reference tracking unless the graph requires identity or cycles.
- Use code generation for hot Go structs when reflection overhead matters.
- Copy returned bytes only when the data must survive the next serialization call.

## Native And Xlang Comparison

| Requirement                              | Use native serialization | Use xlang serialization |
| ---------------------------------------- | ------------------------ | ----------------------- |
| Go-only payloads                         | Yes                      | Optional                |
| Non-Go readers or writers                | No                       | Yes                     |
| Go-native `int`, `uint`, nil slice/map   | Yes                      | Limited                 |
| Schema-consistent same-language payloads | Yes                      | No                      |
| Compatible schema evolution by default   | No                       | Yes                     |
| Portable type mapping across runtimes    | No                       | Yes                     |

## Troubleshooting

### A non-Go runtime cannot read the payload

The writer is using native serialization. Rebuild it with `fory.WithXlang(true)` and align type
registration with every peer runtime.

### A rolling deployment fails after a field change

Native serialization defaults to schema-consistent mode. Use `fory.WithCompatible(true)` on both
writer and reader when struct definitions can differ.

### A nil slice or map changes shape

Use native serialization for Go-only payloads that must preserve Go nil slice/map semantics.
Cross-language schemas should model nullability explicitly.

### Returned bytes change after another serialization

The default runtime reuses its buffer. Copy the byte slice or use `threadsafe.New(...)`.

## Related Topics

- [Xlang Serialization](xlang-serialization.md) - Cross-runtime Go payloads
- [Configuration](configuration.md) - Go runtime options
- [Type Registration](type-registration.md) - Struct and enum registration
- [References](references.md) - Shared and circular references
- [Schema Evolution](schema-evolution.md) - Compatible mode
- [Code Generation](codegen.md) - Generated serializers
