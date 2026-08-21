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
use fory_core::{Error, ReadContext, RefMode, Serializer, WriteContext};
use fory_derive::{ForyEnum, ForyStruct, ForyUnion};
use std::collections::{HashMap, HashSet};
use std::marker::PhantomData;

// RUSTFLAGS="-Awarnings" cargo expand -p tests --test test_struct
#[test]
fn simple() {
    #[derive(ForyStruct, Debug)]
    struct Animal1 {
        f1: HashMap<i8, Vec<i8>>,
        f2: String,
        f3: Vec<i8>,
        f5: String,
        f6: Vec<i8>,
        f7: i8,
        last: i8,
    }

    #[derive(ForyStruct, Debug)]
    struct Animal2 {
        f1: HashMap<i8, Vec<i8>>,
        f3: Vec<i8>,
        f4: String,
        f5: i8,
        f6: Vec<i8>,
        f7: i16,
        last: i8,
    }
    let mut fory1 = Fory::builder().xlang(false).compatible(true).build();
    let mut fory2 = Fory::builder().xlang(false).compatible(true).build();
    fory1.register::<Animal1>(999).unwrap();
    fory2.register::<Animal2>(999).unwrap();
    let animal: Animal1 = Animal1 {
        f1: HashMap::from([(1, vec![2])]),
        f2: String::from("hello"),
        f3: vec![1, 2, 3],
        f5: String::from("5"),
        f6: vec![42],
        f7: 43,
        last: 44,
    };
    let bin = fory1.serialize(&animal).unwrap();
    let obj: Animal2 = fory2.deserialize(&bin).unwrap();
    assert_eq!(animal.f1, obj.f1);
    assert_eq!(animal.f3, obj.f3);
    assert_eq!(obj.f4, String::default());
    assert_eq!(obj.f5, 5);
    assert_eq!(obj.f6, animal.f6);
    assert_eq!(obj.f7, 43);
    assert_eq!(animal.last, obj.last);
}

#[test]
fn compatible_list_array_field_pairs() {
    #[derive(ForyStruct, Debug)]
    struct ListPayload {
        payload: Vec<i32>,
    }

    #[derive(ForyStruct, Debug)]
    struct NullableListPayload {
        #[fory(list(element(nullable = true)))]
        payload: Vec<Option<i32>>,
    }

    #[derive(ForyStruct, Debug)]
    struct ArrayPayload {
        #[fory(array)]
        payload: Vec<i32>,
    }

    #[derive(ForyStruct, Debug)]
    struct NestedListPayload {
        payload: Vec<Vec<i32>>,
    }

    #[derive(ForyStruct, Debug)]
    struct NestedArrayPayload {
        #[fory(list(element(array)))]
        payload: Vec<Vec<i32>>,
    }

    let mut writer = Fory::builder().xlang(false).compatible(true).build();
    let mut reader = Fory::builder().xlang(false).compatible(true).build();
    writer.register::<ListPayload>(991).unwrap();
    reader.register::<ArrayPayload>(991).unwrap();
    let bytes = writer
        .serialize(&ListPayload {
            payload: vec![1, 2, 3],
        })
        .unwrap();
    let decoded: ArrayPayload = reader.deserialize(&bytes).unwrap();
    assert_eq!(decoded.payload, vec![1, 2, 3]);

    let mut writer = Fory::builder().xlang(false).compatible(true).build();
    let mut reader = Fory::builder().xlang(false).compatible(true).build();
    writer.register::<ArrayPayload>(992).unwrap();
    reader.register::<ListPayload>(992).unwrap();
    let bytes = writer
        .serialize(&ArrayPayload {
            payload: vec![1, 2, 3],
        })
        .unwrap();
    let decoded: ListPayload = reader.deserialize(&bytes).unwrap();
    assert_eq!(decoded.payload, vec![1, 2, 3]);

    let mut writer = Fory::builder().xlang(false).compatible(true).build();
    let mut reader = Fory::builder().xlang(false).compatible(true).build();
    writer.register::<NullableListPayload>(993).unwrap();
    reader.register::<ArrayPayload>(993).unwrap();
    let bytes = writer
        .serialize(&NullableListPayload {
            payload: vec![Some(1), Some(2), Some(3)],
        })
        .unwrap();
    let decoded: ArrayPayload = reader.deserialize(&bytes).unwrap();
    assert_eq!(decoded.payload, vec![1, 2, 3]);

    let bytes = writer
        .serialize(&NullableListPayload {
            payload: vec![Some(1), None, Some(3)],
        })
        .unwrap();
    let err = reader
        .deserialize::<ArrayPayload>(&bytes)
        .expect_err("expected nullable list payload to fail compatible array read");
    assert!(
        err.to_string()
            .contains("compatible list to array field requires non-null elements"),
        "{err}"
    );

    let mut writer = Fory::builder().xlang(false).compatible(true).build();
    let mut reader = Fory::builder().xlang(false).compatible(true).build();
    writer.register::<NestedListPayload>(994).unwrap();
    reader.register::<NestedArrayPayload>(994).unwrap();
    let bytes = writer
        .serialize(&NestedListPayload {
            payload: vec![vec![1, 2], vec![3]],
        })
        .unwrap();
    let err = reader
        .deserialize::<NestedArrayPayload>(&bytes)
        .expect_err("expected nested list/array mismatch to fail classification");
    assert!(
        err.to_string()
            .contains("remote and local field schemas are not compatible"),
        "{err}"
    );
}

#[test]
fn skip_option() {
    #[derive(ForyStruct, Debug)]
    struct Item1 {
        f1: Option<i32>,
        f2: Option<String>,
        last: i64,
    }

    #[derive(ForyStruct, Debug)]
    struct Item2 {
        f1: i8,
        f2: i8,
        last: i64,
    }
    let mut fory1 = Fory::builder().xlang(false).compatible(true).build();
    let mut fory2 = Fory::builder().xlang(false).compatible(true).build();
    fory1.register::<Item1>(999).unwrap();
    fory2.register::<Item2>(999).unwrap();
    let item1 = Item1 {
        f1: None,
        f2: Some(String::from("2")),
        last: 42,
    };
    let bin = fory1.serialize(&item1).unwrap();
    let item2: Item2 = fory2.deserialize(&bin).unwrap();

    assert_eq!(item2.f1, i8::default());
    assert_eq!(item2.f2, 2);
    assert_eq!(item2.last, item1.last)
}

#[test]
fn nonexistent_struct() {
    #[derive(ForyStruct, Debug)]
    pub struct Item1 {
        f1: i8,
    }
    #[derive(ForyStruct, Debug, PartialEq)]
    pub struct Item2 {
        f1: i64,
    }
    #[derive(ForyStruct, Debug)]
    struct Person1 {
        f2: Item1,
        f3: i8,
        last: String,
    }
    #[derive(ForyStruct, Debug)]
    struct Person2 {
        f2: Item2,
        f3: i64,
        last: String,
    }
    let mut fory1 = Fory::builder().xlang(false).compatible(true).build();
    let mut fory2 = Fory::builder().xlang(false).compatible(true).build();
    fory1.register::<Item1>(899).unwrap();
    fory1.register::<Person1>(999).unwrap();
    fory2.register::<Item2>(799).unwrap();
    fory2.register::<Person2>(999).unwrap();
    let person = Person1 {
        f2: Item1 { f1: 42 },
        f3: 24,
        last: String::from("foo"),
    };
    let bin = fory1.serialize(&person).unwrap();
    let obj: Person2 = fory2.deserialize(&bin).unwrap();
    assert_eq!(obj.f2, Item2 { f1: 0 });
    assert_eq!(obj.f3, 24);
    assert_eq!(obj.last, person.last);
}

