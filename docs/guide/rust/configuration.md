---
title: Configuration
sidebar_position: 2
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

This page covers Fory configuration options and serialization modes.

## Serialization Modes

Apache Fory™ supports two serialization modes:

### SchemaConsistent Mode (Default)

Type declarations must match exactly between peers:

```rust
let fory = Fory::default(); // SchemaConsistent by default
```

### Compatible Mode

Allows independent schema evolution:

```rust
let fory = Fory::builder().compatible(true).build();
```

## Configuration

### Maximum Dynamic Object Nesting Depth

Apache Fory™ provides protection against stack overflow from deeply nested dynamic objects during deserialization. By default, the maximum nesting depth is set to 5 levels for trait objects and containers.

**Default configuration:**

```rust
let fory = Fory::default(); // max_dyn_depth = 5
```

**Custom depth limit:**

```rust
let fory = Fory::builder().max_dyn_depth(10).build(); // Allow up to 10 levels
```

**When to adjust:**

- **Increase**: For legitimate deeply nested data structures
- **Decrease**: For stricter security requirements or shallow data structures

**Protected types:**

- `Box<dyn Any>`, `Rc<dyn Any>`, `Arc<dyn Any>`
- `Box<dyn Trait>`, `Rc<dyn Trait>`, `Arc<dyn Trait>` (trait objects)
- `RcWeak<T>`, `ArcWeak<T>`
- Collection types (Vec, HashMap, HashSet)
- Nested struct types in Compatible mode

Note: Static data types (non-dynamic types) are secure by nature and not subject to depth limits, as their structure is known at compile time.

### Cross-Language Mode

Enable cross-language serialization:

```rust
let fory = Fory::builder()
    .compatible(true)
    .xlang(true).build();
```

## Builder Pattern

```rust
use fory::Fory;

// Default configuration
let fory = Fory::default();

// Compatible mode for schema evolution
let fory = Fory::builder().compatible(true).build();

// Cross-language mode
let fory = Fory::builder()
    .compatible(true)
    .xlang(true).build();

// Custom depth limit
let fory = Fory::builder().max_dyn_depth(10).build();

// Combined configuration
let fory = Fory::builder()
    .compatible(true)
    .xlang(true)
    .max_dyn_depth(10).build();
```

## Configuration Summary

| Option               | Description                             | Default |
| -------------------- | --------------------------------------- | ------- |
| `compatible(bool)`   | Enable schema evolution                 | `false` |
| `xlang(bool)`        | Enable cross-language mode              | `false` |
| `max_dyn_depth(u32)` | Maximum nesting depth for dynamic types | `5`     |

## Related Topics

- [Basic Serialization](basic-serialization.md) - Using configured Fory
- [Schema Evolution](schema-evolution.md) - Compatible mode details
- [Cross-Language](cross-language.md) - XLANG mode
