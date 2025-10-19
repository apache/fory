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
use fory_core::error::Error;
use fory_core::fory::{read_data, write_data, Fory};
use fory_core::meta::murmurhash3_x64_128;
use fory_core::resolver::context::{ReadContext, WriteContext};
use fory_core::serializer::{ForyDefault, Serializer};
use fory_core::TypeResolver;
use fory_derive::ForyObject;
use std::collections::{HashMap, HashSet};
use std::{fs, vec};

// RUSTFLAGS="-Awarnings" cargo expand -p fory-tests --test test_cross_language
fn get_data_file() -> String {
    std::env::var("DATA_FILE").expect("DATA_FILE not set")
}

#[derive(ForyObject, Debug, PartialEq)]
struct Empty {}

#[derive(ForyObject, Debug, PartialEq, Default)]
enum Color {
    #[default]
    Green,
    Red,
    Blue,
    White,
}

#[derive(ForyObject, Debug, PartialEq)]
struct Item {
    name: Option<String>,
}

#[derive(ForyObject, Debug, PartialEq)]
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
    assert_eq!(reader.read_u8().unwrap(), 1);
    assert_eq!(reader.read_i8().unwrap(), i8::MAX);
    assert_eq!(reader.read_i16().unwrap(), i16::MAX);
    assert_eq!(reader.read_i32().unwrap(), i32::MAX);
    assert_eq!(reader.read_i64().unwrap(), i64::MAX);
    assert_eq!(reader.read_f32().unwrap(), -1.1f32);
    assert_eq!(reader.read_f64().unwrap(), -1.1f64);
    assert_eq!(reader.read_varuint32().unwrap(), 100);
    let bytes_size = reader.read_i32().unwrap() as usize;
    let binary = b"ab";
    assert_eq!(reader.read_bytes(bytes_size).unwrap(), binary);

    let mut writer = Writer::default();
    writer.write_u8(1);
    writer.write_i8(i8::MAX);
    writer.write_i16(i16::MAX);
    writer.write_i32(i32::MAX);
    writer.write_i64(i64::MAX);
    writer.write_f32(-1.1);
    writer.write_f64(-1.1);
    writer.write_varuint32(100);
    writer.write_i32(binary.len() as i32);
    writer.write_bytes(binary);

    fs::write(&data_file_path, writer.dump()).unwrap();
}