#[test]
fn rejects_serializer_container_mismatch() {
    #[derive(ForyStruct, Debug)]
    struct SetI8 {
        values: HashSet<i8>,
    }

    #[derive(ForyStruct, Debug)]
    struct SetI16 {
        values: HashSet<i16>,
    }

    let mut fory1 = Fory::builder().xlang(false).compatible(true).build();
    let mut fory2 = Fory::builder().xlang(false).compatible(true).build();
    fory1.register::<SetI8>(998).unwrap();
    fory2.register::<SetI16>(998).unwrap();
    let bin = fory1
        .serialize(&SetI8 {
            values: HashSet::from([1]),
        })
        .unwrap();
    let err = fory2
        .deserialize::<SetI16>(&bin)
        .expect_err("expected incompatible container element schema to fail classification");
    assert!(
        err.to_string()
            .contains("remote and local field schemas are not compatible"),
        "{err}"
    );
}

#[test]
fn option() {
    #[derive(ForyStruct, Debug, PartialEq)]
    #[fory(debug)]
    struct Animal {
        f1: Option<String>,
        f2: Option<String>,
        f3: Vec<Option<String>>,
        // adjacent Options are not supported
        // f4: Option<Option<String>>,
        f5: Vec<Option<Vec<Option<String>>>>,
        last: i64,
    }
    let mut fory = Fory::builder().xlang(false).compatible(true).build();
    fory.register::<Animal>(999).unwrap();
    let animal: Animal = Animal {
        f1: Some(String::from("f1")),
        f2: None,
        f3: vec![Option::<String>::None, Some(String::from("f3"))],
        f5: vec![Some(vec![Some(String::from("f1"))])],
        last: 666,
    };
    let bin = fory.serialize(&animal).unwrap();
    let obj: Animal = fory.deserialize(&bin).unwrap();
    assert_eq!(animal, obj);
}

#[test]
fn nullable() {
    /*
        f1: value -> value
        f2: value -> Option(value)
        f3: Option(value) -> value
        f4: Option(value) -> Option(value)
        f5: Option(None) -> Option(None)
        f6: Option(None) -> value_default
    */
    #[derive(ForyStruct, Debug)]
    pub struct Item1 {
        f2: i8,
        f3: Option<i8>,
        f4: Option<i8>,
        f5: Option<i8>,
        f6: Option<i8>,
        last: i64,
    }

    #[derive(ForyStruct, Debug)]
    pub struct Item2 {
        f2: Option<i8>,
        f3: i8,
        f4: Option<i8>,
        f5: Option<i8>,
        f6: i8,
        last: i64,
    }

    let mut fory1 = Fory::builder().xlang(false).compatible(true).build();
    let mut fory2 = Fory::builder().xlang(false).compatible(true).build();
    fory1.register::<Item1>(999).unwrap();
    fory2.register::<Item2>(999).unwrap();

    let item1 = Item1 {
        f2: 43,
        f3: Some(44),
        f4: Some(45),
        f5: None,
        f6: None,
        last: 666,
    };

    let bin = fory1.serialize(&item1).unwrap();
    let item2: Item2 = fory2.deserialize(&bin).unwrap();
    assert_eq!(item2.f2.unwrap(), item1.f2);
    assert_eq!(item2.f3, item1.f3.unwrap());
    assert_eq!(item2.f4, item1.f4);
    assert_eq!(item2.f5, item1.f5);
    assert_eq!(item2.f6, i8::default());
    assert_eq!(item2.last, item1.last);
}

#[test]
fn nullable_container() {
    #[derive(ForyStruct, Debug)]
    pub struct Item1 {
        f1: Vec<i8>,
        f2: Option<Vec<i8>>,
        f3: HashSet<i8>,
        f4: Option<HashSet<i8>>,
        f5: HashMap<i8, Vec<i8>>,
        f6: Option<HashMap<i8, Vec<i8>>>,
        f7: Option<Vec<i8>>,
        f8: Option<HashSet<i8>>,
        f9: Option<HashMap<i8, i8>>,
        last: i64,
    }

    #[derive(ForyStruct, Debug)]
    pub struct Item2 {
        f1: Option<Vec<i8>>,
        f2: Vec<i8>,
        f3: Option<HashSet<i8>>,
        f4: HashSet<i8>,
        f5: Option<HashMap<i8, Vec<i8>>>,
        f6: HashMap<i8, Vec<i8>>,
        f7: Vec<i8>,
        f8: HashSet<i8>,
        f9: HashMap<i8, i8>,
        last: i64,
    }

    let mut fory1 = Fory::builder().xlang(false).compatible(true).build();
    let mut fory2 = Fory::builder().xlang(false).compatible(true).build();
    fory1.register::<Item1>(999).unwrap();
    fory2.register::<Item2>(999).unwrap();

    let item1 = Item1 {
        f1: vec![44, 45],
        f2: Some(vec![43]),
        f3: HashSet::from([44, 45]),
        f4: Some(HashSet::from([46, 47])),
        f5: HashMap::from([(48, vec![49])]),
        f6: Some(HashMap::from([(48, vec![49])])),
        f7: None,
        f8: None,
        f9: None,
        last: 666,
    };

    let bin = fory1.serialize(&item1).unwrap();
    let item2: Item2 = fory2.deserialize(&bin).unwrap();

    assert_eq!(item2.f1.unwrap(), item1.f1);
    assert_eq!(item2.f2, item1.f2.unwrap());
    assert_eq!(item2.f3.unwrap(), item1.f3);
    assert_eq!(item2.f4, item1.f4.unwrap());
    assert_eq!(item2.f5.unwrap(), item1.f5);
    assert_eq!(item2.f6, item1.f6.unwrap());
    assert_eq!(item2.f7, Vec::default());
    assert_eq!(item2.f8, HashSet::default());
    assert_eq!(item2.f9, HashMap::default());
    assert_eq!(item2.last, item1.last);
}

#[test]
fn inner_nullable() {
    #[derive(ForyStruct, Debug)]
    #[fory(debug)]
    pub struct Item1 {
        f1: Vec<Option<String>>,
        f2: HashSet<Option<i8>>,
        f3: HashMap<i8, Option<i8>>,
        last: i64,
    }

    #[derive(ForyStruct, Debug)]
    #[fory(debug)]
    pub struct Item2 {
        f1: Vec<String>,
        f2: HashSet<i8>,
        f3: HashMap<i8, i8>,
        last: i64,
    }
    let mut fory1 = Fory::builder().xlang(false).compatible(true).build();
    let mut fory2 = Fory::builder().xlang(false).compatible(true).build();
    fory1.register::<Item1>(999).unwrap();
    fory2.register::<Item2>(999).unwrap();

    let item1 = Item1 {
        f1: vec![None, Some("hello".to_string())],
        f2: HashSet::from([None, Some(43)]),
        f3: HashMap::from([(44, None), (45, Some(46))]),
        last: 666,
    };
    let bin = fory1.serialize(&item1).unwrap();
    let item2: Item2 = fory2.deserialize(&bin).unwrap();

    assert_eq!(item2.f1, vec![String::default(), "hello".to_string()]);
    assert_eq!(item2.f2, HashSet::from([0, 43]));
    assert_eq!(item2.f3, HashMap::from([(44, 0), (45, 46)]));
    assert_eq!(item2.last, item1.last);
}

