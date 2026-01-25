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

This page covers basic object graph serialization and the core serialization APIs.

## Object Graph Serialization

Apache Fory™ provides automatic serialization of complex object graphs, preserving the structure and relationships between objects. The `FORY_STRUCT` macro generates efficient serialization code at compile time, eliminating runtime overhead.

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
scope. This only works with **public** fields.

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
- Only public fields are supported

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

## Related Topics

- [Configuration](configuration.md) - Builder options
- [Type Registration](type-registration.md) - Registering types
- [Supported Types](supported-types.md) - All supported types
