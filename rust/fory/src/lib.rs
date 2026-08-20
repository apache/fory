// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

//! Apache Fory's public Rust facade.
//!
//! Derive macros create serializers for local types:
//!
//! ```rust,ignore
//! use fory::{Fory, ForyStruct};
//!
//! #[derive(ForyStruct)]
//! struct User {
//!     name: String,
//!     age: u32,
//! }
//!
//! let mut fory = Fory::builder().xlang(false).build();
//! fory.register::<User>(100)?;
//! let bytes = fory.serialize(&User {
//!     name: "Ada".to_owned(),
//!     age: 37,
//! })?;
//! let user: User = fory.deserialize(&bytes)?;
//! # Ok::<(), fory::Error>(())
//! ```
//!
//! A local serializer can target a type owned by another crate. External structural
//! serializers use `#[fory(target = path::Type)]`; opaque or invariant-bearing targets use a
//! custom [`Serializer`]. The serializer is selected explicitly at a field with
//! `#[fory(with = UserSerializer)]` or at a root with the `*_with` API family:
//!
//! ```rust,ignore
//! let bytes = fory.serialize_with::<UserSerializer>(&external_user)?;
//! let user = fory.deserialize_with::<UserSerializer>(&bytes)?;
//!
//! let mut buffer = Vec::new();
//! fory.serialize_to_with::<UserSerializer>(&mut buffer, &external_user)?;
//! let mut reader = fory::Reader::new(&buffer);
//! let user = fory.deserialize_from_with::<UserSerializer>(&mut reader)?;
//! ```
//!
//! Fory-owned carrier serializers compose an external child at a root without changing the
//! carrier's standard wire representation:
//!
//! ```rust,ignore
//! use fory::{HashMapSerializer, VecSerializer};
//!
//! type Users = VecSerializer<UserSerializer>;
//! type Directory = HashMapSerializer<String, Users>;
//! let bytes = fory.serialize_with::<Directory>(&directory)?;
//! let decoded = fory.deserialize_with::<Directory>(&bytes)?;
//! ```
//!
//! Application traits extend [`ForyObject`], not [`Serializer`]. The concrete target list is
//! closed by [`register_trait_type!`], while each target's registered serializer determines its
//! serialization:
//!
//! ```rust,ignore
//! use fory::{register_trait_type, ForyObject};
//!
//! trait Animal: ForyObject {
//!     fn name(&self) -> &str;
//! }
//!
//! register_trait_type!(Animal, Dog, third_party::Cat);
//! ```
//!
//! Use `register_trait_type!(sync Trait, Targets...)` for traits and targets that are
//! `Send + Sync`. The generated `TraitRcSerializer` and `TraitArcSerializer` types support
//! explicit `Rc<dyn Trait>` and `Arc<dyn Trait>` roots without wrapper values.

// Derive macros resolve the facade through `::fory::__private`, including doctests where
// `crate` is the doctest crate instead of this facade crate.
extern crate self as fory;

pub use fory_core::{
    error::Error,
    fory::Fory,
    fory::ForyBuilder,
    register_trait_type,
    row::{from_row, to_row, to_row_into, ArrayIter, ArrayView, MapView, Row, RowView},
    ArcSerializer, ArcWeak, ArcWeakSerializer, ArraySerializer, BFloat16, BTreeMapSerializer,
    BTreeSetSerializer, BinaryHeapSerializer, BoxSerializer, Date, Decimal, Duration, Float16,
    ForyObject, HashMapSerializer, HashSetSerializer, LinkedListSerializer, MutexSerializer,
    OptionSerializer, RcSerializer, RcWeak, RcWeakSerializer, ReadContext, Reader,
    RefCellSerializer, RefFlag, RefMode, Serializer, StructSerializer, Timestamp,
    Tuple10Serializer, Tuple11Serializer, Tuple12Serializer, Tuple13Serializer, Tuple14Serializer,
    Tuple15Serializer, Tuple16Serializer, Tuple17Serializer, Tuple18Serializer, Tuple19Serializer,
    Tuple1Serializer, Tuple20Serializer, Tuple21Serializer, Tuple22Serializer, Tuple2Serializer,
    Tuple3Serializer, Tuple4Serializer, Tuple5Serializer, Tuple6Serializer, Tuple7Serializer,
    Tuple8Serializer, Tuple9Serializer, TypeId, TypeResolver, UnknownCase, VecDequeSerializer,
    VecSerializer, WriteContext, Writer,
};
pub use fory_derive::{ForyEnum, ForyRow, ForyStruct, ForyUnion};

#[doc(hidden)]
pub mod __private {
    pub use fory_core::*;
}
