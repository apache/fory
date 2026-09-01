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

This page covers basic object graph serialization and supported types in the default xlang mode for Fory Rust.

## Object Graph Serialization

Apache Fory™ provides automatic serialization of complex object graphs, preserving the structure and relationships between objects. The `#[derive(ForyStruct)]` macro generates efficient serialization code at compile time, eliminating reflection overhead.

**Key capabilities:**

- Nested struct serialization with arbitrary depth
- Collection types (Vec, HashMap, HashSet, BTreeMap)
- Optional fields with `Option<T>`
- Automatic handling of primitive types and strings
- Efficient binary encoding with variable-length integers

```rust
use fory::{Fory, Error};
use fory::ForyStruct;
use std::collections::HashMap;

#[derive(ForyStruct, Debug, PartialEq)]
struct Person {
    name: String,
    age: i32,
    address: Address,
    hobbies: Vec<String>,
    metadata: HashMap<String, String>,
}

#[derive(ForyStruct, Debug, PartialEq)]
struct Address {
    street: String,
    city: String,
    country: String,
}

let mut fory = Fory::builder().xlang(true).build();
fory.register_by_name::<Address>("example.Address").unwrap();
fory.register_by_name::<Person>("example.Person").unwrap();

let person = Person {
    name: "John Doe".to_string(),
    age: 30,
    address: Address {
        street: "123 Main St".to_string(),
        city: "New York".to_string(),
        country: "USA".to_string(),
    },
    hobbies: vec!["reading".to_string(), "coding".to_string()],
    metadata: HashMap::from([
        ("role".to_string(), "developer".to_string()),
    ]),
};

let bytes = fory.serialize(&person).unwrap();
let decoded: Person = fory.deserialize(&bytes)?;
assert_eq!(person, decoded);
```

## Supported Types

### Primitive Types

| Rust Type                 | Description                 |
| ------------------------- | --------------------------- |
| `bool`                    | Boolean                     |
| `i8`, `i16`, `i32`, `i64` | Signed integers             |
| `f32`, `f64`              | Floating point              |
| `BFloat16`                | 16-bit brain floating point |
| `String`                  | UTF-8 string                |

### Collections

| Rust Type        | Description        |
| ---------------- | ------------------ |
| `Vec<T>`         | Dynamic array      |
| `VecDeque<T>`    | Double-ended queue |
| `LinkedList<T>`  | Doubly-linked list |
| `HashMap<K, V>`  | Hash map           |
| `BTreeMap<K, V>` | Ordered map        |
| `HashSet<T>`     | Hash set           |
| `BTreeSet<T>`    | Ordered set        |
| `BinaryHeap<T>`  | Binary heap        |
| `Option<T>`      | Optional value     |

`Vec<BFloat16>` is the dense carrier when the schema is `array<bfloat16>`.

### Smart Pointers

| Rust Type    | Description                                          |
| ------------ | ---------------------------------------------------- |
| `Box<T>`     | Heap allocation                                      |
| `Rc<T>`      | Reference counting (shared refs tracked)             |
| `Arc<T>`     | Thread-safe reference counting (shared refs tracked) |
| `RcWeak<T>`  | Weak reference to `Rc<T>` (breaks circular refs)     |
| `ArcWeak<T>` | Weak reference to `Arc<T>` (breaks circular refs)    |
| `RefCell<T>` | Interior mutability (runtime borrow checking)        |
| `Mutex<T>`   | Thread-safe interior mutability                      |

### Date and Time

| Rust Type   | Description                                             |
| ----------- | ------------------------------------------------------- |
| `Date`      | Date without timezone, stored as epoch days             |
| `Timestamp` | Point in time, stored as epoch seconds and nanos        |
| `Duration`  | Signed duration, stored as seconds and normalized nanos |

The built-in carriers expose dependency-free constructors, accessors, conversions, and checked
arithmetic:

```rust
use fory::{Date, Duration, Timestamp};

let date = Date::from_epoch_days(19_782);
assert_eq!(date.checked_add_days(1)?.epoch_days(), 19_783);

let timestamp = Timestamp::from_epoch_millis(-1);
assert_eq!(timestamp.to_epoch_millis()?, -1);

let duration = Duration::from_parts(1, 1_500_000_000)?;
assert_eq!(duration.to_millis()?, 2_500);
let later = timestamp.checked_add_duration(duration)?;
```

