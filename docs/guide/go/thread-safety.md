---
title: Thread Safety
sidebar_position: 100
id: go_thread_safety
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

This guide covers concurrent usage patterns for Fory Go, including the thread-safe wrapper and best practices for multi-goroutine environments.

## Default Fory Instance

The default `Fory` instance is **not thread-safe**:

```go
f := fory.New()

// NOT SAFE: Concurrent access from multiple goroutines
go func() {
    f.Serialize(value1)  // Race condition!
}()
go func() {
    f.Serialize(value2)  // Race condition!
}()
```

### Why Not Thread-Safe?

For performance, Fory reuses internal state:

- Buffer is cleared and reused between calls
- Reference resolvers are reset
- Context objects are recycled

This avoids allocations but requires exclusive access.

## Thread-Safe Wrapper

For concurrent use, use the `threadsafe` package:

```go
import "github.com/apache/fory/go/fory/threadsafe"

// Create thread-safe Fory
f := threadsafe.New()

// Safe for concurrent use
go func() {
    data, _ := f.Serialize(value1)
}()
go func() {
    data, _ := f.Serialize(value2)
}()
```

### How It Works

The thread-safe wrapper uses `sync.Pool`:

1. **Acquire**: Gets a Fory instance from the pool
2. **Use**: Performs serialization/deserialization
3. **Copy**: Copies result data (buffer will be reused)
4. **Release**: Returns instance to pool

```go
// Simplified implementation
func (f *Fory) Serialize(v interface{}) ([]byte, error) {
    fory := f.pool.Get().(*fory.Fory)
    defer f.pool.Put(fory)

    data, err := fory.Serialize(v)
    if err != nil {
        return nil, err
    }

    // Copy because underlying buffer will be reused
    result := make([]byte, len(data))
    copy(result, data)
    return result, nil
}
```

### API

```go
// Create thread-safe instance
f := threadsafe.New()

// Instance methods
data, err := f.Serialize(value)
err = f.Deserialize(data, &target)

// Generic functions
data, err := threadsafe.Serialize(f, &value)
err = threadsafe.Deserialize(f, data, &target)

// Global convenience functions
data, err := threadsafe.Marshal(&value)
err = threadsafe.Unmarshal(data, &target)
```

## Type Registration

Type registration should be done before concurrent use:

```go
f := threadsafe.New()

// Register types BEFORE concurrent access
f.RegisterByName(User{}, "example.User")
f.RegisterByName(Order{}, "example.Order")

// Now safe to use concurrently
go func() {
    f.Serialize(&User{ID: 1})
}()
```

### Thread-Safe Registration

The thread-safe wrapper handles registration safely:

```go
// Safe: Registration is synchronized
f := threadsafe.New()
f.RegisterByName(User{}, "example.User")  // Thread-safe
```

However, for best performance, register all types at startup before concurrent use.

## Zero-Copy Considerations

### Non-Thread-Safe Instance

With the default Fory, returned byte slices are views into the internal buffer:

```go
f := fory.New()

data1, _ := f.Serialize(value1)
// data1 is valid

data2, _ := f.Serialize(value2)
// data1 is NOW INVALID (buffer was reused)
```

### Thread-Safe Instance

The thread-safe wrapper copies data automatically:

```go
f := threadsafe.New()

data1, _ := f.Serialize(value1)
data2, _ := f.Serialize(value2)
// Both data1 and data2 are valid (independent copies)
```

This is safer but has allocation overhead.

## Performance Comparison

| Scenario            | Non-Thread-Safe | Thread-Safe            |
| ------------------- | --------------- | ---------------------- |
| Single goroutine    | Fastest         | Slower (pool overhead) |
| Multiple goroutines | Unsafe          | Safe, good scaling     |
| Memory allocations  | Minimal         | Per-call copy          |
| Buffer reuse        | Yes             | Per-pool-instance      |

### Benchmarking

