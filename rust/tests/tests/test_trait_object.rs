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
use fory_core::register_trait_type;
use fory_core::serializer::Serializer;
use fory_derive::ForyObject;
use std::collections::{HashMap, HashSet};

fn fory_compatible() -> Fory {
    Fory::default().compatible(true)
}

#[test]
fn test_multiple_types_in_sequence() {
    let fory = fory_compatible();

    let original1 = 42i32;
    let original2 = String::from("test");
    let original3 = vec![1, 2, 3];

    let val1: Box<dyn Serializer> = Box::new(original1);
    let val2: Box<dyn Serializer> = Box::new(original2.clone());
    let val3: Box<dyn Serializer> = Box::new(original3.clone());

    let ser1 = fory.serialize(&val1);
    let ser2 = fory.serialize(&val2);
    let ser3 = fory.serialize(&val3);

    let de1_trait: Box<dyn Serializer> = fory.deserialize(&ser1).unwrap();
    let de2_trait: Box<dyn Serializer> = fory.deserialize(&ser2).unwrap();
    let de3_trait: Box<dyn Serializer> = fory.deserialize(&ser3).unwrap();

    let de1_concrete: i32 = fory.deserialize(&ser1).unwrap();
    let de2_concrete: String = fory.deserialize(&ser2).unwrap();
    let de3_concrete: Vec<i32> = fory.deserialize(&ser3).unwrap();

    assert_eq!(de1_concrete, original1);
    assert_eq!(de2_concrete, original2);
    assert_eq!(de3_concrete, original3);

    assert_eq!(ser1, fory.serialize(&de1_trait));
    assert_eq!(ser2, fory.serialize(&de2_trait));
    assert_eq!(ser3, fory.serialize(&de3_trait));
}

#[test]
fn test_option_some_roundtrip() {
    let fory = fory_compatible();

    let original = Some(42);
    let trait_obj: Box<dyn Serializer> = Box::new(original);
    let serialized = fory.serialize(&trait_obj);

    let deserialized_trait: Box<dyn Serializer> = fory.deserialize(&serialized).unwrap();
    let deserialized_concrete: Option<i32> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized_concrete, original);
    assert_eq!(fory.serialize(&deserialized_trait), serialized);
}

#[test]
fn test_hashmap_roundtrip() {
    let mut fory = fory_compatible();
    fory.register_serializer::<HashMap<String, i32>>(1001);

    let mut original = HashMap::new();
    original.insert(String::from("one"), 1);
    original.insert(String::from("two"), 2);
    original.insert(String::from("three"), 3);

    let trait_obj: Box<dyn Serializer> = Box::new(original.clone());
    let serialized = fory.serialize(&trait_obj);

    let deserialized_concrete: HashMap<String, i32> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized_concrete.len(), 3);
    assert_eq!(deserialized_concrete.get("one"), Some(&1));
    assert_eq!(deserialized_concrete.get("two"), Some(&2));
    assert_eq!(deserialized_concrete.get("three"), Some(&3));
}

#[test]
fn test_hashset_roundtrip() {
    let mut fory = fory_compatible();
    fory.register_serializer::<HashSet<i32>>(1002);

    let mut original = HashSet::new();
    original.insert(1);
    original.insert(2);
    original.insert(3);

    let trait_obj: Box<dyn Serializer> = Box::new(original.clone());
    let serialized = fory.serialize(&trait_obj);

    let deserialized_concrete: HashSet<i32> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized_concrete.len(), 3);
    assert!(deserialized_concrete.contains(&1));
    assert!(deserialized_concrete.contains(&2));
    assert!(deserialized_concrete.contains(&3));
}

#[test]
fn test_vec_of_trait_objects() {
    let mut fory = fory_compatible();
    fory.register_serializer::<Vec<Box<dyn Serializer>>>(3000);

    let vec_of_trait_objects: Vec<Box<dyn Serializer>> = vec![
        Box::new(42i32),
        Box::new(String::from("hello")),
        Box::new(2.71f64),
    ];

    let serialized = fory.serialize(&vec_of_trait_objects);
    let deserialized: Vec<Box<dyn Serializer>> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized.len(), 3);
}

