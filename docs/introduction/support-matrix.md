---
title: Support Matrix
sidebar_position: 4
id: support-matrix
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

Use this matrix to confirm the documented API surface before choosing a capability. A language page
does not imply support for every Fory capability.

| Capability                  | Documented languages                                                               | Interoperability                                         |
| --------------------------- | ---------------------------------------------------------------------------------- | -------------------------------------------------------- |
| Xlang object serialization  | Java, Python, C++, Go, Rust, JavaScript/TypeScript, C#, Swift, Dart, Scala, Kotlin | One shared xlang wire format                             |
| Native object serialization | Java, Python, C++, Go, Rust, Scala, Kotlin                                         | One Fory implementation family only                      |
| Standard Row Format         | Java, Python, C++, Rust                                                            | Shared Standard Row layout                               |
| Compact Row Format          | Java                                                                               | Java-only compact layout                                 |
| Fory JSON                   | Java, Kotlin, Scala                                                                | Standard JSON text                                       |
| Fory compiler output        | Java, Python, C++, Go, Rust, JavaScript/TypeScript, C#, Swift, Dart, Scala, Kotlin | Generated models use supported Fory APIs                 |
| Fory gRPC                   | Java, Python, C++, Go, Rust, JavaScript/TypeScript, C#, Dart, Scala, Kotlin        | Peers must use matching generated Fory service contracts |

Platform constraints such as [Android](../object-serialization/java/android.md) and
[GraalVM Native Image](../object-serialization/java/graalvm.md) are documented in the Java Object
Serialization and Fory JSON guides.
