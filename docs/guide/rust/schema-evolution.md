---
title: Schema Evolution
sidebar_position: 9
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

Compatible mode is enabled by default:

```rust
use fory::Fory;
use fory::ForyStruct;
use std::collections::HashMap;

#[derive(ForyStruct, Debug)]
struct PersonV1 {
    name: String,
    age: i32,
    address: String,
}

#[derive(ForyStruct, Debug)]
struct PersonV2 {
    name: String,
    age: i32,
    // address removed
    // phone added
    phone: Option<String>,
    metadata: HashMap<String, String>,
}

let mut fory1 = Fory::builder().xlang(false).build();
fory1.register::<PersonV1>(1)?;

let mut fory2 = Fory::builder().xlang(false).build();
fory2.register::<PersonV2>(1)?;

let person_v1 = PersonV1 {
    name: "Alice".to_string(),
    age: 30,
    address: "123 Main St".to_string(),
};

// Serialize with V1
let bytes = fory1.serialize(&person_v1)?;

// Deserialize with V2 - missing fields get default values
let person_v2: PersonV2 = fory2.deserialize(&bytes)?;
assert_eq!(person_v2.name, "Alice");
assert_eq!(person_v2.age, 30);
assert_eq!(person_v2.phone, None);
```

## Schema Evolution Features

- Add new fields with default values
- Remove obsolete fields (skipped during deserialization)
- Change field nullability (`T` ↔ `Option<T>`)
- Reorder fields (matched by name, not position)
- Change selected scalar field types when the value converts without precision or range loss
- Type-safe fallback to default values for missing fields

Compatible readers can handle selected scalar field type changes when the value is lossless. A
matched field can read between `bool`, `String`, numeric scalars, and decimal fields when the
converted value has the same logical value. For example, `"true"` and `"false"` can be read as
booleans, `"123"` can be read as a numeric field that can hold `123`, numbers and decimals can be
read as canonical strings, and numeric widening or narrowing succeeds only when no precision or range
is lost. Numeric strings use finite ASCII decimal syntax. Optional fields still compose with these
conversions, but reference-tracked scalar type changes are incompatible. Invalid strings and lossy
conversions fail during deserialization.

## Compatibility Rules

- Field names must match (case-sensitive)
- Type changes are supported only for nullable/non-nullable changes and selected lossless scalar
  conversions
- Nested struct types must be registered on both sides

## Same-Schema Optimization

Use `.compatible(false)` only when the schema used to deserialize every payload is always the same as the schema used to serialize it, and you want faster serialization and smaller size. For xlang payloads, use `.compatible(false)` only after verifying that every language uses the same schema, or when native types are generated from Fory schema IDL.

```rust
let mut fory = Fory::builder()
    .xlang(false)
    .compatible(false)
    .build();
```

For one struct, you can opt out of evolution metadata with `#[fory(evolving = false)]`:

```rust
use fory::ForyStruct;

#[derive(ForyStruct)]
#[fory(evolving = false)]
struct SameSchemaMessage {
    id: i32,
}
```

## Enum Support

Apache Fory™ supports three types of enum variants with full schema evolution in Compatible mode:

**Variant Types:**

- **Unit**: C-style enums (`Status::Active`)
- **Unnamed**: Tuple-like variants (`Message::Pair(String, i32)`)
- **Named**: Struct-like variants (`Event::Click { x: i32, y: i32 }`)

```rust
use fory::{Fory, ForyUnion};

#[derive(ForyUnion, Debug, PartialEq)]
enum Value {
    #[fory(default)]
    Null,
    Bool(bool),
    Number(f64),
    Text(String),
    Object { name: String, value: i32 },
}

let mut fory = Fory::builder().xlang(false).build();
fory.register::<Value>(1)?;

let value = Value::Object { name: "score".to_string(), value: 100 };
let bytes = fory.serialize(&value)?;
let decoded: Value = fory.deserialize(&bytes)?;
assert_eq!(value, decoded);
```

For typed ADT unions whose cases are unit or single-payload variants, add an
`#[fory(unknown)] Unknown(::fory::UnknownCase)` variant when you need to
preserve future payload variants. Do not make the unknown variant the default;
keep a real schema case marked `#[fory(default)]`. Register future payload types
locally before deserializing unknown cases you need to preserve.

`UnknownCase` stores its payload as `Arc<dyn Any + Send + Sync>`, so preserved
payload types must satisfy `Send + Sync`. Direct generic containers are not
supported as erased `Any` payloads; wrap the container in a registered derived
type if an unknown case needs to preserve it.

### Enum Schema Evolution

Compatible mode enables robust schema evolution with variant type encoding (2 bits):

- `0b0` = Unit, `0b1` = Unnamed, `0b10` = Named

```rust
use fory::{Fory, ForyUnion};

// Old version
#[derive(ForyUnion)]
enum OldEvent {
    #[fory(default)]
    Click { x: i32, y: i32 },
    Scroll { delta: f64 },
}

// New version - added field and variant
#[derive(ForyUnion)]
enum NewEvent {
    #[fory(default)]
    Unknown,
    Click { x: i32, y: i32, timestamp: u64 },  // Added field
    Scroll { delta: f64 },
    KeyPress(String),  // New variant
}

let mut fory = Fory::builder().xlang(false).build();

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

- Always mark exactly one union variant with `#[fory(default)]`
- Named variants provide better evolution than unnamed
- Use compatible mode for cross-version communication

## Tuple Support

Apache Fory™ supports tuples up to 22 elements out of the box with efficient serialization in both compatible mode and the same-schema optimization.

**Features:**

- Automatic serialization for tuples from 1 to 22 elements
- Heterogeneous type support (each element can be a different type)
- Schema evolution in Compatible mode (handles missing/extra elements)

**Schema modes:**

1. **Same-schema optimization**: Serializes elements sequentially without collection headers for minimal overhead
2. **Compatible mode**: Uses collection protocol with type metadata for schema evolution

```rust
use fory::{Fory, Error};

let mut fory = Fory::builder().xlang(false).build();

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

- [Configuration](configuration.md) - Compatible mode settings
- [Polymorphism](polymorphism.md) - Trait objects with schema evolution
- [Xlang Serialization](xlang-serialization.md) - Schema evolution across languages
