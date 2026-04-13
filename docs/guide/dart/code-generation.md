---
title: Code Generation
sidebar_position: 3
id: dart_code_generation
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

Fory Dart uses source generation for structs and enums defined in your Dart libraries.

## Annotate Struct Types

Mark classes with `@ForyStruct()` and include the generated part file.

```dart
import 'package:fory/fory.dart';

part 'models.fory.dart';

@ForyStruct()
class Address {
  Address();

  String city = '';
  String street = '';
}

@ForyStruct()
class User {
  User();

  String name = '';
  Int32 age = Int32(0);
  Address address = Address();
}
```

Enums in the same library also participate in generated registration.

## Run the Generator

From `dart/packages/fory` or the package that depends on the generator:

```bash
dart run build_runner build --delete-conflicting-outputs
```

The builder emits a `.fory.dart` part file next to the source library.

## Register Through the Generated Namespace

Generated code exposes a library-level namespace that knows how to install generated metadata into `Fory`.

```dart
final fory = Fory();
ModelsFory.register(fory, Address, id: 1);
ModelsFory.register(fory, User, id: 2);
```

Or use named registration:

```dart
ModelsFory.register(
  fory,
  User,
  namespace: 'example',
  typeName: 'User',
);
```

Exactly one registration mode is required:

- `id: ...`
- `namespace: ...` plus `typeName: ...`

## Choosing `evolving`

`@ForyStruct()` defaults to `evolving: true`.

```dart
@ForyStruct(evolving: true)
class Event {
  Event();

  String name = '';
}
```

Use `evolving: true` when you want compatible-mode field evolution. Use `evolving: false` for a fixed schema when you want schema-consistent behavior.

## Generated Registration Design

Generated registration keeps serializer metadata private to the defining library while exposing a public wrapper API. Applications should call the generated wrapper instead of private helper functions.

## When to Use Customized Serializers Instead

Use [Custom Serializers](custom-serializers.md) when:

- the type is external and cannot be annotated
- you need custom payload layout
- you are implementing a union or extension type manually

## Related Topics

- [Type Registration](type-registration.md)
- [Field Configuration](field-configuration.md)
- [Schema Evolution](schema-evolution.md)
