# Dart Serialization Benchmark

This directory contains Dart benchmarks comparing Apache Fory with Protocol
Buffers using the shared benchmark schema in `benchmarks/proto/bench.proto`.

The harness uses:

- generated Fory serializers via `package:fory/fory.dart` and `part` files
  named `*.fory.dart`
- generated protobuf messages via `package:protobuf`
- the same benchmark data families used by the C++, Go, and Rust benchmarks

## Status

This package is generated and validated from the repository checkout. Run
`./run.sh` to generate code, compile the benchmark runner, and execute the
measurements.
