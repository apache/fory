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

use std::collections::{HashMap, HashSet};
use chrono::{NaiveDate, NaiveDateTime};
use fory_core::buffer::{Reader, Writer};
use fory_core::error::Error;
use fory_core::fory::{read, write, Fory};
use fory_core::resolver::context::{ReadContext, WriteContext};
use fory_core::serializer::Serializer;
use fory_core::types::Mode::Compatible;
use fory_derive::Fory;

#[derive(Fory, Default, Debug, PartialEq, Eq, Hash)]
struct Item {
    id: i32,
}

#[derive(Fory, Default, Debug, PartialEq, Eq, Hash)]
enum Color {
    #[default]
    Green,
    Red,
    Blue,
    White,
}

#[derive(Fory, Default, Debug, PartialEq)]
struct Person {
    // primitive
    f1: bool,
    f2: i8,
    f3: i16,
    f4: i32,
    f5: i64,
    f6: f32,
    f7: f64,
    // nullable_primitive
    f8: Option<bool>,
    f9: Option<i8>,
    f10: Option<i16>,
    f11: Option<i32>,
    f12: Option<i64>,
    f13: Option<f32>,
    f14: Option<f64>,
    // final (string/enum/time/primitive_list)
    f15: String,
    f16: Color,
    f17: NaiveDate,
    f18: NaiveDateTime,
    f19: Item,
    f20: Vec<bool>,
    f21: Vec<i8>,
    f22: Vec<i16>,
    f23: Vec<i32>,
    f24: Vec<i64>,
    f25: Vec<f32>,
    f26: Vec<f64>,
    // collection
    f27: Vec<String>,
    f28: HashSet<String>,
    // map
    f29: HashMap<String, i32>,
}

#[derive(Fory, Default, Debug, PartialEq)]
struct Empty {}

#[test]
fn basic() {
    let mut fory1 = Fory::default().mode(Compatible);
    fory1.register::<Color>(101);
    fory1.register::<Item>(102);
    fory1.register::<Person>(103);
    let mut fory2 = Fory::default().mode(Compatible);
    fory2.register_by_name::<Color>("color");
    fory2.register_by_name::<Item>("item");
    fory2.register_by_name::<Person>("person");
    for fory in [fory1, fory2] {
        let mut writer = Writer::default();
        let mut write_context = WriteContext::new(&fory, &mut writer);
        let person = Person::default();
        fory.serialize_with_context(&person, &mut write_context);
        fory.serialize_with_context(&person, &mut write_context);
        let bytes = write_context.writer.dump();
        let reader = Reader::new(bytes.as_slice());
        let mut read_context = ReadContext::new(&fory, reader);
        assert_eq!(person, fory.deserialize_with_context::<Person>(&mut read_context).unwrap());
        assert_eq!(person, fory.deserialize_with_context::<Person>(&mut read_context).unwrap());
        assert_eq!(read_context.reader.slice_after_cursor().len(), 0);
    }
}

#[test]
fn outer_nullable() {
    let mut fory1 = Fory::default().mode(Compatible);
    fory1.register::<Color>(101);
    fory1.register::<Item>(102);
    fory1.register::<Person>(103);
    let mut fory2 = Fory::default().mode(Compatible);
    fory2.register_by_name::<Color>("color");
    fory2.register_by_name::<Item>("item");
    fory2.register_by_name::<Person>("person");
    for fory in [fory1, fory2] {
        let null_person: Option<Person> = None;
        let bytes = fory.serialize(&null_person);
        assert_eq!(fory.deserialize::<Person>(&bytes).unwrap(), Person::default());
    }
}