```go
func BenchmarkNonThreadSafe(b *testing.B) {
    f := fory.New()
    f.RegisterByName(User{}, "example.User")
    user := &User{ID: 1, Name: "Alice"}

    for i := 0; i < b.N; i++ {
        data, _ := f.Serialize(user)
        _ = data
    }
}

func BenchmarkThreadSafe(b *testing.B) {
    f := threadsafe.New()
    f.RegisterByName(User{}, "example.User")
    user := &User{ID: 1, Name: "Alice"}

    for i := 0; i < b.N; i++ {
        data, _ := f.Serialize(user)
        _ = data
    }
}
```

## Patterns

### Per-Goroutine Instance

For maximum performance with known goroutine count:

```go
func worker(id int) {
    // Each worker has its own Fory instance
    f := fory.New()
    f.RegisterByName(User{}, "example.User")

    for task := range tasks {
        data, _ := f.Serialize(task)
        process(data)
    }
}

// Start workers
for i := 0; i < numWorkers; i++ {
    go worker(i)
}
```

### Shared Thread-Safe Instance

For dynamic goroutine count or simplicity:

```go
// Single shared instance
var f = threadsafe.New()

func init() {
    f.RegisterByName(User{}, "example.User")
}

func handleRequest(user *User) []byte {
    // Safe from any goroutine
    data, _ := f.Serialize(user)
    return data
}
```

### HTTP Handler Example

```go
var fory = threadsafe.New()

func init() {
    fory.RegisterByName(Response{}, "api.Response")
}

func handler(w http.ResponseWriter, r *http.Request) {
    response := &Response{
        Status: "ok",
        Data:   getData(),
    }

    // Safe: threadsafe.Fory handles concurrency
    data, err := fory.Serialize(response)
    if err != nil {
        http.Error(w, err.Error(), 500)
        return
    }

    w.Header().Set("Content-Type", "application/octet-stream")
    w.Write(data)
}
```

### Worker Pool Pattern

```go
type SerializerPool struct {
    fory *threadsafe.Fory
}

func NewSerializerPool() *SerializerPool {
    f := threadsafe.New()
    f.RegisterByName(Message{}, "example.Message")
    return &SerializerPool{fory: f}
}

func (p *SerializerPool) Serialize(msg *Message) ([]byte, error) {
    return p.fory.Serialize(msg)
}

func (p *SerializerPool) Deserialize(data []byte) (*Message, error) {
    var msg Message
    err := p.fory.Deserialize(data, &msg)
    return &msg, err
}
```

## Common Mistakes

### Sharing Non-Thread-Safe Instance

```go
// WRONG: Race condition
var f = fory.New()

func handler1() {
    f.Serialize(value1)  // Race!
}

func handler2() {
    f.Serialize(value2)  // Race!
}
```

**Fix**: Use `threadsafe.New()` or per-goroutine instances.

### Keeping Reference to Buffer

```go
// WRONG: Buffer invalidated on next call
f := fory.New()
data, _ := f.Serialize(value1)
savedData := data  // Just copies the slice header!

f.Serialize(value2)  // Invalidates data and savedData
```

**Fix**: Clone the data or use thread-safe wrapper.

```go
// Correct: Clone the data
data, _ := f.Serialize(value1)
savedData := make([]byte, len(data))
copy(savedData, data)

// Or use thread-safe (auto-copies)
f := threadsafe.New()
data, _ := f.Serialize(value1)  // Already copied
```

### Registering Types Concurrently

```go
// RISKY: Concurrent registration
go func() {
    f.RegisterByName(TypeA{}, "example.TypeA")
}()
go func() {
    f.Serialize(value)  // May not see TypeA
}()
```

**Fix**: Register all types before concurrent use.

## Best Practices

1. **Default to thread-safe**: Unless profiling shows it's a bottleneck
2. **Register types at startup**: Before any concurrent operations
3. **Clone data if keeping references**: With non-thread-safe instance
4. **Use per-worker instances for hot paths**: Eliminates pool contention
5. **Profile before optimizing**: Thread-safe overhead may be negligible

## Related Topics

- [Configuration](configuration)
- [Basic Serialization](basic-serialization)
- [Troubleshooting](troubleshooting)
