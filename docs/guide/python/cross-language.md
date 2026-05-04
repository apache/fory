---
title: Cross-Language Serialization
sidebar_position: 10
id: cross_language
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

`pyfory` supports cross-language object graph serialization, allowing you to serialize data in Python and deserialize it in Java, Go, Rust, or other supported languages.

## Enable Cross-Language Mode

To use xlang mode, create `Fory` with `xlang=True`:

```python
import pyfory
fory = pyfory.Fory(xlang=True, ref=False, strict=True)
```

## Cross-Language Example

### Python (Serializer)

```python
import pyfory
from dataclasses import dataclass

# Cross-language mode for interoperability
f = pyfory.Fory(xlang=True, ref=True)

# Register type for cross-language compatibility
@dataclass
class Person:
    name: str
    age: pyfory.Int32

f.register(Person, typename="example.Person")

person = Person("Charlie", 35)
binary_data = f.serialize(person)
# binary_data can now be sent to Java, Go, etc.
```

### Java (Deserializer)

```java
import org.apache.fory.*;

public class Person {
    public String name;
    public int age;
}

Fory fory = Fory.builder()
    .withXlang(true)
    .withRefTracking(true)
    .build();

fory.register(Person.class, "example.Person");
Person person = (Person) fory.deserialize(binaryData);
```

### Rust (Deserializer)

```rust
use fory::Fory;
use fory::ForyObject;

#[derive(ForyObject)]
struct Person {
    name: String,
    age: i32,
}

let mut fory = Fory::builder()
    .compatible(true)
    .xlang(true).build();

fory.register_by_namespace::<Person>("example", "Person");
let person: Person = fory.deserialize(&binary_data)?;
```

## Type Annotations for Cross-Language

Use pyfory type annotations for explicit cross-language type mapping:

```python
from dataclasses import dataclass
from typing import Dict, List
import pyfory

@dataclass
class TypedData:
    int_value: pyfory.Int32       # 32-bit integer
    long_value: pyfory.Int64      # 64-bit integer
    float_value: pyfory.Float32   # 32-bit float
    double_value: pyfory.Float64  # 64-bit float
    values: Dict[pyfory.Int32, List[pyfory.Int64]]
```

Nested collection annotations are part of the field schema. Compatible-mode
reads consume bytes with the remote schema metadata, then assign only when the
decoded value safely satisfies the local schema.

## Reduced-Precision Types

`pyfory.Float16` and `pyfory.BFloat16` are reserved annotation markers for xlang
reduced-precision fields. They are not runtime value classes; scalar values deserialize as native
Python `float`.

Dense reduced-precision arrays use public dense wrappers with list-like sequence behavior. Construct them from Python
numeric values with `pyfory.Float16Array.from_values([...])` or
`pyfory.BFloat16Array.from_values([...])`. Use `from_buffer(...)` and `to_buffer()` only when you
already need packed little-endian `uint16` storage and want the raw-buffer fast path.

## Type Mapping

| Python marker/carrier  | Java           | Rust            | Go                    |
| ---------------------- | -------------- | --------------- | --------------------- |
| `str`                  | `String`       | `String`        | `string`              |
| `int`                  | `long`         | `i64`           | `int64`               |
| `pyfory.Int32`         | `int`          | `i32`           | `int32`               |
| `pyfory.Int64`         | `long`         | `i64`           | `int64`               |
| `float`                | `double`       | `f64`           | `float64`             |
| `pyfory.Float32`       | `float`        | `f32`           | `float32`             |
| `pyfory.Float16`       | `Float16`      | `Float16`       | `float16.Float16`     |
| `pyfory.BFloat16`      | `BFloat16`     | `BFloat16`      | `bfloat16.BFloat16`   |
| `pyfory.Float16Array`  | `Float16List`  | `Vec<Float16>`  | `[]float16.Float16`   |
| `pyfory.BFloat16Array` | `BFloat16List` | `Vec<BFloat16>` | `[]bfloat16.BFloat16` |
| `list`                 | `List`         | `Vec`           | `[]T`                 |
| `dict`                 | `Map`          | `HashMap`       | `map[K]V`             |

### Lists and Dense Arrays

