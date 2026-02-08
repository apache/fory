---
title: Schema Evolution
sidebar_position: 7
id: schema_evolution
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

Apache Fory™ supports schema evolution in **Compatible mode**, allowing serialization and deserialization peers to have different type definitions.

## Compatible Mode

Enable schema evolution with `compatible(true)`:

```rust
use fory::Fory;
use fory::ForyObject;
use std::collections::HashMap;

#[derive(ForyObject, Debug)]
struct PersonV1 {
    name: String,
    age: i32,
    address: String,
}

#[derive(ForyObject, Debug)]
struct PersonV2 {
    name: String,
    age: i32,
    // address removed
    // phone added
    phone: Option<String>,
    metadata: HashMap<String, String>,
}

let mut fory1 = Fory::default().compatible(true);
fory1.register::<PersonV1>(1);

let mut fory2 = Fory::default().compatible(true);
fory2.register::<PersonV2>(1);

let person_v1 = PersonV1 {
    name: "Alice".to_string(),
    age: 30,
    address: "123 Main St".to_string(),
};

// Serialize with V1
let bytes = fory1.serialize(&person_v1);

// Deserialize with V2 - missing fields get default values
let person_v2: PersonV2 = fory2.deserialize(&bytes)?;
assert_eq!(person_v2.name, "Alice");
assert_eq!(person_v2.age, 30);
assert_eq!(person_v2.phone, None);
```

### Disable Evolution for Stable Structs

If a struct schema is stable and will not change, you can disable evolution for that struct to avoid compatible metadata overhead. Use `#[fory(evolving = false)]`:

```rust
use fory::ForyObject;

#[derive(ForyObject)]
#[fory(evolving = false)]
struct StableMessage {
    id: i32,
}
```

## Schema Evolution Features

- Add new fields with default values
- Remove obsolete fields (skipped during deserialization)
- Change field nullability (`T` ↔ `Option<T>`)
- Reorder fields (matched by name, not position)
- Type-safe fallback to default values for missing fields

## Compatibility Rules

- Field names must match (case-sensitive)
- Type changes are not supported (except nullable/non-nullable)
- Nested struct types must be registered on both sides

## Enum Support

Apache Fory™ supports three types of enum variants with full schema evolution in Compatible mode:

**Variant Types:**

- **Unit**: C-style enums (`Status::Active`)
- **Unnamed**: Tuple-like variants (`Message::Pair(String, i32)`)
- **Named**: Struct-like variants (`Event::Click { x: i32, y: i32 }`)

```rust
use fory::{Fory, ForyObject};

#[derive(Default, ForyObject, Debug, PartialEq)]
enum Value {
    #[default]
    Null,
    Bool(bool),
    Number(f64),
    Text(String),
    Object { name: String, value: i32 },
}

let mut fory = Fory::default();
fory.register::<Value>(1)?;

let value = Value::Object { name: "score".to_string(), value: 100 };
let bytes = fory.serialize(&value)?;
let decoded: Value = fory.deserialize(&bytes)?;
assert_eq!(value, decoded);
```

### Enum Schema Evolution

Compatible mode enables robust schema evolution with variant type encoding (2 bits):

- `0b0` = Unit, `0b1` = Unnamed, `0b10` = Named

```rust
use fory::{Fory, ForyObject};

// Old version
#[derive(ForyObject)]
enum OldEvent {
    Click { x: i32, y: i32 },
    Scroll { delta: f64 },
}

// New version - added field and variant
#[derive(Default, ForyObject)]
enum NewEvent {
    #[default]
    Unknown,
    Click { x: i32, y: i32, timestamp: u64 },  // Added field
    Scroll { delta: f64 },
    KeyPress(String),  // New variant
}

let mut fory = Fory::default().compatible(true);

// Serialize with old schema
let old_bytes = fory.serialize(&OldEvent::Click { x: 100, y: 200 })?;

// Deserialize with new schema - timestamp gets default value (0)
let new_event: NewEvent = fory.deserialize(&old_bytes)?;
assert!(matches!(new_event, NewEvent::Click { x: 100, y: 200, timestamp: 0 }));
```

**Evolution capabilities:**

- **Unknown variants** → Falls back to default variant
- **Named variant fields** → Add/remove fields (missing fields use defaults)
- **Unnamed variant elements** → Add/remove elements (extras skipped, missing use defaults)
- **Variant type mismatches** → Automatically uses default value for current variant

**Best practices:**

- Always mark a default variant with `#[default]`
- Named variants provide better evolution than unnamed
- Use compatible mode for cross-version communication

## Tuple Support

Apache Fory™ supports tuples up to 22 elements out of the box with efficient serialization in both compatible and non-compatible modes.

**Features:**

- Automatic serialization for tuples from 1 to 22 elements
- Heterogeneous type support (each element can be a different type)
- Schema evolution in Compatible mode (handles missing/extra elements)

**Serialization modes:**

1. **Non-compatible mode**: Serializes elements sequentially without collection headers for minimal overhead
2. **Compatible mode**: Uses collection protocol with type metadata for schema evolution

```rust
use fory::{Fory, Error};

let mut fory = Fory::default();

// Tuple with heterogeneous types
let data: (i32, String, bool, Vec<i32>) = (
    42,
    "hello".to_string(),
    true,
    vec![1, 2, 3],
);

let bytes = fory.serialize(&data)?;
let decoded: (i32, String, bool, Vec<i32>) = fory.deserialize(&bytes)?;
assert_eq!(data, decoded);
```

## Related Topics

- [Configuration](configuration.md) - Enabling compatible mode
- [Polymorphism](polymorphism.md) - Trait objects with schema evolution
- [Cross-Language](cross-language.md) - Schema evolution across languages
