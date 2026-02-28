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
use crate::serializer::collection::{DECL_ELEMENT_TYPE, HAS_NULL, IS_SAME_TYPE};
use crate::serializer::util;
use crate::serializer::Serializer;
use crate::types;
use crate::types::RefFlag;
use crate::util::ENABLE_FORY_DEBUG_OUTPUT;
use chrono::{NaiveDate, NaiveDateTime};
use std::rc::Rc;
use std::time::Duration;

#[allow(unreachable_code)]
pub fn skip_field_value(
    context: &mut ReadContext,
    field_type: &FieldType,
    read_ref_flag: bool,
) -> Result<(), Error> {
    skip_value(context, field_type, read_ref_flag, true, &None)
}

const UNKNOWN_FIELD_TYPE: FieldType = FieldType {
    type_id: types::UNKNOWN,
    user_type_id: u32::MAX,
    nullable: true,
    track_ref: false,
    generics: vec![],
};

pub fn skip_any_value(context: &mut ReadContext, read_ref_flag: bool) -> Result<(), Error> {
    // Handle ref flag first if needed
    if read_ref_flag {
        let ref_flag = context.reader.read_i8()?;
        if ref_flag == (RefFlag::Null as i8) {
            return Ok(());
        }
        if ref_flag == (RefFlag::Ref as i8) {
            // Reference to already-seen object, skip the reference index
            let _ref_index = context.reader.read_varuint32()?;
            return Ok(());
        }
        // RefValue (0) or NotNullValue (-1) means we need to read the actual object
    }

    // Read type_id first
    let type_id = context.reader.read_u8()? as u32;
    let internal_id = type_id;
    let _user_type_id = if types::needs_user_type_id(type_id) && type_id != types::COMPATIBLE_STRUCT
    {
        Some(context.reader.read_varuint32()?)
    } else {
        None
    };

    // NONE type has no data - return early
    if internal_id == types::NONE {
        return Ok(());
    }

    // For struct-like types, also read meta_index to get type_info
    // This is critical for polymorphic collections where elements are struct types.
    let (field_type, type_info_opt) = match internal_id {
        types::LIST | types::SET => (
            FieldType {
                type_id,
                user_type_id: u32::MAX,
                nullable: true,
                track_ref: false,
                generics: vec![UNKNOWN_FIELD_TYPE],
            },
            None,
        ),
        types::MAP => (
            FieldType {
                type_id,
                user_type_id: u32::MAX,
                nullable: true,
                track_ref: false,
                generics: vec![UNKNOWN_FIELD_TYPE, UNKNOWN_FIELD_TYPE],
            },
            None,
        ),
        types::COMPATIBLE_STRUCT | types::NAMED_COMPATIBLE_STRUCT => {
            // For compatible struct types, read type meta inline using streaming protocol
            let type_info = context.read_type_meta()?;
            (
                FieldType {
                    type_id,
                    user_type_id: u32::MAX,
                    nullable: true,
                    track_ref: false,
                    generics: vec![],
                },
                Some(type_info),
            )
        }
        types::NAMED_ENUM | types::NAMED_EXT | types::NAMED_STRUCT | types::NAMED_UNION => {
            if context.is_share_meta() {
                let type_info = context.read_type_meta()?;
                (
                    FieldType {
                        type_id,
                        user_type_id: u32::MAX,
                        nullable: true,
                        track_ref: false,
                        generics: vec![],
                    },
                    Some(type_info),
                )
            } else {
                let namespace = context.read_meta_string()?.to_owned();
                let type_name = context.read_meta_string()?.to_owned();
                let rc_namespace = Rc::from(namespace);
                let rc_type_name = Rc::from(type_name);
                let type_info = context
                    .get_type_resolver()
                    .get_type_info_by_meta_string_name(rc_namespace, rc_type_name)
                    .ok_or_else(|| crate::Error::type_error("Name harness not found"))?;
                (
                    FieldType {
                        type_id,
                        user_type_id: u32::MAX,
                        nullable: true,
                        track_ref: false,
                        generics: vec![],
                    },
                    Some(type_info),
                )
            }
        }
        _ => {
            let type_info = if let Some(user_type_id) = _user_type_id {
                context
                    .get_type_resolver()
                    .get_user_type_info_by_id(user_type_id)
            } else {
                None
            };
            (
                FieldType {
                    type_id,
                    user_type_id: _user_type_id.unwrap_or(u32::MAX),
                    nullable: true,
                    track_ref: false,
                    generics: vec![],
                },
                type_info,
            )
        }
    };
    // Don't read ref flag again in skip_value since we already handled it.
    // Pass type_info so skip_struct doesn't try to read type_id/meta_index again.
    skip_value(context, &field_type, false, false, &type_info_opt)
}