#[test]
fn skip_basic() {
    let person_default = Person::default();
    let person2_default = Empty::default();

    let mut id_fory1 = Fory::default().mode(Compatible);
    id_fory1.register::<Color>(101);
    id_fory1.register::<Item>(102);
    id_fory1.register::<Person>(103);
    let mut id_fory2 = Fory::default().mode(Compatible);
    id_fory2.register::<Empty>(103);

    let mut name_fory1 = Fory::default().mode(Compatible);
    name_fory1.register_by_name::<Color>("color");
    name_fory1.register_by_name::<Item>("item");
    name_fory1.register_by_name::<Person>("person");
    let mut name_fory2 = Fory::default().mode(Compatible);
    name_fory2.register_by_name::<Empty>("person");

    for (fory1, fory2) in [(id_fory1, id_fory2), (name_fory1, name_fory2)] {
        let bytes = fory1.serialize(&person_default);
        assert_eq!(fory2.deserialize::<Empty>(&bytes).unwrap(), person2_default);
        let bytes = fory2.serialize(&person2_default);
        assert_eq!(fory1.deserialize::<Person>(&bytes).unwrap(), person_default);
    }
}

#[test]
fn nested() {
    #[derive(Fory, Default, Debug, PartialEq)]
    struct Element {
        f1: Vec<Item>,
        f2: HashSet<Item>,
        f3: HashMap<Item, Item>,
        f4: Vec<Color>,
        f5: HashSet<Color>,
        f6: HashMap<Color, Color>,
    }
    #[derive(Fory, Default, Debug, PartialEq)]
    struct Nested {
        f1: Vec<Item>,
        f2: HashSet<Item>,
        f3: HashMap<Item, Item>,
        f4: Vec<Color>,
        f5: HashSet<Color>,
        f6: HashMap<Color, Color>,
        f7: Element,
    }
    let mut id_fory1 = Fory::default().mode(Compatible);
    id_fory1.register::<Item>(101);
    id_fory1.register::<Color>(102);
    id_fory1.register::<Element>(103);
    id_fory1.register::<Nested>(104);
    let mut id_fory2 = Fory::default().mode(Compatible);
    id_fory2.register::<Empty>(104);

    let mut name_fory1 = Fory::default().mode(Compatible);
    name_fory1.register_by_name::<Item>("item");
    name_fory1.register_by_name::<Color>("color");
    name_fory1.register_by_name::<Element>("element");
    name_fory1.register_by_name::<Nested>("nested");
    let mut name_fory2 = Fory::default().mode(Compatible);
    name_fory2.register_by_name::<Empty>("nested");

    for (fory1, fory2) in [(id_fory1, id_fory2), (name_fory1, name_fory2)] {
        let bytes = fory1.serialize(&Nested::default());
        assert_eq!(fory2.deserialize::<Empty>(&bytes).unwrap(), Empty::default());
        let bytes = fory2.serialize(&Empty::default());
        assert_eq!(fory1.deserialize::<Nested>(&bytes).unwrap(), Nested::default());
    }
}

