---
title: Troubleshooting
sidebar_position: 90
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

## Derived Class Reports `FORY019`

**Cause**: A non-`object` base class does not expose exactly one compatible
generated hierarchy declaration. This usually means a first-party base is
missing its own direct `[ForyStruct]`, the base package was built with an older
generator, or two referenced schema assemblies declare the same third-party
base.

**Fix**:

- Add `[ForyStruct]` directly to every modifiable base class and rebuild the
  base assembly.
- For an unmodifiable base, reference exactly one external declaration with
  `Target` set to the derived type's immediate third-party base.
- Remove duplicate provider assemblies and rebuild descendants.

Fory does not inspect a referenced package's private fields to replace a
missing declaration.

## Private External Field Throws `MissingFieldException`

**Cause**: An exact external field declaration no longer matches the installed
package version. `TargetDeclaringType`, `TargetMemberName`, or the declared CLR
type differs from the package's private application binary interface.

**Fix**: Check the member metadata against the exact package version, update
the external declaration and its storage-only field entries, then rebuild.
There is no reflection or alternate-member fallback.

## Concurrency Issues

**Cause**: Sharing a single `Fory` instance across threads.

**Fix**: Use `BuildThreadSafe()`.

## Generated gRPC Compile Errors

**Symptom**: Generated `*Grpc.cs` files cannot find `Grpc.Core` types.

**Cause**: gRPC packages are application dependencies. The `Apache.Fory`
package does not add gRPC as a hard dependency.

**Fix**: Add `Grpc.Core.Api` and your chosen gRPC server or client package, such
as `Grpc.AspNetCore` for server hosting or `Grpc.Net.Client` for clients. See
[gRPC Support](../../grpc/csharp.md).

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
- [gRPC Support](../../grpc/csharp.md)
- [Schema Evolution](schema-evolution.md)
- [Thread Safety](thread-safety.md)
