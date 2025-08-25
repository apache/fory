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

use crate::error::Error;
use crate::fory::Fory;
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::serializer::Serializer;
use crate::types::{FieldId, ForyGeneralList, SIZE_OF_REF_AND_TYPE};
use std::collections::HashMap;
use std::mem;

impl<T1: Serializer + Eq + std::hash::Hash, T2: Serializer> Serializer for HashMap<T1, T2> {
    fn write(&self, context: &mut WriteContext) {
        // length
        context.writer.var_int32(self.len() as i32);

        let reserved_space = (<T1 as Serializer>::reserved_space() + SIZE_OF_REF_AND_TYPE)
            * self.len()
            + (<T2 as Serializer>::reserved_space() + SIZE_OF_REF_AND_TYPE) * self.len();
        context.writer.reserve(reserved_space);

        // key-value
        for i in self.iter() {
            i.0.serialize(context);
            i.1.serialize(context);
        }
    }

    fn read(context: &mut ReadContext) -> Result<Self, Error> {
        // map length
        let len = context.reader.var_int32();
        (0..len)
            .map(|_| {
                <T1 as Serializer>::deserialize(context)
                    .and_then(|k| <T2 as Serializer>::deserialize(context).map(|v| (k, v)))
            })
            .collect::<Result<HashMap<_, _>, Error>>()
    }

    fn reserved_space() -> usize {
        mem::size_of::<i32>()
    }

    fn get_type_id(_fory: &Fory) -> i16 {
        FieldId::MAP.into()
    }
}

impl<T1: Serializer + Eq + std::hash::Hash, T2: Serializer> ForyGeneralList for HashMap<T1, T2> {}
