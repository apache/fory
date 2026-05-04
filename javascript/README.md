# Apache Fory™ JavaScript

[![npm](https://img.shields.io/npm/v/@apache-fory/core?style=flat-square)](https://www.npmjs.com/package/@apache-fory/core)
[![License](https://img.shields.io/npm/l/@apache-fory/core?style=flat-square)](https://www.apache.org/licenses/LICENSE-2.0)

JavaScript / TypeScript implementation of the [Apache Fory™](https://fory.apache.org) cross-language serialization protocol. Serialize JavaScript objects to bytes and deserialize them back — including across services written in Java, Python, Go, Rust, C++, and other Fory-supported languages.

## Key Features

- **Cross-language** — serialize in JavaScript, deserialize in Java, Python, Go, Rust, C++, and more without writing glue code
- **Fast** — serializer code is generated and cached at registration time; optimized for V8 JIT
- **Reference-aware** — shared references and circular object graphs are supported
- **Schema-driven** — field types, nullability, and polymorphism are declared once with `Type.*` builders
- **Schema evolution** — optional forward/backward compatibility for independent service deployments
- **Modern types** — `bigint`, typed arrays, `Map`, `Set`, `Date`, `float16`, `bfloat16` supported

## Packages

| Package                                                                | Description                                           |
| ---------------------------------------------------------------------- | ----------------------------------------------------- |
| [`@apache-fory/core`](https://www.npmjs.com/package/@apache-fory/core) | Main Fory runtime for JavaScript/TypeScript           |
| [`@apache-fory/hps`](https://www.npmjs.com/package/@apache-fory/hps)   | Optional Node.js high-performance suite (Node.js 20+) |

## Installation

```bash
npm install @apache-fory/core
```

For optional Node.js string-detection acceleration:

```bash
npm install @apache-fory/hps
```

`@apache-fory/hps` uses V8's fast-call API to detect string encoding efficiently. It requires Node.js 20+ and is **completely optional** — if installation fails or your environment does not support it, `@apache-fory/core` works perfectly on its own.

## Quick Start

```ts
import Fory, { Type } from "@apache-fory/core";

// Optional: import hps for Node.js string performance boost
// import hps from "@apache-fory/hps";

const userType = Type.struct(
  { typeName: "example.user" },
  {
    id: Type.int64(),
    name: Type.string(),
    age: Type.int32(),
  },
);

const fory = new Fory();
// With hps: const fory = new Fory({ hps });
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

## Cross-Language Serialization

Fory JavaScript serializes to the same binary format as the Java, Python, Go, Rust, C++, and Swift runtimes. A message written in JavaScript can be read in any other supported language without any conversion layer.

```ts
// JavaScript side
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

Register the same `example.message` type on the other side using the peer runtime's API.

## Schema Evolution

Enable compatible mode for independent service deployments:

```ts
const fory = new Fory({ compatible: true });
```

Readers can skip unknown fields and tolerate missing ones, supporting rolling upgrades and schema changes across services.

## Documentation

Full documentation is available at [fory.apache.org](https://fory.apache.org):

- [JavaScript Serialization Guide](https://fory.apache.org/docs/guide/javascript)
- [Cross-Language Serialization](https://fory.apache.org/docs/guide/javascript/cross_language)
- [Supported Types](https://fory.apache.org/docs/guide/javascript/supported_types)
- [Schema Evolution](https://fory.apache.org/docs/guide/javascript/schema_evolution)
- [Xlang Serialization Spec](https://fory.apache.org/docs/specification/xlang_serialization_spec)

## License

Apache License 2.0 — see [LICENSE](https://github.com/apache/fory/blob/main/LICENSE) for details.
