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

This page covers `Config` and recommended Fory presets.

## Config

`Fory` is configured with:

```swift
public struct Config {
  public let trackRef: Bool
  public let compatible: Bool
  public let checkClassVersion: Bool
  public let maxDepth: Int
  public let maxTypeFields: Int
  public let maxTypeMetaBytes: Int
  public let maxSchemaVersionsPerType: Int
  public let maxAverageSchemaVersionsPerType: Int
}
```

Default configuration:

```swift
let fory = Fory() // ref=false, compatible=true
```

Swift supports the xlang wire format only, so there is no `xlang` option in
`Config` or the `Fory` initializer.

## Threading

`Fory` is single-threaded and optimized to reuse one read/write context pair on the calling thread.
Reuse one instance per thread and do not use the same instance concurrently.

## Options

### `trackRef`

Enables shared/circular reference tracking for reference-trackable types.

- `false`: No reference table (smaller/faster for acyclic or value-only graphs)
- `true`: Preserve object identity for class/reference graphs

```swift
let fory = Fory(ref: true)
```

### `compatible`

Enables compatible schema mode for evolution across versions.

- `false`: Faster serialization and smaller size
- `true`: Compatible mode (supports add/remove/reorder fields)

Use `compatible: false` only when every reader and writer always uses the same schema and you want faster serialization and smaller size. For cross-language payloads, set `compatible: false` only after verifying that every language uses the same schema, or when native types are generated from Fory schema IDL.

```swift
let fory = Fory(compatible: false)
```

### `checkClassVersion`

Controls class-version validation when compatible mode is disabled. When
omitted, it defaults to `true` when `compatible: false` and `false` when
`compatible: true`.

```swift
let fory = Fory(compatible: false, checkClassVersion: true)
```

### Size and Depth Limits

`maxDepth` bounds decoded payload nesting depth. Compatible-mode remote metadata
is also limited:

- `maxTypeFields` defaults to `512` and limits fields in one received struct metadata body.
- `maxTypeMetaBytes` defaults to `4096` and limits encoded body bytes in one received TypeMeta body,
  excluding the 8-byte header and any extended-size varint.
- `maxSchemaVersionsPerType` defaults to `10` and limits accepted remote metadata versions for one
  logical type.
- `maxAverageSchemaVersionsPerType` defaults to `3` and limits the average across accepted remote
  types. The effective global floor is `8192` schemas.

```swift
let fory = Fory(
  maxDepth: 5,
  maxTypeFields: 512,
  maxTypeMetaBytes: 4096,
  maxSchemaVersionsPerType: 10,
  maxAverageSchemaVersionsPerType: 3
)
```

## Recommended Presets

### Default service payloads

```swift
let fory = Fory()
```

### Graph/object identity workloads

```swift
let fory = Fory(ref: true)
```

### Same-schema optimization

Use this only when every reader and writer always uses the same schema.

```swift
let fory = Fory(compatible: false)
```

## Security

Security-related configuration:

- Register only the expected generated models before deserializing untrusted payloads.
- Use `checkClassVersion` with `compatible: false` for intentional same-schema payloads.
- Set `maxDepth` for the largest nesting depth your service accepts.
- Keep the remote schema metadata limits at their defaults unless the data is not malicious and a
  trusted peer sends larger metadata or many schema versions.
