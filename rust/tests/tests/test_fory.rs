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

use fory_core::buffer::Reader;
use fory_core::fory::Fory;
use fory_derive::ForyObject;

#[test]
fn test_nested_struct_register_order() {
    #[derive(ForyObject, Debug, PartialEq)]
    struct Data1 {
        value: i32,
        data2: Data2,
    }

    #[derive(ForyObject, Debug, PartialEq)]
    struct Data2 {
        value: i32,
    }

    let mut fory = Fory::default();
    // outer struct registered first. building fields info should be executed lazily,
    // otherwise the inner struct won't be found.
    fory.register::<Data1>(100).unwrap();
    fory.register::<Data2>(101).unwrap();
    let data = Data1 {
        value: 42,
        data2: Data2 { value: 24 },
    };
    let bytes = fory.serialize(&data).unwrap();
    let result: Data1 = fory.deserialize(&bytes).unwrap();
    assert_eq!(data, result);
}

#[test]
fn test_serialize_to_appends_bytes() {
    #[derive(ForyObject, Debug, PartialEq)]
    struct Point {
        x: i32,
        y: i32,
    }

    let mut fory = Fory::default();
    fory.register::<Point>(100).unwrap();
    let p1 = Point { x: 1, y: 2 };
    let p2 = Point { x: -3, y: 4 };

    let expected_first = fory.serialize(&p1).unwrap();
    let expected_second = fory.serialize(&p2).unwrap();

    let mut buf = Vec::new();
    let len_first = fory.serialize_to(&mut buf, &p1).unwrap();
    assert_eq!(len_first, expected_first.len());
    assert_eq!(buf.len(), len_first);
    assert_eq!(&buf[..len_first], &expected_first);

    let len_second = fory.serialize_to(&mut buf, &p2).unwrap();
    assert_eq!(len_second, expected_second.len());
    assert_eq!(buf.len(), len_first + len_second);
    assert_eq!(&buf[len_first..], &expected_second);
}

#[test]
fn test_serialize_to_detailed() {
    #[derive(ForyObject, Debug, PartialEq)]
    struct Point {
        x: i32,
        y: i32,
    }

    #[derive(ForyObject, Debug, PartialEq)]
    struct Line {
        start: Point,
        end: Point,
        name: String,
    }

    let mut fory = Fory::default();
    fory.register::<Point>(100).unwrap();
    fory.register::<Line>(101).unwrap();

    // Test 1: Basic serialization to empty buffer
    let p1 = Point { x: 10, y: 20 };
    let mut buf = Vec::new();
    let len1 = fory.serialize_to(&mut buf, &p1).unwrap();
    assert_eq!(len1, buf.len());
    let deserialized1: Point = fory.deserialize_from(&mut Reader::new(&buf)).unwrap();
    assert_eq!(p1, deserialized1);

    // Test 2: Multiple serializations to the same buffer
    let p2 = Point { x: -5, y: 15 };
    let p3 = Point { x: 0, y: 0 };

    buf.clear();
    let len2_first = fory.serialize_to(&mut buf, &p2).unwrap();
    let offset1 = buf.len();
    let len2_second = fory.serialize_to(&mut buf, &p3).unwrap();
    let offset2 = buf.len();

    assert_eq!(offset1, len2_first);
    assert_eq!(offset2, len2_first + len2_second);

    // Deserialize both objects from the buffer using a single reader
    let mut reader = Reader::new(&buf);
    let des2: Point = fory.deserialize_from(&mut reader).unwrap();
    let des3: Point = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(p2, des2);
    assert_eq!(p3, des3);

    // Test 3: Complex nested struct serialization
    let line = Line {
        start: Point { x: 1, y: 2 },
        end: Point { x: 3, y: 4 },
        name: "diagonal".to_string(),
    };

    buf.clear();
    let len3 = fory.serialize_to(&mut buf, &line).unwrap();
    assert_eq!(len3, buf.len());
    let deserialized_line: Line = fory.deserialize_from(&mut Reader::new(&buf)).unwrap();
    assert_eq!(line, deserialized_line);

    // Test 4: Writing with pre-allocated header space
    let p4 = Point { x: 100, y: 200 };
    buf.clear();

    // Reserve 8 bytes for header
    buf.resize(8, 0);
    let header_size = buf.len();

    // Serialize data after header
    let data_len = fory.serialize_to(&mut buf, &p4).unwrap();

    // Write the data length into the header
    buf[0..8].copy_from_slice(&(data_len as u64).to_le_bytes());

    // Verify header
    let stored_len = u64::from_le_bytes(buf[0..8].try_into().unwrap()) as usize;
    assert_eq!(stored_len, data_len);

    // Verify we can deserialize the data portion by skipping the header
    let mut reader = Reader::new(&buf);
    reader.set_cursor(header_size);
    let des4: Point = fory.deserialize_from(&mut reader).unwrap();
    assert_eq!(p4, des4);

    // Test 5: Buffer reuse with resize (capacity preservation)
    buf.clear();
    let mut buf_with_capacity = Vec::with_capacity(1024);
    buf_with_capacity.resize(16, 0);

    let initial_capacity = buf_with_capacity.capacity();
    fory.serialize_to(&mut buf_with_capacity, &p1).unwrap();

    // Reset to smaller size - capacity should remain unchanged
    buf_with_capacity.resize(16, 0);
    assert_eq!(buf_with_capacity.capacity(), initial_capacity);

    // Serialize again - should not reallocate
    fory.serialize_to(&mut buf_with_capacity, &p2).unwrap();
    assert_eq!(buf_with_capacity.capacity(), initial_capacity);

    // Test 6: Serializing many objects sequentially
    buf.clear();
    let points = vec![
        Point { x: 1, y: 1 },
        Point { x: 2, y: 4 },
        Point { x: 3, y: 9 },
        Point { x: 4, y: 16 },
        Point { x: 5, y: 25 },
    ];

    for point in &points {
        fory.serialize_to(&mut buf, point).unwrap();
    }

    // Deserialize all objects and verify using a single reader
    let mut reader = Reader::new(&buf);
    for point in &points {
        let deserialized: Point = fory.deserialize_from(&mut reader).unwrap();
        assert_eq!(*point, deserialized);
    }
}

