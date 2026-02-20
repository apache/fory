---
title: Row Format
sidebar_position: 10
id: row_format
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

Swift currently focuses on object-graph serialization (`Fory.serialize` / `Fory.deserialize`).

## Current Status

- Object graph serialization: supported
- Cross-language xlang object protocol: supported
- Swift row-format APIs: not exposed yet

## Recommended Approach Today

- Use object serialization for application payloads
- Use compatible mode for schema evolution across service versions
- Use xlang mode for interoperability with other Fory runtimes

## Future Direction

Row-format support in Swift can be added without changing existing object serialization APIs. Until then, treat row-format features as unavailable in Swift runtime code.
