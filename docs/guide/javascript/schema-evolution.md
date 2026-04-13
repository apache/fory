---
title: Schema Evolution
sidebar_position: 60
id: schema_evolution
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

Fory JavaScript supports two struct encodings:

- **schema-consistent mode**: more compact, assumes the schema matches exactly
- **compatible mode**: writes extra metadata so readers can tolerate added or missing fields

## Enable compatible mode

```ts
const fory = new Fory({ compatible: true });
```

Compatible mode is the right choice when:

- multiple services roll out schema changes independently
- older readers may see newer payloads
- newer readers may see older payloads

## Example

Writer schema:

```ts
const writerType = Type.struct(
  { typeId: 1001 },
  {
    name: Type.string(),
    age: Type.int32(),
  },
);
```

Reader schema with fewer fields:

```ts
const readerType = Type.struct(
  { typeId: 1001 },
  {
    name: Type.string(),
  },
);
```

With `compatible: true`, the reader can ignore fields it does not know about.

## Per-struct evolving override

In JavaScript, struct schemas can explicitly disable evolving metadata even when type identity is otherwise compatible.

```ts
const fixedType = Type.struct(
  { typeId: 1002, evolving: false },
  {
    name: Type.string(),
  },
);
```

This produces a smaller payload than an otherwise evolving-compatible struct, but the reader must then assume the schema matches. In cross-language use, both sides must agree on whether that struct is evolving; if one side treats the type as compatible and the other uses `evolving: false`, the on-wire type kind no longer matches.

## Practical guidance

Choose **schema-consistent mode** when:

- both ends deploy together
- size and throughput matter most
- schema drift is tightly controlled

Choose **compatible mode** when:

- services evolve independently
- you need forward/backward tolerance
- operational safety is more important than the last bytes of payload size

## Cross-language requirement

Compatible mode only helps when the logical type identity still matches across runtimes. The same struct must still be registered with the same numeric ID or name across languages.

## Related Topics

- [Type Registration](type-registration.md)
- [Cross-Language](cross-language.md)
