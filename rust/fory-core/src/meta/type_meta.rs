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

use crate::buffer::{Reader, Writer};
use crate::error::Error;
use crate::meta::{
    Encoding, MetaString, MetaStringDecoder, FIELD_NAME_DECODER, FIELD_NAME_ENCODER,
    NAMESPACE_DECODER, TYPE_NAME_DECODER,
};
use crate::resolver::{TypeInfo, TypeResolver};
use crate::type_id::{
    TypeId, BINARY, COMPATIBLE_STRUCT, ENUM, EXT, NAMED_COMPATIBLE_STRUCT, NAMED_ENUM, NAMED_EXT,
    NAMED_STRUCT, NAMED_UNION, STRUCT, TYPED_UNION, UINT8_ARRAY, UNION, UNKNOWN,
};
use crate::util::{murmurhash3_x64_128, to_snake_case};

/// Normalizes a type ID for comparison purposes in cross-language schema evolution.
/// This treats all struct variants (STRUCT, COMPATIBLE_STRUCT, NAMED_STRUCT,
/// NAMED_COMPATIBLE_STRUCT) and UNKNOWN as equivalent to STRUCT.
/// UNKNOWN (0) is used for polymorphic types (interfaces) in cross-language serialization.
/// Similarly for ENUM, EXT, and UNION variants. Dense byte arrays stay distinct here because schema
/// equality and schema hashes must not turn compatibility-only byte-sequence assignment into
/// same-schema equality.
fn normalize_type_id_for_eq(type_id: u32) -> u32 {
    match type_id {
        // All struct variants and UNKNOWN normalize to STRUCT
        _ if type_id == STRUCT
            || type_id == COMPATIBLE_STRUCT
            || type_id == NAMED_STRUCT
            || type_id == NAMED_COMPATIBLE_STRUCT
            || type_id == UNKNOWN =>
        {
            STRUCT
        }
        // All enum variants normalize to ENUM
        _ if type_id == ENUM || type_id == NAMED_ENUM => ENUM,
        // All ext variants normalize to EXT
        _ if type_id == EXT || type_id == NAMED_EXT => EXT,
        // All union variants normalize to UNION
        _ if type_id == UNION || type_id == TYPED_UNION || type_id == NAMED_UNION => UNION,
        // Everything else stays the same
        _ => type_id,
    }
}

fn compatible_scalar_type_id(type_id: u32) -> bool {
    matches!(
        type_id,
        crate::type_id::BOOL
            | crate::type_id::INT8
            | crate::type_id::INT16
            | crate::type_id::INT32
            | crate::type_id::VARINT32
            | crate::type_id::INT64
            | crate::type_id::VARINT64
            | crate::type_id::TAGGED_INT64
            | crate::type_id::UINT8
            | crate::type_id::UINT16
            | crate::type_id::UINT32
            | crate::type_id::VAR_UINT32
            | crate::type_id::UINT64
            | crate::type_id::VAR_UINT64
            | crate::type_id::TAGGED_UINT64
            | crate::type_id::FLOAT16
            | crate::type_id::BFLOAT16
            | crate::type_id::FLOAT32
            | crate::type_id::FLOAT64
            | crate::type_id::STRING
            | crate::type_id::DECIMAL
    )
}
use std::clone::Clone;
use std::cmp::min;
use std::collections::HashMap;
use std::rc::Rc;

const SMALL_NUM_FIELDS_THRESHOLD: usize = 0b11111;
const MAX_TYPE_META_FIELDS: usize = i16::MAX as usize;
const MAX_COMPATIBLE_MATCHED_FIELD_INDEX: usize = (i16::MAX as usize - 1) / 2;
const REGISTER_BY_NAME_FLAG: u8 = 0b0010_0000;
const COMPATIBLE_TYPEDEF_FLAG: u8 = 0b0100_0000;
const STRUCT_TYPEDEF_FLAG: u8 = 0b1000_0000;
const FIELD_NAME_SIZE_THRESHOLD: usize = 0b1111;
/// Marker value in encoding bits to indicate field ID mode (instead of field name)
const FIELD_ID_ENCODING_MARKER: u8 = 0b11;
/// Threshold for field ID that fits in 4-bit size field
const SMALL_FIELD_ID_THRESHOLD: i16 = 0b1111;

const BIG_NAME_THRESHOLD: usize = 0b111111;

const META_SIZE_MASK: i64 = 0xff;
const COMPRESS_META_FLAG: i64 = 0b1 << 8;
const RESERVED_META_FLAGS: i64 = 0b111 << 9;
const NUM_HASH_BITS: i8 = 52;
const TYPE_META_HASH_SHIFT: u32 = 64 - NUM_HASH_BITS as u32;
const NO_USER_TYPE_ID: u32 = u32::MAX;
const MAX_HASH32: u64 = (1 << 31) - 1;

pub static NAMESPACE_ENCODINGS: &[Encoding] = &[
    Encoding::Utf8,
    Encoding::AllToLowerSpecial,
    Encoding::LowerUpperDigitSpecial,
];

pub static TYPE_NAME_ENCODINGS: &[Encoding] = &[
    Encoding::Utf8,
    Encoding::AllToLowerSpecial,
    Encoding::LowerUpperDigitSpecial,
    Encoding::FirstToLowerSpecial,
];

static FIELD_NAME_ENCODINGS: &[Encoding] = &[
    Encoding::Utf8,
    Encoding::AllToLowerSpecial,
    Encoding::LowerUpperDigitSpecial,
];

#[inline(always)]
fn is_struct_type_def_kind(type_id: u32) -> bool {
    type_id == STRUCT
        || type_id == COMPATIBLE_STRUCT
        || type_id == NAMED_STRUCT
        || type_id == NAMED_COMPATIBLE_STRUCT
}

#[inline(always)]
fn is_named_type_def_kind(type_id: u32) -> bool {
    type_id == NAMED_STRUCT
        || type_id == NAMED_COMPATIBLE_STRUCT
        || type_id == NAMED_ENUM
        || type_id == NAMED_EXT
        || type_id == NAMED_UNION
}

fn non_struct_kind_code(type_id: u32) -> Result<u8, Error> {
    match type_id {
        x if x == ENUM => Ok(0),
        x if x == NAMED_ENUM => Ok(1),
        x if x == EXT => Ok(2),
        x if x == NAMED_EXT => Ok(3),
        x if x == TYPED_UNION => Ok(4),
        x if x == NAMED_UNION => Ok(5),
        _ => Err(Error::invalid_data(format!(
            "unsupported TypeMeta kind {type_id}"
        ))),
    }
}

fn non_struct_type_id(kind_code: u8) -> Result<u32, Error> {
    match kind_code {
        0 => Ok(ENUM),
        1 => Ok(NAMED_ENUM),
        2 => Ok(EXT),
        3 => Ok(NAMED_EXT),
        4 => Ok(TYPED_UNION),
        5 => Ok(NAMED_UNION),
        _ => Err(Error::invalid_data(format!(
            "unsupported TypeMeta kind code {kind_code}"
        ))),
    }
}

#[inline(always)]
fn validate_type_meta_header(header: i64) -> Result<(), Error> {
    if (header & RESERVED_META_FLAGS) != 0 {
        return Err(Error::invalid_data("invalid TypeMeta global header"));
    }
    if (header & COMPRESS_META_FLAG) != 0 {
        return Err(Error::invalid_data("compressed TypeMeta is not supported"));
    }
    Ok(())
}

