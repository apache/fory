---
title: Custom Serializers
sidebar_position: 4
id: custom_serializers
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

Use custom serializers when a type is not generated with `[ForyObject]` or requires specialized encoding.

## Implement `Serializer<T>`

```csharp
using Apache.Fory;

public sealed class Point
{
    public int X { get; set; }
    public int Y { get; set; }
}

public sealed class PointSerializer : Serializer<Point>
{
    public override Point DefaultValue => new();

    public override void WriteData(WriteContext context, in Point value, bool hasGenerics)
    {
        context.Writer.WriteVarInt32(value.X);
        context.Writer.WriteVarInt32(value.Y);
    }

    public override Point ReadData(ReadContext context)
    {
        return new Point
        {
            X = context.Reader.ReadVarInt32(),
            Y = context.Reader.ReadVarInt32(),
        };
    }
}
```

## Register the Serializer

```csharp
Fory fory = Fory.Builder().Build();
fory.Register<Point, PointSerializer>(200);

Point value = new() { X = 10, Y = 20 };
byte[] payload = fory.Serialize(value);
Point decoded = fory.Deserialize<Point>(payload);
```

## Serializer Behavior Notes

- `WriteData` / `ReadData` only handle payload content.
- Ref flags and type info are handled by base `Serializer<T>.Write` / `Read` unless overridden.
- `DefaultValue` is used for null/default fallback paths.

## Best Practices

1. Keep serializers deterministic and symmetric.
2. Use varint/fixed/tagged encoding intentionally for integer-heavy payloads.
3. Register custom serializers on all reader/writer peers.
4. Prefer generated `[ForyObject]` serializers for normal domain models.

## Related Topics

- [Type Registration](type-registration.md)
- [Field Configuration](field-configuration.md)
- [Troubleshooting](troubleshooting.md)
