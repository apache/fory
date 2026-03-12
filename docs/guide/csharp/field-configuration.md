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

## `[ForyObject]` and `[Field]`

Use `[ForyObject]` to enable source-generated serializers. Use `[Field]` to override integer encoding for a specific field.

```csharp
using Apache.Fory;

[ForyObject]
public sealed class Metrics
{
    // Fixed-width 32-bit encoding
    [Field(Encoding = FieldEncoding.Fixed)]
    public uint Count { get; set; }

    // Tagged 64-bit encoding
    [Field(Encoding = FieldEncoding.Tagged)]
    public ulong TraceId { get; set; }

    // Default (varint) encoding
    public long LatencyMicros { get; set; }
}
```

## Available Encodings

| Encoding               | Meaning                                         |
| ---------------------- | ----------------------------------------------- |
| `FieldEncoding.Varint` | Variable-length integer encoding (default)      |
| `FieldEncoding.Fixed`  | Fixed-width integer encoding                    |
| `FieldEncoding.Tagged` | Tagged integer encoding (`long` / `ulong` only) |

## Supported Field Types for Encoding Override

`[Field(Encoding = ...)]` currently applies to:

- `int`
- `uint`
- `long`
- `ulong`

Nullable value variants (for example `long?`) are also handled by generated serializers.

## Nullability and Reference Tracking

- Field nullability comes from C# type nullability (`string?`, nullable value types, etc.).
- Reference tracking is controlled at runtime by `ForyBuilder.TrackRef(...)`.

## Related Topics

- [Configuration](configuration.md)
- [Schema Evolution](schema-evolution.md)
- [Supported Types](supported-types.md)
