---
title: FDL Syntax Reference
sidebar_position: 2
id: fdl_syntax
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

# FDL Syntax Reference

This document provides a complete reference for the Fory Definition Language (FDL) syntax.

## File Structure

An FDL file consists of:

1. Optional package declaration
2. Optional import statements
3. Type definitions (enums and messages)

```fdl
// Optional package declaration
package com.example.models;

// Import statements
import "common/types.fdl";

// Type definitions
enum Color [id=100] { ... }
message User [id=101] { ... }
message Order [id=102] { ... }
```

## Comments

FDL supports both single-line and block comments:

```fdl
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

```fdl
package com.example.models;
```

**Rules:**

- Optional but recommended
- Must appear before any type definitions
- Only one package declaration per file
- Used for namespace-based type registration

**Language Mapping:**

| Language | Package Usage                     |
| -------- | --------------------------------- |
| Java     | Java package                      |
| Python   | Module name (dots to underscores) |
| Go       | Package name (last component)     |
| Rust     | Module name (dots to underscores) |
| C++      | Namespace (dots to `::`)          |

## File-Level Options

Options can be specified at file level to control language-specific code generation.

### Syntax

```fdl
option option_name = value;
```

### Java Package Option

Override the Java package for generated code:

```fdl
package payment;
option java_package = "com.mycorp.payment.v1";

message Payment {
    string id = 1;
}
```

**Effect:**

- Generated Java files will be in `com/mycorp/payment/v1/` directory
- Java package declaration will be `package com.mycorp.payment.v1;`
- Type registration still uses the FDL package (`payment`) for cross-language compatibility

### Go Package Option

Specify the Go import path and package name:

```fdl
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
- Type registration still uses the FDL package (`payment`) for cross-language compatibility

### Java Outer Classname Option

Generate all types as inner classes of a single outer wrapper class:

```fdl
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

```fdl
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

```fdl
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

```fdl
option java_outer_classname = "PaymentProtos";
// Generates: PaymentProtos.java containing Payment and Receipt as inner classes
```

**Example with java_multiple_files = true:**

```fdl
option java_outer_classname = "PaymentProtos";
option java_multiple_files = true;
// Generates: Payment.java, Receipt.java (separate files)
```

### Multiple Options

Multiple options can be specified:

```fdl
package payment;
option java_package = "com.mycorp.payment.v1";
option go_package = "github.com/mycorp/apis/gen/payment/v1;paymentv1";
option deprecated = true;

message Payment {
    string id = 1;
}
```

### Fory Extension Options

FDL supports protobuf-style extension options for Fory-specific configuration:

```fdl
option (fory).use_record_for_java_message = true;
option (fory).polymorphism = true;
```

**Available File Options:**

| Option                        | Type | Description                              |
| ----------------------------- | ---- | ---------------------------------------- |
| `use_record_for_java_message` | bool | Generate Java records instead of classes |
| `polymorphism`                | bool | Enable polymorphism for all types        |

See the [Fory Extension Options](#fory-extension-options) section for complete documentation of message, enum, and field options.

### Option Priority

For language-specific packages:

1. Command-line package override (highest priority)
2. Language-specific option (`java_package`, `go_package`)
3. FDL package declaration (fallback)

**Example:**

```fdl
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

```fdl
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

Import statements allow you to use types defined in other FDL files.

### Basic Syntax

```fdl
import "path/to/file.fdl";
```

### Multiple Imports

```fdl
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

```fdl
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

```fdl
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

```fdl
// NOT SUPPORTED - will produce an error
import public "other.fdl";
import weak "other.fdl";
```

**`import public`**: FDL uses a simpler import model. All imported types are available to the importing file only. Re-exporting is not supported. Import each file directly where needed.

**`import weak`**: FDL requires all imports to be present at compile time. Optional dependencies are not supported.

### Import Errors

The compiler reports errors for:

- **File not found**: The imported file doesn't exist
- **Circular import**: A imports B which imports A (directly or indirectly)
- **Parse errors**: Syntax errors in imported files
- **Unsupported syntax**: `import public` or `import weak`

## Enum Definition

Enums define a set of named integer constants.

### Basic Syntax

```fdl
enum Status {
    PENDING = 0;
    ACTIVE = 1;
    COMPLETED = 2;
}
```

### With Type ID

```fdl
enum Status [id=100] {
    PENDING = 0;
    ACTIVE = 1;
    COMPLETED = 2;
}
```

### Reserved Values

Reserve field numbers or names to prevent reuse:

```fdl
enum Status {
    reserved 2, 15, 9 to 11, 40 to max;  // Reserved numbers
    reserved "OLD_STATUS", "DEPRECATED"; // Reserved names
    PENDING = 0;
    ACTIVE = 1;
    COMPLETED = 3;
}
```

