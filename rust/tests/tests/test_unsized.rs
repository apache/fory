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

use fory_core::{ArcWeak, Fory, RcWeak, Serializer};
use fory_derive::ForyStruct;
use std::collections::HashMap;
use std::fmt::Debug;
use std::rc::Rc;
use std::sync::Arc;

#[derive(ForyStruct, Debug, Clone, PartialEq)]
struct Item {
    value: i32,
    text: String,
}

fn roundtrip<T: Serializer<Target = T> + PartialEq + Debug>(fory: &Fory, value: &T) -> T {
    let bytes = fory.serialize(value).unwrap();
    let decoded = fory.deserialize::<T>(&bytes).unwrap();
    assert_eq!(&decoded, value);
    decoded
}

#[test]
fn roots() {
    for xlang in [false, true] {
        for compatible in [false, true] {
            for track_ref in [false, true] {
                let mut fory = Fory::builder()
                    .xlang(xlang)
                    .compatible(compatible)
                    .track_ref(track_ref)
                    .build();
                fory.register::<Item>(2000).unwrap();
                for text in ["", "hello", "caf\u{e9}", "\u{4e16}\u{754c}\u{1f980}"] {
                    roundtrip(&fory, &Box::<str>::from(text));
                    roundtrip(&fory, &Rc::<str>::from(text));
                    roundtrip(&fory, &Arc::<str>::from(text));
                    assert_eq!(
                        fory.serialize(&Arc::<str>::from(text)).unwrap(),
                        fory.serialize(&Arc::new(text.to_owned())).unwrap(),
                    );
                }
                for values in [vec![], vec![1_i32, -20, i32::MAX]] {
                    roundtrip(&fory, &Box::<[i32]>::from(values.clone()));
                    roundtrip(&fory, &Rc::<[i32]>::from(values.clone()));
                    roundtrip(&fory, &Arc::<[i32]>::from(values.clone()));
                    assert_eq!(
                        fory.serialize(&Arc::<[i32]>::from(values.clone())).unwrap(),
                        fory.serialize(&Arc::new(values)).unwrap(),
                    );
                }
                roundtrip(&fory, &Arc::<[u8]>::from(vec![0, 127, 255]));
                roundtrip(&fory, &Rc::<[bool]>::from(vec![true, false]));
                roundtrip(&fory, &Box::<[f64]>::from(vec![1.25, -9.5]));
                let items = vec![Item {
                    value: 42,
                    text: "item".into(),
                }];
                roundtrip(&fory, &Arc::<[Item]>::from(items.clone()));
                roundtrip(&fory, &Rc::<[Item]>::from(items.clone()));
                roundtrip(&fory, &Box::<[Item]>::from(items));
            }
        }
    }
}

#[derive(ForyStruct, Debug, PartialEq)]
struct Pointers {
    arc_text: Arc<str>,
    rc_text: Rc<str>,
    box_text: Box<str>,
    arc_items: Arc<[Item]>,
    rc_items: Rc<[Item]>,
    box_items: Box<[Item]>,
    #[fory(list(element(encoding = "fixed")))]
    numbers: Arc<[i32]>,
    #[fory(array)]
    dense: Rc<[i64]>,
    #[fory(bytes)]
    bytes: Box<[u8]>,
    optional: Option<Arc<[String]>>,
    nested: Vec<Box<[Arc<str>]>>,
    map: HashMap<String, Rc<[i32]>>,
}

#[test]
fn fields() {
    for xlang in [false, true] {
        for compatible in [false, true] {
            let mut fory = Fory::builder().xlang(xlang).compatible(compatible).build();
            fory.register::<Item>(2000).unwrap();
            fory.register::<Pointers>(2001).unwrap();
            let items = vec![Item {
                value: -3,
                text: "field".into(),
            }];
            let text = Arc::<str>::from("shared");
            let value = Pointers {
                arc_text: text.clone(),
                rc_text: Rc::from("rc"),
                box_text: Box::from("box"),
                arc_items: items.clone().into(),
                rc_items: items.clone().into(),
                box_items: items.into(),
                numbers: vec![10, 20].into(),
                dense: vec![-42, i64::MAX].into(),
                bytes: vec![0, 255].into(),
                optional: Some(vec!["a".into(), "b".into()].into()),
                nested: vec![vec![text.clone(), text].into()],
                map: HashMap::from([("numbers".into(), vec![7, 8].into())]),
            };
            let decoded = roundtrip(&fory, &value);
            assert!(Arc::ptr_eq(&decoded.arc_text, &decoded.nested[0][0]));
            assert!(Arc::ptr_eq(&decoded.nested[0][0], &decoded.nested[0][1]));
        }
    }
}