#[inline(always)]
fn read_type_meta_body_size(reader: &mut Reader, header: i64) -> Result<usize, Error> {
    let mut meta_size = (header & META_SIZE_MASK) as usize;
    if meta_size == META_SIZE_MASK as usize {
        meta_size = meta_size
            .checked_add(reader.read_var_u32()? as usize)
            .ok_or_else(|| Error::invalid_data("invalid TypeMeta metadata size"))?;
    }
    Ok(meta_size)
}

#[inline(always)]
fn type_meta_hash_bits(body: &[u8], header_low_bits: u64) -> u64 {
    let mut hash_input = Vec::with_capacity(body.len() + 2);
    hash_input.extend_from_slice(body);
    hash_input.push(header_low_bits as u8);
    hash_input.push((header_low_bits >> 8) as u8);
    let hash_value = murmurhash3_x64_128(&hash_input, 47).0 as i64;
    hash_value.wrapping_shl(TYPE_META_HASH_SHIFT).wrapping_abs() as u64
}

#[inline(always)]
fn validate_type_meta_body_hash(header: i64, body: &[u8]) -> Result<(), Error> {
    let hash_mask = u64::MAX << TYPE_META_HASH_SHIFT;
    let expected_hash = type_meta_hash_bits(body, (header as u64) & !hash_mask);
    if ((header as u64) & hash_mask) != (expected_hash & hash_mask) {
        return Err(Error::invalid_data("TypeMeta metadata hash mismatch"));
    }
    Ok(())
}

#[derive(Eq, Clone)]
pub struct FieldType {
    pub type_id: u32,
    pub user_type_id: u32,
    pub nullable: bool,
    pub track_ref: bool,
    pub generics: Vec<FieldType>,
    compatible_fingerprint: u64,
}

impl FieldType {
    pub fn new(type_id: u32, nullable: bool, generics: Vec<FieldType>) -> Self {
        Self::new_with_user_type_id(type_id, NO_USER_TYPE_ID, nullable, false, generics)
    }

    pub(crate) fn new_with_user_type_id(
        type_id: u32,
        user_type_id: u32,
        nullable: bool,
        track_ref: bool,
        generics: Vec<FieldType>,
    ) -> Self {
        let compatible_fingerprint = compute_field_type_fingerprint(type_id, &generics);
        Self {
            type_id,
            user_type_id,
            nullable,
            track_ref,
            generics,
            compatible_fingerprint,
        }
    }

    pub fn new_with_ref(
        type_id: u32,
        nullable: bool,
        track_ref: bool,
        generics: Vec<FieldType>,
    ) -> Self {
        Self::new_with_user_type_id(type_id, NO_USER_TYPE_ID, nullable, track_ref, generics)
    }

    #[inline(always)]
    pub(crate) fn compatible_fingerprint(&self) -> u64 {
        self.compatible_fingerprint
    }

    #[inline(always)]
    pub(crate) fn exact_shape_match(&self, other: &Self) -> bool {
        if normalize_type_id_for_eq(self.type_id) != normalize_type_id_for_eq(other.type_id)
            || self.nullable != other.nullable
            || self.track_ref != other.track_ref
            || self.generics.len() != other.generics.len()
        {
            return false;
        }
        self.generics
            .iter()
            .zip(other.generics.iter())
            .all(|(left, right)| left.exact_shape_match(right))
    }

    #[inline(always)]
    pub(crate) fn compatible_shape_match(&self, other: &Self) -> bool {
        if compatible_scalar_type_id(self.type_id) || compatible_scalar_type_id(other.type_id) {
            return !self.track_ref && !other.track_ref && self.type_id == other.type_id;
        }
        if normalize_type_id_for_eq(self.type_id) != normalize_type_id_for_eq(other.type_id) {
            return false;
        }
        if self.generics.len() != other.generics.len() {
            return collection_missing_generics_match(self, other);
        }
        if self.type_id == TypeId::MAP as u32 {
            return self
                .generics
                .iter()
                .zip(other.generics.iter())
                .all(|(left, right)| map_entry_shape_match(left, right));
        }
        self.generics
            .iter()
            .zip(other.generics.iter())
            .all(|(left, right)| left.compatible_shape_match(right))
    }

    fn to_bytes(&self, writer: &mut Writer, write_flag: bool, nullable: bool) -> Result<(), Error> {
        let mut header = self.type_id;
        if header == NAMED_ENUM {
            header = ENUM;
        } else if header == TypeId::NAMED_UNION as u32 || header == TypeId::TYPED_UNION as u32 {
            header = TypeId::UNION as u32;
        }
        if write_flag {
            header <<= 2;
            if nullable {
                header |= 2;
            }
            if self.track_ref {
                header |= 1;
            }
            writer.write_var_u32(header);
        } else {
            writer.write_u8(header as u8);
        }
        match self.type_id {
            x if x == TypeId::LIST as u32 || x == TypeId::SET as u32 => {
                if let Some(generic) = self.generics.first() {
                    generic.to_bytes(writer, true, generic.nullable)?;
                } else {
                    let generic = FieldType::new(TypeId::UNKNOWN as u32, true, vec![]);
                    generic.to_bytes(writer, true, generic.nullable)?;
                }
            }
            x if x == TypeId::MAP as u32 => {
                if let (Some(key_generic), Some(val_generic)) =
                    (self.generics.first(), self.generics.get(1))
                {
                    key_generic.to_bytes(writer, true, key_generic.nullable)?;
                    val_generic.to_bytes(writer, true, val_generic.nullable)?;
                }
            }
            _ => {}
        }
        Ok(())
    }

    fn from_bytes(
        reader: &mut Reader,
        read_flag: bool,
        nullable: Option<bool>,
    ) -> Result<Self, Error> {
        let header = if read_flag {
            reader.read_var_u32()?
        } else {
            reader.read_u8()? as u32
        };
        let mut type_id;
        let _nullable;
        let _ref_tracking;
        if read_flag {
            type_id = header >> 2;
            _ref_tracking = (header & 1) != 0;
            _nullable = (header & 2) != 0;
        } else {
            type_id = header;
            _nullable = match nullable {
                Some(value) => value,
                None => {
                    return Err(Error::invalid_data("missing TypeMeta field nullability"));
                }
            };
            _ref_tracking = false;
        }
        if type_id == NAMED_ENUM {
            type_id = ENUM;
        } else if type_id == TypeId::NAMED_UNION as u32 || type_id == TypeId::TYPED_UNION as u32 {
            type_id = TypeId::UNION as u32;
        }
        let user_type_id = NO_USER_TYPE_ID;
        Ok(match type_id {
            x if x == TypeId::LIST as u32 || x == TypeId::SET as u32 => {
                let generic = Self::from_bytes(reader, true, None)?;
                Self::new_with_user_type_id(
                    type_id,
                    user_type_id,
                    _nullable,
                    _ref_tracking,
                    vec![generic],
                )
            }
            x if x == TypeId::MAP as u32 => {
                let key_generic = Self::from_bytes(reader, true, None)?;
                let val_generic = Self::from_bytes(reader, true, None)?;
                Self::new_with_user_type_id(
                    type_id,
                    user_type_id,
                    _nullable,
                    _ref_tracking,
                    vec![key_generic, val_generic],
                )
            }
            _ => {
                Self::new_with_user_type_id(type_id, user_type_id, _nullable, _ref_tracking, vec![])
            }
        })
    }
}

#[derive(Debug, PartialEq, Eq, Clone)]
pub struct FieldInfo {
    pub field_id: i16,
    pub field_name: String,
    pub field_type: FieldType,
}

