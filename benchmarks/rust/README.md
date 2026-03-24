# Fory Rust Benchmark

This benchmark compares Rust serialization and deserialization throughput for Apache Fory and Protocol Buffers using the shared benchmark dataset defined in `benchmarks/proto/bench.proto`.

## Quick Start

Run the complete Rust benchmark pipeline:

```bash
cd benchmarks/rust
./run.sh
```

## Run Options

```bash
./run.sh --help

Options:
  --data <struct|sample|mediacontent|structlist|samplelist|mediacontentlist>
                               Filter benchmark by data type
  --serializer <fory|protobuf>
                               Filter benchmark by serializer
  --filter <regex>             Custom criterion filter
  --no-report                  Skip Python report generation
```

Examples:

```bash
# Run only Struct benchmarks
./run.sh --data struct

# Run only Protobuf benchmarks
./run.sh --serializer protobuf

# Run only Sample and MediaContent benchmarks for Protobuf
./run.sh --data sample,mediacontent --serializer protobuf
```

## Benchmark Cases

| Benchmark case     | Description                                                            |
| ------------------ | ---------------------------------------------------------------------- |
| `Struct`           | Numeric struct with 8 int32 fields                                     |
| `Sample`           | Mixed primitive and array payload matching the shared benchmark schema |
| `MediaContent`     | Media and image payload matching the Java/C++ benchmark data           |
| `StructList`       | List of shared `Struct` payloads                                       |
| `SampleList`       | List of shared `Sample` payloads                                       |
| `MediaContentList` | List of shared `MediaContent` payloads                                 |

## Shared Proto Schema

The Rust benchmark uses the shared protobuf definition at `benchmarks/proto/bench.proto`, the same benchmark schema used by the C++ benchmark suite.

## Manual Commands

Run Criterion benchmarks:

```bash
cd benchmarks/rust
cargo bench --bench serialization_bench
```

Print serialized sizes:

```bash
cd benchmarks/rust
cargo run --release --bin fory_profiler -- --print-all-serialized-sizes
```

Generate the markdown report manually:

```bash
cd benchmarks/rust
cargo bench --bench serialization_bench 2>&1 | tee results/cargo_bench.log
cargo run --release --bin fory_profiler -- --print-all-serialized-sizes | tee results/serialized_sizes.txt
python benchmark_report.py --log-file results/cargo_bench.log --size-file results/serialized_sizes.txt --output-dir results
```

## Report Output

The report generator writes:

- `results/README.md`
- `results/throughput.png`
- per-datatype plots such as `results/struct.png` and `results/mediacontent.png`
