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

This guide describes the current xlang implementation ownership model used by
the reference Java implementation and mirrored by the Dart implementation rewrite.

The wire format is defined by
[Xlang Serialization Spec](xlang_serialization_spec.md). This document is about
service boundaries, operation flow, and internal ownership. New language implementations do not
need the same class names, but they should preserve the same control flow:

- root operations stay on the `Fory` facade
- nested payload work stays on explicit read and write contexts
- type metadata stays in the type resolver layer
- serializers stay payload-focused

When this guide conflicts with the wire-format specification, follow
`docs/specification/xlang_serialization_spec.md`. When it conflicts with a
language-specific implementation detail, follow the current implementation code for
that language.

## Source Of Truth

Use these sources in this order:

1. `docs/specification/xlang_serialization_spec.md`
2. the current implementation for the language
3. cross-language tests under `integration_tests/`

For Dart, the implementation shape is centered on:

- `Fory`
- `WriteContext`
- `ReadContext`
- `RefWriter`
- `RefReader`
- `TypeResolver`
- `StructSerializer`

## Implementation Ownership Model

### `Fory` is the root-operation facade

`Fory` owns the reusable services for one Fory instance.

In Dart, `Fory` owns exactly four reusable members:

- `Buffer`
- `WriteContext`
- `ReadContext`
- `TypeResolver`

In Java, `Fory` also owns instance-local services such as `JITContext` and
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
- logical object-graph depth

Generated and hand-written serializers should treat these contexts as the only
source of operation-local services. Serializers must not keep ambient instance
state in thread locals, globals, or serializer instance fields.

### `WriteContext`

`WriteContext` owns all write-side per-operation state:

- current `Buffer`
- `RefWriter`
- `MetaStringWriter`
- shared TypeDef write state
- root `trackRef` mode
- recursion depth and limits

It exposes one-shot primitive helpers such as:

- `writeBool`
- `writeInt32`
- `writeVarUInt32`

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

It exposes matching one-shot primitive helpers such as:

- `readBool`
- `readInt32`
- `readVarUInt32`

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

### Stream And Buffer Byte Reads

Implementations must keep byte availability in the byte owner layer while
keeping string, binary, primitive-array, compression, and collection semantics in
serializers.

The required byte-owner primitive for allocation-before-read checks is a
readability check such as `checkReadableBytes(byteCount)`. Implementations do
not need additional generic read-context methods for this design. After the
readability check succeeds, serializers use their existing local buffer read,
copy, or decode paths.

The readability check is a byte operation only. It must not decode strings,
primitive-array element counts, compression modes, or collection capacity
policy.

For large byte-counted values, every implementation should call the byte-owner
readability check before allocating a variable-length result. This applies to
binary values, strings, decimal or metadata bodies, and primitive wire arrays
whose encoded body is measured in bytes. For multi-byte primitive wire arrays,
compare the encoded byte count, not only the logical element count, with the
readable bytes.

1. Validate the encoded byte count in the serializer. For fixed-width primitive
   arrays, check overflow and element alignment before allocation, such as
   `wireByteCount % elementByteWidth == 0`, then derive the logical element
   count from the encoded byte count.
2. Call `checkReadableBytes(wireByteCount)` unconditionally before allocating
   the variable-length result. Buffer-backed inputs normally return from this
   check with only a bounds comparison. Stream-backed inputs use the same call;
   the byte owner handles the fast path when enough bytes are already buffered
   and otherwise fills the read buffer until the requested encoded body is
   readable or an input error is recorded.
3. After readability is proven, allocate the final value once and copy or decode
   from the current readable buffer into the final result.

`checkReadableBytes` is not an `ensureCapacity(wireByteCount)` operation. In
stream mode it may end with the byte owner holding the full encoded body in its
read buffer, but it must grow that buffer as bytes are successfully read from
the stream. It should grow from current proven buffer capacity, such as by
doubling current capacity, and cap only when that bounded growth step reaches
the immediate target. A byte owner may use an owner-local availability signal as
a one-shot growth hint when the stream implementation itself is caller-owned
trusted code; if that hint is absent or insufficient, it must fall back to
bounded growth from already buffered bytes. It must not reserve the
attacker-declared length before input bytes or an owner-local growth hint
justify that intermediate buffer capacity. The stream slow path may pay one
extra intermediate buffer copy; this is preferable to serializer-local chunk
accumulation and repeated final-container growth.

