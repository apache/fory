# Apache Fory™ C++

Apache Fory™ is a blazingly-fast multi-language serialization framework powered by just-in-time compilation and zero-copy.

## Build Apache Fory™ C++

```bash
# Build all projects
bazel build //:all
# Run all tests
bazel test //:all
```

## Environment

- Bazel version: 6.3.2

## Benchmark

```bash
bazel build //cpp/fory/benchmark:all
bazel test //cpp/fory/benchmark:all
# You can also run a single benchmark to see how efficient it is.
# For example
bazel run //cpp/fory/benchmark:benchmark_string_util
```
