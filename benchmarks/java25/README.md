# Java 25 Direct Memory Access Benchmark

This diagnostic JMH module compares direct-buffer scalar access paths used to reason about
`MemoryBuffer` on JDK 25:

- `MemorySegment.get/set` with native-order unaligned layouts.
- `MethodHandles.byteBufferViewVarHandle` over a direct `ByteBuffer`.

Each benchmark invocation performs one absolute scalar access at a rolling aligned offset. This is
closer to generated serializer calls into `MemoryBuffer.writeInt32`, `_unsafePutInt64`, and matching
read paths than a bulk array-copy benchmark, while keeping the JDK25 benchmark module free of
`jdk.unsupported` APIs.

Build and run with JDK 25:

```bash
cd benchmarks/java25
mvn package
java -jar target/java25-memory-access-benchmarks.jar \
  'org.apache.fory.benchmark.java25.DirectMemoryAccessBenchmark.*' \
  -f 1 -wi 5 -i 5 -t 1 -w 1s -r 1s
```

Run the direct-to-heap copy benchmark:

```bash
java -jar target/java25-memory-access-benchmarks.jar \
  'org.apache.fory.benchmark.java25.DirectToHeapCopyBenchmark.*' \
  -f 1 -wi 5 -i 5 -t 1 -w 1s -r 1s
```
