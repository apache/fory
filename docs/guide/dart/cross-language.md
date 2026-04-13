---
title: Cross-Language Serialization
sidebar_position: 9
id: dart_cross_language
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

Apache Fory™ Dart supports cross-language serialization with other Fory runtimes.

## Cross-Language Runtime

The Dart runtime only supports xlang payloads, so you do not enable a separate cross-language mode.

```dart
final fory = Fory();
```

Configure only the runtime behavior that still varies, such as compatible mode and safety limits.

## Use Stable Registration Identity

Choose one registration strategy and keep it stable across all peers.

### Numeric ID example

```dart
ModelsFory.register(fory, Person, id: 100);
```

### Name-based example

```dart
ModelsFory.register(
  fory,
  Person,
  namespace: 'example',
  typeName: 'Person',
);
```

## Dart to Java Example

### Dart

```dart
import 'package:fory/fory.dart';

part 'person.fory.dart';

@ForyStruct()
class Person {
  Person();

  String name = '';
  Int32 age = Int32(0);
}

final fory = Fory();
PersonFory.register(fory, Person, id: 100);
final bytes = fory.serialize(Person()
  ..name = 'Alice'
  ..age = Int32(30));
```

### Java

```java
Fory fory = Fory.builder()
    .withLanguage(Language.XLANG)
    .build();

fory.register(Person.class, 100);
Person value = (Person) fory.deserialize(bytesFromDart);
```

## Dart to C# Example

### Dart

```dart
final fory = Fory(compatible: true);
PersonFory.register(fory, Person, id: 100);
final bytes = fory.serialize(Person()
  ..name = 'Alice'
  ..age = Int32(30));
```

### CSharp

```csharp
[ForyObject]
public sealed class Person
{
    public string Name { get; set; } = string.Empty;
    public int Age { get; set; }
}

Fory fory = Fory.Builder()
    .Compatible(true)
    .Build();

fory.Register<Person>(100);
Person person = fory.Deserialize<Person>(payloadFromDart);
```

## Dart to Go Example

### Dart

```dart
final fory = Fory();
PersonFory.register(fory, Person, id: 100);
final bytes = fory.serialize(Person()
  ..name = 'Alice'
  ..age = Int32(30));
```

### Go

```go
type Person struct {
    Name string
    Age  int32
}

f := fory.New(fory.WithXlang(true))
_ = f.RegisterStruct(Person{}, 100)

var person Person
_ = f.Deserialize(bytesFromDart, &person)
```

## Field Matching Rules

The xlang spec is the source of truth, but for application authors the practical rules are:

1. Register the same logical type identity on every side.
2. Keep field meaning aligned across languages.
3. For evolving schemas, keep field IDs stable.
4. Use compatible type mappings for numeric widths, timestamps, collections, and nullability.
5. Validate real payload round trips.

Dart model fields often use lowerCamelCase. Go fields are exported PascalCase. C# commonly uses PascalCase properties. What matters is that the peer runtimes agree on the logical field mapping and wire schema. Stable field IDs are the safest option when models evolve independently.

## Type Mapping Notes for Dart

Because Dart `int` is not itself a promise about the exact xlang wire width, prefer wrappers or numeric field annotations when exact cross-language interpretation matters:

- `Int32` for xlang `int32`
- `UInt32` for xlang `uint32`
- `Float16` and `Float32` for reduced-width floating point
- `Timestamp` and `LocalDate` for explicit temporal semantics

See [Supported Types](supported-types.md) and [xlang type mapping](../../specification/xlang_type_mapping.md).

## Validation Advice

Before relying on an interop contract, test the same payload through every runtime you support.

At minimum for Dart runtime work:

```bash
cd dart
dart run build_runner build --delete-conflicting-outputs
dart analyze
dart test
```

## Related Topics

- [Type Registration](type-registration.md)
- [Schema Evolution](schema-evolution.md)
- [Cross-language guide](../xlang/index.md)
