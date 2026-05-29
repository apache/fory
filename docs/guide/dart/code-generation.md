---
title: Code Generation
sidebar_position: 3
id: code_generation
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

Fory generates fast serializer code for your Dart classes at build time. You annotate your models, run `build_runner`, and Fory takes care of the rest.

## Step 1 — Annotate Your Models

Add `@ForyStruct()` to each class you want to serialize. Include the generated part directive at the top of the file.

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

  @ForyField(type: Int32Type())
  int age = 0;
  Address address = Address();
}
```

Enums defined in the same file are automatically included in the generated registration.

## Step 2 — Run the Generator

From the directory that contains your `pubspec.yaml`:

```bash
dart run build_runner build --delete-conflicting-outputs
```

This emits a `.fory.dart` file next to your source file. Re-run this command any time you add or rename annotated types.

## Step 3 — Register and Use

The generator creates a namespace (named after your file) with a `register` function. Call it before serializing:

```dart
final fory = Fory();
ModelsForyModule.register(fory, Address, id: 1);
ModelsForyModule.register(fory, User, id: 2);
```

Or use a stable name instead of a numeric ID (useful for cross-language scenarios):

```dart
ModelsForyModule.register(
  fory,
  User,
  namespace: 'example',
  typeName: 'User',
);
```

See [Type Registration](type-registration.md) for guidance on choosing between IDs and names.

## Schema Evolution: `evolving`

`@ForyStruct()` defaults to `evolving: true`, which is the right choice for most applications.

- `evolving: true` — Fory stores enough metadata so that if you add or remove fields later, old and new code can still exchange messages. Enable this whenever different versions of your app or service may be running at the same time.
- `evolving: false` — No extra metadata; marginally smaller payloads. Safe only when both writer and reader are always updated together.

```dart
// evolving: true is the default, you can omit it
@ForyStruct(evolving: true)
class Event {
  Event();

  String name = '';
}
```

When using evolving structs, also assign stable field IDs with `@ForyField(id: ...)` before you ship your first payload — those IDs are how Fory matches fields after a schema change.

## When Not to Use Code Generation

If you cannot annotate a type (e.g., it comes from a package you do not own), write a [Custom Serializer](custom-serializers.md) instead.

## Related Topics

- [Type Registration](type-registration.md)
- [Schema Metadata](schema-metadata.md)
- [Schema Evolution](schema-evolution.md)
