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

//! Tests for RefWriter and RefReader functionality

use fory_core::buffer::Writer;
use fory_core::resolver::ref_resolver::{RefReader, RefWriter};
use fory_core::serializer::weak::{ArcWeak, RcWeak};
use std::rc::Rc;
use std::sync::Arc;

#[test]
fn test_rc_ref_tracking() {
    let mut ref_writer = RefWriter::new();
    let mut writer = Writer::default();

    let rc1 = Rc::new(42i32);
    let rc2 = rc1.clone();

    // First write should register the reference
    assert!(!ref_writer.try_write_rc_ref(&mut writer, &rc1));

    // Second write should find existing reference
    assert!(ref_writer.try_write_rc_ref(&mut writer, &rc2));
}

#[test]
fn test_arc_ref_tracking() {
    let mut ref_writer = RefWriter::new();
    let mut writer = Writer::default();

    let arc1 = Arc::new(42i32);
    let arc2 = arc1.clone();

    // First write should register the reference
    assert!(!ref_writer.try_write_arc_ref(&mut writer, &arc1));

    // Second write should find existing reference
    assert!(ref_writer.try_write_arc_ref(&mut writer, &arc2));
}

#[test]
fn test_rc_storage_and_retrieval() {
    let mut ref_reader = RefReader::new();
    let rc = Rc::new(String::from("test"));

    let ref_id = ref_reader.store_rc_ref(rc.clone());

    let retrieved = ref_reader.get_rc_ref::<String>(ref_id).unwrap();
    assert_eq!(*retrieved, "test");
    assert!(Rc::ptr_eq(&rc, &retrieved));
}

#[test]
fn test_arc_storage_and_retrieval() {
    let mut ref_reader = RefReader::new();
    let arc = Arc::new(String::from("test"));

    let ref_id = ref_reader.store_arc_ref(arc.clone());

    let retrieved = ref_reader.get_arc_ref::<String>(ref_id).unwrap();
    assert_eq!(*retrieved, "test");
    assert!(Arc::ptr_eq(&arc, &retrieved));
}

#[test]
fn test_ref_writer_clear() {
    let mut ref_writer = RefWriter::new();
    let mut writer = Writer::default();

    let rc = Rc::new(42i32);

    // Register a reference
    assert!(!ref_writer.try_write_rc_ref(&mut writer, &rc));

    // Clear the writer
    ref_writer.reset();

    // After clearing, should register as new reference again
    assert!(!ref_writer.try_write_rc_ref(&mut writer, &rc));
}

#[test]
fn test_ref_reader_clear() {
    let mut ref_reader = RefReader::new();
    let rc = Rc::new(String::from("test"));

    // Store a reference
    let ref_id = ref_reader.store_rc_ref(rc.clone());
    assert!(ref_reader.get_rc_ref::<String>(ref_id).is_some());

    // Clear the reader
    ref_reader.reset();

    // After clearing, reference should no longer be found
    assert!(ref_reader.get_rc_ref::<String>(ref_id).is_none());
}

#[test]
fn test_ref_writer_ref_reader_separation() {
    let mut ref_writer = RefWriter::new();
    let mut ref_reader = RefReader::new();
    let mut writer = Writer::default();

    let rc1 = Rc::new(42i32);
    let rc2 = rc1.clone();

    // Test writing with RefWriter
    assert!(!ref_writer.try_write_rc_ref(&mut writer, &rc1));
    assert!(ref_writer.try_write_rc_ref(&mut writer, &rc2));

    // Test storing and retrieving with RefReader
    let ref_id = ref_reader.store_rc_ref(rc1.clone());
    let retrieved = ref_reader.get_rc_ref::<i32>(ref_id).unwrap();
    assert!(Rc::ptr_eq(&rc1, &retrieved));
}

#[test]
fn test_rc_weak_wrapper() {
    let rc = Rc::new(42i32);
    let weak = RcWeak::from(&rc);

    // Test upgrade
    assert_eq!(*weak.upgrade().unwrap(), 42);

    // Test strong and weak counts
    assert_eq!(weak.strong_count(), 1);
    assert!(weak.weak_count() > 0);

    // Test clone
    let weak2 = weak.clone();
    assert_eq!(*weak2.upgrade().unwrap(), 42);
}

#[test]
fn test_arc_weak_wrapper() {
    let arc = Arc::new(42i32);
    let weak = ArcWeak::from(&arc);

    // Test upgrade
    assert_eq!(*weak.upgrade().unwrap(), 42);

    // Test strong and weak counts
    assert_eq!(weak.strong_count(), 1);
    assert!(weak.weak_count() > 0);

    // Test clone
    let weak2 = weak.clone();
    assert_eq!(*weak2.upgrade().unwrap(), 42);
}

#[test]
fn test_rc_weak_update() {
    let rc1 = Rc::new(10i32);
    let rc2 = Rc::new(20i32);

    let weak = RcWeak::from(&rc1);
    assert_eq!(*weak.upgrade().unwrap(), 10);

    // Update the weak to point to rc2
    weak.update(Rc::downgrade(&rc2));
    assert_eq!(*weak.upgrade().unwrap(), 20);
}

#[test]
fn test_arc_weak_update() {
    let arc1 = Arc::new(10i32);
    let arc2 = Arc::new(20i32);

    let weak = ArcWeak::from(&arc1);
    assert_eq!(*weak.upgrade().unwrap(), 10);

    // Update the weak to point to arc2
    weak.update(Arc::downgrade(&arc2));
    assert_eq!(*weak.upgrade().unwrap(), 20);
}