impl FieldInfo {
    pub fn new(field_name: &str, field_type: FieldType) -> FieldInfo {
        FieldInfo {
            field_id: -1i16,
            field_name: field_name.to_string(),
            field_type,
        }
    }

    pub fn new_with_id(field_id: i16, field_name: &str, field_type: FieldType) -> FieldInfo {
        FieldInfo {
            field_id,
            field_name: field_name.to_string(),
            field_type,
        }
    }

    fn u8_to_encoding(value: u8) -> Result<Encoding, Error> {
        match value {
            0x00 => Ok(Encoding::Utf8),
            0x01 => Ok(Encoding::AllToLowerSpecial),
            0x02 => Ok(Encoding::LowerUpperDigitSpecial),
            _ => Err(Error::encoding_error(format!(
                "Unsupported encoding of field name in type meta, value:{value}"
            )))?,
        }
    }

    pub fn from_bytes(reader: &mut Reader) -> Result<FieldInfo, Error> {
        let header = reader.read_u8()?;
        let nullable = (header & 2) != 0;
        let track_ref = (header & 1) != 0;
        let encoding_bits = (header >> 6) & 0b11;

        // Check if this is field ID mode (encoding bits == 0b11)
        if encoding_bits == FIELD_ID_ENCODING_MARKER {
            // Field ID mode: | 0b11:2bits | field_id_low:4bits | nullable:1bit | track_ref:1bit |
            let mut field_id = ((header >> 2) & FIELD_NAME_SIZE_THRESHOLD as u8) as i16;
            if field_id == SMALL_FIELD_ID_THRESHOLD {
                field_id = field_id
                    .checked_add(reader.read_var_u32()? as i16)
                    .ok_or_else(|| Error::invalid_data("field_id overflow"))?;
            }

            let mut field_type = FieldType::from_bytes(reader, false, Option::from(nullable))?;
            field_type.track_ref = track_ref;

            Ok(FieldInfo {
                field_id,
                field_name: String::new(), // No field name when using ID encoding
                field_type,
            })
        } else {
            // Field name mode (original behavior)
            let encoding = Self::u8_to_encoding(encoding_bits)?;
            let mut name_size = ((header >> 2) & FIELD_NAME_SIZE_THRESHOLD as u8) as usize;
            if name_size == FIELD_NAME_SIZE_THRESHOLD {
                name_size += reader.read_var_u32()? as usize;
            }
            name_size += 1;

            let mut field_type = FieldType::from_bytes(reader, false, Option::from(nullable))?;
            field_type.track_ref = track_ref;

            let field_name_bytes = reader.read_bytes(name_size)?;

            let field_name = FIELD_NAME_DECODER.decode(field_name_bytes, encoding)?;
            Ok(FieldInfo {
                field_id: -1i16,
                field_name: field_name.original,
                field_type,
            })
        }
    }

    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut buffer = vec![];
        let mut writer = Writer::from_buffer(&mut buffer);
        let nullable = self.field_type.nullable;
        let track_ref = self.field_type.track_ref;

        // Use field ID encoding if:
        // 1. field_id >= 0 (user-set or matched from local type), OR
        // 2. field_name is empty (ID-encoded field that couldn't be matched - use ID even if -1)
        if self.field_id >= 0 || self.field_name.is_empty() {
            // Field ID mode: | 0b11:2bits | field_id_low:4bits | nullable:1bit | track_ref:1bit |
            // Use max(0, field_id) to handle unmatched fields that have field_id = -1
            let field_id = std::cmp::max(0, self.field_id);
            let mut header: u8 = (min(SMALL_FIELD_ID_THRESHOLD, field_id) as u8) << 2;
            if track_ref {
                header |= 1;
            }
            if nullable {
                header |= 2;
            }
            // Set encoding bits to 0b11 to indicate field ID mode
            header |= FIELD_ID_ENCODING_MARKER << 6;
            writer.write_u8(header);
            if field_id >= SMALL_FIELD_ID_THRESHOLD {
                writer.write_var_u32((field_id - SMALL_FIELD_ID_THRESHOLD) as u32);
            }
            self.field_type.to_bytes(&mut writer, false, nullable)?;
            // No field name written in ID mode
        } else {
            // Field name mode (original behavior)
            // field_bytes: | header | type_info | field_name |
            // header: | field_name_encoding:2bits | size:4bits | nullability:1bit | track_ref:1bit |
            let meta_string =
                FIELD_NAME_ENCODER.encode_with_encodings(&self.field_name, FIELD_NAME_ENCODINGS)?;
            let name_encoded = meta_string.bytes.as_slice();
            let name_size = name_encoded.len() - 1;
            let mut header: u8 = (min(FIELD_NAME_SIZE_THRESHOLD, name_size) as u8) << 2;
            if track_ref {
                header |= 1;
            }
            if nullable {
                header |= 2;
            }
            let encoding_idx = FIELD_NAME_ENCODINGS
                .iter()
                .position(|x| *x == meta_string.encoding)
                .unwrap() as u8;
            header |= encoding_idx << 6;
            writer.write_u8(header);
            if name_size >= FIELD_NAME_SIZE_THRESHOLD {
                writer.write_var_u32((name_size - FIELD_NAME_SIZE_THRESHOLD) as u32);
            }
            self.field_type.to_bytes(&mut writer, false, nullable)?;
            // write field_name
            writer.write_bytes(name_encoded);
        }
        Ok(buffer)
    }
}

const FNV_OFFSET_BASIS: u64 = 14695981039346656037;
const FNV_PRIME: u64 = 1099511628211;

#[inline(always)]
fn fnv1a_hash_bytes(mut hash: u64, bytes: &[u8]) -> u64 {
    for &b in bytes {
        hash ^= b as u64;
        hash = hash.wrapping_mul(FNV_PRIME);
    }
    hash
}

#[inline(always)]
fn fnv1a_hash_u8(hash: u64, value: u8) -> u64 {
    fnv1a_hash_bytes(hash, &[value])
}

#[inline(always)]
fn fnv1a_hash_u32(hash: u64, value: u32) -> u64 {
    fnv1a_hash_bytes(hash, &value.to_le_bytes())
}

#[inline(always)]
fn fnv1a_hash_u64(hash: u64, value: u64) -> u64 {
    fnv1a_hash_bytes(hash, &value.to_le_bytes())
}

#[inline(always)]
fn compatible_fingerprint_type_id(type_id: u32) -> u32 {
    match type_id {
        _ if type_id == STRUCT
            || type_id == COMPATIBLE_STRUCT
            || type_id == NAMED_STRUCT
            || type_id == NAMED_COMPATIBLE_STRUCT
            || type_id == UNKNOWN =>
        {
            STRUCT
        }
        _ if type_id == ENUM || type_id == NAMED_ENUM => ENUM,
        _ if type_id == EXT || type_id == NAMED_EXT => EXT,
        _ if type_id == BINARY || type_id == UINT8_ARRAY => BINARY,
        _ if type_id == TypeId::INT32 as u32 || type_id == TypeId::VARINT32 as u32 => {
            TypeId::VARINT32 as u32
        }
        _ if type_id == TypeId::INT64 as u32
            || type_id == TypeId::VARINT64 as u32
            || type_id == TypeId::TAGGED_INT64 as u32 =>
        {
            TypeId::VARINT64 as u32
        }
        _ if type_id == TypeId::UINT32 as u32 || type_id == TypeId::VAR_UINT32 as u32 => {
            TypeId::VAR_UINT32 as u32
        }
        _ if type_id == TypeId::UINT64 as u32
            || type_id == TypeId::VAR_UINT64 as u32
            || type_id == TypeId::TAGGED_UINT64 as u32 =>
        {
            TypeId::VAR_UINT64 as u32
        }
        _ => type_id,
    }
}

