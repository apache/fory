---
title: Choose a Format
sidebar_position: 2
id: choose-a-format
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

| Format        | Use it when                                                   | Start here                                                     |
| ------------- | ------------------------------------------------------------- | -------------------------------------------------------------- |
| Xlang binary  | Data crosses language boundaries                              | [Cross-language guide](../object-serialization/xlang/index.md) |
| Native binary | Producer and consumer are in the same language                | Language guide                                                 |
| Row format    | You need random field access or analytics-style partial reads | [Row format spec](../row-format/index.md)                      |
| Fory JSON     | Java applications need high-performance standard JSON         | [Fory JSON guide](../json/index.md)                            |

For Java, Scala, Kotlin, Python, C++, Go, and Rust, use native mode for
same-language traffic. It avoids xlang's cross-language type mapping and
metadata constraints, stays closer to each language's native type system, and
supports broader language-specific object graphs. Use it when both producer and
consumer are in the same language family and you want the native object model
rather than a portable cross-language schema.

For Java/JVM-only systems, native mode is the replacement path for JDK
serialization, Kryo, FST, Hessian, and Java-only Protocol Buffers payloads. For
Python-only systems, native mode is the replacement path for pickle and
cloudpickle.

Compatible mode is Fory's schema-evolution mode. It writes the metadata readers
and writers need to tolerate schema differences. It is the default for xlang
mode and native mode in implementations that expose the option.

Use compatible mode when services deploy independently or when fields may be
added or deleted over time. Set compatible mode to `false` only when every reader
and writer always uses the same schema and you want faster serialization and
smaller size. For xlang payloads, set compatible mode to `false` only after
verifying that every language uses the same schema, or when native types are
generated from Fory schema IDL.

For xlang, all peers must agree on type identity. Name-based registration is
easier to read in examples. Numeric IDs are smaller and faster, but they require
coordination across every reader and writer.

## Decision summary

Choose xlang or native mode when you need to reconstruct object graphs. Choose Row Format for
trusted analytical data that benefits from random field access. Choose Fory JSON for standard JSON
in Java applications. Use Fory IDL and the compiler when multiple teams need one schema-first
contract; it generates models that use the relevant Fory capability.
