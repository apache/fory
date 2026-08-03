---
title: Dart Setup
sidebar_position: 9
id: dart
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

Fory Dart is published on pub.dev and requires Dart SDK 3.7 or later. Generated serializers use `build_runner`. Keep Fory packages and generated code in one application on compatible versions.

## Verify the Toolchain

```bash
dart --version
dart pub --help
```

## Choose a Capability

| Capability                  | Package or tool                          | Continue with                                                                                                         |
| --------------------------- | ---------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| Object Serialization        | `fory` plus `build_runner`               | [Dart object serialization](../object-serialization/dart/index.md) and [xlang](../object-serialization/dart/xlang.md) |
| Schema and generated models | `fory-compiler`                          | [Fory IDL and Compiler](../compiler/index.md)                                                                         |
| Fory gRPC                   | generated companions plus `package:grpc` | [Dart gRPC](../grpc/dart.md)                                                                                          |

Each capability guide owns its exact `pubspec.yaml`, generation command, and first runnable example.
