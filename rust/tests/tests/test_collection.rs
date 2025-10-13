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

use fory_core::{Fory, Serializer};
use fory_derive::ForyObject;
use std::collections::{BTreeSet, BinaryHeap, HashSet};

#[test]
fn test_btreeset_roundtrip() {
    let fory: Fory = Fory::default();

    let mut original = BTreeSet::new();
    original.insert(1);
    original.insert(2);
    original.insert(3);

    let trait_obj: Box<dyn Serializer> = Box::new(original.clone());
    let serialized = fory.serialize(&trait_obj);

    let deserialized_concrete: BTreeSet<i32> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized_concrete.len(), 3);
    assert!(deserialized_concrete.contains(&1));
    assert!(deserialized_concrete.contains(&2));
    assert!(deserialized_concrete.contains(&3));
}

#[test]
fn test_binaryheap_roundtrip() {
    let fory: Fory = Fory::default();

    let mut original = BinaryHeap::new();
    original.push(10);
    original.push(20);
    original.push(15);

    let serialized = fory.serialize(&original);
    let deserialized_concrete: BinaryHeap<i32> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized_concrete.len(), 3);
    assert_eq!(deserialized_concrete.peek(), Some(&20));
}

#[derive(ForyObject, Debug, Clone, PartialEq)]
struct SetContainer {
    btree_set: BTreeSet<String>,
    hash_set: HashSet<String>,
}

#[test]
fn test_set_container() {
    let mut fory: Fory = Fory::default();
    fory.register::<SetContainer>(100);

    let mut btree = BTreeSet::new();
    btree.insert("apple".to_string());
    btree.insert("banana".to_string());
    btree.insert("cherry".to_string());

    let mut hash = HashSet::new();
    hash.insert("one".to_string());
    hash.insert("two".to_string());
    hash.insert("three".to_string());

    let original = SetContainer {
        btree_set: btree,
        hash_set: hash,
    };

    let serialized = fory.serialize(&original);
    let deserialized: SetContainer = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized, original);
    assert_eq!(deserialized.btree_set.len(), 3);
    assert!(deserialized.btree_set.contains("apple"));
    assert!(deserialized.btree_set.contains("banana"));
    assert!(deserialized.btree_set.contains("cherry"));
    assert_eq!(deserialized.hash_set.len(), 3);
    assert!(deserialized.hash_set.contains("one"));
    assert!(deserialized.hash_set.contains("two"));
    assert!(deserialized.hash_set.contains("three"));
}

#[derive(ForyObject, Debug)]
struct HeapContainer {
    binary_heap: BinaryHeap<i32>,
}

#[test]
fn test_heap_container() {
    let mut fory: Fory = Fory::default();
    fory.register::<HeapContainer>(100);

    let mut binary_heap = BinaryHeap::new();
    binary_heap.push(3);
    binary_heap.push(1);
    binary_heap.push(2);

    let original = HeapContainer { binary_heap };

    let serialized = fory.serialize(&original);
    let deserialized: HeapContainer = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized.binary_heap.len(), 3);
    assert_eq!(deserialized.binary_heap.len(), 3);
    assert_eq!(deserialized.binary_heap.peek(), Some(&3));
}
