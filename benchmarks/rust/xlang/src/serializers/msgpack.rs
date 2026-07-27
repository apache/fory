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

use crate::serializers::{BenchmarkSerializer, BoxError};
use serde::{de::DeserializeOwned, Serialize};

#[derive(Default)]
pub struct MsgpackSerializer;

impl MsgpackSerializer {
    pub fn new() -> Self {
        Self
    }
}

impl<T> BenchmarkSerializer<T> for MsgpackSerializer
where
    T: Serialize + DeserializeOwned,
{
    fn serialize(&self, data: &T) -> Result<Vec<u8>, BoxError> {
        let mut buffer = Vec::new();
        data.serialize(&mut rmp_serde::Serializer::new(&mut buffer).with_struct_map())?;
        Ok(buffer)
    }

    fn deserialize(&self, data: &[u8]) -> Result<T, BoxError> {
        Ok(rmp_serde::from_slice(data)?)
    }
}
