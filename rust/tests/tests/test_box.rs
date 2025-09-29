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
use fory_derive::Fory;
use std::collections::HashMap;

#[test]
fn test_box_primitive() {
    let fory = Fory::default();

    // Test Box<i32>
    let value = Box::new(42i32);
    let bin = fory.serialize(&value);
    let deserialized: Box<i32> = fory.deserialize(&bin).expect("Should deserialize Box<i32>");
    assert_eq!(*value, *deserialized);

    // Test Box<String>
    let value = Box::new("Hello, Box!".to_string());
    let bin = fory.serialize(&value);
    let deserialized: Box<String> = fory
        .deserialize(&bin)
        .expect("Should deserialize Box<String>");
    assert_eq!(*value, *deserialized);

    // Test Box<f64>
    let value = Box::new(std::f64::consts::PI);
    let bin = fory.serialize(&value);
    let deserialized: Box<f64> = fory.deserialize(&bin).expect("Should deserialize Box<f64>");
    assert_eq!(*value, *deserialized);
}

#[test]
fn test_box_struct() {
    #[derive(Fory, Debug, PartialEq, Default)]
    struct Person {
        name: String,
        age: i32,
    }

    let mut fory = Fory::default();
    fory.register::<Person>(999);

    let person = Person {
        name: "John Doe".to_string(),
        age: 30,
    };
    let value = Box::new(person);
    let bin = fory.serialize(&value);
    let deserialized: Box<Person> = fory
        .deserialize(&bin)
        .expect("Should deserialize Box<Person>");
    assert_eq!(*value, *deserialized);
}

#[test]
fn test_box_struct_separate() {
    #[derive(Fory, Debug, PartialEq, Default)]
    struct Person {
        name: String,
        age: i32,
    }

    let mut fory = Fory::default();
    fory.register::<Person>(999);

    // Test serializing the Box<Person> directly, not as a field
    let person = Person {
        name: "Jane Doe".to_string(),
        age: 25,
    };
    let boxed_person = Box::new(person);
    let bin = fory.serialize(&boxed_person);
    let deserialized: Box<Person> = fory
        .deserialize(&bin)
        .expect("Should deserialize Box<Person>");
    assert_eq!(*boxed_person, *deserialized);
}

#[test]
fn test_box_collection() {
    let fory = Fory::default();

    // Test Box<Vec<i32>>
    let value = Box::new(vec![1, 2, 3, 4, 5]);
    let bin = fory.serialize(&value);
    let deserialized: Box<Vec<i32>> = fory
        .deserialize(&bin)
        .expect("Should deserialize Box<Vec<i32>>");
    assert_eq!(*value, *deserialized);

    // Test Box<HashMap<String, i32>>
    let mut map = HashMap::new();
    map.insert("key1".to_string(), 10);
    map.insert("key2".to_string(), 20);
    let value = Box::new(map);
    let bin = fory.serialize(&value);
    let deserialized: Box<HashMap<String, i32>> = fory
        .deserialize(&bin)
        .expect("Should deserialize Box<HashMap<String, i32>>");
    assert_eq!(*value, *deserialized);
}

#[test]
fn test_box_option() {
    let fory = Fory::default();

    // Test Box<Option<String>> with Some value
    let value = Box::new(Some("Hello".to_string()));
    let bin = fory.serialize(&value);
    let deserialized: Box<Option<String>> = fory
        .deserialize(&bin)
        .expect("Should deserialize Box<Option<String>>");
    assert_eq!(*value, *deserialized);

    // Note: Box<Option<None>> is not supported due to the way Option's serializer works
    // The Option serializer expects None cases to be handled at the serialize() level,
    // not at the write() level, but Box calls write() directly on the inner type.
}

#[test]
fn test_nested_box() {
    // Test Box<Box<i32>> - though unusual, should work
    let fory = Fory::default();

    let value = Box::new(Box::new(42i32));
    let bin = fory.serialize(&value);
    let deserialized: Box<Box<i32>> = fory
        .deserialize(&bin)
        .expect("Should deserialize Box<Box<i32>>");
    assert_eq!(**value, **deserialized);
}
