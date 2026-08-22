# Scala JSON Benchmarks

This JMH suite compares `fory-json-scala`, `jsoniter-scala`, and Jackson Scala with the same
immutable Scala `MediaContent` model and Eishay JSON document.

The suite contains twelve benchmarks: three libraries, String and UTF-8 byte-array representations,
and serialization and deserialization operations. Library instances, generated codecs, model data,
and input documents are prepared once in JMH setup and are not part of the measured operation.

String benchmarks exclude UTF-8 conversion. All three byte-array benchmarks call direct byte APIs.
Setup verifies that every library decodes the fixture to the same model and round-trips its own
output.

The generated report contains separate String and UTF-8 charts. Each serialize or deserialize panel
contains three bars, followed by raw throughput and Fory performance-advantage tables.

`run_json_benchmark.py` rotates the library order across short JMH runs and reports the median for
each case. This reduces bias from changing machine load while retaining the same fork, thread, and
operation boundaries for all three libraries.

Published results are stored in [the Scala JSON benchmark report](../../docs/benchmarks/json/scala/README.md).
