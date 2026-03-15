---
title: Field Configuration
sidebar_position: 7
id: field_configuration
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

This page explains how to configure field-level metadata for serialization.

## Overview

Apache Fory™ provides three ways to configure field-level metadata at compile time:

1. **`fory::field<>` template** - Inline metadata in struct definition with wrapper types
2. **`FORY_FIELD_TAGS` macro** - Non-invasive metadata for basic field configuration
3. **`FORY_FIELD_CONFIG` macro** - Advanced configuration with builder pattern and encoding control

These enable:

- **Tag IDs**: Assign compact numeric IDs for schema evolution
- **Nullability**: Mark pointer fields as nullable
- **Reference Tracking**: Enable reference tracking for shared pointers
- **Encoding Control**: Specify wire format for integers (varint, fixed, tagged)
- **Dynamic Dispatch**: Control polymorphic type info for smart pointers

**Comparison:**

| Feature                 | `fory::field<>`       | `FORY_FIELD_TAGS` | `FORY_FIELD_CONFIG`       |
| ----------------------- | --------------------- | ----------------- | ------------------------- |
| **Struct modification** | Required (wrap types) | None              | None                      |
| **Encoding control**    | No                    | No                | Yes (varint/fixed/tagged) |
| **Builder pattern**     | No                    | No                | Yes                       |
| **Dynamic control**     | Yes                   | No                | Yes                       |
| **Compile-time verify** | Yes                   | Limited           | Yes (member pointers)     |
| **Cross-lang compat**   | Limited               | Limited           | Full                      |
| **Recommended for**     | Simple structs        | Third-party types | Complex/xlang structs     |

## The fory::field Template

```cpp
template <typename T, int16_t Id, typename... Options>
class field;
```

### Template Parameters

| Parameter | Description                                      |
| --------- | ------------------------------------------------ |
| `T`       | The underlying field type                        |
| `Id`      | Field tag ID (int16_t) for compact serialization |
| `Options` | Optional tags: `fory::nullable`, `fory::ref`     |

### Basic Usage

```cpp
#include "fory/serialization/fory.h"

using namespace fory::serialization;

struct Person {
  fory::field<std::string, 0> name;
  fory::field<int32_t, 1> age;
  fory::field<std::optional<std::string>, 2> nickname;
};
FORY_STRUCT(Person, name, age, nickname);
```

The `fory::field<>` wrapper is transparent - you can use it like the underlying type:

```cpp
Person person;
person.name = "Alice";           // Direct assignment
person.age = 30;
std::string n = person.name;     // Implicit conversion
int a = person.age.get();        // Explicit get()
```

## Tag Types

### fory::nullable

Marks a smart pointer field as nullable (can be `nullptr`):

```cpp
struct Node {
  fory::field<std::string, 0> name;
  fory::field<std::shared_ptr<Node>, 1, fory::nullable> next;  // Can be nullptr
};
FORY_STRUCT(Node, name, next);
```

**Valid for:** `std::shared_ptr<T>`, `fory::serialization::SharedWeak<T>`, `std::unique_ptr<T>`

**Note:** For nullable primitives or strings, use `std::optional<T>` instead:

```cpp
// Correct: use std::optional for nullable primitives
fory::field<std::optional<int32_t>, 0> optional_value;

// Wrong: nullable is not allowed for primitives
// fory::field<int32_t, 0, fory::nullable> value;  // Compile error!
```

### fory::not_null

Explicitly marks a pointer field as non-nullable. This is the default for smart pointers, but can be used for documentation:

```cpp
fory::field<std::shared_ptr<Data>, 0, fory::not_null> data;  // Must not be nullptr
```

**Valid for:** `std::shared_ptr<T>`, `fory::serialization::SharedWeak<T>`, `std::unique_ptr<T>`

### fory::ref

Enables reference tracking for shared pointer fields. When multiple fields reference the same object, it will be serialized once and shared:

```cpp
struct Graph {
  fory::field<std::string, 0> name;
  fory::field<std::shared_ptr<Graph>, 1, fory::ref> left;    // Ref tracked
  fory::field<std::shared_ptr<Graph>, 2, fory::ref> right;   // Ref tracked
};
FORY_STRUCT(Graph, name, left, right);
```

