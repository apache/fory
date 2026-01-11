---
title: Basic Serialization
sidebar_position: 20
id: go_basic_serialization
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

This guide covers the core serialization APIs in Fory Go.

## Core API

### Serialize and Deserialize

The primary API for serialization:

```go
import "github.com/apache/fory/go/fory"

f := fory.New()

// Serialize any value
data, err := f.Serialize(value)
if err != nil {
    // Handle error
}

// Deserialize into target
var result MyType
err = f.Deserialize(data, &result)
if err != nil {
    // Handle error
}
```

### Marshal and Unmarshal

Aliases for `Serialize` and `Deserialize` (familiar to Go developers):

```go
data, err := f.Marshal(value)
err = f.Unmarshal(data, &result)
```

## Serializing Primitives

```go
f := fory.New()

// Integers
data, _ := f.Serialize(int64(42))
var i int64
f.Deserialize(data, &i)  // i = 42

// Floats
data, _ = f.Serialize(float64(3.14))
var fl float64
f.Deserialize(data, &fl)  // fl = 3.14

// Strings
data, _ = f.Serialize("hello")
var s string
f.Deserialize(data, &s)  // s = "hello"

// Booleans
data, _ = f.Serialize(true)
var b bool
f.Deserialize(data, &b)  // b = true
```

## Serializing Collections

### Slices

```go
f := fory.New()

// String slice
strs := []string{"a", "b", "c"}
data, _ := f.Serialize(strs)

var result []string
f.Deserialize(data, &result)
// result = ["a", "b", "c"]

// Integer slice
nums := []int64{1, 2, 3}
data, _ = f.Serialize(nums)

var intResult []int64
f.Deserialize(data, &intResult)
// intResult = [1, 2, 3]
```

### Maps

```go
f := fory.New()

// String to string map
m := map[string]string{"key": "value"}
data, _ := f.Serialize(m)

var result map[string]string
f.Deserialize(data, &result)
// result = {"key": "value"}

// String to int map
m2 := map[string]int64{"count": 42}
data, _ = f.Serialize(m2)

var result2 map[string]int64
f.Deserialize(data, &result2)
// result2 = {"count": 42}
```

## Serializing Structs

### Basic Struct Serialization

Only **exported fields** (starting with uppercase) are serialized:

```go
type User struct {
    ID       int64   // Serialized
    Name     string  // Serialized
    password string  // NOT serialized (unexported)
}

f := fory.New()
f.RegisterByName(User{}, "example.User")

user := &User{ID: 1, Name: "Alice", password: "secret"}
data, _ := f.Serialize(user)

var result User
f.Deserialize(data, &result)
// result.ID = 1, result.Name = "Alice", result.password = ""
```

### Nested Structs

```go
type Address struct {
    City    string
    Country string
}

type Person struct {
    Name    string
    Address Address
}

f := fory.New()
f.RegisterByName(Address{}, "example.Address")
f.RegisterByName(Person{}, "example.Person")

person := &Person{
    Name: "Alice",
    Address: Address{City: "NYC", Country: "USA"},
}

data, _ := f.Serialize(person)

var result Person
f.Deserialize(data, &result)
// result.Address.City = "NYC"
```

### Pointer Fields

```go
type Node struct {
    Value int32
    Child *Node
}

f := fory.New(fory.WithTrackRef(true))
f.RegisterByName(Node{}, "example.Node")

root := &Node{
    Value: 1,
    Child: &Node{Value: 2, Child: nil},
}

data, _ := f.Serialize(root)

var result Node
f.Deserialize(data, &result)
// result.Child.Value = 2
```

## Streaming API

For scenarios where you want to control the buffer:

### SerializeTo

Serialize to an existing buffer:

```go
f := fory.New()
buf := fory.NewByteBuffer(nil)

// Serialize multiple values to same buffer
f.SerializeTo(buf, value1)
f.SerializeTo(buf, value2)

// Get all serialized data
data := buf.GetByteSlice(0, buf.WriterIndex())
```

### DeserializeFrom

Deserialize from an existing buffer:

```go
buf := fory.NewByteBuffer(data)

var result1, result2 MyType
f.DeserializeFrom(buf, &result1)
f.DeserializeFrom(buf, &result2)
```

## Generic API (Type-Safe)

Fory Go provides generic functions for type-safe serialization:

```go
import "github.com/apache/fory/go/fory"

type User struct {
    ID   int64
    Name string
}

f := fory.New()

// Type-safe serialization
user := &User{ID: 1, Name: "Alice"}
data, err := fory.Serialize(f, user)

// Type-safe deserialization
var result User
err = fory.Deserialize(f, data, &result)
```

The generic API:

- Infers type at compile time
- Provides better type safety
- May offer performance benefits

## Error Handling

Always check errors from serialization operations:

```go
f := fory.New()

data, err := f.Serialize(value)
if err != nil {
    switch e := err.(type) {
    case fory.Error:
        fmt.Printf("Fory error: %s (kind: %d)\n", e.Error(), e.Kind())
    default:
        fmt.Printf("Unknown error: %v\n", err)
    }
    return
}

err = f.Deserialize(data, &result)
if err != nil {
    // Handle deserialization error
}
```

Common error kinds:

- `ErrKindBufferOutOfBound`: Read/write beyond buffer bounds
- `ErrKindTypeMismatch`: Type ID mismatch during deserialization
- `ErrKindUnknownType`: Unknown type encountered
- `ErrKindMaxDepthExceeded`: Recursion depth limit exceeded
- `ErrKindHashMismatch`: Struct hash mismatch (schema changed)

See [Troubleshooting](troubleshooting) for error resolution.

## Nil Handling

### Nil Pointers

```go
f := fory.New()

var ptr *User = nil
data, _ := f.Serialize(ptr)

var result *User
f.Deserialize(data, &result)
// result = nil
```

### Empty Collections

```go
f := fory.New()

// Nil slice
var slice []string = nil
data, _ := f.Serialize(slice)

var result []string
f.Deserialize(data, &result)
// result = nil

// Empty slice (different from nil)
empty := []string{}
data, _ = f.Serialize(empty)

f.Deserialize(data, &result)
// result = [] (empty, not nil)
```

## Complete Example

```go
package main

import (
    "fmt"
    "github.com/apache/fory/go/fory"
)

type Order struct {
    ID       int64
    Customer string
    Items    []Item
    Total    float64
}

type Item struct {
    Name     string
    Quantity int32
    Price    float64
}

func main() {
    f := fory.New()
    f.RegisterByName(Order{}, "example.Order")
    f.RegisterByName(Item{}, "example.Item")

    order := &Order{
        ID:       12345,
        Customer: "Alice",
        Items: []Item{
            {Name: "Widget", Quantity: 2, Price: 9.99},
            {Name: "Gadget", Quantity: 1, Price: 24.99},
        },
        Total: 44.97,
    }

    // Serialize
    data, err := f.Serialize(order)
    if err != nil {
        panic(err)
    }
    fmt.Printf("Serialized %d bytes\n", len(data))

    // Deserialize
    var result Order
    if err := f.Deserialize(data, &result); err != nil {
        panic(err)
    }

    fmt.Printf("Order ID: %d\n", result.ID)
    fmt.Printf("Customer: %s\n", result.Customer)
    fmt.Printf("Items: %d\n", len(result.Items))
    fmt.Printf("Total: %.2f\n", result.Total)
}
```

## Related Topics

- [Configuration](configuration)
- [Type Registration](type-registration)
- [Supported Types](supported-types)
- [References](references)
