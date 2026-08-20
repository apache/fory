---
title: External-Type Serialization
sidebar_position: 9
id: external-types
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

External-type serialization generates a Fory struct serializer for a class
owned by another Dart library or package. Define a local serializer declaration
with the same fields and name the external class with `ForyStruct.target`.

## Define an External Structural Serializer

Suppose a dependency owns this class:

```dart
final class User {
  const User({
    required this.name,
    required this.age,
  });

  final String name;
  final int age;
}
```

Add the serializer declaration in your package:

```dart
import 'package:fory/fory.dart';
import 'package:third_party/models.dart' as third_party;

part 'external_serializers.fory.dart';

@ForyStruct(target: third_party.User)
abstract final class UserSerializer {
  @ForyField(id: 1)
  late final String name;

  @ForyField(id: 2, type: Int32Type())
  late final int age;
}
```

The declaration must be `abstract final`, cannot have type parameters, and
must declare each schema field as `late final` without an initializer. Each
serialized field name and Dart type, including nullability and generic
arguments, must exactly match an accessible getter on the target.

The declaration's field list is the complete external schema. Fory does not
automatically add fields from the target, its superclass, or its mixins. You
may explicitly declare an accessible inherited target property just like any
other schema field. `exposePrivateFields` and
`ignoreInheritedPrivateFields` apply only to ordinary Dart hierarchies and are
invalid with `ForyStruct.target`.

Public target fields count toward the graph-memory budget automatically, but
that accounting does not add them to the serialized schema. Use
`@ForyField(ignore: true)` on an extra declaration field to count other storage
without serializing it.

Run the generator as usual:

```bash
dart run build_runner build
```

## Register and Use the Target

Register the external target through the generated module:

```dart
final fory = Fory();
ExternalSerializersForyModule.register(
  fory,
  third_party.User,
  name: 'example.User',
);

final bytes = fory.serialize(
  const third_party.User(name: 'Ada', age: 36),
);
final user = fory.deserialize<third_party.User>(bytes);
```

Numeric IDs work the same way:

```dart
ExternalSerializersForyModule.register(
  fory,
  third_party.User,
  id: 100,
);
```

Register the target `third_party.User`, not `UserSerializer`.

## Constructors and Mutable Targets

Without a `constructor` option, generation uses the target's public unnamed
generative constructor. Constructor parameters map to schema fields by name
and must use exactly the same Dart types.

For example, suppose the dependency exposes this immutable class:

```dart
final class Money {
  const Money.fromParts({
    required this.currency,
    required this.units,
  });

  final String currency;
  final int units;
}
```

Select its public named generative constructor in the serializer declaration:

```dart
@ForyStruct(
  target: third_party.Money,
  constructor: 'fromParts',
)
abstract final class MoneySerializer {
  late final String currency;

  @ForyField(type: Int64Type())
  late final int units;
}
```

After decoding the fields, the generated serializer calls
`third_party.Money.fromParts(currency: ..., units: ...)`.

For a mutable target, a public generative constructor with no required
arguments plus matching setters allows Fory to construct the target first and
then assign its fields. This also supports cycles when reference tracking is
enabled. For example, suppose the dependency exposes:

```dart
final class Node {
  Node.empty();

  late String label;
  Node? next;
}
```

Select `Node.empty` in the serializer declaration:

```dart
@ForyStruct(
  target: third_party.Node,
  constructor: 'empty',
)
abstract final class NodeSerializer {
  late final String label;

  @ForyField(ref: true)
  late final third_party.Node? next;
}
```

The generated serializer can call `third_party.Node.empty()`, publish the new
node for reference tracking, and then assign `label` and `next`.

A constructor-based target cannot decode a statically known reference-tracked
path back to itself because the target does not exist until its constructor
arguments have been read. This includes the target nested as a `List` or `Set`
element or as a `Map` key or value. Generation rejects these schemas. Use a
mutable two-phase target or a custom serializer when cycles are required.
Indirect cycles that cannot be determined from the declaration are also
unsupported for constructor-based targets.

Factory constructors, private constructors, abstract targets, external enums,
external unions, records, extension types, and built-in collection types are
not external struct targets.

## Fields and Collections

After registration, the target works anywhere an ordinary registered struct
works. A containing generated class does not select the serializer declaration:

```dart
@ForyStruct()
final class Group {
  Group();

  third_party.User? owner;

  @ListField(element: DeclaredType())
  List<third_party.User> users = <third_party.User>[];

  @MapField(value: DeclaredType())
  Map<String, third_party.User> usersByName =
      <String, third_party.User>{};
}
```

Nested lists, sets, and maps resolve the registered target recursively.
Dynamic fields and heterogeneous collections also resolve each concrete target
by its registered type:

```dart
@ForyField(dynamic: true)
Object? value;
```

Register every concrete external type that can appear dynamically.

Non-empty root lists, sets, and maps decode their elements, keys, and values as
the registered external targets. Dart root collections retain their existing
untyped outer shapes, so read a root collection as `Object?` and cast its outer
carrier:

```dart
final decoded =
    fory.deserialize<Object?>(
          fory.serialize(<third_party.User>[user]),
        )
        as List<Object?>;
final first = decoded.first as third_party.User;
```

An empty root collection contains no element type identity.

## Closed Generic Targets

Suppose the dependency exposes this generic class:

```dart
final class Box<T> {
  const Box(this.value);

  final T value;
}
```

`Box<T>` is open because `T` is unresolved. Supplying a concrete type argument,
such as `Box<String>`, produces a closed generic instantiation that code
generation can analyze:

```dart
@ForyStruct(target: third_party.Box<String>)
abstract final class StringBoxSerializer {
  late final String value;
}
```

The generated serializer reconstructs values with
`third_party.Box<String>(...)` and applies only to `Box<String>`. Register and
use that exact target type directly:

```dart
final fory = Fory();
ExternalSerializersForyModule.register(
  fory,
  third_party.Box<String>,
  id: 102,
);

final input = const third_party.Box<String>('hello');
final bytes = fory.serialize(input);
final output = fory.deserialize<third_party.Box<String>>(bytes);

print(output.value); // hello
```

Register `third_party.Box<String>`, not `StringBoxSerializer`. A different
instantiation such as `Box<int>` needs its own serializer declaration and
registration; one declaration does not cover every `Box<T>`.

## Schema Evolution

`evolving` and field IDs work exactly as they do for an ordinary generated
struct:

```dart
@ForyStruct(
  target: third_party.User,
  evolving: true,
)
abstract final class UserSerializer {
  @ForyField(id: 1)
  late final String name;
}
```

Keep field IDs and the registered type identity stable across peers. A field
name must still match the corresponding property on the local target class.

## When to Use a Custom Serializer

Use a [custom serializer](custom-serializers.md) when the target needs a custom
wire body, field-name translation, value conversion, factory-only
construction, private state, or any reconstruction rule that cannot be
expressed through matching public getters, constructor parameters, and
setters.

## Related Topics

- [Struct Inheritance](inheritance.md)
- [Code Generation](code-generation.md)
- [Type Registration](type-registration.md)
- [Schema Metadata](schema-metadata.md)
- [Schema Evolution](schema-evolution.md)
- [Custom Serializers](custom-serializers.md)
