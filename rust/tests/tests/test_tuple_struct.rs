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

//! Tests for tuple struct serialization with #[derive(ForyObject)]
//!
//! Tuple structs are structs with unnamed fields, like:
//! - `struct Point(f64, f64);`
//! - `struct Wrapper(String);`

use fory_core::fory::Fory;
use fory_derive::ForyObject;
use std::collections::HashMap;
use std::rc::Rc;

// Basic Tuple Structs

#[derive(ForyObject, Debug, PartialEq, Clone)]
struct Point(f64, f64);

#[derive(ForyObject, Debug, PartialEq, Clone)]
struct Wrapper(String);

#[derive(ForyObject, Debug, PartialEq, Clone)]
struct Triple(i32, i64, u32);

#[derive(ForyObject, Debug, PartialEq, Clone)]
struct Single(i32);

#[test]
fn test_basic_tuple_struct() {
    let mut fory = Fory::default();
    fory.register::<Point>(100).unwrap();

    let point = Point(3.15, 2.72);
    let bytes = fory.serialize(&point).unwrap();
    let result: Point = fory.deserialize(&bytes).unwrap();
    assert_eq!(result, point);
}

#[test]
fn test_single_field_tuple_struct() {
    let mut fory = Fory::default();
    fory.register::<Single>(101).unwrap();

    let single = Single(42);
    let bytes = fory.serialize(&single).unwrap();
    let result: Single = fory.deserialize(&bytes).unwrap();
    assert_eq!(result, single);
}

#[test]
fn test_string_wrapper_tuple_struct() {
    let mut fory = Fory::default();
    fory.register::<Wrapper>(102).unwrap();

    let wrapper = Wrapper("hello world".to_string());
    let bytes = fory.serialize(&wrapper).unwrap();
    let result: Wrapper = fory.deserialize(&bytes).unwrap();
    assert_eq!(result, wrapper);
}

#[test]
fn test_triple_tuple_struct() {
    let mut fory = Fory::default();
    fory.register::<Triple>(103).unwrap();

    let triple = Triple(1, 2, 3);
    let bytes = fory.serialize(&triple).unwrap();
    let result: Triple = fory.deserialize(&bytes).unwrap();
    assert_eq!(result, triple);
}

// Tuple Structs with Complex Types

#[derive(ForyObject, Debug, PartialEq, Clone)]
struct WithVec(Vec<i32>, String);

#[derive(ForyObject, Debug, PartialEq, Clone)]
struct WithOption(Option<i32>, Option<String>);

#[derive(ForyObject, Debug, PartialEq, Clone)]
struct WithMap(HashMap<String, i32>);

#[test]
fn test_tuple_struct_with_vec() {
    let mut fory = Fory::default();
    fory.register::<WithVec>(104).unwrap();

    let data = WithVec(vec![1, 2, 3, 4, 5], "test".to_string());
    let bytes = fory.serialize(&data).unwrap();
    let result: WithVec = fory.deserialize(&bytes).unwrap();
    assert_eq!(result, data);
}

#[test]
fn test_tuple_struct_with_option() {
    let mut fory = Fory::default();
    fory.register::<WithOption>(105).unwrap();

    // Test with Some values
    let data1 = WithOption(Some(42), Some("hello".to_string()));
    let bytes1 = fory.serialize(&data1).unwrap();
    let result1: WithOption = fory.deserialize(&bytes1).unwrap();
    assert_eq!(result1, data1);

    // Test with None values
    let data2 = WithOption(None, None);
    let bytes2 = fory.serialize(&data2).unwrap();
    let result2: WithOption = fory.deserialize(&bytes2).unwrap();
    assert_eq!(result2, data2);

    // Test with mixed values
    let data3 = WithOption(Some(100), None);
    let bytes3 = fory.serialize(&data3).unwrap();
    let result3: WithOption = fory.deserialize(&bytes3).unwrap();
    assert_eq!(result3, data3);
}

#[test]
fn test_tuple_struct_with_map() {
    let mut fory = Fory::default();
    fory.register::<WithMap>(106).unwrap();

    let mut map = HashMap::new();
    map.insert("one".to_string(), 1);
    map.insert("two".to_string(), 2);
    map.insert("three".to_string(), 3);

    let data = WithMap(map);
    let bytes = fory.serialize(&data).unwrap();
    let result: WithMap = fory.deserialize(&bytes).unwrap();
    assert_eq!(result, data);
}

// Nested Tuple Structs

#[derive(ForyObject, Debug, PartialEq, Clone)]
struct Inner(i32, String);

#[derive(ForyObject, Debug, PartialEq, Clone)]
struct Outer(Inner, Vec<Inner>);

