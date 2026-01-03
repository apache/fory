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
use fory_core::meta::murmurhash3_x64_128;
use fory_core::resolver::context::{ReadContext, WriteContext};
use fory_core::serializer::{ForyDefault, Serializer};
use fory_core::TypeResolver;
use fory_core::{read_data, write_data, Fory};
use fory_derive::ForyObject;
use std::collections::{HashMap, HashSet};
use std::{fs, vec};

// RUSTFLAGS="-Awarnings" cargo expand -p tests --test test_cross_language
fn get_data_file() -> String {
    std::env::var("DATA_FILE").expect("DATA_FILE not set")
}

#[derive(ForyObject, Debug, PartialEq, Default)]
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
    // Use String (not Option<String>) to match Java's non-nullable String field
    // xlang mode defaults to nullable=false for non-Optional types
    name: String,
}

#[derive(ForyObject, Debug, PartialEq)]
#[fory(debug)]
struct SimpleStruct {
    // field_order != sorted_order
    f1: HashMap<i32, f64>,
    f2: i32,
    f3: Item,
    // Use String (not Option<String>) to match Java's non-nullable String field
    f4: String,
    f5: Color,
    // Use Vec<String> to match Java's List<String> with non-nullable elements
    f6: Vec<String>,
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

    let mut buffer = vec![];
    let mut writer = Writer::from_buffer(&mut buffer);
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

    let mut buffer = vec![];
    let mut writer = Writer::from_buffer(&mut buffer);
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
    let mut reader = Reader::new(bytes.as_slice());
    let fory = Fory::default()
        .compatible(true)
        .xlang(true)
        .compress_string(false);
    let mut reader_compress = Reader::new(bytes.as_slice());
    let fory_compress = Fory::default()
        .compatible(true)
        .xlang(true)
        .compress_string(true);
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
        assert_eq!(*s, fory.deserialize_from::<String>(&mut reader).unwrap());
        assert_eq!(
            *s,
            fory_compress
                .deserialize_from::<String>(&mut reader_compress)
                .unwrap()
        );
    }
    let fory = Fory::default().compatible(true).xlang(true);
    let mut buf = Vec::new();
    for s in &test_strings {
        fory.serialize_to(&mut buf, s).unwrap();
    }
    fs::write(&data_file_path, buf).unwrap();
}

macro_rules! assert_de {
    ($fory:expr, $reader:expr, $ty:ty, $expected:expr) => {{
        let v: $ty = $fory.deserialize_from(&mut $reader).unwrap();
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
    let mut reader = Reader::new(bytes.as_slice());
    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<Color>(101).unwrap();
    assert_de!(fory, reader, bool, true);
    assert_de!(fory, reader, bool, false);
    assert_de!(fory, reader, i32, -1);
    assert_de!(fory, reader, i8, i8::MAX);
    assert_de!(fory, reader, i8, i8::MIN);
    assert_de!(fory, reader, i16, i16::MAX);
    assert_de!(fory, reader, i16, i16::MIN);
    assert_de!(fory, reader, i32, i32::MAX);
    assert_de!(fory, reader, i32, i32::MIN);
    assert_de!(fory, reader, i64, i64::MAX);
    assert_de!(fory, reader, i64, i64::MIN);
    assert_de!(fory, reader, f32, -1f32);
    assert_de!(fory, reader, f64, -1f64);
    assert_de!(fory, reader, String, "str".to_string());
    assert_de!(fory, reader, NaiveDate, day);
    assert_de!(fory, reader, NaiveDateTime, instant);
    assert_de!(fory, reader, Vec<bool>, [true, false]);
    assert_de!(fory, reader, Vec<u8>, [1, i8::MAX as u8]);
    assert_de!(fory, reader, Vec<i16>, [1, i16::MAX]);
    assert_de!(fory, reader, Vec<i32>, [1, i32::MAX]);
    assert_de!(fory, reader, Vec<i64>, [1, i64::MAX]);
    assert_de!(fory, reader, Vec<f32>, [1f32, 2f32]);
    assert_de!(fory, reader, Vec<f64>, [1f64, 2f64]);
    assert_de!(fory, reader, Vec<String>, str_list);
    assert_de!(fory, reader, HashSet<String>, str_set);
    assert_de!(fory, reader, HashMap::<String, String>, str_map);
    assert_de!(fory, reader, Color, color);

    let mut buf = Vec::new();
    fory.serialize_to(&mut buf, &true).unwrap();
    fory.serialize_to(&mut buf, &false).unwrap();
    fory.serialize_to(&mut buf, &-1).unwrap();
    fory.serialize_to(&mut buf, &i8::MAX).unwrap();
    fory.serialize_to(&mut buf, &i8::MIN).unwrap();
    fory.serialize_to(&mut buf, &i16::MAX).unwrap();
    fory.serialize_to(&mut buf, &i16::MIN).unwrap();
    fory.serialize_to(&mut buf, &i32::MAX).unwrap();
    fory.serialize_to(&mut buf, &i32::MIN).unwrap();
    fory.serialize_to(&mut buf, &i64::MAX).unwrap();
    fory.serialize_to(&mut buf, &i64::MIN).unwrap();
    fory.serialize_to(&mut buf, &-1f32).unwrap();
    fory.serialize_to(&mut buf, &-1f64).unwrap();
    fory.serialize_to(&mut buf, &"str".to_string()).unwrap();
    fory.serialize_to(&mut buf, &day).unwrap();
    fory.serialize_to(&mut buf, &instant).unwrap();
    fory.serialize_to(&mut buf, &vec![true, false]).unwrap();
    fory.serialize_to(&mut buf, &vec![1, i8::MAX as u8])
        .unwrap();
    fory.serialize_to(&mut buf, &vec![1, i16::MAX]).unwrap();
    fory.serialize_to(&mut buf, &vec![1, i32::MAX]).unwrap();
    fory.serialize_to(&mut buf, &vec![1, i64::MAX]).unwrap();
    fory.serialize_to(&mut buf, &vec![1f32, 2f32]).unwrap();
    fory.serialize_to(&mut buf, &vec![1f64, 2f64]).unwrap();
    fory.serialize_to(&mut buf, &str_list).unwrap();
    fory.serialize_to(&mut buf, &str_set).unwrap();
    fory.serialize_to(&mut buf, &str_map).unwrap();
    fory.serialize_to(&mut buf, &color).unwrap();
    fs::write(&data_file_path, buf).unwrap();
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
            name: "item".to_string(),
        },
        f4: "f4".to_string(),
        f5: Color::White,
        f6: vec!["f6".to_string()],
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
            name: "item".to_string(),
        },
        f4: "f4".to_string(),
        f5: Color::White,
        f6: vec!["f6".to_string()],
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

    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<Item>(102).unwrap();
    let mut reader = Reader::new(bytes.as_slice());

    let str_list = vec![Some("a".to_string()), Some("b".to_string())];
    let str_list2 = vec![None, Some("b".to_string())];
    let item = Item {
        name: "a".to_string(),
    };
    let item2 = Item {
        name: "b".to_string(),
    };
    let item3 = Item {
        name: "c".to_string(),
    };
    let item_list = vec![Some(item), Some(item2)];
    let item_list2 = vec![None, Some(item3)];

    let remote_str_list: Vec<Option<String>> = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_str_list, str_list);
    let remote_str_list2: Vec<Option<String>> = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_str_list2, str_list2);
    let remote_item_list: Vec<Option<Item>> = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_item_list, item_list);
    let remote_item_list2: Vec<Option<Item>> = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_item_list2, item_list2);

    let mut buf = Vec::new();
    fory.serialize_to(&mut buf, &remote_str_list).unwrap();
    fory.serialize_to(&mut buf, &remote_str_list2).unwrap();
    fory.serialize_to(&mut buf, &remote_item_list).unwrap();
    fory.serialize_to(&mut buf, &remote_item_list2).unwrap();

    fs::write(&data_file_path, buf).unwrap();
}

