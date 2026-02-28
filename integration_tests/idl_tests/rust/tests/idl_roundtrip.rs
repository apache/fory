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

use std::collections::HashMap;
use std::sync::Arc;
use std::{env, fs};

use chrono::NaiveDate;
use fory::{ArcWeak, Fory};
use idl_tests::generated::addressbook::{
    self,
    person::{PhoneNumber, PhoneType},
    AddressBook, Animal, Cat, Dog, Person,
};
use idl_tests::generated::any_example::{self, AnyHolder, AnyInner, AnyUnion};
use idl_tests::generated::auto_id;
use idl_tests::generated::collection::{
    self, NumericCollectionArrayUnion, NumericCollectionUnion, NumericCollections,
    NumericCollectionsArray,
};
use idl_tests::generated::complex_fbs::{self, Container, Note, Payload, ScalarPack, Status};
use idl_tests::generated::complex_pb::{self, PrimitiveTypes};
use idl_tests::generated::evolving1;
use idl_tests::generated::evolving2;
use idl_tests::generated::monster::{self, Color, Monster, Vec3};
use idl_tests::generated::optional_types::{self, AllOptionalTypes, OptionalHolder, OptionalUnion};
use idl_tests::generated::root;
use idl_tests::generated::{graph, tree};

fn build_address_book() -> AddressBook {
    let mobile = PhoneNumber {
        number: "555-0100".to_string(),
        phone_type: PhoneType::Mobile,
    };
    let work = PhoneNumber {
        number: "555-0111".to_string(),
        phone_type: PhoneType::Work,
    };

    let mut pet = Animal::Dog(Dog {
        name: "Rex".to_string(),
        bark_volume: 5,
    });
    pet = Animal::Cat(Cat {
        name: "Mimi".to_string(),
        lives: 9,
    });

    let person = Person {
        name: "Alice".to_string(),
        id: 123,
        email: "alice@example.com".to_string(),
        tags: vec!["friend".to_string(), "colleague".to_string()],
        scores: HashMap::from([("math".to_string(), 100), ("science".to_string(), 98)]),
        salary: 120000.5,
        phones: vec![mobile, work],
        pet,
    };

    AddressBook {
        people: vec![person.clone()],
        people_by_name: HashMap::from([(person.name.clone(), person)]),
    }
}

fn build_root_holder() -> root::MultiHolder {
    let owner = Person {
        name: "Alice".to_string(),
        id: 123,
        email: String::new(),
        tags: Vec::new(),
        scores: HashMap::new(),
        salary: 0.0,
        phones: Vec::new(),
        pet: Animal::Dog(Dog {
            name: "Rex".to_string(),
            bark_volume: 5,
        }),
    };

    let book = AddressBook {
        people: vec![owner.clone()],
        people_by_name: HashMap::from([(owner.name.clone(), owner.clone())]),
    };

    let root_node = tree::TreeNode {
        id: "root".to_string(),
        name: "root".to_string(),
        children: Vec::new(),
        parent: None,
    };

    root::MultiHolder {
        book: Some(book),
        root: Some(root_node),
        owner: Some(owner),
    }
}

fn build_auto_id_envelope() -> auto_id::Envelope {
    let payload = auto_id::envelope::Payload { value: 42 };
    let detail = auto_id::envelope::Detail::Payload(payload.clone());
    auto_id::Envelope {
        id: "env-1".to_string(),
        payload: Some(payload),
        detail,
        status: auto_id::Status::Ok,
    }
}

fn build_auto_id_wrapper(envelope: auto_id::Envelope) -> auto_id::Wrapper {
    auto_id::Wrapper::Envelope(envelope)
}

#[test]
fn test_to_bytes_from_bytes() {
    let book = build_address_book();
    let bytes = book.to_bytes().expect("serialize addressbook");
    let decoded = AddressBook::from_bytes(&bytes).expect("deserialize addressbook");
    assert_eq!(decoded, book);

    let dog = Dog {
        name: "Rex".to_string(),
        bark_volume: 5,
    };
    let animal = Animal::Dog(dog);
    let animal_bytes = animal.to_bytes().expect("serialize animal");
    let decoded_animal =
        Animal::from_bytes(&animal_bytes).expect("deserialize animal");
    assert_eq!(decoded_animal, animal);

    let multi = build_root_holder();
    let multi_bytes = multi.to_bytes().expect("serialize root");
    let decoded_multi =
        root::MultiHolder::from_bytes(&multi_bytes).expect("deserialize root");
    assert_eq!(decoded_multi, multi);
}

