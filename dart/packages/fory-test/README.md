<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Fory Dart Tests

This package exercises Dart generated serializers, registration, external-type
serialization, and consumer-side xlang integration.

## External-Type Benchmark

Compile and run the paired ordinary/external throughput benchmark in AOT mode:

```bash
mkdir -p build
dart compile exe \
  -S build/external_type_benchmark.debug \
  -o build/external_type_benchmark \
  benchmark/external_type_benchmark.dart
build/external_type_benchmark
```

Check equivalent-path object allocations with:

```bash
dart run benchmark/external_type_benchmark.dart --check-allocations
```
