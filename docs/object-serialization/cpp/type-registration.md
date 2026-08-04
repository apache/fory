---
title: Type Registration
sidebar_position: 5
id: type-registration
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

This page explains how to register types for serialization.

## Overview

Apache Fory™ requires explicit type registration for struct types. This design enables:

- **Xlang compatibility**: Registered type IDs are used across language boundaries
- **Type Safety**: Detects type mismatches at deserialization time
- **Polymorphic Serialization**: Enables serialization of polymorphic objects via smart pointers

## Registering Structs

Use `register_struct<T>(type_id)` to register a struct type:

```cpp
#include "fory/serialization/fory.h"

using namespace fory::serialization;

struct Person {
  std::string name;
  int32_t age;
};
FORY_STRUCT(Person, name, age);

int main() {
  auto fory = Fory::builder().xlang(true).build();

  // Register with a unique type ID
  fory.register_struct<Person>(100);

  Person person{"Alice", 30};
  auto bytes = fory.serialize(person).value();
  auto decoded = fory.deserialize<Person>(bytes).value();
}
```

## Type ID Guidelines

Type IDs must be:

1. **Unique**: Each type must have a unique ID within a Fory instance
2. **Consistent**: Same ID must be used across all languages and versions

User-registered type IDs are in a separate namespace from built-in type IDs, so you can start from 0:

```cpp
// User type IDs can start from 0
fory.register_struct<Address>(0);
fory.register_struct<Person>(1);
fory.register_struct<Order>(2);
```

## Registering Enums

Use `register_enum<T>(type_id)` to register an enum type. For simple enums with continuous values starting from 0, no macro is needed:

```cpp
// Simple continuous enum - no FORY_ENUM needed
enum class Color { RED, GREEN, BLUE };  // Values: 0, 1, 2

// Register with register_enum
fory.register_enum<Color>(0);
```

For enums with non-continuous values, use the `FORY_ENUM` macro to map values to ordinals:

```cpp
// Non-continuous enum values - FORY_ENUM required
enum class Priority { LOW = 10, MEDIUM = 50, HIGH = 100 };
FORY_ENUM(Priority, LOW, MEDIUM, HIGH);
// FORY_ENUM must be defined at namespace scope.

// Global namespace enum (prefix with ::)
enum SparseStatus { UNKNOWN = -1, OK = 0, ERROR = 1 };
FORY_ENUM(::SparseStatus, UNKNOWN, OK, ERROR);

// Register after FORY_ENUM
fory.register_enum<Priority>(1);
fory.register_enum<SparseStatus>(2);
```

**When to use `FORY_ENUM`:**

- Enum values don't start from 0
- Enum values are not continuous (e.g., 10, 50, 100)
- You need name-to-value mapping at compile time

## Thread-Safe Registration

For `ThreadSafeFory`, register types before spawning threads:

```cpp
auto fory = Fory::builder().xlang(true).build_thread_safe();

// Register all types first
fory.register_struct<TypeA>(100);
fory.register_struct<TypeB>(101);

// Now safe to use from multiple threads
std::thread t1([&]() {
  auto result = fory.serialize(obj_a);
});
std::thread t2([&]() {
  auto result = fory.serialize(obj_b);
});
```

## Xlang Registration

For cross-language compatibility, ensure:

1. **Same Type ID**: Use identical IDs in all languages
2. **Compatible Types**: Use equivalent types across languages

### Java

```java
Fory fory = Fory.builder().withXlang(true).build();
fory.register(Person.class, 100);
fory.register(Address.class, 101);
```

### Python

```python
import pyfory

fory = pyfory.Fory(xlang=True)
fory.register(Person, type_id=100)
fory.register(Address, type_id=101)
```

### C++

```cpp
auto fory = Fory::builder().xlang(true).build();
fory.register_struct<Person>(100);
fory.register_struct<Address>(101);
```

## Built-in Type IDs

Built-in types have pre-assigned type IDs and don't need registration:

| Type ID | Type                    |
| ------- | ----------------------- |
| 0       | UNKNOWN                 |
| 1       | BOOL                    |
| 2       | INT8                    |
| 3       | INT16                   |
| 4       | INT32                   |
| 5       | VARINT32                |
| 6       | INT64                   |
| 7       | VARINT64                |
| 8       | TAGGED_INT64            |
| 9       | UINT8                   |
| 10      | UINT16                  |
| 11      | UINT32                  |
| 12      | VAR_UINT32              |
| 13      | UINT64                  |
| 14      | VAR_UINT64              |
| 15      | TAGGED_UINT64           |
| 16      | FLOAT8                  |
| 17      | FLOAT16                 |
| 18      | BFLOAT16                |
| 19      | FLOAT32                 |
| 20      | FLOAT64                 |
| 21      | STRING                  |
| 22      | LIST                    |
| 23      | SET                     |
| 24      | MAP                     |
| 25      | ENUM                    |
| 26      | NAMED_ENUM              |
| 27      | STRUCT                  |
| 28      | COMPATIBLE_STRUCT       |
| 29      | NAMED_STRUCT            |
| 30      | NAMED_COMPATIBLE_STRUCT |
| 31      | EXT                     |
| 32      | NAMED_EXT               |
| 33      | UNION                   |
| 34      | TYPED_UNION             |
| 35      | NAMED_UNION             |
| 36      | NONE                    |
| 37      | DURATION                |
| 38      | TIMESTAMP               |
| 39      | DATE                    |
| 40      | DECIMAL                 |
| 41      | BINARY                  |
| 42      | ARRAY                   |
| 43      | BOOL_ARRAY              |
| 44      | INT8_ARRAY              |
| 45      | INT16_ARRAY             |
| 46      | INT32_ARRAY             |
| 47      | INT64_ARRAY             |
| 48      | UINT8_ARRAY             |
| 49      | UINT16_ARRAY            |
| 50      | UINT32_ARRAY            |
| 51      | UINT64_ARRAY            |
| 52      | FLOAT8_ARRAY            |
| 53      | FLOAT16_ARRAY           |
| 54      | BFLOAT16_ARRAY          |
| 55      | FLOAT32_ARRAY           |
| 56      | FLOAT64_ARRAY           |
| 64      | CHAR                    |
| 65      | CHAR16                  |
| 66      | CHAR32                  |

## Error Handling

Registration errors are checked at serialization/deserialization time:

```cpp
// Attempting to serialize unregistered type
auto result = fory.serialize(unregistered_obj);
if (!result.ok()) {
  // Error: "Type not registered: ..."
  std::cerr << result.error().to_string() << std::endl;
}

// Type ID mismatch during deserialization
auto result = fory.deserialize<WrongType>(bytes);
if (!result.ok()) {
  // Error: "Type mismatch: expected X, got Y"
  std::cerr << result.error().to_string() << std::endl;
}
```

## Related Topics

- [Basic Serialization](core-api.md) - Using registered types
- [Xlang Serialization](xlang.md) - Cross-language considerations
- [Supported Types](supported-types.md) - All supported types