For byte-counted values, the serializer should not duplicate the byte owner's
fast-path branch by testing `availableBytes()` before calling
`checkReadableBytes`. Keeping that branch in the byte owner gives every language
the same correctness rule and keeps serializer hot paths focused on their own
wire semantics.

For primitive wire arrays:

- Compare and prove the encoded wire byte count, not only the logical element
  count.
- Keep compression, bit-packing, byte-order conversion, and other primitive
  array encoding semantics in the serializer. `checkReadableBytes` only proves
  that the encoded bytes are present.
- For compressed or transformed bodies, the serializer must still validate the
  decoded length and encoding-specific metadata before allocating or returning
  the final value.

The common serializer shape is:

```text
wireByteCount = readVarUint32()
elementWidth = primitiveWireElementWidth(kind)
validate wireByteCount and element alignment
elementCount = wireByteCount / elementWidth

ctx.checkReadableBytes(wireByteCount)
result = allocatePrimitiveResult(elementCount)
copy or decode wireByteCount bytes from the current readable buffer into result
advance the reader index by wireByteCount
return result
```

Byte values are the `elementWidth == 1` specialization of the same policy. In
that case the serializer shape is:

```text
byteCount = readVarUint32()

ctx.checkReadableBytes(byteCount)
result = allocateBytes(byteCount)
copy byteCount bytes from the current readable buffer into result
advance the reader index by byteCount
return result
```

This policy avoids three inefficient implementation shapes:

- allocating the complete final contiguous value before the encoded body is
  readable
- growing or repeatedly copying the final result container on stream slow paths
- adding serializer-local chunk buffers when the byte owner can prove
  readability once and expose a normal buffered read

Scratch buffers remain appropriate when the target representation is not a
direct byte target, such as string transcoding, compression, byte-order
conversion that is not performed in place, bit-packed values, or runtimes whose
stream API cannot read into a caller-provided target.

For fixed-width primitive arrays, the final result must not become visible to
callers until the exact encoded byte count has been read successfully.

For list, set, map, and other container readers, the declared logical element
count is not an encoded byte count, so serializers must still own all element,
chunk, nullability, reference, and type-dispatch semantics. It is still the
right allocation proof for count-based preallocation: after validating a
non-empty count and reading any serializer-owned header or type metadata that
precedes allocation, call `checkReadableBytes(logicalCount)` before allocating,
reserving, or size-hinting from that count. The byte owner handles buffer versus
stream readiness; the container serializer then allocates with the declared
count and reads elements through its normal owner path.

This check is not a full container-body validation. It only prevents a small or
truncated input from causing a large count-based preallocation. Chunk sizes,
duplicate keys, element value semantics, and protocol strictness remain owned by
the container/map serializer and should be validated only when they protect a
real owner invariant.

For TypeDef or TypeMeta bodies, first prove that the encoded metadata body bytes
are readable through the byte owner. Field-list allocation should happen after
that body readability check and should not use a separate small initial-capacity
cap as a security rule.

Skip paths do not need to materialize skipped values. Existing byte-skip
operations should consume any available buffered prefix first, then skip or drop
remaining stream bytes in bounded steps.

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

Struct-specific schema/version framing and compatible-field layout belong in the
struct serializer layer, not on `Fory` and not on the public serializer API.

In Dart that internal owner is `StructSerializer`.

`StructSerializer` is responsible for:

- schema-hash framing when compatibility mode is off and version checks are on
- compatible-struct field remapping when compatibility mode is on
- caching compatible read layouts
- skipping unknown compatible fields
- passing compatible read layouts explicitly to generated serializers
- classifying matched compatible fields as exact direct reads, compatible
  conversions, or remote-only skips before generated dispatch

When `Config.compatible` is enabled and the struct is marked evolving:

- the wire type uses the compatible struct form
- the writer emits shared TypeDef metadata
- reads map incoming fields by identifier and skip unknown fields
- generated serializers apply matched fields directly while preserving their own
  object construction and default-value rules
- exact matched field schemas use the same direct read shape as same-schema
  reads and must not receive remote compatible metadata
- matched scalar fields may use compatible scalar conversion only when the
  layout has classified a remote/local top-level scalar pair as lossless
  convertible and both field schemas have `trackingRef = false`
