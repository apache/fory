---
title: Basic Serialization
sidebar_position: 1
id: basic_serialization
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

This page covers basic object graph serialization and supported types.

## Object Graph Serialization

Apache Fory™ provides automatic serialization of complex object graphs, preserving the structure and relationships between objects. The `#[derive(ForyStruct)]` macro generates efficient serialization code at compile time, eliminating runtime overhead.

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

let mut fory = Fory::default();
fory.register::<Address>(100);
fory.register::<Person>(200);

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

let bytes = fory.serialize(&person);
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

`Vec<BFloat16>` is the dense xlang carrier for `bfloat16_array` payloads.

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

| Rust Type               | Description                |
| ----------------------- | -------------------------- |
| `chrono::NaiveDate`     | Date without timezone      |
| `chrono::NaiveDateTime` | Timestamp without timezone |

### Custom Types

| Macro                   | Description                |
| ----------------------- | -------------------------- |
| `#[derive(ForyStruct)]` | Object graph serialization |
| `#[derive(ForyRow)]`    | Row-based serialization    |

## Serialization APIs

```rust
use fory::{Fory, Reader};

let mut fory = Fory::default();
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

## Performance Tips

- **Zero-Copy Deserialization**: Row format enables direct memory access without copying
- **Buffer Pre-allocation**: Minimizes memory allocations during serialization
- **Compact Encoding**: Variable-length encoding for space efficiency
- **Little-Endian**: Optimized for modern CPU architectures
- **Reference Deduplication**: Shared objects serialized only once

## Related Topics

- [Type Registration](type-registration.md) - Registering types
- [References](references.md) - Shared and circular references
- [Custom Serializers](custom-serializers.md) - Manual serialization
