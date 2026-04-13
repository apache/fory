---
title: Supported Types
sidebar_position: 40
id: supported_types
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

This page summarizes the main JavaScript and TypeScript values supported by Fory JavaScript.

## Primitive and Scalar Types

| JavaScript/TypeScript    | Fory schema                                                                                               | Notes                                                                             |
| ------------------------ | --------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| `boolean`                | `Type.bool()`                                                                                             | Boolean value                                                                     |
| `number`                 | `Type.int8()` / `Type.int16()` / `Type.int32()` / `Type.float32()` / `Type.float64()` / unsigned variants | Pick explicit numeric width/encoding in schemas                                   |
| `bigint`                 | `Type.int64()` / `Type.varInt64()` / `Type.uint64()` / tagged variants                                    | Use for 64-bit integer fields                                                     |
| `string`                 | `Type.string()`                                                                                           | Encoded as Latin1, UTF-16, or UTF-8 depending on content/runtime path             |
| `Uint8Array`             | `Type.binary()`                                                                                           | Variable-length binary payload                                                    |
| `Date`                   | `Type.timestamp()` / dynamic root date                                                                    | Timestamps round-trip as `Date`                                                   |
| `Date` or day count      | `Type.date()`                                                                                             | Date values deserialize as `Date`; some typed APIs also accept numeric day counts |
| duration in milliseconds | `Type.duration()`                                                                                         | JavaScript currently exposes duration fields as numeric millisecond values        |
| `BFloat16` or `number`   | `Type.bfloat16()`                                                                                         | Deserializes to `BFloat16`                                                        |
| `number`                 | `Type.float16()`                                                                                          | Half-precision floating-point support                                             |

## Integer Types

Use explicit integer schema helpers when the wire contract matters.

```ts
Type.int8();
Type.int16();
Type.int32();
Type.varInt32();
Type.int64();
Type.varInt64();
Type.sliInt64();
Type.uint8();
Type.uint16();
Type.uint32();
Type.varUInt32();
Type.uint64();
Type.varUInt64();
Type.taggedUInt64();
```

### Important JavaScript notes

- `number` cannot safely represent all 64-bit integers.
- For 64-bit integer fields, prefer `bigint` values in application code.
- Dynamic root deserialization may return `bigint` for integer values that exceed JavaScript's safe integer range.

## Floating-Point Types

```ts
Type.float16();
Type.float32();
Type.float64();
Type.bfloat16();
```

`float16` and `bfloat16` are useful when interoperating with runtimes or payloads that use reduced-precision numeric formats.

## Arrays and Typed Arrays

### Generic arrays

```ts
Type.array(Type.string());
Type.array(
  Type.struct("example.item", {
    id: Type.int64(),
  }),
);
```

These map to JavaScript arrays.

### Optimized typed arrays

Fory JavaScript supports specialized array schemas for compact numeric and boolean arrays.

```ts
Type.boolArray();
Type.int16Array();
Type.int32Array();
Type.int64Array();
Type.float16Array();
Type.bfloat16Array();
Type.float32Array();
Type.float64Array();
```

Typical runtime values are:

| Schema                 | Typical JavaScript value                                             |
| ---------------------- | -------------------------------------------------------------------- |
| `Type.boolArray()`     | `boolean[]`                                                          |
| `Type.int16Array()`    | `Int16Array`                                                         |
| `Type.int32Array()`    | `Int32Array`                                                         |
| `Type.int64Array()`    | `BigInt64Array`                                                      |
| `Type.float32Array()`  | `Float32Array`                                                       |
| `Type.float64Array()`  | `Float64Array`                                                       |
| `Type.float16Array()`  | `number[]`                                                           |
| `Type.bfloat16Array()` | `BFloat16Array` or `number[]` as input; deserializes to `BFloat16[]` |

## Maps and Sets

```ts
Type.map(Type.string(), Type.int32());
Type.set(Type.string());
```

These map to JavaScript `Map` and `Set` values.

## Structs

```ts
Type.struct("example.user", {
  id: Type.int64(),
  name: Type.string(),
  tags: Type.array(Type.string()),
});
```

Structs can be declared inline, by decorators, or nested within other schemas.

## Enums

```ts
Type.enum("example.color", {
  Red: 1,
  Green: 2,
  Blue: 3,
});
```

Both numeric and string enum values are supported. JavaScript encodes enum members by ordinal position in the declared enum object order and maps that ordinal back to the corresponding JavaScript value on read, so cross-language peers must agree on the enum member order and shape, not only the literal values.

## Nullable fields

Use `.setNullable(true)` when a field may be `null`.

```ts
Type.string().setNullable(true);
```

## Dynamic fields

Use `Type.any()` for dynamically typed content.

```ts
const eventType = Type.struct("example.event", {
  kind: Type.string(),
  payload: Type.any(),
});
```

This is useful for polymorphic payload slots, but more explicit field types are preferable when the schema is stable.

## Reference-tracked fields

Fields that can participate in shared or circular graphs should opt in:

```ts
Type.struct("example.node").setTrackingRef(true).setNullable(true);
```

This requires `new Fory({ ref: true })` at the instance level.

## Extension types and advanced areas

JavaScript supports extension types through `Type.ext(...)` plus a custom serializer passed to `fory.register(...)`.

The xlang specification also includes additional kinds such as unions. If you plan to depend on advanced features beyond the documented JavaScript surface, validate the exact API and interoperability behavior in your target runtime versions before committing to a shared wire contract.

## Related Topics

- [Basic Serialization](basic-serialization.md)
- [References](references.md)
- [Cross-Language](cross-language.md)
