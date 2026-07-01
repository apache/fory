---
title: Deserialization Security Model
sidebar_position: 3
---

This document defines the security model for Apache Fory deserialization. It is
a public security reference for classifying deserialization behavior and
deciding where validation is required. It is not a vulnerability disclosure,
does not describe exploit techniques, and does not document implementation
history.

The model is intentionally narrow. Fory should prevent resource and policy
failures caused by untrusted input, but it should not add hot-path validation
that only enforces byte-form strictness when doing so does not protect a Fory
security boundary.

## Scope

This model applies to deserializing Fory binary data from untrusted or
partially trusted sources.

It does not treat the semantic content of a successfully deserialized value as a
Fory security boundary. A sender can always construct protocol-valid data whose
value is chosen by that sender. Application authorization, object-level business
rules, and domain-specific validation remain application responsibilities.

This model also does not cover trusted in-memory formats. Row format and other
memory-format paths are trusted-data paths unless a runtime explicitly exposes
them as untrusted deserialization APIs.

## Trust Boundaries

Fory deserialization should treat the encoded input as untrusted at API
boundaries that accept external bytes or streams.

Fory security boundaries include:

- Resource ownership, such as memory, CPU progress, stream buffering, file
  handles, native allocations, callbacks, and retained read-side tables.
- Runtime safety, such as avoiding crashes, panics, undefined behavior, and
  out-of-bounds reads or writes.
- Explicit Fory policy checks, such as type, function, method, class, or
  registration policies that are intended to restrict what may be materialized.
- Cleanup boundaries, where state created during a failed read must be released
  or reset before the next root operation.

Fory security boundaries do not include:

- The business meaning of a protocol-valid value.
- Which protocol-allowed byte form was used for a value.
- Whether a map, set, object, or metadata value uses one specific encoding
  shape, unless rejecting other shapes is an explicit owner policy or protects
  one of the boundaries above.

## Type And Class Policy

Type, class, function, method, registration, and deserialization policies are
security boundaries when they are intended to restrict what untrusted bytes may
materialize.

For untrusted data, a bypass is security-relevant when encoded bytes can
materialize a type, function, method, class, or dynamic object that the active
Fory policy should reject. This includes bypasses of class or type
registration, allow-list checkers, strict-mode checks, or language-specific
deserialization policies.

Disabling registration or dynamic-type checks for trusted data is a caller
configuration choice. That choice only removes the arbitrary-type materialization
claim provided by that policy; it does not remove Fory's runtime-safety,
resource, cleanup, retained-state, or no-progress-loop requirements for
untrusted deserialization paths.

Fory is not a sandbox for application-owned types. If a registered type or
serializer is allowed by the active policy, the application owns whether that
type's construction, hooks, setters, finalizers, or other logic is safe for the
application's trust boundary.

## Depth And Progress

Deserialization paths that recurse through objects, metadata, containers, or
references should enforce the runtime's configured depth limit before crafted
nesting can exhaust the call stack or bypass cleanup. A malformed input that
exceeds the configured depth should fail the root operation instead of
continuing unbounded recursion.

Loops that consume encoded data should guarantee byte progress, logical
progress, or a terminal error. Inputs that can keep a reader in a no-progress
loop are security-relevant even when they do not allocate memory.

## Security Invariants

Deserialization code must prevent the following outcomes for untrusted input:

- Crash, panic, undefined behavior, or out-of-bounds memory access.
- OOM or disproportionate allocation compared with bytes that are already
  supplied or proven readable.
- No-progress loops, including loops where neither logical progress nor byte
  progress is guaranteed after malformed input.
- Stream-buffer growth to an attacker-declared size before the corresponding
  bytes have been read or skipped exactly.
- Resource leaks, including native allocations, handles, callbacks, or
  registered cleanup work that cannot run.
- Retained attacker-controlled state after failure when that state can affect a
  later root operation or grow across operations.
- Successful bypass of an explicit Fory policy boundary.

When a path cannot produce one of these outcomes, earlier rejection of malformed
bytes is normally a correctness or interoperability choice, not a security
requirement.

## Non-Security Semantics

The following patterns are not vulnerabilities by default:

- Protocol-allowed collection chunking, map chunking, and field ordering.
- Duplicate keys, set elements, or compatible fields that collapse according to
  the target data structure or owning serializer semantics.