- compatible scalar conversion applies only to the immediate matched field.
  Nested collection, array, map key, and map value schemas must not be accepted
  by recursively applying scalar conversion to child schemas.
- direct top-level `list<T?>` to dense `array<T>` matched fields must be
  classified as compatible when element domains match; the nullable element
  schema bit alone is not a schema-pair rejection. Actual null element payloads
  fail in the dense-array reader. Ref-tracked list-element framing is separate
  and may remain rejected when the runtime cannot materialize it without
  generic/reference paths.

When `compatible` is disabled and `checkStructVersion` is enabled:

- the writer emits the schema hash for struct payloads
- the read side checks that hash before reading fields

Compatible scalar conversion is owned by the compatible struct field reader or
the generated compatible layout action. Root facades, read/write contexts, type
resolvers, class resolvers, xlang type resolvers, and raw buffer utilities must
not expose public conversion APIs or carry conversion state. Resolvers may
provide field schema metadata for layout classification, but the conversion
decision and value adaptation stay with the serializer-owned compatible field
layout. Layout classification must reject top-level scalar conversions when
either matched schema has `trackingRef = true` and must reject same scalar type
pairs whose top-level `trackingRef` framing differs; converters must not add a
reference-table path for scalar mismatches. Recursive schema comparison inside
containers must reject scalar mismatches instead of reusing the top-level scalar
conversion matrix. Generated serializers should consume the classified layout
decision directly:

- source-generated serializers use the layout's matched-field dispatch key to
  select exact direct field code, compatible conversion code, or skip code
- regenerated serializers may instead compile a remote-schema-specific
  straight-line reader after classification, without a second outer matched-id
  switch, when the generated source still has pure direct, pure conversion, and
  explicit skip operations
- compatible scalar conversion cases must read the concrete remote wire scalar
  selected by classification and compose only the required lossless conversion;
  they must not call a generic runtime converter that redispatches by remote and
  local scalar type IDs, field descriptors, field names, or schema eligibility
  helpers

Same-schema readers with matching reference and null/optional framing must keep
direct scalar read paths without conversion branches or per-field conversion
objects. Same raw scalar types with different null/optional framing may still
use the compatible nullable/optional composition path when both fields are not
reference-tracked.

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

Other language implementations should preserve the same wire rule even if the configuration or
annotation surface differs.

## Out-Of-Band Buffer Objects

Buffer-object handling follows the same split:

- one root bit advertises whether out-of-band buffers are in play
- nested buffer-object payloads still decide in-band vs out-of-band one value at
  a time
- serializers use read/write context helpers rather than bypassing the context layer

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

The public helper should be a thin generated wrapper around the Fory
registration API, not a public global registry or a second unrelated
registration API family.

## Directory Layout

Under each Dart package `lib/` tree, only one nested source layer is allowed.

Allowed:

- `lib/fory.dart`
- `lib/src/<file>.dart`
- `lib/src/<area>/<file>.dart`

Not allowed:

- `lib/src/<area>/<subarea>/<file>.dart`

## Serializer Design Rules For New Implementations

Any new xlang implementation should follow these rules even if its surface API looks
different:

1. Keep root operations on the `Fory` facade and nested payload work on
   explicit read and write contexts.
2. Keep reference tracking behind dedicated read-side and write-side services
   so the disabled path stays cheap.
3. Make serializers payload-only. Type metadata, registration, and root
   framing belong to the `Fory` and type resolver layers.
4. Track per-operation state explicitly. Do not rely on ambient thread-local
   instance state.
5. Reserve read reference IDs before materializing new objects, and bind
   partially built objects as soon as a nested child may refer back to them.
6. Keep operation setup and operation cleanup separate. `prepare(...)` binds
   the current operation inputs, and `reset()` clears operation-local state.
7. Preserve the separation between the root bitmap, per-object ref flags, type
   headers, and payload bytes.
8. Keep internal naming in the serialization domain. Prefer words like
   `serializer`, `binding`, and `layout`; avoid RPC-style terms such as
   `session` or vague control-flow terms such as `plan`.
9. After any xlang protocol or ownership change, run the cross-language test
   matrix and update both this guide and
   [Xlang Serialization Spec](xlang_serialization_spec.md).

## Validation

For Dart implementation changes, run at minimum:

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
