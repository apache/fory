---
title: Object Serialization Security
sidebar_position: 4
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

This guide defines the trust boundary and safe operating model for Fory binary object serialization in xlang and native mode. Contributor-facing classification rules live in the [deserialization security model](deserialization-security-model.md).

Fory is an in-process serialization library. Applications link Fory into their
own process, configure serializers and type policies, and call Fory APIs to
serialize application-owned objects or deserialize encoded Fory data. Fory does
not provide a standalone network service, daemon, authentication system, or
transport protocol.

## Trust Boundaries

Fory's primary security boundary is encoded bytes or streams passed to
deserialization APIs from untrusted or partially trusted sources. The embedding
application owns where those bytes come from and which Fory configuration,
registered types, schemas, and policies are used to read them.

The adversary model for untrusted deserialization is a sender that can craft
encoded bytes or stream behavior presented to a Fory read API. It does not assume
the sender can change the embedding application's Fory configuration, registered
type set, `TypeChecker` or equivalent allow-list policy, schema definitions,
classloader, or other active policy objects unless the application itself exposes
those controls.

Fory security boundaries include:

- Runtime safety, including avoiding crashes, panics, undefined behavior, and
  out-of-bounds memory access.
- Resource ownership, including memory, CPU progress, stream buffers, native
  allocations, callbacks, and retained read-side state.
- Explicit Fory policy checks, such as class, type, function, method,
  registration, or deserialization policies that restrict what may be
  materialized.
- Cleanup boundaries, where state created during a failed root operation must
  not leak into later operations.

Runtime serializer code generation and JIT compilation are not paths for
executing encoded input. They operate on types and schemas after the active
registration check, `TypeChecker`, schema check, or policy check has accepted the
type surface. When class registration is disabled, `TypeChecker` or an
equivalent allow-list policy is the relevant gate. Generated serializer code is
derived from checked type descriptors rather than from attacker-controlled byte
contents.

The [deserialization security model](deserialization-security-model.md) defines how to
classify these boundaries for untrusted deserialization paths.

## Non-Goals

Fory does not provide:

- Encoded-data authenticity, integrity, confidentiality, signing, MACs, or
  encryption.
- Transport security or protection for bytes while they are stored or moved
  outside Fory.
- Application-level authorization or validation for the business meaning of a
  successfully deserialized value.
- A sandbox for user-registered classes, functions, constructors, setters,
  finalizers, or other application-owned logic.

Applications that receive Fory data from untrusted sources should authenticate
or integrity-check those bytes before passing them to Fory when authenticity or
tamper resistance matters.

## Downstream Responsibilities

Applications are responsible for:

- Choosing whether a byte source is trusted enough for the configured
  deserialization mode.
- Keeping class or type registration enabled for untrusted data unless another
  explicit Fory policy owns the accepted type surface.
- Registering only types and serializers that are safe for the application's
  trust boundary.
- Configuring depth and resource limits for the largest data shape the
  application intends to accept.
- Treating cross-language peers and schemas as part of the application's trust
  relationship.

Disabling registration or using dynamic deserialization on trusted data is a
configuration choice. For untrusted data, bypassing an explicit Fory policy,
crashing, leaking resources, retaining attacker-controlled state, or allocating
disproportionately remains security-relevant as described in the
[deserialization security model](deserialization-security-model.md).

## Resource Limits

### Depth Limits

Set the runtime's depth limit to the deepest graph the application deliberately
accepts. Some runtimes apply this limit to every nested value; others apply a
separate dynamic-object depth limit. Use the selected runtime's configuration
page for its exact scope and default. A depth limit prevents excessively nested
input from turning into unbounded recursion, but it is not a byte or memory
quota.

### Graph Memory Limit

`maxGraphMemoryBytes`, or the runtime-equivalent option, is an approximate gate
for graph owners materialized by one root deserialization operation. The fixed
default is 128 MiB, and explicit values must be positive. Each root operation
starts with the full configured budget, including after a failed read.

The budget covers runtime-owned collections, maps, arrays, structs, and objects
according to each implementation's storage model. It is not exact heap
accounting, an input-size limit, or a replacement for readable-byte checks.
Actual process memory may be higher. Keep external body or file-size limits at
the boundary that receives the bytes.

### Remote Schema Metadata Limits

Compatible mode may receive remote metadata (`TypeDef` or `TypeMeta`) for types that are not already
known by the reader. Fory limits how many distinct remote metadata versions can be accepted, and
also limits the size of each received metadata body:

- `maxSchemaVersionsPerType`: maximum accepted remote metadata versions for one logical type. The
  default is `10`.
- `maxAverageSchemaVersionsPerType`: average accepted remote metadata versions across all accepted
  remote types. The default is `3`; the effective global floor is `8192` metadata entries.
- `maxTypeFields`: maximum fields declared by one received struct metadata body. The default is
  `512`.
- `maxTypeMetaBytes`: maximum encoded metadata body bytes for one received TypeDef or TypeMeta body,
  excluding the 8-byte header and any extended-size varint. The default is `4096`.

These limits are resource protections. They do not change wire format, registration requirements,
dynamic type loading, unknown-type handling, or schema-evolution compatibility.

Raise these values only when a known peer deliberately sends larger metadata or
many schema versions.

### Count-Driven Container Work Limit

Every runtime limits collection elements and map entries whose repeated read
bodies do not consume proportional input. The default root allowance is `8192`.
Zero is a strict limit, and negative values are rejected. Raise the limit only
for trusted payloads that intentionally use compact zero-byte element codecs or
empty Struct bodies. This is a reader resource limit and does not change the
wire format or writer behavior.

## Configure a Runtime

Keep registration enabled for untrusted input, choose the wire mode explicitly,
and set limits to values derived from the endpoint's accepted models. A minimal
Java boundary looks like this:

```java
Fory fory =
    Fory.builder()
        .withXlang(true)
        .requireClassRegistration(true)
        .withMaxDepth(50)
        .withMaxGraphMemoryBytes(128L * 1024 * 1024)
        .withMaxUnbackedContainerItems(8192)
        .build();
```

Register only the application types that the endpoint accepts. If registration
is disabled, configure the runtime's explicit type checker or allow-list before
reading external data.

Exact option names, defaults, and mode-specific behavior belong to the runtime
configuration guides:

| Runtime               | Configuration                                           |
| --------------------- | ------------------------------------------------------- |
| Java                  | [Java configuration](java/configuration.md)             |
| Python                | [Python configuration](python/configuration.md)         |
| C++                   | [C++ configuration](cpp/configuration.md)               |
| Go                    | [Go configuration](go/configuration.md)                 |
| Rust                  | [Rust configuration](rust/configuration.md)             |
| JavaScript/TypeScript | [JavaScript configuration](javascript/configuration.md) |
| C#                    | [C# configuration](csharp/configuration.md)             |
| Swift                 | [Swift configuration](swift/configuration.md)           |
| Dart                  | [Dart configuration](dart/configuration.md)             |
| Scala                 | [Scala configuration](scala/configuration.md)           |
| Kotlin                | [Kotlin configuration](kotlin/configuration.md)         |

## Verify the Boundary

Add negative tests alongside the normal round trip. Verify that the configured
reader rejects:

- an unregistered or disallowed application type;
- a graph deeper than the accepted model;
- a graph that exceeds the configured memory budget;
- excessive remote schema versions or metadata size in compatible xlang mode;
- excessive count-driven container work; and
- a malformed root followed by a valid root on the same reusable runtime.

Also verify the application's external authentication, integrity, request-size,
timeout, and domain-validation controls independently of Fory.
