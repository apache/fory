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
    let len_first = fory.serialize_to(&p1, &mut buf).unwrap();
    assert_eq!(len_first, expected_first.len());
    assert_eq!(buf.len(), len_first);
    assert_eq!(&buf[..len_first], &expected_first);

    let len_second = fory.serialize_to(&p2, &mut buf).unwrap();
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
    let len1 = fory.serialize_to(&p1, &mut buf).unwrap();
    assert_eq!(len1, buf.len());
    let deserialized1: Point = fory.deserialize_from(&mut Reader::new(&buf)).unwrap();
    assert_eq!(p1, deserialized1);

    // Test 2: Multiple serializations to the same buffer
    let p2 = Point { x: -5, y: 15 };
    let p3 = Point { x: 0, y: 0 };

    buf.clear();
    let len2_first = fory.serialize_to(&p2, &mut buf).unwrap();
    let offset1 = buf.len();
    let len2_second = fory.serialize_to(&p3, &mut buf).unwrap();
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
    let len3 = fory.serialize_to(&line, &mut buf).unwrap();
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
    let data_len = fory.serialize_to(&p4, &mut buf).unwrap();

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
    fory.serialize_to(&p1, &mut buf_with_capacity).unwrap();

    // Reset to smaller size - capacity should remain unchanged
    buf_with_capacity.resize(16, 0);
    assert_eq!(buf_with_capacity.capacity(), initial_capacity);

    // Serialize again - should not reallocate
    fory.serialize_to(&p2, &mut buf_with_capacity).unwrap();
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
        fory.serialize_to(point, &mut buf).unwrap();
    }

    // Deserialize all objects and verify using a single reader
    let mut reader = Reader::new(&buf);
    for point in &points {
        let deserialized: Point = fory.deserialize_from(&mut reader).unwrap();
        assert_eq!(*point, deserialized);
    }
}
