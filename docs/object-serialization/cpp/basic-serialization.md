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

This page covers basic object graph serialization and the core serialization APIs in the default xlang mode.

## Object Graph Serialization

Apache Fory™ provides automatic serialization of complex object graphs, preserving the structure and relationships between objects. The `FORY_STRUCT` macro generates efficient serialization code at compile time, eliminating reflection overhead.

**Key capabilities:**

- Nested struct serialization with arbitrary depth
- Collection types (vector, set, map)
- Optional fields with `std::optional<T>`
- Smart pointers (`std::shared_ptr`, `std::unique_ptr`)
- Automatic handling of primitive types and strings
- Efficient binary encoding with variable-length integers

```cpp
#include "fory/serialization/fory.h"
#include <vector>
#include <map>

using namespace fory::serialization;

// Define structs
struct Address {
  std::string street;
  std::string city;
  std::string country;

  bool operator==(const Address &other) const {
    return street == other.street && city == other.city &&
           country == other.country;
  }
};
FORY_STRUCT(Address, street, city, country);

struct Person {
  std::string name;
  int32_t age;
  Address address;
  std::vector<std::string> hobbies;
  std::map<std::string, std::string> metadata;

  bool operator==(const Person &other) const {
    return name == other.name && age == other.age &&
           address == other.address && hobbies == other.hobbies &&
           metadata == other.metadata;
  }
};
FORY_STRUCT(Person, name, age, address, hobbies, metadata);

int main() {
  auto fory = Fory::builder().xlang(true).build();
  fory.register_struct<Address>(100);
  fory.register_struct<Person>(200);

  Person person{
      "John Doe",
      30,
      {"123 Main St", "New York", "USA"},
      {"reading", "coding"},
      {{"role", "developer"}}
  };

  auto result = fory.serialize(person);
  auto decoded = fory.deserialize<Person>(result.value());
  assert(person == decoded.value());
}
```

## Serialization APIs

### Serialize to New Vector

```cpp
auto fory = Fory::builder().xlang(true).build();
fory.register_struct<MyStruct>(1);

MyStruct obj{/* ... */};

// Serialize - returns Result<std::vector<uint8_t>, Error>
auto result = fory.serialize(obj);
if (result.ok()) {
  std::vector<uint8_t> bytes = std::move(result).value();
  // Use bytes...
} else {
  // Handle error
  std::cerr << result.error().to_string() << std::endl;
}
```

### Serialize to Existing Buffer

```cpp
// Serialize to existing Buffer (fastest path)
Buffer buffer;
auto result = fory.serialize_to(buffer, obj);
if (result.ok()) {
  size_t bytes_written = result.value();
  // buffer now contains serialized data
}

// Serialize to existing vector (zero-copy)
std::vector<uint8_t> output;
auto result = fory.serialize_to(output, obj);
if (result.ok()) {
  size_t bytes_written = result.value();
  // output now contains serialized data
}
```

### Deserialize from Byte Array

```cpp
// Deserialize from raw pointer
auto result = fory.deserialize<MyStruct>(data_ptr, data_size);
if (result.ok()) {
  MyStruct obj = std::move(result).value();
}

// Deserialize from vector
std::vector<uint8_t> data = /* ... */;
auto result = fory.deserialize<MyStruct>(data);

// Deserialize from Buffer (updates reader_index)
Buffer buffer(data);
auto result = fory.deserialize<MyStruct>(buffer);
```

## Error Handling

Fory uses a `Result<T, Error>` type for error handling:

```cpp
auto result = fory.serialize(obj);

// Check if operation succeeded
if (result.ok()) {
  auto value = std::move(result).value();
  // Use value...
} else {
  Error error = result.error();
  std::cerr << "Error: " << error.to_string() << std::endl;
}

// Or use FORY_TRY macro for early return
FORY_TRY(bytes, fory.serialize(obj));
// Use bytes directly...
```

Common error types:

- `Error::type_mismatch` - Type ID mismatch during deserialization
- `Error::invalid_data` - Invalid or corrupted data
- `Error::buffer_out_of_bound` - Buffer overflow/underflow
- `Error::type_error` - Type registration error

## The FORY_STRUCT Macro

The `FORY_STRUCT` macro registers a class for serialization (struct works the
same way):

```cpp
class MyStruct {
public:
  int32_t x;
  std::string y;
  std::vector<int32_t> z;
  FORY_STRUCT(MyStruct, x, y, z);
};
```

