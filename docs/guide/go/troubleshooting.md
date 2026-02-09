---
title: Troubleshooting
sidebar_position: 110
id: troubleshooting
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

This guide covers common issues and solutions when using Fory Go.

## Error Types

Fory Go uses typed errors with specific error kinds:

```go
type Error struct {
    kind    ErrorKind
    message string
    // Additional context fields
}

func (e Error) Kind() ErrorKind { return e.kind }
func (e Error) Error() string   { return e.message }
```

### Error Kinds

| Kind                           | Value | Description                     |
| ------------------------------ | ----- | ------------------------------- |
| `ErrKindOK`                    | 0     | No error                        |
| `ErrKindBufferOutOfBound`      | 1     | Read/write beyond buffer bounds |
| `ErrKindTypeMismatch`          | 2     | Type ID mismatch                |
| `ErrKindUnknownType`           | 3     | Unknown type encountered        |
| `ErrKindSerializationFailed`   | 4     | General serialization failure   |
| `ErrKindDeserializationFailed` | 5     | General deserialization failure |
| `ErrKindMaxDepthExceeded`      | 6     | Recursion depth limit exceeded  |
| `ErrKindNilPointer`            | 7     | Unexpected nil pointer          |
| `ErrKindInvalidRefId`          | 8     | Invalid reference ID            |
| `ErrKindHashMismatch`          | 9     | Struct hash mismatch            |
| `ErrKindInvalidTag`            | 10    | Invalid fory struct tag         |

## Common Errors and Solutions

### ErrKindUnknownType

**Error**: `unknown type encountered`

**Cause**: Type not registered before serialization/deserialization.

**Solution**:

```go
f := fory.New()

// Register type before use
f.RegisterStruct(User{}, 1)

// Now serialization works
data, _ := f.Serialize(&User{ID: 1})
```

### ErrKindTypeMismatch

**Error**: `type mismatch: expected X, got Y`

**Cause**: Serialized data has different type than expected.

**Solutions**:

1. **Use correct target type**:

```go
// Wrong: Deserializing User into Order
var order Order
f.Deserialize(userData, &order)  // Error!

// Correct
var user User
f.Deserialize(userData, &user)
```

2. **Ensure consistent registration**:

```go
// Serializer
f1 := fory.New()
f1.RegisterStruct(User{}, 1)

// Deserializer - must use same ID
f2 := fory.New()
f2.RegisterStruct(User{}, 1)  // Same ID!
```

### ErrKindHashMismatch

**Error**: `hash X is not consistent with Y for type Z`

**Cause**: Struct definition changed between serialization and deserialization.

**Solutions**:

1. **Enable compatible mode**:

```go
f := fory.New(fory.WithCompatible(true))
```

2. **Ensure struct definitions match**:

```go
// Both serializer and deserializer must have same struct
type User struct {
    ID   int64
    Name string
}
```

3. **Regenerate codegen** (if using):

```bash
go generate ./...
```

### ErrKindMaxDepthExceeded

**Error**: `max depth exceeded`

**Cause**: Data nesting exceeds maximum depth limit.

**Possible causes**:

- Deeply nested data structures exceeding the default limit (20)
- Unintended circular references without reference tracking enabled
- **Malicious data**: Attackers may craft deeply nested payloads to cause resource exhaustion

**Solutions**:

1. **Increase max depth** (default is 20):

```go
f := fory.New(fory.WithMaxDepth(50))
```

2. **Enable reference tracking** (for circular data):

```go
f := fory.New(fory.WithTrackRef(true))
```

3. **Check for unintended circular references** in your data.

4. **Validate untrusted data**: When deserializing data from untrusted sources, do not blindly increase max depth. Consider validating input size and structure before deserialization.

### ErrKindBufferOutOfBound

**Error**: `buffer out of bound: offset=X, need=Y, size=Z`

**Cause**: Reading beyond available data.

**Solutions**:

1. **Ensure complete data transfer**:

```go
// Wrong: Truncated data
data := fullData[:100]
f.Deserialize(data, &target)  // Error if data was larger

// Correct: Use full data
f.Deserialize(fullData, &target)
```

2. **Check for data corruption**: Verify data integrity during transmission.

### ErrKindInvalidRefId

**Error**: `invalid reference ID`

**Cause**: Reference to non-existent or unknown object in serialized data.

**Solutions**:

1. **Ensure reference tracking consistency**:

```go
// Serializer and deserializer must have same setting
f1 := fory.New(fory.WithTrackRef(true))
f2 := fory.New(fory.WithTrackRef(true))  // Must match!
```

2. **Check for data corruption**.

### ErrKindInvalidTag

**Error**: `invalid fory struct tag`

**Cause**: Invalid struct tag configuration.

**Common causes**:

1. **Invalid tag ID**: ID must be >= -1

```go
// Wrong: negative ID (other than -1)
type Bad struct {
    Field int `fory:"id=-5"`
}

// Correct
type Good struct {
    Field int `fory:"id=0"`
}
```

2. **Duplicate tag IDs**: Each field must have a unique ID within the struct

