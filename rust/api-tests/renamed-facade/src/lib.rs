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
    use fory_facade::{
        from_row, register_trait_type, to_row, Error, Fory, ForyDefault, ForyEnum, ForyRow,
        ForyStruct, ForyUnion, ReadContext, Serializer, TypeId, TypeResolver, WriteContext,
    };

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

    #[derive(ForyRow)]
    struct RenamedRow {
        id: i64,
    }

    pub(crate) trait RenamedAnimal: Serializer {
        fn name(&self) -> &str;
    }

    #[derive(ForyStruct, Debug, PartialEq)]
    struct RenamedDog {
        name: String,
    }

    impl RenamedAnimal for RenamedDog {
        fn name(&self) -> &str {
            &self.name
        }
    }

    register_trait_type!(RenamedAnimal, RenamedDog);

    #[derive(Debug, PartialEq)]
    struct RenamedManual {
        value: i32,
    }

    impl ForyDefault for RenamedManual {
        fn fory_default() -> Self {
            Self { value: 0 }
        }
    }

    impl Serializer for RenamedManual {
        fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
            context.writer.write_i32(self.value);
            Ok(())
        }

        fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error>
        where
            Self: Sized + ForyDefault,
        {
            Ok(Self {
                value: context.reader.read_i32()?,
            })
        }

        fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<TypeId, Error> {
            Self::fory_get_type_id(type_resolver)
        }

        fn as_any(&self) -> &dyn std::any::Any {
            self
        }
    }

    #[test]
    fn renamed_facade_object_derive_roundtrip() {
        let mut fory = Fory::builder().xlang(false).compatible(false).build();
        fory.register::<RenamedValue>(200).unwrap();
        fory.register::<RenamedStatus>(201).unwrap();
        fory.register::<RenamedEvent>(202).unwrap();

        let value = RenamedValue {
            value: "renamed".to_string(),
        };
        let bytes = fory.serialize(&value).unwrap();
        let decoded: RenamedValue = fory.deserialize(&bytes).unwrap();

        assert_eq!(decoded, value);

        let status = RenamedStatus::Done;
        let bytes = fory.serialize(&status).unwrap();
        let decoded: RenamedStatus = fory.deserialize(&bytes).unwrap();
        assert_eq!(decoded, status);

        let event = RenamedEvent::Pair {
            key: "answer".to_string(),
            value: 42,
        };
        let bytes = fory.serialize(&event).unwrap();
        let decoded: RenamedEvent = fory.deserialize(&bytes).unwrap();
        assert_eq!(decoded, event);
    }

    #[test]
    fn renamed_facade_row_derive_roundtrip() {
        let row = to_row(&RenamedRow { id: 9 }).unwrap();
        let decoded = from_row::<RenamedRow>(&row);

        assert_eq!(decoded.id(), 9);
    }

    #[test]
    fn renamed_facade_trait_object_roundtrip() {
        let mut fory = Fory::builder().xlang(false).compatible(false).build();
        fory.register::<RenamedDog>(210).unwrap();

        let animal: Box<dyn RenamedAnimal> = Box::new(RenamedDog {
            name: "Spot".to_string(),
        });
        let bytes = fory.serialize(&animal).unwrap();
        let decoded: Box<dyn RenamedAnimal> = fory.deserialize(&bytes).unwrap();

        assert_eq!(decoded.name(), "Spot");
    }

    #[test]
    fn renamed_facade_manual_serializer_roundtrip() {
        let mut fory = Fory::builder().xlang(false).compatible(false).build();
        fory.register_serializer::<RenamedManual>(220).unwrap();

        let value = RenamedManual { value: 64 };
        let bytes = fory.serialize(&value).unwrap();
        let decoded: RenamedManual = fory.deserialize(&bytes).unwrap();

        assert_eq!(decoded, value);
    }
}
