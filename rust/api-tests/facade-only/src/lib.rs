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

#[cfg(test)]
mod tests {
    use fory::{
        from_row, register_trait_type, to_row, Error, Fory, ForyDefault, ForyEnum, ForyRow,
        ForyStruct, ForyUnion, ReadContext, Serializer, TypeId, TypeResolver, WriteContext,
    };

    #[derive(ForyStruct, Debug, PartialEq)]
    struct Address {
        city: String,
    }

    #[derive(ForyStruct, Debug, PartialEq)]
    struct Person {
        name: String,
        age: i32,
        address: Address,
    }

    #[derive(ForyEnum, Debug, Default, PartialEq)]
    enum Status {
        #[default]
        Active,
        Inactive,
    }

    #[derive(ForyUnion, Debug, PartialEq)]
    enum Event {
        #[fory(unknown)]
        Unknown(fory::UnknownCase),
        #[fory(id = 0, default)]
        Created(String),
        #[fory(id = 1)]
        Count(i64),
    }

    #[derive(ForyRow)]
    struct RowUser {
        id: i64,
        name: String,
    }

    pub(crate) trait Animal: Serializer {
        fn name(&self) -> &str;
    }

    #[derive(ForyStruct, Debug, PartialEq)]
    struct Dog {
        name: String,
    }

    impl Animal for Dog {
        fn name(&self) -> &str {
            &self.name
        }
    }

    register_trait_type!(Animal, Dog);

    #[derive(Debug, PartialEq)]
    struct ManualValue {
        value: i32,
    }

    impl ForyDefault for ManualValue {
        fn fory_default() -> Self {
            Self { value: 0 }
        }
    }

    impl Serializer for ManualValue {
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
    fn facade_object_derives_roundtrip() {
        let mut fory = Fory::builder().xlang(false).compatible(false).build();
        fory.register::<Address>(100).unwrap();
        fory.register::<Person>(101).unwrap();
        fory.register::<Status>(102).unwrap();
        fory.register_union::<Event>(103).unwrap();

        let person = Person {
            name: "Ada".to_string(),
            age: 37,
            address: Address {
                city: "London".to_string(),
            },
        };
        let bytes = fory.serialize(&person).unwrap();
        let decoded: Person = fory.deserialize(&bytes).unwrap();
        assert_eq!(decoded, person);

        let status = Status::Inactive;
        let bytes = fory.serialize(&status).unwrap();
        let decoded: Status = fory.deserialize(&bytes).unwrap();
        assert_eq!(decoded, status);

        let event = Event::Created("created".to_string());
        let bytes = fory.serialize(&event).unwrap();
        let decoded: Event = fory.deserialize(&bytes).unwrap();
        assert_eq!(decoded, event);
    }

    #[test]
    fn facade_row_derive_roundtrip() {
        let user = RowUser {
            id: 7,
            name: "Grace".to_string(),
        };
        let row = to_row(&user).unwrap();
        let decoded = from_row::<RowUser>(&row);

        assert_eq!(decoded.id(), 7);
        assert_eq!(decoded.name(), "Grace");
    }

    #[test]
    fn facade_trait_object_roundtrip() {
        let mut fory = Fory::builder().xlang(false).compatible(false).build();
        fory.register::<Dog>(120).unwrap();

        let animal: Box<dyn Animal> = Box::new(Dog {
            name: "Rex".to_string(),
        });
        let bytes = fory.serialize(&animal).unwrap();
        let decoded: Box<dyn Animal> = fory.deserialize(&bytes).unwrap();

        assert_eq!(decoded.name(), "Rex");
    }

    #[test]
    fn facade_manual_serializer_roundtrip() {
        let mut fory = Fory::builder().xlang(false).compatible(false).build();
        fory.register_serializer::<ManualValue>(130).unwrap();

        let value = ManualValue { value: 42 };
        let bytes = fory.serialize(&value).unwrap();
        let decoded: ManualValue = fory.deserialize(&bytes).unwrap();

        assert_eq!(decoded, value);
    }
}