#[test]
fn test_context_reusable_after_unwind_in_entrypoints() {
    use fory_core::error::Error;
    use fory_core::resolver::context::{ReadContext, WriteContext};
    use fory_core::serializer::{ForyDefault, Serializer};
    use fory_core::TypeResolver;
    use std::panic::{catch_unwind, AssertUnwindSafe};

    #[derive(Debug)]
    struct PanicOnWrite;

    impl ForyDefault for PanicOnWrite {
        fn fory_default() -> Self {
            Self
        }
    }

    impl Serializer for PanicOnWrite {
        fn fory_write_data(&self, _context: &mut WriteContext) -> Result<(), Error> {
            panic!("intentional panic in fory_write_data")
        }

        fn fory_read_data(_context: &mut ReadContext) -> Result<Self, Error> {
            Ok(Self)
        }

        fn fory_type_id_dyn(
            &self,
            type_resolver: &TypeResolver,
        ) -> Result<fory_core::TypeId, Error> {
            Self::fory_get_type_id(type_resolver)
        }

        fn as_any(&self) -> &dyn std::any::Any {
            self
        }
    }

    #[derive(Debug)]
    struct PanicOnRead {
        value: i32,
    }

    impl ForyDefault for PanicOnRead {
        fn fory_default() -> Self {
            Self { value: 0 }
        }
    }

    impl Serializer for PanicOnRead {
        fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
            context.writer.write_i32(self.value);
            Ok(())
        }

        fn fory_read_data(_context: &mut ReadContext) -> Result<Self, Error> {
            panic!("intentional panic in fory_read_data")
        }

        fn fory_type_id_dyn(
            &self,
            type_resolver: &TypeResolver,
        ) -> Result<fory_core::TypeId, Error> {
            Self::fory_get_type_id(type_resolver)
        }

        fn as_any(&self) -> &dyn std::any::Any {
            self
        }
    }

    #[derive(ForyObject, Debug, PartialEq)]
    struct StablePoint {
        x: i32,
        y: i32,
    }

    let mut fory = Fory::default().xlang(true).compatible(true);
    fory.register_serializer_by_namespace::<PanicOnWrite>("panic.test", "PanicOnWrite")
        .unwrap();
    fory.register_serializer_by_namespace::<PanicOnRead>("panic.test", "PanicOnRead")
        .unwrap();
    fory.register_by_namespace::<StablePoint>("panic.test", "StablePoint")
        .unwrap();

    let write_unwind = {
        let mut external_buf = Vec::new();
        catch_unwind(AssertUnwindSafe(|| {
            let _ = fory.serialize_to(&mut external_buf, &PanicOnWrite);
        }))
    };
    assert!(write_unwind.is_err());

    let stable = StablePoint { x: 7, y: 9 };
    let stable_bytes_after_serialize_to_panic = fory.serialize(&stable).unwrap();
    let stable_after_serialize_to_panic: StablePoint = fory
        .deserialize(&stable_bytes_after_serialize_to_panic)
        .unwrap();
    assert_eq!(stable_after_serialize_to_panic, stable);

    let panic_read_bytes = fory.serialize(&PanicOnRead { value: 42 }).unwrap();

    let deserialize_unwind = catch_unwind(AssertUnwindSafe(|| {
        let _: PanicOnRead = fory.deserialize(&panic_read_bytes).unwrap();
    }));
    assert!(deserialize_unwind.is_err());

    let stable_bytes_after_deserialize_panic = fory.serialize(&stable).unwrap();
    let stable_after_deserialize_panic: StablePoint = fory
        .deserialize(&stable_bytes_after_deserialize_panic)
        .unwrap();
    assert_eq!(stable_after_deserialize_panic, stable);

    let mut panic_reader = Reader::new(&panic_read_bytes);
    let deserialize_from_unwind = catch_unwind(AssertUnwindSafe(|| {
        let _: PanicOnRead = fory.deserialize_from(&mut panic_reader).unwrap();
    }));
    assert!(deserialize_from_unwind.is_err());

    let mut stable_reader = Reader::new(&stable_bytes_after_deserialize_panic);
    let stable_after_deserialize_from_panic: StablePoint =
        fory.deserialize_from(&mut stable_reader).unwrap();
    assert_eq!(stable_after_deserialize_from_panic, stable);
}

