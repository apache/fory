---
title: Cross-Language Serialization
sidebar_position: 80
id: go_cross_language
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

Cross-language (xlang) mode is enabled by default:

```go
f := fory.New()  // xlang enabled by default

// Or explicitly:
f := fory.New(fory.WithXlang(true))
```

## Type Registration for Cross-Language

Use consistent names across all languages:

### Go

```go
type User struct {
    ID   int64
    Name string
}

f := fory.New()
f.RegisterByName(User{}, "example.User")

data, _ := f.Serialize(&User{ID: 1, Name: "Alice"})
```

### Java

```java
public class User {
    public long id;
    public String name;
}

Fory fory = Fory.builder()
    .withLanguage(Language.XLANG)
    .build();
fory.register(User.class, "example.User");

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

fory = pyfory.Fory()
fory.register_type(User, typename="example.User")

user = fory.deserialize(data)
```

### Rust

```rust
use fory::{Fory, ForyObject};

#[derive(ForyObject)]
#[tag("example.User")]
struct User {
    id: i64,
    name: String,
}

let fory = Fory::default();
let user: User = fory.deserialize(&data)?;
```

### C++

```cpp
#include "fory/serialization/fory.h"

struct User {
    int64_t id;
    std::string name;
};
FORY_STRUCT(User, id, name);

auto fory = fory::Fory::create();
fory.register_struct<User>("example.User");

auto user = fory.deserialize<User>(data);
```

## Type Mapping

### Primitive Types

| Go        | Java      | Python  | C++           | Rust     | Fory TypeId  |
| --------- | --------- | ------- | ------------- | -------- | ------------ |
| `bool`    | `boolean` | `bool`  | `bool`        | `bool`   | BOOL (1)     |
| `int8`    | `byte`    | `int`   | `int8_t`      | `i8`     | INT8 (2)     |
| `int16`   | `short`   | `int`   | `int16_t`     | `i16`    | INT16 (3)    |
| `int32`   | `int`     | `int`   | `int32_t`     | `i32`    | INT32 (4)    |
| `int64`   | `long`    | `int`   | `int64_t`     | `i64`    | INT64 (6)    |
| `float32` | `float`   | `float` | `float`       | `f32`    | FLOAT32 (17) |
| `float64` | `double`  | `float` | `double`      | `f64`    | FLOAT64 (18) |
| `string`  | `String`  | `str`   | `std::string` | `String` | STRING (19)  |

### Unsigned Integers

| Go       | Java | Python | C++        | Rust  | Fory TypeId |
| -------- | ---- | ------ | ---------- | ----- | ----------- |
| `uint8`  | -    | `int`  | `uint8_t`  | `u8`  | UINT8 (9)   |
| `uint16` | -    | `int`  | `uint16_t` | `u16` | UINT16 (10) |
| `uint32` | -    | `int`  | `uint32_t` | `u32` | UINT32 (11) |
| `uint64` | -    | `int`  | `uint64_t` | `u64` | UINT64 (13) |

**Note**: Java doesn't have unsigned types. Use the next larger signed type or handle overflow.

### Collection Types

| Go        | Java       | Python  | C++                       | Rust           |
| --------- | ---------- | ------- | ------------------------- | -------------- |
| `[]T`     | `List<T>`  | `list`  | `std::vector<T>`          | `Vec<T>`       |
| `map[K]V` | `Map<K,V>` | `dict`  | `std::unordered_map<K,V>` | `HashMap<K,V>` |
| `[]byte`  | `byte[]`   | `bytes` | `std::vector<uint8_t>`    | `Vec<u8>`      |

### Time Types

| Go              | Java       | Python      | Notes                |
| --------------- | ---------- | ----------- | -------------------- |
| `time.Time`     | `Instant`  | `datetime`  | Nanosecond precision |
| `time.Duration` | `Duration` | `timedelta` | Nanosecond precision |

## Field Ordering

Cross-language serialization requires consistent field ordering. Fory sorts fields by:

1. **Snake_case name** (alphabetically)
2. **Type ID**

Go field names are converted to snake_case for sorting:

