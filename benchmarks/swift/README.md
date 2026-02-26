# Fory Swift Benchmark

This benchmark compares serialization and deserialization performance between Apache Fory, Protocol Buffers, and MessagePack in Swift.

## Serializers Compared

- **Fory**: Apache Fory Swift implementation (`swift/Sources/Fory`)
- **Protocol Buffers**: `apple/swift-protobuf`
- **MessagePack**: `Flight-School/MessagePack`

## Benchmarked Data Types

| Data Type        | Description                                      |
| ---------------- | ------------------------------------------------ |
| Struct           | Simple struct with 8 int32 fields                |
| Sample           | Complex struct with primitives and array fields  |
| MediaContent     | Nested object graph with strings, enums, and ids |
| StructList       | List of `Struct` entries                         |
| SampleList       | List of `Sample` entries                         |
| MediaContentList | List of `MediaContent` entries                   |

Benchmark data is aligned with `benchmarks/cpp_benchmark` for cross-language comparison.

## Quick Start

```bash
cd benchmarks/swift
./run.sh
```

### Run Options

```bash
./run.sh --help
```

Supported flags:

- `--data <struct|sample|mediacontent|structlist|samplelist|mediacontentlist>`
- `--serializer <fory|protobuf|msgpack>`
- `--duration <seconds>`
- `--no-report`

Examples:

```bash
# Run only Struct benchmarks
./run.sh --data struct

# Run only protobuf benchmarks
./run.sh --serializer protobuf

# Run a single serializer and datatype
./run.sh --data sample --serializer msgpack --duration 5

# Skip report generation
./run.sh --no-report
```

## Manual Commands

```bash
# Build benchmark
swift build -c release

# Run benchmark executable
swift run -c release swift-benchmark --duration 3 --output results/benchmark_results.json

# Generate markdown report and plot
python3 benchmark_report.py --json-file results/benchmark_results.json --output-dir results
```

## Output Files

After running `./run.sh`, the following files are generated in `benchmarks/swift/results/`:

- `benchmark_results.json`: raw benchmark metrics and serialized-size comparison
- `throughput.png`: throughput comparison plot
- `REPORT.md`: markdown report with hardware/runtime info and result tables

## Notes

- Protobuf Swift types are generated in `Sources/SwiftBenchmarkProto/bench.pb.swift` from `Sources/SwiftBenchmarkProto/bench.proto`.
- Regenerate protobuf code:

```bash
protoc \
  --plugin=protoc-gen-swift=.build/arm64-apple-macosx/release/protoc-gen-swift-tool \
  --swift_opt=Visibility=Public \
  --swift_out=Sources/SwiftBenchmarkProto \
  --proto_path=Sources/SwiftBenchmarkProto \
  Sources/SwiftBenchmarkProto/bench.proto
```

- The benchmark intentionally includes plain-model conversion for protobuf to mirror real-world usage.
- Results vary across machines and runtime environments.
