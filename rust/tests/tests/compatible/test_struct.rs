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
use fory_core::types::Mode::Compatible;
use fory_derive::ForyObject;
use std::collections::{HashMap, HashSet};

// RUSTFLAGS="-Awarnings" cargo expand -p fory-tests --test test_struct
#[test]
fn simple() {
    #[derive(ForyObject, Debug)]
    struct Animal1 {
        f1: HashMap<i8, Vec<i8>>,
        f2: String,
        f3: Vec<i8>,
        f5: String,
        f6: Vec<i8>,
        f7: i8,
        last: i8,
    }

    #[derive(ForyObject, Debug)]
    struct Animal2 {
        f1: HashMap<i8, Vec<i8>>,
        f3: Vec<i8>,
        f4: String,
        f5: i8,
        f6: Vec<i16>,
        f7: i16,
        last: i8,
    }
    let mut fory1 = Fory::default().mode(Compatible);
    let mut fory2 = Fory::default().mode(Compatible);
    fory1.register::<Animal1>(999);
    fory2.register::<Animal2>(999);
    let animal: Animal1 = Animal1 {
        f1: HashMap::from([(1, vec![2])]),
        f2: String::from("hello"),
        f3: vec![1, 2, 3],
        f5: String::from("f5"),
        f6: vec![42],
        f7: 43,
        last: 44,
    };
    let bin = fory1.serialize(&animal);
    let obj: Animal2 = fory2.deserialize(&bin).unwrap();
    assert_eq!(animal.f1, obj.f1);
    assert_eq!(animal.f3, obj.f3);
    assert_eq!(obj.f4, String::default());
    assert_eq!(obj.f5, i8::default());
    assert_eq!(obj.f6, Vec::<i16>::default());
    assert_eq!(obj.f7, i16::default());
    assert_eq!(animal.last, obj.last);
}

#[test]
fn skip_option() {
    #[derive(ForyObject, Debug)]
    struct Item1 {
        f1: Option<i32>,
        f2: Option<String>,
        last: i64,
    }

    #[derive(ForyObject, Debug)]
    struct Item2 {
        f1: i8,
        f2: i8,
        last: i64,
    }
    let mut fory1 = Fory::default().mode(Compatible);
    let mut fory2 = Fory::default().mode(Compatible);
    fory1.register::<Item1>(999);
    fory2.register::<Item2>(999);
    let item1 = Item1 {
        f1: None,
        f2: Some(String::from("f2")),
        last: 42,
    };
    let bin = fory1.serialize(&item1);
    let item2: Item2 = fory2.deserialize(&bin).unwrap();

    assert_eq!(item2.f1, i8::default());
    assert_eq!(item2.f2, i8::default());
    assert_eq!(item2.last, item1.last)
}

#[test]
fn nonexistent_struct() {
    #[derive(ForyObject, Debug)]
    pub struct Item1 {
        f1: i8,
    }
    #[derive(ForyObject, Debug, PartialEq)]
    pub struct Item2 {
        f1: i64,
    }
    #[derive(ForyObject, Debug)]
    struct Person1 {
        f2: Item1,
        f3: i8,
        last: String,
    }
    #[derive(ForyObject, Debug)]
    struct Person2 {
        f2: Item2,
        f3: i64,
        last: String,
    }
    let mut fory1 = Fory::default().mode(Compatible);
    let mut fory2 = Fory::default().mode(Compatible);
    fory1.register::<Item1>(899);
    fory1.register::<Person1>(999);
    fory2.register::<Item2>(799);
    fory2.register::<Person2>(999);
    let person = Person1 {
        f2: Item1 { f1: 42 },
        f3: 24,
        last: String::from("foo"),
    };
    let bin = fory1.serialize(&person);
    let obj: Person2 = fory2.deserialize(&bin).unwrap();
    assert_eq!(obj.f2, Item2::default());
    assert_eq!(obj.f3, i64::default());
    assert_eq!(obj.last, person.last);
}

