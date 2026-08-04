---
title: Getting Started
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

Apache Fory™ releases are available both as source artifacts and language-specific packages.

For source downloads, see the Apache Fory™ [download](https://fory.apache.org/download) page.

## Choose a Runtime

Each runtime setup page checks the toolchain and routes to the capabilities that
runtime supports:

| Runtime               | Setup                                  |
| --------------------- | -------------------------------------- |
| Java                  | [Java](java.md)                        |
| Python                | [Python](python.md)                    |
| C++                   | [C++](cpp.md)                          |
| Go                    | [Go](go.md)                            |
| Rust                  | [Rust](rust.md)                        |
| JavaScript/TypeScript | [JavaScript/TypeScript](javascript.md) |
| C#                    | [C#](csharp.md)                        |
| Swift                 | [Swift](swift.md)                      |
| Dart                  | [Dart](dart.md)                        |
| Scala                 | [Scala](scala.md)                      |
| Kotlin                | [Kotlin](kotlin.md)                    |

## Choose a Capability

| Need                                | Continue with                                            |
| ----------------------------------- | -------------------------------------------------------- |
| Reconstruct object graphs           | [Object Serialization](../object-serialization/index.md) |
| Random or partial analytical access | [Row Format](../row-format/index.md)                     |
| Exchange standard JSON from Java    | [Fory JSON](../json/index.md)                            |
| Generate models from a schema       | [Fory IDL and Compiler](../compiler/index.md)            |
| Use generated models over gRPC      | [Fory gRPC](../grpc/index.md)                            |

Object Serialization then asks you to choose xlang mode for portable
cross-language payloads or native mode for same-runtime payloads. Use
[Choose a Format](../introduction/choose-a-format.md) when that product decision
is not yet clear.

The selected capability guide owns its exact dependency and first successful
task. Schema compatibility settings and production configuration are
runtime-specific.