fn build_primitive_types() -> PrimitiveTypes {
    let mut contact =
        complex_pb::primitive_types::Contact::Email("alice@example.com".to_string());
    contact = complex_pb::primitive_types::Contact::Phone(12345);

    PrimitiveTypes {
        bool_value: true,
        int8_value: 12,
        int16_value: 1234,
        int32_value: -123456,
        varint32_value: -12345,
        int64_value: -123456789,
        varint64_value: -987654321,
        tagged_int64_value: 123456789,
        uint8_value: 200,
        uint16_value: 60000,
        uint32_value: 1234567890,
        var_uint32_value: 1234567890,
        uint64_value: 9876543210,
        var_uint64_value: 12345678901,
        tagged_uint64_value: 2222222222,
        float32_value: 2.5,
        float64_value: 3.5,
        contact: Some(contact),
    }
}

fn build_numeric_collections() -> NumericCollections {
    NumericCollections {
        int8_values: vec![1, -2, 3],
        int16_values: vec![100, -200, 300],
        int32_values: vec![1000, -2000, 3000],
        int64_values: vec![10000, -20000, 30000],
        uint8_values: vec![200, 250],
        uint16_values: vec![50000, 60000],
        uint32_values: vec![2000000000, 2100000000],
        uint64_values: vec![9000000000, 12000000000],
        float32_values: vec![1.5, 2.5],
        float64_values: vec![3.5, 4.5],
    }
}

fn build_numeric_collection_union() -> NumericCollectionUnion {
    NumericCollectionUnion::Int32Values(vec![7, 8, 9])
}

fn build_numeric_collections_array() -> NumericCollectionsArray {
    NumericCollectionsArray {
        int8_values: vec![1, -2, 3],
        int16_values: vec![100, -200, 300],
        int32_values: vec![1000, -2000, 3000],
        int64_values: vec![10000, -20000, 30000],
        uint8_values: vec![200, 250],
        uint16_values: vec![50000, 60000],
        uint32_values: vec![2000000000, 2100000000],
        uint64_values: vec![9000000000, 12000000000],
        float32_values: vec![1.5, 2.5],
        float64_values: vec![3.5, 4.5],
    }
}

fn build_numeric_collection_array_union() -> NumericCollectionArrayUnion {
    NumericCollectionArrayUnion::Uint16Values(vec![1000, 2000, 3000])
}

fn build_monster() -> Monster {
    let pos = Vec3 {
        x: 1.0,
        y: 2.0,
        z: 3.0,
    };
    Monster {
        pos: Some(pos),
        mana: 200,
        hp: 80,
        name: "Orc".to_string(),
        friendly: true,
        inventory: vec![1, 2, 3],
        color: Color::Blue,
    }
}

fn build_container() -> Container {
    let scalars = ScalarPack {
        b: -8,
        ub: 200,
        s: -1234,
        us: 40000,
        i: -123456,
        ui: 123456,
        l: -123456789,
        ul: 987654321,
        f: 1.5,
        d: 2.5,
        ok: true,
    };
    let mut payload = Payload::Note(Note {
        text: "alpha".to_string(),
    });
    payload = Payload::Metric(complex_fbs::Metric { value: 42.0 });

    Container {
        id: 9876543210,
        status: Status::Started,
        bytes: vec![1, 2, 3],
        numbers: vec![10, 20, 30],
        scalars: Some(scalars),
        names: vec!["alpha".to_string(), "beta".to_string()],
        flags: vec![true, false],
        payload,
    }
}

