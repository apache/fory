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

This page covers `ForyBuilder` options and default configuration values for Apache Fory™ C#.
`Config` is an immutable configuration snapshot created by `ForyBuilder`.

## Build a Fory Instance

```csharp
using Apache.Fory;

Fory fory = Fory.Builder().Build();
ThreadSafeFory threadSafe = Fory.Builder().BuildThreadSafe();
```

## Default Configuration

`Fory.Builder().Build()` uses:

| Option               | Default | Description                                  |
| -------------------- | ------- | -------------------------------------------- |
| `TrackRef`           | `false` | Reference tracking disabled                  |
| `Compatible`         | `true`  | Compatible schema-evolution metadata enabled |
| `CheckStructVersion` | `false` | Struct schema hash checks disabled           |
| `MaxDepth`           | `20`    | Max dynamic nesting depth                    |

## Builder Options

C# always uses xlang-compatible framing, so `ForyBuilder` does not expose a mode toggle.

### `TrackRef(bool enabled = false)`

Enables reference tracking for shared/circular object graphs.

```csharp
Fory fory = Fory.Builder()
    .TrackRef(true)
    .Build();
```

### `Compatible(bool enabled = false)`

Enables schema evolution mode. C# uses the xlang wire format only, so compatible mode is enabled by
default for independently deployed peers. Use `.Build()` without calling this method for the
default compatible mode. Passing `false`, or calling `Compatible()` without an argument, opts into
same-schema payloads. Use that only when every reader and writer always uses the same schema and you want faster serialization and smaller size. For cross-language payloads, call `Compatible(false)` only after verifying that every peer uses the same schema, or when native types are generated from Fory schema IDL.

```csharp
Fory fory = Fory.Builder()
    .Compatible(false)
    .Build();
```

### `CheckStructVersion(bool enabled = false)`

Checks the schema hash when you intentionally use same-schema payloads.

```csharp
Fory fory = Fory.Builder()
    .Compatible(false)
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

### Compatible service

```csharp
Fory fory = Fory.Builder()
    .TrackRef(true)
    .Build();
```

### Same-schema optimization

Use this only when every reader and writer always uses the same schema.

```csharp
Fory fory = Fory.Builder()
    .Compatible(false)
    .Build();
```

### Thread-safe service instance

```csharp
ThreadSafeFory fory = Fory.Builder()
    .TrackRef(true)
    .BuildThreadSafe();
```

## Security

Security-related configuration:

- Register only the expected types before deserializing untrusted payloads.
- Use `CheckStructVersion(true)` with `Compatible(false)` for intentional same-schema payloads.
- Set `MaxDepth(...)` to reject unexpectedly deep dynamic object graphs.
- Prefer generated or registered concrete models over broad dynamic fields for untrusted input.

## Related Topics

- [Basic Serialization](basic-serialization.md)
- [Schema Evolution](schema-evolution.md)
- [Thread Safety](thread-safety.md)
