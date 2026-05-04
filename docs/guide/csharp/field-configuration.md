---
title: Field Configuration
sidebar_position: 5
id: field_configuration
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

This page covers field-level serializer configuration for C# generated serializers.

## `[ForyObject]` and `[ForyField]`

Use `[ForyObject]` to enable source-generated serializers. Use `[ForyField]` to assign an optional stable field id or to override the Fory schema type used for a field.

```csharp
using Apache.Fory;
using S = Apache.Fory.Schema.Types;

[ForyObject]
public sealed class Metrics
{
    [ForyField(Type = typeof(S.UInt32))]
    public uint Count { get; set; }

    [ForyField(Type = typeof(S.Tagged<S.UInt64>))]
    public ulong TraceId { get; set; }

    public long LatencyMicros { get; set; }
}
```

`Id` is optional. When it is omitted, compatible mode still matches the field by name.

```csharp
using Apache.Fory;
using S = Apache.Fory.Schema.Types;

[ForyObject]
public sealed class NestedMetrics
{
    [ForyField(Type = typeof(S.Map<S.Fixed<S.UInt32>, S.List<S.Tagged<S.UInt64>>>))]
    public Dictionary<uint, List<ulong?>?> Values { get; set; } = [];

    [ForyField(3, Type = typeof(S.UInt64))]
    public ulong StableCount { get; set; }
}
```

## Schema Descriptor Types

Schema descriptors live under `Apache.Fory.Schema.Types` and are metadata only. They do not replace normal C# carrier types.

Common scalar descriptors include:

- `S.Int32`, `S.UInt32`
- `S.Int64`, `S.UInt64`
- `S.Float16`, `S.BFloat16`, `S.Float32`, `S.Float64`

Container descriptors are composable:

- `S.Fixed<TScalar>` and `S.Tagged<TScalar>` for scalar integer encodings
- `S.List<TElement>`
- `S.Set<TElement>`
- `S.Map<TKey, TValue>`
- `S.Array<TElement>`

Dense array fields use `S.Array<TElement>`, for example `S.Array<S.Int32>` or `S.Array<S.BFloat16>`.

Nullability comes from the C# carrier type. Use `List<ulong?>` for nullable list elements and `NullableKeyDictionary<TKey, TValue>` when a map needs nullable keys.

## Nullability and Reference Tracking

- Field nullability comes from C# type nullability (`string?`, nullable value types, etc.).
- Reference tracking is controlled at runtime by `ForyBuilder.TrackRef(...)`.

## Related Topics

- [Configuration](configuration.md)
- [Schema Evolution](schema-evolution.md)
- [Supported Types](supported-types.md)