#[test]
#[ignore]
fn test_buffer_var() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();
    let mut reader = Reader::new(bytes.as_slice());

    let varint32_values = vec![
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
    for &expected in &varint32_values {
        let value = reader.read_varint32().unwrap();
        assert_eq!(expected, value, "varint32 value mismatch");
    }
    let varuint32_values = vec![
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
    for &expected in &varuint32_values {
        let value = reader.read_varuint32().unwrap();
        assert_eq!(expected, value as i32, "varuint32 value mismatch");
    }
    let varuint64_values = vec![
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
    for &expected in &varuint64_values {
        let value = reader.read_varuint64().unwrap();
        assert_eq!(expected, value, "varuint64 value mismatch");
    }
    let varint64_values = vec![
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
    for &expected in &varint64_values {
        let value = reader.read_varint64().unwrap();
        assert_eq!(expected, value, "varint64 value mismatch");
    }

    let mut writer = Writer::default();
    for &value in &varint32_values {
        writer.write_varint32(value);
    }
    for &value in &varuint32_values {
        writer.write_varuint32(value as u32);
    }
    for &value in &varuint64_values {
        writer.write_varuint64(value);
    }
    for &value in &varint64_values {
        writer.write_varint64(value);
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
    assert_eq!(reader.read_i64().unwrap(), h1 as i64);
    assert_eq!(reader.read_i64().unwrap(), h2 as i64);
}

#[test]
#[ignore]
fn test_string_serializer() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();
    let reader = Reader::new(bytes.as_slice());
    let fory = Fory::default()
        .compatible(true)
        .xlang(true)
        .compress_string(false);
    let mut context = ReadContext::new_from_fory(reader, &fory);
    let reader_compress = Reader::new(bytes.as_slice());
    let fory_compress = Fory::default()
        .compatible(true)
        .xlang(true)
        .compress_string(true);
    let mut context_compress = ReadContext::new_from_fory(reader_compress, &fory_compress);
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
        assert_eq!(*s, String::fory_read_data(&mut context).unwrap());
        assert_eq!(*s, String::fory_read_data(&mut context_compress).unwrap());
    }
    let writer = Writer::default();
    let fory = Fory::default().compatible(true).xlang(true);
    let mut context = WriteContext::new_from_fory(writer, &fory);
    for s in &test_strings {
        s.fory_write_data(&mut context).unwrap();
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
    let day = NaiveDate::from_ymd_opt(2021, 11, 23).unwrap();
    let instant = NaiveDateTime::from_timestamp(100, 0);
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
    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<Color>(101).unwrap();
    let mut context = ReadContext::new_from_fory(reader, &fory);
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
    assert_de!(fory, context, NaiveDate, day);
    assert_de!(fory, context, NaiveDateTime, instant);
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

    let writer = Writer::default();
    let mut context = WriteContext::new_from_fory(writer, &fory);
    fory.serialize_with_context(&true, &mut context).unwrap();
    fory.serialize_with_context(&false, &mut context).unwrap();
    fory.serialize_with_context(&-1, &mut context).unwrap();
    fory.serialize_with_context(&i8::MAX, &mut context).unwrap();
    fory.serialize_with_context(&i8::MIN, &mut context).unwrap();
    fory.serialize_with_context(&i16::MAX, &mut context)
        .unwrap();
    fory.serialize_with_context(&i16::MIN, &mut context)
        .unwrap();
    fory.serialize_with_context(&i32::MAX, &mut context)
        .unwrap();
    fory.serialize_with_context(&i32::MIN, &mut context)
        .unwrap();
    fory.serialize_with_context(&i64::MAX, &mut context)
        .unwrap();
    fory.serialize_with_context(&i64::MIN, &mut context)
        .unwrap();
    fory.serialize_with_context(&-1f32, &mut context).unwrap();
    fory.serialize_with_context(&-1f64, &mut context).unwrap();
    fory.serialize_with_context(&"str".to_string(), &mut context)
        .unwrap();
    fory.serialize_with_context(&day, &mut context).unwrap();
    fory.serialize_with_context(&instant, &mut context).unwrap();
    fory.serialize_with_context(&vec![true, false], &mut context)
        .unwrap();
    fory.serialize_with_context(&vec![1, i16::MAX], &mut context)
        .unwrap();
    fory.serialize_with_context(&vec![1, i32::MAX], &mut context)
        .unwrap();
    fory.serialize_with_context(&vec![1, i64::MAX], &mut context)
        .unwrap();
    fory.serialize_with_context(&vec![1f32, 2f32], &mut context)
        .unwrap();
    fory.serialize_with_context(&vec![1f64, 2f64], &mut context)
        .unwrap();
    fory.serialize_with_context(&str_list, &mut context)
        .unwrap();
    fory.serialize_with_context(&str_set, &mut context).unwrap();
    fory.serialize_with_context(&str_map, &mut context).unwrap();
    fory.serialize_with_context(&color, &mut context).unwrap();
    fs::write(&data_file_path, context.writer.dump()).unwrap();
}

#[test]
#[ignore]
fn test_simple_struct() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();
    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<Color>(101).unwrap();
    fory.register::<Item>(102).unwrap();
    fory.register::<SimpleStruct>(103).unwrap();

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
    let remote_obj: SimpleStruct = fory.deserialize(&bytes).unwrap();
    assert_eq!(remote_obj, local_obj);
    let new_bytes = fory.serialize(&remote_obj).unwrap();
    let new_local_obj: SimpleStruct = fory.deserialize(&new_bytes).unwrap();
    assert_eq!(new_local_obj, local_obj);
    fs::write(&data_file_path, new_bytes).unwrap();
}

#[test]
#[ignore]
fn test_simple_named_struct() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();
    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register_by_namespace::<Color>("demo", "color")
        .unwrap();
    fory.register_by_namespace::<Item>("demo", "item").unwrap();
    fory.register_by_namespace::<SimpleStruct>("demo", "simple_struct")
        .unwrap();

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
    let remote_obj: SimpleStruct = fory.deserialize(&bytes).unwrap();
    assert_eq!(remote_obj, local_obj);
    let new_bytes = fory.serialize(&remote_obj).unwrap();
    let new_local_obj: SimpleStruct = fory.deserialize(&new_bytes).unwrap();
    assert_eq!(new_local_obj, local_obj);
    fs::write(&data_file_path, new_bytes).unwrap();
}

#[test]
#[ignore]
fn test_list() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(true);
    fory.register::<Item>(102).unwrap();
    let reader = Reader::new(bytes.as_slice());
    let mut context = ReadContext::new_from_fory(reader, &fory);

    let str_list = vec![Some("a".to_string()), Some("b".to_string())];
    let str_list2 = vec![None, Some("b".to_string())];
    let item = Item {
        name: Some("a".to_string()),
    };
    let item2 = Item {
        name: Some("b".to_string()),
    };
    let item3 = Item {
        name: Some("c".to_string()),
    };
    let item_list = vec![Some(item), Some(item2)];
    let item_list2 = vec![None, Some(item3)];

    let remote_str_list: Vec<Option<String>> = fory.deserialize_with_context(&mut context).unwrap();
    assert_eq!(remote_str_list, str_list);
    let remote_str_list2: Vec<Option<String>> =
        fory.deserialize_with_context(&mut context).unwrap();
    assert_eq!(remote_str_list2, str_list2);
    let remote_item_list: Vec<Option<Item>> = fory.deserialize_with_context(&mut context).unwrap();
    assert_eq!(remote_item_list, item_list);
    let remote_item_list2: Vec<Option<Item>> = fory.deserialize_with_context(&mut context).unwrap();
    assert_eq!(remote_item_list2, item_list2);

    let writer = Writer::default();
    let mut context = WriteContext::new_from_fory(writer, &fory);
    fory.serialize_with_context(&remote_str_list, &mut context)
        .unwrap();
    fory.serialize_with_context(&remote_str_list2, &mut context)
        .unwrap();
    fory.serialize_with_context(&remote_item_list, &mut context)
        .unwrap();
    fory.serialize_with_context(&remote_item_list2, &mut context)
        .unwrap();

    fs::write(&data_file_path, context.writer.dump()).unwrap();
}

