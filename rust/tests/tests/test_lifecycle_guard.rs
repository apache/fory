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

//! tests for the lifecycle guard that forbids type registrations
//! after the resolver snapshot has been initialized (i.e after the
//! first serialize or deserialize call).

use fory_core::error::Error;
use fory_core::fory::Fory;
use fory_derive::ForyObject;

/// helper struct used across multiple tests.
#[derive(ForyObject, Debug, PartialEq)]
struct Point {
    x: i32,
    y: i32,
}

/// A second type used for late-registration attempts.
#[derive(ForyObject, Debug, PartialEq)]
struct Color {
    r: u8,
    g: u8,
    b: u8,
}

// Postive tests
#[test]
fn test_register_before_serialize_succeeds() {
    let mut fory = Fory::default();
    // registration before any serialize/deserialize should succeed.
    assert!(fory.register::<Point>(100).is_ok());

    let point = Point { x: 1, y: 2 };
    let bytes = fory.serialize(&point).unwrap();
    let result: Point = fory.deserialize(&bytes).unwrap();
    assert_eq!(point, result);
}

#[test]
fn test_multiple_registrations_before_serialize_succeed() {
    let mut fory = Fory::default();
    assert!(fory.register::<Point>(100).is_ok());
    assert!(fory.register::<Color>(101).is_ok());

    let point = Point { x: 10, y: 20 };
    let bytes = fory.serialize(&point).unwrap();
    let result: Point = fory.deserialize(&bytes).unwrap();
    assert_eq!(point, result);
}

// Negative tests

/// ensures `register()` is forbidden after `serialize()` triggers snapshot init.
#[test]
fn test_register_after_serialize_fails() {
    let mut fory = Fory::default();
    fory.register::<Point>(100).unwrap();

    // first serialize, this initializes the final_type_resolver snapshot.
    let point = Point { x: 1, y: 2 };
    let _bytes = fory.serialize(&point).unwrap();

    // now any registration must fail with NotAllowed.
    let err = fory
        .register::<Color>(101)
        .expect_err("register after serialize should fail");
    assert!(
        matches!(err, Error::NotAllowed(_)),
        "expected NotAllowed, got: {:?}",
        err
    );
    let msg = format!("{}", err);
    assert!(
        msg.contains("not allowed"),
        "error message should explain the restriction, got: {}",
        msg
    );
}

/// Ensures `register()` is forbidden after `deserialize()` triggers snapshot init.
#[test]
fn test_register_after_deserialize_fails() {
    let mut fory = Fory::default();
    fory.register::<Point>(100).unwrap();

    let point = Point { x: 5, y: 10 };
    let bytes = fory.serialize(&point).unwrap();

    // Deserialize — also initializes the snapshot if not already done.
    let _result: Point = fory.deserialize(&bytes).unwrap();

    let err = fory
        .register::<Color>(101)
        .expect_err("register after deserialize should fail");
    assert!(matches!(err, Error::NotAllowed(_)));
}

/// Ensures `register_by_name()` is forbidden after snapshot init.
#[test]
fn test_register_by_name_after_serialize_fails() {
    let mut fory = Fory::default();
    fory.register::<Point>(100).unwrap();
    let _bytes = fory.serialize(&Point { x: 0, y: 0 }).unwrap();

    let err = fory
        .register_by_name::<Color>("Color")
        .expect_err("register_by_name after serialize should fail");
    assert!(matches!(err, Error::NotAllowed(_)));
}

/// Ensures `register_by_namespace()` is forbidden after snapshot init.
#[test]
fn test_register_by_namespace_after_serialize_fails() {
    let mut fory = Fory::default();
    fory.register::<Point>(100).unwrap();
    let _bytes = fory.serialize(&Point { x: 0, y: 0 }).unwrap();

    let err = fory
        .register_by_namespace::<Color>("com.example", "Color")
        .expect_err("register_by_namespace after serialize should fail");
    assert!(matches!(err, Error::NotAllowed(_)));
}

/// Ensures `register_serializer()` is forbidden after snapshot init.
#[test]
fn test_register_serializer_after_serialize_fails() {
    let mut fory = Fory::default();
    fory.register::<Point>(100).unwrap();
    let _bytes = fory.serialize(&Point { x: 0, y: 0 }).unwrap();

    let err = fory
        .register_serializer::<Color>(102)
        .expect_err("register_serializer after serialize should fail");
    assert!(matches!(err, Error::NotAllowed(_)));
}

