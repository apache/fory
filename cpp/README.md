# Apache Fory™ C++

Apache Fory™ is a blazingly-fast multi-language serialization framework powered by just-in-time compilation and zero-copy.

## Environment

- Bazel version: 6.3.2

## Build Apache Fory™ C++

```bash
# Build all projects
bazel build //:all
# Run all tests
bazel test //:all
# Run serialization tests
bazel test //cpp/fory/serialization:all
```

## Format Code

```bash
bash ci/format.sh --cpp
```
