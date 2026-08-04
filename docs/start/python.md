---
title: Python Setup
sidebar_position: 2
id: python
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

Python packages are published on PyPI. `pyfory` supports Python 3.8 and later on Linux, macOS, and Windows. Use one compatible Fory release across every peer in an application.

## Verify the Toolchain

```bash
python --version
python -m pip --version
```

## Choose a Capability

| Capability                  | Package or extra       | Continue with                                                                                                                                                                              |
| --------------------------- | ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Object Serialization        | `pyfory`               | [Python object serialization](../object-serialization/python/index.md), then choose [xlang](../object-serialization/python/xlang.md) or [native](../object-serialization/python/native.md) |
| Row Format                  | `pyfory[format]`       | [Python Row Format](../row-format/python.md)                                                                                                                                               |
| Schema and generated models | `fory-compiler`        | [Fory IDL and Compiler](../compiler/index.md)                                                                                                                                              |
| Fory gRPC                   | `pyfory` plus `grpcio` | [Python gRPC](../grpc/python.md)                                                                                                                                                           |

Each capability guide owns its exact install command and first runnable example.
