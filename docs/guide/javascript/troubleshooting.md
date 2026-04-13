---
title: Troubleshooting
sidebar_position: 90
id: troubleshooting
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

This page covers common problems when using Fory JavaScript.

## Non-xlang payloads cannot be deserialized

The JavaScript runtime reads xlang payloads only. If you try to deserialize a non-xlang payload, deserialization fails.

Make sure the producer is writing Fory xlang data.

## `maxDepth must be an integer >= 2`

`maxDepth` protects the deserializer from excessive nesting.

```ts
new Fory({ maxDepth: 100 });
```

Use a larger value only when your payloads genuinely need it.

## `Binary size ... exceeds maxBinarySize`

A binary field or payload exceeded the configured safety limit.

```ts
new Fory({ maxBinarySize: 128 * 1024 * 1024 });
```

Increase the limit only if the input size is expected and trusted.

## `Collection size ... exceeds maxCollectionSize`

A list, set, or map exceeded the configured collection limit.

```ts
new Fory({ maxCollectionSize: 2_000_000 });
```

This is commonly hit when a producer sends unexpectedly large arrays or maps.

## `Field "..." is not nullable`

A schema field was written as `null` but was not marked nullable.

```ts
const userType = Type.struct("example.user", {
  name: Type.string(),
  email: Type.string().setNullable(true),
});
```

Mark nullable fields explicitly.

## Reference graphs do not preserve identity

Check both conditions:

1. `new Fory({ ref: true })` is enabled
2. the relevant schema fields use `.setTrackingRef(true)`

Missing either one will usually turn the graph into ordinary value-based serialization.

## Large integers come back as `bigint`

This is expected for 64-bit integer values or for dynamic numbers that exceed JavaScript's safe integer range. Use explicit numeric schemas and `bigint` in your application when exact 64-bit integer semantics matter.

## Debugging generated serializers

Use `hooks.afterCodeGenerated` to inspect generated code.

```ts
const fory = new Fory({
  hooks: {
    afterCodeGenerated(code) {
      console.error(code);
      return code;
    },
  },
});
```

## Optional `@apache-fory/hps` install issues

`@apache-fory/hps` is optional and Node-specific. If installation fails, remove it from your config and continue with `@apache-fory/core` alone.

```ts
const fory = new Fory();
```

## Related Topics

- [Basic Serialization](basic-serialization.md)
- [References](references.md)
- [Cross-Language](cross-language.md)