#[test]
fn compatible_nullable() {
    #[derive(Fory, Default, Debug, PartialEq)]
    struct Nonnull {
        f1: bool,
        f2: i8,
        f3: i16,
        f4: i32,
        f5: i64,
        f6: f32,
        f7: f64,
        f15: String,
        f16: Color,
        f17: NaiveDate,
        f18: NaiveDateTime,
        f19: Item,
        f20: Vec<bool>,
        f21: Vec<i8>,
        f22: Vec<i16>,
        f23: Vec<i32>,
        f24: Vec<i64>,
        f25: Vec<f32>,
        f26: Vec<f64>,
        f27: Vec<String>,
        f28: HashSet<String>,
        f29: HashMap<String, i32>,
    }
    #[derive(Fory, Default, Debug, PartialEq)]
    struct Nullable {
        f1: Option<bool>,
        f2: Option<i8>,
        f3: Option<i16>,
        f4: Option<i32>,
        f5: Option<i64>,
        f6: Option<f32>,
        f7: Option<f64>,
        f15: Option<String>,
        f16: Option<Color>,
        f17: Option<NaiveDate>,
        f18: Option<NaiveDateTime>,
        f19: Option<Item>,
        f20: Option<Vec<bool>>,
        f21: Option<Vec<i8>>,
        f22: Option<Vec<i16>>,
        f23: Option<Vec<i32>>,
        f24: Option<Vec<i64>>,
        f25: Option<Vec<f32>>,
        f26: Option<Vec<f64>>,
        f27: Option<Vec<String>>,
        f28: Option<HashSet<String>>,
        f29: Option<HashMap<String, i32>>,
    }
    let nullable_obj =  Nullable {
        f1: Some(bool::default()),
        f2: Some(i8::default()),
        f3: Some(i16::default()),
        f4: Some(i32::default()),
        f5: Some(i64::default()),
        f6: Some(f32::default()),
        f7: Some(f64::default()),
        f15: Some(String::default()),
        f16: Some(Color::default()),
        f17: Some(NaiveDate::from_ymd_opt(1970, 1, 1).unwrap()),
        f18: Some(NaiveDateTime::from_timestamp_opt(0, 0).unwrap()),
        f19: Some(Item::default()),
        f20: Some(Vec::<bool>::default()),
        f21: Some(Vec::<i8>::default()),
        f22: Some(Vec::<i16>::default()),
        f23: Some(Vec::<i32>::default()),
        f24: Some(Vec::<i64>::default()),
        f25: Some(Vec::<f32>::default()),
        f26: Some(Vec::<f64>::default()),
        f27: Some(Vec::<String>::default()),
        f28: Some(HashSet::<String>::default()),
        f29: Some(HashMap::<String, i32>::default()),
    };
    let mut id_fory1 = Fory::default().mode(Compatible);
    id_fory1.register::<Color>(101);
    id_fory1.register::<Item>(102);
    id_fory1.register::<Nonnull>(103);
    let mut id_fory2 = Fory::default().mode(Compatible);
    id_fory2.register::<Color>(101);
    id_fory2.register::<Item>(102);
    id_fory2.register::<Nullable>(103);

    let mut name_fory1 = Fory::default().mode(Compatible);
    name_fory1.register_by_name::<Color>("color");
    name_fory1.register_by_name::<Item>("item");
    name_fory1.register_by_name::<Nonnull>("obj");
    let mut name_fory2 = Fory::default().mode(Compatible);
    name_fory2.register_by_name::<Color>("color");
    name_fory2.register_by_name::<Item>("item");
    name_fory2.register_by_name::<Nullable>("obj");

    for (fory1, fory2) in [(id_fory1, id_fory2), (name_fory1, name_fory2)] {
        let bytes = fory1.serialize(&Nonnull::default());
        assert_eq!(fory2.deserialize::<Nullable>(&bytes).unwrap(), nullable_obj);
        let bytes = fory2.serialize(&Nullable::default());
        assert_eq!(fory1.deserialize::<Nonnull>(&bytes).unwrap(), Nonnull::default());
    }
}

#[test]
fn name_mismatch() {
    #[derive(Fory, Default, Debug, PartialEq)]
    struct MismatchPerson {
        f2: bool,
        f3: i8,
        f4: i16,
        f5: i32,
        f6: i64,
        f7: f32,
        f8: f64,
        f9: Option<bool>,
        f10: Option<i8>,
        f11: Option<i16>,
        f12: Option<i32>,
        f13: Option<i64>,
        f14: Option<f32>,
        f15: Option<f64>,
        f16: String,
        f17: Color,
        f18: NaiveDate,
        f19: NaiveDateTime,
        f20: Item,
        f21: Vec<bool>,
        f22: Vec<i8>,
        f23: Vec<i16>,
        f24: Vec<i32>,
        f25: Vec<i64>,
        f26: Vec<f32>,
        f27: Vec<f64>,
        f28: Vec<String>,
        f29: HashSet<String>,
        f30: HashMap<String, i32>,
    }
    let mut id_fory1 = Fory::default().mode(Compatible);
    id_fory1.register::<Color>(101);
    id_fory1.register::<Item>(102);
    id_fory1.register::<Person>(103);
    let mut id_fory2 = Fory::default().mode(Compatible);
    id_fory2.register::<Color>(101);
    id_fory2.register::<Item>(102);
    id_fory2.register::<MismatchPerson>(103);

    let mut name_fory1 = Fory::default().mode(Compatible);
    name_fory1.register_by_name::<Color>("color");
    name_fory1.register_by_name::<Item>("item");
    name_fory1.register_by_name::<Person>("person");
    let mut name_fory2 = Fory::default().mode(Compatible);
    name_fory2.register_by_name::<Color>("color");
    name_fory2.register_by_name::<Item>("item");
    name_fory2.register_by_name::<MismatchPerson>("person");

    for (fory1, fory2) in [(id_fory1, id_fory2), (name_fory1, name_fory2)] {
        let bytes = fory1.serialize(&Person::default());
        assert_eq!(fory2.deserialize::<MismatchPerson>(&bytes).unwrap(), MismatchPerson::default());
        let bytes = fory2.serialize(&MismatchPerson::default());
        assert_eq!(fory1.deserialize::<Person>(&bytes).unwrap(), Person::default());
    }
}

