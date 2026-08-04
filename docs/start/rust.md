---
title: Rust Setup
sidebar_position: 5
id: rust
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

Fory Rust is published on crates.io. The workspace minimum supported Rust version is 1.70 and uses the Rust 2021 edition. Use one compatible Fory release across every peer in an application.

## Verify the Toolchain

```bash
rustc --version
cargo --version
```

## Choose a Capability

| Capability                  | Crate or tool                                 | Continue with                                                                                                                                                                      |
| --------------------------- | --------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Object Serialization        | `fory`                                        | [Rust object serialization](../object-serialization/rust/index.md), then choose [xlang](../object-serialization/rust/xlang.md) or [native](../object-serialization/rust/native.md) |
| Standard Row Format         | `fory`                                        | [Rust Row Format](../row-format/rust.md)                                                                                                                                           |
| Schema and generated models | `fory-compiler`                               | [Fory IDL and Compiler](../compiler/index.md)                                                                                                                                      |
| Fory gRPC                   | generated companions plus `tonic` and `bytes` | [Rust gRPC](../grpc/rust.md)                                                                                                                                                       |

Each capability guide owns its exact dependency declaration and first runnable example.
