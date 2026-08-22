---
title: Introduction
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

Binary Object Serialization reconstructs object graphs, including registered application types,
collections, polymorphic values, and optional shared references.

## Choose a mode

| Mode            | Use it when                                                 | Start here                        |
| --------------- | ----------------------------------------------------------- | --------------------------------- |
| Xlang (default) | Bytes cross language boundaries                             | [Xlang Serialization](xlang.md)   |
| Native          | Every writer and reader uses one Fory implementation family | [Native serialization](native.md) |

Xlang and native are the only object-serialization modes. Row Format is a random-access analytical
representation, and Fory JSON is a Java JSON codec; use the
[format chooser](../introduction/choose-a-format.md) when object reconstruction is not your goal.

## Read the concepts and modes

Read these pages before choosing the Fory API for a language:

1. [Core Concepts](core-concepts.md) explains object graphs, types, schemas, references,
   polymorphism, and schema evolution across both modes.
2. [Xlang Serialization](xlang.md) explains the portable format used by default and the rules that
   Fory implementations in different languages must share.
3. [Native Serialization](native.md) explains when an implementation-specific native format is
   appropriate.

## Browse by language

Choose a language to find its installation route, lifecycle, exact APIs, configuration, type
registration, schema behavior, extensions, platforms, and troubleshooting:

| Language              | Modes                | Documentation                                        |
| --------------------- | -------------------- | ---------------------------------------------------- |
| Java                  | xlang and native     | [Java guide](./java/index.md)                        |
| Python                | xlang and native     | [Python guide](./python/index.md)                    |
| C++                   | xlang and native     | [C++ guide](./cpp/index.md)                          |
| Go                    | xlang and native     | [Go guide](./go/index.md)                            |
| Rust                  | xlang and native     | [Rust guide](./rust/index.md)                        |
| JavaScript/TypeScript | xlang                | [JavaScript/TypeScript guide](./javascript/index.md) |
| C#                    | xlang                | [C# guide](./csharp/index.md)                        |
| Swift                 | xlang                | [Swift guide](./swift/index.md)                      |
| Dart                  | xlang                | [Dart guide](./dart/index.md)                        |
| Scala                 | xlang and JVM native | [Scala guide](./scala/index.md)                      |
| Kotlin                | xlang and JVM native | [Kotlin guide](./kotlin/index.md)                    |

## Specifications

- [Xlang serialization format](../specification/xlang_serialization_spec.md)
- [Java native object graph format](../specification/java_serialization_spec.md)
- [Xlang type mapping](../specification/xlang_type_mapping.md)
