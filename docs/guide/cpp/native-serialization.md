---
title: Native Serialization
sidebar_position: 3
id: native_serialization
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

C++ native serialization is the C++-only wire mode selected with `.xlang(false)`. Use it when every
writer and reader is C++ and the payload should follow C++ type behavior instead of the portable
xlang type system.

Use [Xlang Serialization](xlang-serialization.md), the default C++ mode, when
bytes must be read by Java, Python, Go, Rust, JavaScript/TypeScript, C#, Swift,
Dart, Scala, Kotlin, or another non-C++ Fory runtime.

## When To Use Native Serialization

Use native serialization when:

- A payload is produced and consumed only by C++ applications.
- The data model uses C++-specific types such as character types, unsigned-native type IDs,
  `std::tuple`, smart pointers, or C++ polymorphic models.
- You want schema-consistent C++ payloads for lockstep services.
- You need compatible schema evolution for C++-only rolling deployments.
- You want to avoid portable xlang type-mapping constraints for a C++ boundary.

## Create a Native Runtime

```cpp
#include "fory/serialization/fory.h"
#include <cassert>
#include <cstdint>
#include <string>

using namespace fory::serialization;

struct Order {
  int64_t id;
  double amount;

  bool operator==(const Order &other) const {
    return id == other.id && amount == other.amount;
  }
};
FORY_STRUCT(Order, id, amount);

int main() {
  auto fory = Fory::builder()
      .xlang(false)
      .build();
  fory.register_struct<Order>(100);

  Order order{1, 42.5};
  auto bytes = fory.serialize(order).value();
  auto decoded = fory.deserialize<Order>(bytes).value();
  assert(order == decoded);
}
```

Use one configured `Fory` instance per thread, or build a thread-safe runtime when the same runtime
is shared by multiple threads:

```cpp
auto fory = Fory::builder()
    .xlang(false)
    .track_ref(true)
    .build_thread_safe();
```

Register types before concurrent serialization starts.

## Schema Evolution

Native serialization defaults to schema-consistent mode. Enable compatible mode when C++-only
writer and reader schemas can differ:

```cpp
auto fory = Fory::builder()
    .xlang(false)
    .compatible(true)
    .build();
```

Compatible mode writes schema metadata so readers can tolerate added, removed, or reordered fields
when field identity remains compatible. See [Schema Evolution](schema-evolution.md).

## Registration

Register structs with stable IDs or names before serialization:

```cpp
fory.register_struct<Order>(100);
fory.register_struct<Order>("example", "Order");
```

Use numeric IDs for compact payloads. Use namespace/type-name registration when independent teams
coordinate type identity by names.

## C++ Object Surface

Native serialization owns the C++-specific object surface:

- Structs and classes described by `FORY_STRUCT`.
- Standard containers such as `std::vector`, `std::map`, `std::unordered_map`, `std::set`, and
  `std::unordered_set`.
- `std::optional`, `std::variant`, and tuple-like values.
- `std::shared_ptr` and `std::unique_ptr`.
- Character types such as `char`, `char16_t`, and `char32_t`.
- Unsigned integer types with native-mode type IDs.
- Polymorphic serialization registered through the C++ runtime.

Use [Supported Types](supported-types.md) for the full type surface and xlang mapping notes.

## References And Smart Pointers

Native serialization supports smart pointers and reference tracking:

```cpp
auto fory = Fory::builder()
    .xlang(false)
    .track_ref(true)
    .build();
```

When reference tracking is enabled, shared pointer identity can be preserved and cyclic object
graphs can be represented through supported pointer patterns. Disable reference tracking for
value-shaped data when identity is not part of the model.

## Native-Only Scalar Shapes

Some C++ scalar shapes are not portable xlang payloads. Use native serialization when these shapes
must round-trip as C++ values:

```cpp
auto fory = Fory::builder().xlang(false).build();

auto char_bytes = fory.serialize(char32_t{U'A'}).value();
auto value = fory.deserialize<char32_t>(char_bytes).value();

auto unsigned_bytes = fory.serialize(uint64_t{42}).value();
auto unsigned_value = fory.deserialize<uint64_t>(unsigned_bytes).value();
```

For xlang payloads, use schema metadata and the shared xlang type mapping instead of relying on
C++ native-only type IDs.

## Performance Guidelines

- Reuse configured `Fory` instances.
- Use single-threaded `Fory` per thread for the fastest path; use `build_thread_safe()` for shared
  concurrent use.
- Keep native schema-consistent mode for lockstep C++ services.
- Enable `.compatible(true)` only when C++-only schema evolution is required.
- Register structs with explicit numeric IDs for compact payloads.
- Disable reference tracking for value-shaped graphs.
- Prefer concrete types over polymorphic/dynamic fields on hot paths.

## Native And Xlang Comparison

| Requirement                              | Use native serialization | Use xlang serialization |
| ---------------------------------------- | ------------------------ | ----------------------- |
| C++-only payloads                        | Yes                      | Optional                |
| Non-C++ readers or writers               | No                       | Yes                     |
| C++ native character and unsigned shapes | Yes                      | Limited                 |
| Smart pointers and C++ object graphs     | Yes                      | Limited                 |
| Schema-consistent same-language payloads | Yes                      | No                      |
| Compatible schema evolution by default   | No                       | Yes                     |
| Portable type mapping across runtimes    | No                       | Yes                     |

## Troubleshooting

### A non-C++ runtime cannot read the payload

The writer is using native serialization. Rebuild it with `.xlang(true)` and align type
registration with every peer runtime.

### A rolling deployment fails after a field change

Native serialization defaults to schema-consistent mode. Use `.compatible(true)` on both writer and
reader when schemas can differ.

### A native-only scalar does not map to another language

Use xlang serialization with explicit schema metadata for portable payloads. Native C++ type IDs
are only for C++ readers.

### A shared pointer graph loses identity

Enable `.track_ref(true)` and verify the graph uses supported pointer patterns.

## Related Topics

- [Xlang Serialization](xlang-serialization.md) - Cross-runtime C++ payloads
- [Configuration](configuration.md) - Builder options
- [Basic Serialization](basic-serialization.md) - Object graph serialization
- [Supported Types](supported-types.md) - C++ type support
- [Polymorphic Serialization](polymorphism.md) - Polymorphic object models
- [Schema Evolution](schema-evolution.md) - Compatible mode
