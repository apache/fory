# Apache Fory Python Benchmarks

This directory contains two benchmark entrypoints:

1. `benchmark.py` + `run.sh` (new): C++-parity benchmark matrix covering:
   - `NumericStruct`, `Sample`, `MediaContent`
   - `NumericStructList`, `SampleList`, `MediaContentList`
   - operations: `serialize`, `deserialize`
   - serializers: `fory`, `protobuf`, `pickle`
2. `fory_benchmark.py`: CPython microbench script using the current annotation surface.

## Quick Start (Comprehensive Suite)

```bash
cd benchmarks/python
./run.sh
```

`run.sh` will:

1. Generate Python protobuf bindings from `benchmarks/proto/bench.proto`
2. Run `benchmark.py`
3. Generate plots + markdown report via `benchmark_report.py`
4. Copy report/plots to `docs/benchmarks/python`

### Common Options

```bash
# Run only NumericStruct benchmarks for Fory serialize
./run.sh --data struct --serializer fory --operation serialize

# Run all data types, deserialize only
./run.sh --operation deserialize

# Adjust benchmark loops
./run.sh --warmup 5 --iterations 30 --repeat 8 --number 1500

# Skip docs sync
./run.sh --no-copy-docs
```

Supported values:

- `--data`: `struct,sample,mediacontent,structlist,samplelist,mediacontentlist`
- `--serializer`: `fory,protobuf,pickle`
- `--operation`: `all|serialize|deserialize`

## Schema Mismatch Mode

Set `FORY_BENCH_SCHEMA_MISMATCH=1` to run the Fory-only compatible-read
schema-mismatch mode. This mode is off by default. When enabled, run with
`--serializer fory`; protobuf and pickle benchmark modes fail with a
configuration error. Fory serialization uses the normal v1 benchmark dataclasses,
and Fory deserialization uses v2 dataclasses registered with the same Fory type
IDs where one int32 field is widened to int64.

## CPython Microbenchmark

`fory_benchmark.py` can be used directly:

```bash
cd benchmarks/python
python fory_benchmark.py
```

For its original options and behavior, refer to `python fory_benchmark.py --help`.

## Notes

- `pyfory` must be installed in your current Python environment.
- `protoc` is required by `run.sh` to generate `bench_pb2.py`.
- `protobuf` benchmarks include dataclass <-> protobuf conversion in the timed path.
