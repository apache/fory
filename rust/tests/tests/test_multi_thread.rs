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

use fory_core::Fory;
use fory_derive::ForyObject;
use std::collections::HashSet;
use std::sync::Arc;
use std::thread;

#[test]
fn test_simple_multi_thread() {
    let fory = Arc::new(Fory::default());
    let src: HashSet<_> = [41, 42, 43, 45, 46, 47].into_iter().collect();
    // serialize
    let mut handles = vec![];
    for item in &src {
        let fory_clone = Arc::clone(&fory);
        let item = *item;
        let handle = thread::spawn(move || fory_clone.serialize(&item));
        handles.push(handle);
    }
    let mut serialized_data = vec![];
    for handle in handles {
        let bytes = handle.join().unwrap();
        serialized_data.push(bytes);
    }
    // deserialize
    let mut dest = HashSet::new();
    let mut handles = vec![];
    for bytes in serialized_data {
        let fory_clone = Arc::clone(&fory);
        let handle = thread::spawn(move || fory_clone.deserialize::<i32>(&bytes).unwrap());
        handles.push(handle);
    }
    for handle in handles {
        let value = handle.join().unwrap();
        dest.insert(value);
    }
    // verify
    assert_eq!(dest, src);
}

#[test]
fn test_struct_multi_thread() {
    #[derive(ForyObject, Debug, PartialEq, Eq, Hash, Clone, Copy)]
    struct Item1 {
        f1: i32,
    }
    let mut fory = Fory::default();
    fory.register::<Item1>(101);
    let fory = Arc::new(fory);
    let src: HashSet<_> = [
        Item1 { f1: 42 },
        Item1 { f1: 43 },
        Item1 { f1: 45 },
        Item1 { f1: 46 },
        Item1 { f1: 47 },
    ]
    .into_iter()
    .collect();
    // serialize
    let mut handles = vec![];
    for item in &src {
        let fory_clone = Arc::clone(&fory);
        let item = *item;
        let handle = thread::spawn(move || fory_clone.serialize(&item));
        handles.push(handle);
    }
    let mut serialized_data = vec![];
    for handle in handles {
        let bytes = handle.join().unwrap();
        serialized_data.push(bytes);
    }
    // deserialize
    let mut dest = HashSet::new();
    let mut handles = vec![];
    for bytes in serialized_data {
        let fory_clone = Arc::clone(&fory);
        let handle = thread::spawn(move || fory_clone.deserialize::<Item1>(&bytes).unwrap());
        handles.push(handle);
    }
    for handle in handles {
        let value = handle.join().unwrap();
        dest.insert(value);
    }
    // verify
    assert_eq!(dest, src);
}
