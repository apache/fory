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

use std::collections::HashMap;

use fory_core::fory::Fory;
use fory_derive::ForyObject;

// Test 1: Simple struct with one primitive field, non-compatible mode
#[test]
fn test_one_field_primitive_non_compatible() {
    #[derive(ForyObject, Debug, PartialEq)]
    struct Data {
        value: i32,
    }

    let mut fory = Fory::default();
    fory.register::<Data>(100).unwrap();
    let data = Data { value: 42 };
    let bytes = fory.serialize(&data).unwrap();
    let result: Data = fory.deserialize(&bytes).unwrap();
    assert_eq!(data, result);
}

// Test 2: Simple struct with one String field, non-compatible mode
#[test]
fn test_one_field_string_non_compatible() {
    #[derive(ForyObject, Debug, PartialEq)]
    struct Data {
        name: String,
    }

    let mut fory = Fory::default();
    fory.register::<Data>(101).unwrap();
    let data = Data {
        name: String::from("hello"),
    };
    let bytes = fory.serialize(&data).unwrap();
    let result: Data = fory.deserialize(&bytes).unwrap();
    assert_eq!(data, result);
}

// Test 3: Compatible mode - serialize with one field, deserialize with different type
#[test]
fn test_compatible_field_type_change() {
    #[derive(ForyObject, Debug)]
    struct Data1 {
        value: i32,
    }

    #[derive(ForyObject, Debug)]
    struct Data2 {
        value: Option<i32>,
    }

    let mut fory1 = Fory::default().compatible(true);
    let mut fory2 = Fory::default().compatible(true);
    fory1.register::<Data1>(100).unwrap();
    fory2.register::<Data2>(100).unwrap();

    let data1 = Data1 { value: 42 };
    let bytes = fory1.serialize(&data1).unwrap();
    let result: Data2 = fory2.deserialize(&bytes).unwrap();
    assert_eq!(result.value.unwrap(), 42i32);
}

// Test 4: Compatible mode - serialize with field, deserialize with empty struct
#[test]
fn test_compatible_to_empty_struct() {
    #[derive(ForyObject, Debug)]
    struct DataWithField {
        value: i32,
        name: String,
    }

    #[derive(ForyObject, Debug)]
    struct EmptyData {}

    let mut fory1 = Fory::default().compatible(true);
    let mut fory2 = Fory::default().compatible(true);
    fory1.register::<DataWithField>(101).unwrap();
    fory2.register::<EmptyData>(101).unwrap();

    let data1 = DataWithField {
        value: 42,
        name: String::from("test"),
    };
    let bytes = fory1.serialize(&data1).unwrap();
    let _result: EmptyData = fory2.deserialize(&bytes).unwrap();
    // If we get here without panic, the test passes
}

// Test 5: Compatible mode - empty struct to struct with fields (fields get defaults)
#[test]
fn test_compatible_from_empty_struct() {
    #[derive(ForyObject, Debug)]
    struct EmptyData {}

    #[derive(ForyObject, Debug)]
    struct DataWithField {
        value: i32,
        name: String,
    }

    let mut fory1 = Fory::default().compatible(true);
    let mut fory2 = Fory::default().compatible(true);
    fory1.register::<EmptyData>(102).unwrap();
    fory2.register::<DataWithField>(102).unwrap();

    let data1 = EmptyData {};
    let bytes = fory1.serialize(&data1).unwrap();
    let result: DataWithField = fory2.deserialize(&bytes).unwrap();
    assert_eq!(result.value, 0); // Default for i32
    assert_eq!(result.name, String::default()); // Default for String
}

#[test]
fn test_compatible_vec_to_empty_struct() {
    #[derive(ForyObject, Debug)]
    struct DataWithField {
        value: Vec<i32>,
        name: String,
    }

    #[derive(ForyObject, Debug)]
    struct EmptyData {}

    let mut fory1 = Fory::default().compatible(true);
    let mut fory2 = Fory::default().compatible(true);
    fory1.register::<DataWithField>(101).unwrap();
    fory2.register::<EmptyData>(101).unwrap();

    let data1 = DataWithField {
        value: vec![32],
        name: String::from("test"),
    };
    let bytes = fory1.serialize(&data1).unwrap();
    let _result: EmptyData = fory2.deserialize(&bytes).unwrap();
    // If we get here without panic, the test passes
}

#[test]
fn test_compatible_map_to_empty_struct() {
    #[derive(ForyObject, Debug)]
    struct DataWithField {
        value: HashMap<String, i32>,
        name: String,
    }

    #[derive(ForyObject, Debug)]
    struct EmptyData {}

    let mut fory1 = Fory::default().compatible(true);
    let mut fory2 = Fory::default().compatible(true);
    fory1.register::<DataWithField>(101).unwrap();
    fory2.register::<EmptyData>(101).unwrap();

    let data1 = DataWithField {
        value: HashMap::from([(String::from("k1"), 1i32), (String::from("k2"), 2i32)]),
        name: String::from("test"),
    };
    let bytes = fory1.serialize(&data1).unwrap();
    let _result: EmptyData = fory2.deserialize(&bytes).unwrap();
    // If we get here without panic, the test passes
}
