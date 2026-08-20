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

use fory_core::{
    register_trait_type, Fory, ForyObject, HashMapSerializer, Serializer, VecSerializer,
};
use fory_derive::ForyStruct;
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::Arc;

trait Animal: ForyObject + Send + Sync {
    fn name(&self) -> &str;
}

#[derive(ForyStruct, Debug, PartialEq)]
struct Dog {
    name: String,
}

impl Animal for Dog {
    fn name(&self) -> &str {
        &self.name
    }
}

#[derive(ForyStruct, Debug, PartialEq)]
struct Cat {
    name: String,
}

impl Animal for Cat {
    fn name(&self) -> &str {
        &self.name
    }
}

register_trait_type!(sync Animal, Dog, Cat);

#[allow(clippy::assertions_on_constants)]
const _: () = {
    assert!(AnimalRcSerializer::IS_POLYMORPHIC);
    assert!(AnimalRcSerializer::IS_SHARED_REF);
    assert!(AnimalRcSerializer::IS_WRAPPER);
    assert!(!AnimalRcSerializer::REQUIRES_SCOPED_ACCESS);
    assert!(AnimalArcSerializer::IS_POLYMORPHIC);
    assert!(AnimalArcSerializer::IS_SHARED_REF);
    assert!(AnimalArcSerializer::IS_WRAPPER);
    assert!(!AnimalArcSerializer::REQUIRES_SCOPED_ACCESS);
};

#[derive(ForyStruct)]
struct SharedZoo {
    rc_featured: Rc<dyn Animal>,
    rc_alias: Rc<dyn Animal>,
    rc_residents: Vec<Rc<dyn Animal>>,
    arc_featured: Arc<dyn Animal>,
    arc_alias: Arc<dyn Animal>,
    arc_by_name: HashMap<String, Arc<dyn Animal>>,
}

fn fory(track_ref: bool) -> Fory {
    let mut fory = Fory::builder()
        .xlang(false)
        .compatible(true)
        .track_ref(track_ref)
        .build();
    fory.register::<Dog>(8_101).unwrap();
    fory.register::<Cat>(8_102).unwrap();
    fory.register::<SharedZoo>(8_103).unwrap();
    fory
}

#[test]
fn rc_arc_roots_are_direct() {
    let fory = fory(false);

    let rc_value: Rc<dyn Animal> = Rc::new(Dog {
        name: "Rex".to_string(),
    });
    let rc_bytes = fory
        .serialize_with::<AnimalRcSerializer>(&rc_value)
        .unwrap();
    let rc_decoded = fory
        .deserialize_with::<AnimalRcSerializer>(&rc_bytes)
        .unwrap();
    assert_eq!(rc_decoded.name(), "Rex");
    assert!(rc_decoded.as_ref().as_any().downcast_ref::<Dog>().is_some());

    let arc_value: Arc<dyn Animal> = Arc::new(Cat {
        name: "Luna".to_string(),
    });
    let arc_bytes = fory
        .serialize_with::<AnimalArcSerializer>(&arc_value)
        .unwrap();
    let arc_decoded = fory
        .deserialize_with::<AnimalArcSerializer>(&arc_bytes)
        .unwrap();
    assert_eq!(arc_decoded.name(), "Luna");
    assert!(arc_decoded
        .as_ref()
        .as_any()
        .downcast_ref::<Cat>()
        .is_some());
}

#[test]
fn shared_trait_identity() {
    let fory = fory(true);
    let rc: Rc<dyn Animal> = Rc::new(Dog {
        name: "Scout".to_string(),
    });
    let arc: Arc<dyn Animal> = Arc::new(Cat {
        name: "Miso".to_string(),
    });
    let value = SharedZoo {
        rc_featured: rc.clone(),
        rc_alias: rc.clone(),
        rc_residents: vec![rc],
        arc_featured: arc.clone(),
        arc_alias: arc.clone(),
        arc_by_name: HashMap::from([("cat".to_string(), arc)]),
    };

    let bytes = fory.serialize(&value).unwrap();
    let decoded: SharedZoo = fory.deserialize(&bytes).unwrap();

    assert!(Rc::ptr_eq(&decoded.rc_featured, &decoded.rc_alias));
    assert!(Rc::ptr_eq(&decoded.rc_featured, &decoded.rc_residents[0]));
    assert!(Arc::ptr_eq(&decoded.arc_featured, &decoded.arc_alias));
    assert!(Arc::ptr_eq(
        &decoded.arc_featured,
        &decoded.arc_by_name["cat"]
    ));
    assert_eq!(decoded.rc_featured.name(), "Scout");
    assert_eq!(decoded.arc_featured.name(), "Miso");
}

#[test]
fn shared_trait_collections() {
    let fory = fory(true);

    let rc: Rc<dyn Animal> = Rc::new(Dog {
        name: "Scout".to_string(),
    });
    let rc_values = vec![
        rc.clone(),
        rc,
        Rc::new(Dog {
            name: "Rex".to_string(),
        }) as Rc<dyn Animal>,
    ];
    let bytes = fory
        .serialize_with::<VecSerializer<AnimalRcSerializer>>(&rc_values)
        .unwrap();
    let decoded = fory
        .deserialize_with::<VecSerializer<AnimalRcSerializer>>(&bytes)
        .unwrap();
    assert!(Rc::ptr_eq(&decoded[0], &decoded[1]));
    assert_eq!(decoded[2].name(), "Rex");

    let arc: Arc<dyn Animal> = Arc::new(Cat {
        name: "Miso".to_string(),
    });
    let arc_values = HashMap::from([
        ("featured".to_string(), arc.clone()),
        ("alias".to_string(), arc),
    ]);
    let bytes = fory
        .serialize_with::<HashMapSerializer<String, AnimalArcSerializer>>(&arc_values)
        .unwrap();
    let decoded = fory
        .deserialize_with::<HashMapSerializer<String, AnimalArcSerializer>>(&bytes)
        .unwrap();
    assert!(Arc::ptr_eq(&decoded["featured"], &decoded["alias"]));
    assert_eq!(decoded["featured"].name(), "Miso");
}