#[test]
#[ignore]
fn test_map() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<Item>(102).unwrap();
    let mut reader = Reader::new(bytes.as_slice());

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
                name: "item1".to_string(),
            }),
        ),
        (
            None,
            Some(Item {
                name: "item2".to_string(),
            }),
        ),
        (Some("k3".to_string()), None),
        (
            Some("k4".to_string()),
            Some(Item {
                name: "item3".to_string(),
            }),
        ),
    ]);

    let remote_str_map: HashMap<Option<String>, Option<String>> =
        fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_str_map, str_map);
    let data_bytes1 = fory.serialize(&remote_str_map).unwrap();
    let new_local_str_map: HashMap<Option<String>, Option<String>> =
        fory.deserialize(&data_bytes1).unwrap();
    assert_eq!(new_local_str_map, str_map);

    let remote_item_map: HashMap<Option<String>, Option<Item>> =
        fory.deserialize_from(&mut reader).unwrap();
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
    // In xlang mode with nullable=false default:
    // - Java int fields -> Rust i32 (no ref flag)
    // - Java Integer fields (with nullable=false) -> Rust i32 (no ref flag)
    // All fields use i32 because Java xlang mode defaults to nullable=false for all non-primitives
    #[derive(ForyObject, Debug, PartialEq)]
    #[fory(debug)]
    struct Item2 {
        f1: i32,
        f2: i32,
        f3: i32,
        f4: i32,
        f5: i32,
        f6: i32,
    }

    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<Item2>(101).unwrap();
    let mut reader = Reader::new(bytes.as_slice());
    let f1 = 1;
    let f2 = 2;
    let f3 = 3;
    let f4 = 4;
    let f5 = 0;
    let f6 = 0;

    let local_item2 = Item2 {
        f1,
        f2,
        f3,
        f4,
        f5,
        f6,
    };
    let remote_item2: Item2 = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_item2, local_item2);
    // When deserializing standalone values, they're serialized with ref flag
    let remote_f1: i32 = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_f1, f1);
    let remote_f2: i32 = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_f2, f2);
    let remote_f3: i32 = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_f3, f3);
    let remote_f4: i32 = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_f4, f4);
    let remote_f5: i32 = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_f5, f5);
    let remote_f6: i32 = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_f6, f6);

    let mut buf = Vec::new();
    fory.serialize_to(&mut buf, &remote_item2).unwrap();
    fory.serialize_to(&mut buf, &remote_f1).unwrap();
    fory.serialize_to(&mut buf, &remote_f2).unwrap();
    fory.serialize_to(&mut buf, &remote_f3).unwrap();
    fory.serialize_to(&mut buf, &remote_f4).unwrap();
    fory.serialize_to(&mut buf, &remote_f5).unwrap();
    fory.serialize_to(&mut buf, &remote_f6).unwrap();
    fs::write(&data_file_path, buf).unwrap();
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
        write_data(&self.id, context)
    }

    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        Ok(Self {
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
#[fory(debug)]
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
#[ignore]
fn test_skip_id_custom() {
    let mut fory1 = Fory::default().compatible(true).xlang(true);
    fory1.register_serializer::<MyExt>(103).unwrap();
    fory1.register::<Empty>(104).unwrap();
    let mut fory2 = Fory::default().compatible(true).xlang(true);
    fory2.register::<Color>(101).unwrap();
    fory2.register::<MyStruct>(102).unwrap();
    fory2.register_serializer::<MyExt>(103).unwrap();
    fory2.register::<MyWrapper>(104).unwrap();
    _test_skip_custom(&fory1, &fory2);
}

#[test]
#[ignore]
fn test_skip_name_custom() {
    let mut fory1 = Fory::default().compatible(true).xlang(true);
    fory1
        .register_serializer_by_name::<MyExt>("my_ext")
        .unwrap();
    fory1.register_by_name::<Empty>("my_wrapper").unwrap();
    let mut fory2 = Fory::default().compatible(true).xlang(true);
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
    let mut fory = Fory::default().compatible(false).xlang(true);
    fory.register_by_name::<Color>("color").unwrap();
    fory.register_by_name::<MyStruct>("my_struct").unwrap();
    fory.register_serializer_by_name::<MyExt>("my_ext").unwrap();

    let color = Color::White;
    let my_struct = MyStruct { id: 42 };
    let my_ext = MyExt { id: 43 };

    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();
    let mut reader = Reader::new(bytes.as_slice());

    for _ in 0..3 {
        assert_eq!(fory.deserialize_from::<Color>(&mut reader).unwrap(), color);
    }
    for _ in 0..3 {
        assert_eq!(
            fory.deserialize_from::<MyStruct>(&mut reader).unwrap(),
            my_struct
        );
    }
    for _ in 0..3 {
        assert_eq!(fory.deserialize_from::<MyExt>(&mut reader).unwrap(), my_ext);
    }
    let mut buf = Vec::new();
    for _ in 0..3 {
        fory.serialize_to(&mut buf, &color).unwrap();
    }
    for _ in 0..3 {
        fory.serialize_to(&mut buf, &my_struct).unwrap();
    }
    for _ in 0..3 {
        fory.serialize_to(&mut buf, &my_ext).unwrap();
    }
    fs::write(&data_file_path, buf).unwrap();
}

#[derive(ForyObject, Debug, PartialEq)]
#[fory(debug)]
struct VersionCheckStruct {
    f1: i32,
    f2: Option<String>,
    f3: f64,
}

#[derive(ForyObject, Debug, PartialEq)]
struct StructWithList {
    items: Vec<Option<String>>,
}

#[derive(ForyObject, Debug, PartialEq)]
struct StructWithMap {
    data: HashMap<Option<String>, Option<String>>,
}

// ============================================================================
// Schema Evolution Test Types
// ============================================================================

#[derive(ForyObject, Debug, PartialEq)]
struct EmptyStructEvolution {}

// Java f1 has @ForyField(id = -1, nullable = true), so it's nullable
#[derive(ForyObject, Debug, PartialEq)]
struct OneStringFieldStruct {
    #[fory(nullable = true)]
    f1: Option<String>,
}

// Both f1 and f2 are non-nullable in Java xlang mode
#[derive(ForyObject, Debug, PartialEq)]
struct TwoStringFieldStruct {
    f1: String,
    f2: String,
}

#[allow(non_camel_case_types)]
#[derive(ForyObject, Debug, PartialEq, Default, Clone)]
enum TestEnum {
    #[default]
    VALUE_A,
    VALUE_B,
    VALUE_C,
}

#[derive(ForyObject, Debug, PartialEq)]
struct OneEnumFieldStruct {
    f1: TestEnum,
}

// Both f1 and f2 are non-nullable in Java xlang mode
#[derive(ForyObject, Debug, PartialEq)]
struct TwoEnumFieldStruct {
    f1: TestEnum,
    f2: TestEnum,
}

#[test]
#[ignore]
fn test_struct_version_check() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();
    let mut fory = Fory::default()
        .compatible(false)
        .xlang(true)
        .check_struct_version(true);
    fory.register::<VersionCheckStruct>(201).unwrap();

    let local_obj = VersionCheckStruct {
        f1: 10,
        f2: Some("test".to_string()),
        f3: 3.2,
    };
    let remote_obj: VersionCheckStruct = fory.deserialize(&bytes).unwrap();
    assert_eq!(remote_obj, local_obj);
    let new_bytes = fory.serialize(&remote_obj).unwrap();
    let new_local_obj: VersionCheckStruct = fory.deserialize(&new_bytes).unwrap();
    assert_eq!(new_local_obj, local_obj);
    fs::write(&data_file_path, new_bytes).unwrap();
}

#[test]
#[ignore]
fn test_item() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<Item>(102).unwrap();
    let mut reader = Reader::new(bytes.as_slice());

    let item1 = Item {
        name: "test_item_1".to_string(),
    };
    let item2 = Item {
        name: "test_item_2".to_string(),
    };
    // With nullable=false (xlang default), Java sends empty string instead of null
    let item3 = Item {
        name: String::new(),
    };

    let remote_item1: Item = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_item1, item1);
    let remote_item2: Item = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_item2, item2);
    let remote_item3: Item = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_item3, item3);

    let mut buf = Vec::new();
    fory.serialize_to(&mut buf, &remote_item1).unwrap();
    fory.serialize_to(&mut buf, &remote_item2).unwrap();
    fory.serialize_to(&mut buf, &remote_item3).unwrap();
    fs::write(&data_file_path, buf).unwrap();
}

