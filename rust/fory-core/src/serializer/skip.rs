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

use crate::context::ReadContext;
use crate::ensure;
use crate::error::Error;
use crate::meta::FieldType;
use crate::serializer::collection::{DECL_ELEMENT_TYPE, HAS_NULL, IS_SAME_TYPE};
use crate::serializer::util;
use crate::type_id as types;
use crate::util::ENABLE_FORY_DEBUG_OUTPUT;
use crate::RefFlag;
use std::rc::Rc;

#[cold]
#[inline(never)]
#[allow(unreachable_code)]
pub fn skip_field_value(
    context: &mut ReadContext,
    field_type: &FieldType,
    read_ref_flag: bool,
) -> Result<(), Error> {
    skip_value(context, field_type, read_ref_flag, true, &None)
}

#[inline(always)]
fn unknown_field_type() -> FieldType {
    FieldType::new(types::UNKNOWN, true, Vec::new())
}

#[inline(always)]
fn skip_bytes(context: &mut ReadContext, len: usize) -> Result<(), Error> {
    context.reader.check_bound(len)?;
    context.reader.move_next(len);
    Ok(())
}

#[cold]
#[inline(never)]
fn skip_sized_bytes(context: &mut ReadContext, element_size: Option<usize>) -> Result<(), Error> {
    let size_bytes = context.reader.read_var_u32()? as usize;
    if let Some(element_size) = element_size {
        if size_bytes % element_size != 0 {
            return Err(Error::invalid_data("Invalid data length"));
        }
    }
    skip_bytes(context, size_bytes)
}

#[cold]
#[inline(never)]
fn skip_string(context: &mut ReadContext) -> Result<(), Error> {
    let header = context.reader.read_var_u36_small()?;
    let len = (header >> 2) as usize;
    let encoding = header & 0b11;
    match encoding {
        // Latin1 and UTF-16 have no validation beyond readable bytes in the current reader.
        0 | 1 => skip_bytes(context, len),
        2 => {
            if context.is_check_string_read() {
                let start = context.reader.get_cursor();
                context.reader.check_bound(len)?;
                let bytes = context.reader.sub_slice(start, start + len)?;
                std::str::from_utf8(bytes)
                    .map_err(|_| Error::encoding_error("invalid UTF-8 string"))?;
            }
            skip_bytes(context, len)
        }
        _ => Err(Error::encoding_error(format!(
            "wrong encoding value: {}",
            encoding
        ))),
    }
}

#[cold]
#[inline(never)]
fn skip_decimal(context: &mut ReadContext) -> Result<(), Error> {
    let _scale = context.reader.read_var_i32()?;
    let header = context.reader.read_var_u64()?;
    if (header & 1) == 0 {
        return Ok(());
    }

    let meta = header >> 1;
    let len = (meta >> 1) as usize;
    if len == 0 {
        return Err(Error::invalid_data(
            "invalid decimal magnitude length 0".to_string(),
        ));
    }
    let start = context.reader.get_cursor();
    context.reader.check_bound(len)?;
    let payload = context.reader.sub_slice(start, start + len)?;
    if payload[len - 1] == 0 {
        return Err(Error::invalid_data(
            "non-canonical decimal payload: trailing zero byte".to_string(),
        ));
    }
    skip_bytes(context, len)
}

