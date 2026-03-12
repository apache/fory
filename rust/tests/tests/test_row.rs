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

use std::collections::BTreeMap;

use fory_core::row::{from_row, to_row};
use fory_derive::ForyRow;

#[test]
fn row_with_array_field() {
    // Test from GitHub issue: ForyRow should work with fixed-size array fields
    #[derive(ForyRow)]
    struct PointWithArray {
        index: i32,
        point: [f32; 4],
    }

    let data = PointWithArray {
        index: 42,
        point: [1.0, 2.0, 3.0, 4.0],
    };

    let row = to_row(&data).unwrap();
    let obj = from_row::<PointWithArray>(&row);

    assert_eq!(obj.index(), 42);
    let point_getter = obj.point();
    assert_eq!(point_getter.size(), 4);
    assert_eq!(point_getter.get(0).expect("index 0"), 1.0);
    assert_eq!(point_getter.get(1).expect("index 1"), 2.0);
    assert_eq!(point_getter.get(2).expect("index 2"), 3.0);
    assert_eq!(point_getter.get(3).expect("index 3"), 4.0);
    assert!(point_getter.get(4).is_err());
}

#[test]
fn row_with_nested_struct_array() {
    // Test ForyRow with nested struct containing arrays
    #[derive(ForyRow)]
    struct Point3D {
        coords: [f64; 3],
    }

    #[derive(ForyRow)]
    struct Geometry {
        name: String,
        origin: Point3D,
    }

    let data = Geometry {
        name: String::from("origin"),
        origin: Point3D {
            coords: [0.0, 0.0, 0.0],
        },
    };

    let row = to_row(&data).unwrap();
    let obj = from_row::<Geometry>(&row);

    assert_eq!(obj.name(), "origin");
    let coords = obj.origin().coords();
    assert_eq!(coords.size(), 3);
    assert_eq!(coords.get(0).expect("index 0"), 0.0);
    assert_eq!(coords.get(1).expect("index 1"), 0.0);
    assert_eq!(coords.get(2).expect("index 2"), 0.0);
    assert!(coords.get(3).is_err());
}

#[test]
fn row() {
    #[derive(ForyRow)]
    struct Foo {
        f1: String,
        f2: i8,
        f3: Vec<u8>,
        f4: Vec<i8>,
        f5: BTreeMap<String, String>,
    }

    #[derive(ForyRow)]
    struct Bar {
        f3: Foo,
    }

    let mut f5: BTreeMap<String, String> = BTreeMap::new();
    f5.insert(String::from("k1"), String::from("v1"));
    f5.insert(String::from("k2"), String::from("v2"));

    let row = to_row(&Bar {
        f3: Foo {
            f1: String::from("hello"),
            f2: 1,
            f3: vec![1, 2, 3],
            f4: vec![-1, 2, -3],
            f5,
        },
    })
    .unwrap();

    let obj = from_row::<Bar>(&row);
    let f1: &str = obj.f3().f1();
    assert_eq!(f1, "hello");
    let f2: i8 = obj.f3().f2();
    assert_eq!(f2, 1);
    let f3: &[u8] = obj.f3().f3();
    assert_eq!(f3, vec![1, 2, 3]);
    let f4_size: usize = obj.f3().f4().size();
    assert_eq!(f4_size, 3);
    assert_eq!(obj.f3().f4().get(0).expect("index 0"), -1);
    assert_eq!(obj.f3().f4().get(1).expect("index 1"), 2);
    assert_eq!(obj.f3().f4().get(2).expect("index 2"), -3);
    assert!(obj.f3().f4().get(3).is_err());

    let binding = obj.f3().f5();

    assert_eq!(binding.keys().size(), 2);
    assert_eq!(binding.keys().get(0).expect("key 0"), "k1");
    assert!(binding.keys().get(2).is_err());

    assert_eq!(binding.values().size(), 2);
    assert_eq!(binding.values().get(0).expect("value 0"), "v1");
    assert!(binding.values().get(2).is_err());

    let f5 = binding.to_btree_map().expect("should be map");
    assert_eq!(f5.get("k1").expect("should exists"), &"v1");
    assert_eq!(f5.get("k2").expect("should exists"), &"v2");
}
