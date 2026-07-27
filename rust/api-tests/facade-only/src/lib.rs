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

//! ```compile_fail
//! use fory::ForyStruct;
//!
//! #[derive(ForyStruct)]
//! #[fory(crate = "fory")]
//! struct Invalid {
//!     value: i32,
//! }
//! ```
//!
//! ```compile_fail
//! use fory::ForyRow;
//!
//! #[derive(ForyRow)]
//! #[fory(crate = "fory")]
//! struct Invalid {
//!     value: i32,
//! }
//! ```
//!
//! ```compile_fail
//! use fory::ForyRow;
//!
//! #[derive(ForyRow)]
//! struct Invalid {
//!     #[fory(skip)]
//!     value: i32,
//! }
//! ```
//!
//! ```compile_fail
//! use fory::ForyStruct;
//! use fory_external_model::ExtraState;
//!
//! #[derive(ForyStruct)]
//! #[fory(target = ExtraState)]
//! struct MissingStateSerializer {
//!     name: String,
//!     age: u32,
//! }
//! ```
//!
//! ```compile_fail
//! use fory::ForyStruct;
//! use fory_external_model::PrivateState;
//!
//! #[derive(ForyStruct)]
//! #[fory(target = PrivateState)]
//! struct PrivateStateSerializer {
//!     name: String,
//!     secret: u32,
//! }
//! ```
//!
//! ```compile_fail
//! use fory::ForyStruct;
//! use fory_external_model::NonExhaustiveUser;
//!
//! #[derive(ForyStruct)]
//! #[fory(target = NonExhaustiveUser)]
//! struct NonExhaustiveUserSerializer {
//!     name: String,
//!     age: u32,
//! }
//! ```
//!
//! ```compile_fail
//! use fory::ForyEnum;
//! use fory_external_model::NonExhaustiveStatus;
//!
//! #[derive(ForyEnum)]
//! #[fory(target = NonExhaustiveStatus)]
//! enum NonExhaustiveStatusSerializer {
//!     Active,
//!     Inactive,
//! }
//! ```
//!
//! ```compile_fail
//! use fory::ForyStruct;
//! use fory_external_model::{Status, User};
//!
//! #[derive(ForyStruct)]
//! #[fory(target = User)]
//! struct UserSerializer {
//!     name: String,
//!     age: u32,
//! }
//!
//! #[derive(ForyStruct)]
//! struct InvalidTarget {
//!     #[fory(with = UserSerializer)]
//!     status: Status,
//! }
//! ```
//!
//! ```compile_fail
//! use fory::ForyStruct;
//! use fory_external_model::User;
//!
//! #[derive(ForyStruct)]
//! #[fory(target = User)]
//! struct UserSerializer {
//!     name: String,
//!     age: u32,
//! }
//!
//! #[derive(ForyStruct)]
//! struct DuplicateTuplePosition {
//!     #[fory(tuple(
//!         element(index = 1, with = UserSerializer),
//!         element(index = 1, with = UserSerializer)
//!     ))]
//!     value: (String, User),
//! }
//! ```
//!
//! ```compile_fail
//! use fory::ForyStruct;
//! use fory_external_model::User;
//!
//! #[derive(ForyStruct)]
//! #[fory(target = Vec<User>)]
//! struct UserContainerSerializer(Vec<User>);
//! ```
//!
//! ```compile_fail
//! use fory::ForyStruct;
//! use fory_external_model::User;
//!
//! #[derive(ForyStruct)]
//! #[fory(target = User, generate_default)]
//! struct InvalidDefaultSerializer {
//!     name: String,
//!     age: u32,
//! }
//! ```
//!
//! ```compile_fail
//! use fory::{ForyStruct, VecSerializer};
//! use fory_external_model::User;
//!
//! #[derive(ForyStruct)]
//! #[fory(target = User)]
//! struct UserSerializer {
//!     name: String,
//!     age: u32,
//! }
//!
//! fn invalid_registration(fory: &mut fory::Fory) {
//!     fory.register::<VecSerializer<UserSerializer>>(100).unwrap();
//! }
//! ```
//!
//! ```compile_fail
//! use fory::{Error, Fory, ReadContext, Serializer, WriteContext};
//! use fory_external_model::ExternalId;
//!
//! struct ExternalIdSerializer;
//!
//! impl Serializer for ExternalIdSerializer {
//!     type Target = ExternalId;
//!
//!     fn write_data(_: &ExternalId, _: &mut WriteContext) -> Result<(), Error> {
//!         Ok(())
//!     }
//!
//!     fn read_data(_: &mut ReadContext) -> Result<ExternalId, Error> {
//!         Ok(ExternalId(0))
//!     }
//! }
//!
//! fn invalid_registration(fory: &mut Fory) {
//!     fory.register::<ExternalIdSerializer>(100).unwrap();
//! }
//! ```
//!
//! ```compile_fail
//! use fory::ForyStruct;
//! use fory_external_model::User;
//!
//! #[derive(ForyStruct)]
//! #[fory(target = User)]
//! struct UserSerializer {
//!     name: String,
//!     age: u32,
//! }
//!
//! #[derive(ForyStruct)]
//! struct ConflictingNode {
//!     #[fory(with = UserSerializer, encoding = fixed)]
//!     user: User,
//! }
//! ```
//!
//! ```compile_fail
//! use fory::ForyStruct;
//! use fory_external_model::{Point, User};
//! use std::collections::HashMap;
//!
//! #[derive(ForyStruct)]
//! #[fory(target = Point)]
//! struct PointSerializer(i32, i32);
//!
//! #[derive(ForyStruct)]
//! #[fory(target = User)]
//! struct UserSerializer {
//!     name: String,
//!     age: u32,
//! }
//!
//! #[derive(ForyStruct)]
//! struct InvalidMapKey {
//!     #[fory(map(
//!         key(with = PointSerializer),
//!         value(with = UserSerializer)
//!     ))]
//!     values: HashMap<Point, User>,
//! }
//! ```
//!
//! ```compile_fail
//! use fory::ForyStruct;
//! use fory_external_model::User;
//!
//! #[derive(ForyStruct)]
//! #[fory(target = User)]
//! struct UserSerializer {
//!     name: String,
//!     age: u32,
//! }
//!
//! #[derive(ForyStruct)]
//! struct OutOfRangeTuplePosition {
//!     #[fory(tuple(element(index = 2, with = UserSerializer)))]
//!     value: (String, User),
//! }
//! ```
//!
//! ```compile_fail
//! use fory::ForyStruct;
//! use fory_external_model::User;
//!
//! #[derive(ForyStruct)]
//! #[fory(target = User)]
//! struct UserSerializer {
//!     name: String,
//!     age: u32,
//! }
//!
//! #[derive(ForyStruct)]
//! struct TupleMetadataOnScalar {
//!     #[fory(tuple(element(index = 0, with = UserSerializer)))]
//!     value: User,
//! }
//! ```
//!
//! ```compile_fail
//! use fory::{ForyStruct, ForyUnion, UnknownCase};
//! use fory_external_model::Value;
//!
//! #[derive(ForyUnion)]
//! #[fory(target = Value<UnknownCase>)]
//! enum MissingUnknownSerializer {
//!     #[fory(default)]
//!     Null,
//!     Text(String),
//!     Named { text: String },
//!     Count(i64),
//! }
//! ```
//!
//! ```compile_fail
//! use fory::{ForyStruct, ForyUnion, UnknownCase};
//! use fory_external_model::Value;
//!
//! #[derive(ForyUnion)]
//! #[fory(target = Value<()>)]
//! enum InvalidUnknownTargetSerializer {
//!     #[fory(default)]
//!     Null,
//!     Text(String),
//!     Named { text: String },
//!     Count(i64),
//!     #[fory(unknown)]
//!     Unknown(UnknownCase),
//! }
//! ```
//!
//! ```compile_fail
//! use fory::ForyStruct;
//! use fory_external_model::User;
//! use std::any::Any;
//!
//! #[derive(ForyStruct)]
//! #[fory(target = User)]
//! struct UserSerializer {
//!     name: String,
//!     age: u32,
//! }
//!
//! #[derive(ForyStruct)]
//! struct InvalidAnySelection {
//!     #[fory(with = UserSerializer)]
//!     value: Box<dyn Any>,
//! }
//! ```