fn skip_collection(context: &mut ReadContext, field_type: &FieldType) -> Result<(), Error> {
    let length = context.reader.read_varuint32()? as usize;
    if length == 0 {
        return Ok(());
    }
    let header = context.reader.read_u8()?;
    let has_null = (header & HAS_NULL) != 0;
    let is_same_type = (header & IS_SAME_TYPE) != 0;
    let skip_ref_flag = is_same_type && !has_null;
    let is_declared = (header & DECL_ELEMENT_TYPE) != 0;
    let default_elem_type = field_type.generics.first().unwrap();
    let (type_info, elem_field_type);
    let elem_type = if is_same_type && !is_declared {
        let type_info_rc = context.read_any_type_info()?;
        elem_field_type = FieldType {
            type_id: type_info_rc.get_type_id() as u32,
            user_type_id: type_info_rc.get_user_type_id(),
            nullable: has_null,
            track_ref: false,
            generics: vec![],
        };
        type_info = Some(type_info_rc);
        &elem_field_type
    } else {
        type_info = None;
        default_elem_type
    };
    context.inc_depth()?;
    for _ in 0..length {
        skip_value(context, elem_type, !skip_ref_flag, false, &type_info)?;
    }
    context.dec_depth();
    Ok(())
}

fn skip_map(context: &mut ReadContext, field_type: &FieldType) -> Result<(), Error> {
    let length = context.reader.read_varuint32()?;
    if length == 0 {
        return Ok(());
    }
    let mut len_counter = 0;
    let default_key_type = field_type.generics.first().unwrap();
    let default_value_type = field_type.generics.get(1).unwrap();
    loop {
        if len_counter == length {
            break;
        }
        let header = context.reader.read_u8()?;
        if header & crate::serializer::map::KEY_NULL != 0
            && header & crate::serializer::map::VALUE_NULL != 0
        {
            len_counter += 1;
            continue;
        }
        if header & crate::serializer::map::KEY_NULL != 0 {
            // Read value type info if not declared
            let value_declared = (header & crate::serializer::map::DECL_VALUE_TYPE) != 0;
            let (value_type_info, value_field_type);
            let value_type = if !value_declared {
                let type_info = context.read_any_type_info()?;
                value_field_type = FieldType {
                    type_id: type_info.get_type_id() as u32,
                    user_type_id: type_info.get_user_type_id(),
                    nullable: true,
                    track_ref: false,
                    generics: vec![],
                };
                value_type_info = Some(type_info);
                &value_field_type
            } else {
                value_type_info = None;
                default_value_type
            };
            context.inc_depth()?;
            skip_value(context, value_type, false, false, &value_type_info)?;
            context.dec_depth();
            len_counter += 1;
            continue;
        }
        if header & crate::serializer::map::VALUE_NULL != 0 {
            // Read key type info if not declared
            let key_declared = (header & crate::serializer::map::DECL_KEY_TYPE) != 0;
            let (key_type_info, key_field_type);
            let key_type = if !key_declared {
                let type_info = context.read_any_type_info()?;
                key_field_type = FieldType {
                    type_id: type_info.get_type_id() as u32,
                    user_type_id: type_info.get_user_type_id(),
                    nullable: true,
                    track_ref: false,
                    generics: vec![],
                };
                key_type_info = Some(type_info);
                &key_field_type
            } else {
                key_type_info = None;
                default_key_type
            };
            context.inc_depth()?;
            skip_value(context, key_type, false, false, &key_type_info)?;
            context.dec_depth();
            len_counter += 1;
            continue;
        }
        // Both key and value are non-null
        let chunk_size = context.reader.read_u8()?;
        let key_declared = (header & crate::serializer::map::DECL_KEY_TYPE) != 0;
        let value_declared = (header & crate::serializer::map::DECL_VALUE_TYPE) != 0;

        // Read key type info if not declared
        let (key_type_info, key_field_type);
        let key_type = if !key_declared {
            let type_info = context.read_any_type_info()?;
            key_field_type = FieldType {
                type_id: type_info.get_type_id() as u32,
                user_type_id: type_info.get_user_type_id(),
                nullable: true,
                track_ref: false,
                generics: vec![],
            };
            key_type_info = Some(type_info);
            &key_field_type
        } else {
            key_type_info = None;
            default_key_type
        };

        // Read value type info if not declared
        let (value_type_info, value_field_type);
        let value_type = if !value_declared {
            let type_info = context.read_any_type_info()?;
            value_field_type = FieldType {
                type_id: type_info.get_type_id() as u32,
                user_type_id: type_info.get_user_type_id(),
                nullable: true,
                track_ref: false,
                generics: vec![],
            };
            value_type_info = Some(type_info);
            &value_field_type
        } else {
            value_type_info = None;
            default_value_type
        };

        context.inc_depth()?;
        for _ in 0..chunk_size {
            skip_value(context, key_type, false, false, &key_type_info)?;
            skip_value(context, value_type, false, false, &value_type_info)?;
        }
        context.dec_depth();
        len_counter += chunk_size as u32;
    }
    Ok(())
}

