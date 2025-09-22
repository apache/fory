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

// RUSTFLAGS="-Awarnings" cargo expand -p fory-tests --test test_cross_language
fn get_data_file() -> String {
    std::env::var("DATA_FILE").expect("DATA_FILE not set")
}

#[derive(Fory, Debug, PartialEq, Default)]
enum Color {
    #[default]
    Green,
    Red,
    Blue,
    White,
}

#[derive(Fory, Debug, PartialEq, Default)]
struct Item {
    name: Option<String>,
}

#[derive(Fory, Debug, PartialEq, Default)]
struct SimpleStruct {
    // field_order != sorted_order
    f1: HashMap<i32, f64>,
    f2: i32,
    f3: Item,
    f4: Option<String>,
    f5: Color,
    f6: Vec<Option<String>>,
    f7: i32,
    f8: i32,
    last: i32,
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
    let fory = Fory::default()
        .mode(Compatible)
        .xlang(true)
        .compress_string(false);
    let mut context = ReadContext::new(&fory, reader);
    let reader_compress = Reader::new(bytes.as_slice());
    let fory_compress = Fory::default()
        .mode(Compatible)
        .xlang(true)
        .compress_string(true);
    let mut context_compress = ReadContext::new(&fory_compress, reader_compress);
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
        // make is_field=true to skip read/write type_id
        assert_eq!(*s, String::read(&mut context).unwrap());
        assert_eq!(*s, String::read(&mut context_compress).unwrap());
    }
    let mut writer = Writer::default();
    let fory = Fory::default().mode(Compatible).xlang(true);
    let mut context = WriteContext::new(&fory, &mut writer);
    for s in &test_strings {
        s.write(&mut context, true);
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
    let str_list = vec!["hello".to_string(), "world".to_string()];
    let str_set = HashSet::from(["hello".to_string(), "world".to_string()]);
    let str_map = HashMap::<String, String>::from([
        ("hello".to_string(), "world".to_string()),
        ("foo".to_string(), "bar".to_string()),
    ]);
    let color = Color::White;

    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();
    let reader = Reader::new(bytes.as_slice());
    let mut fory = Fory::default().mode(Compatible).xlang(true);
    fory.register::<Color>(101);
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
    assert_de!(fory, context, Vec<String>, str_list);
    assert_de!(fory, context, HashSet<String>, str_set);
    assert_de!(fory, context, HashMap::<String, String>, str_map);
    assert_de!(fory, context, Color, color);

    let mut writer = Writer::default();
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
    fory.serialize_with_context(&color, &mut context);
    fs::write(&data_file_path, context.writer.dump()).unwrap();
}

#[test]
#[ignore]
fn test_simple_struct() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();
    let mut fory = Fory::default().mode(Compatible).xlang(true);
    let mut fory2 = Fory::default().mode(Compatible).xlang(true);
    fory.register::<Color>(101);
    fory.register::<Item>(102);
    fory.register::<SimpleStruct>(103);
    fory2.register_by_namespace::<Color>("demo", "color");
    fory2.register_by_namespace::<Item>("demo", "item");
    fory2.register_by_namespace::<SimpleStruct>("demo", "simple_struct");

    let local_obj = SimpleStruct {
        f1: HashMap::from([(1, 1.0f64), (2, 2.0f64)]),
        f2: 39,
        f3: Item {
            name: Some("item".to_string()),
        },
        f4: Some("f4".to_string()),
        f5: Color::White,
        f6: vec![Some("f6".to_string())],
        f7: 40,
        f8: 41,
        last: 42,
    };
    let reader = Reader::new(bytes.as_slice());
    let context = ReadContext::new(&fory, reader);
    let remote_obj: SimpleStruct = fory.deserialize(&bytes).unwrap();
    assert_eq!(remote_obj, local_obj);

    let rest_bytes = context.reader.slice_after_cursor();
    let remote_obj2: SimpleStruct = fory.deserialize(rest_bytes).unwrap();
    assert_eq!(remote_obj2, local_obj);

    let new_bytes = fory.serialize(&remote_obj);
    let new_bytes2 = fory2.serialize(&remote_obj);

    let new_local_obj: SimpleStruct = fory.deserialize(new_bytes.as_slice()).unwrap();
    assert_eq!(new_local_obj, local_obj);
    let new_local_obj2: SimpleStruct = fory2.deserialize(new_bytes2.as_slice()).unwrap();
    assert_eq!(new_local_obj2, local_obj);

    let all_bytes = [new_bytes.as_slice(), new_bytes2.as_slice()].concat();
    fs::write(&data_file_path, all_bytes).unwrap();
}

