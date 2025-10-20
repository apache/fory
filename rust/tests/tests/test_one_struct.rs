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
use fory_derive::ForyObject;
use std::collections::HashMap;

#[test]
fn test_simple() {
    // a single test for cargo expand and analysis: `cargo expand --test test_simple_struct 2>&1 > expanded.rs`
    // &["f7", "last", "f2", "f5", "f3", "f6", "f1"]
    #[derive(ForyObject, Debug)]
    #[fory_debug]
    struct Animal1 {
        f1: HashMap<i8, Vec<i8>>,
        f2: String,
        f3: Vec<i8>,
        f5: String,
        f6: Vec<i8>,
        f7: i8,
        last: i8,
    }

    // &["f7", "f5", "last", "f4", "f3", "f6", "f1"]
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
    let mut fory1 = Fory::default().compatible(true);
    let mut fory2 = Fory::default().compatible(true);
    fory1.register::<Animal1>(999).unwrap();
    fory2.register::<Animal2>(999).unwrap();
    let animal: Animal1 = Animal1 {
        f1: HashMap::from([(1, vec![2])]),
        f2: String::from("hello"),
        f3: vec![1, 2, 3],
        f5: String::from("f5"),
        f6: vec![42],
        f7: 43,
        last: 44,
    };

    let bin = fory1.serialize(&animal).unwrap();
    let obj: Animal2 = fory2.deserialize(&bin).unwrap();
    assert_eq!(animal.f1, obj.f1);
    assert_eq!(animal.f3, obj.f3);
    assert_eq!(obj.f4, String::default());
    assert_eq!(obj.f5, i8::default());
    assert_eq!(obj.f6, Vec::<i16>::default());
    assert_eq!(obj.f7, i16::default());
    assert_eq!(animal.last, obj.last);
}
