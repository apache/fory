# Fory Dart Benchmark

This benchmark compares serialization and deserialization throughput for Apache Fory, Protocol Buffers, and JSON in Dart.

## Throughput Plot

![Throughput](throughput.png)

## Hardware and Runtime Info

| Key                   | Value                                                             |
| --------------------- | ----------------------------------------------------------------- |
| Timestamp             | 2026-05-08T08:23:10.201764Z                                       |
| OS                    | Version 15.7.2 (Build 24G325)                                     |
| Host                  | MacBook-Pro.local                                                 |
| CPU Cores (Logical)   | 12                                                                |
| Memory (GB)           | 48.00                                                             |
| Dart                  | 3.10.7 (stable) (Tue Dec 23 00:01:57 2025 -0800) on "macos_arm64" |
| Samples per case      | 5                                                                 |
| Warmup per case (s)   | 1.0                                                               |
| Duration per case (s) | 1.5                                                               |

## Throughput Results

| Datatype          | Operation   |  Fory TPS | Protobuf TPS |  JSON TPS | Fastest       |
| ----------------- | ----------- | --------: | -----------: | --------: | ------------- |
| NumericStruct     | Serialize   | 9,007,809 |    1,582,003 |   774,574 | fory (5.69x)  |
| NumericStruct     | Deserialize | 9,039,403 |    3,343,459 | 1,391,036 | fory (2.70x)  |
| Sample            | Serialize   | 2,434,800 |      538,385 |   133,800 | fory (4.52x)  |
| Sample            | Deserialize | 2,362,665 |      909,410 |   239,924 | fory (2.60x)  |
| MediaContent      | Serialize   | 1,167,225 |      423,564 |   223,387 | fory (2.76x)  |
| MediaContent      | Deserialize | 1,987,141 |      770,107 |   254,156 | fory (2.58x)  |
| NumericStructList | Serialize   | 2,551,102 |      283,827 |   139,615 | fory (8.99x)  |
| NumericStructList | Deserialize | 3,028,068 |      530,360 |   265,058 | fory (5.71x)  |
| SampleList        | Serialize   |   568,937 |       47,426 |    25,386 | fory (12.00x) |
| SampleList        | Deserialize |   542,871 |      108,349 |    48,058 | fory (5.01x)  |
| MediaContentList  | Serialize   |   226,507 |       81,828 |    41,780 | fory (2.77x)  |
| MediaContentList  | Deserialize |   458,667 |      139,395 |    50,183 | fory (3.29x)  |

## Serialized Size (bytes)

| Datatype          | Fory | Protobuf | JSON |
| ----------------- | ---: | -------: | ---: |
| NumericStruct     |   78 |       93 |  159 |
| Sample            |  445 |      377 |  791 |
| MediaContent      |  362 |      307 |  619 |
| NumericStructList |  255 |      475 |  816 |
| SampleList        | 1978 |     1900 | 3976 |
| MediaContentList  | 1531 |     1550 | 3122 |