#[cfg(test)]
mod tests {
    use fory::{
        from_row, register_trait_type, to_row, ArcSerializer, ArcWeakSerializer, ArraySerializer,
        BTreeMapSerializer, BTreeSetSerializer, BinaryHeapSerializer, BoxSerializer, Error, Fory,
        ForyEnum, ForyObject, ForyRow, ForyStruct, ForyUnion, HashMapSerializer, HashSetSerializer,
        LinkedListSerializer, MutexSerializer, OptionSerializer, RcSerializer, RcWeakSerializer,
        ReadContext, Reader, RefCellSerializer, Serializer, VecDequeSerializer, VecSerializer,
        WriteContext,
    };
    use fory_external_model::{Command, ExternalId, Key, Marker, Point, Status, User, Value};
    use std::collections::HashMap;
    use std::rc::Rc;
    use std::sync::Arc;

    #[derive(ForyStruct)]
    #[fory(target = User)]
    struct UserSerializer {
        name: String,
        age: u32,
    }

    #[derive(ForyStruct)]
    #[fory(target = Key)]
    struct KeySerializer {
        namespace: String,
        id: u64,
    }

    #[derive(ForyStruct)]
    #[fory(target = Point)]
    struct PointSerializer(i32, i32);

    #[derive(ForyStruct)]
    #[fory(target = Marker)]
    struct MarkerSerializer;

