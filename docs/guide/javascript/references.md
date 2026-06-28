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

By default Fory treats every value as a separate copy — if the same object appears in two fields it gets serialized twice, and after deserialization you get two independent copies. Enable reference tracking when:

- the same object instance is referenced from multiple places in the graph
- your data contains a circular structure (e.g. a node that points to itself)
- object identity must be preserved after a round trip

Leave reference tracking off for plain tree-shaped data; it adds a small overhead.

## Step 1: Enable Reference Tracking on the `Fory` Instance

```ts
const fory = new Fory({ ref: true });
```

## Step 2: Mark the Fields That Can Have Shared or Circular References

For each field whose value may be shared or circular, call `.setTrackingRef(true)` on the field's schema:

```ts
const nodeType = Type.struct("example.node", {
  value: Type.string(),
  next: Type.struct("example.node").setNullable(true).setTrackingRef(true),
});
```

You need **both** the global flag and the field-level flag. Missing either one results in values being copied rather than referenced.

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

## Xlang Note

Reference tracking is part of the Fory binary protocol and works across languages. Both sides must enable reference tracking and mark the same fields as reference-tracked for the behavior to be consistent.

## Related Topics

- [Basic Serialization](basic-serialization.md)
- [Schema Evolution](schema-evolution.md)
- [Xlang Serialization](xlang-serialization.md)
