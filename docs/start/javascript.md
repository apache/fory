---
title: JavaScript/TypeScript Setup
sidebar_position: 6
id: javascript
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

Fory JavaScript/TypeScript packages are published on npm. The core package works without native acceleration; the optional `@apache-fory/hps` Node.js fast path requires Node.js 20 or later. Keep Fory packages in one application on compatible versions.

## Verify the Toolchain

```bash
node --version
npm --version
```

## Choose a Capability

| Capability                  | Package or tool                                  | Continue with                                                                                                                                      |
| --------------------------- | ------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| Object Serialization        | `@apache-fory/core`; optional `@apache-fory/hps` | [JavaScript/TypeScript object serialization](../object-serialization/javascript/index.md) and [xlang](../object-serialization/javascript/xlang.md) |
| Schema and generated models | `fory-compiler`                                  | [Fory IDL and Compiler](../compiler/index.md)                                                                                                      |
| Fory gRPC                   | generated Node.js or gRPC-Web companions         | [JavaScript gRPC](../grpc/javascript.md)                                                                                                           |

Each capability guide owns its exact install command, environment-specific setup, and first runnable example.
