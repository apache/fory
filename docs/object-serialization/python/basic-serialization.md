---
title: Basic Serialization
sidebar_position: 1
id: basic-serialization
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

This page covers the Python xlang quickstart. `pyfory.Fory()` defaults to xlang mode with
compatible schema evolution; examples set `xlang=True` explicitly so the mode choice is visible.

## Basic Object Serialization

Serialize and deserialize Python objects with a simple API:

```python
import pyfory

fory = pyfory.Fory(xlang=True)

# Serialize xlang-compatible values
data = fory.dumps({"name": "Alice", "age": 30, "scores": [95, 87, 92]})

# Deserialize back to Python object
obj = fory.loads(data)
print(obj)  # {'name': 'Alice', 'age': 30, 'scores': [95, 87, 92]}
```

**Note**: `dumps()`/`loads()` are aliases for `serialize()`/`deserialize()`. Both APIs are identical, use whichever feels more intuitive.

## Custom Class Serialization

Use dataclasses and type annotations for stable xlang payloads:

```python
import pyfory
from dataclasses import dataclass
from typing import List, Dict

@dataclass
class Person:
    name: str
    age: pyfory.Int32
    scores: List[pyfory.Int32]
    metadata: Dict[str, str]

fory = pyfory.Fory(xlang=True, ref=True)
fory.register(Person, name="example.Person")
person = Person("Bob", 25, [88, 92, 85], {"team": "engineering"})
data = fory.serialize(person)
result = fory.deserialize(data)
print(result)  # Person(name='Bob', age=25, ...)
```

## Reference Tracking & Circular References

Handle repeated references safely when the payload uses xlang-compatible types:

```python
import pyfory

f = pyfory.Fory(xlang=True, ref=True)

shared = ["shared"]
value = [shared, shared]

data = f.serialize(value)
result = f.deserialize(data)
assert result[0] is result[1]
```

For arbitrary Python object graphs, local classes, functions, and methods, use
[Native Serialization](native.md).

## Performance Tips

1. **Disable `ref=True` if not needed**: Reference tracking has overhead
2. **Use type_id instead of name**: Integer IDs are faster than string names
3. **Reuse Fory instances**: Create once, use many times
4. **Enable Cython**: Make sure `ENABLE_FORY_CYTHON_SERIALIZATION=1`

```python
# Good: Reuse instance
fory = pyfory.Fory(xlang=True)
for obj in objects:
    data = fory.dumps(obj)

# Bad: Create new instance each time
for obj in objects:
    fory = pyfory.Fory(xlang=True)  # Wasteful!
    data = fory.dumps(obj)
```

## Cross-Language Interoperability

The default xlang format is shared by all supported Fory implementations. The following sections cover its cross-language type mapping, type identity, and interoperability requirements.

`pyfory` supports xlang object graph serialization, allowing you to serialize
data in Python and deserialize it in Java, C++, Go, Rust,
JavaScript/TypeScript, C#, Swift, Dart, Scala, Kotlin, or another supported
language.

### Xlang Configuration

Python defaults to xlang mode with compatible schema evolution. Set the mode explicitly in xlang examples:

```python
import pyfory
fory = pyfory.Fory(xlang=True, ref=False, strict=True)
```

### Xlang Example

#### Python (Serializer)

```python
import pyfory
from dataclasses import dataclass

f = pyfory.Fory(xlang=True, ref=True)

# Register type for xlang compatibility
@dataclass
class Person:
    name: str
    age: pyfory.Int32

f.register(Person, name="example.Person")

person = Person("Charlie", 35)
binary_data = f.serialize(person)
# binary_data can now be sent to Java, Go, etc.
```

#### Java (Deserializer)

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

#### Rust (Deserializer)

```rust
use fory::Fory;
use fory::ForyStruct;

#[derive(ForyStruct)]
struct Person {
    name: String,
    age: i32,
}

let mut fory = Fory::builder().xlang(true).build();

fory.register_by_name::<Person>("example.Person");
let person: Person = fory.deserialize(&binary_data)?;
```

### Type Annotations for Xlang

Use pyfory type annotations for explicit xlang type mapping:

Use these markers directly in Python type annotations. Field values remain
ordinary Python `int` or `float` values, and Fory serializes them with the
requested xlang numeric width and encoding.

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

### Reduced-Precision Types

`pyfory.Float16` and `pyfory.BFloat16` are reserved annotation markers for xlang
reduced-precision fields. They are not value wrapper classes; scalar values deserialize as native
Python `float`.

