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

use crate::models::complex::{ECommerceData, FuryCustomer, FuryOrder, FuryOrderItem, FuryProduct};
use crate::models::medium::{Company, FuryAddress, Person};
use crate::models::realworld::{FuryAPIMetrics, FuryLogEntry, FuryUserProfile, SystemData};
use crate::models::simple::{SimpleList, SimpleMap, SimpleStruct};
use crate::serializers::Serializer;
use fory_core::fory::Fory;

#[derive(Default)]
pub struct FurySerializer {
    fory: Fory,
}

impl FurySerializer {
    pub fn new() -> Self {
        let mut fory = Fory::default();

        // Register simple types
        fory.register::<SimpleStruct>(100);
        fory.register::<SimpleList>(101);
        fory.register::<SimpleMap>(102);

        // Register medium types
        fory.register::<FuryAddress>(200);
        fory.register::<Person>(201);
        fory.register::<Company>(202);

        // Register complex types
        fory.register::<FuryProduct>(300);
        fory.register::<FuryOrderItem>(301);
        fory.register::<FuryCustomer>(302);
        fory.register::<FuryOrder>(303);
        fory.register::<ECommerceData>(304);

        // Register realworld types
        fory.register::<FuryLogEntry>(400);
        fory.register::<FuryUserProfile>(401);
        fory.register::<FuryAPIMetrics>(402);
        fory.register::<SystemData>(403);

        Self { fory }
    }
}

impl Serializer<SimpleStruct> for FurySerializer {
    fn serialize(&self, data: &SimpleStruct) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        Ok(self.fory.serialize(data))
    }

    fn deserialize(&self, data: &[u8]) -> Result<SimpleStruct, Box<dyn std::error::Error>> {
        Ok(self.fory.deserialize(data)?)
    }
}

impl Serializer<SimpleList> for FurySerializer {
    fn serialize(&self, data: &SimpleList) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        Ok(self.fory.serialize(data))
    }

    fn deserialize(&self, data: &[u8]) -> Result<SimpleList, Box<dyn std::error::Error>> {
        Ok(self.fory.deserialize(data)?)
    }
}

impl Serializer<SimpleMap> for FurySerializer {
    fn serialize(&self, data: &SimpleMap) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        Ok(self.fory.serialize(data))
    }

    fn deserialize(&self, data: &[u8]) -> Result<SimpleMap, Box<dyn std::error::Error>> {
        Ok(self.fory.deserialize(data)?)
    }
}

impl Serializer<Person> for FurySerializer {
    fn serialize(&self, data: &Person) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        Ok(self.fory.serialize(data))
    }

    fn deserialize(&self, data: &[u8]) -> Result<Person, Box<dyn std::error::Error>> {
        Ok(self.fory.deserialize(data)?)
    }
}

impl Serializer<Company> for FurySerializer {
    fn serialize(&self, data: &Company) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        Ok(self.fory.serialize(data))
    }

    fn deserialize(&self, data: &[u8]) -> Result<Company, Box<dyn std::error::Error>> {
        Ok(self.fory.deserialize(data)?)
    }
}

impl Serializer<ECommerceData> for FurySerializer {
    fn serialize(&self, data: &ECommerceData) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        Ok(self.fory.serialize(data))
    }

    fn deserialize(&self, data: &[u8]) -> Result<ECommerceData, Box<dyn std::error::Error>> {
        Ok(self.fory.deserialize(data)?)
    }
}

impl Serializer<SystemData> for FurySerializer {
    fn serialize(&self, data: &SystemData) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        Ok(self.fory.serialize(data))
    }

    fn deserialize(&self, data: &[u8]) -> Result<SystemData, Box<dyn std::error::Error>> {
        Ok(self.fory.deserialize(data)?)
    }
}