#[test]
fn option() {
    #[derive(ForyObject, Debug, PartialEq)]
    struct Animal {
        f1: Option<String>,
        f2: Option<String>,
        f3: Vec<Option<String>>,
        // adjacent Options are not supported
        // f4: Option<Option<String>>,
        f5: Vec<Option<Vec<Option<String>>>>,
        last: i64,
    }
    let mut fory = Fory::default().mode(Compatible);
    fory.register::<Animal>(999);
    let animal: Animal = Animal {
        f1: Some(String::from("f1")),
        f2: None,
        f3: vec![Option::<String>::None, Some(String::from("f3"))],
        f5: vec![Some(vec![Some(String::from("f1"))])],
        last: 666,
    };
    let bin = fory.serialize(&animal);
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
    #[derive(ForyObject, Debug)]
    pub struct Item1 {
        f2: i8,
        f3: Option<i8>,
        f4: Option<i8>,
        f5: Option<i8>,
        f6: Option<i8>,
        last: i64,
    }

    #[derive(ForyObject, Debug)]
    pub struct Item2 {
        f2: Option<i8>,
        f3: i8,
        f4: Option<i8>,
        f5: Option<i8>,
        f6: i8,
        last: i64,
    }

    let mut fory1 = Fory::default().mode(Compatible);
    let mut fory2 = Fory::default().mode(Compatible);
    fory1.register::<Item1>(999);
    fory2.register::<Item2>(999);

    let item1 = Item1 {
        f2: 43,
        f3: Some(44),
        f4: Some(45),
        f5: None,
        f6: None,
        last: 666,
    };

    let bin = fory1.serialize(&item1);
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
    #[derive(ForyObject, Debug)]
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

    #[derive(ForyObject, Debug)]
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

    let mut fory1 = Fory::default().mode(Compatible);
    let mut fory2 = Fory::default().mode(Compatible);
    fory1.register::<Item1>(999);
    fory2.register::<Item2>(999);

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

    let bin = fory1.serialize(&item1);
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
    #[derive(ForyObject, Debug)]
    pub struct Item1 {
        f1: Vec<Option<String>>,
        f2: HashSet<Option<i8>>,
        f3: HashMap<i8, Option<i8>>,
        last: i64,
    }

    #[derive(ForyObject, Debug)]
    pub struct Item2 {
        f1: Vec<String>,
        f2: HashSet<i8>,
        f3: HashMap<i8, i8>,
        last: i64,
    }
    let mut fory1 = Fory::default().mode(Compatible);
    let mut fory2 = Fory::default().mode(Compatible);
    fory1.register::<Item1>(999);
    fory2.register::<Item2>(999);

    let item1 = Item1 {
        f1: vec![None, Some("hello".to_string())],
        f2: HashSet::from([None, Some(43)]),
        f3: HashMap::from([(44, None), (45, Some(46))]),
        last: 666,
    };
    let bin = fory1.serialize(&item1);
    let item2: Item2 = fory2.deserialize(&bin).unwrap();

    assert_eq!(item2.f1, vec![String::default(), "hello".to_string()]);
    assert_eq!(item2.f2, HashSet::from([0, 43]));
    assert_eq!(item2.f3, HashMap::from([(44, 0), (45, 46)]));
    assert_eq!(item2.last, item1.last);
}

#[test]
fn nullable_struct() {
    #[derive(ForyObject, Debug, PartialEq)]
    pub struct Item {
        name: String,
        data: Vec<Option<String>>,
        last: i64,
    }

    #[derive(ForyObject, Debug)]
    pub struct Person1 {
        f1: Item,
        f2: Option<Item>,
        f3: Option<Item>,
        last: i64,
    }

    #[derive(ForyObject, Debug)]
    pub struct Person2 {
        f1: Option<Item>,
        f2: Item,
        f3: Item,
        last: i64,
    }
    let mut fory1 = Fory::default().mode(Compatible);
    let mut fory2 = Fory::default().mode(Compatible);
    fory1.register::<Item>(199);
    fory1.register::<Person1>(200);
    fory2.register::<Item>(199);
    fory2.register::<Person2>(200);

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
    let bin = fory1.serialize(&person1);
    let person2: Person2 = fory2.deserialize(&bin).unwrap();

    assert_eq!(person2.f1.unwrap(), person1.f1);
    assert_eq!(person2.f2, Item::default());
    assert_eq!(person2.f3, person1.f3.unwrap());
    assert_eq!(person2.last, person1.last);
}

