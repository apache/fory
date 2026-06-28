---
title: Configuration
sidebar_position: 2
id: configuration
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

This page explains the `Fory` constructor options.

## Creating a `Fory` Instance

Pass options directly to the constructor:

```dart
import 'package:fory/fory.dart';

// defaults: xlang wire format with compatible schema evolution
final fory = Fory();

// customize limits while keeping default compatible mode
final fory = Fory(
  maxDepth: 512,
  maxTypeFields: 512,
  maxTypeMetaBytes: 4096,
  maxSchemaVersionsPerType: 10,
  maxAverageSchemaVersionsPerType: 3,
);
```

Create one instance per application and reuse it; there is no benefit to creating a new `Fory` per request.

## Options

### `compatible`

Compatible mode is enabled by default. Keep it enabled when your service needs to handle payloads
from different versions of the same model, for example during rolling deployments or client/server
version skew.

When `compatible: true`:

- Adding or removing fields on one side does not break the other.
- Peers must still use the same `name` (or numeric `id`) to identify types.

When `compatible: false`:

- Both sides must have exactly the same schema. Use this only when every reader and writer always
  uses that schema and you want faster serialization and smaller size. For cross-language payloads, set `compatible: false` only after verifying that every language uses the same schema, or when native types are generated from Fory schema IDL.

```dart
final fory = Fory(compatible: false);
```

### `checkStructVersion`

Relevant only when `compatible: false`. When `true`, Fory validates that the schema version in the payload matches the one the receiver knows about, catching accidental mismatches for intentional same-schema payloads.

```dart
final fory = Fory(
  compatible: false,
  checkStructVersion: true, // default
);
```

This option has no effect when `compatible: true`.

### `maxDepth`

Limits how deeply nested an object graph can be. Increase this if you have legitimately deep trees; lower it to reject unexpectedly deep payloads fast.

```dart
final fory = Fory(maxDepth: 128);
```

### Remote schema metadata limits

Compatible mode can receive remote metadata for schema evolution. These limits
bound metadata size and accepted schema versions:

```dart
final fory = Fory(
  maxTypeFields: 512,
  maxTypeMetaBytes: 4096,
  maxSchemaVersionsPerType: 10,
  maxAverageSchemaVersionsPerType: 3,
);
```

- `maxTypeFields` limits fields in one received struct metadata body.
- `maxTypeMetaBytes` limits encoded body bytes in one received TypeMeta body, excluding the 8-byte
  header and any extended-size varint.
- `maxSchemaVersionsPerType` limits accepted remote metadata versions for one logical type.
- `maxAverageSchemaVersionsPerType` limits the average across accepted remote types. The
  effective global floor is `8192` schemas.

## Defaults

| Option                            | Default |
| --------------------------------- | ------- |
| `compatible`                      | `true`  |
| `checkStructVersion`              | `false` |
| `maxDepth`                        | 256     |
| `maxTypeFields`                   | 512     |
| `maxTypeMetaBytes`                | 4096    |
| `maxSchemaVersionsPerType`        | 10      |
| `maxAverageSchemaVersionsPerType` | 3       |

## Xlang Notes

When Fory is used to communicate between services written in different languages:

- Keep compatible mode enabled on all sides if any side needs schema evolution.
- Use the same numeric IDs or `name` values on every side.
- Match the `compatible` setting on both the writing and reading side — mismatching modes will fail.

## Security

Security-related configuration:

- Register only the expected generated models before deserializing untrusted payloads.
- Use `checkStructVersion: true` with `compatible: false` for intentional same-schema payloads.
- Set `maxDepth` to reject unexpectedly deep payload shapes.
- Keep the remote schema metadata limits at their defaults unless the data is not malicious and a
  trusted peer sends larger metadata or many schema versions.
- Prefer generated schemas and explicit field metadata over broad dynamic fields for untrusted input.

## Related Topics

- [Basic Serialization](basic-serialization.md)
- [Schema Evolution](schema-evolution.md)
- [Xlang Serialization](xlang-serialization.md)
