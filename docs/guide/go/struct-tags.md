---
title: Field Configuration
sidebar_position: 60
id: struct_tags
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

Fory Go uses struct tags to customize field-level serialization behavior. This allows fine-grained control over how individual fields are serialized.

## Tag Syntax

The general syntax for Fory struct tags:

```go
type MyStruct struct {
    Field Type `fory:"option1,option2=value"`
}
```

Multiple options are separated by commas (`,`).

## Available Tags

### Field ID

Use `id=N` to assign a numeric ID to a field for compact encoding:

```go
type User struct {
    ID   int64  `fory:"id=0"`
    Name string `fory:"id=1"`
    Age  int32  `fory:"id=2"`
}
```

**Benefits**:

- Smaller serialized size (numeric IDs vs field names)
- Faster serialization/deserialization
- Required for optimal cross-language compatibility

**Notes**:

- IDs must be unique within a struct
- IDs must be >= 0
- If not specified, field name is used (larger payload)

### Ignoring Fields

Use `-` to exclude a field from serialization:

```go
type User struct {
    ID       int64
    Name     string
    Password string `fory:"-"`  // Not serialized
}
```

The `Password` field will not be included in serialized output and will remain at its zero value after deserialization.

### Nullable

Use `nullable` to control whether null flags are written for pointer fields:

```go
type Record struct {
    // Write null flag for this field (allows nil values)
    OptionalData *Data `fory:"nullable"`

    // Skip null flag (field must not be nil)
    RequiredData *Data `fory:"nullable=false"`
}
```

**Notes**:

- Only applies to pointer, slice, and map fields
- When `nullable=false`, serializing a nil value will cause an error
- Default is `false` (no null flag written)

### Reference Tracking

Control per-field reference tracking for slices, maps, or pointer to struct fields:

```go
type Container struct {
    // Enable reference tracking for this field
    SharedData *Data `fory:"ref"`

    // Disable reference tracking for this field
    SimpleData *Data `fory:"ref=false"`
}
```

**Notes**:

- Applies to slices, maps, and pointer to struct fields
- Pointer to primitive types (e.g., `*int`, `*string`) cannot use this tag
- Default is `ref=false` (no reference tracking)
- When global `WithTrackRef(false)` is set, field ref tags are ignored
- When global `WithTrackRef(true)` is set, use `ref=false` to disable for specific fields

**Use cases**:

- Enable for fields that may be circular or shared
- Disable for fields that are always unique (optimization)

### Encoding

Use `encoding` to control how numeric fields are encoded:

```go
type Metrics struct {
    // Variable-length encoding (default, smaller for small values)
    Count int64 `fory:"encoding=varint"`

    // Fixed-length encoding (consistent size)
    Timestamp int64 `fory:"encoding=fixed"`

    // Tagged encoding (includes type tag)
    Value int64 `fory:"encoding=tagged"`
}
```

**Supported encodings**:

| Type     | Options                     | Default  |
| -------- | --------------------------- | -------- |
| `int32`  | `varint`, `fixed`           | `varint` |
| `uint32` | `varint`, `fixed`           | `varint` |
| `int64`  | `varint`, `fixed`, `tagged` | `varint` |
| `uint64` | `varint`, `fixed`, `tagged` | `varint` |

**When to use**:

- `varint`: Best for values that are often small (default)
- `fixed`: Best for values that use full range (e.g., timestamps, hashes)
- `tagged`: When type information needs to be preserved

### Type Overrides

Use `type=` to override inferred carrier semantics or nested value encoding:

```go
type Foo struct {
    // Force general list protocol.
    Values []int32 `fory:"type=list"`

    // Override inner integer encoding for a general list
    FixedValues []int32 `fory:"type=list(element=int32(encoding=fixed))"`

    // Override nested map/list integer encoding
    Nested map[string][]*uint64 `fory:"type=map(value=list(element=uint64(encoding=tagged)))"`

    // Declare dense numeric array schema explicitly.
    Dense []int32 `fory:"type=array(element=int32)"`

    // Use array schema inside a map value.
    Packed map[string][]int32 `fory:"type=map(value=array(element=int32))"`
}
```

**Notes**:

- `list(...)`, `array(...)`, `set(...)`, and `map(...)` are explicit container overrides
- `list(...)` always uses list schema and never collapses into dense array schema
- `array(element=...)` requires a bool or numeric element domain and rejects nullable elements and scalar encoding modifiers

## Combining Tags

Multiple tags can be combined using comma separator:

```go
type Document struct {
    ID      int64  `fory:"id=0,encoding=fixed"`
    Content string `fory:"id=1"`
    Author  *User  `fory:"id=2,nullable=false,ref"`
}
```

## Integration with Other Tags