- Malformed ref, null, or type flags that eventually produce a read error.
- Malformed scalar bytes that are consumed linearly and eventually produce a
  read error.
- Reading an encoded body before later shape validation when the operation
  ultimately returns an error and does not create a security-invariant failure.

Fory may still reject malformed forms for specification strictness or
interoperability. That validation should be added only when it is required by
the protocol owner, is effectively free on the relevant path, or protects a
security invariant listed above. Do not add protocol-layer validation solely to
reject scalar byte forms whose only effect is extra decode cost.

### Value-bearing ref flags

Some read paths intentionally share handling for multiple value-bearing flags.
For example, when both `NotNullValue` and `RefValue` mean that an encoded value
follows, a reader may merge their hot-path handling. This is not a malformed
flag bug by itself. Treat it as a bug only if the merged handling loses required
reference semantics, returns success across an explicit owner policy, or creates
a resource or runtime-safety failure.

## Allocation And Byte Availability

Fory should not make large allocations from attacker-declared lengths before
the required bytes are available or have been read exactly.

For buffer-backed input:

- Fixed-size binary values and primitive dense arrays should call the byte
  owner's readability check for the required encoded byte size before allocating
  the destination. For buffer-backed input this is normally a remaining-byte
  comparison.
- Multi-byte element arrays should compute the required byte size with overflow
  checks before allocation.
- Container readers that allocate backing storage or size-hint from a declared
  logical element count should call the byte owner's readability check for that
  count before that backing allocation or capacity reservation. This is not a
  full container-body validation; it is the allocation proof that the sender has
  supplied at least proportional input bytes before the reader preallocates from
  the count. Estimated memory-budget accounting may reserve budget before this
  byte check because it does not allocate backing storage.

For stream-backed input:

- Reading or skipping a large byte region is the proof that the bytes exist.
- Byte-counted variable-length result allocation should use the byte owner's
  readability check before allocation. Skip paths may use bounded skip without
  materializing the skipped value.
- A stream-backed buffer may hold the full requested encoded body after that
  body has been read from the stream. It must not reserve the attacker-declared
  length before input bytes prove that length exists.
- Stream-backed fill buffers should grow from the current proven buffer size,
  such as by doubling current capacity, and cap only to the immediate target
  when the next bounded growth step reaches it. A byte owner may use an
  owner-local availability signal as a one-shot growth hint when the stream
  implementation itself is caller-owned trusted code; if that hint is absent or
  insufficient, the reader must fall back to bounded growth from already
  buffered bytes. Serializers should not add their own availability branches.
- A truncated stream should fail before allocating the final deserialized value
  and should allocate only for bytes actually read plus bounded spare capacity.

The byte owner should stay byte-oriented. Buffer, reader, or read-context APIs
may expose byte read and byte skip operations, but string decoding, decimal
parsing, primitive-array encoding, compression modes, and collection capacity
policy belong to the owning serializers.

## Collection And Map Capacity

Large valid collection inputs are allowed. If the input contains many encoded
elements, proportional deserialization is expected.

The security requirement is to avoid disproportionate preallocation from a
declared logical count before enough input bytes justify that capacity. For a
non-empty container, a reader that will allocate or reserve from the declared
count should call `checkReadableBytes(logicalCount)` or the runtime equivalent
before that allocation. The check remains byte-owner-only: it does not decode
the whole container, validate element semantics, or replace chunk validation.
Readers that do not preallocate from the logical count may still grow
proportionally as elements are actually read.

Map or collection chunk validation is security-relevant only when missing
validation can cause a no-progress loop, unbounded resource growth, retained
state, or success across a Fory policy boundary. Protocol-allowed chunk
segmentation is normal input and is not a security issue by itself.

## Root Graph Memory Budget

Runtimes should enforce a root-deserialization budget for estimated shallow memory created by one
materialized graph. This is cumulative accounting for graph owners created by one root read; it is
not exact heap measurement and it is not a raw element-slot limit.

The public configuration is `maxGraphMemoryBytes`. The default is a fixed `128 MiB` for all root
input forms; positive user configuration overrides the default. Explicit non-positive configuration
disables this budget and can expose deserialization DoS risk from compact inputs that materialize
large object graphs. The budget is not derived from root input size, and stream budgeting should not
depend on dynamic bytes-read accounting.

Graph budget accounting should:

