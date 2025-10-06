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

use fory_core::buffer::{Reader, Writer};
use fory_core::fory::Fory;
use fory_core::resolver::context::{ReadContext, WriteContext};
use fory_core::types::Mode::Compatible;
use fory_derive::{ForyObject};

#[derive(ForyObject, PartialEq, Eq, Hash, Debug)]
struct Item {
    id: i32,
}

fn basic_list() -> Vec<String> {
    vec![
        "42".to_string(),
        "".to_string(),
        "43".to_string(),
        "".to_string(),
        "44".to_string(),
    ]
}

fn basic_set() -> HashSet<String> {
    HashSet::from([
        "45".to_string(),
        "46".to_string(),
        "".to_string(),
        "47".to_string(),
    ])
}

fn basic_map() -> HashMap<String, String> {
    HashMap::from([
        ("48".to_string(), "49".to_string()),
        ("".to_string(), "50".to_string()),
        ("51".to_string(), "".to_string()),
    ])
}

fn nullable_basic_list(deserialize_auto_conv: bool) -> Vec<Option<String>> {
    if deserialize_auto_conv {
        vec![
            Some("42".to_string()),
            Some("".to_string()),
            Some("43".to_string()),
            Some("".to_string()),
            Some("44".to_string()),
        ]
    } else {
        vec![
            Some("42".to_string()),
            Some("".to_string()),
            Some("43".to_string()),
            None,
            Some("44".to_string()),
        ]
    }
}

fn nullable_basic_set(deserialize_auto_conv: bool) -> HashSet<Option<String>> {
    if deserialize_auto_conv {
        HashSet::from([
            Some("45".to_string()),
            Some("46".to_string()),
            Some("47".to_string()),
            Some("".to_string()),
        ])
    } else {
        HashSet::from([
            Some("45".to_string()),
            Some("46".to_string()),
            None,
            Some("47".to_string()),
            Some("".to_string()),
        ])
    }
}

fn nullable_basic_map(deserialize_auto_conv: bool) -> HashMap<Option<String>, Option<String>> {
    if deserialize_auto_conv {
        HashMap::from([
            (Some("48".to_string()), Some("49".to_string())),
            (Some("".to_string()), Some("50".to_string())),
            (Some("51".to_string()), Some("".to_string())),
        ])
    } else {
        HashMap::from([
            (Some("48".to_string()), Some("49".to_string())),
            (None, Some("50".to_string())),
            (Some("51".to_string()), None),
        ])
    }
}

fn item_list() -> Vec<Item> {
    vec![
        Item { id: 42 },
        Item { id: 0 },
        Item { id: 43 },
        Item { id: 0 },
        Item { id: 44 },
    ]
}

fn item_set() -> HashSet<Item> {
    HashSet::from([
        Item { id: 45 },
        Item { id: 0 },
        Item { id: 46 },
        Item { id: 47 },
    ])
}

fn item_map() -> HashMap<Item, Item> {
    HashMap::from([
        (Item { id: 48 }, Item { id: 49 }),
        (Item { id: 0 }, Item { id: 50 }),
        (Item { id: 51 }, Item { id: 0 }),
    ])
}

fn nullable_item_list(deserialize_auto_conv: bool) -> Vec<Option<Item>> {
    if deserialize_auto_conv {
        vec![
            Some(Item { id: 42 }),
            Some(Item { id: 0 }),
            Some(Item { id: 43 }),
            Some(Item { id: 0 }),
            Some(Item { id: 44 }),
        ]
    } else {
        vec![
            Some(Item { id: 42 }),
            Some(Item { id: 0 }),
            Some(Item { id: 43 }),
            None,
            Some(Item { id: 44 }),
        ]
    }
}

fn nullable_item_set(deserialize_auto_conv: bool) -> HashSet<Option<Item>> {
    if deserialize_auto_conv {
        HashSet::from([
            Some(Item { id: 45 }),
            Some(Item { id: 46 }),
            Some(Item { id: 47 }),
            Some(Item { id: 0 }),
        ])
    } else {
        HashSet::from([
            Some(Item { id: 45 }),
            Some(Item { id: 46 }),
            None,
            Some(Item { id: 47 }),
            Some(Item { id: 0 }),
        ])
    }
}