    #[derive(ForyEnum)]
    #[fory(target = Status)]
    enum StatusSerializer {
        Active,
        Inactive,
    }

    #[derive(ForyUnion)]
    #[fory(target = Value<fory::UnknownCase>)]
    enum ValueSerializer {
        #[fory(default)]
        Null,
        Text(String),
        Named {
            text: String,
        },
        Count(i64),
        #[fory(unknown)]
        Unknown(fory::UnknownCase),
    }

    #[derive(ForyUnion)]
    #[fory(target = Command)]
    enum CommandSerializer {
        #[fory(default)]
        Idle,
        Create {
            id: u128,
            label: String,
        },
        Move(i32, i32),
    }

    struct ExternalIdSerializer;

    impl Serializer for ExternalIdSerializer {
        type Target = ExternalId;

        fn write_data(value: &ExternalId, context: &mut WriteContext) -> Result<(), Error> {
            context.writer.write_u128(value.0);
            Ok(())
        }

        fn read_data(context: &mut ReadContext) -> Result<ExternalId, Error> {
            Ok(ExternalId(context.reader.read_u128()?))
        }

        fn default_value(_context: &mut ReadContext) -> Result<ExternalId, Error> {
            Ok(ExternalId(0))
        }
    }

    #[derive(ForyStruct, Debug, PartialEq)]
    struct Envelope {
        #[fory(with = ExternalIdSerializer)]
        id: ExternalId,
        #[fory(with = UserSerializer)]
        user: User,
        #[fory(with = OptionSerializer<UserSerializer>)]
        optional_user: Option<User>,
        #[fory(list(element(with = UserSerializer)))]
        users: Vec<User>,
        #[fory(map(value(with = UserSerializer)))]
        by_name: HashMap<String, User>,
        #[fory(tuple(element(index = 1, with = UserSerializer)))]
        entry: (String, User),
    }

    #[derive(ForyRow)]
    struct RowUser {
        id: i64,
        name: String,
    }

    trait Animal: ForyObject {
        fn name(&self) -> &str;
    }

    impl Animal for User {
        fn name(&self) -> &str {
            &self.name
        }
    }

    register_trait_type!(Animal, User);

    pub trait SyncAnimal: ForyObject + Send + Sync {
        fn name(&self) -> &str;
    }

    impl SyncAnimal for User {
        fn name(&self) -> &str {
            &self.name
        }
    }

    register_trait_type!(pub sync SyncAnimal, User);

    fn register_types(fory: &mut Fory) {
        fory.register::<UserSerializer>(100).unwrap();
        fory.register::<KeySerializer>(101).unwrap();
        fory.register::<PointSerializer>(102).unwrap();
        fory.register::<MarkerSerializer>(103).unwrap();
        fory.register::<StatusSerializer>(104).unwrap();
        fory.register_union::<ValueSerializer>(105).unwrap();
        fory.register::<CommandSerializer>(106).unwrap();
        fory.register_serializer::<ExternalIdSerializer>(107)
            .unwrap();
        fory.register::<Envelope>(108).unwrap();
    }

