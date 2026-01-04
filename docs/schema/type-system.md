---
title: FDL Type System
sidebar_position: 3
id: fdl_type_system
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

# FDL Type System

This document describes the FDL type system and how types map to each target language.

## Overview

FDL provides a rich type system designed for cross-language compatibility:

- **Primitive Types**: Basic scalar types (integers, floats, strings, etc.)
- **Enum Types**: Named integer constants
- **Message Types**: Structured compound types
- **Collection Types**: Lists and maps
- **Nullable Types**: Optional/nullable variants

## Primitive Types

### Boolean

```fdl
bool is_active = 1;
```

| Language | Type                  | Notes              |
| -------- | --------------------- | ------------------ |
| Java     | `boolean` / `Boolean` | Primitive or boxed |
| Python   | `bool`                |                    |
| Go       | `bool`                |                    |
| Rust     | `bool`                |                    |
| C++      | `bool`                |                    |

### Integer Types

FDL provides fixed-width signed integers:

| FDL Type | Size   | Range             |
| -------- | ------ | ----------------- |
| `int8`   | 8-bit  | -128 to 127       |
| `int16`  | 16-bit | -32,768 to 32,767 |
| `int32`  | 32-bit | -2^31 to 2^31 - 1 |
| `int64`  | 64-bit | -2^63 to 2^63 - 1 |

**Language Mapping:**

| FDL     | Java    | Python             | Go      | Rust  | C++       |
| ------- | ------- | ------------------ | ------- | ----- | --------- |
| `int8`  | `byte`  | `pyfory.Int8Type`  | `int8`  | `i8`  | `int8_t`  |
| `int16` | `short` | `pyfory.Int16Type` | `int16` | `i16` | `int16_t` |
| `int32` | `int`   | `pyfory.Int32Type` | `int32` | `i32` | `int32_t` |
| `int64` | `long`  | `int`              | `int64` | `i64` | `int64_t` |

**Examples:**

```fdl
message Counters {
    int8 tiny = 1;
    int16 small = 2;
    int32 medium = 3;
    int64 large = 4;
}
```

**Python Type Hints:**

Python's native `int` is arbitrary precision, so FDL uses type wrappers for fixed-width integers:

```python
from pyfory import Int8Type, Int16Type, Int32Type

@dataclass
class Counters:
    tiny: Int8Type
    small: Int16Type
    medium: Int32Type
    large: int  # int64 maps to native int
```

### Floating-Point Types

| FDL Type  | Size   | Precision     |
| --------- | ------ | ------------- |
| `float32` | 32-bit | ~7 digits     |
| `float64` | 64-bit | ~15-16 digits |

**Language Mapping:**

| FDL       | Java     | Python               | Go        | Rust  | C++      |
| --------- | -------- | -------------------- | --------- | ----- | -------- |
| `float32` | `float`  | `pyfory.Float32Type` | `float32` | `f32` | `float`  |
| `float64` | `double` | `float`              | `float64` | `f64` | `double` |

**Example:**

```fdl
message Coordinates {
    float64 latitude = 1;
    float64 longitude = 2;
    float32 altitude = 3;
}
```

### String Type

UTF-8 encoded text:

```fdl
string name = 1;
```

| Language | Type          | Notes                 |
| -------- | ------------- | --------------------- |
| Java     | `String`      | Immutable             |
| Python   | `str`         |                       |
| Go       | `string`      | Immutable             |
| Rust     | `String`      | Owned, heap-allocated |
| C++      | `std::string` |                       |

### Bytes Type

Raw binary data:

```fdl
bytes data = 1;
```

| Language | Type                   | Notes     |
| -------- | ---------------------- | --------- |
| Java     | `byte[]`               |           |
| Python   | `bytes`                | Immutable |
| Go       | `[]byte`               |           |
| Rust     | `Vec<u8>`              |           |
| C++      | `std::vector<uint8_t>` |           |

### Temporal Types

#### Date

Calendar date without time:

```fdl
date birth_date = 1;
```