fn skip_struct(
    context: &mut ReadContext,
    type_id_num: u32,
    type_info: &Option<Rc<crate::TypeInfo>>,
) -> Result<(), Error> {
    let type_info_rc: Option<Rc<crate::TypeInfo>>;
    let type_info_value = if type_info.is_none() {
        let remote_type_info = context.read_any_type_info()?;
        let remote_type_id = remote_type_info.get_type_id() as u32;
        if type_id_num != types::UNKNOWN && remote_type_id != types::UNKNOWN {
            ensure!(
                type_id_num == remote_type_id,
                Error::type_mismatch(type_id_num, remote_type_id)
            );
        }
        type_info_rc = Some(remote_type_info);
        type_info_rc.as_ref().unwrap()
    } else {
        type_info.as_ref().unwrap()
    };
    let type_meta = type_info_value.get_type_meta();
    if ENABLE_FORY_DEBUG_OUTPUT {
        eprintln!(
            "[skip_struct] type_name: {:?}, num_fields: {}",
            type_meta.get_type_name(),
            type_meta.get_field_infos().len()
        );
    }
    let field_infos = type_meta.get_field_infos().to_vec();
    context.inc_depth()?;
    for field_info in field_infos.iter() {
        if ENABLE_FORY_DEBUG_OUTPUT {
            eprintln!(
                "[skip_struct] field: {:?}, type_id: {}, internal_id: {}",
                field_info.field_name, field_info.field_type.type_id, field_info.field_type.type_id
            );
        }
        let read_ref_flag = util::field_need_write_ref_into(
            field_info.field_type.type_id,
            field_info.field_type.nullable,
        );
        skip_value(context, &field_info.field_type, read_ref_flag, true, &None)?;
    }
    context.dec_depth();
    Ok(())
}

fn skip_ext(
    context: &mut ReadContext,
    type_id_num: u32,
    type_info: &Option<Rc<crate::TypeInfo>>,
) -> Result<(), Error> {
    let type_info_rc: Option<Rc<crate::TypeInfo>>;
    let type_info_value = if type_info.is_none() {
        let remote_type_info = context.read_any_type_info()?;
        let remote_type_id = remote_type_info.get_type_id() as u32;
        ensure!(
            type_id_num == remote_type_id,
            Error::type_mismatch(type_id_num, remote_type_id)
        );
        type_info_rc = Some(remote_type_info);
        type_info_rc.as_ref().unwrap()
    } else {
        type_info.as_ref().unwrap()
    };
    type_info_value.get_harness().get_read_data_fn()(context)?;
    Ok(())
}

