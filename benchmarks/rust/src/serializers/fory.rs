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

use crate::data::{register_fory_types, register_fory_types_v2};
use crate::serializers::{BenchmarkSerializer, BoxError};
use fory::{Fory, ForyDefault, Serializer as ForyValueSerializer};

#[derive(Default)]
pub struct ForySerializer {
    writer: Fory,
    reader: Fory,
}

impl ForySerializer {
    pub fn new() -> Self {
        let mut writer = Fory::builder().xlang(true).compatible(true).build();
        register_fory_types(&mut writer).expect("register benchmark writer types");
        let mut reader = Fory::builder().xlang(true).compatible(true).build();
        if schema_mismatch_enabled() {
            register_fory_types_v2(&mut reader).expect("register benchmark v2 reader types");
        } else {
            register_fory_types(&mut reader).expect("register benchmark reader types");
        }

        Self { writer, reader }
    }

    pub fn deserialize_as<T>(&self, data: &[u8]) -> Result<T, BoxError>
    where
        T: ForyValueSerializer + ForyDefault,
    {
        Ok(self.reader.deserialize(data)?)
    }
}

pub fn schema_mismatch_enabled() -> bool {
    std::env::var("FORY_BENCH_SCHEMA_MISMATCH").as_deref() == Ok("1")
}

impl<T> BenchmarkSerializer<T> for ForySerializer
where
    T: ForyValueSerializer + ForyDefault,
{
    fn serialize(&self, data: &T) -> Result<Vec<u8>, BoxError> {
        Ok(self.writer.serialize(data)?)
    }

    fn deserialize(&self, data: &[u8]) -> Result<T, BoxError> {
        self.deserialize_as(data)
    }
}
