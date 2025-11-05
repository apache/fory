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

use fory_core::{Fory, Reader};
use fory_derive::ForyObject;

#[test]
pub fn basic() {
    #[derive(ForyObject, Debug, PartialEq)]
    struct Item {
        f1: i32,
    }
    let f1: i32 = 13;
    let item = Item { f1 };
    for compress_int in [true, false] {
        let mut fory = Fory::default().compress_int(compress_int);
        fory.register::<Item>(100).unwrap();
        let mut buf = Vec::new();
        fory.serialize_to(&f1, &mut buf).unwrap();
        fory.serialize_to(&item, &mut buf).unwrap();

        let mut reader = Reader::new(buf.as_slice());
        let new_f1: i32 = fory.deserialize_from(&mut reader).unwrap();
        let new_item: Item = fory.deserialize_from(&mut reader).unwrap();
        assert_eq!(f1, new_f1);
        assert_eq!(item, new_item);
    }
}