**Valid for:** `std::shared_ptr<T>`, `fory::serialization::SharedWeak<T>` (requires shared ownership)

### fory::dynamic\<V\>

Controls whether type info is written for polymorphic smart pointer fields:

- `fory::dynamic<true>`: Force type info to be written (enable runtime subtype support)
- `fory::dynamic<false>`: skip type info (use declared type directly, no dynamic dispatch)

By default, Fory auto-detects polymorphism via `std::is_polymorphic<T>`. Use this tag to override:

```cpp
// Base class with virtual methods (detected as polymorphic by default)
struct Animal {
  virtual ~Animal() = default;
  virtual std::string speak() const = 0;
};

struct Zoo {
  // Auto: type info written because Animal has virtual methods
  fory::field<std::shared_ptr<Animal>, 0, fory::nullable> animal;

  // Force non-dynamic: skip type info even though Animal has virtual methods
  // Use when you know the runtime type will always be exactly as declared
  fory::field<std::shared_ptr<Animal>, 1, fory::nullable, fory::dynamic<false>> fixed_animal;
};
FORY_STRUCT(Zoo, animal, fixed_animal);
```

**Valid for:** `std::shared_ptr<T>`, `std::unique_ptr<T>`

### Combining Tags

Multiple tags can be combined for shared pointers and SharedWeak:

```cpp
// Nullable + ref tracking
fory::field<std::shared_ptr<Node>, 0, fory::nullable, fory::ref> link;
```

## Type Rules

| Type                                 | Allowed Options                 | Nullability                        |
| ------------------------------------ | ------------------------------- | ---------------------------------- |
| Primitives, strings                  | None                            | Use `std::optional<T>` if nullable |
| `std::optional<T>`                   | None                            | Inherently nullable                |
| `std::shared_ptr<T>`                 | `nullable`, `ref`, `dynamic<V>` | Non-null by default                |
| `fory::serialization::SharedWeak<T>` | `nullable`, `ref`, `dynamic<V>` | Non-null by default                |
| `std::unique_ptr<T>`                 | `nullable`, `dynamic<V>`        | Non-null by default                |

## Complete Example

```cpp
#include "fory/serialization/fory.h"

using namespace fory::serialization;

// Define a struct with various field configurations
struct Document {
  // Required fields (non-nullable)
  fory::field<std::string, 0> title;
  fory::field<int32_t, 1> version;

  // Optional primitive using std::optional
  fory::field<std::optional<std::string>, 2> description;

  // Nullable pointer
  fory::field<std::unique_ptr<std::string>, 3, fory::nullable> metadata;

  // Reference-tracked shared pointer
  fory::field<std::shared_ptr<Document>, 4, fory::ref> parent;

  // Nullable + reference-tracked
  fory::field<std::shared_ptr<Document>, 5, fory::nullable, fory::ref> related;
};
FORY_STRUCT(Document, title, version, description, metadata, parent, related);

int main() {
  auto fory = Fory::builder().xlang(true).build();
  fory.register_struct<Document>(100);

  Document doc;
  doc.title = "My Document";
  doc.version = 1;
  doc.description = "A sample document";
  doc.metadata = nullptr;  // Allowed because nullable
  doc.parent = std::make_shared<Document>();
  doc.parent.get()->title = "Parent Doc";
  doc.related = nullptr;  // Allowed because nullable

  auto bytes = fory.serialize(doc).value();
  auto decoded = fory.deserialize<Document>(bytes).value();
}
```

## Compile-Time Validation

Invalid configurations are caught at compile time:

```cpp
// Error: nullable and not_null are mutually exclusive
fory::field<std::shared_ptr<int>, 0, fory::nullable, fory::not_null> bad1;

// Error: nullable only valid for smart pointers
fory::field<int32_t, 0, fory::nullable> bad2;

// Error: ref only valid for shared_ptr
fory::field<std::unique_ptr<int>, 0, fory::ref> bad3;

// Error: options not allowed for std::optional (inherently nullable)
fory::field<std::optional<int>, 0, fory::nullable> bad4;
```

