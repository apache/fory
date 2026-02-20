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

use fory_core::fory::Fory;
use fory_core::register_trait_type;
use fory_core::serializer::Serializer;
use fory_derive::ForyObject;
use std::rc::Rc;

#[test]
fn test_array_i32() {
    let fory = Fory::default();
    let arr = [1, 2, 3, 4, 5];
    let bin = fory.serialize(&arr).unwrap();
    let obj: [i32; 5] = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(arr, obj);
}

#[test]
fn test_array_i64() {
    let fory = Fory::default();
    let arr = [100i64, 200, 300];
    let bin = fory.serialize(&arr).unwrap();
    let obj: [i64; 3] = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(arr, obj);
}

#[test]
fn test_array_f64() {
    let fory = Fory::default();
    let arr = [1.5, 2.5, 3.5, 4.5];
    let bin = fory.serialize(&arr).unwrap();
    let obj: [f64; 4] = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(arr, obj);
}

#[test]
fn test_array_f32() {
    let fory = Fory::default();
    let arr = [1.1f32, 2.2, 3.3];
    let bin = fory.serialize(&arr).unwrap();
    let obj: [f32; 3] = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(arr, obj);
}

#[test]
fn test_array_bool() {
    let fory = Fory::default();
    let arr = [true, false, true, false];
    let bin = fory.serialize(&arr).unwrap();
    let obj: [bool; 4] = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(arr, obj);
}

#[test]
fn test_array_i8() {
    let fory = Fory::default();
    let arr = [1i8, 2, 3, 4, 5, 6, 7, 8];
    let bin = fory.serialize(&arr).unwrap();
    let obj: [i8; 8] = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(arr, obj);
}

#[test]
fn test_array_i16() {
    let fory = Fory::default();
    let arr = [100i16, 200, 300, 400];
    let bin = fory.serialize(&arr).unwrap();
    let obj: [i16; 4] = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(arr, obj);
}

#[test]
fn test_array_string() {
    let fory = Fory::default();
    let arr = ["hello".to_string(), "world".to_string(), "fory".to_string()];
    let bin = fory.serialize(&arr).unwrap();
    let obj: [String; 3] = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(arr, obj);
}

#[test]
fn test_array_empty() {
    let fory = Fory::default();
    let arr: [i32; 0] = [];
    let bin = fory.serialize(&arr).unwrap();
    let obj: [i32; 0] = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(arr, obj);
}

#[test]
fn test_array_single_element() {
    let fory = Fory::default();
    let arr = [42];
    let bin = fory.serialize(&arr).unwrap();
    let obj: [i32; 1] = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(arr, obj);
}

#[test]
fn test_array_large() {
    let fory = Fory::default();
    let arr = [1; 100];
    let bin = fory.serialize(&arr).unwrap();
    let obj: [i32; 100] = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(arr, obj);
}

#[derive(ForyObject, PartialEq, Debug)]
struct Point {
    x: i32,
    y: i32,
}

#[test]
fn test_array_struct() {
    let mut fory = Fory::default();
    fory.register_by_name::<Point>("Point").unwrap();

    let arr = [
        Point { x: 1, y: 2 },
        Point { x: 3, y: 4 },
        Point { x: 5, y: 6 },
    ];
    let bin = fory.serialize(&arr).unwrap();
    let obj: [Point; 3] = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(arr, obj);
}

#[derive(ForyObject, PartialEq, Debug)]
struct ArrayStruct {
    int_array: [i32; 5],
    float_array: [f64; 3],
    string_array: [String; 2],
}

#[test]
fn test_struct_with_arrays() {
    let mut fory = Fory::default();
    fory.register_by_name::<ArrayStruct>("ArrayStruct").unwrap();

    let data = ArrayStruct {
        int_array: [1, 2, 3, 4, 5],
        float_array: [1.1, 2.2, 3.3],
        string_array: ["hello".to_string(), "world".to_string()],
    };

    let bin = fory.serialize(&data).unwrap();
    let obj: ArrayStruct = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(data, obj);
}