### Enum Options

Options can be specified within enums:

```fdl
enum Status {
    option deprecated = true;  // Allowed
    PENDING = 0;
    ACTIVE = 1;
}
```

**Forbidden Options:**

- `option allow_alias = true` is **not supported**. Each enum value must have a unique integer.

### Enum Prefix Stripping

When enum values use a protobuf-style prefix (enum name in UPPER_SNAKE_CASE), the compiler automatically strips the prefix for languages with scoped enums:

```fdl
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
enum_body    := (option_stmt | reserved_stmt | enum_value)*
option_stmt  := 'option' IDENTIFIER '=' option_value ';'
reserved_stmt := 'reserved' reserved_items ';'
enum_value   := IDENTIFIER '=' INTEGER ';'
```

**Rules:**

- Enum names must be unique within the file
- Enum values must have explicit integer assignments
- Value integers must be unique within the enum (no aliases)
- Type ID (`[id=100]`) is optional but recommended for cross-language use

**Example with All Features:**

```fdl
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

```fdl
message Person {
    string name = 1;
    int32 age = 2;
}
```

### With Type ID

```fdl
message Person [id=101] {
    string name = 1;
    int32 age = 2;
}
```

### Reserved Fields

Reserve field numbers or names to prevent reuse after removing fields:

```fdl
message User {
    reserved 2, 15, 9 to 11;       // Reserved field numbers
    reserved "old_field", "temp";  // Reserved field names
    string id = 1;
    string name = 3;
}
```

### Message Options

Options can be specified within messages:

```fdl
message User {
    option deprecated = true;
    string id = 1;
    string name = 2;
}
```

**Grammar:**

```
message_def  := 'message' IDENTIFIER [type_options] '{' message_body '}'
type_options := '[' type_option (',' type_option)* ']'
type_option  := IDENTIFIER '=' option_value
message_body := (option_stmt | reserved_stmt | nested_type | field_def)*
nested_type  := enum_def | message_def
```

## Nested Types

Messages can contain nested message and enum definitions. This is useful for defining types that are closely related to their parent message.

### Nested Messages

```fdl
message SearchResponse {
    message Result {
        string url = 1;
        string title = 2;
        repeated string snippets = 3;
    }
    repeated Result results = 1;
}
```

### Nested Enums

```fdl
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

```fdl
message SearchResponse {
    message Result {
        string url = 1;
        string title = 2;
    }
}

message SearchResultCache {
    // Reference nested type with qualified name
    SearchResponse.Result cached_result = 1;
    repeated SearchResponse.Result all_results = 2;
}
```

### Deeply Nested Types

Nesting can be multiple levels deep:

```fdl
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

| Language | Nested Type Generation                                 |
| -------- | ------------------------------------------------------ |
| Java     | Static inner classes (`SearchResponse.Result`)         |
| Python   | Nested classes within dataclass                        |
| Go       | Flat structs with underscore (`SearchResponse_Result`) |
| Rust     | Flat structs with underscore (`SearchResponse_Result`) |
| C++      | Flat structs with underscore (`SearchResponse_Result`) |

**Note:** For Go, Rust, and C++, nested types are flattened to top-level types with qualified names using underscores because these languages don't have true nested type support or it's not idiomatic.

### Nested Type Rules

- Nested type names must be unique within their parent message
- Nested types can have their own type IDs
- Type IDs must be globally unique (including nested types)
- Within a message, you can reference nested types by simple name
- From outside, use the qualified name (Parent.Child)

## Field Definition

Fields define the properties of a message.

### Basic Syntax

```fdl
field_type field_name = field_number;
```

### With Modifiers

```fdl
optional ref repeated field_type field_name = field_number;
```

**Grammar:**

```
field_def    := [modifiers] field_type IDENTIFIER '=' INTEGER ';'
modifiers    := ['optional'] ['ref'] ['repeated']
field_type   := primitive_type | named_type | map_type
```

### Field Modifiers

#### `optional`

Marks the field as nullable:

```fdl
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

#### `ref`

Enables reference tracking for shared/circular references:

```fdl
message Node {
    string value = 1;
    ref Node parent = 2;     // Can point to shared object
    repeated ref Node children = 3;
}
```

**Use Cases:**

- Shared objects (same object referenced multiple times)
- Circular references (object graphs with cycles)
- Tree structures with parent pointers

**Generated Code:**

| Language | Without `ref`  | With `ref`                                        |
| -------- | -------------- | ------------------------------------------------- |
| Java     | `Node parent`  | `Node parent` with `@ForyField(trackingRef=true)` |
| Python   | `parent: Node` | `parent: Node` (runtime tracking)                 |
| Go       | `Parent Node`  | `Parent *Node` with `fory:"trackRef"`             |
| Rust     | `parent: Node` | `parent: Rc<Node>`                                |
| C++      | `Node parent`  | `std::shared_ptr<Node> parent`                    |