#[test]
fn test_hashmap_string_to_trait_objects() {
    let mut fory = fory_compatible();
    fory.register_serializer::<HashMap<String, Box<dyn Serializer>>>(3002);

    let mut map: HashMap<String, Box<dyn Serializer>> = HashMap::new();
    map.insert(String::from("int"), Box::new(42i32));
    map.insert(String::from("string"), Box::new(String::from("hello")));
    map.insert(String::from("float"), Box::new(2.71f64));

    let serialized = fory.serialize(&map);
    let deserialized: HashMap<String, Box<dyn Serializer>> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized.len(), 3);
}

#[derive(ForyObject, Debug, PartialEq, Clone)]
struct Person {
    name: String,
    age: i32,
}

#[derive(ForyObject, Debug, PartialEq)]
struct Company {
    name: String,
    employees: Vec<Person>,
}

#[test]
fn test_fory_derived_struct_as_trait_object() {
    let mut fory = fory_compatible();
    fory.register::<Person>(5000);

    let person = Person {
        name: String::from("Alice"),
        age: 30,
    };
    let trait_obj: Box<dyn Serializer> = Box::new(person.clone());
    let serialized = fory.serialize(&trait_obj);

    let deserialized_trait: Box<dyn Serializer> = fory.deserialize(&serialized).unwrap();
    let deserialized_concrete: Person = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized_concrete.name, person.name);
    assert_eq!(deserialized_concrete.age, person.age);

    let reserialized = fory.serialize(&deserialized_trait);
    assert_eq!(serialized, reserialized);
}

#[test]
fn test_vec_of_fory_derived_trait_objects() {
    let mut fory = fory_compatible();
    fory.register::<Person>(5000);
    fory.register::<Company>(5001);
    fory.register_serializer::<Vec<Box<dyn Serializer>>>(3000);

    let vec_of_trait_objects: Vec<Box<dyn Serializer>> = vec![
        Box::new(Person {
            name: String::from("Alice"),
            age: 30,
        }),
        Box::new(Company {
            name: String::from("Acme"),
            employees: vec![Person {
                name: String::from("Bob"),
                age: 25,
            }],
        }),
        Box::new(42i32),
    ];

    let serialized = fory.serialize(&vec_of_trait_objects);
    let deserialized: Vec<Box<dyn Serializer>> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized.len(), 3);
}

#[test]
fn test_hashmap_with_fory_derived_values() {
    let mut fory = fory_compatible();
    fory.register::<Person>(5000);
    fory.register::<Company>(5001);
    fory.register_serializer::<HashMap<String, Box<dyn Serializer>>>(3002);

    let mut map: HashMap<String, Box<dyn Serializer>> = HashMap::new();
    map.insert(
        String::from("person"),
        Box::new(Person {
            name: String::from("Alice"),
            age: 30,
        }),
    );
    map.insert(
        String::from("company"),
        Box::new(Company {
            name: String::from("Acme"),
            employees: vec![],
        }),
    );
    map.insert(String::from("number"), Box::new(42i32));

    let serialized = fory.serialize(&map);
    let deserialized: HashMap<String, Box<dyn Serializer>> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized.len(), 3);
}

// Tests for custom trait objects (Box<dyn CustomTrait>)

trait Animal: Serializer {
    fn speak(&self) -> String;
    fn name(&self) -> &str;
}

#[derive(ForyObject, Debug, PartialEq)]
struct Dog {
    name: String,
    breed: String,
}

impl Animal for Dog {
    fn speak(&self) -> String {
        "Woof!".to_string()
    }

    fn name(&self) -> &str {
        &self.name
    }
}

#[derive(ForyObject, Debug, PartialEq)]
struct Cat {
    name: String,
    color: String,
}

impl Animal for Cat {
    fn speak(&self) -> String {
        "Meow!".to_string()
    }

