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

use fory_core::buffer::Reader;
use fory_core::fory::Fory;
use fory_core::stream::ForyStreamBuf;
use fory_core::{ForyDefault, Serializer};

use std::any::Any;
use std::fmt::Debug;
use std::io::{Cursor, Read};
use std::rc::Rc;
use std::sync::Arc;

/// A reader that returns exactly one byte per read call.
/// This stresses the streaming refill logic.
pub struct OneByte<R> {
    pub inner: R,
}

impl<R: Read> Read for OneByte<R> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        self.inner.read(&mut buf[..1])
    }
}

/// Deserialize helper required by streaming tests.
///
/// It validates both:
/// 1. Normal in-memory deserialization
/// 2. Streaming deserialization using OneByteStream
///
/// and ensures the results match.
pub fn deserialize_check<T>(fory: &Fory, bytes: &[u8]) -> T
where
    T: Serializer + ForyDefault,
{
    // normal deserialize
    let _: T = fory.deserialize(bytes).expect("in-memory deserialize");

    // stream deserialize
    let cursor = Cursor::new(bytes.to_vec());
    let stream = ForyStreamBuf::new(OneByte { inner: cursor });

    let mut reader = Reader::from_stream(stream);
    fory.deserialize_from(&mut reader)
        .expect("stream deserialize")
}

/// Generic helper function for roundtrip serialization testing
pub fn test_roundtrip<T>(fory: &Fory, value: T)
where
    T: Serializer + ForyDefault + PartialEq + Debug,
{
    let bytes = fory.serialize(&value).unwrap();
    let result: T = deserialize_check(fory, &bytes);
    assert_eq!(value, result);
}

/// Generic helper for testing Box<dyn Any> serialization
pub fn test_box_any<T>(fory: &Fory, value: T)
where
    T: 'static + PartialEq + Debug + Clone,
{
    let wrapped: Box<dyn Any> = Box::new(value.clone());
    let bytes = fory.serialize(&wrapped).unwrap();
    let result: Box<dyn Any> = deserialize_check(fory, &bytes);
    assert_eq!(result.downcast_ref::<T>().unwrap(), &value);
}

/// Generic helper for testing Rc<dyn Any> serialization
pub fn test_rc_any<T>(fory: &Fory, value: T)
where
    T: 'static + PartialEq + Debug + Clone,
{
    let wrapped: Rc<dyn Any> = Rc::new(value.clone());
    let bytes = fory.serialize(&wrapped).unwrap();
    let result: Rc<dyn Any> = deserialize_check(fory, &bytes);
    assert_eq!(result.downcast_ref::<T>().unwrap(), &value);
}

/// Generic helper for testing Arc<dyn Any> serialization
pub fn test_arc_any<T>(fory: &Fory, value: T)
where
    T: 'static + PartialEq + Debug + Clone,
{
    let wrapped: Arc<dyn Any> = Arc::new(value.clone());
    let bytes = fory.serialize(&wrapped).unwrap();
    let result: Arc<dyn Any> = deserialize_check(fory, &bytes);
    assert_eq!(result.downcast_ref::<T>().unwrap(), &value);
}
