# User Guide

- For Cross Language Object Graph Guide, see [xlang serialization guide](guide/xlang_serialization_guide.md) doc.
- For Java Object Graph Guide, see [java serialization guide](guide/java_serialization_guide.md) doc.
- For Row Format Guide, see [row format guide](guide/row_format_guide.md) doc.
- For Scala Guide, see [scala guide](guide/scala_guide.md) doc.
- For using Apache Fory™ with GraalVM native image, see [graalvm native image guide](guide/graalvm_guide.md) doc.

## FDL Schema (Fory Definition Language)

Define cross-language data structures with FDL and generate native code for multiple languages.

- [FDL Overview](compiler/index.md) - Introduction and quick start
- [FDL Syntax Reference](compiler/fdl-syntax.md) - Complete language syntax
- [Type System](compiler/type-system.md) - Primitive types, collections, and mappings
- [Compiler Guide](compiler/compiler-guide.md) - CLI usage and build integration
- [Generated Code](compiler/generated-code.md) - Output format for each language
- [Protocol Buffers vs FDL](compiler/protobuf-idl.md) - Feature comparison and migration

## Serialization Format

- For Cross Language Serialization Format, see [xlang serialization spec](specification/xlang_serialization_spec.md) doc.
- For Java Object Graph Format, see [java serialization spec](specification/java_serialization_spec.md) doc.
- For Row Format, see [row format spec](specification/row_format_spec.md) doc.

## Benchmarks

- Benchmark source code:
  - Java: [Java Benchmarks Source](../benchmarks/java_benchmark)
  - Python: [Python Benchmarks Source](../benchmarks/cpython_benchmark)
  - Rust: [Rust Benchmarks Source](../benchmarks/rust_benchmark/)
- Benchmark result:
  - Java: [Java Benchmarks](benchmarks/)
  - Rust: [Rust Benchmarks](benchmarks/rust_benchmark).

## Development

- For cpp debug, see [cpp debug](cpp_debug.md) doc.
- For development, see [CONTRIBUTING](../CONTRIBUTING.md) doc.
