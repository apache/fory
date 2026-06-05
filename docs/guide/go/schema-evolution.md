---
title: Schema Evolution
sidebar_position: 9
id: schema_evolution
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

Schema evolution allows your data structures to change over time while maintaining compatibility with previously serialized data. Fory Go supports this through compatible mode, which is the default for both xlang and native mode.

## Compatible Mode Defaults

For cross-language payloads, create a Fory instance with the default xlang settings:

```go
f := fory.New(fory.WithXlang(true))
```

For Go-only native-mode payloads that need schema evolution, use native mode and keep the compatible
default:

```go
f := fory.New(fory.WithXlang(false))
```

## How It Works

### With Compatible Mode

- Type metadata is written to serialized data
- Supports adding, removing, and reordering fields
- Enables forward and backward compatibility

### Same-Schema Optimization

- Compact serialization without evolution metadata
- Struct hash is checked during deserialization
- Any schema change causes `ErrKindHashMismatch`

## Supported Schema Changes

### Adding Fields

New fields can be added; they receive zero values when deserializing old data:

```go
// Version 1
type UserV1 struct {
    ID   int64
    Name string
}

// Version 2 (added Email)
type UserV2 struct {
    ID    int64
    Name  string
    Email string  // New field
}

f := fory.New(fory.WithXlang(true))
f.RegisterStruct(UserV1{}, 1)

// Serialize with V1
userV1 := &UserV1{ID: 1, Name: "Alice"}
data, _ := f.Serialize(userV1)

// Deserialize with V2
f2 := fory.New(fory.WithXlang(true))
f2.RegisterStruct(UserV2{}, 1)

var userV2 UserV2
f2.Deserialize(data, &userV2)
// userV2.Email = "" (zero value)
```

### Removing Fields

Removed fields are skipped during deserialization:

```go
// Version 1
type ConfigV1 struct {
    Host     string
    Port     int32
    Timeout  int64
    Debug    bool  // Will be removed
}

// Version 2 (removed Debug)
type ConfigV2 struct {
    Host    string
    Port    int32
    Timeout int64
    // Debug field removed
}

f := fory.New(fory.WithXlang(true))
f.RegisterStruct(ConfigV1{}, 1)

// Serialize with V1
config := &ConfigV1{Host: "localhost", Port: 8080, Timeout: 30, Debug: true}
data, _ := f.Serialize(config)

// Deserialize with V2
f2 := fory.New(fory.WithXlang(true))
f2.RegisterStruct(ConfigV2{}, 1)

var configV2 ConfigV2
f2.Deserialize(data, &configV2)
// Debug field data is skipped
```

### Reordering Fields

Field order can change between versions:

```go
// Version 1
type PersonV1 struct {
    FirstName string
    LastName  string
    Age       int32
}

// Version 2 (reordered)
type PersonV2 struct {
    Age       int32   // Moved up
    LastName  string
    FirstName string  // Moved down
}
```

Compatible mode handles this automatically by matching fields by name.

### Compatible Scalar Field Changes

Compatible mode can also read selected scalar type changes for matched top-level
struct fields when the serialized value converts without changing its logical
value:

- `bool` fields can be read from strings that are exactly `"0"`, `"1"`,
  `"true"`, or `"false"`. Bool values read as strings become `"true"` or
  `"false"`, and numeric `0` and `1` can be read as bools.
- Integer, unsigned integer, floating point, and decimal fields can be read
  across numeric scalar types only when the value is represented exactly by the
  target field type.
- Numeric fields can be read from strings only when the string is a finite ASCII
  decimal literal with no whitespace, leading `+`, Unicode digits, separators,
  radix prefixes, or special values such as `NaN` and `Infinity`.
- Numeric fields read as strings use canonical output: integers have normal
  decimal text, floating point values use exact plain decimal text with a
  decimal point, and decimals omit insignificant trailing fractional zeros.

Scalar conversion composes with pointer and `optional.Optional[T]` fields when
the matched top-level scalar field is not reference-tracked. If a remote
nullable or optional field is absent, the local field follows the normal
missing/null compatible-mode behavior. Reference-tracked scalar type changes are
incompatible. If a present value cannot be converted losslessly,
deserialization fails with a data error instead of treating the field as
missing.

## Incompatible Changes

Some changes are NOT supported, even in compatible mode:

### Type Changes

```go
// NOT SUPPORTED
type V1 struct {
    Value []int32  // list of int32
}

type V2 struct {
    Value []string  // Element type changed - INCOMPATIBLE
}
```

### Renaming Fields

```go
// NOT SUPPORTED (treated as remove + add)
type V1 struct {
    UserName string
}

type V2 struct {
    Username string  // Different name - NOT a rename
}
```