#[test]
fn nullable_struct() {
    #[derive(ForyStruct, Debug, PartialEq)]
    #[fory(debug)]
    pub struct Item {
        name: String,
        data: Vec<Option<String>>,
        last: i64,
    }

    #[derive(ForyStruct, Debug)]
    #[fory(debug)]
    pub struct Person1 {
        f1: Item,
        f2: Option<Item>,
        f3: Option<Item>,
        last: i64,
    }

    #[derive(ForyStruct, Debug)]
    #[fory(debug)]
    pub struct Person2 {
        f1: Option<Item>,
        f2: Item,
        f3: Item,
        last: i64,
    }
    let mut fory1 = Fory::builder().xlang(false).compatible(true).build();
    let mut fory2 = Fory::builder().xlang(false).compatible(true).build();
    fory1.register::<Item>(199).unwrap();
    fory1.register::<Person1>(200).unwrap();
    fory2.register::<Item>(199).unwrap();
    fory2.register::<Person2>(200).unwrap();

    let person1 = Person1 {
        f1: Item {
            name: "f1".to_string(),
            data: vec![None, Some("hi".to_string())],
            last: 43,
        },
        f2: None,
        f3: Some(Item {
            name: "b".to_string(),
            data: vec![None, Some("a".to_string())],
            last: 45,
        }),
        last: 46,
    };
    let bin = fory1.serialize(&person1).unwrap();
    let person2: Person2 = fory2.deserialize(&bin).unwrap();

    assert_eq!(person2.f1.unwrap(), person1.f1);
    assert_eq!(
        person2.f2,
        Item {
            name: String::new(),
            data: Vec::new(),
            last: 0,
        }
    );
    assert_eq!(person2.f3, person1.f3.unwrap());
    assert_eq!(person2.last, person1.last);
}

#[test]
fn enum_without_payload() {
    #[derive(ForyEnum, Debug, PartialEq, Default)]
    enum Color1 {
        #[default]
        Green,
        Red,
        Blue,
        White,
    }
    #[derive(ForyEnum, Debug, PartialEq, Default)]
    enum Color2 {
        #[default]
        Green,
        Red,
        Blue,
    }
    #[derive(ForyStruct, Debug, PartialEq)]
    #[fory(debug)]
    struct Person1 {
        f1: Color1,
        f2: Color1,
        // skip
        f3: Color2,
        f5: Vec<Color1>,
        f6: Option<Color1>,
        f7: Option<Color1>,
        f8: Color1,
        last: i8,
    }
    #[derive(ForyStruct, Debug, PartialEq)]
    #[fory(debug)]
    struct Person2 {
        // same
        f1: Color1,
        // type different
        f2: Color2,
        // should be default
        f4: Color2,
        f5: Vec<Color2>,
        f6: Color1,
        f7: Color1,
        f8: Option<Color1>,
        last: i8,
    }

    let mut fory1 = Fory::builder().compatible(true).xlang(true).build();
    fory1.register::<Color1>(101).unwrap();
    fory1.register::<Color2>(102).unwrap();
    fory1.register::<Person1>(103).unwrap();
    let mut fory2 = Fory::builder().compatible(true).xlang(true).build();
    fory2.register::<Color1>(101).unwrap();
    fory2.register::<Color2>(102).unwrap();
    fory2.register::<Person2>(103).unwrap();

    let person1 = Person1 {
        f1: Color1::Blue,
        f2: Color1::White,
        f3: Color2::Green,
        f5: vec![Color1::Blue],
        f6: Some(Color1::Blue),
        f7: None,
        f8: Color1::Red,
        last: 10,
    };
    let bin = fory1.serialize(&person1).unwrap();
    let person2: Person2 = fory2.deserialize(&bin).expect("");
    assert_eq!(person2.f1, person1.f1);
    assert_eq!(person2.f2, Color2::default());
    assert_eq!(person2.f4, Color2::default());
    assert_eq!(person2.f6, person1.f6.unwrap());
    assert_eq!(person2.f7, Color1::default());
    assert_eq!(person2.f8.unwrap(), person1.f8);
    assert_eq!(person2.last, person1.last);
}

#[test]
fn named_enum() {
    #[derive(ForyEnum, Debug, PartialEq, Default)]
    enum Color {
        #[default]
        Green,
        Red,
        Blue,
        White,
    }
    #[derive(ForyStruct, Debug, PartialEq)]
    #[fory(debug)]
    struct Item1 {
        f1: Color,
        f2: Color,
        f3: Option<Color>,
        f4: Option<Color>,
        f5: Option<Color>,
        f6: Option<Color>,
        // skip
        f7: Color,
        f8: Option<Color>,
        f9: Option<Color>,
        last: i8,
    }
    #[derive(ForyStruct, Debug, PartialEq)]
    #[fory(debug)]
    struct Item2 {
        f1: Color,
        f2: Option<Color>,
        f3: Color,
        f4: Option<Color>,
        f5: Color,
        f6: Option<Color>,
        last: i8,
    }
    let mut fory1 = Fory::builder().compatible(true).xlang(true).build();
    fory1.register_by_name::<Color>("a").unwrap();
    fory1.register::<Item1>(101).unwrap();
    let mut fory2 = Fory::builder().compatible(true).xlang(true).build();
    fory2.register_by_name::<Color>("a").unwrap();
    fory2.register::<Item2>(101).unwrap();
    let item1 = Item1 {
        f1: Color::Red,
        f2: Color::Blue,
        f3: Some(Color::White),
        f4: Some(Color::White),
        f5: None,
        f6: None,
        f7: Color::White,
        f8: Some(Color::White),
        f9: Some(Color::White),
        last: 42,
    };
    let expected_item2 = Item2 {
        f1: Color::Red,
        f2: Some(Color::Blue),
        f3: Color::White,
        f4: Some(Color::White),
        f5: Color::default(),
        f6: None,
        last: 42,
    };
    let bin = fory1.serialize(&item1).unwrap();
    let actual_item2: Item2 = fory2.deserialize(&bin).unwrap();
    assert_eq!(expected_item2, actual_item2);
}

#[test]
#[allow(clippy::unnecessary_literal_unwrap)]
fn boxed() {
    // cargo expand --test mod compatible::test_struct > e1.rs
    #[derive(ForyStruct, Debug, PartialEq)]
    struct Item1 {
        f1: i32,
        f2: i32,
        f3: Option<i32>,
        f4: Option<i32>,
        f5: Option<i32>,
        f6: Option<i32>,
    }

    #[derive(ForyStruct, Debug, PartialEq)]
    struct Item2 {
        f1: i32,
        f2: Option<i32>,
        f3: Option<i32>,
        f4: i32,
        f5: i32,
        f6: Option<i32>,
    }

    let mut fory1 = Fory::builder().compatible(true).xlang(true).build();
    fory1.register::<Item1>(101).unwrap();
    let mut fory2 = Fory::builder().compatible(true).xlang(true).build();
    fory2.register::<Item2>(101).unwrap();

    let f1 = 1;
    let f2 = 2;
    let f3 = Some(3);
    let f4 = Some(4);
    let f5: Option<i32> = None;
    let f6: Option<i32> = None;
    let item1 = Item1 {
        f1,
        f2,
        f3,
        f4,
        f5,
        f6,
    };
    let bytes = fory1.serialize(&item1).unwrap();
    let item2: Item2 = fory2.deserialize(&bytes).unwrap();
    assert_eq!(item2.f1, f1);
    assert_eq!(item2.f2.unwrap(), f2);
    assert_eq!(item2.f3, f3);
    assert_eq!(item2.f4, f4.unwrap());
    assert_eq!(item2.f5, i32::default());
    assert_eq!(item2.f6, f6);

    let bytes = fory1.serialize(&f1).unwrap();
    let item2_f1: i32 = fory2.deserialize(&bytes).unwrap();
    assert_eq!(item2.f1, item2_f1);

    let bytes = fory1.serialize(&f2).unwrap();
    let item2_f2: Option<i32> = fory2.deserialize(&bytes).unwrap();
    assert_eq!(item2.f2, item2_f2);

    let bytes = fory1.serialize(&f3).unwrap();
    let item2_f3: Option<i32> = fory2.deserialize(&bytes).unwrap();
    assert_eq!(item2.f3, item2_f3);

    let bytes = fory1.serialize(&f4).unwrap();
    let item2_f4: i32 = fory2.deserialize(&bytes).unwrap();
    assert_eq!(item2.f4, item2_f4);

    let bytes = fory1.serialize(&f5).unwrap();
    let item2_f5: i32 = fory2.deserialize(&bytes).unwrap();
    assert_eq!(item2.f5, item2_f5);

    let bytes = fory1.serialize(&f6).unwrap();
    let item2_f6: Option<i32> = fory2.deserialize(&bytes).unwrap();
    assert_eq!(item2.f6, item2_f6);
}

