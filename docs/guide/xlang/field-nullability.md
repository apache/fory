---
title: Field Nullability
sidebar_position: 40
id: field_nullability
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

This page explains how Fory handles field nullability in cross-language (xlang) serialization mode.

## Default Behavior

In xlang mode, **fields are non-nullable by default**. This means:

- Values must always be present (non-null)
- No null flag byte is written for the field
- Serialization is more compact

The following types are nullable by default:

- `Optional<T>` (Java, C++)
- Java boxed types (`Integer`, `Long`, `Double`, etc.)
- Go pointer types (`*int32`, `*string`, etc.)
- Rust `Option<T>`
- Python `Optional[T]`

| Field Type                                 | Default Nullable | Null Flag Written |
| ------------------------------------------ | ---------------- | ----------------- |
| Primitives (`int`, `bool`, `float`, etc.)  | No               | No                |
| `String`                                   | No               | No                |
| `List<T>`, `Map<K,V>`, `Set<T>`            | No               | No                |
| Custom structs                             | No               | No                |
| Enums                                      | No               | No                |
| Java boxed types (`Integer`, `Long`, etc.) | Yes              | Yes               |
| Go pointer types (`*int32`, `*string`)     | Yes              | Yes               |
| `Optional<T>` / `Option<T>`                | Yes              | Yes               |

## Wire Format

The nullable flag controls whether a **null flag byte** is written before the field value:

```
Non-nullable field: [value data]
Nullable field:     [null_flag] [value data if not null]
```

Where `null_flag` is:

- `-1` (NULL_FLAG): Value is null
- `-2` (NOT_NULL_VALUE_FLAG): Value is present

## Nullable vs Reference Tracking

These are related but distinct concepts:

| Concept                | Purpose                              | Flag Values                                 |
| ---------------------- | ------------------------------------ | ------------------------------------------- |
| **Nullable**           | Allow null values for a field        | `-1` (null), `-2` (not null)                |
| **Reference Tracking** | Deduplicate shared object references | `-1` (null), `-2` (not null), `≥0` (ref ID) |

Key differences:

- **Nullable only**: Writes `-1` or `-2` flag, no reference deduplication
- **Reference tracking**: Extends nullable semantics with reference IDs (`≥0`) for previously seen objects
- Both use the same flag byte position—ref tracking is a superset of nullable

When `refTracking=true`, the null flag byte doubles as a ref flag:

```
ref_flag = -1  → null value
ref_flag = -2  → new object (first occurrence)
ref_flag >= 0  → reference to object at index ref_flag
```

For detailed reference tracking behavior, see [Reference Tracking](field-reference-tracking.md).

## Language-Specific Examples

### Java

```java
public class Person {
    // Non-nullable by default in xlang mode
    String name;           // Must not be null
    int age;              // Primitive, always non-nullable
    List<String> tags;    // Must not be null

    // Explicitly nullable
    @ForyField(nullable = true)
    String nickname;      // Can be null

    // Optional wrapper - nullable by default
    Optional<String> bio; // Can be empty/null
}

Fory fory = Fory.builder()
    .withLanguage(Language.XLANG)
    .build();
fory.register(Person.class, "example.Person");
```

### Python

```python
from dataclasses import dataclass
from typing import Optional, List
import pyfory

@dataclass
class Person:
    # Non-nullable by default
    name: str              # Must have a value
    age: pyfory.int32      # Primitive
    tags: List[str]        # Must not be None

    # Optional makes it nullable
    nickname: Optional[str] = None  # Can be None
    bio: Optional[str] = None       # Can be None

fory = pyfory.Fory(xlang=True)
fory.register_type(Person, typename="example.Person")
```

### Rust

```rust
use fory::{Fory, ForyObject};

#[derive(ForyObject)]
struct Person {
    // Non-nullable by default
    name: String,
    age: i32,
    tags: Vec<String>,

    // Option<T> is nullable
    nickname: Option<String>,  // Can be None
    bio: Option<String>,       // Can be None
}
```

### Go

```go
type Person struct {
    // Non-nullable by default
    Name string
    Age  int32
    Tags []string

    // Pointer types for nullable fields
    Nickname *string  // Can be nil
    Bio      *string  // Can be nil
}

fory := forygo.NewFory(forygo.WithXlang(true))
fory.RegisterNamedStruct(Person{}, "example.Person")
```

### C++

```cpp
struct Person {
    // Non-nullable by default
    std::string name;
    int32_t age;
    std::vector<std::string> tags;

    // std::optional for nullable
    std::optional<std::string> nickname;
    std::optional<std::string> bio;
};
FORY_STRUCT(Person, name, age, tags, nickname, bio);
```

## Customizing Nullability

### Java: @ForyField Annotation

```java
public class Config {
    @ForyField(nullable = true)
    String optionalSetting;  // Explicitly nullable

    @ForyField(nullable = false)
    String requiredSetting;  // Explicitly non-nullable (default)
}
```

### C++: fory::field Wrapper

```cpp
struct Config {
    // Explicitly mark as nullable
    fory::field<std::string, 1, fory::nullable<true>> optional_setting;

    // Explicitly mark as non-nullable (default)
    fory::field<std::string, 2, fory::nullable<false>> required_setting;
};
FORY_STRUCT(Config, optional_setting, required_setting);
```

## Null Value Handling

When a non-nullable field receives a null value:

| Language | Behavior                                             |
| -------- | ---------------------------------------------------- |
| Java     | Throws `NullPointerException` or serialization error |
| Python   | Raises `TypeError` or serialization error            |
| Rust     | Compile-time error (non-Option types can't be None)  |
| Go       | Zero value is used (empty string, 0, etc.)           |
| C++      | Default-constructed value or undefined behavior      |

## Schema Compatibility

The nullable flag is part of the struct schema fingerprint. Changing a field's nullability is a **breaking change** that will cause schema version mismatch errors.

```
Schema A: { name: String (non-nullable) }
Schema B: { name: String (nullable) }
// These have different fingerprints and are incompatible
```

## Best Practices

1. **Use non-nullable by default**: Only make fields nullable when null is a valid semantic value
2. **Use Optional/Option wrappers**: Instead of raw types with nullable annotation
3. **Be consistent across languages**: Use the same nullability for corresponding fields
4. **Document nullable fields**: Make it clear which fields can be null in your API

## See Also

- [Reference Tracking](field-reference-tracking.md) - Shared and circular reference handling
- [Serialization](serialization.md) - Basic cross-language serialization
- [Type Mapping](../../specification/xlang_type_mapping.md) - Cross-language type mapping reference
- [Xlang Specification](../../specification/xlang_serialization_spec.md) - Binary protocol details
