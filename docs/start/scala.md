---
title: Scala Setup
sidebar_position: 10
id: scala
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

Fory Scala artifacts are published to Maven Central for Scala 2.13 and Scala 3. Schema-generated Scala sources and macro-derived xlang serializers require Scala 3. The runtime uses Fory Java, so keep all Fory artifacts on the same release.

## Verify the Toolchain

```bash
java -version
scala -version
sbt --version
```

## Choose a Capability

| Capability                  | Artifact or tool                            | Continue with                                                                                                                                                                          |
| --------------------------- | ------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Object Serialization        | `fory-scala_2.13` or `fory-scala_3`         | [Scala object serialization](../object-serialization/scala/index.md), then choose [xlang](../object-serialization/scala/xlang.md) or [native](../object-serialization/scala/native.md) |
| Schema and generated models | `fory-compiler`; Scala 3 output             | [Fory IDL and Compiler](../compiler/index.md)                                                                                                                                          |
| Fory gRPC                   | Scala 3 generated companions plus grpc-java | [Scala gRPC](../grpc/scala.md)                                                                                                                                                         |

Each capability guide owns its exact Maven or sbt declaration and first runnable example.
