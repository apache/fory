# Repository Reference

Load this file when you need repo layout, protocol context, compiler guidance, or a quick runtime map before diving into a subsystem.

## Key Directories

- `docs/`: specifications, guides, compiler docs, and benchmark reports
- `benchmarks/`: benchmark harnesses and benchmark-specific assets
- `examples/`: usage examples and sample code
- `compiler/`: Fory compiler, parser, IR, and code generators
- `java/`, `csharp/`, `python/`, `cpp/`, `go/`, `rust/`, `swift/`, `javascript/`, `dart/`, `kotlin/`, `scala/`: language implementations
- `integration_tests/`: cross-language integration tests
- `.github/workflows/` and `ci/`: CI configuration and helper scripts
- `licenses/`: third-party license reports and metadata

## Important Files

- `AGENTS.md`: repo-wide AI guidance entry point
- `CLAUDE.md`: compatibility shim that points back to `AGENTS.md`
- `README.md`: project overview and quick start
- `CONTRIBUTING.md`: contributor workflow and environment notes
- `docs/development/building.md`: development setup and build notes
- `docs/development/cpp-debugging.md`: C++ debugging guidance
- `licenserc.toml`: license header configuration

## Protocol Overview

Apache Fory is a multi-language serialization framework with multiple wire formats. Read the relevant spec before touching any protocol behavior.

- Xlang serialization format: `docs/specification/xlang_serialization_spec.md`
- Row format: `docs/specification/row_format_spec.md`
- Java serialization format: `docs/specification/java_serialization_spec.md`
- Type mapping: `docs/specification/xlang_type_mapping.md`

## Compiler And IDL Notes

- Primary references:
  - `docs/compiler/index.md`
  - `docs/compiler/cli.md`
  - `docs/compiler/schema-idl.md`
  - `docs/compiler/schema-idl.md#type-system`
  - `docs/compiler/generated-code/index.md`
  - `docs/compiler/protobuf-idl.md`
  - `docs/compiler/flatbuffers-idl.md`
- Compiler location: `compiler/`
- Install and CLI:
  - `cd compiler && pip install -e .`
  - `foryc --help`
  - `foryc schema.fdl --lang <langs> --output <dir>`
- Never edit generated code manually. Update the source schema or IDL and regenerate.
- Protocol changes must update `docs/specification/**` and the relevant cross-language tests.
- Remote `TypeDef` or `TypeMeta` schema limits are resource controls on cold metadata cache-miss
  parse/publish paths only. They must not change wire format, registration, dynamic type loading,
  unknown-type behavior, deserialization policy, schema-evolution semantics, or metadata
  cache-hit/generated-reader hot paths. Count a remote metadata version only after the required read
  state has been successfully built and the owning metadata cache can publish it; failed or
  incompatible metadata must not consume the limit. Struct types may have multiple schema-evolution
  versions; compatible named enum/ext/union metadata normally has one version but still counts
  against remote metadata total limits when it is sent as shared metadata. Pure id-based enum, ext,
  and typed-union values use type id plus user type id and must not be moved onto this metadata
  cache path. The protocol-defined 52-bit TypeDef/TypeMeta header hash is the unique schema
  identity. When the selected local type already owns the received header, that is a local-schema
  hit: skip the body and use the local metadata without body comparison, cache publication, or
  schema-version counting. A checked remote-cache hit likewise skips the body without rehashing,
  byte comparison, repeated validation, or policy work. The low 12 bits describe only the current
  frame; a hit uses its current size for bounds and skip without validating reserved or compression
  flags. A cache miss is the only path that parses and validates a body. After that first
  validation, a runtime may compare the received 52-bit hash with lazily built local metadata when
  no local header was available before the parse; hash equality selects the local owner and may
  bypass remote schema-version counting without a byte or field comparison.
  Derive a miss-only local candidate inside the metadata owner from the decoded identity, after
  existing class, registration, and policy checks. A statically declared reader may pass its
  concrete expected owner so reference and cache hits can route by owner identity before publish;
  do not thread expected-type parameters solely to repeat miss-time metadata validation. Do not add
  parallel accepted-header state or retain metadata bytes to revalidate either path.
