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

use chrono::{NaiveDate, NaiveDateTime};
use fory_core::buffer::{Reader, Writer};
use fory_core::fory::Fory;
use fory_core::meta::murmurhash3_x64_128;
use fory_core::resolver::context::{ReadContext, WriteContext};
use fory_core::serializer::Serializer;
use fory_core::types::Mode::Compatible;
use fory_derive::Fory;
use std::collections::{HashMap, HashSet};
use std::fs;

fn get_data_file() -> String {
    std::env::var("DATA_FILE").expect("DATA_FILE not set")
}

#[test]
#[ignore]
fn test_buffer() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();
    let mut reader = Reader::new(bytes.as_slice());
    assert_eq!(reader.u8(), 1);
    assert_eq!(reader.i8(), i8::MAX);
    assert_eq!(reader.i16(), i16::MAX);
    assert_eq!(reader.i32(), i32::MAX);
    assert_eq!(reader.i64(), i64::MAX);
    assert_eq!(reader.f32(), -1.1f32);
    assert_eq!(reader.f64(), -1.1f64);
    assert_eq!(reader.var_uint32(), 100);
    let bytes_size = reader.i32() as usize;
    let binary = b"ab";
    assert_eq!(reader.bytes(bytes_size), binary);

    let mut writer = Writer::default();
    writer.u8(1);
    writer.i8(i8::MAX);
    writer.i16(i16::MAX);
    writer.i32(i32::MAX);
    writer.i64(i64::MAX);
    writer.f32(-1.1);
    writer.f64(-1.1);
    writer.var_uint32(100);
    writer.i32(binary.len() as i32);
    writer.bytes(binary);

    fs::write(&data_file_path, writer.dump()).unwrap();
}

#[test]
#[ignore]
fn test_buffer_var() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();
    let mut reader = Reader::new(bytes.as_slice());

    let var_int32_values = vec![
        i32::MIN,
        i32::MIN + 1,
        -1000000,
        -1000,
        -128,
        -1,
        0,
        1,
        127,
        128,
        16383,
        16384,
        2097151,
        2097152,
        268435455,
        268435456,
        i32::MAX - 1,
        i32::MAX,
    ];
    for &expected in &var_int32_values {
        let value = reader.var_int32();
        assert_eq!(expected, value, "var_int32 value mismatch");
    }
    let var_uint32_values = vec![
        0,
        1,
        127,
        128,
        16383,
        16384,
        2097151,
        2097152,
        268435455,
        268435456,
        i32::MAX - 1,
        i32::MAX,
    ];
    for &expected in &var_uint32_values {
        let value = reader.var_uint32();
        assert_eq!(expected, value as i32, "var_uint32 value mismatch");
    }
    let var_uint64_values = vec![
        0u64,
        1,
        127,
        128,
        16383,
        16384,
        2097151,
        2097152,
        268435455,
        268435456,
        34359738367,
        34359738368,
        4398046511103,
        4398046511104,
        562949953421311,
        562949953421312,
        72057594037927935,
        72057594037927936,
        i64::MAX as u64,
    ];
    for &expected in &var_uint64_values {
        let value = reader.var_uint64();
        assert_eq!(expected, value, "var_uint64 value mismatch");
    }
    let var_int64_values = vec![
        i64::MIN,
        i64::MIN + 1,
        -1000000000000,
        -1000000,
        -1000,
        -128,
        -1,
        0,
        1,
        127,
        1000,
        1000000,
        1000000000000,
        i64::MAX - 1,
        i64::MAX,
    ];
    for &expected in &var_int64_values {
        let value = reader.var_int64();
        assert_eq!(expected, value, "var_int64 value mismatch");
    }

    let mut writer = Writer::default();
    for &value in &var_int32_values {
        writer.var_int32(value);
    }
    for &value in &var_uint32_values {
        writer.var_uint32(value as u32);
    }
    for &value in &var_uint64_values {
        writer.var_uint64(value);
    }
    for &value in &var_int64_values {
        writer.var_int64(value);
    }
    fs::write(data_file_path, writer.dump()).unwrap();
}

#[test]
#[ignore]
fn test_murmurhash3() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();
    let mut reader = Reader::new(bytes.as_slice());
    let (h1, h2) = murmurhash3_x64_128(&[1, 2, 8], 47);
    assert_eq!(reader.i64(), h1 as i64);
    assert_eq!(reader.i64(), h2 as i64);
}

#[test]
#[ignore]
fn test_string_serializer() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();
    let reader = Reader::new(bytes.as_slice());
    let fory = Fory::default().mode(Compatible).xlang(true);
    let mut context = ReadContext::new(&fory, reader);
    let test_strings: Vec<String> = vec![
        // Latin1
        "ab".to_string(),
        "Rust123".to_string(),
        "Çüéâäàåçêëèïî".to_string(),
        // UTF16
        "こんにちは".to_string(),
        "Привет".to_string(),
        "𝄞🎵🎶".to_string(),
        // UTF8
        "Hello, 世界".to_string(),
    ];
    for s in &test_strings {
        assert_eq!(*s, String::read(&mut context).unwrap());
    }
    let mut writer = Writer::default();
    let fory = Fory::default().mode(Compatible).xlang(true);
    let mut context = WriteContext::new(&fory, &mut writer);
    for s in &test_strings {
        s.write(&mut context);
    }
    fs::write(&data_file_path, context.writer.dump()).unwrap();
}