#[test]
fn test_array_nested() {
    let fory = Fory::default();
    let arr = [[1, 2], [3, 4], [5, 6]];
    let bin = fory.serialize(&arr).unwrap();
    let obj: [[i32; 2]; 3] = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(arr, obj);
}

#[test]
fn test_array_option() {
    let fory = Fory::default();
    let arr = [Some(1), None, Some(3)];
    let bin = fory.serialize(&arr).unwrap();
    let obj: [Option<i32>; 3] = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(arr, obj);
}

#[test]
fn test_array_vec_compatibility() {
    // Test that an array can be serialized and deserialized as a Vec
    let fory = Fory::default();
    let arr = [1, 2, 3, 4, 5];
    let bin = fory.serialize(&arr).unwrap();
    // Deserialize as Vec should work since they use the same type ID
    let vec: Vec<i32> = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(&arr[..], &vec[..]);
}

#[test]
fn test_vec_array_compatibility() {
    // Test that a Vec can be serialized and deserialized as an array
    let fory = Fory::default();
    let vec = vec![1, 2, 3];
    let bin = fory.serialize(&vec).unwrap();
    // Deserialize as array should work if the size matches
    let arr: [i32; 3] = fory.deserialize(&bin).expect("deserialize");
    assert_eq!(&vec[..], &arr[..]);
}

#[test]
fn test_array_size_mismatch() {
    // Test that deserializing with wrong size fails gracefully
    let fory = Fory::default();
    let arr = [1, 2, 3, 4, 5];
    let bin = fory.serialize(&arr).unwrap();
    // Try to deserialize as array with wrong size
    let result: Result<[i32; 3], _> = fory.deserialize(&bin);
    assert!(result.is_err());
}

// Trait object tests

trait Shape: Serializer {
    fn area(&self) -> f64;
    fn name(&self) -> &str;
}

#[derive(ForyObject, Debug, PartialEq)]
struct Circle {
    radius: f64,
}

impl Shape for Circle {
    fn area(&self) -> f64 {
        std::f64::consts::PI * self.radius * self.radius
    }
    fn name(&self) -> &str {
        "Circle"
    }
}

#[derive(ForyObject, Debug, PartialEq)]
struct Rectangle {
    width: f64,
    height: f64,
}

impl Shape for Rectangle {
    fn area(&self) -> f64 {
        self.width * self.height
    }
    fn name(&self) -> &str {
        "Rectangle"
    }
}

register_trait_type!(Shape, Circle, Rectangle);

#[test]
fn test_array_box_trait_objects() {
    let mut fory = Fory::default().compatible(true);
    fory.register::<Circle>(9001).unwrap();
    fory.register::<Rectangle>(9002).unwrap();

    // Create an array of Box<dyn Shape>
    let shapes: [Box<dyn Shape>; 3] = [
        Box::new(Circle { radius: 5.0 }),
        Box::new(Rectangle {
            width: 4.0,
            height: 6.0,
        }),
        Box::new(Circle { radius: 3.0 }),
    ];

    // Calculate expected areas before serialization
    let expected_areas: [f64; 3] = [shapes[0].area(), shapes[1].area(), shapes[2].area()];
    let expected_names: [&str; 3] = [shapes[0].name(), shapes[1].name(), shapes[2].name()];

    let bin = fory.serialize(&shapes).unwrap();
    let deserialized: [Box<dyn Shape>; 3] = fory.deserialize(&bin).expect("deserialize");

    // Verify the trait methods work correctly
    assert_eq!(deserialized.len(), 3);
    for i in 0..3 {
        assert_eq!(deserialized[i].area(), expected_areas[i]);
        assert_eq!(deserialized[i].name(), expected_names[i]);
    }
}

#[test]
fn test_vec_of_arrays() {
    // Test from GitHub issue: Vec<[f32;4]> should work with ForyObject
    let fory = Fory::default();

    // Test Vec of primitive arrays
    let points: Vec<[f32; 4]> = vec![
        [1.0, 2.0, 3.0, 4.0],
        [5.0, 6.0, 7.0, 8.0],
        [9.0, 10.0, 11.0, 12.0],
    ];

    let bin = fory.serialize(&points).unwrap();
    let result: Vec<[f32; 4]> = fory.deserialize(&bin).expect("deserialize");

    assert_eq!(result.len(), 3);
    assert_eq!(result[0], [1.0, 2.0, 3.0, 4.0]);
    assert_eq!(result[1], [5.0, 6.0, 7.0, 8.0]);
    assert_eq!(result[2], [9.0, 10.0, 11.0, 12.0]);
}