fn build_optional_holder() -> OptionalHolder {
    let all_types = AllOptionalTypes {
        bool_value: Some(true),
        int8_value: Some(12),
        int16_value: Some(1234),
        int32_value: Some(-123456),
        fixed_int32_value: Some(-123456),
        varint32_value: Some(-12345),
        int64_value: Some(-123456789),
        fixed_int64_value: Some(-123456789),
        varint64_value: Some(-987654321),
        tagged_int64_value: Some(123456789),
        uint8_value: Some(200),
        uint16_value: Some(60000),
        uint32_value: Some(1234567890),
        fixed_uint32_value: Some(1234567890),
        var_uint32_value: Some(1234567890),
        uint64_value: Some(9876543210),
        fixed_uint64_value: Some(9876543210),
        var_uint64_value: Some(12345678901),
        tagged_uint64_value: Some(2222222222),
        float32_value: Some(2.5),
        float64_value: Some(3.5),
        string_value: Some("optional".to_string()),
        bytes_value: Some(vec![1, 2, 3]),
        date_value: Some(NaiveDate::from_ymd_opt(2024, 1, 2).unwrap()),
        timestamp_value: Some(
            NaiveDate::from_ymd_opt(2024, 1, 2)
                .unwrap()
                .and_hms_opt(3, 4, 5)
                .expect("timestamp"),
        ),
        int32_list: Some(vec![1, 2, 3]),
        string_list: Some(vec!["alpha".to_string(), "beta".to_string()]),
        int64_map: Some(HashMap::from([("alpha".to_string(), 10), ("beta".to_string(), 20)])),
    };

    OptionalHolder {
        all_types: Some(all_types.clone()),
        choice: Some(OptionalUnion::Note("optional".to_string())),
    }
}

fn build_any_holder() -> AnyHolder {
    AnyHolder {
        bool_value: Box::new(true),
        string_value: Box::new("hello".to_string()),
        date_value: Box::new(NaiveDate::from_ymd_opt(2024, 1, 2).unwrap()),
        timestamp_value: Box::new(
            NaiveDate::from_ymd_opt(2024, 1, 2)
                .unwrap()
                .and_hms_opt(3, 4, 5)
                .expect("timestamp"),
        ),
        message_value: Box::new(AnyInner {
            name: "inner".to_string(),
        }),
        union_value: Box::new(AnyUnion::Text("union".to_string())),
        list_value: Box::new("list-placeholder".to_string()),
        map_value: Box::new("map-placeholder".to_string()),
    }
}

fn build_any_holder_with_collections() -> AnyHolder {
    AnyHolder {
        bool_value: Box::new(true),
        string_value: Box::new("hello".to_string()),
        date_value: Box::new(NaiveDate::from_ymd_opt(2024, 1, 2).unwrap()),
        timestamp_value: Box::new(
            NaiveDate::from_ymd_opt(2024, 1, 2)
                .unwrap()
                .and_hms_opt(3, 4, 5)
                .expect("timestamp"),
        ),
        message_value: Box::new(AnyInner {
            name: "inner".to_string(),
        }),
        union_value: Box::new(AnyUnion::Text("union".to_string())),
        list_value: Box::new(vec!["alpha".to_string(), "beta".to_string()]),
        map_value: Box::new(HashMap::from([
            ("k1".to_string(), "v1".to_string()),
            ("k2".to_string(), "v2".to_string()),
        ])),
    }
}

fn assert_any_holder(holder: &AnyHolder) {
    let bool_value = holder.bool_value.downcast_ref::<bool>().expect("bool any");
    assert_eq!(*bool_value, true);
    let string_value = holder
        .string_value
        .downcast_ref::<String>()
        .expect("string any");
    assert_eq!(string_value, "hello");
    let date_value = holder
        .date_value
        .downcast_ref::<NaiveDate>()
        .expect("date any");
    assert_eq!(*date_value, NaiveDate::from_ymd_opt(2024, 1, 2).unwrap());
    let timestamp_value = holder
        .timestamp_value
        .downcast_ref::<chrono::NaiveDateTime>()
        .expect("timestamp any");
    assert_eq!(
        *timestamp_value,
        NaiveDate::from_ymd_opt(2024, 1, 2)
            .unwrap()
            .and_hms_opt(3, 4, 5)
            .expect("timestamp")
    );
    let message_value = holder
        .message_value
        .downcast_ref::<AnyInner>()
        .expect("message any");
    assert_eq!(message_value.name, "inner");
    let union_value = holder
        .union_value
        .downcast_ref::<AnyUnion>()
        .expect("union any");
    assert_eq!(*union_value, AnyUnion::Text("union".to_string()));
}