#[cold]
#[inline(never)]
pub fn skip_any_value(context: &mut ReadContext, read_ref_flag: bool) -> Result<(), Error> {
    // Handle ref flag first if needed
    if read_ref_flag {
        let ref_flag = context.reader.read_i8()?;
        if ref_flag == (RefFlag::Null as i8) {
            return Ok(());
        }
        if ref_flag == (RefFlag::Ref as i8) {
            // Reference to already-seen object, skip the reference index
            let _ref_index = context.reader.read_var_u32()?;
            return Ok(());
        }
        // RefValue (0) or NotNullValue (-1) means we need to read the actual object
    }

    // Read type_id first
    let type_id = context.reader.read_u8()? as u32;
    if type_id == types::UNKNOWN {
        return Err(Error::type_error(
            "UNKNOWN type id cannot appear as a concrete value",
        ));
    }
    let internal_id = type_id;
    let _user_type_id = if types::needs_user_type_id(type_id) && type_id != types::COMPATIBLE_STRUCT
    {
        Some(context.reader.read_var_u32()?)
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
            FieldType::new(type_id, true, vec![unknown_field_type()]),
            None,
        ),
        types::MAP => (
            FieldType::new(
                type_id,
                true,
                vec![unknown_field_type(), unknown_field_type()],
            ),
            None,
        ),
        types::COMPATIBLE_STRUCT | types::NAMED_COMPATIBLE_STRUCT => {
            // For compatible struct types, read type meta inline using streaming protocol
            let type_info = context.read_type_meta()?;
            (FieldType::new(type_id, true, Vec::new()), Some(type_info))
        }
        types::NAMED_ENUM | types::NAMED_EXT | types::NAMED_STRUCT | types::NAMED_UNION => {
            if context.is_share_meta() {
                let type_info = context.read_type_meta()?;
                (FieldType::new(type_id, true, Vec::new()), Some(type_info))
            } else {
                let namespace = context.read_meta_string()?.to_owned();
                let type_name = context.read_meta_string()?.to_owned();
                let rc_namespace = Rc::from(namespace);
                let rc_type_name = Rc::from(type_name);
                let type_info = context
                    .get_type_resolver()
                    .get_type_info_by_meta_string_name(rc_namespace, rc_type_name)
                    .ok_or_else(|| crate::Error::type_error("Name harness not found"))?;
                (FieldType::new(type_id, true, Vec::new()), Some(type_info))
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
                FieldType::new_with_user_type_id(
                    type_id,
                    _user_type_id.unwrap_or(u32::MAX),
                    true,
                    false,
                    Vec::new(),
                ),
                type_info,
            )
        }
    };
    // Don't read ref flag again in skip_value since we already handled it.
    // Pass type_info so skip_struct doesn't try to read type_id/meta_index again.
    skip_value(context, &field_type, false, false, &type_info_opt)
}