#[test]
#[ignore]
fn test_color() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<Color>(101).unwrap();
    let mut reader = Reader::new(bytes.as_slice());

    let remote_green: Color = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_green, Color::Green);
    let remote_red: Color = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_red, Color::Red);
    let remote_blue: Color = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_blue, Color::Blue);
    let remote_white: Color = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_white, Color::White);

    let mut buf = Vec::new();
    fory.serialize_to(&mut buf, &Color::Green).unwrap();
    fory.serialize_to(&mut buf, &Color::Red).unwrap();
    fory.serialize_to(&mut buf, &Color::Blue).unwrap();
    fory.serialize_to(&mut buf, &Color::White).unwrap();
    fs::write(&data_file_path, buf).unwrap();
}

#[test]
#[ignore]
fn test_struct_with_list() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<StructWithList>(201).unwrap();
    let mut reader = Reader::new(bytes.as_slice());

    let struct1 = StructWithList {
        items: vec![
            Some("a".to_string()),
            Some("b".to_string()),
            Some("c".to_string()),
        ],
    };
    let struct2 = StructWithList {
        items: vec![Some("x".to_string()), None, Some("z".to_string())],
    };

    let remote_struct1: StructWithList = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_struct1, struct1);
    let remote_struct2: StructWithList = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_struct2, struct2);

    let mut buf = Vec::new();
    fory.serialize_to(&mut buf, &remote_struct1).unwrap();
    fory.serialize_to(&mut buf, &remote_struct2).unwrap();
    fs::write(&data_file_path, buf).unwrap();
}

