---
title: Custom Serializers
sidebar_position: 10
id: custom_serializers
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

Custom serializers allow you to define exactly how a type is serialized and deserialized. This is useful for types that require special handling, optimization, or cross-language compatibility.

## When to Use Custom Serializers

- **Special encoding**: Types that need a specific binary format
- **Third-party types**: Types from external libraries that Fory doesn't handle automatically
- **Optimization**: When you can serialize more efficiently than the default fast serializer path
- **Cross-language compatibility**: When you need precise control over the binary format for interoperability

## ExtensionSerializer Interface

Custom serializers implement the `ExtensionSerializer` interface:

```go
type ExtensionSerializer interface {
    // WriteData serializes the value to the buffer.
    // Only write the data - Fory handles type info and references.
    // Use ctx.Buffer() to access the ByteBuffer.
    // Use ctx.SetError() to report errors.
    WriteData(ctx *WriteContext, value reflect.Value)

    // ReadData deserializes the value from the buffer into the provided value.
    // Only read the data - Fory handles type info and references.
    // Use ctx.Buffer() to access the ByteBuffer.
    // Use ctx.SetError() to report errors.
    ReadData(ctx *ReadContext, value reflect.Value)
}
```

## Basic Example

Here's a simple custom serializer for a type with an integer field:

```go
import (
    "reflect"
    "github.com/apache/fory/go/fory"
)

type MyExt struct {
    Id int32
}

type MyExtSerializer struct{}

func (s *MyExtSerializer) WriteData(ctx *fory.WriteContext, value reflect.Value) {
    myExt := value.Interface().(MyExt)
    ctx.Buffer().WriteVarint32(myExt.Id)
}

func (s *MyExtSerializer) ReadData(ctx *fory.ReadContext, value reflect.Value) {
    id := ctx.Buffer().ReadVarint32(ctx.Err())
    value.Set(reflect.ValueOf(MyExt{Id: id}))
}

// Register the custom serializer
f := fory.New(fory.WithXlang(true))
err := f.RegisterExtension(MyExt{}, 100, &MyExtSerializer{})
```

## Context Methods

The `WriteContext` and `ReadContext` provide access to serialization resources:

| Method           | Description                                    |
| ---------------- | ---------------------------------------------- |
| `Buffer()`       | Returns the `*ByteBuffer` for reading/writing  |
| `Err()`          | Returns `*Error` for deferred error checking   |
| `SetError(err)`  | Sets an error on the context                   |
| `HasError()`     | Returns true if an error has been set          |
| `TypeResolver()` | Returns the type resolver for nested types     |
| `RefResolver()`  | Returns the reference resolver for ref support |

## ByteBuffer Methods

The `ByteBuffer` provides methods for reading and writing primitive types:

### Writing Methods

| Method                     | Description                                   |
| -------------------------- | --------------------------------------------- |
| `WriteBool(v bool)`        | Write a boolean                               |
| `WriteInt8(v int8)`        | Write a signed 8-bit integer                  |
| `WriteInt16(v int16)`      | Write a signed 16-bit integer                 |
| `WriteInt32(v int32)`      | Write a signed 32-bit integer                 |
| `WriteInt64(v int64)`      | Write a signed 64-bit integer                 |
| `WriteFloat32(v float32)`  | Write a 32-bit float                          |
| `WriteFloat64(v float64)`  | Write a 64-bit float                          |
| `WriteVarint32(v int32)`   | Write a variable-length signed 32-bit integer |
| `WriteVarint64(v int64)`   | Write a variable-length signed 64-bit integer |
| `WriteBinary(data []byte)` | Write raw bytes                               |

### Reading Methods

All read methods take an `*Error` parameter for deferred error checking:

| Method                                      | Description                                  |
| ------------------------------------------- | -------------------------------------------- |
| `ReadBool(err *Error) bool`                 | Read a boolean                               |
| `ReadInt8(err *Error) int8`                 | Read a signed 8-bit integer                  |
| `ReadInt16(err *Error) int16`               | Read a signed 16-bit integer                 |
| `ReadInt32(err *Error) int32`               | Read a signed 32-bit integer                 |
| `ReadInt64(err *Error) int64`               | Read a signed 64-bit integer                 |
| `ReadFloat32(err *Error) float32`           | Read a 32-bit float                          |
| `ReadFloat64(err *Error) float64`           | Read a 64-bit float                          |
| `ReadVarint32(err *Error) int32`            | Read a variable-length signed 32-bit integer |
| `ReadVarint64(err *Error) int64`            | Read a variable-length signed 64-bit integer |
| `ReadBinary(length int, err *Error) []byte` | Read raw bytes of specified length           |