#[test]
fn enum_without_payload() {
    #[derive(ForyObject, Debug, PartialEq, Default)]
    enum Color1 {
        #[default]
        Green,
        Red,
        Blue,
        White,
    }
    #[derive(ForyObject, Debug, PartialEq, Default)]
    enum Color2 {
        #[default]
        Green,
        Red,
        Blue,
    }
    #[derive(ForyObject, Debug, PartialEq)]
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
    #[derive(ForyObject, Debug, PartialEq)]
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

    let mut fory1 = Fory::default().mode(Compatible).xlang(true);
    fory1.register::<Color1>(101);
    fory1.register::<Color2>(102);
    fory1.register::<Person1>(103);
    let mut fory2 = Fory::default().mode(Compatible).xlang(true);
    fory2.register::<Color1>(101);
    fory2.register::<Color2>(102);
    fory2.register::<Person2>(103);

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
    let bin = fory1.serialize(&person1);
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
    #[derive(ForyObject, Debug, PartialEq, Default)]
    enum Color {
        #[default]
        Green,
        Red,
        Blue,
        White,
    }
    #[derive(ForyObject, Debug, PartialEq)]
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
    #[derive(ForyObject, Debug, PartialEq)]
    struct Item2 {
        f1: Color,
        f2: Option<Color>,
        f3: Color,
        f4: Option<Color>,
        f5: Color,
        f6: Option<Color>,
        last: i8,
    }
    let mut fory1 = Fory::default().mode(Compatible).xlang(true);
    fory1.register_by_name::<Color>("a");
    fory1.register::<Item1>(101);
    let mut fory2 = Fory::default().mode(Compatible).xlang(true);
    fory2.register_by_name::<Color>("a");
    fory2.register::<Item2>(101);
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
    let bin = fory1.serialize(&item1);
    let actual_item2: Item2 = fory2.deserialize(&bin).unwrap();
    assert_eq!(expected_item2, actual_item2);
}

#[test]
#[allow(clippy::unnecessary_literal_unwrap)]
fn boxed() {
    #[derive(ForyObject, Debug, PartialEq)]
    struct Item1 {
        f1: i32,
        f2: i32,
        f3: Option<i32>,
        f4: Option<i32>,
        f5: Option<i32>,
        f6: Option<i32>,
    }

    #[derive(ForyObject, Debug, PartialEq)]
    struct Item2 {
        f1: i32,
        f2: Option<i32>,
        f3: Option<i32>,
        f4: i32,
        f5: i32,
        f6: Option<i32>,
    }

    let mut fory1 = Fory::default().mode(Compatible).xlang(true);
    fory1.register::<Item1>(101);
    let mut fory2 = Fory::default().mode(Compatible).xlang(true);
    fory2.register::<Item2>(101);

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
    let bytes = fory1.serialize(&item1);
    let item2: Item2 = fory2.deserialize(&bytes).unwrap();
    assert_eq!(item2.f1, f1);
    assert_eq!(item2.f2.unwrap(), f2);
    assert_eq!(item2.f3, f3);
    assert_eq!(item2.f4, f4.unwrap());
    assert_eq!(item2.f5, i32::default());
    assert_eq!(item2.f6, f6);

    let bytes = fory1.serialize(&f1);
    let item2_f1: i32 = fory2.deserialize(&bytes).unwrap();
    assert_eq!(item2.f1, item2_f1);

    let bytes = fory1.serialize(&f2);
    let item2_f2: Option<i32> = fory2.deserialize(&bytes).unwrap();
    assert_eq!(item2.f2, item2_f2);

    let bytes = fory1.serialize(&f3);
    let item2_f3: Option<i32> = fory2.deserialize(&bytes).unwrap();
    assert_eq!(item2.f3, item2_f3);

    let bytes = fory1.serialize(&f4);
    let item2_f4: i32 = fory2.deserialize(&bytes).unwrap();
    assert_eq!(item2.f4, item2_f4);

    let bytes = fory1.serialize(&f5);
    let item2_f5: i32 = fory2.deserialize(&bytes).unwrap();
    assert_eq!(item2.f5, item2_f5);

    let bytes = fory1.serialize(&f6);
    let item2_f6: Option<i32> = fory2.deserialize(&bytes).unwrap();
    assert_eq!(item2.f6, item2_f6);
}