## Backwards Compatibility

Existing structs without `fory::field<>` wrappers continue to work:

```cpp
// Old style - still works
struct LegacyPerson {
  std::string name;
  int32_t age;
};
FORY_STRUCT(LegacyPerson, name, age);

// New style with field metadata
struct ModernPerson {
  fory::field<std::string, 0> name;
  fory::field<int32_t, 1> age;
};
FORY_STRUCT(ModernPerson, name, age);
```

## FORY_FIELD_TAGS Macro

The `FORY_FIELD_TAGS` macro provides a non-invasive way to add field metadata without modifying struct definitions. This is useful for:

- **Third-party types**: Add metadata to types you don't own
- **Clean structs**: Keep struct definitions as pure C++
- **Isolated dependencies**: Confine Fory headers to serialization config files

### Usage

```cpp
// user_types.h - NO fory headers needed!
struct Document {
  std::string title;
  int32_t version;
  std::optional<std::string> description;
  std::shared_ptr<User> author;
  std::shared_ptr<User> reviewer;
  std::shared_ptr<Document> parent;
  std::unique_ptr<Data> data;
};

// serialization_config.cpp - fory config isolated here
#include "fory/serialization/fory.h"
#include "user_types.h"

FORY_STRUCT(Document, title, version, description, author, reviewer, parent, data)

FORY_FIELD_TAGS(Document,
  (title, 0),                      // string: non-nullable
  (version, 1),                    // int: non-nullable
  (description, 2),                // optional: inherently nullable
  (author, 3),                     // shared_ptr: non-nullable (default)
  (reviewer, 4, nullable),         // shared_ptr: nullable
  (parent, 5, ref),                // shared_ptr: non-nullable, with ref tracking
  (data, 6, nullable)              // unique_ptr: nullable
)
```

### FORY_FIELD_TAGS Options

| Field Type           | Valid Combinations                                                                       |
| -------------------- | ---------------------------------------------------------------------------------------- |
| Primitives, strings  | `(field, id)` only                                                                       |
| `std::optional<T>`   | `(field, id)` only                                                                       |
| `std::shared_ptr<T>` | `(field, id)`, `(field, id, nullable)`, `(field, id, ref)`, `(field, id, nullable, ref)` |
| `std::unique_ptr<T>` | `(field, id)`, `(field, id, nullable)`                                                   |

## FORY_FIELD_CONFIG Macro

The `FORY_FIELD_CONFIG` macro is the most powerful and flexible way to configure field-level serialization. It provides:

- **Builder pattern API**: Fluent, chainable configuration with `F(id).option1().option2()`
- **Encoding control**: Specify how unsigned integers are encoded (varint, fixed, tagged)
- **Compile-time verification**: Field names are verified against member pointers
- **Cross-language compatibility**: Configure encoding to match other languages (Java, Rust, etc.)

### Basic Syntax

```cpp
FORY_FIELD_CONFIG(StructType,
    (field1, fory::F(0)),                           // Simple: just ID
    (field2, fory::F(1).nullable()),                // With nullable
    (field3, fory::F(2).varint()),                  // With encoding
    (field4, fory::F(3).nullable().ref()),          // Multiple options
    (field5, 4)                                     // Backward compatible: integer ID
);
```

### The F() Builder

The `fory::F(id)` factory creates a `FieldMeta` object that supports method chaining:

```cpp
fory::F(0)                    // Create with field ID 0
    .nullable()               // Mark as nullable
    .ref()                    // Enable reference tracking
    .varint()                 // Use variable-length encoding
    .fixed()                  // Use fixed-size encoding
    .tagged()                 // Use tagged encoding
    .dynamic(false)           // skip type info (no dynamic dispatch)
    .dynamic(true)            // Force type info (enable dynamic dispatch)
    .compress(false)          // Disable compression
```

**Tip:** To use `F()` without the `fory::` prefix, add a using declaration:

