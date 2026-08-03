---
title: Go Setup
sidebar_position: 4
id: go
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

Fory Go is published as the Go module `github.com/apache/fory/go/fory` and requires Go 1.24 or later. Use one compatible Fory release across every peer in an application.

## Verify the Toolchain

```bash
go version
go env GOPROXY
```

If a Go proxy has not picked up a new submodule tag yet, retry later or use `GOPROXY=direct` temporarily.

## Choose a Capability

| Capability                  | Module or tool                    | Continue with                                                                                                                                                              |
| --------------------------- | --------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Object Serialization        | `github.com/apache/fory/go/fory`  | [Go object serialization](../object-serialization/go/index.md), then choose [xlang](../object-serialization/go/xlang.md) or [native](../object-serialization/go/native.md) |
| Schema and generated models | `fory-compiler`                   | [Fory IDL and Compiler](../compiler/index.md)                                                                                                                              |
| Fory gRPC                   | generated companions plus grpc-go | [Go gRPC](../grpc/go.md)                                                                                                                                                   |

Each capability guide owns its exact module command and first runnable example.
