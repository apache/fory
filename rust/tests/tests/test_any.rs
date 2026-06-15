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
use fory_derive::{ForyEnum, ForyStruct, ForyUnion};
use std::any::Any;
use std::collections::{HashMap, HashSet, LinkedList};
use std::rc::Rc;
use std::sync::Arc;
use std::vec;

fn assert_erased_container_error(message: String) {
    assert!(
        message.contains("top-level erased Any")
            || message.contains("Erased Any payloads require")
            || message.contains("not found in type_info registry"),
        "unexpected error: {message}"
    );
}

fn assert_box_any_unsupported<T: 'static>(fory: &Fory, value: T) {
    let wrapped: Box<dyn Any> = Box::new(value);
    match fory.serialize(&wrapped) {
        Ok(bytes) => {
            let result: Result<Box<dyn Any>, _> = fory.deserialize(&bytes);
            let err = result.expect_err("expected Box<dyn Any> container read to fail");
            assert_erased_container_error(err.to_string());
        }
        Err(err) => assert_erased_container_error(err.to_string()),
    }
}

fn assert_rc_any_unsupported<T: 'static>(fory: &Fory, value: T) {
    let wrapped: Rc<dyn Any> = Rc::new(value);
    match fory.serialize(&wrapped) {
        Ok(bytes) => {
            let result: Result<Rc<dyn Any>, _> = fory.deserialize(&bytes);
            let err = result.expect_err("expected Rc<dyn Any> container read to fail");
            assert_erased_container_error(err.to_string());
        }
        Err(err) => assert_erased_container_error(err.to_string()),
    }
}

fn assert_arc_any_unsupported<T: 'static + Send + Sync>(fory: &Fory, value: T) {
    let wrapped: Arc<dyn Any + Send + Sync> = Arc::new(value);
    match fory.serialize(&wrapped) {
        Ok(bytes) => {
            let result: Result<Arc<dyn Any + Send + Sync>, _> = fory.deserialize(&bytes);
            let err = result.expect_err("expected Arc<dyn Any> container read to fail");
            assert_erased_container_error(err.to_string());
        }
        Err(err) => assert_erased_container_error(err.to_string()),
    }
}

fn assert_box_any_values_unsupported(fory: &Fory, values: Vec<Box<dyn Any>>) {
    let result = fory
        .serialize(&values)
        .and_then(|bytes| fory.deserialize::<Vec<Box<dyn Any>>>(&bytes).map(|_| ()));
    let err = result.expect_err("expected Box<dyn Any> container values to fail");
    assert_erased_container_error(err.to_string());
}

fn assert_rc_any_values_unsupported(fory: &Fory, values: Vec<Rc<dyn Any>>) {
    let result = fory
        .serialize(&values)
        .and_then(|bytes| fory.deserialize::<Vec<Rc<dyn Any>>>(&bytes).map(|_| ()));
    let err = result.expect_err("expected Rc<dyn Any> container values to fail");
    assert_erased_container_error(err.to_string());
}

fn assert_arc_any_values_unsupported(fory: &Fory, values: Vec<Arc<dyn Any + Send + Sync>>) {
    let result = fory.serialize(&values).and_then(|bytes| {
        fory.deserialize::<Vec<Arc<dyn Any + Send + Sync>>>(&bytes)
            .map(|_| ())
    });
    let err = result.expect_err("expected Arc<dyn Any> container values to fail");
    assert_erased_container_error(err.to_string());
}

fn assert_box_any_map_unsupported(fory: &Fory, values: HashMap<String, Box<dyn Any>>) {
    let result = fory.serialize(&values).and_then(|bytes| {
        fory.deserialize::<HashMap<String, Box<dyn Any>>>(&bytes)
            .map(|_| ())
    });
    let err = result.expect_err("expected Box<dyn Any> map values to fail");
    assert_erased_container_error(err.to_string());
}

fn assert_rc_any_map_unsupported(fory: &Fory, values: HashMap<String, Rc<dyn Any>>) {
    let result = fory.serialize(&values).and_then(|bytes| {
        fory.deserialize::<HashMap<String, Rc<dyn Any>>>(&bytes)
            .map(|_| ())
    });
    let err = result.expect_err("expected Rc<dyn Any> map values to fail");
    assert_erased_container_error(err.to_string());
}