    fn name(&self) -> &str {
        &self.name
    }
}

register_trait_type!(Animal, Dog, Cat);

#[derive(ForyObject)]
struct Zoo {
    star_animal: Box<dyn Animal>,
}

#[test]
fn test_custom_trait_object_basic() {
    let mut fory = fory_compatible();
    fory.register::<Dog>(8001);
    fory.register::<Cat>(8002);
    fory.register::<Zoo>(8003);

    let zoo_dog = Zoo {
        star_animal: Box::new(Dog {
            name: "Buddy".to_string(),
            breed: "Labrador".to_string(),
        }),
    };

    let zoo_cat = Zoo {
        star_animal: Box::new(Cat {
            name: "Whiskers".to_string(),
            color: "Orange".to_string(),
        }),
    };

    let serialized_dog = fory.serialize(&zoo_dog);
    let serialized_cat = fory.serialize(&zoo_cat);

    let deserialized_dog: Zoo = fory.deserialize(&serialized_dog).unwrap();
    let deserialized_cat: Zoo = fory.deserialize(&serialized_cat).unwrap();

    assert_eq!(deserialized_dog.star_animal.name(), "Buddy");
    assert_eq!(deserialized_dog.star_animal.speak(), "Woof!");

    assert_eq!(deserialized_cat.star_animal.name(), "Whiskers");
    assert_eq!(deserialized_cat.star_animal.speak(), "Meow!");
}

trait Pet: Serializer {
    fn pet_name(&self) -> &str;
}

impl Pet for Dog {
    fn pet_name(&self) -> &str {
        &self.name
    }
}

impl Pet for Cat {
    fn pet_name(&self) -> &str {
        &self.name
    }
}

register_trait_type!(Pet, Dog, Cat);

#[derive(ForyObject)]
struct PetOwner {
    pets: Vec<Box<dyn Pet>>,
    animals: Vec<Box<dyn Animal>>,
}

#[test]
fn test_multiple_traits() {
    let mut fory = fory_compatible();
    fory.register::<Dog>(8001);
    fory.register::<Cat>(8002);
    fory.register::<PetOwner>(9001);

    let owner = PetOwner {
        pets: vec![
            Box::new(Dog {
                name: "Rex".to_string(),
                breed: "German Shepherd".to_string(),
            }),
            Box::new(Cat {
                name: "Luna".to_string(),
                color: "Black".to_string(),
            }),
        ],
        animals: vec![
            Box::new(Dog {
                name: "Rex".to_string(),
                breed: "German Shepherd".to_string(),
            }),
            Box::new(Cat {
                name: "Luna".to_string(),
                color: "Black".to_string(),
            }),
        ],
    };

    let serialized = fory.serialize(&owner);
    let deserialized: PetOwner = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized.pets.len(), 2);
    assert_eq!(deserialized.pets[0].pet_name(), "Rex");
    assert_eq!(deserialized.pets[1].pet_name(), "Luna");
    assert_eq!(deserialized.animals.len(), 2);
    assert_eq!(deserialized.animals[0].name(), "Rex");
    assert_eq!(deserialized.animals[0].speak(), "Woof!");
    assert_eq!(deserialized.animals[1].name(), "Luna");
    assert_eq!(deserialized.animals[1].speak(), "Meow!");
}

// Tests for direct Vec<Box<dyn CustomTrait>> and HashMap<String, Box<dyn CustomTrait>>
// These should work automatically now with the enhanced register_trait_type! macro

#[test]
fn test_single_custom_trait_object_direct() {
    let mut fory = fory_compatible();
    fory.register::<Dog>(8001);
    fory.register::<Cat>(8002);

    let animal: Box<dyn Animal> = Box::new(Dog {
        name: "Rex".to_string(),
        breed: "Golden Retriever".to_string(),
    });

    let serialized = fory.serialize(&animal);
    let deserialized: Box<dyn Animal> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized.name(), "Rex");
    assert_eq!(deserialized.speak(), "Woof!");
}

