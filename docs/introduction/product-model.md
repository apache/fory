---
title: Product Model
sidebar_position: 3
id: product-model
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

Apache Fory exposes three serialization products and one schema toolchain. Start from the data
contract you need, then choose a runtime API.

## Serialization products

| Product                                  | Data model                           | Interoperability boundary                                                       |
| ---------------------------------------- | ------------------------------------ | ------------------------------------------------------------------------------- |
| Binary Object Serialization: xlang mode  | Portable object graphs               | Shared wire format across supported runtimes                                    |
| Binary Object Serialization: native mode | Runtime-native object graphs         | Same runtime family only                                                        |
| Row Format                               | Random-access binary rows            | Standard Row is shared by Java, Python, C++, and Rust; Compact Row is Java-only |
| Fory JSON                                | Standard JSON mapped to Java objects | Java API with text interoperability                                             |

Xlang and native are sibling modes of Binary Object Serialization. Row Format and Fory JSON are
separate products; neither is a third object-serialization mode.

## Schema and services

[Fory IDL and the compiler](../compiler/index.md) generate native models for supported runtimes.
Service definitions can also generate [Fory gRPC](../grpc/index.md) companions. The compiler and
gRPC integration do not define additional serialization formats.

## Normative formats

Protocol implementers should use the unchanged [Specification](../specification/xlang_serialization_spec.md)
surface. User guides explain tasks and link to the exact specification instead of duplicating its
wire-level rules.
