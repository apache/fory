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
native data structure code for Java, Python, Go, Rust, C++, C#, Swift, and JavaScript.

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
```

## Why Fory IDL?

### Schema-First Development

Define your data model once in Fory IDL and generate consistent, type-safe code across all languages. This ensures:

- **Type Safety**: Catch type errors at compile time, not runtime
- **Consistency**: All languages use the same field names, types, and structures
- **Documentation**: Schema serves as living documentation
- **Evolution**: Managed schema changes across all implementations

### Fory-Native Features

Unlike generic IDLs, Fory IDL is designed specifically for Fory serialization:

- **Reference Tracking**: First-class support for shared and circular references via `ref`
- **Nullable Fields**: Explicit `optional` modifier for nullable types
- **Type Registration**: Built-in support for both numeric IDs and namespace-based registration
- **Native Code Generation**: Generates idiomatic code with Fory annotations/macros

### Low Integration Overhead

Generated code uses native language constructs:

- Java: Plain POJOs with `@ForyField` annotations
- Python: Dataclasses with type hints
- Go: Structs with struct tags
- Rust: Structs with `#[derive(ForyObject)]`
- C++: Structs with `FORY_STRUCT` macros
- C#: Classes with `[ForyObject]` and registration helpers
- JavaScript: Interfaces with registration function
- Swift: `@ForyObject` models with `@ForyField` metadata and registration helpers

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
foryc example.fdl --lang java,python,csharp,javascript,swift --output ./generated
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

| Document                                        | Description                                       |
| ----------------------------------------------- | ------------------------------------------------- |
| [Fory IDL Syntax](schema-idl.md)                | Complete language syntax and grammar              |
| [Type System](schema-idl.md#type-system)        | Primitive types, collections, and type rules      |
| [Compiler Guide](compiler-guide.md)             | CLI options and build integration                 |
| [Generated Code](generated-code.md)             | Output format for each target language            |
| [Protocol Buffers IDL Support](protobuf-idl.md) | Comparison with protobuf and migration guide      |
| [FlatBuffers IDL Support](flatbuffers-idl.md)   | FlatBuffers mapping rules and codegen differences |

## Key Concepts

### Field Modifiers

- **`optional`**: Field can be null/None
- **`ref`**: Enable reference tracking for shared/circular references
- **`list`**: Field is a list/array (alias: `repeated`)

```protobuf
message Example {
    optional string nullable = 1;
    ref Node parent = 2;
    list<int32> numbers = 3;
}
```

### Cross-Language Compatibility

Fory IDL types map to native types in each language:

| Fory IDL Type | Java      | Python         | Go       | Rust     | C++           | C#       | JavaScript | Swift    |
| ------------- | --------- | -------------- | -------- | -------- | ------------- | -------- | ---------- | -------- |
| `int32`       | `int`     | `pyfory.int32` | `int32`  | `i32`    | `int32_t`     | `int`    | `number`   | `Int32`  |
| `string`      | `String`  | `str`          | `string` | `String` | `std::string` | `string` | `string`   | `String` |
| `bool`        | `boolean` | `bool`         | `bool`   | `bool`   | `bool`        | `bool`   | `boolean`  | `Bool`   |

See [Type System](schema-idl.md#type-system) for complete mappings.

## Best Practices

1. **Use meaningful package names**: Group related types together
2. **Assign type IDs for performance**: Numeric IDs are faster than name-based registration
3. **Reserve ID ranges**: Leave gaps for future additions (e.g., 100-199 for users, 200-299 for orders)
4. **Use `optional` explicitly**: Make nullability clear in the schema
5. **Use `ref` for shared objects**: Enable reference tracking when objects are shared

## Examples

See the [examples](https://github.com/apache/fory/tree/main/compiler/examples) directory for complete working examples.