fn assert_arc_any_map_unsupported(
    fory: &Fory,
    values: HashMap<String, Arc<dyn Any + Send + Sync>>,
) {
    let result = fory.serialize(&values).and_then(|bytes| {
        fory.deserialize::<HashMap<String, Arc<dyn Any + Send + Sync>>>(&bytes)
            .map(|_| ())
    });
    let err = result.expect_err("expected Arc<dyn Any> map values to fail");
    assert_erased_container_error(err.to_string());
}

#[test]
fn test_box_dyn_any() {
    let fory = Fory::builder().xlang(false).compatible(false).build();

    let value: Box<dyn Any> = Box::new("hello".to_string());
    let bytes = fory.serialize(&value).unwrap();
    let deserialized: Box<dyn Any> = fory.deserialize(&bytes).unwrap();
    assert_eq!(
        deserialized.downcast_ref::<String>().unwrap(),
        &"hello".to_string()
    );

    let value2: Box<dyn Any> = Box::new(42i32);
    let bytes2 = fory.serialize(&value2).unwrap();
    let deserialized2: Box<dyn Any> = fory.deserialize(&bytes2).unwrap();
    assert_eq!(deserialized2.downcast_ref::<i32>().unwrap(), &42i32);

    let value3: Box<dyn Any> = Box::new("".to_string());
    let bytes3 = fory.serialize(&value3).unwrap();
    let deserialized3: Box<dyn Any> = fory.deserialize(&bytes3).unwrap();
    assert_eq!(deserialized3.downcast_ref::<String>().unwrap(), "");

    let value5: Box<dyn Any> = Box::new(3.15f64);
    let bytes5 = fory.serialize(&value5).unwrap();
    let deserialized5: Box<dyn Any> = fory.deserialize(&bytes5).unwrap();
    assert_eq!(deserialized5.downcast_ref::<f64>().unwrap(), &3.15f64);
}

#[test]
fn test_rc_dyn_any() {
    let fory = Fory::builder().xlang(false).compatible(false).build();
    let value: Rc<dyn Any> = Rc::new("world".to_string());
    let bytes = fory.serialize(&value).unwrap();
    let deserialized: Rc<dyn Any> = fory.deserialize(&bytes).unwrap();
    assert_eq!(
        deserialized.downcast_ref::<String>().unwrap(),
        &"world".to_string()
    );

    let value2: Rc<dyn Any> = Rc::new(99i32);
    let bytes2 = fory.serialize(&value2).unwrap();
    let deserialized2: Rc<dyn Any> = fory.deserialize(&bytes2).unwrap();
    assert_eq!(deserialized2.downcast_ref::<i32>().unwrap(), &99i32);

    let value3: Rc<dyn Any> = Rc::new(true);
    let bytes3 = fory.serialize(&value3).unwrap();
    let deserialized3: Rc<dyn Any> = fory.deserialize(&bytes3).unwrap();
    assert_eq!(deserialized3.downcast_ref::<bool>().unwrap(), &true);
}

#[test]
fn test_arc_dyn_any() {
    let fory = Fory::builder().xlang(false).compatible(false).build();

    let value: Arc<dyn Any + Send + Sync> = Arc::new("arc test".to_string());
    let bytes = fory.serialize(&value).unwrap();
    let deserialized: Arc<dyn Any + Send + Sync> = fory.deserialize(&bytes).unwrap();
    assert_eq!(
        deserialized.downcast_ref::<String>().unwrap(),
        &"arc test".to_string()
    );

    let value2: Arc<dyn Any + Send + Sync> = Arc::new(123i32);
    let bytes2 = fory.serialize(&value2).unwrap();
    let deserialized2: Arc<dyn Any + Send + Sync> = fory.deserialize(&bytes2).unwrap();
    assert_eq!(deserialized2.downcast_ref::<i32>().unwrap(), &123i32);

    let value3: Arc<dyn Any + Send + Sync> = Arc::new(true);
    let bytes3 = fory.serialize(&value3).unwrap();
    let deserialized3: Arc<dyn Any + Send + Sync> = fory.deserialize(&bytes3).unwrap();
    assert_eq!(deserialized3.downcast_ref::<bool>().unwrap(), &true);
}

