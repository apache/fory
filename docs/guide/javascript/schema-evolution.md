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

Schema evolution lets different versions of your service exchange messages safely — a v2 writer can produce a message a v1 reader still understands, and vice versa.

## Compatible Mode

Compatible mode is the default. It writes extra field metadata so readers can skip unknown fields and
tolerate missing ones. Keep it for independent deployments, rolling upgrades, and xlang services.
For payloads whose reader and writer schemas never differ, see
[When to Use Each Mode](#when-to-use-each-mode).

Compatible readers also tolerate selected scalar field type changes when the conversion is lossless.
A matched field can read between `boolean`, `string`, numeric scalars, and `Decimal` when the
converted value has the same logical value. For example, `"true"`, `"false"`, `"1"`, and `"0"` can
be read as booleans, exact finite ASCII numeric strings can be read as numeric fields that can hold
them, numbers and decimals can be read as canonical strings, and numeric widening or narrowing
succeeds only when no precision or range is lost. Invalid strings and lossy conversions fail during
deserialization. Nullable fields still compose with these conversions, but reference-tracked scalar
type changes are incompatible.

## Default Compatible Mode

```ts
const fory = new Fory();
```

Use this when:

- services deploy schema changes independently
- older readers may see newer payloads
- newer readers may see older payloads from before a field was added

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

With compatible mode, the reader ignores fields it does not know about, and fills unknown fields with default values.

## When to Use Each Mode

| Requirement                                  | Same-schema opt-out | Compatible mode |
| -------------------------------------------- | ------------------- | --------------- |
| Every reader and writer uses the same schema | works               | works           |
| Independent deployments                      | unsafe              | recommended     |
| Best size and speed for same-schema data     | yes                 | no              |
| Rolling upgrades                             | unsafe              | recommended     |

Set `compatible: false` for xlang payloads only after verifying that every
language uses the same schema, or when native types are generated from Fory
schema IDL.

## Same-Schema Per-Struct Opt-Out

You can disable evolution metadata for a specific struct even inside a `compatible: true` instance:

```ts
const fixedType = Type.struct(
  { typeId: 1002, evolving: false },
  {
    name: Type.string(),
  },
);
```

`evolving: false` can be faster and smaller for that struct. Use it only when every reader and
writer always uses the same struct schema. If one side writes with `evolving: false` and the other
reads expecting compatible metadata, deserialization will fail.

## Xlang Requirement

Compatible mode only protects you from schema differences in the _fields_ of a type. You still need the same type identity (same numeric ID or same `typeName`) on every side. See [Xlang Serialization](xlang-serialization.md).

## Related Topics

- [Type Registration](type-registration.md)
- [Xlang Serialization](xlang-serialization.md)
