---
title: Field Configuration
sidebar_position: 5
id: field_configuration
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

This page explains how to configure field-level metadata for serialization in Python.

## Overview

Apache Fory™ provides field-level configuration through:

- **`pyfory.field()`**: Configure field metadata (id, nullable, ref, ignore, dynamic)
- **Type annotations**: Control integer encoding (varint, fixed, tagged)
- **`Optional[T]`**: Mark fields as nullable

This enables:

- **Tag IDs**: Assign compact numeric IDs to reduce struct field meta size overhead
- **Nullability**: Control whether fields can be null
- **Reference Tracking**: Enable reference tracking for shared objects
- **Field Skipping**: Exclude fields from serialization
- **Encoding Control**: Specify how integers are encoded (varint, fixed, tagged)
- **Polymorphism**: Control whether type info is written for struct fields

## Basic Syntax

Use `@dataclass` decorator with type annotations and `pyfory.field()`:

```python
from dataclasses import dataclass
from typing import Optional
import pyfory

@dataclass
class Person:
    name: str = pyfory.field(id=0)
    age: pyfory.Int32 = pyfory.field(id=1, default=0)
    nickname: Optional[str] = pyfory.field(id=2, nullable=True, default=None)
```

## The `pyfory.field()` Function

Use `pyfory.field()` to configure field-level metadata:

```python
@dataclass
class User:
    id: pyfory.Int64 = pyfory.field(id=0, default=0)
    name: str = pyfory.field(id=1, default="")
    email: Optional[str] = pyfory.field(id=2, nullable=True, default=None)
    friends: List["User"] = pyfory.field(id=3, ref=True, default_factory=list)
    _cache: dict = pyfory.field(ignore=True, default_factory=dict)
```

### Parameters

| Parameter         | Type     | Default   | Description                          |
| ----------------- | -------- | --------- | ------------------------------------ |
| `id`              | `int`    | `-1`      | Field tag ID (-1 = use field name)   |
| `nullable`        | `bool`   | `False`   | Whether the field can be null        |
| `ref`             | `bool`   | `False`   | Enable reference tracking            |
| `ignore`          | `bool`   | `False`   | Exclude field from serialization     |
| `dynamic`         | `bool`   | `None`    | Control whether type info is written |
| `default`         | Any      | `MISSING` | Default value for the field          |
| `default_factory` | Callable | `MISSING` | Factory function for default value   |

## Field ID (`id`)

Assigns a numeric ID to a field to minimize struct field meta size overhead:

```python
@dataclass
class User:
    id: pyfory.Int64 = pyfory.field(id=0, default=0)
    name: str = pyfory.field(id=1, default="")
    age: pyfory.Int32 = pyfory.field(id=2, default=0)
```

**Benefits**:

- Smaller serialized size (numeric IDs vs field names in metadata)
- Reduced struct field meta overhead
- Allows renaming fields without breaking binary compatibility

**Recommendation**: It is recommended to configure field IDs for compatible mode since it reduces serialization cost.

**Notes**:

- IDs must be unique within a class
- IDs must be >= 0 (use -1 to use field name encoding, which is the default)
- If not specified, field name is used in metadata (larger overhead)

**Without field IDs** (field names used in metadata):

```python
@dataclass
class User:
    id: pyfory.Int64 = 0
    name: str = ""
```

## Nullable Fields (`nullable`)

Use `nullable=True` for fields that can be `None`:

```python
from typing import Optional

@dataclass
class Record:
    # Nullable string field
    optional_name: Optional[str] = pyfory.field(id=0, nullable=True, default=None)

    # Nullable integer field
    optional_count: Optional[pyfory.Int32] = pyfory.field(id=1, nullable=True, default=None)
```

**Notes**:

- `Optional[T]` fields must have `nullable=True`
- Non-optional fields default to `nullable=False`

## Reference Tracking (`ref`)

Enable reference tracking for fields that may be shared or circular:

```python
@dataclass
class RefOuter:
    # Both fields may point to the same inner object
    inner1: Optional[RefInner] = pyfory.field(id=0, ref=True, nullable=True, default=None)
    inner2: Optional[RefInner] = pyfory.field(id=1, ref=True, nullable=True, default=None)


@dataclass
class CircularRef:
    name: str = pyfory.field(id=0, default="")
    # Self-referencing field for circular references
    self_ref: Optional["CircularRef"] = pyfory.field(id=1, ref=True, nullable=True, default=None)
```

**Use Cases**:

