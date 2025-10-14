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
use std::cell::RefCell;
use std::rc::Rc;

#[derive(ForyObject, Debug)]
struct Simple {
    value: i32,
}

#[test]
fn test_rc_refcell_simple() {
    let mut fory = Fory::default();
    fory.register::<Simple>(3000).unwrap();

    let node = Rc::new(RefCell::new(Simple { value: 42 }));
    let serialized = fory.serialize(&node).unwrap();
    let deserialized: Rc<RefCell<Simple>> = fory.deserialize(&serialized).unwrap();
    assert_eq!(deserialized.borrow().value, 42);
}

#[derive(ForyObject, Debug)]
struct Parent {
    value: i32,
    child: Option<Rc<RefCell<Simple>>>,
}

#[test]
fn test_rc_refcell_in_struct() {
    let mut fory = Fory::default();
    fory.register::<Simple>(3001).unwrap();
    fory.register::<Parent>(3002).unwrap();

    let child = Rc::new(RefCell::new(Simple { value: 99 }));
    let parent = Parent {
        value: 1,
        child: Some(child),
    };
    let serialized = fory.serialize(&parent).unwrap();
    let deserialized: Parent = fory.deserialize(&serialized).unwrap();
    assert_eq!(deserialized.value, 1);
    assert_eq!(deserialized.child.unwrap().borrow().value, 99);
}
