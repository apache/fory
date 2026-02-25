---
title: Schema IDL
sidebar_position: 2
id: syntax
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

This document provides the syntax and semantic reference for Fory IDL.

For compiler usage and build integration, see
[Compiler Guide](compiler-guide.md). For protobuf/FlatBuffers frontend mapping
rules, see [Protocol Buffers IDL Support](protobuf-idl.md) and
[FlatBuffers IDL Support](flatbuffers-idl.md).

## File Structure

An Fory IDL file typically consists of:

1. Optional package declaration
2. Optional file-level options
3. Optional import statements
4. Type definitions (enums, messages, and unions)

```protobuf
// Optional package declaration
package com.example.models;

// Optional file-level options
option java_package = "com.example.models";

// Import statements
import "common/types.fdl";

// Type definitions
enum Color [id=100] { ... }
message User [id=101] { ... }
message Order [id=102] { ... }
union Event [id=103] { ... }
```

## Comments

Fory IDL supports both single-line and block comments:

```protobuf
// This is a single-line comment

/*
 * This is a block comment
 * that spans multiple lines
 */

message Example {
    string name = 1;  // Inline comment
}
```

## Package Declaration

The package declaration defines the namespace for all types in the file.

```protobuf
package com.example.models;
```

You can optionally specify a package alias used for auto-generated type IDs:

```protobuf
package com.example.models alias models_v1;
```

**Rules:**

- Optional but recommended
- Must appear before any type definitions
- Only one package declaration per file
- Used for namespace-based type registration
- Package alias is used for auto-ID hashing

**Language Mapping:**

| Language | Package Usage                     |
| -------- | --------------------------------- |
| Java     | Java package                      |
| Python   | Module name (dots to underscores) |
| Go       | Package name (last component)     |
| Rust     | Module name (dots to underscores) |
| C++      | Namespace (dots to `::`)          |
| C#       | Namespace                         |

## File-Level Options

Options can be specified at file level to control language-specific code generation.

### Syntax

```protobuf
option option_name = value;
```

### Java Package Option

Override the Java package for generated code:

```protobuf
package payment;
option java_package = "com.mycorp.payment.v1";

message Payment {
    string id = 1;
}
```

**Effect:**

- Generated Java files will be in `com/mycorp/payment/v1/` directory
- Java package declaration will be `package com.mycorp.payment.v1;`
- Type registration still uses the Fory IDL package (`payment`) for cross-language compatibility

### Go Package Option

Specify the Go import path and package name:

```protobuf
package payment;
option go_package = "github.com/mycorp/apis/gen/payment/v1;paymentv1";

message Payment {
    string id = 1;
}
```

**Format:** `"import/path;package_name"` or just `"import/path"` (last segment used as package name)

**Effect:**

- Generated Go files will have `package paymentv1`
- The import path can be used in other Go code
- Type registration still uses the Fory IDL package (`payment`) for cross-language compatibility

### C# Namespace Option

Override the C# namespace for generated code:

```protobuf
package payment;
option csharp_namespace = "MyCorp.Payment.V1";

message Payment {
    string id = 1;
}
```

**Effect:**

- Generated C# files use `namespace MyCorp.Payment.V1;`
- Output path follows namespace segments (`MyCorp/Payment/V1/` under `--csharp_out`)
- Type registration still uses the Fory IDL package (`payment`) for cross-language compatibility

### Java Outer Classname Option

Generate all types as inner classes of a single outer wrapper class:

```protobuf
package payment;
option java_outer_classname = "DescriptorProtos";

enum Status {
    UNKNOWN = 0;
    ACTIVE = 1;
}

message Payment {
    string id = 1;
    Status status = 2;
}
```

**Effect:**

- Generates a single file `DescriptorProtos.java` instead of separate files
- All enums and messages become `public static` inner classes
- The outer class is `public final` with a private constructor
- Useful for grouping related types together

**Generated structure:**

```java
public final class DescriptorProtos {
    private DescriptorProtos() {}

    public static enum Status {
        UNKNOWN,
        ACTIVE;
    }

    public static class Payment {
        private String id;
        private Status status;
        // ...
    }
}
```

**Combined with java_package:**

```protobuf
package payment;
option java_package = "com.example.proto";
option java_outer_classname = "PaymentProtos";

message Payment {
    string id = 1;
}
```

This generates `com/example/proto/PaymentProtos.java` with all types as inner classes.

### Java Multiple Files Option

Control whether types are generated in separate files or as inner classes:

```protobuf
package payment;
option java_outer_classname = "PaymentProtos";
option java_multiple_files = true;

message Payment {
    string id = 1;
}

message Receipt {
    string id = 1;
}
```

**Behavior:**

| `java_outer_classname` | `java_multiple_files` | Result                                      |
| ---------------------- | --------------------- | ------------------------------------------- |
| Not set                | Any                   | Separate files (one per type)               |
| Set                    | `false` (default)     | Single file with all types as inner classes |
| Set                    | `true`                | Separate files (overrides outer class)      |

**Effect of `java_multiple_files = true`:**

- Each top-level enum and message gets its own `.java` file
- Overrides `java_outer_classname` behavior
- Useful when you want separate files but still specify an outer class name for other purposes