#[test]
fn test_vec_custom_trait_objects_direct() {
    let mut fory = fory_compatible();
    fory.register::<Dog>(8001);
    fory.register::<Cat>(8002);

    let animals: Vec<Box<dyn Animal>> = vec![
        Box::new(Dog {
            name: "Rex".to_string(),
            breed: "Golden Retriever".to_string(),
        }),
        Box::new(Cat {
            name: "Whiskers".to_string(),
            color: "Orange".to_string(),
        }),
        Box::new(Dog {
            name: "Buddy".to_string(),
            breed: "Labrador".to_string(),
        }),
    ];

    let serialized = fory.serialize(&animals);
    let deserialized: Vec<Box<dyn Animal>> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized.len(), 3);
    assert_eq!(deserialized[0].name(), "Rex");
    assert_eq!(deserialized[0].speak(), "Woof!");
    assert_eq!(deserialized[1].name(), "Whiskers");
    assert_eq!(deserialized[1].speak(), "Meow!");
    assert_eq!(deserialized[2].name(), "Buddy");
    assert_eq!(deserialized[2].speak(), "Woof!");
}

#[test]
fn test_hashmap_custom_trait_objects_direct() {
    let mut fory = fory_compatible();
    fory.register::<Dog>(8001);
    fory.register::<Cat>(8002);

    let mut animal_map: std::collections::HashMap<String, Box<dyn Animal>> =
        std::collections::HashMap::new();
    animal_map.insert(
        "pet1".to_string(),
        Box::new(Dog {
            name: "Max".to_string(),
            breed: "German Shepherd".to_string(),
        }),
    );
    animal_map.insert(
        "pet2".to_string(),
        Box::new(Cat {
            name: "Luna".to_string(),
            color: "Black".to_string(),
        }),
    );
    animal_map.insert(
        "pet3".to_string(),
        Box::new(Dog {
            name: "Charlie".to_string(),
            breed: "Beagle".to_string(),
        }),
    );

    let serialized = fory.serialize(&animal_map);
    let deserialized: std::collections::HashMap<String, Box<dyn Animal>> =
        fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized.len(), 3);
    assert_eq!(deserialized.get("pet1").unwrap().name(), "Max");
    assert_eq!(deserialized.get("pet1").unwrap().speak(), "Woof!");
    assert_eq!(deserialized.get("pet2").unwrap().name(), "Luna");
    assert_eq!(deserialized.get("pet2").unwrap().speak(), "Meow!");
    assert_eq!(deserialized.get("pet3").unwrap().name(), "Charlie");
    assert_eq!(deserialized.get("pet3").unwrap().speak(), "Woof!");
}

#[test]
fn test_nested_custom_trait_object_collections() {
    let mut fory = fory_compatible();
    fory.register::<Dog>(8001);
    fory.register::<Cat>(8002);

    // Test Vec<Vec<Box<dyn Animal>>>
    let nested_animals: Vec<Vec<Box<dyn Animal>>> = vec![
        vec![
            Box::new(Dog {
                name: "Dog1".to_string(),
                breed: "Breed1".to_string(),
            }),
            Box::new(Cat {
                name: "Cat1".to_string(),
                color: "Color1".to_string(),
            }),
        ],
        vec![Box::new(Dog {
            name: "Dog2".to_string(),
            breed: "Breed2".to_string(),
        })],
    ];

    let serialized = fory.serialize(&nested_animals);
    let deserialized: Vec<Vec<Box<dyn Animal>>> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized.len(), 2);
    assert_eq!(deserialized[0].len(), 2);
    assert_eq!(deserialized[1].len(), 1);
    assert_eq!(deserialized[0][0].name(), "Dog1");
    assert_eq!(deserialized[0][0].speak(), "Woof!");
    assert_eq!(deserialized[0][1].name(), "Cat1");
    assert_eq!(deserialized[0][1].speak(), "Meow!");
    assert_eq!(deserialized[1][0].name(), "Dog2");
    assert_eq!(deserialized[1][0].speak(), "Woof!");
}

