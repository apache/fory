# Apache Fory Python Benchmarks

This directory contains two benchmark entrypoints:

1. `benchmark.py` + `run.sh` (new): C++-parity benchmark matrix covering:
   - `Struct`, `Sample`, `MediaContent`
   - `StructList`, `SampleList`, `MediaContentList`
   - operations: `serialize`, `deserialize`
   - serializers: `fory`, `pickle`, `protobuf`
2. `fory_benchmark.py` (legacy): existing CPython microbench script kept intact.

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
# Run only Struct benchmarks for Fory serialize
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
- `--serializer`: `fory,pickle,protobuf`
- `--operation`: `all|serialize|deserialize`

## Legacy Script (Unchanged)

`fory_benchmark.py` remains unchanged and can still be used directly:

```bash
cd benchmarks/python
python fory_benchmark.py
```

For its original options and behavior, refer to `python fory_benchmark.py --help`.

## Notes

- `pyfory` must be installed in your current Python environment.
- `protoc` is required by `run.sh` to generate `bench_pb2.py`.
- `protobuf` benchmarks include dataclass <-> protobuf conversion in the timed path.
