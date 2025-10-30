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

use fory_core::fory::Fory;
use fory_derive::ForyObject;

#[derive(ForyObject, PartialEq, Eq, Hash, Debug)]
#[fory_debug]
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
    let fory = Fory::default().compatible(true);
    // serialize_outer_non-null
    let bytes = fory.serialize(&basic_list()).unwrap();
    assert_eq!(
        Some(basic_list()),
        fory.deserialize::<Option<Vec<String>>>(bytes.as_slice())
            .unwrap()
    );
    let bytes = fory.serialize(&basic_set()).unwrap();
    assert_eq!(
        Some(basic_set()),
        fory.deserialize::<Option<HashSet<String>>>(bytes.as_slice())
            .unwrap()
    );
    let bytes = fory.serialize(&basic_map()).unwrap();
    // deserialize_outer_nullable
    assert_eq!(
        Some(basic_map()),
        fory.deserialize::<Option<HashMap<String, String>>>(bytes.as_slice())
            .unwrap()
    );
    // serialize_outer_nullable
    let mut bins = vec![
        fory.serialize(&Some(basic_list())).unwrap(),
        fory.serialize(&Some(basic_set())).unwrap(),
        fory.serialize(&Some(basic_map())).unwrap(),
        fory.serialize(&Option::<Vec<String>>::None).unwrap(),
        fory.serialize(&Option::<HashSet<String>>::None).unwrap(),
        fory.serialize(&Option::<HashMap<String, String>>::None)
            .unwrap(),
    ];
    bins.reverse();
    assert_eq!(
        basic_list(),
        fory.deserialize::<Vec<String>>(bins.pop().unwrap().as_slice())
            .unwrap()
    );
    assert_eq!(
        basic_set(),
        fory.deserialize::<HashSet<String>>(bins.pop().unwrap().as_slice())
            .unwrap()
    );
    assert_eq!(
        basic_map(),
        fory.deserialize::<HashMap<String, String>>(bins.pop().unwrap().as_slice())
            .unwrap()
    );
    assert_eq!(
        Vec::<String>::default(),
        fory.deserialize::<Vec<String>>(bins.pop().unwrap().as_slice())
            .unwrap()
    );
    assert_eq!(
        HashSet::default(),
        fory.deserialize::<HashSet<String>>(bins.pop().unwrap().as_slice())
            .unwrap()
    );
    assert_eq!(
        HashMap::default(),
        fory.deserialize::<HashMap<String, String>>(bins.pop().unwrap().as_slice())
            .unwrap()
    );
}

#[test]
fn collection_inner() {
    let mut fory1 = Fory::default().compatible(true);
    fory1.register::<Item>(101).unwrap();
    let mut fory2 = Fory::default().compatible(true);
    fory2.register_by_name::<Item>("item").unwrap();
    for fory in [fory1, fory2] {
        // serialize
        let mut bins = vec![
            fory.serialize(&basic_list()).unwrap(),
            fory.serialize(&item_list()).unwrap(),
            fory.serialize(&basic_set()).unwrap(),
            fory.serialize(&item_set()).unwrap(),
            fory.serialize(&nullable_basic_list(false)).unwrap(),
            fory.serialize(&nullable_item_list(false)).unwrap(),
            fory.serialize(&nullable_basic_set(false)).unwrap(),
            fory.serialize(&nullable_item_set(false)).unwrap(),
        ];
        bins.reverse();
        // deserialize
        assert_eq!(
            basic_list(),
            fory.deserialize::<Vec<String>>(bins.pop().unwrap().as_slice())
                .unwrap()
        );
        assert_eq!(
            item_list(),
            fory.deserialize::<Vec<Item>>(bins.pop().unwrap().as_slice())
                .unwrap()
        );
        assert_eq!(
            basic_set(),
            fory.deserialize::<HashSet<String>>(bins.pop().unwrap().as_slice())
                .unwrap()
        );
        assert_eq!(
            item_set(),
            fory.deserialize::<HashSet<Item>>(bins.pop().unwrap().as_slice())
                .unwrap()
        );
        assert_eq!(
            nullable_basic_list(false),
            fory.deserialize::<Vec<Option<String>>>(bins.pop().unwrap().as_slice())
                .unwrap()
        );
        assert_eq!(
            nullable_item_list(false),
            fory.deserialize::<Vec<Option<Item>>>(bins.pop().unwrap().as_slice())
                .unwrap()
        );
        assert_eq!(
            nullable_basic_set(false),
            fory.deserialize::<HashSet<Option<String>>>(bins.pop().unwrap().as_slice())
                .unwrap()
        );
        assert_eq!(
            nullable_item_set(false),
            fory.deserialize::<HashSet<Option<Item>>>(bins.pop().unwrap().as_slice())
                .unwrap()
        );
    }
}

