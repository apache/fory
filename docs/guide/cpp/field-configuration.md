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

Field configuration is embedded directly in `FORY_STRUCT`. A field entry may be
bare, or it may be a tuple containing the member name and a `fory::F(...)`
builder:

```cpp
#include "fory/serialization/fory.h"

struct DataV2 {
  uint32_t id;
  uint64_t timestamp;
  std::optional<uint32_t> version;
};

FORY_STRUCT(DataV2, id, (timestamp, fory::F().tagged()), version);
```

The configuration is compile-time metadata. It does not allocate codec objects
or add virtual dispatch on the serialization path.

## Field Identity

`fory::F()` uses name-mode field identity. Bare fields are also name-mode:

```cpp
FORY_STRUCT(DataV2, id, (timestamp, fory::F().tagged()), version);
```

`fory::F(id)` uses id-mode field identity:

```cpp
FORY_STRUCT(DataV2, (id, fory::F(0)), (timestamp, fory::F(1).tagged()),
            (version, fory::F(2)));
```

A struct must use exactly one identity mode. If any field uses `fory::F(id)`,
every field in that `FORY_STRUCT` must use `fory::F(id)`. Mixed name/id mode is a
compile-time error.

## Scalar Encoding

Integer encoding is configured on the field or on a nested value-node spec:

```cpp
struct Counters {
  uint32_t fixed_id;
  uint64_t tagged_time;
  int64_t signed_score;
};

FORY_STRUCT(Counters, (fixed_id, fory::F().fixed()),
            (tagged_time, fory::F().tagged()),
            (signed_score, fory::F().varint()));
```

Supported scalar encoding methods are:

| Method     | Meaning                                      |
| ---------- | -------------------------------------------- |
| `fixed()`  | Fixed-width integer encoding where valid     |
| `varint()` | Variable-length integer encoding where valid |
| `tagged()` | Tagged integer encoding where valid          |

Invalid scalar/type combinations fail at compile time.

## Nested Specs

Use the `fory::T` namespace for value-node specs inside containers and wrapper
carriers. Untyped specs infer the actual C++ type at that node:

```cpp
namespace T = fory::T;

struct Foo {
  std::vector<uint32_t> values;
  std::map<uint32_t, std::vector<int64_t>> nested;
};

FORY_STRUCT(Foo,
            (values, fory::F().list(T::fixed())),
            (nested, fory::F().map(T::varint(),
                                   T::list(T::tagged()))));
```

Typed specs are optional validators and make the intended node type explicit:

```cpp
FORY_STRUCT(Foo, (nested, fory::F().map(T::uint32().varint(),
                                        T::list(T::int64().tagged()))));
```

Supported recursive composition methods are:

| Method              | Applies to                            |
| ------------------- | ------------------------------------- |
| `list(elem)`        | `std::vector<T>` and list-like fields |
| `set(elem)`         | `std::set<T>` and set-like fields     |
| `map(key, value)`   | `std::map<K, V>` and map-like fields  |
| `map().key(spec)`   | Override only the map key             |
| `map().value(spec)` | Override only the map value           |
| `inner(child)`      | Transparent single-child carriers     |

Partial map overrides are useful when only one side needs a non-default
encoding:

```cpp
FORY_STRUCT(Foo,
            (nested, fory::F().map().key(T::varint())),
            (other, fory::F().map().value(T::list(T::tagged()))));
```

## Carrier Inner Specs

Use `.inner(...)` for wrapper-like carriers. The carrier kind still comes from
the actual C++ type, and controls nullable/reference behavior:

```cpp
struct WrapperFields {
  std::optional<std::vector<uint32_t>> maybe_values;
  std::shared_ptr<std::vector<int64_t>> shared_values;
};

FORY_STRUCT(WrapperFields,
            (maybe_values, fory::F().inner(T::list(T::varint()))),
            (shared_values,
             fory::F().nullable().ref().inner(T::list(T::tagged()))));
```

`.inner(...)` is the only public combinator for `std::optional<T>`,
`std::shared_ptr<T>`, `std::unique_ptr<T>`, and
`fory::serialization::SharedWeak<T>`.

## Nullability, Reference Tracking, And Dynamic Fields

`std::optional<T>` is nullable by default. Smart pointers may be marked nullable
or reference-tracked in the field spec:

```cpp
struct Node {
  std::string name;
  std::shared_ptr<Node> next;
};

FORY_STRUCT(Node, name, (next, fory::F().nullable().ref()));
```

For polymorphic pointer fields, use `.dynamic(true)` to always write runtime
type information, `.dynamic(false)` to use the declared type directly, or omit
it to let Fory infer the behavior from the C++ type:

```cpp
struct Zoo {
  std::shared_ptr<Animal> star;
  std::shared_ptr<Animal> mascot;
};

FORY_STRUCT(Zoo, (star, fory::F().nullable().dynamic(true)),
            (mascot, fory::F().nullable().dynamic(false)));
```

## Unions

`FORY_UNION` cases must use explicit ids. Name-mode `fory::F()` is invalid for
union metadata:

```cpp
struct Choice {
  std::variant<std::string, uint32_t> value;

  static Choice text(std::string value);
  static Choice code(uint32_t value);
};

FORY_UNION(Choice, (text, std::string, fory::F(1)),
           (code, uint32_t, fory::F(2).fixed()));
```

Generated C++ may omit the explicit case type when it can infer the payload type
from a non-overloaded one-argument factory:

```cpp
FORY_UNION(GeneratedChoice, (text, fory::F(1)),
           (code, fory::F(2).fixed()));
```

The three-element form is the stable public form for handwritten code.
