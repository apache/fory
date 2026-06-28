---
title: Trait Object Serialization
sidebar_position: 8
id: polymorphism
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

Apache Fory™ supports polymorphic serialization through trait objects, enabling dynamic dispatch and type flexibility.

## Supported Trait Object Types

- `Box<dyn Trait>` - Owned trait objects
- `Rc<dyn Trait>` - Reference-counted trait objects
- `Arc<dyn Trait>` - Thread-safe reference-counted trait objects
- `Vec<Box<dyn Trait>>`, `HashMap<K, Box<dyn Trait>>` - Collections of trait objects

## Basic Trait Object Serialization

```rust
use fory::{Fory, register_trait_type};
use fory::Serializer;
use fory::ForyStruct;

trait Animal: Serializer {
    fn speak(&self) -> String;
    fn name(&self) -> &str;
}

#[derive(ForyStruct)]
struct Dog { name: String, breed: String }

impl Animal for Dog {
    fn speak(&self) -> String { "Woof!".to_string() }
    fn name(&self) -> &str { &self.name }
}

#[derive(ForyStruct)]
struct Cat { name: String, color: String }

impl Animal for Cat {
    fn speak(&self) -> String { "Meow!".to_string() }
    fn name(&self) -> &str { &self.name }
}

// Register trait implementations
register_trait_type!(Animal, Dog, Cat);

#[derive(ForyStruct)]
struct Zoo {
    star_animal: Box<dyn Animal>,
}

let mut fory = Fory::builder().xlang(false).build();
fory.register::<Dog>(100)?;
fory.register::<Cat>(101)?;
fory.register::<Zoo>(102)?;

let zoo = Zoo {
    star_animal: Box::new(Dog {
        name: "Buddy".to_string(),
        breed: "Labrador".to_string(),
    }),
};

let bytes = fory.serialize(&zoo)?;
let decoded: Zoo = fory.deserialize(&bytes)?;

assert_eq!(decoded.star_animal.name(), "Buddy");
assert_eq!(decoded.star_animal.speak(), "Woof!");
```

## Serializing dyn Any Trait Objects

Apache Fory™ supports serializing `Box<dyn Any>`, `Rc<dyn Any>`, and
`Arc<dyn Any + Send + Sync>` for dynamic type dispatch:

**Key points:**

- Works with registered concrete non-container types that implement `Serializer`
- Requires downcasting after deserialization to access the concrete type
- Type information is preserved during serialization
- Useful for plugin systems and dynamic type handling

```rust
use std::rc::Rc;
use std::any::Any;

let dog_any: Rc<dyn Any> = Rc::new(Dog {
    name: "Rex".to_string(),
    breed: "Golden".to_string()
});

// Serialize the Any wrapper
let bytes = fory.serialize(&dog_any)?;
let decoded: Rc<dyn Any> = fory.deserialize(&bytes)?;

// Downcast back to the concrete type
let unwrapped = decoded.downcast_ref::<Dog>().unwrap();
assert_eq!(unwrapped.name, "Rex");
```

For thread-safe scenarios, use `Arc<dyn Any + Send + Sync>`:

```rust
use std::sync::Arc;
use std::any::Any;

let dog_any: Arc<dyn Any + Send + Sync> = Arc::new(Dog {
    name: "Buddy".to_string(),
    breed: "Labrador".to_string()
});

let bytes = fory.serialize(&dog_any)?;
let decoded: Arc<dyn Any + Send + Sync> = fory.deserialize(&bytes)?;

// Downcast to concrete type
let unwrapped = decoded.downcast_ref::<Dog>().unwrap();
assert_eq!(unwrapped.name, "Buddy");
```

`Box<dyn Any>`, `Rc<dyn Any>`, and `Arc<dyn Any + Send + Sync>` are supported
erased `Any` carriers for registered concrete non-container payloads.
Use `Arc<dyn Any + Send + Sync>` when the erased payload must be shareable
across threads; the concrete payload type must also satisfy `Send + Sync`.
Registered structs, enums, and unions that satisfy those bounds can be used as
the erased payload.

The unsupported case is a generic container used directly as the top-level
erased payload. This applies to all erased `Any` carriers: `Box<dyn Any>`,
`Rc<dyn Any>`, and `Arc<dyn Any + Send + Sync>`. Unsupported direct payloads
include list-, map-, and set-like containers such as `Vec<T>`, `Vec<u8>`,
`HashMap<K, V>`, `HashSet<T>`, and `LinkedList<T>`.

