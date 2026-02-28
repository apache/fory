# Apache Fory™ Go

Fory is a blazingly fast multi-language serialization framework powered by just-in-time compilation and zero-copy.

For comprehensive documentation, see the [Fory Go Guide](https://fory.apache.org/docs/guide/go/).

## Installation

**Requirements**: Go 1.24 or later

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

    // Register struct with a type ID
    if err := f.RegisterStruct(User{}, 1); err != nil {
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

## Benchmark

Fory Go demonstrates excellent performance compared to other go serialization frameworks.

<p align="center">
<img src="../docs/benchmarks/go/benchmark_combined.png" width="95%">
</p>

For more detailed benchmarks and methodology, see [Go Benchmark](../benchmarks/go).

## Code Style

```bash
cd go/fory
gofmt -s -w .
```

When using Go's `gofmt -s -w .` command on Windows, ensure your source files use Unix-style line endings (LF) instead of Windows-style (CRLF). Go tools expect LF by default, and mismatched line endings may cause unexpected behavior or unnecessary changes in version control.

Before committing, you can use `git config core.autocrlf input` to take effect on future commits.
