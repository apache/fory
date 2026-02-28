---
title: Type Registration
sidebar_position: 30
id: type_registration
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

Type registration tells Fory how to identify and serialize your custom types. Registration is required for struct, enum, and extension types.

## Why Register Types?

1. **Type Identification**: Fory needs to identify the actual type during deserialization
2. **Polymorphism**: When deserializing interface types, Fory must know which concrete type to create
3. **Cross-Language Compatibility**: Other languages need to recognize and deserialize your types

## Struct Registration

### Register by ID

Register a struct with a numeric type ID for compact serialization:

```go
type User struct {
    ID   int64
    Name string
}

f := fory.New()
err := f.RegisterStruct(User{}, 1)
if err != nil {
    panic(err)
}
```

**ID Guidelines**:

- IDs must be unique within your application
- IDs must be consistent across all languages for cross-language serialization
- Use the same ID for the same type in serializer and deserializer

### Register by Name

Register a struct with a type name string. This is more flexible but has higher serialization cost:

```go
f := fory.New()
err := f.RegisterNamedStruct(User{}, "example.User")
if err != nil {
    panic(err)
}
```

**Name Guidelines**:

- Use fully-qualified names following `namespace.TypeName` convention
- Names must be unique and consistent across all languages
- Names are case-sensitive

## Enum Registration

Go doesn't have native enums, but you can register integer types as enums:

### Register by ID

```go
type Status int32

const (
    StatusPending  Status = 0
    StatusActive   Status = 1
    StatusComplete Status = 2
)

f := fory.New()
err := f.RegisterEnum(Status(0), 1)
```

### Register by Name

```go
err := f.RegisterNamedEnum(Status(0), "example.Status")
```

## Extension Types

For types requiring custom serialization logic, register as extension types with a custom serializer:

```go
f := fory.New()

// Register by ID
err := f.RegisterExtension(CustomType{}, 1, &CustomSerializer{})

// Or register by name
err = f.RegisterNamedExtension(CustomType{}, "example.Custom", &CustomSerializer{})
```

See [Custom Serializers](custom-serializers.md) for details on implementing the `ExtensionSerializer` interface.

## Registration Scope

Type registration is per-Fory-instance:

```go
f1 := fory.New()
f2 := fory.New()

// Types registered on f1 are NOT available on f2
f1.RegisterStruct(User{}, 1)

// f2 cannot deserialize User unless also registered
f2.RegisterStruct(User{}, 1)
```

## Registration Timing

Register types after creating a Fory instance and before any serialize/deserialize calls:

```go
f := fory.New()

// Register before use
f.RegisterStruct(User{}, 1)
f.RegisterStruct(Order{}, 2)

// Now serialize/deserialize
data, _ := f.Serialize(&User{ID: 1, Name: "Alice"})
```

## Nested Type Registration

Register all struct types in the object graph, including nested types:

```go
type Address struct {
    City    string
    Country string
}

type Person struct {
    Name    string
    Address Address
}

f := fory.New()

// Register ALL struct types used in the object graph
f.RegisterStruct(Address{}, 1)
f.RegisterStruct(Person{}, 2)
```

## Cross-Language Registration

For cross-language serialization, types must be registered consistently across all languages.

### Using IDs

All languages use the same numeric ID:

**Go**:

```go
f.RegisterStruct(User{}, 1)
```

**Java**:

```java
fory.register(User.class, 1);
```

**Python**:

```python
fory.register(User, type_id=1)
```

### Using Names

All languages use the same type name:

**Go**:

```go
f.RegisterNamedStruct(User{}, "example.User")
```

**Java**:

```java
fory.register(User.class, "example.User");
```

**Python**:

```python
fory.register(User, typename="example.User")
```

**Rust**:

```rust
use fory::{Fory, ForyObject};

#[derive(ForyObject)]
struct User {
    id: i64,
    name: String,
}

let mut fory = Fory::default();
fory.register_by_name::<User>("example.User")?;
```

## Best Practices

1. **Register early**: Register all types at application startup before any serialization
2. **Be consistent**: Use the same ID or name across all languages and all instances
3. **Register all types**: Include nested struct types, not just top-level types
4. **Prefer IDs for performance**: Numeric IDs have lower serialization overhead than names
5. **Use names for flexibility**: Names are easier to manage and less prone to conflicts

## Common Errors

### Unregistered Type

```
error: unknown type encountered
```

**Solution**: Register the type before serialization/deserialization.

### ID/Name Mismatch

Data serialized with one ID or name cannot be deserialized if registered with a different ID or name.

**Solution**: Use consistent IDs or names across serializer and deserializer.

### Duplicate Registration

Two types registered with the same ID will conflict.

**Solution**: Ensure unique IDs for each type.

## Related Topics

- [Basic Serialization](basic-serialization.md)
- [Cross-Language Serialization](cross-language.md)
- [Supported Types](supported-types.md)
- [Troubleshooting](troubleshooting.md)
