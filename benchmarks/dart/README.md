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

## Schema Mismatch Mode

Set `FORY_BENCH_SCHEMA_MISMATCH=1` to run the Fory-only compatible-read
schema-mismatch mode. This mode is off by default. When enabled, run with
`--serializer fory`; protobuf and JSON benchmark modes fail with a configuration
error. Fory serialization uses the normal v1 benchmark models, and Fory
deserialization uses v2 models registered with the same Fory type IDs where one
int32 field is widened to int64.
