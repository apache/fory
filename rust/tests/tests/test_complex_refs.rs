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

//! Tests for shared reference handling in Fury Rust

use fory_core::fory::Fory;
use std::rc::Rc;
use std::sync::Arc;

#[test]
fn test_rc_shared_in_nested_vec() {
    let fury = Fory::default();

    let shared1 = Rc::new(String::from("shared_1"));
    let shared2 = Rc::new(String::from("shared_2"));

    // Create a nested structure where the same Rc appears multiple times
    let nested = vec![
        vec![shared1.clone(), shared2.clone()],
        vec![shared1.clone(), shared2.clone()],
        vec![shared1.clone()],
    ];

    let serialized = fury.serialize(&nested);
    let deserialized: Vec<Vec<Rc<String>>> = fury.deserialize(&serialized).unwrap();

    assert_eq!(deserialized.len(), 3);
    assert_eq!(deserialized[0].len(), 2);
    assert_eq!(deserialized[1].len(), 2);
    assert_eq!(deserialized[2].len(), 1);

    // Verify that all references to the same object are preserved as shared
    assert!(Rc::ptr_eq(&deserialized[0][0], &deserialized[1][0]));
    assert!(Rc::ptr_eq(&deserialized[0][0], &deserialized[2][0]));
    assert!(Rc::ptr_eq(&deserialized[0][1], &deserialized[1][1]));

    // Verify values
    assert_eq!(*deserialized[0][0], "shared_1");
    assert_eq!(*deserialized[0][1], "shared_2");
}

#[test]
fn test_arc_shared_in_nested_vec() {
    let fury = Fory::default();

    let shared1 = Arc::new(String::from("shared_1"));
    let shared2 = Arc::new(String::from("shared_2"));

    // Create a nested structure where the same Arc appears multiple times
    let nested = vec![
        vec![shared1.clone(), shared2.clone()],
        vec![shared1.clone(), shared2.clone()],
        vec![shared1.clone()],
    ];

    let serialized = fury.serialize(&nested);
    let deserialized: Vec<Vec<Arc<String>>> = fury.deserialize(&serialized).unwrap();

    assert_eq!(deserialized.len(), 3);
    assert_eq!(deserialized[0].len(), 2);
    assert_eq!(deserialized[1].len(), 2);
    assert_eq!(deserialized[2].len(), 1);

    // Verify that all references to the same object are preserved as shared
    assert!(Arc::ptr_eq(&deserialized[0][0], &deserialized[1][0]));
    assert!(Arc::ptr_eq(&deserialized[0][0], &deserialized[2][0]));
    assert!(Arc::ptr_eq(&deserialized[0][1], &deserialized[1][1]));

    // Verify values
    assert_eq!(*deserialized[0][0], "shared_1");
    assert_eq!(*deserialized[0][1], "shared_2");
}

#[test]
fn test_mixed_rc_arc_sharing() {
    let fury = Fory::default();

    // Test both Rc and Arc sharing within the same structure
    let shared_rc = Rc::new(42i32);
    let shared_arc = Arc::new(String::from("shared"));

    // Create vectors that mix Rc and Arc types
    let rc_vec = vec![shared_rc.clone(), shared_rc.clone()];
    let arc_vec = vec![shared_arc.clone(), shared_arc.clone()];

    let serialized_rc = fury.serialize(&rc_vec);
    let serialized_arc = fury.serialize(&arc_vec);

    let deserialized_rc: Vec<Rc<i32>> = fury.deserialize(&serialized_rc).unwrap();
    let deserialized_arc: Vec<Arc<String>> = fury.deserialize(&serialized_arc).unwrap();

    // Verify Rc sharing
    assert!(Rc::ptr_eq(&deserialized_rc[0], &deserialized_rc[1]));
    assert_eq!(*deserialized_rc[0], 42);

    // Verify Arc sharing
    assert!(Arc::ptr_eq(&deserialized_arc[0], &deserialized_arc[1]));
    assert_eq!(*deserialized_arc[0], "shared");
}

#[test]
fn test_deep_sharing_stress_test() {
    let fury = Fory::default();

    // Create a stress test with deep nesting and many shared references
    let shared = Rc::new(String::from("deep_shared"));

    let deep_structure = vec![
        vec![vec![shared.clone()]],
        vec![vec![shared.clone()]],
        vec![vec![shared.clone()]],
    ];

    let serialized = fury.serialize(&deep_structure);
    let deserialized: Vec<Vec<Vec<Rc<String>>>> = fury.deserialize(&serialized).unwrap();

    // Verify structure
    assert_eq!(deserialized.len(), 3);
    assert_eq!(deserialized[0][0][0].as_str(), "deep_shared");

    // Verify all deeply nested references are shared
    assert!(Rc::ptr_eq(&deserialized[0][0][0], &deserialized[1][0][0]));
    assert!(Rc::ptr_eq(&deserialized[1][0][0], &deserialized[2][0][0]));
    assert!(Rc::ptr_eq(&deserialized[0][0][0], &deserialized[2][0][0]));
}
