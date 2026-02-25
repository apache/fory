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

This page covers `ForyBuilder` options and default configuration values for Apache Fory™ C#.

## Build a Runtime

```csharp
using Apache.Fory;

Fory fory = Fory.Builder().Build();
ThreadSafeFory threadSafe = Fory.Builder().BuildThreadSafe();
```

## Default Configuration

`Fory.Builder().Build()` uses:

| Option               | Default | Description                                    |
| -------------------- | ------- | ---------------------------------------------- |
| `Xlang`              | `true`  | Cross-language protocol mode                   |
| `TrackRef`           | `false` | Reference tracking disabled                    |
| `Compatible`         | `false` | Schema-consistent mode (no evolution metadata) |
| `CheckStructVersion` | `false` | Struct schema hash checks disabled             |
| `MaxDepth`           | `20`    | Max dynamic nesting depth                      |

## Builder Options

### `Xlang(bool enabled = true)`

Controls cross-language mode.

```csharp
Fory fory = Fory.Builder()
    .Xlang(true)
    .Build();
```

### `TrackRef(bool enabled = false)`

Enables reference tracking for shared/circular object graphs.

```csharp
Fory fory = Fory.Builder()
    .TrackRef(true)
    .Build();
```

### `Compatible(bool enabled = false)`

Enables schema evolution mode.

```csharp
Fory fory = Fory.Builder()
    .Compatible(true)
    .Build();
```

### `CheckStructVersion(bool enabled = false)`

Enables strict schema hash validation for generated struct serializers.

```csharp
Fory fory = Fory.Builder()
    .CheckStructVersion(true)
    .Build();
```

### `MaxDepth(int value)`

Sets max nesting depth for dynamic object graphs.

```csharp
Fory fory = Fory.Builder()
    .MaxDepth(32)
    .Build();
```

`value` must be greater than `0`.

## Common Configurations

### Fast schema-consistent service

```csharp
Fory fory = Fory.Builder()
    .TrackRef(false)
    .Compatible(false)
    .Build();
```

### Compatible cross-language service

```csharp
Fory fory = Fory.Builder()
    .Xlang(true)
    .Compatible(true)
    .TrackRef(true)
    .Build();
```

### Thread-safe service instance

```csharp
ThreadSafeFory fory = Fory.Builder()
    .Compatible(true)
    .TrackRef(true)
    .BuildThreadSafe();
```

## Related Topics

- [Basic Serialization](basic-serialization.md)
- [Schema Evolution](schema-evolution.md)
- [Thread Safety](thread-safety.md)
