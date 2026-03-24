# Apache Fory™ Dart

## Overview

This PR adds Dart language support to Apache Fory™, implementing a comprehensive serialization solution for Dart and Flutter applications. Apache Fory™ Dart consists of approximately 15,000 lines of code and provides an efficient serialization mechanism that works within Flutter's reflection limitations.

## Implementation Approach

Dart supports reflection, but Flutter explicitly prohibits it. To address this constraint, Apache Fory™ Dart uses a combination of:

1. Core serialization/deserialization logic
2. Static code generation for type handling

This approach ensures compatibility with Flutter while maintaining the performance and flexibility expected from Apache Fory™.

## Features

- XLANG mode support for cross-language serialization
- Reference tracking for handling object graphs
- Support for primitive types, collections, and custom classes
- Serializer registration system
- Code generation for class and enum serialization
- Support for nested collections with automatic generic type conversion
- Custom serializer registration
- Support for using ByteWriter/ByteReader as serialization sources

## Usage Examples

### Basic Class Serialization

```dart
import 'package:fory/fory.dart';

part 'example.g.dart';

@foryClass
class SomeClass {
  late int id;
  late String name;
  late Map<String, double> map;

  SomeClass(this.id, this.name, this.map);

  SomeClass.noArgs();
}
```

After annotating your class with `@foryClass`, run:

```bash
dart run build_runner build
```

This generates the necessary schema metadata in `example.g.dart`.

### Serializing and Deserializing

```dart
Fory fory = Fory(ref: true);
fory.registerStruct(SomeClass, typename: "example.SomeClass");
SomeClass obj = SomeClass(1, 'SomeClass', {'a': 1.0});

// Serialize
Uint8List bytes = fory.serialize(obj);

// Deserialize
obj = fory.deserialize(bytes) as SomeClass;
```

### Enum Serialization

```dart
import 'package:fory/fory.dart';

part 'example.g.dart';

@foryEnum
enum EnumFoo {
  A,
  B
}
```

Registration is similar to classes:

```dart
fory.registerEnum(EnumFoo, typename: "example.EnumFoo");
```

## Type Support

Fory Dart currently supports the following type mappings in XLANG mode:

| Fory Type     | Dart Type                                  |
| ------------- | ------------------------------------------ |
| bool          | bool                                       |
| int8          | fory.Int8                                  |
| int16         | fory.Int16                                 |
| int32         | fory.Int32                                 |
| var_int32     | fory.Int32                                 |
| int64         | int                                        |
| var_int64     | int                                        |
| sli_int64     | int                                        |
| float32       | fory.Float32                               |
| float64       | double                                     |
| string        | String                                     |
| enum          | Enum                                       |
| named_enum    | Enum                                       |
| named_struct  | class                                      |
| list          | List                                       |
| set           | Set (LinkedHashSet, HashSet, SplayTreeSet) |
| map           | Map (LinkedHashMap, HashMap, SplayTreeMap) |
| timestamp     | fory.TimeStamp                             |
| local_date    | fory.LocalDate                             |
| binary        | Uint8List                                  |
| bool_array    | BoolList                                   |
| int8_array    | Int8List                                   |
| int16_array   | Int16List                                  |
| int32_array   | Int32List                                  |
| int64_array   | Int64List                                  |
| float32_array | Float32List                                |
| float64_array | Float64List                                |

## Project Structure

The implementation is organized into three main components:

1. **Codegen**: Located at `dart/packages/fory/lib/src/codegen`  
   Handles static code generation for serialization/deserialization.

2. **ForyCore**: Located at `dart/packages/fory/lib/src`  
   Contains the core serialization and deserialization logic.

3. **ForyTest**: Located at `dart/fory_test`  
   Comprehensive test suite for Apache Fory™ Dart functionality.

## Testing Approach

The test suite is inspired by Apache Fory™ Java's testing approach and includes:

- **Datatype Tests**: Validates custom data types implemented for Dart
- **Code Generation Tests**: Ensures correctness of the generated static code
- **Buffer Tests**: Validates correct memory handling for primitive types
- **Cross-Language Tests**: Tests functionality against other Apache Fory™ implementations
- **Performance Tests**: Simple benchmarks for serialization/deserialization performance

### Running Tests

Tests use the standard [dart test](https://pub.dev/packages/test) framework.

To run tests:

```bash
# First, generate necessary code
cd fory-test
dart run build_runner build

# Run all tests
dart test

# For more options (skipping tests, platform-specific tests, etc.)
# See: https://github.com/dart-lang/test/blob/master/pkgs/test/README.md
```

## Code Quality

Apache Fory™ Dart maintains high code quality standards. You can verify this using:

```bash
dart analyze
dart fix --dry-run
dart fix --apply
```

## Current Limitations

- Only supports XLANG mode (priority was given to cross-language compatibility)
- No out-of-band buffer functionality
- No data type compression (e.g., String compression)
- Generic parameters in user-defined types can be serialized but require manual type conversion after deserialization

## Development Information

- **Dart SDK**: 3.6.1

## Dependencies

### fory package:

```
analyzer: '>=6.5.0 <8.0.0'
build: ^2.4.1
build_config: ^1.1.0
collection: ^1.19.1
meta: ^1.14.0
source_gen: ^2.0.0
glob: ^2.1.3
decimal: ^3.2.1
lints: ^5.0.0
test: ^1.24.4
```

### fory-test package:

```
lints: ^5.0.0
build: ^2.4.2
build_runner: ^2.4.15
test: ^1.24.0
checks: ^0.3.0
build_test: ^2.2.3
analyzer: '>=6.5.0 <8.0.0'
collection: ^1.19.1
```
