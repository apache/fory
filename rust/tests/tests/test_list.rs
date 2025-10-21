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
use std::collections::{LinkedList, VecDeque};

#[test]
fn test_vecdeque_i32() {
    let fory = Fory::default();
    let mut deque = VecDeque::new();
    deque.push_back(1);
    deque.push_back(2);
    deque.push_back(3);
    let bin = fory.serialize(&deque).unwrap();
    let obj: VecDeque<i32> = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(deque, obj);
}

#[test]
fn test_vecdeque_empty() {
    let fory = Fory::default();
    let deque: VecDeque<i32> = VecDeque::new();
    let bin = fory.serialize(&deque).unwrap();
    let obj: VecDeque<i32> = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(deque, obj);
}

#[test]
fn test_vecdeque_string() {
    let fory = Fory::default();
    let mut deque = VecDeque::new();
    deque.push_back("hello".to_string());
    deque.push_back("world".to_string());
    let bin = fory.serialize(&deque).unwrap();
    let obj: VecDeque<String> = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(deque, obj);
}

#[test]
fn test_vecdeque_f64() {
    let fory = Fory::default();
    let mut deque = VecDeque::new();
    deque.push_back(1.5);
    deque.push_back(2.5);
    deque.push_back(3.5);
    let bin = fory.serialize(&deque).unwrap();
    let obj: VecDeque<f64> = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(deque, obj);
}

#[test]
fn test_linkedlist_i32() {
    let fory = Fory::default();
    let mut list = LinkedList::new();
    list.push_back(1);
    list.push_back(2);
    list.push_back(3);
    let bin = fory.serialize(&list).unwrap();
    let obj: LinkedList<i32> = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(list, obj);
}

#[test]
fn test_linkedlist_empty() {
    let fory = Fory::default();
    let list: LinkedList<i32> = LinkedList::new();
    let bin = fory.serialize(&list).unwrap();
    let obj: LinkedList<i32> = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(list, obj);
}

#[test]
fn test_linkedlist_string() {
    let fory = Fory::default();
    let mut list = LinkedList::new();
    list.push_back("foo".to_string());
    list.push_back("bar".to_string());
    let bin = fory.serialize(&list).unwrap();
    let obj: LinkedList<String> = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(list, obj);
}

#[test]
fn test_linkedlist_bool() {
    let fory = Fory::default();
    let mut list = LinkedList::new();
    list.push_back(true);
    list.push_back(false);
    list.push_back(true);
    let bin = fory.serialize(&list).unwrap();
    let obj: LinkedList<bool> = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(list, obj);
}

#[derive(ForyObject, PartialEq, Debug)]
struct CollectionStruct {
    vec_field: Vec<i32>,
    deque_field: VecDeque<String>,
    list_field: LinkedList<bool>,
}

#[test]
fn test_struct_with_collections() {
    let mut fory = Fory::default();
    fory.register_by_name::<CollectionStruct>("CollectionStruct")
        .unwrap();

    let mut deque = VecDeque::new();
    deque.push_back("hello".to_string());
    deque.push_back("world".to_string());

    let mut list = LinkedList::new();
    list.push_back(true);
    list.push_back(false);

    let data = CollectionStruct {
        vec_field: vec![1, 2, 3],
        deque_field: deque,
        list_field: list,
    };

    let bin = fory.serialize(&data).unwrap();
    let obj: CollectionStruct = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(data, obj);
}
