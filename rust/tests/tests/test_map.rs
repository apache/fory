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
use std::collections::{BTreeMap, HashMap};

#[test]
fn test_hashmap_string() {
    let fory = Fory::default();
    let mut map = HashMap::new();
    map.insert("key1".to_string(), "value1".to_string());
    map.insert("key2".to_string(), "value2".to_string());
    let bin = fory.serialize(&map).unwrap();
    let obj: HashMap<String, String> = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(map, obj);
}

#[test]
fn test_btreemap_string() {
    let fory = Fory::default();
    let mut map = BTreeMap::new();
    map.insert("key1".to_string(), "value1".to_string());
    map.insert("key2".to_string(), "value2".to_string());
    let bin = fory.serialize(&map).unwrap();
    let obj: BTreeMap<String, String> = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(map, obj);
}

#[derive(ForyObject, PartialEq, Debug)]
struct MapContainer {
    hash_map: HashMap<String, String>,
    btree_map: BTreeMap<String, i32>,
}

#[test]
fn test_struct_with_maps() {
    let mut fory = Fory::default();
    fory.register_by_name::<MapContainer>("MapContainer")
        .unwrap();
    let mut hash_map = HashMap::new();
    hash_map.insert("foo".to_string(), "bar".to_string());
    let mut btree_map = BTreeMap::new();
    btree_map.insert("a".to_string(), 1);
    btree_map.insert("b".to_string(), 2);

    let container = MapContainer {
        hash_map,
        btree_map,
    };

    let bin = fory.serialize(&container).unwrap();
    let obj: MapContainer = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(container, obj);
}
