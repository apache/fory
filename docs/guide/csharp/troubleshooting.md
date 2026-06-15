---
title: Troubleshooting
sidebar_position: 13
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

This page covers common C# issues and fixes.

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

**Cause**: The payload is not an xlang Fory frame, or it came from a peer mode that does
not emit the xlang header C# requires.

**Fix**: Ensure the payload was produced by an xlang-compatible peer. C# always expects the
xlang header and does not expose a mode switch, so configure the writer instead:

```java
Fory fory = Fory.builder()
    .withXlang(true)
    .build();
```

```python
fory = pyfory.Fory(xlang=True)
```

## Schema Version Mismatch with Same-Schema Payloads

**Symptom**: `InvalidDataException` while deserializing generated struct types.

**Cause**: `Compatible(false)` with `CheckStructVersion(true)` checks schema hashes for intentional
same-schema payloads.

**Fix options**:

- Keep compatible mode enabled for schema evolution.
- Use `Compatible(false)` only when every reader and writer always uses the same schema.

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

## Generated gRPC Compile Errors

**Symptom**: Generated `*Grpc.cs` files cannot find `Grpc.Core` types.

**Cause**: gRPC packages are application dependencies. The `Apache.Fory`
package does not add gRPC as a hard dependency.

**Fix**: Add `Grpc.Core.Api` and your chosen gRPC server or client package, such
as `Grpc.AspNetCore` for server hosting or `Grpc.Net.Client` for clients. See
[gRPC Support](grpc-support.md).

## Protobuf Client Cannot Decode a Fory gRPC Service

**Cause**: Fory gRPC companions use gRPC transports with Fory-encoded message
bodies. They do not send protobuf message bytes.

**Fix**: Use a Fory-generated client and server for the Fory endpoint, or expose
a separate protobuf endpoint for generic protobuf clients.

## Validation Commands

Run C# tests from repo root:

```bash
cd csharp
dotnet test Fory.sln -c Release
```

## Related Topics

- [Configuration](configuration.md)
- [gRPC Support](grpc-support.md)
- [Schema Evolution](schema-evolution.md)
- [Thread Safety](thread-safety.md)
