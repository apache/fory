---
title: Overview
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

Fory JSON is Apache Fory's thread-safe Java JSON codec. It provides interpreted and
runtime-generated codecs for Java objects, records, immutable creator-based classes, common JDK
types, generic containers, and custom complete-value codecs.

Fory JSON is a separate data format from Fory's binary native and xlang protocols. Use it when a
system must exchange ordinary JSON with browsers, APIs, logs, configuration, or another JSON
implementation. Use the Fory binary protocol when you need cross-language schema metadata,
reference identity, circular graphs, or Fory's binary-only features.

## Documentation map

| Goal                                                           | Page                                  |
| -------------------------------------------------------------- | ------------------------------------- |
| First runnable JSON round trip                                 | [Getting Started](getting-started.md) |
| Understand Java object mapping and configuration               | [Object Mapping](object-mapping.md)   |
| Configure properties, creators, values, validators, and mixins | [Annotations](annotations.md)         |
| Extend complete values, children, and map keys                 | [Custom Codecs](custom-codecs.md)     |
| Deploy on Android                                              | [Android](android.md)                 |
| Build a GraalVM native image                                   | [GraalVM Native Image](graalvm.md)    |
| Decode input safely                                            | [Security](security.md)               |
| Diagnose failures                                              | [Troubleshooting](troubleshooting.md) |

## Performance

The Java JSON benchmark compares fory-json, Jackson, and Gson with the same data. Results below are
single-threaded throughput measurements on an Apple M4 Pro with JDK 26.0.1; higher is better. See
the [complete benchmark report](../benchmarks/json/java/README.md) for the command, environment, and
measurement configuration.

![Java JSON String benchmark throughput](../benchmarks/json/java/string_throughput.png)

![Java JSON UTF-8 bytes benchmark throughput](../benchmarks/json/java/utf8_bytes_throughput.png)

| Representation | Operation   | fory-json ops/sec | jackson ops/sec | gson ops/sec |
| -------------- | ----------- | ----------------: | --------------: | -----------: |
| String         | Serialize   |         7,387,465 |       2,049,368 |    1,084,042 |
| String         | Deserialize |         2,897,955 |       1,074,885 |      902,772 |
| UTF-8 bytes    | Serialize   |        10,375,498 |       1,868,614 |    1,037,211 |
| UTF-8 bytes    | Deserialize |         3,077,158 |       1,268,397 |      933,079 |

## Related Java guides

For binary serialization, start with [Java Object Serialization](../object-serialization/java/index.md)
and choose [xlang](../object-serialization/java/basic-serialization.md#cross-language-interoperability) or
[native](../object-serialization/java/native.md). Binary builder options are documented separately
in [Java Configuration](../object-serialization/java/configuration.md).
