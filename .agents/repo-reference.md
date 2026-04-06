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
- `docs/guide/DEVELOPMENT.md`: development setup and build notes
- `docs/cpp_debug.md`: C++ debugging guidance
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
  - `docs/compiler/compiler-guide.md`
  - `docs/compiler/schema-idl.md`
  - `docs/compiler/type-system.md`
  - `docs/compiler/generated-code.md`
  - `docs/compiler/protobuf-idl.md`
  - `docs/compiler/flatbuffers-idl.md`
- Compiler location: `compiler/`
- Install and CLI:
  - `cd compiler && pip install -e .`
  - `foryc --help`
  - `foryc schema.fdl --lang <langs> --output <dir>`
- Never edit generated code manually. Update the source schema or IDL and regenerate.
- Protocol changes must update `docs/specification/**` and the relevant cross-language tests.

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
- `go/fory/codegen`: code generation support

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