#[test]
fn test_rc_dyn_any_shared_reference() {
    let fory = Fory::builder().xlang(false).compatible(false).build();

    let shared_str: Rc<dyn Any> = Rc::new("shared".to_string());

    let data = vec![shared_str.clone(), shared_str.clone()];

    let bytes = fory.serialize(&data).unwrap();
    let deserialized: Vec<Rc<dyn Any>> = fory.deserialize(&bytes).unwrap();

    let first_str = deserialized[0].downcast_ref::<String>().unwrap();
    let second_str = deserialized[1].downcast_ref::<String>().unwrap();

    assert_eq!(first_str, "shared");
    assert_eq!(second_str, "shared");
    assert_eq!(Rc::strong_count(&shared_str), 3);
}

#[test]
fn test_arc_dyn_any_shared_reference() {
    let fory = Fory::builder().xlang(false).compatible(false).build();

    let shared_vec: Arc<dyn Any + Send + Sync> = Arc::new("shared".to_string());

    let data = vec![shared_vec.clone(), shared_vec.clone()];

    let bytes = fory.serialize(&data).unwrap();
    let deserialized: Vec<Arc<dyn Any + Send + Sync>> = fory.deserialize(&bytes).unwrap();

    let first_vec = deserialized[0].downcast_ref::<String>().unwrap();
    let second_vec = deserialized[1].downcast_ref::<String>().unwrap();
    assert_eq!(first_vec, "shared");
    assert_eq!(second_vec, "shared");
    assert_eq!(Arc::strong_count(&shared_vec), 3);
}

#[test]
fn test_any_registered_by_name() {
    use fory_derive::ForyStruct;

    #[derive(ForyStruct, PartialEq, Debug)]
    struct Person {
        name: String,
        age: i32,
    }

    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register_by_name::<Person>("test.Person").unwrap();

    let person = Person {
        name: "Alice".to_string(),
        age: 30,
    };

    let value: Box<dyn Any> = Box::new(person);
    let bytes = fory.serialize(&value).unwrap();
    let deserialized: Box<dyn Any> = fory.deserialize(&bytes).unwrap();

    let result = deserialized.downcast_ref::<Person>().unwrap();
    assert_eq!(result.name, "Alice");
    assert_eq!(result.age, 30);
}

#[test]
fn test_mixed_any_types() {
    use fory_derive::ForyStruct;

    #[derive(ForyStruct, PartialEq, Debug)]
    struct Item {
        id: i32,
        value: String,
    }

    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register_by_name::<Item>("Item").unwrap();

    let item = Item {
        id: 123,
        value: "test".to_string(),
    };

    let mixed: Vec<Box<dyn Any>> = vec![
        Box::new(42i32),
        Box::new("hello".to_string()),
        Box::new(item),
        Box::new(3.15f64),
    ];

    let bytes = fory.serialize(&mixed).unwrap();
    let deserialized: Vec<Box<dyn Any>> = fory.deserialize(&bytes).unwrap();

    assert_eq!(deserialized[0].downcast_ref::<i32>().unwrap(), &42i32);
    assert_eq!(deserialized[1].downcast_ref::<String>().unwrap(), "hello");

    let item_result = deserialized[2].downcast_ref::<Item>().unwrap();
    assert_eq!(item_result.id, 123);
    assert_eq!(item_result.value, "test");

    assert_eq!(deserialized[3].downcast_ref::<f64>().unwrap(), &3.15f64);
}

#[derive(ForyStruct, PartialEq, Debug)]
struct Container {
    id: i32,
    items: Vec<String>,
}

#[derive(ForyStruct, PartialEq, Debug)]
struct RcRefPayload {
    name: String,
    shared: Rc<String>,
}

#[derive(ForyStruct, PartialEq, Debug)]
struct ArcRefPayload {
    name: String,
    shared: Arc<String>,
}

