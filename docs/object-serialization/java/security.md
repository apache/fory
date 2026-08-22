---
title: Security
sidebar_position: 99
id: security
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

Use this page when a Java reader accepts bytes from outside the application's trust boundary.
Fory reconstructs application values; it does not authenticate the sender, protect transport
integrity, or decide whether a valid value is authorized for a business operation.

## Application boundary

Before deserialization:

- Authenticate the sender and protect message integrity at the transport or storage layer.
- Enforce request or file size, timeout, and concurrency limits outside Fory.
- Register only the application types the endpoint accepts and configure the reader before its
  first root operation.
- Validate the deserialized value against application authorization and domain rules before use.

## Built-in safeguards

Keep class registration enabled for production and any untrusted payload source:

```java
Fory fory = Fory.builder()
    .requireClassRegistration(true)
    .withMaxDepth(50)
    .withMaxGraphMemoryBytes(128L * 1024 * 1024)
    .withMaxUnbackedContainerItems(8192)
    .build();
```

Security-related options:

- `requireClassRegistration(true)` restricts deserialization to registered classes.
- `withMaxDepth(...)` rejects unexpectedly deep object graphs.
- `withMaxGraphMemoryBytes(...)` sets an approximate gate for materialized graph memory during one
  root deserialization. The estimate mainly covers collections, maps, arrays, structs, and objects;
  Fory core primitive arrays and primitive lists count their primitive storage from the decoded
  length. It skips leaf values such as strings, primitive scalars, and dedicated binary values that
  do not use a primitive-array serializer. Actual process memory can be higher than this limit. Leaf
  values remain protected by byte-availability checks: if the unread input does not contain enough
  bytes, Fory will not read or create that leaf value. The default is a fixed `128 MiB`; set a
  positive byte limit when trusted workloads need a larger or smaller gate.
- `withMaxUnbackedContainerItems(...)` limits count-driven collection and map work whose repeated
  read bodies do not consume proportional input. The default is `8192`; zero is a strict limit.
- `withMaxTypeFields(...)` and `withMaxTypeMetaBytes(...)` bound the field count
  and encoded body size of one received remote metadata body.
- `withMaxSchemaVersionsPerType(...)` and
  `withMaxAverageSchemaVersionsPerType(...)` bound accepted remote metadata versions without
  changing registration, dynamic loading, or schema-evolution semantics.
- `withDeserializeUnknownClass(false)` avoids materializing unknown classes from metadata.
- `checkJdkClassSerializable(true)` keeps the JDK serializability check for `java.*` classes.
- Class registration warnings can be useful during security audits; use
  `suppressClassRegistrationWarnings(false)` when you need to surface unexpected types.

Use `requireClassRegistration(false)` only for trusted payloads, and pair it with a `TypeChecker`
allow list when dynamic class loading is required.

## Verification

Add negative tests for the boundary as well as normal round trips. Verify that the configured reader
rejects unexpected application types, excessive nesting, resource-limit violations, and malformed
input. After a failed read, verify that a valid root can still be read with the same Fory instance.

See [Configuration](configuration.md) for the complete option reference and
[Type Registration](type-registration.md) for the Fory registration API.
