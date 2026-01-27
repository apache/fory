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

The Fory compiler can ingest FlatBuffers schemas (`.fbs`) and translate them into
Fory IR before code generation. This provides a smooth migration path when you
already have FlatBuffers schemas but want Fory-native serialization and codegen.

## Key Differences vs FDL

- **Field numbering**: FlatBuffers fields have no explicit IDs; Fory assigns
  sequential field numbers based on declaration order, starting at 1.
- **Tables vs structs**: FlatBuffers `table` maps to a Fory message with
  `evolving=true`; `struct` maps to `evolving=false`.
- **Default values**: Parsed for compatibility but ignored in generated Fory
  code. Use Fory options or language defaults instead.
- **Attributes**: Metadata in `(...)` is mapped to Fory options on types and
  fields; FDL uses `[option=value]` inline syntax.
- **Root type**: `root_type` is ignored because Fory does not require a root
  message to serialize.
- **Unions**: FlatBuffers `union` is translated into an FDL `union`. Case IDs
  follow declaration order, starting at 1.

## Scalar Type Mapping

| FlatBuffers | Fory Primitive |
| ----------- | -------------- |
| `byte`      | `int8`         |
| `ubyte`     | `uint8`        |
| `short`     | `int16`        |
| `ushort`    | `uint16`       |
| `int`       | `varint32`     |
| `uint`      | `var_uint32`   |
| `long`      | `varint64`     |
| `ulong`     | `var_uint64`   |
| `float`     | `float32`      |
| `double`    | `float64`      |
| `bool`      | `bool`         |
| `string`    | `string`       |

Vectors (`[T]`) map to Fory list types.

## Union Mapping

FlatBuffers unions are converted to FDL unions and then to native union APIs in
each target language.

**FlatBuffers:**

```fbs
union Payload {
  Note,
  Metric
}

table Container {
  payload: Payload;
}
```

**FDL (conceptual):**

```protobuf
union Payload {
    Note note = 1;
    Metric metric = 2;
}

message Container {
    Payload payload = 1;
}
```

Case IDs are derived from the declaration order in the `union`. The generated
case names are based on the type names (converted to each language's naming
convention).

## Usage

Compile a FlatBuffers schema directly:

```bash
fory compile schema.fbs --lang java,python --output ./generated
```

To inspect the translated FDL for debugging:

```bash
fory compile schema.fbs --emit-fdl --emit-fdl-path ./translated
```

## Generated Code Differences

FlatBuffers-generated APIs are centered around `ByteBuffer` accessors and
builders. Fory code generation instead produces native language structures and
registration helpers, the same as when compiling FDL:

- **Java**: Plain POJOs with Fory annotations.
- **Python**: Dataclasses with registration helpers.
- **Go/Rust/C++**: Native structs with Fory metadata.

Because Fory generates native types, the resulting APIs are different from
FlatBuffers builder/accessor APIs, and the serialization format is Fory's binary
protocol rather than the FlatBuffers wire format.
