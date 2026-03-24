---
title: Troubleshooting
sidebar_position: 11
id: troubleshooting
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

This page covers common C# runtime issues and fixes.

## `TypeNotRegisteredException`

**Symptom**: `Type not registered: ...`

**Cause**: A user type was serialized/deserialized without registration.

**Fix**:

```csharp
Fory fory = Fory.Builder().Build();
fory.Register<MyType>(100);
```

Ensure the same type-ID/name mapping exists on both write and read sides.

## `InvalidDataException: xlang bitmap mismatch`

**Cause**: Writer/reader disagree on `Xlang` mode.

**Fix**: Use the same `Xlang(...)` value on both peers.

```csharp
Fory writer = Fory.Builder().Xlang(true).Build();
Fory reader = Fory.Builder().Xlang(true).Build();
```

## Schema Version Mismatch in Strict Mode

**Symptom**: `InvalidDataException` while deserializing generated struct types.

**Cause**: `Compatible(false)` with `CheckStructVersion(true)` enforces exact schema hashes.

**Fix options**:

- Enable `Compatible(true)` for schema evolution.
- Keep writer/reader model definitions in sync.

## Circular Reference Failures

**Symptom**: Stack overflow-like recursion or graph reconstruction issues.

**Cause**: Cyclic graphs with `TrackRef(false)`.

**Fix**:

```csharp
Fory fory = Fory.Builder().TrackRef(true).Build();
```

## Concurrency Issues

**Cause**: Sharing a single `Fory` instance across threads.

**Fix**: Use `BuildThreadSafe()`.

## Validation Commands

Run C# tests from repo root:

```bash
cd csharp
dotnet test Fory.sln -c Release
```

## Related Topics

- [Configuration](configuration.md)
- [Schema Evolution](schema-evolution.md)
- [Thread Safety](thread-safety.md)