`chrono::NaiveDate`, `chrono::NaiveDateTime`, and `chrono::Duration` are supported when the Rust
`chrono` feature is enabled:

```toml
[dependencies]
fory = { version = "1.7.1", features = ["chrono"] }
```

### Custom Types

Use `#[derive(ForyStruct)]` for object graph serialization. The separate
[Rust Row Format guide](../../row-format/rust.md) documents `#[derive(ForyRow)]` and its supported
type set.

## Serialization APIs

```rust
use fory::{Fory, Reader};

let mut fory = Fory::builder().xlang(true).build();
fory.register::<MyStruct>(1)?;

let obj = MyStruct { /* ... */ };

// Basic serialize/deserialize
let bytes = fory.serialize(&obj)?;
let decoded: MyStruct = fory.deserialize(&bytes)?;

// Serialize to existing buffer
let mut buf: Vec<u8> = vec![];
fory.serialize_to(&mut buf, &obj)?;

// Deserialize from reader
let mut reader = Reader::new(&buf);
let decoded: MyStruct = fory.deserialize_from(&mut reader)?;
```

When the Rust value type uses an external structural serializer or custom
serializer, select it explicitly at the root:

```rust
let bytes = fory.serialize_with::<UserSerializer>(&user)?;
let decoded: third_party::User =
    fory.deserialize_with::<UserSerializer>(&bytes)?;
```

Carrier serializers compose the same selection for a root container:

```rust
use fory::VecSerializer;

let bytes =
    fory.serialize_with::<VecSerializer<UserSerializer>>(&users)?;
let decoded: Vec<third_party::User> =
    fory.deserialize_with::<VecSerializer<UserSerializer>>(&bytes)?;
```

See [External-Type Serialization](external-types.md) for field annotations,
all supported carriers, and registration.

## Performance Tips

- **Buffer Pre-allocation**: Minimizes memory allocations during serialization
- **Compact Encoding**: Variable-length encoding for space efficiency
- **Little-Endian**: Optimized for modern CPU architectures
- **Reference Deduplication**: Shared objects serialized only once

## Cross-Language Interoperability

The default xlang format is shared by all supported Fory implementations. The following sections cover its cross-language type mapping, type identity, and interoperability requirements.

Apache Fory™ supports seamless data exchange across Java, Python, C++, Go,
Rust, JavaScript/TypeScript, C#, Swift, Dart, Scala, and Kotlin.

### Xlang Configuration

Rust defaults to xlang mode with compatible schema evolution. Set the mode explicitly in xlang examples:

```rust
use fory::Fory;

// Use xlang mode
let mut fory = Fory::builder().xlang(true).build();

// Register types with consistent IDs across languages
fory.register::<MyStruct>(100)?;

// Or, on a different Fory instance, use name-based registration
// fory.register_by_name::<MyStruct>("com.example.MyStruct")?;
```

### Type Registration for Xlang

#### Register by ID

For fast, compact serialization with consistent IDs across languages:

```rust
let mut fory = Fory::builder().xlang(true).build();

fory.register::<User>(100)?;  // Same ID in Java, Python, etc.
```

#### Register by Name

For more flexible type naming:

```rust
fory.register_by_name::<User>("com.example.User")?;
```

### Xlang Example

#### Rust (Serializer)

```rust
use fory::Fory;
use fory::ForyStruct;

#[derive(ForyStruct)]
struct Person {
    name: String,
    age: i32,
}

let mut fory = Fory::builder().xlang(true).build();

fory.register::<Person>(100)?;

let person = Person {
    name: "Alice".to_string(),
    age: 30,
};

let bytes = fory.serialize(&person)?;
// bytes can be deserialized by Java, Python, etc.
```

#### Third-Party Rust Types

An external structural serializer gives a third-party Rust type the same xlang
schema as an equivalent local derive:

```rust
#[derive(ForyStruct)]
#[fory(target = third_party::User)]
struct UserSerializer {
    name: String,
    age: u32,
}

let mut fory = Fory::builder().xlang(true).build();
fory.register::<UserSerializer>(100)?;

let bytes = fory.serialize_with::<UserSerializer>(&user)?;
```

Container roots compose with carrier serializers and keep the ordinary xlang
LIST, MAP, tuple, or array representation:

```rust
use fory::VecSerializer;

let bytes =
    fory.serialize_with::<VecSerializer<UserSerializer>>(&users)?;
```

