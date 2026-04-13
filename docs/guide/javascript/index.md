---
title: JavaScript Serialization Guide
sidebar_position: 0
id: index
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

Apache Fory JavaScript provides cross-language serialization for JavaScript and TypeScript applications. It uses the xlang wire format described in the [xlang serialization specification](../../specification/xlang_serialization_spec.md), so it is designed to interoperate with other Fory runtimes such as Java, Python, Go, Rust, Swift, and C++.

The JavaScript runtime is schema-driven: you describe your data shape with `Type.*` builders or TypeScript decorators, register that schema with `Fory`, and then reuse the returned serializer pair for serialization and deserialization.

## Why Fory JavaScript?

- **Cross-language by design**: JavaScript uses the xlang wire format and interoperates with other Fory runtimes.
- **Generated hot paths**: serializers are generated at runtime and cached per `Fory` instance.
- **Reference-aware object graphs**: shared references and circular structures are supported when enabled.
- **Explicit schemas**: field types, nullability, dynamic dispatch, and type identity are declared up front.
- **Guardrails for untrusted data**: configurable depth, binary size, and collection size limits help bound deserialization work.
- **Modern numeric support**: `bigint`, typed arrays, `Map`, `Set`, `Date`, `float16`, and `bfloat16` are supported.

## Installation

Install the JavaScript packages from npm:

```bash
npm install @apache-fory/core
```

Optional Node.js string fast-path support is available through `@apache-fory/hps`:

```bash
npm install @apache-fory/core @apache-fory/hps
```

`@apache-fory/hps` depends on Node.js 20+ and is optional. If it is unavailable, Fory still works correctly; omit `hps` from the configuration.

## Quick Start

```ts
import Fory, { Type } from "@apache-fory/core";

const userType = Type.struct(
  { typeName: "example.user" },
  {
    id: Type.int64(),
    name: Type.string(),
    age: Type.int32(),
  },
);

const fory = new Fory();
const { serialize, deserialize } = fory.register(userType);

const bytes = serialize({
  id: 1n,
  name: "Alice",
  age: 30,
});

const user = deserialize(bytes);
console.log(user);
// { id: 1n, name: 'Alice', age: 30 }
```

## Core Model

### `Fory`

A `Fory` instance owns:

- the type registry
- generated serializers
- read/write contexts
- configuration such as reference tracking and guardrails

Reuse the same `Fory` instance across many operations. Registration is per instance.

### `Type`

`Type` is the schema DSL. It is used to describe:

- primitive fields such as `Type.int32()` and `Type.string()`
- collections such as `Type.array(...)`, `Type.map(...)`, and `Type.set(...)`
- user types such as `Type.struct(...)` and `Type.enum(...)`
- field-level metadata such as nullability, reference tracking, and dynamic dispatch

### Registration

`fory.register(...)` compiles and registers a serializer. It returns:

- `serializer`: the generated serializer object
- `serialize(value)`: serialize using that schema
- `deserialize(bytes)`: deserialize using that schema

```ts
const personType = Type.struct("example.person", {
  name: Type.string(),
  email: Type.string().setNullable(true),
});

const fory = new Fory();
const personSerde = fory.register(personType);
```

## Configuration

The JavaScript runtime always uses xlang serialization. The most important options are:

```ts
import Fory from "@apache-fory/core";
import hps from "@apache-fory/hps";

const fory = new Fory({
  ref: true,
  compatible: true,
  maxDepth: 100,
  maxBinarySize: 64 * 1024 * 1024,
  maxCollectionSize: 1_000_000,
  useSliceString: false,
  hps,
});
```

| Option                     | Default            | Meaning                                                                                                                           |
| -------------------------- | ------------------ | --------------------------------------------------------------------------------------------------------------------------------- |
| `ref`                      | `false`            | Enables reference tracking for graphs with shared or circular references.                                                         |
| `compatible`               | `false`            | Uses compatible struct encoding for schema evolution.                                                                             |
| `maxDepth`                 | `50`               | Maximum nesting depth accepted during deserialization. Must be `>= 2`.                                                            |
| `maxBinarySize`            | `64 * 1024 * 1024` | Maximum allowed binary payload for guarded binary reads.                                                                          |
| `maxCollectionSize`        | `1_000_000`        | Maximum number of elements accepted for lists, sets, and maps.                                                                    |
| `useSliceString`           | `false`            | Optional string-reading mode for Node.js environments. Leave it at the default unless you have benchmarked a reason to change it. |
| `hps`                      | unset              | Optional Node.js string fast-path helper from `@apache-fory/hps`.                                                                 |
| `hooks.afterCodeGenerated` | unset              | Callback to inspect or transform generated serializer code. Useful for debugging.                                                 |

## Documentation

| Topic                                         | Description                                             |
| --------------------------------------------- | ------------------------------------------------------- |
| [Basic Serialization](basic-serialization.md) | Core APIs and everyday usage                            |
| [Type Registration](type-registration.md)     | Numeric IDs, names, decorators, and schema registration |
| [Supported Types](supported-types.md)         | Primitive, collection, time, enum, and struct mappings  |
| [References](references.md)                   | Shared references and circular object graphs            |
| [Schema Evolution](schema-evolution.md)       | Compatible mode and evolving structs                    |
| [Cross-Language](cross-language.md)           | Interop guidance and mapping rules                      |
| [Troubleshooting](troubleshooting.md)         | Common issues, limits, and debugging tips               |

## Related Resources

- [Xlang Serialization Specification](../../specification/xlang_serialization_spec.md)
- [Cross-Language Type Mapping](../../specification/xlang_type_mapping.md)