#[test]
fn test_mixed_trait_object_collections() {
    let mut fory = fory_compatible();
    fory.register::<Dog>(8001);
    fory.register::<Cat>(8002);

    // Test HashMap<String, Vec<Box<dyn Animal>>>
    let mut groups: std::collections::HashMap<String, Vec<Box<dyn Animal>>> =
        std::collections::HashMap::new();

    groups.insert(
        "dogs".to_string(),
        vec![
            Box::new(Dog {
                name: "Rex".to_string(),
                breed: "Husky".to_string(),
            }),
            Box::new(Dog {
                name: "Max".to_string(),
                breed: "Poodle".to_string(),
            }),
        ],
    );

    groups.insert(
        "cats".to_string(),
        vec![Box::new(Cat {
            name: "Fluffy".to_string(),
            color: "White".to_string(),
        })],
    );

    let serialized = fory.serialize(&groups);
    let deserialized: std::collections::HashMap<String, Vec<Box<dyn Animal>>> =
        fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized.len(), 2);

    let dogs = deserialized.get("dogs").unwrap();
    assert_eq!(dogs.len(), 2);
    assert_eq!(dogs[0].name(), "Rex");
    assert_eq!(dogs[0].speak(), "Woof!");
    assert_eq!(dogs[1].name(), "Max");
    assert_eq!(dogs[1].speak(), "Woof!");

    let cats = deserialized.get("cats").unwrap();
    assert_eq!(cats.len(), 1);
    assert_eq!(cats[0].name(), "Fluffy");
    assert_eq!(cats[0].speak(), "Meow!");
}

#[test]
fn test_empty_trait_object_collections() {
    let mut fory = fory_compatible();
    fory.register::<Dog>(8001);
    fory.register::<Cat>(8002);

    // Test empty Vec<Box<dyn Animal>>
    let empty_animals: Vec<Box<dyn Animal>> = vec![];
    let serialized = fory.serialize(&empty_animals);
    let deserialized: Vec<Box<dyn Animal>> = fory.deserialize(&serialized).unwrap();
    assert_eq!(deserialized.len(), 0);

    // Test empty HashMap<String, Box<dyn Animal>>
    let empty_map: std::collections::HashMap<String, Box<dyn Animal>> =
        std::collections::HashMap::new();
    let serialized = fory.serialize(&empty_map);
    let deserialized: std::collections::HashMap<String, Box<dyn Animal>> =
        fory.deserialize(&serialized).unwrap();
    assert_eq!(deserialized.len(), 0);
}

#[test]
fn test_single_item_trait_object_collections() {
    let mut fory = fory_compatible();
    fory.register::<Dog>(8001);
    fory.register::<Cat>(8002);

    // Test single item Vec<Box<dyn Animal>>
    let single_animal: Vec<Box<dyn Animal>> = vec![Box::new(Dog {
        name: "Solo".to_string(),
        breed: "Bulldog".to_string(),
    })];
    let serialized = fory.serialize(&single_animal);
    let deserialized: Vec<Box<dyn Animal>> = fory.deserialize(&serialized).unwrap();
    assert_eq!(deserialized.len(), 1);
    assert_eq!(deserialized[0].name(), "Solo");
    assert_eq!(deserialized[0].speak(), "Woof!");

    // Test single item HashMap<String, Box<dyn Animal>>
    let mut single_map: std::collections::HashMap<String, Box<dyn Animal>> =
        std::collections::HashMap::new();
    single_map.insert(
        "only_pet".to_string(),
        Box::new(Cat {
            name: "Loner".to_string(),
            color: "Gray".to_string(),
        }),
    );
    let serialized = fory.serialize(&single_map);
    let deserialized: std::collections::HashMap<String, Box<dyn Animal>> =
        fory.deserialize(&serialized).unwrap();
    assert_eq!(deserialized.len(), 1);
    assert_eq!(deserialized.get("only_pet").unwrap().name(), "Loner");
    assert_eq!(deserialized.get("only_pet").unwrap().speak(), "Meow!");
}
