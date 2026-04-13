---
title: References
sidebar_position: 50
id: references
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

Fory can preserve shared references and circular object graphs, but in JavaScript you should enable reference tracking globally and mark the specific struct fields that participate in shared or circular references.

## Enable reference tracking globally

```ts
const fory = new Fory({ ref: true });
```

Without `ref: true`, Fory treats values as ordinary tree-shaped data.

## Mark reference-capable fields

For schema-based structs, mark fields that can hold shared or circular references with `.setTrackingRef(true)`.

```ts
const nodeType = Type.struct("example.node", {
  value: Type.string(),
  next: Type.struct("example.node").setNullable(true).setTrackingRef(true),
});
```

## Circular self-reference example

```ts
import Fory, { Type } from "@apache-fory/core";

const nodeType = Type.struct("example.node", {
  name: Type.string(),
  selfRef: Type.struct("example.node").setNullable(true).setTrackingRef(true),
});

const fory = new Fory({ ref: true });
const { serialize, deserialize } = fory.register(nodeType);

const node: any = { name: "root", selfRef: null };
node.selfRef = node;

const copy = deserialize(serialize(node));
console.log(copy.selfRef === copy); // true
```

## Shared nested reference example

```ts
const innerType = Type.struct(501, {
  value: Type.string(),
});

const outerType = Type.struct(502, {
  left: Type.struct(501).setNullable(true).setTrackingRef(true),
  right: Type.struct(501).setNullable(true).setTrackingRef(true),
});

const fory = new Fory({ ref: true });
const { serialize, deserialize } = fory.register(outerType);

const shared = { value: "same-object" };
const copy = deserialize(serialize({ left: shared, right: shared }));
console.log(copy.left === copy.right); // true
```

## When to enable it

Enable reference tracking when:

- the same object instance is reused in multiple fields
- your graph can be cyclic
- identity preservation matters after deserialization

Leave it disabled when:

- the data is a plain tree
- you want the lowest overhead
- object identity does not matter

## Cross-language note

Reference tracking is part of the xlang protocol, but every participating runtime still needs compatible schema and configuration. If one side models a graph as plain values and the other depends on object identity, behavior may not match your expectations.

## Related Topics

- [Basic Serialization](basic-serialization.md)
- [Schema Evolution](schema-evolution.md)
- [Cross-Language](cross-language.md)
