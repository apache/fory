---
title: Row Format
sidebar_position: 0
id: index
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

Row Format stores typed values in a cache-friendly binary layout for random and partial access
without reconstructing a complete object graph. Use it for analytical and
in-memory data processing.

## Choose a row family

| Family                      | Runtime support         | Compatibility                    |
| --------------------------- | ----------------------- | -------------------------------- |
| [Standard Row](standard.md) | Java, Python, C++, Rust | Shared Standard Row layout       |
| [Compact Row](compact.md)   | Java                    | Java-only, space-oriented layout |

Use Binary Object Serialization when the goal is complete object reconstruction, references, or
general application messaging. Use Row Format when a workload reads selected fields, nested arrays,
or maps directly from encoded data.

## Runtime guides

- [Java](java.md)
- [Python](python.md)
- [C++](cpp.md)
- [Rust](rust.md)

The normative [Row Format specification](../specification/row_format_spec.md) defines Standard and
Compact layouts.
