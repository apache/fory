# Apache Fory™ Go

Fory is a blazingly fast multi-language serialization framework powered by just-in-time compilation and zero-copy.

## Installation

```bash
go get github.com/apache/fory/go/fory
```

## Quick Start

```go
package main

import (
    "fmt"
    "github.com/apache/fory/go/fory"
)

type User struct {
    ID   int64
    Name string
    Age  int32
}

func main() {
    // Create a Fory instance
    f := fory.New()

    // Register struct for cross-language serialization
    if err := f.RegisterByName(User{}, "example.User"); err != nil {
        panic(err)
    }

    // Serialize
    user := &User{ID: 1, Name: "Alice", Age: 30}
    data, err := f.Serialize(user)
    if err != nil {
        panic(err)
    }
    fmt.Printf("Serialized %d bytes\n", len(data))

    // Deserialize
    var result User
    if err := f.Deserialize(data, &result); err != nil {
        panic(err)
    }
    fmt.Printf("Deserialized: %+v\n", result)
}
```

## Supported Types

### Basic Data Types

- `bool`
- `int8`, `int16`, `int32`, `int64`, `int`
- `uint8` (byte), `uint16`, `uint32`, `uint64`
- `float32`, `float64`
- `string`

### Collection Types

- `[]bool`, `[]int16`, `[]int32`, `[]int64`
- `[]float32`, `[]float64`
- `[]string`
- `[]interface{}` (dynamic slice)
- `map[string]string`, `map[int]int`, `map[string]int`, and more

### Time Types

- `time.Time`
- `time.Duration`

## Configuration Options

Fory Go supports configuration through functional options:

```go
// Enable reference tracking for circular references
f := fory.New(fory.WithTrackRef(true))

// Enable compatible mode for schema evolution
f := fory.New(fory.WithCompatible(true))

// Set maximum nesting depth
f := fory.New(fory.WithMaxDepth(200))

// Combine multiple options
f := fory.New(
    fory.WithTrackRef(true),
    fory.WithCompatible(true),
)
```

## Cross-Language Serialization

Fory Go enables seamless data exchange with Java, Python, C++, Rust, and JavaScript:

```go
// Go
f := fory.New()
f.RegisterByName(User{}, "example.User")
data, _ := f.Serialize(&User{ID: 1, Name: "Alice"})
// 'data' can be deserialized by Java, Python, etc.
```

## Thread Safety

The default Fory instance is not thread-safe. For concurrent use:

```go
import "github.com/apache/fory/go/fory/threadsafe"

f := threadsafe.New()

// Safe for concurrent use
go func() { f.Serialize(value1) }()
go func() { f.Serialize(value2) }()
```

## Documentation

For comprehensive documentation, see the [Fory Go Guide](https://fory.apache.org/docs/guide/go/).

Topics covered:

- [Configuration](https://fory.apache.org/docs/guide/go/configuration) - Options and settings
- [Basic Serialization](https://fory.apache.org/docs/guide/go/basic-serialization) - Core APIs and usage patterns
- [Type Registration](https://fory.apache.org/docs/guide/go/type-registration) - Registering types for serialization
- [Supported Types](https://fory.apache.org/docs/guide/go/supported-types) - Complete type support reference
- [References](https://fory.apache.org/docs/guide/go/references) - Circular references and shared objects
- [Schema Evolution](https://fory.apache.org/docs/guide/go/schema-evolution) - Forward/backward compatibility
- [Cross-Language](https://fory.apache.org/docs/guide/go/cross-language) - Multi-language serialization
- [Code Generation](https://fory.apache.org/docs/guide/go/codegen) - Experimental AOT code generation
- [Thread Safety](https://fory.apache.org/docs/guide/go/thread-safety) - Concurrent usage patterns
- [Troubleshooting](https://fory.apache.org/docs/guide/go/troubleshooting) - Common issues and solutions

## How to Test

```bash
cd go/fory
go test -v ./...
go test -v fory_xlang_test.go
```

## Code Style

```bash
cd go/fory
gofmt -s -w .
```

When using Go's `gofmt -s -w .` command on Windows, ensure your source files use Unix-style line endings (LF) instead of Windows-style (CRLF). Go tools expect LF by default, and mismatched line endings may cause unexpected behavior or unnecessary changes in version control.

Before committing, you can use `git config core.autocrlf input` to take effect on future commits.
