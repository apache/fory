# Fory Dart Benchmark

This benchmark compares serialization and deserialization throughput for Apache Fory and Protocol Buffers in Dart.

## Hardware and Runtime Info

| Key                   | Value                                                             |
| --------------------- | ----------------------------------------------------------------- |
| Timestamp             | 2026-04-13T06:44:47.141825Z                                       |
| OS                    | Version 15.7.2 (Build 24G325)                                     |
| Host                  | MacBook-Pro.local                                                 |
| CPU Cores (Logical)   | 12                                                                |
| Memory (GB)           | 48.00                                                             |
| Dart                  | 3.10.7 (stable) (Tue Dec 23 00:01:57 2025 -0800) on "macos_arm64" |
| Samples per case      | 5                                                                 |
| Warmup per case (s)   | 1.0                                                               |
| Duration per case (s) | 1.5                                                               |

## Throughput Results

![Throughput](throughput.png)

| Datatype         | Operation   |  Fory TPS | Protobuf TPS | Fastest          |
| ---------------- | ----------- | --------: | -----------: | ---------------- |
| Struct           | Serialize   | 3,875,513 |    2,246,269 | fory (1.73x)     |
| Struct           | Deserialize | 4,897,372 |    5,085,804 | protobuf (1.04x) |
| Sample           | Serialize   | 1,686,476 |      579,225 | fory (2.91x)     |
| Sample           | Deserialize | 1,865,579 |      972,355 | fory (1.92x)     |
| MediaContent     | Serialize   |   785,533 |      448,202 | fory (1.75x)     |
| MediaContent     | Deserialize | 1,109,347 |      806,526 | fory (1.38x)     |
| StructList       | Serialize   | 1,262,676 |      402,451 | fory (3.14x)     |
| StructList       | Deserialize | 1,444,735 |      765,407 | fory (1.89x)     |
| SampleList       | Serialize   |   411,793 |       54,598 | fory (7.54x)     |
| SampleList       | Deserialize |   441,751 |      118,623 | fory (3.72x)     |
| MediaContentList | Serialize   |   178,695 |       86,306 | fory (2.07x)     |
| MediaContentList | Deserialize |   266,480 |      156,231 | fory (1.71x)     |

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