Python `List[T]` maps to Fory `list<T>`. Use `pyfory.Array[T]`,
`pyfory.NDArray[T]`, or `pyfory.PyArray[T]` only when the schema is the dense
one-dimensional `array<T>` kind.

| Fory schema       | Python annotation and default carrier              |
| ----------------- | -------------------------------------------------- |
| `list<int32>`     | `List[pyfory.Int32]`                               |
| `array<bool>`     | `pyfory.Array[bool]` -> `BoolArray`                |
| `array<int8>`     | `pyfory.Array[pyfory.Int8]` -> `Int8Array`         |
| `array<int16>`    | `pyfory.Array[pyfory.Int16]` -> `Int16Array`       |
| `array<int32>`    | `pyfory.Array[pyfory.Int32]` -> `Int32Array`       |
| `array<int64>`    | `pyfory.Array[pyfory.Int64]` -> `Int64Array`       |
| `array<uint8>`    | `pyfory.Array[pyfory.UInt8]` -> `UInt8Array`       |
| `array<uint16>`   | `pyfory.Array[pyfory.UInt16]` -> `UInt16Array`     |
| `array<uint32>`   | `pyfory.Array[pyfory.UInt32]` -> `UInt32Array`     |
| `array<uint64>`   | `pyfory.Array[pyfory.UInt64]` -> `UInt64Array`     |
| `array<float16>`  | `pyfory.Array[pyfory.Float16]` -> `Float16Array`   |
| `array<bfloat16>` | `pyfory.Array[pyfory.BFloat16]` -> `BFloat16Array` |
| `array<float32>`  | `pyfory.Array[pyfory.Float32]` -> `Float32Array`   |
| `array<float64>`  | `pyfory.Array[pyfory.Float64]` -> `Float64Array`   |

The `pyfory.*Array` wrappers accept iterable constructors such as
`pyfory.Float32Array([1, 2, 3])` and expose list-like sequence behavior over
dense owned storage.

`pyfory.Array[T]`, `pyfory.NDArray[T]`, and `pyfory.PyArray[T]` all describe
the same Fory `array<T>` schema. They differ only in the Python carrier
contract:

| Python field annotation | Value accepted for that field                           | Deserialized carrier |
| ----------------------- | ------------------------------------------------------- | -------------------- |
| `pyfory.Array[T]`       | `pyfory.*Array`, `numpy.ndarray`, `array.array`, `list` | `pyfory.*Array`      |
| `pyfory.NDArray[T]`     | `numpy.ndarray`                                         | `numpy.ndarray`      |
| `pyfory.PyArray[T]`     | Python `array.array`                                    | Python `array.array` |

In compatible mode, a writer and reader can use different Python carriers for
the same named field as long as both annotations lower to the same Fory
`array<T>` schema. For example, a writer field declared as
`pyfory.Array[pyfory.Int32]` can be read by a Python class whose matching field
is declared as `pyfory.NDArray[pyfory.Int32]`, and the reader receives a NumPy
`int32` ndarray. The reverse pattern also works for `pyfory.PyArray[T]`; that
name always means Python `array.array`.

PyArrow is a separate row/columnar format surface, not a `pyfory.PyArray`
carrier. Use `pyfory.format.from_arrow_schema(...)` and
`pyfory.format.to_arrow_schema(...)` to convert between PyArrow schemas and
Fory row-format schemas.

## Differences from Python Native Mode

The binary protocol and API are similar to `pyfory`'s python-native mode, but Python-native mode can serialize any Python object—including global functions, local functions, lambdas, local classes, and types with customized serialization using `__getstate__/__reduce__/__reduce_ex__`, which are **not allowed** in xlang mode.

## See Also

- [Cross-Language Serialization Specification](../../specification/xlang_serialization_spec.md)
- [Type Mapping Reference](../../specification/xlang_type_mapping.md)
- [Java Cross-Language Guide](../java/cross-language.md)
- [Rust Cross-Language Guide](../rust/cross-language.md)

## Related Topics

- [Configuration](configuration.md) - XLANG mode settings
- [Schema Evolution](schema-evolution.md) - Compatible mode
- [Type Registration](type-registration.md) - Registration patterns
