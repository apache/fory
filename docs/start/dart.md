---
title: Dart Setup
sidebar_position: 9
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

Fory Dart provides xlang Object Serialization, generated models, and Fory gRPC.
It is published on pub.dev, requires Dart 3.7 or later, and uses `build_runner`
to generate serializers.

## Verify the Toolchain

```bash
dart --version
dart pub --help
```

## Object Serialization

Add Fory and the generator to `pubspec.yaml`:

```yaml
dependencies:
  fory: 1.6.1

dev_dependencies:
  build_runner: ^2.4.0
```

Create `lib/person.dart`:

```dart
import 'package:fory/fory.dart';

part 'person.fory.dart';

@ForyStruct()
class Person {
  Person();

  @ForyField(type: Int64Type())
  int id = 0;
  String name = '';
}

void main() {
  final fory = Fory();
  PersonForyModule.register(fory, Person, name: 'example.Person');

  final input = Person()
    ..id = 1
    ..name = 'Alice';
  final bytes = fory.serialize(input);
  final decoded = fory.deserialize<Person>(bytes);
  print(decoded.name);
}
```

Generate the serializer and run the example:

```bash
dart pub get
dart run build_runner build
dart run lib/person.dart
```

Dart uses xlang mode. Continue with
[Dart Object Serialization](../object-serialization/dart/index.md),
[code generation](../object-serialization/dart/code-generation.md),
[web support](../object-serialization/dart/web-platform-support.md), and
[schema evolution](../object-serialization/dart/schema-evolution.md).

## Other Capabilities

- **Fory IDL and Compiler** generates Dart models and registration helpers. See [Compiler Getting Started](../compiler/getting-started.md) and the [Dart generated-code guide](../compiler/generated-code/dart.md).
- **Fory gRPC** uses package:grpc transports with Fory-encoded messages. See [Dart gRPC](../grpc/dart.md).
