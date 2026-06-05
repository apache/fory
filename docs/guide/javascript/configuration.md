---
title: Configuration
sidebar_position: 2
id: configuration
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

Fory JavaScript is an xlang-only implementation. `new Fory()` writes xlang payloads and uses compatible
schema evolution by default. There is no native-mode switch in the JavaScript API.

## Basic Configuration

```ts
import Fory from "@apache-fory/core";

const fory = new Fory();
```

Create one `Fory` instance per application area and reuse it. Registration generates and caches
serializer code for each schema.

## Constructor Options

```ts
import Fory from "@apache-fory/core";
import hps from "@apache-fory/hps";

const fory = new Fory({
  ref: true,
  compatible: true,
  maxDepth: 100,
  maxBinarySize: 64 * 1024 * 1024,
  maxCollectionSize: 1_000_000,
  hps,
});
```

| Option                     | Default     | Description                                                                           |
| -------------------------- | ----------- | ------------------------------------------------------------------------------------- |
| `ref`                      | `false`     | Enable reference tracking for shared or circular object graphs                        |
| `compatible`               | `true`      | Allow field additions/removals without breaking existing messages                     |
| `maxDepth`                 | `50`        | Maximum nesting depth. Must be `>= 2`. Increase for deeply nested structures          |
| `maxBinarySize`            | 64 MiB      | Maximum bytes accepted for any single binary field                                    |
| `maxCollectionSize`        | `1_000_000` | Maximum elements accepted in any list, set, or map                                    |
| `useSliceString`           | `false`     | Optional string-reading optimization for Node.js. Leave at default unless benchmarked |
| `hps`                      | unset       | Optional fast string helper from `@apache-fory/hps` (Node.js 20+)                     |
| `hooks.afterCodeGenerated` | unset       | Callback to inspect the generated serializer code, useful for debugging               |

## Reference Tracking

Global reference tracking must be enabled before field-level reference metadata can take effect:

```ts
const fory = new Fory({ ref: true });
```

Then mark reference-tracked fields in the schema, for example with
`Type.struct("example.node").setTrackingRef(true)`. See [References](references.md) and
[Schema Metadata](schema-metadata.md).

## Compatible Schema Evolution

Compatible mode is the default. To use faster serialization and smaller size:

```ts
const fory = new Fory({ compatible: false });
```

Use compatible mode for rolling upgrades, independently deployed services, and
cross-language payloads. Use `compatible: false` only when every reader and
writer always uses the same struct schema and you want faster serialization and
smaller size. For individual structs, `evolving: false` applies the same opt-out
to that struct. For cross-language payloads, set `compatible: false` only after
verifying that every language uses the same schema, or when native types are
generated from Fory schema IDL. See [Schema Evolution](schema-evolution.md).

## Optional HPS String Path

`@apache-fory/hps` provides an optional Node.js string fast path:

```ts
import hps from "@apache-fory/hps";

const fory = new Fory({ hps });
```

Leave this unset unless you run on Node.js 20+ and have benchmarked your workload.

## Security

Security-related configuration:

- Register only the expected schemas before deserializing untrusted payloads.
- Set `maxDepth`, `maxBinarySize`, and `maxCollectionSize` for the maximum payload shape your
  service accepts.
- Prefer explicit `Type.struct(...)` schemas over `Type.any()` for untrusted input.
- Pass `hps` only from the official package version you deploy with Fory.

## Related Topics

- [Basic Serialization](basic-serialization.md)
- [Schema Metadata](schema-metadata.md)
- [Schema Evolution](schema-evolution.md)
- [References](references.md)
