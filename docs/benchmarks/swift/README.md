# Fory Swift Benchmark

This benchmark compares serialization and deserialization throughput for Apache Fory, Protocol Buffers, and JSON in Swift.

## Benchmark Products

The ordinary/xlang cases are built by `swift-benchmark`. External-type and
carrier comparisons are built by the separate `swift-external-benchmark`
product, so building the ordinary product does not compile those models or
serializer specializations.

## Throughput Plot

![Throughput](throughput.png)

## Hardware and Runtime Info

| Key                   | Value                         |
| --------------------- | ----------------------------- |
| Timestamp             | 2026-05-08T09:05:32Z          |
| OS                    | Version 15.7.2 (Build 24G325) |
| Host                  | macbook-pro.local             |
| CPU Cores (Logical)   | 12                            |
| Memory (GB)           | 48.00                         |
| Duration per case (s) | 3                             |

## Throughput Results

| Datatype          | Operation   |   Fory TPS | Protobuf TPS | JSON TPS | Fastest      |
| ----------------- | ----------- | ---------: | -----------: | -------: | ------------ |
| NumericStruct     | Serialize   |  9,435,623 |    6,175,939 |  408,960 | fory (1.53x) |
| NumericStruct     | Deserialize | 11,037,225 |    6,842,676 |  328,302 | fory (1.61x) |
| Sample            | Serialize   |  3,596,835 |    1,257,100 |   79,781 | fory (2.86x) |
| Sample            | Deserialize |    982,255 |      733,588 |   41,274 | fory (1.34x) |
| MediaContent      | Serialize   |  1,561,376 |      609,896 |   98,677 | fory (2.56x) |
| MediaContent      | Deserialize |    523,836 |      395,202 |   70,528 | fory (1.33x) |
| NumericStructList | Serialize   |  2,910,846 |      918,363 |   82,965 | fory (3.17x) |
| NumericStructList | Deserialize |  2,436,636 |      701,656 |   69,353 | fory (3.47x) |
| SampleList        | Serialize   |    694,557 |      202,040 |   16,679 | fory (3.44x) |
| SampleList        | Deserialize |    187,109 |      131,947 |    8,236 | fory (1.42x) |
| MediaContentList  | Serialize   |    348,238 |       98,007 |   18,698 | fory (3.55x) |
| MediaContentList  | Deserialize |    104,990 |       74,422 |   16,298 | fory (1.41x) |

## Serialized Size (bytes)

| Datatype          | Fory | Protobuf | JSON |
| ----------------- | ---: | -------: | ---: |
| NumericStruct     |   78 |       93 |  159 |
| Sample            |  445 |      375 |  696 |
| MediaContent      |  362 |      301 |  608 |
| NumericStructList |  255 |      475 |  816 |
| SampleList        | 1978 |     1890 | 3501 |
| MediaContentList  | 1531 |     1520 | 3067 |
