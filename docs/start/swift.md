---
title: Swift Setup
sidebar_position: 8
id: swift
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

Fory Swift is distributed through Swift Package Manager from the Apache Fory repository. The current package uses Swift tools 6.0 and targets macOS 13 or later and iOS 16 or later. Pin one Fory release across every peer in an application.

## Verify the Toolchain

```bash
swift --version
```

## Choose a Capability

| Capability                  | Package product or tool      | Continue with                                                                                                            |
| --------------------------- | ---------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| Object Serialization        | Swift package product `Fory` | [Swift object serialization](../object-serialization/swift/index.md) and [xlang](../object-serialization/swift/xlang.md) |
| Schema and generated models | `fory-compiler`              | [Fory IDL and Compiler](../compiler/index.md)                                                                            |

The selected capability guide owns its exact package declaration and first runnable example.
