# Graalvm native image Tests

Examples and tests for Fory serialization in graalvm native image

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