fn nullable_item_map(deserialize_auto_conv: bool) -> HashMap<Option<Item>, Option<Item>> {
    if deserialize_auto_conv {
        HashMap::from([
            (Some(Item { id: 48 }), Some(Item { id: 49 })),
            (Some(Item { id: 0 }), Some(Item { id: 50 })),
            (Some(Item { id: 51 }), Some(Item { id: 0 })),
        ])
    } else {
        HashMap::from([
            (Some(Item { id: 48 }), Some(Item { id: 49 })),
            (None, Some(Item { id: 50 })),
            (Some(Item { id: 51 }), None),
        ])
    }
}

fn nested_collection() -> Vec<HashSet<Item>> {
    vec![
        HashSet::from([Item { id: 42 }, Item { id: 0 }, Item { id: 43 }]),
        HashSet::from([Item { id: 44 }]),
    ]
}

fn complex_container1() -> Vec<HashMap<Item, Item>> {
    vec![
        HashMap::from([(Item { id: 42 }, Item { id: 43 })]),
        HashMap::from([(Item { id: 44 }, Item { id: 45 })]),
    ]
}

fn complex_container2() -> Vec<HashMap<Vec<Item>, Vec<Item>>> {
    vec![
        HashMap::from([(
            vec![Item { id: 42 }, Item { id: 43 }],
            vec![Item { id: 44 }, Item { id: 45 }],
        )]),
        HashMap::from([(
            vec![Item { id: 46 }, Item { id: 47 }],
            vec![Item { id: 48 }, Item { id: 49 }],
        )]),
    ]
}

#[test]
fn container_outer_auto_conv() {
    let fory = Fory::default().mode(Compatible);
    // serialize_outer_non-null
    let mut writer = Writer::default();
    let mut write_context = WriteContext::new(&fory, &mut writer);
    fory.serialize_with_context(&basic_list(), &mut write_context);
    fory.serialize_with_context(&basic_set(), &mut write_context);
    fory.serialize_with_context(&basic_map(), &mut write_context);
    // deserialize_outer_nullable
    let bytes = write_context.writer.dump();
    let reader = Reader::new(bytes.as_slice());
    let mut read_context = ReadContext::new(&fory, reader);
    assert_eq!(
        Some(basic_list()),
        fory.deserialize_with_context::<Option<Vec<String>>>(&mut read_context)
            .unwrap()
    );
    assert_eq!(
        Some(basic_set()),
        fory.deserialize_with_context::<Option<HashSet<String>>>(&mut read_context)
            .unwrap()
    );
    assert_eq!(
        Some(basic_map()),
        fory.deserialize_with_context::<Option<HashMap<String, String>>>(&mut read_context)
            .unwrap()
    );
    assert_eq!(read_context.reader.slice_after_cursor().len(), 0);
    // serialize_outer_nullable
    let mut writer = Writer::default();
    let mut write_context = WriteContext::new(&fory, &mut writer);
    fory.serialize_with_context(&Some(basic_list()), &mut write_context);
    fory.serialize_with_context(&Some(basic_set()), &mut write_context);
    fory.serialize_with_context(&Some(basic_map()), &mut write_context);
    fory.serialize_with_context(&Option::<Vec<String>>::None, &mut write_context);
    fory.serialize_with_context(&Option::<HashSet<String>>::None, &mut write_context);
    fory.serialize_with_context(&Option::<HashMap<String, String>>::None, &mut write_context);
    // deserialize_outer_non-null
    let bytes = write_context.writer.dump();
    let reader = Reader::new(bytes.as_slice());
    let mut read_context = ReadContext::new(&fory, reader);
    assert_eq!(
        basic_list(),
        fory.deserialize_with_context::<Vec<String>>(&mut read_context)
            .unwrap()
    );
    assert_eq!(
        basic_set(),
        fory.deserialize_with_context::<HashSet<String>>(&mut read_context)
            .unwrap()
    );
    assert_eq!(
        basic_map(),
        fory.deserialize_with_context::<HashMap<String, String>>(&mut read_context)
            .unwrap()
    );
    assert_eq!(
        Vec::<String>::default(),
        fory.deserialize_with_context::<Vec<String>>(&mut read_context)
            .unwrap()
    );
    assert_eq!(
        HashSet::default(),
        fory.deserialize_with_context::<HashSet<String>>(&mut read_context)
            .unwrap()
    );
    assert_eq!(
        HashMap::default(),
        fory.deserialize_with_context::<HashMap<String, String>>(&mut read_context)
            .unwrap()
    );
    assert_eq!(read_context.reader.slice_after_cursor().len(), 0);
}

