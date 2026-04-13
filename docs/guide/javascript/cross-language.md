---
title: Cross-Language Serialization
sidebar_position: 80
id: cross_language
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

Apache Fory JavaScript always uses the xlang wire format. Interoperability depends on matching schemas and compatible type mappings rather than on enabling a separate mode switch.

Current limits to keep in mind:

- JavaScript deserializes xlang payloads only
- out-of-band mode is not currently supported in the JavaScript runtime

## Interop rules

For a payload to round-trip between JavaScript and another runtime:

1. **Register the same logical type identity** on both sides.
   - same numeric type ID, or
   - same namespace + type name
2. **Use compatible field types** according to the [type mapping spec](../../specification/xlang_type_mapping.md).
3. **Match nullability and reference-tracking expectations** where object graphs require them.
4. **Use compatible mode** when independent schema evolution is expected.

## Interoperability workflow

When wiring JavaScript to another runtime in production code:

1. define the JavaScript schema with the same type identity used by the other runtime
2. register the same type name or type ID in every participating runtime
3. keep field types, nullability, enum layout, and schema-evolution settings aligned
4. validate the end-to-end payload before relying on it in a shared contract

Example JavaScript side:

```ts
import Fory, { Type } from "@apache-fory/core";

const messageType = Type.struct(
  { typeName: "example.message" },
  {
    id: Type.int64(),
    content: Type.string(),
  },
);

const fory = new Fory();
const { serialize } = fory.register(messageType);

const bytes = serialize({
  id: 1n,
  content: "hello from JavaScript",
});
```

On the receiving side, register the same `example.message` type identity and compatible field types using that runtime's public API and guide:

- [Go guide](../go/index.md)
- [Python guide](../python/index.md)
- [Java guide](../java/index.md)
- [Rust guide](../rust/index.md)

## Field naming and ordering

Cross-language matching depends on consistent field identity. When multiple runtimes model the same struct, use a naming scheme that maps cleanly across languages.

Practical guidance:

- prefer stable snake_case or clearly corresponding names across runtimes
- avoid accidental renames without updating every participating runtime
- use compatible mode when fields may be added or removed over time

## Numeric mapping guidance

JavaScript needs extra care because `number` is IEEE 754 double precision.

- use `Type.int64()` with `bigint` for true 64-bit integers
- use `Type.float32()` or `Type.float64()` for floating-point fields
- avoid assuming that a dynamic JavaScript `number` maps cleanly to every integer width in another language

## Time mapping guidance

- `Type.timestamp()` maps to a point in time and deserializes as `Date`
- `Type.duration()` should be treated as a duration value, but JavaScript currently exposes typed duration fields as numeric time values rather than a dedicated duration class
- `Type.date()` corresponds to a date-without-timezone type in the specification and deserializes as `Date`

## Polymorphism and `Type.any()`

Use `Type.any()` only when you genuinely need runtime-dispatched values.

```ts
const wrapperType = Type.struct(
  { typeId: 3001 },
  {
    payload: Type.any(),
  },
);
```

This works for polymorphic values, but explicit field schemas are easier to keep aligned across languages.

## Enums

Enums must also be registered consistently across languages.

```ts
const Color = { Red: 1, Green: 2, Blue: 3 };
const fory = new Fory();
fory.register(Type.enum({ typeId: 210 }, Color));
```

Use the same type ID or type name in Java, Python, Go, and other runtimes.

## Limits and safety

Deserialization guardrails such as `maxDepth`, `maxBinarySize`, and `maxCollectionSize` are local runtime protections. They do not affect the wire format, but they can reject payloads that exceed local policy.

## Related Topics

- [Supported Types](supported-types.md)
- [Schema Evolution](schema-evolution.md)
- [Xlang Serialization Specification](../../specification/xlang_serialization_spec.md)
