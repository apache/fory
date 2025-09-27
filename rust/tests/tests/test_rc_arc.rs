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

//! Tests for Rc and Arc serialization support in Fury

use fory_core::fory::Fory;
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::Arc;

#[test]
fn test_rc_string_serialization() {
    let fury = Fory::default();

    let data = String::from("Hello, Rc!");
    let rc_data = Rc::new(data);

    let serialized = fury.serialize(&rc_data);
    let deserialized: Rc<String> = fury.deserialize(&serialized).unwrap();

    assert_eq!(*rc_data, *deserialized);
    assert_eq!("Hello, Rc!", *deserialized);
}

#[test]
fn test_arc_string_serialization() {
    let fury = Fory::default();

    let data = String::from("Hello, Arc!");
    let arc_data = Arc::new(data);

    let serialized = fury.serialize(&arc_data);
    let deserialized: Arc<String> = fury.deserialize(&serialized).unwrap();

    assert_eq!(*arc_data, *deserialized);
    assert_eq!("Hello, Arc!", *deserialized);
}

#[test]
fn test_rc_number_serialization() {
    let fury = Fory::default();

    let rc_number = Rc::new(42i32);

    let serialized = fury.serialize(&rc_number);
    let deserialized: Rc<i32> = fury.deserialize(&serialized).unwrap();

    assert_eq!(*rc_number, *deserialized);
    assert_eq!(42, *deserialized);
}

#[test]
fn test_arc_number_serialization() {
    let fury = Fory::default();

    let arc_number = Arc::new(100i64);

    let serialized = fury.serialize(&arc_number);
    let deserialized: Arc<i64> = fury.deserialize(&serialized).unwrap();

    assert_eq!(*arc_number, *deserialized);
    assert_eq!(100, *deserialized);
}

#[test]
fn test_rc_in_collections() {
    let fury = Fory::default();

    let string1 = Rc::new(String::from("First"));
    let string2 = Rc::new(String::from("Second"));

    let strings = vec![string1.clone(), string2.clone(), string1.clone()];

    let serialized = fury.serialize(&strings);
    let deserialized: Vec<Rc<String>> = fury.deserialize(&serialized).unwrap();

    assert_eq!(strings.len(), deserialized.len());
    assert_eq!(*strings[0], *deserialized[0]);
    assert_eq!(*strings[1], *deserialized[1]);
    assert_eq!(*strings[2], *deserialized[2]);
    assert_eq!("First", *deserialized[0]);
    assert_eq!("Second", *deserialized[1]);
    assert_eq!("First", *deserialized[2]);
}

#[test]
fn test_arc_in_collections() {
    let fury = Fory::default();

    let number1 = Arc::new(123i32);
    let number2 = Arc::new(456i32);

    let numbers = vec![number1.clone(), number2.clone(), number1.clone()];

    let serialized = fury.serialize(&numbers);
    let deserialized: Vec<Arc<i32>> = fury.deserialize(&serialized).unwrap();

    assert_eq!(numbers.len(), deserialized.len());
    assert_eq!(*numbers[0], *deserialized[0]);
    assert_eq!(*numbers[1], *deserialized[1]);
    assert_eq!(*numbers[2], *deserialized[2]);
    assert_eq!(123, *deserialized[0]);
    assert_eq!(456, *deserialized[1]);
    assert_eq!(123, *deserialized[2]);
}

#[test]
fn test_rc_vec_serialization() {
    let fury = Fory::default();

    let data = vec![1, 2, 3, 4, 5];
    let rc_data = Rc::new(data);

    let serialized = fury.serialize(&rc_data);
    let deserialized: Rc<Vec<i32>> = fury.deserialize(&serialized).unwrap();

    assert_eq!(*rc_data, *deserialized);
    assert_eq!(vec![1, 2, 3, 4, 5], *deserialized);
}

#[test]
fn test_arc_vec_serialization() {
    let fury = Fory::default();

    let data = vec![String::from("a"), String::from("b"), String::from("c")];
    let arc_data = Arc::new(data);

    let serialized = fury.serialize(&arc_data);
    let deserialized: Arc<Vec<String>> = fury.deserialize(&serialized).unwrap();

    assert_eq!(*arc_data, *deserialized);
    assert_eq!(vec!["a", "b", "c"], *deserialized);
}