#[test]
fn test_struct_with_generic() {
    #[derive(Debug, PartialEq)]
    struct Wrapper<T> {
        value: String,
        _marker: PhantomData<T>,
        data: T,
    }

    #[derive(ForyStruct, Debug, PartialEq)]
    #[fory(debug)]
    struct MyStruct {
        my_vec: Vec<Wrapper<Another>>,
        my_vec1: Vec<Wrapper<i32>>,
    }

    #[derive(ForyStruct, Debug, PartialEq)]
    #[fory(debug)]
    struct Another {
        f1: i32,
    }

    impl<T: Serializer<Target = T>> Serializer for Wrapper<T> {
        type Target = Self;

        fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
            context.writer.write_var_u32(value.value.len() as u32);
            context.writer.write_utf8_string(&value.value);
            T::write_data(&value.data, context)?;
            Ok(())
        }

        fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
            let len = context.reader.read_var_u32()? as usize;
            let value = context.reader.read_utf8_string(len)?;
            let data = T::read_data(context)?;
            Ok(Self {
                value,
                _marker: PhantomData,
                data,
            })
        }

        fn default_value(context: &mut ReadContext) -> Result<Self, Error> {
            Ok(Self {
                value: String::new(),
                _marker: PhantomData,
                data: T::default_value(context)?,
            })
        }
    }

    let mut fory1 = Fory::builder().xlang(false).compatible(true).build();
    let mut fory2 = Fory::builder().xlang(false).compatible(false).build(); // Without compatible it works fine.
    let mut fory3 = Fory::builder().xlang(true).compatible(false).build();

    fn inner_test(fory: &mut Fory) -> Result<(), Error> {
        fory.register::<MyStruct>(1)?;
        fory.register::<Another>(2)?;
        fory.register_serializer::<Wrapper<Another>>(3)?;
        fory.register_serializer::<Wrapper<i32>>(4)?;

        let w1 = Wrapper::<Another> {
            value: "Value1".into(),
            _marker: PhantomData,
            data: Another { f1: 10 },
        };
        let w2 = Wrapper::<Another> {
            value: "Value2".into(),
            _marker: PhantomData,
            data: Another { f1: 11 },
        };

        let w3 = Wrapper::<i32> {
            value: "Value3".into(),
            _marker: PhantomData,
            data: 12,
        };
        let w4 = Wrapper::<i32> {
            value: "Value4".into(),
            _marker: PhantomData,
            data: 13,
        };

        let ms = MyStruct {
            my_vec: vec![w1, w2],
            my_vec1: vec![w3, w4],
        };

        let bytes = fory.serialize(&ms)?;
        let new_ms = fory.deserialize::<MyStruct>(&bytes)?;
        assert_eq!(ms, new_ms);
        Ok(())
    }

    for fory in [&mut fory1, &mut fory2] {
        assert!(inner_test(fory).is_ok());
    }
    assert!(inner_test(&mut fory3).is_ok());
}

