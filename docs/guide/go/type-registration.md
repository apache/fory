---
title: Type Registration
sidebar_position: 30
id: go_type_registration
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

Type registration tells Fory how to identify and serialize your custom types. This is essential for cross-language serialization and polymorphic deserialization.

## Why Register Types?

1. **Cross-Language Compatibility**: Other languages need to know how to deserialize your types
2. **Polymorphism**: Fory needs to identify the actual type when deserializing interfaces
3. **Type Safety**: Registration ensures consistent type handling across serialization boundaries

## Registration Methods

### Register by Name

Register a type with a fully-qualified name string:

```go
f := fory.New()

type User struct {
    ID   int64
    Name string
}

// Register with a name
err := f.RegisterByName(User{}, "example.User")
if err != nil {
    panic(err)
}
```

The name should be unique and consistent across all languages. Convention: `namespace.TypeName`

### Register by Namespace

Register with separate namespace and type name:

```go
err := f.RegisterByNamespace(User{}, "example", "User")
```

This is equivalent to `RegisterByName(User{}, "example.User")`.

### Register by ID

Register with a numeric type ID:

```go
// Register with numeric ID
err := f.Register(User{}, 101)
```

Numeric IDs are more compact but require coordination across languages.

**ID Guidelines**:

- Use IDs 0-8192 for user types
- IDs must be consistent across all languages
- IDs 0-63 are reserved for internal types

## Enum Registration

Go doesn't have native enums, but you can use integer types:

```go
type Color int32

const (
    Red   Color = 0
    Green Color = 1
    Blue  Color = 2
)

f := fory.New()

// Register enum by name
err := f.RegisterEnumByName(Color(0), "example.Color")

// Or register by ID
err = f.RegisterEnum(Color(0), 102)
```

## Extension Types

For custom serialization logic, register extension types with a custom serializer:

```go
type CustomType struct {
    Data []byte
}

type CustomSerializer struct{}

func (s *CustomSerializer) Write(ctx *fory.WriteContext, value reflect.Value) {
    data := value.Interface().(CustomType).Data
    ctx.Buffer().WriteLength(len(data))
    ctx.Buffer().WriteBinary(data)
}

func (s *CustomSerializer) Read(ctx *fory.ReadContext, value reflect.Value) {
    length := ctx.Buffer().ReadLength(nil)
    data := ctx.Buffer().ReadBinary(length, nil)
    value.Set(reflect.ValueOf(CustomType{Data: data}))
}

// Register extension type
f := fory.New()
err := f.RegisterExtensionTypeByName(CustomType{}, "example.Custom", &CustomSerializer{})

// Or with numeric ID
err = f.RegisterExtensionType(CustomType{}, 103, &CustomSerializer{})
```

## Registration Scope

Type registration is per-Fory-instance:

```go
f1 := fory.New()
f2 := fory.New()

// Types registered on f1 are NOT available on f2
f1.RegisterByName(User{}, "example.User")

// f2 cannot deserialize User unless also registered
f2.RegisterByName(User{}, "example.User")
```

## Registration Timing

Register types before serialization/deserialization:

```go
f := fory.New()

// Good: Register before use
f.RegisterByName(User{}, "example.User")
data, _ := f.Serialize(&User{ID: 1})

// Bad: Type not registered
f2 := fory.New()
data2, err := f2.Serialize(&User{ID: 1})
// May fail or produce incompatible data
```

## Nested Type Registration

Register all types in a hierarchy:

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

// Register ALL types used in the object graph
f.RegisterByName(Address{}, "example.Address")
f.RegisterByName(Person{}, "example.Person")
```

## Cross-Language Registration

For cross-language serialization, use consistent names or IDs:

### Go

```go
f := fory.New()
f.RegisterByName(User{}, "example.User")
```

### Java

```java
Fory fory = Fory.builder().withLanguage(Language.XLANG).build();
fory.register(User.class, "example.User");
```

### Python

```python
fory = pyfory.Fory()
fory.register_type(User, typename="example.User")
```

### Rust

```rust
#[derive(Fory)]
#[tag("example.User")]
struct User {
    id: i64,
    name: String,
}
```

### C++

```cpp
auto fory = fory::Fory::create();
fory.register_struct<User>("example.User");
```

## Type ID Encoding

When using numeric IDs, Fory encodes them with the base type:

```
Full Type ID = (user_type_id << 8) | internal_type_id
```

For example:

- User ID: 1, Struct base type: 25
- Full ID: (1 << 8) | 25 = 281

This allows Fory to identify both the user type and its base serialization format.

## Best Practices

1. **Use meaningful names**: Follow `namespace.TypeName` convention
2. **Be consistent**: Use the same name/ID across all languages
3. **Register early**: Register all types before first serialization
4. **Register everything**: Include nested types, not just top-level types
5. **Document IDs**: If using numeric IDs, document the mapping

## Common Errors

### Unregistered Type

```
error: unknown type encountered
```

Solution: Register the type before serialization.

### Name Mismatch

Data serialized with name `example.User` cannot be deserialized if registered as `app.User`.

Solution: Use consistent names across all serializers.

### ID Conflict

Two types registered with the same ID will conflict.

Solution: Ensure unique IDs for each type.

## Related Topics

- [Cross-Language Serialization](cross-language)
- [Supported Types](supported-types)
- [Troubleshooting](troubleshooting)
