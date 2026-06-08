# JavaScript Benchmark

This benchmark compares serialization and deserialization throughput in JavaScript for Apache Fory, Protocol Buffers, and JSON.

It mirrors the benchmark layout used by [`benchmarks/cpp`](benchmarks/cpp/README.md) and uses the shared schema in [`benchmarks/proto/bench.proto`](benchmarks/proto/bench.proto).

## Coverage

- `NumericStruct`
- `Sample`
- `MediaContent`
- `NumericStructList`
- `SampleList`
- `MediaContentList`

For Fory, all struct schemas use explicit type IDs and field IDs so compatible-mode type metadata stays compact. The numeric type IDs match the C++ benchmark registration order.

## Quick Start

```bash
cd benchmarks/javascript
./run.sh
```

## Run Options

```bash
./run.sh --help

Options:
  --data <struct|sample|mediacontent|structlist|samplelist|mediacontentlist>
                               Filter benchmark by data type
  --serializer <fory|protobuf|json>
                               Filter benchmark by serializer
  --duration <seconds>         Minimum time to run each benchmark
```

Examples:

```bash
./run.sh --data struct
./run.sh --serializer fory
./run.sh --data sample --serializer protobuf --duration 10
```

## Schema Mismatch Mode

Set `FORY_BENCH_SCHEMA_MISMATCH=1` to run the Fory-only compatible-read
schema-mismatch mode. This mode is off by default. When enabled, run with
`--serializer fory`; protobuf and JSON benchmark modes fail with a configuration
error. Fory serialization uses the normal v1 schemas, and Fory deserialization
uses v2 schemas registered with the same Fory type IDs where one int32 field is
widened to int64.

## Generated Artifacts

Running the pipeline writes:

- raw benchmark JSON to `benchmarks/javascript/benchmark_results.json`
- throughput plot to `docs/benchmarks/javascript/throughput.png`
- Markdown report to `docs/benchmarks/javascript/README.md`

## Notes

- The benchmark builds the JavaScript package from `javascript/` before running.
- Protobuf uses `protobufjs` with the shared `bench.proto` schema.
- JSON results use UTF-8 byte length for serialized size.
