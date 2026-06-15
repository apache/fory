---
title: Schema Evolution
sidebar_position: 8
id: schema_evolution
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

Apache Fory™ C# supports schema evolution in compatible mode. Compatible mode is enabled by default.

## Compatible Mode

```csharp
Fory fory = Fory.Builder()
    .Build();
```

Compatible mode writes type metadata that allows readers and writers with different struct definitions to interoperate.

Compatible readers also tolerate selected scalar field type changes when the value is lossless. A
matched field can read between `bool`, `string`, numeric scalars, and `decimal` when the converted
value has the same logical value. Boolean strings must be exactly `"0"`, `"1"`, `"true"`, or
`"false"`. Numeric strings use finite ASCII decimal syntax without whitespace, a leading plus sign,
Unicode digits, underscores, hexadecimal notation, `NaN`, or infinities. Numbers and decimals read as
strings use canonical plain decimal text. Nullable fields still compose with these conversions, but
reference-tracked scalar type changes are incompatible. Invalid strings, out-of-range values, and lossy
numeric conversions fail during deserialization.

## Example: Add a Field

```csharp
using Apache.Fory;

[ForyStruct]
public sealed class OneStringField
{
    public string? F1 { get; set; }
}

[ForyStruct]
public sealed class TwoStringField
{
    public string F1 { get; set; } = string.Empty;
    public string F2 { get; set; } = string.Empty;
}

Fory fory1 = Fory.Builder().Build();
fory1.Register<OneStringField>(200);

Fory fory2 = Fory.Builder().Build();
fory2.Register<TwoStringField>(200);

byte[] payload = fory1.Serialize(new OneStringField { F1 = "hello" });
TwoStringField evolved = fory2.Deserialize<TwoStringField>(payload);

// F2 falls back to default value on reader side.
System.Diagnostics.Debug.Assert(evolved.F1 == "hello");
System.Diagnostics.Debug.Assert(evolved.F2 == string.Empty);
```

## Same-Schema Optimization

Use this only when every reader and writer always uses the same schema and you
want faster serialization and smaller size:

```csharp
Fory sameSchema = Fory.Builder()
    .Compatible(false)
    .CheckStructVersion(true)
    .Build();
```

Because C# uses the xlang wire format only, use `Compatible(false)` only after verifying that every peer uses the same schema, or when native types are generated from Fory schema IDL. This mode throws on schema hash mismatches.

## Best Practices

1. Keep compatible mode enabled for independently deployed services.
2. Keep stable type IDs across versions.
3. Add new fields with safe defaults.
4. Use `CheckStructVersion(true)` with `Compatible(false)` for intentional same-schema payloads.

## Related Topics

- [Configuration](configuration.md)
- [Type Registration](type-registration.md)
- [Troubleshooting](troubleshooting.md)
