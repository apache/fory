---
title: Schema Metadata
sidebar_position: 35
id: schema_metadata
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

JavaScript schema metadata is declared with `Type.*` builders or TypeScript decorators. Metadata
defines type identity, field types, nullability, reference tracking, dynamic fields, and per-struct
schema evolution behavior.

## Type Identity

Structs and enums can use a numeric ID or a name. Pick one identity strategy for a type and use it
consistently in every implementation that reads or writes the payload.

```ts
import { Type } from "@apache-fory/core";

const byId = Type.struct(
  { typeId: 1001 },
  {
    id: Type.int64(),
    name: Type.string(),
  },
);

const byName = Type.struct(
  { typeName: "example.user" },
  {
    id: Type.int64(),
    name: Type.string(),
  },
);
```

Use `.` inside `typeName` to add a namespace prefix.

## Decorator Metadata

Decorators keep the schema next to a TypeScript class declaration:

```ts
@Type.struct({ typeName: "example.user" })
class User {
  @Type.int64()
  id!: bigint;

  @Type.string()
  name!: string;
}
```

The decorator metadata is equivalent to the builder metadata registered with `fory.register(...)`.

## Field Types

Use explicit scalar builders for stable contracts:

```ts
Type.int8();
Type.int16();
Type.int32();
Type.int64(); // JavaScript value is bigint
Type.uint32();
Type.uint64(); // JavaScript value is bigint
Type.float16();
Type.bfloat16();
Type.float32();
Type.float64();
Type.string();
Type.binary();
```

Use collection builders for nested values:

```ts
Type.list(Type.string());
Type.map(Type.string(), Type.int32());
Type.set(Type.string());
Type.int32Array();
Type.float64Array();
```

## Nullability

Fields are non-nullable unless the schema says otherwise:

```ts
const userType = Type.struct("example.user", {
  name: Type.string(),
  email: Type.string().setNullable(true),
});
```

Passing `null` to a non-nullable field throws.

## Reference Tracking

When the same object instance can appear in multiple fields, or when an object graph can be
circular, enable global reference tracking and mark reference-tracked fields:

```ts
import Fory, { Type } from "@apache-fory/core";

const fory = new Fory({ ref: true });

const nodeType = Type.struct("example.node", {
  next: Type.struct("example.node").setNullable(true).setTrackingRef(true),
});
```

Field-level reference metadata has no effect unless `new Fory({ ref: true })` is also set.

## Dynamic Fields

Use `Type.any()` when a field can hold values with different concrete Fory types:

```ts
const eventType = Type.struct("example.event", {
  kind: Type.string(),
  payload: Type.any(),
});
```

For a struct field with a declared type, `.setDynamic(Dynamic.FALSE)` always treats values as the
declared type and `.setDynamic(Dynamic.TRUE)` always writes the concrete type. The default
`Dynamic.AUTO` is appropriate for most fields.

## Per-Struct Schema Evolution

JavaScript uses compatible schema evolution by default. For a same-schema struct that should omit
evolution metadata, set `evolving: false`:

```ts
const fixedType = Type.struct(
  { typeId: 1002, evolving: false },
  {
    name: Type.string(),
  },
);
```

Use `evolving: false` only when every reader and writer always uses the same struct schema.

## Related Topics

- [Configuration](configuration.md)
- [Type Registration](type-registration.md)
- [Supported Types](supported-types.md)
- [Schema Evolution](schema-evolution.md)