fn build_tree() -> tree::TreeNode {
    let mut child_a = Arc::new(tree::TreeNode {
        id: "child-a".to_string(),
        name: "child-a".to_string(),
        children: vec![],
        parent: None,
    });
    let mut child_b = Arc::new(tree::TreeNode {
        id: "child-b".to_string(),
        name: "child-b".to_string(),
        children: vec![],
        parent: None,
    });

    let child_a_weak = ArcWeak::from(&child_a);
    let child_b_weak = ArcWeak::from(&child_b);
    Arc::get_mut(&mut child_a)
        .expect("child a unique")
        .parent = Some(child_b_weak);
    Arc::get_mut(&mut child_b)
        .expect("child b unique")
        .parent = Some(child_a_weak);

    tree::TreeNode {
        id: "root".to_string(),
        name: "root".to_string(),
        children: vec![Arc::clone(&child_a), Arc::clone(&child_a), Arc::clone(&child_b)],
        parent: None,
    }
}

fn assert_tree(root: &tree::TreeNode) {
    assert_eq!(root.children.len(), 3);
    assert!(Arc::ptr_eq(&root.children[0], &root.children[1]));
    assert!(!Arc::ptr_eq(&root.children[0], &root.children[2]));
    let parent_a = root.children[0]
        .parent
        .as_ref()
        .expect("child a parent")
        .upgrade()
        .expect("upgrade child a parent");
    let parent_b = root.children[2]
        .parent
        .as_ref()
        .expect("child b parent")
        .upgrade()
        .expect("upgrade child b parent");
    assert!(Arc::ptr_eq(&parent_a, &root.children[2]));
    assert!(Arc::ptr_eq(&parent_b, &root.children[0]));
}

fn build_graph() -> graph::Graph {
    let mut node_a = Arc::new(graph::Node {
        id: "node-a".to_string(),
        out_edges: vec![],
        in_edges: vec![],
    });
    let mut node_b = Arc::new(graph::Node {
        id: "node-b".to_string(),
        out_edges: vec![],
        in_edges: vec![],
    });

    let edge = Arc::new(graph::Edge {
        id: "edge-1".to_string(),
        weight: 1.5_f32,
        from: Some(ArcWeak::from(&node_a)),
        to: Some(ArcWeak::from(&node_b)),
    });

    Arc::get_mut(&mut node_a)
        .expect("node a unique")
        .out_edges = vec![Arc::clone(&edge)];
    Arc::get_mut(&mut node_a)
        .expect("node a unique")
        .in_edges = vec![Arc::clone(&edge)];
    Arc::get_mut(&mut node_b)
        .expect("node b unique")
        .in_edges = vec![Arc::clone(&edge)];

    graph::Graph {
        nodes: vec![Arc::clone(&node_a), Arc::clone(&node_b)],
        edges: vec![Arc::clone(&edge)],
    }
}

fn assert_graph(value: &graph::Graph) {
    assert_eq!(value.nodes.len(), 2);
    assert_eq!(value.edges.len(), 1);
    let node_a = &value.nodes[0];
    let node_b = &value.nodes[1];
    let edge = &value.edges[0];
    assert!(Arc::ptr_eq(&node_a.out_edges[0], &node_a.in_edges[0]));
    assert!(Arc::ptr_eq(&node_a.out_edges[0], edge));
    let from = edge
        .from
        .as_ref()
        .expect("edge from")
        .upgrade()
        .expect("upgrade from");
    let to = edge
        .to
        .as_ref()
        .expect("edge to")
        .upgrade()
        .expect("upgrade to");
    assert!(Arc::ptr_eq(&from, node_a));
    assert!(Arc::ptr_eq(&to, node_b));
}

#[test]
fn test_address_book_roundtrip_compatible() {
    run_address_book_roundtrip(true);
}

#[test]
fn test_address_book_roundtrip_schema_consistent() {
    run_address_book_roundtrip(false);
}

