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
use crate::meta::FieldType;
use crate::resolver::context::ReadContext;
use crate::serializer::Serializer;
use crate::types::{RefFlag, TypeId, BASIC_TYPES, COLLECTION_TYPES};
use anyhow::anyhow;
use chrono::{NaiveDate, NaiveDateTime};

macro_rules! basic_type_deserialize {
    ($tid:expr, $context:expr; $(($ty:ty, $id:ident)),+ $(,)?) => {
        $(
            if $tid == TypeId::$id {
                <$ty>::deserialize($context)?;
                return Ok(());
            }
        )+else {
            unreachable!()
        }
    };
}

pub fn skip_field_value(context: &mut ReadContext, field_type: &FieldType) -> Result<(), Error> {
    match TypeId::try_from(field_type.type_id) {
        Ok(type_id) => {
            if BASIC_TYPES.contains(&type_id) {
                basic_type_deserialize!(type_id, context;
                    (bool, BOOL),
                    (i8, INT8),
                    (i16, INT16),
                    (i32, INT32),
                    (i64, INT64),
                    (f32, FLOAT32),
                    (f64, FLOAT64),
                    (String, STRING),
                    (NaiveDate, LOCAL_DATE),
                    (NaiveDateTime, TIMESTAMP),
                );
            } else if COLLECTION_TYPES.contains(&type_id) {
                let ref_flag = context.reader.i8();
                let actual_type_id = context.reader.i16();
                let type_id_num = type_id.into();
                ensure!(
                    actual_type_id == type_id_num,
                    anyhow!("Invalid field type, expected:{type_id_num}, actual:{actual_type_id}")
                );
                if ref_flag == (RefFlag::NotNullValue as i8)
                    || ref_flag == (RefFlag::RefValue as i8)
                {
                    if type_id == TypeId::ARRAY || type_id == TypeId::SET {
                        let length = context.reader.var_int32() as usize;
                        println!("skipping array with length {}", length);
                        for _ in 0..length {
                            skip_field_value(context, field_type.generics.first().unwrap())?;
                        }
                    } else if type_id == TypeId::MAP {
                        let length = context.reader.var_int32() as usize;
                        for _ in 0..length {
                            skip_field_value(context, field_type.generics.first().unwrap())?;
                            skip_field_value(context, field_type.generics.get(1).unwrap())?;
                        }
                    }
                    Ok(())
                } else if ref_flag == (RefFlag::Null as i8) {
                    Err(anyhow!("Try to deserialize non-option type to null"))?
                } else if ref_flag == (RefFlag::Ref as i8) {
                    Err(Error::Ref)
                } else {
                    Err(anyhow!("Unknown ref flag, value:{ref_flag}"))?
                }
            } else {
                unreachable!()
            }
        }
        Err(_) => {
            // skip ref_flag and meta_index
            context.reader.i8();
            context.reader.i16();
            let type_defs: Vec<_> = context.meta_resolver.reading_type_defs.to_vec();
            for type_def in type_defs.iter() {
                if type_def.get_type_id() == field_type.type_id {
                    let field_infos: Vec<_> = type_def.get_field_infos().to_vec();
                    for field_info in field_infos.iter() {
                        skip_field_value(context, &field_info.field_type)?;
                    }
                }
            }
            Ok(())
        }
    }
}
