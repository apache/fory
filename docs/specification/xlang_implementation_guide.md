---
title: Xlang Implementation Guide
sidebar_position: 10
id: xlang_implementation_guide
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

## Overview

This guide describes the current xlang runtime ownership model used by the
reference Java runtime and mirrored by the Dart runtime rewrite.

The wire format is defined by
[Xlang Serialization Spec](xlang_serialization_spec.md). This document is about
service boundaries, operation flow, and internal ownership. New runtimes do not
need the same class names, but they should preserve the same control flow:

- root operations stay on the runtime facade
- nested payload work stays on explicit read and write contexts
- type metadata stays in the type resolver layer
- serializers stay payload-focused

When this guide conflicts with the wire-format specification, follow
`docs/specification/xlang_serialization_spec.md`. When it conflicts with a
runtime-specific implementation detail, follow the current runtime code for
that language.

## Source Of Truth

Use these sources in this order:

1. `docs/specification/xlang_serialization_spec.md`
2. the current runtime implementation for the language
3. cross-language tests under `integration_tests/`

For Dart, the runtime shape is centered on:

- `Fory`
- `WriteContext`
- `ReadContext`
- `RefWriter`
- `RefReader`
- `TypeResolver`
- `StructCodec`

## Runtime Ownership Model

### `Fory` is the root-operation facade

`Fory` owns the reusable runtime services for one runtime instance.

In Dart, `Fory` owns exactly four runtime members:

- `Buffer`
- `WriteContext`
- `ReadContext`
- `TypeResolver`

In Java, `Fory` also owns runtime-local services such as `JITContext` and
`CopyContext`, but the ownership rule is the same: `Fory` is the root facade,
not the place where nested serializers do their work.

`Fory` is responsible for:

- preparing the shared buffer for root operations
- writing and reading the root xlang header bitmap
- delegating nested value encoding to `WriteContext`
- delegating nested value decoding to `ReadContext`
- owning registration through `TypeResolver`
- resetting operation-local context state in a top-level `finally`

Nested serializers must not call back into root `serialize(...)` or
`deserialize(...)` entry points.

### `WriteContext` and `ReadContext` hold operation-local state

`WriteContext` and `ReadContext` are prepared by `Fory` for one root operation
and reset by `Fory` in a `finally` block before reuse.

`prepare(...)` should only bind the active buffer and root-operation inputs.
`reset()` should clear operation-local mutable state.

That operation-local state includes:

- the current buffer
- the active `RefWriter` or `RefReader`
- meta-string state
- shared type-definition state
- operation-local scratch state keyed by identity
- compatible struct slot state
- logical object-graph depth

Generated and hand-written serializers should treat these contexts as the only
source of operation-local services. Serializers must not keep ambient runtime
state in thread locals, globals, or serializer instance fields.

### `WriteContext`

`WriteContext` owns all write-side per-operation state:

- current `Buffer`
- `RefWriter`
- `MetaStringWriter`
- shared TypeDef write state
- root `trackRef` mode
- recursion depth and limits
- local struct slot state used by compatible writes

It exposes one-shot primitive helpers such as:

- `writeBool`
- `writeInt32`
- `writeVarUint32`

These helpers are convenience methods. Serializers that perform repeated
primitive IO should cache `final buffer = context.buffer;` and call buffer
methods directly.

### `ReadContext`

`ReadContext` owns all read-side per-operation state:

- current `Buffer`
- `RefReader`
- `MetaStringReader`
- shared TypeDef read state
- recursion depth and limits
- local struct slot state used by compatible reads

It exposes matching one-shot primitive helpers such as:

- `readBool`
- `readInt32`
- `readVarUint32`

Generated struct serializers call `context.reference(value)` immediately after
constructing the target instance so back-references can resolve to that object.

## Reference Tracking

Reference handling is split behind two explicit services:

- `RefWriter` writes null, ref, and new-value markers and remembers previously
  written objects by identity.
- `RefReader` decodes those markers, reserves read reference IDs, and resolves
  previously materialized objects.

The xlang ref markers are:

- `NULL_FLAG (-3)`
- `REF_FLAG (-2)`
- `NOT_NULL_VALUE_FLAG (-1)`
- `REF_VALUE_FLAG (0)`