#[test]
fn collection_inner_auto_conv() {
    let mut fory1 = Fory::default().compatible(true);
    fory1.register::<Item>(101).unwrap();
    let mut fory2 = Fory::default().compatible(true);
    fory2.register_by_name::<Item>("item").unwrap();
    for fory in [fory1, fory2] {
        // serialize_non_null
        let mut bins = vec![
            fory.serialize(&basic_list()).unwrap(),
            fory.serialize(&item_list()).unwrap(),
            fory.serialize(&basic_set()).unwrap(),
            fory.serialize(&item_set()).unwrap(),
        ];
        bins.reverse();
        // deserialize_nullable
        assert_eq!(
            nullable_basic_list(true),
            fory.deserialize::<Vec<Option<String>>>(bins.pop().unwrap().as_slice())
                .unwrap()
        );
        assert_eq!(
            nullable_item_list(true),
            fory.deserialize::<Vec<Option<Item>>>(bins.pop().unwrap().as_slice())
                .unwrap()
        );
        assert_eq!(
            nullable_basic_set(true),
            fory.deserialize::<HashSet<Option<String>>>(bins.pop().unwrap().as_slice())
                .unwrap()
        );
        assert_eq!(
            nullable_item_set(true),
            fory.deserialize::<HashSet<Option<Item>>>(bins.pop().unwrap().as_slice())
                .unwrap()
        );
        // serialize_nullable
        let mut bins = vec![
            fory.serialize(&nullable_basic_list(false)).unwrap(),
            fory.serialize(&nullable_item_list(false)).unwrap(),
            fory.serialize(&nullable_basic_set(false)).unwrap(),
            fory.serialize(&nullable_item_set(false)).unwrap(),
        ];
        bins.reverse();
        // deserialize_non-null
        assert_eq!(
            basic_list(),
            fory.deserialize::<Vec<String>>(bins.pop().unwrap().as_slice())
                .unwrap()
        );
        assert_eq!(
            item_list(),
            fory.deserialize::<Vec<Item>>(bins.pop().unwrap().as_slice())
                .unwrap()
        );
        assert_eq!(
            basic_set(),
            fory.deserialize::<HashSet<String>>(bins.pop().unwrap().as_slice())
                .unwrap()
        );
        assert_eq!(
            item_set(),
            fory.deserialize::<HashSet<Item>>(bins.pop().unwrap().as_slice())
                .unwrap()
        );
    }
}

#[test]
fn map_inner() {
    let mut fory1 = Fory::default().compatible(true);
    fory1.register::<Item>(101).unwrap();
    let mut fory2 = Fory::default().compatible(true);
    fory2.register_by_name::<Item>("item").unwrap();
    for fory in [fory1, fory2] {
        // serialize
        let bytes = fory.serialize(&basic_map()).unwrap();
        assert_eq!(
            basic_map(),
            fory.deserialize::<HashMap<String, String>>(bytes.as_slice())
                .unwrap()
        );

        let bytes = fory.serialize(&item_map()).unwrap();
        assert_eq!(
            item_map(),
            fory.deserialize::<HashMap<Item, Item>>(bytes.as_slice())
                .unwrap()
        );

        let bytes = fory.serialize(&nullable_basic_map(false)).unwrap();
        assert_eq!(
            nullable_basic_map(false),
            fory.deserialize::<HashMap<Option<String>, Option<String>>>(bytes.as_slice())
                .unwrap()
        );
        let bytes = fory.serialize(&nullable_item_map(false)).unwrap();
        assert_eq!(
            nullable_item_map(false),
            fory.deserialize::<HashMap<Option<Item>, Option<Item>>>(bytes.as_slice())
                .unwrap()
        );
    }
}

#[test]
fn map_inner_auto_conv() {
    let mut fory1 = Fory::default().compatible(true);
    fory1.register::<Item>(101).unwrap();
    let mut fory2 = Fory::default().compatible(true);
    fory2.register_by_name::<Item>("item").unwrap();
    for fory in [fory1, fory2] {
        // serialize_non_null
        let bytes = fory.serialize(&basic_map()).unwrap();
        // deserialize_nullable
        assert_eq!(
            nullable_basic_map(true),
            fory.deserialize::<HashMap<Option<String>, Option<String>>>(bytes.as_slice())
                .unwrap()
        );
        let bytes = fory.serialize(&item_map()).unwrap();
        // deserialize_nullable
        assert_eq!(
            nullable_item_map(true),
            fory.deserialize::<HashMap<Option<Item>, Option<Item>>>(bytes.as_slice())
                .unwrap()
        );
        // serialize_nullable
        let bytes = fory.serialize(&nullable_basic_map(false)).unwrap();
        // deserialize_non-null
        assert_eq!(
            basic_map(),
            fory.deserialize::<HashMap<String, String>>(bytes.as_slice())
                .unwrap()
        );
        let bytes = fory.serialize(&nullable_item_map(false)).unwrap();
        // deserialize_non-null
        assert_eq!(
            item_map(),
            fory.deserialize::<HashMap<Item, Item>>(bytes.as_slice())
                .unwrap()
        );
    }
}

#[test]
fn complex() {
    let mut fory1 = Fory::default().compatible(true);
    fory1.register::<Item>(101).unwrap();
    let mut fory2 = Fory::default().compatible(true);
    fory2.register_by_name::<Item>("item").unwrap();
    for fory in [fory1, fory2] {
        let mut bins = vec![
            fory.serialize(&nested_collection()).unwrap(),
            fory.serialize(&complex_container1()).unwrap(),
            fory.serialize(&complex_container2()).unwrap(),
        ];
        bins.reverse();
        assert_eq!(
            nested_collection(),
            fory.deserialize::<Vec<HashSet<Item>>>(bins.pop().unwrap().as_slice())
                .unwrap()
        );
        assert_eq!(
            complex_container1(),
            fory.deserialize::<Vec<HashMap<Item, Item>>>(bins.pop().unwrap().as_slice())
                .unwrap()
        );
        assert_eq!(
            complex_container2(),
            fory.deserialize::<Vec<HashMap<Vec<Item>, Vec<Item>>>>(bins.pop().unwrap().as_slice())
                .unwrap()
        );
    }
}
