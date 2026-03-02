# Apache Fory™ C\#

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://github.com/apache/fory/blob/main/LICENSE)

Apache Fory™ is a blazing fast multi-language serialization framework powered by JIT compilation and zero-copy techniques.

The C# implementation provides high-performance object graph serialization for .NET with source-generated serializers, optional reference tracking, schema evolution support, and cross-language compatibility.

## Why Apache Fory™ C\#?

- High-performance binary serialization for .NET 8+
- Cross-language compatibility with Java, Python, C++, Go, Rust, and JavaScript
- Source-generator-based serializers for `[ForyObject]` types
- Optional shared/circular reference tracking (`TrackRef(true)`)
- Compatible mode for schema evolution
- Thread-safe runtime wrapper (`ThreadSafeFory`) for concurrent workloads
- Dynamic object serialization APIs for heterogeneous payloads

## Quick Start

### Requirements

- .NET SDK 8.0+
- C# 12+

### Add Apache Fory™ C\#

From this repository, reference the library project:

```xml
<ItemGroup>
  <ProjectReference Include="../fory/csharp/src/Fory/Fory.csproj" />
  <ProjectReference
      Include="../fory/csharp/src/Fory.Generator/Fory.Generator.csproj"
      OutputItemType="Analyzer"
      ReferenceOutputAssembly="false" />
</ItemGroup>
```

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

## Core Features

### 1. Object Graph Serialization

`[ForyObject]` types are serialized with generated serializers.

```csharp
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
    public List<Address> Addresses { get; set; } = [];
}

Fory fory = Fory.Builder().Build();
fory.Register<Address>(100);
fory.Register<Person>(101);
```

### 2. Shared and Circular References

Enable reference tracking to preserve object identity.

```csharp
[ForyObject]
public sealed class Node
{
    public int Value { get; set; }
    public Node? Next { get; set; }
}

Fory fory = Fory.Builder().TrackRef(true).Build();
fory.Register<Node>(200);

Node node = new() { Value = 7 };
node.Next = node;

Node decoded = fory.Deserialize<Node>(fory.Serialize(node));
System.Diagnostics.Debug.Assert(object.ReferenceEquals(decoded, decoded.Next));
```

### 3. Schema Evolution

Compatible mode allows schema changes between writer and reader.

```csharp
[ForyObject]
public sealed class OneField
{
    public string? F1 { get; set; }
}

[ForyObject]
public sealed class TwoFields
{
    public string F1 { get; set; } = string.Empty;
    public string F2 { get; set; } = string.Empty;
}

Fory fory1 = Fory.Builder().Compatible(true).Build();
fory1.Register<OneField>(300);

Fory fory2 = Fory.Builder().Compatible(true).Build();
fory2.Register<TwoFields>(300);

TwoFields decoded = fory2.Deserialize<TwoFields>(fory1.Serialize(new OneField { F1 = "hello" }));
```

### 4. Dynamic Object Serialization

Use dynamic APIs for unknown/heterogeneous payloads.

```csharp
Fory fory = Fory.Builder().Build();

Dictionary<object, object?> map = new()
{
    ["k1"] = 7,
    [2] = "v2",
    [true] = null,
};

byte[] payload = fory.Serialize<object?>(map);
object? decoded = fory.Deserialize<object?>(payload);
```

### 5. Thread-Safe Runtime

`Fory` is single-thread optimized. Use `ThreadSafeFory` for concurrent access.

```csharp
using ThreadSafeFory fory = Fory.Builder().BuildThreadSafe();

fory.Register<User>(1);
Parallel.For(0, 128, i =>
{
    byte[] payload = fory.Serialize(i);
    int decoded = fory.Deserialize<int>(payload);
});
```

### 6. Custom Serializers

Provide custom encoding logic with `Serializer<T>`.

```csharp
public sealed class PointSerializer : Serializer<Point>
{
    public override Point DefaultValue => new();

    public override void WriteData(WriteContext context, in Point value, bool hasGenerics)
    {
        context.Writer.WriteVarInt32(value.X);
        context.Writer.WriteVarInt32(value.Y);
    }

    public override Point ReadData(ReadContext context)
    {
        return new Point
        {
            X = context.Reader.ReadVarInt32(),
            Y = context.Reader.ReadVarInt32(),
        };
    }
}

Fory fory = Fory.Builder().Build();
fory.Register<Point, PointSerializer>(400);
```

## Cross-Language Serialization

Use consistent registration mappings across languages.

```csharp
Fory fory = Fory.Builder()
    .Xlang(true)
    .Compatible(true)
    .Build();

fory.Register<Person>(100); // same ID on other language peers
```

See [xlang guide](../docs/guide/xlang/index.md) for mapping details.

## Architecture

The C# implementation consists of:

```text
csharp/
├── src/
│   ├── Fory/
│   │   ├── Fory.cs                 # Main runtime entry point
│   │   ├── Config.cs               # Builder + runtime config
│   │   ├── TypeResolver.cs         # Type binding and registration
│   │   ├── Serializer.cs           # Serializer base class
│   │   ├── PrimitiveSerializers.cs # Primitive serializers
│   │   ├── CollectionSerializers.cs
│   │   ├── DictionarySerializers.cs
│   │   └── ...
│   └── Fory.Generator/
│       └── ForyObjectGenerator.cs  # Source generator for [ForyObject]
└── tests/
    ├── Fory.Tests/
    └── Fory.XlangPeer/
```

## Building and Testing

Run from the `csharp` directory:

```bash
# Build
dotnet build Fory.sln -c Release

# Run tests
dotnet test Fory.sln -c Release
```

## Documentation

- [C# guide index](../docs/guide/csharp/index.md)
- [Cross-language serialization spec](../docs/specification/xlang_serialization_spec.md)
- [Cross-language type mapping](../docs/specification/xlang_type_mapping.md)
