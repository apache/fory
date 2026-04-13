---
title: Type Registration
sidebar_position: 30
id: type_registration
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

Type registration tells Fory JavaScript how to identify and encode user-defined structs and enums.

## Registering Structs

You can register a struct by numeric ID or by name.

### Register by numeric type ID

```ts
const userType = Type.struct(
  { typeId: 1001 },
  {
    id: Type.int64(),
    name: Type.string(),
  },
);

const fory = new Fory();
const userSerde = fory.register(userType);
```

Use numeric IDs when:

- you want compact metadata on the wire
- you control IDs across all participating languages
- the same logical type is registered everywhere with the same ID

### Register by name

```ts
const userType = Type.struct(
  { typeName: "example.user" },
  {
    id: Type.int64(),
    name: Type.string(),
  },
);

const fory = new Fory();
const userSerde = fory.register(userType);
```

Named registration is usually easier to evolve operationally because it avoids central numeric ID allocation, but it writes more metadata than numeric IDs.

### Explicit namespace and type name

```ts
const userType = Type.struct(
  {
    namespace: "example",
    typeName: "user",
  },
  {
    id: Type.int64(),
    name: Type.string(),
  },
);
```

This corresponds to the named xlang type identity carried in metadata.

## Registering with Decorators

```ts
@Type.struct({ typeId: 1001 })
class User {
  @Type.int64()
  id!: bigint;

  @Type.string()
  name!: string;
}

const fory = new Fory();
const { serialize, deserialize } = fory.register(User);
```

Decorator-based registration is convenient when you want your TypeScript class declaration and schema to live together.

## Registering Enums

Fory JavaScript supports both plain JavaScript enum-like objects and TypeScript enums.

### JavaScript object enum

```ts
const Color = {
  Red: 1,
  Green: 2,
  Blue: 3,
};

const fory = new Fory();
const colorSerde = fory.register(Type.enum("example.color", Color));
```

### TypeScript enum

```ts
enum Status {
  Pending = "pending",
  Active = "active",
}

const fory = new Fory();
fory.register(Type.enum("example.status", Status));
```

## Registration Scope

Registration is per `Fory` instance.

```ts
const fory1 = new Fory();
const fory2 = new Fory();

fory1.register(
  Type.struct("example.user", {
    id: Type.int64(),
  }),
);

// fory2 does not know that schema until you register it again there.
```

## Schema Handles Returned by `register`

`register` returns a bound serializer pair:

```ts
const orderType = Type.struct("example.order", {
  id: Type.int64(),
  total: Type.float64(),
});

const { serializer, serialize, deserialize } = new Fory().register(orderType);
```

Use these bound functions for repeated operations on the same type.

## Field Metadata

Each field type can be refined with schema metadata.

### Nullability

```ts
Type.string().setNullable(true);
```

### Reference tracking on a field

```ts
Type.struct("example.node").setTrackingRef(true);
```

This matters only when `new Fory({ ref: true })` is also enabled globally.

### Dynamic dispatch

```ts
import { Dynamic, Type } from "@apache-fory/core";

Type.struct("example.child").setDynamic(Dynamic.FALSE);
```

Use `dynamic` when the runtime type behavior must be controlled explicitly. In this implementation, `Dynamic.FALSE` forces monomorphic handling, `Dynamic.TRUE` forces polymorphic handling, and `Dynamic.AUTO` leaves the decision to the runtime. This is especially relevant for polymorphic fields in xlang payloads, while most users should keep the default behavior unless they are tuning a specific schema edge case.

## Choosing IDs vs names

Use **numeric IDs** when:

- you want the smallest wire representation
- your organization can keep IDs stable and unique
- multiple runtimes are coordinated tightly

Use **names** when:

- you want easier decentralized coordination
- schemas are shared by package/module name already
- slightly larger metadata is acceptable

## Cross-language requirement

For xlang interoperability, the serializer and deserializer must agree on the same type identity:

- same numeric ID, or
- same namespace + type name

The field schema must also match in a cross-language-compatible way. See [Cross-Language](cross-language.md).

## Related Topics

- [Basic Serialization](basic-serialization.md)
- [Schema Evolution](schema-evolution.md)
- [Cross-Language](cross-language.md)
