---
title: Code Generation
sidebar_position: 90
id: codegen
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

:::warning Experimental Feature
Code generation is an **experimental** feature in Fory Go. The API and behavior may change in future releases. The reflection-based path remains the stable, recommended approach for most use cases.
:::

Fory Go provides optional ahead-of-time (AOT) code generation for performance-critical paths. This eliminates reflection overhead and provides compile-time type safety.

## Why Code Generation?

| Aspect      | Reflection-Based   | Code Generation        |
| ----------- | ------------------ | ---------------------- |
| Setup       | Zero configuration | Requires `go generate` |
| Performance | Good               | Better (no reflection) |
| Type Safety | Runtime            | Compile-time           |
| Maintenance | Automatic          | Requires regeneration  |

**Use code generation when**:

- Maximum performance is required
- Compile-time type safety is important
- Hot paths are performance-critical

**Use reflection when**:

- Simple setup is preferred
- Types change frequently
- Dynamic typing is needed
- Code generation complexity is undesirable

## Installation

Install the `fory` generator binary:

```bash
go install github.com/apache/fory/go/fory/cmd/fory@latest

GO111MODULE=on go get -u github.com/apache/fory/go/fory/cmd/fory
```

Ensure `$GOBIN` or `$GOPATH/bin` is in your `PATH`.

## Basic Usage

### Step 1: Annotate Structs

Add the `//fory:generate` comment above structs:

```go
package models

//fory:generate
type User struct {
    ID   int64  `json:"id"`
    Name string `json:"name"`
}

//fory:generate
type Order struct {
    ID       int64
    Customer string
    Total    float64
}
```

### Step 2: Add Go Generate Directive

Add a `go:generate` directive (once per file or package):

```go
//go:generate fory -file models.go
```

Or for the entire package:

```go
//go:generate fory -pkg .
```

### Step 3: Run Code Generation

```bash
go generate ./...
```

This creates `models_fory_gen.go` with generated serializers.

## Generated Code Structure

The generator creates:

### Type Snapshot

A compile-time check to detect struct changes:

```go
// Snapshot of User's underlying type at generation time
type _User_expected struct {
    ID   int64
    Name string
}

// Compile-time check: fails if User no longer matches
var _ = func(x User) { _ = _User_expected(x) }
```

### Serializer Implementation

Strongly-typed serialization methods:

```go
type User_ForyGenSerializer struct{}

func NewSerializerFor_User() fory.Serializer {
    return &User_ForyGenSerializer{}
}

func (User_ForyGenSerializer) WriteTyped(ctx *fory.WriteContext, v *User) error {
    buf := ctx.Buffer()
    buf.WriteInt64(v.ID)
    ctx.WriteString(v.Name)
    return nil
}

func (User_ForyGenSerializer) ReadTyped(ctx *fory.ReadContext, v *User) error {
    err := ctx.Err()
    buf := ctx.Buffer()
    v.ID = buf.ReadInt64(err)
    v.Name = ctx.ReadString()
    if ctx.HasError() {
        return ctx.TakeError()
    }
    return nil
}
```

### Auto-Registration

Serializers are registered in `init()`:

```go
func init() {
    fory.RegisterSerializerFactory((*User)(nil), NewSerializerFor_User)
}
```

## Command-Line Options

### File-Based Generation

Generate for a specific file:

```bash
fory -file models.go
```

### Package-Based Generation

Generate for a package:

```bash
fory -pkg ./models
```

### Explicit Types (Legacy)

Specify types explicitly:

```bash
fory -pkg ./models -type "User,Order"
```

### Force Regeneration

Force regeneration even if up-to-date:

```bash
fory --force -file models.go
```

## When to Regenerate

Regenerate when any of these change:

- Field additions, removals, or renames
- Field type changes
- Struct tag changes
- New structs with `//fory:generate`

### Automatic Detection

Fory includes a compile-time guard:

```go
// If struct changed, this fails to compile
var _ = func(x User) { _ = _User_expected(x) }
```

If you forget to regenerate, the build fails with a clear message.

### Auto-Retry

When invoked via `go generate`, the generator detects stale code and retries:

1. Detects compile error from guard
2. Removes stale generated file
3. Regenerates fresh code

## Supported Types

Code generation supports:

- All primitive types (`bool`, `int*`, `uint*`, `float*`, `string`)
- Slices of primitives and structs
- Maps with supported key/value types
- Nested structs (must also be generated)
- Pointers to structs

### Nested Structs

All nested structs must also have `//fory:generate`:

```go
//fory:generate
type Address struct {
    City    string
    Country string
}

//fory:generate
type Person struct {
    Name    string
    Address Address  // Address must also be generated
}
```

## CI/CD Integration

### Check In Generated Code

**Recommended for libraries**:

```bash
go generate ./...
git add *_fory_gen.go
git commit -m "Regenerate Fory serializers"
```

**Pros**: Consumers can build without generator; reproducible builds
**Cons**: Larger diffs; must remember to regenerate

### Generate in Pipeline

**Recommended for applications**:

```yaml
steps:
  - run: go install github.com/apache/fory/go/fory/cmd/fory@latest
  - run: go generate ./...
  - run: go build ./...
```

## Usage with Generated Code

Generated code integrates transparently:

```go
f := fory.New()

// Fory automatically uses generated serializer if available
user := &User{ID: 1, Name: "Alice"}
data, _ := f.Serialize(user)

var result User
f.Deserialize(data, &result)
```

No code changes needed - registration happens in `init()`.

## Mixing Generated and Non-Generated

You can mix approaches:

```go
//fory:generate
type HotPathStruct struct {
    // Performance-critical, use codegen
}

type ColdPathStruct struct {
    // Not annotated, uses reflection
}
```

## Limitations

### Experimental Status

- API may change
- Not all edge cases tested
- May have undiscovered bugs

### Not Supported

- Interface fields (dynamic types)
- Recursive types without pointers
- Private (unexported) fields
- Custom serializers

### Reflection Fallback

If codegen fails, Fory falls back to reflection:

```go
// If User_ForyGenSerializer not found, uses reflection
f.Serialize(&User{})
```

## Troubleshooting

### "fory: command not found"

Ensure the binary is in PATH:

```bash
export PATH=$PATH:$(go env GOPATH)/bin
```

### Compile Error After Struct Change

Regenerate:

```bash
go generate ./...
```

Or force:

```bash
fory --force -file yourfile.go
```

### Generated Code Out of Sync

The compile-time guard catches this:

```
cannot use x (variable of type User) as type _User_expected in argument
```

Run `go generate` to fix.

## Example Project Structure

```
myproject/
├── models/
│   ├── models.go           # Struct definitions
│   ├── models_fory_gen.go  # Generated code
│   └── generate.go         # go:generate directive
├── main.go
└── go.mod
```

**models/generate.go**:

```go
package models

//go:generate fory -pkg .
```

**models/models.go**:

```go
package models

//fory:generate
type User struct {
    ID   int64
    Name string
}
```

## FAQ

### Is codegen required?

No. Reflection-based serialization works without code generation.

### Does generated code work across Go versions?

Yes. Generated code is plain Go with no version-specific features.

### Can I mix generated and non-generated types?

Yes. Fory automatically uses generated serializers when available.

### How do I update generated code?

Run `go generate ./...` after struct changes.

### Should I commit generated files?

For libraries: yes. For applications: either works.

## Related Topics

- [Basic Serialization](basic-serialization.md)
- [Configuration](configuration.md)
- [Troubleshooting](troubleshooting.md)