#[test]
fn test_nested_tuple_structs() {
    let mut fory = Fory::default();
    fory.register::<Inner>(107).unwrap();
    fory.register::<Outer>(108).unwrap();

    let inner1 = Inner(1, "first".to_string());
    let inner2 = Inner(2, "second".to_string());
    let inner3 = Inner(3, "third".to_string());

    let outer = Outer(inner1.clone(), vec![inner2, inner3]);
    let bytes = fory.serialize(&outer).unwrap();
    let result: Outer = fory.deserialize(&bytes).unwrap();
    assert_eq!(result, outer);
}

// Tuple Struct with Rc (shared reference)

#[derive(ForyObject, Debug, PartialEq, Clone)]
struct WithRc(Rc<String>, Rc<i32>);

#[test]
fn test_tuple_struct_with_rc() {
    let mut fory = Fory::default();
    fory.register::<WithRc>(109).unwrap();

    let data = WithRc(Rc::new("shared".to_string()), Rc::new(42));
    let bytes = fory.serialize(&data).unwrap();
    let result: WithRc = fory.deserialize(&bytes).unwrap();
    assert_eq!(*result.0, "shared");
    assert_eq!(*result.1, 42);
}

// Mixed: Tuple Struct inside Named Struct

#[derive(ForyObject, Debug, PartialEq, Clone)]
struct NamedWithTupleStruct {
    id: i32,
    point: Point,
    wrapper: Wrapper,
}

#[test]
fn test_named_struct_with_tuple_struct_fields() {
    let mut fory = Fory::default();
    fory.register::<Point>(100).unwrap();
    fory.register::<Wrapper>(102).unwrap();
    fory.register::<NamedWithTupleStruct>(110).unwrap();

    let data = NamedWithTupleStruct {
        id: 1,
        point: Point(1.5, 2.5),
        wrapper: Wrapper("test".to_string()),
    };

    let bytes = fory.serialize(&data).unwrap();
    let result: NamedWithTupleStruct = fory.deserialize(&bytes).unwrap();
    assert_eq!(result, data);
}

// Tuple Struct with Tuple field

#[derive(ForyObject, Debug, PartialEq, Clone)]
struct TupleStructWithTuple(i32, (String, f64));

#[test]
fn test_tuple_struct_with_tuple_field() {
    let mut fory = Fory::default();
    fory.register::<TupleStructWithTuple>(111).unwrap();

    let data = TupleStructWithTuple(42, ("hello".to_string(), 3.15));
    let bytes = fory.serialize(&data).unwrap();
    let result: TupleStructWithTuple = fory.deserialize(&bytes).unwrap();
    assert_eq!(result, data);
}

// xlang mode tests

#[test]
fn test_tuple_struct_xlang_mode() {
    let mut fory = Fory::default().xlang(true);
    fory.register::<Point>(100).unwrap();
    fory.register::<Wrapper>(102).unwrap();
    fory.register::<Triple>(103).unwrap();

    let point = Point(3.15, 2.72);
    let bytes = fory.serialize(&point).unwrap();
    let result: Point = fory.deserialize(&bytes).unwrap();
    assert_eq!(result, point);

    let wrapper = Wrapper("xlang test".to_string());
    let bytes = fory.serialize(&wrapper).unwrap();
    let result: Wrapper = fory.deserialize(&bytes).unwrap();
    assert_eq!(result, wrapper);

    let triple = Triple(-100, 9999999999i64, 200);
    let bytes = fory.serialize(&triple).unwrap();
    let result: Triple = fory.deserialize(&bytes).unwrap();
    assert_eq!(result, triple);
}

// Edge cases

#[derive(ForyObject, Debug, PartialEq, Clone)]
struct EmptyVecTuple(Vec<i32>);

#[test]
fn test_tuple_struct_with_empty_vec() {
    let mut fory = Fory::default();
    fory.register::<EmptyVecTuple>(112).unwrap();

    let data = EmptyVecTuple(vec![]);
    let bytes = fory.serialize(&data).unwrap();
    let result: EmptyVecTuple = fory.deserialize(&bytes).unwrap();
    assert_eq!(result, data);
}

#[derive(ForyObject, Debug, PartialEq, Clone)]
struct LargeTupleStruct(i8, i16, i32, i64, u8, u16, u32, u64, f32, f64, bool, String);

#[test]
fn test_large_tuple_struct() {
    let mut fory = Fory::default();
    fory.register::<LargeTupleStruct>(113).unwrap();

    let data = LargeTupleStruct(
        1,
        2,
        3,
        4,
        5,
        6,
        7,
        8,
        9.0,
        10.0,
        true,
        "twelve".to_string(),
    );

    let bytes = fory.serialize(&data).unwrap();
    let result: LargeTupleStruct = fory.deserialize(&bytes).unwrap();
    assert_eq!(result, data);
}

