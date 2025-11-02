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

use fory_core::{Fory, ForyDefault, Reader};
use fory_derive::ForyObject;

#[derive(ForyObject, Debug, PartialEq)]
struct TestSkipFields {
    serialized_field: i32,
    #[fory(skip)]
    skipped_field: String,
    another_serialized: f64,
}

#[derive(ForyObject, Debug, PartialEq)]
struct NestedStruct {
    value: i32,
}

#[derive(ForyObject, Debug, PartialEq)]
struct TestNestedSkip {
    normal_field: i32,
    nested: NestedStruct,
    #[fory(skip)]
    skipped_nested: NestedStruct,
}

#[derive(ForyObject, Debug, PartialEq)]
struct MultipleSkipFields {
    field1: i32,
    #[fory(skip)]
    skipped1: String,
    field2: f64,
    #[fory(skip)]
    skipped2: bool,
    field3: f32,
}

#[derive(ForyObject, Debug, PartialEq)]
struct AllFieldsSkipped {
    #[fory(skip)]
    skipped1: String,
    #[fory(skip)]
    skipped2: i32,
    #[fory(skip)]
    skipped3: f64,
}

#[derive(ForyObject, Debug, PartialEq)]
struct ComplexNestedSkip {
    normal_field: i32,
    #[fory(skip)]
    skipped_field: String,
    nested: TestSkipFields,
    #[fory(skip)]
    skipped_nested: TestSkipFields,
}

#[derive(ForyObject, Debug, PartialEq)]
enum TestEnumSkip {
    Pending,
    // #[default]
    Active,
    Inactive,
    #[fory(skip)]
    Deleted,
}

#[test]
fn test_basic_skip_functionality() {
    let mut fory = Fory::default();
    fory.register::<TestSkipFields>(1).unwrap();

    let original = TestSkipFields {
        serialized_field: 42,
        skipped_field: "this should be skipped".to_string(),
        another_serialized: 2.142,
    };

    let bytes = fory.serialize(&original).unwrap();
    let decoded: TestSkipFields = fory.deserialize(&bytes).unwrap();
    assert_eq!(original.serialized_field, decoded.serialized_field);
    assert_eq!(original.another_serialized, decoded.another_serialized);
    assert_eq!(decoded.skipped_field, String::default());

    let mut buf: Vec<u8> = vec![];
    fory.serialize_to(&original, &mut buf).unwrap();
    let mut reader = Reader::new(&buf);
    let decoded: TestSkipFields = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(original.serialized_field, decoded.serialized_field);
    assert_eq!(original.another_serialized, decoded.another_serialized);
    assert_eq!(decoded.skipped_field, String::default());
}

#[test]
fn test_nested_skip_functionality() {
    let mut fory = Fory::default();
    fory.register::<TestNestedSkip>(2).unwrap();
    fory.register::<NestedStruct>(3).unwrap();

    let original = TestNestedSkip {
        normal_field: 100,
        nested: NestedStruct { value: 200 },
        skipped_nested: NestedStruct { value: 300 },
    };

    let bytes = fory.serialize(&original).unwrap();
    let decoded: TestNestedSkip = fory.deserialize(&bytes).unwrap();

    assert_eq!(original.normal_field, decoded.normal_field);
    assert_eq!(original.nested, decoded.nested);
    assert_eq!(decoded.skipped_nested, NestedStruct::default());
}

#[test]
fn test_multiple_skip_fields() {
    let mut fory = Fory::default();
    fory.register::<MultipleSkipFields>(3).unwrap();

    let original = MultipleSkipFields {
        field1: 42,
        skipped1: "skipped string".to_string(),
        field2: 2.71,
        skipped2: true,
        field3: 255.9,
    };

    let bytes = fory.serialize(&original).unwrap();
    let decoded: MultipleSkipFields = fory.deserialize(&bytes).unwrap();

    assert_eq!(original.field1, decoded.field1);
    assert_eq!(original.field2, decoded.field2);
    assert_eq!(original.field3, decoded.field3);
    assert_eq!(decoded.skipped1, String::default());
    assert_eq!(decoded.skipped2, bool::default());
}

#[test]
fn test_all_fields_skipped() {
    let mut fory = Fory::default();
    fory.register::<AllFieldsSkipped>(4).unwrap();

    let original = AllFieldsSkipped {
        skipped1: "test1".to_string(),
        skipped2: 42,
        skipped3: 2.142,
    };

    let bytes = fory.serialize(&original).unwrap();
    let decoded: AllFieldsSkipped = fory.deserialize(&bytes).unwrap();

    assert_eq!(decoded.skipped1, String::default());
    assert_eq!(decoded.skipped2, i32::default());
    assert_eq!(decoded.skipped3, f64::default());
}