#[inline(always)]
fn same_numeric_wire_shape(left: u32, right: u32) -> bool {
    let left = compatible_fingerprint_type_id(left);
    left == compatible_fingerprint_type_id(right)
        && (left == TypeId::VARINT32 as u32
            || left == TypeId::VARINT64 as u32
            || left == TypeId::VAR_UINT32 as u32
            || left == TypeId::VAR_UINT64 as u32)
}

#[inline(always)]
fn collection_missing_generics_match(local: &FieldType, remote: &FieldType) -> bool {
    matches!(
        normalize_type_id_for_eq(local.type_id),
        x if x == TypeId::LIST as u32 || x == TypeId::SET as u32 || x == TypeId::MAP as u32
    ) && (local.generics.is_empty() || remote.generics.is_empty())
}

#[inline(always)]
fn map_entry_shape_match(local: &FieldType, remote: &FieldType) -> bool {
    if local.exact_shape_match(remote) || local.compatible_shape_match(remote) {
        return true;
    }
    // Map readers consume key/value payloads through the remote entry FieldType.
    // Fixed and variable integer encodings are therefore payload-shape variants
    // for the same Rust key/value carrier, not top-level scalar conversions.
    !local.track_ref
        && !remote.track_ref
        && local.nullable == remote.nullable
        && local.generics.is_empty()
        && remote.generics.is_empty()
        && same_numeric_wire_shape(local.type_id, remote.type_id)
}

#[inline(always)]
fn compute_field_type_fingerprint(type_id: u32, generics: &[FieldType]) -> u64 {
    let mut hash = fnv1a_hash_u32(FNV_OFFSET_BASIS, compatible_fingerprint_type_id(type_id));
    hash = fnv1a_hash_u32(hash, generics.len() as u32);
    for generic in generics {
        hash = fnv1a_hash_u64(hash, generic.compatible_fingerprint());
    }
    hash
}

impl std::fmt::Debug for FieldType {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("FieldType")
            .field("type_id", &self.type_id)
            .field("user_type_id", &self.user_type_id)
            .field("nullable", &self.nullable)
            .field("track_ref", &self.track_ref)
            .field("generics", &self.generics)
            .finish()
    }
}

#[inline(always)]
fn hash_field_type(mut hash: u64, field_type: &FieldType) -> u64 {
    let type_id = normalize_type_id_for_eq(field_type.type_id);
    hash = fnv1a_hash_u32(hash, type_id);
    hash = fnv1a_hash_u32(hash, field_type.user_type_id);
    hash = fnv1a_hash_u8(hash, field_type.nullable as u8);
    hash = fnv1a_hash_u8(hash, field_type.track_ref as u8);
    hash = fnv1a_hash_u32(hash, field_type.generics.len() as u32);
    for generic in &field_type.generics {
        hash = hash_field_type(hash, generic);
    }
    hash
}

#[inline(always)]
fn compute_schema_hash(field_infos: &[FieldInfo]) -> i64 {
    let mut hash = fnv1a_hash_u32(FNV_OFFSET_BASIS, field_infos.len() as u32);
    for field in field_infos {
        hash = fnv1a_hash_bytes(hash, field.field_name.as_bytes());
        hash = hash_field_type(hash, &field.field_type);
    }
    hash as i64
}

#[inline(always)]
pub fn compute_field_hash(hash: u32, id: i16) -> u32 {
    let mut next_hash = (hash as u64) * 31 + (id as u64);
    while next_hash >= MAX_HASH32 {
        next_hash /= 7;
    }
    next_hash as u32
}

#[inline(always)]
pub fn compute_struct_hash(field_ids: impl IntoIterator<Item = i16>) -> u32 {
    field_ids.into_iter().fold(17u32, compute_field_hash)
}

#[inline(always)]
fn scalar_numeric_type(type_id: u32) -> bool {
    matches!(
        type_id,
        x if x == TypeId::INT8 as u32
            || x == TypeId::INT16 as u32
            || x == TypeId::INT32 as u32
            || x == TypeId::VARINT32 as u32
            || x == TypeId::INT64 as u32
            || x == TypeId::VARINT64 as u32
            || x == TypeId::TAGGED_INT64 as u32
            || x == TypeId::UINT8 as u32
            || x == TypeId::UINT16 as u32
            || x == TypeId::UINT32 as u32
            || x == TypeId::VAR_UINT32 as u32
            || x == TypeId::UINT64 as u32
            || x == TypeId::VAR_UINT64 as u32
            || x == TypeId::TAGGED_UINT64 as u32
            || x == TypeId::FLOAT16 as u32
            || x == TypeId::BFLOAT16 as u32
            || x == TypeId::FLOAT32 as u32
            || x == TypeId::FLOAT64 as u32
            || x == TypeId::DECIMAL as u32
    )
}

#[inline(always)]
fn scalar_conversion_type(type_id: u32) -> bool {
    type_id == TypeId::BOOL as u32
        || type_id == TypeId::STRING as u32
        || scalar_numeric_type(type_id)
}

#[inline(always)]
fn scalar_types_compatible(local: u32, remote: u32) -> bool {
    if local == remote {
        return scalar_conversion_type(local);
    }
    let local_numeric = scalar_numeric_type(local);
    let remote_numeric = scalar_numeric_type(remote);
    (local == TypeId::BOOL as u32 && (remote == TypeId::STRING as u32 || remote_numeric))
        || (remote == TypeId::BOOL as u32 && (local == TypeId::STRING as u32 || local_numeric))
        || (local == TypeId::STRING as u32 && remote_numeric)
        || (remote == TypeId::STRING as u32 && local_numeric)
        || (local_numeric && remote_numeric)
}

#[inline(always)]
pub(crate) fn compatible_scalar_field_pair(local: &FieldType, remote: &FieldType) -> bool {
    !local.track_ref
        && !remote.track_ref
        && (local.type_id != remote.type_id || local.nullable != remote.nullable)
        && scalar_types_compatible(local.type_id, remote.type_id)
}

/// Sorts field infos according to the provided sorted field names and assigns field IDs.
///
/// This function takes a vector of field infos and a slice of sorted field names,
/// then reorders the field infos to match the sorted order. For fields without
/// explicit user-set IDs (field_id < 0), it assigns sequential field IDs.
/// Fields with user-set IDs (field_id >= 0) preserve their original IDs.
///
/// # Arguments
///
/// * `fields_info` - A mutable vector of FieldInfo to be sorted and assigned IDs
/// * `sorted_field_names` - A slice of field names in the desired sorted order
///
/// # Errors
///
/// Returns an error if a field name in `sorted_field_names` is not found in `fields_info`
pub fn sort_fields(
    fields_info: &mut Vec<FieldInfo>,
    sorted_field_names: &[&str],
) -> Result<(), Error> {
    let mut sorted_field_infos: Vec<FieldInfo> = Vec::with_capacity(fields_info.len());
    for name in sorted_field_names.iter() {
        let mut found = false;
        for i in 0..fields_info.len() {
            if &fields_info[i].field_name == name {
                // swap_remove is faster
                sorted_field_infos.push(fields_info.swap_remove(i));
                found = true;
                break;
            }
        }
        if !found {
            return Err(Error::type_error(format!(
                "Field {} not found in fields_info",
                name
            )));
        }
    }
    // Keep field IDs as-is:
    // - Fields with explicit #[fory(id = N)] have field_id >= 0 (use ID encoding)
    // - Fields without explicit ID have field_id = -1 (use field name encoding)
    // This ensures schema evolution works correctly with field name matching
    *fields_info = sorted_field_infos;
    Ok(())
}

