# C# Benchmark Performance Report

_Generated on 2026-03-04 10:55:09_

## How to Generate This Report

```bash
cd benchmarks/csharp
dotnet run -c Release --project ./Fory.CSharpBenchmark.csproj -- --output build/benchmark_results.json
python3 benchmark_report.py --json-file build/benchmark_results.json --output-dir report
```

## Hardware & OS Info

| Key                                | Value                                                                                                                        |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| OS                                 | Darwin 24.6.0 Darwin Kernel Version 24.6.0: Wed Oct 15 21:12:15 PDT 2025; root:xnu-11417.140.69.703.14~1/RELEASE_ARM64_T6041 |
| OS Architecture                    | Arm64                                                                                                                        |
| Machine                            | Arm64                                                                                                                        |
| Runtime Version                    | 8.0.24                                                                                                                       |
| Benchmark Date (UTC)               | 2026-03-04T02:49:28.6972030Z                                                                                                 |
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

## Benchmark Plots

All class-level plots below show throughput (ops/sec).

### Throughput

<p align="center">
<img src="throughput.png" width="90%" />
</p>

### Mediacontent

<p align="center">
<img src="mediacontent.png" width="90%" />
</p>

### Mediacontentlist

<p align="center">
<img src="mediacontentlist.png" width="90%" />
</p>

### Sample

<p align="center">
<img src="sample.png" width="90%" />
</p>

### Samplelist

<p align="center">
<img src="samplelist.png" width="90%" />
</p>

### Struct

<p align="center">
<img src="struct.png" width="90%" />
</p>

### Structlist

<p align="center">
<img src="structlist.png" width="90%" />
</p>

## Benchmark Results

### Timing Results (nanoseconds)

| Datatype         | Operation   | fory (ns) | protobuf (ns) | msgpack (ns) | Fastest |
| ---------------- | ----------- | --------- | ------------- | ------------ | ------- |
| Struct           | Serialize   | 47.8      | 129.5         | 66.9         | fory    |
| Struct           | Deserialize | 55.2      | 195.6         | 103.5        | fory    |
| Sample           | Serialize   | 307.7     | 570.8         | 345.0        | fory    |
| Sample           | Deserialize | 174.7     | 1150.5        | 561.7        | fory    |
| MediaContent     | Serialize   | 289.6     | 481.0         | 360.2        | fory    |
| MediaContent     | Deserialize | 343.2     | 779.3         | 732.0        | fory    |
| StructList       | Serialize   | 127.2     | 477.2         | 272.2        | fory    |
| StructList       | Deserialize | 205.1     | 698.3         | 502.7        | fory    |
| SampleList       | Serialize   | 1268.6    | 2813.2        | 1685.8       | fory    |
| SampleList       | Deserialize | 868.8     | 5590.2        | 2736.3       | fory    |
| MediaContentList | Serialize   | 1320.5    | 2362.1        | 1689.8       | fory    |
| MediaContentList | Deserialize | 1621.1    | 3612.8        | 3617.5       | fory    |

### Throughput Results (ops/sec)

| Datatype         | Operation   | fory TPS   | protobuf TPS | msgpack TPS | Fastest |
| ---------------- | ----------- | ---------- | ------------ | ----------- | ------- |
| Struct           | Serialize   | 20,918,778 | 7,720,907    | 14,945,879  | fory    |
| Struct           | Deserialize | 18,124,445 | 5,113,717    | 9,663,285   | fory    |
| Sample           | Serialize   | 3,250,380  | 1,752,019    | 2,898,164   | fory    |
| Sample           | Deserialize | 5,724,338  | 869,217      | 1,780,261   | fory    |
| MediaContent     | Serialize   | 3,452,789  | 2,079,167    | 2,776,587   | fory    |
| MediaContent     | Deserialize | 2,913,564  | 1,283,181    | 1,366,101   | fory    |
| StructList       | Serialize   | 7,858,803  | 2,095,745    | 3,673,471   | fory    |
| StructList       | Deserialize | 4,874,887  | 1,432,142    | 1,989,397   | fory    |
| SampleList       | Serialize   | 788,246    | 355,470      | 593,176     | fory    |
| SampleList       | Deserialize | 1,150,990  | 178,885      | 365,453     | fory    |
| MediaContentList | Serialize   | 757,278    | 423,351      | 591,774     | fory    |
| MediaContentList | Deserialize | 616,871    | 276,793      | 276,437     | fory    |

### Serialized Data Sizes (bytes)

| Datatype         | fory | protobuf | msgpack |
| ---------------- | ---- | -------- | ------- |
| Struct           | 58   | 61       | 55      |
| Sample           | 446  | 460      | 562     |
| MediaContent     | 304  | 307      | 479     |
| StructList       | 155  | 315      | 284     |
| SampleList       | 1915 | 2315     | 2819    |
| MediaContentList | 1440 | 1550     | 2404    |