#[test]
fn collection_inner() {
    let mut fory1 = Fory::default().mode(Compatible);
    fory1.register::<Item>(101);
    let mut fory2 = Fory::default().mode(Compatible);
    fory2.register_by_name::<Item>("item");
    for fory in [fory1, fory2] {
        // serialize
        let mut writer = Writer::default();
        let mut write_context = WriteContext::new(&fory, &mut writer);
        fory.serialize_with_context(&basic_list(), &mut write_context);
        fory.serialize_with_context(&item_list(), &mut write_context);
        fory.serialize_with_context(&basic_set(), &mut write_context);
        fory.serialize_with_context(&item_set(), &mut write_context);
        fory.serialize_with_context(&nullable_basic_list(false), &mut write_context);
        fory.serialize_with_context(&nullable_item_list(false), &mut write_context);
        fory.serialize_with_context(&nullable_basic_set(false), &mut write_context);
        fory.serialize_with_context(&nullable_item_set(false), &mut write_context);
        // deserialize
        let bytes = write_context.writer.dump();
        let reader = Reader::new(bytes.as_slice());
        let mut read_context = ReadContext::new(&fory, reader);
        assert_eq!(
            basic_list(),
            fory.deserialize_with_context::<Vec<String>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(
            item_list(),
            fory.deserialize_with_context::<Vec<Item>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(
            basic_set(),
            fory.deserialize_with_context::<HashSet<String>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(
            item_set(),
            fory.deserialize_with_context::<HashSet<Item>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(
            nullable_basic_list(false),
            fory.deserialize_with_context::<Vec<Option<String>>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(
            nullable_item_list(false),
            fory.deserialize_with_context::<Vec<Option<Item>>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(
            nullable_basic_set(false),
            fory.deserialize_with_context::<HashSet<Option<String>>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(
            nullable_item_set(false),
            fory.deserialize_with_context::<HashSet<Option<Item>>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(read_context.reader.slice_after_cursor().len(), 0);
    }
}

#[test]
fn collection_inner_auto_conv() {
    let mut fory1 = Fory::default().mode(Compatible);
    fory1.register::<Item>(101);
    let mut fory2 = Fory::default().mode(Compatible);
    fory2.register_by_name::<Item>("item");
    for fory in [fory1, fory2] {
        // serialize_non-null
        let mut writer = Writer::default();
        let mut write_context = WriteContext::new(&fory, &mut writer);
        fory.serialize_with_context(&basic_list(), &mut write_context);
        fory.serialize_with_context(&item_list(), &mut write_context);
        fory.serialize_with_context(&basic_set(), &mut write_context);
        fory.serialize_with_context(&item_set(), &mut write_context);
        // deserialize_nullable
        let bytes = write_context.writer.dump();
        let reader = Reader::new(bytes.as_slice());
        let mut read_context = ReadContext::new(&fory, reader);
        assert_eq!(
            nullable_basic_list(true),
            fory.deserialize_with_context::<Vec<Option<String>>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(
            nullable_item_list(true),
            fory.deserialize_with_context::<Vec<Option<Item>>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(
            nullable_basic_set(true),
            fory.deserialize_with_context::<HashSet<Option<String>>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(
            nullable_item_set(true),
            fory.deserialize_with_context::<HashSet<Option<Item>>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(read_context.reader.slice_after_cursor().len(), 0);
        // serialize_nullable
        let mut writer = Writer::default();
        let mut write_context = WriteContext::new(&fory, &mut writer);
        fory.serialize_with_context(&nullable_basic_list(false), &mut write_context);
        fory.serialize_with_context(&nullable_item_list(false), &mut write_context);
        fory.serialize_with_context(&nullable_basic_set(false), &mut write_context);
        fory.serialize_with_context(&nullable_item_set(false), &mut write_context);
        // deserialize_non-null
        let bytes = write_context.writer.dump();
        let reader = Reader::new(bytes.as_slice());
        let mut read_context = ReadContext::new(&fory, reader);
        assert_eq!(
            basic_list(),
            fory.deserialize_with_context::<Vec<String>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(
            item_list(),
            fory.deserialize_with_context::<Vec<Item>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(
            basic_set(),
            fory.deserialize_with_context::<HashSet<String>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(
            item_set(),
            fory.deserialize_with_context::<HashSet<Item>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(read_context.reader.slice_after_cursor().len(), 0);
    }
}

#[test]
fn map_inner() {
    let mut fory1 = Fory::default().mode(Compatible);
    fory1.register::<Item>(101);
    let mut fory2 = Fory::default().mode(Compatible);
    fory2.register_by_name::<Item>("item");
    for fory in [fory1, fory2] {
        // serialize
        let mut writer = Writer::default();
        let mut write_context = WriteContext::new(&fory, &mut writer);
        fory.serialize_with_context(&basic_map(), &mut write_context);
        fory.serialize_with_context(&item_map(), &mut write_context);
        fory.serialize_with_context(&nullable_basic_map(false), &mut write_context);
        fory.serialize_with_context(&nullable_item_map(false), &mut write_context);
        // deserialize
        let bytes = write_context.writer.dump();
        let reader = Reader::new(bytes.as_slice());
        let mut read_context = ReadContext::new(&fory, reader);
        assert_eq!(
            basic_map(),
            fory.deserialize_with_context::<HashMap<String, String>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(
            item_map(),
            fory.deserialize_with_context::<HashMap<Item, Item>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(
            nullable_basic_map(false),
            fory.deserialize_with_context::<HashMap<Option<String>, Option<String>>>(
                &mut read_context
            )
                .unwrap()
        );
        assert_eq!(
            nullable_item_map(false),
            fory.deserialize_with_context::<HashMap<Option<Item>, Option<Item>>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(read_context.reader.slice_after_cursor().len(), 0);
    }
}

#[test]
fn map_inner_auto_conv() {
    let mut fory1 = Fory::default().mode(Compatible);
    fory1.register::<Item>(101);
    let mut fory2 = Fory::default().mode(Compatible);
    fory2.register_by_name::<Item>("item");
    for fory in [fory1, fory2] {
        // serialize_non-null
        let mut writer = Writer::default();
        let mut write_context = WriteContext::new(&fory, &mut writer);
        fory.serialize_with_context(&basic_map(), &mut write_context);
        fory.serialize_with_context(&item_map(), &mut write_context);
        // deserialize_nullable
        let bytes = write_context.writer.dump();
        let reader = Reader::new(bytes.as_slice());
        let mut read_context = ReadContext::new(&fory, reader);
        assert_eq!(
            nullable_basic_map(true),
            fory.deserialize_with_context::<HashMap<Option<String>, Option<String>>>(
                &mut read_context
            )
                .unwrap()
        );
        assert_eq!(
            nullable_item_map(true),
            fory.deserialize_with_context::<HashMap<Option<Item>, Option<Item>>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(read_context.reader.slice_after_cursor().len(), 0);
        // serialize_nullable
        let mut writer = Writer::default();
        let mut write_context = WriteContext::new(&fory, &mut writer);
        fory.serialize_with_context(&nullable_basic_map(false), &mut write_context);
        fory.serialize_with_context(&nullable_item_map(false), &mut write_context);
        // deserialize_non-null
        let bytes = write_context.writer.dump();
        let reader = Reader::new(bytes.as_slice());
        let mut read_context = ReadContext::new(&fory, reader);
        assert_eq!(
            basic_map(),
            fory.deserialize_with_context::<HashMap<String, String>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(
            item_map(),
            fory.deserialize_with_context::<HashMap<Item, Item>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(read_context.reader.slice_after_cursor().len(), 0);
    }
}

#[test]
fn complex() {
    let mut fory1 = Fory::default().mode(Compatible);
    fory1.register::<Item>(101);
    let mut fory2 = Fory::default().mode(Compatible);
    fory2.register_by_name::<Item>("item");
    for fory in [fory1, fory2] {
        let mut writer = Writer::default();
        let mut write_context = WriteContext::new(&fory, &mut writer);
        fory.serialize_with_context(&nested_collection(), &mut write_context);
        fory.serialize_with_context(&complex_container1(), &mut write_context);
        fory.serialize_with_context(&complex_container2(), &mut write_context);
        let bytes = write_context.writer.dump();
        let reader = Reader::new(bytes.as_slice());
        let mut read_context = ReadContext::new(&fory, reader);
        assert_eq!(
            nested_collection(),
            fory.deserialize_with_context::<Vec<HashSet<Item>>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(
            complex_container1(),
            fory.deserialize_with_context::<Vec<HashMap<Item, Item>>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(
            complex_container2(),
            fory.deserialize_with_context::<Vec<HashMap<Vec<Item>, Vec<Item>>>>(&mut read_context)
                .unwrap()
        );
        assert_eq!(read_context.reader.slice_after_cursor().len(), 0);
    }
}