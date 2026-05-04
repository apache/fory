---
title: Cross-Language Serialization
sidebar_position: 8
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

Apache Fory™ supports seamless data exchange across multiple languages including Java, Python, C++, Go, and JavaScript.

## Enable Cross-Language Mode

```rust
use fory::Fory;

// Enable cross-language mode
let mut fory = Fory::builder()
    .compatible(true)
    .xlang(true).build();

// Register types with consistent IDs across languages
fory.register::<MyStruct>(100);

// Or use namespace-based registration
fory.register_by_namespace::<MyStruct>("com.example", "MyStruct");
```

## Type Registration for Cross-Language

### Register by ID

For fast, compact serialization with consistent IDs across languages:

```rust
let mut fory = Fory::builder()
    .compatible(true)
    .xlang(true).build();

fory.register::<User>(100);  // Same ID in Java, Python, etc.
```

### Register by Namespace

For more flexible type naming:

```rust
fory.register_by_namespace::<User>("com.example", "User");
```

## Cross-Language Example

### Rust (Serializer)

```rust
use fory::Fory;
use fory::ForyStruct;

#[derive(ForyStruct)]
struct Person {
    name: String,
    age: i32,
}

let mut fory = Fory::builder()
    .compatible(true)
    .xlang(true).build();

fory.register::<Person>(100);

let person = Person {
    name: "Alice".to_string(),
    age: 30,
};

let bytes = fory.serialize(&person)?;
// bytes can be deserialized by Java, Python, etc.
```

### Java (Deserializer)

```java
import org.apache.fory.*;
import org.apache.fory.config.*;

public class Person {
    public String name;
    public int age;
}

Fory fory = Fory.builder()
    .withXlang(true)
    .withRefTracking(true)
    .build();

fory.register(Person.class, 100);  // Same ID as Rust

Person person = (Person) fory.deserialize(bytesFromRust);
```

### Python (Deserializer)

```python
import pyfory
from dataclasses import dataclass

@dataclass
class Person:
    name: str
    age: pyfory.Int32

fory = pyfory.Fory(xlang=True, ref=True)
fory.register_type(Person, type_id=100)  # Same ID as Rust

person = fory.deserialize(bytes_from_rust)
```

## Type Mapping

See [xlang_type_mapping.md](../../specification/xlang_type_mapping.md) for complete type mapping across languages.

### Common Type Mappings

| Rust            | Java           | Python          |
| --------------- | -------------- | --------------- |
| `i32`           | `int`          | `int32`         |
| `i64`           | `long`         | `int64`         |
| `f32`           | `float`        | `float32`       |
| `f64`           | `double`       | `float64`       |
| `Float16`       | `Float16`      | `float16`       |
| `BFloat16`      | `BFloat16`     | `bfloat16`      |
| `String`        | `String`       | `str`           |
| `Vec<T>`        | `List<T>`      | `List[T]`       |
| `Vec<Float16>`  | `Float16List`  | `Float16Array`  |
| `Vec<BFloat16>` | `BFloat16List` | `BFloat16Array` |
| `[Float16; N]`  | `Float16List`  | `Float16Array`  |
| `[BFloat16; N]` | `BFloat16List` | `BFloat16Array` |
| `HashMap<K,V>`  | `Map<K,V>`     | `Dict[K,V]`     |
| `Option<T>`     | nullable `T`   | `Optional[T]`   |

### Lists and Dense Arrays

Rust `Vec<T>` maps to Fory `list<T>` by default for manual structs. Use an
explicit array field attribute when the schema is dense `array<T>`.

| Fory schema       | Rust carrier and metadata      |
| ----------------- | ------------------------------ |
| `list<int32>`     | `Vec<i32>`                     |
| `array<bool>`     | `#[fory(array)] Vec<bool>`     |
| `array<int8>`     | `#[fory(array)] Vec<i8>`       |
| `array<int16>`    | `#[fory(array)] Vec<i16>`      |
| `array<int32>`    | `#[fory(array)] Vec<i32>`      |
| `array<int64>`    | `#[fory(array)] Vec<i64>`      |
| `array<uint8>`    | `#[fory(array)] Vec<u8>`       |
| `array<uint16>`   | `#[fory(array)] Vec<u16>`      |
| `array<uint32>`   | `#[fory(array)] Vec<u32>`      |
| `array<uint64>`   | `#[fory(array)] Vec<u64>`      |
| `array<float16>`  | `#[fory(array)] Vec<Float16>`  |
| `array<bfloat16>` | `#[fory(array)] Vec<BFloat16>` |
| `array<float32>`  | `#[fory(array)] Vec<f32>`      |
| `array<float64>`  | `#[fory(array)] Vec<f64>`      |

## Best Practices

1. **Use consistent type IDs** across all languages
2. **Enable compatible mode** for schema evolution
3. **Register all types** before serialization
4. **Test cross-language** compatibility during development

## See Also

- [Cross-Language Serialization Specification](../../specification/xlang_serialization_spec.md)
- [Type Mapping Reference](../../specification/xlang_type_mapping.md)
- [Java Cross-Language Guide](../java/cross-language.md)
- [Python Cross-Language Guide](../python/cross-language.md)

## Related Topics

- [Configuration](configuration.md) - XLANG mode configuration
- [Schema Evolution](schema-evolution.md) - Compatible mode
- [Type Registration](type-registration.md) - Registration methods
