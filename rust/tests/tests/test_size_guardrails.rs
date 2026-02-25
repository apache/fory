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

#[test]
fn test_collection_size_limit_exceeded() {
    if fory_core::error::should_panic_on_error() {
        return;
    }
    let fory_write = Fory::default();
    let items: Vec<i32> = vec![1, 2, 3, 4, 5];
    let bytes = fory_write.serialize(&items).unwrap();

    let fory_read = Fory::default().max_collection_size(3);
    let result: Result<Vec<i32>, _> = fory_read.deserialize(&bytes);
    assert!(
        result.is_err(),
        "Expected deserialization to fail due to collection size limit"
    );
    let err_msg = format!("{:?}", result.unwrap_err());
    assert!(err_msg.contains("collection length"));
}

#[test]
fn test_collection_size_within_limit() {
    if fory_core::error::should_panic_on_error() {
        return;
    }
    let fory = Fory::default().max_collection_size(5);
    let items: Vec<i32> = vec![1, 2, 3, 4, 5];
    let bytes = fory.serialize(&items).unwrap();
    let result: Result<Vec<i32>, _> = fory.deserialize(&bytes);
    assert!(result.is_ok());
}

#[test]
fn test_map_size_limit_exceeded() {
    if fory_core::error::should_panic_on_error() {
        return;
    }
    let fory_write = Fory::default();
    let mut map: HashMap<String, i32> = HashMap::new();
    map.insert("a".to_string(), 1);
    map.insert("b".to_string(), 2);
    map.insert("c".to_string(), 3);
    let bytes = fory_write.serialize(&map).unwrap();

    let fory_read = Fory::default().max_map_size(2);
    let result: Result<HashMap<String, i32>, _> = fory_read.deserialize(&bytes);
    assert!(
        result.is_err(),
        "Expected deserialization to fail due to map size limit"
    );
    let err_msg = format!("{:?}", result.unwrap_err());
    assert!(err_msg.contains("map entry count"));
}

#[test]
fn test_map_size_within_limit() {
    if fory_core::error::should_panic_on_error() {
        return;
    }
    let fory = Fory::default().max_map_size(3);
    let mut map: HashMap<String, i32> = HashMap::new();
    map.insert("a".to_string(), 1);
    map.insert("b".to_string(), 2);
    map.insert("c".to_string(), 3);
    let bytes = fory.serialize(&map).unwrap();
    let result: Result<HashMap<String, i32>, _> = fory.deserialize(&bytes);
    assert!(result.is_ok());
}

#[test]
fn test_string_size_limit_exceeded() {
    if fory_core::error::should_panic_on_error() {
        return;
    }
    let fory_write = Fory::default();
    let s = "hello world".to_string();
    let bytes = fory_write.serialize(&s).unwrap();

    let fory_read = Fory::default().max_string_bytes(5);
    let result: Result<String, _> = fory_read.deserialize(&bytes);
    assert!(
        result.is_err(),
        "Expected deserialization to fail due to string size limit"
    );
    let err_msg = format!("{:?}", result.unwrap_err());
    assert!(err_msg.contains("string byte length"));
}

#[test]
fn test_string_size_within_limit() {
    if fory_core::error::should_panic_on_error() {
        return;
    }
    let fory = Fory::default().max_string_bytes(20);
    let s = "hello world".to_string();
    let bytes = fory.serialize(&s).unwrap();
    let result: Result<String, _> = fory.deserialize(&bytes);
    assert!(result.is_ok());
}