```go
type Example struct {
    UserID    int64   // -> user_id
    FirstName string  // -> first_name
    Age       int32   // -> age
}

// Sorted order: age, first_name, user_id
```

Ensure other languages use matching field order or snake_case naming.

## String Encoding

Fory supports multiple string encodings in xlang mode:

| Encoding | ID  | Use Case              |
| -------- | --- | --------------------- |
| Latin1   | 0   | ASCII-only strings    |
| UTF-16LE | 1   | Wide characters       |
| UTF-8    | 2   | Default, most strings |

Go strings are UTF-8 by default. Fory handles encoding automatically.

## Binary Protocol

### Header Format

```
[Magic: 2 bytes (0x62D4)]
[Bitmap: 1 byte]
  - Bit 0: IsNil flag
  - Bit 1: LittleEndian flag
  - Bit 2: Xlang flag
  - Bit 3: OutOfBand flag
[Language: 1 byte]
  - Go = 4
[Meta Offset: 4 bytes (if compatible mode)]
```

### Language IDs

| Language   | ID  |
| ---------- | --- |
| Java       | 1   |
| Python     | 2   |
| C++        | 3   |
| Go         | 4   |
| JavaScript | 5   |
| Rust       | 6   |
| Dart       | 7   |

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

f := fory.New()
f.RegisterByName(Order{}, "example.Order")

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

Fory fory = Fory.builder()
    .withLanguage(Language.XLANG)
    .build();
fory.register(Order.class, "example.Order");

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

fory = pyfory.Fory()
fory.register_type(Message, typename="example.Message")

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

f := fory.New()
f.RegisterByName(Message{}, "example.Message")

var msg Message
f.Deserialize(data, &msg)
fmt.Println(msg.Content)  // "Hello from Python"
```

### Nested Structures

Cross-language nested structures require all types registered:

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

f := fory.New()
f.RegisterByName(Address{}, "example.Address")
f.RegisterByName(Company{}, "example.Company")
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

fory.register(Address.class, "example.Address");
fory.register(Company.class, "example.Company");
```

## Testing Cross-Language Compatibility

### Running Go Xlang Tests

```bash
cd java
mvn -T16 install -DskipTests
cd fory-core
FORY_GO_JAVA_CI=1 ENABLE_FORY_DEBUG_OUTPUT=1 mvn test -Dtest=org.apache.fory.xlang.GoXlangTest
```

This runs integration tests that verify Go-Java serialization compatibility.

### Manual Testing

1. Serialize in Go, save to file
2. Deserialize in target language
3. Verify data integrity

```go
// Go: Serialize to file
data, _ := f.Serialize(object)
os.WriteFile("test.fory", data, 0644)
```

```python
data = open("test.fory", "rb").read()
obj = fory.deserialize(data)
```

## Common Issues

### Field Name Mismatch

Go uses PascalCase, other languages may use camelCase or snake_case:

```go
// Go
type User struct {
    FirstName string  // Serialized as field name "FirstName"
}

// Java - must match
public class User {
    public String firstName;  // May not match!
}
```

**Solution**: Use consistent naming or struct tags.

### Type Overflow

Unsigned Go types don't exist in Java:

```go
var value uint64 = 18446744073709551615  // Max uint64
```

Java's `long` is signed and cannot hold this value.

**Solution**: Use signed types or handle overflow explicitly.

### Nil vs Null

Go nil slices/maps serialize as null:

```go
var slice []string = nil
// Serializes as null
```

Ensure other languages handle null appropriately.

## Best Practices

1. **Use consistent type names**: `namespace.TypeName` format
2. **Register all types**: Including nested and collection element types
3. **Match field names**: Consider case sensitivity
4. **Test cross-language**: Run integration tests early
5. **Handle type limitations**: Unsigned types, nil values
6. **Use compatible mode**: For schema flexibility across languages

## Related Topics

- [Type Registration](type-registration)
- [Supported Types](supported-types)
- [Schema Evolution](schema-evolution)
- [Xlang Serialization Specification](/docs/specification/xlang_serialization_spec)
- [Type Mapping Specification](/docs/specification/xlang_type_mapping)
