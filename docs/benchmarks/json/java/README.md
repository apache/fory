# Java JSON Benchmark Report

The benchmark compares fory-json, Jackson, and Gson using the same data. The String group excludes
UTF-8 conversion. The UTF-8 bytes group uses direct byte-array APIs when available. Gson includes
String-to-UTF-8 encoding and UTF-8-to-String decoding.

```bash
cd benchmarks/java
./run_json.sh --libs fory-json,jackson,gson
```

- Benchmark date: `2026-07-28`
- Source commit: `bd0ca9825b3206b1a53bf27bfb180a551ab39236`
- Platform: Apple M4 Pro, macOS 15.7.2, arm64
- JDK: `26.0.1`
- VM: `OpenJDK 64-Bit Server VM`
- JMH: `1.37`
- Warmup: 3 iterations × `2 s`
- Measurement: 5 iterations × `2 s`
- Forks: 1; threads: 1
- Mode: throughput; higher is better

## String

![Java JSON String benchmark throughput](string_throughput.png)

## UTF-8 Bytes

![Java JSON UTF-8 bytes benchmark throughput](utf8_bytes_throughput.png)

## Results

| Representation | Operation   | fory-json ops/sec | jackson ops/sec | gson ops/sec | Fastest   |
| -------------- | ----------- | ----------------: | --------------: | -----------: | --------- |
| String         | Serialize   |         7,387,465 |       2,049,368 |    1,084,042 | fory-json |
| String         | Deserialize |         2,897,955 |       1,074,885 |      902,772 | fory-json |
| UTF-8 bytes    | Serialize   |        10,375,498 |       1,868,614 |    1,037,211 | fory-json |
| UTF-8 bytes    | Deserialize |         3,077,158 |       1,268,397 |      933,079 | fory-json |