Only xlang-representable schemas are accepted. A native Rust enum variant with
multiple tuple or named fields is supported with `xlang(false)`, but its
serializer registration is rejected in xlang mode. See
[External-Type Serialization](external-types.md).

#### Dynamic Rust Carriers

`Box<dyn Any>`, `Rc<dyn Any>`, `Arc<dyn Any + Send + Sync>`, and application
`dyn Trait` carriers can be used in xlang mode when every selected concrete
target has an xlang-compatible structural or EXT identity. Fory writes the
concrete registered target identity; the Rust trait or erased-carrier identity
does not appear on the wire.

#### Java (Deserializer)

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

#### Python (Deserializer)

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

### Type Mapping

See [xlang_type_mapping.md](../../specification/xlang_type_mapping.md) for complete type mapping across languages.

#### Common Type Mappings

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

#### Lists and Dense Arrays

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

### Interoperability Best Practices

1. **Use consistent type IDs** across all languages
2. **Keep compatible mode** for schema evolution
3. **Register all types** before serialization
4. **Test cross-language** compatibility during development

### Specifications and References

- [Xlang Serialization Specification](../../specification/xlang_serialization_spec.md)
- [Type Mapping Reference](../../specification/xlang_type_mapping.md)
- [Java Interoperability Guide](../java/basic-serialization.md#cross-language-interoperability)
- [Python Interoperability Guide](../python/basic-serialization.md#cross-language-interoperability)

### Related Guides

- [Configuration](configuration.md) - xlang mode configuration
- [Schema Evolution](schema-evolution.md) - Compatible mode
- [Type Registration](type-registration.md) - Registration methods
- [External-Type Serialization](external-types.md) - Third-party values in xlang mode

### Built-in values

```rust
use fory::Fory;

fn run() {
    let fory = Fory::builder().xlang(true).build();
    let bin = fory.serialize(&"hello".to_string()).expect("serialize success");
    let obj: String = fory.deserialize(&bin).expect("deserialize success");
    assert_eq!("hello".to_string(), obj);
}
```

### Custom values

```rust
use chrono::{NaiveDate, NaiveDateTime};
use fory::{Fory, ForyStruct};
use std::collections::HashMap;

#[test]
fn complex_struct() {
    #[derive(ForyStruct, Debug, PartialEq)]
    struct Animal {
        category: String,
    }

    #[derive(ForyStruct, Debug, PartialEq)]
    struct Person {
        c1: Vec<u8>,  // binary
        c2: Vec<i16>, // primitive array
        animal: Vec<Animal>,
        c3: Vec<Vec<u8>>,
        name: String,
        c4: HashMap<String, String>,
        age: u16,
        op: Option<String>,
        op2: Option<String>,
        date: NaiveDate,
        time: NaiveDateTime,
        c5: f32,
        c6: f64,
    }
    let person: Person = Person {
        c1: vec![1, 2, 3],
        c2: vec![5, 6, 7],
        c3: vec![vec![1, 2], vec![1, 3]],
        animal: vec![Animal {
            category: "Dog".to_string(),
        }],
        c4: HashMap::from([
            ("hello1".to_string(), "hello2".to_string()),
            ("hello2".to_string(), "hello3".to_string()),
        ]),
        age: 12,
        name: "helo".to_string(),
        op: Some("option".to_string()),
        op2: None,
        date: NaiveDate::from_ymd_opt(2025, 12, 12).unwrap(),
        time: NaiveDateTime::from_timestamp_opt(1689912359, 0).unwrap(),
        c5: 2.0,
        c6: 4.0,
    };

    let mut fory = Fory::builder().xlang(true).build();
    fory
        .register_by_name::<Animal>("example.foo2")
        .expect("register Animal");
    fory
        .register_by_name::<Person>("example.foo")
        .expect("register Person");
    let bin = fory.serialize(&person).expect("serialize success");
    let obj: Person = fory.deserialize(&bin).expect("deserialize success");
    assert_eq!(person, obj);
}
```

### Shared and circular references

Circular references cannot be implemented in Rust due to ownership restrictions.

## Related Topics

- [Type Registration](type-registration.md) - Registering types
- [References](references.md) - Shared and circular references
- [Custom Serializers](custom-serializers.md) - Custom serialization
- [External-Type Serialization](external-types.md) - Third-party values and carrier roots
- [Row Format](../../row-format/rust.md) - Standard Row Format and zero-copy borrowed views