#[test]
fn concrete_owner_rejects_meta_ref() {
    // Each Vec body writes typed-group metadata. Pairing two groups in one root makes the second
    // B a same-root TypeMeta reference that the malformed reader tries to consume as static A.
    #[derive(Debug, PartialEq)]
    struct Pair<T, U> {
        first: T,
        second: U,
    }

    struct PairSerializer<S1, S2>(PhantomData<(S1, S2)>);

    impl<S1: Serializer, S2: Serializer> Serializer for PairSerializer<S1, S2> {
        type Target = Pair<S1::Target, S2::Target>;

        fn write_data(value: &Self::Target, context: &mut WriteContext) -> Result<(), Error> {
            S1::write(&value.first, context, RefMode::None, true)?;
            S2::write(&value.second, context, RefMode::None, true)
        }

        fn read_data(context: &mut ReadContext) -> Result<Self::Target, Error> {
            Ok(Pair {
                first: S1::read(context, RefMode::None, true)?,
                second: S2::read(context, RefMode::None, true)?,
            })
        }
    }

    #[derive(ForyStruct, Debug, PartialEq)]
    struct StructA {
        value: i32,
    }

    #[derive(ForyStruct, Debug, PartialEq)]
    struct StructB {
        value: i32,
    }

    let mut direct_writer = Fory::builder().compatible(true).build();
    direct_writer
        .register_by_name::<StructB>("owner.CompatibleB")
        .unwrap();
    let foreign_root = direct_writer
        .serialize_with::<StructB>(&StructB { value: 1 })
        .unwrap();

    let mut direct_good_writer = Fory::builder().compatible(true).build();
    direct_good_writer
        .register_by_name::<StructA>("owner.CompatibleA")
        .unwrap();
    let valid_root = direct_good_writer
        .serialize_with::<StructA>(&StructA { value: 2 })
        .unwrap();

    let mut direct_reader = Fory::builder().compatible(true).build();
    direct_reader
        .register_by_name::<StructA>("owner.CompatibleA")
        .unwrap();
    direct_reader
        .register_by_name::<StructB>("owner.CompatibleB")
        .unwrap();
    let error = direct_reader
        .deserialize_with::<StructA>(&foreign_root)
        .unwrap_err();
    assert!(error.to_string().contains("does not match declared target"));
    assert_eq!(
        direct_reader
            .deserialize_with::<StructA>(&valid_root)
            .unwrap(),
        StructA { value: 2 }
    );

    type StructWireSerializer = PairSerializer<Vec<StructB>, Vec<StructB>>;
    type StructLocalSerializer = PairSerializer<Vec<StructB>, Vec<StructA>>;

    let mut struct_writer = Fory::builder().compatible(true).build();
    struct_writer
        .register_by_name::<StructB>("owner.StructB")
        .unwrap();
    struct_writer
        .register_serializer_by_name::<StructWireSerializer>("owner.StructRoot")
        .unwrap();
    let malformed_struct = struct_writer
        .serialize_with::<StructWireSerializer>(&Pair {
            first: vec![StructB { value: 1 }],
            second: vec![StructB { value: 2 }],
        })
        .unwrap();

    let mut struct_good_writer = Fory::builder().compatible(true).build();
    struct_good_writer
        .register_by_name::<StructA>("owner.StructA")
        .unwrap();
    struct_good_writer
        .register_by_name::<StructB>("owner.StructB")
        .unwrap();
    struct_good_writer
        .register_serializer_by_name::<StructLocalSerializer>("owner.StructRoot")
        .unwrap();
    let valid_struct = struct_good_writer
        .serialize_with::<StructLocalSerializer>(&Pair {
            first: vec![StructB { value: 3 }],
            second: vec![StructA { value: 4 }],
        })
        .unwrap();

    let mut struct_reader = Fory::builder().compatible(true).build();
    struct_reader
        .register_by_name::<StructA>("owner.StructA")
        .unwrap();
    struct_reader
        .register_by_name::<StructB>("owner.StructB")
        .unwrap();
    struct_reader
        .register_serializer_by_name::<StructLocalSerializer>("owner.StructRoot")
        .unwrap();
    let error = struct_reader
        .deserialize_with::<StructLocalSerializer>(&malformed_struct)
        .unwrap_err();
    assert!(error.to_string().contains("does not match declared target"));
    assert_eq!(
        struct_reader
            .deserialize_with::<StructLocalSerializer>(&valid_struct)
            .unwrap(),
        Pair {
            first: vec![StructB { value: 3 }],
            second: vec![StructA { value: 4 }],
        }
    );

    // Schema-consistent generated structs in xlang mode still carry a named wire owner. A second
    // StructB must not be consumed by declared StructA even when compatible mode is disabled.
    type DirectStructWire = PairSerializer<StructB, StructB>;
    type DirectStructLocal = PairSerializer<StructB, StructA>;

    let mut xlang_writer = Fory::builder().compatible(false).xlang(true).build();
    xlang_writer
        .register_by_name::<StructB>("owner.DirectB")
        .unwrap();
    xlang_writer
        .register_serializer_by_name::<DirectStructWire>("owner.DirectRoot")
        .unwrap();
    let named_foreign = xlang_writer
        .serialize_with::<DirectStructWire>(&Pair {
            first: StructB { value: 5 },
            second: StructB { value: 6 },
        })
        .unwrap();

    let mut xlang_reader = Fory::builder().compatible(false).xlang(true).build();
    xlang_reader
        .register_by_name::<StructA>("owner.DirectA")
        .unwrap();
    xlang_reader
        .register_by_name::<StructB>("owner.DirectB")
        .unwrap();
    xlang_reader
        .register_serializer_by_name::<DirectStructLocal>("owner.DirectRoot")
        .unwrap();
    let error = xlang_reader
        .deserialize_with::<DirectStructLocal>(&named_foreign)
        .unwrap_err();
    assert!(error.to_string().contains("does not match declared target"));

    let mut xlang_good_writer = Fory::builder().compatible(false).xlang(true).build();
    xlang_good_writer
        .register_by_name::<StructA>("owner.DirectA")
        .unwrap();
    xlang_good_writer
        .register_by_name::<StructB>("owner.DirectB")
        .unwrap();
    xlang_good_writer
        .register_serializer_by_name::<DirectStructLocal>("owner.DirectRoot")
        .unwrap();
    let valid_named = xlang_good_writer
        .serialize_with::<DirectStructLocal>(&Pair {
            first: StructB { value: 9 },
            second: StructA { value: 10 },
        })
        .unwrap();
    assert_eq!(
        xlang_reader
            .deserialize_with::<DirectStructLocal>(&valid_named)
            .unwrap(),
        Pair {
            first: StructB { value: 9 },
            second: StructA { value: 10 },
        }
    );

    // The native schema-consistent path retains its compact user-type-id owner check.
    let mut native_writer = Fory::builder().compatible(false).xlang(false).build();
    native_writer.register::<StructB>(201).unwrap();
    native_writer
        .register_serializer::<DirectStructWire>(200)
        .unwrap();
    let native_foreign = native_writer
        .serialize_with::<DirectStructWire>(&Pair {
            first: StructB { value: 7 },
            second: StructB { value: 8 },
        })
        .unwrap();

    let mut native_reader = Fory::builder().compatible(false).xlang(false).build();
    native_reader.register::<StructA>(202).unwrap();
    native_reader.register::<StructB>(201).unwrap();
    native_reader
        .register_serializer::<DirectStructLocal>(200)
        .unwrap();
    let error = native_reader
        .deserialize_with::<DirectStructLocal>(&native_foreign)
        .unwrap_err();
    assert!(error.to_string().contains("User type id mismatch"));

    let mut named_native_writer = Fory::builder().compatible(false).xlang(false).build();
    named_native_writer
        .register_by_name::<StructB>("owner.NativeB")
        .unwrap();
    named_native_writer
        .register_serializer_by_name::<DirectStructWire>("owner.NativeRoot")
        .unwrap();
    let named_native_foreign = named_native_writer
        .serialize_with::<DirectStructWire>(&Pair {
            first: StructB { value: 11 },
            second: StructB { value: 12 },
        })
        .unwrap();

    let mut named_native_reader = Fory::builder().compatible(false).xlang(false).build();
    named_native_reader
        .register_by_name::<StructA>("owner.NativeA")
        .unwrap();
    named_native_reader
        .register_by_name::<StructB>("owner.NativeB")
        .unwrap();
    named_native_reader
        .register_serializer_by_name::<DirectStructLocal>("owner.NativeRoot")
        .unwrap();
    let error = named_native_reader
        .deserialize_with::<DirectStructLocal>(&named_native_foreign)
        .unwrap_err();
    assert!(error.to_string().contains("does not match declared target"));

    #[derive(ForyEnum, Debug, Default, PartialEq)]
    enum EnumA {
        #[default]
        Zero,
        One,
    }

    #[derive(ForyEnum, Debug, Default, PartialEq)]
    enum EnumB {
        #[default]
        Zero,
        One,
    }

    type EnumWireSerializer = PairSerializer<EnumB, EnumB>;
    type EnumLocalSerializer = PairSerializer<EnumB, EnumA>;

    let mut enum_writer = Fory::builder().compatible(true).build();
    enum_writer
        .register_by_name::<EnumB>("owner.EnumB")
        .unwrap();
    enum_writer
        .register_serializer_by_name::<EnumWireSerializer>("owner.EnumRoot")
        .unwrap();
    let malformed_enum = enum_writer
        .serialize_with::<EnumWireSerializer>(&Pair {
            first: EnumB::One,
            second: EnumB::One,
        })
        .unwrap();

    let mut enum_reader = Fory::builder().compatible(true).build();
    enum_reader
        .register_by_name::<EnumA>("owner.EnumA")
        .unwrap();
    enum_reader
        .register_by_name::<EnumB>("owner.EnumB")
        .unwrap();
    enum_reader
        .register_serializer_by_name::<EnumLocalSerializer>("owner.EnumRoot")
        .unwrap();
    let error = enum_reader
        .deserialize_with::<EnumLocalSerializer>(&malformed_enum)
        .unwrap_err();
    assert!(error.to_string().contains("does not match declared target"));

    type WrappedEnumWire = PairSerializer<Vec<Box<EnumB>>, Vec<Box<EnumB>>>;
    type WrappedEnumLocal = PairSerializer<Vec<Box<EnumB>>, Vec<Box<EnumA>>>;

    let mut enum_writer = Fory::builder().compatible(true).build();
    enum_writer
        .register_by_name::<EnumB>("owner.EnumB")
        .unwrap();
    enum_writer
        .register_serializer_by_name::<WrappedEnumWire>("owner.WrappedEnumRoot")
        .unwrap();
    let malformed_enum = enum_writer
        .serialize_with::<WrappedEnumWire>(&Pair {
            first: vec![Box::new(EnumB::One)],
            second: vec![Box::new(EnumB::One)],
        })
        .unwrap();

    let mut enum_reader = Fory::builder().compatible(true).build();
    enum_reader
        .register_by_name::<EnumA>("owner.EnumA")
        .unwrap();
    enum_reader
        .register_by_name::<EnumB>("owner.EnumB")
        .unwrap();
    enum_reader
        .register_serializer_by_name::<WrappedEnumLocal>("owner.WrappedEnumRoot")
        .unwrap();
    let error = enum_reader
        .deserialize_with::<WrappedEnumLocal>(&malformed_enum)
        .unwrap_err();
    assert!(error.to_string().contains("does not match declared target"));

    #[derive(Debug)]
    struct ExtA(i32);

    #[derive(Debug)]
    struct ExtB(i32);

    impl Serializer for ExtA {
        type Target = Self;

        fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
            context.writer.write_i32(value.0);
            Ok(())
        }

        fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
            Ok(ExtA(context.reader.read_i32()?))
        }
    }

    impl Serializer for ExtB {
        type Target = Self;

        fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
            context.writer.write_i32(value.0);
            Ok(())
        }

        fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
            Ok(ExtB(context.reader.read_i32()?))
        }
    }

    type ExtWireSerializer = PairSerializer<ExtB, ExtB>;
    type ExtLocalSerializer = PairSerializer<ExtB, ExtA>;

    let mut ext_writer = Fory::builder().compatible(true).build();
    ext_writer
        .register_serializer_by_name::<ExtB>("owner.ExtB")
        .unwrap();
    ext_writer
        .register_serializer_by_name::<ExtWireSerializer>("owner.ExtRoot")
        .unwrap();
    let malformed_ext = ext_writer
        .serialize_with::<ExtWireSerializer>(&Pair {
            first: ExtB(1),
            second: ExtB(2),
        })
        .unwrap();

    let mut ext_reader = Fory::builder().compatible(true).build();
    ext_reader
        .register_serializer_by_name::<ExtA>("owner.ExtA")
        .unwrap();
    ext_reader
        .register_serializer_by_name::<ExtB>("owner.ExtB")
        .unwrap();
    ext_reader
        .register_serializer_by_name::<ExtLocalSerializer>("owner.ExtRoot")
        .unwrap();
    let error = ext_reader
        .deserialize_with::<ExtLocalSerializer>(&malformed_ext)
        .unwrap_err();
    assert!(error.to_string().contains("does not match declared target"));

    type WrappedExtWire = PairSerializer<Vec<Option<ExtB>>, Vec<Option<ExtB>>>;
    type WrappedExtLocal = PairSerializer<Vec<Option<ExtB>>, Vec<Option<ExtA>>>;

    let mut ext_writer = Fory::builder().compatible(true).build();
    ext_writer
        .register_serializer_by_name::<ExtB>("owner.ExtB")
        .unwrap();
    ext_writer
        .register_serializer_by_name::<WrappedExtWire>("owner.WrappedExtRoot")
        .unwrap();
    let malformed_ext = ext_writer
        .serialize_with::<WrappedExtWire>(&Pair {
            first: vec![Some(ExtB(1))],
            second: vec![Some(ExtB(2))],
        })
        .unwrap();

    let mut ext_reader = Fory::builder().compatible(true).build();
    ext_reader
        .register_serializer_by_name::<ExtA>("owner.ExtA")
        .unwrap();
    ext_reader
        .register_serializer_by_name::<ExtB>("owner.ExtB")
        .unwrap();
    ext_reader
        .register_serializer_by_name::<WrappedExtLocal>("owner.WrappedExtRoot")
        .unwrap();
    let error = ext_reader
        .deserialize_with::<WrappedExtLocal>(&malformed_ext)
        .unwrap_err();
    assert!(error.to_string().contains("does not match declared target"));
}

