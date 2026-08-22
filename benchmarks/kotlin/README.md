# Kotlin JSON Benchmarks

This standalone Gradle/JMH project compares Fory JSON Kotlin, kotlinx.serialization, Moshi, and
Jackson Kotlin on one immutable Eishay `MediaContent` model. It is a repository benchmark project,
not a published Fory artifact.

The model uses only `val` properties and has no public zero-argument constructor. All four
libraries consume the same model and fixture; no library-specific data transfer object or measured
model conversion is used. The fixture SHA-256 is
`8faba2f57ab397f319aced5cf1e8411a76785557d4c7d1703ec9d540354310a1`.

## Compared operations

The suite contains exactly 16 methods: four libraries multiplied by String serialization, UTF-8
byte serialization, String deserialization, and UTF-8 byte deserialization.

Each library uses a retained declared-type or generated serializer API:

- Fory retains `jsonTypeRef<MediaContent>()`, disables asynchronous compilation for deterministic
  setup, enables null emission, and warms all four generated paths before measurement.
- kotlinx.serialization retains `MediaContent.serializer()` and uses its String and stream APIs.
- Moshi retains its KSP-generated adapter and uses its String and Okio buffer APIs.
- Jackson Kotlin retains one `ObjectReader` and one `ObjectWriter` and uses its direct String and
  byte APIs.

The final byte materialization required by a library remains inside its measured byte-serialization
method. Deserialization likewise includes any fresh in-memory stream or buffer required by that
library. No byte method routes through a prebuilt String.

## Correctness gates

Before timing, setup verifies that every library:

- decodes the exact fixture from String and UTF-8 bytes to the independent expected object;
- emits structurally equivalent JSON from String and byte APIs; and
- round-trips its own String and byte output.

The Gradle build also fails unless the Moshi KSP adapter is present for every object model. Fory
uses its normal HotSpot metadata path in this benchmark; Android retention rules do not participate
in the measured runtime.

## Build and run

Install the current `fory-json-kotlin` artifact in Maven local first. Use Gradle 9.3.0 and a JDK 17
or later toolchain.

Build the correctness and JMH artifacts:

```bash
gradle --no-daemon test verifyGeneratedJsonArtifacts jmhJar
```

Run the standard JMH benchmark:

```bash
python run_json_benchmark.py --output-dir reports/json
```

The runner invokes JMH once for all 16 methods. By default, each method uses three 2-second warmup
iterations and five 2-second measurement iterations. Standard JMH JSON is written to
`benchmark_results.json`; the full process log is retained in `benchmark.log`. For a quick harness
check without collecting publishable numbers, pass
`--warmup-iterations 1 --iterations 1 --duration 100ms`.

Use `--prepare-only` for CI correctness and Moshi adapter-generation checks without performance
timing.

See the [published Kotlin JSON benchmark report](../../docs/benchmarks/json/kotlin/README.md). The
published page explicitly remains pending until a complete measured run is available; the tooling
does not synthesize results or publish reports automatically.