Dense reduced-precision arrays use public dense wrappers with list-like sequence behavior. Construct them from Python
numeric values with `pyfory.Float16Array.from_values([...])` or
`pyfory.BFloat16Array.from_values([...])`. Use `from_buffer(...)` and `to_buffer()` only when you
already need packed little-endian `uint16` storage and want the raw-buffer fast path.

### Type Mapping

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

#### Lists and Dense Arrays

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

### Differences from Python Native Mode

The binary protocol and API are similar to `pyfory`'s Python native mode, but Python native mode can serialize any Python object—including global functions, local functions, lambdas, local classes, and types with custom serialization using `__getstate__/__reduce__/__reduce_ex__`, which are **not allowed** in xlang mode.

### Specifications and References

- [Xlang Serialization Specification](../../specification/xlang_serialization_spec.md)
- [Type Mapping Reference](../../specification/xlang_type_mapping.md)
- [Java Interoperability Guide](../java/basic-serialization.md#cross-language-interoperability)
- [Rust Interoperability Guide](../rust/basic-serialization.md#cross-language-interoperability)

### Related Guides

- [Configuration](configuration.md) - xlang mode settings
- [Schema Evolution](schema-evolution.md) - Compatible mode
- [Type Registration](type-registration.md) - Registration patterns

### Read the Java file example

```python
import pyfory
from dataclasses import dataclass

@dataclass
class Person:
    name: str
    age: pyfory.Int32

fory = pyfory.Fory(xlang=True)
fory.register_type(Person, name="example.Person")

with open("person.bin", "rb") as f:
    data = f.read()

person = fory.deserialize(data)
print(f"Name: {person.name}, Age: {person.age}")
# Output: Name: Alice, Age: 30
```

### Built-in values

```python
import pyfory
import numpy as np

fory = pyfory.Fory(xlang=True)
object_list = [True, False, "str", -1.1, 1,
               np.full(100, 0, dtype=np.int32), np.full(20, 0.0, dtype=np.double)]
data = fory.serialize(object_list)
# bytes can be deserialized by other languages
new_list = fory.deserialize(data)
object_map = {"k1": "v1", "k2": object_list, "k3": -1}
data = fory.serialize(object_map)
# bytes can be deserialized by other languages
new_map = fory.deserialize(data)
print(new_map)
```

### Custom values

```python
from dataclasses import dataclass
from typing import List, Dict, Any
import pyfory, array


@dataclass
class SomeClass1:
    f1: Any
    f2: Dict[pyfory.Int8, pyfory.Int32]


@dataclass
class SomeClass2:
    f1: Any = None
    f2: str = None
    f3: List[str] = None
    f4: Dict[pyfory.Int8, pyfory.Int32] = None
    f5: pyfory.Int8 = None
    f6: pyfory.Int16 = None
    f7: pyfory.Int32 = None
    # int type will be taken as `pyfory.Int64`.
    # use `pyfory.Int32` for type hint if peer uses more narrow type.
    f8: int = None
    f9: pyfory.Float32 = None
    # float type will be taken as `pyfory.Float64`
    f10: float = None
    f11: pyfory.Array[pyfory.Int16] = None
    f12: List[pyfory.Int16] = None


if __name__ == "__main__":
    f = pyfory.Fory(xlang=True)
    f.register_type(SomeClass1, name="example.SomeClass1")
    f.register_type(SomeClass2, name="example.SomeClass2")
    obj1 = SomeClass1(f1=True, f2={-1: 2})
    obj = SomeClass2(
        f1=obj1,
        f2="abc",
        f3=["abc", "abc"],
        f4={1: 2},
        f5=2 ** 7 - 1,
        f6=2 ** 15 - 1,
        f7=2 ** 31 - 1,
        f8=2 ** 63 - 1,
        f9=1.0 / 2,
        f10=1 / 3.0,
        f11=array.array("h", [1, 2]),
        f12=[-1, 4],
    )
    data = f.serialize(obj)
    # bytes can be deserialized by other languages
    print(f.deserialize(data))
```

### Shared and circular references

```python
from typing import Dict
import pyfory

class SomeClass:
    f1: "SomeClass"
    f2: Dict[str, str]
    f3: Dict[str, str]

fory = pyfory.Fory(xlang=True, ref=True)
fory.register_type(SomeClass, name="example.SomeClass")
obj = SomeClass()
obj.f2 = {"k1": "v1", "k2": "v2"}
obj.f1, obj.f3 = obj, obj.f2
data = fory.serialize(obj)
# bytes can be deserialized by other languages
print(fory.deserialize(data))
```

## Related Topics

- [Configuration](configuration.md) - Fory parameters
- [Type Registration](type-registration.md) - Registration patterns
- [Native Serialization](native.md) - Functions and lambdas
- [Out-of-Band Serialization](out-of-band.md) - Buffer callback APIs
