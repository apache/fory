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
use fory_core::{ForyDefault, Serializer};
use std::any::Any;
use std::rc::Rc;
use std::sync::Arc;

/// Generic helper function for roundtrip serialization testing
pub fn test_roundtrip<T>(fory: &Fory, value: T)
where
    T: Serializer + ForyDefault + PartialEq + std::fmt::Debug,
{
    let bytes = fory.serialize(&value).unwrap();
    let result: T = fory.deserialize(&bytes).unwrap();
    assert_eq!(value, result);
}

/// Generic helper for testing Box<dyn Any> serialization
pub fn test_box_any<T>(fory: &Fory, value: T)
where
    T: 'static + PartialEq + std::fmt::Debug + Clone,
{
    let wrapped: Box<dyn Any> = Box::new(value.clone());
    let bytes = fory.serialize(&wrapped).unwrap();
    let result: Box<dyn Any> = fory.deserialize(&bytes).unwrap();
    assert_eq!(result.downcast_ref::<T>().unwrap(), &value);
}

/// Generic helper for testing Rc<dyn Any> serialization
pub fn test_rc_any<T>(fory: &Fory, value: T)
where
    T: 'static + PartialEq + std::fmt::Debug + Clone,
{
    let wrapped: Rc<dyn Any> = Rc::new(value.clone());
    let bytes = fory.serialize(&wrapped).unwrap();
    let result: Rc<dyn Any> = fory.deserialize(&bytes).unwrap();
    assert_eq!(result.downcast_ref::<T>().unwrap(), &value);
}

/// Generic helper for testing Arc<dyn Any> serialization
pub fn test_arc_any<T>(fory: &Fory, value: T)
where
    T: 'static + PartialEq + std::fmt::Debug + Clone,
{
    let wrapped: Arc<dyn Any> = Arc::new(value.clone());
    let bytes = fory.serialize(&wrapped).unwrap();
    let result: Arc<dyn Any> = fory.deserialize(&bytes).unwrap();
    assert_eq!(result.downcast_ref::<T>().unwrap(), &value);
}
