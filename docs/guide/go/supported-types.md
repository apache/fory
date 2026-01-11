---
title: Supported Types
sidebar_position: 40
id: go_supported_types
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

Fory Go supports a wide range of Go types for serialization. This guide covers all supported types and their cross-language mappings.

## Primitive Types

| Go Type          | Fory TypeId  | Encoding              | Notes                             |
| ---------------- | ------------ | --------------------- | --------------------------------- |
| `bool`           | BOOL (1)     | 1 byte                |                                   |
| `int8`           | INT8 (2)     | 1 byte, signed        |                                   |
| `int16`          | INT16 (3)    | 2 bytes, signed       | Little-endian                     |
| `int32`          | INT32 (4)    | Varint                | Variable-length encoding          |
| `int64`          | INT64 (6)    | Varint                | Variable-length encoding          |
| `int`            | INT32/INT64  | Varint                | Platform-dependent (32 or 64 bit) |
| `uint8` / `byte` | UINT8 (9)    | 1 byte, unsigned      |                                   |
| `uint16`         | UINT16 (10)  | 2 bytes, unsigned     | Little-endian                     |
| `uint32`         | UINT32 (11)  | Varuint               | Variable-length encoding          |
| `uint64`         | UINT64 (13)  | Varuint               | Variable-length encoding          |
| `float32`        | FLOAT32 (17) | 4 bytes               | IEEE 754                          |
| `float64`        | FLOAT64 (18) | 8 bytes               | IEEE 754                          |
| `string`         | STRING (19)  | Length-prefixed UTF-8 |                                   |

### Integer Encoding

Fory uses variable-length integer encoding (varint) for better compression:

- Small values use fewer bytes
- Negative values use ZigZag encoding
- Platform `int` maps to `int32` on 32-bit, `int64` on 64-bit systems

```go
f := fory.New()

// All integer types supported
var i8 int8 = 127
var i16 int16 = 32767
var i32 int32 = 2147483647
var i64 int64 = 9223372036854775807

data, _ := f.Serialize(i64)  // Uses varint encoding
```

## Collection Types

### Slices

| Go Type         | Fory TypeId   | Notes                 |
| --------------- | ------------- | --------------------- |
| `[]bool`        | BOOL_ARRAY    | Optimized encoding    |
| `[]int8`        | INT8_ARRAY    | Optimized encoding    |
| `[]int16`       | INT16_ARRAY   | Optimized encoding    |
| `[]int32`       | INT32_ARRAY   | Optimized encoding    |
| `[]int64`       | INT64_ARRAY   | Optimized encoding    |
| `[]float32`     | FLOAT32_ARRAY | Optimized encoding    |
| `[]float64`     | FLOAT64_ARRAY | Optimized encoding    |
| `[]string`      | LIST          | Generic list encoding |
| `[]T` (any)     | LIST (20)     | Generic list encoding |
| `[]interface{}` | LIST          | Dynamic element types |

```go
f := fory.New()

// Primitive slices (optimized)
ints := []int32{1, 2, 3, 4, 5}
data, _ := f.Serialize(ints)

// String slices
strs := []string{"a", "b", "c"}
data, _ = f.Serialize(strs)

// Struct slices
users := []User{{ID: 1}, {ID: 2}}
data, _ = f.Serialize(users)

// Dynamic slices
dynamic := []interface{}{1, "hello", true}
data, _ = f.Serialize(dynamic)
```

### Maps

| Go Type                       | Fory TypeId | Notes                   |
| ----------------------------- | ----------- | ----------------------- |
| `map[string]string`           | MAP (22)    | Optimized               |
| `map[string]int64`            | MAP         | Optimized               |
| `map[string]int32`            | MAP         | Optimized               |
| `map[string]int`              | MAP         | Optimized               |
| `map[string]float64`          | MAP         | Optimized               |
| `map[string]bool`             | MAP         | Optimized               |
| `map[int32]int32`             | MAP         | Optimized               |
| `map[int64]int64`             | MAP         | Optimized               |
| `map[int]int`                 | MAP         | Optimized               |
| `map[string]interface{}`      | MAP         | Dynamic values          |
| `map[interface{}]interface{}` | MAP         | Dynamic keys and values |

```go
f := fory.New()

// String key maps
m1 := map[string]string{"key": "value"}
m2 := map[string]int64{"count": 42}

// Integer key maps
m3 := map[int32]int32{1: 100, 2: 200}

// Dynamic maps
m4 := map[string]interface{}{
    "name": "Alice",
    "age":  int64(30),
}
```

**Note**: Not all map type combinations have optimized paths. Use the supported combinations above for best performance.

### Sets

Fory supports sets via `GenericSet`:

```go
// Sets are represented as maps with empty struct values
type StringSet map[string]struct{}

// Or use the Fory GenericSet type
```

## Time Types

| Go Type         | Fory TypeId    | Notes                |
| --------------- | -------------- | -------------------- |
| `time.Time`     | TIMESTAMP (34) | Nanosecond precision |
| `time.Duration` | DURATION (33)  | Nanosecond precision |