This is treated as removing `UserName` and adding `Username`, resulting in data loss.

## Best Practices

### 1. Use Compatible Mode for Persistent Data

```go
// Default xlang payloads already use compatible mode.
f := fory.New(fory.WithXlang(true))
```

For Go-only native-mode data stored in databases, files, or caches, use compatible mode:

```go
f := fory.New(fory.WithXlang(false))
```

### 2. Provide Default Values

```go
type ConfigV2 struct {
    Host    string
    Port    int32
    Timeout int64
    Retries int32  // New field
}

func NewConfigV2() *ConfigV2 {
    return &ConfigV2{
        Retries: 3,  // Default value
    }
}

// After deserialize, apply defaults
if config.Retries == 0 {
    config.Retries = 3
}
```

## Xlang Schema Evolution

Schema evolution works across languages:

### Go (Producer)

```go
type MessageV1 struct {
    ID      int64
    Content string
}

f := fory.New(fory.WithXlang(true))
f.RegisterStruct(MessageV1{}, 1)
data, _ := f.Serialize(&MessageV1{ID: 1, Content: "Hello"})
```

### Java (Consumer with newer schema)

```java
public class Message {
    long id;
    String content;
    String author;  // New field in Java
}

Fory fory = Fory.builder().withXlang(true).build();
fory.register(Message.class, 1);
Message msg = fory.deserialize(data, Message.class);
// msg.author will be null
```

## Performance Considerations

Compatible mode mainly affects serialized size:

| Aspect             | `WithCompatible(false)` | Compatible mode                                          |
| ------------------ | ----------------------- | -------------------------------------------------------- |
| Serialized Size    | Smaller                 | Larger (includes metadata, especially without field IDs) |
| Speed              | Fast                    | Similar (metadata is just memcpy)                        |
| Schema Flexibility | Same schema required    | Add, remove, and reorder fields                          |

**Note**: Using field IDs (`fory:"id=N"`) reduces metadata size in compatible mode.

**Recommendation**: Use compatible mode for:

- Persistent storage
- Cross-service communication
- Long-lived caches

Use `WithCompatible(false)` only when every reader and writer always uses the same Go struct schema
and you want faster serialization and smaller size. For xlang payloads, use `WithCompatible(false)` only after verifying that every language uses the same schema, or when native types are generated from Fory schema IDL. Same-schema uses include:

- In-memory operations
- Same-schema communication
- Faster serialization and smaller size

### Per-Struct Opt-Out

For one struct, you can opt out of evolution metadata by implementing `ForyEvolving` and returning
`false`:

```go
type SameSchemaMessage struct {
    ID int64
}

func (SameSchemaMessage) ForyEvolving() bool {
    return false
}
```

## Error Handling

### Hash Mismatch (Native Same-Schema Mode)

```go
f := fory.New(fory.WithXlang(false), fory.WithCompatible(false))

// Schema changed without compatible mode
err := f.Deserialize(oldData, &newStruct)
// Error: ErrKindHashMismatch
```

### Unknown Fields

In compatible mode, unknown fields are skipped silently. To detect them:

```go
// Currently, Fory skips unknown fields automatically
// No explicit API for detecting unknown fields
```

## Complete Example

```go
package main

import (
    "fmt"
    "github.com/apache/fory/go/fory"
)

// V1: Initial schema
type ProductV1 struct {
    ID    int64
    Name  string
    Price float64
}

// V2: Added fields
type ProductV2 struct {
    ID          int64
    Name        string
    Price       float64
    Description string  // New
    InStock     bool    // New
}

func main() {
    // Serialize with V1
    f1 := fory.New(fory.WithXlang(true))
    f1.RegisterStruct(ProductV1{}, 1)

    product := &ProductV1{ID: 1, Name: "Widget", Price: 9.99}
    data, _ := f1.Serialize(product)
    fmt.Printf("V1 serialized: %d bytes\n", len(data))

    // Deserialize with V2
    f2 := fory.New(fory.WithXlang(true))
    f2.RegisterStruct(ProductV2{}, 1)

    var productV2 ProductV2
    if err := f2.Deserialize(data, &productV2); err != nil {
        panic(err)
    }

    fmt.Printf("ID: %d\n", productV2.ID)
    fmt.Printf("Name: %s\n", productV2.Name)
    fmt.Printf("Price: %.2f\n", productV2.Price)
    fmt.Printf("Description: %q (zero value)\n", productV2.Description)
    fmt.Printf("InStock: %v (zero value)\n", productV2.InStock)
}
```

## Related Topics

- [Configuration](configuration.md)
- [Xlang Serialization](xlang-serialization.md)
- [Troubleshooting](troubleshooting.md)
