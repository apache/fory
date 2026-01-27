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

//! Tests for marker types like `PhantomData<T>` and TypeId constants.
//!
//! `PhantomData<T>` is a zero-sized marker type used for type-level information
//! without any runtime data. These tests verify that structs containing
//! `PhantomData<T>` can be serialized correctly.
//!
//! Also tests `UNION` and `NONE` TypeId constants defined in xlang spec.

use fory_core::fory::Fory;
use fory_core::serializer::Serializer;
use fory_core::types::TypeId;
use fory_derive::ForyObject;
use std::marker::PhantomData;

/// Test struct containing PhantomData with concrete type
#[derive(Debug, PartialEq, ForyObject)]
struct StructWithPhantom {
    name: String,
    _marker: PhantomData<i32>,
    count: i32,
}

#[test]
fn test_struct_with_phantom_data() {
    let mut fory = Fory::default();
    fory.register::<StructWithPhantom>(100).unwrap();

    let value = StructWithPhantom {
        name: "test".to_string(),
        _marker: PhantomData,
        count: 42,
    };
    let bytes = fory.serialize(&value).unwrap();
    let result: StructWithPhantom = fory.deserialize(&bytes).unwrap();
    assert_eq!(result, value);
}

/// Test struct containing multiple PhantomData fields with different types
#[derive(Debug, PartialEq, ForyObject)]
struct StructWithMultiplePhantom {
    name: String,
    _phantom1: PhantomData<String>,
    count: i32,
    _phantom2: PhantomData<Vec<u8>>,
}

#[test]
fn test_struct_with_multiple_phantom_data() {
    let mut fory = Fory::default();
    fory.register::<StructWithMultiplePhantom>(101).unwrap();

    let value = StructWithMultiplePhantom {
        name: "test".to_string(),
        _phantom1: PhantomData,
        count: 42,
        _phantom2: PhantomData,
    };
    let bytes = fory.serialize(&value).unwrap();
    let result: StructWithMultiplePhantom = fory.deserialize(&bytes).unwrap();
    assert_eq!(result, value);
}

/// Test nested struct with PhantomData
#[derive(Debug, PartialEq, ForyObject)]
struct InnerWithPhantom {
    value: i32,
    _marker: PhantomData<String>,
}

#[derive(Debug, PartialEq, ForyObject)]
struct OuterWithPhantom {
    inner: InnerWithPhantom,
    name: String,
    _marker: PhantomData<Vec<i32>>,
}

#[test]
fn test_nested_struct_with_phantom_data() {
    let mut fory = Fory::default();
    fory.register::<InnerWithPhantom>(102).unwrap();
    fory.register::<OuterWithPhantom>(103).unwrap();

    let value = OuterWithPhantom {
        inner: InnerWithPhantom {
            value: 100,
            _marker: PhantomData,
        },
        name: "outer".to_string(),
        _marker: PhantomData,
    };
    let bytes = fory.serialize(&value).unwrap();
    let result: OuterWithPhantom = fory.deserialize(&bytes).unwrap();
    assert_eq!(result, value);
}

// ============================================================================
// TypeId Tests for UNION and NONE
// ============================================================================

/// Test that UNION TypeId matches xlang spec (31)
#[test]
fn test_union_type_id() {
    assert_eq!(TypeId::UNION as i16, 31);
    assert_eq!(fory_core::types::UNION, 31);
}

/// Test that NONE TypeId matches xlang spec (34)
#[test]
fn test_none_type_id() {
    assert_eq!(TypeId::NONE as i16, 34);
    assert_eq!(fory_core::types::NONE, 34);
}

/// Test that PhantomData uses NONE TypeId (no runtime data)
#[test]
fn test_phantom_data_uses_none_type_id() {
    assert_eq!(
        <PhantomData<i32> as Serializer>::fory_static_type_id(),
        TypeId::NONE
    );
    assert_eq!(
        <PhantomData<String> as Serializer>::fory_static_type_id(),
        TypeId::NONE
    );
    assert_eq!(
        <PhantomData<Vec<u8>> as Serializer>::fory_static_type_id(),
        TypeId::NONE
    );
}

/// Test that unit type () uses NONE TypeId (empty/unit value with no data)
#[test]
fn test_unit_type_uses_none_type_id() {
    assert_eq!(<() as Serializer>::fory_static_type_id(), TypeId::NONE);
}
