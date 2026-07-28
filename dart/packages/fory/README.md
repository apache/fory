# Apache Fory™ Dart

Apache Fory™ Dart is the Dart xlang implementation for
[Apache Fory™](https://github.com/apache/fory). It reads and writes Fory's
cross-language wire format and is designed around generated serializers for
annotated Dart models, with manual serializers available for advanced use
cases.

## Features

- Cross-language serialization with the Fory xlang format
- Dart VM/AOT, Flutter, and web platform support
- Generated serializers for annotated structs and enums
- Flattened superclass and mixin storage for ordinary generated structs
- External structural serializers for classes owned by another package
- Compatible mode for schema evolution
- Optional reference tracking for shared and circular object graphs
- Manual serializers for custom payloads, construction, and unions
- Explicit exact-width value classes for `Int64`, `Uint64`, `Float32`,
  `LocalDate`, and `Timestamp`, plus `Duration` support

## Getting Started

Add `fory` to your package dependencies.

```yaml
dependencies:
  fory: ^1.5.0

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

  @ForyField(type: Int32Type())
  int age = 0;
  Color favoriteColor = Color.red;
  List<String> tags = <String>[];
}

void main() {
  final fory = Fory();

  PersonForyModule.register(
    fory,
    Color,
    name: 'example.Color',
  );
  PersonForyModule.register(
    fory,
    Person,
    name: 'example.Person',
  );

  final person = Person()
    ..name = 'Ada'
    ..age = 36
    ..favoriteColor = Color.blue
    ..tags = <String>['engineer', 'mathematician'];

  final bytes = fory.serialize(person);
  final roundTrip = fory.deserialize<Person>(bytes);

  print(roundTrip.name);
}
```

Generate the companion file before running the program:

```bash
dart run build_runner build
```

## Ordinary Struct Inheritance

```dart
class MessageBase {
  int sequence = 0;
}

@ForyStruct()
class TextMessage extends MessageBase {
  TextMessage();

  String text = '';
}
```

The generated `TextMessage` schema contains both `sequence` and `text`.
Superclass and applied-mixin fields are flattened into one child serializer.
See [Struct Inheritance](https://fory.apache.org/docs/guide/dart/inheritance)
for private fields, mixins, constructors, and
`ignoreInheritedPrivateFields`.

## External-Type Serialization

Use `ForyStruct.target` when another package owns a class whose public schema
can be read and reconstructed directly:

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

The declaration is schema-only.
Use `@ForyField(ignore: true)` for declaration-only storage that should count
toward the graph-memory budget without being serialized.

Register and serialize the target:

```dart
ExternalSerializersForyModule.register(
  fory,
  third_party.User,
  name: 'example.User',
);

final bytes = fory.serialize(user);
final decoded = fory.deserialize<third_party.User>(bytes);
```

Fields and nested `List`, `Set`, and `Map` values use the same target
registration. Select a public named generative constructor with
`constructor: 'name'` when the unnamed constructor is not appropriate.
External declarations remain explicit schemas: they may list an accessible
inherited target property, but Fory does not scan the external target's
hierarchy automatically. Neither `exposePrivateFields` nor
`ignoreInheritedPrivateFields` is valid with `target`.

## Type Registration

Generated types register through the generated Fory module. The module
class is named `<FileName>ForyModule` based on the source file that contains the
annotated types.

```dart
PersonForyModule.register(fory, Person, id: 100);
```

Or use named registration:

```dart
PersonForyModule.register(
  fory,
  Person,
  name: 'example.Person',
);
```

Exactly one registration mode is required:

- `id: ...`
- `name: ...`

Use `.` inside `name` to add a namespace prefix, for example `example.Person`.

Keep the same registration identity on every peer that exchanges the type.

## Configuration

```dart
final fory = Fory(
  maxDepth: 256,
);
```

| Option               | Default | Description                                             |
| -------------------- | ------- | ------------------------------------------------------- |
| `compatible`         | `true`  | Enables compatible struct encoding for schema evolution |
| `checkStructVersion` | `false` | Validates struct version for same-schema payloads       |
| `maxDepth`           | `256`   | Maximum nesting depth per operation                     |

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

Included inherited field metadata enters this same reference analysis.
Inheritance does not add a second reference owner or change the runtime
reference protocol.

## Field Annotations

`@ForyField()` controls per-field serialization behavior:

| Option     | Description                                         |
| ---------- | --------------------------------------------------- |
| `ignore`   | Exclude the declaring storage field from the schema |
| `id`       | Stable field ID for compatible-mode evolution       |
| `nullable` | Override nullability inference                      |
| `ref`      | Enable reference tracking for this field            |
| `dynamic`  | Control whether runtime type metadata is written    |

`type:` is the canonical override surface for nested field semantics:

```dart
@MapField(
  value: ListType(
    element: Int32Type(encoding: Encoding.fixed),
  ),
)
Map<String, List<int?>> nested = <String, List<int?>>{};
```

## Manual Serializers

Use `Serializer<T>` when a type needs custom wire behavior, field conversion,
or construction that generated ordinary or external structural serializers
cannot prove.

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
    name: 'example.Person',
  );

  final bytes = fory.serialize(Person('Ada', 36));
  final roundTrip = fory.deserialize<Person>(bytes);
  print(roundTrip.name);
}
```

## Type Mapping

Dart has no native fixed-width 8/16/32-bit integer, unsigned 64-bit integer,
or reduced/single-precision float scalar types. Fory Dart uses plain Dart `int`
or `double` plus field annotations for exact wire widths, keeps `Int64` and
`Uint64` for full-range 64-bit values, and keeps `Float32` for single-precision
rounding. For 16-bit floating-point arrays, Dart exposes `Float16List` and
`Bfloat16List` as contiguous fixed-length buffers.

| Fory xlang type | Dart type                                       |
| --------------- | ----------------------------------------------- |
| bool            | `bool`                                          |
| int8            | `int` + `@ForyField(type: Int8Type())`          |
| int16           | `int` + `@ForyField(type: Int16Type())`         |
| int32           | `int` + `@ForyField(type: Int32Type())`         |
| int64           | `int` or `fory.Int64`                           |
| uint8           | `int` + `@ForyField(type: Uint8Type())`         |
| uint16          | `int` + `@ForyField(type: Uint16Type())`        |
| uint32          | `int` + `@ForyField(type: Uint32Type())`        |
| uint64          | `fory.Uint64` (wrapper)                         |
| float16         | `double` + `@ForyField(type: Float16Type())`    |
| bfloat16        | `double` + `@ForyField(type: Bfloat16Type())`   |
| float32         | `fory.Float32` (wrapper)                        |
| float64         | `double`                                        |
| string          | `String`                                        |
| binary          | `Uint8List`                                     |
| duration        | `Duration`                                      |
| local_date      | `LocalDate`                                     |
| timestamp       | `Timestamp`                                     |
| list            | `List`                                          |
| set             | `Set`                                           |
| map             | `Map`                                           |
| enum            | `enum`                                          |
| named_struct    | `class`                                         |
| array<bool>     | `BoolList` + `@ArrayField(element: BoolType())` |
| array<int8>     | `Int8List`                                      |
| array<int16>    | `Int16List`                                     |
| array<int32>    | `Int32List`                                     |
| array<int64>    | `Int64List`                                     |
| array<uint8>    | `Uint8List`                                     |
| array<uint16>   | `Uint16List`                                    |
| array<uint32>   | `Uint32List`                                    |
| array<uint64>   | `Uint64List`                                    |
| array<float16>  | `Float16List`                                   |
| array<bfloat16> | `Bfloat16List`                                  |
| array<float32>  | `Float32List`                                   |
| array<float64>  | `Float64List`                                   |

## Public API

The main exported API includes:

- `Fory` — main serialization facade
- `Config` — Fory configuration
- `ForyStruct`, including `target`, `constructor`, `exposePrivateFields`, and
  `ignoreInheritedPrivateFields`, plus `ForyField`, `ListField`, `SetField`,
  and `MapField` — struct annotations
- `ForyUnion` — union type annotation
- `Serializer`, `UnionSerializer`, `EnumSerializer` — serializer base classes
- `Buffer`, `WriteContext`, `ReadContext` — low-level I/O
- `TypeSpec`, `DeclaredType`, `ListType`, `SetType`, `MapType` — nested type
  annotations
- `Int8Type`, `Int16Type`, `Int32Type`, `Int64Type`, `Uint8Type`, `Uint16Type`,
  `Uint32Type`, `Uint64Type`, `Float16Type`, `Bfloat16Type`, `Float32Type` —
  scalar wire-type overrides
- Numeric value wrappers: `Int64`, `Uint64`, `Float32`
- Temporal types: `LocalDate`, `Timestamp`, `Duration`

## Cross-Language Notes

- Fory Dart only supports xlang payloads.
- Register user-defined types before serialization or deserialization.
- Keep numeric IDs or `name` mappings consistent across
  languages.
- Use Dart `int` plus `@ForyField(type: ...)` for 8/16/32-bit integer fields,
  Dart `double` plus `Float16Type` or `Bfloat16Type` for 16-bit
  floating-point fields, and `Int64` / `Uint64` when full-range 64-bit values
  matter.

For the xlang wire format and type mapping details, see the
[Apache Fory specification](https://github.com/apache/fory/tree/main/docs/specification).

For the full Dart guide, see
[https://fory.apache.org/docs/guide/dart/](https://fory.apache.org/docs/guide/dart/).