- Remote metadata body and struct field-count limits are also cold-path resource controls.
  `maxTypeMetaBytes` limits one received TypeDef or TypeMeta body excluding the 8-byte header and
  extended-size varint; `maxTypeFields` limits one received struct metadata body's field count
  (Java native TypeDef counts total fields across class layers). Check these before body
  copy/decompression and before field-list allocation, and never add cache-hit or generated-reader
  hot-path work for them.

## Root Graph Memory Budget Ownership

Root graph memory budgeting is a read-state accounting feature only. Read context or equivalent
read state may expose raw byte reservation and, when a runtime cannot reasonably avoid it,
root-operation budget setup/reset. Root facades may reset the per-operation budget, but must not
pre-reserve root type, root self bytes, or root value storage. It must not grow semantic APIs for
collection, map, array, struct, object, temporary-owner, serializer-owner, conversion,
counted-allocation, or ref-publication control. Concrete serializers and generated serializers own
allocation formulas, overflow checks, allocation-owner decisions, and reference publication timing.
Value serializers only read their data; the holder or materializer that stores, boxes, or allocates
the value reserves the storage it owns.

Treat `maxGraphMemoryBytes` and runtime-named equivalents as approximate gates, mainly for
materialized collection, map, array, struct, and object owners. Actual process memory can be higher.
Dedicated string, binary, primitive scalar, primitive array, and dense primitive-array leaf values
are skipped unless a runtime-specific owner rule includes them. Java Fory core primitive arrays and
primitive lists reserve their retained owners once from the validated logical length; compressed
paths use the decompressed length. Java Fory JSON primitive arrays decoded from JSON arrays reserve
their array header plus actual primitive storage; a `byte[]` handled by a JSON binary or Base64
codec remains a binary leaf. Values skipped by this graph budget must remain gated by unread input
bytes: if remaining bytes are insufficient, the value must not be read or created.

## Runtime Map

### Java

- `java/fory-core`: core object graph serialization runtime
- `java/fory-format`: row format encoding and decoding
- `java/fory-extensions`: optional extensions such as protobuf serializers and zstd meta compression
- `java/fory-simd`: SIMD-accelerated paths
- `java/fory-test-core`: shared Java test utilities
- `java/testsuite`: issue-driven and complex regression tests
- `java/benchmark`: JMH benchmarks

### Bazel

- `MODULE.bazel`: bzlmod dependency management
- `bazel/cython_library.bzl`: `pyx_library` support for Cython extension builds

### C++

- `cpp/fory/row`: row-format data structures
- `cpp/fory/meta`: compile-time reflection utilities
- `cpp/fory/encoder`: row encoder and decoder
- `cpp/fory/util`: core utilities such as buffer and status types

### Python

- `python/pyfory/serialization.pyx`: Cython xlang serialization core
- `python/pyfory/_fory.py`: pure-Python xlang serialization entry point
- `python/pyfory/registry.py`: type registry and serializer dispatch
- `python/pyfory/resolver.py`: pure-Python reference resolver
- `python/pyfory/format`: row-format support
- `python/pyfory/buffer.pyx`: shared buffer and string helpers

### Go

- `go/fory/fory.go`: entry point
- `go/fory/resolver.go`: shared and circular reference tracking
- `go/fory/type.go`: type resolution and dispatch

### Rust

- `rust/fory/src/lib.rs`: public entry point
- `rust/fory-core/src/fory.rs`: core runtime entry point
- `rust/fory-core/src/resolver/`: resolver and context state
- `rust/fory-core/src/serializer/`: serializers
- `rust/fory-derive/src/`: derive macros for code generation

## Shared Debugging Heuristics

- For protocol issues, start with the relevant spec before changing code.
- For performance issues, profile first and verify memory-allocation behavior and ownership boundaries.
- For build issues, prefer clean rebuilds and explicit dependency-version checks before assuming tool bugs.
- For Bazel-specific build issues, use `bazel clean --expunge` when a deep clean is needed.
