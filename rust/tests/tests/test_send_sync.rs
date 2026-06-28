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

#![allow(dead_code)]

use fory_core::fory::Fory;
use fory_core::{
    read_data, write_data, Config, Error, ForyDefault, ReadContext, Serializer, TypeResolver,
    WriteContext,
};
use fory_derive::{ForyEnum, ForyStruct, ForyUnion};
use std::{
    any::Any,
    collections::{HashMap, HashSet, LinkedList},
    fmt::Debug,
    rc::Rc,
    sync::Arc,
};

fn assert_arc_any_roundtrip<T>(fory: &Fory, value: T)
where
    T: 'static + Clone + Debug + PartialEq + Send + Sync,
{
    let wrapped: Arc<dyn Any + Send + Sync> = Arc::new(value.clone());
    let bytes = fory.serialize(&wrapped).unwrap();
    let decoded: Arc<dyn Any + Send + Sync> = fory.deserialize(&bytes).unwrap();
    assert_eq!(decoded.downcast_ref::<T>().unwrap(), &value);
}

fn assert_arc_any_unsupported<T>(fory: &Fory, value: T)
where
    T: 'static + Send + Sync,
{
    let wrapped: Arc<dyn Any + Send + Sync> = Arc::new(value);
    let err = match fory.serialize(&wrapped) {
        Ok(bytes) => {
            let result: Result<Arc<dyn Any + Send + Sync>, _> = fory.deserialize(&bytes);
            match result {
                Ok(_) => panic!("expected direct generic container payload to be unsupported"),
                Err(err) => err,
            }
        }
        Err(err) => err,
    };
    let message = err.to_string();
    assert!(
        message.contains("top-level erased Any")
            || message.contains("Erased Any payloads require")
            || message.contains("cannot be represented as Arc<dyn Any + Send + Sync>"),
        "unexpected error: {err}"
    );
}

fn assert_send_sync_reader_unsupported<T>()
where
    T: Serializer + ForyDefault,
{
    let mut context = ReadContext::new(TypeResolver::default(), Config::default());
    let result = T::fory_read_data_as_send_sync_any(&mut context);
    let err = match result {
        Ok(_) => panic!("expected send-sync Any reader to be unsupported"),
        Err(err) => err,
    };
    let message = err.to_string();
    assert!(
        message.contains("cannot be represented as Arc<dyn Any + Send + Sync>"),
        "unexpected error: {err}"
    );
}

#[test]
fn test_builtin_send_sync_arc_any_reads() {
    let fory = Fory::builder().xlang(false).compatible(false).build();

    assert_arc_any_roundtrip(&fory, 42_i32);
    assert_arc_any_roundtrip(&fory, true);
    assert_arc_any_roundtrip(&fory, "thread-safe".to_string());
}

#[test]
fn test_derived_send_sync_arc_any_read() {
    #[derive(ForyStruct, Clone, Debug, PartialEq)]
    struct Value {
        name: String,
        count: i32,
    }

    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register::<Value>(900).unwrap();

    assert_arc_any_roundtrip(
        &fory,
        Value {
            name: "derived".to_string(),
            count: 7,
        },
    );
}

#[test]
fn compatible_struct_arc_any_read() {
    #[derive(ForyStruct, Clone, Debug, PartialEq)]
    struct ValueV1 {
        #[fory(id = 0)]
        name: String,
        #[fory(id = 1)]
        count: i32,
    }

    #[derive(ForyStruct, Clone, Debug, PartialEq)]
    struct ValueV2 {
        #[fory(id = 0)]
        name: String,
        #[fory(id = 1)]
        count: i32,
        #[fory(id = 2)]
        label: String,
    }

    let mut writer = Fory::builder().xlang(false).compatible(true).build();
    writer.register::<ValueV1>(909).unwrap();

    let mut reader = Fory::builder().xlang(false).compatible(true).build();
    reader.register::<ValueV2>(909).unwrap();

    let value: Arc<dyn Any + Send + Sync> = Arc::new(ValueV1 {
        name: "compatible".to_string(),
        count: 11,
    });
    let bytes = writer.serialize(&value).unwrap();
    let decoded: Arc<dyn Any + Send + Sync> = reader.deserialize(&bytes).unwrap();
    let value = decoded.downcast_ref::<ValueV2>().unwrap();

    assert_eq!(value.name, "compatible");
    assert_eq!(value.count, 11);
    assert_eq!(value.label, String::default());
}

#[test]
fn wrapped_container_arc_any_read() {
    #[derive(ForyStruct, Clone, Debug, PartialEq)]
    struct IntList {
        values: Vec<i32>,
    }

    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register::<IntList>(901).unwrap();

    assert_arc_any_roundtrip(
        &fory,
        IntList {
            values: vec![1, 2, 3],
        },
    );
}

