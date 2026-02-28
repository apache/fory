---
title: Field Configuration
sidebar_position: 5
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

This page explains how to configure field-level metadata for serialization in Rust.

## Overview

Apache Fory™ provides the `#[fory(...)]` attribute macro to specify optional field-level metadata at compile time. This enables:

- **Tag IDs**: Assign compact numeric IDs to minimize struct field meta size overhead
- **Nullability**: Control whether fields can be null
- **Reference Tracking**: Enable reference tracking for shared ownership types
- **Field Skipping**: Exclude fields from serialization
- **Encoding Control**: Specify how integers are encoded (varint, fixed, tagged)

## Basic Syntax

The `#[fory(...)]` attribute is placed on individual struct fields:

```rust
use fory::ForyObject;

#[derive(ForyObject)]
struct Person {
    #[fory(id = 0)]
    name: String,

    #[fory(id = 1)]
    age: i32,

    #[fory(id = 2, nullable)]
    nickname: Option<String>,
}
```

Multiple options are separated by commas.

## Available Options

### Field ID (`id = N`)

Assigns a numeric ID to a field to minimize struct field meta size overhead:

```rust
#[derive(ForyObject)]
struct User {
    #[fory(id = 0)]
    id: i64,

    #[fory(id = 1)]
    name: String,

    #[fory(id = 2)]
    age: i32,
}
```

**Benefits**:

- Smaller serialized size (numeric IDs vs field names in metadata)
- Allows renaming fields without breaking binary compatibility

**Recommendation**: It is recommended to configure field IDs for compatible mode since it reduces serialization cost.

**Notes**:

- IDs must be unique within a struct
- IDs must be >= 0 (use -1 to explicitly opt-out of tag ID encoding)
- If not specified, field name is used in metadata (larger overhead)

### Skipping Fields (`skip`)

Excludes a field from serialization:

```rust
#[derive(ForyObject)]
struct User {
    #[fory(id = 0)]
    id: i64,

    #[fory(id = 1)]
    name: String,

    #[fory(skip)]
    password: String, // Not serialized
}
```

The `password` field will not be included in serialized output and will remain at its default value after deserialization.

### Nullable (`nullable`)

Controls whether null flags are written for fields:

```rust
use fory::{Fory, RcWeak};

#[derive(ForyObject)]
struct Record {
    // RcWeak is nullable by default, override to non-nullable
    #[fory(id = 0, nullable = false)]
    required_ref: RcWeak<Data>,
}
```

**Default Behavior**:

| Type                      | Default Nullable |
| ------------------------- | ---------------- |
| `Option<T>`               | `true`           |
| `RcWeak<T>`, `ArcWeak<T>` | `true`           |
| All other types           | `false`          |

**Notes**:

- For `Option<T>`, `RcWeak<T>`, `ArcWeak<T>`, nullable defaults to true
- For all other types, nullable defaults to false
- Use `nullable = false` to override defaults for types that are nullable by default

### Reference Tracking (`ref`)

Controls per-field reference tracking for shared ownership types:

```rust
use std::rc::Rc;
use std::sync::Arc;

#[derive(ForyObject)]
struct Container {
    // Enable reference tracking (default for Rc/Arc)
    #[fory(id = 0, ref = true)]
    shared_data: Rc<Data>,

    // Disable reference tracking
    #[fory(id = 1, ref = false)]
    unique_data: Rc<Data>,
}
```

**Default Behavior**:

| Type                              | Default Ref Tracking |
| --------------------------------- | -------------------- |
| `Rc<T>`, `Arc<T>`                 | `true`               |
| `RcWeak<T>`, `ArcWeak<T>`         | `true`               |
| `Option<Rc<T>>`, `Option<Arc<T>>` | `true` (inherited)   |
| All other types                   | `false`              |

**Use Cases**:

- Enable for fields that may be circular or shared
- Disable for fields that are always unique (optimization)

### Encoding (`encoding`)

Controls how integer fields are encoded:

```rust
#[derive(ForyObject)]
struct Metrics {
    // Variable-length encoding (smaller for small values)
    #[fory(id = 0, encoding = "varint")]
    count: i64,

    // Fixed-length encoding (consistent size)
    #[fory(id = 1, encoding = "fixed")]
    timestamp: i64,

    // Tagged encoding (includes type tag, u64 only)
    #[fory(id = 2, encoding = "tagged")]
    value: u64,
}
```

