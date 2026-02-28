# Fory C++ Benchmark

This benchmark compares serialization/deserialization performance between Apache Fory and Protocol Buffers in C++.

## Prerequisites

- CMake 3.16+
- C++17 compatible compiler (GCC 8+, Clang 7+, MSVC 2019+)
- Git (for fetching dependencies)

Note: Protobuf is fetched automatically via CMake FetchContent, so no manual installation is required.

## Benchmark Results

### Hardware & OS Info

| Key                  | Value         |
| -------------------- | ------------- |
| OS                   | Darwin 24.5.0 |
| Machine              | arm64         |
| Processor            | arm           |
| CPU Cores (Physical) | 12            |
| CPU Cores (Logical)  | 12            |
| Total RAM (GB)       | 48.0          |

### Throughput Results (ops/sec)

<p align="center">
<img src="../../docs/benchmarks/cpp/throughput.png" width="90%">
</p>

| Datatype     | Operation   | Fory TPS  | Protobuf TPS | Faster      |
| ------------ | ----------- | --------- | ------------ | ----------- |
| Mediacontent | Serialize   | 2,254,915 | 504,410      | Fory (4.5x) |
| Mediacontent | Deserialize | 741,303   | 396,013      | Fory (1.9x) |
| Sample       | Serialize   | 4,248,973 | 3,229,102    | Fory (1.3x) |
| Sample       | Deserialize | 935,709   | 715,837      | Fory (1.3x) |
| Struct       | Serialize   | 9,143,618 | 5,881,005    | Fory (1.6x) |
| Struct       | Deserialize | 7,746,787 | 6,202,164    | Fory (1.2x) |

## Quick Start

Run the complete benchmark pipeline (build, run, generate report):

```bash
cd benchmarks/cpp
./run.sh
```

### Run Options

```bash
./run.sh --help

Options:
  --data <struct|sample>       Filter benchmark by data type
  --serializer <fory|protobuf> Filter benchmark by serializer
  --duration <seconds>         Minimum time to run each benchmark (e.g., 10, 30)
  --debug                      Build with debug symbols for profiling
```

Examples:

```bash
# Run only Struct benchmarks
./run.sh --data struct

# Run only Fory benchmarks
./run.sh --serializer fory

# Run each benchmark for at least 10 seconds (for more stable results)
./run.sh --duration 10

# Combine options
./run.sh --data struct --serializer fory --duration 5
```

## Building

```bash
cd benchmarks/cpp
mkdir build && cd build
cmake -DCMAKE_BUILD_TYPE=Release ..
cmake --build . -j$(nproc)
```

## Running Benchmarks

```bash
./fory_benchmark
```

### Filter specific benchmarks

```bash
# Run only Struct benchmarks
./fory_benchmark --benchmark_filter="Struct"

# Run only Fory benchmarks
./fory_benchmark --benchmark_filter="Fory"

# Run only serialization benchmarks
./fory_benchmark --benchmark_filter="Serialize"
```

### Output formats

```bash
# JSON output
./fory_benchmark --benchmark_format=json --benchmark_out=results.json

# CSV output
./fory_benchmark --benchmark_format=csv --benchmark_out=results.csv
```

## Benchmark Cases

| Benchmark                              | Description                                                         |
| -------------------------------------- | ------------------------------------------------------------------- |
| `BM_Fory_Struct_Serialize`             | Serialize a simple struct with 8 int32 fields using Fory            |
| `BM_Protobuf_Struct_Serialize`         | Serialize the same struct using Protobuf                            |
| `BM_Fory_Struct_Deserialize`           | Deserialize a simple struct using Fory                              |
| `BM_Protobuf_Struct_Deserialize`       | Deserialize the same struct using Protobuf                          |
| `BM_Fory_Sample_Serialize`             | Serialize a complex object with various types and arrays using Fory |
| `BM_Protobuf_Sample_Serialize`         | Serialize the same object using Protobuf                            |
| `BM_Fory_Sample_Deserialize`           | Deserialize a complex object using Fory                             |
| `BM_Protobuf_Sample_Deserialize`       | Deserialize the same object using Protobuf                          |
| `BM_Fory_MediaContent_Serialize`       | Serialize a complex object with Media and Images using Fory         |
| `BM_Protobuf_MediaContent_Serialize`   | Serialize the same object using Protobuf                            |
| `BM_Fory_MediaContent_Deserialize`     | Deserialize a complex object with Media and Images using Fory       |
| `BM_Protobuf_MediaContent_Deserialize` | Deserialize the same object using Protobuf                          |
| `BM_PrintSerializedSizes`              | Just compares the serialization sizes of Fory and Protobuf          |

## Data Structures

### Struct (Simple)

A simple structure with 8 int32 fields, useful for measuring baseline serialization overhead.

### Sample (Complex)

A complex structure containing:

- Primitive types (int32, int64, float, double, bool)
- Multiple arrays (int, long, float, double, short, char, bool)
- String field

### MediaContent

Contains one Media and multiple Images.

## Proto Definition

The benchmark uses `benchmarks/proto/bench.proto` which is shared with the Java benchmark for consistency.

## Generating Benchmark Report

A Python script is provided to generate visual reports from benchmark results.

### Prerequisites for Report Generation

```bash
pip install matplotlib numpy psutil
```

### Generate Report

```bash
# Run benchmark and save JSON output
cd build
./fory_benchmark --benchmark_format=json --benchmark_out=benchmark_results.json

# Generate report
cd ..
python benchmark_report.py --json-file build/benchmark_results.json --output-dir report
```

The script will generate:

- PNG plots comparing Fory vs Protobuf performance
- A markdown report (`REPORT.md`) with detailed results

### Report Options

```bash
python benchmark_report.py --help

Options:
  --json-file     Benchmark JSON output file (default: benchmark_results.json)
  --output-dir    Output directory for plots and report
  --plot-prefix   Image path prefix in Markdown report
```

## Profiling / Flamegraph

Use `profile.sh` to generate flamegraphs for performance analysis:

```bash
# Profile all benchmarks
./profile.sh

# Profile specific benchmarks
./profile.sh --data struct --serializer fory

# Profile with custom duration
./profile.sh --serializer fory --duration 10
```

### Profile Options

```bash
./profile.sh --help

Options:
  --filter <pattern>           Custom benchmark filter (regex pattern)
  --data <struct|sample>       Filter benchmark by data type
  --serializer <fory|protobuf> Filter benchmark by serializer
  --duration <seconds>         Profiling duration (default: 5)
  --output-dir <dir>           Output directory (default: profile_output)
```

Example with custom filter:

```bash
# Profile a specific benchmark
./profile.sh --filter BM_Fory_Struct_Serialize
```

### Supported Profiling Tools

The script automatically detects and uses available tools (in order of preference):

1. **samply** (recommended): `cargo install samply`
2. **perf** (Linux)

### Flamegraph Output

When using `perf` on Linux, the script automatically generates flamegraph SVG files.
FlameGraph tools are auto-installed to `~/FlameGraph` if not found.

Output files are saved to `profile_output/`:

- `perf_<timestamp>.data` - Raw perf data
- `flamegraph_<timestamp>.svg` - Interactive flamegraph visualization

Open the SVG file in a browser to explore the flamegraph interactively.
