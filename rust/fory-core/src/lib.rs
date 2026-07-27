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

//! Core runtime for Apache Fory's Rust implementation.
//!
//! [`Serializer`] statically handles one associated [`Serializer::Target`]. An ordinary local
//! type serializes itself. A local external structural serializer or manual serializer can
//! target a type owned by another crate without creating a wrapper value.
//!
//! [`Fory`] exposes ordinary roots for self-serializing types and four explicit-serializer roots:
//! [`Fory::serialize_with`], [`Fory::serialize_to_with`], [`Fory::deserialize_with`], and
//! [`Fory::deserialize_from_with`]. Fory-owned carrier serializers such as [`VecSerializer`] and
//! [`HashMapSerializer`] recursively compose child serializers while preserving standard carrier
//! wire formats.
//!
//! Application traits extend [`ForyObject`] and use [`register_trait_type!`] with a closed list of
//! concrete target types. Concrete targets are dispatched through their registered serializer;
//! serializer declarations and Rust trait names never appear on the wire.

// Direct lower-level derive users may resolve this crate through `::fory_core`, including
// doctests where `crate` is the doctest crate instead of this runtime crate.
extern crate self as fory_core;

pub mod buffer;
pub mod config;
pub mod context;
pub mod error;
pub mod fory;
pub mod meta;
pub mod resolver;
pub mod row;
pub mod serializer;
pub mod type_id;
pub mod types;
pub mod util;

// Re-exported for generated macro code.
pub use paste;

pub use crate::buffer::{Reader, Writer};
pub use crate::config::Config;
pub use crate::context::{ReadContext, WriteContext};
pub use crate::error::Error;
pub use crate::fory::{Fory, ForyBuilder};
pub use crate::meta::{compute_field_hash, compute_struct_hash};
pub use crate::resolver::{RefFlag, RefMode, TypeInfo, TypeResolver};
pub use crate::serializer::trait_object::ForyObject;
#[doc(hidden)]
pub use crate::serializer::{
    read_data, write_data, ArcSerializer, ArcWeakSerializer, ArraySerializer, BTreeMapSerializer,
    BTreeSetSerializer, BinaryHeapSerializer, BoxSerializer, HashMapSerializer, HashSetSerializer,
    LinkedListSerializer, MutexSerializer, OptionSerializer, RcSerializer, RcWeakSerializer,
    RefCellSerializer, Serializer, StructSerializer, Tuple10Serializer, Tuple11Serializer,
    Tuple12Serializer, Tuple13Serializer, Tuple14Serializer, Tuple15Serializer, Tuple16Serializer,
    Tuple17Serializer, Tuple18Serializer, Tuple19Serializer, Tuple1Serializer, Tuple20Serializer,
    Tuple21Serializer, Tuple22Serializer, Tuple2Serializer, Tuple3Serializer, Tuple4Serializer,
    Tuple5Serializer, Tuple6Serializer, Tuple7Serializer, Tuple8Serializer, Tuple9Serializer,
    VecDequeSerializer, VecSerializer,
};
pub use crate::type_id::TypeId;
pub use crate::types::bfloat16::bfloat16 as BFloat16;
pub use crate::types::float16::float16 as Float16;
pub use crate::types::{ArcWeak, Date, Decimal, Duration, RcWeak, Timestamp, UnknownCase};