- Enable for fields that may be circular or shared
- When the same object is referenced from multiple fields

**Notes**:

- Reference tracking only takes effect when `Fory(ref=True)` is set globally
- Field-level `ref=True` AND global `ref=True` must both be enabled

## Skipping Fields (`ignore`)

Exclude fields from serialization:

```python
@dataclass
class User:
    id: pyfory.Int64 = pyfory.field(id=0, default=0)
    name: str = pyfory.field(id=1, default="")
    # Not serialized
    _cache: dict = pyfory.field(ignore=True, default_factory=dict)
    _internal_state: str = pyfory.field(ignore=True, default="")
```

## Dynamic Fields (`dynamic`)

Control whether type information is written for struct fields. This is essential for polymorphism support:

```python
from abc import ABC, abstractmethod

class Shape(ABC):
    @abstractmethod
    def area(self) -> float:
        pass

@dataclass
class Circle(Shape):
    radius: float = 0.0

    def area(self) -> float:
        return 3.14159 * self.radius * self.radius

@dataclass
class Container:
    # Abstract class: dynamic is always True (type info written)
    shape: Shape = pyfory.field(id=0)

    # Force type info for concrete type (support runtime subtypes)
    circle: Circle = pyfory.field(id=1, dynamic=True)

    # Skip type info for concrete type (use declared type directly)
    fixed_circle: Circle = pyfory.field(id=2, dynamic=False)
```

**Default Behavior**:

| Mode        | Abstract Class | Concrete Object Types | Numeric/str/time Types |
| ----------- | -------------- | --------------------- | ---------------------- |
| Native mode | `True`         | `True`                | `False`                |
| Xlang mode  | `True`         | `False`               | `False`                |

**Notes**:

- **Abstract classes**: `dynamic` is always `True` (type info must be written)
- **Native mode**: `dynamic` defaults to `True` for object types, `False` for numeric/str/time types
- **Xlang mode**: `dynamic` defaults to `False` for concrete types
- Use `dynamic=True` when a concrete field may hold subclass instances
- Use `dynamic=False` for performance optimization when type is known

## Integer Type Annotations

Fory provides type annotations to control integer encoding:

### Signed Integers

```python
@dataclass
class SignedIntegers:
    byte_val: pyfory.Int8 = 0      # 8-bit signed
    short_val: pyfory.Int16 = 0    # 16-bit signed
    int_val: pyfory.Int32 = 0      # 32-bit signed (varint encoding)
    long_val: pyfory.Int64 = 0     # 64-bit signed (varint encoding)
```

### Unsigned Integers

```python
@dataclass
class UnsignedIntegers:
    # Fixed-size encoding
    u8_val: pyfory.UInt8 = 0       # 8-bit unsigned (fixed)
    u16_val: pyfory.UInt16 = 0     # 16-bit unsigned (fixed)

    # Variable-length encoding (default for u32/u64)
    u32_var: pyfory.UInt32 = 0     # 32-bit unsigned (varint)
    u64_var: pyfory.UInt64 = 0     # 64-bit unsigned (varint)

    # Explicit fixed-size encoding
    u32_fixed: pyfory.FixedUInt32 = 0   # 32-bit unsigned (fixed 4 bytes)
    u64_fixed: pyfory.FixedUInt64 = 0   # 64-bit unsigned (fixed 8 bytes)

    # Tagged encoding (includes type tag)
    u64_tagged: pyfory.TaggedUInt64 = 0  # 64-bit unsigned (tagged)
```

### Floating Point

```python
@dataclass
class FloatingPoint:
    float_val: pyfory.Float32 = 0.0   # 32-bit float
    double_val: pyfory.Float64 = 0.0  # 64-bit double
```

### Encoding Summary

| Type                  | Encoding | Size       |
| --------------------- | -------- | ---------- |
| `pyfory.Int8`         | fixed    | 1 byte     |
| `pyfory.Int16`        | fixed    | 2 bytes    |
| `pyfory.Int32`        | varint   | 1-5 bytes  |
| `pyfory.Int64`        | varint   | 1-10 bytes |
| `pyfory.FixedInt32`   | fixed    | 4 bytes    |
| `pyfory.FixedInt64`   | fixed    | 8 bytes    |
| `pyfory.TaggedInt64`  | tagged   | 1-9 bytes  |
| `pyfory.UInt8`        | fixed    | 1 byte     |
| `pyfory.UInt16`       | fixed    | 2 bytes    |
| `pyfory.UInt32`       | varint   | 1-5 bytes  |
| `pyfory.UInt64`       | varint   | 1-10 bytes |
| `pyfory.FixedUInt32`  | fixed    | 4 bytes    |
| `pyfory.FixedUInt64`  | fixed    | 8 bytes    |
| `pyfory.TaggedUInt64` | tagged   | 1-9 bytes  |
| `pyfory.Float32`      | fixed    | 4 bytes    |
| `pyfory.Float64`      | fixed    | 8 bytes    |