#[cold]
#[inline(never)]
fn skip_collection(context: &mut ReadContext, field_type: &FieldType) -> Result<(), Error> {
    let length = context.reader.read_var_u32()? as usize;
    if length == 0 {
        return Ok(());
    }
    let header = context.reader.read_u8()?;
    let has_null = (header & HAS_NULL) != 0;
    let is_same_type = (header & IS_SAME_TYPE) != 0;
    let skip_ref_flag = is_same_type && !has_null;
    let is_declared = (header & DECL_ELEMENT_TYPE) != 0;
    if field_type.generics.is_empty() {
        return Err(Error::invalid_data("empty generics"));
    }
    let default_elem_type = field_type.generics.first().unwrap();
    let (type_info, elem_field_type);
    let elem_type = if is_same_type && !is_declared {
        let type_info_rc = context.read_any_type_info()?;
        elem_field_type = FieldType::new_with_user_type_id(
            type_info_rc.get_type_id() as u32,
            type_info_rc.get_user_type_id(),
            has_null,
            false,
            Vec::new(),
        );
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

#[cold]
#[inline(never)]
fn skip_map(context: &mut ReadContext, field_type: &FieldType) -> Result<(), Error> {
    let length = context.reader.read_var_u32()?;
    if length == 0 {
        return Ok(());
    }
    let mut len_counter = 0;
    if field_type.generics.len() < 2 {
        return Err(Error::invalid_data("map must have at least 2 generics"));
    }
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
                value_field_type = FieldType::new_with_user_type_id(
                    type_info.get_type_id() as u32,
                    type_info.get_user_type_id(),
                    true,
                    false,
                    Vec::new(),
                );
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
                key_field_type = FieldType::new_with_user_type_id(
                    type_info.get_type_id() as u32,
                    type_info.get_user_type_id(),
                    true,
                    false,
                    Vec::new(),
                );
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
            key_field_type = FieldType::new_with_user_type_id(
                type_info.get_type_id() as u32,
                type_info.get_user_type_id(),
                true,
                false,
                Vec::new(),
            );
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
            value_field_type = FieldType::new_with_user_type_id(
                type_info.get_type_id() as u32,
                type_info.get_user_type_id(),
                true,
                false,
                Vec::new(),
            );
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

#[cold]
#[inline(never)]
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

#[cold]
#[inline(never)]
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
#[cold]
#[inline(never)]
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
            let _ref_index = context.reader.read_var_u32()?;
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
        } else if type_id_num == types::ENUM || type_id_num == types::NAMED_ENUM {
            let _ordinal = context.reader.read_var_u32()?;
            return Ok(());
        } else if type_id_num == types::UNION
            || type_id_num == types::TYPED_UNION
            || type_id_num == types::NAMED_UNION
        {
            let _ordinal = context.reader.read_var_u32()?;
            return skip_any_value(context, true);
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
        // ============ UNKNOWN ============
        types::UNKNOWN => {
            // UNKNOWN is used for polymorphic types in cross-language serialization
            return skip_any_value(context, false);
        }

        // ============ BOOL ============
        types::BOOL => {
            let _ = context.reader.read_u8()?;
        }

        // ============ INT8 ============
        types::INT8 => {
            let _ = context.reader.read_i8()?;
        }

        // ============ INT16 ============
        types::INT16 => {
            let _ = context.reader.read_i16()?;
        }

        // ============ INT32 ============
        types::INT32 => {
            context.reader.read_i32()?;
        }

        // ============ VARINT32 ============
        types::VARINT32 => {
            let _ = context.reader.read_var_i32()?;
        }

        // ============ INT64 ============
        types::INT64 => {
            context.reader.read_i64()?;
        }

        // ============ VARINT64 ============
        types::VARINT64 => {
            let _ = context.reader.read_var_i64()?;
        }

        // ============ TAGGED_INT64 ============
        types::TAGGED_INT64 => {
            context.reader.read_tagged_i64()?;
        }

        // ============ UINT8 ============
        types::UINT8 => {
            let _ = context.reader.read_u8()?;
        }

        // ============ UINT16 ============
        types::UINT16 => {
            let _ = context.reader.read_u16()?;
        }

        // ============ UINT32 ============
        types::UINT32 => {
            context.reader.read_u32()?;
        }

        // ============ VAR_UINT32 ============
        types::VAR_UINT32 => {
            let _ = context.reader.read_var_u32()?;
        }

        // ============ UINT64 ============
        types::UINT64 => {
            context.reader.read_u64()?;
        }

        // ============ VAR_UINT64 ============
        types::VAR_UINT64 => {
            let _ = context.reader.read_var_u64()?;
        }

        // ============ TAGGED_UINT64 ============
        types::TAGGED_UINT64 => {
            context.reader.read_tagged_u64()?;
        }

        // ============ FLOAT16 ============
        types::FLOAT16 => {
            let _ = context.reader.read_f16()?;
        }

        // ============ BFLOAT16 ============
        types::BFLOAT16 => {
            let _ = context.reader.read_bf16()?;
        }

        // ============ FLOAT32 ============
        types::FLOAT32 => {
            let _ = context.reader.read_f32()?;
        }

        // ============ FLOAT64 ============
        types::FLOAT64 => {
            let _ = context.reader.read_f64()?;
        }

        // ============ STRING ============
        types::STRING => {
            skip_string(context)?;
        }

        // ============ LIST ============
        // ============ SET ============
        types::LIST | types::SET => {
            return skip_collection(context, field_type);
        }

        // ============ MAP ============
        types::MAP => {
            return skip_map(context, field_type);
        }

        // ============ ENUM ============
        types::ENUM => {
            let _ordinal = context.reader.read_var_u32()?;
        }

        // ============ NAMED_ENUM ============
        types::NAMED_ENUM => {
            let _ordinal = context.reader.read_var_u32()?;
        }

        // ============ STRUCT ============
        types::STRUCT => {
            return skip_struct(context, type_id_num, type_info);
        }

        // ============ COMPATIBLE_STRUCT ============
        types::COMPATIBLE_STRUCT => {
            return skip_struct(context, type_id_num, type_info);
        }

        // ============ NAMED_STRUCT ============
        types::NAMED_STRUCT => {
            return skip_struct(context, type_id_num, type_info);
        }

        // ============ NAMED_COMPATIBLE_STRUCT ============
        types::NAMED_COMPATIBLE_STRUCT => {
            return skip_struct(context, type_id_num, type_info);
        }

        // ============ EXT ============
        types::EXT => {
            return skip_ext(context, type_id_num, type_info);
        }

        // ============ NAMED_EXT ============
        types::NAMED_EXT => {
            return skip_ext(context, type_id_num, type_info);
        }

        // ============ UNION ============
        types::UNION => {
            let _ = context.reader.read_var_u32()?;
            return skip_any_value(context, true);
        }

        // ============ TYPED_UNION ============
        types::TYPED_UNION => {
            let _ = context.reader.read_var_u32()?;
            return skip_any_value(context, true);
        }

        // ============ NAMED_UNION ============
        types::NAMED_UNION => {
            let _ = context.reader.read_var_u32()?;
            return skip_any_value(context, true);
        }

        // ============ NONE ============
        types::NONE => {
            return Ok(());
        }

        // ============ DURATION ============
        types::DURATION => {
            let _ = context.reader.read_var_i64()?;
            let _ = context.reader.read_i32()?;
        }

        // ============ TIMESTAMP ============
        types::TIMESTAMP => {
            let _ = context.reader.read_i64()?;
            let _ = context.reader.read_u32()?;
        }

        // ============ DATE ============
        types::DATE => {
            if context.is_xlang() {
                let _ = context.reader.read_var_i64()?;
            } else {
                let _ = context.reader.read_i32()?;
            }
        }

        // ============ DECIMAL ============
        types::DECIMAL => {
            skip_decimal(context)?;
        }

        // ============ BINARY ============
        types::BINARY => {
            skip_sized_bytes(context, None)?;
        }

        // ============ BOOL_ARRAY ============
        types::BOOL_ARRAY => {
            skip_sized_bytes(context, Some(1))?;
        }

        // ============ INT8_ARRAY ============
        types::INT8_ARRAY => {
            skip_sized_bytes(context, Some(1))?;
        }

        // ============ INT16_ARRAY ============
        types::INT16_ARRAY => {
            skip_sized_bytes(context, Some(2))?;
        }

        // ============ INT32_ARRAY ============
        types::INT32_ARRAY => {
            skip_sized_bytes(context, Some(4))?;
        }

        // ============ INT64_ARRAY ============
        types::INT64_ARRAY => {
            skip_sized_bytes(context, Some(8))?;
        }

        // ============ UINT8_ARRAY ============
        types::UINT8_ARRAY => {
            skip_sized_bytes(context, Some(1))?;
        }

        // ============ UINT16_ARRAY ============
        types::UINT16_ARRAY => {
            skip_sized_bytes(context, Some(2))?;
        }

        // ============ UINT32_ARRAY ============
        types::UINT32_ARRAY => {
            skip_sized_bytes(context, Some(4))?;
        }

        // ============ UINT64_ARRAY ============
        types::UINT64_ARRAY => {
            skip_sized_bytes(context, Some(8))?;
        }

        // ============ FLOAT16_ARRAY ============
        types::FLOAT16_ARRAY => {
            skip_sized_bytes(context, Some(2))?;
        }

        // ============ BFLOAT16_ARRAY ============
        types::BFLOAT16_ARRAY => {
            skip_sized_bytes(context, Some(2))?;
        }

        // ============ FLOAT32_ARRAY ============
        types::FLOAT32_ARRAY => {
            skip_sized_bytes(context, Some(4))?;
        }

        // ============ FLOAT64_ARRAY ============
        types::FLOAT64_ARRAY => {
            skip_sized_bytes(context, Some(8))?;
        }

        // ============ Rust-specific types ============

        // ============ U128 ============
        types::U128 => {
            let _ = context.reader.read_u128()?;
        }

        // ============ INT128 ============
        types::INT128 => {
            let _ = context.reader.read_i128()?;
        }

        // ============ USIZE ============
        types::USIZE => {
            let _ = context.reader.read_usize()?;
        }

        // ============ ISIZE ============
        types::ISIZE => {
            let _ = context.reader.read_isize()?;
        }

        // ============ U128_ARRAY ============
        types::U128_ARRAY => {
            skip_sized_bytes(context, Some(16))?;
        }

        // ============ INT128_ARRAY ============
        types::INT128_ARRAY => {
            skip_sized_bytes(context, Some(16))?;
        }

        // ============ USIZE_ARRAY ============
        types::USIZE_ARRAY => {
            skip_sized_bytes(context, Some(std::mem::size_of::<usize>()))?;
        }

        // ============ ISIZE_ARRAY ============
        types::ISIZE_ARRAY => {
            skip_sized_bytes(context, Some(std::mem::size_of::<isize>()))?;
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
            let field_type = FieldType::new(types::LIST, false, vec![unknown_field_type()]);
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