// Schema Evolution Tests for Tuple Structs

// Simulate remote version with fewer fields
mod remote_v1 {
    use fory_derive::ForyObject;

    #[derive(ForyObject, Debug, PartialEq, Clone)]
    pub struct Point(pub f64, pub f64);
}

// Simulate local version with more fields (added field 2)
mod local_v2 {
    use fory_derive::ForyObject;

    #[derive(ForyObject, Debug, PartialEq, Clone)]
    pub struct Point(pub f64, pub f64, pub f64);
}

// Simulate remote version with more fields
mod remote_v3 {
    use fory_derive::ForyObject;

    #[derive(ForyObject, Debug, PartialEq, Clone)]
    pub struct Point(pub f64, pub f64, pub f64, pub f64);
}

/// Test schema evolution: remote has fewer fields than local.
#[test]
fn test_tuple_struct_schema_evolution_add_field() {
    let mut fory_writer = Fory::default().compatible(true);
    fory_writer.register::<remote_v1::Point>(100).unwrap();

    let remote_data = remote_v1::Point(1.0, 2.0);
    let bytes = fory_writer.serialize(&remote_data).unwrap();

    let mut fory_reader = Fory::default().compatible(true);
    fory_reader.register::<local_v2::Point>(100).unwrap();

    let local_data: local_v2::Point = fory_reader.deserialize(&bytes).unwrap();

    assert_eq!(local_data.0, 1.0);
    assert_eq!(local_data.1, 2.0);
    assert_eq!(local_data.2, 0.0);
}

/// Test schema evolution: remote has more fields than local.
#[test]
fn test_tuple_struct_schema_evolution_remove_field() {
    let mut fory_writer = Fory::default().compatible(true);
    fory_writer.register::<remote_v3::Point>(100).unwrap();

    let remote_data = remote_v3::Point(1.0, 2.0, 3.0, 4.0);
    let bytes = fory_writer.serialize(&remote_data).unwrap();

    let mut fory_reader = Fory::default().compatible(true);
    fory_reader.register::<remote_v1::Point>(100).unwrap();

    let local_data: remote_v1::Point = fory_reader.deserialize(&bytes).unwrap();

    assert_eq!(local_data.0, 1.0);
    assert_eq!(local_data.1, 2.0);
}

// Test with mixed types to verify sorting doesn't break schema evolution
mod remote_mixed_v1 {
    use fory_derive::ForyObject;

    #[derive(ForyObject, Debug, PartialEq, Clone)]
    pub struct MixedPoint(pub f64, pub f64);
}

mod local_mixed_v2 {
    use fory_derive::ForyObject;

    #[derive(ForyObject, Debug, PartialEq, Clone)]
    pub struct MixedPoint(pub f64, pub f64, pub i64);
}

mod local_mixed_v3 {
    use fory_derive::ForyObject;

    // Adding u8 (which has smaller size than f64)
    #[derive(ForyObject, Debug, PartialEq, Clone)]
    pub struct MixedPoint(pub f64, pub f64, pub u8);
}

/// Test that adding i64 (compress=true) doesn't break schema evolution
#[test]
fn test_tuple_struct_schema_evolution_add_i64() {
    let mut fory_writer = Fory::default().compatible(true);
    fory_writer
        .register::<remote_mixed_v1::MixedPoint>(100)
        .unwrap();

    let remote_data = remote_mixed_v1::MixedPoint(1.0, 2.0);
    let bytes = fory_writer.serialize(&remote_data).unwrap();

    let mut fory_reader = Fory::default().compatible(true);
    fory_reader
        .register::<local_mixed_v2::MixedPoint>(100)
        .unwrap();

    let local_data: local_mixed_v2::MixedPoint = fory_reader.deserialize(&bytes).unwrap();

    assert_eq!(local_data.0, 1.0);
    assert_eq!(local_data.1, 2.0);
    assert_eq!(local_data.2, 0);
}

/// Test that adding u8 (smaller size) doesn't break schema evolution
#[test]
fn test_tuple_struct_schema_evolution_add_u8() {
    let mut fory_writer = Fory::default().compatible(true);
    fory_writer
        .register::<remote_mixed_v1::MixedPoint>(100)
        .unwrap();

    let remote_data = remote_mixed_v1::MixedPoint(1.0, 2.0);
    let bytes = fory_writer.serialize(&remote_data).unwrap();

    let mut fory_reader = Fory::default().compatible(true);
    fory_reader
        .register::<local_mixed_v3::MixedPoint>(100)
        .unwrap();

    let local_data: local_mixed_v3::MixedPoint = fory_reader.deserialize(&bytes).unwrap();

    assert_eq!(local_data.0, 1.0);
    assert_eq!(local_data.1, 2.0);
    assert_eq!(local_data.2, 0);
}
