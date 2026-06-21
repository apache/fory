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

This page covers C++ Fory instance configuration. `Fory::builder()` creates xlang
payloads by default. Compatible mode is also enabled by default. Select native
mode only when the payload stays in C++.

## Builder Pattern

Use `Fory::builder()` to construct Fory instances with custom configuration:

```cpp
#include "fory/serialization/fory.h"

using namespace fory::serialization;

// Default xlang mode.
auto fory = Fory::builder().build();

// Native mode for C++-only payloads.
auto fory = Fory::builder()
    .xlang(false)
    .build();

// Same-schema optimization. Use only when every reader and writer
// always uses the same schema.
auto fory = Fory::builder()
    .compatible(false)
    .build();
```

## Configuration

### xlang(bool)

Select the wire mode.

```cpp
auto fory = Fory::builder()
    .xlang(false)
    .build();
```

When `true`, C++ writes the xlang wire format used by Java, Python, Go, Rust,
JavaScript/TypeScript, C#, Swift, Dart, Scala, and Kotlin. When `false`, C++
writes native-mode payloads for C++-only traffic.

**Default:** `true`

### compatible(bool)

Compatible mode is enabled by default. No builder call is required for the
default compatible mode. Set `.compatible(false)` only when every reader and
writer always uses the same schema and you want faster serialization and smaller
size.

```cpp
auto fory = Fory::builder()
    .compatible(false)
    .build();
```

For xlang payloads, use `.compatible(false)` only after verifying that every language uses the same schema, or when native types are generated from Fory schema IDL.

**Default:** `true`

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

### max_schema_versions_per_type(uint32_t)

Set the maximum accepted remote metadata versions for one logical type.

```cpp
auto fory = Fory::builder()
    .max_schema_versions_per_type(10)
    .build();
```

**Default:** `10`

### max_type_fields(uint32_t)

Set the maximum fields accepted in one received remote struct metadata body.

```cpp
auto fory = Fory::builder()
    .max_type_fields(512)
    .build();
```

**Default:** `512`

### max_type_meta_bytes(uint32_t)

Set the maximum encoded body bytes accepted for one received TypeDef body,
excluding the 8-byte header and any extended-size varint.

```cpp
auto fory = Fory::builder()
    .max_type_meta_bytes(4096)
    .build();
```

**Default:** `4096`

### max_average_schema_versions_per_type(uint32_t)

Set the average accepted remote metadata versions across accepted remote types.
The effective global floor is `8192` schemas.

```cpp
auto fory = Fory::builder()
    .max_average_schema_versions_per_type(3)
    .build();
```

**Default:** `3`

### check_struct_version(bool)

Enable/disable struct version checking.

```cpp
auto fory = Fory::builder()
    .compatible(false)
    .check_struct_version(true)  // Enable version checking
    .build();
```

When enabled, validates type hashes to detect schema mismatches.

**Default:** `false`

## Thread-Safe vs Single-Threaded

### Single-Threaded (Fastest)

```cpp
auto fory = Fory::builder().build();  // Returns Fory
```

Single-threaded `Fory` is the fastest option, but NOT thread-safe. Use one instance per thread.

### Thread-Safe

```cpp
auto fory = Fory::builder().build_thread_safe();  // Returns ThreadSafeFory
```

`ThreadSafeFory` uses a pool of Fory instances to provide thread-safe serialization. Slightly slower due to pool overhead, but safe to use from multiple threads concurrently.

## Configuration Summary

| Option                                           | Description                                       | Default |
| ------------------------------------------------ | ------------------------------------------------- | ------- |
| `xlang(bool)`                                    | Use xlang mode                                    | `true`  |
| `compatible(bool)`                               | Enable schema evolution                           | `true`  |
| `track_ref(bool)`                                | Enable reference tracking                         | `true`  |
| `max_dyn_depth(uint32_t)`                        | Maximum nesting depth for dynamic types           | `5`     |
| `max_type_fields(uint32_t)`                      | Max fields in one received struct metadata body   | `512`   |
| `max_type_meta_bytes(uint32_t)`                  | Max encoded bytes in one received metadata body   | `4096`  |
| `max_schema_versions_per_type(uint32_t)`         | Max remote metadata versions for one logical type | `10`    |
| `max_average_schema_versions_per_type(uint32_t)` | Average remote metadata versions across types     | `3`     |
| `check_struct_version(bool)`                     | Enable struct version checking                    | `false` |

## Security

Security-related configuration:

- Register all structs and polymorphic implementations before deserializing untrusted payloads.
- Use `check_struct_version(true)` with `compatible(false)` for intentional same-schema payloads.
- Keep `max_dyn_depth(...)` as low as your model permits to reject unexpectedly deep polymorphic
  graphs.
- Keep the remote schema metadata limits at their defaults unless the data is not malicious and a
  trusted peer sends larger metadata or many schema versions.
- Prefer concrete fields over broad polymorphic fields for untrusted input.

## Related Topics

- [Basic Serialization](basic-serialization.md) - Using configured Fory
- [Xlang Serialization](xlang-serialization.md) - xlang mode details
- [Type Registration](type-registration.md) - Registering types
