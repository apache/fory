# Fory Definition Language (FDL) Compiler

The FDL compiler generates cross-language serialization code from schema definitions. It enables type-safe cross-language data exchange by generating native data structures with Fory serialization support for multiple programming languages.

## Features

- **Multi-language code generation**: Java, Python, Go, Rust, C++, C#
- **Rich type system**: Primitives, enums, messages, lists, maps
- **Cross-language serialization**: Generated code works seamlessly with Apache Fory
- **Type ID and namespace support**: Both numeric IDs and name-based type registration
- **Field modifiers**: Optional fields, reference tracking, list fields
- **File imports**: Modular schemas with import support

## Documentation

For comprehensive documentation, see the [FDL Schema Guide](../docs/compiler/index.md):

- [FDL Syntax Reference](../docs/compiler/schema-idl.md) - Complete language syntax and grammar
- [Type System](../docs/compiler/type-system.md) - Primitive types, collections, and language mappings
- [Compiler Guide](../docs/compiler/compiler-guide.md) - CLI options and build integration
- [Generated Code](../docs/compiler/generated-code.md) - Output format for each target language
- [Protocol Buffers vs FDL](../docs/compiler/protobuf-idl.md) - Feature comparison and migration guide

## Installation

```bash
cd compiler
pip install -e .
```

## Quick Start

### 1. Define Your Schema

Create a `.fdl` file:

```fdl
package demo;

enum Color [id=101] {
    GREEN = 0;
    RED = 1;
    BLUE = 2;
}

message Dog [id=102] {
    optional string name = 1;
    int32 age = 2;
}

message Cat [id=103] {
    ref Dog friend = 1;
    optional string name = 2;
    list<string> tags = 3;
    map<string, int32> scores = 4;
    int32 lives = 5;
}
```

### 2. Compile

```bash
# Generate for all languages
foryc schema.fdl --output ./generated

# Generate for specific languages
foryc schema.fdl --lang java,python,csharp --output ./generated

# Override package name
foryc schema.fdl --package myapp.models --output ./generated

# Language-specific output directories (protoc-style)
foryc schema.fdl --java_out=./src/main/java --python_out=./python/src --csharp_out=./csharp/src/Generated

# Combine with other options
foryc schema.fdl --java_out=./gen --go_out=./gen/go --csharp_out=./gen/csharp -I ./proto
```

### 3. Use Generated Code

**Java:**

```java
import demo.*;
import org.apache.fory.Fory;

Fory fory = Fory.builder().build();
DemoForyRegistration.register(fory);

Cat cat = new Cat();
cat.setName("Whiskers");
cat.setLives(9);
byte[] bytes = fory.serialize(cat);
```

**Python:**

```python
import pyfory
from demo import Cat, register_demo_types

fory = pyfory.Fory()
register_demo_types(fory)

cat = Cat(name="Whiskers", lives=9)
data = fory.serialize(cat)
```

## FDL Syntax

### Package Declaration

```fdl
package com.example.models;
```

### Imports

Import types from other FDL files:

```fdl
import "common/types.fdl";
import "models/address.fdl";
```

Imports are resolved relative to the importing file. All types from imported files become available for use in the current file.

**Example:**

```fdl
// common.fdl
package common;

message Address [id=100] {
    string street = 1;
    string city = 2;
}
```

```fdl
// user.fdl
package user;
import "common.fdl";

message User [id=101] {
    string name = 1;
    Address address = 2;  // Uses imported type
}
```

### Enum Definition

```fdl
enum Status [id=100] {
    PENDING = 0;
    ACTIVE = 1;
    INACTIVE = 2;
}
```

### Message Definition

```fdl
message User [id=101] {
    string name = 1;
    int32 age = 2;
    optional string email = 3;
}
```

### Type Options

Types can have options specified in brackets after the name:

```fdl
message User [id=101] { ... }              // Registered with type ID 101
message User [id=101, deprecated=true] { ... }  // Multiple options
```

Types without `[id=...]` use namespace-based registration:

```fdl
message Config { ... }  // Registered as "package.Config"
```

### Primitive Types

| FDL Type    | Java        | Python              | Go          | Rust                    | C++                    | C#               |
| ----------- | ----------- | ------------------- | ----------- | ----------------------- | ---------------------- | ---------------- |
| `bool`      | `boolean`   | `bool`              | `bool`      | `bool`                  | `bool`                 | `bool`           |
| `int8`      | `byte`      | `pyfory.int8`       | `int8`      | `i8`                    | `int8_t`               | `sbyte`          |
| `int16`     | `short`     | `pyfory.int16`      | `int16`     | `i16`                   | `int16_t`              | `short`          |
| `int32`     | `int`       | `pyfory.int32`      | `int32`     | `i32`                   | `int32_t`              | `int`            |
| `int64`     | `long`      | `pyfory.int64`      | `int64`     | `i64`                   | `int64_t`              | `long`           |
| `float32`   | `float`     | `pyfory.float32`    | `float32`   | `f32`                   | `float`                | `float`          |
| `float64`   | `double`    | `pyfory.float64`    | `float64`   | `f64`                   | `double`               | `double`         |
| `string`    | `String`    | `str`               | `string`    | `String`                | `std::string`          | `string`         |
| `bytes`     | `byte[]`    | `bytes`             | `[]byte`    | `Vec<u8>`               | `std::vector<uint8_t>` | `byte[]`         |
| `date`      | `LocalDate` | `datetime.date`     | `time.Time` | `chrono::NaiveDate`     | `fory::Date`           | `DateOnly`       |
| `timestamp` | `Instant`   | `datetime.datetime` | `time.Time` | `chrono::NaiveDateTime` | `fory::Timestamp`      | `DateTimeOffset` |