If you need to put a container in an erased `Any` payload, wrap it in a
registered struct, enum, or union and use that wrapper as the erased payload:

```rust
use fory::{Fory, ForyStruct};
use std::any::Any;
use std::sync::Arc;

#[derive(ForyStruct)]
struct IntList {
    values: Vec<i32>,
}

let mut fory = Fory::builder().xlang(false).build();
fory.register::<IntList>(100)?;

let value: Arc<dyn Any + Send + Sync> = Arc::new(IntList {
    values: vec![1, 2, 3],
});
let bytes = fory.serialize(&value)?;
let decoded: Arc<dyn Any + Send + Sync> = fory.deserialize(&bytes)?;
```

The wrapper makes the erased payload a concrete registered type while the
container remains a normal typed field. The same wrapper model is the supported
path for `Box<dyn Any>` and `Rc<dyn Any>`.

## Rc/Arc-Based Trait Objects in Structs

For fields with `Rc<dyn Trait>` or `Arc<dyn Trait>`, Fory automatically handles the conversion:

```rust
use std::sync::Arc;
use std::rc::Rc;
use std::collections::HashMap;

#[derive(ForyStruct)]
struct AnimalShelter {
    animals_rc: Vec<Rc<dyn Animal>>,
    animals_arc: Vec<Arc<dyn Animal>>,
    registry: HashMap<String, Arc<dyn Animal>>,
}

let mut fory = Fory::builder().xlang(false).build();
fory.register::<Dog>(100)?;
fory.register::<Cat>(101)?;
fory.register::<AnimalShelter>(102)?;

let shelter = AnimalShelter {
    animals_rc: vec![
        Rc::new(Dog { name: "Rex".to_string(), breed: "Golden".to_string() }),
        Rc::new(Cat { name: "Mittens".to_string(), color: "Gray".to_string() }),
    ],
    animals_arc: vec![
        Arc::new(Dog { name: "Buddy".to_string(), breed: "Labrador".to_string() }),
    ],
    registry: HashMap::from([
        ("pet1".to_string(), Arc::new(Dog {
            name: "Max".to_string(),
            breed: "Shepherd".to_string()
        }) as Arc<dyn Animal>),
    ]),
};

let bytes = fory.serialize(&shelter)?;
let decoded: AnimalShelter = fory.deserialize(&bytes)?;

assert_eq!(decoded.animals_rc[0].name(), "Rex");
assert_eq!(decoded.animals_arc[0].speak(), "Woof!");
```

## Standalone Trait Object Serialization

Due to Rust's orphan rule, `Rc<dyn Trait>` and `Arc<dyn Trait>` cannot implement `Serializer` directly. For standalone serialization (not inside struct fields), the `register_trait_type!` macro generates wrapper types.

**Note:** If you don't want to use wrapper types for concrete non-container payloads, you can serialize as `Box<dyn Any>`, `Rc<dyn Any>`, or `Arc<dyn Any + Send + Sync>` instead (see the dyn Any section above).

The `register_trait_type!` macro generates `AnimalRc` and `AnimalArc` wrapper types:

```rust
// For Rc<dyn Trait>
let dog_rc: Rc<dyn Animal> = Rc::new(Dog {
    name: "Rex".to_string(),
    breed: "Golden".to_string()
});
let wrapper = AnimalRc::from(dog_rc);

let bytes = fory.serialize(&wrapper)?;
let decoded: AnimalRc = fory.deserialize(&bytes)?;

// Unwrap back to Rc<dyn Animal>
let unwrapped: Rc<dyn Animal> = decoded.unwrap();
assert_eq!(unwrapped.name(), "Rex");

// For Arc<dyn Trait>
let dog_arc: Arc<dyn Animal> = Arc::new(Dog {
    name: "Buddy".to_string(),
    breed: "Labrador".to_string()
});
let wrapper = AnimalArc::from(dog_arc);

let bytes = fory.serialize(&wrapper)?;
let decoded: AnimalArc = fory.deserialize(&bytes)?;

let unwrapped: Arc<dyn Animal> = decoded.unwrap();
assert_eq!(unwrapped.name(), "Buddy");
```

## Best Practices

1. **Use `register_trait_type!`** to register all trait implementations
2. **Keep compatible mode enabled** for trait objects
3. **Register all concrete types** before serialization
4. **Prefer dyn Any** for simpler standalone serialization

## Related Topics

- [References](references.md) - Rc/Arc shared references
- [Schema Evolution](schema-evolution.md) - Compatible mode
- [Type Registration](type-registration.md) - Registering types
