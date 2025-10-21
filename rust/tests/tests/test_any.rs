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
use std::any::Any;
use std::rc::Rc;
use std::sync::Arc;
use std::vec;

#[test]
fn test_box_dyn_any() {
    let fory = Fory::default();

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
    let fory = Fory::default();
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
    let fory = Fory::default();

    let value: Arc<dyn Any> = Arc::new("arc test".to_string());
    let bytes = fory.serialize(&value).unwrap();
    let deserialized: Arc<dyn Any> = fory.deserialize(&bytes).unwrap();
    assert_eq!(
        deserialized.downcast_ref::<String>().unwrap(),
        &"arc test".to_string()
    );

    let value2: Arc<dyn Any> = Arc::new(123i32);
    let bytes2 = fory.serialize(&value2).unwrap();
    let deserialized2: Arc<dyn Any> = fory.deserialize(&bytes2).unwrap();
    assert_eq!(deserialized2.downcast_ref::<i32>().unwrap(), &123i32);

    let value3: Arc<dyn Any> = Arc::new(vec![1, 2, 3]);
    let bytes3 = fory.serialize(&value3).unwrap();
    let deserialized3: Arc<dyn Any> = fory.deserialize(&bytes3).unwrap();
    assert_eq!(
        deserialized3.downcast_ref::<Vec<i32>>().unwrap(),
        &vec![1, 2, 3]
    );
}

#[test]
fn test_rc_dyn_any_shared_reference() {
    let fory = Fory::default();

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
    let fory = Fory::default();

    let shared_vec: Arc<dyn Any> = Arc::new(vec![1, 2, 3]);

    let data = vec![shared_vec.clone(), shared_vec.clone()];

    let bytes = fory.serialize(&data).unwrap();
    let deserialized: Vec<Arc<dyn Any>> = fory.deserialize(&bytes).unwrap();

    let first_vec = deserialized[0].downcast_ref::<Vec<i32>>().unwrap();
    let second_vec = deserialized[1].downcast_ref::<Vec<i32>>().unwrap();
    assert_eq!(first_vec, &vec![1, 2, 3]);
    assert_eq!(second_vec, &vec![1, 2, 3]);
    assert_eq!(Arc::strong_count(&shared_vec), 3);
}

#[test]
fn test_any_registered_by_name() {
    use fory_derive::ForyObject;

    #[derive(ForyObject, PartialEq, Debug)]
    struct Person {
        name: String,
        age: i32,
    }

    let mut fory = Fory::default();
    fory.register_by_namespace::<Person>("test", "Person")
        .unwrap();

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
    use fory_derive::ForyObject;

    #[derive(ForyObject, PartialEq, Debug)]
    struct Item {
        id: i32,
        value: String,
    }

    let mut fory = Fory::default();
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

#[derive(ForyObject, PartialEq, Debug)]
struct Container {
    id: i32,
    items: Vec<String>,
}

#[test]
fn test_arc_by_name() {
    let mut fory = Fory::default();
    fory.register_by_name::<Container>("Container").unwrap();

    let container = Container {
        id: 999,
        items: vec!["a".to_string(), "b".to_string(), "c".to_string()],
    };

    let value: Arc<dyn Any> = Arc::new(container);
    let bytes = fory.serialize(&value).unwrap();
    let deserialized: Arc<dyn Any> = fory.deserialize(&bytes).unwrap();

    let result = deserialized.downcast_ref::<Container>().unwrap();
    assert_eq!(result.id, 999);
    assert_eq!(result.items, vec!["a", "b", "c"]);

    let container_vec: Vec<Arc<dyn Any>> = vec![value.clone(), value.clone()];
    let bytes_vec = fory.serialize(&container_vec).unwrap();
    let deserialized_vec: Vec<Arc<dyn Any>> = fory.deserialize(&bytes_vec).unwrap();
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
fn test_rc_by_name() {
    let mut fory = Fory::default();
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