macro_rules! check_shared {
    ($fory:ident, $pointer:ident, $weak:ident, $value:expr) => {{
        let strong = $value;
        let weak = $weak::from(&strong);
        let value = (weak.clone(), strong.clone(), strong, weak);
        let bytes = $fory.serialize(&value).unwrap();
        let decoded = $fory.deserialize(&bytes).unwrap();
        fn same_type<T>(_: &T, _: &T) {}
        same_type(&value, &decoded);
        assert!($pointer::ptr_eq(&decoded.1, &decoded.2));
        assert!($pointer::ptr_eq(&decoded.0.upgrade().unwrap(), &decoded.1));
        assert!($pointer::ptr_eq(&decoded.3.upgrade().unwrap(), &decoded.1));
        assert_eq!(&*decoded.1, &*value.1);
    }};
}

#[test]
fn shared_and_weak() {
    for xlang in [false, true] {
        for compatible in [false, true] {
            let fory = Fory::builder()
                .xlang(xlang)
                .compatible(compatible)
                .track_ref(true)
                .build();
            check_shared!(fory, Rc, RcWeak, Rc::<str>::from("shared"));
            check_shared!(fory, Arc, ArcWeak, Arc::<str>::from("shared"));
            check_shared!(fory, Rc, RcWeak, Rc::<[i32]>::from(vec![1, 2, 3]));
            check_shared!(fory, Arc, ArcWeak, Arc::<[i32]>::from(vec![1, 2, 3]));
            check_shared!(fory, Rc, RcWeak, Rc::<[String]>::from(vec!["item".into()]));
            check_shared!(
                fory,
                Arc,
                ArcWeak,
                Arc::<[String]>::from(vec!["item".into()])
            );
            check_shared!(fory, Rc, RcWeak, Rc::<str>::from(""));
            check_shared!(fory, Arc, ArcWeak, Arc::<[i32]>::from(vec![]));
        }
    }
}

#[test]
fn empty_weak() {
    let fory = Fory::builder().track_ref(true).build();
    let rc_str = RcWeak::<str>::new();
    let arc_str = ArcWeak::<str>::new();
    let rc_slice = RcWeak::<[i32]>::new();
    let arc_slice = ArcWeak::<[String]>::new();
    assert!(rc_str.upgrade().is_none());
    assert!(arc_str.upgrade().is_none());
    assert!(rc_slice.upgrade().is_none());
    assert!(arc_slice.upgrade().is_none());
    assert!(rc_str.ptr_eq(&rc_str.clone()));
    assert!(arc_str.ptr_eq(&arc_str.clone()));
    let bytes = fory
        .serialize(&(rc_str, arc_str, rc_slice, arc_slice))
        .unwrap();
    type EmptyWeak = (RcWeak<str>, ArcWeak<str>, RcWeak<[i32]>, ArcWeak<[String]>);
    let decoded: EmptyWeak = fory.deserialize(&bytes).unwrap();
    assert!(decoded.0.upgrade().is_none());
    assert!(decoded.1.upgrade().is_none());
    assert!(decoded.2.upgrade().is_none());
    assert!(decoded.3.upgrade().is_none());

    let dead = ArcWeak::from(&Arc::<str>::from("dropped"));
    let bytes = fory.serialize(&dead).unwrap();
    let decoded: ArcWeak<str> = fory.deserialize(&bytes).unwrap();
    assert!(decoded.upgrade().is_none());

    let strong = Arc::<str>::from("updated");
    let alias = decoded.clone();
    decoded.update(Arc::downgrade(&strong));
    assert!(Arc::ptr_eq(&alias.upgrade().unwrap(), &strong));
}

#[derive(ForyStruct)]
struct OwnedFields {
    text: Arc<String>,
    items: Rc<Vec<Item>>,
}

#[derive(ForyStruct)]
struct UnsizedFields {
    text: Arc<str>,
    items: Rc<[Item]>,
    added_text: Box<str>,
    added_items: Arc<[Item]>,
    added_rc: RcWeak<str>,
    added_arc: ArcWeak<[Item]>,
}

