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

//! Tests for stream-backed deserialization.
//!
//! Mirrors C++ `stream_test.cc` and `buffer_test.cc` stream tests from PR #3307.
//! Uses a `OneByteReader` that delivers data one byte at a time, which is the
//! worst-case scenario for streaming: every multi-byte read triggers a fill.

use fory_core::buffer::{Reader, Writer};
use fory_core::fory::Fory;
use fory_derive::ForyObject;
use std::io::{self, Read};

/// A `Read` implementation that delivers exactly one byte per `read()` call.
/// This forces the stream buffer to fill repeatedly, exercising all ensure_readable paths.
/// Equivalent to C++ `OneByteStreamBuf` / `OneByteIStream` from stream_test.cc.
struct OneByteReader {
    data: Vec<u8>,
    pos: usize,
}

impl OneByteReader {
    fn new(data: Vec<u8>) -> Self {
        Self { data, pos: 0 }
    }
}

impl Read for OneByteReader {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if self.pos >= self.data.len() || buf.is_empty() {
            return Ok(0);
        }
        buf[0] = self.data[self.pos];
        self.pos += 1;
        Ok(1)
    }
}

// ============ Buffer-level tests (matching C++ buffer_test.cc) ============

/// Tests reading various fixed-size and variable-length encoded types from
/// a one-byte stream. Equivalent to C++ `Buffer, StreamReadFromOneByteSource`.
#[test]
fn test_stream_buffer_read_primitives() {
    // Write test data
    let mut buf = Vec::new();
    let mut writer = Writer::from_buffer(&mut buf);
    writer.write_u32(0x01020304);
    writer.write_i64(-1234567890);
    writer.write_var_uint32(300);
    writer.write_varint64(-4567890123);
    writer.write_tagged_u64(0x123456789);
    writer.write_var_uint36_small(0x1FFFF);

    // Read from one-byte stream
    let mut reader = Reader::from_stream_with_capacity(Box::new(OneByteReader::new(buf)), 8);

    assert_eq!(reader.read_u32().unwrap(), 0x01020304);
    assert_eq!(reader.read_i64().unwrap(), -1234567890);
    assert_eq!(reader.read_varuint32().unwrap(), 300);
    assert_eq!(reader.read_varint64().unwrap(), -4567890123);
    assert_eq!(reader.read_tagged_u64().unwrap(), 0x123456789);
    assert_eq!(reader.read_varuint36small().unwrap(), 0x1FFFF);
}

/// Tests that stream-backed reader correctly handles short reads.
/// Equivalent to C++ `Buffer, StreamReadErrorWhenInsufficientData`.
#[test]
fn test_stream_short_read_error() {
    let data = vec![0x01, 0x02, 0x03]; // only 3 bytes for a u32 read
    let mut reader = Reader::from_stream_with_capacity(Box::new(OneByteReader::new(data)), 2);

    let result = reader.read_u32();
    assert!(result.is_err(), "expected error reading u32 from 3 bytes");
}

/// Tests basic bool/u8 types and skip.
#[test]
fn test_stream_read_small_types() {
    let mut buf = Vec::new();
    let mut writer = Writer::from_buffer(&mut buf);
    writer.write_bool(true);
    writer.write_i8(-42);
    writer.write_u8(0xAB);
    writer.write_i16(-1000);
    writer.write_u16(60000);

    let mut reader = Reader::from_stream_with_capacity(Box::new(OneByteReader::new(buf)), 4);

    assert!(reader.read_bool().unwrap());
    assert_eq!(reader.read_i8().unwrap(), -42);
    assert_eq!(reader.read_u8().unwrap(), 0xAB);
    assert_eq!(reader.read_i16().unwrap(), -1000);
    assert_eq!(reader.read_u16().unwrap(), 60000);
}

/// Tests float types through stream.
#[test]
fn test_stream_read_floats() {
    let mut buf = Vec::new();
    let mut writer = Writer::from_buffer(&mut buf);
    writer.write_f32(1.5f32);
    writer.write_f64(123.456789f64);

    let mut reader = Reader::from_stream_with_capacity(Box::new(OneByteReader::new(buf)), 4);

    assert!((reader.read_f32().unwrap() - 1.5f32).abs() < f32::EPSILON);
    assert!((reader.read_f64().unwrap() - 123.456789f64).abs() < f64::EPSILON);
}

/// Tests read_bytes and skip through stream.
#[test]
fn test_stream_read_bytes_and_skip() {
    let mut buf = Vec::new();
    let mut writer = Writer::from_buffer(&mut buf);
    writer.write_u32(0xDEADBEEF);
    writer.write_bytes(&[1, 2, 3, 4, 5]);
    writer.write_u8(0xFF);

    let mut reader = Reader::from_stream_with_capacity(Box::new(OneByteReader::new(buf)), 4);

    // Skip past the u32
    reader.skip(4).unwrap();
    // Read 5 bytes
    let bytes = reader.read_bytes(5).unwrap().to_vec();
    assert_eq!(bytes, vec![1, 2, 3, 4, 5]);
    // Read trailing byte
    assert_eq!(reader.read_u8().unwrap(), 0xFF);
}

