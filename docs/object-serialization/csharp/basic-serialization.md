---
title: Basic Serialization
sidebar_position: 1
id: basic-serialization
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

This page covers typed serialization APIs in the default xlang mode for Apache Fory™ C#.

## Object Graph Serialization

Use `[ForyStruct]` on your classes/structs and register them before use.

```csharp
using Apache.Fory;

[ForyStruct]
public sealed class Address
{
    public string Street { get; set; } = string.Empty;
    public int Zip { get; set; }
}

[ForyStruct]
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

## Class Inheritance

An annotated class includes the supported members declared by its annotated
base classes in one flattened schema. Annotate every class in the hierarchy
directly; `[ForyStruct]` is not inherited.

```csharp
[ForyStruct]
public abstract class Entity
{
    [ForyField(1)]
    private long _id;

    public long Id => _id;
}

[ForyStruct]
public sealed class User : Entity
{
    [ForyField(2)]
    public string Name { get; set; } = string.Empty;
}
```

Public and assembly-accessible mutable members are included automatically.
Private, protected-only, and otherwise inaccessible fields or properties must
carry `[ForyField]` on the class that declares them. Abstract annotated bases
publish schema information for concrete descendants but are not registered or
serialized as roots.

The concrete derived type is still registered once:

```csharp
fory.Register<User>(102);
```

For a base class from an unmodifiable package, declare its fields explicitly as
described in [External Types](external-types.md).

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

Dynamic maps normally decode as `Dictionary<object, object?>` when they have no
null key. If the payload uses reference tracking for the dynamic map itself, C#
returns `NullableKeyDictionary<object, object?>` so nested references and null
keys point to the decoded map owner.

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
- Register user types handled by `[ForyStruct]`, `[ForyEnum]`, `[ForyUnion]`,
  external structural serializers, or custom serializers explicitly.

## Cross-Language Interoperability

The default xlang format is shared by all Fory implementations. The following sections cover its cross-language type mapping, type identity, and interoperability requirements.

Apache Fory™ C# supports xlang serialization with other Fory implementations.

### Xlang Configuration

C# always writes and reads the xlang frame header. There is no mode switch, so interoperability code
only needs to configure the remaining settings such as compatibility mode and reference
tracking.

```csharp
Fory fory = Fory.Builder()
    .Build();
```

### Register with Stable IDs

```csharp
[ForyStruct]
public sealed class Person
{
    public string Name { get; set; } = string.Empty;
    public int Age { get; set; }
}

Fory fory = Fory.Builder()
    .Build();

fory.Register<Person>(100);
```

Use the same ID mapping on all languages.

Third-party classes, structs, and enums can use
[external-type serialization](external-types.md). Register the target type,
not its local serializer declaration, with the same ID or name used by the
other language peers.

### Register by Name

```csharp
fory.Register<Person>("com.example.Person");
```

### Xlang Example

#### C# (Serializer)

```csharp
Person person = new() { Name = "Alice", Age = 30 };
byte[] payload = fory.Serialize(person);
```

#### Java (Deserializer)

```java
Fory fory = Fory.builder()
        .withXlang(true)
        .withRefTracking(true)
    .build();

fory.register(Person.class, 100);
Person value = (Person) fory.deserialize(payloadFromCSharp);
```

#### Python (Deserializer)

```python
import pyfory

fory = pyfory.Fory(xlang=True, ref=True)
fory.register_type(Person, type_id=100)
value = fory.deserialize(payload_from_csharp)
```

### Type Mapping Reference

See [cross-language interoperability guide](../xlang.md) for complete mapping.

For reduced-precision numeric payloads, use `Half` / `Half[]` or `List<Half>` for xlang `float16`, and `BFloat16` / `BFloat16[]` or `List<BFloat16>` for xlang `bfloat16`.

### Lists and Dense Arrays

C# `List<T>` maps to Fory `list<T>`. Use the schema marker
`Apache.Fory.Schema.Types.Array<T>` when a field is dense `array<T>`.

| Fory schema       | C# schema marker sketch |
| ----------------- | ----------------------- |
| `list<int32>`     | `S.List<S.Int32>`       |
| `array<bool>`     | `S.Array<S.Bool>`       |
| `array<int8>`     | `S.Array<S.Int8>`       |
| `array<int16>`    | `S.Array<S.Int16>`      |
| `array<int32>`    | `S.Array<S.Int32>`      |
| `array<int64>`    | `S.Array<S.Int64>`      |
| `array<uint8>`    | `S.Array<S.UInt8>`      |
| `array<uint16>`   | `S.Array<S.UInt16>`     |
| `array<uint32>`   | `S.Array<S.UInt32>`     |
| `array<uint64>`   | `S.Array<S.UInt64>`     |
| `array<float16>`  | `S.Array<S.Float16>`    |
| `array<bfloat16>` | `S.Array<S.BFloat16>`   |
| `array<float32>`  | `S.Array<S.Float32>`    |
| `array<float64>`  | `S.Array<S.Float64>`    |

### Interoperability Best Practices

1. Keep type IDs stable and documented.
2. Keep compatible mode enabled for rolling upgrades.
3. Register all user types on both read/write peers.
4. Validate integration with real payload round trips.

### Related Guides

- [Type Registration](type-registration.md)
- [External Types](external-types.md)
- [Schema Evolution](schema-evolution.md)
- [Supported Types](supported-types.md)

## Related Topics

- [Type Registration](type-registration.md)
- [Supported Types](supported-types.md)
- [References](references.md)