#[test]
fn test_struct_with_vec_of_arrays() {
    // Test from GitHub issue: struct with Vec<[f32;4]> field should work
    #[derive(ForyObject, PartialEq, Debug)]
    struct PointCloud {
        index: i32,
        points: Vec<[f32; 4]>,
    }

    let mut fory = Fory::default();
    fory.register_by_name::<PointCloud>("PointCloud").unwrap();

    let data = PointCloud {
        index: 42,
        points: vec![[1.0, 2.0, 3.0, 4.0], [5.0, 6.0, 7.0, 8.0]],
    };

    let bin = fory.serialize(&data).unwrap();
    let result: PointCloud = fory.deserialize(&bin).expect("deserialize");

    assert_eq!(result.index, 42);
    assert_eq!(result.points.len(), 2);
    assert_eq!(result.points[0], [1.0, 2.0, 3.0, 4.0]);
    assert_eq!(result.points[1], [5.0, 6.0, 7.0, 8.0]);
}

#[test]
fn test_array_rc_trait_objects() {
    let mut fory = Fory::default().compatible(true);
    fory.register::<Circle>(9001).unwrap();
    fory.register::<Rectangle>(9002).unwrap();

    // Create Rc<dyn Shape> instances and convert to wrappers
    let circle1: Rc<dyn Shape> = Rc::new(Circle { radius: 2.0 });
    let rect: Rc<dyn Shape> = Rc::new(Rectangle {
        width: 3.0,
        height: 4.0,
    });
    let circle2: Rc<dyn Shape> = Rc::new(Circle { radius: 7.0 });

    // Convert to wrapper types for serialization
    let shapes: [ShapeRc; 3] = [
        ShapeRc::from(circle1),
        ShapeRc::from(rect),
        ShapeRc::from(circle2),
    ];

    // Calculate expected areas
    let expected_areas: [f64; 3] = [
        std::f64::consts::PI * 4.0,  // Circle radius 2.0
        12.0,                        // Rectangle 3x4
        std::f64::consts::PI * 49.0, // Circle radius 7.0
    ];

    let bin = fory.serialize(&shapes).unwrap();
    let deserialized: [ShapeRc; 3] = fory.deserialize(&bin).expect("deserialize");

    // Verify by unwrapping and calling trait methods
    assert_eq!(deserialized.len(), 3);
    for i in 0..3 {
        let shape: Rc<dyn Shape> = deserialized[i].clone().into();
        assert!((shape.area() - expected_areas[i]).abs() < 0.001);
    }
}

#[test]
fn test_array_float16() {
    use fory_core::float16::float16;
    let fory = fory_core::fory::Fory::default();
    let arr = [float16::from_f32(1.0), float16::from_f32(2.5), float16::from_f32(-1.5), float16::ZERO];
    let bin = fory.serialize(&arr).unwrap();
    let obj: [float16; 4] = fory.deserialize(&bin).expect("deserialize float16 array");
    for (a, b) in arr.iter().zip(obj.iter()) { assert_eq!(a.to_bits(), b.to_bits()); }
}

#[test]
fn test_array_float16_special_values() {
    use fory_core::float16::float16;
    let fory = fory_core::fory::Fory::default();
    let arr = [float16::INFINITY, float16::NEG_INFINITY, float16::MAX, float16::MIN_POSITIVE, float16::MIN_POSITIVE_SUBNORMAL];
    let bin = fory.serialize(&arr).unwrap();
    let obj: [float16; 5] = fory.deserialize(&bin).expect("deserialize float16 array specials");
    assert!(obj[0].is_infinite() && obj[0].is_sign_positive());
    assert!(obj[1].is_infinite() && obj[1].is_sign_negative());
    assert_eq!(obj[2].to_bits(), float16::MAX.to_bits());
    assert!(obj[4].is_subnormal());
}
