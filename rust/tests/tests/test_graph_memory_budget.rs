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

use fory_core::{Error, Fory, Reader};
use fory_derive::ForyStruct;
use std::collections::HashMap;
use std::mem;

const DEFAULT_GRAPH_MEMORY_BYTES: i64 = 128 * 1024 * 1024;

#[derive(ForyStruct, Debug, PartialEq)]
struct BudgetSiblings {
    first: Vec<String>,
    second: Vec<String>,
}

#[derive(ForyStruct, Debug, PartialEq)]
struct BudgetItem {
    left: u64,
    right: u64,
}

#[derive(ForyStruct, Debug, PartialEq)]
struct BudgetEmpty;

#[derive(ForyStruct, Debug)]
struct ListWireInts {
    values: Vec<Option<i32>>,
}

#[derive(ForyStruct, Debug, PartialEq)]
struct DenseWireInts {
    values: Vec<i32>,
}

fn fory_with_budget(max_graph_memory_bytes: i64) -> Fory {
    let mut fory = Fory::builder()
        .xlang(false)
        .compatible(false)
        .max_graph_memory_bytes(max_graph_memory_bytes)
        .build();
    fory.register_by_name::<BudgetSiblings>("BudgetSiblings")
        .unwrap();
    fory.register_by_name::<BudgetItem>("BudgetItem").unwrap();
    fory.register_by_name::<BudgetEmpty>("BudgetEmpty").unwrap();
    fory
}

fn compatible_fory<T>(max_graph_memory_bytes: i64) -> Fory
where
    T: fory_core::Serializer + fory_core::StructSerializer + fory_core::ForyDefault,
{
    let mut fory = Fory::builder()
        .xlang(false)
        .compatible(true)
        .max_graph_memory_bytes(max_graph_memory_bytes)
        .build();
    fory.register::<T>(88_001).unwrap();
    fory
}

fn compact_empty_lists(count: usize) -> Vec<Vec<String>> {
    (0..count).map(|_| Vec::new()).collect()
}

#[test]
fn config_validation() {
    assert_eq!(
        Fory::builder().build().config().max_graph_memory_bytes,
        DEFAULT_GRAPH_MEMORY_BYTES
    );
    assert_eq!(
        Fory::builder()
            .max_graph_memory_bytes(0)
            .build()
            .config()
            .max_graph_memory_bytes,
        0
    );
    assert_eq!(
        Fory::builder()
            .max_graph_memory_bytes(-2)
            .build()
            .config()
            .max_graph_memory_bytes,
        -2
    );
    let _ = Fory::builder().max_graph_memory_bytes(1).build();
}

#[test]
fn non_positive_budget_disables_enforcement() {
    let value: Vec<String> = Vec::new();
    let writer = fory_with_budget(DEFAULT_GRAPH_MEMORY_BYTES);
    let bytes = writer.serialize(&value).unwrap();

    assert!(fory_with_budget(1)
        .deserialize::<Vec<String>>(&bytes)
        .is_err());
    assert!(fory_with_budget(0)
        .deserialize::<Vec<String>>(&bytes)
        .is_ok());
}

#[test]
fn byte_root_uses_fixed_default_budget() {
    let value = compact_empty_lists(12000);
    let writer = fory_with_budget(DEFAULT_GRAPH_MEMORY_BYTES);
    let bytes = writer.serialize(&value).unwrap();
    let decoded = writer.deserialize::<Vec<Vec<String>>>(&bytes).unwrap();
    assert_eq!(decoded, value);
}

#[test]
fn reader_root_uses_fixed_default_budget() {
    let value = compact_empty_lists(12000);
    let writer = fory_with_budget(DEFAULT_GRAPH_MEMORY_BYTES);
    let bytes = writer.serialize(&value).unwrap();

    let mut reader = Reader::new(&bytes);
    let decoded = writer
        .deserialize_from::<Vec<Vec<String>>>(&mut reader)
        .unwrap();
    assert_eq!(decoded, value);
}

