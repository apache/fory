---
title: Cross-Language Serialization
sidebar_position: 80
id: cross_language
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

Fory Go enables seamless data exchange with Java, Python, C++, Rust, and JavaScript. This guide covers cross-language compatibility and type mapping.

## Enabling Cross-Language Mode

Cross-language (xlang) mode must be explicitly enabled:

```go
f := fory.New(fory.WithXlang(true))
```

## Type Registration for Cross-Language

Use consistent type IDs across all languages:

### Go

```go
type User struct {
    ID   int64
    Name string
}

f := fory.New(fory.WithXlang(true))
f.RegisterStruct(User{}, 1)
data, _ := f.Serialize(&User{ID: 1, Name: "Alice"})
```

### Java

```java
public class User {
    public long id;
    public String name;
}
Fory fory = Fory.builder().withXlang(true).build();
fory.register(User.class, 1);
User user = fory.deserialize(data, User.class);
```

### Python

```python
from dataclasses import dataclass
import pyfory

@dataclass
class User:
    id: pyfory.Int64Type
    name: str

fory = pyfory.Fory(xlang=True)
fory.register(User, type_id=1)
user = fory.deserialize(data)
```

## Type Mapping

See [Type Mapping Specification](../../specification/xlang_type_mapping.md) for detailed type mappings across all languages.

## Field Ordering

Cross-language serialization requires consistent field ordering. Fory sorts fields by their snake_case names alphabetically.

Go field names are converted to snake_case for sorting:

```go
type Example struct {
    UserID    int64   // -> user_id
    FirstName string  // -> first_name
    Age       int32   // -> age
}

// Sorted order: age, first_name, user_id
```

Ensure other languages use matching field names that produce the same snake_case ordering, or use field IDs for explicit control:

```go
type Example struct {
    UserID    int64  `fory:"id=0"`
    FirstName string `fory:"id=1"`
    Age       int32  `fory:"id=2"`
}
```

## Examples

### Go to Java

**Go (Serializer)**:

```go
type Order struct {
    ID       int64
    Customer string
    Total    float64
    Items    []string
}

f := fory.New(fory.WithXlang(true))
f.RegisterStruct(Order{}, 1)

order := &Order{
    ID:       12345,
    Customer: "Alice",
    Total:    99.99,
    Items:    []string{"Widget", "Gadget"},
}
data, _ := f.Serialize(order)
// Send 'data' to Java service
```

**Java (Deserializer)**:

```java
public class Order {
    public long id;
    public String customer;
    public double total;
    public List<String> items;
}

Fory fory = Fory.builder().withXlang(true).build();
fory.register(Order.class, 1);

Order order = fory.deserialize(data, Order.class);
```

### Python to Go

**Python (Serializer)**:

```python
from dataclasses import dataclass
import pyfory

@dataclass
class Message:
    id: pyfory.Int64Type
    content: str
    timestamp: pyfory.Int64Type

fory = pyfory.Fory(xlang=True)
fory.register(Message, type_id=1)

msg = Message(id=1, content="Hello from Python", timestamp=1234567890)
data = fory.serialize(msg)
```

**Go (Deserializer)**:

```go
type Message struct {
    ID        int64
    Content   string
    Timestamp int64
}

f := fory.New(fory.WithXlang(true))
f.RegisterStruct(Message{}, 1)

var msg Message
f.Deserialize(data, &msg)
fmt.Println(msg.Content)  // "Hello from Python"
```

### Nested Structures

Cross-language nested structures require all types to be registered:

**Go**:

```go
type Address struct {
    Street  string
    City    string
    Country string
}

type Company struct {
    Name    string
    Address Address
}

f := fory.New(fory.WithXlang(true))
f.RegisterStruct(Address{}, 1)
f.RegisterStruct(Company{}, 2)
```

**Java**:

```java
public class Address {
    public String street;
    public String city;
    public String country;
}

public class Company {
    public String name;
    public Address address;
}

fory.register(Address.class, 1);
fory.register(Company.class, 2);
```

## Common Issues

### Field Name Mismatch

Go uses PascalCase, other languages may use camelCase or snake_case. Fields are matched by their snake_case conversion:

```go
// Go
type User struct {
    FirstName string  // -> first_name
}

// Java - field name converted to snake_case must match
public class User {
    public String firstName;  // -> first_name (matches)
}
```

### Type Interpretation

Go unsigned types map to Java signed types with the same bit pattern:

```go
var value uint64 = 18446744073709551615  // Max uint64
```

Java's `long` holds the same bits but interprets as -1. Use `Long.toUnsignedString()` in Java if unsigned interpretation is needed.

### Nil vs Null

Go nil slices/maps serialize differently based on configuration:

```go
var slice []string = nil
// In xlang mode: serializes based on nullable configuration
```

Ensure other languages handle null appropriately.

## Best Practices

1. **Use consistent type IDs**: Same numeric ID for the same type across all languages
2. **Register all types**: Including nested struct types
3. **Match field ordering**: Use same snake_case names or explicit field IDs
4. **Test cross-language**: Run integration tests early and often
5. **Handle type differences**: Be aware of signed/unsigned interpretation differences

## Related Topics

- [Type Registration](type-registration.md)
- [Supported Types](supported-types.md)
- [Schema Evolution](schema-evolution.md)
- [Xlang Serialization Specification](../../specification/xlang_serialization_spec.md)
- [Type Mapping Specification](../../specification/xlang_type_mapping.md)
