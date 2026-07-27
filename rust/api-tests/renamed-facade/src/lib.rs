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

#[cfg(test)]
mod tests {
    use fory_external_model::{ExternalId, User};
    use fory_facade::{
        from_row, register_trait_type, to_row, ArcSerializer, ArcWeakSerializer, ArraySerializer,
        BTreeMapSerializer, BTreeSetSerializer, BinaryHeapSerializer, BoxSerializer, Error, Fory,
        ForyEnum, ForyObject, ForyRow, ForyStruct, ForyUnion, HashMapSerializer, HashSetSerializer,
        LinkedListSerializer, MutexSerializer, OptionSerializer, RcSerializer, RcWeakSerializer,
        ReadContext, RefCellSerializer, Serializer, VecDequeSerializer, VecSerializer,
        WriteContext,
    };
    use std::collections::HashMap;
    use std::rc::Rc;

    #[derive(ForyStruct, Debug, PartialEq)]
    struct RenamedValue {
        value: String,
    }

    #[derive(ForyEnum, Debug, Default, PartialEq)]
    enum RenamedStatus {
        #[default]
        Ready,
        Done,
    }

    #[derive(ForyUnion, Debug, PartialEq)]
    enum RenamedEvent {
        #[fory(default)]
        Empty,
        Message(String),
        Pair {
            key: String,
            value: i32,
        },
    }

    #[derive(ForyStruct)]
    #[fory(target = User)]
    struct UserSerializer {
        name: String,
        age: u32,
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
    struct RenamedEnvelope {
        #[fory(with = ExternalIdSerializer)]
        id: ExternalId,
        #[fory(with = UserSerializer)]
        user: User,
        #[fory(list(element(with = UserSerializer)))]
        users: Vec<User>,
        #[fory(map(value(with = UserSerializer)))]
        by_name: HashMap<String, User>,
        #[fory(tuple(element(index = 1, with = UserSerializer)))]
        entry: (String, User),
    }

    #[derive(ForyRow)]
    struct RenamedRow {
        id: i64,
    }

    pub trait RenamedAnimal: ForyObject {
        fn name(&self) -> &str;
    }

    impl RenamedAnimal for User {
        fn name(&self) -> &str {
            &self.name
        }
    }

    register_trait_type!(pub RenamedAnimal, User);

    fn register_types(fory: &mut Fory) {
        fory.register::<RenamedValue>(200).unwrap();
        fory.register::<RenamedStatus>(201).unwrap();
        fory.register::<RenamedEvent>(202).unwrap();
        fory.register::<UserSerializer>(203).unwrap();
        fory.register_serializer::<ExternalIdSerializer>(204)
            .unwrap();
        fory.register::<RenamedEnvelope>(205).unwrap();
    }

    fn user(name: &str, age: u32) -> User {
        User {
            name: name.to_string(),
            age,
        }
    }

    #[test]
    fn renamed_facade_derives() {
        let mut fory = Fory::builder().xlang(false).compatible(false).build();
        register_types(&mut fory);

        let value = RenamedValue {
            value: "renamed".to_string(),
        };
        let bytes = fory.serialize(&value).unwrap();
        assert_eq!(fory.deserialize::<RenamedValue>(&bytes).unwrap(), value);

        let status = RenamedStatus::Done;
        let bytes = fory.serialize(&status).unwrap();
        assert_eq!(fory.deserialize::<RenamedStatus>(&bytes).unwrap(), status);

        let event = RenamedEvent::Pair {
            key: "answer".to_string(),
            value: 42,
        };
        let bytes = fory.serialize(&event).unwrap();
        assert_eq!(fory.deserialize::<RenamedEvent>(&bytes).unwrap(), event);
    }

