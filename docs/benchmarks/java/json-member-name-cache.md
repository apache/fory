# JSON Member-Name Cache Benchmark

This report compares JSON parsing before and after the bounded member-name cache. It covers the
three input representations, cache-disabled and ineligible controls, escaped and Unicode names,
local-slot displacement, a full shared cache, and a typed object whose known fields do not retain
parsed keys.

## Environment

- Baseline: `bb0cbd1c0dcfad6e8ab0afd84e0ebbb16d9c1b9b`
- Current production revision: `8df34bf337961cb35822baa09b35f33321a3de2c`
- Baseline benchmark JAR SHA-256:
  `4281f73a9ef30e9fbe59a9fa16313b270290f44ad52adce0aa869baa45ce6c65`
- Current benchmark JAR SHA-256:
  `9ea822791f22c5ac8083e47d615ace4a8e5f44dc057a84b1915ac03464ef979e`
- Operating system: macOS 15.7.2, arm64
- Processor: Apple M4 Pro
- Memory: 48 GiB
- Java: OpenJDK 21.0.11, 64-bit Server VM
- JMH: 1.37
- Heap: `-Xms2g -Xmx2g`
- Execution: one fork, one thread, five 2-second warmup iterations, five 2-second measurement
  iterations, throughput mode, `gc` profiler

Each case was run as an adjacent pair: the verified baseline shaded JAR was measured immediately
before the verified current shaded JAR, with no overlapping benchmark processes. Because the
benchmark class is new, the exact current source was copied into the baseline worktree and the
checked-in compatibility patch changed only setup-time access to the new builder method. The
payload constants and benchmark methods therefore stayed identical. `javap` verified that the
baseline JAR did not contain `withMaxCachedMemberNames`, the current JAR did, and both JARs contained
the same benchmark payload. The measured current reader and the current production revision also
have identical `javap -c -p` output; the post-measurement changes were tests and a source comment.

## Command

Create the baseline worktree, copy the exact benchmark source, and apply the setup-only compatibility
patch:

```bash
git fetch apache main
git worktree add --detach ../fory-benchmark-baseline \
  bb0cbd1c0dcfad6e8ab0afd84e0ebbb16d9c1b9b

cp benchmarks/java/src/main/java/org/apache/fory/benchmark/JsonObjectParseBenchmark.java \
  ../fory-benchmark-baseline/benchmarks/java/src/main/java/org/apache/fory/benchmark/

git -C ../fory-benchmark-baseline apply --unidiff-zero \
  "$(pwd)/docs/benchmarks/java/data/json-member-name-cache-baseline.patch"
```

The patch uses reflection only inside JMH `@Setup`. On the baseline, absence of
`withMaxCachedMemberNames` already means caching is disabled. No reflection, feature detection, or
compatibility branch runs inside a benchmark method. The current worktree uses the source directly
without this patch.

Install the Java artifacts and build the benchmark JAR in each worktree before running its half of
the pair:

```bash
cd java
JAVA_HOME="$JDK21_HOME" PATH="$JDK21_HOME/bin:$PATH" \
  mvn -T16 -B --no-transfer-progress clean install \
  -DskipTests -Dmaven.javadoc.skip=true

cd ../benchmarks/java
JAVA_HOME="$JDK21_HOME" PATH="$JDK21_HOME/bin:$PATH" \
  mvn -B --no-transfer-progress clean package -Pjmh -DskipTests

"$JDK21_HOME/bin/java" -jar target/benchmarks.jar \
  'org.apache.fory.benchmark.JsonObjectParseBenchmark.<case>$' \
  -f 1 -wi 5 -i 5 -w 2s -r 2s -t 1 -prof gc \
  -jvmArgsAppend '-Xms2g -Xmx2g' -rf json -rff <result.json>
```

`parseLocalCollision` parses `{"first":1,"second":2}` with a one-slot reader-local table. Both
names are already in the shared cache, so every invocation forces two local displacements and two
shared canonical-entry recoveries instead of becoming a warmed local hit.

## Results

Higher throughput is better. Allocation change is normalized bytes per operation; negative values
mean less allocation. All 13 cases first ran once. Every initial result below +2% then ran as two
more adjacent pairs, and the median of the three pair ratios is retained. For repeated cases, the
table shows the actual adjacent pair whose ratio is the median, so the throughput columns and
reported change remain one coherent observation.

![JSON member-name cache throughput and allocation changes](json_member_name_cache.png)

| Benchmark                     | Baseline ops/ms | Current ops/ms | Throughput | Baseline B/op | Current B/op | Allocation |
| ----------------------------- | --------------: | -------------: | ---------: | ------------: | -----------: | ---------: |
| `parseLatin1`                 |        4025.350 |       5089.462 |    +26.44% |      1360.001 |      976.001 |    -28.24% |
| `parseUtf8`                   |        4297.273 |       5265.112 |    +22.52% |      1360.001 |      976.001 |    -28.24% |
| `parseUtf16`                  |        4158.521 |       4362.233 |     +4.90% |      1360.001 |      976.001 |    -28.24% |
| `parseCacheDisabled`          |        3688.262 |       4110.599 |    +11.45% |      1360.001 |     1360.001 |      0.00% |
| `parseCacheDisabledObject`    |        4349.185 |       4470.750 |     +2.80% |      1360.001 |     1360.001 |      0.00% |
| `parseCacheDisabledStringMap` |        5607.709 |       5616.865 |     +0.16% |       976.001 |      976.001 |      0.00% |
| `parseLength17`               |        3478.744 |       3516.708 |     +1.09% |      1488.001 |     1488.001 |      0.00% |
| `parseEscapedNames`           |        3113.468 |       3391.772 |     +8.94% |      1360.001 |     1360.001 |      0.00% |
| `parseUnicodeNames`           |        4461.887 |       4482.636 |     +0.47% |      1360.001 |     1360.001 |      0.00% |
| `parseLocalCollision`         |       12650.661 |      12803.292 |     +1.21% |       416.000 |      368.000 |    -11.54% |
| `parseSharedFull`             |       27970.471 |      28052.363 |     +0.29% |       248.000 |      248.000 |      0.00% |
| `parseSharedFullMixed`        |       12639.491 |      13952.540 |    +10.39% |       416.000 |      368.000 |    -11.54% |
| `parseTypedPojo`              |       50045.383 |      50903.330 |     +1.71% |        48.000 |       48.000 |      0.00% |

The common retained-key cases improve throughput by 4.90% to 26.44% and reduce allocation by
28.24%. The local-displacement and mixed full-cache cases remove 48 bytes per parse. The worst
retained control result is still +0.16%, so every measured case is within the required 1% regression
gate, with no allocation increase.

The source data for the table and plot is
[`data/json-member-name-cache.csv`](data/json-member-name-cache.csv).
The exact baseline setup delta is
[`data/json-member-name-cache-baseline.patch`](data/json-member-name-cache-baseline.patch).
