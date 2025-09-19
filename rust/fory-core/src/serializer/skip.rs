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
use crate::meta::NullableFieldType;
use crate::resolver::context::ReadContext;
use crate::serializer::collection::HAS_NULL;
use crate::serializer::Serializer;
use crate::types::{RefFlag, TypeId, BASIC_TYPES, CONTAINER_TYPES};
use chrono::{NaiveDate, NaiveDateTime};

macro_rules! basic_type_deserialize {
    ($tid:expr, $context:expr; $(($ty:ty, $id:ident)),+ $(,)?) => {
        $(
            if $tid == TypeId::$id {
                <$ty>::read($context, true)?;
                return Ok(());
            }
        )+else {
            unreachable!()
        }
    };
}

// call when is_field && is_compatible_mode
#[allow(unreachable_code)]
pub fn skip_field_value(
    context: &mut ReadContext,
    field_type: &NullableFieldType,
    read_ref_flag: bool,
) -> Result<(), Error> {
    if read_ref_flag {
        let ref_flag = context.reader.i8();
        if field_type.nullable
            && ref_flag != (RefFlag::NotNullValue as i8)
            && ref_flag != (RefFlag::RefValue as i8)
        {
            return Ok(());
        }
    }
    let type_id_num = field_type.type_id;
    match TypeId::try_from(type_id_num as i16) {
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
                    (Vec<u8> , BINARY),
                    (Vec<bool> , BOOL_ARRAY),
                    (Vec<i8> , INT8_ARRAY),
                    (Vec<i16> , INT16_ARRAY),
                    (Vec<i32> , INT32_ARRAY),
                    (Vec<i64> , INT64_ARRAY),
                    (Vec<f32>, FLOAT32_ARRAY),
                    (Vec<f64> , FLOAT64_ARRAY),
                );
            } else if CONTAINER_TYPES.contains(&type_id) {
                if type_id == TypeId::LIST || type_id == TypeId::SET {
                    let length = context.reader.var_uint32() as usize;
                    // todo
                    let header = context.reader.u8();
                    let read_ref_flag = (header & HAS_NULL) != 0;
                    let _elem_type = context.reader.var_uint32();
                    for _ in 0..length {
                        skip_field_value(
                            context,
                            field_type.generics.first().unwrap(),
                            read_ref_flag,
                        )?;
                    }
                } else if type_id == TypeId::MAP {
                    todo!();
                    let length = context.reader.var_uint32() as usize;
                    for _ in 0..length {
                        skip_field_value(context, field_type.generics.first().unwrap(), true)?;
                        skip_field_value(context, field_type.generics.get(1).unwrap(), true)?;
                    }
                }
                Ok(())
            } else {
                unreachable!()
            }
        }
        Err(_) => {
            let tag = type_id_num & 0xff;
            if tag == TypeId::COMPATIBLE_STRUCT as u32 {
                let remote_type_id = context.reader.var_uint32();
                let meta_index = context.reader.var_uint32();
                let type_def = context.get_meta(meta_index as usize);
                assert_eq!(remote_type_id, type_def.get_type_id());
                let field_infos: Vec<_> = type_def.get_field_infos().to_vec();
                for field_info in field_infos.iter() {
                    let nullable_field_type =
                        NullableFieldType::from(field_info.field_type.clone());
                    skip_field_value(context, &nullable_field_type, true)?;
                }
            } else if tag == TypeId::ENUM as u32 {
                context
                    .fory
                    .get_type_resolver()
                    .get_harness(type_id_num)
                    .unwrap()
                    .get_deserializer()(context)?;
            } else {
                unimplemented!()
            }
            Ok(())
        }
    }
}
