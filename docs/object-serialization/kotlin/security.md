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

Use this page when a Kotlin reader accepts bytes from outside the application's trust boundary.
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

Kotlin uses the Java configuration surface. Keep class registration enabled for production
and any untrusted payload source:

```kotlin
val fory = ForyKotlin.builder()
    .requireClassRegistration(true)
    .withMaxDepth(50)
    .withMaxGraphMemoryBytes(128L * 1024 * 1024)
    .withMaxUnbackedContainerItems(8192)
    .withMaxTypeFields(512)
    .withMaxTypeMetaBytes(4096)
    .build()
```

Security-related configuration:

- Keep `requireClassRegistration(true)` and register application classes or generated modules.
- Use `withMaxDepth(...)` to reject unexpectedly deep object graphs.
- Use `withMaxGraphMemoryBytes(...)` as an approximate gate for collection, map, array, struct, and
  object-heavy payloads. It is not an exact heap cap; leaf values are gated by remaining input
  bytes.
- Keep `withMaxUnbackedContainerItems(...)` at `8192` unless trusted compact codecs require a
  larger root allowance. Zero rejects every unbacked item.
- Keep `withMaxTypeFields(...)`, `withMaxTypeMetaBytes(...)`, and the remote schema-version limits
  at their defaults unless the data is not malicious and a trusted peer sends larger metadata or
  many schema versions.
- Follow [Java Security](../java/security.md) for allow-listing and unknown-class
  controls.

## Verification

Add negative tests for the boundary as well as normal round trips. Verify that the configured reader
rejects unexpected application types, excessive nesting, resource-limit violations, and malformed
input. After a failed read, verify that a valid root can still be read with the same Fory instance.

See [Configuration](configuration.md) for the Kotlin option reference and
[Java Type Registration](../java/type-registration.md) for the underlying registration API.