macro_rules! assert_de {
    ($fory:expr, $context:expr, $ty:ty, $expected:expr) => {{
        let v: $ty = $fory.deserialize_with_context(&mut $context).unwrap();
        assert_eq!(v, $expected);
    }};
}

#[test]
#[ignore]
#[allow(deprecated)]
fn test_cross_language_serializer() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();
    let reader = Reader::new(bytes.as_slice());
    let fory = Fory::default().mode(Compatible).xlang(true);
    let mut context = ReadContext::new(&fory, reader);
    assert_de!(fory, context, bool, true);
    assert_de!(fory, context, bool, false);
    assert_de!(fory, context, i32, -1);
    assert_de!(fory, context, i8, i8::MAX);
    assert_de!(fory, context, i8, i8::MIN);
    assert_de!(fory, context, i16, i16::MAX);
    assert_de!(fory, context, i16, i16::MIN);
    assert_de!(fory, context, i32, i32::MAX);
    assert_de!(fory, context, i32, i32::MIN);
    assert_de!(fory, context, i64, i64::MAX);
    assert_de!(fory, context, i64, i64::MIN);
    assert_de!(fory, context, f32, -1f32);
    assert_de!(fory, context, f64, -1f64);
    assert_de!(fory, context, String, "str".to_string());
    assert_de!(
        fory,
        context,
        NaiveDate,
        NaiveDate::from_ymd_opt(2021, 11, 23).unwrap()
    );
    assert_de!(
        fory,
        context,
        NaiveDateTime,
        NaiveDateTime::from_timestamp(100, 0)
    );
    assert_de!(fory, context, Vec<bool>, [true, false]);
    assert_de!(fory, context, Vec<i16>, [1, i16::MAX]);
    assert_de!(fory, context, Vec<i32>, [1, i32::MAX]);
    assert_de!(fory, context, Vec<i64>, [1, i64::MAX]);
    assert_de!(fory, context, Vec<f32>, [1f32, 2f32]);
    assert_de!(fory, context, Vec<f64>, [1f64, 2f64]);
    let str_list = vec!["hello".to_string(), "world".to_string()];
    let str_set = HashSet::from(["hello".to_string(), "world".to_string()]);
    let str_map =
        HashMap::<String, i32>::from([("hello".to_string(), 42), ("world".to_string(), 666)]);
    assert_de!(fory, context, Vec<String>, str_list);
    assert_de!(fory, context, HashSet<String>, str_set);
    assert_de!(fory, context, HashMap::<String, i32>, str_map);

    let mut writer = Writer::default();
    let fory = Fory::default().mode(Compatible).xlang(true);
    let mut context = WriteContext::new(&fory, &mut writer);
    fory.serialize_with_context(&true, &mut context);
    fory.serialize_with_context(&false, &mut context);
    fory.serialize_with_context(&-1, &mut context);
    fory.serialize_with_context(&i8::MAX, &mut context);
    fory.serialize_with_context(&i8::MIN, &mut context);
    fory.serialize_with_context(&i16::MAX, &mut context);
    fory.serialize_with_context(&i16::MIN, &mut context);
    fory.serialize_with_context(&i32::MAX, &mut context);
    fory.serialize_with_context(&i32::MIN, &mut context);
    fory.serialize_with_context(&i64::MAX, &mut context);
    fory.serialize_with_context(&i64::MIN, &mut context);
    fory.serialize_with_context(&-1f32, &mut context);
    fory.serialize_with_context(&-1f64, &mut context);
    fory.serialize_with_context(&"str".to_string(), &mut context);
    fory.serialize_with_context(
        &NaiveDate::from_ymd_opt(2021, 11, 23).unwrap(),
        &mut context,
    );
    fory.serialize_with_context(&NaiveDateTime::from_timestamp(100, 0), &mut context);
    fory.serialize_with_context(&vec![true, false], &mut context);
    fory.serialize_with_context(&vec![1, i16::MAX], &mut context);
    fory.serialize_with_context(&vec![1, i32::MAX], &mut context);
    fory.serialize_with_context(&vec![1, i64::MAX], &mut context);
    fory.serialize_with_context(&vec![1f32, 2f32], &mut context);
    fory.serialize_with_context(&vec![1f64, 2f64], &mut context);
    fory.serialize_with_context(&str_list, &mut context);
    fory.serialize_with_context(&str_set, &mut context);
    fory.serialize_with_context(&str_map, &mut context);

    fs::write(&data_file_path, context.writer.dump()).unwrap();
}

#[derive(Fory, Debug, PartialEq, Default)]
struct SimpleStruct {
    // f1: HashMap<i32, f64>,
    f2: i32,
}

#[test]
#[ignore]
fn test_simple_struct() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();
    let mut fory = Fory::default().mode(Compatible).xlang(true);
    fory.register::<SimpleStruct>(100);
    let remote_obj: SimpleStruct = fory.deserialize(&bytes).unwrap();
    let local_obj = SimpleStruct {
        // f1: HashMap::from([(1, 1.0f64), (2, 2.0f64)]),
        f2: 10,
    };
    assert_eq!(remote_obj, local_obj);
    // let new_bytes = fory.serialize(&remote_obj);
    // let new_remote_obj: SimpleStruct = fory.deserialize(new_bytes.as_slice()).unwrap();
    // assert_eq!(remote_obj, new_remote_obj);
    // fs::write(&data_file_path, new_bytes).unwrap();
}
