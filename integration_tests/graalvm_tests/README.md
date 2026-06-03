# Graalvm native image Tests

Examples and tests for Fory serialization in graalvm native image

## Test

```bash
mvn -DskipTests=true -Pnative package
```

## Benchmark

```bash
BENCHMARK_REPEAT=400000 mvn -DskipTests=true -Pnative package
```
