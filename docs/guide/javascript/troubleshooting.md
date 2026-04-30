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

## Cannot deserialize a non-cross-language payload

The Fory JavaScript runtime only reads Fory cross-language payloads. If the producer is a Java or Go service using a language-native format, the JavaScript side cannot decode it.

Fix: switch the producer to the cross-language mode. For Java, use `.withXlang(true)`; for Go, use `WithXlang(true)`.

## `maxDepth must be an integer >= 2`

This means you passed an invalid `maxDepth` value. It must be a positive integer of at least 2.

```ts
new Fory({ maxDepth: 100 });
```

Increase this only if your data is legitimately deeply nested.

## `Binary size ... exceeds maxBinarySize`

A binary field or the overall message exceeded the safety limit. If the size is expected and the source is trusted, increase the limit:

```ts
new Fory({ maxBinarySize: 128 * 1024 * 1024 });
```

## `Collection size ... exceeds maxCollectionSize`

A list, set, or map has more elements than the configured limit. This often means the data is unexpectedly large. If it is legitimate, increase the limit:

```ts
new Fory({ maxCollectionSize: 2_000_000 });
```

## `Field "..." is not nullable`

You are passing `null` to a field that was not declared nullable. Fix: add `.setNullable(true)` to the field schema:

```ts
const userType = Type.struct("example.user", {
  name: Type.string(),
  email: Type.string().setNullable(true), // ← this field can be null
});
```

## Objects are not the same instance after deserialization

Fory does not preserve object identity by default. Two fields pointing to the same object will become two independent copies.

Fix: enable **both** of these:

1. `new Fory({ ref: true })` on the instance
2. `.setTrackingRef(true)` on the specific fields

See [References](references.md).

## Large integers come back as `bigint`

This is expected. Fory uses `bigint` for any 64-bit integer field (`Type.int64()`, `Type.uint64()`). If you need a `number`, use a smaller integer type like `Type.int32()` — but only if the value actually fits in 32 bits.

## Inspecting Generated Serializer Code

If you need to debug what Fory is doing under the hood, inspect the generated serializer code with a hook:

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

## `@apache-fory/hps` Install Fails

`@apache-fory/hps` is an optional Node.js accelerator. If it fails to install (e.g. on a platform without native module support), just remove it from your dependencies. Fory still works correctly without it.

## Related Topics

- [Basic Serialization](basic-serialization.md)
- [References](references.md)
- [Cross-Language](cross-language.md)