**Supported Encodings**:

| Type         | Options                     | Default  |
| ------------ | --------------------------- | -------- |
| `i32`, `u32` | `varint`, `fixed`           | `varint` |
| `i64`, `u64` | `varint`, `fixed`, `tagged` | `varint` |

**When to Use**:

- `varint`: Best for values that are often small (default)
- `fixed`: Best for values that use full range (e.g., timestamps, hashes)
- `tagged`: When type information needs to be preserved (u64 only)

### Compress (`compress`)

A convenience shorthand for controlling integer encoding:

```rust
#[derive(ForyObject)]
struct Data {
    // compress = true -> varint encoding (default)
    #[fory(id = 0, compress)]
    small_value: i32,

    // compress = false -> fixed encoding
    #[fory(id = 1, compress = false)]
    fixed_value: u32,
}
```

**Notes**:

- `compress` or `compress = true` is equivalent to `encoding = "varint"`
- `compress = false` is equivalent to `encoding = "fixed"`
- If both `compress` and `encoding` are specified, they must not conflict

## Type Classification

Fory classifies field types to determine default behavior:

| Type Class | Examples                       | Default Nullable | Default Ref |
| ---------- | ------------------------------ | ---------------- | ----------- |
| Primitive  | `i8`, `i32`, `f64`, `bool`     | `false`          | `false`     |
| Option     | `Option<T>`                    | `true`           | `false`     |
| Rc         | `Rc<T>`                        | `false`          | `true`      |
| Arc        | `Arc<T>`                       | `false`          | `true`      |
| RcWeak     | `RcWeak<T>` (fory type)        | `true`           | `true`      |
| ArcWeak    | `ArcWeak<T>` (fory type)       | `true`           | `true`      |
| Other      | `String`, `Vec<T>`, user types | `false`          | `false`     |

**Special Case**: `Option<Rc<T>>` and `Option<Arc<T>>` inherit the inner type's ref tracking behavior.

## Complete Example

```rust
use fory::ForyObject;
use std::rc::Rc;

#[derive(ForyObject, Default)]
struct Document {
    // Required fields with tag IDs
    #[fory(id = 0)]
    title: String,

    #[fory(id = 1)]
    version: i32,

    // Optional field (nullable by default for Option)
    #[fory(id = 2)]
    description: Option<String>,

    // Reference-tracked shared pointer
    #[fory(id = 3)]
    parent: Rc<Document>,

    // Nullable + reference-tracked
    #[fory(id = 4, nullable)]
    related: Option<Rc<Document>>,

    // Counter with varint encoding (small values)
    #[fory(id = 5, encoding = "varint")]
    view_count: u64,

    // Timestamp with fixed encoding (full range values)
    #[fory(id = 6, encoding = "fixed")]
    created_at: i64,

    // Skip sensitive field
    #[fory(skip)]
    internal_state: String,
}

fn main() {
    let fory = fory::Fory::default();

    let doc = Document {
        title: "My Document".to_string(),
        version: 1,
        description: Some("A sample document".to_string()),
        parent: Rc::new(Document::default()),
        related: None, // Allowed because nullable
        view_count: 42,
        created_at: 1704067200,
        internal_state: "secret".to_string(), // Will be skipped
    };

    let bytes = fory.serialize(&doc);
    let decoded: Document = fory.deserialize(&bytes).unwrap();
}
```

## Compile-Time Validation

Invalid configurations are caught at compile time:

```rust
// Error: duplicate field IDs
#[derive(ForyObject)]
struct Bad {
    #[fory(id = 0)]
    field1: String,

    #[fory(id = 0)]  // Compile error: duplicate id
    field2: String,
}

// Error: invalid id value
#[derive(ForyObject)]
struct Bad2 {
    #[fory(id = -2)]  // Compile error: id must be >= -1
    field: String,
}

// Error: conflicting encoding attributes
#[derive(ForyObject)]
struct Bad3 {
    #[fory(compress = true, encoding = "fixed")]  // Compile error: conflict
    field: i32,
}
```

## Cross-Language Compatibility

When serializing data to be read by other languages (Java, C++, Go, Python), use field configuration to match encoding expectations:

```rust
#[derive(ForyObject)]
struct CrossLangData {
    // Matches Java Integer with varint
    #[fory(id = 0, encoding = "varint")]
    int_var: i32,

    // Matches Java Integer with fixed
    #[fory(id = 1, encoding = "fixed")]
    int_fixed: i32,

    // Matches Java Long with tagged encoding
    #[fory(id = 2, encoding = "tagged")]
    long_tagged: u64,

    // Nullable pointer matches Java nullable reference
    #[fory(id = 3, nullable)]
    optional: Option<String>,
}
```

## Schema Evolution

Compatible mode supports schema evolution. It is recommended to configure field IDs to reduce serialization cost:

```rust
// Version 1
#[derive(ForyObject)]
struct DataV1 {
    #[fory(id = 0)]
    id: i64,

    #[fory(id = 1)]
    name: String,
}

// Version 2: Added new field
#[derive(ForyObject)]
struct DataV2 {
    #[fory(id = 0)]
    id: i64,

    #[fory(id = 1)]
    name: String,

    #[fory(id = 2)]
    email: Option<String>,  // New nullable field
}
```

Data serialized with V1 can be deserialized with V2 (new field will be `None`).

Alternatively, field IDs can be omitted (field names will be used in metadata with larger overhead):

```rust
#[derive(ForyObject)]
struct Data {
    id: i64,
    name: String,
}
```

## Default Values

- **Nullable**: `Option<T>`, `RcWeak<T>`, and `ArcWeak<T>` are nullable by default; all other types are non-nullable
- **Ref tracking**: `Rc<T>`, `Arc<T>`, `RcWeak<T>`, and `ArcWeak<T>` enable ref tracking by default; all other types are disabled

You **need to configure fields** when:

- A field can be None (use `Option<T>`)
- A field needs reference tracking for shared/circular objects (use `ref = true`)
- Integer types need specific encoding for cross-language compatibility
- You want to reduce metadata size (use field IDs)

```rust
// Xlang mode: explicit configuration required
#[derive(ForyObject)]
struct User {
    #[fory(id = 0)]
    name: String,                    // Non-nullable by default

    #[fory(id = 1)]
    email: Option<String>,           // Nullable (Option<T>)

    #[fory(id = 2, ref = true)]
    friend: Rc<User>,                // Ref tracking (default for Rc)
}
```

### Default Values Summary

| Type                      | Default Nullable | Default Ref Tracking |
| ------------------------- | ---------------- | -------------------- |
| Primitives, `String`      | `false`          | `false`              |
| `Option<T>`               | `true`           | `false`              |
| `Rc<T>`, `Arc<T>`         | `false`          | `true`               |
| `RcWeak<T>`, `ArcWeak<T>` | `true`           | `true`               |

## Best Practices

1. **Configure field IDs**: Recommended for compatible mode to reduce serialization cost
2. **Use `skip` for sensitive data**: Passwords, tokens, internal state
3. **Enable ref tracking for shared objects**: When the same pointer appears multiple times
4. **Disable ref tracking for unique fields**: Optimization when you know the field is unique
5. **Choose appropriate encoding**: `varint` for small values, `fixed` for full-range values
6. **Keep IDs stable**: Once assigned, don't change field IDs

## Options Reference

| Option     | Syntax                             | Description                          | Valid For                  |
| ---------- | ---------------------------------- | ------------------------------------ | -------------------------- |
| `id`       | `id = N`                           | Field tag ID to reduce metadata size | All fields                 |
| `skip`     | `skip`                             | Exclude field from serialization     | All fields                 |
| `nullable` | `nullable` or `nullable = bool`    | Control null flag writing            | All fields                 |
| `ref`      | `ref` or `ref = bool`              | Control reference tracking           | `Rc`, `Arc`, weak types    |
| `encoding` | `encoding = "varint/fixed/tagged"` | Integer encoding method              | `i32`, `u32`, `i64`, `u64` |
| `compress` | `compress` or `compress = bool`    | Shorthand for varint/fixed           | `i32`, `u32`               |

## Related Topics

- [Basic Serialization](basic-serialization.md) - Getting started with Fory serialization
- [Schema Evolution](schema-evolution.md) - Compatible mode and schema evolution
- [Cross-Language](cross-language.md) - Interoperability with Java, C++, Go, Python