// ============ Fory-level tests (matching C++ stream_test.cc) ============

/// Equivalent to C++ `StreamSerializationTest, PrimitiveAndStringRoundTrip`.
#[test]
fn test_stream_fory_primitive_roundtrip() {
    let fory = Fory::default();

    // i64 roundtrip through stream
    let original: i64 = -9876543212345;
    let bytes = fory.serialize(&original).unwrap();
    let mut reader = Reader::from_stream_with_capacity(Box::new(OneByteReader::new(bytes)), 8);
    let result: i64 = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(result, original);
}

/// Tests string deserialization through stream.
#[test]
fn test_stream_fory_string_roundtrip() {
    let fory = Fory::default();

    let original = "stream-hello-世界".to_string();
    let bytes = fory.serialize(&original).unwrap();
    let mut reader = Reader::from_stream_with_capacity(Box::new(OneByteReader::new(bytes)), 8);
    let result: String = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(result, original);
}

/// Tests custom struct roundtrip. Equivalent to C++ `StreamSerializationTest, StructRoundTrip`.
#[test]
fn test_stream_fory_struct_roundtrip() {
    #[derive(ForyObject, Debug, PartialEq)]
    struct StreamPoint {
        x: i32,
        y: i32,
    }

    let mut fory = Fory::default();
    fory.register::<StreamPoint>(100).unwrap();

    let original = StreamPoint { x: 42, y: -7 };
    let bytes = fory.serialize(&original).unwrap();
    let mut reader = Reader::from_stream_with_capacity(Box::new(OneByteReader::new(bytes)), 4);
    let result: StreamPoint = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(result, original);
}

/// Tests sequential deserialization of multiple objects from a single stream.
/// Equivalent to C++ `StreamSerializationTest, SequentialDeserializeFromSingleStream`.
#[test]
fn test_stream_fory_sequential_deserialize() {
    #[derive(ForyObject, Debug, PartialEq)]
    struct SeqPoint {
        x: i32,
        y: i32,
    }

    let mut fory = Fory::default();
    fory.register::<SeqPoint>(100).unwrap();

    // Serialize multiple objects into one buffer
    let mut bytes = Vec::new();
    fory.serialize_to(&mut bytes, &42i32).unwrap();
    fory.serialize_to(&mut bytes, &"next-value".to_string())
        .unwrap();
    fory.serialize_to(&mut bytes, &SeqPoint { x: 9, y: 8 })
        .unwrap();

    let total_len = bytes.len();

    // Deserialize sequentially from one-byte stream
    let mut reader = Reader::from_stream_with_capacity(Box::new(OneByteReader::new(bytes)), 3);

    let first: i32 = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(first, 42);

    let second: String = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(second, "next-value");

    let third: SeqPoint = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(third, SeqPoint { x: 9, y: 8 });

    // Final cursor should match total serialized length
    assert_eq!(reader.get_cursor(), total_len);
}

/// Tests that truncated stream produces an error.
/// Equivalent to C++ `StreamSerializationTest, TruncatedStreamReturnsError`.
#[test]
fn test_stream_fory_truncated_error() {
    #[derive(ForyObject, Debug, PartialEq)]
    struct TruncPoint {
        x: i32,
        y: i32,
    }

    let mut fory = Fory::default();
    fory.register::<TruncPoint>(100).unwrap();

    let original = TruncPoint { x: 7, y: 7 };
    let mut bytes = fory.serialize(&original).unwrap();
    assert!(bytes.len() > 1);
    bytes.pop(); // Remove last byte

    let mut reader = Reader::from_stream_with_capacity(Box::new(OneByteReader::new(bytes)), 4);
    let result = fory.deserialize_from::<TruncPoint>(&mut reader);
    assert!(result.is_err(), "expected error from truncated stream");
}

/// Tests Vec<i32> roundtrip through stream.
#[test]
fn test_stream_fory_vec_roundtrip() {
    let fory = Fory::default();

    let original = vec![1i32, 2, 3, 5, 8, 13, 21];
    let bytes = fory.serialize(&original).unwrap();
    let mut reader = Reader::from_stream_with_capacity(Box::new(OneByteReader::new(bytes)), 4);
    let result: Vec<i32> = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(result, original);
}

/// Ensures that existing in-memory paths are not regressed.
/// No stream involved — just verifies Reader::new still works as expected.
#[test]
fn test_no_regression_in_memory_reader() {
    let fory = Fory::default();

    let original = "Hello, regression test!".to_string();
    let bytes = fory.serialize(&original).unwrap();
    let mut reader = Reader::new(&bytes);
    let result: String = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(result, original);
}
