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
use fory_core::serializer::weak::{ArcWeak, RcWeak};
use fory_derive::ForyObject;
use std::cell::RefCell;
use std::rc::Rc;
use std::sync::Arc;
use std::sync::Mutex;

#[test]
fn test_rc_weak_null_serialization() {
    let fory = Fory::default().track_ref(true);

    let weak: RcWeak<i32> = RcWeak::new();

    let serialized = fory.serialize(&weak).unwrap();
    let deserialized: RcWeak<i32> = fory.deserialize(&serialized).unwrap();

    assert!(deserialized.upgrade().is_none());
}

#[test]
fn test_arc_weak_null_serialization() {
    let fory = Fory::default().track_ref(true);

    let weak: ArcWeak<i32> = ArcWeak::new();

    let serialized = fory.serialize(&weak).unwrap();
    let deserialized: ArcWeak<i32> = fory.deserialize(&serialized).unwrap();

    assert!(deserialized.upgrade().is_none());
}

#[test]
fn test_rc_weak_dead_pointer_serializes_as_null() {
    let fory = Fory::default().track_ref(true);

    let weak = {
        let rc = Rc::new(42i32);
        RcWeak::from(&rc)
        // rc is dropped here
    };

    // Weak is now dead
    assert!(weak.upgrade().is_none());

    // Should serialize as Null
    let serialized = fory.serialize(&weak).unwrap();
    let deserialized: RcWeak<i32> = fory.deserialize(&serialized).unwrap();

    assert!(deserialized.upgrade().is_none());
}

#[test]
fn test_arc_weak_dead_pointer_serializes_as_null() {
    let fory = Fory::default().track_ref(true);

    let weak = {
        let arc = Arc::new(String::from("test"));
        ArcWeak::from(&arc)
        // arc is dropped here
    };

    // Weak is now dead
    assert!(weak.upgrade().is_none());

    // Should serialize as Null
    let serialized = fory.serialize(&weak).unwrap();
    let deserialized: ArcWeak<String> = fory.deserialize(&serialized).unwrap();

    assert!(deserialized.upgrade().is_none());
}

#[test]
fn test_rc_weak_in_vec_circular_reference() {
    let fory = Fory::default().track_ref(true);

    let data1 = Rc::new(42i32);
    let data2 = Rc::new(100i32);

    let weak1 = RcWeak::from(&data1);
    let weak2 = RcWeak::from(&data2);
    let weak3 = weak1.clone();

    let weaks = vec![weak1, weak2, weak3];
    let serialized = fory.serialize(&weaks).unwrap();
    let deserialized: Vec<RcWeak<i32>> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized.len(), 3);
}

#[test]
fn test_arc_weak_in_vec_circular_reference() {
    let fory = Fory::default().track_ref(true);

    let data1 = Arc::new(String::from("hello"));
    let data2 = Arc::new(String::from("world"));

    let weak1 = ArcWeak::from(&data1);
    let weak2 = ArcWeak::from(&data2);
    let weak3 = weak1.clone();

    let weaks = vec![weak1, weak2, weak3];
    let serialized = fory.serialize(&weaks).unwrap();
    let deserialized: Vec<ArcWeak<String>> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized.len(), 3);
}

#[test]
fn test_rc_weak_field_in_struct() {
    use fory_derive::ForyObject;

    #[derive(ForyObject, Debug)]
    struct SimpleNode {
        value: i32,
        weak_ref: RcWeak<i32>,
    }

    let mut fory = Fory::default().track_ref(true);
    fory.register::<SimpleNode>(1000).unwrap();

    let data = Rc::new(42i32);
    let node = SimpleNode {
        value: 1,
        weak_ref: RcWeak::from(&data),
    };

    let serialized = fory.serialize(&node).unwrap();
    let deserialized: SimpleNode = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized.value, 1);
}

#[derive(ForyObject, Debug)]
struct Node {
    value: i32,
    // Weak ref to parent Rc<RefCell<Node>>
    parent: RcWeak<RefCell<Node>>,
    // Strong refs to children Rc<RefCell<Node>>
    children: Vec<Rc<RefCell<Node>>>,
}

#[test]
fn test_node_circular_reference_with_parent_children() {
    // Register the Node type with Fory
    let mut fory = Fory::default().track_ref(true);
    fory.register::<Node>(2000).unwrap();

    // Create parent
    let parent = Rc::new(RefCell::new(Node {
        value: 1,
        parent: RcWeak::new(),
        children: vec![],
    }));

    // Create children pointing back to parent via weak ref
    let child1 = Rc::new(RefCell::new(Node {
        value: 2,
        parent: RcWeak::new(),
        children: vec![],
    }));

    let child2 = Rc::new(RefCell::new(Node {
        value: 3,
        parent: RcWeak::new(),
        children: vec![],
    }));

    // Add children to parent's children list
    parent.borrow_mut().children.push(child1.clone());
    parent.borrow_mut().children.push(child2.clone());

    // Set children's parent weak refs to point back to parent (creating circular reference)
    child1.borrow_mut().parent = RcWeak::from(&parent);
    child2.borrow_mut().parent = RcWeak::from(&parent);

    // --- Serialize the parent node (will include children recursively) ---
    let serialized = fory.serialize(&parent).unwrap();

    // --- Deserialize ---
    let deserialized: Rc<RefCell<Node>> = fory.deserialize(&serialized).unwrap();

    // --- Verify ---
    let des_parent = deserialized.borrow();
    assert_eq!(des_parent.value, 1);
    assert_eq!(des_parent.children.len(), 2);

    for child in &des_parent.children {
        let upgraded_parent = child.borrow().parent.upgrade();
        assert!(upgraded_parent.is_some());
        assert!(Rc::ptr_eq(&deserialized, &upgraded_parent.unwrap()));
    }
}

#[test]
fn test_arc_mutex_circular_reference() {
    #[derive(ForyObject)]
    struct Node {
        val: i32,
        parent: ArcWeak<Mutex<Node>>,
        children: Vec<Arc<Mutex<Node>>>,
    }

    let mut fory = Fory::default().track_ref(true);
    fory.register::<Node>(6000).unwrap();

    let parent = Arc::new(Mutex::new(Node {
        val: 10,
        parent: ArcWeak::new(),
        children: vec![],
    }));

    let child1 = Arc::new(Mutex::new(Node {
        val: 20,
        parent: ArcWeak::from(&parent),
        children: vec![],
    }));

    let child2 = Arc::new(Mutex::new(Node {
        val: 30,
        parent: ArcWeak::from(&parent),
        children: vec![],
    }));

    parent.lock().unwrap().children.push(child1.clone());
    parent.lock().unwrap().children.push(child2.clone());

    let serialized = fory.serialize(&parent).unwrap();
    let deserialized: Arc<Mutex<Node>> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized.lock().unwrap().children.len(), 2);
    for child in &deserialized.lock().unwrap().children {
        let upgraded_parent = child.lock().unwrap().parent.upgrade().unwrap();
        assert!(Arc::ptr_eq(&deserialized, &upgraded_parent));
    }
}
