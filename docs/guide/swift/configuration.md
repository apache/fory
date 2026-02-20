---
title: Configuration
sidebar_position: 1
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

This page covers `ForyConfig` and recommended runtime presets.

## ForyConfig

`Fory` is configured with:

```swift
public struct ForyConfig {
    public var xlang: Bool
    public var trackRef: Bool
    public var compatible: Bool
}
```

Default configuration:

```swift
let fory = Fory() // xlang=true, trackRef=false, compatible=false
```

## Options

### `xlang`

Controls cross-language protocol mode.

- `true`: Use xlang wire format (default)
- `false`: Use Swift-native mode

```swift
let fory = Fory(config: .init(xlang: true))
```

### `trackRef`

Enables shared/circular reference tracking for reference-trackable types.

- `false`: No reference table (smaller/faster for acyclic or value-only graphs)
- `true`: Preserve object identity for class/reference graphs

```swift
let fory = Fory(config: .init(xlang: true, trackRef: true))
```

### `compatible`

Enables compatible schema mode for evolution across versions.

- `false`: Schema-consistent mode (stricter, lower metadata overhead)
- `true`: Compatible mode (supports add/remove/reorder fields)

```swift
let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
```

## Recommended Presets

### Local, strict schema

```swift
let fory = Fory(config: .init(xlang: false, trackRef: false, compatible: false))
```

### Cross-language service payloads

```swift
let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
```

### Graph/object identity workloads

```swift
let fory = Fory(config: .init(xlang: true, trackRef: true, compatible: true))
```