/// Ensures `register_serializer_by_name()` is forbidden after snapshot init.
#[test]
fn test_register_serializer_by_name_after_serialize_fails() {
    let mut fory = Fory::default();
    fory.register::<Point>(100).unwrap();
    let _bytes = fory.serialize(&Point { x: 0, y: 0 }).unwrap();

    let err = fory
        .register_serializer_by_name::<Color>("Color")
        .expect_err("register_serializer_by_name after serialize should fail");
    assert!(matches!(err, Error::NotAllowed(_)));
}

/// Ensures `register_serializer_by_namespace()` is forbidden after snapshot init.
#[test]
fn test_register_serializer_by_namespace_after_serialize_fails() {
    let mut fory = Fory::default();
    fory.register::<Point>(100).unwrap();
    let _bytes = fory.serialize(&Point { x: 0, y: 0 }).unwrap();

    let err = fory
        .register_serializer_by_namespace::<Color>("com.example", "Color")
        .expect_err("register_serializer_by_namespace after serialize should fail");
    assert!(matches!(err, Error::NotAllowed(_)));
}

/// Ensures `register_generic_trait()` is forbidden after snapshot init.
#[test]
fn test_register_generic_trait_after_serialize_fails() {
    let mut fory = Fory::default();
    fory.register::<Point>(100).unwrap();
    let _bytes = fory.serialize(&Point { x: 0, y: 0 }).unwrap();

    let err = fory
        .register_generic_trait::<Vec<i32>>()
        .expect_err("register_generic_trait after serialize should fail");
    assert!(matches!(err, Error::NotAllowed(_)));
}

/// Ensures `register_union()` is forbidden after snapshot init.
#[test]
fn test_register_union_after_serialize_fails() {
    let mut fory = Fory::default();
    fory.register::<Point>(100).unwrap();
    let _bytes = fory.serialize(&Point { x: 0, y: 0 }).unwrap();

    let err = fory
        .register_union::<Color>(103)
        .expect_err("register_union after serialize should fail");
    assert!(matches!(err, Error::NotAllowed(_)));
}

/// Ensures `register_union_by_name()` is forbidden after snapshot init.
#[test]
fn test_register_union_by_name_after_serialize_fails() {
    let mut fory = Fory::default();
    fory.register::<Point>(100).unwrap();
    let _bytes = fory.serialize(&Point { x: 0, y: 0 }).unwrap();

    let err = fory
        .register_union_by_name::<Color>("Color")
        .expect_err("register_union_by_name after serialize should fail");
    assert!(matches!(err, Error::NotAllowed(_)));
}

/// Ensures `register_union_by_namespace()` is forbidden after snapshot init.
#[test]
fn test_register_union_by_namespace_after_serialize_fails() {
    let mut fory = Fory::default();
    fory.register::<Point>(100).unwrap();
    let _bytes = fory.serialize(&Point { x: 0, y: 0 }).unwrap();

    let err = fory
        .register_union_by_namespace::<Color>("com.example", "Color")
        .expect_err("register_union_by_namespace after serialize should fail");
    assert!(matches!(err, Error::NotAllowed(_)));
}

// Edge-case
#[test]
fn test_late_registration_error_message_is_descriptive() {
    let mut fory = Fory::default();
    fory.register::<Point>(100).unwrap();
    let _bytes = fory.serialize(&Point { x: 0, y: 0 }).unwrap();

    let err = fory.register::<Color>(101).unwrap_err();
    let msg = format!("{}", err);
    assert!(
        msg.contains("not allowed"),
        "should mention 'not allowed', got: {}",
        msg
    );
    assert!(
        msg.contains("serialize/deserialize"),
        "should mention serialize/deserialize, got: {}",
        msg
    );
    assert!(
        msg.contains("finalized"),
        "should mention the snapshot is finalized, got: {}",
        msg
    );
}

// Positive edge-case

#[test]
fn test_serialize_multiple_times_after_registration_succeeds() {
    let mut fory = Fory::default();
    fory.register::<Point>(100).unwrap();

    let p1 = Point { x: 1, y: 2 };
    let p2 = Point { x: 3, y: 4 };

    let bytes1 = fory.serialize(&p1).unwrap();
    let bytes2 = fory.serialize(&p2).unwrap();

    let r1: Point = fory.deserialize(&bytes1).unwrap();
    let r2: Point = fory.deserialize(&bytes2).unwrap();
    assert_eq!(p1, r1);
    assert_eq!(p2, r2);
}
