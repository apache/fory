---
title: Schema Evolution
sidebar_position: 7
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

Apache Fory C# supports schema evolution in `Compatible(true)` mode.

## Compatible Mode

```csharp
Fory fory = Fory.Builder()
    .Compatible(true)
    .Build();
```

Compatible mode writes type metadata that allows readers and writers with different struct definitions to interoperate.

## Example: Add a Field

```csharp
using Apache.Fory;

[ForyObject]
public sealed class OneStringField
{
    public string? F1 { get; set; }
}

[ForyObject]
public sealed class TwoStringField
{
    public string F1 { get; set; } = string.Empty;
    public string F2 { get; set; } = string.Empty;
}

Fory writer = Fory.Builder().Compatible(true).Build();
writer.Register<OneStringField>(200);

Fory reader = Fory.Builder().Compatible(true).Build();
reader.Register<TwoStringField>(200);

byte[] payload = writer.Serialize(new OneStringField { F1 = "hello" });
TwoStringField evolved = reader.Deserialize<TwoStringField>(payload);

// F2 falls back to default value on reader side.
System.Diagnostics.Debug.Assert(evolved.F1 == "hello");
System.Diagnostics.Debug.Assert(evolved.F2 == string.Empty);
```

## Schema-Consistent Mode with Version Check

If you want strict schema identity checks instead of evolution behavior:

```csharp
Fory strict = Fory.Builder()
    .Compatible(false)
    .CheckStructVersion(true)
    .Build();
```

This mode throws on schema hash mismatches.

## Best Practices

1. Use `Compatible(true)` for independently deployed services.
2. Keep stable type IDs across versions.
3. Add new fields with safe defaults.
4. Use `CheckStructVersion(true)` when strict matching is required.

## Related Topics

- [Configuration](configuration.md)
- [Type Registration](type-registration.md)
- [Troubleshooting](troubleshooting.md)
