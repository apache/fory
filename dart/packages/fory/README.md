# Apache Fory™ Dart

Apache Fory™ Dart is the Dart xlang runtime for
[Apache Fory™](https://github.com/apache/fory). It reads and writes Fory's
cross-language wire format and is designed around generated serializers for
annotated Dart models, with customized serializers available for advanced use
cases.

## Features

- Cross-language serialization with the Fory xlang format
- Dart VM/AOT, Flutter, and web platform support
- Generated serializers for annotated structs and enums
- Compatible mode for schema evolution
- Optional reference tracking for shared and circular object graphs
- Manual serializers for external types, custom payloads, and unions
- Explicit xlang value class such as `Int32`, `Int64`, `Uint32`, `Uint64`,
  `Float16`, `Bfloat16`, `Float32`, `LocalDate`, and `Timestamp`, plus `Duration`
  support

## Getting Started

Add `fory` to your package dependencies.

```yaml
dependencies:
  fory: ^0.17.0

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

Generated types register through the generated library namespace. The namespace
class is named `<FileName>Fory` based on the source file that contains the
annotated types.

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

```dart
final fory = Fory(
  compatible: true,
  maxDepth: 256,
  maxCollectionSize: 1 << 20,
  maxBinarySize: 64 * 1024 * 1024,
);
```

| Option               | Default    | Description                                             |
| -------------------- | ---------- | ------------------------------------------------------- |
| `compatible`         | `false`    | Enables compatible struct encoding for schema evolution |
| `checkStructVersion` | `true`     | Validates struct version in schema-consistent mode      |
| `maxDepth`           | `256`      | Maximum nesting depth per operation                     |
| `maxCollectionSize`  | `1 << 20`  | Maximum collection and map payload size                 |
| `maxBinarySize`      | `64 << 20` | Maximum binary payload size                             |

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

## Field Annotations

`@ForyField()` controls per-field serialization behavior:

| Option     | Description                                      |
| ---------- | ------------------------------------------------ |
| `skip`     | Skip the field during serialization              |
| `id`       | Stable field ID for compatible-mode evolution    |
| `nullable` | Override nullability inference                   |
| `ref`      | Enable reference tracking for this field         |
| `dynamic`  | Control whether runtime type metadata is written |

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
    buffer.writeInt64FromInt(value.age);
  }

  @override
  Person read(ReadContext context) {
    final buffer = context.buffer;
    return Person(buffer.readUtf8(), buffer.readInt64AsInt());
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

## Type Mapping

Dart has no native fixed-width 8/16/32-bit integer, unsigned 64-bit integer,
or single-precision float types. Fory Dart provides thin wrapper types
(`Int8`, `Int16`, `Int32`, `Int64`, `Uint8`, `Uint16`, `Uint32`, `Uint64`,
`Float16`, `Bfloat16`, `Float32`) imported from `package:fory/fory.dart` to
represent these xlang wire types. For 16-bit floating-point arrays, Dart exposes
`Float16List` and `Bfloat16List` as contiguous fixed-length buffers.

| Fory xlang type | Dart type                 |
| --------------- | ------------------------- |
| bool            | `bool`                    |
| int8            | `fory.Int8` (wrapper)     |
| int16           | `fory.Int16` (wrapper)    |
| int32           | `fory.Int32` (wrapper)    |
| int64           | `int` or `fory.Int64`     |
| uint8           | `fory.Uint8` (wrapper)    |
| uint16          | `fory.Uint16` (wrapper)   |
| uint32          | `fory.Uint32` (wrapper)   |
| uint64          | `fory.Uint64` (wrapper)   |
| float16         | `fory.Float16` (wrapper)  |
| bfloat16        | `fory.Bfloat16` (wrapper) |
| float32         | `fory.Float32` (wrapper)  |
| float64         | `double`                  |
| string          | `String`                  |
| binary          | `Uint8List`               |
| duration        | `Duration`                |
| local_date      | `LocalDate`               |
| timestamp       | `Timestamp`               |
| list            | `List`                    |
| set             | `Set`                     |
| map             | `Map`                     |
| enum            | `enum`                    |
| named_struct    | `class`                   |
| bool_array      | `List<bool>`              |
| int8_array      | `Int8List`                |
| int16_array     | `Int16List`               |
| int32_array     | `Int32List`               |
| int64_array     | `Int64List`               |
| uint16_array    | `Uint16List`              |
| uint32_array    | `Uint32List`              |
| uint64_array    | `Uint64List`              |
| float16_array   | `Float16List`             |
| bfloat16_array  | `Bfloat16List`            |
| float32_array   | `Float32List`             |
| float64_array   | `Float64List`             |

## Public API

The main exported API includes:

- `Fory` — main serialization facade
- `Config` — runtime configuration
- `ForyStruct`, `ForyField` — struct annotations
- `ForyUnion` — union type annotation
- `Serializer`, `UnionSerializer`, `EnumSerializer` — serializer base classes
- `Buffer`, `WriteContext`, `ReadContext` — low-level I/O
- `TypeSpec`, `ListType`, `MapType`, `ValueType` — nested container type
  annotations
- `Int32Type`, `Int64Type`, `Uint32Type`, `Uint64Type` — numeric encoding
  overrides
- Numeric wrappers: `Int8`, `Int16`, `Int32`, `Int64`, `Uint8`, `Uint16`,
  `Uint32`, `Uint64`, `Float16`, `Bfloat16`, `Float32`
- Temporal types: `LocalDate`, `Timestamp`, `Duration`

## Cross-Language Notes

- The Dart runtime only supports xlang payloads.
- Register user-defined types before serialization or deserialization.
- Keep numeric IDs or `namespace + typeName` mappings consistent across
  languages.
- Use wrappers or numeric field annotations when the exact xlang wire type
  matters.

For the xlang wire format and type mapping details, see the
[Apache Fory specification](https://github.com/apache/fory/tree/main/docs/specification).

For the full Dart guide, see
[https://fory.apache.org/docs/guide/dart/](https://fory.apache.org/docs/guide/dart/).
