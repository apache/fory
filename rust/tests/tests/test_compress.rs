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
pub fn test_i32() {
    #[derive(ForyObject, Debug, PartialEq)]
    struct Item {
        f1: i32,
        f2: [i32; 1],
    }
    #[derive(ForyObject, Debug, PartialEq)]
    #[fory(compress_int)]
    struct ItemCompressed {
        f1: i32,
        f2: [i32; 1],
    }
    let f1: i32 = 13;
    // `primitive_array` is not related to compression; just for testing.
    let primitive_array: [i32; 1] = [100];
    let item = Item {
        f1,
        f2: primitive_array,
    };
    let item_compressed = ItemCompressed {
        f1,
        f2: primitive_array,
    };
    for compress_int in [true, false] {
        let mut fory = Fory::default();
        if compress_int {
            fory.register::<ItemCompressed>(100).unwrap();
        } else {
            fory.register::<Item>(100).unwrap();
        };
        let mut buf = Vec::new();
        fory.serialize_to(&f1, &mut buf).unwrap();
        fory.serialize_to(&primitive_array, &mut buf).unwrap();
        if compress_int {
            fory.serialize_to(&item_compressed, &mut buf).unwrap();
        } else {
            fory.serialize_to(&item, &mut buf).unwrap();
        }

        let mut reader = Reader::new(buf.as_slice());
        let new_f1: i32 = fory.deserialize_from(&mut reader).unwrap();
        assert_eq!(f1, new_f1);
        let new_primitive_array: [i32; 1] = fory.deserialize_from(&mut reader).unwrap();
        assert_eq!(primitive_array, new_primitive_array);
        if compress_int {
            let new_item: ItemCompressed = fory.deserialize_from(&mut reader).unwrap();
            assert_eq!(item_compressed, new_item);
        } else {
            let new_item: Item = fory.deserialize_from(&mut reader).unwrap();
            assert_eq!(item, new_item);
        }
    }
}

#[test]
fn test_inconsistent() {
    #[derive(ForyObject)]
    #[fory(compress_int = true)]
    struct Item1 {}

    #[derive(ForyObject)]
    #[fory(compress_int = false)]
    struct Item2 {}

    let mut fory = Fory::default();
    fory.register::<Item1>(100).unwrap();
    assert!(fory.register::<Item2>(101).is_err());
}

#[test]
fn test_inconsistent_with_enum() {
    #[derive(ForyObject)]
    #[fory(compress_int = true)]
    struct Item1 {}

    #[derive(ForyObject)]
    #[fory(compress_int = false)]
    enum Item2 {
        Red,
    }

    let mut fory = Fory::default();
    fory.register::<Item1>(100).unwrap();
    assert!(fory.register::<Item2>(101).is_err());
}
