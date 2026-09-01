# Apache Fory™ C\#

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://github.com/apache/fory/blob/main/LICENSE)

Apache Fory™ is a blazing fast multi-language serialization framework powered by JIT compilation and zero-copy techniques.

The C# implementation provides high-performance object graph serialization for .NET with source-generated serializers, optional reference tracking, schema evolution support, and cross-language compatibility.

## Why Apache Fory™ C\#?

- High-performance binary serialization for .NET 8+
- Cross-language compatibility with Java, Python, C++, Go, Rust, and JavaScript
- Source-generator-based serializers for `[ForyStruct]` types, plus `[ForyEnum]` and `[ForyUnion]` registration
- Flattened class inheritance schemas, including explicitly selected private base state
- Field-level schema descriptors with `[ForyField(Type = typeof(...))]`
- Optional shared/circular reference tracking (`TrackRef(true)`)
- Compatible mode for schema evolution
- Reduced-precision carriers for `Half` / `BFloat16` scalars and `Half[]` / `List<Half>` / `BFloat16[]` / `List<BFloat16>` array payloads
- Thread-safe wrapper (`ThreadSafeFory`) for concurrent workloads
- Dynamic object serialization APIs for heterogeneous payloads

## Quick Start

### Requirements

- .NET SDK 8.0+
- C# 12+

### Add Apache Fory™ C\#

From NuGet, reference the single `Apache.Fory` package. It includes the Fory library plus the source generator for `[ForyStruct]`, `[ForyEnum]`, and `[ForyUnion]` types.

```xml
<ItemGroup>
  <PackageReference Include="Apache.Fory" Version="1.7.1" />
</ItemGroup>
```

For local development against this repository, reference the Fory project and generator project directly:

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

## Core Features

### 1. Object Graph Serialization

`[ForyStruct]` types are serialized with generated serializers.

```csharp
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
    public List<Address> Addresses { get; set; } = [];
}

Fory fory = Fory.Builder().Build();
fory.Register<Address>(100);
fory.Register<Person>(101);
```

### 2. Class Inheritance

Annotate every first-party class in a serializable hierarchy directly. The
concrete serializer flattens inherited fields and properties into one schema:

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

An inaccessible base member is serialized only when it has `[ForyField]`.
Unmodifiable third-party bases use an external `BaseOnly` declaration. See the
[class inheritance guide](https://fory.apache.org/docs/object-serialization/csharp/basic-serialization/#class-inheritance).

### 3. Shared and Circular References

Enable reference tracking to preserve object identity.

```csharp
[ForyStruct]
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

### 4. Schema Evolution

Compatible mode allows schema changes between writer and reader.

```csharp
[ForyStruct]
public sealed class OneField
{
    public string? F1 { get; set; }
}

[ForyStruct]
public sealed class TwoFields
{
    public string F1 { get; set; } = string.Empty;
    public string F2 { get; set; } = string.Empty;
}

Fory fory1 = Fory.Builder().Build();
fory1.Register<OneField>(300);

Fory fory2 = Fory.Builder().Build();
fory2.Register<TwoFields>(300);

TwoFields decoded = fory2.Deserialize<TwoFields>(fory1.Serialize(new OneField { F1 = "hello" }));
```

### 5. Dynamic Object Serialization

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

### 6. Thread-Safe Fory

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

### 7. External Types

Generate serializers for mutable third-party classes, structs, and enums with a
local serializer declaration:

```csharp
[ForyStruct(Target = typeof(ThirdParty.User))]
internal abstract class UserSerializer
{
    [ForyField(
        1,
        TargetDeclaringType = typeof(ThirdParty.User),
        TargetMemberName = "<Name>k__BackingField")]
    public abstract string Name { get; }
}

Fory fory = Fory.Builder().Build();
fory.Register<ThirdParty.User>(300);

ThirdParty.User user = new() { Name = "Alice" };
byte[] payload = fory.Serialize(user);
ThirdParty.User decoded = fory.Deserialize<ThirdParty.User>(payload);
```

The target is the runtime and registration type. See the
[external-types guide](https://fory.apache.org/docs/object-serialization/csharp/external-types/).

### 8. Custom Serializers

Provide specialized encoding logic with `Serializer<T>`.

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
    .Build();

fory.Register<Person>(100); // same ID on other language peers
```

See [xlang guide](https://fory.apache.org/docs/object-serialization/xlang/) for mapping details.

## Documentation

- [C# guide index](https://fory.apache.org/docs/object-serialization/csharp/)
- [Cross-language serialization spec](https://fory.apache.org/docs/specification/xlang_serialization_spec/)
- [Cross-language type mapping](https://fory.apache.org/docs/specification/xlang_type_mapping/)
