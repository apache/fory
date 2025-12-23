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

//! Tests for structs with associated types (e.g., `C::NodeId`)

use fory_core::fory::Fory;
use fory_core::{ForyDefault, Serializer};
use fory_derive::ForyObject;

/// A trait that defines associated types, similar to OpenRaft's RaftTypeConfig
pub trait TypeConfig: Sized + Send + Sync + 'static {
    type NodeId: Clone
        + Default
        + PartialEq
        + std::fmt::Debug
        + Send
        + Sync
        + Serializer
        + ForyDefault
        + 'static;
    type Term: Clone
        + Default
        + PartialEq
        + std::fmt::Debug
        + Send
        + Sync
        + Serializer
        + ForyDefault
        + 'static;
}

/// A concrete implementation of TypeConfig
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct TestConfig;

impl TypeConfig for TestConfig {
    type NodeId = u64;
    type Term = u64;
}

/// A struct with fields using associated types
/// The where clause ensures all associated types implement required traits
#[derive(ForyObject, Debug, Clone, PartialEq)]
pub struct LeaderId<C>
where
    C: TypeConfig,
    C::NodeId: Serializer + ForyDefault,
    C::Term: Serializer + ForyDefault,
{
    pub term: C::Term,
    pub node_id: C::NodeId,
}

#[test]
fn test_leader_id_with_associated_types() {
    let mut fory = Fory::default();
    fory.register::<LeaderId<TestConfig>>(100).unwrap();

    let leader_id: LeaderId<TestConfig> = LeaderId {
        term: 1,
        node_id: 42,
    };

    let bytes = fory.serialize(&leader_id).unwrap();
    let deserialized: LeaderId<TestConfig> = fory.deserialize(&bytes).unwrap();

    assert_eq!(leader_id, deserialized);
}

#[test]
fn test_leader_id_default_values() {
    let mut fory = Fory::default();
    fory.register::<LeaderId<TestConfig>>(100).unwrap();

    let leader_id: LeaderId<TestConfig> = LeaderId {
        term: 0,
        node_id: 0,
    };

    let bytes = fory.serialize(&leader_id).unwrap();
    let deserialized: LeaderId<TestConfig> = fory.deserialize(&bytes).unwrap();

    assert_eq!(leader_id, deserialized);
}

#[test]
fn test_vec_of_leader_ids() {
    let mut fory = Fory::default();
    fory.register::<LeaderId<TestConfig>>(100).unwrap();

    let leader_ids: Vec<LeaderId<TestConfig>> = vec![
        LeaderId {
            term: 1,
            node_id: 1,
        },
        LeaderId {
            term: 2,
            node_id: 2,
        },
        LeaderId {
            term: 3,
            node_id: 3,
        },
    ];

    let bytes = fory.serialize(&leader_ids).unwrap();
    let deserialized: Vec<LeaderId<TestConfig>> = fory.deserialize(&bytes).unwrap();

    assert_eq!(leader_ids, deserialized);
}

#[test]
fn test_option_leader_id() {
    let mut fory = Fory::default();
    fory.register::<LeaderId<TestConfig>>(100).unwrap();

    // Test with Some value
    let some_leader: Option<LeaderId<TestConfig>> = Some(LeaderId {
        term: 5,
        node_id: 10,
    });

    let bytes = fory.serialize(&some_leader).unwrap();
    let deserialized: Option<LeaderId<TestConfig>> = fory.deserialize(&bytes).unwrap();
    assert_eq!(some_leader, deserialized);

    // Test with None value
    let none_leader: Option<LeaderId<TestConfig>> = None;

    let bytes = fory.serialize(&none_leader).unwrap();
    let deserialized: Option<LeaderId<TestConfig>> = fory.deserialize(&bytes).unwrap();
    assert_eq!(none_leader, deserialized);
}
