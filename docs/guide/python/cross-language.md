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
    age: pyfory.int32

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
import pyfory

@dataclass
class TypedData:
    int_value: pyfory.int32      # 32-bit integer
    long_value: pyfory.int64     # 64-bit integer
    float_value: pyfory.float32  # 32-bit float
    double_value: pyfory.float64 # 64-bit float
```

## Reduced-Precision Types

`pyfory.serialization` exports Cython-only carrier types for xlang reduced-precision values:

- `float16` and `float16array`
- `bfloat16` and `bfloat16array`

These names are compiled into the `pyfory.serialization` extension and re-exported from `pyfory`. There is no pure-Python fallback module for them.

The scalar wrappers behave like reduced-precision numeric value types. They support arithmetic and
ordering with Python numeric operands, and each operation quantizes the result back to the wrapper's
own format (`pyfory.float16` or `pyfory.bfloat16`).

The array wrappers are value-oriented public APIs. Construct them from Python numeric values with
`pyfory.float16array([...])`, `pyfory.float16array.from_values([...])`,
`pyfory.bfloat16array([...])`, or `pyfory.bfloat16array.from_values([...])`. Use
`from_buffer(...)` and `to_buffer()` only when you already need packed little-endian `uint16`
storage and want the raw-buffer fast path. Both array carriers also implement the CPython buffer
protocol, so `memoryview(pyfory.float16array(...))` and `memoryview(pyfory.bfloat16array(...))`
expose the packed `uint16` storage directly.

## Type Mapping

| Python                 | Java           | Rust            | Go                    |
| ---------------------- | -------------- | --------------- | --------------------- |
| `str`                  | `String`       | `String`        | `string`              |
| `int`                  | `long`         | `i64`           | `int64`               |
| `pyfory.int32`         | `int`          | `i32`           | `int32`               |
| `pyfory.int64`         | `long`         | `i64`           | `int64`               |
| `float`                | `double`       | `f64`           | `float64`             |
| `pyfory.float32`       | `float`        | `f32`           | `float32`             |
| `pyfory.float16`       | `Float16`      | `Float16`       | `float16.Float16`     |
| `pyfory.bfloat16`      | `BFloat16`     | `BFloat16`      | `bfloat16.BFloat16`   |
| `pyfory.float16array`  | `Float16List`  | `Vec<Float16>`  | `[]float16.Float16`   |
| `pyfory.bfloat16array` | `BFloat16List` | `Vec<BFloat16>` | `[]bfloat16.BFloat16` |
| `list`                 | `List`         | `Vec`           | `[]T`                 |
| `dict`                 | `Map`          | `HashMap`       | `map[K]V`             |

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