    #[test]
    fn renamed_external_roots_and_fields() {
        let mut fory = Fory::builder().xlang(false).compatible(false).build();
        register_types(&mut fory);

        let ada = user("Ada", 37);
        let bytes = fory.serialize_with::<UserSerializer>(&ada).unwrap();
        assert_eq!(
            fory.deserialize_with::<UserSerializer>(&bytes).unwrap(),
            ada
        );

        let users = vec![ada.clone(), user("Grace", 28)];
        let bytes = fory
            .serialize_with::<VecSerializer<UserSerializer>>(&users)
            .unwrap();
        assert_eq!(
            fory.deserialize_with::<VecSerializer<UserSerializer>>(&bytes)
                .unwrap(),
            users
        );

        type DirectorySerializer = HashMapSerializer<String, VecSerializer<UserSerializer>>;
        let directory = HashMap::from([("team".to_string(), users.clone())]);
        let bytes = fory
            .serialize_with::<DirectorySerializer>(&directory)
            .unwrap();
        assert_eq!(
            fory.deserialize_with::<DirectorySerializer>(&bytes)
                .unwrap(),
            directory
        );

        type EntrySerializer = fory_facade::Tuple2Serializer<String, UserSerializer>;
        let entry = ("lead".to_string(), ada.clone());
        let bytes = fory.serialize_with::<EntrySerializer>(&entry).unwrap();
        assert_eq!(
            fory.deserialize_with::<EntrySerializer>(&bytes).unwrap(),
            entry
        );

        let envelope = RenamedEnvelope {
            id: ExternalId(9),
            user: ada.clone(),
            users,
            by_name: HashMap::from([("lead".to_string(), ada.clone())]),
            entry: ("lead".to_string(), ada),
        };
        let bytes = fory.serialize(&envelope).unwrap();
        assert_eq!(
            fory.deserialize::<RenamedEnvelope>(&bytes).unwrap(),
            envelope
        );
    }

    #[test]
    fn renamed_manual_and_trait_roots() {
        let mut fory = Fory::builder().xlang(false).compatible(false).build();
        register_types(&mut fory);

        let id = ExternalId(64);
        let bytes = fory.serialize_with::<ExternalIdSerializer>(&id).unwrap();
        assert_eq!(
            fory.deserialize_with::<ExternalIdSerializer>(&bytes)
                .unwrap(),
            id
        );

        let animal: Box<dyn RenamedAnimal> = Box::new(user("Spot", 4));
        let bytes = fory.serialize(&animal).unwrap();
        let decoded: Box<dyn RenamedAnimal> = fory.deserialize(&bytes).unwrap();
        assert_eq!(decoded.name(), "Spot");

        let animal: Rc<dyn RenamedAnimal> = Rc::new(user("Milo", 5));
        let bytes = fory
            .serialize_with::<RenamedAnimalRcSerializer>(&animal)
            .unwrap();
        let decoded = fory
            .deserialize_with::<RenamedAnimalRcSerializer>(&bytes)
            .unwrap();
        assert_eq!(decoded.name(), "Milo");
    }

    #[test]
    fn renamed_facade_row_derive() {
        let row = to_row(&RenamedRow { id: 9 }).unwrap();
        let decoded = from_row::<RenamedRow>(&row);
        assert_eq!(decoded.id(), 9);
    }

    #[test]
    fn renamed_carrier_exports() {
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
        assert_serializer::<HashMapSerializer<UserSerializer, UserSerializer>>();
        assert_serializer::<BTreeMapSerializer<UserSerializer, UserSerializer>>();
        assert_serializer::<fory_facade::Tuple1Serializer<UserSerializer>>();
        assert_serializer::<fory_facade::Tuple2Serializer<UserSerializer, UserSerializer>>();
        assert_serializer::<
            fory_facade::Tuple3Serializer<UserSerializer, UserSerializer, UserSerializer>,
        >();
        assert_serializer::<
            fory_facade::Tuple4Serializer<
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory_facade::Tuple5Serializer<
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory_facade::Tuple6Serializer<
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory_facade::Tuple7Serializer<
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory_facade::Tuple8Serializer<
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory_facade::Tuple9Serializer<
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory_facade::Tuple10Serializer<
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory_facade::Tuple11Serializer<
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory_facade::Tuple12Serializer<
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory_facade::Tuple13Serializer<
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory_facade::Tuple14Serializer<
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory_facade::Tuple15Serializer<
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory_facade::Tuple16Serializer<
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory_facade::Tuple17Serializer<
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory_facade::Tuple18Serializer<
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory_facade::Tuple19Serializer<
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory_facade::Tuple20Serializer<
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory_facade::Tuple21Serializer<
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
            >,
        >();
        assert_serializer::<
            fory_facade::Tuple22Serializer<
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
                UserSerializer,
            >,
        >();
    }
}
