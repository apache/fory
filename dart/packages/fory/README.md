# Apache Fory Dart

Apache Fory Dart is the Dart xlang runtime for Apache Fory. It reads and writes
Fory's cross-language wire format and is designed around generated serializers
for annotated Dart models, with customized serializers available for advanced use
cases.

## Features

- Cross-language serialization with the Fory xlang format
- Generated serializers for annotated structs and enums
- Compatible mode for schema evolution
- Optional reference tracking for shared and circular object graphs
- Manual serializers for external types, custom payloads, and unions
- Explicit xlang value wrappers such as `Int32`, `UInt32`, `Float16`,
  `Float32`, `LocalDate`, and `Timestamp`

## Getting Started

Add `fory` to your package dependencies.

```yaml
dependencies:
  fory: ^0.17.0-dev

dev_dependencies:
  build_runner: ^2.4.13
```

## Basic Usage

Use `@ForyStruct()` for generated struct serializers and include the generated
part file.

```dart
import 'package:fory/fory.dart';

part 'person.fory.dart';

enum Color {
  red,
  blue,
}

@ForyStruct()
class Person {
  Person();

  String name = '';
  Int32 age = Int32(0);
  Color favoriteColor = Color.red;
  List<String> tags = <String>[];
}

void main() {
  final fory = Fory();

  PersonFory.register(
    fory,
    Color,
    namespace: 'example',
    typeName: 'Color',
  );
  PersonFory.register(
    fory,
    Person,
    namespace: 'example',
    typeName: 'Person',
  );

  final person = Person()
    ..name = 'Ada'
    ..age = Int32(36)
    ..favoriteColor = Color.blue
    ..tags = <String>['engineer', 'mathematician'];

  final bytes = fory.serialize(person);
  final roundTrip = fory.deserialize<Person>(bytes);

  print(roundTrip.name);
}
```

Generate the companion file before running the program:

```bash
dart run build_runner build --delete-conflicting-outputs
```

## Type Registration

Generated types register through the generated library namespace.

```dart
PersonFory.register(fory, Person, id: 100);
```

Or use namespace and type name registration:

```dart
PersonFory.register(
  fory,
  Person,
  namespace: 'example',
  typeName: 'Person',
);
```

Exactly one registration mode is required:

- `id: ...`
- `namespace: ...` and `typeName: ...`

Keep the same registration identity on all runtimes that exchange the type.

## Configuration

Configure the runtime through `Config`.

```dart
final fory = Fory(
  compatible: true,
  maxDepth: 256,
  maxCollectionSize: 1 << 20,
  maxBinarySize: 64 * 1024 * 1024,
);
```

Key options:

- `compatible`: enables compatible struct encoding and decoding
- `checkStructVersion`: enables struct-version validation in
  schema-consistent mode
- `maxDepth`: limits nesting depth for one operation
- `maxCollectionSize`: limits collection and map payload sizes
- `maxBinarySize`: limits binary payload size

## Reference Tracking

Enable root-level reference tracking only when the root value itself is a graph
or container that needs shared-reference tracking.

```dart
final shared = String.fromCharCodes('shared'.codeUnits);
final bytes = fory.serialize(<Object?>[shared, shared], trackRef: true);
final roundTrip = fory.deserialize<List<Object?>>(bytes);
```

For generated structs, prefer field-level reference metadata:

```dart
@ForyStruct()
class NodeList {
  NodeList();

  @ForyField(ref: true)
  List<Object?> values = <Object?>[];
}
```

## Customized Serializers

Use `Serializer<T>` when a type cannot use generated struct support or when you
need custom wire behavior.

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

void main() {
  final fory = Fory();
  fory.registerSerializer(
    Person,
    const PersonSerializer(),
    namespace: 'example',
    typeName: 'Person',
  );

  final bytes = fory.serialize(Person('Ada', 36));
  final roundTrip = fory.deserialize<Person>(bytes);
  print(roundTrip.name);
}
```

## Public API

The main exported API includes:

- `Fory`
- `Config`
- `Buffer`
- `WriteContext`
- `ReadContext`
- `Serializer`
- `UnionSerializer`
- `ForyStruct`
- `ForyField`
- Numeric and temporal wrappers such as `Int8`, `Int16`, `Int32`, `UInt8`,
  `UInt16`, `UInt32`, `Float16`, `Float32`, `LocalDate`, and `Timestamp`

## Cross-Language Notes

- The Dart runtime only supports xlang payloads.
- Register user-defined types before serialization or deserialization.
- Keep numeric IDs or `namespace + typeName` mappings consistent across
  languages.
- Use wrappers or numeric field annotations when the exact xlang wire type
  matters.

For the xlang wire format and type mapping details, see the Apache Fory
specification in the main repository.
