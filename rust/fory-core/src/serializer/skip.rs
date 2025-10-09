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
use crate::serializer::collection::{HAS_NULL, IS_SAME_TYPE};
use crate::serializer::Serializer;
use crate::types::{RefFlag, TypeId, BASIC_TYPES, CONTAINER_TYPES, PRIMITIVE_TYPES};
use chrono::{NaiveDate, NaiveDateTime};

pub fn get_read_ref_flag(field_type: &NullableFieldType) -> bool {
    let nullable = field_type.nullable;
    nullable || !PRIMITIVE_TYPES.contains(&field_type.type_id)
}

macro_rules! basic_type_deserialize {
    ($tid:expr, $context:expr; $(($ty:ty, $id:ident)),+ $(,)?) => {
        $(
            if $tid == TypeId::$id {
                <$ty as Serializer>::fory_read_type_info($context, true);
                <$ty as Serializer>::fory_read_data($context, true)?;
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
        let ref_flag = context.reader.read_i8();
        if field_type.nullable && ref_flag == (RefFlag::Null as i8) {
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
                    let length = context.reader.read_varuint32() as usize;
                    if length == 0 {
                        return Ok(());
                    }
                    let header = context.reader.read_u8();
                    let has_null = (header & HAS_NULL) != 0;
                    let is_same_type = (header & IS_SAME_TYPE) != 0;
                    let skip_ref_flag = is_same_type && !has_null;
                    let elem_type = field_type.generics.first().unwrap();
                    for _ in 0..length {
                        skip_field_value(context, elem_type, !skip_ref_flag)?;
                    }
                } else if type_id == TypeId::MAP {
                    let length = context.reader.read_varuint32();
                    if length == 0 {
                        return Ok(());
                    }
                    let mut len_counter = 0;
                    let key_type = field_type.generics.first().unwrap();
                    let value_type = field_type.generics.get(1).unwrap();
                    loop {
                        if len_counter == length {
                            break;
                        }
                        let header = context.reader.read_u8();
                        if header & crate::serializer::map::KEY_NULL != 0
                            && header & crate::serializer::map::VALUE_NULL != 0
                        {
                            len_counter += 1;
                            continue;
                        }
                        if header & crate::serializer::map::KEY_NULL != 0 {
                            // let read_ref_flag = get_read_ref_flag(value_type);
                            skip_field_value(context, value_type, false)?;
                            len_counter += 1;
                            continue;
                        }
                        if header & crate::serializer::map::VALUE_NULL != 0 {
                            // let read_ref_flag = get_read_ref_flag(key_type);
                            skip_field_value(context, key_type, false)?;
                            len_counter += 1;
                            continue;
                        }
                        let chunk_size = context.reader.read_u8();
                        for _ in (0..chunk_size).enumerate() {
                            // let read_ref_flag = get_read_ref_flag(key_type);
                            skip_field_value(context, key_type, false)?;
                            // let read_ref_flag = get_read_ref_flag(value_type);
                            skip_field_value(context, value_type, false)?;
                        }
                        len_counter += chunk_size as u32;
                    }
                }
                Ok(())
            } else if type_id == TypeId::NAMED_ENUM {
                let _ordinal = context.reader.read_varuint32();
                Ok(())
            } else if type_id == TypeId::NAMED_COMPATIBLE_STRUCT {
                let remote_type_id = context.reader.read_varuint32();
                assert_eq!(type_id_num, remote_type_id);
                let meta_index = context.reader.read_varuint32();
                let type_meta = context.get_meta(meta_index as usize);
                let field_infos = type_meta.get_field_infos().to_vec();
                for field_info in field_infos.iter() {
                    let nullable_field_type =
                        NullableFieldType::from(field_info.field_type.clone());
                    let read_ref_flag = get_read_ref_flag(&nullable_field_type);
                    skip_field_value(context, &nullable_field_type, read_ref_flag)?;
                }
                Ok(())
            } else if type_id == TypeId::NAMED_EXT {
                let remote_type_id = context.reader.read_varuint32();
                assert_eq!(type_id_num, remote_type_id);
                let meta_index = context.reader.read_varuint32();
                let type_meta = context.get_meta(meta_index as usize);
                let type_resolver = context.get_fory().get_type_resolver();
                type_resolver
                    .get_ext_name_harness(&type_meta.get_namespace(), &type_meta.get_type_name())
                    .get_read_data_fn()(context, true)?;
                Ok(())
            } else {
                unreachable!("unimplemented type: {:?}", type_id);
            }
        }
        Err(_) => {
            let internal_id = type_id_num & 0xff;
            const COMPATIBLE_STRUCT_ID: u32 = TypeId::COMPATIBLE_STRUCT as u32;
            const EXT_ID: u32 = TypeId::EXT as u32;
            const ENUM_ID: u32 = TypeId::ENUM as u32;
            if internal_id == COMPATIBLE_STRUCT_ID {
                let remote_type_id = context.reader.read_varuint32();
                let meta_index = context.reader.read_varuint32();
                let type_meta = context.get_meta(meta_index as usize);
                assert_eq!(remote_type_id, type_meta.get_type_id());
                let field_infos = type_meta.get_field_infos().to_vec();
                for field_info in field_infos.iter() {
                    let nullable_field_type =
                        NullableFieldType::from(field_info.field_type.clone());
                    let read_ref_flag = get_read_ref_flag(&nullable_field_type);
                    skip_field_value(context, &nullable_field_type, read_ref_flag)?;
                }
            } else if internal_id == ENUM_ID {
                let _ordinal = context.reader.read_varuint32();
            } else if internal_id == EXT_ID {
                let remote_type_id = context.reader.read_varuint32();
                assert_eq!(remote_type_id, type_id_num);
                let type_resolver = context.get_fory().get_type_resolver();
                type_resolver
                    .get_ext_harness(type_id_num)
                    .get_read_data_fn()(context, true)?;
            } else {
                unreachable!("unimplemented skipped type: {:?}", type_id_num);
            }
            Ok(())
        }
    }
}