    fn user(name: &str, age: u32) -> User {
        User {
            name: name.to_string(),
            age,
        }
    }

    #[test]
    fn external_and_manual_roots() {
        let mut fory = Fory::builder().xlang(false).compatible(false).build();
        register_types(&mut fory);

        let value = user("Ada", 37);
        let bytes = fory.serialize_with::<UserSerializer>(&value).unwrap();
        let decoded = fory.deserialize_with::<UserSerializer>(&bytes).unwrap();
        assert_eq!(decoded, value);

        let mut buffer = Vec::new();
        let written = fory
            .serialize_to_with::<UserSerializer>(&mut buffer, &value)
            .unwrap();
        assert_eq!(written, buffer.len());
        let mut reader = Reader::new(&buffer);
        let decoded = fory
            .deserialize_from_with::<UserSerializer>(&mut reader)
            .unwrap();
        assert_eq!(decoded, value);

        let id = ExternalId(0x1020_3040_5060_7080_90a0_b0c0_d0e0_f000);
        let bytes = fory.serialize_with::<ExternalIdSerializer>(&id).unwrap();
        let decoded = fory
            .deserialize_with::<ExternalIdSerializer>(&bytes)
            .unwrap();
        assert_eq!(decoded, id);
    }

    #[test]
    fn carrier_roots_and_field_codegen() {
        let mut fory = Fory::builder().xlang(false).compatible(false).build();
        register_types(&mut fory);

        let first = user("Ada", 37);
        let second = user("Grace", 28);
        let users = vec![first.clone(), second.clone()];

        let bytes = fory
            .serialize_with::<VecSerializer<UserSerializer>>(&users)
            .unwrap();
        let decoded = fory
            .deserialize_with::<VecSerializer<UserSerializer>>(&bytes)
            .unwrap();
        assert_eq!(decoded, users);

        let mut directory = HashMap::new();
        directory.insert("team".to_string(), users.clone());
        type DirectorySerializer = HashMapSerializer<String, VecSerializer<UserSerializer>>;
        let bytes = fory
            .serialize_with::<DirectorySerializer>(&directory)
            .unwrap();
        let decoded = fory
            .deserialize_with::<DirectorySerializer>(&bytes)
            .unwrap();
        assert_eq!(decoded, directory);

        let entry = ("lead".to_string(), first.clone());
        let bytes = fory
            .serialize_with::<fory::Tuple2Serializer<String, UserSerializer>>(&entry)
            .unwrap();
        let decoded = fory
            .deserialize_with::<fory::Tuple2Serializer<String, UserSerializer>>(&bytes)
            .unwrap();
        assert_eq!(decoded, entry);

        let envelope = Envelope {
            id: ExternalId(9),
            user: first.clone(),
            optional_user: Some(second.clone()),
            users: users.clone(),
            by_name: HashMap::from([("lead".to_string(), first.clone())]),
            entry: ("lead".to_string(), first),
        };
        let bytes = fory.serialize(&envelope).unwrap();
        let decoded: Envelope = fory.deserialize(&bytes).unwrap();
        assert_eq!(decoded, envelope);
    }

    #[test]
    fn external_enum_shapes() {
        let mut fory = Fory::builder().xlang(false).compatible(false).build();
        register_types(&mut fory);

        let point = Point(3, 5);
        let bytes = fory.serialize_with::<PointSerializer>(&point).unwrap();
        assert_eq!(
            fory.deserialize_with::<PointSerializer>(&bytes).unwrap(),
            point
        );

        let marker = Marker;
        let bytes = fory.serialize_with::<MarkerSerializer>(&marker).unwrap();
        assert_eq!(
            fory.deserialize_with::<MarkerSerializer>(&bytes).unwrap(),
            marker
        );

        let status = Status::Inactive;
        let bytes = fory.serialize_with::<StatusSerializer>(&status).unwrap();
        assert_eq!(
            fory.deserialize_with::<StatusSerializer>(&bytes).unwrap(),
            status
        );

        let command = Command::Create {
            id: 7,
            label: "build".to_string(),
        };
        let bytes = fory.serialize_with::<CommandSerializer>(&command).unwrap();
        assert_eq!(
            fory.deserialize_with::<CommandSerializer>(&bytes).unwrap(),
            command
        );
    }