#[test]
fn generic_containers_rejected_arc_any() {
    let fory = Fory::builder().xlang(false).compatible(false).build();

    assert_arc_any_unsupported(&fory, vec![1_i32, 2, 3]);
    assert_arc_any_unsupported(&fory, LinkedList::from([1_i32, 2, 3]));
    assert_arc_any_unsupported(&fory, HashSet::from([1_i32, 2, 3]));
    assert_arc_any_unsupported(
        &fory,
        HashMap::from([("one".to_string(), 1_i32), ("two".to_string(), 2)]),
    );
}

#[test]
fn test_auto_send_sync_struct() {
    #[derive(ForyStruct, Clone, Debug, PartialEq)]
    struct Value {
        name: String,
    }

    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register::<Value>(902).unwrap();

    assert_arc_any_roundtrip(
        &fory,
        Value {
            name: "auto".to_string(),
        },
    );
}

#[test]
fn non_send_sync_carrier_reader_unsupported() {
    assert_send_sync_reader_unsupported::<Rc<dyn Any>>();
}

#[test]
fn manual_serializer_arc_any_read() {
    #[derive(Clone, Debug, PartialEq)]
    struct ManualValue {
        id: i32,
        name: String,
    }

    impl ForyDefault for ManualValue {
        fn fory_default() -> Self {
            Self {
                id: 0,
                name: String::new(),
            }
        }
    }

    impl Serializer for ManualValue {
        fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
            write_data(&self.id, context)?;
            write_data(&self.name, context)
        }

        fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
            Ok(Self {
                id: read_data(context)?,
                name: read_data(context)?,
            })
        }

        fn fory_read_data_as_send_sync_any(
            context: &mut ReadContext,
        ) -> Result<Box<dyn Any + Send + Sync>, Error> {
            Ok(fory_core::serializer::box_send_sync(Self::fory_read_data(
                context,
            )?))
        }

        fn fory_type_id_dyn(
            &self,
            type_resolver: &TypeResolver,
        ) -> Result<fory_core::TypeId, Error> {
            Self::fory_get_type_id(type_resolver)
        }

        fn as_any(&self) -> &dyn Any {
            self
        }
    }

    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register_serializer::<ManualValue>(910).unwrap();

    assert_arc_any_roundtrip(
        &fory,
        ManualValue {
            id: 7,
            name: "manual".to_string(),
        },
    );
}

#[test]
fn test_nested_custom_default() {
    #[derive(ForyStruct, Clone, Debug, PartialEq)]
    struct Leaf {
        name: String,
    }

    #[derive(ForyStruct, Clone, Debug, PartialEq)]
    struct Value {
        leaf: Leaf,
    }

    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register::<Leaf>(902).unwrap();
    fory.register::<Value>(903).unwrap();

    assert_arc_any_roundtrip(
        &fory,
        Value {
            leaf: Leaf {
                name: "nested".to_string(),
            },
        },
    );
}

#[test]
fn test_known_non_send_sync_struct() {
    #[derive(ForyStruct)]
    struct Value {
        name: Rc<String>,
    }

    assert_send_sync_reader_unsupported::<Value>();
}

#[test]
fn test_send_sync_union() {
    #[derive(ForyUnion, Clone, Debug, PartialEq)]
    enum Event {
        #[fory(unknown)]
        Unknown(fory_core::UnknownCase),
        #[fory(id = 0, default)]
        Value(String),
    }

    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register_union::<Event>(905).unwrap();

    assert_arc_any_roundtrip(&fory, Event::Value("union".to_string()));
}

#[test]
fn test_send_sync_enum() {
    #[derive(ForyEnum, Clone, Debug, Default, PartialEq)]
    enum Status {
        #[default]
        Active,
        Inactive,
    }

    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register::<Status>(906).unwrap();

    assert_arc_any_roundtrip(&fory, Status::Inactive);
}

#[test]
fn compatible_union_arc_any_read() {
    #[derive(ForyUnion, Clone, Debug, PartialEq)]
    enum Event {
        #[fory(unknown)]
        Unknown(fory_core::UnknownCase),
        #[fory(id = 0, default)]
        Value(String),
    }

    let mut fory = Fory::builder().xlang(false).compatible(true).build();
    fory.register_union::<Event>(907).unwrap();

    assert_arc_any_roundtrip(&fory, Event::Value("compatible".to_string()));
}

#[test]
fn compatible_enum_arc_any_read() {
    #[derive(ForyEnum, Clone, Debug, Default, PartialEq)]
    enum Status {
        #[default]
        Active,
        Inactive,
    }

    let mut fory = Fory::builder().xlang(false).compatible(true).build();
    fory.register::<Status>(908).unwrap();

    assert_arc_any_roundtrip(&fory, Status::Inactive);
}
