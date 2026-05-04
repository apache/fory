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

This page lists the JavaScript and TypeScript types supported by Fory, and explains when you need to be deliberate about type choices for cross-language compatibility.

## Primitive and Scalar Types

| JavaScript value      | Fory schema                                                                           | Notes                                                |
| --------------------- | ------------------------------------------------------------------------------------- | ---------------------------------------------------- |
| `boolean`             | `Type.bool()`                                                                         |                                                      |
| `number`              | `Type.int8()` / `Type.int16()` / `Type.int32()` / `Type.float32()` / `Type.float64()` | Pick the width that matches the peer language        |
| `bigint`              | `Type.int64()` / `Type.uint64()`                                                      | Use `bigint` for 64-bit integers                     |
| `string`              | `Type.string()`                                                                       |                                                      |
| `Uint8Array`          | `Type.binary()`                                                                       | Binary blob                                          |
| `Date`                | `Type.timestamp()`                                                                    | Serializes/deserializes as `Date`                    |
| `Date`                | `Type.date()`                                                                         | Date without time; deserializes as `Date`            |
| duration (ms)         | `Type.duration()`                                                                     | Exposed as a numeric millisecond value in JavaScript |
| `number`              | `Type.float16()`                                                                      | Half-precision float                                 |
| `BFloat16` / `number` | `Type.bfloat16()`                                                                     | Deserializes to `BFloat16`                           |

## Integer Types

JavaScript `number` is a 64-bit float. It cannot safely represent all 64-bit integers (integers above `Number.MAX_SAFE_INTEGER` lose precision). Use explicit schemas to match the width expected by the peer language:

```ts
Type.int8(); // -128 to 127
Type.int16(); // -32,768 to 32,767
Type.int32(); // variable-length int32; default for semantic int32
Type.int32({ encoding: "fixed" });
Type.int64(); // variable-length int64; use with bigint
Type.int64({ encoding: "fixed" });
Type.int64({ encoding: "tagged" });
Type.uint8();
Type.uint16();
Type.uint32(); // variable-length uint32
Type.uint32({ encoding: "fixed" });
Type.uint64(); // variable-length uint64; use with bigint
Type.uint64({ encoding: "fixed" });
Type.uint64({ encoding: "tagged" });
```

**Rule of thumb**: anything that maps to a 64-bit integer in another language should use `Type.int64()` or `Type.uint64()` on the JavaScript side and be passed as a `bigint` value.

## Floating-Point Types

```ts
Type.float16();
Type.float32();
Type.float64();
Type.bfloat16();
```

`float16` and `bfloat16` are useful when interoperating with runtimes or payloads that use reduced-precision numeric formats.

## Arrays and Typed Arrays

### Lists

```ts
Type.list(Type.string());
Type.list(
  Type.struct("example.item", {
    id: Type.int64(),
  }),
);
```

These map to JavaScript arrays and use the Fory `list<T>` schema.

## Optimized Numeric Arrays

For dense arrays of bools and numbers, use the element-specific array builders. They are more compact and map to native typed arrays where JavaScript has one:

```ts
Type.boolArray(); // boolean[] in JS
Type.int16Array(); // Int16Array
Type.int32Array(); // Int32Array
Type.int64Array(); // BigInt64Array
Type.float32Array(); // Float32Array
Type.float64Array(); // Float64Array
Type.float16Array(); // number[]
Type.bfloat16Array(); // BFloat16[]
```

Use `Type.list(elementType)` for non-numeric, struct, nullable-element, or ref-tracked ordered collections.

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
  tags: Type.list(Type.string()),
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

Fory encodes enum values by their ordinal position in the object (not their value). Both sides must declare enum members in the same order. When interoperating with another language, make sure the member order matches, not just the values.

## Nullable fields

Use `.setNullable(true)` when a field may be `null`.

```ts
Type.string().setNullable(true);
```

## Dynamic Fields

Use `Type.any()` when a field can hold values of different types at runtime.

```ts
const eventType = Type.struct("example.event", {
  kind: Type.string(),
  payload: Type.any(),
});
```

Explicit field schemas are preferable when the type is known — `Type.any()` is harder to keep aligned across languages.

## Reference-Tracked Fields

When the same object instance can appear in multiple fields, or when your graph is circular, opt individual fields into reference tracking:

```ts
Type.struct("example.node").setTrackingRef(true).setNullable(true);
```

This requires `new Fory({ ref: true })`. See [References](references.md).

## Extension Types

For types that need completely custom encoding, use `Type.ext(...)` and pass a custom serializer to `fory.register(...)`. This is an advanced use case; the standard `Type.struct` covers most scenarios.

## Related Topics

- [Basic Serialization](basic-serialization.md)
- [References](references.md)
- [Cross-Language](cross-language.md)