impl PartialEq for FieldType {
    fn eq(&self, other: &Self) -> bool {
        self.exact_shape_match(other)
    }
}

#[doc(hidden)]
pub fn assign_remote_field_ids(
    local_field_infos: &[FieldInfo],
    field_infos: &mut [FieldInfo],
) -> Result<(), Error> {
    // Build maps for both name-based and ID-based lookup.
    // The value is the sorted local index, not the field's ID attribute.
    let field_index_by_name: HashMap<String, (usize, &FieldInfo)> = local_field_infos
        .iter()
        .enumerate()
        .filter(|(_, f)| f.field_id < 0 && !f.field_name.is_empty())
        .map(|(i, f)| (f.field_name.clone(), (i, f)))
        .collect();

    let field_index_by_id: HashMap<i16, (usize, &FieldInfo)> = local_field_infos
        .iter()
        .enumerate()
        .filter(|(_, f)| f.field_id >= 0)
        .map(|(i, f)| (f.field_id, (i, f)))
        .collect();

    let mut used_local_fields = vec![false; local_field_infos.len()];
    for field in field_infos.iter_mut() {
        let local_match = if field.field_id >= 0 {
            field_index_by_id.get(&field.field_id).copied()
        } else {
            let snake_case_name = to_snake_case(&field.field_name);
            field_index_by_name.get(&snake_case_name).copied()
        };

        match local_match {
            Some((sorted_index, local_info)) => {
                if used_local_fields[sorted_index] {
                    return Err(Error::type_error(format!(
                        "Remote field {} duplicates local field {} in compatible metadata",
                        field.field_name, local_info.field_name
                    )));
                }
                let exact_field = local_info.field_type.exact_shape_match(&field.field_type);
                if !exact_field
                    && !crate::serializer::codec::compatible_field_pair(
                        &local_info.field_type,
                        &field.field_type,
                    )
                {
                    if crate::util::ENABLE_FORY_DEBUG_OUTPUT {
                        eprintln!(
                            "[fory-debug] schema-incompatible field: name={}, remote_type={:?}, local_type={:?}",
                            field.field_name, field.field_type, local_info.field_type
                        );
                    }
                    return Err(Error::type_error(format!(
                        "Cannot read remote field {} as local field {}: remote and local field schemas are not compatible",
                        field.field_name, local_info.field_name
                    )));
                }
                // ID-encoded remote fields have no name. Copying the local name keeps later
                // metadata reserialization and diagnostics tied to the matched local field.
                if field.field_name.is_empty() {
                    field.field_name = local_info.field_name.clone();
                }
                if sorted_index > MAX_COMPATIBLE_MATCHED_FIELD_INDEX {
                    return Err(Error::type_error(format!(
                        "Cannot assign compatible matched id for local field {}: local field index {} exceeds max {}",
                        local_info.field_name, sorted_index, MAX_COMPATIBLE_MATCHED_FIELD_INDEX
                    )));
                }
                field.field_id = if exact_field {
                    (sorted_index * 2) as i16
                } else {
                    (sorted_index * 2 + 1) as i16
                };
                used_local_fields[sorted_index] = true;
                if crate::util::ENABLE_FORY_DEBUG_OUTPUT {
                    eprintln!(
                        "[fory-debug]   matched field: name={}, assigned_field_id={}, remote_type={:?}, local_type={:?}",
                        field.field_name, field.field_id, field.field_type, local_info.field_type
                    );
                }
            }
            None => {
                if crate::util::ENABLE_FORY_DEBUG_OUTPUT {
                    eprintln!(
                        "[fory-debug] no local match for field: name={}",
                        field.field_name
                    );
                }
                field.field_id = -1;
            }
        }
    }
    Ok(())
}

#[derive(Debug)]
pub struct TypeMeta {
    // assigned valid value and used, only during deserializing
    hash: i64,
    schema_hash: i64,
    type_id: u32,
    user_type_id: u32,
    namespace: Rc<MetaString>,
    type_name: Rc<MetaString>,
    register_by_name: bool,
    field_infos: Vec<FieldInfo>,
    bytes: Vec<u8>,
}

impl TypeMeta {
    pub fn new(
        type_id: u32,
        user_type_id: u32,
        namespace: MetaString,
        type_name: MetaString,
        register_by_name: bool,
        field_infos: Vec<FieldInfo>,
    ) -> Result<TypeMeta, Error> {
        let schema_hash = compute_schema_hash(&field_infos);
        let mut meta = TypeMeta {
            hash: 0,
            schema_hash,
            type_id,
            user_type_id,
            namespace: Rc::from(namespace),
            type_name: Rc::from(type_name),
            register_by_name,
            field_infos,
            bytes: vec![],
        };
        let (bytes, meta_hash) = meta.to_bytes()?;
        meta.bytes = bytes;
        meta.hash = meta_hash;
        Ok(meta)
    }

    #[inline(always)]
    pub fn get_field_infos(&self) -> &Vec<FieldInfo> {
        &self.field_infos
    }

    #[inline(always)]
    pub fn get_type_id(&self) -> u32 {
        self.type_id
    }

    #[inline(always)]
    pub fn get_user_type_id(&self) -> u32 {
        self.user_type_id
    }

    #[inline(always)]
    pub fn get_hash(&self) -> i64 {
        self.hash
    }

    #[inline(always)]
    pub fn get_schema_hash(&self) -> i64 {
        self.schema_hash
    }

    #[inline(always)]
    pub fn get_type_name(&self) -> Rc<MetaString> {
        self.type_name.clone()
    }

    #[inline(always)]
    pub fn get_namespace(&self) -> Rc<MetaString> {
        self.namespace.clone()
    }

    #[inline(always)]
    pub fn get_bytes(&self) -> &[u8] {
        &self.bytes
    }

    #[inline(always)]
    pub fn empty() -> Result<TypeMeta, Error> {
        Ok(TypeMeta {
            hash: 0,
            schema_hash: 0,
            type_id: 0,
            user_type_id: NO_USER_TYPE_ID,
            namespace: Rc::from(MetaString::get_empty().clone()),
            type_name: Rc::from(MetaString::get_empty().clone()),
            register_by_name: false,
            field_infos: vec![],
            bytes: vec![],
        })
    }

    /// Creates a deep clone with new Rc instances.
    /// This is safe for concurrent use from multiple threads.
    pub fn deep_clone(&self) -> TypeMeta {
        TypeMeta {
            hash: self.hash,
            schema_hash: self.schema_hash,
            type_id: self.type_id,
            user_type_id: self.user_type_id,
            namespace: Rc::new((*self.namespace).clone()),
            type_name: Rc::new((*self.type_name).clone()),
            register_by_name: self.register_by_name,
            field_infos: self.field_infos.clone(),
            bytes: self.bytes.clone(),
        }
    }

    pub(crate) fn from_fields(
        type_id: u32,
        user_type_id: u32,
        namespace: MetaString,
        type_name: MetaString,
        register_by_name: bool,
        field_infos: Vec<FieldInfo>,
    ) -> Result<TypeMeta, Error> {
        TypeMeta::new(
            type_id,
            user_type_id,
            namespace,
            type_name,
            register_by_name,
            field_infos,
        )
    }