#[test]
#[ignore]
fn test_struct_with_map() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<StructWithMap>(202).unwrap();
    let mut reader = Reader::new(bytes.as_slice());

    let struct1 = StructWithMap {
        data: HashMap::from([
            (Some("key1".to_string()), Some("value1".to_string())),
            (Some("key2".to_string()), Some("value2".to_string())),
        ]),
    };
    let struct2 = StructWithMap {
        data: HashMap::from([
            (Some("k1".to_string()), None),
            (None, Some("v2".to_string())),
        ]),
    };

    let remote_struct1: StructWithMap = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_struct1, struct1);
    let remote_struct2: StructWithMap = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(remote_struct2, struct2);

    let mut buf = Vec::new();
    fory.serialize_to(&mut buf, &remote_struct1).unwrap();
    fory.serialize_to(&mut buf, &remote_struct2).unwrap();
    fs::write(&data_file_path, buf).unwrap();
}

// ============================================================================
// Polymorphic Container Test Types - Using Box<dyn Any> for runtime polymorphism
// ============================================================================

use std::any::Any;

#[derive(ForyObject, Debug, PartialEq, Clone)]
struct Dog {
    age: i32,
    // Match Java's @ForyField(nullable = true) annotation
    name: Option<String>,
}

#[derive(ForyObject, Debug, PartialEq, Clone)]
struct Cat {
    age: i32,
    lives: i32,
}

#[derive(ForyObject, Debug)]
struct AnimalListHolder {
    animals: Vec<Box<dyn Any>>,
}

#[derive(ForyObject, Debug)]
struct AnimalMapHolder {
    animal_map: HashMap<String, Box<dyn Any>>,
}

#[test]
#[ignore]
fn test_polymorphic_list() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<Dog>(302).unwrap();
    fory.register::<Cat>(303).unwrap();
    fory.register::<AnimalListHolder>(304).unwrap();
    let mut reader = Reader::new(bytes.as_slice());

    // Part 1: Read List<Animal> with polymorphic elements (Dog, Cat)
    let animals: Vec<Box<dyn Any>> = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(animals.len(), 2);

    // First element should be Dog
    let dog = animals[0]
        .downcast_ref::<Dog>()
        .expect("First element should be Dog");
    assert_eq!(dog.age, 3);
    assert_eq!(dog.name, Some("Buddy".to_string()));

    // Second element should be Cat
    let cat = animals[1]
        .downcast_ref::<Cat>()
        .expect("Second element should be Cat");
    assert_eq!(cat.age, 5);
    assert_eq!(cat.lives, 9);

    // Part 2: Read AnimalListHolder (List<Animal> as struct field)
    let holder: AnimalListHolder = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(holder.animals.len(), 2);

    let dog2 = holder.animals[0]
        .downcast_ref::<Dog>()
        .expect("First holder element should be Dog");
    assert_eq!(dog2.name, Some("Rex".to_string()));

    let cat2 = holder.animals[1]
        .downcast_ref::<Cat>()
        .expect("Second holder element should be Cat");
    assert_eq!(cat2.lives, 7);

    // Write back
    let mut buf = Vec::new();
    fory.serialize_to(&mut buf, &animals).unwrap();
    fory.serialize_to(&mut buf, &holder).unwrap();
    fs::write(&data_file_path, buf).unwrap();
}

#[test]
#[ignore]
fn test_polymorphic_map() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<Dog>(302).unwrap();
    fory.register::<Cat>(303).unwrap();
    fory.register::<AnimalMapHolder>(305).unwrap();
    let mut reader = Reader::new(bytes.as_slice());

    // Part 1: Read Map<String, Animal> with polymorphic values
    let animal_map: HashMap<String, Box<dyn Any>> = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(animal_map.len(), 2);

    let dog1 = animal_map
        .get("dog1")
        .expect("dog1 should exist")
        .downcast_ref::<Dog>()
        .expect("dog1 should be Dog");
    assert_eq!(dog1.name, Some("Rex".to_string()));
    assert_eq!(dog1.age, 2);

    let cat1 = animal_map
        .get("cat1")
        .expect("cat1 should exist")
        .downcast_ref::<Cat>()
        .expect("cat1 should be Cat");
    assert_eq!(cat1.lives, 9);
    assert_eq!(cat1.age, 4);

    // Part 2: Read AnimalMapHolder (Map<String, Animal> as struct field)
    let holder: AnimalMapHolder = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(holder.animal_map.len(), 2);

    let my_dog = holder
        .animal_map
        .get("myDog")
        .expect("myDog should exist")
        .downcast_ref::<Dog>()
        .expect("myDog should be Dog");
    assert_eq!(my_dog.name, Some("Fido".to_string()));

    let my_cat = holder
        .animal_map
        .get("myCat")
        .expect("myCat should exist")
        .downcast_ref::<Cat>()
        .expect("myCat should be Cat");
    assert_eq!(my_cat.lives, 8);

    // Write back
    let mut buf = Vec::new();
    fory.serialize_to(&mut buf, &animal_map).unwrap();
    fory.serialize_to(&mut buf, &holder).unwrap();
    fs::write(&data_file_path, buf).unwrap();
}

// ============================================================================
// Schema Evolution Tests - String Fields
// ============================================================================

#[test]
#[ignore]
fn test_one_string_field_schema() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(false).xlang(true);
    fory.register::<OneStringFieldStruct>(200).unwrap();

    let value: OneStringFieldStruct = fory.deserialize(&bytes).unwrap();
    assert_eq!(value.f1, Some("hello".to_string()));

    let new_bytes = fory.serialize(&value).unwrap();
    fs::write(&data_file_path, new_bytes).unwrap();
}

