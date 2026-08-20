---
title: Basic Serialization
sidebar_position: 1
id: basic-serialization
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

This guide covers the core serialization APIs in the default xlang mode for Apache Fory JavaScript.

## Create a `Fory` Instance

```ts
import Fory from "@apache-fory/core";

const fory = new Fory();
```

Create one instance, register your schemas, and reuse it. Fory caches the generated serializers after the first `register` call, so recreating it on every request wastes that work.

## Define a Schema with `Type.struct`

The most common path is to define a schema and register it.

```ts
import Fory, { Type } from "@apache-fory/core";

const accountType = Type.struct(
  { typeName: "example.account" },
  {
    id: Type.int64(),
    owner: Type.string(),
    active: Type.bool(),
    nickname: Type.string().setNullable(true),
  },
);

const fory = new Fory();
const { serialize, deserialize } = fory.register(accountType);
```

## Serialize and Deserialize

```ts
const bytes = serialize({
  id: 42n,
  owner: "Alice",
  active: true,
  nickname: null,
});

const value = deserialize(bytes);
console.log(value);
// { id: 42n, owner: 'Alice', active: true, nickname: null }
```

The returned `bytes` value is a `Uint8Array`/platform buffer and can be sent over the network or written to storage.

## Root-Level Dynamic Serialization

`Fory` can also serialize dynamic root values without first binding a schema-specific serializer.

```ts
const fory = new Fory();

const bytes = fory.serialize(
  new Map([
    ["name", "Alice"],
    ["age", 30],
  ]),
);

const value = fory.deserialize(bytes);
```

This is convenient for dynamic payloads, but explicit schemas are usually better for stable interfaces and cross-language contracts.

## Primitive Values

```ts
const fory = new Fory();

fory.deserialize(fory.serialize(true));
// true

fory.deserialize(fory.serialize("hello"));
// 'hello'

fory.deserialize(fory.serialize(123));
// 123

fory.deserialize(fory.serialize(123n));
// 123n

fory.deserialize(fory.serialize(new Date("2021-10-20T09:13:00Z")));
// Date
```

### Number and `bigint`

JavaScript `number` is a 64-bit float, which cannot exactly represent all 64-bit integers. For cross-language contracts or anywhere exact integer sizes matter, use explicit field types in your schema:

- `Type.int32()` — 32-bit integer; use JavaScript `number`
- `Type.int64()` — 64-bit integer; use JavaScript `bigint`
- `Type.float32()` / `Type.float64()` — floating-point

Dynamic root serialization (calling `fory.serialize(someNumber)` without a schema) will infer a type, but the inferred type is not guaranteed by the API. Use a schema for any stable contract.

## Arrays, Maps, and Sets

```ts
const inventoryType = Type.struct("example.inventory", {
  tags: Type.list(Type.string()),
  counts: Type.map(Type.string(), Type.int32()),
  labels: Type.set(Type.string()),
});

const fory = new Fory({ ref: true });
const { serialize, deserialize } = fory.register(inventoryType);

const bytes = serialize({
  tags: ["hot", "new"],
  counts: new Map([
    ["apple", 3],
    ["pear", 8],
  ]),
  labels: new Set(["featured", "seasonal"]),
});

const value = deserialize(bytes);
```

## Nested Structs

```ts
const addressType = Type.struct("example.address", {
  city: Type.string(),
  country: Type.string(),
});

const userType = Type.struct("example.user", {
  name: Type.string(),
  address: Type.struct("example.address", {
    city: Type.string(),
    country: Type.string(),
  }),
});

const fory = new Fory();
const { serialize, deserialize } = fory.register(userType);

const bytes = serialize({
  name: "Alice",
  address: { city: "Hangzhou", country: "CN" },
});

const user = deserialize(bytes);
```

If a nested value can be missing, mark it nullable:

```ts
const wrapperType = Type.struct("example.wrapper", {
  child: Type.struct("example.child", {
    name: Type.string(),
  }).setNullable(true),
});
```

## Decorator-Based Registration

TypeScript decorators are also supported.

```ts
import Fory, { Type } from "@apache-fory/core";

@Type.struct("example.user")
class User {
  @Type.int64()
  id!: bigint;

  @Type.string()
  name!: string;
}

const fory = new Fory();
const { serialize, deserialize } = fory.register(User);

const user = new User();
user.id = 1n;
user.name = "Alice";

const copy = deserialize(serialize(user));
console.log(copy instanceof User); // true
```

## Nullability

Field nullability is explicit in schema-based structs.

```ts
const nullableType = Type.struct("example.optional_user", {
  name: Type.string(),
  email: Type.string().setNullable(true),
});
```

If a field is not marked nullable and you try to write `null`, serialization throws.

## Debugging Generated Code

You can inspect generated serializer code with `hooks.afterCodeGenerated`.

```ts
const fory = new Fory({
  hooks: {
    afterCodeGenerated(code) {
      console.log(code);
      return code;
    },
  },
});
```

This is useful when debugging schema behavior, field ordering, or generated fast paths.

## Cross-Language Interoperability

The default xlang format is shared by all Fory implementations. The following sections cover its cross-language type mapping, type identity, and interoperability requirements.

Fory JavaScript serializes to the same binary format as the Java, Python, C++,
Go, Rust, C#, Swift, Dart, Scala, and Kotlin Fory implementations. You can write a
message in JavaScript and read it in Java, or any other direction, without a
conversion layer.

