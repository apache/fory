# Fory Swift Benchmark

This benchmark compares serialization and deserialization throughput for Apache Fory, Protocol Buffers, and JSON in Swift.

## Benchmark Scope

The results below cover the ordinary/xlang cases run by `swift-benchmark`. External-type and carrier comparisons use the separate `swift-external-benchmark` executable and are included only when requested.

## Throughput Plot

![Throughput](throughput.png)

## Hardware and Runtime Info

| Key                   | Value                         |
| --------------------- | ----------------------------- |
| Timestamp             | 2026-08-04T09:52:12Z          |
| OS                    | Version 15.7.2 (Build 24G325) |
| Host                  | MacBook-Pro.local             |
| CPU Cores (Logical)   | 12                            |
| Memory (GB)           | 48.00                         |
| Duration per case (s) | 3                             |

## Throughput Results

| Datatype          | Operation   |   Fory TPS | Protobuf TPS | JSON TPS | Fastest      |
| ----------------- | ----------- | ---------: | -----------: | -------: | ------------ |
| NumericStruct     | Serialize   | 14,759,606 |    7,572,190 |  481,278 | fory (1.95x) |
| NumericStruct     | Deserialize | 18,155,795 |    7,317,942 |  377,802 | fory (2.48x) |
| Sample            | Serialize   |  5,633,231 |    1,380,130 |  102,372 | fory (4.08x) |
| Sample            | Deserialize |  1,509,740 |      916,626 |   52,897 | fory (1.65x) |
| MediaContent      | Serialize   |  2,987,821 |      784,863 |  111,200 | fory (3.81x) |
| MediaContent      | Deserialize |  1,072,345 |      549,726 |   97,799 | fory (1.95x) |
| NumericStructList | Serialize   |  5,515,769 |    1,093,924 |   93,635 | fory (5.04x) |
| NumericStructList | Deserialize |  2,598,609 |      746,527 |   76,161 | fory (3.48x) |
| SampleList        | Serialize   |  1,201,868 |      220,772 |   20,772 | fory (5.44x) |
| SampleList        | Deserialize |    286,949 |      161,192 |   10,777 | fory (1.78x) |
| MediaContentList  | Serialize   |    657,276 |      118,004 |   22,744 | fory (5.57x) |
| MediaContentList  | Deserialize |    215,726 |       99,445 |   19,521 | fory (2.17x) |

## Serialized Size (bytes)

| Datatype          | Fory | Protobuf | JSON |
| ----------------- | ---: | -------: | ---: |
| NumericStruct     |   78 |       93 |  159 |
| Sample            |  445 |      375 |  696 |
| MediaContent      |  362 |      301 |  608 |
| NumericStructList |  255 |      475 |  816 |
| SampleList        | 1978 |     1890 | 3501 |
| MediaContentList  | 1531 |     1520 | 3067 |
