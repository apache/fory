---
title: Custom Serializers
sidebar_position: 5
id: dart_custom_serializers
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

A custom serializer lets you control exactly how a type is encoded and decoded. You only need one when:

- the type comes from a package you cannot modify and cannot be annotated with `@ForyStruct()`
- you need a completely custom binary layout
- you are implementing a union/discriminated type

For your own models, `@ForyStruct()` with code generation is almost always the better choice.

## Implement `Serializer<T>`

Subclass `Serializer<T>` and implement `write` and `read`. Use `context.buffer` to read and write raw bytes:

```dart
import 'package:fory/fory.dart';

final class Person {
  Person(this.name, this.age);

  final String name;
  final int age;
}

final class PersonSerializer extends Serializer<Person> {
  const PersonSerializer();

  @override
  void write(WriteContext context, Person value) {
    final buffer = context.buffer;
    buffer.writeUtf8(value.name);
    buffer.writeInt64FromInt(value.age);
  }

  @override
  Person read(ReadContext context) {
    final buffer = context.buffer;
    return Person(buffer.readUtf8(), buffer.readInt64AsInt());
  }
}
```

Register the serializer before you use it:

```dart
final fory = Fory();
fory.registerSerializer(
  Person,
  const PersonSerializer(),
  namespace: 'example',
  typeName: 'Person',
);
```

## Writing Nested Objects

When your serializer has a field that is itself a Fory-managed type, use `context.writeRef` and `context.readRef` rather than calling `fory.serialize` recursively. This keeps reference tracking correct and avoids writing a full root frame inside a nested payload.

```dart
@override
void write(WriteContext context, Wrapper value) {
  context.writeRef(value.child);
}

@override
Wrapper read(ReadContext context) {
  return Wrapper(context.readRef() as Child);
}
```

If you do not need reference identity tracking for a nested value (i.e., you know the value will never appear more than once in a graph), use `writeNonRef`:

```dart
context.writeNonRef(value.child);
```

## Unions

For a discriminated/tagged union, extend `UnionSerializer<T>` instead of `Serializer<T>`. Write a discriminant value first, then the active variant; read the discriminant and dispatch accordingly.

```dart
final class ShapeSerializer extends UnionSerializer<Shape> {
  const ShapeSerializer();

  @override
  void write(WriteContext context, Shape value) {
    // write active variant
  }

  @override
  Shape read(ReadContext context) {
    // read discriminant, return correct variant
    throw UnimplementedError();
  }
}
```

## Circular References in Custom Serializers

If your serializer can encounter circular object graphs, bind the object to the reference tracker **before** reading its nested fields:

```dart
final value = Node.empty();
context.reference(value);         // register the object first
value.next = context.readRef() as Node?;  // now nested reads can refer back to it
return value;
```

Skipping this step causes back-references to that object to resolve to `null`.

## Tips

- Use `context.buffer` for direct byte reads/writes in hot paths.
- Register the serializer with the same identity (`id` or `namespace + typeName`) on every side.

## Related Topics

- [Type Registration](type-registration.md)
- [Cross-Language](cross-language.md)
- [Troubleshooting](troubleshooting.md)