### Collection Types

```fdl
list<string> tags = 1;          // List<String>
map<string, int32> scores = 2;  // Map<String, Integer>
```

### Field Modifiers

- **`optional`**: Field can be null/None
- **`ref`**: Enable reference tracking for shared/circular references
- **`list`**: Field is a list/array (alias: `repeated`)

```fdl
message Example {
    optional string nullable_field = 1;
    ref OtherMessage shared_ref = 2;
    list<int32> numbers = 3;
}
```

### Fory Options

FDL uses plain option keys without a `(fory)` prefix:

**File-level options:**

```fdl
option use_record_for_java_message = true;
option polymorphism = true;
option enable_auto_type_id = true;
```

`enable_auto_type_id` defaults to `true`. Set it to `false` to keep namespace-based registration
for types that omit explicit IDs.

**Message/Enum options:**

```fdl
message MyMessage [id=100] {
    option evolving = false;
    option use_record_for_java = true;
    string name = 1;
}

enum Status [id=101] {
    UNKNOWN = 0;
    ACTIVE = 1;
}
```

**Field options:**

```fdl
message Example {
    ref MyType friend = 1;
    string nickname = 2 [nullable=true];
    ref MyType data = 3 [nullable=true];
    ref(weak=true) MyType parent = 4;
}
```

## Architecture

```
fory_compiler/
├── __init__.py           # Package exports
├── __main__.py           # Module entry point
├── cli.py                # Command-line interface
├── frontend/
│   └── fdl/
│       ├── __init__.py
│       ├── lexer.py      # Hand-written tokenizer
│       └── parser.py     # Recursive descent parser
├── ir/
│   ├── __init__.py
│   ├── ast.py            # Canonical Fory IDL AST
│   ├── validator.py      # Schema validation
│   └── emitter.py        # Optional FDL emitter
└── generators/
    ├── base.py           # Base generator class
    ├── java.py           # Java POJO generator
    ├── python.py         # Python dataclass generator
    ├── go.py             # Go struct generator
    ├── rust.py           # Rust struct generator
    ├── cpp.py            # C++ struct generator
    └── csharp.py         # C# class generator
```

### FDL Frontend

The FDL frontend is a hand-written lexer/parser that produces the Fory IDL AST:

- **Lexer** (`frontend/fdl/lexer.py`): Tokenizes FDL source into tokens
- **Parser** (`frontend/fdl/parser.py`): Builds the AST from the token stream
- **AST** (`ir/ast.py`): Canonical node types - `Schema`, `Message`, `Enum`, `Field`, `FieldType`

### Generators

Each generator extends `BaseGenerator` and implements:

- `generate()`: Returns list of `GeneratedFile` objects
- `generate_type()`: Converts FDL types to target language types
- Language-specific registration helpers

## Generated Output

### Java

Generates POJOs with:

- Private fields with getters/setters
- `@ForyField` annotations for nullable/ref fields
- Registration helper class

```java
public class Cat {
    @ForyField(ref = true)
    private Dog friend;

    @ForyField(nullable = true)
    private String name;

    private List<String> tags;
    // ...
}
```

### Python

Generates dataclasses with:

- Type hints
- Default values
- Registration function

```python
@dataclass
class Cat:
    friend: Optional[Dog] = None
    name: Optional[str] = None
    tags: List[str] = None
```

### Go

Generates structs with:

- Fory struct tags
- Pointer types for nullable fields
- Registration function with error handling

```go
type Cat struct {
    Friend *Dog              `fory:"ref"`
    Name   *string           `fory:"nullable"`
    Tags   []string
}
```

### Rust

Generates structs with:

- `#[derive(ForyObject)]` macro
- `#[fory(...)]` field attributes
- a registration helper for namespace-based registration

```rust
#[derive(ForyObject, Debug, Clone, PartialEq, Default)]
pub struct Cat {
    pub friend: Arc<Dog>,
    #[fory(nullable = true)]
    pub name: Option<String>,
    pub tags: Vec<String>,
}
```

### C++

Generates structs with:

- `FORY_STRUCT` macro for serialization
- `std::optional` for nullable fields
- `std::shared_ptr` for ref fields

```cpp
struct Cat {
    std::shared_ptr<Dog> friend;
    std::optional<std::string> name;
    std::vector<std::string> tags;
    int32_t scores;
    int32_t lives;
    FORY_STRUCT(Cat, friend, name, tags, scores, lives);
};
```

### C#

Generates classes with:

- `[ForyObject]` model attributes
- Auto-properties for schema fields
- Registration helper class and `ToBytes`/`FromBytes` helpers

```csharp
[ForyObject]
public sealed partial class Cat
{
    public Dog? Friend { get; set; }
    public string Name { get; set; } = string.Empty;
    public List<string> Tags { get; set; } = new();
}
```

For full C# IDL verification (including root cross-package imports and file-based
roundtrip paths), run:

```bash
cd integration_tests/idl_tests
./run_csharp_tests.sh
```

## CLI Reference

```
foryc [OPTIONS] FILES...

Arguments:
  FILES                 FDL files to compile

Options:
  --lang TEXT          Target languages (java,python,cpp,rust,go,csharp or "all")
                       Default: all
  --output, -o PATH    Output directory
                       Default: ./generated
  --package TEXT       Override package name from FDL file
  --help               Show help message
```

## Examples

See the `examples/` directory for sample FDL files and generated output.

```bash
# Compile the demo schema
foryc examples/demo.fdl --output examples/generated
```

## Development

```bash
# Install in development mode
pip install -e .

# Run the compiler
python -m fory_compiler compile examples/demo.fdl

# Or use the installed command
foryc examples/demo.fdl
```

## License

Apache License 2.0
