---
title: Basic Serialization
sidebar_position: 1
id: basic_serialization
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

This page shows how to serialize and deserialize values with Apache Fory™ Dart.

## Create a `Fory` Instance

Create one instance and reuse it — creating a new `Fory` for every call wastes resources.

```dart
import 'package:fory/fory.dart';

final fory = Fory();
```

## Serialize and Deserialize Annotated Types

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

void main() {
  final fory = Fory();
  PersonForyModule.register(
    fory,
    Person,
    name: 'example.Person',
  );

  final person = Person()
    ..name = 'Ada'
    ..age = 36;

  final bytes = fory.serialize(person);
  final roundTrip = fory.deserialize<Person>(bytes);
  print(roundTrip.name);
}
```

`deserialize<T>` returns the decoded value cast to `T`. If the payload describes a different type than `T`, it throws.

## Null Values

Serializing `null` is supported directly:

```dart
final fory = Fory();
final bytes = fory.serialize(null);
final value = fory.deserialize<Object?>(bytes);
```

## Serialize Collections and Dynamic Payloads

You can serialize collection values directly:

```dart
final fory = Fory();
final bytes = fory.serialize(<Object?>[
  'hello',
  42,
  true,
]);
final value = fory.deserialize<List<Object?>>(bytes);
```

For heterogeneous collections, deserialize to `Object?`, `List<Object?>`, or `Map<Object?, Object?>`.

## Reference Tracking

By default, Fory does not track object identity — if the same object appears twice in a list, it is serialized twice. Enable reference tracking when your data contains shared references or circular structures.

For a top-level collection:

```dart
final fory = Fory();
final shared = String.fromCharCodes('shared'.codeUnits);
final bytes = fory.serialize(<Object?>[shared, shared], trackRef: true);
final roundTrip = fory.deserialize<List<Object?>>(bytes);
print(identical(roundTrip[0], roundTrip[1])); // true
```

For fields inside a generated struct, use `@ForyField(ref: true)` on that field instead.

## Reusing a Buffer

If you want to avoid allocating a new `Uint8List` on every call, use `serializeTo` and `deserializeFrom` with an explicit `Buffer`:

```dart
final fory = Fory();
final buffer = Buffer();

fory.serializeTo('Ada', buffer);
final value = fory.deserializeFrom<String>(buffer);
```

This is an optimization. For most applications the default `serialize`/`deserialize` pair is fine.

## Register Your Types Before Serializing

Before you can serialize a custom class or enum, register it with `Fory`. The generated code makes this easy:

```dart
PersonForyModule.register(
  fory,
  Person,
  id: 100,
);
```

If you skip registration, deserialization fails with `Type ... is not registered`. See [Type Registration](type-registration.md) and [Code Generation](code-generation.md).

## Related Topics

- [Configuration](configuration.md)
- [Type Registration](type-registration.md)
- [Schema Metadata](schema-metadata.md)