**When to Use**:

- `varint`: Best for values that are often small (default for int32/int64/uint32/uint64)
- `fixed`: Best for values that use full range (e.g., timestamps, hashes)
- `tagged`: When type information needs to be preserved (int64/uint64 only)

## Nested Container Type Annotations

Integer encoding aliases can be used inside declared collection schemas. Fory uses the declared
field schema for every nested element, key, and value in both pure Python and Cython modes:

```python
from dataclasses import dataclass, field
from typing import Dict, List
import pyfory


@dataclass
class Counters:
    values: Dict[pyfory.FixedInt32, List[pyfory.TaggedInt64]] = field(default_factory=dict)
```

For `values`, map keys are written as fixed-width int32 values and each nested list element is
written as tagged int64. Runtime type inference is used only for dynamic or unknown container
schemas.

In compatible mode, readers consume field bytes using the remote schema metadata. Python assigns the
decoded value only when it can safely satisfy the local declared schema. Different integer encodings
in the same signedness and width domain are compatible, and same-signedness narrowing is assigned
only after range validation.

## Complete Example

```python
from dataclasses import dataclass
from typing import Optional, List, Dict, Set
import pyfory


@dataclass
class Document:
    # Fields with tag IDs (recommended for compatible mode)
    title: str = pyfory.field(id=0, default="")
    version: pyfory.Int32 = pyfory.field(id=1, default=0)

    # Nullable field
    description: Optional[str] = pyfory.field(id=2, nullable=True, default=None)

    # Collection fields
    tags: List[str] = pyfory.field(id=3, default_factory=list)
    metadata: Dict[str, str] = pyfory.field(id=4, default_factory=dict)
    categories: Set[str] = pyfory.field(id=5, default_factory=set)

    # Unsigned integers with different encodings
    view_count: pyfory.UInt64 = pyfory.field(id=6, default=0)           # varint encoding
    file_size: pyfory.FixedUInt64 = pyfory.field(id=7, default=0)       # fixed encoding
    checksum: pyfory.TaggedUInt64 = pyfory.field(id=8, default=0)       # tagged encoding

    # Reference-tracked field for shared/circular references
    parent: Optional["Document"] = pyfory.field(id=9, ref=True, nullable=True, default=None)

    # Ignored field (not serialized)
    _cache: dict = pyfory.field(ignore=True, default_factory=dict)


def main():
    fory = pyfory.Fory(xlang=True, compatible=True, ref=True)
    fory.register_type(Document, type_id=100)

    doc = Document(
        title="My Document",
        version=1,
        description="A sample document",
        tags=["tag1", "tag2"],
        metadata={"key": "value"},
        categories={"cat1"},
        view_count=42,
        file_size=1024,
        checksum=123456789,
        parent=None,
    )

    # Serialize
    data = fory.serialize(doc)

    # Deserialize
    decoded = fory.deserialize(data)
    assert decoded.title == doc.title
    assert decoded.version == doc.version


if __name__ == "__main__":
    main()
```

## Cross-Language Compatibility

When serializing data to be read by other languages (Java, Rust, C++, Go), use field IDs and matching type annotations:

```python
@dataclass
class CrossLangData:
    # Use field IDs for cross-language compatibility
    int_var: pyfory.Int32 = pyfory.field(id=0, default=0)
    long_fixed: pyfory.FixedUInt64 = pyfory.field(id=1, default=0)
    long_tagged: pyfory.TaggedUInt64 = pyfory.field(id=2, default=0)
    optional_value: Optional[str] = pyfory.field(id=3, nullable=True, default=None)
```

## Schema Evolution

Compatible mode supports schema evolution. It is recommended to configure field IDs to reduce serialization cost:

```python
# Version 1
@dataclass
class DataV1:
    id: pyfory.Int64 = pyfory.field(id=0, default=0)
    name: str = pyfory.field(id=1, default="")


# Version 2: Added new field
@dataclass
class DataV2:
    id: pyfory.Int64 = pyfory.field(id=0, default=0)
    name: str = pyfory.field(id=1, default="")
    email: Optional[str] = pyfory.field(id=2, nullable=True, default=None)  # New field
```