```cpp
using fory::F;

FORY_FIELD_CONFIG(MyStruct,
    (field1, F(0).varint()),      // No prefix needed
    (field2, F(1).nullable())
);
```

### Encoding Options for Unsigned Integers

For `uint32_t` and `uint64_t` fields, you can specify the wire encoding:

| Method      | Type ID       | Description                                    | Use Case                              |
| ----------- | ------------- | ---------------------------------------------- | ------------------------------------- |
| `.varint()` | VAR_UINT32/64 | Variable-length encoding (1-5 or 1-10 bytes)   | Values typically small                |
| `.fixed()`  | UINT32/64     | Fixed-size encoding (always 4 or 8 bytes)      | Values uniformly distributed          |
| `.tagged()` | TAGGED_UINT64 | Tagged hybrid encoding with size hint (uint64) | Mixed small and large values (uint64) |

**Note:** `uint8_t` and `uint16_t` always use fixed encoding (UINT8, UINT16).

### Complete Example

```cpp
#include "fory/serialization/fory.h"

using namespace fory::serialization;

// Define struct with unsigned integer fields
struct MetricsData {
  // Counters - often small values, use varint for space efficiency
  uint32_t request_count;
  uint64_t bytes_sent;

  // IDs - uniformly distributed, use fixed for consistent performance
  uint32_t user_id;
  uint64_t session_id;

  // Timestamps - use tagged encoding for mixed value ranges
  uint64_t created_at;

  // Nullable fields
  std::optional<uint32_t> error_count;
  std::optional<uint64_t> last_access_time;
};

FORY_STRUCT(MetricsData, request_count, bytes_sent, user_id, session_id,
            created_at, error_count, last_access_time);

// Configure field encoding
FORY_FIELD_CONFIG(MetricsData,
    // Small counters - varint saves space
    (request_count, fory::F(0).varint()),
    (bytes_sent, fory::F(1).varint()),

    // IDs - fixed for consistent performance
    (user_id, fory::F(2).fixed()),
    (session_id, fory::F(3).fixed()),

    // Timestamp - tagged encoding
    (created_at, fory::F(4).tagged()),

    // Nullable fields
    (error_count, fory::F(5).nullable().varint()),
    (last_access_time, fory::F(6).nullable().tagged())
);

int main() {
  auto fory = Fory::builder().xlang(true).build();
  fory.register_struct<MetricsData>(100);

  MetricsData data{
      .request_count = 42,
      .bytes_sent = 1024,
      .user_id = 12345678,
      .session_id = 9876543210,
      .created_at = 1704067200000000000ULL, // 2024-01-01 in nanoseconds
      .error_count = 3,
      .last_access_time = std::nullopt
  };

  auto bytes = fory.serialize(data).value();
  auto decoded = fory.deserialize<MetricsData>(bytes).value();
}
```

### Cross-Language Compatibility

When serializing data to be read by other languages, use `FORY_FIELD_CONFIG` to match their encoding expectations:

**Java Compatibility:**

```cpp
// Java uses these type IDs for unsigned integers:
// - Byte (u8): UINT8 (fixed)
// - Short (u16): UINT16 (fixed)
// - Integer (u32): VAR_UINT32 (varint) or UINT32 (fixed)
// - Long (u64): VAR_UINT64 (varint), UINT64 (fixed), or TAGGED_UINT64

struct JavaCompatible {
  uint8_t byte_field;      // Maps to Java Byte
  uint16_t short_field;    // Maps to Java Short
  uint32_t int_var_field;   // Maps to Java Integer with varint
  uint32_t int_fixed_field; // Maps to Java Integer with fixed
  uint64_t long_var_field;  // Maps to Java Long with varint
  uint64_t long_tagged;    // Maps to Java Long with tagged
};

FORY_STRUCT(JavaCompatible, byte_field, short_field, int_var_field,
            int_fixed_field, long_var_field, long_tagged);

FORY_FIELD_CONFIG(JavaCompatible,
    (byte_field, fory::F(0)),                    // UINT8 (auto)
    (short_field, fory::F(1)),                   // UINT16 (auto)
    (int_var_field, fory::F(2).varint()),         // VAR_UINT32
    (int_fixed_field, fory::F(3).fixed()),        // UINT32
    (long_var_field, fory::F(4).varint()),        // VAR_UINT64
    (long_tagged, fory::F(5).tagged())           // TAGGED_UINT64
);
```

