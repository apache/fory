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
use fory_core::buffer::Reader;
use fory_core::fory::Fory;
use fory_core::stream::ForyStreamBuf;
use fory_derive::ForyObject;

use std::collections::HashMap;
use std::io::Cursor;
use std::sync::Arc;
mod test_helpers;
use test_helpers::{deserialize_check, OneByte};

#[derive(ForyObject, Debug, PartialEq, Clone)]
struct StreamPoint {
    x: i32,
    y: i32,
}

#[derive(ForyObject, Debug, PartialEq, Clone)]
struct StreamEnvelope {
    name: String,
    values: Vec<i32>,
    metrics: HashMap<String, i64>,
    point: StreamPoint,
    active: bool,
}

#[derive(ForyObject, Debug, PartialEq, Clone)]
struct SharedIntPair {
    first: Arc<i32>,
    second: Arc<i32>,
}

fn register_stream_types(fory: &mut Fory) {
    fory.register::<StreamPoint>(1).unwrap();
    fory.register::<StreamEnvelope>(2).unwrap();
    fory.register::<SharedIntPair>(3).unwrap();
}

fn reader_from_bytes(bytes: Vec<u8>) -> Reader<'static> {
    let cursor = Cursor::new(bytes);
    let stream = ForyStreamBuf::new(OneByte { inner: cursor });
    Reader::from_stream(stream)
}

#[test]
fn primitive_and_string_roundtrip() {
    let fory = Fory::default().xlang(true).track_ref(false);

    let number_bytes = fory.serialize(&-9876543212345_i64).unwrap();
    let number: i64 = deserialize_check(&fory, &number_bytes);
    assert_eq!(number, -9876543212345_i64);

    let string_bytes = fory.serialize(&"stream-hello-世界".to_string()).unwrap();
    let s: String = deserialize_check(&fory, &string_bytes);
    assert_eq!(s, "stream-hello-世界");
}

#[test]
fn struct_roundtrip() {
    let mut fory = Fory::default().xlang(true).track_ref(true);
    register_stream_types(&mut fory);

    let original = StreamEnvelope {
        name: "payload-name".into(),
        values: vec![1, 3, 5, 7, 9],
        metrics: HashMap::from([("count".into(), 5), ("sum".into(), 25), ("max".into(), 9)]),
        point: StreamPoint { x: 42, y: -7 },
        active: true,
    };

    let bytes = fory.serialize(&original).unwrap();
    let result: StreamEnvelope = deserialize_check(&fory, &bytes);

    assert_eq!(result, original);
}

#[test]
fn sequential_deserialize_from_single_stream() {
    let mut fory = Fory::default().xlang(true).track_ref(true);
    register_stream_types(&mut fory);

    let envelope = StreamEnvelope {
        name: "batch".into(),
        values: vec![10, 20, 30],
        metrics: HashMap::from([("a".into(), 1), ("b".into(), 2)]),
        point: StreamPoint { x: 9, y: 8 },
        active: false,
    };

    let mut bytes = Vec::new();

    fory.serialize_to(&mut bytes, &12345_i32).unwrap();
    fory.serialize_to(&mut bytes, &"next-value".to_string())
        .unwrap();
    fory.serialize_to(&mut bytes, &envelope).unwrap();

    let mut reader = reader_from_bytes(bytes);

    let first: i32 = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(first, 12345);

    let second: String = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(second, "next-value");

    let third: StreamEnvelope = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(third, envelope);

    assert_eq!(reader.stream_remaining(), Some(0));
}

#[test]
fn shared_pointer_identity_roundtrip() {
    let mut fory = Fory::default().xlang(true).track_ref(true);
    register_stream_types(&mut fory);

    let shared = Arc::new(2026);
    let pair = SharedIntPair {
        first: shared.clone(),
        second: shared.clone(),
    };

    let bytes = fory.serialize(&pair).unwrap();
    let result: SharedIntPair = deserialize_check(&fory, &bytes);

    assert_eq!(*result.first, 2026);
    assert!(Arc::ptr_eq(&result.first, &result.second));
}

#[test]
fn truncated_stream_returns_error() {
    let mut fory = Fory::default().xlang(true).track_ref(true);
    register_stream_types(&mut fory);

    let original = StreamEnvelope {
        name: "truncated".into(),
        values: vec![1, 2, 3, 4],
        metrics: HashMap::from([("k".into(), 99)]),
        point: StreamPoint { x: 7, y: 7 },
        active: true,
    };

    let mut bytes = fory.serialize(&original).unwrap();
    bytes.pop();

    let mut reader = reader_from_bytes(bytes);
    let result: Result<StreamEnvelope, _> = fory.deserialize_from(&mut reader);

    assert!(result.is_err());
}
