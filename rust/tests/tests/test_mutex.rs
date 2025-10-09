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
use std::sync::{Arc, Mutex};

#[test]
fn test_mutex_basic_serialization() {
    let fory = Fory::default();
    let m = Mutex::new(42i32);
    let serialized = fory.serialize(&m);
    let deserialized: Mutex<i32> = fory.deserialize(&serialized).unwrap();
    assert_eq!(deserialized.lock().unwrap().clone(), 42);
}

#[test]
fn test_arc_mutex_serialization() {
    let fory = Fory::default();
    let arc_mutex = Arc::new(Mutex::new(String::from("hello")));
    let serialized = fory.serialize(&arc_mutex);
    let deserialized: Arc<Mutex<String>> = fory.deserialize(&serialized).unwrap();
    assert_eq!(deserialized.lock().unwrap().as_str(), "hello");
}

#[test]
fn test_arc_mutex_sharing_preserved() {
    let fory = Fory::default();

    let data = Arc::new(Mutex::new(123i32));
    let list = vec![data.clone(), data.clone()];

    let serialized = fory.serialize(&list);
    let deserialized: Vec<Arc<Mutex<i32>>> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized.len(), 2);
    assert!(Arc::ptr_eq(&deserialized[0], &deserialized[1]));
}