Private fields are supported when the macro is placed in a `public:` section:

```cpp
class PrivateUser {
public:
  PrivateUser(int32_t id, std::string name) : id_(id), name_(std::move(name)) {}

  bool operator==(const PrivateUser &other) const {
    return id_ == other.id_ && name_ == other.name_;
  }

private:
  int32_t id_ = 0;
  std::string name_;

public:
  FORY_STRUCT(PrivateUser, id_, name_);
};
```

### Accessor Properties

Use `FORY_PROPERTY` when the serialized field is exposed through accessor
methods instead of a data member. This keeps the type registered as a normal
struct type:

```cpp
struct AccountImpl {
  int32_t id = 0;
};

class Account {
public:
  explicit Account(AccountImpl *impl) : impl_(impl) {}

  const int32_t &id() const { return impl_->id; }
  Account &id(int32_t value) {
    impl_->id = value;
    return *this;
  }

private:
  AccountImpl *impl_ = nullptr;

public:
  FORY_STRUCT(Account, FORY_PROPERTY(id));
};
```

`FORY_PROPERTY(id)` calls `obj.id()` to read the field and `obj.id(value)` to
write it. The field type is inferred from the const getter return type with
cv-qualifiers and references removed, so `const int32_t &` is treated as
`int32_t`.

Use the three-argument form when the getter and setter have different names:

```cpp
class User {
public:
  const int32_t &get_id() const;
  void set_id(int32_t value);

  FORY_STRUCT(User, FORY_PROPERTY(id, get_id, set_id));
};
```

Field metadata can be attached as the final argument:

```cpp
FORY_STRUCT(Account, FORY_PROPERTY(id, fory::F().varint()));
FORY_STRUCT(User, FORY_PROPERTY(id, get_id, set_id, fory::F(1).varint()));
```

When `FORY_STRUCT` is declared at namespace scope, the accessor methods must be
public. For private PIMPL accessors or private data members, place
`FORY_STRUCT` inside the class in a `public:` section.

The macro:

1. Generates compile-time field metadata
2. Enables member or ADL (Argument-Dependent Lookup) discovery for serialization
3. Creates efficient serialization code via template specialization

**Requirements:**

- Must be declared inside the class definition (struct works the same way) or
  at namespace scope
- Must be placed after all field declarations (when used inside the class)
- When used inside a class, the macro must be placed in a `public:` section
- All listed fields must be serializable types
- Field order in the macro is not important

## External / Third-Party Types

When you cannot modify a third-party type, use `FORY_STRUCT` at namespace
scope. This only works with public data members or public accessor methods.

```cpp
namespace thirdparty {
struct Foo {
  int32_t id;
  std::string name;
};

FORY_STRUCT(Foo, id, name);
} // namespace thirdparty
```

**Limitations:**

- Must be declared at namespace scope in the same namespace as the type
- Only public data members or accessor methods are supported

## Inherited Fields

To include base-class fields in a derived type, use `FORY_BASE(Base)` inside
`FORY_STRUCT`. The base must define its own `FORY_STRUCT` so its fields can be
referenced.

```cpp
struct Base {
  int32_t a;
  FORY_STRUCT(Base, a);
};

struct Derived : Base {
  int32_t b;
  FORY_STRUCT(Derived, FORY_BASE(Base), b);
};
```

**Notes:**

- Base fields are serialized before derived fields.
- Only fields visible from the derived type are supported.

## Nested Structs

Nested structs are fully supported:

```cpp
struct Inner {
  int32_t value;
  FORY_STRUCT(Inner, value);
};

struct Outer {
  Inner inner;
  std::string label;
  FORY_STRUCT(Outer, inner, label);
};

// Both must be registered
fory.register_struct<Inner>(1);
fory.register_struct<Outer>(2);
```

## Performance Tips

- **Buffer Reuse**: Use `serialize_to(buffer, obj)` with pre-allocated buffers
- **Pre-registration**: Register all types before serialization starts
- **Single-Threaded**: Use `build()` instead of `build_thread_safe()` when possible
- **Disable Tracking**: Use `track_ref(false)` when references aren't needed
- **Compact Encoding**: Variable-length encoding for space efficiency

## Cross-Language Interoperability

The default xlang format is shared by all supported Fory implementations. The following sections cover its cross-language type mapping, type identity, and interoperability requirements.

This page explains how to use Fory xlang serialization between C++ and other languages.