    #[test]
    fn application_trait_roots() {
        let mut fory = Fory::builder().xlang(false).compatible(false).build();
        register_types(&mut fory);

        let animal: Box<dyn Animal> = Box::new(user("Rex", 4));
        let bytes = fory.serialize(&animal).unwrap();
        let decoded: Box<dyn Animal> = fory.deserialize(&bytes).unwrap();
        assert_eq!(decoded.name(), "Rex");

        let animal: Rc<dyn Animal> = Rc::new(user("Milo", 5));
        let bytes = fory.serialize_with::<AnimalRcSerializer>(&animal).unwrap();
        let decoded = fory.deserialize_with::<AnimalRcSerializer>(&bytes).unwrap();
        assert_eq!(decoded.name(), "Milo");

        let animal: Arc<dyn SyncAnimal> = Arc::new(user("Luna", 6));
        let bytes = fory
            .serialize_with::<SyncAnimalArcSerializer>(&animal)
            .unwrap();
        let decoded = fory
            .deserialize_with::<SyncAnimalArcSerializer>(&bytes)
            .unwrap();
        assert_eq!(decoded.name(), "Luna");
    }

    #[test]
    fn facade_row_derive_roundtrip() {
        let value = RowUser {
            id: 7,
            name: "Grace".to_string(),
        };
        let row = to_row(&value).unwrap();
        let decoded = from_row::<RowUser>(&row);
        assert_eq!(decoded.id(), 7);
        assert_eq!(decoded.name(), "Grace");
    }

    #[test]
    fn carrier_serializer_exports() {
        fn assert_serializer<S: Serializer>() {}

        assert_serializer::<OptionSerializer<UserSerializer>>();
        assert_serializer::<BoxSerializer<UserSerializer>>();
        assert_serializer::<RcSerializer<UserSerializer>>();
        assert_serializer::<ArcSerializer<UserSerializer>>();
        assert_serializer::<RcWeakSerializer<UserSerializer>>();
        assert_serializer::<ArcWeakSerializer<UserSerializer>>();
        assert_serializer::<RefCellSerializer<UserSerializer>>();
        assert_serializer::<MutexSerializer<UserSerializer>>();
        assert_serializer::<VecSerializer<UserSerializer>>();
        assert_serializer::<VecDequeSerializer<UserSerializer>>();
        assert_serializer::<LinkedListSerializer<UserSerializer>>();
        assert_serializer::<HashSetSerializer<UserSerializer>>();
        assert_serializer::<BTreeSetSerializer<UserSerializer>>();
        assert_serializer::<BinaryHeapSerializer<UserSerializer>>();
        assert_serializer::<ArraySerializer<UserSerializer, 2>>();
        assert_serializer::<HashMapSerializer<KeySerializer, UserSerializer>>();
        assert_serializer::<BTreeMapSerializer<KeySerializer, UserSerializer>>();

        assert_serializer::<fory::Tuple1Serializer<UserSerializer>>();
        assert_serializer::<fory::Tuple2Serializer<String, UserSerializer>>();
        assert_serializer::<fory::Tuple3Serializer<String, String, UserSerializer>>();
        assert_serializer::<fory::Tuple4Serializer<String, String, String, UserSerializer>>();
        assert_serializer::<fory::Tuple5Serializer<String, String, String, String, UserSerializer>>(
        );
        assert_serializer::<
            fory::Tuple6Serializer<String, String, String, String, String, UserSerializer>,
        >();
        assert_serializer::<
            fory::Tuple7Serializer<String, String, String, String, String, String, UserSerializer>,
        >();
        assert_serializer::<
            fory::Tuple8Serializer<
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory::Tuple9Serializer<
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory::Tuple10Serializer<
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory::Tuple11Serializer<
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory::Tuple12Serializer<
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory::Tuple13Serializer<
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory::Tuple14Serializer<
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory::Tuple15Serializer<
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory::Tuple16Serializer<
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory::Tuple17Serializer<
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory::Tuple18Serializer<
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory::Tuple19Serializer<
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory::Tuple20Serializer<
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory::Tuple21Serializer<
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory::Tuple22Serializer<
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                String,
                UserSerializer,
            >,
        >();
    }
}