#[test]
#[ignore]
fn test_one_string_field_compatible() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<OneStringFieldStruct>(200).unwrap();

    let value: OneStringFieldStruct = fory.deserialize(&bytes).unwrap();
    assert_eq!(value.f1, Some("hello".to_string()));

    let new_bytes = fory.serialize(&value).unwrap();
    fs::write(&data_file_path, new_bytes).unwrap();
}

#[test]
#[ignore]
fn test_two_string_field_compatible() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<TwoStringFieldStruct>(201).unwrap();

    let value: TwoStringFieldStruct = fory.deserialize(&bytes).unwrap();
    assert_eq!(value.f1, "first".to_string());
    assert_eq!(value.f2, "second".to_string());

    let new_bytes = fory.serialize(&value).unwrap();
    fs::write(&data_file_path, new_bytes).unwrap();
}

#[test]
#[ignore]
fn test_schema_evolution_compatible() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    // Read TwoStringFieldStruct data as EmptyStructEvolution
    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<EmptyStructEvolution>(200).unwrap();

    let value: EmptyStructEvolution = fory.deserialize(&bytes).unwrap();

    // Serialize back as EmptyStructEvolution
    let new_bytes = fory.serialize(&value).unwrap();
    fs::write(&data_file_path, new_bytes).unwrap();
}

#[test]
#[ignore]
fn test_schema_evolution_compatible_reverse() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    // Read OneStringFieldStruct data as TwoStringFieldStruct (missing f2)
    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<TwoStringFieldStruct>(200).unwrap();

    let value: TwoStringFieldStruct = fory.deserialize(&bytes).unwrap();

    // f1 should be "only_one", f2 should be empty string (default for missing non-nullable String field)
    assert_eq!(value.f1, "only_one".to_string());
    assert_eq!(value.f2, String::default()); // Empty string for missing non-nullable field

    // Serialize back
    let new_bytes = fory.serialize(&value).unwrap();
    fs::write(&data_file_path, new_bytes).unwrap();
}

// ============================================================================
// Schema Evolution Tests - Enum Fields
// ============================================================================

#[test]
#[ignore]
fn test_one_enum_field_schema() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(false).xlang(true);
    fory.register::<TestEnum>(210).unwrap();
    fory.register::<OneEnumFieldStruct>(211).unwrap();

    let value: OneEnumFieldStruct = fory.deserialize(&bytes).unwrap();
    assert_eq!(value.f1, TestEnum::VALUE_B);

    let new_bytes = fory.serialize(&value).unwrap();
    fs::write(&data_file_path, new_bytes).unwrap();
}

#[test]
#[ignore]
fn test_one_enum_field_compatible() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<TestEnum>(210).unwrap();
    fory.register::<OneEnumFieldStruct>(211).unwrap();

    let value: OneEnumFieldStruct = fory.deserialize(&bytes).unwrap();
    assert_eq!(value.f1, TestEnum::VALUE_A);

    let new_bytes = fory.serialize(&value).unwrap();
    fs::write(&data_file_path, new_bytes).unwrap();
}

#[test]
#[ignore]
fn test_two_enum_field_compatible() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<TestEnum>(210).unwrap();
    fory.register::<TwoEnumFieldStruct>(212).unwrap();

    let value: TwoEnumFieldStruct = fory.deserialize(&bytes).unwrap();
    assert_eq!(value.f1, TestEnum::VALUE_A);
    assert_eq!(value.f2, TestEnum::VALUE_C);

    let new_bytes = fory.serialize(&value).unwrap();
    fs::write(&data_file_path, new_bytes).unwrap();
}

#[test]
#[ignore]
fn test_enum_schema_evolution_compatible() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    // Read TwoEnumFieldStruct data as EmptyStructEvolution
    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<TestEnum>(210).unwrap();
    fory.register::<EmptyStructEvolution>(211).unwrap();

    let value: EmptyStructEvolution = fory.deserialize(&bytes).unwrap();

    // Serialize back as EmptyStructEvolution
    let new_bytes = fory.serialize(&value).unwrap();
    fs::write(&data_file_path, new_bytes).unwrap();
}

#[test]
#[ignore]
fn test_enum_schema_evolution_compatible_reverse() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    // Read OneEnumFieldStruct data as TwoEnumFieldStruct (missing f2)
    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<TestEnum>(210).unwrap();
    fory.register::<TwoEnumFieldStruct>(211).unwrap();

    let value: TwoEnumFieldStruct = fory.deserialize(&bytes).unwrap();

    // f1 should be ValueC
    assert_eq!(value.f1, TestEnum::VALUE_C);
    // f2 should be default (VALUE_A) since it's not present in source data and is non-nullable
    assert_eq!(value.f2, TestEnum::VALUE_A);

    // Serialize back
    let new_bytes = fory.serialize(&value).unwrap();
    fs::write(&data_file_path, new_bytes).unwrap();
}

// ============================================================================
// Nullable Field Tests - Comprehensive nullable field testing
// ============================================================================

/// Comprehensive struct for testing nullable fields in SCHEMA_CONSISTENT mode (compatible=false).
/// Fields are organized as:
/// - Base non-nullable fields: byte, short, int, long, float, double, bool, string, list, set, map
/// - Nullable fields (first half - boxed numeric types): Integer, Long, Float
/// - Nullable fields (second half - @ForyField): Double, Boolean, String, List, Set, Map
#[derive(ForyObject, Debug, PartialEq)]
#[fory(debug)]
struct NullableComprehensiveSchemaConsistent {
    // Base non-nullable primitive fields
    byte_field: i8,
    short_field: i16,
    int_field: i32,
    long_field: i64,
    float_field: f32,
    double_field: f64,
    bool_field: bool,

