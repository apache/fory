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

Use customized serializers when generated struct support is not the right fit.

## When Customized Serializers Make Sense

Typical cases include:

- external types you cannot annotate
- custom payload layouts
- customized extension types
- unions built on the `UnionSerializer<T>` base class

## Implement `Serializer<T>`

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
    buffer.writeInt64(value.age);
  }

  @override
  Person read(ReadContext context) {
    final buffer = context.buffer;
    return Person(buffer.readUtf8(), buffer.readInt64());
  }
}
```

Register it before use:

```dart
final fory = Fory();
fory.registerSerializer(
  Person,
  const PersonSerializer(),
  namespace: 'example',
  typeName: 'Person',
);
```

## Use `WriteContext` and `ReadContext`

Manual serializers should do nested xlang work through the context rather than calling root APIs recursively.

### Write nested values with reference handling

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

### Write values without seeding references

`writeNonRef` writes a non-reference payload and does not seed later back-references.

```dart
context.writeNonRef(value.child);
```

### Fine-grained ref/value control

Use `writeRefValueFlag` when your serializer needs explicit control over whether payload bytes follow.

```dart
if (context.writeRefValueFlag(value.payload)) {
  context.writeNonRef(value.payload);
}
```

## Unions

Manual union serializers should extend `UnionSerializer<T>`.

```dart
final class ShapeSerializer extends UnionSerializer<Shape> {
  const ShapeSerializer();

  @override
  void write(WriteContext context, Shape value) {
    // write active alternative
  }

  @override
  Shape read(ReadContext context) {
    // read active alternative
    throw UnimplementedError();
  }
}
```

The xlang spec defines `UNION`, `TYPED_UNION`, `NAMED_UNION`, and `NONE` wire types. Use registrations that match the peers you interoperate with.

## Early Reference Binding During Reads

If your serializer allocates an object before all nested fields are read, bind it early so back-references can resolve to that instance.

```dart
final value = Node.empty();
context.reference(value);
value.next = context.readRef() as Node?;
return value;
```

## Best Practices

- Keep payload code focused on the payload only.
- Let `Fory` own the root frame and top-level reset lifecycle.
- Prefer direct buffer access for repeated primitive IO inside hot serializers.
- Register serializers consistently across all peers.

## Related Topics

- [Type Registration](type-registration.md)
- [Cross-Language](cross-language.md)
- [Troubleshooting](troubleshooting.md)
