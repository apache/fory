---
title: C# Serialization Guide
sidebar_position: 0
id: serialization_index
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

Apache Fory™ C# is a high-performance, cross-language serialization library for .NET. It provides object graph serialization, schema evolution, generic object payload support, and a thread-safe wrapper for concurrent workloads.

## Why Fory C#?

- High performance binary serialization for .NET 8+
- Xlang compatibility with Fory implementations in Java, Python, C++, Go, Rust,
  JavaScript/TypeScript, Swift, Dart, Scala, and Kotlin
- Source-generator-based serializers for `[ForyStruct]` types, plus `[ForyEnum]` and `[ForyUnion]` registration
- Optional reference tracking for shared and circular object graphs
- Compatible mode for schema evolution
- Thread-safe wrapper (`ThreadSafeFory`) for multi-threaded services

## Quick Start

### Requirements

- .NET SDK 8.0+
- C# language version 12+

### Install from NuGet

Reference the single `Apache.Fory` package. It includes the Fory library and the source generator for `[ForyStruct]`, `[ForyEnum]`, and `[ForyUnion]` types.

```xml
<ItemGroup>
  <PackageReference Include="Apache.Fory" Version="1.1.0" />
</ItemGroup>
```

### Basic Example

```csharp
using Apache.Fory;

[ForyStruct]
public sealed class User
{
    public long Id { get; set; }
    public string Name { get; set; } = string.Empty;
    public string? Email { get; set; }
}

Fory fory = Fory.Builder().Build();
fory.Register<User>(1);

User user = new()
{
    Id = 1,
    Name = "Alice",
    Email = "alice@example.com",
};

byte[] payload = fory.Serialize(user);
User decoded = fory.Deserialize<User>(payload);
```

## Core API Surface

- `Serialize<T>(in T value)` / `Deserialize<T>(...)`
- `Serialize<object?>(...)` / `Deserialize<object?>(...)` for dynamic payloads
- `Register<T>(uint typeId)` and name registration APIs
- `Register<T, TSerializer>(...)` for custom serializers

## Documentation

| Topic                                         | Description                                   |
| --------------------------------------------- | --------------------------------------------- |
| [Configuration](configuration.md)             | Builder options and mode settings             |
| [Basic Serialization](basic-serialization.md) | Typed and dynamic serialization APIs          |
| [Xlang Serialization](xlang-serialization.md) | Interoperability guidance                     |
| [Schema Metadata](schema-metadata.md)         | `[ForyField]` ids and schema type descriptors |
| [Type Registration](type-registration.md)     | Registering user types and custom serializers |
| [Custom Serializers](custom-serializers.md)   | Implementing `Serializer<T>`                  |
| [References](references.md)                   | Shared/circular reference handling            |
| [Schema Evolution](schema-evolution.md)       | Compatible mode behavior                      |
| [Supported Types](supported-types.md)         | Built-in and generated type support           |
| [Thread Safety](thread-safety.md)             | `Fory` vs `ThreadSafeFory` usage              |
| [gRPC Support](grpc-support.md)               | Generated Fory-backed gRPC service companions |
| [Troubleshooting](troubleshooting.md)         | Common errors and debugging steps             |

## Related Resources

- [Xlang serialization specification](../../specification/xlang_serialization_spec.md)
- [Xlang guide](../xlang/index.md)
- [C# source directory](https://github.com/apache/fory/tree/main/csharp)