#[test]
fn test_mixed_rc_arc_serialization() {
    let fury = Fory::default();

    // Test basic types wrapped in Rc/Arc
    let rc_number = Rc::new(42i32);
    let arc_number = Arc::new(100i64);

    let rc_serialized = fury.serialize(&rc_number);
    let arc_serialized = fury.serialize(&arc_number);

    let rc_deserialized: Rc<i32> = fury.deserialize(&rc_serialized).unwrap();
    let arc_deserialized: Arc<i64> = fury.deserialize(&arc_serialized).unwrap();

    assert_eq!(*rc_number, *rc_deserialized);
    assert_eq!(*arc_number, *arc_deserialized);
}

#[test]
fn test_nested_rc_arc() {
    let fury = Fory::default();

    // Test Rc containing Arc
    let inner_data = Arc::new(String::from("nested"));
    let outer_data = Rc::new(inner_data.clone());

    let serialized = fury.serialize(&outer_data);
    let deserialized: Rc<Arc<String>> = fury.deserialize(&serialized).unwrap();

    assert_eq!(**outer_data, **deserialized);
}

#[test]
fn test_rc_arc_with_hashmaps() {
    let fury = Fory::default();

    let string_data = Arc::new(String::from("shared"));

    let mut map = HashMap::new();
    map.insert("key1".to_string(), string_data.clone());
    map.insert("key2".to_string(), string_data.clone());

    let serialized = fury.serialize(&map);
    let deserialized: HashMap<String, Arc<String>> = fury.deserialize(&serialized).unwrap();

    assert_eq!(map.len(), deserialized.len());
    assert_eq!(*map["key1"], *deserialized["key1"]);
    assert_eq!(*map["key2"], *deserialized["key2"]);
    assert_eq!("shared", *deserialized["key1"]);
    assert_eq!("shared", *deserialized["key2"]);
}

// Additional tests moved from arc.rs and rc.rs serializer modules

#[test]
fn test_arc_serialization_basic() {
    let fury = Fory::default();
    let arc = Arc::new(42i32);

    let serialized = fury.serialize(&arc);
    let deserialized: Arc<i32> = fury.deserialize(&serialized).unwrap();

    assert_eq!(*deserialized, 42);
}

#[test]
fn test_arc_shared_reference() {
    let fury = Fory::default();
    let arc1 = Arc::new(String::from("shared"));

    let serialized = fury.serialize(&arc1);
    let deserialized: Arc<String> = fury.deserialize(&serialized).unwrap();

    assert_eq!(*deserialized, "shared");
    // In a full implementation with proper reference tracking,
    // multiple references to the same object would be preserved
}

#[test]
fn test_arc_thread_safety() {
    use std::thread;

    let fury = Fory::default();
    let arc = Arc::new(vec![1, 2, 3, 4, 5]);

    let serialized = fury.serialize(&arc);

    // Test that Arc can be sent across threads
    let handle = thread::spawn(move || {
        let fury = Fory::default();
        let deserialized: Arc<Vec<i32>> = fury.deserialize(&serialized).unwrap();
        assert_eq!(*deserialized, vec![1, 2, 3, 4, 5]);
    });

    handle.join().unwrap();
}

#[test]
fn test_rc_serialization_basic() {
    let fury = Fory::default();
    let rc = Rc::new(42i32);

    let serialized = fury.serialize(&rc);
    let deserialized: Rc<i32> = fury.deserialize(&serialized).unwrap();

    assert_eq!(*deserialized, 42);
}

#[test]
fn test_rc_shared_reference() {
    let fury = Fory::default();
    let rc1 = Rc::new(String::from("shared"));

    let serialized = fury.serialize(&rc1);
    let deserialized: Rc<String> = fury.deserialize(&serialized).unwrap();

    assert_eq!(*deserialized, "shared");
    // In a full implementation with proper reference tracking,
    // multiple references to the same object would be preserved
}