Things to keep in mind:

- Fory JavaScript reads and writes cross-language payloads only; it does not support any native-mode format.
- JavaScript does not support out-of-band mode.

### Requirements for a Successful Round Trip

For a message to survive a round trip between JavaScript and another language:

1. **Same type identity** on both sides — same numeric ID, or same `typeName`.
2. **Compatible field types** — a `Type.int32()` field in JavaScript matches Java `int`, Go `int32`, C# `int`.
3. **Same nullability** — if one side marks a field nullable, the other should too.
4. Compatible schema evolution on both sides. JavaScript enables it by default.
5. **Same reference tracking config** if your data has shared or circular references.

### Step-by-Step: JavaScript to Another Peer

1. Define the JavaScript schema with the same type name or numeric ID used by the peer.
2. Register the schema in both peers.
3. Match field types, nullability, and schema-evolution settings.
4. Test a real payload end-to-end before shipping.

JavaScript side:

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

On the other side, register the same `example.message` type (same name or same numeric ID) using the peer language's API:

- [Java guide](../java/index.md)
- [Python guide](../python/index.md)
- [Go guide](../go/index.md)
- [Rust guide](../rust/index.md)

### Field Naming

Fory matches fields by name. When models are defined in multiple languages, keep field names consistent — or at minimum use a naming scheme that maps unambiguously across languages (e.g. `snake_case` everywhere).

With the default compatible schema evolution, field order differences are tolerated, but the names
themselves must still match.

### Numeric Types

JavaScript `number` is a 64-bit float, which does not map cleanly to every integer type in other languages. Use explicit schema types:

- `Type.int32()` for 32-bit integers (Java `int`, Go `int32`, C# `int`)
- `Type.int64()` with `bigint` values for 64-bit integers (Java `long`, Go `int64`)
- `Type.float32()` or `Type.float64()` for floating-point values

### Lists and Dense Arrays

Use `Type.list(T)` for ordinary JavaScript `Array<T>` values and Fory
`list<T>` schema. Dense bool/numeric vectors use the explicit array builders
listed below.

| Fory schema       | JavaScript/TypeScript schema builder |
| ----------------- | ------------------------------------ |
| `list<int32>`     | `Type.list(Type.int32())`            |
| `array<bool>`     | `Type.boolArray()`                   |
| `array<int8>`     | `Type.int8Array()`                   |
| `array<int16>`    | `Type.int16Array()`                  |
| `array<int32>`    | `Type.int32Array()`                  |
| `array<int64>`    | `Type.int64Array()`                  |
| `array<uint8>`    | `Type.uint8Array()`                  |
| `array<uint16>`   | `Type.uint16Array()`                 |
| `array<uint32>`   | `Type.uint32Array()`                 |
| `array<uint64>`   | `Type.uint64Array()`                 |
| `array<float16>`  | `Type.float16Array()`                |
| `array<bfloat16>` | `Type.bfloat16Array()`               |
| `array<float32>`  | `Type.float32Array()`                |
| `array<float64>`  | `Type.float64Array()`                |

### Date and Time

- `Type.timestamp()` — a point in time; round-trips as a JavaScript `Date`
- `Type.date()` — a date without time; deserializes as `Date`
- `Type.duration()` — exposed as a numeric millisecond value in JavaScript

### Polymorphic Fields

`Type.any()` lets a field hold different concrete types, but it is harder to keep in sync across languages. Prefer explicit field schemas whenever possible.

```ts
const wrapperType = Type.struct(
  { typeId: 3001 },
  {
    payload: Type.any(),
  },
);
```

### Enums

Enum member **order** must match across languages. Fory encodes enums by ordinal position, not by value.

```ts
const Color = { Red: 1, Green: 2, Blue: 3 };
const fory = new Fory();
fory.register(Type.enum({ typeId: 210 }, Color));
```

Use the same type ID or type name in every peer.

### Safety Limits

The `maxDepth` option bounds nested payloads. It does not change the binary format; it only controls what the local `Fory` instance accepts.

### Related Guides

- [Supported Types](supported-types.md)
- [Schema Evolution](schema-evolution.md)
- [Xlang Serialization Specification](../../specification/xlang_serialization_spec.md)

### Built-in values

```javascript
import Fory from "@apache-fory/core";

const fory = new Fory();
const input = fory.serialize("hello fory");
const result = fory.deserialize(input);
console.log(result);
```

### Custom values

```javascript
import Fory, { Type } from "@apache-fory/core";

// Describe data structures using JSON schema
const description = Type.struct(
  { typeName: "example.foo" },
  {
    foo: Type.string(),
  },
);
const fory = new Fory();
const { serialize, deserialize } = fory.register(description);
const input = serialize({ foo: "hello fory" });
const result = deserialize(input);
console.log(result);
```

### Shared and circular references

```javascript
import Fory, { Type } from "@apache-fory/core";

const description = Type.struct("example.foo", {
  foo: Type.string(),
  bar: Type.struct("example.foo").setTrackingRef(true),
});

const fory = new Fory({ ref: true });
const { serialize, deserialize } = fory.register(description);
const data: any = {
  foo: "hello fory",
};
data.bar = data;
const input = serialize(data);
const result = deserialize(input);
console.log(result.bar.foo === result.foo);
```

## Related Topics

- [Type Registration](type-registration.md)
- [Supported Types](supported-types.md)
- [References](references.md)
