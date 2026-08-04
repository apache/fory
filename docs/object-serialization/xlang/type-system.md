---
title: Type System
sidebar_position: 1
id: type-system
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

## Serialize Built-in Types

Common types can be serialized automatically without registration: primitive numeric types, string, binary, array, list, map, and more.

Reduced-precision floating-point values are also part of the built-in xlang type system:

- `float16` and `array<float16>`
- `bfloat16` and `array<bfloat16>`

Use the language-specific carrier types documented in the type mapping reference. Python uses `pyfory.Float16` and `pyfory.BFloat16` as annotation markers only; scalar values are native Python `float`, and dense reduced-precision arrays use `pyfory.Float16Array` and `pyfory.BFloat16Array`. Go uses the `float16` and `bfloat16` packages for scalar, slice, and array carriers; JavaScript uses `number` for scalar `float16` and `bfloat16`, and dense array carriers `BoolArray`, `Float16Array`, and `BFloat16Array` for the corresponding `array<T>` schemas. Dart uses `double` plus `Float16Type` or `Bfloat16Type` metadata for scalar fields, and `Float16List` / `Bfloat16List` for dense arrays. Java uses `@ArrayType` on supported reduced-precision carriers for `array<float16>` / `array<bfloat16>` schema, while general object arrays stay on the `list` path; C++, Rust, and C# provide their own dedicated scalar and array carriers.

When `compatible=true`, a direct struct/class field can evolve between `list<T>` and `array<T>` for dense bool/numeric `T`. Integer list element encodings in the same signedness and width domain match the corresponding dense array element domain. This applies only to the immediate matched field schema. It does not apply to nested collection, map, array, union, or generic positions. A peer `list<T?>` schema can be read into a local `array<T>` field when the actual payload has no null elements. If the payload carries a null element or ref-tracked element encoding, reading it into a local `array<T>` field raises a compatible-read error.

## Serialize Custom Types

User-defined types must be registered using the register API to establish the mapping relationship between types in different languages. Use consistent type names across all languages.

## Exact mappings

The normative [xlang type mapping](../../specification/xlang_type_mapping.md) defines the exact
carrier mapping for every runtime. Runtime pages show the API syntax and examples for that mapping.