### Overview

Apache Fory™ enables seamless data exchange between C++, Java, Python, Go, Rust,
JavaScript/TypeScript, C#, Swift, Dart, Scala, and Kotlin. Xlang mode ensures
binary compatibility across all supported languages.

### Xlang Configuration

C++ defaults to xlang mode. Compatible schema evolution is also the xlang default. Set the mode explicitly in xlang examples:

```cpp
#include "fory/serialization/fory.h"

using namespace fory::serialization;

auto fory = Fory::builder().xlang(true).build();
```

### Xlang Example

#### C++ Producer

```cpp
#include "fory/serialization/fory.h"
#include <fstream>

using namespace fory::serialization;

struct Message {
  std::string topic;
  int64_t timestamp;
  std::map<std::string, std::string> headers;
  std::vector<uint8_t> payload;

  bool operator==(const Message &other) const {
    return topic == other.topic && timestamp == other.timestamp &&
           headers == other.headers && payload == other.payload;
  }
};
FORY_STRUCT(Message, topic, timestamp, headers, payload);

int main() {
  auto fory = Fory::builder().xlang(true).build();
  fory.register_struct<Message>(100);

  Message msg{
      "events.user",
      1699999999000,
      {{"content-type", "application/json"}},
      {'h', 'e', 'l', 'l', 'o'}
  };

  auto result = fory.serialize(msg);
  if (result.ok()) {
    auto bytes = std::move(result).value();
    // write to file, send over network, etc.
    std::ofstream file("message.bin", std::ios::binary);
    file.write(reinterpret_cast<const char*>(bytes.data()), bytes.size());
  }
  return 0;
}
```

#### Java Consumer

```java
import org.apache.fory.Fory;

public class Message {
    public String topic;
    public long timestamp;
    public Map<String, String> headers;
    public byte[] payload;
}

public class Consumer {
    public static void main(String[] args) throws Exception {
        Fory fory = Fory.builder()
            .withXlang(true)
            .build();
        fory.register(Message.class, 100);  // Same ID as C++

        byte[] bytes = Files.readAllBytes(Path.of("message.bin"));
        Message msg = (Message) fory.deserialize(bytes);

        System.out.println("Topic: " + msg.topic);
        System.out.println("Timestamp: " + msg.timestamp);
    }
}
```

#### Python Consumer

```python
import pyfory

class Message:
    topic: str
    timestamp: int
    headers: dict[str, str]
    payload: bytes

fory = pyfory.Fory(xlang=True)
fory.register(Message, type_id=100)  # Same ID as C++

with open("message.bin", "rb") as f:
    data = f.read()

msg = fory.deserialize(data)
print(f"Topic: {msg.topic}")
print(f"Timestamp: {msg.timestamp}")
```

### Type Mapping

#### Primitive Types

| C++ Type           | Java Type  | Python Type       | Go Type             | Rust Type  |
| ------------------ | ---------- | ----------------- | ------------------- | ---------- |
| `bool`             | `boolean`  | `bool`            | `bool`              | `bool`     |
| `int8_t`           | `byte`     | `int`             | `int8`              | `i8`       |
| `int16_t`          | `short`    | `int`             | `int16`             | `i16`      |
| `int32_t`          | `int`      | `int`             | `int32`             | `i32`      |
| `int64_t`          | `long`     | `int`             | `int64`             | `i64`      |
| `float`            | `float`    | `float`           | `float32`           | `f32`      |
| `double`           | `double`   | `float`           | `float64`           | `f64`      |
| `fory::float16_t`  | `Float16`  | `pyfory.Float16`  | `float16.Float16`   | `Float16`  |
| `fory::bfloat16_t` | `BFloat16` | `pyfory.BFloat16` | `bfloat16.BFloat16` | `BFloat16` |

#### String Types

| C++ Type      | Java Type | Python Type | Go Type  | Rust Type |
| ------------- | --------- | ----------- | -------- | --------- |
| `std::string` | `String`  | `str`       | `string` | `String`  |

#### Collection Types

