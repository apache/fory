---
title: Java Setup
sidebar_position: 1
id: java
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

Fory Java artifacts are published to Maven Central. Fory core supports Java 8 and later; Java Records require Java 17 or later, and Row Format requires Java 11 or later. Keep every Fory artifact in one application on the same version.

## Verify the Toolchain

```bash
java -version
mvn -version
# or: ./gradlew --version
```

## Choose a Capability

| Capability                  | Artifact or tool                                 | Continue with                                                                                                                                                                      |
| --------------------------- | ------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Object Serialization        | `fory-core`                                      | [Java object serialization](../object-serialization/java/index.md), then choose [xlang](../object-serialization/java/xlang.md) or [native](../object-serialization/java/native.md) |
| Fory JSON                   | `fory-json`                                      | [Fory JSON Getting Started](../json/getting-started.md)                                                                                                                            |
| Row Format                  | `fory-format`                                    | [Java Row Format](../row-format/java.md)                                                                                                                                           |
| Schema and generated models | `fory-compiler`                                  | [Fory IDL and Compiler](../compiler/index.md)                                                                                                                                      |
| Fory gRPC                   | generated companions plus gRPC Java dependencies | [Java gRPC](../grpc/java.md)                                                                                                                                                       |

Each capability guide owns its exact dependency declaration and first runnable example.
