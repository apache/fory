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
use std::io::{Cursor, Read};
use std::rc::Rc;
use std::sync::Arc;

/// Reader that returns exactly one byte per `read()` call.
/// Stresses the streaming deserializer at every boundary.
struct OneByte(Cursor<Vec<u8>>);

impl Read for OneByte {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        let mut one = [0u8; 1];
        match self.0.read(&mut one)? {
            0 => Ok(0),
            _ => {
                buf[0] = one[0];
                Ok(1)
            }
        }
    }
}

/// Deserializes `bytes` via both in-memory and OneByteStream paths,
/// asserts the results are identical, and returns the value.
///
/// This is the core helper that the maintainer requested: "first deserialize
/// from bytes, then wrap it into a OneByteStream to deserialize it."
#[allow(dead_code)]
pub fn deserialize_check<T>(fory: &Fory, bytes: &[u8]) -> T
where
    T: Serializer + ForyDefault + PartialEq + std::fmt::Debug,
{
    let expected: T = fory.deserialize(bytes).unwrap();
    let actual: T = fory
        .deserialize_from_stream(OneByte(Cursor::new(bytes.to_vec())))
        .unwrap();
    assert_eq!(
        expected, actual,
        "stream and in-memory deserialization results differ"
    );
    expected
}

/// Roundtrip: serialize `value`, then deserialize via both paths and compare.
#[allow(dead_code)]
pub fn test_roundtrip<T>(fory: &Fory, value: T)
where
    T: Serializer + ForyDefault + PartialEq + std::fmt::Debug,
{
    let bytes = fory.serialize(&value).unwrap();
    let result = deserialize_check::<T>(fory, &bytes);
    assert_eq!(value, result);
}

/// Generic helper for testing Box<dyn Any> serialization
#[allow(dead_code)]
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
#[allow(dead_code)]
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
#[allow(dead_code)]
pub fn test_arc_any<T>(fory: &Fory, value: T)
where
    T: 'static + PartialEq + std::fmt::Debug + Clone,
{
    let wrapped: Arc<dyn Any> = Arc::new(value.clone());
    let bytes = fory.serialize(&wrapped).unwrap();
    let result: Arc<dyn Any> = fory.deserialize(&bytes).unwrap();
    assert_eq!(result.downcast_ref::<T>().unwrap(), &value);
}
