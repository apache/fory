# C++ Benchmark Performance Report

_Generated on 2025-12-15 23:03:06_

## How to Generate This Report

```bash
cd benchmarks/cpp_benchmark/build
./fory_benchmark --benchmark_format=json --benchmark_out=benchmark_results.json
cd ..
python benchmark_report.py --json-file build/benchmark_results.json --output-dir report
```

## Hardware & OS Info

| Key                        | Value                     |
| -------------------------- | ------------------------- |
| OS                         | Darwin 24.6.0             |
| Machine                    | arm64                     |
| Processor                  | arm                       |
| CPU Cores (Physical)       | 12                        |
| CPU Cores (Logical)        | 12                        |
| Total RAM (GB)             | 48.0                      |
| Benchmark Date             | 2025-12-15T23:02:49+08:00 |
| CPU Cores (from benchmark) | 12                        |

## Benchmark Plots

<p align="center">
<img src="throughput.png" width="90%">
</p>

## Benchmark Results

### Timing Results (nanoseconds)

| Datatype     | Operation   | Fory (ns) | Protobuf (ns) | Faster      |
| ------------ | ----------- | --------- | ------------- | ----------- |
| Mediacontent | Serialize   | 443.5     | 1982.5        | Fory (4.5x) |
| Mediacontent | Deserialize | 1349.0    | 2525.2        | Fory (1.9x) |
| Sample       | Serialize   | 235.4     | 309.7         | Fory (1.3x) |
| Sample       | Deserialize | 1068.7    | 1397.0        | Fory (1.3x) |
| Struct       | Serialize   | 109.4     | 170.0         | Fory (1.6x) |
| Struct       | Deserialize | 129.1     | 161.2         | Fory (1.2x) |

### Throughput Results (ops/sec)

| Datatype     | Operation   | Fory TPS  | Protobuf TPS | Faster      |
| ------------ | ----------- | --------- | ------------ | ----------- |
| Mediacontent | Serialize   | 2,254,915 | 504,410      | Fory (4.5x) |
| Mediacontent | Deserialize | 741,303   | 396,013      | Fory (1.9x) |
| Sample       | Serialize   | 4,248,973 | 3,229,102    | Fory (1.3x) |
| Sample       | Deserialize | 935,709   | 715,837      | Fory (1.3x) |
| Struct       | Serialize   | 9,143,618 | 5,881,005    | Fory (1.6x) |
| Struct       | Deserialize | 7,746,787 | 6,202,164    | Fory (1.2x) |

### Serialized Data Sizes (bytes)

| Datatype | Fory | Protobuf |
| -------- | ---- | -------- |
| Struct   | 32   | 61       |
| Sample   | 384  | 375      |
