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
use fory_derive::Fory;
use std::collections::HashMap;

// RUSTFLAGS="-Awarnings" cargo expand -p fory-tests --test test_compatible
#[test]
fn simple() {
    #[derive(Fory, Debug)]
    struct Animal1 {
        f1: HashMap<i8, Vec<i8>>,
        f2: String,
        f3: Vec<i8>,
        // f4: String,
        f5: String,
        // f6: Vec<i8>,
    }

    #[derive(Fory, Debug)]
    struct Animal2 {
        f1: HashMap<i8, Vec<i8>>,
        // f2: String,
        f3: Vec<i8>,
        f4: String,
        f5: i8,
        // f6: Vec<i16>,
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
        // f6: vec![42]
    };
    let bin = fory1.serialize(&animal);
    let obj: Animal2 = fory2.deserialize(&bin).unwrap();

    assert_eq!(animal.f1, obj.f1);
    assert_eq!(animal.f3, obj.f3);
    assert_eq!(obj.f4, String::default());
    assert_eq!(obj.f5, i8::default());
}

#[test]
fn option() {
    #[derive(Fory, Debug)]
    struct Animal {
        f1: Option<String>,
        f2: Option<String>,
    }
    let mut fory1 = Fory::default().mode(Compatible);
    fory1.register::<Animal>(999);
    let animal: Animal = Animal {
        f1: Some(String::from("foo")),
        f2: None,
    };
    let _bin = fory1.serialize(&animal);
    // todo
    // let obj: crate::Animal2 = fory2.deserialize(&bin).unwrap();
}

// #[test]
// fn not_impl_default() {
//     #[derive(Fory, Debug)]
//     struct Person1 {
//         // f1: Box<dyn Any>,
//         f2: String,
//     }
//
//     #[derive(Fory, Debug)]
//     struct Person2 {
//         f1: Box<dyn Any>,
//         f2: String,
//     }
//
//     let mut fory1 = Fory::default().mode(Compatible);
//     let mut fory2 = Fory::default().mode(Compatible);
//     fory1.register::<Person1>(999);
//     fory2.register::<Person2>(999);
//     let person: Person1 = Person1 {
//         f2: String::from("hello"),
//     };
//     let bin = fory1.serialize(&person);
//     let obj: Person2 = fory2.deserialize(&bin).unwrap();
//     assert_eq!(person.f2, obj.f2);
//     // assert_eq!(obj.f1, obj.f1);
// }
