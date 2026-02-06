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
let mut fory = Fory::default()
    .compatible(true)
    .xlang(true);

// Register types with consistent IDs across languages
fory.register::<MyStruct>(100);

// Or use namespace-based registration
fory.register_by_namespace::<MyStruct>("com.example", "MyStruct");
```

## Type Registration for Cross-Language

### Register by ID

For fast, compact serialization with consistent IDs across languages:

```rust
let mut fory = Fory::default()
    .compatible(true)
    .xlang(true);

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
use fory::ForyObject;

#[derive(ForyObject)]
struct Person {
    name: String,
    age: i32,
}

let mut fory = Fory::default()
    .compatible(true)
    .xlang(true);

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
    .withLanguage(Language.XLANG)
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
    age: pyfory.int32

fory = pyfory.Fory(xlang=True, ref=True)
fory.register_type(Person, type_id=100)  # Same ID as Rust

person = fory.deserialize(bytes_from_rust)
```

## Type Mapping

See [xlang_type_mapping.md](../../specification/xlang_type_mapping.md) for complete type mapping across languages.

### Common Type Mappings

| Rust           | Java         | Python        |
| -------------- | ------------ | ------------- |
| `i32`          | `int`        | `int32`       |
| `i64`          | `long`       | `int64`       |
| `f32`          | `float`      | `float32`     |
| `f64`          | `double`     | `float64`     |
| `String`       | `String`     | `str`         |
| `Vec<T>`       | `List<T>`    | `List[T]`     |
| `HashMap<K,V>` | `Map<K,V>`   | `Dict[K,V]`   |
| `Option<T>`    | nullable `T` | `Optional[T]` |

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