#[test]
fn test_evolving_roundtrip() {
    let mut fory_v1 = Fory::default().xlang(true).compatible(true);
    evolving1::register_types(&mut fory_v1).expect("register evolving1 types");
    let mut fory_v2 = Fory::default().xlang(true).compatible(true);
    evolving2::register_types(&mut fory_v2).expect("register evolving2 types");

    let msg_v1 = evolving1::EvolvingMessage {
        id: 1,
        name: "Alice".to_string(),
        city: "NYC".to_string(),
    };
    let bytes = fory_v1.serialize(&msg_v1).expect("serialize evolving v1");
    let mut msg_v2: evolving2::EvolvingMessage = fory_v2
        .deserialize(&bytes)
        .expect("deserialize evolving v2");
    assert_eq!(msg_v1.id, msg_v2.id);
    assert_eq!(msg_v1.name, msg_v2.name);
    assert_eq!(msg_v1.city, msg_v2.city);

    msg_v2.email = Some("alice@example.com".to_string());
    let round_bytes = fory_v2.serialize(&msg_v2).expect("serialize evolving v2");
    let msg_v1_round: evolving1::EvolvingMessage = fory_v1
        .deserialize(&round_bytes)
        .expect("deserialize evolving v1");
    assert_eq!(msg_v1, msg_v1_round);

    let fixed_v1 = evolving1::FixedMessage {
        id: 10,
        name: "Bob".to_string(),
        score: 90,
        note: "note".to_string(),
    };
    let fixed_bytes = fory_v1
        .serialize(&fixed_v1)
        .expect("serialize fixed v1");
    let fixed_v2 = fory_v2.deserialize::<evolving2::FixedMessage>(&fixed_bytes);
    match fixed_v2 {
        Err(_) => return,
        Ok(value) => {
            let round = fory_v2.serialize(&value);
            if let Ok(round_bytes) = round {
                let fixed_round = fory_v1.deserialize::<evolving1::FixedMessage>(&round_bytes);
                if let Ok(fixed_round) = fixed_round {
                    assert_ne!(fixed_round, fixed_v1);
                }
            }
        }
    }
}

