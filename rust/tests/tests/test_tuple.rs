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

use fory_core::fory::Fory;
use fory_derive::ForyObject;
use std::rc::Rc;

const PI_F64: f64 = std::f64::consts::PI;

type ComplexNestedTuple = ((Vec<i32>, Option<String>), (Rc<bool>, (i32, f64)));

// Test homogeneous tuples with primitive types
#[test]
fn test_homogeneous_tuple_i32() {
    let fory = Fory::default();
    let tuple = (1i32, 2i32, 3i32);
    let bin = fory.serialize(&tuple).unwrap();
    let obj: (i32, i32, i32) = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(tuple, obj);
}

#[test]
fn test_homogeneous_tuple_f64() {
    let fory = Fory::default();
    let tuple = (1.5f64, 2.5f64, 3.5f64, 4.5f64);
    let bin = fory.serialize(&tuple).unwrap();
    let obj: (f64, f64, f64, f64) = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(tuple, obj);
}

#[test]
fn test_homogeneous_tuple_string() {
    let fory = Fory::default();
    let tuple = ("hello".to_string(), "world".to_string(), "fory".to_string());
    let bin = fory.serialize(&tuple).unwrap();
    let obj: (String, String, String) = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(tuple, obj);
}

// Test heterogeneous tuples with different types
#[test]
fn test_heterogeneous_tuple_simple() {
    let fory = Fory::default();
    let tuple = (42i32, "hello".to_string());
    let bin = fory.serialize(&tuple).unwrap();
    let obj: (i32, String) = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(tuple, obj);
}

#[test]
fn test_heterogeneous_tuple_complex() {
    let fory = Fory::default();
    let tuple = (42i32, "hello".to_string(), PI_F64, true, vec![1, 2, 3]);
    let bin = fory.serialize(&tuple).unwrap();
    let obj: (i32, String, f64, bool, Vec<i32>) = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(tuple, obj);
}

// Test single element tuple
#[test]
fn test_single_element_tuple() {
    let fory = Fory::default();
    let tuple = (42i32,);
    let bin = fory.serialize(&tuple).unwrap();
    let obj: (i32,) = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(tuple, obj);
}

// Test tuples with Option types
#[test]
fn test_tuple_with_options() {
    let fory = Fory::default();
    let tuple = (Some(42i32), None::<i32>, Some(100i32));
    let bin = fory.serialize(&tuple).unwrap();
    let obj: (Option<i32>, Option<i32>, Option<i32>) = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(tuple, obj);
}

#[test]
fn test_heterogeneous_tuple_with_options() {
    let fory = Fory::default();
    let tuple = (Some(42i32), "hello".to_string(), None::<String>);
    let bin = fory.serialize(&tuple).unwrap();
    let obj: (Option<i32>, String, Option<String>) = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(tuple, obj);
}

// Test tuples with collections
#[test]
fn test_tuple_with_vectors() {
    let fory = Fory::default();
    let tuple = (vec![1, 2, 3], vec![4, 5, 6]);
    let bin = fory.serialize(&tuple).unwrap();
    let obj: (Vec<i32>, Vec<i32>) = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(tuple, obj);
}

#[test]
fn test_tuple_with_mixed_collections() {
    let fory = Fory::default();
    let tuple = (vec![1, 2, 3], vec!["a".to_string(), "b".to_string()]);
    let bin = fory.serialize(&tuple).unwrap();
    let obj: (Vec<i32>, Vec<String>) = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(tuple, obj);
}

// Test nested tuples
#[test]
fn test_nested_tuples() {
    let fory = Fory::default();
    let tuple = ((1i32, 2i32), (3i32, 4i32));
    let bin = fory.serialize(&tuple).unwrap();
    let obj: ((i32, i32), (i32, i32)) = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(tuple, obj);
}

#[test]
fn test_deeply_nested_tuples() {
    let fory = Fory::default();
    let tuple = (1i32, (2i32, (3i32, 4i32)));
    let bin = fory.serialize(&tuple).unwrap();
    let obj: (i32, (i32, (i32, i32))) = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(tuple, obj);
}