    fn write_name(writer: &mut Writer, name: &MetaString, encodings: &[Encoding]) {
        let encoding_idx = encodings.iter().position(|x| *x == name.encoding).unwrap() as u8;
        let bytes = name.bytes.as_slice();
        if bytes.len() >= BIG_NAME_THRESHOLD {
            writer.write_u8((BIG_NAME_THRESHOLD << 2) as u8 | encoding_idx);
            writer.write_var_u32((bytes.len() - BIG_NAME_THRESHOLD) as u32);
        } else {
            writer.write_u8((bytes.len() << 2) as u8 | encoding_idx);
        }
        writer.write_bytes(bytes);
    }

    pub fn write_namespace(&self, writer: &mut Writer) {
        Self::write_name(writer, &self.namespace, NAMESPACE_ENCODINGS)
    }

    pub fn write_type_name(&self, writer: &mut Writer) {
        Self::write_name(writer, &self.type_name, TYPE_NAME_ENCODINGS)
    }

    fn read_name(
        reader: &mut Reader,
        decoder: &MetaStringDecoder,
        encodings: &[Encoding],
    ) -> Result<MetaString, Error> {
        let header = reader.read_u8()?;
        let encoding_idx = header & 0b11;
        let length = header >> 2;
        let length = if length >= BIG_NAME_THRESHOLD as u8 {
            BIG_NAME_THRESHOLD + reader.read_var_u32()? as usize
        } else {
            length as usize
        };
        let bytes = reader.read_bytes(length)?;
        if encoding_idx as usize >= encodings.len() {
            return Err(Error::invalid_data("encoding_index out of bounds"));
        }
        let encoding = encodings[encoding_idx as usize];
        decoder.decode(bytes, encoding)
    }

    pub fn read_namespace(reader: &mut Reader) -> Result<MetaString, Error> {
        Self::read_name(reader, &NAMESPACE_DECODER, NAMESPACE_ENCODINGS)
    }

    pub fn read_type_name(reader: &mut Reader) -> Result<MetaString, Error> {
        Self::read_name(reader, &TYPE_NAME_DECODER, TYPE_NAME_ENCODINGS)
    }

