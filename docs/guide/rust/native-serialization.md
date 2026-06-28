---
title: Native Serialization
sidebar_position: 3
id: native_serialization
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

Rust native serialization is the Rust-only wire mode selected with `.xlang(false)`. Use it when
every writer and reader is Rust and the payload should preserve Rust object-graph behavior instead
of the portable xlang type system.

Use [Xlang Serialization](xlang-serialization.md), the default Rust mode, when bytes must be read
by Java, Python, C++, Go, JavaScript/TypeScript, C#, Swift, Dart, Scala,
Kotlin, or another non-Rust Fory implementation.

## When To Use Native Serialization

Use native serialization when:

- A payload is produced and consumed only by Rust applications.
- The data model uses Rust-specific object graph features such as `Rc<T>`, `Arc<T>`, weak
  pointers, `RefCell<T>`, `Mutex<T>`, trait objects, or `dyn Any`.
- You want faster serialization and smaller size, and every reader uses the same schema as the
  writer.
- You need compatible schema evolution for Rust-only rolling deployments.
- You want compile-time serializers from `#[derive(ForyStruct)]` without portable xlang mapping
  constraints.

## Create a Native-Mode Fory Instance

```rust
use fory::{Error, Fory, ForyStruct};

#[derive(ForyStruct, Debug, PartialEq)]
struct Order {
    id: i64,
    amount: f64,
}

fn main() -> Result<(), Error> {
    let mut fory = Fory::builder().xlang(false).build();
    fory.register::<Order>(100)?;

    let order = Order { id: 1, amount: 42.5 };
    let bytes = fory.serialize(&order)?;
    let decoded: Order = fory.deserialize(&bytes)?;
    assert_eq!(order, decoded);
    Ok(())
}
```

Perform registrations before sharing a `Fory` instance across threads. Once configured, `Fory` can
be shared through `Arc`.

## Schema Evolution

Native serialization defaults to compatible mode. Keep that default when Rust-only writer and reader
versions can differ:

```rust
let mut writer = Fory::builder().xlang(false).build();
let mut reader = Fory::builder().xlang(false).build();
```

Compatible mode uses metadata to tolerate added, removed, or reordered fields when field
identity remains compatible. See [Schema Evolution](schema-evolution.md).

For faster serialization and smaller size, set `.compatible(false)` only when
every reader and writer always uses the same Rust schema.

## Registration

Register application structs and enum-like types before serialization:

```rust
fory.register::<Order>(100)?;
fory.register_by_name::<Order>("example.Order")?;
```

Use explicit numeric IDs for compact payloads and stable deployments. Use named registration
when independent teams coordinate type identity by names; add a namespace prefix with `.` when needed.

## Rust Object Surface

Native serialization owns the Rust-specific object surface:

- Structs and tuple structs with `#[derive(ForyStruct)]`.
- Enums and union-like models supported by Fory derive macros.
- `Vec`, maps, sets, tuples, arrays, and optional values.
- `Box<T>`, `Rc<T>`, `Arc<T>`, `RcWeak<T>`, and `ArcWeak<T>`.
- `RefCell<T>` and `Mutex<T>`.
- Trait objects such as `Box<dyn Trait>`, `Rc<dyn Trait>`, and `Arc<dyn Trait>`.
- Runtime type dispatch with `Box<dyn Any>`, `Rc<dyn Any>`, and
  `Arc<dyn Any + Send + Sync>` for registered non-container payloads. Wrap
  containers in registered structs, enums, or unions before using them behind
  erased `Any` carriers.
- Date and time carriers, including optional `chrono` support.

Use [Basic Serialization](basic-serialization.md), [References](references.md), and
[Trait Object Serialization](polymorphism.md) for focused examples.

## Shared And Circular References

Native mode can preserve shared references with `Rc<T>` and `Arc<T>`:

```rust
use fory::{Error, Fory};
use std::rc::Rc;

fn main() -> Result<(), Error> {
    let fory = Fory::builder().xlang(false).build();
    let shared = Rc::new(String::from("shared"));
    let values = vec![shared.clone(), shared.clone()];

    let bytes = fory.serialize(&values)?;
    let decoded: Vec<Rc<String>> = fory.deserialize(&bytes)?;
    assert!(Rc::ptr_eq(&decoded[0], &decoded[1]));
    Ok(())
}
```

Use `.track_ref(true)` when weak pointers or explicit cyclic graphs need reference tracking:

```rust
let mut fory = Fory::builder().xlang(false).track_ref(true).build();
```

Weak pointers serialize as references to their target when the target is still alive, and as null
when the target has been dropped.

## Trait Objects

Trait objects are Rust language features and belong in native serialization:

```rust
use fory::{register_trait_type, Error, Fory, ForyStruct, Serializer};

trait Animal: Serializer {
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

register_trait_type!(Animal, Dog);

fn main() -> Result<(), Error> {
    let mut fory = Fory::builder().xlang(false).build();
    fory.register::<Dog>(100)?;

    let value: Box<dyn Animal> = Box::new(Dog { name: "Milo".into() });
    let bytes = fory.serialize(&value)?;
    let decoded: Box<dyn Animal> = fory.deserialize(&bytes)?;
    assert_eq!(decoded.name(), "Milo");
    Ok(())
}
```

Register every concrete implementation that can appear behind the trait object.

## Performance Guidelines

- Reuse a configured `Fory` instance and register types before concurrent use.
- Use `.compatible(false)` only when every reader and writer always uses the same Rust schema and
  the application wants faster serialization and smaller size.
- Use derive-generated serializers for application structs.
- Use `.track_ref(true)` only for weak-pointer or cyclic graph scenarios that require it.
- Prefer concrete typed fields over `dyn Any` or trait objects on hot paths.

## Native And Xlang Comparison

| Requirement                            | Use native serialization | Use xlang serialization |
| -------------------------------------- | ------------------------ | ----------------------- |
| Rust-only payloads                     | Yes                      | Optional                |
| Non-Rust readers or writers            | No                       | Yes                     |
| `Rc`, `Arc`, weak pointers             | Yes                      | No                      |
| Trait objects and `dyn Any`            | Yes                      | No                      |
| Same-schema compact payloads           | Yes                      | No                      |
| Compatible schema evolution by default | Yes                      | Yes                     |
| Portable type mapping across languages | No                       | Yes                     |

## Troubleshooting

### A non-Rust implementation cannot read the payload

The writer is using native serialization. Rebuild it with `.xlang(true)` and align type
registration with every peer.

### A weak pointer fails to resolve

Use `.track_ref(true)` and make sure the target object is still alive when serialized. Dropped weak
targets deserialize as null.

### A trait object cannot deserialize

Register the trait mapping and every concrete implementation that can appear behind the trait
object.

### A rolling deployment fails after a field change

Native serialization defaults to compatible mode. Keep that default when schemas can differ.

## Related Topics

- [Xlang Serialization](xlang-serialization.md) - Cross-language Rust payloads
- [Configuration](configuration.md) - Builder options
- [Basic Serialization](basic-serialization.md) - Object graph serialization
- [Shared & Circular References](references.md) - `Rc`, `Arc`, and weak pointers
- [Trait Object Serialization](polymorphism.md) - Trait objects and dynamic dispatch
- [Schema Evolution](schema-evolution.md) - Compatible mode
