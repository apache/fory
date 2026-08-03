# Python Benchmark Performance Report

_Generated on 2026-05-08 17:54:45_

## How to Generate This Report

```bash
cd benchmarks/python
./run.sh
```

## Benchmark Plot

The plot shows throughput (ops/sec); higher is better.

![Throughput](throughput.png)

## Hardware & OS Info

| Key                   | Value                        |
| --------------------- | ---------------------------- |
| OS                    | Darwin 24.6.0                |
| Machine               | arm64                        |
| Processor             | arm                          |
| Python                | 3.10.8                       |
| CPU Cores (Physical)  | 12                           |
| CPU Cores (Logical)   | 12                           |
| Total RAM (GB)        | 48.0                         |
| Python Implementation | CPython                      |
| Benchmark Platform    | macOS-15.7.2-arm64-arm-64bit |

## Benchmark Configuration

| Key        | Value |
| ---------- | ----- |
| warmup     | 3     |
| iterations | 15    |
| repeat     | 5     |
| number     | 1000  |
| list_size  | 5     |

## Benchmark Results

### Timing Results (nanoseconds)

| Datatype          | Operation   | fory (ns) | protobuf (ns) | pickle (ns) | Fastest |
| ----------------- | ----------- | --------- | ------------- | ----------- | ------- |
| NumericStruct     | Serialize   | 491.4     | 802.3         | 1119.8      | fory    |
| NumericStruct     | Deserialize | 522.2     | 1211.6        | 1788.7      | fory    |
| Sample            | Serialize   | 1096.4    | 3315.8        | 10185.2     | fory    |
| Sample            | Deserialize | 2772.0    | 6659.7        | 7061.9      | fory    |
| MediaContent      | Serialize   | 989.2     | 3433.2        | 4392.7      | fory    |
| MediaContent      | Deserialize | 1518.7    | 4381.2        | 4305.1      | fory    |
| NumericStructList | Serialize   | 1111.2    | 4707.9        | 3235.8      | fory    |
| NumericStructList | Deserialize | 1891.7    | 6891.0        | 3974.9      | fory    |
| SampleList        | Serialize   | 3447.2    | 18719.1       | 32125.7     | fory    |
| SampleList        | Deserialize | 13131.6   | 35264.2       | 24154.4     | fory    |
| MediaContentList  | Serialize   | 2996.5    | 17597.4       | 11087.8     | fory    |
| MediaContentList  | Deserialize | 6228.7    | 21562.0       | 10459.3     | fory    |

### Throughput Results (ops/sec)

| Datatype          | Operation   | fory TPS  | protobuf TPS | pickle TPS | Fastest |
| ----------------- | ----------- | --------- | ------------ | ---------- | ------- |
| NumericStruct     | Serialize   | 2,035,025 | 1,246,379    | 893,009    | fory    |
| NumericStruct     | Deserialize | 1,915,112 | 825,344      | 559,055    | fory    |
| Sample            | Serialize   | 912,072   | 301,590      | 98,182     | fory    |
| Sample            | Deserialize | 360,751   | 150,158      | 141,605    | fory    |
| MediaContent      | Serialize   | 1,010,939 | 291,275      | 227,652    | fory    |
| MediaContent      | Deserialize | 658,462   | 228,247      | 232,281    | fory    |
| NumericStructList | Serialize   | 899,960   | 212,407      | 309,040    | fory    |
| NumericStructList | Deserialize | 528,636   | 145,116      | 251,580    | fory    |
| SampleList        | Serialize   | 290,092   | 53,421       | 31,128     | fory    |
| SampleList        | Deserialize | 76,152    | 28,357       | 41,400     | fory    |
| MediaContentList  | Serialize   | 333,720   | 56,826       | 90,189     | fory    |
| MediaContentList  | Deserialize | 160,547   | 46,378       | 95,609     | fory    |

### Serialized Data Sizes (bytes)

| Datatype          | fory | protobuf | pickle |
| ----------------- | ---- | -------- | ------ |
| NumericStruct     | 78   | 93       | 169    |
| Sample            | 445  | 375      | 1176   |
| MediaContent      | 366  | 301      | 624    |
| NumericStructList | 219  | 475      | 582    |
| SampleList        | 1914 | 1890     | 3546   |
| MediaContentList  | 1614 | 1520     | 1415   |