    fn to_meta_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut buffer = vec![];
        let mut writer = Writer::from_buffer(&mut buffer);
        let num_fields = self.field_infos.len();
        let mut meta_header: u8;
        if is_struct_type_def_kind(self.type_id) {
            meta_header = STRUCT_TYPEDEF_FLAG | min(num_fields, SMALL_NUM_FIELDS_THRESHOLD) as u8;
            if self.type_id == COMPATIBLE_STRUCT || self.type_id == NAMED_COMPATIBLE_STRUCT {
                meta_header |= COMPATIBLE_TYPEDEF_FLAG;
            }
            if self.register_by_name {
                meta_header |= REGISTER_BY_NAME_FLAG;
            }
        } else {
            if num_fields != 0 {
                return Err(Error::invalid_data(
                    "non-struct TypeMeta cannot carry field metadata",
                ));
            }
            meta_header = non_struct_kind_code(self.type_id)?;
        }
        writer.write_u8(meta_header);
        if is_struct_type_def_kind(self.type_id) && num_fields >= SMALL_NUM_FIELDS_THRESHOLD {
            writer.write_var_u32((num_fields - SMALL_NUM_FIELDS_THRESHOLD) as u32);
        }
        if self.register_by_name {
            self.write_namespace(&mut writer);
            self.write_type_name(&mut writer);
        } else {
            if self.user_type_id == NO_USER_TYPE_ID {
                return Err(Error::type_error(
                    "User type id is required for this type id",
                ));
            }
            writer.write_var_u32(self.user_type_id);
        }
        if is_struct_type_def_kind(self.type_id) {
            for field in self.field_infos.iter() {
                writer.write_bytes(field.to_bytes()?.as_slice());
            }
        }
        Ok(buffer)
    }

    fn from_meta_bytes(
        reader: &mut Reader,
        type_resolver: &TypeResolver,
    ) -> Result<TypeMeta, Error> {
        let meta_header = reader.read_u8()?;
        let is_struct = (meta_header & STRUCT_TYPEDEF_FLAG) != 0;
        let register_by_name;
        let mut num_fields = 0usize;
        let type_id;
        let mut user_type_id = NO_USER_TYPE_ID;
        let namespace;
        let type_name;
        if is_struct {
            register_by_name = (meta_header & REGISTER_BY_NAME_FLAG) != 0;
            let compatible = (meta_header & COMPATIBLE_TYPEDEF_FLAG) != 0;
            type_id = if register_by_name {
                if compatible {
                    NAMED_COMPATIBLE_STRUCT
                } else {
                    NAMED_STRUCT
                }
            } else if compatible {
                COMPATIBLE_STRUCT
            } else {
                STRUCT
            };
            num_fields = meta_header as usize & SMALL_NUM_FIELDS_THRESHOLD;
            if num_fields == SMALL_NUM_FIELDS_THRESHOLD {
                num_fields += reader.read_var_u32()? as usize;
            }
            if num_fields > MAX_TYPE_META_FIELDS {
                return Err(Error::invalid_data(format!(
                    "too many fields in type meta: {}, max: {}",
                    num_fields, MAX_TYPE_META_FIELDS
                )));
            }
        } else {
            if (meta_header & 0b0111_0000) != 0 {
                return Err(Error::invalid_data("invalid TypeMeta kind header"));
            }
            type_id = non_struct_type_id(meta_header & 0b1111)?;
            register_by_name = is_named_type_def_kind(type_id);
        }
        if register_by_name {
            namespace = Self::read_namespace(reader)?;
            type_name = Self::read_type_name(reader)?;
        } else {
            user_type_id = reader.read_var_u32()?;
            let empty_name = MetaString::default();
            namespace = empty_name.clone();
            type_name = empty_name;
        }

        let mut field_infos = Vec::with_capacity(num_fields);
        for _ in 0..num_fields {
            field_infos.push(FieldInfo::from_bytes(reader)?);
        }
        if !is_struct && !field_infos.is_empty() {
            return Err(Error::invalid_data(
                "non-struct TypeMeta cannot carry field metadata",
            ));
        }
        // TypeMeta field order is the payload order. Preserve the peer's encoded order while only
        // remapping matched fields to local generated field indexes.
        let mut sorted_field_infos = field_infos;

        if register_by_name {
            if let Some(type_info_current) =
                type_resolver.get_type_info_by_name(&namespace.original, &type_name.original)
            {
                if type_info_current.get_type_id() as u32 != type_id {
                    return Err(Error::invalid_data(
                        "TypeMeta kind does not match registered type metadata",
                    ));
                }
                Self::assign_field_ids(&type_info_current, &mut sorted_field_infos)?;
            }
        } else if user_type_id != NO_USER_TYPE_ID {
            if let Some(type_info_current) = type_resolver.get_user_type_info_by_id(user_type_id) {
                if type_info_current.get_type_id() as u32 != type_id {
                    return Err(Error::invalid_data(
                        "TypeMeta kind does not match registered type metadata",
                    ));
                }
                Self::assign_field_ids(&type_info_current, &mut sorted_field_infos)?;
            }
        } else if let Some(type_info_current) = type_resolver.get_type_info_by_id(type_id) {
            Self::assign_field_ids(&type_info_current, &mut sorted_field_infos)?;
        }
        // if no type found, keep all fields id as -1 to be skipped.
        TypeMeta::new(
            type_id,
            user_type_id,
            namespace,
            type_name,
            register_by_name,
            sorted_field_infos,
        )
    }

    fn assign_field_ids(
        type_info_current: &TypeInfo,
        field_infos: &mut [FieldInfo],
    ) -> Result<(), Error> {
        if crate::util::ENABLE_FORY_DEBUG_OUTPUT {
            eprintln!(
                "[fory-debug] assign_field_ids called for type: {:?}",
                type_info_current.get_type_name()
            );
            for f in field_infos.iter() {
                eprintln!(
                    "[fory-debug]   remote field before assign: name={}, field_id={}, type={:?}",
                    f.field_name, f.field_id, f.field_type
                );
            }
        }
        let type_meta = type_info_current.get_type_meta();
        let local_field_infos = type_meta.get_field_infos();
        if crate::util::ENABLE_FORY_DEBUG_OUTPUT {
            for f in local_field_infos.iter() {
                eprintln!(
                    "[fory-debug]   local field: name={}, field_id={}, type={:?}",
                    f.field_name, f.field_id, f.field_type
                );
            }
        }

        assign_remote_field_ids(local_field_infos, field_infos)
    }

    #[allow(dead_code)]
    pub(crate) fn from_bytes(
        reader: &mut Reader,
        type_resolver: &TypeResolver,
    ) -> Result<TypeMeta, Error> {
        let header = reader.read_i64()?;
        Self::from_bytes_with_header(reader, type_resolver, header)
    }

    pub(crate) fn from_bytes_with_header(
        reader: &mut Reader,
        type_resolver: &TypeResolver,
        header: i64,
    ) -> Result<TypeMeta, Error> {
        validate_type_meta_header(header)?;
        let meta_size = read_type_meta_body_size(reader, header)?;
        let body = reader.read_bytes(meta_size)?;
        let mut body_reader = Reader::new(body);
        let mut meta = Self::from_meta_bytes(&mut body_reader, type_resolver)?;
        if !body_reader.slice_after_cursor().is_empty() {
            return Err(Error::invalid_data("invalid TypeMeta metadata size"));
        }
        validate_type_meta_body_hash(header, body)?;
        let meta_hash = header >> TYPE_META_HASH_SHIFT;
        meta.hash = meta_hash;
        Ok(meta)
    }

    #[inline(always)]
    pub(crate) fn skip_bytes_for_validated_header(
        reader: &mut Reader,
        header: i64,
    ) -> Result<(), Error> {
        // Header-cache hits intentionally treat the current body as opaque bytes and skip by the
        // current header size. Parsed TypeMeta entries are cached only after body parse and hash
        // validation; cache hits must not reparse or rehash that body.
        let mut meta_size = (header & META_SIZE_MASK) as usize;
        if meta_size == META_SIZE_MASK as usize {
            meta_size += reader.read_var_u32()? as usize;
        }
        reader.skip(meta_size)
    }

    /// Check class version consistency, similar to Java's checkClassVersion
    #[inline(always)]
    pub fn check_struct_version(
        read_version: i32,
        local_version: i32,
        type_name: &str,
    ) -> Result<(), Error> {
        if read_version != local_version {
            return Err(Error::struct_version_mismatch(format!(
                "Read class {} version {} is not consistent with {}, please align struct field types and names, 
                or keep compatible mode enabled on every Fory peer",
                type_name, read_version, local_version
            )));
        }
        Ok(())
    }

    fn to_bytes(&self) -> Result<(Vec<u8>, i64), Error> {
        // | global_binary_header | meta_bytes |
        let mut buffer = vec![];
        let mut result = Writer::from_buffer(&mut buffer);
        let mut meta_buffer = vec![];
        let mut meta_writer = Writer::from_buffer(&mut meta_buffer);
        meta_writer.write_bytes(self.to_meta_bytes()?.as_slice());
        let meta_size = meta_writer.len() as i64;
        let mut header: i64 = min(META_SIZE_MASK, meta_size);
        let is_compressed = false;
        if is_compressed {
            header |= COMPRESS_META_FLAG;
        }
        let meta_hash_shifted =
            type_meta_hash_bits(meta_writer.dump().as_slice(), header as u64) as i64;
        let meta_hash = meta_hash_shifted >> TYPE_META_HASH_SHIFT;
        header |= meta_hash_shifted;
        result.write_i64(header);
        if meta_size >= META_SIZE_MASK {
            result.write_var_u32((meta_size - META_SIZE_MASK) as u32);
        }
        result.write_bytes(meta_buffer.as_slice());
        Ok((buffer, meta_hash))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exact_field_type_requires_wire_metadata() {
        let base = FieldType::new(crate::type_id::INT32, false, vec![]);
        let nullable = FieldType::new(crate::type_id::INT32, true, vec![]);
        let tracked = FieldType::new_with_ref(crate::type_id::INT32, false, true, vec![]);

        assert_eq!(base, base);
        assert_ne!(base, nullable);
        assert_ne!(base, tracked);
        assert!(base.exact_shape_match(&base));
        assert!(!base.exact_shape_match(&nullable));
        assert!(!base.exact_shape_match(&tracked));

        let list = FieldType::new(crate::type_id::LIST, false, vec![base.clone()]);
        let nullable_list = FieldType::new(crate::type_id::LIST, false, vec![nullable]);
        assert!(!list.exact_shape_match(&nullable_list));

        let struct_field =
            FieldType::new_with_user_type_id(crate::type_id::STRUCT, 1, false, false, vec![]);
        let unknown_field =
            FieldType::new_with_user_type_id(crate::type_id::UNKNOWN, 2, false, false, vec![]);
        assert!(struct_field.exact_shape_match(&unknown_field));

        let union_field = FieldType::new(crate::type_id::UNION, false, vec![]);
        let typed_union_field = FieldType::new(crate::type_id::TYPED_UNION, false, vec![]);
        assert!(union_field.exact_shape_match(&typed_union_field));
    }

    #[test]
    fn rejects_unrepresentable_matched_field_id() {
        let field_type = FieldType::new(crate::type_id::INT32, false, vec![]);
        let mut local_fields = Vec::with_capacity(MAX_COMPATIBLE_MATCHED_FIELD_INDEX + 2);
        for index in 0..=MAX_COMPATIBLE_MATCHED_FIELD_INDEX + 1 {
            local_fields.push(FieldInfo::new(
                &format!("field_{}", index),
                field_type.clone(),
            ));
        }
        let mut remote_fields = [FieldInfo::new(
            &format!("field_{}", MAX_COMPATIBLE_MATCHED_FIELD_INDEX + 1),
            field_type,
        )];

        let message = assign_remote_field_ids(&local_fields, &mut remote_fields)
            .err()
            .map(|error| error.to_string())
            .unwrap_or_default();

        assert!(message.contains("exceeds max"));
    }

    #[test]
    fn classifies_compatible_scalar_field() {
        let local_type = FieldType::new(crate::type_id::INT32, false, vec![]);
        let remote_type = FieldType::new(crate::type_id::INT16, false, vec![]);
        let local_fields = [FieldInfo::new("value", local_type)];
        let mut remote_fields = [FieldInfo::new("value", remote_type)];

        assign_remote_field_ids(&local_fields, &mut remote_fields).unwrap();

        assert_eq!(remote_fields[0].field_id, 1);
    }

    #[test]
    fn classifies_list_array_bridge() {
        let array_type = FieldType::new(crate::type_id::INT32_ARRAY, false, vec![]);
        let int_type = FieldType::new(crate::type_id::INT32, false, vec![]);
        let local_fields = [FieldInfo::new("values", array_type.clone())];
        let mut remote_fields = [FieldInfo::new(
            "values",
            FieldType::new(crate::type_id::LIST, false, vec![int_type]),
        )];

        assign_remote_field_ids(&local_fields, &mut remote_fields).unwrap();
        assert_eq!(remote_fields[0].field_id, 1);

        let nullable_int = FieldType::new(crate::type_id::INT32, true, vec![]);
        let mut nullable_remote = [FieldInfo::new(
            "values",
            FieldType::new(crate::type_id::LIST, false, vec![nullable_int]),
        )];
        assign_remote_field_ids(&local_fields, &mut nullable_remote).unwrap();
        assert_eq!(nullable_remote[0].field_id, 1);

        let tracked_int = FieldType::new_with_ref(crate::type_id::INT32, false, true, vec![]);
        let mut tracked_remote = [FieldInfo::new(
            "values",
            FieldType::new(crate::type_id::LIST, false, vec![tracked_int]),
        )];
        assert!(assign_remote_field_ids(&local_fields, &mut tracked_remote).is_err());

        let nullable_array_local = [FieldInfo::new(
            "values",
            FieldType::new(crate::type_id::INT32_ARRAY, true, vec![]),
        )];
        let mut remote_list = [FieldInfo::new(
            "values",
            FieldType::new(
                crate::type_id::LIST,
                false,
                vec![FieldType::new(crate::type_id::INT32, false, vec![])],
            ),
        )];
        assert!(assign_remote_field_ids(&nullable_array_local, &mut remote_list).is_err());
    }

    #[test]
    fn classifies_map_entry_encoding_shape() {
        let any_type = FieldType::new_with_ref(crate::type_id::UNKNOWN, false, true, vec![]);
        let local_map = FieldType::new(
            crate::type_id::MAP,
            false,
            vec![
                FieldType::new(crate::type_id::VAR_UINT32, false, vec![]),
                any_type.clone(),
            ],
        );
        let remote_map = FieldType::new(
            crate::type_id::MAP,
            false,
            vec![
                FieldType::new(crate::type_id::UINT32, false, vec![]),
                any_type,
            ],
        );
        let local_fields = [FieldInfo::new_with_id(0, "values", local_map)];
        let mut remote_fields = [FieldInfo::new_with_id(0, "", remote_map)];

        assign_remote_field_ids(&local_fields, &mut remote_fields).unwrap();

        assert_eq!(remote_fields[0].field_id, 1);
    }

    #[test]
    fn classifies_missing_collection_generics() {
        let local_list = FieldType::new(crate::type_id::LIST, false, vec![]);
        let remote_list = FieldType::new(
            crate::type_id::LIST,
            false,
            vec![FieldType::new(crate::type_id::UNKNOWN, true, vec![])],
        );
        let local_fields = [FieldInfo::new("values", local_list)];
        let mut remote_fields = [FieldInfo::new("values", remote_list)];

        assign_remote_field_ids(&local_fields, &mut remote_fields).unwrap();

        assert_eq!(remote_fields[0].field_id, 1);
    }

    #[test]
    fn name_remote_does_not_match_tagged_local() {
        let field_type = FieldType::new(crate::type_id::STRING, false, vec![]);
        let local_fields = [FieldInfo::new_with_id(1, "value", field_type.clone())];
        let mut remote_fields = [FieldInfo::new("value", field_type)];

        assign_remote_field_ids(&local_fields, &mut remote_fields).unwrap();

        assert_eq!(remote_fields[0].field_id, -1);
    }

    #[test]
    fn duplicate_remote_name_binding_fails() {
        let field_type = FieldType::new(crate::type_id::STRING, false, vec![]);
        let local_fields = [FieldInfo::new("value", field_type.clone())];
        let mut remote_fields = [
            FieldInfo::new("value", field_type.clone()),
            FieldInfo::new("value", field_type),
        ];

        let message = assign_remote_field_ids(&local_fields, &mut remote_fields)
            .err()
            .map(|error| error.to_string())
            .unwrap_or_default();

        assert!(message.contains("duplicates local field"));
    }

    #[test]
    fn rejects_body_hash_mismatch_after_successful_parse() {
        let meta = TypeMeta::new(
            STRUCT,
            1,
            MetaString::get_empty().clone(),
            MetaString::get_empty().clone(),
            false,
            vec![],
        )
        .unwrap();
        let (mut bytes, _) = meta.to_bytes().unwrap();
        let last = bytes.len() - 1;
        bytes[last] ^= 1;

        let mut reader = Reader::new(&bytes);
        let result = TypeMeta::from_bytes(&mut reader, &TypeResolver::default());
        let message = result
            .err()
            .map(|error| error.to_string())
            .unwrap_or_default();
        assert!(message.contains("hash mismatch"));
    }

    #[test]
    fn rejects_body_only_header_hash() {
        let meta = TypeMeta::new(
            STRUCT,
            1,
            MetaString::get_empty().clone(),
            MetaString::get_empty().clone(),
            false,
            vec![],
        )
        .unwrap();
        let (mut bytes, _) = meta.to_bytes().unwrap();
        let header = i64::from_le_bytes(bytes[0..8].try_into().unwrap()) as u64;
        let hash_mask = u64::MAX << TYPE_META_HASH_SHIFT;
        let body_only_hash = body_only_type_meta_hash_bits(&bytes[8..]);
        assert_ne!(header & hash_mask, body_only_hash);
        let rewritten_header = body_only_hash | (header & !hash_mask);
        bytes[0..8].copy_from_slice(&(rewritten_header as i64).to_le_bytes());

        let mut reader = Reader::new(&bytes);
        let result = TypeMeta::from_bytes(&mut reader, &TypeResolver::default());
        let message = result
            .err()
            .map(|error| error.to_string())
            .unwrap_or_default();
        assert!(message.contains("hash mismatch"));
    }

    #[test]
    fn rejects_hash_consistent_trailing_body_bytes() {
        let meta = TypeMeta::new(
            STRUCT,
            1,
            MetaString::get_empty().clone(),
            MetaString::get_empty().clone(),
            false,
            vec![],
        )
        .unwrap();
        let mut body = meta.to_meta_bytes().unwrap();
        body.push(0);

        let mut frame = vec![];
        let mut writer = Writer::from_buffer(&mut frame);
        let body_size = body.len() as i64;
        let header_low_bits = min(META_SIZE_MASK, body_size);
        let mut header = type_meta_hash_bits(&body, header_low_bits as u64) as i64;
        header |= header_low_bits;
        writer.write_i64(header);
        if body_size >= META_SIZE_MASK {
            writer.write_var_u32((body_size - META_SIZE_MASK) as u32);
        }
        writer.write_bytes(&body);

        let mut reader = Reader::new(&frame);
        let result = TypeMeta::from_bytes(&mut reader, &TypeResolver::default());
        let message = result
            .err()
            .map(|error| error.to_string())
            .unwrap_or_default();
        assert!(message.contains("metadata size"));
    }

    fn body_only_type_meta_hash_bits(body: &[u8]) -> u64 {
        let hash_value = murmurhash3_x64_128(body, 47).0 as i64;
        let shifted = hash_value << TYPE_META_HASH_SHIFT;
        shifted.wrapping_abs() as u64 & (u64::MAX << TYPE_META_HASH_SHIFT)
    }
}
