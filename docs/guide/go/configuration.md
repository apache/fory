---
title: Configuration
sidebar_position: 10
id: configuration
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

Fory Go uses a functional options pattern for configuration. This allows you to customize serialization behavior while maintaining sensible defaults.

## Creating a Fory Instance

### Default Configuration

```go
import "github.com/apache/fory/go/fory"

f := fory.New()
```

Default settings:

| Option     | Default | Description                    |
| ---------- | ------- | ------------------------------ |
| TrackRef   | false   | Reference tracking disabled    |
| MaxDepth   | 20      | Maximum nesting depth          |
| IsXlang    | false   | Cross-language mode disabled   |
| Compatible | false   | Schema evolution mode disabled |

### With Options

```go
f := fory.New(
    fory.WithTrackRef(true),
    fory.WithCompatible(true),
    fory.WithMaxDepth(10),
)
```

## Configuration

### WithTrackRef

Enable reference tracking to handle circular references and shared objects:

```go
f := fory.New(fory.WithTrackRef(true))
```

**When enabled:**

- Objects appearing multiple times are serialized once
- Circular references are handled correctly
- Per-field `fory:"ref"` tags take effect
- Adds overhead for tracking object identity

**When disabled (default):**

- Each object occurrence is serialized independently
- Circular references cause stack overflow or max depth error
- Per-field `fory:"ref"` tags are ignored
- Better performance for simple data structures

**Use reference tracking when:**

- Data contains circular references
- Same object is referenced multiple times
- Serializing graph structures (trees with parent pointers, linked lists with cycles)

See [References](references.md) for details.

### WithCompatible

Enable compatible mode for schema evolution:

```go
f := fory.New(fory.WithCompatible(true))
```

**When enabled:**

- Type metadata is written to serialized data
- Supports adding/removing fields between versions
- Field names or ids are used for matching (order-independent)
- Larger serialized output due to metadata

**When disabled (default):**

- Compact serialization without field metadata
- Faster serialization and smaller output
- Fields matched by sorted order
- Requires consistent struct definitions across all services

See [Schema Evolution](schema-evolution.md) for details.

### WithMaxDepth

Set the maximum nesting depth to prevent stack overflow:

```go
f := fory.New(fory.WithMaxDepth(30))
```

- Default: 20
- Protects against deeply nested, recursive structures or malicious data
- Serialization fails with error when exceeded

### WithXlang

Enable cross-language serialization mode:

```go
f := fory.New(fory.WithXlang(true))
```

**When enabled:**

- Uses cross-language type system
- Compatible with Java, Python, C++, Rust, JavaScript
- Type IDs follow xlang specification

**When disabled (default):**

- Go-native serialization mode
- Support more Go-native types
- Not compatible with other language implementations

## Thread Safety

The default `Fory` instance is **NOT thread-safe**. For concurrent use, use the thread-safe wrapper:

```go
import "github.com/apache/fory/go/fory/threadsafe"

// Create thread-safe Fory with same options
f := threadsafe.New(
    fory.WithTrackRef(true),
    fory.WithCompatible(true),
)

// Safe for concurrent use from multiple goroutines
go func() {
    data, _ := f.Serialize(value1)
    // data is already copied, safe to use after return
}()
go func() {
    data, _ := f.Serialize(value2)
}()
```

The thread-safe wrapper:

- Uses `sync.Pool` internally for efficient instance reuse
- Automatically copies serialized data before returning
- Accepts the same configuration options as `fory.New()`

### Global Thread-Safe Instance

For convenience, the threadsafe package provides global functions:

```go
import "github.com/apache/fory/go/fory/threadsafe"

// Uses a global thread-safe instance with default configuration
data, err := threadsafe.Marshal(&myValue)
err = threadsafe.Unmarshal(data, &result)
```

See [Thread Safety](thread-safety.md) for details.

## Buffer Management

### Zero-Copy Behavior

The default `Fory` instance reuses its internal buffer:

```go
f := fory.New()

data1, _ := f.Serialize(value1)
// WARNING: data1 becomes invalid after next Serialize call!
data2, _ := f.Serialize(value2)
// data1 now points to invalid memory

// To keep the data, copy it:
safeCopy := make([]byte, len(data1))
copy(safeCopy, data1)
```

The thread-safe wrapper automatically copies data, so this is not a concern:

```go
f := threadsafe.New()
data1, _ := f.Serialize(value1)
data2, _ := f.Serialize(value2)
// Both data1 and data2 are valid
```

### Manual Buffer Control

For high-throughput scenarios, you can manage buffers manually:

```go
f := fory.New()
buf := fory.NewByteBuffer(nil)

// Serialize to existing buffer
err := f.SerializeTo(buf, value)

// Get serialized data
data := buf.GetByteSlice(0, buf.WriterIndex())

// Process data...

// Reset for next use
buf.Reset()
```

## Configuration Examples

### Simple Data (Default)

For simple structs without circular references:

```go
f := fory.New()

type Config struct {
    Host string
    Port int32
}

f.RegisterStruct(Config{}, 1)
data, _ := f.Serialize(&Config{Host: "localhost", Port: 8080})
```

### Graph Structures

For data with circular references:

```go
f := fory.New(fory.WithTrackRef(true))

type Node struct {
    Value int32
    Next  *Node `fory:"ref"`
}

f.RegisterStruct(Node{}, 1)
n1 := &Node{Value: 1}
n2 := &Node{Value: 2}
n1.Next = n2
n2.Next = n1  // Circular reference

data, _ := f.Serialize(n1)
```

### Schema Evolution

For data that may evolve over time:

```go
// V1: original struct
type UserV1 struct {
    ID   int64
    Name string
}

// V2: added Email field
type UserV2 struct {
    ID    int64
    Name  string
    Email string  // New field
}

// Serialize with V1
f1 := fory.New(fory.WithCompatible(true))
f1.RegisterStruct(UserV1{}, 1)
data, _ := f1.Serialize(&UserV1{ID: 1, Name: "Alice"})

// Deserialize into V2 - Email will have zero value
f2 := fory.New(fory.WithCompatible(true))
f2.RegisterStruct(UserV2{}, 1)
var user UserV2
f2.Deserialize(data, &user)
```

### High-Performance Concurrent

For concurrent high-throughput scenarios:

```go
type Request struct {
    ID      int64
    Payload string
}

f := threadsafe.New(
    fory.WithMaxDepth(30),
)
f.RegisterStruct(Request{}, 1)

// Process requests concurrently
for req := range requests {
    go func(r Request) {
        data, _ := f.Serialize(&r)
        sendResponse(data)
    }(req)
}
```

## Best Practices

1. **Reuse Fory instances**: Creating a Fory instance involves initialization overhead. Create once and reuse.

2. **Use thread-safe wrapper for concurrency**: Never share a non-thread-safe Fory instance across goroutines.

3. **Enable reference tracking only when needed**: It adds overhead for tracking object identity.

4. **Copy serialized data if keeping it**: With the default Fory, the returned byte slice is invalidated on the next operation.

5. **Set appropriate max depth**: Increase for deeply nested structures, but be aware of memory usage.

6. **Use compatible mode for evolving schemas**: Enable when struct definitions may change between service versions.

## Related Topics

- [Basic Serialization](basic-serialization.md)
- [References](references.md)
- [Schema Evolution](schema-evolution.md)
- [Thread Safety](thread-safety.md)
