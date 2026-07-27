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

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct User {
    pub name: String,
    pub age: u32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Point(pub i32, pub i32);

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Marker;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Status {
    Inactive,
    Active,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Value<U> {
    Null,
    Text(String),
    Named { text: String },
    Count(i64),
    Unknown(U),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Command {
    Idle,
    Create { id: u128, label: String },
    Move(i32, i32),
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Key {
    pub namespace: String,
    pub id: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ExternalId(pub u128);

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GenericRecord<T> {
    pub value: T,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct UserV1 {
    pub name: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct UserV2 {
    pub name: String,
    pub age: u32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct UserWithState {
    pub name: String,
    pub state: ExternalId,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Workflow {
    Idle,
    Assign {
        id: ExternalId,
        user: User,
    },
    Batch(
        Vec<User>,
        std::collections::HashMap<Key, User>,
        (String, User),
    ),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ExtraState {
    pub name: String,
    pub age: u32,
    pub active: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PrivateState {
    pub name: String,
    secret: u32,
}

impl PrivateState {
    pub fn new(name: String, secret: u32) -> Self {
        Self { name, secret }
    }

    pub fn secret(&self) -> u32 {
        self.secret
    }
}

#[non_exhaustive]
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NonExhaustiveUser {
    pub name: String,
    pub age: u32,
}

#[non_exhaustive]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum NonExhaustiveStatus {
    Active,
    Inactive,
}
