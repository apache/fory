---
title: FDL Schema Guide
sidebar_position: 1
id: schema_index
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

# Fory Definition Language (FDL)

Fory Definition Language (FDL) is a schema definition language for Apache Fory that enables type-safe cross-language serialization. Define your data structures once and generate native code for Java, Python, Go, Rust, and C++.

## Overview

FDL provides a simple, intuitive syntax for defining cross-language data structures:

```fdl
package example;

enum Status [id=100] {
    PENDING = 0;
    ACTIVE = 1;
    COMPLETED = 2;
}

message User [id=101] {
    string name = 1;
    int32 age = 2;
    optional string email = 3;
    repeated string tags = 4;
}

message Order [id=102] {
    ref User customer = 1;
    repeated Item items = 2;
    Status status = 3;
    map<string, int32> metadata = 4;
}
```

## Why FDL?

### Schema-First Development

Define your data model once in FDL and generate consistent, type-safe code across all languages. This ensures:

- **Type Safety**: Catch type errors at compile time, not runtime
- **Consistency**: All languages use the same field names, types, and structures
- **Documentation**: Schema serves as living documentation
- **Evolution**: Managed schema changes across all implementations

### Fory-Native Features

Unlike generic IDLs, FDL is designed specifically for Fory serialization:

- **Reference Tracking**: First-class support for shared and circular references via `ref`
- **Nullable Fields**: Explicit `optional` modifier for nullable types
- **Type Registration**: Built-in support for both numeric IDs and namespace-based registration
- **Native Code Generation**: Generates idiomatic code with Fory annotations/macros

### Zero Runtime Overhead

Generated code uses native language constructs:

- Java: Plain POJOs with `@ForyField` annotations
- Python: Dataclasses with type hints
- Go: Structs with struct tags
- Rust: Structs with `#[derive(ForyObject)]`
- C++: Structs with `FORY_STRUCT` macros

## Quick Start

### 1. Install the Compiler

```bash
cd compiler
pip install -e .
```

### 2. Write Your Schema

Create `example.fdl`:

```fdl
package example;

message Person [id=100] {
    string name = 1;
    int32 age = 2;
    optional string email = 3;
}
```

### 3. Generate Code

```bash
# Generate for all languages
fory compile example.fdl --output ./generated

# Generate for specific languages
fory compile example.fdl --lang java,python --output ./generated
```

### 4. Use Generated Code

**Java:**

```java
Fory fory = Fory.builder().withLanguage(Language.XLANG).build();
ExampleForyRegistration.register(fory);

Person person = new Person();
person.setName("Alice");
person.setAge(30);
byte[] data = fory.serialize(person);
```

**Python:**

```python
import pyfory
from example import Person, register_example_types

fory = pyfory.Fory()
register_example_types(fory)

person = Person(name="Alice", age=30)
data = fory.serialize(person)
```

## Documentation

| Document                                   | Description                                  |
| ------------------------------------------ | -------------------------------------------- |
| [FDL Syntax Reference](fdl-syntax.md)      | Complete language syntax and grammar         |
| [Type System](type-system.md)              | Primitive types, collections, and type rules |
| [Compiler Guide](compiler-guide.md)        | CLI options and build integration            |
| [Generated Code](generated-code.md)        | Output format for each target language       |
| [Protocol Buffers vs FDL](proto-vs-fdl.md) | Comparison with protobuf and migration guide |

## Key Concepts

### Type Registration

FDL supports two registration modes:

**Numeric Type IDs** - Fast and compact:

```fdl
message User [id=100] { ... }  // Registered with ID 100
```

**Namespace-based** - Flexible and readable:

```fdl
message Config { ... }  // Registered as "package.Config"
```

### Field Modifiers

- **`optional`**: Field can be null/None
- **`ref`**: Enable reference tracking for shared/circular references
- **`repeated`**: Field is a list/array

```fdl
message Example {
    optional string nullable = 1;
    ref Node parent = 2;
    repeated int32 numbers = 3;
}
```

### Cross-Language Compatibility

FDL types map to native types in each language:

| FDL Type | Java      | Python | Go       | Rust     | C++           |
| -------- | --------- | ------ | -------- | -------- | ------------- |
| `int32`  | `int`     | `int`  | `int32`  | `i32`    | `int32_t`     |
| `string` | `String`  | `str`  | `string` | `String` | `std::string` |
| `bool`   | `boolean` | `bool` | `bool`   | `bool`   | `bool`        |

See [Type System](type-system.md) for complete mappings.

## Best Practices

1. **Use meaningful package names**: Group related types together
2. **Assign type IDs for performance**: Numeric IDs are faster than name-based registration
3. **Reserve ID ranges**: Leave gaps for future additions (e.g., 100-199 for users, 200-299 for orders)
4. **Use `optional` explicitly**: Make nullability clear in the schema
5. **Use `ref` for shared objects**: Enable reference tracking when objects are shared

## Examples

See the [examples](https://github.com/apache/fory/tree/main/compiler/examples) directory for complete working examples.
