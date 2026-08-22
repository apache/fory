# JavaScript Benchmark Performance Report

_Generated on 2026-05-08 17:55:12_

## How to Generate This Report

```bash
cd benchmarks/javascript
./run.sh
```

## Benchmark Semantics

The timed serializer loops use serializer-native typed values. Fory receives the pre-normalized Fory value used by its schema, protobuf receives the prebuilt protobuf-shaped value, and JSON receives the benchmark JavaScript object. Protobuf timings do not include `toProto`, `fromProto`, `protobufjs.create`, or `toObject` conversion work.

## Benchmark Plot

The plot shows throughput (ops/sec); higher is better.

![Throughput](throughput.png)

## Hardware & OS Info

| Key                        | Value                    |
| -------------------------- | ------------------------ |
| OS                         | Darwin 24.6.0            |
| Machine                    | arm64                    |
| Processor                  | arm                      |
| CPU Cores (Physical)       | 12                       |
| CPU Cores (Logical)        | 12                       |
| Total RAM (GB)             | 48.0                     |
| Benchmark Date             | 2026-05-08T08:07:36.073Z |
| CPU Cores (from benchmark) | 12                       |
| Node.js                    | v22.20.0                 |
| V8                         | 12.4.254.21-node.33      |

## Benchmark Results

### Timing Results (nanoseconds)

| Datatype          | Operation   | fory (ns) | protobuf (ns) | json (ns) | Fastest  |
| ----------------- | ----------- | --------- | ------------- | --------- | -------- |
| NumericStruct     | Serialize   | 76.0      | 613.0         | 496.0     | fory     |
| NumericStruct     | Deserialize | 56.9      | 94.8          | 333.0     | fory     |
| Sample            | Serialize   | 318.0     | 2016.6        | 1409.3    | fory     |
| Sample            | Deserialize | 496.0     | 902.5         | 1609.6    | fory     |
| MediaContent      | Serialize   | 494.1     | 1358.5        | 803.5     | fory     |
| MediaContent      | Deserialize | 539.3     | 628.3         | 1134.3    | fory     |
| NumericStructList | Serialize   | 195.3     | 3019.3        | 2013.5    | fory     |
| NumericStructList | Deserialize | 183.7     | 606.9         | 1944.0    | fory     |
| SampleList        | Serialize   | 1681.9    | 19346.7       | 11870.3   | fory     |
| SampleList        | Deserialize | 2571.9    | 5730.6        | 9074.5    | fory     |
| MediaContentList  | Serialize   | 2785.9    | 7616.6        | 3611.5    | fory     |
| MediaContentList  | Deserialize | 3709.7    | 3018.6        | 5294.5    | protobuf |

### Throughput Results (ops/sec)

| Datatype          | Operation   | fory TPS   | protobuf TPS | json TPS  | Fastest  |
| ----------------- | ----------- | ---------- | ------------ | --------- | -------- |
| NumericStruct     | Serialize   | 13,162,466 | 1,631,271    | 2,016,097 | fory     |
| NumericStruct     | Deserialize | 17,568,418 | 10,543,763   | 3,002,971 | fory     |
| Sample            | Serialize   | 3,144,194  | 495,893      | 709,593   | fory     |
| Sample            | Deserialize | 2,015,942  | 1,108,010    | 621,285   | fory     |
| MediaContent      | Serialize   | 2,023,719  | 736,097      | 1,244,512 | fory     |
| MediaContent      | Deserialize | 1,854,348  | 1,591,617    | 881,572   | fory     |
| NumericStructList | Serialize   | 5,121,376  | 331,201      | 496,645   | fory     |
| NumericStructList | Deserialize | 5,444,504  | 1,647,728    | 514,414   | fory     |
| SampleList        | Serialize   | 594,551    | 51,688       | 84,244    | fory     |
| SampleList        | Deserialize | 388,820    | 174,503      | 110,199   | fory     |
| MediaContentList  | Serialize   | 358,954    | 131,293      | 276,891   | fory     |
| MediaContentList  | Deserialize | 269,561    | 331,275      | 188,876   | protobuf |

### Serialized Data Sizes (bytes)

| Datatype          | fory | protobuf | json |
| ----------------- | ---- | -------- | ---- |
| NumericStruct     | 78   | 93       | 159  |
| Sample            | 445  | 377      | 724  |
| MediaContent      | 388  | 307      | 596  |
| NumericStructList | 255  | 475      | 817  |
| SampleList        | 1978 | 1900     | 3642 |
| MediaContentList  | 1661 | 1550     | 3009 |
