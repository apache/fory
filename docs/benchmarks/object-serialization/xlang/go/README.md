# Go Serialization Benchmark Report

Generated: 2026-05-08 17:55:12

## Performance Chart

![Throughput](throughput.png)

## System Information

- **OS**: Darwin 24.6.0
- **Architecture**: arm64
- **Python**: 3.10.8

## Performance Summary

| Data Type         | Operation   | Fory (ops/s) | Protobuf (ops/s) | Msgpack (ops/s) | Fory vs PB | Fory vs MP |
| ----------------- | ----------- | ------------ | ---------------- | --------------- | ---------- | ---------- |
| NumericStruct     | Serialize   | 12.74M       | 7.16M            | 3.63M           | 1.78x      | 3.51x      |
| NumericStruct     | Deserialize | 10.63M       | 8.40M            | 1.78M           | 1.27x      | 5.98x      |
| Sample            | Serialize   | 7.16M        | 2.53M            | 646K            | 2.84x      | 11.10x     |
| Sample            | Deserialize | 3.27M        | 2.10M            | 343K            | 1.56x      | 9.54x      |
| MediaContent      | Serialize   | 3.74M        | 1.75M            | 1.14M           | 2.14x      | 3.27x      |
| MediaContent      | Deserialize | 2.03M        | 1.23M            | 646K            | 1.66x      | 3.15x      |
| NumericStructList | Serialize   | 1.10M        | 386K             | 201K            | 2.84x      | 5.44x      |
| NumericStructList | Deserialize | 1.09M        | 368K             | 103K            | 2.96x      | 10.54x     |
| SampleList        | Serialize   | 496K         | 126K             | 36K             | 3.93x      | 13.83x     |
| SampleList        | Deserialize | 195K         | 96K              | 17K             | 2.04x      | 11.73x     |
| MediaContentList  | Serialize   | 250K         | 91K              | 57K             | 2.73x      | 4.38x      |
| MediaContentList  | Deserialize | 112K         | 74K              | 31K             | 1.53x      | 3.65x      |

## Detailed Timing (ns/op)

| Data Type         | Operation   | Fory   | Protobuf | Msgpack |
| ----------------- | ----------- | ------ | -------- | ------- |
| NumericStruct     | Serialize   | 78.5   | 139.6    | 275.5   |
| NumericStruct     | Deserialize | 94.0   | 119.0    | 562.5   |
| Sample            | Serialize   | 139.6  | 395.9    | 1549.0  |
| Sample            | Deserialize | 306.0  | 475.9    | 2919.0  |
| MediaContent      | Serialize   | 267.3  | 571.6    | 875.1   |
| MediaContent      | Deserialize | 492.4  | 815.8    | 1549.0  |
| NumericStructList | Serialize   | 912.8  | 2594.0   | 4970.0  |
| NumericStructList | Deserialize | 919.9  | 2721.0   | 9698.0  |
| SampleList        | Serialize   | 2018.0 | 7927.0   | 27909.0 |
| SampleList        | Deserialize | 5126.0 | 10460.0  | 60118.0 |
| MediaContentList  | Serialize   | 4006.0 | 10939.0  | 17553.0 |
| MediaContentList  | Deserialize | 8893.0 | 13588.0  | 32439.0 |

### Serialized Data Sizes (bytes)

| Data Type         | Fory | Protobuf | Msgpack |
| ----------------- | ---- | -------- | ------- |
| NumericStruct     | 78   | 93       | 88      |
| Sample            | 445  | 375      | 524     |
| MediaContent      | 340  | 301      | 400     |
| NumericStructList | 819  | 1900     | 1766    |
| SampleList        | 7599 | 7560     | 10486   |
| MediaContentList  | 5774 | 6080     | 8006    |