Key behavior:

- basic values never use ref tracking
- field metadata controls ref behavior inside generated structs
- root `trackRef` is only for top-level graphs and container roots with no
  field metadata
- serializers that allocate an object before all nested reads complete must bind
  that object early with `context.reference(...)`

## Type Resolution

`TypeResolver` owns:

- built-in type resolution
- registration by numeric id or by `namespace + typeName`
- serializer lookup
- struct metadata lookup
- type metadata encoding and decoding
- canonical encoded meta strings for package names, type names, and field names
- encoded-name lookup for named type resolution
- wire type decisions for struct, compatible struct, enum, ext, and union forms

In Java xlang mode the concrete implementation is `XtypeResolver`. In Dart the
same ownership stays behind the internal `TypeResolver`.

Serializers do not resolve class metadata themselves. They ask the current
context to read or write nested values, and the context delegates type work to
`TypeResolver`.

## Root Frame Responsibilities

Every root payload starts with a one-byte bitmap written and read by `Fory`
itself, not by serializers.

Current xlang root bits:

| Bit | Meaning                    |
| --- | -------------------------- |
| `0` | null root payload          |
| `1` | xlang payload              |
| `2` | out-of-band buffers in use |

Keep the root bitmap separate from per-object ref markers:

- the root bitmap describes the whole payload
- ref flags describe one nested value at a time

## Serialization Flow

### Root write path

The current root write flow is:

1. `Fory.serialize(...)` or `serializeTo(...)` prepares the target buffer.
2. `Fory` calls `writeContext.prepare(...)`.
3. `Fory` writes the root bitmap.
4. `Fory` delegates the root object to `WriteContext`.
5. `writeContext.reset()` runs in `finally`.

For a non-null root value, `WriteContext.writeRootValue(...)` performs:

1. ref/null framing
2. type metadata write
3. payload write

Payload serializers are responsible only for the payload of their type. They do
not write the root bitmap and they do not own registration or type-header
encoding.

### Nested writes use `WriteContext`

Important rules:

- nested serializers must use `WriteContext` helpers such as `writeRef(...)`,
  `writeNonRef(...)`, and container helpers when they need ref handling or type
  metadata
- repeated primitive writes should go directly through the buffer
- nested serializer flow should stay straight-line; do not add internal
  `try/finally` blocks just to clean per-operation state
- top-level `Fory.serialize(...)` owns the operation reset `finally`

## Deserialization Flow

### Root read path

The current root read flow mirrors the write flow:

1. `Fory.deserialize(...)` or `deserializeFrom(...)` reads the root bitmap.
2. null roots return immediately.
3. `Fory` validates xlang mode and other root framing requirements.
4. `Fory` calls `readContext.prepare(...)`.
5. `Fory` delegates to `ReadContext`.
6. `readContext.reset()` runs in `finally`.

### `ReadContext` owns ref reservation and payload materialization

`ReadContext.readRef()` performs the normal xlang read sequence:

1. consume the next ref marker
2. return `null` or a back-reference immediately when appropriate
3. reserve a fresh read ref id for new reference-tracked values
4. read type metadata
5. read the payload
6. bind the reserved read ref id to the completed object

Primitive and string-like hot paths should read directly from the buffer;
complex payloads delegate to the resolved serializer.

### Nested reads use `ReadContext`

Important rules:

- serializers that allocate the result object early must call
  `context.reference(obj)` before reading nested children that may refer back to
  it
- nested serializer flow should stay straight-line; do not add internal
  `try/finally` blocks just to restore operation-local state
- top-level `Fory.deserialize(...)` owns the operation reset `finally`

## Depth Tracking

`WriteContext` and `ReadContext` track logical object depth explicitly.
`increaseDepth()` enforces `Config.maxDepth`.

Depth should stay explicit on the contexts rather than relying on the native
call stack alone. At the same time, depth cleanup should not depend on nested
`try/finally` blocks throughout serializer code. Top-level context reset must be
able to recover operation-local state after failures.

## Struct Compatibility

Struct-specific schema/version framing and compatible-field staging belong in
the struct serializer layer, not on `Fory` and not on the public serializer
API.