#[test]
fn structural_wire_kind_rejected() {
    #[derive(ForyStruct, Debug, PartialEq)]
    struct StructTarget {
        value: i32,
    }

    #[derive(Debug)]
    struct UnknownExt(i32);

    impl Serializer for UnknownExt {
        type Target = Self;

        fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
            context.writer.write_i32(value.0);
            Ok(())
        }

        fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
            Ok(Self(context.reader.read_i32()?))
        }
    }

    #[derive(ForyEnum, Debug, Default)]
    enum UnknownEnum {
        #[default]
        Value,
    }

    #[derive(ForyUnion, Debug)]
    enum UnknownUnion {
        #[fory(default)]
        Value(i32),
        #[fory(unknown)]
        Unknown(fory_core::UnknownCase),
    }

    let mut ext_writer = Fory::builder().compatible(true).build();
    ext_writer
        .register_serializer_by_name::<UnknownExt>("owner.StructTarget")
        .unwrap();
    let ext_bytes = ext_writer
        .serialize_with::<UnknownExt>(&UnknownExt(1))
        .unwrap();

    let mut enum_writer = Fory::builder().compatible(true).build();
    enum_writer
        .register_by_name::<UnknownEnum>("remote.UnknownEnum")
        .unwrap();
    let enum_bytes = enum_writer
        .serialize_with::<UnknownEnum>(&UnknownEnum::Value)
        .unwrap();

    let mut union_writer = Fory::builder().compatible(true).build();
    union_writer
        .register_union_by_name::<UnknownUnion>("remote.UnknownUnion")
        .unwrap();
    let union_bytes = union_writer
        .serialize_with::<UnknownUnion>(&UnknownUnion::Value(2))
        .unwrap();

    struct RefWire;
    #[derive(Debug)]
    struct RefLocal;
    struct RefWireSerializer;
    struct RefLocalSerializer;

    impl Serializer for RefWireSerializer {
        type Target = RefWire;

        fn write_data(_value: &RefWire, context: &mut WriteContext) -> Result<(), Error> {
            UnknownExt::write(&UnknownExt(3), context, RefMode::None, true)?;
            UnknownExt::write(&UnknownExt(4), context, RefMode::None, true)
        }

        fn read_data(_context: &mut ReadContext) -> Result<RefWire, Error> {
            Ok(RefWire)
        }
    }

    impl Serializer for RefLocalSerializer {
        type Target = RefLocal;

        fn write_data(_value: &RefLocal, _context: &mut WriteContext) -> Result<(), Error> {
            Ok(())
        }

        fn read_data(context: &mut ReadContext) -> Result<RefLocal, Error> {
            let type_info = context.read_any_type_info()?;
            assert_eq!(
                type_info.get_type_meta_ref().get_type_id(),
                fory_core::TypeId::NAMED_EXT as u32
            );
            let _ = UnknownExt::read_data(context)?;
            let _ = StructTarget::read(context, RefMode::None, true)?;
            Ok(RefLocal)
        }
    }

    let mut ref_writer = Fory::builder().compatible(true).build();
    ref_writer
        .register_serializer_by_name::<UnknownExt>("remote.CachedExt")
        .unwrap();
    ref_writer
        .register_serializer_by_name::<RefWireSerializer>("owner.RefRoot")
        .unwrap();
    let ref_bytes = ref_writer
        .serialize_with::<RefWireSerializer>(&RefWire)
        .unwrap();

    struct CacheWire;
    #[derive(Debug)]
    struct CacheLocal;
    struct CacheWireSerializer;
    struct CacheLocalSerializer;

    impl Serializer for CacheWireSerializer {
        type Target = CacheWire;

        fn write_data(_value: &CacheWire, context: &mut WriteContext) -> Result<(), Error> {
            UnknownExt::write(&UnknownExt(5), context, RefMode::None, true)
        }

        fn read_data(_context: &mut ReadContext) -> Result<CacheWire, Error> {
            Ok(CacheWire)
        }
    }

    impl Serializer for CacheLocalSerializer {
        type Target = CacheLocal;

        fn write_data(_value: &CacheLocal, _context: &mut WriteContext) -> Result<(), Error> {
            Ok(())
        }

        fn read_data(context: &mut ReadContext) -> Result<CacheLocal, Error> {
            let _ = StructTarget::read(context, RefMode::None, true)?;
            Ok(CacheLocal)
        }
    }

    let mut cache_writer = Fory::builder().compatible(true).build();
    cache_writer
        .register_serializer_by_name::<UnknownExt>("remote.CachedExt")
        .unwrap();
    cache_writer
        .register_serializer_by_name::<CacheWireSerializer>("owner.CacheRoot")
        .unwrap();
    let cache_bytes = cache_writer
        .serialize_with::<CacheWireSerializer>(&CacheWire)
        .unwrap();

    let mut valid_writer = Fory::builder().compatible(true).build();
    valid_writer
        .register_by_name::<StructTarget>("owner.StructTarget")
        .unwrap();
    let valid_bytes = valid_writer
        .serialize_with::<StructTarget>(&StructTarget { value: 6 })
        .unwrap();

    let mut reader = Fory::builder().compatible(true).build();
    reader
        .register_by_name::<StructTarget>("owner.StructTarget")
        .unwrap();
    reader
        .register_serializer_by_name::<RefLocalSerializer>("owner.RefRoot")
        .unwrap();
    reader
        .register_serializer_by_name::<CacheLocalSerializer>("owner.CacheRoot")
        .unwrap();

    let error = reader
        .deserialize_with::<StructTarget>(&ext_bytes)
        .unwrap_err();
    assert!(error
        .to_string()
        .contains("kind does not match registered type metadata"));
    for bytes in [&enum_bytes, &union_bytes] {
        let error = reader.deserialize_with::<StructTarget>(bytes).unwrap_err();
        assert!(error.to_string().contains("not structural metadata"));
    }

    let error = reader
        .deserialize_with::<RefLocalSerializer>(&ref_bytes)
        .unwrap_err();
    assert!(error.to_string().contains("not structural metadata"));
    let error = reader
        .deserialize_with::<CacheLocalSerializer>(&cache_bytes)
        .unwrap_err();
    assert!(error.to_string().contains("not structural metadata"));
    assert_eq!(
        reader
            .deserialize_with::<StructTarget>(&valid_bytes)
            .unwrap(),
        StructTarget { value: 6 }
    );
}

