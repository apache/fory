---
title: Rust
sidebar_position: 4
id: rust
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

## Output Layout

Rust output is one module file per schema, for example:

- `<rust_out>/addressbook.rs`

When `--grpc` is used and the schema contains services, Rust also emits:

- `<rust_out>/addressbook_service.rs`
- `<rust_out>/addressbook_service_grpc.rs`

## Type Generation

Unions map to Rust enums with `#[fory(id = ...)]` schema case attributes.
`#[fory(unknown)] Unknown(::fory::UnknownCase)` marks the Fory-provided
forward-compatibility carrier. The marker only selects the carrier and does not
add an entry to the schema case table; schema cases still use the full `0..N`
ID range. A generated typed union must have at least one non-`Unknown` case. The
compiler marks the first declared non-`Unknown` case as `#[fory(default)]`.
When that case's payload implements Rust's standard `Default` trait, the
compiler also emits a standard `Default` implementation from that case:

```rust
#[derive(::fory::ForyUnion, Clone, Debug, PartialEq, Eq, Hash)]
pub enum Animal {
    #[fory(unknown)]
    Unknown(::fory::UnknownCase),
    #[fory(id = 0, default)]
    Dog(self::Dog),
    #[fory(id = 1)]
    Cat(self::Cat),
}

impl ::std::default::Default for Animal {
    fn default() -> Self {
        Self::Dog(<self::Dog as ::std::default::Default>::default())
    }
}
```

If the selected payload does not implement standard `Default`, such as an
`any` payload, the generated union has no infallible `Default`
implementation. This model-level default is independent of Fory's fallible
deserialization default, which reconstructs the selected case through its
codecs and active read context.

Nested types generate nested modules:

```rust
pub mod person {
    #[derive(ForyEnum, Debug, Clone, PartialEq, Default)]
    #[repr(i32)]
    pub enum PhoneType {
        #[default]
        Mobile = 0,
        Home = 1,
        Work = 2,
    }

    #[derive(ForyStruct, Debug, Clone, PartialEq, Default)]
    pub struct PhoneNumber {
        #[fory(id = 1)]
        pub number: String,
        #[fory(id = 2)]
        pub phone_type: PhoneType,
    }
}
```

Messages derive `ForyStruct` and include `to_bytes`/`from_bytes` helpers:

```rust
#[derive(ForyStruct, Debug, Clone, PartialEq, Default)]
pub struct Person {
    #[fory(id = 1)]
    pub name: String,
    #[fory(id = 7)]
    pub phones: Vec<person::PhoneNumber>,
    #[fory(id = 8)]
    pub pet: Animal,
}
```

## Registration

Generated registration function:

```rust
pub fn register_types(fory: &mut Fory) -> Result<(), fory::Error> {
    fory.register_union::<Animal>(106)?;
    fory.register::<person::PhoneType>(101)?;
    fory.register::<person::PhoneNumber>(102)?;
    fory.register::<Person>(100)?;
    fory.register::<Dog>(104)?;
    fory.register::<Cat>(105)?;
    fory.register::<AddressBook>(103)?;
    Ok(())
}
```

For schemas without explicit `[id=...]`, generated registration uses computed numeric IDs:

```rust
fory.register::<Status>(1124725126)?;
fory.register_union::<Wrapper>(1471345060)?;
fory.register::<Envelope>(3022445236)?;
fory.register_union::<envelope::Detail>(1609214087)?;
fory.register::<envelope::Payload>(2862577837)?;
```

If `option enable_auto_type_id = false;` is set:

```rust
fory.register_by_name::<Config>("myapp.models.Config")?;
fory.register_union_by_name::<Holder>("myapp.models.Holder")?;
```

## Usage

```rust
let person = Person {
    name: "Alice".into(),
    pet: Animal::Dog(self::Dog::default()),
    ..Default::default()
};

let bytes = person.to_bytes()?;
let restored = Person::from_bytes(&bytes)?;
```

## gRPC Service Companions

With `--grpc`, Rust emits `<module>_service.rs` for the async service trait and path constants, and `<module>_service_grpc.rs` for tonic clients, servers, codecs, and `ForyGrpcPayload` implementations backed by the model's `to_bytes` and `from_bytes` helpers. See [Rust gRPC](../../grpc/rust.md) for dependencies and usage.
