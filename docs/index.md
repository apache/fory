---
title: Apache Fory Documentation
sidebar_position: 0
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

Apache Fory is a multi-language serialization framework for binary object graphs, random-access
rows, standard JSON, schema-driven models, and Fory-backed gRPC services.

## Start here

1. Read the [Introduction](introduction/index.md) and [choose a format](introduction/choose-a-format.md).
2. Install Fory for the selected language from [Getting Started](start/index.md).
3. Review [Benchmarks](benchmarks/index.md) and their methodology for relevant performance evidence.
4. Continue with the owning capability guide.

## Capabilities

| Capability            | Use it for                                            | Documentation                                         |
| --------------------- | ----------------------------------------------------- | ----------------------------------------------------- |
| Object Serialization  | Reconstruct xlang or language-native object graphs    | [Object Serialization](object-serialization/index.md) |
| Row Format            | Random and partial access to trusted analytical data  | [Row Format](row-format/index.md)                     |
| Fory JSON             | High-throughput standard JSON for Java, Kotlin, Scala | [Fory JSON](json/index.md)                            |
| Fory IDL and compiler | Generate language-native models from a shared schema  | [Compiler](compiler/index.md)                         |
| Fory gRPC             | Generate gRPC companions that marshal Fory models     | [Fory gRPC](grpc/index.md)                            |

## Language guides

Binary Object Serialization provides multi-page language guides for
[Java](object-serialization/java/index.md), [Python](object-serialization/python/index.md),
[C++](object-serialization/cpp/index.md), [Go](object-serialization/go/index.md),
[Rust](object-serialization/rust/index.md),
[JavaScript/TypeScript](object-serialization/javascript/index.md),
[C#](object-serialization/csharp/index.md), [Swift](object-serialization/swift/index.md),
[Dart](object-serialization/dart/index.md), [Scala](object-serialization/scala/index.md), and
[Kotlin](object-serialization/kotlin/index.md).

Fory JSON has language guides for [Kotlin](json/kotlin.md) and [Scala](json/scala.md) in addition
to its Java API documentation.

## Reference and development

- Wire and type-system details remain in the separate [Specification](specification/xlang_serialization_spec.md) surface.
- Repository contributors should start with [Development](development/index.md) and the
  [contributing guide](https://github.com/apache/fory/blob/main/CONTRIBUTING.md).
- Vulnerability reporting instructions are in the repository
  [Security Policy](https://github.com/apache/fory/blob/main/SECURITY.md).
