---
title: Configuration
sidebar_position: 10
id: go_configuration
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

// Create with defaults
f := fory.New()
```

Default settings:

- Cross-language mode (xlang): enabled
- Reference tracking: disabled
- Compatible mode: disabled
- Max depth: 100

### With Options

```go
f := fory.New(
    fory.WithTrackRef(true),
    fory.WithCompatible(true),
    fory.WithMaxDepth(200),
)
```

## Configuration Options

### WithTrackRef

Enable reference tracking to handle circular references and shared objects:

```go
f := fory.New(fory.WithTrackRef(true))
```

When enabled:

- Objects appearing multiple times are serialized once
- Circular references are handled automatically
- Adds overhead for tracking object identity

When disabled (default):

- Each object occurrence is serialized independently
- Circular references will cause infinite recursion
- Better performance for simple data structures

**Use reference tracking when**:

- Data contains circular references
- Same object is referenced multiple times
- Serializing graph structures (trees with parent pointers, linked lists with cycles)

### WithCompatible

Enable compatible mode for schema evolution:

```go
f := fory.New(fory.WithCompatible(true))
```

When enabled:

- Type metadata is written to the serialized data
- Supports adding/removing fields between versions
- Enables forward and backward compatibility

When disabled (default):

- Compact serialization without metadata
- Faster serialization and smaller output
- Requires exact field match between serializer and deserializer

See [Schema Evolution](schema-evolution) for details.

### WithMaxDepth

Set the maximum nesting depth to prevent stack overflow:

```go
f := fory.New(fory.WithMaxDepth(200))
```

- Default: 100
- Protects against deeply nested or recursive structures
- Returns `ErrKindMaxDepthExceeded` when exceeded

### WithXlang

Enable or disable cross-language serialization mode:

```go
f := fory.New(fory.WithXlang(true))  // Default
```

When enabled (default):

- Uses cross-language type system
- Compatible with Java, Python, C++, Rust, JavaScript
- Type IDs follow xlang specification

When disabled:

- Go-native serialization mode
- May be more efficient for Go-only use cases

## Configuration Examples

### Simple Data Serialization

For simple structs without circular references:

```go
f := fory.New()  // Use defaults

type Config struct {
    Host string
    Port int32
}

data, _ := f.Serialize(&Config{Host: "localhost", Port: 8080})
```

### Graph Structures

For data with circular references or shared objects:

```go
f := fory.New(fory.WithTrackRef(true))

type Node struct {
    Value int32
    Next  *Node
}

// Create circular linked list
n1 := &Node{Value: 1}
n2 := &Node{Value: 2}
n1.Next = n2
n2.Next = n1  // Circular reference

data, _ := f.Serialize(n1)
```

### Versioned Data

For data that may evolve over time:

```go
f := fory.New(fory.WithCompatible(true))

// v1 struct
type UserV1 struct {
    ID   int64
    Name string
}

// v2 struct (added Email field)
type UserV2 struct {
    ID    int64
    Name  string
    Email string
}

// Data serialized with v1 can be deserialized with v2
```

### Combined Options

```go
f := fory.New(
    fory.WithTrackRef(true),
    fory.WithCompatible(true),
    fory.WithMaxDepth(500),
)
```

## Thread-Safe Configuration

The default `Fory` instance is **not thread-safe**. For concurrent use, use the thread-safe wrapper:

```go
import "github.com/apache/fory/go/fory/threadsafe"

// Thread-safe Fory with pooled instances
f := threadsafe.New()

// Safe for concurrent use
go func() {
    data, _ := f.Serialize(value1)
}()
go func() {
    data, _ := f.Serialize(value2)
}()
```

See [Thread Safety](thread-safety) for details.

## Buffer Management

### Reusing Buffers

For high-throughput scenarios, you can reuse buffers:

```go
f := fory.New()
buf := fory.NewByteBuffer(nil)

// Serialize to existing buffer
err := f.SerializeTo(buf, value1)

// Get serialized data
data := buf.GetByteSlice(0, buf.WriterIndex())

// Reset for next use
buf.Reset()
```

### Zero-Copy Considerations

Serialized byte slices are views into the internal buffer:

```go
data, _ := f.Serialize(value)

// WARNING: 'data' becomes invalid after next Serialize call
// To keep the data, clone it:
dataCopy := make([]byte, len(data))
copy(dataCopy, data)
```

## Best Practices

1. **Reuse Fory instances**: Creating a Fory instance is expensive. Create once and reuse.

2. **Choose reference tracking wisely**: Only enable when needed, as it adds overhead.

3. **Set appropriate max depth**: Increase for deeply nested structures, but be aware of memory usage.

4. **Use thread-safe wrapper for concurrent access**: Never share a non-thread-safe Fory instance across goroutines.

5. **Clone serialized data if needed**: The returned byte slice is invalidated on the next operation.

## Related Topics

- [Basic Serialization](basic-serialization)
- [Schema Evolution](schema-evolution)
- [Thread Safety](thread-safety)
- [References](references)
