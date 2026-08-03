---
title: C# Setup
sidebar_position: 7
id: csharp
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

Fory C# is published on NuGet as `Apache.Fory` and requires the .NET 8 SDK or later. The package includes the runtime and source generator for `ForyStruct` types. Use one compatible Fory release across every peer in an application.

## Verify the Toolchain

```bash
dotnet --version
```

## Choose a Capability

| Capability                  | Package or tool                                  | Continue with                                                                                                           |
| --------------------------- | ------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------- |
| Object Serialization        | `Apache.Fory`                                    | [C# object serialization](../object-serialization/csharp/index.md) and [xlang](../object-serialization/csharp/xlang.md) |
| Schema and generated models | `fory-compiler`                                  | [Fory IDL and Compiler](../compiler/index.md)                                                                           |
| Fory gRPC                   | generated companions plus gRPC .NET dependencies | [C# gRPC](../grpc/csharp.md)                                                                                            |

Each capability guide owns its exact package declaration and first runnable example.
