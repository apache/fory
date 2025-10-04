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
use fory_core::types::Mode;
use fory_derive::{fory_trait, Fory};

#[fory_trait]
trait Animal {
    fn speak(&self) -> String;
    fn name(&self) -> &str;
}

#[derive(Fory, Debug, Clone)]
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

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

#[derive(Fory, Debug, Clone)]
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

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

register_trait_type!(Animal, Dog, Cat);

#[derive(Fory)]
struct Zoo {
    star_animal: Box<dyn Animal>,
}

fn main() {
    let mut fory = Fory::default().mode(Mode::Compatible);
    fory.register::<Dog>(8001);
    fory.register::<Cat>(8002);
    fory.register::<Zoo>(8003);

    let zoo = Zoo {
        star_animal: Box::new(Dog {
            name: "Buddy".to_string(),
            breed: "Golden Retriever".to_string(),
        }),
    };

    println!("Original zoo's star animal: {}", zoo.star_animal.name());
    println!("Says: {}", zoo.star_animal.speak());

    let serialized = fory.serialize(&zoo);
    println!("\nSerialized {} bytes", serialized.len());

    let deserialized: Zoo = fory.deserialize(&serialized).unwrap();

    println!("\nDeserialized zoo's star animal: {}", deserialized.star_animal.name());
    println!("Says: {}", deserialized.star_animal.speak());

    let zoo_cat = Zoo {
        star_animal: Box::new(Cat {
            name: "Whiskers".to_string(),
            color: "Orange".to_string(),
        }),
    };

    println!("\n\nOriginal zoo's star animal: {}", zoo_cat.star_animal.name());
    println!("Says: {}", zoo_cat.star_animal.speak());

    let serialized_cat = fory.serialize(&zoo_cat);
    let deserialized_cat: Zoo = fory.deserialize(&serialized_cat).unwrap();

    println!("\nDeserialized zoo's star animal: {}", deserialized_cat.star_animal.name());
    println!("Says: {}", deserialized_cat.star_animal.speak());
}