    // Base non-nullable reference fields
    string_field: String,
    list_field: Vec<String>,
    set_field: HashSet<String>,
    map_field: HashMap<String, String>,

    // Nullable fields - first half using boxed types
    #[fory(nullable = true)]
    nullable_int: Option<i32>,
    #[fory(nullable = true)]
    nullable_long: Option<i64>,
    #[fory(nullable = true)]
    nullable_float: Option<f32>,

    // Nullable fields - second half using @ForyField annotation
    #[fory(nullable = true)]
    nullable_double: Option<f64>,
    #[fory(nullable = true)]
    nullable_bool: Option<bool>,
    #[fory(nullable = true)]
    nullable_string: Option<String>,
    #[fory(nullable = true)]
    nullable_list: Option<Vec<String>>,
    #[fory(nullable = true)]
    nullable_set: Option<HashSet<String>>,
    #[fory(nullable = true)]
    nullable_map: Option<HashMap<String, String>>,
}

/// Cross-language schema evolution test struct for COMPATIBLE mode.
/// This struct has INVERTED nullability compared to Java:
/// - Group 1: Nullable in Rust (Option), Non-nullable in Java
/// - Group 2: Non-nullable in Rust, Nullable in Java (@ForyField(nullable=true))
///
/// This tests that compatible mode properly handles schema differences across languages.
#[derive(ForyObject, Debug, PartialEq)]
#[fory(debug)]
struct NullableComprehensiveCompatible {
    // Group 1: Nullable in Rust, Non-nullable in Java
    // Primitive fields
    #[fory(nullable = true)]
    byte_field: Option<i8>,
    #[fory(nullable = true)]
    short_field: Option<i16>,
    #[fory(nullable = true)]
    int_field: Option<i32>,
    #[fory(nullable = true)]
    long_field: Option<i64>,
    #[fory(nullable = true)]
    float_field: Option<f32>,
    #[fory(nullable = true)]
    double_field: Option<f64>,
    #[fory(nullable = true)]
    bool_field: Option<bool>,

    // Boxed fields - also nullable in Rust
    #[fory(nullable = true)]
    boxed_int: Option<i32>,
    #[fory(nullable = true)]
    boxed_long: Option<i64>,
    #[fory(nullable = true)]
    boxed_float: Option<f32>,
    #[fory(nullable = true)]
    boxed_double: Option<f64>,
    #[fory(nullable = true)]
    boxed_bool: Option<bool>,

    // Reference fields - also nullable in Rust
    #[fory(nullable = true)]
    string_field: Option<String>,
    #[fory(nullable = true)]
    list_field: Option<Vec<String>>,
    #[fory(nullable = true)]
    set_field: Option<HashSet<String>>,
    #[fory(nullable = true)]
    map_field: Option<HashMap<String, String>>,

    // Group 2: Non-nullable in Rust, Nullable in Java (@ForyField(nullable=true))
    // Boxed types
    nullable_int1: i32,
    nullable_long1: i64,
    nullable_float1: f32,
    nullable_double1: f64,
    nullable_bool1: bool,

    // Reference types
    nullable_string2: String,
    nullable_list2: Vec<String>,
    nullable_set2: HashSet<String>,
    nullable_map2: HashMap<String, String>,
}

#[test]
#[ignore]
fn test_nullable_field_schema_consistent_not_null() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(false).xlang(true);
    fory.register::<NullableComprehensiveSchemaConsistent>(401)
        .unwrap();

    let local_obj = NullableComprehensiveSchemaConsistent {
        // Base non-nullable primitive fields
        byte_field: 1,
        short_field: 2,
        int_field: 42,
        long_field: 123456789,
        float_field: 1.5,
        double_field: 2.5,
        bool_field: true,

        // Base non-nullable reference fields
        string_field: "hello".to_string(),
        list_field: vec!["a".to_string(), "b".to_string(), "c".to_string()],
        set_field: HashSet::from(["x".to_string(), "y".to_string()]),
        map_field: HashMap::from([
            ("key1".to_string(), "value1".to_string()),
            ("key2".to_string(), "value2".to_string()),
        ]),

        // Nullable fields - all have values (first half - boxed)
        nullable_int: Some(100),
        nullable_long: Some(200),
        nullable_float: Some(1.5),

        // Nullable fields - all have values (second half - @ForyField)
        nullable_double: Some(2.5),
        nullable_bool: Some(false),
        nullable_string: Some("nullable_value".to_string()),
        nullable_list: Some(vec!["p".to_string(), "q".to_string()]),
        nullable_set: Some(HashSet::from(["m".to_string(), "n".to_string()])),
        nullable_map: Some(HashMap::from([("nk1".to_string(), "nv1".to_string())])),
    };

    let remote_obj: NullableComprehensiveSchemaConsistent = fory.deserialize(&bytes).unwrap();
    assert_eq!(remote_obj, local_obj);

    let new_bytes = fory.serialize(&remote_obj).unwrap();
    fs::write(&data_file_path, new_bytes).unwrap();
}

#[test]
#[ignore]
fn test_nullable_field_schema_consistent_null() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(false).xlang(true);
    fory.register::<NullableComprehensiveSchemaConsistent>(401)
        .unwrap();

    let local_obj = NullableComprehensiveSchemaConsistent {
        // Base non-nullable primitive fields - must have values
        byte_field: 1,
        short_field: 2,
        int_field: 42,
        long_field: 123456789,
        float_field: 1.5,
        double_field: 2.5,
        bool_field: true,

        // Base non-nullable reference fields - must have values
        string_field: "hello".to_string(),
        list_field: vec!["a".to_string(), "b".to_string(), "c".to_string()],
        set_field: HashSet::from(["x".to_string(), "y".to_string()]),
        map_field: HashMap::from([
            ("key1".to_string(), "value1".to_string()),
            ("key2".to_string(), "value2".to_string()),
        ]),

        // Nullable fields - all null (first half - boxed)
        nullable_int: None,
        nullable_long: None,
        nullable_float: None,

        // Nullable fields - all null (second half - @ForyField)
        nullable_double: None,
        nullable_bool: None,
        nullable_string: None,
        nullable_list: None,
        nullable_set: None,
        nullable_map: None,
    };

    let remote_obj: NullableComprehensiveSchemaConsistent = fory.deserialize(&bytes).unwrap();
    assert_eq!(remote_obj, local_obj);

    let new_bytes = fory.serialize(&remote_obj).unwrap();
    fs::write(&data_file_path, new_bytes).unwrap();
}

