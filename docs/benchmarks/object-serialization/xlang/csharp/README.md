# C# Benchmark Performance Report

_Generated on 2026-05-08 17:54:45_

## How to Generate This Report

```bash
cd benchmarks/csharp
dotnet run -c Release --project ./Fory.CSharpBenchmark.csproj -- --output build/benchmark_results.json
python3 benchmark_report.py --json-file build/benchmark_results.json --output-dir report
```

## Benchmark Plot

The plot shows throughput (ops/sec); higher is better.

![Throughput](throughput.png)

## Hardware & OS Info

| Key                                | Value                                                                                                                        |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| OS                                 | Darwin 24.6.0 Darwin Kernel Version 24.6.0: Wed Oct 15 21:12:15 PDT 2025; root:xnu-11417.140.69.703.14~1/RELEASE_ARM64_T6041 |
| OS Architecture                    | Arm64                                                                                                                        |
| Machine                            | Arm64                                                                                                                        |
| Runtime Version                    | 8.0.24                                                                                                                       |
| Benchmark Date (UTC)               | 2026-05-08T08:17:48.7871870Z                                                                                                 |
| Warmup Seconds                     | 1                                                                                                                            |
| Duration Seconds                   | 3                                                                                                                            |
| CPU Logical Cores (from benchmark) | 12                                                                                                                           |
| CPU Cores (Physical)               | 12                                                                                                                           |
| CPU Cores (Logical)                | 12                                                                                                                           |
| Total RAM (GB)                     | 48.0                                                                                                                         |

## Benchmark Coverage

| Key                 | Value                                                                  |
| ------------------- | ---------------------------------------------------------------------- |
| Cases in input JSON | 36 / 36                                                                |
| Serializers         | fory, msgpack, protobuf                                                |
| Datatypes           | struct, sample, mediacontent, structlist, samplelist, mediacontentlist |
| Operations          | serialize, deserialize                                                 |

## Benchmark Results

### Timing Results (nanoseconds)

| Datatype          | Operation   | fory (ns) | protobuf (ns) | msgpack (ns) | Fastest |
| ----------------- | ----------- | --------- | ------------- | ------------ | ------- |
| NumericStruct     | Serialize   | 50.3      | 170.8         | 107.8        | fory    |
| NumericStruct     | Deserialize | 82.4      | 252.0         | 143.4        | fory    |
| Sample            | Serialize   | 263.2     | 607.1         | 377.1        | fory    |
| Sample            | Deserialize | 199.4     | 1191.7        | 785.6        | fory    |
| MediaContent      | Serialize   | 379.7     | 509.6         | 417.6        | fory    |
| MediaContent      | Deserialize | 450.3     | 846.6         | 791.4        | fory    |
| NumericStructList | Serialize   | 183.7     | 641.8         | 447.8        | fory    |
| NumericStructList | Deserialize | 288.3     | 974.3         | 702.1        | fory    |
| SampleList        | Serialize   | 1205.7    | 3559.1        | 1864.1       | fory    |
| SampleList        | Deserialize | 895.1     | 5710.3        | 2757.4       | fory    |
| MediaContentList  | Serialize   | 1495.4    | 2473.6        | 1812.4       | fory    |
| MediaContentList  | Deserialize | 1946.7    | 3789.3        | 3778.4       | fory    |

### Throughput Results (ops/sec)

| Datatype          | Operation   | fory TPS   | protobuf TPS | msgpack TPS | Fastest |
| ----------------- | ----------- | ---------- | ------------ | ----------- | ------- |
| NumericStruct     | Serialize   | 19,881,457 | 5,853,473    | 9,276,378   | fory    |
| NumericStruct     | Deserialize | 12,137,374 | 3,968,585    | 6,973,504   | fory    |
| Sample            | Serialize   | 3,799,418  | 1,647,119    | 2,652,142   | fory    |
| Sample            | Deserialize | 5,016,006  | 839,129      | 1,272,975   | fory    |
| MediaContent      | Serialize   | 2,633,704  | 1,962,428    | 2,394,549   | fory    |
| MediaContent      | Deserialize | 2,220,537  | 1,181,222    | 1,263,568   | fory    |
| NumericStructList | Serialize   | 5,445,002  | 1,558,156    | 2,232,996   | fory    |
| NumericStructList | Deserialize | 3,469,207  | 1,026,402    | 1,424,322   | fory    |
| SampleList        | Serialize   | 829,415    | 280,973      | 536,448     | fory    |
| SampleList        | Deserialize | 1,117,133  | 175,122      | 362,663     | fory    |
| MediaContentList  | Serialize   | 668,732    | 404,272      | 551,755     | fory    |
| MediaContentList  | Deserialize | 513,699    | 263,899      | 264,664     | fory    |

### Serialized Data Sizes (bytes)

| Datatype          | fory | protobuf | msgpack |
| ----------------- | ---- | -------- | ------- |
| NumericStruct     | 78   | 93       | 87      |
| Sample            | 445  | 460      | 562     |
| MediaContent      | 362  | 307      | 479     |
| NumericStructList | 255  | 475      | 444     |
| SampleList        | 1978 | 2315     | 2819    |
| MediaContentList  | 1531 | 1550     | 2404    |