```go
import "time"

f := fory.New()

// Timestamp
t := time.Now()
data, _ := f.Serialize(t)

// Duration
d := 5 * time.Second
data, _ = f.Serialize(d)
```

## Struct Types

| Category                | Fory TypeId                  | Notes                       |
| ----------------------- | ---------------------------- | --------------------------- |
| Struct                  | STRUCT (25)                  | Default mode                |
| Compatible Struct       | COMPATIBLE_STRUCT (26)       | With schema evolution       |
| Named Struct            | NAMED_STRUCT (27)            | Registered by name          |
| Named Compatible Struct | NAMED_COMPATIBLE_STRUCT (28) | Named with schema evolution |

### Struct Requirements

1. **Exported fields only**: Fields starting with uppercase are serialized
2. **Supported field types**: All types listed in this document
3. **Registration**: Structs should be registered for cross-language use

```go
type User struct {
    ID       int64   // Serialized
    Name     string  // Serialized
    Age      int32   // Serialized
    password string  // NOT serialized (unexported)
}

f := fory.New()
f.RegisterByName(User{}, "example.User")

user := &User{ID: 1, Name: "Alice", Age: 30, password: "secret"}
data, _ := f.Serialize(user)
```

### Nested Structs

```go
type Address struct {
    Street  string
    City    string
    Country string
}

type Company struct {
    Name    string
    Address Address
    Founded int32
}

f := fory.New()
f.RegisterByName(Address{}, "example.Address")
f.RegisterByName(Company{}, "example.Company")
```

## Pointer Types

| Go Type | Behavior                                 |
| ------- | ---------------------------------------- |
| `*T`    | Nil-able, reference tracked (if enabled) |
| `**T`   | Nested pointers supported                |

```go
f := fory.New(fory.WithTrackRef(true))

type Node struct {
    Value int32
    Left  *Node
    Right *Node
}

f.RegisterByName(Node{}, "example.Node")

root := &Node{
    Value: 1,
    Left:  &Node{Value: 2},
    Right: &Node{Value: 3},
}

data, _ := f.Serialize(root)
```

### Nil Handling

```go
var ptr *User = nil
data, _ := f.Serialize(ptr)

var result *User
f.Deserialize(data, &result)
// result == nil
```

## Interface Types

| Go Type       | Fory TypeId | Notes              |
| ------------- | ----------- | ------------------ |
| `interface{}` | UNION (31)  | Polymorphic values |

```go
f := fory.New()

// Serialize interface{}
var value interface{} = "hello"
data, _ := f.Serialize(value)

var result interface{}
f.Deserialize(data, &result)
// result = "hello" (string)
```

For struct interfaces, register all possible concrete types:

```go
type Shape interface {
    Area() float64
}

type Circle struct {
    Radius float64
}

func (c Circle) Area() float64 {
    return 3.14159 * c.Radius * c.Radius
}

f := fory.New()
f.RegisterByName(Circle{}, "example.Circle")

var shape Shape = Circle{Radius: 5.0}
data, _ := f.Serialize(shape)
```

## Binary Data

| Go Type  | Fory TypeId | Notes                 |
| -------- | ----------- | --------------------- |
| `[]byte` | BINARY (37) | Variable-length bytes |

```go
f := fory.New()

data := []byte{0x01, 0x02, 0x03, 0x04}
serialized, _ := f.Serialize(data)

var result []byte
f.Deserialize(serialized, &result)
```

## Enum Types

Go uses integer types for enums:

```go
type Status int32

const (
    StatusPending  Status = 0
    StatusActive   Status = 1
    StatusComplete Status = 2
)

f := fory.New()
f.RegisterEnumByName(Status(0), "example.Status")

status := StatusActive
data, _ := f.Serialize(status)
```

## Cross-Language Type Mapping

| Go Type         | Java     | Python    | C++                | Rust         |
| --------------- | -------- | --------- | ------------------ | ------------ |
| `bool`          | boolean  | bool      | bool               | bool         |
| `int8`          | byte     | int       | int8_t             | i8           |
| `int16`         | short    | int       | int16_t            | i16          |
| `int32`         | int      | int       | int32_t            | i32          |
| `int64`         | long     | int       | int64_t            | i64          |
| `float32`       | float    | float     | float              | f32          |
| `float64`       | double   | float     | double             | f64          |
| `string`        | String   | str       | std::string        | String       |
| `[]T`           | List<T>  | list      | std::vector<T>     | Vec<T>       |
| `map[K]V`       | Map<K,V> | dict      | std::unordered_map | HashMap<K,V> |
| `time.Time`     | Instant  | datetime  | -                  | -            |
| `time.Duration` | Duration | timedelta | -                  | -            |

See [Cross-Language Serialization](cross-language) for detailed mapping.

## Unsupported Types

The following Go types are **not supported**:

- Channels (`chan T`)
- Functions (`func()`)
- Complex numbers (`complex64`, `complex128`)
- Unsafe pointers (`unsafe.Pointer`)

Attempting to serialize these types will result in an error.

## Related Topics

- [Type Registration](type-registration)
- [Cross-Language Serialization](cross-language)
- [References](references)