#[test]
fn explicit_override() {
    let value = compact_empty_lists(12000);
    let writer = fory_with_budget(DEFAULT_GRAPH_MEMORY_BYTES);
    let bytes = writer.serialize(&value).unwrap();

    let vec_bytes = mem::size_of::<Vec<String>>();
    let estimate = mem::size_of::<Vec<Vec<String>>>() + value.len() * vec_bytes;
    let limited = fory_with_budget((estimate - 1) as i64);
    assert!(limited.deserialize::<Vec<Vec<String>>>(&bytes).is_err());
    let explicit = fory_with_budget(estimate as i64);
    let decoded: Vec<Vec<String>> = explicit.deserialize(&bytes).unwrap();
    assert_eq!(decoded, value);
}

#[test]
fn empty_collection_owner_self() {
    let value: Vec<String> = Vec::new();
    let writer = fory_with_budget(DEFAULT_GRAPH_MEMORY_BYTES);
    let bytes = writer.serialize(&value).unwrap();

    let limited = fory_with_budget((mem::size_of::<Vec<String>>() - 1) as i64);
    assert!(limited.deserialize::<Vec<String>>(&bytes).is_err());

    let limited = fory_with_budget(mem::size_of::<Vec<String>>() as i64);
    let decoded: Vec<String> = limited.deserialize(&bytes).unwrap();
    assert!(decoded.is_empty());
}

#[test]
fn empty_struct_owner_self() {
    let value = BudgetEmpty;
    let writer = fory_with_budget(DEFAULT_GRAPH_MEMORY_BYTES);
    let bytes = writer.serialize(&value).unwrap();

    assert_eq!(
        fory_with_budget(1)
            .deserialize::<BudgetEmpty>(&bytes)
            .unwrap(),
        value
    );

    let values = vec![BudgetEmpty, BudgetEmpty, BudgetEmpty];
    let bytes = writer.serialize(&values).unwrap();
    let required = mem::size_of::<Vec<BudgetEmpty>>() + values.len();
    assert!(fory_with_budget((required - 1) as i64)
        .deserialize::<Vec<BudgetEmpty>>(&bytes)
        .is_err());
    assert_eq!(
        fory_with_budget(required as i64)
            .deserialize::<Vec<BudgetEmpty>>(&bytes)
            .unwrap(),
        values
    );
}

#[test]
fn sibling_cumulative_budget() {
    let value = BudgetSiblings {
        first: vec!["a".to_string()],
        second: vec!["b".to_string()],
    };
    let writer = fory_with_budget(DEFAULT_GRAPH_MEMORY_BYTES);
    let bytes = writer.serialize(&value).unwrap();
    let root = mem::size_of::<BudgetSiblings>() as i64;
    let one_vec = mem::size_of::<String>() as i64;

    let limited = fory_with_budget(root + one_vec);
    assert!(limited.deserialize::<BudgetSiblings>(&bytes).is_err());
    let enough = fory_with_budget(root + one_vec * 2);
    assert_eq!(enough.deserialize::<BudgetSiblings>(&bytes).unwrap(), value);
}

#[test]
fn map_budget() {
    let value: HashMap<String, i32> = HashMap::from([("a".to_string(), 1)]);
    let writer = fory_with_budget(DEFAULT_GRAPH_MEMORY_BYTES);
    let bytes = writer.serialize(&value).unwrap();
    let required = (mem::size_of::<HashMap<String, i32>>()
        + mem::size_of::<String>()
        + mem::size_of::<i32>()) as i64;

    let limited = fory_with_budget(required - 1);
    assert!(limited.deserialize::<HashMap<String, i32>>(&bytes).is_err());
    assert_eq!(
        fory_with_budget(required)
            .deserialize::<HashMap<String, i32>>(&bytes)
            .unwrap(),
        value
    );
}