#[test]
fn wrapped_container_box_any() {
    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register_by_name::<Container>("Container").unwrap();

    let value: Box<dyn Any> = Box::new(Container {
        id: 321,
        items: vec!["wrapped".to_string(), "values".to_string()],
    });
    let bytes = fory.serialize(&value).unwrap();
    let decoded: Box<dyn Any> = fory.deserialize(&bytes).unwrap();
    let result = decoded.downcast_ref::<Container>().unwrap();

    assert_eq!(result.id, 321);
    assert_eq!(result.items, vec!["wrapped", "values"]);
}

#[test]
fn rc_any_refvalue_keeps_outer_ref() {
    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register_by_name::<RcRefPayload>("RcRefPayload")
        .unwrap();

    let payload: Rc<dyn Any> = Rc::new(RcRefPayload {
        name: "outer".to_string(),
        shared: Rc::new("nested".to_string()),
    });
    let values = vec![payload.clone(), payload.clone()];

    let bytes = fory.serialize(&values).unwrap();
    let decoded: Vec<Rc<dyn Any>> = fory.deserialize(&bytes).unwrap();

    assert!(Rc::ptr_eq(&decoded[0], &decoded[1]));
    let payload = decoded[0].downcast_ref::<RcRefPayload>().unwrap();
    assert_eq!(payload.name, "outer");
    assert_eq!(payload.shared.as_str(), "nested");
}

#[test]
fn arc_any_refvalue_keeps_outer_ref() {
    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register_by_name::<ArcRefPayload>("ArcRefPayload")
        .unwrap();

    let payload: Arc<dyn Any + Send + Sync> = Arc::new(ArcRefPayload {
        name: "outer".to_string(),
        shared: Arc::new("nested".to_string()),
    });
    let values = vec![payload.clone(), payload.clone()];

    let bytes = fory.serialize(&values).unwrap();
    let decoded: Vec<Arc<dyn Any + Send + Sync>> = fory.deserialize(&bytes).unwrap();

    assert!(Arc::ptr_eq(&decoded[0], &decoded[1]));
    let payload = decoded[0].downcast_ref::<ArcRefPayload>().unwrap();
    assert_eq!(payload.name, "outer");
    assert_eq!(payload.shared.as_str(), "nested");
}

#[test]
fn generic_containers_rejected_in_any() {
    let fory = Fory::builder().xlang(false).compatible(false).build();

    assert_box_any_unsupported(&fory, vec![1_i32, 2, 3]);
    assert_rc_any_unsupported(&fory, vec![1_i32, 2, 3]);
    assert_arc_any_unsupported(&fory, vec![1_i32, 2, 3]);

    assert_box_any_unsupported(&fory, LinkedList::from([1_i32, 2, 3]));
    assert_rc_any_unsupported(&fory, HashSet::from([1_i32, 2, 3]));
    assert_arc_any_unsupported(
        &fory,
        HashMap::from([("one".to_string(), 1_i32), ("two".to_string(), 2)]),
    );
}

#[test]
fn any_collection_rejects_containers() {
    let fory = Fory::builder().xlang(false).compatible(false).build();

    assert_box_any_values_unsupported(&fory, vec![Box::new(vec![1_i32, 2, 3]) as Box<dyn Any>]);
    assert_box_any_values_unsupported(
        &fory,
        vec![
            Box::new(vec![1_i32, 2, 3]) as Box<dyn Any>,
            Box::new(vec![4_i32, 5, 6]) as Box<dyn Any>,
        ],
    );

    assert_rc_any_values_unsupported(&fory, vec![Rc::new(vec![1_i32, 2, 3]) as Rc<dyn Any>]);
    assert_rc_any_values_unsupported(
        &fory,
        vec![
            Rc::new(vec![1_i32, 2, 3]) as Rc<dyn Any>,
            Rc::new(vec![4_i32, 5, 6]) as Rc<dyn Any>,
        ],
    );

    assert_arc_any_values_unsupported(
        &fory,
        vec![Arc::new(vec![1_i32, 2, 3]) as Arc<dyn Any + Send + Sync>],
    );
    assert_arc_any_values_unsupported(
        &fory,
        vec![
            Arc::new(vec![1_i32, 2, 3]) as Arc<dyn Any + Send + Sync>,
            Arc::new(vec![4_i32, 5, 6]) as Arc<dyn Any + Send + Sync>,
        ],
    );
}