#[test]
fn static_owner_rejects_unknown_meta() {
    use fory_core::serializer::{
        ArcSerializer, ArraySerializer, RcSerializer, Tuple2Serializer, VecSerializer,
    };
    use std::rc::Rc;
    use std::sync::Arc;

    #[derive(ForyEnum, Debug, Default, PartialEq)]
    enum EnumA {
        #[default]
        Zero,
        One,
    }

    #[derive(ForyEnum, Debug, Default, PartialEq)]
    enum EnumB {
        #[default]
        Zero,
        One,
    }

    struct EnumASerializer;

    impl Serializer for EnumASerializer {
        type Target = EnumA;

        fn write_data(value: &EnumA, context: &mut WriteContext) -> Result<(), Error> {
            EnumA::write_data(value, context)
        }

        fn read_data(context: &mut ReadContext) -> Result<EnumA, Error> {
            EnumA::read_data(context)
        }

        fn read_with_type_info(
            context: &mut ReadContext,
            ref_mode: RefMode,
            type_info: &Rc<fory_core::TypeInfo>,
        ) -> Result<EnumA, Error> {
            EnumA::read_with_type_info(context, ref_mode, type_info)
        }
    }

    struct EnumBSerializer;

    impl Serializer for EnumBSerializer {
        type Target = EnumB;

        fn write_data(value: &EnumB, context: &mut WriteContext) -> Result<(), Error> {
            EnumB::write_data(value, context)
        }

        fn read_data(context: &mut ReadContext) -> Result<EnumB, Error> {
            EnumB::read_data(context)
        }

        fn read_with_type_info(
            context: &mut ReadContext,
            ref_mode: RefMode,
            type_info: &Rc<fory_core::TypeInfo>,
        ) -> Result<EnumB, Error> {
            EnumB::read_with_type_info(context, ref_mode, type_info)
        }
    }

    #[derive(ForyStruct, Debug)]
    struct EnumWire {
        #[fory(with = EnumBSerializer)]
        value: EnumB,
    }

    #[derive(ForyStruct, Debug, PartialEq)]
    struct EnumLocal {
        #[fory(with = EnumASerializer)]
        value: EnumA,
    }

    let mut enum_writer = Fory::builder().compatible(true).build();
    enum_writer
        .register_serializer_by_name::<EnumBSerializer>("owner.UnknownEnumB")
        .unwrap();
    enum_writer
        .register_by_name::<EnumWire>("owner.EnumFieldRoot")
        .unwrap();
    let unknown_enum = enum_writer
        .serialize(&EnumWire { value: EnumB::One })
        .unwrap();

    let mut enum_reader = Fory::builder().compatible(true).build();
    enum_reader
        .register_serializer_by_name::<EnumASerializer>("owner.EnumA")
        .unwrap();
    enum_reader
        .register_by_name::<EnumLocal>("owner.EnumFieldRoot")
        .unwrap();
    let error = enum_reader
        .deserialize::<EnumLocal>(&unknown_enum)
        .unwrap_err();
    assert!(error.to_string().contains("does not match declared target"));

    #[derive(Debug)]
    struct ExtA(i32);

    #[derive(Debug)]
    struct ExtB(i32);

    #[derive(ForyStruct, Debug)]
    struct GroupStructA {
        value: i32,
    }

    #[derive(ForyStruct, Debug)]
    struct GroupStructB {
        value: i32,
    }

    struct ExtASerializer;

    impl Serializer for ExtASerializer {
        type Target = ExtA;

        fn write_data(value: &ExtA, context: &mut WriteContext) -> Result<(), Error> {
            context.writer.write_i32(value.0);
            Ok(())
        }

        fn read_data(context: &mut ReadContext) -> Result<ExtA, Error> {
            Ok(ExtA(context.reader.read_i32()?))
        }
    }

    struct ExtBSerializer;

    impl Serializer for ExtBSerializer {
        type Target = ExtB;

        fn write_data(value: &ExtB, context: &mut WriteContext) -> Result<(), Error> {
            context.writer.write_i32(value.0);
            Ok(())
        }

        fn read_data(context: &mut ReadContext) -> Result<ExtB, Error> {
            Ok(ExtB(context.reader.read_i32()?))
        }
    }

    #[derive(ForyStruct, Debug)]
    struct ExtWire {
        #[fory(with = ExtBSerializer)]
        value: ExtB,
    }

    #[derive(ForyStruct, Debug)]
    struct ExtLocal {
        #[fory(with = ExtASerializer)]
        value: ExtA,
    }

    let mut ext_writer = Fory::builder().compatible(true).build();
    ext_writer
        .register_serializer_by_name::<ExtBSerializer>("owner.UnknownExtB")
        .unwrap();
    ext_writer
        .register_by_name::<ExtWire>("owner.ExtFieldRoot")
        .unwrap();
    let unknown_ext = ext_writer.serialize(&ExtWire { value: ExtB(7) }).unwrap();

    let mut ext_reader = Fory::builder().compatible(true).build();
    ext_reader
        .register_serializer_by_name::<ExtASerializer>("owner.ExtA")
        .unwrap();
    ext_reader
        .register_by_name::<ExtLocal>("owner.ExtFieldRoot")
        .unwrap();
    let error = ext_reader
        .deserialize::<ExtLocal>(&unknown_ext)
        .unwrap_err();
    assert!(error.to_string().contains("does not match declared target"));

    type RcWire = VecSerializer<RcSerializer<ExtBSerializer>>;
    type RcLocal = VecSerializer<RcSerializer<ExtASerializer>>;

    let mut rc_writer = Fory::builder().compatible(true).build();
    rc_writer
        .register_serializer_by_name::<ExtBSerializer>("owner.UnknownRcB")
        .unwrap();
    let unknown_rc = rc_writer
        .serialize_with::<RcWire>(&vec![Rc::new(ExtB(8))])
        .unwrap();

    let mut rc_reader = Fory::builder().compatible(true).build();
    rc_reader
        .register_serializer_by_name::<ExtASerializer>("owner.RcA")
        .unwrap();
    let error = rc_reader
        .deserialize_with::<RcLocal>(&unknown_rc)
        .unwrap_err();
    assert!(error.to_string().contains("does not match declared target"));

    type ArcWire = ArraySerializer<ArcSerializer<ExtBSerializer>, 1>;
    type ArcLocal = ArraySerializer<ArcSerializer<ExtASerializer>, 1>;

    let mut arc_writer = Fory::builder().compatible(true).build();
    arc_writer
        .register_serializer_by_name::<ExtBSerializer>("owner.UnknownArcB")
        .unwrap();
    let unknown_arc = arc_writer
        .serialize_with::<ArcWire>(&[Arc::new(ExtB(9))])
        .unwrap();

    let mut arc_reader = Fory::builder().compatible(true).build();
    arc_reader
        .register_serializer_by_name::<ExtASerializer>("owner.ArcA")
        .unwrap();
    let error = arc_reader
        .deserialize_with::<ArcLocal>(&unknown_arc)
        .unwrap_err();
    assert!(error.to_string().contains("does not match declared target"));

    struct SameTupleWriter;

    impl Serializer for SameTupleWriter {
        type Target = (ExtB, ExtB);

        fn write_data(value: &Self::Target, context: &mut WriteContext) -> Result<(), Error> {
            context.writer.write_var_u32(2);
            context.writer.write_u8(0b1000);
            ExtBSerializer::write_type_info(context)?;
            ExtBSerializer::write_data(&value.0, context)?;
            ExtBSerializer::write_data(&value.1, context)
        }

        fn read_data(_context: &mut ReadContext) -> Result<Self::Target, Error> {
            unreachable!()
        }

        fn static_type_id() -> fory_core::TypeId {
            fory_core::TypeId::LIST
        }

        fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
            context.writer.write_u8(fory_core::TypeId::LIST as u8);
            Ok(())
        }
    }

    type TupleLocal = Tuple2Serializer<ExtASerializer, ExtASerializer>;
    let mut tuple_writer = Fory::builder().compatible(true).build();
    tuple_writer
        .register_serializer_by_name::<ExtBSerializer>("owner.UnknownTupleB")
        .unwrap();
    let unknown_tuple = tuple_writer
        .serialize_with::<SameTupleWriter>(&(ExtB(10), ExtB(11)))
        .unwrap();

    let mut tuple_reader = Fory::builder().compatible(true).build();
    tuple_reader
        .register_serializer_by_name::<ExtASerializer>("owner.TupleA")
        .unwrap();
    let error = tuple_reader
        .deserialize_with::<TupleLocal>(&unknown_tuple)
        .unwrap_err();
    assert!(error.to_string().contains("does not match declared target"));

    type TupleSecondLocal = Tuple2Serializer<ExtBSerializer, ExtASerializer>;
    let mut tuple_reader = Fory::builder().compatible(true).build();
    tuple_reader
        .register_serializer_by_name::<ExtASerializer>("owner.TupleA")
        .unwrap();
    tuple_reader
        .register_serializer_by_name::<ExtBSerializer>("owner.UnknownTupleB")
        .unwrap();
    let error = tuple_reader
        .deserialize_with::<TupleSecondLocal>(&unknown_tuple)
        .unwrap_err();
    assert!(error.to_string().contains("does not match declared target"));

    type RcStructWire = VecSerializer<RcSerializer<GroupStructB>>;
    type RcStructLocal = VecSerializer<RcSerializer<GroupStructA>>;
    let mut struct_writer = Fory::builder().compatible(true).build();
    struct_writer
        .register_by_name::<GroupStructB>("owner.UnknownGroupB")
        .unwrap();
    let unknown_struct = struct_writer
        .serialize_with::<RcStructWire>(&vec![Rc::new(GroupStructB { value: 12 })])
        .unwrap();

    let mut struct_reader = Fory::builder().compatible(true).build();
    struct_reader
        .register_by_name::<GroupStructA>("owner.GroupA")
        .unwrap();
    let error = struct_reader
        .deserialize_with::<RcStructLocal>(&unknown_struct)
        .unwrap_err();
    assert!(error.to_string().contains("does not match declared target"));

    type ArcStructWire = ArraySerializer<ArcSerializer<GroupStructB>, 1>;
    type ArcStructLocal = ArraySerializer<ArcSerializer<GroupStructA>, 1>;
    let mut struct_writer = Fory::builder().compatible(true).build();
    struct_writer
        .register_by_name::<GroupStructB>("owner.UnknownArrayB")
        .unwrap();
    let unknown_struct = struct_writer
        .serialize_with::<ArcStructWire>(&[Arc::new(GroupStructB { value: 13 })])
        .unwrap();

    let mut struct_reader = Fory::builder().compatible(true).build();
    struct_reader
        .register_by_name::<GroupStructA>("owner.ArrayA")
        .unwrap();
    let error = struct_reader
        .deserialize_with::<ArcStructLocal>(&unknown_struct)
        .unwrap_err();
    assert!(error.to_string().contains("does not match declared target"));

    struct SameStructTupleWriter;

    impl Serializer for SameStructTupleWriter {
        type Target = (GroupStructB, GroupStructB);

        fn write_data(value: &Self::Target, context: &mut WriteContext) -> Result<(), Error> {
            context.writer.write_var_u32(2);
            context.writer.write_u8(0b1000);
            GroupStructB::write_type_info(context)?;
            GroupStructB::write_data(&value.0, context)?;
            GroupStructB::write_data(&value.1, context)
        }

        fn read_data(_context: &mut ReadContext) -> Result<Self::Target, Error> {
            unreachable!()
        }

        fn static_type_id() -> fory_core::TypeId {
            fory_core::TypeId::LIST
        }

        fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
            context.writer.write_u8(fory_core::TypeId::LIST as u8);
            Ok(())
        }
    }

    type StructTupleLocal = Tuple2Serializer<GroupStructA, GroupStructA>;
    let mut struct_writer = Fory::builder().compatible(true).build();
    struct_writer
        .register_by_name::<GroupStructB>("owner.UnknownTupleStructB")
        .unwrap();
    let unknown_struct = struct_writer
        .serialize_with::<SameStructTupleWriter>(&(
            GroupStructB { value: 14 },
            GroupStructB { value: 15 },
        ))
        .unwrap();

    let mut struct_reader = Fory::builder().compatible(true).build();
    struct_reader
        .register_by_name::<GroupStructA>("owner.TupleStructA")
        .unwrap();
    let error = struct_reader
        .deserialize_with::<StructTupleLocal>(&unknown_struct)
        .unwrap_err();
    assert!(error.to_string().contains("does not match declared target"));
}