## Complex Type Example

A custom serializer for a type with multiple fields:

```go
type Point3D struct {
    X, Y, Z float64
    Label   string
}

type Point3DSerializer struct{}

func (s *Point3DSerializer) WriteData(ctx *fory.WriteContext, value reflect.Value) {
    p := value.Interface().(Point3D)
    buf := ctx.Buffer()
    buf.WriteFloat64(p.X)
    buf.WriteFloat64(p.Y)
    buf.WriteFloat64(p.Z)
    // Write string as length + bytes
    labelBytes := []byte(p.Label)
    buf.WriteVarint32(int32(len(labelBytes)))
    buf.WriteBinary(labelBytes)
}

func (s *Point3DSerializer) ReadData(ctx *fory.ReadContext, value reflect.Value) {
    buf := ctx.Buffer()
    err := ctx.Err()
    x := buf.ReadFloat64(err)
    y := buf.ReadFloat64(err)
    z := buf.ReadFloat64(err)
    labelLen := buf.ReadVarint32(err)
    labelBytes := buf.ReadBinary(int(labelLen), err)
    value.Set(reflect.ValueOf(Point3D{
        X:     x,
        Y:     y,
        Z:     z,
        Label: string(labelBytes),
    }))
}

f := fory.New(fory.WithXlang(true))
f.RegisterExtension(Point3D{}, 101, &Point3DSerializer{})
```

## Handling Pointers

When your type contains pointers, handle nil values explicitly:

```go
type OptionalValue struct {
    Value *int64
}

type OptionalValueSerializer struct{}

func (s *OptionalValueSerializer) WriteData(ctx *fory.WriteContext, value reflect.Value) {
    ov := value.Interface().(OptionalValue)
    buf := ctx.Buffer()
    if ov.Value == nil {
        buf.WriteBool(false) // nil flag
    } else {
        buf.WriteBool(true) // not nil
        buf.WriteInt64(*ov.Value)
    }
}

func (s *OptionalValueSerializer) ReadData(ctx *fory.ReadContext, value reflect.Value) {
    buf := ctx.Buffer()
    err := ctx.Err()
    hasValue := buf.ReadBool(err)
    if !hasValue {
        value.Set(reflect.ValueOf(OptionalValue{Value: nil}))
        return
    }
    v := buf.ReadInt64(err)
    value.Set(reflect.ValueOf(OptionalValue{Value: &v}))
}
```

## Error Handling

Use `ctx.SetError()` to report errors:

```go
func (s *MySerializer) ReadData(ctx *fory.ReadContext, value reflect.Value) {
    buf := ctx.Buffer()
    version := buf.ReadInt8(ctx.Err())
    if ctx.HasError() {
        return
    }
    if version != 1 {
        ctx.SetError(fory.DeserializationErrorf("unsupported version: %d", version))
        return
    }
    // Continue reading...
    value.Set(reflect.ValueOf(result))
}
```

## Registration Options

### Register by ID

More compact serialization, requires ID coordination across languages:

```go
f.RegisterExtension(MyType{}, 100, &MySerializer{})
```

### Register by Name

More flexible but more serialization cost, type name included in serialized data:

```go
f.RegisterExtensionByName(MyType{}, "myapp.MyType", &MySerializer{})
```

## Best Practices

1. **Keep it simple**: Only serialize what you need
2. **Use variable-length integers**: `WriteVarint32`/`WriteVarint64` for integers that are often small
3. **Handle nil explicitly**: Check for nil pointers and slices
4. **Version your format**: Consider adding a version byte for future compatibility
5. **Test round-trips**: Always verify that `Read(Write(value)) == value`
6. **Match read/write order**: Read fields in exactly the same order you write them
7. **Check errors**: Use `ctx.HasError()` after reading to handle errors gracefully
8. **Deploy before use**: Always deploy the registered serializer to all services before sending data serialized with it. If a service receives data for an unregistered serializer, deserialization will fail

## Testing Custom Serializers

```go
func TestMySerializer(t *testing.T) {
    f := fory.New(fory.WithXlang(true))
    f.RegisterExtension(MyType{}, 100, &MySerializer{})

    original := MyType{Field: "test"}

    // Serialize
    data, err := f.Serialize(original)
    require.NoError(t, err)

    // Deserialize
    var result MyType
    err = f.Deserialize(data, &result)
    require.NoError(t, err)

    assert.Equal(t, original, result)
}
```

## Related Topics

- [Type Registration](type-registration.md)
- [Supported Types](supported-types.md)
- [Xlang Serialization](xlang-serialization.md)
