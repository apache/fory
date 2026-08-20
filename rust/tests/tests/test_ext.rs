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
use fory_core::serializer::Serializer;
use fory_core::{ReadContext, WriteContext};
use fory_derive::ForyStruct;

#[test]
#[allow(dead_code)]
fn test_duplicate_impl() {
    #[derive(ForyStruct, Debug, PartialEq)]
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
    #[derive(Debug, PartialEq)]
    struct Item {
        f1: i32,
        f2: i8,
    }

    impl Serializer for Item {
        type Target = Self;

        fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
            context.writer.write_i32(value.f1);
            Ok(())
        }

        fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
            Ok(Self {
                f1: context.reader.read_i32()?,
                f2: 0,
            })
        }
    }
    let mut fory = Fory::builder().compatible(true).xlang(true).build();
    let item = Item { f1: 1, f2: 2 };
    fory.register_serializer::<Item>(100).unwrap();
    let bytes = fory.serialize(&item).unwrap();
    let new_item: Item = fory.deserialize(&bytes).unwrap();
    assert_eq!(new_item.f1, item.f1);
    assert_eq!(new_item.f2, 0);
}