fn run_address_book_roundtrip(compatible: bool) {
    let mut fory = Fory::default().xlang(true).compatible(compatible);
    complex_pb::register_types(&mut fory).expect("register complex pb types");
    addressbook::register_types(&mut fory).expect("register types");
    auto_id::register_types(&mut fory).expect("register auto_id types");
    monster::register_types(&mut fory).expect("register monster types");
    complex_fbs::register_types(&mut fory).expect("register flatbuffers types");
    collection::register_types(&mut fory).expect("register collection types");
    optional_types::register_types(&mut fory).expect("register optional types");
    any_example::register_types(&mut fory).expect("register any example types");

    let book = build_address_book();
    let bytes = fory.serialize(&book).expect("serialize");
    let roundtrip: AddressBook = fory.deserialize(&bytes).expect("deserialize");

    assert_eq!(book, roundtrip);

    let auto_env = build_auto_id_envelope();
    let auto_bytes = fory.serialize(&auto_env).expect("serialize auto_id");
    let auto_roundtrip: auto_id::Envelope =
        fory.deserialize(&auto_bytes).expect("deserialize auto_id");
    assert_eq!(auto_env, auto_roundtrip);

    let auto_wrapper = build_auto_id_wrapper(auto_env.clone());
    let wrapper_bytes = fory
        .serialize(&auto_wrapper)
        .expect("serialize auto_id wrapper");
    let wrapper_roundtrip: auto_id::Wrapper =
        fory.deserialize(&wrapper_bytes).expect("deserialize auto_id wrapper");
    assert_eq!(auto_wrapper, wrapper_roundtrip);

    let data_file = match env::var("DATA_FILE") {
        Ok(path) => path,
        Err(_) => return,
    };
    let payload = fs::read(&data_file).expect("read data file");
    let peer_book: AddressBook = fory
        .deserialize(&payload)
        .expect("deserialize peer payload");
    assert_eq!(book, peer_book);
    let encoded = fory.serialize(&peer_book).expect("serialize peer payload");
    fs::write(data_file, encoded).expect("write data file");

    if let Ok(data_file) = env::var("DATA_FILE_AUTO_ID") {
        let payload = fs::read(&data_file).expect("read auto_id data file");
        let peer_env: auto_id::Envelope = fory
            .deserialize(&payload)
            .expect("deserialize auto_id peer payload");
        assert_eq!(auto_env, peer_env);
        let encoded = fory.serialize(&peer_env).expect("serialize auto_id payload");
        fs::write(data_file, encoded).expect("write auto_id data file");
    }

    let types = build_primitive_types();
    let bytes = fory.serialize(&types).expect("serialize");
    let roundtrip: PrimitiveTypes = fory.deserialize(&bytes).expect("deserialize");
    assert_eq!(types, roundtrip);

    let primitive_file = match env::var("DATA_FILE_PRIMITIVES") {
        Ok(path) => path,
        Err(_) => return,
    };
    let payload = fs::read(&primitive_file).expect("read data file");
    let peer_types: PrimitiveTypes = fory
        .deserialize(&payload)
        .expect("deserialize peer payload");
    assert_eq!(types, peer_types);
    let encoded = fory.serialize(&peer_types).expect("serialize peer payload");
    fs::write(primitive_file, encoded).expect("write data file");

    let collections = build_numeric_collections();
    let bytes = fory.serialize(&collections).expect("serialize collections");
    let roundtrip: NumericCollections = fory.deserialize(&bytes).expect("deserialize");
    assert_eq!(collections, roundtrip);

    if let Ok(data_file) = env::var("DATA_FILE_COLLECTION") {
        let payload = fs::read(&data_file).expect("read data file");
        let peer_collections: NumericCollections = fory
            .deserialize(&payload)
            .expect("deserialize peer payload");
        assert_eq!(collections, peer_collections);
        let encoded = fory
            .serialize(&peer_collections)
            .expect("serialize peer payload");
        fs::write(data_file, encoded).expect("write data file");
    }

    let collection_union = build_numeric_collection_union();
    let bytes = fory
        .serialize(&collection_union)
        .expect("serialize collection union");
    let roundtrip: NumericCollectionUnion = fory.deserialize(&bytes).expect("deserialize");
    assert_eq!(collection_union, roundtrip);

    if let Ok(data_file) = env::var("DATA_FILE_COLLECTION_UNION") {
        let payload = fs::read(&data_file).expect("read data file");
        let peer_union: NumericCollectionUnion = fory
            .deserialize(&payload)
            .expect("deserialize peer payload");
        assert_eq!(collection_union, peer_union);
        let encoded = fory
            .serialize(&peer_union)
            .expect("serialize peer payload");
        fs::write(data_file, encoded).expect("write data file");
    }

    let collections_array = build_numeric_collections_array();
    let bytes = fory
        .serialize(&collections_array)
        .expect("serialize collection array");
    let roundtrip: NumericCollectionsArray = fory.deserialize(&bytes).expect("deserialize");
    assert_eq!(collections_array, roundtrip);

    if let Ok(data_file) = env::var("DATA_FILE_COLLECTION_ARRAY") {
        let payload = fs::read(&data_file).expect("read data file");
        let peer_array: NumericCollectionsArray = fory
            .deserialize(&payload)
            .expect("deserialize peer payload");
        assert_eq!(collections_array, peer_array);
        let encoded = fory
            .serialize(&peer_array)
            .expect("serialize peer payload");
        fs::write(data_file, encoded).expect("write data file");
    }

    let collection_array_union = build_numeric_collection_array_union();
    let bytes = fory
        .serialize(&collection_array_union)
        .expect("serialize collection array union");
    let roundtrip: NumericCollectionArrayUnion =
        fory.deserialize(&bytes).expect("deserialize");
    assert_eq!(collection_array_union, roundtrip);

    if let Ok(data_file) = env::var("DATA_FILE_COLLECTION_ARRAY_UNION") {
        let payload = fs::read(&data_file).expect("read data file");
        let peer_union: NumericCollectionArrayUnion = fory
            .deserialize(&payload)
            .expect("deserialize peer payload");
        assert_eq!(collection_array_union, peer_union);
        let encoded = fory
            .serialize(&peer_union)
            .expect("serialize peer payload");
        fs::write(data_file, encoded).expect("write data file");
    }

    let monster = build_monster();
    let bytes = fory.serialize(&monster).expect("serialize");
    let roundtrip: Monster = fory.deserialize(&bytes).expect("deserialize");
    assert_eq!(monster, roundtrip);

    if let Ok(data_file) = env::var("DATA_FILE_FLATBUFFERS_MONSTER") {
        let payload = fs::read(&data_file).expect("read data file");
        let peer_monster: Monster = fory
            .deserialize(&payload)
            .expect("deserialize peer payload");
        assert_eq!(monster, peer_monster);
        let encoded = fory
            .serialize(&peer_monster)
            .expect("serialize peer payload");
        fs::write(data_file, encoded).expect("write data file");
    }

    let container = build_container();
    let bytes = fory.serialize(&container).expect("serialize");
    let roundtrip: Container = fory.deserialize(&bytes).expect("deserialize");
    assert_eq!(container, roundtrip);

    if let Ok(data_file) = env::var("DATA_FILE_FLATBUFFERS_TEST2") {
        let payload = fs::read(&data_file).expect("read data file");
        let peer_container: Container = fory
            .deserialize(&payload)
            .expect("deserialize peer payload");
        assert_eq!(container, peer_container);
        let encoded = fory
            .serialize(&peer_container)
            .expect("serialize peer payload");
        fs::write(data_file, encoded).expect("write data file");
    }

    let holder = build_optional_holder();
    let bytes = fory.serialize(&holder).expect("serialize");
    let roundtrip: OptionalHolder = fory.deserialize(&bytes).expect("deserialize");
    assert_eq!(holder, roundtrip);

    if let Ok(data_file) = env::var("DATA_FILE_OPTIONAL_TYPES") {
        let payload = fs::read(&data_file).expect("read data file");
        let peer_holder: OptionalHolder = fory
            .deserialize(&payload)
            .expect("deserialize peer payload");
        assert_eq!(holder, peer_holder);
        let encoded = fory
            .serialize(&peer_holder)
            .expect("serialize peer payload");
        fs::write(data_file, encoded).expect("write data file");
    }

    let any_holder = build_any_holder();
    let bytes = fory.serialize(&any_holder).expect("serialize any");
    let roundtrip: AnyHolder = fory.deserialize(&bytes).expect("deserialize any");
    assert_any_holder(&roundtrip);

    let any_holder_collections = build_any_holder_with_collections();
    let bytes = fory
        .serialize(&any_holder_collections)
        .expect("serialize any collections");
    let result: Result<AnyHolder, _> = fory.deserialize(&bytes);
    assert!(result.is_err());

    let mut ref_fory = Fory::default()
        .xlang(true)
        .compatible(compatible)
        .track_ref(true);
    tree::register_types(&mut ref_fory).expect("register tree types");
    graph::register_types(&mut ref_fory).expect("register graph types");

    let tree_root = build_tree();
    let bytes = ref_fory.serialize(&tree_root).expect("serialize tree");
    let roundtrip: tree::TreeNode = ref_fory.deserialize(&bytes).expect("deserialize");
    assert_tree(&roundtrip);

    if let Ok(data_file) = env::var("DATA_FILE_TREE") {
        let payload = fs::read(&data_file).expect("read tree data file");
        let peer_tree: tree::TreeNode = ref_fory
            .deserialize(&payload)
            .expect("deserialize peer tree payload");
        assert_tree(&peer_tree);
        let encoded = ref_fory
            .serialize(&peer_tree)
            .expect("serialize peer tree payload");
        fs::write(data_file, encoded).expect("write tree data file");
    }

    let graph_value = build_graph();
    let bytes = ref_fory.serialize(&graph_value).expect("serialize graph");
    let roundtrip: graph::Graph = ref_fory.deserialize(&bytes).expect("deserialize");
    assert_graph(&roundtrip);

    if let Ok(data_file) = env::var("DATA_FILE_GRAPH") {
        let payload = fs::read(&data_file).expect("read graph data file");
        let peer_graph: graph::Graph = ref_fory
            .deserialize(&payload)
            .expect("deserialize peer graph payload");
        assert_graph(&peer_graph);
        let encoded = ref_fory
            .serialize(&peer_graph)
            .expect("serialize peer graph payload");
        fs::write(data_file, encoded).expect("write graph data file");
    }
}