#[test]
fn test_complex_nested_skip() {
    let mut fory = Fory::default();
    fory.register::<ComplexNestedSkip>(5).unwrap();
    fory.register::<TestSkipFields>(6).unwrap();

    let original = ComplexNestedSkip {
        normal_field: 1,
        skipped_field: "should be skipped".to_string(),
        nested: TestSkipFields {
            serialized_field: 2,
            skipped_field: "nested skipped".to_string(),
            another_serialized: 1.41,
        },
        skipped_nested: TestSkipFields {
            serialized_field: 3,
            skipped_field: "completely skipped".to_string(),
            another_serialized: 2.71,
        },
    };

    let bytes = fory.serialize(&original).unwrap();
    let decoded: ComplexNestedSkip = fory.deserialize(&bytes).unwrap();

    assert_eq!(original.normal_field, decoded.normal_field);
    assert_eq!(
        original.nested.serialized_field,
        decoded.nested.serialized_field
    );
    assert_eq!(decoded.nested.skipped_field, String::default());
    assert_eq!(decoded.skipped_field, String::default());
    assert_eq!(decoded.skipped_nested, TestSkipFields::default());
}

#[test]
fn test_enum_skip() {
    let mut fory = Fory::default();
    fory.register::<TestEnumSkip>(6).unwrap();

    let original_v1 = TestEnumSkip::Pending;

    let bytes = fory.serialize(&original_v1).unwrap();
    let decoded: TestEnumSkip = fory.deserialize(&bytes).unwrap();
    assert_eq!(original_v1, decoded);

    let original_skip = TestEnumSkip::Deleted;
    let bytes = fory.serialize(&original_skip).unwrap();
    let decoded: TestEnumSkip = fory.deserialize(&bytes).unwrap();
    assert_eq!(decoded, TestEnumSkip::default());
}

#[test]
fn test_skip_serialization_size() {
    let mut fory = Fory::default();
    fory.register::<TestSkipFields>(10).unwrap();

    let with_skip = TestSkipFields {
        serialized_field: 42,
        skipped_field: "this is a long string that should be skipped".to_string(),
        another_serialized: 2.142,
    };
    #[derive(ForyObject, Debug, PartialEq)]
    struct TestNoSkip {
        serialized_field: i32,
        skipped_field: String,
        another_serialized: f64,
    }

    fory.register::<TestNoSkip>(11).unwrap();

    let without_skip = TestNoSkip {
        serialized_field: 42,
        skipped_field: "this is a long string that should be skipped".to_string(),
        another_serialized: 2.142,
    };

    let bytes_with_skip = fory.serialize(&with_skip).unwrap();
    let bytes_without_skip = fory.serialize(&without_skip).unwrap();

    assert!(
        bytes_with_skip.len() < bytes_without_skip.len(),
        "Skipped version should be smaller: {} < {}",
        bytes_with_skip.len(),
        bytes_without_skip.len()
    );
}

#[test]
fn test_skip_with_different_types() {
    #[derive(ForyObject, Debug, PartialEq)]
    struct MultiTypeSkip {
        field1: i32,
        #[fory(skip)]
        skipped_string: String,
        field2: f64,
        #[fory(skip)]
        skipped_bool: bool,
        field3: i8,
        #[fory(skip)]
        skipped_vec: Vec<i32>,
        field4: i64,
    }

    let mut fory = Fory::default();
    fory.register::<MultiTypeSkip>(12).unwrap();

    let original = MultiTypeSkip {
        field1: 1,
        skipped_string: "test".to_string(),
        field2: 2.0,
        skipped_bool: true,
        field3: 3,
        skipped_vec: vec![1, 2, 3],
        field4: 4,
    };
    let bytes = fory.serialize(&original).unwrap();
    let decoded: MultiTypeSkip = fory.deserialize(&bytes).unwrap();

    assert_eq!(original.field1, decoded.field1);
    assert_eq!(original.field2, decoded.field2);
    assert_eq!(original.field3, decoded.field3);
    assert_eq!(original.field4, decoded.field4);

    assert_eq!(decoded.skipped_string, String::default());
    assert_eq!(decoded.skipped_bool, bool::default());
    assert_eq!(decoded.skipped_vec, Vec::<i32>::default());
}

