---
title: Trait Object Serialization
sidebar_position: 9
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

Apache Fory supports dynamic values through registered application traits and
the standard `Any` trait.

## Application Traits

An application trait extends `ForyObject`. List every concrete target accepted
by the trait in `register_trait_type!`:

```rust
use fory::{
    register_trait_type, Fory, ForyObject, ForyStruct,
};

trait Animal: ForyObject {
    fn name(&self) -> &str;
}

#[derive(ForyStruct)]
struct Dog {
    name: String,
}

impl Animal for Dog {
    fn name(&self) -> &str {
        &self.name
    }
}

#[derive(ForyStruct)]
struct Cat {
    name: String,
}

impl Animal for Cat {
    fn name(&self) -> &str {
        &self.name
    }
}

register_trait_type!(Animal, Dog, Cat);
```

The concrete list contains concrete Rust value types. Each type must also be
registered with the `Fory` instance:

```rust
let mut fory = Fory::builder().xlang(false).build();
fory.register::<Dog>(100)?;
fory.register::<Cat>(101)?;
```

A listed third-party target can be registered through an external structural
serializer or custom serializer. The list still names the target, not that
serializer.

The generated names are private to the macro's module by default. A library
that exports the generated root serializers adds normal Rust visibility:

```rust
register_trait_type!(pub Animal, Dog, Cat);
```

The trait and listed types must be visible at least as broadly as the generated
serializers. `pub(crate)` and restricted visibility are also supported.

## Trait Object Fields

`Box<dyn Trait>` and `Rc<dyn Trait>` work directly in derived fields and nested
containers:

```rust
use std::collections::HashMap;
use std::rc::Rc;

#[derive(ForyStruct)]
struct Shelter {
    featured: Box<dyn Animal>,
    shared: Rc<dyn Animal>,
    animals: Vec<Box<dyn Animal>>,
    by_name: HashMap<String, Rc<dyn Animal>>,
}
```

Fory checks the closed concrete list before materializing a value. Do not add
`#[fory(with = ...)]` to a trait-object node; its concrete serializer is chosen
dynamically from the registered target.

For `Arc<dyn Trait>`, the trait must be thread-safe and the macro uses its sync
form:

```rust
use std::sync::Arc;

trait SharedAnimal: ForyObject + Send + Sync {
    fn name(&self) -> &str;
}

impl SharedAnimal for Dog {
    fn name(&self) -> &str {
        &self.name
    }
}

impl SharedAnimal for Cat {
    fn name(&self) -> &str {
        &self.name
    }
}

register_trait_type!(sync SharedAnimal, Dog, Cat);

#[derive(ForyStruct)]
struct SharedShelter {
    featured: Arc<dyn SharedAnimal>,
}
```

Every listed target in a sync declaration must implement `Send + Sync`.
Use `register_trait_type!(pub sync SharedAnimal, Dog, Cat)` when a library
exports the generated root serializers.

## Trait Object Roots

`Box<dyn Trait>` is an ordinary root:

```rust
let animal: Box<dyn Animal> = Box::new(Dog {
    name: "Rex".to_string(),
});

let bytes = fory.serialize(&animal)?;
let decoded: Box<dyn Animal> = fory.deserialize(&bytes)?;
assert_eq!(decoded.name(), "Rex");
```

Rust's orphan rules prevent an ordinary serializer implementation directly on
`Rc<dyn Trait>` and `Arc<dyn Trait>`. The macro generates serializer types for
those roots:

```rust
let animal: Rc<dyn Animal> = Rc::new(Dog {
    name: "Milo".to_string(),
});

let bytes =
    fory.serialize_with::<AnimalRcSerializer>(&animal)?;
let decoded =
    fory.deserialize_with::<AnimalRcSerializer>(&bytes)?;
assert_eq!(decoded.name(), "Milo");
```

```rust
let animal: Arc<dyn SharedAnimal> = Arc::new(Dog {
    name: "Luna".to_string(),
});

let bytes =
    fory.serialize_with::<SharedAnimalArcSerializer>(&animal)?;
let decoded =
    fory.deserialize_with::<SharedAnimalArcSerializer>(&bytes)?;
assert_eq!(decoded.name(), "Luna");
```

These APIs serialize the original `Rc` or `Arc`; there is no conversion wrapper.
The generated serializer types are not registered by ID or name.

## Dynamic `Any`

Fory supports:

- `Box<dyn Any>`;
- `Rc<dyn Any>`;
- `Arc<dyn Any + Send + Sync>`.

The concrete target must be registered:

```rust
use std::any::Any;

let value: Rc<dyn Any> = Rc::new(Dog {
    name: "Rex".to_string(),
});

let bytes = fory.serialize(&value)?;
let decoded: Rc<dyn Any> = fory.deserialize(&bytes)?;
let dog = decoded.downcast_ref::<Dog>().unwrap();
assert_eq!(dog.name, "Rex");
```

Use `Arc<dyn Any + Send + Sync>` when the erased value must be shared across
threads:

```rust
let value: Arc<dyn Any + Send + Sync> = Arc::new(Dog {
    name: "Buddy".to_string(),
});

let bytes = fory.serialize(&value)?;
let decoded: Arc<dyn Any + Send + Sync> =
    fory.deserialize(&bytes)?;
let dog = decoded.downcast_ref::<Dog>().unwrap();
assert_eq!(dog.name, "Buddy");
```

Derived serializers support synchronized `Arc` materialization when their
targets satisfy `Send + Sync`. A custom serializer that needs this path
implements `read_arc_any`.

Generic LIST, SET, and MAP identities do not identify one exact Rust generic
target behind `Any`. Put such a container in a registered struct, or register an
exact whole-container custom serializer when an opaque EXT representation is
intentional.

## Related Topics

- [External-Type Serialization](external-types.md)
- [References](references.md)
- [Type Registration](type-registration.md)
