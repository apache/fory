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

Apache Fory JavaScript lets you serialize JavaScript and TypeScript objects to
bytes and deserialize them back, including across services written in Java,
Python, C++, Go, Rust, C#, Swift, Dart, Scala, Kotlin, and other
Fory-supported languages.

## Why Fory JavaScript?

- **Xlang**: serialize in JavaScript/TypeScript, deserialize in any supported
  Fory library without writing glue code
- **Fast**: serializer code is generated and cached the first time you register a schema, not on every call
- **Reference-aware**: shared references and circular object graphs are supported when enabled
- **Explicit schemas**: field types, nullability, and polymorphism are declared once with `Type.*` builders or TypeScript decorators
- **Safe defaults**: configurable depth checks reject unexpectedly deep payloads
- **Modern types**: `bigint`, typed arrays, `Map`, `Set`, `Date`, `float16`, and `bfloat16` are supported

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

## Generated Code from Fory IDL

You can also define schemas in Fory IDL and generate TypeScript model files:

```protobuf
package example;

message Person {
  string name = 1;
  int32 age = 2;
}
```

Generate JavaScript/TypeScript code:

```bash
foryc person.fdl --javascript_out=./generated
```

The generated model file exports TypeScript interfaces, enums, unions, a
registration helper, and root serialization helpers:

```ts
import {
  Person,
  deserializePerson,
  registerPersonTypes,
  serializePerson,
} from "./generated/person";

const bytes = serializePerson({ name: "Alice", age: 30 });
const person: Person = deserializePerson(bytes);
```

When you manage your own `Fory` instance, register the generated schema module
before serializing values with that instance:

```ts
import Fory from "@apache-fory/core";
import { registerPersonTypes } from "./generated/person";

const fory = new Fory();
const { person } = registerPersonTypes(fory);

const bytes = person.serialize({ name: "Alice", age: 30 });
const copy = person.deserialize(bytes);
```

## How it works

Fory is schema-driven. You describe the shape of your data once with `Type.*` builders (or TypeScript decorators), then call `fory.register(schema)`. This returns a `{ serialize, deserialize }` pair that is fast to call repeatedly.

```ts
// 1. Define the schema
const personType = Type.struct("example.person", {
  name: Type.string(),
  email: Type.string().setNullable(true),
});

// 2. Register once
const fory = new Fory();
const { serialize, deserialize } = fory.register(personType);

// 3. Use as many times as needed
const bytes = serialize({ name: "Alice", email: null });
const person = deserialize(bytes);
```

Create one `Fory` instance per application and reuse it — creating a new one for every request wastes the work of schema registration.

## Configuration

Fory JavaScript is xlang-only. `new Fory()` uses compatible schema evolution by default. Configure
reference tracking, maximum read depth, and optional Node.js string acceleration through constructor
options; see [Configuration](configuration.md).

## Documentation

| Topic                                         | Description                                             |
| --------------------------------------------- | ------------------------------------------------------- |
| [Basic Serialization](basic-serialization.md) | Core APIs and everyday usage                            |
| [Configuration](configuration.md)             | Fory options, compatible mode, limits, and HPS          |
| [Type Registration](type-registration.md)     | Numeric IDs, names, decorators, and schema registration |
| [Schema Metadata](schema-metadata.md)         | Type builders, field options, and decorators            |
| [Supported Types](supported-types.md)         | Primitive, collection, time, enum, and struct mappings  |
| [References](references.md)                   | Shared references and circular object graphs            |
| [Schema Evolution](schema-evolution.md)       | Compatible mode and evolving structs                    |
| [Xlang Serialization](xlang-serialization.md) | Interop guidance and mapping rules                      |
| [Fory IDL Compiler](../../compiler/index.md)  | Generate TypeScript models from `.fdl` schemas          |
| [gRPC Support](grpc-support.md)               | Node.js gRPC and browser gRPC-Web generated clients     |
| [Troubleshooting](troubleshooting.md)         | Common issues, limits, and debugging tips               |

## Related Resources

- [Xlang Serialization Specification](../../specification/xlang_serialization_spec.md)
- [Xlang Type Mapping](../../specification/xlang_type_mapping.md)
