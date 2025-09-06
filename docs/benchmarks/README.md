
## Why?

add fory rust benchmark

## What does this PR do?

add fory rust benchmark, compare with protobuf/serde_json, for all kinds of struct.

## Related issues

<!--
Is there any related issue? If this PR closes them you say say fix/closes:

- #xxxx0
- #xxxx1
- Fixes #xxxx2
-->

## Does this PR introduce any user-facing change?

<!--
If any user-facing interface changes, please [open an issue](https://github.com/apache/fory/issues/new/choose) describing the need to do so and update the document if necessary.

Delete section if not applicable.
-->

- [ ] Does this PR introduce any public API change?
- [ ] Does this PR introduce any binary protocol compatibility change?

## Benchmark

## System Data Performance

| Operation | Size | Fory (ns) | Protobuf (ns) | JSON (ns) | Fory vs Protobuf | Fory vs JSON |
|-----------|------|-----------|---------------|-----------|------------------|--------------|
| **Serialize** | Small | 2.2199 µs | 6.2741 µs | 7.1390 µs | 2.8x faster | 3.2x faster |
| **Serialize** | Medium | 34.718 µs | 260.01 µs | 273.80 µs | 7.5x faster | 7.9x faster |
| **Serialize** | Large | 516.34 µs | 3.7771 ms | 3.6049 ms | 7.3x faster | 7.0x faster |
| **Deserialize** | Small | 6.3551 µs | 5.6267 µs | 6.8910 µs | 13% slower | 8% faster |
| **Deserialize** | Medium | 306.26 µs | 287.02 µs | 391.41 µs | 7% slower | 22% faster |
| **Deserialize** | Large | 4.9874 ms | 4.1081 ms | 5.2107 ms | 21% slower | 4% faster |

### Key Insights

- **Serialization Dominance**: Fory excels at serializing system telemetry data with 2.8-7.5x speedup over alternatives
- **Scaling Performance**: Performance advantage increases dramatically with data size (7.5x for medium, 7.3x for large)
- **Deserialization Trade-off**: Fory shows modest deserialization overhead vs protobuf (7-21% slower) but consistently beats JSON
- **Large Data Advantage**: For large system data, Fory serialization is 7.3x faster than protobuf and 7.0x faster than JSON
- For small data, Fory still has a slight edge of meta code over protobuf and JSON, which should be optimized in the future.

### Performance Trends

1. **Serialization**: Fory demonstrates exceptional performance for system monitoring data
2. **Data Size Impact**: Clear advantage emerges with medium and large datasets
3. **Deserialization**: Competitive performance with protobuf, superior to JSON
4. **Real-world Applicability**: Excellent choice for high-volume system telemetry and monitoring applications