#[test]
#[ignore]
fn test_map() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(true);
    fory.register::<Item>(102).unwrap();
    let reader = Reader::new(bytes.as_slice());
    let mut context = ReadContext::new_from_fory(reader, &fory);

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
    assert_eq!(remote_str_map, str_map);
    let data_bytes1 = fory.serialize(&remote_str_map).unwrap();
    let new_local_str_map: HashMap<Option<String>, Option<String>> =
        fory.deserialize(&data_bytes1).unwrap();
    assert_eq!(new_local_str_map, str_map);

    let remote_item_map: HashMap<Option<String>, Option<Item>> =
        fory.deserialize_with_context(&mut context).unwrap();
    assert_eq!(remote_item_map, item_map);
    let data_bytes2 = fory.serialize(&remote_item_map).unwrap();
    let new_local_item_map: HashMap<Option<String>, Option<Item>> =
        fory.deserialize(&data_bytes2).unwrap();
    assert_eq!(new_local_item_map, item_map);

    let all_bytes = [data_bytes1.as_slice(), data_bytes2.as_slice()].concat();
    fs::write(&data_file_path, all_bytes).unwrap();
}

#[test]
#[ignore]
fn test_integer() {
    #[derive(ForyObject, Debug, PartialEq)]
    struct Item2 {
        f1: i32,
        f2: Option<i32>,
        f3: Option<i32>,
        f4: i32,
        f5: i32,
        f6: Option<i32>,
    }

    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(true);
    fory.register::<Item2>(101).unwrap();
    let reader = Reader::new(bytes.as_slice());
    let mut context = ReadContext::new_from_fory(reader, &fory);
    let f1 = 1;
    let f2 = Some(2);
    let f3 = Some(3);
    let f4 = 4;
    let f5 = i32::default();
    let f6 = None;

    let local_item2 = Item2 {
        f1,
        f2,
        f3,
        f4,
        f5,
        f6,
    };
    let remote_item2: Item2 = fory.deserialize_with_context(&mut context).unwrap();
    assert_eq!(remote_item2, local_item2);
    let remote_f1: i32 = fory.deserialize_with_context(&mut context).unwrap();
    assert_eq!(remote_f1, f1);
    let remote_f2: Option<i32> = fory.deserialize_with_context(&mut context).unwrap();
    assert_eq!(remote_f2, f2);
    let remote_f3: Option<i32> = fory.deserialize_with_context(&mut context).unwrap();
    assert_eq!(remote_f3, f3);
    let remote_f4: i32 = fory.deserialize_with_context(&mut context).unwrap();
    assert_eq!(remote_f4, f4);
    let remote_f5: i32 = fory.deserialize_with_context(&mut context).unwrap();
    assert_eq!(remote_f5, f5);
    let remote_f6: Option<i32> = fory.deserialize_with_context(&mut context).unwrap();
    assert_eq!(remote_f6, f6);

    let writer = Writer::default();
    let mut context = WriteContext::new_from_fory(writer, &fory);
    fory.serialize_with_context(&remote_item2, &mut context)
        .unwrap();
    fory.serialize_with_context(&remote_f1, &mut context)
        .unwrap();
    fory.serialize_with_context(&remote_f2, &mut context)
        .unwrap();
    fory.serialize_with_context(&remote_f3, &mut context)
        .unwrap();
    fory.serialize_with_context(&remote_f4, &mut context)
        .unwrap();
    fory.serialize_with_context(&remote_f5, &mut context)
        .unwrap();
    fory.serialize_with_context(&remote_f6, &mut context)
        .unwrap();
    fs::write(&data_file_path, context.writer.dump()).unwrap();
}