// Test large tuples
#[test]
fn test_large_homogeneous_tuple() {
    let fory = Fory::default();
    let tuple = (
        1i32, 2i32, 3i32, 4i32, 5i32, 6i32, 7i32, 8i32, 9i32, 10i32, 11i32, 12i32,
    );
    let bin = fory.serialize(&tuple).unwrap();
    let obj: (i32, i32, i32, i32, i32, i32, i32, i32, i32, i32, i32, i32) =
        fory.deserialize(&bin).expect("deserialize");
    assert_eq!(tuple, obj);
}

#[test]
fn test_large_heterogeneous_tuple() {
    let fory = Fory::default();
    let tuple = (
        1i32,
        2i64,
        3u32,
        4u64,
        5.0f32,
        6.0f64,
        "seven".to_string(),
        true,
    );
    let bin = fory.serialize(&tuple).unwrap();
    let obj: (i32, i64, u32, u64, f32, f64, String, bool) =
        fory.deserialize(&bin).expect("deserialize");
    assert_eq!(tuple, obj);
}

// Test tuples with Rc/Arc (shared references)
#[test]
fn test_tuple_with_rc() {
    let fory = Fory::default();
    let value = Rc::new(42i32);
    let tuple = (Rc::clone(&value), Rc::clone(&value));
    let bin = fory.serialize(&tuple).unwrap();
    let obj: (Rc<i32>, Rc<i32>) = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(*obj.0, 42);
    assert_eq!(*obj.1, 42);
    // Note: deserialization creates independent Rc instances, not shared ones
}

// Test tuples with bool
#[test]
fn test_homogeneous_tuple_bool() {
    let fory = Fory::default();
    let tuple = (true, false, true, false);
    let bin = fory.serialize(&tuple).unwrap();
    let obj: (bool, bool, bool, bool) = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(tuple, obj);
}

// Test tuples with u8, u16, u32, u64
#[test]
fn test_homogeneous_tuple_unsigned() {
    let fory = Fory::default();
    let tuple_u8 = (1u8, 2u8, 3u8);
    let bin = fory.serialize(&tuple_u8).unwrap();
    let obj: (u8, u8, u8) = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(tuple_u8, obj);

    let tuple_u16 = (100u16, 200u16, 300u16);
    let bin = fory.serialize(&tuple_u16).unwrap();
    let obj: (u16, u16, u16) = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(tuple_u16, obj);

    let tuple_u32 = (1000u32, 2000u32, 3000u32);
    let bin = fory.serialize(&tuple_u32).unwrap();
    let obj: (u32, u32, u32) = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(tuple_u32, obj);

    let tuple_u64 = (10000u64, 20000u64, 30000u64);
    let bin = fory.serialize(&tuple_u64).unwrap();
    let obj: (u64, u64, u64) = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(tuple_u64, obj);
}

// Test that tuples are serialized with LIST type ID
#[test]
fn test_tuple_type_id() {
    use fory_core::serializer::Serializer;
    use fory_core::types::TypeId;
    assert_eq!(<(i32, i32)>::fory_static_type_id(), TypeId::LIST);
    assert_eq!(<(i32, String)>::fory_static_type_id(), TypeId::LIST);
    assert_eq!(<(i32,)>::fory_static_type_id(), TypeId::LIST);
}

