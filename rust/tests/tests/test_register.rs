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

use fory_core::Fory;
use fory_derive::ForyObject;

// RUSTFLAGS="-Awarnings" cargo expand -p tests --test test_register

#[test]
fn test_register() {
    #[derive(ForyObject, Debug)]
    struct TypeA {}

    #[derive(ForyObject, Debug)]
    struct TypeB {
        a: TypeA,
    }
    let mut fory = Fory::default();
    fory.register::<TypeB>(101).unwrap();
    fory.register::<TypeA>(100).unwrap();

    let b = TypeB { a: TypeA {} };
    let bin = fory.serialize(&b).unwrap();
    let deserialized: TypeB = fory.deserialize(&bin).unwrap();
    println!("{:?}", deserialized);
}

#[test]
fn test_not_register() {
    #[derive(ForyObject, Debug)]
    struct TypeA {}

    #[derive(ForyObject, Debug)]
    struct TypeB {
        a: TypeA,
    }
    let mut fory = Fory::default();
    fory.register::<TypeB>(101).unwrap();

    let b = TypeB { a: TypeA {} };
    assert!(fory.serialize(&b).is_err());
}
