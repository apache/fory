---
title: Basic Serialization
sidebar_position: 1
id: basic_serialization
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

This guide covers the core serialization APIs in Apache Fory JavaScript.

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

## Related Topics

- [Type Registration](type-registration.md)
- [Supported Types](supported-types.md)
- [References](references.md)
