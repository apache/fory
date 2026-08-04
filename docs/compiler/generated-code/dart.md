---
title: Dart
sidebar_position: 10
id: dart
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

## Output Layout

Dart output is two files per schema: a main `.dart` file with annotated types and the IDL module owner, and a `.fory.dart` part file with generated serializers and metadata.

- `<dart_out>/package/package.dart`
- `<dart_out>/package/package.fory.dart`

## Type Generation

Messages generate `@ForyStruct` annotated `final class` declarations with `@ForyField` on each field:

```dart
@ForyStruct()
final class Person {
  Person();

  @ForyField(id: 1)
  String name = '';

  @ForyField(id: 2, type: Int32Type())
  int id = 0;

  @ForyField(id: 7)
  List<Person_PhoneNumber> phones = <Person_PhoneNumber>[];

  @ForyField(id: 8)
  Animal pet = Animal._empty();
}
```

Enums generate Dart `enum` declarations with a `rawValue` getter and `fromRawValue` factory:

```dart
enum Person_PhoneType {
  mobile,
  home,
  work;

  int get rawValue => switch (this) {
    Person_PhoneType.mobile => 0,
    Person_PhoneType.home => 1,
    Person_PhoneType.work => 2,
  };

  static Person_PhoneType fromRawValue(int value) => switch (value) {
    0 => Person_PhoneType.mobile,
    1 => Person_PhoneType.home,
    2 => Person_PhoneType.work,
    _ => throw StateError('Unknown Person_PhoneType raw value $value.'),
  };
}
```

Unions generate `@ForyUnion` annotated classes with factory constructors, a case enum, and a custom serializer:

```dart
enum AnimalCase {
  dog,
  cat;

  int get id => switch (this) {
    AnimalCase.dog => 1,
    AnimalCase.cat => 2,
  };
}

@ForyUnion()
final class Animal {
  final AnimalCase _case;
  final Object? _value;

  const Animal._(this._case, this._value);

  factory Animal.dog(Dog value) => Animal._(AnimalCase.dog, value);
  factory Animal.cat(Cat value) => Animal._(AnimalCase.cat, value);

  bool get isDog => _case == AnimalCase.dog;
  Dog get dogValue => _value as Dog;
  // ...
}
```

Nested types use flat underscore naming (e.g., `Person_PhoneNumber`, `Person_PhoneType`).

`list<T>` fields generate ordered collection carriers and use the Fory list
protocol. `array<T>` fields generate dense one-dimensional bool or numeric
carriers and use the specialized dense-array protocol. Generated code must not
choose `array<T>` only because a language has an optimized list-like carrier;
the schema kind comes from the IDL.

| IDL schema          | Dart generated carrier | Notes                                      |
| ------------------- | ---------------------- | ------------------------------------------ |
| `list<int32>`       | `List<int>`            | List protocol, varint element encoding     |
| `list<fixed int32>` | `List<int>`            | List protocol, fixed-width element segment |
| `array<bool>`       | `BoolList`             | One byte per bool                          |
| `array<int8>`       | `Int8List`             | Dense signed bytes                         |
| `array<int16>`      | `Int16List`            | Dense little-endian int16                  |
| `array<int32>`      | `Int32List`            | Dense little-endian int32                  |
| `array<int64>`      | `Int64List`            | Dense little-endian int64                  |
| `array<uint8>`      | `Uint8List`            | Dense unsigned bytes                       |
| `array<uint16>`     | `Uint16List`           | Dense little-endian uint16                 |
| `array<uint32>`     | `Uint32List`           | Dense little-endian uint32                 |
| `array<uint64>`     | `Uint64List`           | Dense little-endian uint64                 |
| `array<float16>`    | `Float16List`          | Dense binary16 storage                     |
| `array<bfloat16>`   | `Bfloat16List`         | Dense bfloat16 storage                     |
| `array<float32>`    | `Float32List`          | Dense little-endian float32                |
| `array<float64>`    | `Float64List`          | Dense little-endian float64                |

Generated Dart fields that use `ArrayType(element: BoolType())` must use
`BoolList`; plain `List<bool>` remains the generated and handwritten carrier
for `list<bool>`.

Reference tracking on list elements or map values uses the container sugar annotations:

```dart
@ListField(element: DeclaredType(ref: true))
@ForyField(id: 3)
List<Node> children = <Node>[];

@MapField(value: DeclaredType(ref: true))
@ForyField(id: 2)
Map<String, Node> byName = <String, Node>{};
```

## Module Installation

Each generated Dart IDL library includes a module owner named after the input
file, such as `AddressbookForyModule` for `addressbook.dart`. The module
installs imported modules first and then registers every local schema type with
its default IDL identity:

```dart
abstract final class AddressbookForyModule {
  static void install(Fory fory) {
    complex_pb.ComplexPbForyModule.install(fory);
    _registerType(fory, Person);
    _registerType(fory, Dog);
  }

  static Fory getFory() { ... }

  static void _registerType(Fory fory, Type type) {
    if (type == Person) {
      registerGeneratedStruct(fory, _personForySchema, id: 100, namespace: null, typeName: null);
      return;
    }
    // ... other types
  }
}
```

## Usage

```dart
import 'package:fory/fory.dart';
import 'generated/addressbook/addressbook.dart';

void main() {
  final fory = Fory();
  AddressbookForyModule.install(fory);

  final person = Person()
    ..name = 'Alice'
    ..id = 1;

  final bytes = fory.serialize(person);
  final roundTrip = fory.deserialize<Person>(bytes);
}
```

## gRPC Service Companions

With `--grpc`, Dart emits one `<stem>_grpc.dart` per schema containing `<Service>Client` and `<Service>ServiceBase`. The service base registers its own method descriptors, so no separate registrar is generated. See [Dart gRPC](../../grpc/dart.md) for generated module naming, Fory installation, and usage.