#[test]
fn any_map_values_reject_containers() {
    let fory = Fory::builder().xlang(false).compatible(false).build();

    assert_box_any_map_unsupported(
        &fory,
        HashMap::from([(
            "list".to_string(),
            Box::new(vec![1_i32, 2, 3]) as Box<dyn Any>,
        )]),
    );
    assert_rc_any_map_unsupported(
        &fory,
        HashMap::from([(
            "map".to_string(),
            Rc::new(HashMap::from([("one".to_string(), 1_i32)])) as Rc<dyn Any>,
        )]),
    );
    assert_arc_any_map_unsupported(
        &fory,
        HashMap::from([(
            "list".to_string(),
            Arc::new(vec![1_i32, 2, 3]) as Arc<dyn Any + Send + Sync>,
        )]),
    );
}

#[test]
fn compatible_enum_box_any_read() {
    #[derive(ForyEnum, Debug, Default, PartialEq)]
    enum Status {
        #[default]
        Active,
        Inactive,
    }

    let mut fory = Fory::builder().xlang(false).compatible(true).build();
    fory.register::<Status>(710).unwrap();

    let value: Box<dyn Any> = Box::new(Status::Inactive);
    let bytes = fory.serialize(&value).unwrap();
    let decoded: Box<dyn Any> = fory.deserialize(&bytes).unwrap();

    assert_eq!(decoded.downcast_ref::<Status>().unwrap(), &Status::Inactive);
}

#[test]
fn compatible_union_rc_any_read() {
    #[derive(ForyUnion, Debug, PartialEq)]
    enum Event {
        #[fory(unknown)]
        Unknown(fory_core::UnknownCase),
        #[fory(id = 0, default)]
        Value(String),
    }

    let mut fory = Fory::builder().xlang(false).compatible(true).build();
    fory.register_union::<Event>(711).unwrap();

    let value: Rc<dyn Any> = Rc::new(Event::Value("compatible".to_string()));
    let bytes = fory.serialize(&value).unwrap();
    let decoded: Rc<dyn Any> = fory.deserialize(&bytes).unwrap();

    assert_eq!(
        decoded.downcast_ref::<Event>().unwrap(),
        &Event::Value("compatible".to_string())
    );
}

#[derive(ForyStruct)]
struct ArcAnyHolder {
    value: Arc<dyn Any + Send + Sync>,
}

#[derive(ForyStruct)]
struct AnyMapVarKey {
    #[fory(id = 0)]
    values: HashMap<u32, Rc<dyn Any>>,
}

#[derive(ForyStruct)]
struct AnyMapFixedKey {
    #[fory(id = 0, map(key(encoding = fixed)))]
    values: HashMap<u32, Rc<dyn Any>>,
}

#[test]
fn test_hashmap_fixed_key_rc_any_field_compatible() {
    let mut writer = Fory::builder().xlang(false).compatible(true).build();
    writer.register::<AnyMapFixedKey>(700).unwrap();

    let mut reader = Fory::builder().xlang(false).compatible(true).build();
    reader.register::<AnyMapVarKey>(700).unwrap();

    let original = AnyMapFixedKey {
        values: HashMap::from([
            (1, Rc::new(42_i32) as Rc<dyn Any>),
            (2, Rc::new("answer".to_string()) as Rc<dyn Any>),
        ]),
    };

    let bytes = writer.serialize(&original).unwrap();
    let decoded: AnyMapVarKey = reader.deserialize(&bytes).unwrap();

    assert_eq!(
        decoded.values.get(&1).unwrap().downcast_ref::<i32>(),
        Some(&42)
    );
    assert_eq!(
        decoded.values.get(&2).unwrap().downcast_ref::<String>(),
        Some(&"answer".to_string())
    );
}

