---
title: Kotlin Setup
sidebar_position: 11
id: kotlin
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

Fory Kotlin artifacts are published to Maven Central and run on Fory Java. Fory core supports Java 8 and later. Keep Kotlin, Java core, and generated-code artifacts in one application on the same Fory release.

## Verify the Toolchain

```bash
java -version
./gradlew --version
# or: mvn -version
```

## Choose a Capability

| Capability                   | Artifact or tool                                | Continue with                                                                                                                                                                              |
| ---------------------------- | ----------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Object Serialization         | `fory-kotlin`                                   | [Kotlin object serialization](../object-serialization/kotlin/index.md), then choose [xlang](../object-serialization/kotlin/xlang.md) or [native](../object-serialization/kotlin/native.md) |
| Schema and generated models  | `fory-compiler` and Kotlin KSP support          | [Fory IDL and Compiler](../compiler/index.md)                                                                                                                                              |
| Fory gRPC                    | generated coroutine companions plus grpc-kotlin | [Kotlin gRPC](../grpc/kotlin.md)                                                                                                                                                           |
| Android object serialization | `fory-kotlin` plus generated serializers        | [Android](../object-serialization/java/android.md)                                                                                                                                         |

Each capability guide owns its exact Maven or Gradle declaration and first runnable example.