#[test]
fn ext() {
    #[derive(Debug, PartialEq, Default)]
    struct ExtItem {
        id: i32,
    }
    impl Serializer for ExtItem {
        fn fory_write_data(&self, context: &mut WriteContext, is_field: bool) {
            write(&self.id, context, is_field, false, false);
        }
        fn fory_read_data(context: &mut ReadContext, is_field: bool) -> Result<Self, Error> {
            Ok(Self {
                id: read(context, is_field, false, false)?,
            })
        }
    }
    #[derive(Fory, Debug, PartialEq, Default)]
    struct ExtWrapper {
        f1: ExtItem,
    }

    let mut id_fory = Fory::default().mode(Compatible).xlang(true);
    id_fory.register_serializer::<ExtItem>(100);
    id_fory.register::<ExtWrapper>(101);

    let mut name_fory = Fory::default().mode(Compatible).xlang(true);
    name_fory.register_serializer_by_name::<ExtItem>("ext_item");
    name_fory.register::<ExtWrapper>(101);

    for fory in [id_fory, name_fory] {
        let wrapper = ExtWrapper {f1: ExtItem { id: 1} };
        let bytes = fory.serialize(&wrapper);
        assert_eq!(fory.deserialize::<ExtWrapper>(&bytes).unwrap(), wrapper);
    }
}

#[test]
fn skip_ext() {
    #[derive(Debug, PartialEq, Default)]
    struct ExtItem {
        id: i32,
    }
    impl Serializer for ExtItem {
        fn fory_write_data(&self, context: &mut WriteContext, is_field: bool) {
            write(&self.id, context, is_field, false, false);
        }
        fn fory_read_data(context: &mut ReadContext, is_field: bool) -> Result<Self, Error> {
            Ok(Self {
                id: read(context, is_field, false, false)?,
            })
        }
    }
    #[derive(Fory, Debug, PartialEq, Default)]
    struct ExtWrapper {
        f1: ExtItem,
    }

    let mut id_fory1 = Fory::default().mode(Compatible).xlang(true);
    id_fory1.register_serializer::<ExtItem>(100);
    id_fory1.register::<ExtWrapper>(101);
    let mut id_fory2 = Fory::default().mode(Compatible).xlang(true);
    id_fory2.register_serializer::<ExtItem>(100);
    id_fory2.register::<Empty>(101);

    let mut name_fory1 = Fory::default().mode(Compatible).xlang(true);
    name_fory1.register_serializer_by_name::<ExtItem>("ext_item");
    name_fory1.register::<ExtWrapper>(101);
    let mut name_fory2 = Fory::default().mode(Compatible).xlang(true);
    name_fory2.register_serializer_by_name::<ExtItem>("ext_item");
    name_fory2.register::<Empty>(101);

    for (fory1, fory2) in [(id_fory1, id_fory2),(name_fory1, name_fory2)] {
        let wrapper = ExtWrapper {f1: ExtItem { id: 1} };
        let bytes = fory1.serialize(&wrapper);
        assert_eq!(fory2.deserialize::<Empty>(&bytes).unwrap(), Empty::default());
    }
}