### Schema Evolution with FORY_FIELD_CONFIG

In compatible mode, fields can have different nullability between sender and receiver:

```cpp
// Version 1: All fields non-nullable
struct DataV1 {
  uint32_t id;
  uint64_t timestamp;
};
FORY_STRUCT(DataV1, id, timestamp);
FORY_FIELD_CONFIG(DataV1,
    (id, fory::F(0).varint()),
    (timestamp, fory::F(1).tagged())
);

// Version 2: Added nullable fields
struct DataV2 {
  uint32_t id;
  uint64_t timestamp;
  std::optional<uint32_t> version;  // New nullable field
};
FORY_STRUCT(DataV2, id, timestamp, version);
FORY_FIELD_CONFIG(DataV2,
    (id, fory::F(0).varint()),
    (timestamp, fory::F(1).tagged()),
    (version, fory::F(2).nullable().varint())  // New field with nullable
);
```

### FORY_FIELD_CONFIG Options Reference

| Method            | Description                                      | Valid For                                            |
| ----------------- | ------------------------------------------------ | ---------------------------------------------------- |
| `.nullable()`     | Mark field as nullable                           | Smart pointers, primitives                           |
| `.ref()`          | Enable reference tracking                        | `std::shared_ptr`, `fory::serialization::SharedWeak` |
| `.dynamic(true)`  | Force type info to be written (dynamic dispatch) | Smart pointers                                       |
| `.dynamic(false)` | skip type info (use declared type directly)      | Smart pointers                                       |
| `.varint()`       | Use variable-length encoding                     | `uint32_t`, `uint64_t`                               |
| `.fixed()`        | Use fixed-size encoding                          | `uint32_t`, `uint64_t`                               |
| `.tagged()`       | Use tagged hybrid encoding                       | `uint64_t` only                                      |
| `.compress(v)`    | Enable/disable field compression                 | All types                                            |

## Default Values

- **Nullable**: Only `std::optional<T>` is nullable by default; all other types (including `std::shared_ptr`) are non-nullable
- **Ref tracking**: Enabled by default for `std::shared_ptr<T>` and `fory::serialization::SharedWeak<T>`, disabled for other types unless configured

You **need to configure fields** when:

- A field can be null (use `std::optional<T>` or mark with `nullable()`)
- A field needs reference tracking for shared/circular objects (use `ref()`)
- Integer types need specific encoding for cross-language compatibility
- You want to reduce metadata size (use field IDs)

```cpp
// Xlang mode: explicit configuration required
struct User {
    std::string name;                              // Non-nullable by default
    std::optional<std::string> email;              // Nullable (std::optional)
    std::shared_ptr<User> friend_ptr;              // Ref tracking by default
};

FORY_STRUCT(User, name, email, friend_ptr);

FORY_FIELD_CONFIG(User,
    (name, fory::F(0)),
    (email, fory::F(1)),                           // nullable implicit for optional
    (friend_ptr, fory::F(2).nullable().ref())      // explicit nullable + ref
);
```

### Default Values Summary

| Type                                 | Default Nullable | Default Ref Tracking |
| ------------------------------------ | ---------------- | -------------------- |
| Primitives, `string`                 | `false`          | `false`              |
| `std::optional<T>`                   | `true`           | `false`              |
| `std::shared_ptr<T>`                 | `false`          | `true`               |
| `fory::serialization::SharedWeak<T>` | `false`          | `true`               |
| `std::unique_ptr<T>`                 | `false`          | `false`              |

## Related Topics

- [Type Registration](type-registration.md) - Registering types with FORY_STRUCT
- [Schema Evolution](schema-evolution.md) - Using tag IDs for schema evolution
- [Configuration](configuration.md) - Enabling reference tracking globally
- [Cross-Language](cross-language.md) - Interoperability with Java, Rust, Python
