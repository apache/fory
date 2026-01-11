---
title: Struct Tags
sidebar_position: 60
id: go_struct_tags
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
    Field Type `fory:"option1;option2=value"`
}
```

Multiple options are separated by semicolons (`;`).

## Available Tags

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

### Reference Tracking

Control per-field reference tracking:

```go
type Container struct {
    // Enable reference tracking for this field
    SharedData *Data `fory:"ref"`

    // Disable reference tracking for this field
    SimpleData *Data `fory:"ref=false"`
}
```

This overrides the global `WithTrackRef` setting for specific fields.

**Use cases**:

- Enable for fields that may be circular or shared
- Disable for fields that are always unique (optimization)

### Field Name

Customize the serialized field name:

```go
type User struct {
    ID   int64  `fory:"user_id"`
    Name string `fory:"user_name"`
}
```

This affects cross-language serialization where field names must match.

## Combining Tags

Multiple tags can be combined:

```go
type Document struct {
    ID      int64  `fory:"doc_id;ref=false"`
    Content string
    Author  *User  `fory:"ref"`
}
```

## Integration with Other Tags

Fory tags coexist with other struct tags:

```go
type User struct {
    ID       int64  `json:"id" fory:"user_id"`
    Name     string `json:"name,omitempty"`
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

```go
type Example struct {
    Zebra  string  // Serialized last (alphabetically)
    Alpha  int32   // Serialized first
    Middle float64 // Serialized in middle
}
```

## Struct Hash

Fory computes a hash of struct fields for version checking:

- Hash includes field names and types
- Hash is written to serialized data
- Mismatch triggers `ErrKindHashMismatch`

Tags that change field names affect the hash:

```go
// These produce different hashes
type V1 struct {
    UserID int64 `fory:"id"`
}

type V2 struct {
    UserID int64 `fory:"user_id"`  // Different name = different hash
}
```

## Examples

### API Response Struct

```go
type APIResponse struct {
    Status    int32  `json:"status" fory:"status"`
    Message   string `json:"message" fory:"msg"`
    Data      any    `json:"data" fory:"ref"`
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
err := f.RegisterByName(BadStruct{}, "example.Bad")
// Error: ErrKindInvalidTag
```

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

- [References](references)
- [Basic Serialization](basic-serialization)
- [Schema Evolution](schema-evolution)