#[derive(ForyObject, Debug, PartialEq)]
struct MyStruct {
    id: i32,
}
#[derive(Debug, PartialEq, Default)]
struct MyExt {
    id: i32,
}
impl Serializer for MyExt {
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), fory_core::error::Error> {
        // set is_field=false to write type_id like in java
        write_data(&self.id, context)
    }

    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        Ok(Self {
            // set is_field=false to write type_id like in java
            id: read_data(context)?,
        })
    }

    fn fory_type_id_dyn(
        &self,
        type_resolver: &TypeResolver,
    ) -> Result<u32, fory_core::error::Error> {
        Self::fory_get_type_id(type_resolver)
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}
impl ForyDefault for MyExt {
    fn fory_default() -> Self {
        Self::default()
    }
}
#[derive(ForyObject, Debug, PartialEq)]
struct MyWrapper {
    color: Color,
    my_struct: MyStruct,
    my_ext: MyExt,
}

fn _test_skip_custom(fory1: &Fory, fory2: &Fory) {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();
    assert_eq!(
        fory1.deserialize::<Empty>(&bytes).unwrap(),
        Empty::default()
    );
    let wrapper = MyWrapper {
        color: Color::White,
        my_struct: MyStruct { id: 42 },
        my_ext: MyExt { id: 43 },
    };
    let bytes = fory2.serialize(&wrapper).unwrap();
    fs::write(&data_file_path, bytes).unwrap();
}

