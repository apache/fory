# Fory Dart Benchmark

This benchmark compares serialization and deserialization throughput for Apache Fory and Protocol Buffers in Dart.

## Hardware and Runtime Info

| Key                   | Value                                                            |
| --------------------- | ---------------------------------------------------------------- |
| Timestamp             | 2026-04-23T12:21:28Z                                             |
| OS                    | Version 26.2 (Build 25C56)                                       |
| Host                  | Macbook-Air.local                                                |
| CPU Cores (Logical)   | 8                                                                |
| Memory (GB)           | 8.00                                                             |
| Dart                  | 3.10.4 (stable) (Tue Dec 9 00:01:55 2025 -0800) on "macos_arm64" |
| Samples per case      | 5                                                                |
| Warmup per case (s)   | 1.0                                                              |
| Duration per case (s) | 1.5                                                              |

## Throughput Results

![Throughput](throughput.png)

| Datatype         | Operation   |  Fory TPS | Protobuf TPS | Fastest      |
| ---------------- | ----------- | --------: | -----------: | ------------ |
| Struct           | Serialize   | 4,696,615 |    1,998,625 | fory (2.35x) |
| Struct           | Deserialize | 5,815,245 |    4,173,568 | fory (1.39x) |
| Sample           | Serialize   | 1,744,871 |      481,801 | fory (3.62x) |
| Sample           | Deserialize | 2,007,877 |      780,317 | fory (2.57x) |
| MediaContent     | Serialize   |   944,111 |      398,324 | fory (2.37x) |
| MediaContent     | Deserialize | 1,457,065 |      675,724 | fory (2.16x) |
| StructList       | Serialize   | 1,981,716 |      351,853 | fory (5.63x) |
| StructList       | Deserialize | 2,261,436 |      596,027 | fory (3.79x) |
| SampleList       | Serialize   |   426,153 |       46,590 | fory (9.15x) |
| SampleList       | Deserialize |   479,900 |       99,694 | fory (4.81x) |
| MediaContentList | Serialize   |   220,342 |       76,330 | fory (2.89x) |
| MediaContentList | Deserialize |   341,839 |      131,730 | fory (2.60x) |

## Serialized Size (bytes)

| Datatype         | Fory | Protobuf |
| ---------------- | ---: | -------: |
| Struct           |   58 |       61 |
| Sample           |  446 |      377 |
| MediaContent     |  365 |      307 |
| StructList       |  184 |      315 |
| SampleList       | 1980 |     1900 |
| MediaContentList | 1535 |     1550 |

## Per-workload Plots

### Struct

![Struct](struct.png)

### Sample

![Sample](sample.png)

### MediaContent

![MediaContent](mediacontent.png)

### StructList

![StructList](structlist.png)

### SampleList

![SampleList](samplelist.png)

### MediaContentList

![MediaContentList](mediacontentlist.png)
