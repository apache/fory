---
title: Cross-Language Serialization
sidebar_position: 8
id: cross_language
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

Apache Fory™ C# supports cross-language serialization with other Fory runtimes.

## Enable Cross-Language Mode

C# defaults to `Xlang(true)`, but it is good practice to configure it explicitly in interoperability code.

```csharp
Fory fory = Fory.Builder()
    .Xlang(true)
    .Compatible(true)
    .Build();
```

## Register with Stable IDs

```csharp
[ForyObject]
public sealed class Person
{
    public string Name { get; set; } = string.Empty;
    public int Age { get; set; }
}

Fory fory = Fory.Builder()
    .Xlang(true)
    .Compatible(true)
    .Build();

fory.Register<Person>(100);
```

Use the same ID mapping on all languages.

## Register by Namespace/Type Name

```csharp
fory.Register<Person>("com.example", "Person");
```

## Cross-Language Example

### C# (Serializer)

```csharp
Person person = new() { Name = "Alice", Age = 30 };
byte[] payload = fory.Serialize(person);
```

### Java (Deserializer)

```java
Fory fory = Fory.builder()
    .withLanguage(Language.XLANG)
    .withRefTracking(true)
    .build();

fory.register(Person.class, 100);
Person value = (Person) fory.deserialize(payloadFromCSharp);
```

### Python (Deserializer)

```python
import pyfory

fory = pyfory.Fory(xlang=True, ref=True)
fory.register_type(Person, type_id=100)
value = fory.deserialize(payload_from_csharp)
```

## Type Mapping Reference

See [xlang_type_mapping.md](../../specification/xlang_type_mapping.md) for complete mapping.

## Best Practices

1. Keep type IDs stable and documented.
2. Enable `Compatible(true)` for rolling upgrades.
3. Register all user types on both read/write peers.
4. Validate integration with real payload round trips.

## Related Topics

- [Type Registration](type-registration.md)
- [Schema Evolution](schema-evolution.md)
- [Supported Types](supported-types.md)