#[test]
fn test_kankankan() {
    let mut fory1 = Fory::default().compatible(true);
    fory1.register_serializer::<MyExt>(103).unwrap();
    fory1.register::<Empty>(104).unwrap();
    let mut fory2 = Fory::default().compatible(true);
    fory2.register::<Color>(101).unwrap();
    fory2.register::<MyStruct>(102).unwrap();
    fory2.register_serializer::<MyExt>(103).unwrap();
    fory2.register::<MyWrapper>(104).unwrap();
    let wrapper = MyWrapper {
        color: Color::White,
        my_struct: MyStruct { id: 42 },
        my_ext: MyExt { id: 43 },
    };
    let bytes = fory2.serialize(&wrapper).unwrap();
    fory1.deserialize::<Empty>(&bytes).unwrap();
}

#[test]
#[ignore]
fn test_skip_id_custom() {
    let mut fory1 = Fory::default().compatible(true);
    fory1.register_serializer::<MyExt>(103).unwrap();
    fory1.register::<Empty>(104).unwrap();
    let mut fory2 = Fory::default().compatible(true);
    fory2.register::<Color>(101).unwrap();
    fory2.register::<MyStruct>(102).unwrap();
    fory2.register_serializer::<MyExt>(103).unwrap();
    fory2.register::<MyWrapper>(104).unwrap();
    _test_skip_custom(&fory1, &fory2);
}

#[test]
#[ignore]
fn test_skip_name_custom() {
    let mut fory1 = Fory::default().compatible(true);
    fory1
        .register_serializer_by_name::<MyExt>("my_ext")
        .unwrap();
    fory1.register_by_name::<Empty>("my_wrapper").unwrap();
    let mut fory2 = Fory::default().compatible(true);
    fory2.register_by_name::<Color>("color").unwrap();
    fory2.register_by_name::<MyStruct>("my_struct").unwrap();
    fory2
        .register_serializer_by_name::<MyExt>("my_ext")
        .unwrap();
    fory2.register_by_name::<MyWrapper>("my_wrapper").unwrap();
    _test_skip_custom(&fory1, &fory2);
}

#[test]
#[ignore]
fn test_consistent_named() {
    let mut fory = Fory::default().compatible(true);
    fory.register_by_name::<Color>("color").unwrap();
    fory.register_by_name::<MyStruct>("my_struct").unwrap();
    fory.register_serializer_by_name::<MyExt>("my_ext").unwrap();

    let color = Color::White;
    let _my_struct = MyStruct { id: 42 };
    let my_ext = MyExt { id: 43 };

    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();
    let reader = Reader::new(bytes.as_slice());
    let mut context = ReadContext::new_from_fory(reader, &fory);

    assert_eq!(
        fory.deserialize_with_context::<Color>(&mut context)
            .unwrap(),
        color
    );
    assert_eq!(
        fory.deserialize_with_context::<Color>(&mut context)
            .unwrap(),
        color
    );
    assert_eq!(
        fory.deserialize_with_context::<Color>(&mut context)
            .unwrap(),
        color
    );
    // assert_eq!(fory.deserialize_with_context::<MyStruct>(&mut context).unwrap(), my_struct);
    assert_eq!(
        fory.deserialize_with_context::<MyExt>(&mut context)
            .unwrap(),
        my_ext
    );
    assert_eq!(
        fory.deserialize_with_context::<MyExt>(&mut context)
            .unwrap(),
        my_ext
    );
    assert_eq!(
        fory.deserialize_with_context::<MyExt>(&mut context)
            .unwrap(),
        my_ext
    );

    let writer = Writer::default();
    let mut context = WriteContext::new_from_fory(writer, &fory);
    fory.serialize_with_context(&color, &mut context).unwrap();
    fory.serialize_with_context(&color, &mut context).unwrap();
    fory.serialize_with_context(&color, &mut context).unwrap();
    // todo: checkVersion
    // fory.serialize_with_context(&my_struct, &mut context);
    fory.serialize_with_context(&my_ext, &mut context).unwrap();
    fory.serialize_with_context(&my_ext, &mut context).unwrap();
    fory.serialize_with_context(&my_ext, &mut context).unwrap();
    fs::write(&data_file_path, context.writer.dump()).unwrap();
}