#[test]
#[ignore]
fn test_vec() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().mode(Compatible);
    fory.register::<Item>(102);
    let reader = Reader::new(bytes.as_slice());
    let mut context = ReadContext::new(&fory, reader);

    let str_list = vec![Some("a".to_string()), Some("b".to_string())];
    let str_list2 = vec![None, Some("b".to_string())];
    let item = Item {
        name: Some("item1".to_string()),
    };
    let item2 = Item {
        name: Some("item2".to_string()),
    };
    let item3 = Item {
        name: Some("item3".to_string()),
    };
    let item_list = vec![Some(item), Some(item2)];
    let item_list2 = vec![None, Some(item3)];

    let remote_str_list: Vec<Option<String>> = fory.deserialize_with_context(&mut context).unwrap();
    let remote_str_list2: Vec<Option<String>> =
        fory.deserialize_with_context(&mut context).unwrap();
    let remote_item_list: Vec<Option<Item>> = fory.deserialize_with_context(&mut context).unwrap();
    let remote_item_list2: Vec<Option<Item>> = fory.deserialize_with_context(&mut context).unwrap();

    assert_eq!(remote_str_list, str_list);
    assert_eq!(remote_str_list2, str_list2);
    assert_eq!(remote_item_list, item_list);
    assert_eq!(remote_item_list2, item_list2);

    let bytes1 = fory.serialize(&remote_str_list);
    let bytes2 = fory.serialize(&remote_str_list2);
    let bytes3 = fory.serialize(&remote_item_list);
    let bytes4 = fory.serialize(&remote_item_list2);

    let new_str_list: Vec<Option<String>> = fory.deserialize(&bytes1).unwrap();
    let new_str_list2: Vec<Option<String>> = fory.deserialize(&bytes2).unwrap();
    let new_item_list: Vec<Option<Item>> = fory.deserialize(&bytes2).unwrap();
    let new_item_list2: Vec<Option<Item>> = fory.deserialize(&bytes4).unwrap();

    assert_eq!(new_str_list, str_list);
    assert_eq!(new_str_list2, str_list2);
    assert_eq!(new_item_list, item_list);
    assert_eq!(new_item_list2, item_list2);

    let all_bytes = [
        bytes1.as_slice(),
        bytes2.as_slice(),
        bytes3.as_slice(),
        bytes4.as_slice(),
    ]
    .concat();
    fs::write(&data_file_path, all_bytes).unwrap();
}

#[test]
#[ignore]
fn test_map() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().mode(Compatible);
    fory.register::<Item>(102);
    let reader = Reader::new(bytes.as_slice());
    let mut context = ReadContext::new(&fory, reader);

    let str_map = HashMap::from([
        (Some("k1".to_string()), Some("v1".to_string())),
        (None, Some("v2".to_string())),
        (Some("k3".to_string()), None),
        (Some("k4".to_string()), Some("v4".to_string())),
    ]);
    let item_map = HashMap::from([
        (
            Some("k1".to_string()),
            Some(Item {
                name: Some("item1".to_string()),
            }),
        ),
        (
            None,
            Some(Item {
                name: Some("item2".to_string()),
            }),
        ),
        (Some("k3".to_string()), None),
        (
            Some("k4".to_string()),
            Some(Item {
                name: Some("item3".to_string()),
            }),
        ),
    ]);

    let remote_str_map: HashMap<Option<String>, Option<String>> =
        fory.deserialize_with_context(&mut context).unwrap();
    let remote_item_map: HashMap<Option<String>, Option<Item>> =
        fory.deserialize_with_context(&mut context).unwrap();
    assert_eq!(remote_str_map, str_map);
    assert_eq!(remote_item_map, item_map);

    let bytes1 = fory.serialize(&remote_str_map);
    let bytes2 = fory.serialize(&remote_item_map);

    let new_str_map: HashMap<Option<String>, Option<String>> = fory.deserialize(&bytes1).unwrap();
    let new_item_map: HashMap<Option<String>, Option<Item>> = fory.deserialize(&bytes2).unwrap();

    assert_eq!(new_str_map, remote_str_map);
    assert_eq!(new_item_map, remote_item_map);

    let all_bytes = [bytes1.as_slice(), bytes2.as_slice()].concat();
    fs::write(&data_file_path, all_bytes).unwrap();
}