In Dart that internal owner is `StructCodec`.

`StructCodec` is responsible for:

- schema-hash framing when compatibility mode is off and version checks are on
- compatible-struct field remapping when compatibility mode is on
- caching compatible write and read layouts
- providing compatible write/read slot state to generated serializers
- remembering remote struct metadata after successful reads

When `Config.compatible` is enabled and the struct is marked evolving:

- the wire type uses the compatible struct form
- the runtime writes shared TypeDef metadata
- reads map incoming fields by identifier and skip unknown fields

When `compatible` is disabled and `checkStructVersion` is enabled:

- the runtime writes the schema hash for struct payloads
- the read side checks that hash before reading fields

## Meta Strings And Shared Type Metadata

Two explicit pieces of state back xlang type metadata:

- `MetaStringWriter` and `MetaStringReader` deduplicate and decode namespace
  and type-name strings
- shared TypeDef write/read state tracks announced compatible struct metadata

Ownership rules:

- canonical encoded names live in `TypeResolver`
- per-operation dynamic meta-string ids live on `MetaStringWriter` and
  `MetaStringReader`
- shared type-definition tables are operation-local context state

## Enums In Xlang Mode

In xlang mode, enums are serialized by numeric tag, not by name.

In Java:

- the default tag is the declaration ordinal
- `@ForyEnumId` can override that with a stable explicit tag
- `serializeEnumByName(true)` affects native Java mode, not xlang mode

Other runtimes should preserve the same wire rule even if the configuration or
annotation surface differs.

## Out-Of-Band Buffer Objects

Buffer-object handling follows the same split:

- one root bit advertises whether out-of-band buffers are in play
- nested buffer-object payloads still decide in-band vs out-of-band one value at
  a time
- serializers use read/write context helpers rather than bypassing the runtime

## Code Generation

The normal Dart integration path is:

1. annotate structs with `@ForyStruct`
2. annotate field overrides with `@ForyField`
3. run `build_runner`
4. call the generated per-library helper, such as
   `<InputFile>Fory.register(...)`, to bind private generated metadata and
   register generated types

Generated code should emit:

- private serializer classes
- private metadata constants
- a public per-library registration helper that users call from application code
- private generated installation helpers that keep serializer factories private

The public helper should be a thin generated wrapper around the runtime
registration API, not a public global registry or a second unrelated runtime API
family.

## Directory Layout

Under each Dart package `lib/` tree, only one nested source layer is allowed.

Allowed:

- `lib/fory.dart`
- `lib/src/<file>.dart`
- `lib/src/<area>/<file>.dart`

Not allowed:

- `lib/src/<area>/<subarea>/<file>.dart`

## Serializer Design Rules For New Runtimes

Any new xlang runtime should follow these rules even if its surface API looks
different:

1. Keep root operations on the runtime facade and nested payload work on
   explicit read and write contexts.
2. Keep reference tracking behind dedicated read-side and write-side services
   so the disabled path stays cheap.
3. Make serializers payload-only. Type metadata, registration, and root
   framing belong to the runtime and type resolver layers.
4. Track per-operation state explicitly. Do not rely on ambient thread-local
   runtime state.
5. Reserve read reference IDs before materializing new objects, and bind
   partially built objects as soon as a nested child may refer back to them.
6. Keep operation setup and operation cleanup separate. `prepare(...)` binds
   the current operation inputs, and `reset()` clears operation-local state.
7. Preserve the separation between the root bitmap, per-object ref flags, type
   headers, and payload bytes.
8. Keep internal naming in the serialization domain. Prefer words like
   `codec`, `binding`, `layout`, and `slots`; avoid RPC-style terms such as
   `session` or vague control-flow terms such as `plan`.
9. After any xlang protocol or ownership change, run the cross-language test
   matrix and update both this guide and
   [Xlang Serialization Spec](xlang_serialization_spec.md).

## Validation

For Dart runtime changes, run at minimum:

```bash
cd dart
dart run build_runner build --delete-conflicting-outputs
dart analyze
dart test
```

For generated consumer coverage, also run:

```bash
cd dart/packages/fory-test
dart run build_runner build --delete-conflicting-outputs
dart test
```
