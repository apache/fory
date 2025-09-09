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

use crate::ensure;
use crate::error::Error;
use crate::fory::Fory;
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::serializer::Serializer;
use crate::types::{ForyGeneralList, RefFlag};
use anyhow::anyhow;

impl<T: Serializer> Serializer for Option<T> {
    fn read(context: &mut ReadContext) -> Result<Self, Error> {
        Ok(Some(T::read(context)?))
    }

    fn deserialize(context: &mut ReadContext) -> Result<Self, Error> {
        // ref flag
        let ref_flag = context.reader.i8();

        if ref_flag == (RefFlag::NotNullValue as i8) || ref_flag == (RefFlag::RefValue as i8) {
            // type_id
            let actual_type_id = context.reader.var_uint32();
            let expected_type_id = T::get_type_id(context.get_fory());
            ensure!(
                actual_type_id == expected_type_id,
                anyhow!("Invalid field type, expected:{expected_type_id}, actual:{actual_type_id}")
            );

            Ok(Some(T::read(context)?))
        } else if ref_flag == (RefFlag::Null as i8) {
            Ok(None)
        } else if ref_flag == (RefFlag::Ref as i8) {
            Err(Error::Ref)
        } else {
            Err(anyhow!("Unknown ref flag, value:{ref_flag}"))?
        }
    }

    fn write(&self, context: &mut WriteContext) {
        if let Some(v) = self {
            T::write(v, context)
        } else {
            unreachable!("write should be call by serialize")
        }
    }

    fn serialize(&self, context: &mut WriteContext) {
        match self {
            Some(v) => {
                // ref flag
                context.writer.i8(RefFlag::NotNullValue as i8);
                // type
                context
                    .writer
                    .var_uint32(T::get_type_id(context.get_fory()));
                v.write(context);
            }
            None => {
                context.writer.i8(RefFlag::Null as i8);
            }
        }
    }

    fn reserved_space() -> usize {
        std::mem::size_of::<T>()
    }

    fn get_type_id(fory: &Fory) -> u32 {
        T::get_type_id(fory)
    }
}

impl<T: Serializer> ForyGeneralList for Option<T> {}