| Language | Type                             | Notes                   |
| -------- | -------------------------------- | ----------------------- |
| Java     | `java.time.LocalDate`            |                         |
| Python   | `datetime.date`                  |                         |
| Go       | `time.Time`                      | Time portion ignored    |
| Rust     | `chrono::NaiveDate`              | Requires `chrono` crate |
| C++      | `fory::serialization::LocalDate` |                         |

#### Timestamp

Date and time with nanosecond precision:

```fdl
timestamp created_at = 1;
```

| Language | Type                             | Notes                   |
| -------- | -------------------------------- | ----------------------- |
| Java     | `java.time.Instant`              | UTC-based               |
| Python   | `datetime.datetime`              |                         |
| Go       | `time.Time`                      |                         |
| Rust     | `chrono::NaiveDateTime`          | Requires `chrono` crate |
| C++      | `fory::serialization::Timestamp` |                         |

## Enum Types

Enums define named integer constants:

```fdl
enum Priority [id=100] {
    LOW = 0;
    MEDIUM = 1;
    HIGH = 2;
    CRITICAL = 3;
}
```

**Language Mapping:**

| Language | Implementation                          |
| -------- | --------------------------------------- |
| Java     | `enum Priority { LOW, MEDIUM, ... }`    |
| Python   | `class Priority(IntEnum): LOW = 0, ...` |
| Go       | `type Priority int32` with constants    |
| Rust     | `#[repr(i32)] enum Priority { ... }`    |
| C++      | `enum class Priority : int32_t { ... }` |

**Java:**

```java
public enum Priority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;
}
```

**Python:**

```python
class Priority(IntEnum):
    LOW = 0
    MEDIUM = 1
    HIGH = 2
    CRITICAL = 3
```

**Go:**

```go
type Priority int32

const (
    PriorityLow      Priority = 0
    PriorityMedium   Priority = 1
    PriorityHigh     Priority = 2
    PriorityCritical Priority = 3
)
```

**Rust:**

```rust
#[derive(ForyObject, Debug, Clone, PartialEq, Default)]
#[repr(i32)]
pub enum Priority {
    #[default]
    Low = 0,
    Medium = 1,
    High = 2,
    Critical = 3,
}
```

**C++:**

```cpp
enum class Priority : int32_t {
    LOW = 0,
    MEDIUM = 1,
    HIGH = 2,
    CRITICAL = 3,
};
FORY_ENUM(Priority, LOW, MEDIUM, HIGH, CRITICAL);
```

## Message Types

Messages are structured types composed of fields:

```fdl
message User [id=101] {
    string id = 1;
    string name = 2;
    int32 age = 3;
}
```

**Language Mapping:**

| Language | Implementation                      |
| -------- | ----------------------------------- |
| Java     | POJO class with getters/setters     |
| Python   | `@dataclass` class                  |
| Go       | Struct with exported fields         |
| Rust     | Struct with `#[derive(ForyObject)]` |
| C++      | Struct with `FORY_STRUCT` macro     |

## Collection Types

### List (repeated)

The `repeated` modifier creates a list:

```fdl
repeated string tags = 1;
repeated User users = 2;
```

**Language Mapping:**

| FDL               | Java            | Python       | Go         | Rust          | C++                        |
| ----------------- | --------------- | ------------ | ---------- | ------------- | -------------------------- |
| `repeated string` | `List<String>`  | `List[str]`  | `[]string` | `Vec<String>` | `std::vector<std::string>` |
| `repeated int32`  | `List<Integer>` | `List[int]`  | `[]int32`  | `Vec<i32>`    | `std::vector<int32_t>`     |
| `repeated User`   | `List<User>`    | `List[User]` | `[]User`   | `Vec<User>`   | `std::vector<User>`        |

### Map

Maps with typed keys and values:

```fdl
map<string, int32> counts = 1;
map<string, User> users = 2;
```

**Language Mapping:**