**Example without java_multiple_files (default):**

```protobuf
option java_outer_classname = "PaymentProtos";
// Generates: PaymentProtos.java containing Payment and Receipt as inner classes
```

**Example with java_multiple_files = true:**

```protobuf
option java_outer_classname = "PaymentProtos";
option java_multiple_files = true;
// Generates: Payment.java, Receipt.java (separate files)
```

### Multiple Options

Multiple options can be specified:

```protobuf
package payment;
option java_package = "com.mycorp.payment.v1";
option go_package = "github.com/mycorp/apis/gen/payment/v1;paymentv1";
option deprecated = true;

message Payment {
    string id = 1;
}
```

### Protobuf Extension Syntax

In `.fdl` files, use native Fory IDL syntax only (for example, `[id=100]`, `ref`,
`optional`, `nullable=true`).

Protobuf extension syntax with `(fory).` is for `.proto` files and the protobuf
frontend only.

For protobuf extension options, see
[Protocol Buffers IDL Support](protobuf-idl.md#fory-extension-options-protobuf).

### Option Priority

For language-specific packages/namespaces:

1. Command-line package override (highest priority)
2. Language-specific option (`java_package`, `go_package`, `csharp_namespace`)
3. Fory IDL package declaration (fallback)

**Example:**

```protobuf
package myapp.models;
option java_package = "com.example.generated";
```

| Scenario                  | Java Package Used         |
| ------------------------- | ------------------------- |
| No override               | `com.example.generated`   |
| CLI: `--package=override` | `override`                |
| No java_package option    | `myapp.models` (fallback) |

### Cross-Language Type Registration

Language-specific options only affect where code is generated, not the type namespace used for serialization. This ensures cross-language compatibility:

```protobuf
package myapp.models;
option java_package = "com.mycorp.generated";
option go_package = "github.com/mycorp/gen;genmodels";

message User {
    string name = 1;
}
```

All languages will register `User` with namespace `myapp.models`, enabling:

- Java serialized data → Go deserialization
- Go serialized data → Java deserialization
- Any language combination works seamlessly

## Import Statement

Import statements allow you to use types defined in other Fory IDL files.

### Basic Syntax

```protobuf
import "path/to/file.fdl";
```

### Multiple Imports

```protobuf
import "common/types.fdl";
import "common/enums.fdl";
import "models/address.fdl";
```

### Path Resolution

Import paths are resolved relative to the importing file:

```
project/
├── common/
│   └── types.fdl
├── models/
│   ├── user.fdl      # import "../common/types.fdl"
│   └── order.fdl     # import "../common/types.fdl"
└── main.fdl          # import "common/types.fdl"
```

**Rules:**

- Import paths are quoted strings (double or single quotes)
- Paths are resolved relative to the importing file's directory
- Imported types become available as if defined in the current file
- Circular imports are detected and reported as errors
- Transitive imports work (if A imports B and B imports C, A has access to C's types)

### Complete Example

**common/types.fdl:**

```protobuf
package common;

enum Status [id=100] {
    PENDING = 0;
    ACTIVE = 1;
    COMPLETED = 2;
}

message Address [id=101] {
    string street = 1;
    string city = 2;
    string country = 3;
}
```

**models/user.fdl:**

```protobuf
package models;
import "../common/types.fdl";

message User [id=200] {
    string id = 1;
    string name = 2;
    Address home_address = 3;  // Uses imported type
    Status status = 4;          // Uses imported enum
}
```

### Unsupported Import Syntax

The following protobuf import modifiers are **not supported**:

```protobuf
// NOT SUPPORTED - will produce an error
import public "other.fdl";
import weak "other.fdl";
```

**`import public`**: Fory IDL uses a simpler import model. All imported types are available to the importing file only. Re-exporting is not supported. Import each file directly where needed.

**`import weak`**: Fory IDL requires all imports to be present at compile time. Optional dependencies are not supported.

### Import Errors

The compiler reports errors for:

- **File not found**: The imported file doesn't exist
- **Circular import**: A imports B which imports A (directly or indirectly)
- **Parse errors**: Syntax errors in imported files
- **Unsupported syntax**: `import public` or `import weak`

## Enum Definition

Enums define a set of named integer constants.

### Basic Syntax

```protobuf
enum Status {
    PENDING = 0;
    ACTIVE = 1;
    COMPLETED = 2;
}
```

### With Explicit Type ID

```protobuf
enum Status [id=100] {
    PENDING = 0;
    ACTIVE = 1;
    COMPLETED = 2;
}
```

### Reserved Values

Reserve field numbers or names to prevent reuse:

```protobuf
enum Status {
    reserved 2, 15, 9 to 11, 40 to max;  // Reserved numbers
    reserved "OLD_STATUS", "DEPRECATED"; // Reserved names
    PENDING = 0;
    ACTIVE = 1;
    COMPLETED = 3;
}
```

### Enum Type Options

Enum-level options are declared inline in `[]` after the enum name:

```protobuf
enum Status [deprecated=true] {
    PENDING = 0;
    ACTIVE = 1;
}
```

FDL does not support `option ...;` statements inside enum bodies.

**Unsupported:**

- `allow_alias` is **not supported**. Each enum value must have a unique integer.

### Language Mapping

| Language | Implementation                         |
| -------- | -------------------------------------- |
| Java     | `enum Status { UNKNOWN, ACTIVE, ... }` |
| Python   | `class Status(IntEnum): UNKNOWN = 0`   |
| Go       | `type Status int32` with constants     |
| Rust     | `#[repr(i32)] enum Status { Unknown }` |
| C++      | `enum class Status : int32_t { ... }`  |

### Enum Prefix Stripping

When enum values use a protobuf-style prefix (enum name in UPPER_SNAKE_CASE), the compiler automatically strips the prefix for languages with scoped enums:

```protobuf
// Input with prefix
enum DeviceTier {
    DEVICE_TIER_UNKNOWN = 0;
    DEVICE_TIER_TIER1 = 1;
    DEVICE_TIER_TIER2 = 2;
}
```

**Generated code:**

| Language | Output                                    | Style          |
| -------- | ----------------------------------------- | -------------- |
| Java     | `UNKNOWN, TIER1, TIER2`                   | Scoped enum    |
| Rust     | `Unknown, Tier1, Tier2`                   | Scoped enum    |
| C++      | `UNKNOWN, TIER1, TIER2`                   | Scoped enum    |
| Python   | `UNKNOWN, TIER1, TIER2`                   | Scoped IntEnum |
| Go       | `DeviceTierUnknown, DeviceTierTier1, ...` | Unscoped const |

**Note:** The prefix is only stripped if the remainder is a valid identifier. For example, `DEVICE_TIER_1` is kept unchanged because `1` is not a valid identifier name.

**Grammar:**

```
enum_def     := 'enum' IDENTIFIER [type_options] '{' enum_body '}'
type_options := '[' type_option (',' type_option)* ']'
type_option  := IDENTIFIER '=' option_value
enum_body    := (reserved_stmt | enum_value)*
reserved_stmt := 'reserved' reserved_items ';'
enum_value   := IDENTIFIER '=' INTEGER ';'
```

**Rules:**

- Enum names must be unique within the file
- Enum values must have explicit integer assignments
- Value integers must be unique within the enum (no aliases)
- Type ID (`[id=100]`) is optional for enums but recommended for cross-language use

**Example with All Features:**

```protobuf
// HTTP status code categories
enum HttpCategory [id=200] {
    reserved 10 to 20;           // Reserved for future use
    reserved "UNKNOWN";          // Reserved name
    INFORMATIONAL = 1;
    SUCCESS = 2;
    REDIRECTION = 3;
    CLIENT_ERROR = 4;
    SERVER_ERROR = 5;
}
```

## Message Definition

Messages define structured data types with typed fields.

### Basic Syntax

```protobuf
message Person {
    string name = 1;
    int32 age = 2;
}
```

### With Explicit Type ID

```protobuf
message Person [id=101] {
    string name = 1;
    int32 age = 2;
}
```

### Without Explicit Type ID

```protobuf
message Person {  // Auto-generated when enable_auto_type_id = true
    string name = 1;
    int32 age = 2;
}
```

### Language Mapping

| Language | Implementation                      |
| -------- | ----------------------------------- |
| Java     | POJO class with getters/setters     |
| Python   | `@dataclass` class                  |
| Go       | Struct with exported fields         |
| Rust     | Struct with `#[derive(ForyObject)]` |
| C++      | Struct with `FORY_STRUCT` macro     |

Type IDs control cross-language registration for messages, unions, and enums. See
[Type IDs](#type-ids) for auto-generation, aliases, and collision handling.

### Reserved Fields

Reserve field numbers or names to prevent reuse after removing fields:

```protobuf
message User {
    reserved 2, 15, 9 to 11;       // Reserved field numbers
    reserved "old_field", "temp";  // Reserved field names
    string id = 1;
    string name = 3;
}
```

### Message Type Options

Message-level options are declared inline in `[]` after the message name:

```protobuf
message User [deprecated=true] {
    string id = 1;
    string name = 2;
}
```

FDL does not support `option ...;` statements inside message or enum bodies.

**Grammar:**

```
message_def  := 'message' IDENTIFIER [type_options] '{' message_body '}'
type_options := '[' type_option (',' type_option)* ']'
type_option  := IDENTIFIER '=' option_value
message_body := (reserved_stmt | nested_type | field_def)*
nested_type  := enum_def | message_def | union_def
```

**Rules:**

- Type IDs follow the rules in [Type IDs](#type-ids).

## Nested Types

Messages can contain nested message, enum, and union definitions. This is useful for defining types that are closely related to their parent message.

### Nested Messages

```protobuf
message SearchResponse {
    message Result {
        string url = 1;
        string title = 2;
        list<string> snippets = 3;
    }
    list<Result> results = 1;
}
```

### Nested Enums

```protobuf
message Container {
    enum Status {
        STATUS_UNKNOWN = 0;
        STATUS_ACTIVE = 1;
        STATUS_INACTIVE = 2;
    }
    Status status = 1;
}
```

### Qualified Type Names

Nested types can be referenced from other messages using qualified names (Parent.Child):

```protobuf
message SearchResponse {
    message Result {
        string url = 1;
        string title = 2;
    }
}

message SearchResultCache {
    // Reference nested type with qualified name
    SearchResponse.Result cached_result = 1;
    list<SearchResponse.Result> all_results = 2;
}
```

### Deeply Nested Types

Nesting can be multiple levels deep:

```protobuf
message Outer {
    message Middle {
        message Inner {
            string value = 1;
        }
        Inner inner = 1;
    }
    Middle middle = 1;
}

message OtherMessage {
    // Reference deeply nested type
    Outer.Middle.Inner deep_ref = 1;
}
```

### Language-Specific Generation

| Language | Nested Type Generation                                                            |
| -------- | --------------------------------------------------------------------------------- |
| Java     | Static inner classes (`SearchResponse.Result`)                                    |
| Python   | Nested classes within dataclass                                                   |
| Go       | Flat structs with underscore (`SearchResponse_Result`, configurable to camelcase) |
| Rust     | Nested modules (`search_response::Result`)                                        |
| C++      | Nested classes (`SearchResponse::Result`)                                         |

**Note:** Go defaults to underscore-separated nested names; set `option go_nested_type_style = "camelcase";` to use concatenated names. Rust emits nested modules for nested types.

### Nested Type Rules

- Nested type names must be unique within their parent message
- Nested types can have their own type IDs
- Numeric type IDs must be globally unique (including nested types); see [Type IDs](#type-ids)
  for auto-generation and collision handling
- Within a message, you can reference nested types by simple name
- From outside, use the qualified name (Parent.Child)

## Union Definition

Unions define a value that can hold exactly one of several case types.

### Basic Syntax

```protobuf
union Animal [id=106] {
    Dog dog = 1;
    Cat cat = 2;
}
```

### Using a Union in a Message

```protobuf
message Person [id=100] {
    Animal pet = 1;
    optional Animal favorite_pet = 2;
}
```

### Rules

- Case IDs must be unique within the union
- Cases cannot be `optional` or `ref`
- Union cases do not support field options
- Case types can be primitives, enums, messages, or other named types
- Union type IDs follow the rules in [Type IDs](#type-ids).

**Grammar:**

```
union_def  := 'union' IDENTIFIER [type_options] '{' union_field* '}'
union_field := field_type IDENTIFIER '=' INTEGER ';'
```

## Field Definition

Fields define the properties of a message.

### Basic Syntax

```protobuf
field_type field_name = field_number;
```

### With Modifiers

```protobuf
optional list<string> tags = 1;  // Nullable list
list<optional string> tags = 2;  // Elements may be null
ref list<Node> nodes = 3;        // Collection tracked as a reference
list<ref Node> nodes = 4;        // Elements tracked as references
```

**Grammar:**

```
field_def    := [modifiers] field_type IDENTIFIER '=' INTEGER ';'
modifiers    := { 'optional' | 'ref' }
field_type   := primitive_type | named_type | list_type | map_type
list_type    := 'list' '<' { 'optional' | 'ref' } field_type '>'
```

Modifiers apply to the field/collection. Use `list<...>` to describe element
modifiers. `repeated` is accepted as an alias for `list`.

### Field Modifiers

#### `optional`

Marks the field as nullable:

```protobuf
message User {
    string name = 1;           // Required, non-null
    optional string email = 2; // Nullable
}
```

**Generated Code:**

| Language | Non-optional       | Optional                                        |
| -------- | ------------------ | ----------------------------------------------- |
| Java     | `String name`      | `String email` with `@ForyField(nullable=true)` |
| Python   | `name: str`        | `name: Optional[str]`                           |
| Go       | `Name string`      | `Name *string`                                  |
| Rust     | `name: String`     | `name: Option<String>`                          |
| C++      | `std::string name` | `std::optional<std::string> name`               |

**Default Values:**

| Type               | Default Value       |
| ------------------ | ------------------- |
| Non-optional types | Language default    |
| Optional types     | `null`/`None`/`nil` |

#### `ref`

Enables reference tracking for shared/circular references:

```protobuf
message Node {
    string value = 1;
    ref Node parent = 2;     // Can point to shared object
    list<ref Node> children = 3;
}
```

**Use Cases:**

- Shared objects (same object referenced multiple times)
- Circular references (object graphs with cycles)
- Tree structures with parent pointers

**Generated Code:**

| Language | Without `ref`  | With `ref`                                |
| -------- | -------------- | ----------------------------------------- |
| Java     | `Node parent`  | `Node parent` with `@ForyField(ref=true)` |
| Python   | `parent: Node` | `parent: Node = pyfory.field(ref=True)`   |
| Go       | `Parent Node`  | `Parent *Node` with `fory:"ref"`          |
| Rust     | `parent: Node` | `parent: Arc<Node>`                       |
| C++      | `Node parent`  | `std::shared_ptr<Node> parent`            |

Rust uses `Arc` by default; use `ref(thread_safe=false)` or `ref(weak=true)`
to customize pointer types. For protobuf option syntax, see
[Protocol Buffers IDL Support](protobuf-idl.md#field-level-options).

#### `list`

Marks the field as a list/array:

```protobuf
message Document {
    list<string> tags = 1;
    list<User> authors = 2;
}
```

**Generated Code:**

| Language | Type                       |
| -------- | -------------------------- |
| Java     | `List<String>`             |
| Python   | `List[str]`                |
| Go       | `[]string`                 |
| Rust     | `Vec<String>`              |
| C++      | `std::vector<std::string>` |

### Combining Modifiers

Modifiers can be combined:

```fdl
message Example {
    optional list<string> tags = 1;  // Nullable list
    list<optional string> aliases = 2; // Elements may be null
    ref list<Node> nodes = 3;          // Collection tracked as a reference
    list<ref Node> children = 4;       // Elements tracked as references
    optional ref User owner = 5;          // Nullable tracked reference
}
```

Modifiers before `list` apply to the field/collection. Modifiers after `list`
apply to elements. `repeated` is accepted as an alias for `list`.

**List modifier mapping:**

| Fory IDL                | Java                                           | Python                                  | Go                      | Rust                  | C++                                       |
| ----------------------- | ---------------------------------------------- | --------------------------------------- | ----------------------- | --------------------- | ----------------------------------------- |
| `optional list<string>` | `List<String>` + `@ForyField(nullable = true)` | `Optional[List[str]]`                   | `[]string` + `nullable` | `Option<Vec<String>>` | `std::optional<std::vector<std::string>>` |
| `list<optional string>` | `List<String>` (nullable elements)             | `List[Optional[str]]`                   | `[]*string`             | `Vec<Option<String>>` | `std::vector<std::optional<std::string>>` |
| `ref list<User>`        | `List<User>` + `@ForyField(ref = true)`        | `List[User]` + `pyfory.field(ref=True)` | `[]User` + `ref`        | `Arc<Vec<User>>`      | `std::shared_ptr<std::vector<User>>`      |
| `list<ref User>`        | `List<User>`                                   | `List[User]`                            | `[]*User` + `ref=false` | `Vec<Arc<User>>`      | `std::vector<std::shared_ptr<User>>`      |

Use `ref(thread_safe=false)` in Fory IDL (or `[(fory).thread_safe_pointer = false]` in protobuf)
to generate `Rc` instead of `Arc` in Rust.

## Field Numbers

Each field must have a unique positive integer identifier:

```protobuf
message Example {
    string first = 1;
    string second = 2;
    string third = 3;
}
```

**Rules and best practices:**

- Numbers must be unique within a message.
- Numbers must be positive integers.
- Gaps are allowed and are useful when fields are removed.
- Prefer sequential numbering from `1`.
- Never reuse a removed field number for a different field.

## Type System

Fory IDL provides a cross-language type system for primitives, named types, and
collections. Field modifiers (`optional`, `list`, `ref`) control nullability,
collection behavior, and reference tracking (see
[Field Modifiers](#field-modifiers)).

### Primitive Types

| Type            | Description                               | Size     |
| --------------- | ----------------------------------------- | -------- |
| `bool`          | Boolean value                             | 1 byte   |
| `int8`          | Signed 8-bit integer                      | 1 byte   |
| `int16`         | Signed 16-bit integer                     | 2 bytes  |
| `int32`         | Signed 32-bit integer (varint encoding)   | 4 bytes  |
| `int64`         | Signed 64-bit integer (varint encoding)   | 8 bytes  |
| `uint8`         | Unsigned 8-bit integer                    | 1 byte   |
| `uint16`        | Unsigned 16-bit integer                   | 2 bytes  |
| `uint32`        | Unsigned 32-bit integer (varint encoding) | 4 bytes  |
| `uint64`        | Unsigned 64-bit integer (varint encoding) | 8 bytes  |
| `fixed_int32`   | Signed 32-bit integer (fixed encoding)    | 4 bytes  |
| `fixed_int64`   | Signed 64-bit integer (fixed encoding)    | 8 bytes  |
| `fixed_uint32`  | Unsigned 32-bit integer (fixed encoding)  | 4 bytes  |
| `fixed_uint64`  | Unsigned 64-bit integer (fixed encoding)  | 8 bytes  |
| `tagged_int64`  | Signed 64-bit integer (tagged encoding)   | 8 bytes  |
| `tagged_uint64` | Unsigned 64-bit integer (tagged encoding) | 8 bytes  |
| `float32`       | 32-bit floating point                     | 4 bytes  |
| `float64`       | 64-bit floating point                     | 8 bytes  |
| `string`        | UTF-8 string                              | Variable |
| `bytes`         | Binary data                               | Variable |
| `date`          | Calendar date                             | Variable |
| `timestamp`     | Date and time with timezone               | Variable |
| `duration`      | Duration                                  | Variable |
| `decimal`       | Decimal value                             | Variable |
| `any`           | Dynamic value (runtime type)              | Variable |

#### Boolean

| Language | Type                  | Notes              |
| -------- | --------------------- | ------------------ |
| Java     | `boolean` / `Boolean` | Primitive or boxed |
| Python   | `bool`                |                    |
| Go       | `bool`                |                    |
| Rust     | `bool`                |                    |
| C++      | `bool`                |                    |

#### Integer Types

Fory IDL provides fixed-width signed integers (varint encoding for 32/64-bit by default):

| Fory IDL Type | Size   | Range             |
| ------------- | ------ | ----------------- |
| `int8`        | 8-bit  | -128 to 127       |
| `int16`       | 16-bit | -32,768 to 32,767 |
| `int32`       | 32-bit | -2^31 to 2^31 - 1 |
| `int64`       | 64-bit | -2^63 to 2^63 - 1 |

**Language Mapping (Signed):**

| Fory IDL | Java    | Python         | Go      | Rust  | C++       |
| -------- | ------- | -------------- | ------- | ----- | --------- |
| `int8`   | `byte`  | `pyfory.int8`  | `int8`  | `i8`  | `int8_t`  |
| `int16`  | `short` | `pyfory.int16` | `int16` | `i16` | `int16_t` |
| `int32`  | `int`   | `pyfory.int32` | `int32` | `i32` | `int32_t` |
| `int64`  | `long`  | `pyfory.int64` | `int64` | `i64` | `int64_t` |

Fory IDL provides fixed-width unsigned integers (varint encoding for 32/64-bit by default):

| Fory IDL | Size   | Range         |
| -------- | ------ | ------------- |
| `uint8`  | 8-bit  | 0 to 255      |
| `uint16` | 16-bit | 0 to 65,535   |
| `uint32` | 32-bit | 0 to 2^32 - 1 |
| `uint64` | 64-bit | 0 to 2^64 - 1 |

**Language Mapping (Unsigned):**

| Fory IDL | Java    | Python          | Go       | Rust  | C++        |
| -------- | ------- | --------------- | -------- | ----- | ---------- |
| `uint8`  | `short` | `pyfory.uint8`  | `uint8`  | `u8`  | `uint8_t`  |
| `uint16` | `int`   | `pyfory.uint16` | `uint16` | `u16` | `uint16_t` |
| `uint32` | `long`  | `pyfory.uint32` | `uint32` | `u32` | `uint32_t` |
| `uint64` | `long`  | `pyfory.uint64` | `uint64` | `u64` | `uint64_t` |

#### Integer Encoding Variants

For 32/64-bit integers, Fory IDL uses varint encoding by default. Use explicit types when
you need fixed-width or tagged encoding:

| Fory IDL Type   | Encoding | Notes                    |
| --------------- | -------- | ------------------------ |
| `fixed_int32`   | fixed    | Signed 32-bit            |
| `fixed_int64`   | fixed    | Signed 64-bit            |
| `fixed_uint32`  | fixed    | Unsigned 32-bit          |
| `fixed_uint64`  | fixed    | Unsigned 64-bit          |
| `tagged_int64`  | tagged   | Signed 64-bit (hybrid)   |
| `tagged_uint64` | tagged   | Unsigned 64-bit (hybrid) |

#### Floating-Point Types

| Fory IDL Type | Size   | Precision     |
| ------------- | ------ | ------------- |
| `float32`     | 32-bit | ~7 digits     |
| `float64`     | 64-bit | ~15-16 digits |

**Language Mapping:**

| Fory IDL  | Java     | Python           | Go        | Rust  | C++      |
| --------- | -------- | ---------------- | --------- | ----- | -------- |
| `float32` | `float`  | `pyfory.float32` | `float32` | `f32` | `float`  |
| `float64` | `double` | `pyfory.float64` | `float64` | `f64` | `double` |

#### String Type

| Language | Type          | Notes                 |
| -------- | ------------- | --------------------- |
| Java     | `String`      | Immutable             |
| Python   | `str`         |                       |
| Go       | `string`      | Immutable             |
| Rust     | `String`      | Owned, heap-allocated |
| C++      | `std::string` |                       |

#### Bytes Type

| Language | Type                   | Notes     |
| -------- | ---------------------- | --------- |
| Java     | `byte[]`               |           |
| Python   | `bytes`                | Immutable |
| Go       | `[]byte`               |           |
| Rust     | `Vec<u8>`              |           |
| C++      | `std::vector<uint8_t>` |           |

#### Temporal Types

##### Date

| Language | Type                        | Notes                   |
| -------- | --------------------------- | ----------------------- |
| Java     | `java.time.LocalDate`       |                         |
| Python   | `datetime.date`             |                         |
| Go       | `time.Time`                 | Time portion ignored    |
| Rust     | `chrono::NaiveDate`         | Requires `chrono` crate |
| C++      | `fory::serialization::Date` |                         |

##### Timestamp

| Language | Type                             | Notes                   |
| -------- | -------------------------------- | ----------------------- |
| Java     | `java.time.Instant`              | UTC-based               |
| Python   | `datetime.datetime`              |                         |
| Go       | `time.Time`                      |                         |
| Rust     | `chrono::NaiveDateTime`          | Requires `chrono` crate |
| C++      | `fory::serialization::Timestamp` |                         |

#### Any

| Language | Type           | Notes                |
| -------- | -------------- | -------------------- |
| Java     | `Object`       | Runtime type written |
| Python   | `Any`          | Runtime type written |
| Go       | `any`          | Runtime type written |
| Rust     | `Box<dyn Any>` | Runtime type written |
| C++      | `std::any`     | Runtime type written |

**Example:**

```protobuf
enum EventType [id=120] {
    CREATED = 0;
    DELETED = 1;
}

message UserCreated [id=121] {
    string user_id = 1;
}

message Envelope [id=122] {
    EventType type = 1;
    any payload = 2;
}
```

**Generated Code (`Envelope.payload`):**

| Language | Generated Field Type    |
| -------- | ----------------------- |
| Java     | `Object payload`        |
| Python   | `payload: Any`          |
| Go       | `Payload any`           |
| Rust     | `payload: Box<dyn Any>` |
| C++      | `std::any payload`      |

**Notes:**

- `any` always writes a null flag (same as `nullable`) because values may be empty.
- Allowed runtime values are limited to `bool`, `string`, `enum`, `message`, and `union`.
  Other primitives (numeric, bytes, date/time) and list/map are not supported; wrap them in a
  message or use explicit fields instead.
- `ref` is not allowed on `any` fields (including list/map values). Wrap `any` in a message
  if you need reference tracking.
- The runtime type must be registered in the target language schema/IDL registration; unknown
  types fail to deserialize.

### Named Types

Reference other messages, enums, or unions by name:

```protobuf
enum Status { ... }
message User { ... }

message Order {
    User customer = 1;    // Reference to User message
    Status status = 2;    // Reference to Status enum
}
```

### Collection Types

#### List (`list`)

Use the `list<...>` type for list fields. `repeated` is accepted as an alias. See [Field Modifiers](#field-modifiers) for
modifier combinations and language mapping.

Nested collection types are not supported. Use a message wrapper if you need
`list<list<...>>`, `list<map<...>>`, or `map<..., list<...>>`.

#### Map

Maps with typed keys and values:

```protobuf
message Config {
    map<string, string> properties = 1;
    map<string, int32> counts = 2;
    map<int32, User> users = 3;
}
```

**Language Mapping:**

| Fory IDL             | Java                   | Python            | Go                 | Rust                    | C++                              |
| -------------------- | ---------------------- | ----------------- | ------------------ | ----------------------- | -------------------------------- |
| `map<string, int32>` | `Map<String, Integer>` | `Dict[str, int]`  | `map[string]int32` | `HashMap<String, i32>`  | `std::map<std::string, int32_t>` |
| `map<string, User>`  | `Map<String, User>`    | `Dict[str, User]` | `map[string]User`  | `HashMap<String, User>` | `std::map<std::string, User>`    |

**Key Type Restrictions:**

- `string` (most common)
- Integer types (`int8`, `int16`, `int32`, `int64`)
- `bool`

Avoid using messages or complex types as keys.

### Type Compatibility Matrix

This matrix shows which type conversions are safe across languages:

| From -> To | bool | int8 | int16 | int32 | int64 | float32 | float64 | string |
| ---------- | ---- | ---- | ----- | ----- | ----- | ------- | ------- | ------ |
| bool       | Y    | Y    | Y     | Y     | Y     | -       | -       | -      |
| int8       | -    | Y    | Y     | Y     | Y     | Y       | Y       | -      |
| int16      | -    | -    | Y     | Y     | Y     | Y       | Y       | -      |
| int32      | -    | -    | -     | Y     | Y     | -       | Y       | -      |
| int64      | -    | -    | -     | -     | Y     | -       | -       | -      |
| float32    | -    | -    | -     | -     | -     | Y       | Y       | -      |
| float64    | -    | -    | -     | -     | -     | -       | Y       | -      |
| string     | -    | -    | -     | -     | -     | -       | -       | Y      |

Y = Safe conversion, - = Not recommended

### Best Practices

- Use `int32` as the default for most integers; use `int64` for large values.
- Use `string` for text data (UTF-8) and `bytes` for binary data.
- Use `optional` only when the field may legitimately be absent.
- Use `ref` only when needed for shared or circular references.
- Prefer `list` for ordered sequences and `map` for key-value lookups.

## Type IDs

Type IDs enable efficient cross-language serialization and are used for
messages, unions, and enums. When `enable_auto_type_id = true` (default) and
`id` is omitted, the compiler auto-generates one using
`MurmurHash3(utf8(package.type_name))` (32-bit) and annotates it in generated
code. When `enable_auto_type_id = false`, types without explicit IDs are
registered by namespace and name instead. Collisions are detected at
compile-time across the current file and all imports; when a collision occurs,
the compiler raises an error and asks for an explicit `id` or an `alias`.

```protobuf
enum Color [id=100] { ... }
message User [id=101] { ... }
union Event [id=102] { ... }
```

Enum type IDs remain optional; if omitted they are auto-generated using the same
hash when `enable_auto_type_id = true`.

### With Explicit Type ID

```protobuf
message User [id=101] { ... }
message User [id=101, deprecated=true] { ... }  // Multiple options
```

### Without Explicit Type ID

```protobuf
message Config { ... }  // Auto-generated when enable_auto_type_id = true
```

You can set `[alias="..."]` to change the hash source without renaming the type.

### Practical Notes

- If a type omits `id` and `enable_auto_type_id = true`, Fory generates an ID
  with `MurmurHash3(utf8(package.type_name))` (32-bit).
- Package alias and type alias change the hash input and can be used to resolve
  hash collisions without renaming public types.
- Manual IDs in the small varint range (`0-127`) are compact on the wire; auto
  IDs are typically larger and usually consume 4-5 bytes.

### ID Assignment Strategy

```protobuf
// Enums: 100-199
enum Status [id=100] { ... }
enum Priority [id=101] { ... }

// User domain: 200-299
message User [id=200] { ... }
message UserProfile [id=201] { ... }

// Order domain: 300-399
message Order [id=300] { ... }
message OrderItem [id=301] { ... }
```

## Complete Example

```protobuf
// E-commerce domain model
package com.shop.models;

// Enums with type IDs
enum OrderStatus [id=100] {
    PENDING = 0;
    CONFIRMED = 1;
    SHIPPED = 2;
    DELIVERED = 3;
    CANCELLED = 4;
}

enum PaymentMethod [id=101] {
    CREDIT_CARD = 0;
    DEBIT_CARD = 1;
    PAYPAL = 2;
    BANK_TRANSFER = 3;
}

// Messages with type IDs
message Address [id=200] {
    string street = 1;
    string city = 2;
    string state = 3;
    string country = 4;
    string postal_code = 5;
}

message Customer [id=201] {
    string id = 1;
    string name = 2;
    optional string email = 3;
    optional string phone = 4;
    optional Address billing_address = 5;
    optional Address shipping_address = 6;
}

message Product [id=202] {
    string sku = 1;
    string name = 2;
    string description = 3;
    float64 price = 4;
    int32 stock = 5;
    list<string> categories = 6;
    map<string, string> attributes = 7;
}

message OrderItem [id=203] {
    ref Product product = 1;  // Track reference to avoid duplication
    int32 quantity = 2;
    float64 unit_price = 3;
}

message Order [id=204] {
    string id = 1;
    ref Customer customer = 2;
    list<OrderItem> items = 3;
    OrderStatus status = 4;
    PaymentMethod payment_method = 5;
    float64 total = 6;
    optional string notes = 7;
    timestamp created_at = 8;
    optional timestamp shipped_at = 9;
}

// Config without explicit type ID (auto-generated when enable_auto_type_id = true)
message ShopConfig {
    string store_name = 1;
    string currency = 2;
    float64 tax_rate = 3;
    list<string> supported_countries = 4;
}
```

For protobuf-specific extension options and `(fory).` syntax, see
[Protocol Buffers IDL Support](protobuf-idl.md#fory-extension-options-protobuf).

## Grammar Summary

```
file         := [package_decl] file_option* import_decl* type_def*

package_decl := 'package' package_name ['alias' package_name] ';'
package_name := IDENTIFIER ('.' IDENTIFIER)*

file_option  := 'option' option_name '=' option_value ';'
option_name  := IDENTIFIER

import_decl  := 'import' STRING ';'

type_def     := enum_def | message_def | union_def

enum_def     := 'enum' IDENTIFIER [type_options] '{' enum_body '}'
enum_body    := (reserved_stmt | enum_value)*
enum_value   := IDENTIFIER '=' INTEGER ';'

message_def  := 'message' IDENTIFIER [type_options] '{' message_body '}'
message_body := (reserved_stmt | nested_type | field_def)*
nested_type  := enum_def | message_def | union_def
field_def    := [modifiers] field_type IDENTIFIER '=' INTEGER [field_options] ';'

union_def    := 'union' IDENTIFIER [type_options] '{' union_field* '}'
union_field  := ['repeated'] field_type IDENTIFIER '=' INTEGER [field_options] ';'
option_value := 'true' | 'false' | IDENTIFIER | INTEGER | STRING

reserved_stmt := 'reserved' reserved_items ';'
reserved_items := reserved_item (',' reserved_item)*
reserved_item := INTEGER | INTEGER 'to' INTEGER | INTEGER 'to' 'max' | STRING

modifiers    := { 'optional' | 'ref' | 'repeated' }

field_type   := primitive_type | named_type | list_type | map_type
primitive_type := 'bool'
               | 'int8' | 'int16' | 'int32' | 'int64'
               | 'uint8' | 'uint16' | 'uint32' | 'uint64'
               | 'fixed_int32' | 'fixed_int64' | 'fixed_uint32' | 'fixed_uint64'
               | 'tagged_int64' | 'tagged_uint64'
               | 'float32' | 'float64'
               | 'string' | 'bytes'
               | 'date' | 'timestamp' | 'duration' | 'decimal'
               | 'any'
named_type   := qualified_name
qualified_name := IDENTIFIER ('.' IDENTIFIER)*   // e.g., Parent.Child
list_type    := 'list' '<' { 'optional' | 'ref' } field_type '>'
map_type     := 'map' '<' field_type ',' field_type '>'

type_options := '[' type_option (',' type_option)* ']'
type_option  := IDENTIFIER '=' option_value         // e.g., id=100, deprecated=true
field_options := '[' field_option (',' field_option)* ']'
field_option := IDENTIFIER '=' option_value         // e.g., deprecated=true, ref=true

STRING       := '"' [^"\n]* '"' | "'" [^'\n]* "'"
IDENTIFIER   := [a-zA-Z_][a-zA-Z0-9_]*
INTEGER      := '-'? [0-9]+
```
