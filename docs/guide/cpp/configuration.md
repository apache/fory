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

```cpp
auto fory = Fory::builder().build(); // SchemaConsistent by default
```

### Compatible Mode

Allows independent schema evolution:

```cpp
auto fory = Fory::builder().compatible(true).build();
```

## Builder Pattern

Use `ForyBuilder` to construct Fory instances with custom configuration:

```cpp
#include "fory/serialization/fory.h"

using namespace fory::serialization;

// Default configuration
auto fory = Fory::builder().build();

// Compatible mode for schema evolution
auto fory = Fory::builder()
    .compatible(true)
    .build();

// Cross-language mode
auto fory = Fory::builder()
    .xlang(true)
    .build();

// Full configuration
auto fory = Fory::builder()
    .compatible(true)
    .xlang(true)
    .track_ref(true)
    .max_dyn_depth(10)
    .check_struct_version(true)
    .build();
```

## Configuration

### xlang(bool)

Enable/disable cross-language (xlang) serialization mode.

```cpp
auto fory = Fory::builder()
    .xlang(true)  // Enable cross-language compatibility
    .build();
```

When enabled, includes metadata for cross-language compatibility with Java, Python, Go, Rust, and JavaScript.

**Default:** `true`

### compatible(bool)

Enable/disable compatible mode for schema evolution.

```cpp
auto fory = Fory::builder()
    .compatible(true)  // Enable schema evolution
    .build();
```

When enabled, supports reading data serialized with different schema versions.

**Default:** `false`

### track_ref(bool)

Enable/disable reference tracking for shared and circular references.

```cpp
auto fory = Fory::builder()
    .track_ref(true)  // Enable reference tracking
    .build();
```

When enabled, avoids duplicating shared objects and handles cycles.

**Default:** `true`

### max_dyn_depth(uint32_t)

Set maximum allowed nesting depth for dynamically-typed objects.

```cpp
auto fory = Fory::builder()
    .max_dyn_depth(10)  // Allow up to 10 levels
    .build();
```

This limits the maximum depth for nested polymorphic object serialization (e.g., `shared_ptr<Base>`, `unique_ptr<Base>`). This prevents stack overflow from deeply nested structures in dynamic serialization scenarios.

**Default:** `5`

**When to adjust:**

- **Increase**: For legitimate deeply nested data structures
- **Decrease**: For stricter security requirements or shallow data structures

### check_struct_version(bool)

Enable/disable struct version checking.

```cpp
auto fory = Fory::builder()
    .check_struct_version(true)  // Enable version checking
    .build();
```

When enabled, validates type hashes to detect schema mismatches.

**Default:** `false`

## Thread-Safe vs Single-Threaded

### Single-Threaded (Fastest)

```cpp
auto fory = Fory::builder()
    .xlang(true)
    .build();  // Returns Fory
```

Single-threaded `Fory` is the fastest option, but NOT thread-safe. Use one instance per thread.

### Thread-Safe

```cpp
auto fory = Fory::builder()
    .xlang(true)
    .build_thread_safe();  // Returns ThreadSafeFory
```

`ThreadSafeFory` uses a pool of Fory instances to provide thread-safe serialization. Slightly slower due to pool overhead, but safe to use from multiple threads concurrently.

## Configuration Summary

| Option                       | Description                             | Default |
| ---------------------------- | --------------------------------------- | ------- |
| `xlang(bool)`                | Enable cross-language mode              | `true`  |
| `compatible(bool)`           | Enable schema evolution                 | `false` |
| `track_ref(bool)`            | Enable reference tracking               | `true`  |
| `max_dyn_depth(uint32_t)`    | Maximum nesting depth for dynamic types | `5`     |
| `check_struct_version(bool)` | Enable struct version checking          | `false` |

## Related Topics

- [Basic Serialization](basic-serialization.md) - Using configured Fory
- [Cross-Language](cross-language.md) - XLANG mode details
- [Type Registration](type-registration.md) - Registering types
