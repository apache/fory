# @apache-fory/core

[![npm](https://img.shields.io/npm/v/@apache-fory/core?style=flat-square)](https://www.npmjs.com/package/@apache-fory/core)
[![License](https://img.shields.io/npm/l/@apache-fory/core?style=flat-square)](https://www.apache.org/licenses/LICENSE-2.0)

Main JavaScript / TypeScript runtime for [Apache Fory™](https://fory.apache.org) — a blazingly-fast multi-language serialization framework powered by JIT compilation and zero-copy techniques.

Serialize JavaScript objects to bytes and deserialize them back, including across services written in Java, Python, Go, Rust, C++, and other Fory-supported languages.

## Features

- **Cross-language** — serialize in JavaScript, deserialize in Java, Python, Go, Rust, C++, and more
- **Fast** — serializer code is generated and cached at registration time; optimized for V8 JIT
- **Reference-aware** — shared and circular object graphs work correctly
- **Schema-driven** — declare field types, nullability, and polymorphism once with `Type.*` builders
- **Schema evolution** — optional forward/backward compatibility for rolling upgrades
- **Modern types** — `bigint`, typed arrays, `Map`, `Set`, `Date`, `float16`, `bfloat16` supported

## Installation

```bash
npm install @apache-fory/core
```

For optional Node.js string-detection acceleration (Node.js 20+ only):

```bash
npm install @apache-fory/hps
```

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

const bytes = serialize({ id: 1n, name: "Alice", age: 30 });
const user = deserialize(bytes);
console.log(user);
// { id: 1n, name: 'Alice', age: 30 }
```

## Supported Types

| JavaScript Value | Fory Schema                                                       | Notes                                     |
| ---------------- | ----------------------------------------------------------------- | ----------------------------------------- |
| `boolean`        | `Type.bool()`                                                     |                                           |
| `number`         | `Type.int8()` / `int16()` / `int32()` / `float32()` / `float64()` | Pick the width matching the peer language |
| `bigint`         | `Type.int64()` / `uint64()`                                       | Use for 64-bit integers                   |
| `string`         | `Type.string()`                                                   |                                           |
| `Uint8Array`     | `Type.binary()`                                                   | Binary blob                               |
| `Date`           | `Type.timestamp()` / `Type.date()`                                |                                           |
| `Array`          | `Type.list(Type.T())`                                             | Ordered collection                        |
| `Map`            | `Type.map(Type.K(), Type.V())`                                    |                                           |
| `Set`            | `Type.set(Type.T())`                                              |                                           |
| Typed arrays     | `Type.int32Array()` / `Type.float64Array()` / ...                 | Dense numeric or bool arrays              |

## Define Schemas

### Structs

```ts
import { Type } from "@apache-fory/core";

const accountType = Type.struct(
  { typeName: "example.account" },
  {
    id: Type.int64(),
    owner: Type.string(),
    active: Type.bool(),
    nickname: Type.string().setNullable(true),
  },
);
```

### Nested Structs

```ts
const addressType = Type.struct("example.address", {
  city: Type.string(),
  zip: Type.string(),
});

const personType = Type.struct("example.person", {
  name: Type.string(),
  address: addressType,
});
```

### Arrays, Maps, and Sets

```ts
const inventoryType = Type.struct("example.inventory", {
  tags: Type.list(Type.string()),
  sampleIds: Type.int32Array(),
  counts: Type.map(Type.string(), Type.int32()),
  labels: Type.set(Type.string()),
});
```

## Schema Evolution

Enable compatible mode for independent service deployments:

```ts
const fory = new Fory({ compatible: true });
```

Readers skip unknown fields and tolerate missing ones, supporting rolling upgrades.

## Cross-Language Serialization

Fory JavaScript serializes to the same binary format as Java, Python, Go, Rust, C++, and Swift. A `Type.int32()` field in JavaScript matches Java `int`, Go `int32`, C# `int`.

```ts
const messageType = Type.struct(
  { typeName: "example.message" },
  {
    id: Type.int64(),
    content: Type.string(),
  },
);

const fory = new Fory();
const { serialize } = fory.register(messageType);
const bytes = serialize({ id: 1n, content: "hello from JavaScript" });
// Send bytes to a Java/Python/Go/Rust service
```

## Documentation

- [JavaScript Serialization Guide](https://fory.apache.org/docs/guide/javascript)
- [Cross-Language Serialization](https://fory.apache.org/docs/guide/javascript/cross_language)
- [Supported Types](https://fory.apache.org/docs/guide/javascript/supported_types)
- [Schema Evolution](https://fory.apache.org/docs/guide/javascript/schema_evolution)
- [Xlang Serialization Spec](https://fory.apache.org/docs/specification/xlang_serialization_spec)

## License

Apache License 2.0 — see [LICENSE](https://github.com/apache/fory/blob/main/LICENSE) for details.
