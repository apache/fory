---
title: Interoperability
sidebar_position: 3
id: interoperability
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

Fory gRPC peers interoperate only when they use the same generated service contract, matching Fory
type identities, and compatible generated model schemas.

## Protocol boundary

The transport is gRPC, but the message bytes are Fory payloads. Generic protobuf clients and server
reflection tools cannot decode those payloads as protobuf messages. Generate every peer through a
supported Fory compiler frontend.

## Supported generated companions

Java, Python, C++, Go, Rust, JavaScript/TypeScript, C#, Dart, Scala, and Kotlin have documented gRPC
companions. Use the [support matrix](../introduction/support-matrix.md) and the selected runtime page
for current dependencies and streaming support.

## Verification

Test at least one unary call and every streaming shape used by the service. A protobuf
`UNIMPLEMENTED` or decode failure usually means the peer used an ordinary protobuf stub or a
different generated service contract.
