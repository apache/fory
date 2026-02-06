---
title: FlatBuffers IDL Support
sidebar_position: 7
id: flatbuffers_idl
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

This page explains how Apache Fory consumes FlatBuffers schemas (`.fbs`) and
translates them into Fory IR for code generation.

## What This Page Covers

- When to use FlatBuffers input with Fory
- Exact FlatBuffers to Fory mapping behavior
- Supported Fory-specific attributes in `.fbs`
- Migration notes and generated-code differences

## Why Use Apache Fory

- Idiomatic generated code: Fory generates language-idiomatic classes/structs
  that can be used directly as domain objects.
- Java performance: In Java object-serialization workloads, Fory is faster than
  FlatBuffers in Fory benchmarks.
- Other languages: serialization performance is generally in a similar range.
- Deserialization in practice: FlatBuffers does not perform native-object deserialization and is
  faster by default, but if your application needs native objects, it requires
  conversion and that conversion step can dominate read cost. In those cases,
  Fory deserialization is often faster end-to-end.
- Easier APIs: Fory uses direct native objects, so you do not need to
  reverse-build tables or manually manage offsets.
- Better graph modeling: Shared and circular references are first-class features
  in Fory.

## Quick Decision Guide

| Situation                                                          | Recommended Path       |
| ------------------------------------------------------------------ | ---------------------- |
| You already have `.fbs` schemas and want Fory runtime/codegen      | Use FlatBuffers input  |
| You are starting new schema work and want full Fory syntax control | Use native Fory IDL    |
| You need FlatBuffers wire compatibility at runtime                 | Keep FlatBuffers stack |
| You need Fory object-graph semantics (`ref`, weak refs, etc.)      | Use Fory               |

## FlatBuffers to Fory Mapping

### Schema-Level Rules

- `namespace` maps to Fory package namespace.
- `include` entries map to Fory imports.
- `table` is translated as `evolving=true`.
- `struct` is translated as `evolving=false`.
- `root_type` is parsed but ignored by Fory runtime/codegen.
- `file_identifier` and `file_extension` are parsed but not used by Fory codegen.

### Field Numbering

FlatBuffers fields do not have explicit field IDs. Fory assigns field numbers by
source declaration order, starting at `1`.

### Scalar Type Mapping

| FlatBuffers | Fory Type |
| ----------- | --------- |
| `byte`      | `int8`    |
| `ubyte`     | `uint8`   |
| `short`     | `int16`   |
| `ushort`    | `uint16`  |
| `int`       | `int32`   |
| `uint`      | `uint32`  |
| `long`      | `int64`   |
| `ulong`     | `uint64`  |
| `float`     | `float32` |
| `double`    | `float64` |
| `bool`      | `bool`    |
| `string`    | `string`  |

Vectors (`[T]`) map to Fory lists.

### Unions

FlatBuffers unions map to Fory unions.

- Case IDs are assigned by declaration order, starting at `1`.
- Case names are derived from type names using snake_case field naming.

**FlatBuffers**

```fbs
union Payload {
  Note,
  Metric
}

table Container {
  payload: Payload;
}
```

**Fory shape after translation**

```protobuf
union Payload {
    Note note = 1;
    Metric metric = 2;
}

message Container {
    Payload payload = 1;
}
```

### Defaults and Metadata

- FlatBuffers default values are parsed but not applied as Fory runtime defaults.
- Non-Fory metadata attributes are preserved as generic options in IR and may be
  consumed by downstream tooling.

## Fory-Specific Attributes in FlatBuffers

FlatBuffers metadata attributes use `key:value`. For Fory-specific options, use
`fory_` (or `fory.`) prefix in `.fbs`; the prefix is removed during parsing.

### Supported Field Attributes

| FlatBuffers Attribute            | Effect in Fory                                        |
| -------------------------------- | ----------------------------------------------------- |
| `fory_ref:true`                  | Enable reference tracking for the field               |
| `fory_nullable:true`             | Mark field optional/nullable                          |
| `fory_weak_ref:true`             | Enable weak reference semantics and implies `ref`     |
| `fory_thread_safe_pointer:false` | For ref fields, select non-thread-safe pointer flavor |

Semantics:

- `fory_weak_ref:true` implies `ref`.
- `fory_thread_safe_pointer` only takes effect when the field is ref-tracked.
- For list fields, `fory_ref:true` applies to list elements.

Example:

```fbs
table Node {
  parent: Node (fory_weak_ref: true);
  children: [Node] (fory_ref: true);
  cached: Node (fory_ref: true, fory_thread_safe_pointer: false);
}
```

## Generated Code Differences

Using `.fbs` as input to Fory still produces normal Fory-generated code, not
FlatBuffers `ByteBuffer`-style APIs.

- Java: POJOs/records with Fory metadata
- Python: dataclasses plus registration helpers
- Go/Rust/C++: native structs and Fory metadata

The serialization format is Fory binary protocol, not FlatBuffers wire format.

## Usage

Compile a FlatBuffers schema directly:

```bash
foryc schema.fbs --lang java,python --output ./generated
```

Inspect translated schema syntax for debugging:

```bash
foryc schema.fbs --emit-fdl --emit-fdl-path ./translated
```

## Migration Notes

1. Keep existing `namespace` values stable to keep type registration stable.
2. Review fields that relied on FlatBuffers default literals and set explicit
   defaults in application code if needed.
3. Add `fory_ref`/`fory_weak_ref` where object-graph semantics are required.
4. Validate generated model behavior with roundtrip tests before replacing
   existing serialization paths.

## Summary

FlatBuffers input lets you reuse existing `.fbs` schemas while moving to Fory's
runtime and code generation model. This is useful for incremental migration,
while preserving schema investment and adopting Fory-native object APIs.
