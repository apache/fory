---
title: Basic Serialization
sidebar_position: 2
id: dart_basic_serialization
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

This page covers the root serialization APIs in Apache Fory™ Dart.

## Create and Reuse `Fory`

```dart
import 'package:fory/fory.dart';

final fory = Fory();
```

Reuse the same runtime instance across calls. `Fory` owns the reusable `Buffer`, `WriteContext`, `ReadContext`, and `TypeResolver` services for that runtime.

## Serialize and Deserialize Annotated Types

```dart
import 'package:fory/fory.dart';

part 'person.fory.dart';

@ForyStruct()
class Person {
  Person();

  String name = '';
  Int32 age = Int32(0);
}

void main() {
  final fory = Fory();
  PersonFory.register(
    fory,
    Person,
    namespace: 'example',
    typeName: 'Person',
  );

  final person = Person()
    ..name = 'Ada'
    ..age = Int32(36);

  final bytes = fory.serialize(person);
  final roundTrip = fory.deserialize<Person>(bytes);
  print(roundTrip.name);
}
```

`deserialize<T>` uses the wire metadata first, then checks that the result is assignable to `T`.

## Null Root Values

The root xlang frame starts with a one-byte bitmap. A null root payload is encoded by the root header, so serializing `null` works directly:

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
  Int32(42),
  true,
]);
final value = fory.deserialize<List<Object?>>(bytes);
```

For heterogeneous collections, deserialize to `Object?`, `List<Object?>`, `Map<Object?, Object?>`, or a generated struct type that matches the wire schema.

## Root Reference Tracking

Use `trackRef: true` only when the root value itself is a graph or container with repeated references and there is no field metadata to request reference tracking.

```dart
final fory = Fory();
final shared = String.fromCharCodes('shared'.codeUnits);
final bytes = fory.serialize(<Object?>[shared, shared], trackRef: true);
final roundTrip = fory.deserialize<List<Object?>>(bytes);
print(identical(roundTrip[0], roundTrip[1]));
```

Inside generated structs, prefer field-level reference metadata with `@ForyField(ref: true)`.

## Buffer-Based APIs

Use `serializeTo` and `deserializeFrom` when you want explicit `Buffer` reuse.

```dart
final fory = Fory();
final buffer = Buffer();

fory.serializeTo(Int32(42), buffer);
final value = fory.deserializeFrom<Int32>(buffer);
```

`serializeTo` clears the target buffer before writing. `deserializeFrom` consumes bytes from the buffer's current reader position.

## Generated Registration Before Use

Generated and manual user-defined types must be registered before use.

```dart
PersonFory.register(
  fory,
  Person,
  id: 100,
);
```

See [Type Registration](type-registration.md) and [Code Generation](code-generation.md).

## Related Topics

- [Configuration](configuration.md)
- [Type Registration](type-registration.md)
- [Field Configuration](field-configuration.md)