#### `repeated`

Marks the field as a list/array:

```fdl
message Document {
    repeated string tags = 1;
    repeated User authors = 2;
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
    optional repeated string tags = 1;  // Nullable list
    repeated ref Node nodes = 2;        // List of tracked references
    optional ref User owner = 3;        // Nullable tracked reference
}
```

**Order:** `optional` must come before `ref`, which must come before `repeated`.

## Type System

### Primitive Types

| Type        | Description                 | Size     |
| ----------- | --------------------------- | -------- |
| `bool`      | Boolean value               | 1 byte   |
| `int8`      | Signed 8-bit integer        | 1 byte   |
| `int16`     | Signed 16-bit integer       | 2 bytes  |
| `int32`     | Signed 32-bit integer       | 4 bytes  |
| `int64`     | Signed 64-bit integer       | 8 bytes  |
| `float32`   | 32-bit floating point       | 4 bytes  |
| `float64`   | 64-bit floating point       | 8 bytes  |
| `string`    | UTF-8 string                | Variable |
| `bytes`     | Binary data                 | Variable |
| `date`      | Calendar date               | Variable |
| `timestamp` | Date and time with timezone | Variable |

See [Type System](type-system.md) for complete type mappings.

### Named Types

Reference other messages or enums by name:

```fdl
enum Status { ... }
message User { ... }

message Order {
    User customer = 1;    // Reference to User message
    Status status = 2;    // Reference to Status enum
}
```

### Map Types

Maps with typed keys and values:

```fdl
message Config {
    map<string, string> properties = 1;
    map<string, int32> counts = 2;
    map<int32, User> users = 3;
}
```

**Syntax:** `map<KeyType, ValueType>`

**Restrictions:**

- Key type should be a primitive type (typically `string` or integer types)
- Value type can be any type including messages

## Field Numbers

Each field must have a unique positive integer identifier:

```fdl
message Example {
    string first = 1;
    string second = 2;
    string third = 3;
}
```

**Rules:**

- Must be unique within a message
- Must be positive integers
- Used for field ordering and identification
- Gaps in numbering are allowed (useful for deprecating fields)

**Best Practices:**

- Use sequential numbers starting from 1
- Reserve number ranges for different categories
- Never reuse numbers for different fields (even after deletion)

## Type IDs

Type IDs enable efficient cross-language serialization:

```fdl
enum Color [id=100] { ... }
message User [id=101] { ... }
message Order [id=102] { ... }
```

### With Type ID (Recommended)

```fdl
message User [id=101] { ... }
message User [id=101, deprecated=true] { ... }  // Multiple options
```

- Serialized as compact integer
- Fast lookup during deserialization
- Must be globally unique across all types
- Recommended for production use

### Without Type ID

```fdl
message Config { ... }
```

- Registered using namespace + name
- More flexible for development
- Slightly larger serialized size
- Uses package as namespace: `"package.Config"`

### ID Assignment Strategy

```fdl
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

```fdl
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
    repeated string categories = 6;
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
    repeated OrderItem items = 3;
    OrderStatus status = 4;
    PaymentMethod payment_method = 5;
    float64 total = 6;
    optional string notes = 7;
    timestamp created_at = 8;
    optional timestamp shipped_at = 9;
}

