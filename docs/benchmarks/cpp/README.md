# C++ Benchmark Performance Report

_Generated on 2026-04-29 21:16:35_

## How to Generate This Report

```bash
cd benchmarks/cpp/build
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
| Benchmark Date             | 2026-04-29T21:15:49+08:00 |
| CPU Cores (from benchmark) | 12                        |

## Benchmark Plots

All class-level plots below show throughput (ops/sec).

### Throughput

![Throughput](throughput.png)

### Struct

![Struct](struct.png)

### Sample

![Sample](sample.png)

### Mediacontent

![Mediacontent](mediacontent.png)

### Structlist

![Structlist](structlist.png)

### Samplelist

![Samplelist](samplelist.png)

### Mediacontentlist

![Mediacontentlist](mediacontentlist.png)

## Benchmark Results

### Timing Results (nanoseconds)

| Datatype         | Operation   | fory (ns) | protobuf (ns) | msgpack (ns) | Fastest |
| ---------------- | ----------- | --------- | ------------- | ------------ | ------- |
| Struct           | Serialize   | 25.6      | 34.3          | 54.3         | fory    |
| Struct           | Deserialize | 23.1      | 25.5          | 769.9        | fory    |
| Sample           | Serialize   | 68.4      | 96.6          | 317.8        | fory    |
| Sample           | Deserialize | 326.2     | 662.5         | 2633.8       | fory    |
| MediaContent     | Serialize   | 119.5     | 854.1         | 292.7        | fory    |
| MediaContent     | Deserialize | 391.2     | 1207.2        | 2809.7       | fory    |
| StructList       | Serialize   | 72.6      | 421.4         | 286.4        | fory    |
| StructList       | Deserialize | 122.7     | 364.1         | 3400.5       | fory    |
| SampleList       | Serialize   | 284.0     | 4898.1        | 1549.8       | fory    |
| SampleList       | Deserialize | 1824.8    | 5047.4        | 13206.2      | fory    |
| MediaContentList | Serialize   | 477.8     | 4788.9        | 1461.9       | fory    |
| MediaContentList | Deserialize | 2053.4    | 6561.8        | 13702.4      | fory    |

### Throughput Results (ops/sec)

| Datatype         | Operation   | fory TPS   | protobuf TPS | msgpack TPS | Fastest |
| ---------------- | ----------- | ---------- | ------------ | ----------- | ------- |
| Struct           | Serialize   | 39,028,134 | 29,190,083   | 18,400,879  | fory    |
| Struct           | Deserialize | 43,236,159 | 39,162,796   | 1,298,861   | fory    |
| Sample           | Serialize   | 14,612,190 | 10,356,947   | 3,146,384   | fory    |
| Sample           | Deserialize | 3,065,887  | 1,509,472    | 379,673     | fory    |
| MediaContent     | Serialize   | 8,364,768  | 1,170,804    | 3,416,327   | fory    |
| MediaContent     | Deserialize | 2,556,154  | 828,370      | 355,913     | fory    |
| StructList       | Serialize   | 13,768,856 | 2,373,115    | 3,491,609   | fory    |
| StructList       | Deserialize | 8,149,855  | 2,746,704    | 294,075     | fory    |
| SampleList       | Serialize   | 3,521,702  | 204,162      | 645,233     | fory    |
| SampleList       | Deserialize | 547,995    | 198,123      | 75,722      | fory    |
| MediaContentList | Serialize   | 2,092,938  | 208,817      | 684,050     | fory    |
| MediaContentList | Deserialize | 486,993    | 152,398      | 72,980      | fory    |

### Serialized Data Sizes (bytes)

| Datatype         | fory | protobuf | msgpack |
| ---------------- | ---- | -------- | ------- |
| Struct           | 58   | 61       | 55      |
| Sample           | 446  | 375      | 530     |
| MediaContent     | 365  | 301      | 480     |
| StructList       | 184  | 315      | 289     |
| SampleList       | 1980 | 1890     | 2664    |
| MediaContentList | 1535 | 1520     | 2421    |
