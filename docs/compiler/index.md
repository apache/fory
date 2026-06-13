---
title: Overview
sidebar_position: 1
id: index
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

Fory IDL is a schema definition language for Apache Fory that enables type-safe
cross-language serialization. Define your data structures once and generate
native data structure code for Java, Python, C++, Go, Rust,
JavaScript/TypeScript, C#, Swift, Dart, Scala, and Kotlin. Fory IDL can also
describe RPC services; for Java, Python, Go, Rust, C#, Scala, and Kotlin, the
compiler can generate gRPC service companions that use Fory serialization for
request and response payloads.

## Example Schema

Fory IDL provides a simple, intuitive syntax for defining cross-language data structures:

```protobuf
package example;

enum Status {
    PENDING = 0;
    ACTIVE = 1;
    COMPLETED = 2;
}

message User {
    string name = 1;
    int32 age = 2;
    optional string email = 3;
    list<string> tags = 4;
}

message Item {
    string sku = 1;
    int32 quantity = 2;
}

message Order {
    ref User customer = 1;
    list<Item> items = 2;
    Status status = 3;
    map<string, int32> metadata = 4;
}

message Dog [id=104] {
    string name = 1;
    int32 bark_volume = 2;
}

message Cat [id=105] {
    string name = 1;
    int32 lives = 2;
}

union Animal [id=106] {
    Dog dog = 1;
    Cat cat = 2;
}

message LookupRequest [id=107] {
    string name = 1;
}

message LookupResponse [id=108] {
    Animal animal = 1;
}

service AnimalService {
    rpc Lookup (LookupRequest) returns (LookupResponse);
    rpc Classify (Animal) returns (Animal);
}
```

Generate Java, Python, Rust, C#, Scala, and Kotlin models plus gRPC service companions with:

```bash
foryc animals.fdl --java_out=./generated/java --python_out=./generated/python --rust_out=./generated/rust --csharp_out=./generated/csharp --scala_out=./generated/scala --kotlin_out=./generated/kotlin --grpc
```

The generated service code uses normal gRPC APIs, but request and response
objects are serialized with Fory. Applications provide their own grpc-java,
grpc-kotlin, `grpcio`, grpc-go, Rust `tonic` and `bytes`, or C#
`Grpc.Core.Api` and hosting/client dependencies; Fory packages do not add gRPC
as a hard dependency.

## Why Fory IDL?

### Schema-First Development

Define your data model once in Fory IDL and generate consistent, type-safe code across all languages. This ensures:

- **Type Safety**: Catch type errors at compile time, not when the code runs
- **Consistency**: All languages use the same field names, types, and structures
- **Documentation**: Schema serves as living documentation
- **Evolution**: Managed schema changes across all implementations

### Fory-Native Features

Unlike generic IDLs, Fory IDL is designed specifically for Fory serialization:

- **Reference Tracking**: First-class support for shared and circular references via `ref`
- **Nullable Fields**: Explicit `optional` modifier for nullable types
- **Type Registration**: Built-in support for both numeric IDs and name-based registration
- **Native Code Generation**: Generates idiomatic code with Fory annotations/macros

### Low Integration Overhead

Generated code uses native language constructs:

- Java: Plain POJOs with `@ForyField` annotations
- Python: Dataclasses with type hints
- Go: Structs with struct tags
- Rust: Structs with `#[derive(ForyStruct)]`
- C++: Structs with `FORY_STRUCT` macros
- C#: `[ForyStruct]` classes, `[ForyEnum]` enums, `[ForyUnion]` unions, and registration helpers
- JavaScript/TypeScript: Interfaces with registration function
- Swift: Fory model macros with field/case metadata and registration helpers
- Dart: `@ForyStruct` classes with `@ForyField` annotations and registration helpers
- Scala: Scala 3 `case class`, normal class, enum, and ADT enum models with macro-derived serializers
- Kotlin: Kotlin `data class`, enum, and sealed class models with KSP-generated serializers

## Quick Start

### 1. Install the Compiler

```bash
pip install fory-compiler
```

Or install from source:

```bash
cd compiler
pip install -e .
```

### 2. Write Your Schema

Create `example.fdl`:

```protobuf
package example;

message Person {
    string name = 1;
    int32 age = 2;
    optional string email = 3;
}
```

### 3. Generate Code

```bash
# Generate for all languages
foryc example.fdl --output ./generated

# Generate for specific languages
foryc example.fdl --lang java,python,csharp,javascript,swift,dart,scala,kotlin --output ./generated
```

### 4. Use Generated Code

**Java:**

```java
Person person = new Person();
person.setName("Alice");
person.setAge(30);
byte[] data = person.toBytes();
```

**Python:**

```python
import pyfory
from example import Person

person = Person(name="Alice", age=30)
data = bytes(person) # or `person.to_bytes()`
```

## Documentation

| Document                                         | Description                                       |
| ------------------------------------------------ | ------------------------------------------------- |
| [Fory IDL Syntax](schema-idl.md)                 | Complete language syntax and grammar              |
| [Type System](schema-idl.md#type-system)         | Primitive types, collections, and type rules      |
| [RPC Services](schema-idl.md#service-definition) | Service and RPC method syntax                     |
| [Compiler Guide](compiler-guide.md)              | CLI options and build integration                 |
| [Generated Code](generated-code.md)              | Output format for each target language            |
| [Protocol Buffers IDL Support](protobuf-idl.md)  | Protobuf mapping rules and adoption guidance      |
| [FlatBuffers IDL Support](flatbuffers-idl.md)    | FlatBuffers mapping rules and codegen differences |

## Key Concepts

### Field Modifiers

- **`optional`**: Field can be null/None
- **`ref`**: Enable reference tracking for shared/circular references
- **`list`**: Field is an ordered collection (alias: `repeated`)
- **`array`**: Field is dense one-dimensional bool or numeric data

```protobuf
message Example {
    optional string nullable = 1;
    ref Node parent = 2;
    list<int32> numbers = 3;
}
```

### Cross-Language Compatibility

Fory IDL types map to native types in each language:

| Fory IDL Type | Java      | Python         | C++           | Go       | Rust     | JavaScript/TypeScript | C#       | Swift    | Dart     | Scala     | Kotlin    |
| ------------- | --------- | -------------- | ------------- | -------- | -------- | --------------------- | -------- | -------- | -------- | --------- | --------- |
| `int32`       | `int`     | `pyfory.Int32` | `int32_t`     | `int32`  | `i32`    | `number`              | `int`    | `Int32`  | `int`    | `Int`     | `Int`     |
| `string`      | `String`  | `str`          | `std::string` | `string` | `String` | `string`              | `string` | `String` | `String` | `String`  | `String`  |
| `bool`        | `boolean` | `bool`         | `bool`        | `bool`   | `bool`   | `boolean`             | `bool`   | `Bool`   | `bool`   | `Boolean` | `Boolean` |

See [Type System](schema-idl.md#type-system) for complete mappings.

## Best Practices

1. **Use meaningful package names**: Group related types together
2. **Assign type IDs for performance**: Numeric IDs are faster than name-based registration
3. **Reserve ID ranges**: Leave gaps for future additions (e.g., 100-199 for users, 200-299 for orders)
4. **Use `optional` explicitly**: Make nullability clear in the schema
5. **Use `ref` for shared objects**: Enable reference tracking when objects are shared

## Examples

See the [examples](https://github.com/apache/fory/tree/main/compiler/examples) directory for complete working examples.
