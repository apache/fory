---
title: Basic Serialization
sidebar_position: 2
id: basic_serialization
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

This page covers typed serialization APIs in Apache Fory™ C#.

## Object Graph Serialization

Use `[ForyObject]` on your classes/structs and register them before use.

```csharp
using Apache.Fory;

[ForyObject]
public sealed class Address
{
    public string Street { get; set; } = string.Empty;
    public int Zip { get; set; }
}

[ForyObject]
public sealed class Person
{
    public long Id { get; set; }
    public string Name { get; set; } = string.Empty;
    public string? Nickname { get; set; }
    public List<int> Scores { get; set; } = [];
    public List<Address> Addresses { get; set; } = [];
}

Fory fory = Fory.Builder().Build();
fory.Register<Address>(100);
fory.Register<Person>(101);

Person person = new()
{
    Id = 42,
    Name = "Alice",
    Nickname = null,
    Scores = [10, 20, 30],
    Addresses = [new Address { Street = "Main", Zip = 94107 }],
};

byte[] payload = fory.Serialize(person);
Person decoded = fory.Deserialize<Person>(payload);
```

## Typed API

### Serialize / Deserialize with byte arrays

```csharp
byte[] payload = fory.Serialize(value);
MyType decoded = fory.Deserialize<MyType>(payload);
```

### Deserialize from `ReadOnlySpan<byte>`

```csharp
ReadOnlySpan<byte> span = payload;
MyType decoded = fory.Deserialize<MyType>(span);
```

### Stream-style frame consumption

```csharp
using System.Buffers;

ReadOnlySequence<byte> sequence = GetFramedSequence();
MyType first = fory.Deserialize<MyType>(ref sequence);
MyType second = fory.Deserialize<MyType>(ref sequence);
```

## Dynamic Payloads via Generic Object API

When the compile-time type is unknown or heterogeneous, use the generic API with `object?`.

```csharp
Dictionary<object, object?> value = new()
{
    ["k1"] = 7,
    [2] = "v2",
    [true] = null,
};

byte[] payload = fory.Serialize<object?>(value);
object? decoded = fory.Deserialize<object?>(payload);
```

## Buffer Writer API

Serialize directly into `IBufferWriter<byte>` targets.

```csharp
using System.Buffers;

ArrayBufferWriter<byte> writer = new();
fory.Serialize(writer, value);

ArrayBufferWriter<byte> dynamicWriter = new();
fory.Serialize<object?>(dynamicWriter, value);
```

## Notes

- Reuse the same `Fory` or `ThreadSafeFory` instance for better performance.
- Primitive types and collections do not require user registration.
- User `[ForyObject]` and custom serializer types should be registered explicitly.

## Related Topics

- [Type Registration](type-registration.md)
- [Supported Types](supported-types.md)
- [References](references.md)