#[test]
fn test_trait_object_serialization() {
    use fory_core::ForyDefault;
    use fory_core::Serializer;
    use fory_core::{register_trait_type, Fory};
    use fory_derive::ForyObject;
    use std::collections::HashMap;
    use std::rc::Rc;
    use std::sync::Arc;

    trait Animal: Serializer {
        fn speak(&self) -> String;
        fn name(&self) -> &str;
    }

    #[derive(ForyObject, Debug)]
    struct Dog {
        name: String,
        breed: String,
    }

    impl Animal for Dog {
        fn speak(&self) -> String {
            "Woof!".to_string()
        }
        fn name(&self) -> &str {
            &self.name
        }
    }

    #[derive(ForyObject, Debug)]
    struct Cat {
        name: String,
        color: String,
    }

    impl Animal for Cat {
        fn speak(&self) -> String {
            "Meow!".to_string()
        }
        fn name(&self) -> &str {
            &self.name
        }
    }

    register_trait_type!(Animal, Dog, Cat);

    #[derive(ForyObject)]
    struct Zoo {
        star_animal: Box<dyn Animal>,
    }

    #[derive(ForyObject)]
    struct ZooWithSkip {
        regular_animal: Box<dyn Animal>,
        #[fory(skip)]
        skipped_animal: Box<dyn Animal>,
    }

    let mut fory = Fory::default().compatible(true);
    fory.register::<Dog>(100).unwrap();
    fory.register::<Cat>(101).unwrap();
    fory.register::<Zoo>(102).unwrap();
    fory.register::<ZooWithSkip>(103).unwrap();

    let zoo_with_skip = ZooWithSkip {
        regular_animal: Box::new(Dog {
            name: "Speedy".to_string(),
            breed: "Greyhound".to_string(),
        }),
        skipped_animal: Box::new(Cat {
            name: "Felix".to_string(),
            color: "Black".to_string(),
        }),
    };

    let bytes_skip = fory.serialize(&zoo_with_skip).unwrap();

    let decoded_skip: ZooWithSkip = fory.deserialize(&bytes_skip).unwrap();

    assert_eq!(decoded_skip.regular_animal.name(), "Speedy");
    assert_eq!(decoded_skip.regular_animal.speak(), "Woof!");

    assert_eq!(decoded_skip.skipped_animal.name(), "".to_string());
    assert_eq!(decoded_skip.skipped_animal.speak(), "Woof!".to_string());

    #[derive(ForyObject)]
    struct ComplexSkipExample {
        #[fory(skip)]
        boxed_dyn: Box<dyn Animal>,
        #[fory(skip)]
        animals_arc: Vec<Arc<dyn Animal>>,
        #[fory(skip)]
        registry: HashMap<String, Arc<dyn Animal>>,
        #[fory(skip)]
        animals_rc: Vec<Rc<dyn Animal>>,
        normal_field: String,
    }

    let mut fory = Fory::default().compatible(true);
    fory.register::<ComplexSkipExample>(106).unwrap();

    let complex = ComplexSkipExample {
        boxed_dyn: Box::new(Dog {
            name: "BoxTest".to_string(),
            breed: "BoxTestBreed".to_string(),
        }) as Box<dyn Animal>,
        animals_rc: vec![Rc::new(Cat {
            name: "RcTest".to_string(),
            color: "RcTestColor".to_string(),
        }) as Rc<dyn Animal>],
        animals_arc: vec![Arc::new(Cat {
            name: "RcTest".to_string(),
            color: "RcTestColor".to_string(),
        }) as Arc<dyn Animal>],
        registry: HashMap::from_iter([(
            "arc_map".to_string(),
            Arc::new(Dog {
                name: "ArcTest".to_string(),
                breed: "ArcTestBreed".to_string(),
            }) as Arc<dyn Animal>,
        )]),
        normal_field: "test_value".to_string(),
    };

    let complex_bytes = fory.serialize(&complex).unwrap();
    let decoded_complex: ComplexSkipExample = fory.deserialize(&complex_bytes).unwrap();

    assert_eq!(decoded_complex.normal_field, "test_value");

    assert_eq!(decoded_complex.boxed_dyn.name(), "".to_string());
    assert!(decoded_complex.animals_rc.is_empty());
    assert!(decoded_complex.animals_arc.is_empty());
    assert!(decoded_complex.registry.is_empty());
}
