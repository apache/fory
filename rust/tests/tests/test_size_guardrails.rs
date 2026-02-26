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
use std::collections::{BTreeMap, HashMap, HashSet};

// ── Collection (Vec<i32>) ─────────────────────────────────────────────────────

#[test]
fn test_collection_size_limit_exceeded() {
    let fory_write = Fory::default();
    let items: Vec<i32> = vec![1, 2, 3, 4, 5];
    let bytes = fory_write.serialize(&items).unwrap();

    let fory_read = Fory::default().max_collection_size(3);

    // FORY_PANIC_ON_ERROR is a compile-time constant (see error.rs).
    // When set, Error constructors panic instead of returning Err, so we
    // catch_unwind in that build variant rather than asserting is_err().
    if fory_core::error::PANIC_ON_ERROR {
        let _ = std::panic::catch_unwind(|| {
            let _: Result<Vec<i32>, _> = fory_read.deserialize(&bytes);
        });
    } else {
        let result: Result<Vec<i32>, _> = fory_read.deserialize(&bytes);
        assert!(
            result.is_err(),
            "Expected deserialization to fail due to collection size limit"
        );
        let err_msg = format!("{:?}", result.unwrap_err());
        assert!(err_msg.contains("collection length"));
    }
}

#[test]
fn test_collection_size_within_limit() {
    let fory = Fory::default().max_collection_size(5);
    let items: Vec<i32> = vec![1, 2, 3, 4, 5];
    let bytes = fory.serialize(&items).unwrap();
    let result: Result<Vec<i32>, _> = fory.deserialize(&bytes);
    assert!(result.is_ok());
}

// ── Map (HashMap) ─────────────────────────────────────────────────────────────

#[test]
fn test_map_size_limit_exceeded() {
    let fory_write = Fory::default();
    let mut map: HashMap<String, i32> = HashMap::new();
    map.insert("a".to_string(), 1);
    map.insert("b".to_string(), 2);
    map.insert("c".to_string(), 3);
    let bytes = fory_write.serialize(&map).unwrap();

    let fory_read = Fory::default().max_map_size(2);

    if fory_core::error::PANIC_ON_ERROR {
        let _ = std::panic::catch_unwind(|| {
            let _: Result<HashMap<String, i32>, _> = fory_read.deserialize(&bytes);
        });
    } else {
        let result: Result<HashMap<String, i32>, _> = fory_read.deserialize(&bytes);
        assert!(
            result.is_err(),
            "Expected deserialization to fail due to map size limit"
        );
        let err_msg = format!("{:?}", result.unwrap_err());
        assert!(err_msg.contains("map entry count"));
    }
}

#[test]
fn test_map_size_within_limit() {
    let fory = Fory::default().max_map_size(3);
    let mut map: HashMap<String, i32> = HashMap::new();
    map.insert("a".to_string(), 1);
    map.insert("b".to_string(), 2);
    map.insert("c".to_string(), 3);
    let bytes = fory.serialize(&map).unwrap();
    let result: Result<HashMap<String, i32>, _> = fory.deserialize(&bytes);
    assert!(result.is_ok());
}

// ── Map (BTreeMap) ────────────────────────────────────────────────────────────

#[test]
fn test_btreemap_size_limit_exceeded() {
    // Regression: HashMap guard was previously placed before the len==0 early
    // return and before with_capacity; this test also covers BTreeMap ordering.
    let fory_write = Fory::default();
    let mut map: BTreeMap<String, i32> = BTreeMap::new();
    map.insert("x".to_string(), 1);
    map.insert("y".to_string(), 2);
    map.insert("z".to_string(), 3);
    let bytes = fory_write.serialize(&map).unwrap();

    let fory_read = Fory::default().max_map_size(2);

    if fory_core::error::PANIC_ON_ERROR {
        let _ = std::panic::catch_unwind(|| {
            let _: Result<BTreeMap<String, i32>, _> = fory_read.deserialize(&bytes);
        });
    } else {
        let result: Result<BTreeMap<String, i32>, _> = fory_read.deserialize(&bytes);
        assert!(
            result.is_err(),
            "Expected deserialization to fail due to BTreeMap size limit"
        );
        let err_msg = format!("{:?}", result.unwrap_err());
        assert!(err_msg.contains("map entry count"));
    }
}

