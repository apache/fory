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

use fory_core::error::Error;
use fory_core::fory::Fory;
use fory_core::resolver::context::{ReadContext, WriteContext};
use fory_core::serializer::{ForyDefault, Serializer};
use fory_core::types::Mode::Compatible;
use fory_derive::ForyObject;

#[test]
#[allow(dead_code)]
fn test_duplicate_impl() {
    #[derive(ForyObject, Debug, PartialEq)]
    struct Item1 {
        f1: i32,
    }
    trait OtherSerializer<T> {
        fn read() -> Result<T, Error>;
        fn write(&self);
    }
    impl OtherSerializer<Item1> for Item1 {
        fn read() -> Result<Item1, Error> {
            todo!()
        }

        fn write(&self) {
            todo!()
        }
    }
}

#[test]
fn test_use() {
    use fory_core::fory::{read_data, write_data};
    #[derive(Debug, PartialEq)]
    struct Item {
        f1: i32,
        f2: i8,
    }

    impl ForyDefault for Item {
        fn fory_default() -> Self {
            Self { f1: 0, f2: 0 }
        }
    }

    impl Serializer for Item {
        fn fory_write_data(&self, fory: &Fory, context: &mut WriteContext, is_field: bool) {
            write_data(&self.f1, fory, context, is_field);
        }

        fn fory_read_data(
            fory: &Fory,
            context: &mut ReadContext,
            is_field: bool,
        ) -> Result<Self, Error> {
            Ok(Self {
                f1: read_data(fory, context, is_field)?,
                f2: 0,
            })
        }

        fn fory_type_id_dyn(&self, fory: &Fory) -> u32 {
            Self::fory_get_type_id(fory)
        }

        fn as_any(&self) -> &dyn std::any::Any {
            self
        }
    }
    let mut fory = Fory::default().mode(Compatible).xlang(true);
    let item = Item { f1: 1, f2: 2 };
    fory.register_serializer::<Item>(100);
    let bytes = fory.serialize(&item);
    let new_item: Item = fory.deserialize(&bytes).unwrap();
    assert_eq!(new_item.f1, item.f1);
    assert_eq!(new_item.f2, 0);
}
