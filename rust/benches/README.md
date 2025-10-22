## How to generate flamegraph

```bash
cargo flamegraph --bin fory_profiler -- --operation deserialize --serializer fory -t e-commerce-data
```

detailed command:

```bash
cd benches
rm -rf cargo-flamegraph.trace
export CARGO_PROFILE_RELEASE_DEBUG=true &&
cargo flamegraph \
  --inverted \
  --deterministic \
  --palette rust \
  --min-width 0.05 \
  --bin fory_profiler -- \
  --operation deserialize \
  --serializer fory
```

## How to run benchmarks

```bash
cargo bench
```

To run only a specific benchmark group, you can use a command like

```bash
cargo bench --bench serialization_bench -- simple_struct
```