- happen in root-operation read state, with cleanup owned by the root deserialization `finally`;
- keep read context/read state limited to raw byte reservation; counted arithmetic and collection,
  map, array, struct, and object storage formulas belong in the concrete serializer or generated
  serializer owner;
- reject arithmetic overflow before comparing budget or allocating;
- estimate lower-bound shallow owner storage: independently materialized collections, maps, sets,
  and reference arrays reserve nonzero shallow self cost plus backing/reference/inline storage, and
  struct/record/POJO/tuple, compatible, generated, and dynamic object owners reserve a nonzero
  shallow self cost plus shallow field storage;
- use a 4-byte reference slot when the actual reference slot size is not cheap or reliable to query,
  and use primitive/value field widths for inline storage;
- preserve existing byte-availability checks before backing allocation or capacity reservation;
- skip enum/union as separate owners and skip dedicated string, binary, primitive scalar, primitive
  array, and primitive dense-array leaf owners.

Each runtime must inspect the concrete owner path before choosing formulas. Reserve self storage
exactly once at the owner that stores or allocates the value. Reference-backed paths reserve parent
owner self cost plus reference storage, while each referenced heap owner reserves its own shallow
self cost when materialized. Inline/value paths reserve inline element, field, or root storage in the
holder/allocation owner; nested value serializers must not charge their own self storage again.
Parents must not recursively include child object, collection, map, string, binary, or primitive
dense-array contents; the child owner reserves its own shallow memory when it is materialized.

Runtimes should not guess object headers, array headers, allocator headers, debug-mode fields, hash
buckets, tree links, hash-chain links, node headers, map-entry objects, spare blocks, or runtime
table layouts unless the owner path has a cheap, stable, explicit lower-bound storage signal and
documents the formula. C++ STL node, allocator, and debug-mode overheads should not be guessed.

## Skip Semantics

Skipping unknown or incompatible data is classified by concrete impact, not by
whether the runtime materializes a temporary value.

Directly consuming encoded contents is useful when it is simple and owned by the
current runtime path. It is not a security requirement for complex fields such
as lists, sets, and maps. A runtime may materialize a value and discard it when
that preserves the existing serializer ownership model.

For extension, dynamic, or user-owned types, the owning runtime may not always
have enough information to skip without invoking a registered serializer. In
that case, classify the behavior by concrete impact:

- Resource leak, retained state, no-progress loop, or policy bypass is
  security-relevant.
- Bounded materialization followed by an error or discard is allowed unless it
  creates meaningful memory or CPU pressure.
- Pure strictness about whether a skipped value used one specific encoding shape
  is not a security issue.

## Metadata And Type Resolution

Metadata parsing is security-sensitive when it affects retained read-side state,
type dispatch, or policy decisions.

Metadata readers should:

- Avoid unbounded recursion in nested metadata structures.
- Avoid unbounded table growth from attacker-controlled metadata streams.
- Validate metadata bodies before using them to bypass or replace existing
  policy decisions.
- For Java metadata paths, keep name-level checks such as `TypeChecker` and the
  disallowed-class list before `Class.forName` by routing remote class-name
  loading through the existing `TypeResolver.loadClass` owner. Do not bypass
  that owner with direct class loading from TypeDef or TypeMeta names. Other
  deserialization checks that require a materialized `Class<?>`, such as
  post-load class policy checks, remain after loading; do not move them earlier
  or replace them with string-only approximations that change registration,
  dynamic-loading, or unknown-type semantics.
- Reset or release metadata state at the correct root-operation boundary.

Remote metadata that can create persistent read state must be bounded before
that state is retained. The check is resource control only: it must not change
wire compatibility, type registration, dynamic class loading, unknown-type
handling, deserialization policy, or schema-evolution semantics. Failed or
incompatible metadata must not consume schema-version limits, and metadata
cache hits or generated field readers must not add validation, hashing,
allocation, or policy work for these limits. The concrete sequence for metadata
parsing, cache publishing, exact-local matching, and counting belongs to the
[xlang implementation guide](../specification/xlang_implementation_guide.md).

