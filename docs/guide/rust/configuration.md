---
title: Configuration
sidebar_position: 4
id: configuration
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

This page covers Rust Fory instance configuration. `Fory::builder().xlang(true).build()` selects xlang mode with
compatible schema evolution. Native mode is selected explicitly with `.xlang(false)` and also defaults to
compatible schema evolution.

## Wire Modes

Apache Fory™ supports two serialization modes:

### Xlang Mode

Xlang mode is selected with `.xlang(true)` and uses the cross-language wire
format. Compatible schema evolution is the xlang default and is recommended for
cross-language services because schemas can diverge more easily across
languages:

```rust
let fory = Fory::builder().xlang(true).build();
```

Use `.compatible(false)` for xlang payloads only when every reader and writer always uses the same schema and you want faster serialization and smaller size. Use it only after verifying that every language uses that schema, or when native types are generated from Fory schema IDL:

```rust
let fory = Fory::builder().compatible(false).build();
```

### Native Mode

For Rust-only payloads, select native mode explicitly:

```rust
let fory = Fory::builder().xlang(false).build();
```

Compatible mode is enabled by default. Set `.compatible(false)` only when every reader and
writer always uses the same Rust schema and you want faster serialization and smaller size.

## Configuration

### Maximum Dynamic Object Nesting Depth

Apache Fory™ provides protection against stack overflow from deeply nested dynamic objects during deserialization. By default, the maximum nesting depth is set to 5 levels for trait objects and containers.

**Default configuration:**

```rust
let fory = Fory::builder().build(); // max_dyn_depth = 5
```

**Custom depth limit:**

```rust
let fory = Fory::builder().max_dyn_depth(10).build(); // Allow up to 10 levels
```

**When to adjust:**

- **Increase**: For legitimate deeply nested data structures
- **Decrease**: For stricter security requirements or shallow data structures

**Protected types:**

- `Box<dyn Any>`, `Rc<dyn Any>`, `Arc<dyn Any + Send + Sync>`
- `Box<dyn Trait>`, `Rc<dyn Trait>`, `Arc<dyn Trait>` (trait objects)
- `RcWeak<T>`, `ArcWeak<T>`
- Collection types (Vec, HashMap, HashSet)
- Nested struct types in Compatible mode

Note: Static data types (non-dynamic types) are secure by nature and not subject to depth limits, as their structure is known at compile time.

### Remote Schema Metadata Limits

Compatible mode can receive remote metadata for schema evolution. These limits
bound metadata size and accepted schema versions:

```rust
let fory = Fory::builder()
    .max_type_fields(512)
    .max_type_meta_bytes(4096)
    .max_schema_versions_per_type(10)
    .max_average_schema_versions_per_type(3)
    .build();
```

- `max_type_fields` defaults to `512` and limits fields in one received struct metadata body.
- `max_type_meta_bytes` defaults to `4096` and limits encoded body bytes in one received TypeDef or
  TypeMeta body, excluding the 8-byte header and any extended-size varint.
- `max_schema_versions_per_type` defaults to `10` and limits accepted remote metadata versions for
  one logical type.
- `max_average_schema_versions_per_type` defaults to `3` and limits the average across accepted
  remote types. The effective global floor is `8192` schemas.

### Graph Memory Budget

`max_graph_memory_bytes(...)` limits estimated shallow graph memory accepted during one root read.
The budget covers `Vec`/collection element storage, map key/value storage, and materialized struct
or object field storage; it is not an exact process heap limit. The default is `-1`, which selects
an automatic limit based on the input size:

```rust
let fory = Fory::builder().max_graph_memory_bytes(-1).build();
```

For byte-slice and `Reader` roots, the automatic limit is:

```text
input bytes * 8 + 64 KiB
```

Set a positive byte value when trusted payloads need a larger or smaller limit:

```rust
let fory = Fory::builder()
    .max_graph_memory_bytes(256 * 1024 * 1024)
    .build();
```

### Explicit Xlang Examples

Set `.xlang(true)` explicitly for xlang serialization examples:

```rust
let fory = Fory::builder().xlang(true).build();
```

## Builder Pattern

```rust
use fory::Fory;

// Default xlang configuration
let fory = Fory::builder().build();

// Native mode for Rust-only traffic
let fory = Fory::builder().xlang(false).build();

// Same-schema optimization for Rust-only payloads
let fory = Fory::builder().xlang(false).compatible(false).build();

// Custom depth limit
let fory = Fory::builder().max_dyn_depth(10).build();

// Custom graph memory budget
let fory = Fory::builder()
    .max_graph_memory_bytes(256 * 1024 * 1024)
    .build();

// Combined configuration
let fory = Fory::builder()
    .xlang(false)
    .max_dyn_depth(10)
    .build();
```

## Configuration Summary

| Option                                        | Description                                       | Default |
| --------------------------------------------- | ------------------------------------------------- | ------- |
| `compatible(bool)`                            | Enable schema evolution                           | `true`  |
| `xlang(bool)`                                 | Use xlang mode                                    | `true`  |
| `max_dyn_depth(u32)`                          | Maximum nesting depth for dynamic types           | `5`     |
| `max_graph_memory_bytes(i64)`                 | Estimated graph memory per root read              | `-1`    |
| `max_type_fields(usize)`                      | Max fields in one received struct metadata body   | `512`   |
| `max_type_meta_bytes(usize)`                  | Max encoded bytes in one received metadata body   | `4096`  |
| `max_schema_versions_per_type(usize)`         | Max remote metadata versions for one logical type | `10`    |
| `max_average_schema_versions_per_type(usize)` | Average remote metadata versions across types     | `3`     |

## Compatible Mode

Compatible mode is enabled by default for both xlang and native mode. Keep this default when Rust
structs may evolve independently, when services deploy separately, or when xlang schemas are written
by hand in different languages.

Use `.compatible(false)` only when the schema used to deserialize every payload is always the same as the schema used to serialize it and you want faster serialization and smaller size. For xlang payloads, use `.compatible(false)` only after verifying that every language uses the same schema, or when native types are generated from Fory schema IDL.

## Security

Security-related configuration:

- Register application structs and trait-object implementations before deserializing untrusted
  payloads.
- Use `max_dyn_depth(...)` to reject unexpectedly deep dynamic object graphs.
- Keep `max_graph_memory_bytes(-1)` for the default input-shaped graph budget, or set a positive
  byte limit for trusted workloads with larger legitimate object graphs.
- Keep the remote schema metadata limits at their defaults unless the data is not malicious and a
  trusted peer sends larger metadata or many schema versions.
- Prefer concrete typed fields over `dyn Any` or broad trait-object fields for untrusted input.

## Related Topics

- [Basic Serialization](basic-serialization.md) - Using configured Fory
- [Schema Evolution](schema-evolution.md) - Compatible mode details
- [Xlang Serialization](xlang-serialization.md) - xlang mode
