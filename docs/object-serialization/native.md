---
title: Native Serialization
sidebar_position: 2
id: native
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

Native serialization uses a binding-specific wire format and the owning runtime's native type
system. It is not one shared cross-language protocol.

## When to use native mode

Use native mode for same-runtime traffic that needs language-specific object shapes, migration from
a host serializer, or a smaller/faster format without xlang type-mapping constraints. Use
[xlang mode](xlang/index.md) whenever a different runtime must read the bytes.

## Supported runtime families

- [Java](java/native.md), including the JVM path used by Scala and Kotlin
- [Python](python/native.md)
- [C++](cpp/native.md)
- [Go](go/native.md)
- [Rust](rust/native.md)
- [Scala](scala/native.md)
- [Kotlin](kotlin/native.md)

Each runtime page owns its exact object model, schema rules, configuration, extension APIs, and
diagnostics. Native payloads from different runtime families are not interchangeable.
