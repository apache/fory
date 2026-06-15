# User Guide

- For xlang serialization, see the [xlang guide](guide/xlang/index.md).
- For language-specific serialization, see the [Java](guide/java/index.md),
  [Python](guide/python/index.md), [C++](guide/cpp/index.md),
  [Go](guide/go/index.md), [Rust](guide/rust/index.md),
  [JavaScript/TypeScript](guide/javascript/index.md),
  [C#](guide/csharp/index.md), [Swift](guide/swift/index.md),
  [Dart](guide/dart/index.md), [Scala](guide/scala/index.md), and
  [Kotlin](guide/kotlin/index.md) guides.
- For row format, see the [row format spec](specification/row_format_spec.md).
- For using Apache Fory™ with GraalVM native image, see [graalvm support](guide/java/graalvm-support.md) doc.
- For deserialization security boundaries, see the [security model](security/deserialization.md).

## Fory IDL Schema

Define cross-language data structures with Fory IDL and generate native code for multiple languages.

- [Fory IDL Overview](compiler/index.md) - Introduction and quick start
- [Fory Schema IDL](compiler/schema-idl.md) - Complete language syntax
- [Type System](compiler/schema-idl.md#type-system) - Primitive types, collections, and mappings
- [Compiler Guide](compiler/compiler-guide.md) - CLI usage and build integration
- [Generated Code](compiler/generated-code.md) - Output format for each language
- [Protocol Buffers vs Fory IDL](compiler/protobuf-idl.md) - Feature comparison and porting

## Serialization Format

- For Cross Language Serialization Format, see [xlang serialization spec](specification/xlang_serialization_spec.md) doc.
- For Java Object Graph Format, see [java serialization spec](specification/java_serialization_spec.md) doc.
- For Row Format, see [row format spec](specification/row_format_spec.md) doc.

## Benchmarks

- Benchmark source code:
  - Java: [Java Benchmarks Source](../benchmarks/java)
  - Python: [Python Benchmarks Source](../benchmarks/python)
  - C++: [C++ Benchmarks Source](../benchmarks/cpp)
  - Go: [Go Benchmarks Source](../benchmarks/go)
  - Rust: [Rust Benchmarks Source](../benchmarks/rust/)
- Benchmark result:
  - Java: [Java Benchmarks](benchmarks/java/)
  - Python: [Python Benchmarks](benchmarks/python/)
  - C++: [C++ Benchmarks](benchmarks/cpp/)
  - Go: [Go Benchmarks](benchmarks/go/)
  - Rust: [Rust Benchmarks](benchmarks/rust/)
  - JavaScript/TypeScript: [JavaScript Benchmarks](benchmarks/javascript/)
  - C#: [C# Benchmarks](benchmarks/csharp/)
  - Swift: [Swift Benchmarks](benchmarks/swift/)
  - Dart: [Dart Benchmarks](benchmarks/dart/)

## Development

- For cpp debug, see [cpp debug](cpp_debug.md) doc.
- For development, see [CONTRIBUTING](../CONTRIBUTING.md) doc.
