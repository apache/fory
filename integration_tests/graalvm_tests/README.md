# GraalVM Native Image Tests

Examples and tests for Fory serialization in GraalVM Native Image. The Fory JSON entry point is
compiled with annotation processing disabled. It covers direct `JsonType` models, exact
`JsonMixin` target/source mappings, provider-selected hosted codec generation, configuration
fallback to interpreted codecs, and hosted access metadata for unprovided configurations in one
native image.

## Test

```bash
mvn -DmainClass=org.apache.fory.graalvm.ForyJsonExample clean -DskipTests=true -Dexec.skip=true -Pnative package
./target/main
mvn -DmainClass=org.apache.fory.graalvm.ForyJsonExample clean -DskipTests=true -Pnative-module package
./target/main-module
```

## Benchmark

```bash
BENCHMARK_REPEAT=400000 mvn -DskipTests=true -Pnative package
```
