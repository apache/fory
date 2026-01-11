---
title: Troubleshooting
sidebar_position: 110
id: go_troubleshooting
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
f.RegisterByName(User{}, "example.User")

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
f1.RegisterByName(User{}, "example.User")

// Deserializer - must use same name
f2 := fory.New()
f2.RegisterByName(User{}, "example.User")  // Same name!
```

### ErrKindHashMismatch

**Error**: `struct hash mismatch: expected X, got Y for type Z`

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

**Solutions**:

1. **Increase max depth**:

```go
f := fory.New(fory.WithMaxDepth(500))
```

2. **Enable reference tracking** (for circular data):

```go
f := fory.New(fory.WithTrackRef(true))
```

3. **Check for unintended circular references** in your data.

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

**Cause**: Reference to non-existent object in serialized data.

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

**Cause**: Malformed struct tag syntax.

**Solution**:

```go
// Wrong
type Bad struct {
    Field int `fory:"invalid=option=format"`
}

// Correct
type Good struct {
    Field int `fory:"option=value"`
}
```

## Cross-Language Issues

### Field Order Mismatch

**Symptom**: Data deserializes but fields have wrong values.

**Cause**: Different field ordering between languages.

**Solution**: Ensure consistent field naming (snake_case):

```go
type User struct {
    FirstName string  // Go: FirstName -> first_name
    LastName  string  // Sorted alphabetically by snake_case
}
```

### Type Mapping Errors

**Symptom**: Overflow or precision loss.

**Cause**: Type not supported in target language.

**Examples**:

- `uint64` max value → Java `long` (overflow)
- `float32` precision → Python `float` (64-bit)

**Solution**: Use compatible types:

```go
// Instead of uint64 with large values
type Data struct {
    Count int64  // Use signed for Java compatibility
}
```

### Name Registration Mismatch

**Symptom**: `unknown type` in other languages.

**Solution**: Use identical names:

```go
// Go
f.RegisterByName(User{}, "example.User")

// Java - must match exactly
fory.register(User.class, "example.User");

// Python
fory.register_type(User, typename="example.User")
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
err := f.RegisterByName(User{}, "example.User")
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
    f.RegisterByName(User{}, "example.User")

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
    f1.RegisterByName(UserV1{}, "example.User")

    data, _ := f1.Serialize(&UserV1{ID: 1, Name: "Alice"})

    f2 := fory.New(fory.WithCompatible(true))
    f2.RegisterByName(UserV2{}, "example.User")

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

- [Configuration](configuration)
- [Cross-Language Serialization](cross-language)
- [Schema Evolution](schema-evolution)
- [Thread Safety](thread-safety)