// Config without type ID (uses namespace registration)
message ShopConfig {
    string store_name = 1;
    string currency = 2;
    float64 tax_rate = 3;
    repeated string supported_countries = 4;
}
```

## Fory Extension Options

FDL supports protobuf-style extension options for Fory-specific configuration. These use the `(fory)` prefix to indicate they are Fory extensions.

### File-Level Fory Options

```fdl
option (fory).use_record_for_java_message = true;
option (fory).polymorphism = true;
```

| Option                        | Type | Description                              |
| ----------------------------- | ---- | ---------------------------------------- |
| `use_record_for_java_message` | bool | Generate Java records instead of classes |
| `polymorphism`                | bool | Enable polymorphism for all types        |

### Message-Level Fory Options

Options can be specified inside the message body:

```fdl
message MyMessage {
    option (fory).id = 100;
    option (fory).compatible = true;
    option (fory).use_record_for_java = true;
    string name = 1;
}
```

| Option                | Type   | Description                                |
| --------------------- | ------ | ------------------------------------------ |
| `id`                  | int    | Type ID for serialization (sets type_id)   |
| `compatible`          | bool   | Enable schema compatibility mode           |
| `use_record_for_java` | bool   | Generate Java record for this message      |
| `deprecated`          | bool   | Mark this message as deprecated            |
| `namespace`           | string | Custom namespace for type registration     |

**Note:** `option (fory).id = 100` is equivalent to the inline syntax `message MyMessage [id=100]`.

### Enum-Level Fory Options

```fdl
enum Status {
    option (fory).id = 101;
    option (fory).deprecated = true;
    UNKNOWN = 0;
    ACTIVE = 1;
}
```

| Option       | Type | Description                             |
| ------------ | ---- | --------------------------------------- |
| `id`         | int  | Type ID for serialization (sets type_id)|
| `deprecated` | bool | Mark this enum as deprecated            |

### Field-Level Fory Options

Field options are specified in brackets after the field number:

```fdl
message Example {
    MyType friend = 1 [(fory).ref = true];
    string nickname = 2 [(fory).nullable = true];
    MyType data = 3 [(fory).ref = true, (fory).nullable = true];
}
```

| Option        | Type   | Description                                |
| ------------- | ------ | ------------------------------------------ |
| `ref`         | bool   | Enable reference tracking (sets ref flag)  |
| `nullable`    | bool   | Mark field as nullable (sets optional flag)|
| `deprecated`  | bool   | Mark this field as deprecated              |

**Note:** `[(fory).ref = true]` is equivalent to using the `ref` modifier: `ref MyType friend = 1;`

### Combining Standard and Fory Options

You can combine standard options with Fory extension options:

```fdl
message User {
    option deprecated = true;        // Standard option
    option (fory).compatible = true; // Fory extension option

    string name = 1;
    MyType data = 2 [deprecated = true, (fory).ref = true];
}
```

### Fory Options Proto File

For reference, the Fory options are defined in `extension/fory_options.proto`:

```proto
// File-level options
extend google.protobuf.FileOptions {
    optional ForyFileOptions fory = 50001;
}

message ForyFileOptions {
    optional bool use_record_for_java_message = 1;
    optional bool polymorphism = 2;
}

// Message-level options
extend google.protobuf.MessageOptions {
    optional ForyMessageOptions fory = 50001;
}

message ForyMessageOptions {
    optional int32 id = 1;
    optional bool compatible = 2;
    optional bool use_record_for_java = 3;
    optional bool deprecated = 4;
    optional string namespace = 5;
}

// Field-level options
extend google.protobuf.FieldOptions {
    optional ForyFieldOptions fory = 50001;
}

message ForyFieldOptions {
    optional bool ref = 1;
    optional bool nullable = 2;
    optional bool deprecated = 3;
}
```

## Grammar Summary

```
file         := [package_decl] file_option* import_decl* type_def*

package_decl := 'package' package_name ';'
package_name := IDENTIFIER ('.' IDENTIFIER)*

file_option  := 'option' option_name '=' option_value ';'
option_name  := IDENTIFIER | extension_name
extension_name := '(' IDENTIFIER ')' '.' IDENTIFIER   // e.g., (fory).polymorphism

import_decl  := 'import' STRING ';'

type_def     := enum_def | message_def

enum_def     := 'enum' IDENTIFIER [type_options] '{' enum_body '}'
enum_body    := (option_stmt | reserved_stmt | enum_value)*
enum_value   := IDENTIFIER '=' INTEGER ';'

message_def  := 'message' IDENTIFIER [type_options] '{' message_body '}'
message_body := (option_stmt | reserved_stmt | nested_type | field_def)*
nested_type  := enum_def | message_def
field_def    := [modifiers] field_type IDENTIFIER '=' INTEGER [field_options] ';'

option_stmt  := 'option' option_name '=' option_value ';'
option_value := 'true' | 'false' | IDENTIFIER | INTEGER | STRING

reserved_stmt := 'reserved' reserved_items ';'
reserved_items := reserved_item (',' reserved_item)*
reserved_item := INTEGER | INTEGER 'to' INTEGER | INTEGER 'to' 'max' | STRING

modifiers    := ['optional'] ['ref'] ['repeated']

field_type   := primitive_type | named_type | map_type
primitive_type := 'bool' | 'int8' | 'int16' | 'int32' | 'int64'
               | 'float32' | 'float64' | 'string' | 'bytes'
               | 'date' | 'timestamp'
named_type   := qualified_name
qualified_name := IDENTIFIER ('.' IDENTIFIER)*   // e.g., Parent.Child
map_type     := 'map' '<' field_type ',' field_type '>'

type_options := '[' type_option (',' type_option)* ']'
type_option  := IDENTIFIER '=' option_value         // e.g., id=100, deprecated=true
field_options := '[' field_option (',' field_option)* ']'
field_option := option_name '=' option_value        // e.g., deprecated=true, (fory).ref=true

STRING       := '"' [^"\n]* '"' | "'" [^'\n]* "'"
IDENTIFIER   := [a-zA-Z_][a-zA-Z0-9_]*
INTEGER      := '-'? [0-9]+
```
