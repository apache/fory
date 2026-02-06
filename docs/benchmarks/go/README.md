# Go Serialization Benchmark Report

Generated: 2026-02-06 11:26:58

## System Information

- **OS**: Darwin 24.6.0
- **Architecture**: arm64
- **Python**: 3.10.8

## Performance Charts

![Benchmark](benchmark_combined.png)

## Performance Summary

| Data Type        | Operation   | Fory (ops/s) | Protobuf (ops/s) | Msgpack (ops/s) | Fory vs PB | Fory vs MP |
| ---------------- | ----------- | ------------ | ---------------- | --------------- | ---------- | ---------- |
| Struct           | Serialize   | 15.16M       | 10.23M           | 5.41M           | 1.48x      | 2.80x      |
| Struct           | Deserialize | 12.10M       | 11.01M           | 3.23M           | 1.10x      | 3.75x      |
| Structlist       | Serialize   | 1.58M        | 561K             | 299K            | 2.82x      | 5.28x      |
| Structlist       | Deserialize | 1.10M        | 529K             | 175K            | 2.09x      | 6.30x      |
| Sample           | Serialize   | 7.28M        | 2.72M            | 670K            | 2.68x      | 10.87x     |
| Sample           | Deserialize | 3.79M        | 2.37M            | 376K            | 1.60x      | 10.09x     |
| Samplelist       | Serialize   | 510K         | 141K             | 38K             | 3.61x      | 13.34x     |
| Samplelist       | Deserialize | 236K         | 107K             | 19K             | 2.20x      | 12.66x     |
| Mediacontent     | Serialize   | 3.72M        | 2.12M            | 1.29M           | 1.75x      | 2.88x      |
| Mediacontent     | Deserialize | 2.34M        | 1.81M            | 698K            | 1.30x      | 3.35x      |
| Mediacontentlist | Serialize   | 268K         | 110K             | 72K             | 2.44x      | 3.72x      |
| Mediacontentlist | Deserialize | 138K         | 87K              | 36K             | 1.58x      | 3.86x      |

## Detailed Timing (ns/op)

| Data Type        | Operation   | Fory   | Protobuf | Msgpack |
| ---------------- | ----------- | ------ | -------- | ------- |
| Struct           | Serialize   | 66.0   | 97.8     | 184.9   |
| Struct           | Deserialize | 82.7   | 90.9     | 309.6   |
| Structlist       | Serialize   | 632.8  | 1783.0   | 3340.0  |
| Structlist       | Deserialize | 906.4  | 1891.0   | 5709.0  |
| Sample           | Serialize   | 137.3  | 367.3    | 1492.0  |
| Sample           | Deserialize | 263.6  | 422.2    | 2661.0  |
| Samplelist       | Serialize   | 1962.0 | 7087.0   | 26169.0 |
| Samplelist       | Deserialize | 4234.0 | 9321.0   | 53615.0 |
| Mediacontent     | Serialize   | 268.8  | 471.1    | 773.7   |
| Mediacontent     | Deserialize | 426.9  | 553.1    | 1432.0  |
| Mediacontentlist | Serialize   | 3736.0 | 9107.0   | 13911.0 |
| Mediacontentlist | Deserialize | 7247.0 | 11435.0  | 27975.0 |

### Serialized Data Sizes (bytes)

| Data Type        | Fory | Protobuf | Msgpack |
| ---------------- | ---- | -------- | ------- |
| Struct           | 58   | 61       | 57      |
| Sample           | 446  | 375      | 524     |
| MediaContent     | 342  | 301      | 400     |
| StructList       | 560  | 1260     | 1146    |
| SampleList       | 7600 | 7560     | 10486   |
| MediaContentList | 5776 | 6080     | 8006    |
