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
use std::collections::HashMap;

// ── MaxCollectionSize ─────────────────────────────────────────────────────────

#[test]
fn test_slice_exceeds_max_collection_size() {
    let fory_base = Fory::default();
    let slice = vec!["a".to_string(), "b".to_string(), "c".to_string()];
    let bytes = fory_base.serialize(&slice).unwrap();

    let fory = Fory::default().max_collection_size(2);
    let result: Result<Vec<String>, _> = fory.deserialize(&bytes);
    assert!(
        result.is_err(),
        "Expected error due to collection size limit"
    );
    let err_msg = result.unwrap_err().to_string();
    assert!(
        err_msg.contains("collection length"),
        "unexpected error: {err_msg}"
    );
}

#[test]
fn test_map_exceeds_max_collection_size() {
    let fory_base = Fory::default();
    let mut m: HashMap<i32, i32> = HashMap::new();
    m.insert(1, 1);
    m.insert(2, 2);
    m.insert(3, 3);
    let bytes = fory_base.serialize(&m).unwrap();

    let fory = Fory::default().max_collection_size(2);
    let result: Result<HashMap<i32, i32>, _> = fory.deserialize(&bytes);
    assert!(result.is_err(), "Expected error due to map size limit");
    let err_msg = result.unwrap_err().to_string();
    assert!(
        err_msg.contains("collection length"),
        "unexpected error: {err_msg}"
    );
}

#[test]
fn test_string_unaffected_by_max_collection_size() {
    let fory = Fory::default().max_collection_size(2);
    let s = "hello world".to_string();
    let bytes = fory.serialize(&s).unwrap();
    let result: Result<String, _> = fory.deserialize(&bytes);
    assert!(
        result.is_ok(),
        "String must not be blocked by max_collection_size"
    );
    assert_eq!(result.unwrap(), s);
}

// ── MaxBinarySize ─────────────────────────────────────────────────────────────

#[test]
fn test_byte_slice_exceeds_max_binary_size() {
    let fory_base = Fory::default();
    let slice: Vec<u8> = vec![1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
    let bytes = fory_base.serialize(&slice).unwrap();

    let fory = Fory::default().max_binary_size(5);
    let result: Result<Vec<u8>, _> = fory.deserialize(&bytes);
    assert!(result.is_err(), "Expected error due to binary size limit");
    let err_msg = result.unwrap_err().to_string();
    assert!(
        err_msg.contains("binary size"),
        "unexpected error: {err_msg}"
    );
}

#[test]
fn test_string_unaffected_by_max_binary_size() {
    let fory = Fory::default().max_binary_size(2);
    let s = "hello world".to_string();
    let bytes = fory.serialize(&s).unwrap();
    let result: Result<String, _> = fory.deserialize(&bytes);
    assert!(
        result.is_ok(),
        "String must not be blocked by max_binary_size"
    );
    assert_eq!(result.unwrap(), s);
}

#[test]
fn test_btreemap_exceeds_max_collection_size() {
    use std::collections::BTreeMap;
    let fory_base = Fory::default();
    let mut m: BTreeMap<i32, i32> = BTreeMap::new();
    m.insert(1, 1);
    m.insert(2, 2);
    m.insert(3, 3);
    let bytes = fory_base.serialize(&m).unwrap();

    let fory = Fory::default().max_collection_size(2);
    let result: Result<BTreeMap<i32, i32>, _> = fory.deserialize(&bytes);
    assert!(result.is_err(), "Expected error due to BTreeMap size limit");
    let err_msg = result.unwrap_err().to_string();
    assert!(
        err_msg.contains("collection length"),
        "unexpected error: {err_msg}"
    );
}