/// Test cross-language schema evolution - all fields have values.
/// Java sends: Group 1 (non-nullable) + Group 2 (nullable with values)
/// Rust reads: Group 1 (nullable/Option) + Group 2 (non-nullable)
#[test]
#[ignore]
fn test_nullable_field_compatible_not_null() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<NullableComprehensiveCompatible>(402)
        .unwrap();

    let local_obj = NullableComprehensiveCompatible {
        // Group 1: Nullable in Rust (read from Java's non-nullable)
        byte_field: Some(1),
        short_field: Some(2),
        int_field: Some(42),
        long_field: Some(123456789),
        float_field: Some(1.5),
        double_field: Some(2.5),
        bool_field: Some(true),

        boxed_int: Some(10),
        boxed_long: Some(20),
        boxed_float: Some(1.1),
        boxed_double: Some(2.2),
        boxed_bool: Some(true),

        string_field: Some("hello".to_string()),
        list_field: Some(vec!["a".to_string(), "b".to_string(), "c".to_string()]),
        set_field: Some(HashSet::from(["x".to_string(), "y".to_string()])),
        map_field: Some(HashMap::from([
            ("key1".to_string(), "value1".to_string()),
            ("key2".to_string(), "value2".to_string()),
        ])),

        // Group 2: Non-nullable in Rust (read from Java's nullable with values)
        nullable_int1: 100,
        nullable_long1: 200,
        nullable_float1: 1.5,
        nullable_double1: 2.5,
        nullable_bool1: false,

        nullable_string2: "nullable_value".to_string(),
        nullable_list2: vec!["p".to_string(), "q".to_string()],
        nullable_set2: HashSet::from(["m".to_string(), "n".to_string()]),
        nullable_map2: HashMap::from([("nk1".to_string(), "nv1".to_string())]),
    };

    let remote_obj: NullableComprehensiveCompatible = fory.deserialize(&bytes).unwrap();
    assert_eq!(remote_obj, local_obj);

    let new_bytes = fory.serialize(&remote_obj).unwrap();
    fs::write(&data_file_path, new_bytes).unwrap();
}

/// Test cross-language schema evolution - nullable fields are null.
/// Java sends: Group 1 (non-nullable with values) + Group 2 (nullable with null)
/// Rust reads: Group 1 (nullable/Option) + Group 2 (non-nullable -> defaults)
///
/// When Java sends null for Group 2 fields, Rust's non-nullable fields receive
/// default values (0 for numbers, false for bool, empty for collections/strings).
#[test]
#[ignore]
fn test_nullable_field_compatible_null() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(true).xlang(true);
    fory.register::<NullableComprehensiveCompatible>(402)
        .unwrap();

    let local_obj = NullableComprehensiveCompatible {
        // Group 1: Nullable in Rust (read from Java's non-nullable)
        byte_field: Some(1),
        short_field: Some(2),
        int_field: Some(42),
        long_field: Some(123456789),
        float_field: Some(1.5),
        double_field: Some(2.5),
        bool_field: Some(true),

        boxed_int: Some(10),
        boxed_long: Some(20),
        boxed_float: Some(1.1),
        boxed_double: Some(2.2),
        boxed_bool: Some(true),

        string_field: Some("hello".to_string()),
        list_field: Some(vec!["a".to_string(), "b".to_string(), "c".to_string()]),
        set_field: Some(HashSet::from(["x".to_string(), "y".to_string()])),
        map_field: Some(HashMap::from([
            ("key1".to_string(), "value1".to_string()),
            ("key2".to_string(), "value2".to_string()),
        ])),

        // Group 2: Non-nullable in Rust (Java sent null -> use defaults)
        nullable_int1: 0,
        nullable_long1: 0,
        nullable_float1: 0.0,
        nullable_double1: 0.0,
        nullable_bool1: false,

        nullable_string2: String::new(),
        nullable_list2: Vec::new(),
        nullable_set2: HashSet::new(),
        nullable_map2: HashMap::new(),
    };

    let remote_obj: NullableComprehensiveCompatible = fory.deserialize(&bytes).unwrap();
    assert_eq!(remote_obj, local_obj);

    let new_bytes = fory.serialize(&remote_obj).unwrap();
    fs::write(&data_file_path, new_bytes).unwrap();
}

// ============================================================================
// Union Xlang Tests - Rust enum <-> Java Union2
// ============================================================================

/// Rust enum that matches Java Union2<String, Long>
/// Each variant has exactly one field to be Union-compatible
#[derive(ForyObject, Debug, PartialEq)]
enum StringOrLong {
    Str(String),
    Long(i64),
}

impl Default for StringOrLong {
    fn default() -> Self {
        StringOrLong::Str(String::default())
    }
}

/// Struct containing a Union field, matches Java StructWithUnion2
#[derive(ForyObject, Debug, PartialEq)]
struct StructWithUnion2 {
    union: StringOrLong,
}

