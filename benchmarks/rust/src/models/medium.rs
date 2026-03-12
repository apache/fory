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

use crate::models::{generate_random_string, generate_random_strings, TestDataGenerator};
use chrono::{DateTime, NaiveDateTime, Utc};
use fory_derive::ForyObject;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

// Fory models
#[derive(ForyObject, Debug, Clone, PartialEq, Default)]
pub struct ForyAddress {
    #[fory(id = 0)]
    pub street: String,
    #[fory(id = 1)]
    pub city: String,
    #[fory(id = 2)]
    pub country: String,
    #[fory(id = 3)]
    pub zip_code: String,
}

#[derive(ForyObject, Debug, Clone, PartialEq)]
pub struct Person {
    #[fory(id = 0)]
    pub name: String,
    #[fory(id = 1)]
    pub age: i32,
    #[fory(id = 2)]
    pub address: ForyAddress,
    #[fory(id = 3)]
    pub hobbies: Vec<String>,
    #[fory(id = 4)]
    pub metadata: HashMap<String, String>,
    #[fory(id = 5)]
    pub created_at: NaiveDateTime,
}

#[derive(ForyObject, Debug, Clone, PartialEq)]
pub struct Company {
    #[fory(id = 0)]
    pub name: String,
    #[fory(id = 1)]
    pub employees: Vec<Person>,
    #[fory(id = 2)]
    pub offices: HashMap<String, ForyAddress>,
    #[fory(id = 3)]
    pub is_public: bool,
}

// Serde models
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct SerdeAddress {
    pub street: String,
    pub city: String,
    pub country: String,
    pub zip_code: String,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct SerdePerson {
    pub name: String,
    pub age: i32,
    pub address: SerdeAddress,
    pub hobbies: Vec<String>,
    pub metadata: HashMap<String, String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct SerdeCompany {
    pub name: String,
    pub employees: Vec<SerdePerson>,
    pub offices: HashMap<String, SerdeAddress>,
    pub is_public: bool,
}

impl TestDataGenerator for Person {
    type Data = Person;

    fn generate_small() -> Self::Data {
        Person {
            name: "John Doe".to_string(),
            age: 30,
            address: ForyAddress {
                street: "123 Main St".to_string(),
                city: "New York".to_string(),
                country: "USA".to_string(),
                zip_code: "10001".to_string(),
            },
            hobbies: vec!["reading".to_string(), "coding".to_string()],
            metadata: HashMap::from([
                ("department".to_string(), "engineering".to_string()),
                ("level".to_string(), "senior".to_string()),
            ]),
            created_at: DateTime::from_timestamp(1640995200, 0).unwrap().naive_utc(),
        }
    }

    fn generate_medium() -> Self::Data {
        Person {
            name: generate_random_string(30),
            age: 35,
            address: ForyAddress {
                street: generate_random_string(50),
                city: generate_random_string(20),
                country: generate_random_string(15),
                zip_code: generate_random_string(10),
            },
            hobbies: generate_random_strings(10, 15),
            metadata: {
                let mut map = HashMap::new();
                for i in 1..=20 {
                    map.insert(format!("key_{}", i), generate_random_string(20));
                }
                map
            },
            created_at: DateTime::from_timestamp(1640995200, 0).unwrap().naive_utc(),
        }
    }

    fn generate_large() -> Self::Data {
        Person {
            name: generate_random_string(100),
            age: 40,
            address: ForyAddress {
                street: generate_random_string(100),
                city: generate_random_string(50),
                country: generate_random_string(30),
                zip_code: generate_random_string(20),
            },
            hobbies: generate_random_strings(50, 25),
            metadata: {
                let mut map = HashMap::new();
                for i in 1..=100 {
                    map.insert(format!("key_{}", i), generate_random_string(50));
                }
                map
            },
            created_at: DateTime::from_timestamp(1640995200, 0).unwrap().naive_utc(),
        }
    }
}

impl TestDataGenerator for Company {
    type Data = Company;

    fn generate_small() -> Self::Data {
        Company {
            name: "Tech Corp".to_string(),
            employees: vec![Person::generate_small()],
            offices: HashMap::from([(
                "HQ".to_string(),
                ForyAddress {
                    street: "456 Tech Ave".to_string(),
                    city: "San Francisco".to_string(),
                    country: "USA".to_string(),
                    zip_code: "94105".to_string(),
                },
            )]),
            is_public: true,
        }
    }

    fn generate_medium() -> Self::Data {
        let mut employees = Vec::new();
        for i in 1..=10 {
            let mut person = Person::generate_medium();
            person.name = format!("Employee_{}", i);
            employees.push(person);
        }

        let mut offices = HashMap::new();
        for i in 1..=5 {
            offices.insert(
                format!("Office_{}", i),
                ForyAddress {
                    street: generate_random_string(50),
                    city: generate_random_string(20),
                    country: generate_random_string(15),
                    zip_code: generate_random_string(10),
                },
            );
        }

        Company {
            name: generate_random_string(50),
            employees,
            offices,
            is_public: true,
        }
    }

    fn generate_large() -> Self::Data {
        let mut employees = Vec::new();
        for i in 1..=100 {
            let mut person = Person::generate_large();
            person.name = format!("Employee_{}", i);
            employees.push(person);
        }

        let mut offices = HashMap::new();
        for i in 1..=20 {
            offices.insert(
                format!("Office_{}", i),
                ForyAddress {
                    street: generate_random_string(100),
                    city: generate_random_string(50),
                    country: generate_random_string(30),
                    zip_code: generate_random_string(20),
                },
            );
        }

        Company {
            name: generate_random_string(100),
            employees,
            offices,
            is_public: false,
        }
    }
}

// Conversion functions for Serde
impl From<ForyAddress> for SerdeAddress {
    fn from(f: ForyAddress) -> Self {
        SerdeAddress {
            street: f.street,
            city: f.city,
            country: f.country,
            zip_code: f.zip_code,
        }
    }
}

impl From<Person> for SerdePerson {
    fn from(f: Person) -> Self {
        SerdePerson {
            name: f.name,
            age: f.age,
            address: f.address.into(),
            hobbies: f.hobbies,
            metadata: f.metadata,
            created_at: f.created_at.and_utc(),
        }
    }
}

impl From<Company> for SerdeCompany {
    fn from(f: Company) -> Self {
        SerdeCompany {
            name: f.name,
            employees: f.employees.into_iter().map(|e| e.into()).collect(),
            offices: f.offices.into_iter().map(|(k, v)| (k, v.into())).collect(),
            is_public: f.is_public,
        }
    }
}

// Reverse conversions from Serde to Fory
impl From<SerdeAddress> for ForyAddress {
    fn from(s: SerdeAddress) -> Self {
        ForyAddress {
            street: s.street,
            city: s.city,
            country: s.country,
            zip_code: s.zip_code,
        }
    }
}

impl From<SerdePerson> for Person {
    fn from(s: SerdePerson) -> Self {
        Person {
            name: s.name,
            age: s.age,
            address: s.address.into(),
            hobbies: s.hobbies,
            metadata: s.metadata,
            created_at: s.created_at.naive_utc(),
        }
    }
}

impl From<SerdeCompany> for Company {
    fn from(s: SerdeCompany) -> Self {
        Company {
            name: s.name,
            employees: s.employees.into_iter().map(|e| e.into()).collect(),
            offices: s.offices.into_iter().map(|(k, v)| (k, v.into())).collect(),
            is_public: s.is_public,
        }
    }
}