#[test]
fn inline_value_vec_budget() {
    let value = (0..16)
        .map(|i| BudgetItem {
            left: i,
            right: i + 1,
        })
        .collect::<Vec<_>>();
    let writer = fory_with_budget(DEFAULT_GRAPH_MEMORY_BYTES);
    let bytes = writer.serialize(&value).unwrap();
    let under_inline = mem::size_of::<Vec<BudgetItem>>() + value.len() * mem::size_of::<u64>();

    let limited = fory_with_budget(under_inline as i64);
    assert!(limited.deserialize::<Vec<BudgetItem>>(&bytes).is_err());
}

#[test]
fn box_vector_owner_self() {
    let value = Box::new(
        (0..4)
            .map(|i| BudgetItem {
                left: i,
                right: i + 1,
            })
            .collect::<Vec<_>>(),
    );
    let writer = fory_with_budget(DEFAULT_GRAPH_MEMORY_BYTES);
    let bytes = writer.serialize(&value).unwrap();
    let required = mem::size_of::<Vec<BudgetItem>>() + value.len() * mem::size_of::<BudgetItem>();

    assert!(fory_with_budget((required - 1) as i64)
        .deserialize::<Box<Vec<BudgetItem>>>(&bytes)
        .is_err());
    assert_eq!(
        fory_with_budget(required as i64)
            .deserialize::<Box<Vec<BudgetItem>>>(&bytes)
            .unwrap(),
        value
    );
}

#[test]
fn compatible_list_array_budget() {
    let value = ListWireInts {
        values: (0..64).map(Some).collect(),
    };
    let writer = compatible_fory::<ListWireInts>(DEFAULT_GRAPH_MEMORY_BYTES);
    let bytes = writer.serialize(&value).unwrap();

    let required = mem::size_of::<DenseWireInts>() + 64 * mem::size_of::<i32>();
    let limited = compatible_fory::<DenseWireInts>((required - 1) as i64);
    assert!(limited.deserialize::<DenseWireInts>(&bytes).is_err());

    let enough = compatible_fory::<DenseWireInts>(required as i64);
    let decoded = enough.deserialize::<DenseWireInts>(&bytes).unwrap();
    assert_eq!(
        decoded,
        DenseWireInts {
            values: (0..64).collect()
        }
    );
}

#[test]
fn dense_paths_skipped() {
    let fory = fory_with_budget(1);

    let string_bytes = fory_with_budget(DEFAULT_GRAPH_MEMORY_BYTES)
        .serialize(&"hello".to_string())
        .unwrap();
    let decoded: String = fory.deserialize(&string_bytes).unwrap();
    assert_eq!(decoded, "hello");

    let binary = vec![1_u8, 2, 3, 4];
    let binary_bytes = fory_with_budget(DEFAULT_GRAPH_MEMORY_BYTES)
        .serialize(&binary)
        .unwrap();
    let decoded: Vec<u8> = fory.deserialize(&binary_bytes).unwrap();
    assert_eq!(decoded, binary);

    let ints = vec![1_i32, 2, 3, 4];
    let int_bytes = fory_with_budget(DEFAULT_GRAPH_MEMORY_BYTES)
        .serialize(&ints)
        .unwrap();
    let decoded: Vec<i32> = fory.deserialize(&int_bytes).unwrap();
    assert_eq!(decoded, ints);
}

#[test]
fn byte_check_preserved() {
    let writer = fory_with_budget(DEFAULT_GRAPH_MEMORY_BYTES);
    let mut bytes = writer.serialize(&Vec::<i32>::new()).unwrap();
    let last = bytes.len() - 1;
    bytes[last] = 64;

    let reader = fory_with_budget(i64::MAX);
    let err = reader.deserialize::<Vec<i32>>(&bytes).unwrap_err();
    assert!(matches!(err, Error::BufferOutOfBound(..)), "{err}");
}