/// Test cross-language Union serialization between Rust enum and Java Union2.
///
/// Rust enum with single-field variants is Union-compatible and can be deserialized
/// from Java Union2 types. Union fields in xlang mode follow a special format:
/// - Rust writes: ref_flag + union_data (no type_id, since Union fields skip type info)
/// - Java reads: null_flag + union_data (directly calls UnionSerializer.read())
#[test]
#[ignore]
fn test_union_xlang() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(true).xlang(true);
    // Register both the enum and the struct that contains it
    fory.register::<StringOrLong>(300).unwrap();
    fory.register::<StructWithUnion2>(301).unwrap();

    // Read struct1 with String value (index 0)
    let mut reader = Reader::new(bytes.as_slice());
    let struct1: StructWithUnion2 = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(struct1.union, StringOrLong::Str("hello".to_string()));

    // Read struct2 with Long value (index 1)
    let struct2: StructWithUnion2 = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(struct2.union, StringOrLong::Long(42));

    // Serialize back
    let mut buf = Vec::new();
    fory.serialize_to(&mut buf, &struct1).unwrap();
    fory.serialize_to(&mut buf, &struct2).unwrap();
    fs::write(&data_file_path, buf).unwrap();
}

// ============================================================================
// Reference Tracking Tests - Cross-language shared reference tests
// ============================================================================

use std::rc::Rc;

/// Inner struct for reference tracking test (SCHEMA_CONSISTENT mode)
/// Matches Java RefInnerSchemaConsistent with type ID 501
#[derive(ForyObject, Debug, PartialEq, Clone)]
struct RefInnerSchemaConsistent {
    id: i32,
    name: String,
}

/// Outer struct for reference tracking test (SCHEMA_CONSISTENT mode)
/// Contains two fields that both point to the same inner object.
/// Matches Java RefOuterSchemaConsistent with type ID 502
/// Uses Option<Rc<T>> for nullable reference-tracked fields - Rc enables reference tracking
#[derive(ForyObject, Debug, PartialEq)]
#[fory(debug)]
struct RefOuterSchemaConsistent {
    inner1: Option<Rc<RefInnerSchemaConsistent>>,
    inner2: Option<Rc<RefInnerSchemaConsistent>>,
}

/// Inner struct for reference tracking test (COMPATIBLE mode)
/// Matches Java RefInnerCompatible with type ID 503
#[derive(ForyObject, Debug, PartialEq, Clone)]
#[fory(debug)]
struct RefInnerCompatible {
    id: i32,
    name: String,
}

/// Outer struct for reference tracking test (COMPATIBLE mode)
/// Contains two fields that both point to the same inner object.
/// Matches Java RefOuterCompatible with type ID 504
/// Uses Option<Rc<T>> for nullable reference-tracked fields - Rc enables reference tracking
#[derive(ForyObject, Debug, PartialEq)]
#[fory(debug)]
struct RefOuterCompatible {
    inner1: Option<Rc<RefInnerCompatible>>,
    inner2: Option<Rc<RefInnerCompatible>>,
}

/// Test cross-language reference tracking in SCHEMA_CONSISTENT mode (compatible=false).
///
/// This test verifies that when Java serializes an object where two fields point to
/// the same instance, Rust can properly deserialize it and both fields will contain
/// equal values. When re-serializing, the reference relationship should be preserved.
#[test]
#[ignore]
fn test_ref_schema_consistent() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default()
        .compatible(false)
        .xlang(true)
        .track_ref(true);
    fory.register::<RefInnerSchemaConsistent>(501).unwrap();
    fory.register::<RefOuterSchemaConsistent>(502).unwrap();

    let outer: RefOuterSchemaConsistent = fory.deserialize(&bytes).unwrap();

    // Both inner1 and inner2 should have values
    assert!(outer.inner1.is_some(), "inner1 should not be None");
    assert!(outer.inner2.is_some(), "inner2 should not be None");

    // Both should have the same values (they reference the same object in Java)
    let inner1 = outer.inner1.as_ref().unwrap();
    let inner2 = outer.inner2.as_ref().unwrap();
    assert_eq!(inner1.id, 42);
    assert_eq!(inner1.name, "shared_inner");
    // Compare the values (Rc contents)
    assert_eq!(
        inner1.as_ref(),
        inner2.as_ref(),
        "inner1 and inner2 should have equal values"
    );

    // With Rc, after deserialization with ref tracking, both fields should point to the same Rc
    assert!(
        Rc::ptr_eq(inner1, inner2),
        "inner1 and inner2 should be the same Rc (reference identity)"
    );

    // Re-serialize and write back
    let new_bytes = fory.serialize(&outer).unwrap();
    fs::write(&data_file_path, new_bytes).unwrap();
}

/// Test cross-language reference tracking in COMPATIBLE mode (compatible=true).
///
/// This test verifies reference tracking works correctly with schema evolution support.
/// The inner object is shared between two fields, and this relationship should be
/// preserved through serialization/deserialization.
#[test]
#[ignore]
fn test_ref_compatible() {
    let data_file_path = get_data_file();
    let bytes = fs::read(&data_file_path).unwrap();

    let mut fory = Fory::default().compatible(true).xlang(true).track_ref(true);
    fory.register::<RefInnerCompatible>(503).unwrap();
    fory.register::<RefOuterCompatible>(504).unwrap();

    let outer: RefOuterCompatible = fory.deserialize(&bytes).unwrap();

    // Both inner1 and inner2 should have values
    assert!(outer.inner1.is_some(), "inner1 should not be None");
    assert!(outer.inner2.is_some(), "inner2 should not be None");

    // Both should have the same values (they reference the same object in Java)
    let inner1 = outer.inner1.as_ref().unwrap();
    let inner2 = outer.inner2.as_ref().unwrap();
    assert_eq!(inner1.id, 99);
    assert_eq!(inner1.name, "compatible_shared");
    // Compare the values (Rc contents)
    assert_eq!(
        inner1.as_ref(),
        inner2.as_ref(),
        "inner1 and inner2 should have equal values"
    );

    // With Rc, after deserialization with ref tracking, both fields should point to the same Rc
    assert!(
        Rc::ptr_eq(inner1, inner2),
        "inner1 and inner2 should be the same Rc (reference identity)"
    );

    // Re-serialize and write back
    let new_bytes = fory.serialize(&outer).unwrap();
    fs::write(&data_file_path, new_bytes).unwrap();
}
