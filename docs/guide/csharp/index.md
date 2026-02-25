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

Apache Fory C# is a high-performance, cross-language serialization runtime for .NET. It provides object graph serialization, schema evolution, dynamic object support, and a thread-safe wrapper for concurrent workloads.

## Why Fory C#?

- High performance binary serialization for .NET 8+
- Cross-language compatibility with Fory implementations in Java, Python, C++, Go, Rust, and JavaScript
- Source-generator-based serializers for `[ForyObject]` types
- Optional reference tracking for shared and circular object graphs
- Compatible mode for schema evolution
- Thread-safe runtime (`ThreadSafeFory`) for multi-threaded services

## Quick Start

### Requirements

- .NET SDK 8.0+
- C# language version 12+

### Basic Example

```csharp
using Apache.Fory;

[ForyObject]
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
- `SerializeObject(object? value)` / `DeserializeObject(...)` for dynamic payloads
- `Register<T>(uint typeId)` and namespace/name registration APIs
- `Register<T, TSerializer>(...)` for custom serializers

## Documentation

| Topic                                         | Description                                      |
| --------------------------------------------- | ------------------------------------------------ |
| [Configuration](configuration.md)             | Builder options and runtime modes                |
| [Basic Serialization](basic-serialization.md) | Typed and dynamic serialization APIs             |
| [Type Registration](type-registration.md)     | Registering user types and custom serializers    |
| [Custom Serializers](custom-serializers.md)   | Implementing `Serializer<T>`                     |
| [Field Configuration](field-configuration.md) | `[Field]` attribute and integer encoding options |
| [References](references.md)                   | Shared/circular reference handling               |
| [Schema Evolution](schema-evolution.md)       | Compatible mode behavior                         |
| [Cross-Language](cross-language.md)           | Interoperability guidance                        |
| [Supported Types](supported-types.md)         | Built-in and generated type support              |
| [Thread Safety](thread-safety.md)             | `Fory` vs `ThreadSafeFory` usage                 |
| [Troubleshooting](troubleshooting.md)         | Common errors and debugging steps                |

## Related Resources

- [Cross-language serialization specification](../../specification/xlang_serialization_spec.md)
- [Cross-language type mapping](../../specification/xlang_type_mapping.md)
- [C# source directory](https://github.com/apache/fory/tree/main/csharp)
