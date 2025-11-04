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
use fory_derive::ForyObject;
use std::any::Any;

#[derive(ForyObject, Debug)]
#[fory(debug)]
struct Container {
    value: i32,
    nested: Option<Box<dyn Any>>,
}

#[test]
fn test_max_dyn_depth_exceeded_box_dyn_any() {
    if fory_core::error::should_panic_on_error() {
        return;
    }
    for compatible in [false, true] {
        let mut fory = Fory::default().max_dyn_depth(2).compatible(compatible);
        fory.register::<Container>(100).unwrap();

        let level3 = Container {
            value: 3,
            nested: None,
        };
        let level2 = Container {
            value: 2,
            nested: Some(Box::new(level3)),
        };
        let level1 = Container {
            value: 1,
            nested: Some(Box::new(level2)),
        };

        let outer: Box<dyn Any> = Box::new(level1);
        let bytes = fory.serialize(&outer).unwrap();
        let result: Result<Box<dyn Any>, _> = fory.deserialize(&bytes);
        assert!(
            result.is_err(),
            "Expected deserialization to fail due to max depth"
        );
        let err = result.unwrap_err();
        let err_msg = format!("{:?}", err);
        assert!(err_msg.contains("Maximum dynamic object nesting depth"));
    }
}

#[test]
fn test_max_dyn_depth_within_limit_box_dyn_any() {
    if fory_core::error::should_panic_on_error() {
        return;
    }
    let mut fory = Fory::default().max_dyn_depth(3);
    fory.register::<Container>(100).unwrap();

    let level3 = Container {
        value: 3,
        nested: None,
    };
    let level2 = Container {
        value: 2,
        nested: Some(Box::new(level3)),
    };
    let level1 = Container {
        value: 1,
        nested: Some(Box::new(level2)),
    };

    let outer: Box<dyn Any> = Box::new(level1);
    let bytes = fory.serialize(&outer).unwrap();
    let result: Result<Box<dyn Any>, _> = fory.deserialize(&bytes);
    assert!(result.is_ok());
}

#[test]
fn test_max_dyn_depth_default_exceeded() {
    if fory_core::error::should_panic_on_error() {
        return;
    }
    let mut fory = Fory::default();
    fory.register::<Container>(100).unwrap();

    let mut current = Container {
        value: 6,
        nested: None,
    };

    for i in (1..=5).rev() {
        current = Container {
            value: i,
            nested: Some(Box::new(current)),
        };
    }

    let outer: Box<dyn Any> = Box::new(current);
    let bytes = fory.serialize(&outer).unwrap();
    let result: Result<Box<dyn Any>, _> = fory.deserialize(&bytes);

    assert!(result.is_err());
    let err = result.unwrap_err();
    let err_msg = format!("{:?}", err);
    assert!(err_msg.contains("Maximum dynamic object nesting depth"));
    assert!(err_msg.contains("5"));
}

#[test]
fn test_max_dyn_depth_default_within_limit() {
    if fory_core::error::should_panic_on_error() {
        return;
    }
    let mut fory = Fory::default();
    fory.register::<Container>(100).unwrap();

    let mut current = Container {
        value: 5,
        nested: None,
    };

    for i in (1..=4).rev() {
        current = Container {
            value: i,
            nested: Some(Box::new(current)),
        };
    }

    let outer: Box<dyn Any> = Box::new(current);
    let bytes = fory.serialize(&outer).unwrap();
    let result: Result<Box<dyn Any>, _> = fory.deserialize(&bytes);

    assert!(result.is_ok());
}
