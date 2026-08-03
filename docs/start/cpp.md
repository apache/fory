---
title: C++ Setup
sidebar_position: 3
id: cpp
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

Fory C++ is consumed from the Apache Fory source tree. It requires a C++17 compiler and supports CMake 3.16 or later and Bazel 8 or later. Pin one Fory release or commit across every peer in an application.

## Verify the Toolchain

```bash
c++ --version
cmake --version
# or: bazel --version
```

## Choose a Capability

| Capability                  | Build target                                        | Continue with                                                                                                                                                                  |
| --------------------------- | --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Object Serialization        | `fory::serialization` or `//cpp/fory/serialization` | [C++ object serialization](../object-serialization/cpp/index.md), then choose [xlang](../object-serialization/cpp/xlang.md) or [native](../object-serialization/cpp/native.md) |
| Row Format                  | C++ Row and encoder targets                         | [C++ Row Format](../row-format/cpp.md)                                                                                                                                         |
| Schema and generated models | `fory-compiler`                                     | [Fory IDL and Compiler](../compiler/index.md)                                                                                                                                  |
| Fory gRPC                   | generated companions plus gRPC C++                  | [C++ gRPC](../grpc/cpp.md)                                                                                                                                                     |

The selected capability guide owns its exact CMake or Bazel setup and first runnable example.
