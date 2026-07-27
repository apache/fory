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

use fory_core::{register_trait_type, Fory, ForyObject, Serializer};
use fory_derive::ForyStruct;
use std::collections::HashMap;

trait Animal: ForyObject {
    fn name(&self) -> &str;
    fn sound(&self) -> &str;
}

#[derive(ForyStruct, Debug, PartialEq)]
struct Dog {
    name: String,
    breed: String,
}

impl Animal for Dog {
    fn name(&self) -> &str {
        &self.name
    }

    fn sound(&self) -> &str {
        "woof"
    }
}

#[derive(ForyStruct, Debug, PartialEq)]
struct Cat {
    name: String,
    color: String,
}

impl Animal for Cat {
    fn name(&self) -> &str {
        &self.name
    }

    fn sound(&self) -> &str {
        "meow"
    }
}

#[derive(ForyStruct, Debug, PartialEq)]
struct Fox {
    name: String,
}

impl Animal for Fox {
    fn name(&self) -> &str {
        &self.name
    }

    fn sound(&self) -> &str {
        "ring-ding"
    }
}

register_trait_type!(Animal, Dog, Cat);

const _: () = {
    assert!(<Box<dyn Animal> as Serializer>::IS_POLYMORPHIC);
    assert!(<Box<dyn Animal> as Serializer>::IS_WRAPPER);
    assert!(!<Box<dyn Animal> as Serializer>::REQUIRES_SCOPED_ACCESS);
};

#[derive(ForyStruct)]
struct Zoo {
    featured: Box<dyn Animal>,
    residents: Vec<Box<dyn Animal>>,
    by_name: HashMap<String, Box<dyn Animal>>,
}

fn fory() -> Fory {
    let mut fory = Fory::builder().xlang(false).compatible(true).build();
    fory.register::<Dog>(8_001).unwrap();
    fory.register::<Cat>(8_002).unwrap();
    fory.register::<Fox>(8_003).unwrap();
    fory.register::<Zoo>(8_004).unwrap();
    fory
}

fn dog(name: &str) -> Box<dyn Animal> {
    Box::new(Dog {
        name: name.to_string(),
        breed: "retriever".to_string(),
    })
}

fn cat(name: &str) -> Box<dyn Animal> {
    Box::new(Cat {
        name: name.to_string(),
        color: "orange".to_string(),
    })
}

#[test]
fn box_root_dispatches_targets() {
    let fory = fory();

    let bytes = fory.serialize(&dog("Buddy")).unwrap();
    let decoded: Box<dyn Animal> = fory.deserialize(&bytes).unwrap();

    assert_eq!(decoded.name(), "Buddy");
    assert_eq!(decoded.sound(), "woof");
    assert!(decoded.as_ref().as_any().downcast_ref::<Dog>().is_some());
}

#[test]
fn box_fields_and_carriers() {
    let fory = fory();
    let value = Zoo {
        featured: cat("Mochi"),
        residents: vec![dog("Rex"), cat("Luna")],
        by_name: HashMap::from([
            ("dog".to_string(), dog("Scout")),
            ("cat".to_string(), cat("Miso")),
        ]),
    };

    let bytes = fory.serialize(&value).unwrap();
    let decoded: Zoo = fory.deserialize(&bytes).unwrap();

    assert_eq!(decoded.featured.name(), "Mochi");
    assert_eq!(decoded.featured.sound(), "meow");
    assert_eq!(decoded.residents[0].name(), "Rex");
    assert_eq!(decoded.residents[1].sound(), "meow");
    assert_eq!(decoded.by_name["dog"].sound(), "woof");
    assert_eq!(decoded.by_name["cat"].name(), "Miso");
}

#[test]
fn dynamic_trait_collections() {
    let fory = fory();

    let homogeneous = vec![dog("Rex"), dog("Scout")];
    let bytes = fory.serialize(&homogeneous).unwrap();
    let decoded: Vec<Box<dyn Animal>> = fory.deserialize(&bytes).unwrap();
    assert_eq!(decoded[0].name(), "Rex");
    assert_eq!(decoded[1].name(), "Scout");

    let heterogeneous = HashMap::from([
        ("dog".to_string(), dog("Buddy")),
        ("cat".to_string(), cat("Mochi")),
    ]);
    let bytes = fory.serialize(&heterogeneous).unwrap();
    let decoded: HashMap<String, Box<dyn Animal>> = fory.deserialize(&bytes).unwrap();
    assert_eq!(decoded["dog"].sound(), "woof");
    assert_eq!(decoded["cat"].sound(), "meow");

    let nested = HashMap::from([
        ("dogs".to_string(), vec![dog("Max"), dog("Finn")]),
        ("mixed".to_string(), vec![cat("Luna"), dog("Otis")]),
    ]);
    let bytes = fory.serialize(&nested).unwrap();
    let decoded: HashMap<String, Vec<Box<dyn Animal>>> = fory.deserialize(&bytes).unwrap();
    assert_eq!(decoded["dogs"][0].name(), "Max");
    assert_eq!(decoded["dogs"][1].name(), "Finn");
    assert_eq!(decoded["mixed"][0].sound(), "meow");
    assert_eq!(decoded["mixed"][1].sound(), "woof");

    let tuple = ("featured".to_string(), dog("Tuple"));
    let bytes = fory.serialize(&tuple).unwrap();
    let decoded: (String, Box<dyn Animal>) = fory.deserialize(&bytes).unwrap();
    assert_eq!(decoded.0, "featured");
    assert_eq!(decoded.1.name(), "Tuple");
}

#[test]
fn unlisted_target_is_rejected() {
    let fory = fory();
    let value: Box<dyn Animal> = Box::new(Fox {
        name: "Finn".to_string(),
    });

    let error = fory.serialize(&value).unwrap_err();
    assert!(
        error
            .to_string()
            .contains("is not listed for application trait Animal"),
        "{error}"
    );
}