// call when is_field && is_compatible_mode
#[allow(unreachable_code)]
fn skip_value(
    context: &mut ReadContext,
    field_type: &FieldType,
    read_ref_flag: bool,
    _is_field: bool,
    type_info: &Option<Rc<crate::TypeInfo>>,
) -> Result<(), Error> {
    if read_ref_flag {
        let ref_flag = context.reader.read_i8()?;
        if ref_flag == (RefFlag::Null as i8) {
            return Ok(());
        }
        if ref_flag == (RefFlag::Ref as i8) {
            // Reference to already-seen object, skip the reference index
            let _ref_index = context.reader.read_varuint32()?;
            return Ok(());
        }
        // RefValue (0) or NotNullValue (-1) means we need to read the actual object
    }
    let type_id_num = field_type.type_id;

    if type_id_num == types::UNKNOWN {
        return skip_any_value(context, false);
    }

    // Handle user-defined types (struct/enum/ext/union)
    if types::is_user_type(type_id_num) {
        if type_id_num == types::COMPATIBLE_STRUCT
            || type_id_num == types::STRUCT
            || type_id_num == types::NAMED_STRUCT
            || type_id_num == types::NAMED_COMPATIBLE_STRUCT
        {
            return skip_struct(context, type_id_num, type_info);
        } else if type_id_num == types::ENUM
            || type_id_num == types::NAMED_ENUM
            || type_id_num == types::UNION
            || type_id_num == types::TYPED_UNION
            || type_id_num == types::NAMED_UNION
        {
            let _ordinal = context.reader.read_varuint32()?;
            return Ok(());
        } else if type_id_num == types::EXT || type_id_num == types::NAMED_EXT {
            return skip_ext(context, type_id_num, type_info);
        } else {
            return Err(Error::type_error(format!(
                "Unknown type id: {} (type_info provided: {})",
                type_id_num,
                type_info.is_some()
            )));
        }
    }

    // Match on built-in types (ordered by TypeId enum values)
    match type_id_num {
        // ============ UNKNOWN (TypeId = 0) ============
        types::UNKNOWN => {
            // UNKNOWN is used for polymorphic types in cross-language serialization
            return skip_any_value(context, false);
        }

        // ============ BOOL (TypeId = 1) ============
        types::BOOL => {
            <bool as Serializer>::fory_read_data(context)?;
        }

        // ============ INT8 (TypeId = 2) ============
        types::INT8 => {
            <i8 as Serializer>::fory_read_data(context)?;
        }

        // ============ INT16 (TypeId = 3) ============
        types::INT16 => {
            <i16 as Serializer>::fory_read_data(context)?;
        }

        // ============ INT32 (TypeId = 4) ============
        types::INT32 => {
            context.reader.read_i32()?;
        }

        // ============ VARINT32 (TypeId = 5) ============
        types::VARINT32 => {
            <i32 as Serializer>::fory_read_data(context)?;
        }

        // ============ INT64 (TypeId = 6) ============
        types::INT64 => {
            context.reader.read_i64()?;
        }

        // ============ VARINT64 (TypeId = 7) ============
        types::VARINT64 => {
            <i64 as Serializer>::fory_read_data(context)?;
        }

        // ============ TAGGED_INT64 (TypeId = 8) ============
        types::TAGGED_INT64 => {
            context.reader.read_tagged_i64()?;
        }

        // ============ UINT8 (TypeId = 9) ============
        types::UINT8 => {
            <u8 as Serializer>::fory_read_data(context)?;
        }

        // ============ UINT16 (TypeId = 10) ============
        types::UINT16 => {
            <u16 as Serializer>::fory_read_data(context)?;
        }

        // ============ UINT32 (TypeId = 11) ============
        types::UINT32 => {
            context.reader.read_u32()?;
        }

        // ============ VAR_UINT32 (TypeId = 12) ============
        types::VAR_UINT32 => {
            <u32 as Serializer>::fory_read_data(context)?;
        }

        // ============ UINT64 (TypeId = 13) ============
        types::UINT64 => {
            context.reader.read_u64()?;
        }

        // ============ VAR_UINT64 (TypeId = 14) ============
        types::VAR_UINT64 => {
            <u64 as Serializer>::fory_read_data(context)?;
        }

        // ============ TAGGED_UINT64 (TypeId = 15) ============
        types::TAGGED_UINT64 => {
            context.reader.read_tagged_u64()?;
        }

        // ============ FLOAT16 (TypeId = 17) ============
        types::FLOAT16 => {
            <crate::float16::float16 as Serializer>::fory_read_data(context)?;
        }

        // ============ FLOAT32 (TypeId = 17) ============
        types::FLOAT32 => {
            <f32 as Serializer>::fory_read_data(context)?;
        }

        // ============ FLOAT64 (TypeId = 18) ============
        types::FLOAT64 => {
            <f64 as Serializer>::fory_read_data(context)?;
        }

        // ============ STRING (TypeId = 19) ============
        types::STRING => {
            <String as Serializer>::fory_read_data(context)?;
        }

        // ============ LIST (TypeId = 20) ============
        // ============ SET (TypeId = 21) ============
        types::LIST | types::SET => {
            return skip_collection(context, field_type);
        }

        // ============ MAP (TypeId = 22) ============
        types::MAP => {
            return skip_map(context, field_type);
        }

        // ============ ENUM (TypeId = 23) ============
        types::ENUM => {
            let _ordinal = context.reader.read_varuint32()?;
        }

        // ============ NAMED_ENUM (TypeId = 24) ============
        types::NAMED_ENUM => {
            let _ordinal = context.reader.read_varuint32()?;
        }

        // ============ STRUCT (TypeId = 25) ============
        types::STRUCT => {
            return skip_struct(context, type_id_num, type_info);
        }

        // ============ COMPATIBLE_STRUCT (TypeId = 26) ============
        types::COMPATIBLE_STRUCT => {
            return skip_struct(context, type_id_num, type_info);
        }

        // ============ NAMED_STRUCT (TypeId = 27) ============
        types::NAMED_STRUCT => {
            return skip_struct(context, type_id_num, type_info);
        }

        // ============ NAMED_COMPATIBLE_STRUCT (TypeId = 28) ============
        types::NAMED_COMPATIBLE_STRUCT => {
            return skip_struct(context, type_id_num, type_info);
        }

        // ============ EXT (TypeId = 29) ============
        types::EXT => {
            return skip_ext(context, type_id_num, type_info);
        }

        // ============ NAMED_EXT (TypeId = 30) ============
        types::NAMED_EXT => {
            return skip_ext(context, type_id_num, type_info);
        }

        // ============ UNION (TypeId = 31) ============
        types::UNION => {
            let _ = context.reader.read_varuint32()?;
            return skip_any_value(context, true);
        }

        // ============ TYPED_UNION (TypeId = 32) ============
        types::TYPED_UNION => {
            let _ = context.reader.read_varuint32()?;
            return skip_any_value(context, true);
        }

        // ============ NAMED_UNION (TypeId = 33) ============
        types::NAMED_UNION => {
            let _ = context.reader.read_varuint32()?;
            return skip_any_value(context, true);
        }

        // ============ NONE (TypeId = 34) ============
        types::NONE => {
            return Ok(());
        }

        // ============ DURATION (TypeId = 35) ============
        types::DURATION => {
            <Duration as Serializer>::fory_read_data(context)?;
        }

        // ============ TIMESTAMP (TypeId = 36) ============
        types::TIMESTAMP => {
            <NaiveDateTime as Serializer>::fory_read_data(context)?;
        }

        // ============ DATE (TypeId = 37) ============
        types::DATE => {
            <NaiveDate as Serializer>::fory_read_data(context)?;
        }

        // ============ BINARY (TypeId = 39) ============
        types::BINARY => {
            <Vec<u8> as Serializer>::fory_read_data(context)?;
        }

        // ============ BOOL_ARRAY (TypeId = 41) ============
        types::BOOL_ARRAY => {
            <Vec<bool> as Serializer>::fory_read_data(context)?;
        }

        // ============ INT8_ARRAY (TypeId = 42) ============
        types::INT8_ARRAY => {
            <Vec<i8> as Serializer>::fory_read_data(context)?;
        }

        // ============ INT16_ARRAY (TypeId = 43) ============
        types::INT16_ARRAY => {
            <Vec<i16> as Serializer>::fory_read_data(context)?;
        }

        // ============ INT32_ARRAY (TypeId = 44) ============
        types::INT32_ARRAY => {
            <Vec<i32> as Serializer>::fory_read_data(context)?;
        }

        // ============ INT64_ARRAY (TypeId = 45) ============
        types::INT64_ARRAY => {
            <Vec<i64> as Serializer>::fory_read_data(context)?;
        }

        // ============ UINT8_ARRAY (TypeId = 46) ============
        types::UINT8_ARRAY => {
            <Vec<u8> as Serializer>::fory_read_data(context)?;
        }

        // ============ UINT16_ARRAY (TypeId = 47) ============
        types::UINT16_ARRAY => {
            <Vec<u16> as Serializer>::fory_read_data(context)?;
        }

        // ============ UINT32_ARRAY (TypeId = 48) ============
        types::UINT32_ARRAY => {
            <Vec<u32> as Serializer>::fory_read_data(context)?;
        }

        // ============ UINT64_ARRAY (TypeId = 49) ============
        types::UINT64_ARRAY => {
            <Vec<u64> as Serializer>::fory_read_data(context)?;
        }

        // ============ FLOAT16_ARRAY (TypeId = 53) ============
        types::FLOAT16_ARRAY => {
            <Vec<crate::float16::float16> as Serializer>::fory_read_data(context)?;
        }

        // ============ FLOAT32_ARRAY (TypeId = 51) ============
        types::FLOAT32_ARRAY => {
            <Vec<f32> as Serializer>::fory_read_data(context)?;
        }

        // ============ FLOAT64_ARRAY (TypeId = 52) ============
        types::FLOAT64_ARRAY => {
            <Vec<f64> as Serializer>::fory_read_data(context)?;
        }

        // ============ Rust-specific types ============

        // ============ U128 (TypeId = 64) ============
        types::U128 => {
            <u128 as Serializer>::fory_read_data(context)?;
        }

        // ============ INT128 (TypeId = 65) ============
        types::INT128 => {
            <i128 as Serializer>::fory_read_data(context)?;
        }

        // ============ USIZE (TypeId = 66) ============
        types::USIZE => {
            <usize as Serializer>::fory_read_data(context)?;
        }

        // ============ ISIZE (TypeId = 67) ============
        types::ISIZE => {
            <isize as Serializer>::fory_read_data(context)?;
        }

        // ============ U128_ARRAY (TypeId = 68) ============
        types::U128_ARRAY => {
            <Vec<u128> as Serializer>::fory_read_data(context)?;
        }

        // ============ INT128_ARRAY (TypeId = 69) ============
        types::INT128_ARRAY => {
            <Vec<i128> as Serializer>::fory_read_data(context)?;
        }

        // ============ USIZE_ARRAY (TypeId = 70) ============
        types::USIZE_ARRAY => {
            <Vec<usize> as Serializer>::fory_read_data(context)?;
        }

        // ============ ISIZE_ARRAY (TypeId = 71) ============
        types::ISIZE_ARRAY => {
            <Vec<isize> as Serializer>::fory_read_data(context)?;
        }

        _ => {
            return Err(Error::type_error(format!(
                "Unimplemented type id: {}",
                type_id_num
            )));
        }
    }
    Ok(())
}