use chrono::{DateTime, NaiveDateTime, Utc};

macro_rules! impl_value {
    ($record:ident, $value:ident, { $($field:ident : $ty:ty = $expr:expr),* $(,)? }) => {
        #[derive(ForyObject)]
        pub struct $value {
            $(pub $field: $ty,)*
        }

        impl $record {
            pub fn to_key_value(self) -> (String, $value) {
                let Self {
                    feature_key,
                    $($field,)*
                } = self;

                let value = $value {
                    $($field: $expr,)*
                };

                (feature_key, value)
            }
        }
    };
}

#[derive(Debug, Clone)]
pub struct KeyValue {
    feature_key: String,
    count: u64,
    last_seen_event_time: DateTime<Utc>,
}

impl_value!(
    KeyValue,
    Value,
    {
        count: u64 = count,
        last_seen_event_time: NaiveDateTime = last_seen_event_time.naive_utc(),
    }
);

#[test]
fn test_in_macro() {
    let key_value = KeyValue {
        feature_key: "test_key".to_string(),
        count: 100,
        last_seen_event_time: Utc::now(),
    };
    let (key, value) = key_value.clone().to_key_value();
    assert_eq!(key, "test_key");
    assert_eq!(value.count, 100);
    assert_eq!(
        value.last_seen_event_time,
        key_value.last_seen_event_time.naive_utc()
    );
}

#[test]
fn test_unregistered_type_error_message() {
    #[derive(ForyObject)]
    struct Inner {
        v: i32,
    }

    #[derive(ForyObject)]
    struct Outer {
        v: i32,
        inner: Inner,
    }

    let mut fory = Fory::default();
    // Register only the outer type; inner type is intentionally not registered
    fory.register::<Outer>(200).unwrap();
    let obj = Outer {
        v: 1,
        inner: Inner { v: 2 },
    };
    let err = fory
        .serialize(&obj)
        .expect_err("expected serialization to fail due to missing inner registration");
    let err_str = format!("{}", err);
    // The error should include the concrete Rust type name of the inner type (the generic T)
    let inner_name = std::any::type_name::<Inner>();
    assert!(
        err_str.contains(inner_name),
        "error did not contain inner type name; err='{}'",
        err_str
    );
}

#[test]
fn test_type_mismatch_error_shows_type_name() {
    use fory_core::error::Error;
    use fory_core::types::{format_type_id, TypeId};

    // Test internal type (BOOL = 1), no registered_id
    let formatted = format_type_id(TypeId::BOOL as u32);
    assert_eq!(formatted, "BOOL");

    // Test struct type
    let struct_type_id = TypeId::STRUCT as u32;
    let formatted = format_type_id(struct_type_id);
    assert_eq!(formatted, "STRUCT");

    // Test enum type
    let enum_type_id = TypeId::ENUM as u32;
    let formatted = format_type_id(enum_type_id);
    assert_eq!(formatted, "ENUM");

    // Test EXT type
    let ext_type_id = TypeId::EXT as u32;
    let formatted = format_type_id(ext_type_id);
    assert_eq!(formatted, "EXT");

    // Test error message format
    let err = Error::type_mismatch(struct_type_id, enum_type_id);
    let err_str = format!("{}", err);
    assert!(
        err_str.contains("STRUCT"),
        "error should contain STRUCT, got: {}",
        err_str
    );
    assert!(
        err_str.contains("ENUM"),
        "error should contain ENUM, got: {}",
        err_str
    );
    // Check the message contains "local" and "remote" for clarity
    assert!(
        err_str.contains("local") && err_str.contains("remote"),
        "error should indicate local vs remote types, got: {}",
        err_str
    );
}
