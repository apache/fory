---
title: Reference Tracking
sidebar_position: 45
id: reference_tracking
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

This page explains how Fory handles reference tracking for shared and circular references in cross-language serialization.

## Overview

Reference tracking enables:

- **Shared references**: Same object referenced multiple times is serialized once
- **Circular references**: Objects that reference themselves or form cycles
- **Memory efficiency**: No duplicate data for repeated objects

## Enabling Reference Tracking

### Java

```java
Fory fory = Fory.builder()
    .withLanguage(Language.XLANG)
    .withRefTracking(true)
    .build();
```

### Python

```python
fory = pyfory.Fory(xlang=True, ref=True)
```

### Go

```go
fory := forygo.NewFory(
    forygo.WithXlang(true),
    forygo.WithTrackRef(true),
)
```

### C++

```cpp
auto fory = fory::Fory::builder().xlang(true).track_ref(true).build();
```

### Rust

```rust
let fory = Fory::default()
    .xlang(true)
    .track_ref(true);
```

## Wire Format

When reference tracking is enabled, nullable fields write a **ref flag byte** before the value:

```
[ref_flag] [value data if not null/ref]
```

Where `ref_flag` is:

| Value                      | Meaning                                               |
| -------------------------- | ----------------------------------------------------- |
| `-1` (NULL_FLAG)           | Value is null                                         |
| `-2` (NOT_NULL_VALUE_FLAG) | Value is present, first occurrence                    |
| `≥0`                       | Reference ID pointing to previously serialized object |

## Reference Tracking vs Nullability

These are **independent** concepts:

| Concept                | Purpose                                    | Controlled By                            |
| ---------------------- | ------------------------------------------ | ---------------------------------------- |
| **Nullability**        | Whether a field can hold null values       | Field type (`Optional<T>`) or annotation |
| **Reference Tracking** | Whether duplicate objects are deduplicated | Global `refTracking` option              |

Key behavior:

- Ref flag bytes are **only written for nullable fields**
- Non-nullable fields skip ref flags entirely, even with `refTracking=true`
- Reference deduplication only applies to objects that appear multiple times

```java
// Reference tracking enabled, but non-nullable fields still skip ref flags
Fory fory = Fory.builder()
    .withLanguage(Language.XLANG)
    .withRefTracking(true)
    .build();
```

## Per-Field Reference Tracking

By default, **most fields do not track references** even when global `refTracking=true`. Only specific pointer/smart pointer types track references by default.

### Default Behavior by Language

| Language | Default Ref Tracking | Types That Track Refs by Default                           |
| -------- | -------------------- | ---------------------------------------------------------- |
| Java     | No                   | None (use annotation to enable)                            |
| Python   | No                   | None (use annotation to enable)                            |
| Go       | No                   | None (use `fory:"ref"` to enable)                          |
| C++      | Yes                  | `std::shared_ptr<T>`, `fory::serialization::SharedWeak<T>` |
| Rust     | No                   | `Rc<T>`, `Arc<T>`, `Weak<T>`                               |

### Customizing Per-Field Ref Tracking

#### Java: @ForyField Annotation

```java
public class Document {
    // Default: no ref tracking
    String title;

    // Enable ref tracking for this field
    @ForyField(trackingRef = true)
    Author author;

    // Shared across documents, track refs to avoid duplicates
    @ForyField(trackingRef = true)
    List<Tag> tags;
}
```

#### C++: fory::field Wrapper

```cpp
struct Document {
    std::string title;

    // shared_ptr/SharedWeak track refs by default
    std::shared_ptr<Author> author;
    fory::serialization::SharedWeak<Data> data;

    // Explicitly mark ref tracking when using field wrappers (optional)
    fory::field<std::shared_ptr<Tag>, 1, fory::ref> tag_owner;
};
FORY_STRUCT(Document, title, author, data, tag_owner);
```

To disable reference tracking for C++ entirely, set
`Fory::builder().track_ref(false)` on the serializer.

#### Rust: Field Attributes

```rust
use fory::ForyObject;
use std::rc::Rc;

#[derive(ForyObject)]
struct Document {
    title: String,

    // Rc/Arc track refs by default
    author: Rc<Author>,

    // Explicitly enable ref tracking
    #[fory(ref = true)]
    tags: Vec<Tag>,
}
```

#### Go: Struct Tags

```go
type Document struct {
    Title string

    // Enable ref tracking for pointer to struct
    Author *Author `fory:"ref"`

    // Enable ref tracking for slice
    Tags []Tag `fory:"ref"`
}
```

### When to Enable Per-Field Ref Tracking

Enable ref tracking for fields that:

- May contain the same object instance multiple times
- Are part of circular reference chains
- Hold large objects that might be shared

Disable (or leave default) for fields that:

- Always contain unique values
- Are primitives or simple value types
- Don't participate in object sharing

## Example: Shared References

```java
public class Container {
    List<String> data;
    List<String> sameData;  // Points to same list
}

Container obj = new Container();
obj.data = Arrays.asList("a", "b", "c");
obj.sameData = obj.data;  // Shared reference

// With refTracking=true: data serialized once, sameData stores reference ID
// With refTracking=false: data serialized twice (duplicate)
```

## Example: Circular References

```java
public class Node {
    String value;
    Node next;
}

Node a = new Node("A");
Node b = new Node("B");
a.next = b;
b.next = a;  // Circular reference

// With refTracking=true: works correctly
// With refTracking=false: infinite recursion error
```

## Language Support

| Language   | Shared Refs | Circular Refs        |
| ---------- | ----------- | -------------------- |
| Java       | Yes         | Yes                  |
| Python     | Yes         | Yes                  |
| Go         | Yes         | Yes                  |
| C++        | Yes         | Yes                  |
| JavaScript | Yes         | Yes                  |
| Rust       | Yes         | No (ownership rules) |

## Performance Considerations

- **Overhead**: Reference tracking adds a hash map lookup per object
- **When to enable**: Use when data has shared/circular references
- **When to disable**: Use for simple data structures without sharing

## See Also

- [Field Nullability](field-nullability.md) - How nullability affects serialization
- [Serialization](serialization.md) - Basic cross-language serialization examples
- [Xlang Specification](../../specification/xlang_serialization_spec.md) - Binary protocol details