| FDL                  | Java                   | Python            | Go                 | Rust                    | C++                              |
| -------------------- | ---------------------- | ----------------- | ------------------ | ----------------------- | -------------------------------- |
| `map<string, int32>` | `Map<String, Integer>` | `Dict[str, int]`  | `map[string]int32` | `HashMap<String, i32>`  | `std::map<std::string, int32_t>` |
| `map<string, User>`  | `Map<String, User>`    | `Dict[str, User]` | `map[string]User`  | `HashMap<String, User>` | `std::map<std::string, User>`    |

**Key Type Restrictions:**

Map keys should be hashable types:

- `string` (most common)
- Integer types (`int8`, `int16`, `int32`, `int64`)
- `bool`

Avoid using messages or complex types as keys.

## Nullable Types

The `optional` modifier makes a field nullable:

```fdl
message Profile {
    string name = 1;              // Required
    optional string bio = 2;      // Nullable
    optional int32 age = 3;       // Nullable integer
}
```

**Language Mapping:**

| FDL               | Java       | Python          | Go        | Rust             | C++                          |
| ----------------- | ---------- | --------------- | --------- | ---------------- | ---------------------------- |
| `optional string` | `String`\* | `Optional[str]` | `*string` | `Option<String>` | `std::optional<std::string>` |
| `optional int32`  | `Integer`  | `Optional[int]` | `*int32`  | `Option<i32>`    | `std::optional<int32_t>`     |

\*Java uses boxed types with `@ForyField(nullable = true)` annotation.

**Default Values:**

| Type               | Default Value       |
| ------------------ | ------------------- |
| Non-optional types | Language default    |
| Optional types     | `null`/`None`/`nil` |

## Reference Types

The `ref` modifier enables reference tracking:

```fdl
message TreeNode {
    string value = 1;
    ref TreeNode parent = 2;
    repeated ref TreeNode children = 3;
}
```

**Use Cases:**

1. **Shared References**: Same object referenced from multiple places
2. **Circular References**: Object graphs with cycles
3. **Large Objects**: Avoid duplicate serialization

**Language Mapping:**

| FDL        | Java     | Python | Go      | Rust       | C++                     |
| ---------- | -------- | ------ | ------- | ---------- | ----------------------- |
| `ref User` | `User`\* | `User` | `*User` | `Rc<User>` | `std::shared_ptr<User>` |

\*Java uses `@ForyField(trackingRef = true)` annotation.

## Type Compatibility Matrix

This matrix shows which type conversions are safe across languages:

| From → To   | bool | int8 | int16 | int32 | int64 | float32 | float64 | string |
| ----------- | ---- | ---- | ----- | ----- | ----- | ------- | ------- | ------ |
| **bool**    | ✓    | ✓    | ✓     | ✓     | ✓     | -       | -       | -      |
| **int8**    | -    | ✓    | ✓     | ✓     | ✓     | ✓       | ✓       | -      |
| **int16**   | -    | -    | ✓     | ✓     | ✓     | ✓       | ✓       | -      |
| **int32**   | -    | -    | -     | ✓     | ✓     | -       | ✓       | -      |
| **int64**   | -    | -    | -     | -     | ✓     | -       | -       | -      |
| **float32** | -    | -    | -     | -     | -     | ✓       | ✓       | -      |
| **float64** | -    | -    | -     | -     | -     | -       | ✓       | -      |
| **string**  | -    | -    | -     | -     | -     | -       | -       | ✓      |

✓ = Safe conversion, - = Not recommended

## Best Practices

### Choosing Integer Types

- Use `int32` as the default for most integers
- Use `int64` for large values (timestamps, IDs)
- Use `int8`/`int16` only when storage size matters

### String vs Bytes

- Use `string` for text data (UTF-8)
- Use `bytes` for binary data (images, files, encrypted data)

### Optional vs Required

- Use `optional` when the field may legitimately be absent
- Default to required fields for better type safety
- Document why a field is optional

### Reference Tracking

- Use `ref` only when needed (shared/circular references)
- Reference tracking adds overhead
- Test with realistic data to ensure correctness

### Collections

- Prefer `repeated` for ordered sequences
- Use `map` for key-value lookups
- Consider message types for complex map values