Fory tags coexist with other struct tags:

```go
type User struct {
    ID       int64  `json:"id" fory:"id=0"`
    Name     string `json:"name,omitempty" fory:"id=1"`
    Password string `json:"-" fory:"-"`
}
```

Each tag namespace is independent.

## Field Visibility

Only **exported fields** (starting with uppercase) are considered:

```go
type User struct {
    ID       int64  // Serialized
    Name     string // Serialized
    password string // NOT serialized (unexported, no tag needed)
}
```

Unexported fields are always ignored, regardless of tags.

## Field Ordering

Fields are serialized in a consistent order based on:

1. Field name (alphabetically in snake_case)
2. Field type

This ensures cross-language compatibility where field order matters.

## Struct Hash

Fory computes a hash of struct fields for version checking:

- Hash includes field names and types
- Hash is written to serialized data
- Mismatch triggers `ErrKindHashMismatch`

Struct field changes affect the hash:

```go
// These produce different hashes
type V1 struct {
    UserID int64
}

type V2 struct {
    UserId int64  // Different field name = different hash
}
```

## Examples

### API Response Struct

```go
type APIResponse struct {
    Status    int32  `json:"status" fory:"id=0"`
    Message   string `json:"message" fory:"id=1"`
    Data      any    `json:"data" fory:"id=2"`
    Internal  string `json:"-" fory:"-"`  // Ignored in both JSON and Fory
}
```

### Caching with Shared References

```go
type CacheEntry struct {
    Key       string
    Value     *CachedData `fory:"ref"`      // May be shared
    Metadata  *Metadata   `fory:"ref=false"` // Always unique
    ExpiresAt int64
}
```

### Document with Circular References

```go
type Document struct {
    ID       int64
    Title    string
    Parent   *Document   `fory:"ref"`  // May reference self or siblings
    Children []*Document `fory:"ref"`
}
```

## Tag Parsing Errors

Invalid tags produce errors during registration:

```go
type BadStruct struct {
    Field int `fory:"invalid=option=format"`
}

f := fory.New()
err := f.RegisterStruct(BadStruct{}, 1)
// Error: ErrKindInvalidTag
```

## Native Mode vs Xlang Mode

Field configuration behaves differently depending on the serialization mode:

**Native Mode**:

- **Nullable**: Pointer, slice, map, and interface types are nullable by default
- **Ref tracking**: Disabled by default (`ref` tag not set)

**Xlang Mode**:

- **Nullable**: Only pointer types are nullable by default (slices and maps are NOT nullable)
- **Ref tracking**: Disabled by default (`ref` tag not set)

You **need to configure fields** when:

- A field can be nil (use pointer types like `*string`, `*int32`)
- A field needs reference tracking for shared/circular objects (use `fory:"ref"`)
- You want to reduce metadata size (use field IDs with `fory:"id=N"`)

```go
// Xlang mode: explicit configuration required
type User struct {
    ID    int64   `fory:"id=0"`
    Name  string  `fory:"id=1"`
    Email *string `fory:"id=2"`           // Pointer type for nullable
    Friend *User  `fory:"id=3,ref"`       // Must declare ref for shared objects
}
```

### Default Values Summary

| Option     | Default | How to Enable            |
| ---------- | ------- | ------------------------ |
| `nullable` | `false` | Use pointer types (`*T`) |
| `ref`      | `false` | Add `fory:"ref"` tag     |
| `id`       | `-1`    | Add `fory:"id=N"` tag    |

## Best Practices

1. **Use `-` for sensitive data**: Passwords, tokens, internal state
2. **Enable ref tracking for shared objects**: When the same pointer appears multiple times
3. **Disable ref tracking for simple fields**: Optimization when you know the field is unique
4. **Keep names consistent**: Cross-language names should match
5. **Document tag usage**: Especially for non-obvious configurations

## Common Patterns

### Ignoring Computed Fields

```go
type Rectangle struct {
    Width  float64
    Height float64
    Area   float64 `fory:"-"`  // Computed, don't serialize
}

func (r *Rectangle) ComputeArea() {
    r.Area = r.Width * r.Height
}
```

### Circular Structure with Parent

```go
type TreeNode struct {
    Value    string
    Parent   *TreeNode   `fory:"ref"`  // Circular back-reference
    Children []*TreeNode `fory:"ref"`
}
```

### Mixed Serialization Needs

```go
type Session struct {
    ID        string
    UserID    int64
    Token     string    `fory:"-"`           // Security: don't serialize
    User      *User     `fory:"ref"`    // May be shared across sessions
    CreatedAt int64
}
```

## Related Topics

- [References](references.md)
- [Basic Serialization](basic-serialization.md)
- [Schema Evolution](schema-evolution.md)