Data serialized with V1 can be deserialized with V2 (new field will be `None`).

Alternatively, field IDs can be omitted (field names will be used in metadata with larger overhead):

```python
@dataclass
class Data:
    id: pyfory.Int64 = 0
    name: str = ""
```

## Native Mode vs Xlang Mode

Field configuration behaves differently depending on the serialization mode:

### Native Mode (Python-only)

Native mode has **relaxed default values** for maximum compatibility:

- **Nullable**: `str` and numeric types are non-nullable by default unless `Optional` is used
- **Ref tracking**: Enabled by default for object references (except `str` and numeric types)

In native mode, you typically **don't need to configure field annotations** unless you want to:

- Reduce serialized size by using field IDs
- Optimize performance by disabling unnecessary ref tracking

```python
# Native mode: works without field configuration
@dataclass
class User:
    id: int = 0
    name: str = ""
    tags: List[str] = None
```

### Xlang Mode (Cross-language)

Xlang mode has **stricter default values** due to type system differences between languages:

- **Nullable**: Fields are non-nullable by default (`nullable=False`)
- **Ref tracking**: Disabled by default (`ref=False`)

In xlang mode, you **need to configure fields** when:

- A field can be None (use `Optional[T]` with `nullable=True`)
- A field needs reference tracking for shared/circular objects (use `ref=True`)
- Integer types need specific encoding for cross-language compatibility
- You want to reduce metadata size (use field IDs)

```python
# Xlang mode: explicit configuration required for nullable/ref fields
@dataclass
class User:
    id: pyfory.Int64 = pyfory.field(id=0, default=0)
    name: str = pyfory.field(id=1, default="")
    email: Optional[str] = pyfory.field(id=2, nullable=True, default=None)  # Must declare nullable
    friend: Optional["User"] = pyfory.field(id=3, ref=True, nullable=True, default=None)  # Must declare ref
```

### Default Values Summary

| Option     | Native Mode Default                                   | Xlang Mode Default |
| ---------- | ----------------------------------------------------- | ------------------ |
| `nullable` | `False` for `str`/numeric; others nullable by default | `False`            |
| `ref`      | `True` (except `str` and numeric types)               | `False`            |
| `dynamic`  | `True` (except numeric/str/time types)                | `False` (concrete) |

## Best Practices

1. **Configure field IDs**: Recommended for compatible mode to reduce serialization cost
2. **Use `Optional[T]` with `nullable=True`**: Required for nullable fields in xlang mode
3. **Enable ref tracking for shared objects**: Use `ref=True` when objects are shared or circular
4. **Use `ignore=True` for sensitive data**: Passwords, tokens, internal state
5. **Choose appropriate encoding**: `varint` for small values, `fixed` for full-range values
6. **Keep IDs stable**: Once assigned, don't change field IDs

## Options Reference

| Configuration                              | Description                          |
| ------------------------------------------ | ------------------------------------ |
| `pyfory.field(id=N)`                       | Field tag ID to reduce metadata size |
| `pyfory.field(nullable=True)`              | Mark field as nullable               |
| `pyfory.field(ref=True)`                   | Enable reference tracking            |
| `pyfory.field(ignore=True)`                | Exclude field from serialization     |
| `pyfory.field(dynamic=True)`               | Force type info to be written        |
| `pyfory.field(dynamic=False)`              | Skip type info (use declared type)   |
| `Optional[T]`                              | Type hint for nullable fields        |
| `pyfory.Int32`, `pyfory.Int64`             | Signed integers (varint encoding)    |
| `pyfory.FixedInt32`, `pyfory.FixedInt64`   | Fixed-size signed                    |
| `pyfory.TaggedInt64`                       | Tagged encoding for int64            |
| `pyfory.UInt32`, `pyfory.UInt64`           | Unsigned integers (varint encoding)  |
| `pyfory.FixedUInt32`, `pyfory.FixedUInt64` | Fixed-size unsigned                  |
| `pyfory.TaggedUInt64`                      | Tagged encoding for uint64           |

## Related Topics

- [Basic Serialization](basic-serialization.md) - Getting started with Fory serialization
- [Schema Evolution](schema-evolution.md) - Compatible mode and schema evolution
- [Cross-Language](cross-language.md) - Interoperability with Java, Rust, C++, Go