#[test]
fn test_arc_by_name() {
    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register_by_name::<Container>("Container").unwrap();

    let container = Container {
        id: 999,
        items: vec!["a".to_string(), "b".to_string(), "c".to_string()],
    };

    let value: Arc<dyn Any + Send + Sync> = Arc::new(container);
    let bytes = fory.serialize(&value).unwrap();
    let deserialized: Arc<dyn Any + Send + Sync> = fory.deserialize(&bytes).unwrap();

    let result = deserialized.downcast_ref::<Container>().unwrap();
    assert_eq!(result.id, 999);
    assert_eq!(result.items, vec!["a", "b", "c"]);

    let container_vec: Vec<Arc<dyn Any + Send + Sync>> = vec![value.clone(), value.clone()];
    let bytes_vec = fory.serialize(&container_vec).unwrap();
    let deserialized_vec: Vec<Arc<dyn Any + Send + Sync>> = fory.deserialize(&bytes_vec).unwrap();
    assert_eq!(deserialized_vec.len(), 2);
    let first = deserialized_vec[0].downcast_ref::<Container>().unwrap();
    let second = deserialized_vec[1].downcast_ref::<Container>().unwrap();
    assert_eq!(first, second);
    assert!(std::sync::Arc::ptr_eq(
        &deserialized_vec[0],
        &deserialized_vec[1]
    ));
}

#[test]
fn test_arc_any_field_by_name() {
    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register_by_name::<Container>("Container").unwrap();
    fory.register_by_name::<ArcAnyHolder>("ArcAnyHolder")
        .unwrap();

    let holder = ArcAnyHolder {
        value: Arc::new(Container {
            id: 777,
            items: vec!["shared".to_string(), "any".to_string()],
        }),
    };

    let bytes = fory.serialize(&holder).unwrap();
    let decoded: ArcAnyHolder = fory.deserialize(&bytes).unwrap();
    let container = decoded.value.downcast_ref::<Container>().unwrap();

    assert_eq!(container.id, 777);
    assert_eq!(container.items, vec!["shared", "any"]);
}

#[test]
fn test_rc_by_name() {
    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register_by_name::<Container>("Container").unwrap();

    let container = Container {
        id: 555,
        items: vec!["x".to_string(), "y".to_string()],
    };

    let value: Rc<dyn Any> = Rc::new(container);
    let bytes = fory.serialize(&value).unwrap();
    let deserialized: Rc<dyn Any> = fory.deserialize(&bytes).unwrap();

    let result = deserialized.downcast_ref::<Container>().unwrap();
    assert_eq!(result.id, 555);
    assert_eq!(result.items, vec!["x", "y"]);

    let container_vec: Vec<Rc<dyn Any>> = vec![value.clone(), value.clone()];
    let bytes_vec = fory.serialize(&container_vec).unwrap();
    let deserialized_vec: Vec<Rc<dyn Any>> = fory.deserialize(&bytes_vec).unwrap();
    assert_eq!(deserialized_vec.len(), 2);
    let first = deserialized_vec[0].downcast_ref::<Container>().unwrap();
    let second = deserialized_vec[1].downcast_ref::<Container>().unwrap();
    assert_eq!(first, second);
    assert!(std::rc::Rc::ptr_eq(
        &deserialized_vec[0],
        &deserialized_vec[1]
    ));
}

// Tests for GitHub issue: multiple Vec<Struct> types wrapped in Box<dyn Any>
// This reproduces the non-deterministic deserialization failures
#[derive(ForyStruct, Debug, Clone, PartialEq)]
struct StructA {
    a: i32,
}

#[derive(ForyStruct, Debug, Clone, PartialEq)]
struct StructB {
    i: i32,
}

/// Test that different Vec<T> types in Box<dyn Any> return a clear error.
///
/// Different generic container instantiations share broad container metadata, so
/// they cannot be used as unambiguous top-level erased Any payloads.
///
/// Previously this caused non-deterministic failures. Now it returns a clear error message.
#[test]
fn generic_vecs_rejected_in_box_any() {
    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register_by_name::<StructA>("StructA").unwrap();
    fory.register_by_name::<StructB>("StructB").unwrap();

    assert_box_any_unsupported(&fory, vec![StructA { a: 11 }; 5]);
    assert_box_any_unsupported(&fory, vec![StructB { i: 1 }; 5]);
}
