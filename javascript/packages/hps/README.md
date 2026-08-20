# @apache-fory/hps

[![npm](https://img.shields.io/npm/v/@apache-fory/hps?style=flat-square)](https://www.npmjs.com/package/@apache-fory/hps)
[![License](https://img.shields.io/npm/l/@apache-fory/hps?style=flat-square)](https://www.apache.org/licenses/LICENSE-2.0)

Optional Node.js high-performance suite for [Apache Fory™](https://fory.apache.org) — a blazingly-fast multi-language serialization framework.

## What It Does

`@apache-fory/hps` accelerates string encoding detection in V8 using the fast-call API. Fory supports both Latin-1 and UTF-8 strings, and detecting a string's encoding in pure JavaScript is slow. This native addon uses V8's `FASTCALL` mechanism to perform the detection dramatically faster.

## Requirements

- **Node.js 20+**
- A C++ build toolchain (for native addon compilation via `node-gyp`)

## Installation

```bash
npm install @apache-fory/hps
```

If installation fails (e.g., missing build tools or unsupported platform), you can safely skip this package. `@apache-fory/core` works correctly without it — you just miss the string-detection optimization.

## Usage

Pass the `hps` module to the Fory constructor:

```ts
import Fory, { Type } from "@apache-fory/core";
import hps from "@apache-fory/hps";

const fory = new Fory({ hps });
const { serialize, deserialize } = fory.register(
  Type.struct("example.foo", {
    foo: Type.string(),
  }),
);

const bytes = serialize({ foo: "hello fory" });
const result = deserialize(bytes);
console.log(result);
// { foo: 'hello fory' }
```

If `hps` is unavailable, omit it:

```ts
const fory = new Fory(); // works without hps
```

## When to Use

- You are running on **Node.js 20+**
- Your workload serializes a significant amount of string data
- You want the best possible serialization throughput

If you are running in a browser or on a Node.js version below 20, skip this package entirely.

## Documentation

- [JavaScript Serialization Guide](https://fory.apache.org/docs/object-serialization/javascript)
- [Apache Fory GitHub](https://github.com/apache/fory)

## License

Apache License 2.0 — see [LICENSE](https://github.com/apache/fory/blob/main/LICENSE) for details.
