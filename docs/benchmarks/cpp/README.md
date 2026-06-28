# C++ Benchmark Performance Report

_Generated on 2026-06-12 16:14:04_

## How to Generate This Report

```bash
cd benchmarks/cpp/build
./fory_benchmark --benchmark_format=json --benchmark_out=benchmark_results.json
cd ..
python benchmark_report.py --json-file build/benchmark_results.json --output-dir report
```

## Benchmark Plot

The plot shows throughput (ops/sec); higher is better.

![Throughput](throughput.png)

## Hardware & OS Info

| Key                        | Value                     |
| -------------------------- | ------------------------- |
| OS                         | Darwin 24.6.0             |
| Machine                    | arm64                     |
| Processor                  | arm                       |
| CPU Cores (Physical)       | 12                        |
| CPU Cores (Logical)        | 12                        |
| Total RAM (GB)             | 48.0                      |
| Benchmark Date             | 2026-06-12T16:13:27+08:00 |
| CPU Cores (from benchmark) | 12                        |

## Benchmark Results

### Timing Results (nanoseconds)

| Datatype          | Operation   | fory (ns) | protobuf (ns) | msgpack (ns) | Fastest |
| ----------------- | ----------- | --------- | ------------- | ------------ | ------- |
| NumericStruct     | Serialize   | 25.7      | 48.2          | 85.4         | fory    |
| NumericStruct     | Deserialize | 25.1      | 31.8          | 887.2        | fory    |
| Sample            | Serialize   | 60.6      | 96.4          | 361.6        | fory    |
| Sample            | Deserialize | 176.7     | 397.0         | 2031.6       | fory    |
| MediaContent      | Serialize   | 113.5     | 471.0         | 290.4        | fory    |
| MediaContent      | Deserialize | 247.3     | 641.9         | 2015.4       | fory    |
| NumericStructList | Serialize   | 83.3      | 372.9         | 446.9        | fory    |
| NumericStructList | Deserialize | 158.1     | 268.2         | 4342.4       | fory    |
| SampleList        | Serialize   | 258.8     | 2829.7        | 2602.3       | fory    |
| SampleList        | Deserialize | 1001.7    | 2794.4        | 12220.7      | fory    |
| MediaContentList  | Serialize   | 504.2     | 2589.8        | 1549.5       | fory    |
| MediaContentList  | Deserialize | 1258.6    | 3620.3        | 10263.4      | fory    |

### Throughput Results (ops/sec)

| Datatype          | Operation   | fory TPS   | protobuf TPS | msgpack TPS | Fastest |
| ----------------- | ----------- | ---------- | ------------ | ----------- | ------- |
| NumericStruct     | Serialize   | 38,845,461 | 20,734,963   | 11,707,994  | fory    |
| NumericStruct     | Deserialize | 39,872,217 | 31,443,829   | 1,127,092   | fory    |
| Sample            | Serialize   | 16,496,488 | 10,372,657   | 2,765,312   | fory    |
| Sample            | Deserialize | 5,660,852  | 2,518,926    | 492,232     | fory    |
| MediaContent      | Serialize   | 8,808,084  | 2,122,926    | 3,443,519   | fory    |
| MediaContent      | Deserialize | 4,043,028  | 1,557,819    | 496,175     | fory    |
| NumericStructList | Serialize   | 11,999,598 | 2,681,661    | 2,237,536   | fory    |
| NumericStructList | Deserialize | 6,323,730  | 3,728,133    | 230,285     | fory    |
| SampleList        | Serialize   | 3,864,068  | 353,391      | 384,276     | fory    |
| SampleList        | Deserialize | 998,326    | 357,854      | 81,828      | fory    |
| MediaContentList  | Serialize   | 1,983,502  | 386,135      | 645,372     | fory    |
| MediaContentList  | Deserialize | 794,544    | 276,221      | 97,434      | fory    |

### Serialized Data Sizes (bytes)

| Datatype          | fory | protobuf | msgpack |
| ----------------- | ---- | -------- | ------- |
| NumericStruct     | 78   | 93       | 87      |
| Sample            | 445  | 375      | 530     |
| MediaContent      | 362  | 301      | 480     |
| NumericStructList | 255  | 475      | 449     |
| SampleList        | 1978 | 1890     | 2664    |
| MediaContentList  | 1531 | 1520     | 2421    |
