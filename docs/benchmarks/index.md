---
title: Results
sidebar_position: 1
id: index
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

> **Note**: Different serializers and configurations excel in different workloads. Benchmark results are for reference only.
> For your specific use case, conduct benchmarks with appropriate configurations and workloads.

## Java Benchmark

The Java benchmark section compares Fory against popular Java serialization frameworks using the current benchmark suite from `docs/benchmarks/object-serialization/native/java`.

**Serialization Throughput**:

![Java Serialization Throughput](object-serialization/native/java/java_repo_serialization_throughput.png)

**Deserialization Throughput**:

![Java Deserialization Throughput](object-serialization/native/java/java_repo_deserialization_throughput.png)

**JSON Throughput**:

<div style={{display: 'flex', gap: '1rem'}}>
  <img src={require('./json/java/string_throughput.png').default} alt="Java JSON String Throughput" style={{width: 'calc(50% - 0.5rem)'}} />
  <img src={require('./json/java/utf8_bytes_throughput.png').default} alt="Java JSON UTF-8 Throughput" style={{width: 'calc(50% - 0.5rem)'}} />
</div>

**Cross-Language Throughput**:

![Java Cross-Language Throughput](object-serialization/xlang/java/throughput.png)

**Important**: Fory's runtime code generation requires proper warm-up for performance measurement:

For additional benchmark notes, raw data, and the complete Java benchmark README, see [Java Benchmarks](object-serialization/native/java/README.md).

## Python Benchmark

Fory Python demonstrates strong performance compared to `pickle` and Protobuf across object and list workloads.

![Python Throughput](object-serialization/xlang/python/throughput.png)

For benchmark setup, raw results, and reproduction steps, see [Python Benchmarks](object-serialization/xlang/python/README.md).

## Rust Benchmark

Fory Rust demonstrates competitive performance compared to other Rust serialization frameworks.

![Rust Throughput](object-serialization/xlang/rust/throughput.png)

Note: Results depend on hardware, dataset, and implementation versions. See the
[Rust benchmark report](object-serialization/xlang/rust/README.md) for the recorded environment,
workloads, and reproduction command.

## C++ Benchmark

Fory C++ demonstrates competitive performance compared to Protobuf C++ serialization framework.

![C++ Throughput](object-serialization/xlang/cpp/throughput.png)

See the [C++ benchmark report](object-serialization/xlang/cpp/README.md) for the recorded
environment, workloads, and reproduction command.

## Go Benchmark

Fory Go demonstrates strong performance compared to Protobuf and Msgpack across
single-object and list workloads.

![Go Throughput](object-serialization/xlang/go/throughput.png)

Note: Results depend on hardware, dataset, and implementation versions. See the
[Go benchmark report](object-serialization/xlang/go/README.md) for details.

## C# Benchmark

Fory C# demonstrates strong performance compared to Protobuf and Msgpack across
typed object serialization and deserialization workloads.

![C# Throughput](object-serialization/xlang/csharp/throughput.png)

Note: Results depend on hardware and runtime versions. See the
[C# benchmark report](object-serialization/xlang/csharp/README.md) for details.

## Swift Benchmark

Fory Swift demonstrates strong performance compared to Protobuf and Msgpack
across both scalar-object and list workloads.

![Swift Throughput](object-serialization/xlang/swift/throughput.png)

Note: Results depend on hardware and runtime versions. See the
[Swift benchmark report](object-serialization/xlang/swift/README.md) for details.

## JavaScript Benchmark

Fory JavaScript demonstrates strong performance compared to Protocol Buffers and
JSON across representative Node.js workloads.

![JavaScript Throughput](object-serialization/xlang/javascript/throughput.png)

Note: Results depend on hardware, dataset, and runtime versions. See the
[JavaScript benchmark report](object-serialization/xlang/javascript/README.md) for details.

## Dart Benchmark

Fory Dart demonstrates strong performance compared to Protocol Buffers across
representative object and list workloads.

![Dart Throughput](object-serialization/xlang/dart/throughput.png)

Note: Results depend on hardware, dataset, and runtime versions. See the
[Dart benchmark report](object-serialization/xlang/dart/README.md) for details.

## Read Results Responsibly

Start with [Methodology](methodology.md), then open the report whose capability, language, schema,
mode, and operation match your workload. The checked-in results are historical evidence from their
recorded environment, not a guarantee for a different application or current main branch.
