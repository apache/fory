# GraalVM Native Image Tests

Examples and tests for Fory serialization in GraalVM Native Image. The Fory JSON
entry point covers direct `JsonType` models and runtime registration of an exact
`JsonMixin` target/source pair. Both commands below resolve the mix-in for the
first time inside the built executable; the module-path run verifies the same
generated pair factory through JPMS.

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
