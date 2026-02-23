# Fory C# Benchmark

This benchmark compares Apache Fory C#, protobuf-net, and MessagePack-CSharp.

Serializer setup used in this benchmark:

- `fory`: `Fory.Builder().SkipRootRefFlag(true).Build()`
- `protobuf`: `protobuf-net` runtime serializer
- `msgpack`: `MessagePackSerializer.Typeless` with `TypelessContractlessStandardResolver`

## Prerequisites

- .NET SDK 8.0+
- Python 3.8+

## Quick Start

```bash
cd benchmarks/csharp
./run.sh
```

This runs all benchmark cases and generates:

- `build/benchmark_results.json`
- `report/REPORT.md`

## Run Options

```bash
./run.sh --help

Options:
  --data <struct|sample|mediacontent|structlist|samplelist|mediacontentlist>
  --serializer <fory|protobuf|msgpack>
  --duration <seconds>
  --warmup <seconds>
```

Examples:

```bash
# Run only struct benchmarks
./run.sh --data struct

# Run only Fory benchmarks
./run.sh --serializer fory

# Use longer runs for stable numbers
./run.sh --duration 10 --warmup 2
```

## Benchmark Cases

- `struct`: 8-field integer object
- `sample`: mixed primitive fields and arrays
- `mediacontent`: nested object with list fields
- `structlist`: list of struct payloads
- `samplelist`: list of sample payloads
- `mediacontentlist`: list of media content payloads

Each case benchmarks:

- serialize throughput
- deserialize throughput

## Results

Latest run (`Darwin arm64`, `.NET 8.0.24`, `--duration 2 --warmup 0.5`):

| Serializer | Mean ops/sec across all cases |
| ---------- | ----------------------------: |
| fory       |                     2,032,057 |
| protobuf   |                     1,940,328 |
| msgpack    |                     1,901,489 |

Per-case winners vary by payload and operation. The full breakdown is generated at:

- `benchmarks/csharp/build/benchmark_results.json`
- `benchmarks/csharp/report/REPORT.md`
