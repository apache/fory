# GraalVM Native Image Tests

Examples and tests for Fory serialization in GraalVM Native Image. The Fory JSON entry point is
compiled with annotation processing disabled, except for `JsonProcessorExample`. That fixture uses
the real Fory annotation processor; a test Feature verifies that its generated codecs and subtype
table exist during the build but do not become reachable in the native executable. Both generated
and interpreted JSON configurations exercise those models at runtime.

Install `fory-core`, `fory-json`, and `fory-annotation-processor` from `java/` before building.
The tests cover direct `JsonType` models, exact
`JsonMixin` target/source mappings, default and provider-added generated codecs, exact-key fallback
to interpreted codecs, and hosted access metadata for unmatched configurations in one native image.

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
