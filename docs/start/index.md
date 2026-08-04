---
title: Overview
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

Start with a language page below. Each page includes a release-pinned installation,
a runnable object-serialization round trip, the modes available for that language,
and a short path into every additional Fory capability available there.

Apache Fory™ releases are available as source artifacts and language-specific
packages. For source downloads, see the Apache Fory™
[download](https://fory.apache.org/download) page.

## First Five Minutes

1. Choose the language used by your application.
2. Install the package shown on that language page.
3. Run its minimal serialize/deserialize example.
4. Select xlang mode for cross-language data or native mode for data handled by one Fory
   implementation family when both are available.
5. Continue to the capability guide for production configuration and advanced APIs.

## Choose a Language

Each language page provides a release-pinned installation snippet, a minimal
round trip for an application project, and the next capability-specific steps:

| Language              | Setup                                  |
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

## What You Can Build

| Capability           | Use it for                                                                     | Available languages                                              | Detailed guide                                           |
| -------------------- | ------------------------------------------------------------------------------ | ---------------------------------------------------------------- | -------------------------------------------------------- |
| Object Serialization | Reconstruct object graphs, including shared references and schema changes      | All languages                                                    | [Object Serialization](../object-serialization/index.md) |
| Row Format           | Trusted analytical data with zero-copy, random, or partial field access        | Java, Python, C++, Rust                                          | [Row Format](../row-format/index.md)                     |
| Fory JSON            | High-performance standard JSON mapping                                         | Java                                                             | [Fory JSON](../json/index.md)                            |
| Fory IDL             | Generate native models and serializers from Fory, protobuf, or FlatBuffers IDL | All languages                                                    | [Fory IDL and Compiler](../compiler/index.md)            |
| Fory gRPC            | Use generated models over normal gRPC transports with Fory-encoded messages    | Java, Python, C++, Go, Rust, JavaScript, C#, Dart, Scala, Kotlin | [Fory gRPC](../grpc/index.md)                            |

Object Serialization uses xlang mode for portable cross-language data. Java,
Python, C++, Go, Rust, Scala, and Kotlin also offer native mode for data handled by one Fory
implementation family. Use [Choose a Format](../introduction/choose-a-format.md)
when you have not yet chosen a format.