```go
// Wrong: duplicate IDs
type Bad struct {
    Field1 int `fory:"id=0"`
    Field2 int `fory:"id=0"`  // Duplicate!
}

// Correct
type Good struct {
    Field1 int `fory:"id=0"`
    Field2 int `fory:"id=1"`
}
```

## Cross-Language Issues

### Field Order Mismatch

**Symptom**: Data deserializes but fields have wrong values.

**Cause**: Different field ordering between languages. In non-compatible mode, fields are sorted by their snake_case names. CamelCase field names (e.g., `FirstName`) are converted to snake_case (e.g., `first_name`) for sorting.

**Solutions**:

1. **Ensure converted snake_case names are consistent**: Field names across languages must produce the same snake_case ordering:

```go
type User struct {
    FirstName string  // Go: FirstName -> first_name
    LastName  string  // Go: LastName -> last_name
    // Sorted alphabetically by snake_case: first_name, last_name
}
```

2. **Use field IDs for consistent ordering**: Field IDs (non-negative integers) act as aliases for field names, used for both sorting and field matching during deserialization:

```go
type User struct {
    FirstName string `fory:"id=0"`
    LastName  string `fory:"id=1"`
}
```

Ensure the same field IDs are used across all languages for corresponding fields.

### Name Registration Mismatch

**Symptom**: `unknown type` in other languages.

**Solution**: Use identical names:

```go
// Go
f.RegisterNamedStruct(User{}, "example.User")

// Java - must match exactly
fory.register(User.class, "example.User");

// Python
fory.register(User, typename="example.User")
```

## Performance Issues

### Slow Serialization

**Possible causes**:

1. **Large object graphs**: Reduce data size or serialize incrementally.

2. **Excessive reference tracking**: Disable if not needed:

```go
f := fory.New(fory.WithTrackRef(false))
```

3. **Deep nesting**: Flatten data structures where possible.

### High Memory Usage

**Possible causes**:

1. **Large serialized data**: Process in chunks.

2. **Reference tracking overhead**: Disable if not needed.

3. **Buffer not released**: Reuse buffers:

```go
buf := fory.NewByteBuffer(nil)
f.SerializeTo(buf, value)
// Process data
buf.Reset()  // Reuse for next serialization
```

### Thread Contention

**Symptom**: Slowdown under concurrent load.

**Solutions**:

1. **Use per-goroutine instances** for hot paths:

```go
func worker() {
    f := fory.New()  // Each worker has own instance
    for task := range tasks {
        f.Serialize(task)
    }
}
```

2. **Profile pool usage** with thread-safe wrapper.

## Debugging Techniques

### Enable Debug Output

Set environment variable:

```bash
ENABLE_FORY_DEBUG_OUTPUT=1 go test ./...
```

### Inspect Serialized Data

```go
data, _ := f.Serialize(value)
fmt.Printf("Serialized %d bytes\n", len(data))
fmt.Printf("Header: %x\n", data[:4])  // Magic + flags
```

### Check Type Registration

```go
// Verify type is registered
f := fory.New()
err := f.RegisterStruct(User{}, 1)
if err != nil {
    fmt.Printf("Registration failed: %v\n", err)
}
```

### Compare Struct Hashes

If getting hash mismatch, compare struct definitions:

```go
// Print struct info for debugging
t := reflect.TypeOf(User{})
for i := 0; i < t.NumField(); i++ {
    f := t.Field(i)
    fmt.Printf("Field: %s, Type: %s\n", f.Name, f.Type)
}
```

## Testing Tips

### Test Round-Trip

```go
func TestRoundTrip(t *testing.T) {
    f := fory.New()
    f.RegisterStruct(User{}, 1)

    original := &User{ID: 1, Name: "Alice"}

    data, err := f.Serialize(original)
    require.NoError(t, err)

    var result User
    err = f.Deserialize(data, &result)
    require.NoError(t, err)

    assert.Equal(t, original.ID, result.ID)
    assert.Equal(t, original.Name, result.Name)
}
```

### Test Cross-Language

```bash
cd java/fory-core
FORY_GO_JAVA_CI=1 mvn test -Dtest=org.apache.fory.xlang.GoXlangTest
```

### Test Schema Evolution

```go
func TestSchemaEvolution(t *testing.T) {
    f1 := fory.New(fory.WithCompatible(true))
    f1.RegisterStruct(UserV1{}, 1)

    data, _ := f1.Serialize(&UserV1{ID: 1, Name: "Alice"})

    f2 := fory.New(fory.WithCompatible(true))
    f2.RegisterStruct(UserV2{}, 1)

    var result UserV2
    err := f2.Deserialize(data, &result)
    require.NoError(t, err)
}
```

## Getting Help

If you encounter issues not covered here:

1. **Check GitHub Issues**: [github.com/apache/fory/issues](https://github.com/apache/fory/issues)
2. **Enable debug output**: `ENABLE_FORY_DEBUG_OUTPUT=1`
3. **Create minimal reproduction**: Isolate the problem
4. **Report the issue**: Include Go version, Fory version, and minimal code

## Related Topics

- [Configuration](configuration.md)
- [Cross-Language Serialization](cross-language.md)
- [Schema Evolution](schema-evolution.md)
- [Thread Safety](thread-safety.md)