The checked metadata cache is the only owner of whether a received TypeDef or
TypeMeta header has already been validated. A metadata cache hit means the
header was previously parsed, body/hash-validated, policy-checked, and
published by the owning cache, so the reader must skip the remaining metadata
body and use the cached metadata without repeating body validation, hash
validation, limit checks, exact-local checks, or policy work. A metadata cache
miss is the only path that parses the metadata body, validates its hash and
shape, enforces metadata limits, performs exact-local byte comparison, and
publishes to the cache. Do not add separate nullable flags, sentinel headers,
per-TypeInfo acceptance markers, or parallel state to represent this decision.

Only metadata that is actually carried as a TypeDef or TypeMeta body is subject
to metadata body and schema-version limits. Compatible named enum, ext, and
union metadata normally has one version, but still counts against remote
metadata total limits when it is sent as shared metadata. Pure id-based enum,
ext, and typed-union values use type id plus user type id and must not be moved
onto this metadata body path.

Remote metadata bodies and struct field lists must also be bounded on the cold
metadata parse path. `maxTypeMetaBytes` limits the encoded metadata body bytes
for one received TypeDef or TypeMeta body, excluding the 8-byte header and any
extended-size varint. `maxTypeFields` limits the number of fields declared by
one received struct metadata body. For Java native TypeDef class layers, the
field limit applies to the total field count across the class layers in that
one TypeDef. These limits are checked before copying, decompressing, reserving,
or allocating from attacker-declared metadata sizes or field counts.

The default limits are `maxTypeFields = 512` and `maxTypeMetaBytes = 4096`.
Runtimes should report limit failures as possible malicious data and tell users
to increase the exact option only when the data is not malicious. These limits
must not introduce validation on metadata cache-hit, generated serializer, or
already-resolved type-id hot paths.

Metadata byte-form strictness alone is not a security requirement. Rejecting a
metadata shape is useful only when the owner wants that strictness or when the
shape changes type identity, retained state, resource use, or policy behavior.

## Reference Tracking

Reference tracking is part of the wire protocol and is performance-sensitive.
Readers may use sentinel values and shared value-bearing branches to keep hot
paths compact.

Reference tracking validation is security-relevant when malformed input can:

- Access an out-of-range reference without reporting an error.
- Leave retained reference state after a failed root operation.
- Register unbounded callbacks or resolver state before the referenced value is
  available.
- Cause a no-progress loop or crash.

Reference tracking validation is not required merely because a malformed flag is
not rejected at the earliest possible byte. Lazy rejection is acceptable when
the root operation still returns an error and no security invariant is violated.

## Error Propagation And Cleanup

Fory runtimes may intentionally use lazy error propagation. After a read records
an error, later read steps may continue until the outer operation observes and
returns the error.

This is acceptable when the continued work cannot:

- Crash or panic.
- Allocate or retain attacker-controlled state.
- Leak resources.
- Bypass required cleanup.
- Return success across an explicit validation or policy boundary.

Nested `try`/`finally` or equivalent cleanup should be added only when the
outer root-operation cleanup cannot cover the state or resource owned by the
nested path.

## Performance Requirements

Security validation must preserve Fory hot-path performance. Do not add
validation solely for strictness when it introduces:

- Per-element object allocation.
- Dynamic dispatch or callbacks in hot loops.
- Wrapper objects or result carriers on success paths.
- Extra copying for buffer-backed string, binary, or primitive-array reads.
- Branches that do not protect a security invariant.

Prefer owner-local checks that can be inlined and that already use information
available in the current serializer. Do not move serializer-owned semantics into
generic read-context helpers.

## Classification Guide

Use the following questions when reviewing deserialization behavior:

1. Can this input crash, panic, or access memory out of bounds?
2. Can a small or unproven input length cause disproportionate allocation?
3. Can a stream-backed reader grow a buffer before exact read or skip proves the
   bytes exist?
4. Can a loop continue without byte progress or logical progress?
5. Can the path retain attacker-controlled state after the root operation fails?
6. Can the path leak resources or skip required cleanup?
7. Can the path return success across an explicit Fory policy boundary?
8. Is the proposed validation effectively free in the relevant hot path?

If the answer to the first seven questions is no, the issue is normally not a
security finding. If the validation is not effectively free, avoid adding it
unless the protocol owner explicitly requires it.

## Documentation Boundaries

Security model documents must not include exploit samples, CVE narratives,
line-level vulnerability candidates, branch history, migration timelines, or
cleanup plans. Keep those details in private reports, issues, or pull requests
as appropriate.

Public security documentation should describe durable boundaries and invariants,
not the history of how the implementation reached them.
