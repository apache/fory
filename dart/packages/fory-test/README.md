# Fory Dart Tests

This package exercises Dart generated serializers, registration, external-type
serialization, and consumer-side xlang integration.

## External-Type Benchmark

Compile and run the paired ordinary/external throughput benchmark in AOT mode:

```bash
mkdir -p build
dart compile exe \
  -S build/external_type_benchmark.debug \
  -o build/external_type_benchmark \
  benchmark/external_type_benchmark.dart
build/external_type_benchmark
```

Check equivalent-path object allocations with:

```bash
dart run benchmark/external_type_benchmark.dart --check-allocations
```
