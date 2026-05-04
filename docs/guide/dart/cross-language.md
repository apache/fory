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

Apache Fory™ Dart serializes to the same binary format as the Java, Go, C#, Python, Rust, and Swift Fory runtimes. You can write a message in Dart and read it in Java — or any other direction — without any conversion layer.

## Setup

Create a `Fory` instance as normal. There is no separate "cross-language mode" to enable in Dart:

```dart
final fory = Fory(); // or Fory(compatible: true) for schema evolution
```

The key requirement is that both sides register the same type using the same identity.

## Registration Identity

The most important rule: **use the same type identity on every side**. You have two options:

### Numeric ID

Simpler for small, tightly-coordinated teams:

```dart
// Dart
ModelsFory.register(fory, Person, id: 100);
```

### Namespace + Type Name

Better when multiple teams define types independently:

```dart
// Dart
ModelsFory.register(
  fory,
  Person,
  namespace: 'example',
  typeName: 'Person',
);
```

Do not mix the two strategies for the same type across runtimes.

## Dart to Java Example

### Dart

```dart
import 'package:fory/fory.dart';

part 'person.fory.dart';

@ForyStruct()
class Person {
  Person();

  String name = '';

  @ForyField(type: Int32Type())
  int age = 0;
}

final fory = Fory();
PersonFory.register(fory, Person, id: 100);
final bytes = fory.serialize(Person()
  ..name = 'Alice'
  ..age = 30);
```

### Java

```java
Fory fory = Fory.builder()
    .withXlang(true)
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
  ..age = 30);
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
  ..age = 30);
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

Fory matches fields by name or by stable field ID. For robust cross-language interop:

1. Use the same type identity on every side (same numeric ID or same `namespace + typeName`).
2. Assign stable `@ForyField(id: ...)` values to all fields before shipping the first payload.
3. Keep field names consistent or rely on IDs, since Dart typically uses `lowerCamelCase` while Go uses `PascalCase` for exported fields and C# often uses `PascalCase` properties.
4. Use explicit numeric field metadata: `@ForyField(type: Int32Type())` in Dart for Java `int`, Go `int32`, and C# `int`; `double` in Dart for 64-bit floats; `Float32` for 32-bit; `Int64` / `Uint64` for full-range 64-bit values.
5. Use `Timestamp`, `LocalDate`, and `Duration` for temporal fields rather than raw `DateTime`.
6. Validate real round trips across all languages before shipping.

## Type Mapping Notes for Dart

Because Dart `int` is not itself a promise about the exact xlang wire width, prefer explicit field metadata when exact cross-language interpretation matters:

- `@ForyField(type: Int32Type())` for xlang `int32`
- `@ForyField(type: Uint32Type())` for xlang `uint32`
- `@ForyField(type: Int8Type())` / `@ForyField(type: Int16Type())` / `@ForyField(type: Uint8Type())` / `@ForyField(type: Uint16Type())` for narrower integer widths
- `Int64` and `Uint64` for full-range 64-bit values on web
- `Float16`, `Bfloat16`, and `Float32` for reduced-width floating point
- `Float16List` and `Bfloat16List` for 16-bit floating-point array payloads
- `Timestamp`, `LocalDate`, and `Duration` for explicit temporal semantics

### Lists and Dense Arrays

`List<T>` always represents Fory `list<T>` unless a field has explicit array
metadata. Use `array<T>` only for dense one-dimensional bool or numeric data.

| Fory schema       | Dart field carrier and annotation                   |
| ----------------- | --------------------------------------------------- |
| `list<bool>`      | `List<bool>`                                        |
| `array<bool>`     | `@ArrayField(element: BoolType()) BoolList`         |
| `array<int8>`     | `@ArrayField(element: Int8Type()) Int8List`         |
| `array<int16>`    | `@ArrayField(element: Int16Type()) Int16List`       |
| `array<int32>`    | `@ArrayField(element: Int32Type()) Int32List`       |
| `array<int64>`    | `@ArrayField(element: Int64Type()) Int64List`       |
| `array<uint8>`    | `@ArrayField(element: Uint8Type()) Uint8List`       |
| `array<uint16>`   | `@ArrayField(element: Uint16Type()) Uint16List`     |
| `array<uint32>`   | `@ArrayField(element: Uint32Type()) Uint32List`     |
| `array<uint64>`   | `@ArrayField(element: Uint64Type()) Uint64List`     |
| `array<float16>`  | `@ArrayField(element: Float16Type()) Float16List`   |
| `array<bfloat16>` | `@ArrayField(element: Bfloat16Type()) Bfloat16List` |
| `array<float32>`  | `@ArrayField(element: Float32Type()) Float32List`   |
| `array<float64>`  | `@ArrayField(element: Float64Type()) Float64List`   |

See [Supported Types](supported-types.md) and [xlang type mapping](../../specification/xlang_type_mapping.md).

## Validation

Before relying on a cross-language contract in production, test a payload end-to-end through every runtime you support.

Run the Dart side:

```bash
dart run build_runner build --delete-conflicting-outputs
dart analyze
dart test
```

## Related Topics

- [Type Registration](type-registration.md)
- [Schema Evolution](schema-evolution.md)
- [Cross-language guide](../xlang/index.md)