// Test tuples in xlang mode
#[test]
fn test_tuple_xlang_mode() {
    let fory = Fory::default().xlang(true);

    // Test homogeneous tuple
    let homogeneous = (1i32, 2i32, 3i32);
    let bin = fory.serialize(&homogeneous).unwrap();
    let obj: (i32, i32, i32) = fory.deserialize(&bin).expect("deserialize homogeneous");
    assert_eq!(homogeneous, obj);

    // Test heterogeneous tuple
    let heterogeneous = (42i32, "hello".to_string(), PI_F64, true);
    let bin = fory.serialize(&heterogeneous).unwrap();
    let obj: (i32, String, f64, bool) = fory.deserialize(&bin).expect("deserialize heterogeneous");
    assert_eq!(heterogeneous, obj);

    // Test nested tuple
    let nested = ((1i32, "inner".to_string()), (2.5f64, vec![1, 2, 3]));
    let bin = fory.serialize(&nested).unwrap();
    let obj: ((i32, String), (f64, Vec<i32>)) = fory.deserialize(&bin).expect("deserialize nested");
    assert_eq!(nested, obj);

    // Test tuple with Option
    let with_option = (Some(42i32), None::<String>, Some(vec![1, 2]));
    let bin = fory.serialize(&with_option).unwrap();
    let obj: (Option<i32>, Option<String>, Option<Vec<i32>>) =
        fory.deserialize(&bin).expect("deserialize with option");
    assert_eq!(with_option, obj);
}

// Helper method for struct with simple tuple fields
fn run_struct_with_simple_tuple_fields(xlang: bool) {
    #[derive(ForyObject, Debug, PartialEq)]
    struct SimpleTupleStruct {
        id: i32,
        coordinates: (f64, f64),
        pair: (String, i32),
        triple: (bool, u32, i64),
    }

    let mut fory = Fory::default().xlang(xlang);
    fory.register::<SimpleTupleStruct>(1).unwrap();

    let data = SimpleTupleStruct {
        id: 42,
        coordinates: (10.5, 20.3),
        pair: ("hello".to_string(), 100),
        triple: (true, 200, -300),
    };

    // Serialize
    let bytes = fory.serialize(&data).unwrap();
    // Deserialize
    let decoded: SimpleTupleStruct = fory.deserialize(&bytes).unwrap();
    assert_eq!(data, decoded);
}

// Helper method for struct with complex tuple fields
fn run_struct_with_complex_tuple_fields(xlang: bool) {
    #[derive(ForyObject, Debug, PartialEq)]
    struct ComplexTupleStruct {
        name: String,
        // Heterogeneous tuple
        heterogeneous: (i32, String, f64, bool),
        // Nested tuples
        nested: ((i32, String), (f64, bool)),
        // Tuple with collection
        with_collection: (Vec<i32>, Vec<String>),
        // Tuple with Option
        with_option: (Option<i32>, Option<String>),
        // Tuple with Rc (shared_ptr)
        with_rc: (Rc<i32>, Rc<String>),
        // Complex nested tuple with collections and options
        complex_nested: ComplexNestedTuple,
    }

    let mut fory = Fory::default().xlang(xlang);
    fory.register::<ComplexTupleStruct>(2).unwrap();

    let data = ComplexTupleStruct {
        name: "Complex Test".to_string(),
        heterogeneous: (42, "world".to_string(), PI_F64, true),
        nested: ((100, "nested".to_string()), (2.71, false)),
        with_collection: (vec![1, 2, 3], vec!["a".to_string(), "b".to_string()]),
        with_option: (Some(999), None),
        with_rc: (Rc::new(777), Rc::new("shared".to_string())),
        complex_nested: (
            (vec![10, 20, 30], Some("optional".to_string())),
            (Rc::new(true), (42, 1.618)),
        ),
    };

    // Serialize
    let bytes = fory.serialize(&data).unwrap();
    // Deserialize
    let decoded: ComplexTupleStruct = fory.deserialize(&bytes).unwrap();
    assert_eq!(data, decoded);
}

// Test struct with simple tuple fields (non-xlang mode)
#[test]
fn test_struct_with_simple_tuple_fields() {
    run_struct_with_simple_tuple_fields(false);
}

// Test struct with complex tuple fields (non-xlang mode)
#[test]
fn test_struct_with_complex_tuple_fields() {
    run_struct_with_complex_tuple_fields(false);
}

// Test struct with complex tuple fields (xlang mode)
#[test]
fn test_struct_with_complex_tuple_fields_xlang() {
    run_struct_with_complex_tuple_fields(true);
}
