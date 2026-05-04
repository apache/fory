# Apache Fory™ Dart

Apache Fory™ Dart is the Dart xlang runtime for Apache Fory™. It reads and
writes Fory's cross-language wire format and works in both Dart and Flutter
applications. Because Flutter prohibits `dart:mirrors`, the runtime uses static
code generation for type handling.

The publishable package lives at `packages/fory/`. See its
[README](packages/fory/README.md) for the full user-facing documentation
including getting started, API reference, and code examples.

## Project Structure

| Directory                        | Description                             |
| -------------------------------- | --------------------------------------- |
| `packages/fory/lib/`             | Core runtime and public API             |
| `packages/fory/lib/src/codegen/` | Build-runner code generator             |
| `packages/fory/example/`         | Annotated example with generated output |
| `packages/fory/test/`            | Unit and integration tests              |
| `test/`                          | Cross-language integration tests        |

## Type Mapping

| Fory xlang type | Dart type                                       |
| --------------- | ----------------------------------------------- |
| bool            | `bool`                                          |
| int8            | `int` + `@ForyField(type: Int8Type())`          |
| int16           | `int` + `@ForyField(type: Int16Type())`         |
| int32           | `int` + `@ForyField(type: Int32Type())`         |
| int64           | `int` or `Int64`                                |
| uint8           | `int` + `@ForyField(type: Uint8Type())`         |
| uint16          | `int` + `@ForyField(type: Uint16Type())`        |
| uint32          | `int` + `@ForyField(type: Uint32Type())`        |
| uint64          | `int` or `Uint64`                               |
| float16         | `fory.Float16` (wrapper)                        |
| float32         | `fory.Float32` (wrapper)                        |
| float64         | `double`                                        |
| string          | `String`                                        |
| binary          | `Uint8List`                                     |
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

## Quick Start

Annotate your model and run the code generator:

```dart
import 'package:fory/fory.dart';

part 'person.fory.dart';

@ForyStruct()
class Person {
  Person();

  String name = '';

  @ForyField(type: Int32Type())
  int age = 0;
}
```

```bash
dart run build_runner build --delete-conflicting-outputs
```

Serialize and deserialize:

```dart
final fory = Fory();
PersonFory.register(fory, Person, namespace: 'example', typeName: 'Person');

final bytes = fory.serialize(Person()..name = 'Ada'..age = 36);
final roundTrip = fory.deserialize<Person>(bytes);
```

## Development

Run tests from the workspace root:

```bash
cd packages/fory
dart test
```

Run the code generator on the example:

```bash
cd packages/fory
dart run build_runner build --delete-conflicting-outputs
```
