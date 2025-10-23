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

#[test]
fn test_nested_struct_register_order() {
    #[derive(ForyObject, Debug, PartialEq)]
    struct Data1 {
        value: i32,
        data2: Data2,
    }

    #[derive(ForyObject, Debug, PartialEq)]
    struct Data2 {
        value: i32,
    }

    let mut fory = Fory::default();
    // outer struct registered first. building fields info should be executed lazily,
    // otherwise the inner struct won't be found.
    fory.register::<Data1>(100).unwrap();
    fory.register::<Data2>(101).unwrap();
    let data = Data1 {
        value: 42,
        data2: Data2 { value: 24 },
    };
    let bytes = fory.serialize(&data).unwrap();
    let result: Data1 = fory.deserialize(&bytes).unwrap();
    assert_eq!(data, result);
}