#[test]
fn compatible_fields() {
    for xlang in [false, true] {
        let mut writer = Fory::builder()
            .xlang(xlang)
            .compatible(true)
            .track_ref(true)
            .build();
        writer.register::<Item>(2000).unwrap();
        writer.register::<OwnedFields>(2001).unwrap();
        let mut reader = Fory::builder()
            .xlang(xlang)
            .compatible(true)
            .track_ref(true)
            .build();
        reader.register::<Item>(2000).unwrap();
        reader.register::<UnsizedFields>(2001).unwrap();
        let value = OwnedFields {
            text: Arc::new("compatible".into()),
            items: Rc::new(vec![Item {
                value: 3,
                text: "item".into(),
            }]),
        };
        let decoded: UnsizedFields = reader
            .deserialize(&writer.serialize(&value).unwrap())
            .unwrap();
        assert_eq!(&*decoded.text, &**value.text);
        assert_eq!(&*decoded.items, &**value.items);
        assert!(decoded.added_text.is_empty());
        assert!(decoded.added_items.is_empty());
        assert!(decoded.added_rc.upgrade().is_none());
        assert!(decoded.added_arc.upgrade().is_none());
        let restored: OwnedFields = writer
            .deserialize(&reader.serialize(&decoded).unwrap())
            .unwrap();
        assert_eq!(restored.text, value.text);
        assert_eq!(restored.items, value.items);
    }
}

#[derive(ForyStruct, Clone, Debug, PartialEq)]
struct Empty;

#[test]
fn resource_limits() {
    let mut fory = Fory::builder()
        .track_ref(true)
        .max_graph_memory_bytes(std::mem::size_of::<Item>())
        .max_unbacked_container_items(10)
        .build();
    fory.register::<Item>(2000).unwrap();
    fory.register::<Empty>(2001).unwrap();
    let items: Arc<[Item]> = vec![
        Item {
            value: 1,
            text: "item".into()
        };
        2
    ]
    .into();
    let bytes = fory.serialize(&items).unwrap();
    assert!(fory.deserialize::<Arc<[Item]>>(&bytes).is_err());
    let one: Arc<[Item]> = vec![items[0].clone()].into();
    roundtrip(&fory, &one);

    let empty: Box<[Empty]> = vec![Empty; 3000].into();
    let bytes = fory.serialize(&empty).unwrap();
    assert!(fory.deserialize::<Box<[Empty]>>(&bytes).is_err());
    roundtrip(&fory, &Box::<[Empty]>::from(vec![Empty; 10]));

    let text: Arc<str> = "truncated \u{4e16}\u{754c}".into();
    let bytes = fory.serialize(&(text.clone(), text.clone())).unwrap();
    for end in 0..bytes.len() {
        assert!(fory
            .deserialize::<(Arc<str>, Arc<str>)>(&bytes[..end])
            .is_err());
        let decoded: (Arc<str>, Arc<str>) = fory.deserialize(&bytes).unwrap();
        assert!(Arc::ptr_eq(&decoded.0, &decoded.1));
        assert_eq!(decoded.0, text);
    }
}

#[derive(ForyStruct, Debug, PartialEq)]
struct SharedSlices {
    text: Arc<std::primitive::str>,
    values: Arc<[i32]>,
    boxed: Box<[String]>,
}

#[test]
fn dynamic_arc() {
    for compatible in [false, true] {
        let mut fory = Fory::builder().compatible(compatible).build();
        fory.register::<SharedSlices>(2000).unwrap();
        let value = SharedSlices {
            text: "shared".into(),
            values: vec![1, 2, 3].into(),
            boxed: vec!["boxed".into()].into(),
        };
        let value: Arc<dyn std::any::Any + Send + Sync> = Arc::new(value);
        let decoded: Arc<dyn std::any::Any + Send + Sync> =
            fory.deserialize(&fory.serialize(&value).unwrap()).unwrap();
        assert_eq!(
            decoded.downcast_ref::<SharedSlices>(),
            value.downcast_ref::<SharedSlices>()
        );
    }
}

#[test]
fn qualified_pointees() {
    type String = u8;
    let _: String = 0;
    #[derive(ForyStruct, Debug, PartialEq)]
    struct Text {
        arc: Arc<std::primitive::str>,
        boxed: Box<core::primitive::str>,
    }
    let mut fory = Fory::builder().build();
    fory.register::<Text>(2000).unwrap();
    roundtrip(
        &fory,
        &Text {
            arc: "arc".into(),
            boxed: "box".into(),
        },
    );
}
