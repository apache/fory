# GraalVM Native Image Tests

Examples and tests for Fory serialization in GraalVM Native Image. The Fory JSON
entry point covers direct `JsonType` models and runtime registration of exact
`JsonMixin` target/source pairs. Native-image hosted analysis validates each pair and registers its
generated factory. The built executables then select and execute the direct target or either pair at
runtime; the module-path run verifies the same factories through JPMS.

## Test

```bash
mvn clean -DskipTests=true -Pnative package
./target/main
mvn clean -DskipTests=true -Pnative-module package
./target/main-module
```

## Benchmark

```bash
BENCHMARK_REPEAT=400000 mvn -DskipTests=true -Pnative package
```