| C++ Type                                    | Java Type      | Python Type     | Go Type               | Rust Type       |
| ------------------------------------------- | -------------- | --------------- | --------------------- | --------------- |
| `std::vector<T>`                            | `List<T>`      | `list`          | `[]T`                 | `Vec<T>`        |
| `std::vector<fory::float16_t>`              | `Float16List`  | `Float16Array`  | `[]float16.Float16`   | `Vec<Float16>`  |
| `std::vector<fory::bfloat16_t>`             | `BFloat16List` | `BFloat16Array` | `[]bfloat16.BFloat16` | `Vec<BFloat16>` |
| `std::set<T>`                               | `Set<T>`       | `set`           | `map[T]struct{}`      | `HashSet<T>`    |
| `std::map<K,V>` / `std::unordered_map<K,V>` | `Map<K,V>`     | `dict`          | `map[K]V`             | `HashMap<K,V>`  |

#### Lists and Dense Arrays

`std::vector<T>` maps to Fory `list<T>` by default in handwritten C++ structs.
Use the field metadata DSL's array node when the schema is dense `array<T>`.

| Fory schema       | C++ metadata sketch                      |
| ----------------- | ---------------------------------------- |
| `list<int32>`     | `fory::F(id).list(fory::T::int32())`     |
| `array<bool>`     | `fory::F(id).array(fory::T::bool_())`    |
| `array<int8>`     | `fory::F(id).array(fory::T::int8())`     |
| `array<int16>`    | `fory::F(id).array(fory::T::int16())`    |
| `array<int32>`    | `fory::F(id).array(fory::T::int32())`    |
| `array<int64>`    | `fory::F(id).array(fory::T::int64())`    |
| `array<uint8>`    | `fory::F(id).array(fory::T::uint8())`    |
| `array<uint16>`   | `fory::F(id).array(fory::T::uint16())`   |
| `array<uint32>`   | `fory::F(id).array(fory::T::uint32())`   |
| `array<uint64>`   | `fory::F(id).array(fory::T::uint64())`   |
| `array<float16>`  | `fory::F(id).array(fory::T::float16())`  |
| `array<bfloat16>` | `fory::F(id).array(fory::T::bfloat16())` |
| `array<float32>`  | `fory::F(id).array(fory::T::float32())`  |
| `array<float64>`  | `fory::F(id).array(fory::T::float64())`  |

#### Temporal Types

| C++ Type          | Java Type   | Python Type     | Go Type         |
| ----------------- | ----------- | --------------- | --------------- |
| `fory::Timestamp` | `Instant`   | `datetime`      | `time.Time`     |
| `fory::Duration`  | `Duration`  | `timedelta`     | `time.Duration` |
| `fory::Date`      | `LocalDate` | `datetime.date` | `time.Time`     |

### Field Order Requirements

**Critical:** Fields are sorted by snake_case field name. The converted names must match across languages.

#### C++

```cpp
struct Person {
  std::string name;   // Field 0
  int32_t age;        // Field 1
  std::string email;  // Field 2
};
FORY_STRUCT(Person, name, age, email);  // Order matters!
```

#### Java

```java
public class Person {
    public String name;   // Field 0
    public int age;       // Field 1
    public String email;  // Field 2
}
```

#### Python

```python
class Person:
    name: str    # Field 0
    age: int     # Field 1
    email: str   # Field 2
```

### Type ID Consistency

All languages must use the same type IDs:

```cpp
// C++
fory.register_struct<Person>(100);
fory.register_struct<Address>(101);
fory.register_struct<Order>(102);
```

```java
// Java
fory.register(Person.class, 100);
fory.register(Address.class, 101);
fory.register(Order.class, 102);
```

```python
# Python
fory.register(Person, type_id=100)
fory.register(Address, type_id=101)
fory.register(Order, type_id=102)
```

### Compatible Mode

Xlang mode already uses compatible schema evolution by default. Keep that default for schemas that
may evolve independently:

```cpp
auto fory = Fory::builder().xlang(true).build();
```

Compatible mode allows:

- Adding new fields (with defaults)
- Removing unused fields
- Reordering fields

### Interoperability Troubleshooting

#### Type Mismatch Errors

```
Error: Type mismatch: expected 100, got 101
```

**Solution:** Ensure type IDs match across all languages.

#### Encoding Errors

```
Error: Invalid UTF-8 sequence
```

**Solution:** Ensure strings are valid UTF-8 in all languages.

### Related Guides

- [Configuration](configuration.md) - Builder options
- [Type Registration](type-registration.md) - Registering types
- [Supported Types](supported-types.md) - Type compatibility

## Related Topics

- [Configuration](configuration.md) - Builder options
- [Type Registration](type-registration.md) - Registering types
- [Supported Types](supported-types.md) - All supported types
