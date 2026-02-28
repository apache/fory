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

use crate::models::{generate_random_string, TestDataGenerator};
use fory_derive::ForyObject;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

// Fory models
#[derive(ForyObject, Debug, Clone, PartialEq)]
pub struct SimpleStruct {
    #[fory(id = 0)]
    pub id: i32,
    #[fory(id = 1)]
    pub name: String,
    #[fory(id = 2)]
    pub active: bool,
    #[fory(id = 3)]
    pub score: f64,
}

#[derive(ForyObject, Debug, Clone, PartialEq)]
pub struct SimpleList {
    #[fory(id = 0)]
    pub numbers: Vec<i32>,
    #[fory(id = 1)]
    pub names: Vec<String>,
}

#[derive(ForyObject, Debug, Clone, PartialEq)]
pub struct SimpleMap {
    #[fory(id = 0)]
    pub string_to_int: HashMap<String, i32>,
    #[fory(id = 1)]
    pub int_to_string: HashMap<i32, String>,
}

// Serde models
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct SerdeSimpleStruct {
    pub id: i32,
    pub name: String,
    pub active: bool,
    pub score: f64,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct SerdeSimpleList {
    pub numbers: Vec<i32>,
    pub names: Vec<String>,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct SerdeSimpleMap {
    pub string_to_int: HashMap<String, i32>,
    pub int_to_string: HashMap<i32, String>,
}

impl TestDataGenerator for SimpleStruct {
    type Data = SimpleStruct;

    fn generate_small() -> Self::Data {
        SimpleStruct {
            id: 1,
            name: "test".to_string(),
            active: true,
            score: 95.5,
        }
    }

    fn generate_medium() -> Self::Data {
        SimpleStruct {
            id: 12345,
            name: generate_random_string(50),
            active: true,
            score: 87.123456,
        }
    }

    fn generate_large() -> Self::Data {
        SimpleStruct {
            id: 999999,
            name: generate_random_string(200),
            active: false,
            score: 123.456789,
        }
    }
}

impl TestDataGenerator for SimpleList {
    type Data = SimpleList;

    fn generate_small() -> Self::Data {
        SimpleList {
            numbers: vec![1, 2, 3, 4, 5],
            names: vec!["a".to_string(), "b".to_string(), "c".to_string()],
        }
    }

    fn generate_medium() -> Self::Data {
        SimpleList {
            numbers: (1..=100).collect(),
            names: (1..=50).map(|i| format!("name_{}", i)).collect(),
        }
    }

    fn generate_large() -> Self::Data {
        SimpleList {
            numbers: (1..=1000).collect(),
            names: (1..=500).map(|_i| generate_random_string(20)).collect(),
        }
    }
}

impl TestDataGenerator for SimpleMap {
    type Data = SimpleMap;

    fn generate_small() -> Self::Data {
        let mut string_to_int = HashMap::new();
        string_to_int.insert("one".to_string(), 1);
        string_to_int.insert("two".to_string(), 2);

        let mut int_to_string = HashMap::new();
        int_to_string.insert(1, "one".to_string());
        int_to_string.insert(2, "two".to_string());

        SimpleMap {
            string_to_int,
            int_to_string,
        }
    }

    fn generate_medium() -> Self::Data {
        let mut string_to_int = HashMap::new();
        let mut int_to_string = HashMap::new();

        for i in 1..=50 {
            let key = format!("key_{}", i);
            string_to_int.insert(key.clone(), i);
            int_to_string.insert(i, key);
        }

        SimpleMap {
            string_to_int,
            int_to_string,
        }
    }

    fn generate_large() -> Self::Data {
        let mut string_to_int = HashMap::new();
        let mut int_to_string = HashMap::new();

        for i in 1..=500 {
            let key = generate_random_string(15);
            string_to_int.insert(key.clone(), i);
            int_to_string.insert(i, key);
        }

        SimpleMap {
            string_to_int,
            int_to_string,
        }
    }
}

// Conversion functions for Serde
impl From<SimpleStruct> for SerdeSimpleStruct {
    fn from(f: SimpleStruct) -> Self {
        SerdeSimpleStruct {
            id: f.id,
            name: f.name,
            active: f.active,
            score: f.score,
        }
    }
}

impl From<SimpleList> for SerdeSimpleList {
    fn from(f: SimpleList) -> Self {
        SerdeSimpleList {
            numbers: f.numbers,
            names: f.names,
        }
    }
}

impl From<SimpleMap> for SerdeSimpleMap {
    fn from(f: SimpleMap) -> Self {
        SerdeSimpleMap {
            string_to_int: f.string_to_int,
            int_to_string: f.int_to_string,
        }
    }
}

// Reverse conversions from Serde to Fory
impl From<SerdeSimpleStruct> for SimpleStruct {
    fn from(s: SerdeSimpleStruct) -> Self {
        SimpleStruct {
            id: s.id,
            name: s.name,
            active: s.active,
            score: s.score,
        }
    }
}

impl From<SerdeSimpleList> for SimpleList {
    fn from(s: SerdeSimpleList) -> Self {
        SimpleList {
            numbers: s.numbers,
            names: s.names,
        }
    }
}

impl From<SerdeSimpleMap> for SimpleMap {
    fn from(s: SerdeSimpleMap) -> Self {
        SimpleMap {
            string_to_int: s.string_to_int,
            int_to_string: s.int_to_string,
        }
    }
}
