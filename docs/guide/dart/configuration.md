---
title: Configuration
sidebar_position: 1
id: dart_configuration
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

This page covers `Config` options and default runtime values for Apache Fory™ Dart.

## Create a Runtime

```dart
import 'package:fory/fory.dart';

final fory = Fory(
  compatible: true,
  maxDepth: 512,
  maxCollectionSize: 1 << 18,
  maxBinarySize: 16 * 1024 * 1024,
);
```

The same `Fory` instance should be reused across many operations. `Fory` resets operation-local read and write state for each root call.

## Options

### `compatible`

Enables compatible struct encoding and decoding.

```dart
final fory = Fory(compatible: true);
```

In compatible mode, generated evolving structs exchange shared `TypeDef` metadata rather than relying on the schema-consistent struct hash checks used by fixed-schema mode.

### `checkStructVersion`

Controls struct schema-version validation in schema-consistent mode.

```dart
final fory = Fory(
  compatible: false,
  checkStructVersion: true,
);
```

This option is forced to `false` whenever `compatible` is `true`.

### `maxDepth`

Maximum nesting depth accepted during one serialization or deserialization operation.

```dart
const config = Config(maxDepth: 128);
```

Use this to bound recursive payloads and fail fast on malformed or unexpectedly deep graphs.

### `maxCollectionSize`

Maximum number of entries accepted for a single collection or map payload.

```dart
const config = Config(maxCollectionSize: 100000);
```

### `maxBinarySize`

Maximum number of bytes accepted for a single binary payload.

```dart
const config = Config(maxBinarySize: 8 * 1024 * 1024);
```

## Defaults

`Config()` defaults to:

- `compatible: false`
- `checkStructVersion: true`
- `maxDepth: 256`
- `maxCollectionSize: 1 << 20`
- `maxBinarySize: 64 * 1024 * 1024`

## Cross-Language Notes

- The Dart runtime always reads and writes xlang frames.
- Match `compatible` mode across communicating services when you depend on schema evolution.
- Registration choices still matter: use the same numeric IDs or the same `namespace + typeName` mapping across all runtimes.

## Related Topics

- [Basic Serialization](basic-serialization.md)
- [Schema Evolution](schema-evolution.md)
- [Cross-Language](cross-language.md)