/// Skip enum variant data in compatible mode based on variant type.
///
/// # Arguments
/// * `context` - The read context
/// * `variant_type` - The variant type encoded in lower 2 bits:
///   - 0b0 = Unit variant (no data to skip)
///   - 0b1 = Unnamed variant (tuple data)
///   - 0b10 = Named variant (struct-like data)
/// * `type_info` - Optional type info for named variants (must be provided for 0b10)
pub fn skip_enum_variant(
    context: &mut ReadContext,
    variant_type: u32,
    type_info: &Option<Rc<crate::TypeInfo>>,
) -> Result<(), Error> {
    match variant_type {
        0b0 => {
            // Unit variant, no data to skip
            Ok(())
        }
        0b1 => {
            // Unnamed variant, skip tuple data (which is serialized as a collection)
            // Tuple uses collection format but doesn't write type info, so skip directly
            let field_type = FieldType {
                type_id: types::LIST,
                user_type_id: u32::MAX,
                nullable: false,
                track_ref: false,
                generics: vec![UNKNOWN_FIELD_TYPE],
            };
            skip_collection(context, &field_type)
        }
        0b10 => {
            // Named variant, skip struct-like data using skip_struct
            // For named variants, we need the type_info which should have been read already
            if type_info.is_some() {
                let type_id = type_info.as_ref().unwrap().get_type_id() as u32;
                skip_struct(context, type_id, type_info)
            } else {
                // If no type_info provided, read it inline using streaming protocol
                let type_info_rc = context.read_type_meta()?;
                let type_id = type_info_rc.get_type_id() as u32;
                let type_info_opt = Some(type_info_rc);
                skip_struct(context, type_id, &type_info_opt)
            }
        }
        _ => {
            // Invalid variant type
            Err(Error::type_error(format!(
                "Invalid enum variant type: {}",
                variant_type
            )))
        }
    }
}