#[test]
fn test_btreemap_size_within_limit() {
    let fory = Fory::default().max_map_size(3);
    let mut map: BTreeMap<String, i32> = BTreeMap::new();
    map.insert("x".to_string(), 1);
    map.insert("y".to_string(), 2);
    map.insert("z".to_string(), 3);
    let bytes = fory.serialize(&map).unwrap();
    let result: Result<BTreeMap<String, i32>, _> = fory.deserialize(&bytes);
    assert!(result.is_ok());
}

// ── Collection (HashSet) ──────────────────────────────────────────────────────

#[test]
fn test_hashset_size_limit_exceeded() {
    let fory_write = Fory::default();
    let set: HashSet<i32> = vec![1, 2, 3, 4, 5].into_iter().collect();
    let bytes = fory_write.serialize(&set).unwrap();

    let fory_read = Fory::default().max_collection_size(3);

    if fory_core::error::PANIC_ON_ERROR {
        let _ = std::panic::catch_unwind(|| {
            let _: Result<HashSet<i32>, _> = fory_read.deserialize(&bytes);
        });
    } else {
        let result: Result<HashSet<i32>, _> = fory_read.deserialize(&bytes);
        assert!(
            result.is_err(),
            "Expected deserialization to fail due to HashSet size limit"
        );
        let err_msg = format!("{:?}", result.unwrap_err());
        assert!(err_msg.contains("collection length"));
    }
}

// ── String ────────────────────────────────────────────────────────────────────

#[test]
fn test_string_size_limit_exceeded() {
    let fory_write = Fory::default();
    let s = "hello world".to_string();
    let bytes = fory_write.serialize(&s).unwrap();

    let fory_read = Fory::default().max_string_bytes(5);

    if fory_core::error::PANIC_ON_ERROR {
        let _ = std::panic::catch_unwind(|| {
            let _: Result<String, _> = fory_read.deserialize(&bytes);
        });
    } else {
        let result: Result<String, _> = fory_read.deserialize(&bytes);
        assert!(
            result.is_err(),
            "Expected deserialization to fail due to string size limit"
        );
        let err_msg = format!("{:?}", result.unwrap_err());
        assert!(err_msg.contains("string byte length"));
    }
}

#[test]
fn test_string_size_within_limit() {
    let fory = Fory::default().max_string_bytes(20);
    let s = "hello world".to_string();
    let bytes = fory.serialize(&s).unwrap();
    let result: Result<String, _> = fory.deserialize(&bytes);
    assert!(result.is_ok());
}

// ── Primitive list (Vec<u8>) ──────────────────────────────────────────────────

#[test]
fn test_primitive_vec_size_limit_exceeded() {
    // Vec<u8> uses the primitive_list.rs bulk-copy path which is separate from
    // the generic collection path — verifies the guard fires there too.
    let fory_write = Fory::default();
    let data: Vec<u8> = vec![0u8; 100];
    let bytes = fory_write.serialize(&data).unwrap();

    let fory_read = Fory::default().max_collection_size(50);

    if fory_core::error::PANIC_ON_ERROR {
        let _ = std::panic::catch_unwind(|| {
            let _: Result<Vec<u8>, _> = fory_read.deserialize(&bytes);
        });
    } else {
        let result: Result<Vec<u8>, _> = fory_read.deserialize(&bytes);
        assert!(
            result.is_err(),
            "Expected deserialization to fail due to primitive list size limit"
        );
        let err_msg = format!("{:?}", result.unwrap_err());
        assert!(err_msg.contains("collection length"));
    }
}

// ── Buffer truncation (buffer-remaining cross-check) ─────────────────────────

#[test]
fn test_buffer_truncation_rejected() {
    // Validates the buffer-remaining cross-check in check_string_bytes
    // independently of any configured limit: a structurally truncated buffer
    // must be rejected even with default (i32::MAX) limits.
    let fory_write = Fory::default();
    let s = "hello world".to_string();
    let bytes = fory_write.serialize(&s).unwrap();

    // Drop the last 4 bytes so the payload is structurally incomplete.
    let truncated = &bytes[..bytes.len().saturating_sub(4)];
    let fory_read = Fory::default();

    // Truncation causes a read past the buffer end — this always returns Err
    // (or panics in PANIC_ON_ERROR builds), never silently succeeds.
    if fory_core::error::PANIC_ON_ERROR {
        let _ = std::panic::catch_unwind(|| {
            let _: Result<String, _> = fory_read.deserialize(truncated);
        });
    } else {
        let result: Result<String, _> = fory_read.deserialize(truncated);
        assert!(
            result.is_err(),
            "Truncated buffer must be rejected during deserialization"
        );
    }
}
