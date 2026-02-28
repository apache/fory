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
    murmurhash3_x64_128, Encoding, MetaString, MetaStringDecoder, FIELD_NAME_DECODER,
    FIELD_NAME_ENCODER, NAMESPACE_DECODER, TYPE_NAME_DECODER,
};
use crate::resolver::type_resolver::{TypeInfo, TypeResolver};
use crate::types::{
    TypeId, BINARY, COMPATIBLE_STRUCT, ENUM, EXT, INT8_ARRAY, NAMED_COMPATIBLE_STRUCT, NAMED_ENUM,
    NAMED_EXT, NAMED_STRUCT, PRIMITIVE_TYPES, STRUCT, UINT8_ARRAY, UNKNOWN,
};
use crate::util::to_snake_case;

/// Normalizes a type ID for comparison purposes in cross-language schema evolution.
/// This treats all struct variants (STRUCT, COMPATIBLE_STRUCT, NAMED_STRUCT,
/// NAMED_COMPATIBLE_STRUCT) and UNKNOWN as equivalent to STRUCT.
/// UNKNOWN (0) is used for polymorphic types (interfaces) in cross-language serialization.
/// Similarly for ENUM and EXT variants, and byte array encodings.
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
        // Byte array encodings normalize to BINARY
        _ if type_id == BINARY || type_id == INT8_ARRAY || type_id == UINT8_ARRAY => BINARY,
        // Everything else stays the same
        _ => type_id,
    }
}
use std::clone::Clone;
use std::cmp::min;
use std::collections::HashMap;
use std::rc::Rc;

const SMALL_NUM_FIELDS_THRESHOLD: usize = 0b11111;
const REGISTER_BY_NAME_FLAG: u8 = 0b100000;
const FIELD_NAME_SIZE_THRESHOLD: usize = 0b1111;
/// Marker value in encoding bits to indicate field ID mode (instead of field name)
const FIELD_ID_ENCODING_MARKER: u8 = 0b11;
/// Threshold for field ID that fits in 4-bit size field
const SMALL_FIELD_ID_THRESHOLD: i16 = 0b1111;

const BIG_NAME_THRESHOLD: usize = 0b111111;

const META_SIZE_MASK: i64 = 0xff;
const COMPRESS_META_FLAG: i64 = 0b1 << 9;
const HAS_FIELDS_META_FLAG: i64 = 0b1 << 8;
const NUM_HASH_BITS: i8 = 50;
const NO_USER_TYPE_ID: u32 = u32::MAX;

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

#[derive(Debug, Eq, Clone)]
pub struct FieldType {
    pub type_id: u32,
    pub user_type_id: u32,
    pub nullable: bool,
    pub track_ref: bool,
    pub generics: Vec<FieldType>,
}

impl FieldType {
    pub fn new(type_id: u32, nullable: bool, generics: Vec<FieldType>) -> Self {
        FieldType {
            type_id,
            user_type_id: NO_USER_TYPE_ID,
            nullable,
            track_ref: false,
            generics,
        }
    }

    pub fn new_with_ref(
        type_id: u32,
        nullable: bool,
        track_ref: bool,
        generics: Vec<FieldType>,
    ) -> Self {
        FieldType {
            type_id,
            user_type_id: NO_USER_TYPE_ID,
            nullable,
            track_ref,
            generics,
        }
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
            writer.write_var_uint32(header);
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
            reader.read_varuint32()?
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
            _nullable = nullable.unwrap();
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
                Self {
                    type_id,
                    user_type_id,
                    nullable: _nullable,
                    track_ref: _ref_tracking,
                    generics: vec![generic],
                }
            }
            x if x == TypeId::MAP as u32 => {
                let key_generic = Self::from_bytes(reader, true, None)?;
                let val_generic = Self::from_bytes(reader, true, None)?;
                Self {
                    type_id,
                    user_type_id,
                    nullable: _nullable,
                    track_ref: _ref_tracking,
                    generics: vec![key_generic, val_generic],
                }
            }
            _ => Self {
                type_id,
                user_type_id,
                nullable: _nullable,
                track_ref: _ref_tracking,
                generics: vec![],
            },
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
                field_id += reader.read_varuint32()? as i16;
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
                name_size += reader.read_varuint32()? as usize;
            }
            name_size += 1;

            let mut field_type = FieldType::from_bytes(reader, false, Option::from(nullable))?;
            field_type.track_ref = track_ref;

            let field_name_bytes = reader.read_bytes(name_size)?;

            let field_name = FIELD_NAME_DECODER
                .decode(field_name_bytes, encoding)
                .unwrap();
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
                writer.write_var_uint32((field_id - SMALL_FIELD_ID_THRESHOLD) as u32);
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
                writer.write_var_uint32((name_size - FIELD_NAME_SIZE_THRESHOLD) as u32);
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
        // Normalize type IDs for comparison to handle cross-language schema evolution.
        // This allows UNKNOWN (0) polymorphic types to match STRUCT (15) in Rust.
        if normalize_type_id_for_eq(self.type_id) != normalize_type_id_for_eq(other.type_id) {
            return false;
        }
        if self.generics != other.generics {
            return false;
        }
        true
    }
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
            writer.write_var_uint32((bytes.len() - BIG_NAME_THRESHOLD) as u32);
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
            BIG_NAME_THRESHOLD + reader.read_varuint32()? as usize
        } else {
            length as usize
        };
        let bytes = reader.read_bytes(length)?;
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
        // meta_bytes:| meta_header | fields meta |
        let mut writer = Writer::from_buffer(&mut buffer);
        let num_fields = self.field_infos.len();
        // meta_header: | unuse:2 bits | is_register_by_id:1 bit | num_fields:4 bits |
        let mut meta_header: u8 = min(num_fields, SMALL_NUM_FIELDS_THRESHOLD) as u8;
        if self.register_by_name {
            meta_header |= REGISTER_BY_NAME_FLAG;
        }
        writer.write_u8(meta_header);
        if num_fields >= SMALL_NUM_FIELDS_THRESHOLD {
            writer.write_var_uint32((num_fields - SMALL_NUM_FIELDS_THRESHOLD) as u32);
        }
        if self.register_by_name {
            self.write_namespace(&mut writer);
            self.write_type_name(&mut writer);
        } else {
            writer.write_u8(self.type_id as u8);
            if self.user_type_id == NO_USER_TYPE_ID {
                return Err(Error::type_error(
                    "User type id is required for this type id",
                ));
            }
            writer.write_var_uint32(self.user_type_id);
        }
        for field in self.field_infos.iter() {
            writer.write_bytes(field.to_bytes()?.as_slice());
        }
        Ok(buffer)
    }

    fn sort_field_infos(field_infos: Vec<FieldInfo>) -> Vec<FieldInfo> {
        let fields_len = field_infos.len();
        // group
        let mut primitive_fields = Vec::new();
        let mut nullable_primitive_fields = Vec::new();
        let mut internal_type_fields = Vec::new();
        let mut list_fields = Vec::new();
        let mut set_fields = Vec::new();
        let mut map_fields = Vec::new();
        let mut other_fields = Vec::new();

        for field_info in field_infos.into_iter() {
            let type_id = field_info.field_type.type_id;
            let is_nullable = field_info.field_type.nullable;
            if is_nullable && PRIMITIVE_TYPES.contains(&type_id) {
                nullable_primitive_fields.push(field_info);
                continue;
            }

            if PRIMITIVE_TYPES.contains(&type_id) {
                primitive_fields.push(field_info);
            } else if TypeId::LIST as u32 == type_id {
                list_fields.push(field_info);
            } else if TypeId::SET as u32 == type_id {
                set_fields.push(field_info);
            } else if TypeId::MAP as u32 == type_id {
                map_fields.push(field_info);
            } else if crate::types::is_internal_type(type_id) {
                internal_type_fields.push(field_info);
            } else {
                other_fields.push(field_info);
            }
        }

        fn get_primitive_type_size(type_id_num: u32) -> i32 {
            let type_id = TypeId::try_from(type_id_num as u8).unwrap();
            match type_id {
                TypeId::BOOL => 1,
                TypeId::INT8 => 1,
                TypeId::INT16 => 2,
                TypeId::INT32 => 4,
                TypeId::VARINT32 => 4,
                TypeId::INT64 => 8,
                TypeId::VARINT64 => 8,
                TypeId::TAGGED_INT64 => 8,
                TypeId::UINT8 => 1,
                TypeId::UINT16 => 2,
                TypeId::UINT32 => 4,
                TypeId::VAR_UINT32 => 4,
                TypeId::UINT64 => 8,
                TypeId::VAR_UINT64 => 8,
                TypeId::TAGGED_UINT64 => 8,
                TypeId::FLOAT8 => 1,
                TypeId::FLOAT16 => 2,
                TypeId::BFLOAT16 => 2,
                TypeId::FLOAT32 => 4,
                TypeId::FLOAT64 => 8,
                TypeId::U128 => 16,
                TypeId::INT128 => 16,
                TypeId::USIZE => std::mem::size_of::<usize>() as i32,
                TypeId::ISIZE => std::mem::size_of::<isize>() as i32,
                _ => unreachable!(),
            }
        }
        fn is_compress(type_id: u32) -> bool {
            // Variable-size integer types (both signed and unsigned)
            // These are sorted after fixed-size types in field ordering
            [
                TypeId::VARINT32 as u32,
                TypeId::VARINT64 as u32,
                TypeId::TAGGED_INT64 as u32,
                TypeId::VAR_UINT32 as u32,
                TypeId::VAR_UINT64 as u32,
                TypeId::TAGGED_UINT64 as u32,
            ]
            .contains(&type_id)
        }
        fn numeric_sorter(a: &FieldInfo, b: &FieldInfo) -> std::cmp::Ordering {
            let (a_id, b_id) = (a.field_type.type_id, b.field_type.type_id);
            let a_field_name = &a.field_name;
            let b_field_name = &b.field_name;
            let compress_a = is_compress(a_id);
            let compress_b = is_compress(b_id);
            let size_a = get_primitive_type_size(a_id);
            let size_b = get_primitive_type_size(b_id);
            let a_nullable = a.field_type.nullable;
            let b_nullable = b.field_type.nullable;
            a_nullable
                .cmp(&b_nullable) // non-nullable first
                .then_with(|| compress_a.cmp(&compress_b)) // fixed-size (false) first, then variable-size (true) last
                .then_with(|| size_b.cmp(&size_a)) // when same compress status: larger size first
                .then_with(|| b_id.cmp(&a_id)) // when same size: larger type id first
                .then_with(|| a_field_name.cmp(b_field_name)) // when same id: lexicographic name
        }
        fn type_then_name_sorter(a: &FieldInfo, b: &FieldInfo) -> std::cmp::Ordering {
            a.field_type
                .type_id
                .cmp(&b.field_type.type_id)
                .then_with(|| a.field_name.cmp(&b.field_name))
        }
        fn name_sorter(a: &FieldInfo, b: &FieldInfo) -> std::cmp::Ordering {
            a.field_name.cmp(&b.field_name)
        }
        primitive_fields.sort_by(numeric_sorter);
        nullable_primitive_fields.sort_by(numeric_sorter);
        internal_type_fields.sort_by(type_then_name_sorter);
        list_fields.sort_by(name_sorter);
        set_fields.sort_by(name_sorter);
        map_fields.sort_by(name_sorter);
        other_fields.sort_by(name_sorter);
        let mut sorted_field_infos = Vec::with_capacity(fields_len);
        sorted_field_infos.extend(primitive_fields);
        sorted_field_infos.extend(nullable_primitive_fields);
        sorted_field_infos.extend(internal_type_fields);
        sorted_field_infos.extend(list_fields);
        sorted_field_infos.extend(set_fields);
        sorted_field_infos.extend(map_fields);
        sorted_field_infos.extend(other_fields);
        sorted_field_infos
    }

    fn from_meta_bytes(
        reader: &mut Reader,
        type_resolver: &TypeResolver,
    ) -> Result<TypeMeta, Error> {
        let meta_header = reader.read_u8()?;
        let register_by_name = (meta_header & REGISTER_BY_NAME_FLAG) != 0;
        let mut num_fields = meta_header as usize & SMALL_NUM_FIELDS_THRESHOLD;
        if num_fields == SMALL_NUM_FIELDS_THRESHOLD {
            num_fields += reader.read_varuint32()? as usize;
        }
        let mut type_id;
        let mut user_type_id = NO_USER_TYPE_ID;
        let namespace;
        let type_name;
        if register_by_name {
            namespace = Self::read_namespace(reader)?;
            type_name = Self::read_type_name(reader)?;
            type_id = 0;
        } else {
            type_id = reader.read_u8()? as u32;
            user_type_id = reader.read_varuint32()?;
            let empty_name = MetaString::default();
            namespace = empty_name.clone();
            type_name = empty_name;
        }

        let mut field_infos = Vec::with_capacity(num_fields);
        for _ in 0..num_fields {
            field_infos.push(FieldInfo::from_bytes(reader)?);
        }
        let mut sorted_field_infos = Self::sort_field_infos(field_infos);

        if register_by_name {
            if let Some(type_info_current) =
                type_resolver.get_type_info_by_name(&namespace.original, &type_name.original)
            {
                type_id = type_info_current.get_type_id() as u32;
                Self::assign_field_ids(&type_info_current, &mut sorted_field_infos);
            }
        } else if user_type_id != NO_USER_TYPE_ID {
            if let Some(type_info_current) = type_resolver.get_user_type_info_by_id(user_type_id) {
                Self::assign_field_ids(&type_info_current, &mut sorted_field_infos);
            }
        } else if let Some(type_info_current) = type_resolver.get_type_info_by_id(type_id) {
            Self::assign_field_ids(&type_info_current, &mut sorted_field_infos);
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

    fn assign_field_ids(type_info_current: &TypeInfo, field_infos: &mut [FieldInfo]) {
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

        // Build maps for both name-based and ID-based lookup.
        // The value is the SORTED INDEX (position in local_field_infos), not the field's ID attribute.
        // This index is used for matching in generated code.
        let field_index_by_name: HashMap<String, (usize, &FieldInfo)> = local_field_infos
            .iter()
            .enumerate()
            .filter(|(_, f)| !f.field_name.is_empty())
            .map(|(i, f)| (f.field_name.clone(), (i, f)))
            .collect();

        let field_index_by_id: HashMap<i16, (usize, &FieldInfo)> = local_field_infos
            .iter()
            .enumerate()
            .filter(|(_, f)| f.field_id >= 0)
            .map(|(i, f)| (f.field_id, (i, f)))
            .collect();

        for field in field_infos.iter_mut() {
            // Try to match by field ID first (if the incoming field was encoded with ID)
            let local_match = if field.field_id >= 0 && field.field_name.is_empty() {
                // Field was encoded with ID, match by ID
                field_index_by_id.get(&field.field_id).copied()
            } else {
                // Field was encoded with name, match by name
                // Convert incoming field name to snake_case for cross-language compatibility
                // (Java uses camelCase, Rust uses snake_case)
                let snake_case_name = to_snake_case(&field.field_name);
                field_index_by_name.get(&snake_case_name).copied()
            };

            match local_match {
                Some((sorted_index, local_info)) => {
                    // Always copy field name if it was ID-encoded
                    // This is needed because TypeMeta may need to re-serialize the field info
                    if field.field_name.is_empty() {
                        field.field_name = local_info.field_name.clone();
                    }
                    // Use FieldType comparison which normalizes type IDs for cross-language
                    // schema evolution (e.g., UNKNOWN=0 matches STRUCT variants)
                    if field.field_type != local_info.field_type {
                        if crate::util::ENABLE_FORY_DEBUG_OUTPUT {
                            eprintln!(
                                "[fory-debug] field type mismatch: name={}, remote_type={:?}, local_type={:?}",
                                field.field_name, field.field_type, local_info.field_type
                            );
                        }
                        field.field_id = -1; // Type mismatch, skip
                    } else {
                        // Assign SORTED INDEX for matching in generated code
                        field.field_id = sorted_index as i16;
                        if crate::util::ENABLE_FORY_DEBUG_OUTPUT {
                            eprintln!(
                                "[fory-debug]   matched field: name={}, assigned_field_id={}",
                                field.field_name, field.field_id
                            );
                        }
                    }
                }
                None => {
                    if crate::util::ENABLE_FORY_DEBUG_OUTPUT {
                        eprintln!(
                            "[fory-debug] no local match for field: name={}",
                            field.field_name
                        );
                    }
                    field.field_id = -1; // No match, skip
                }
            }
        }
    }

    #[allow(dead_code)]
    pub(crate) fn from_bytes(
        reader: &mut Reader,
        type_resolver: &TypeResolver,
    ) -> Result<TypeMeta, Error> {
        let header = reader.read_i64()?;
        let meta_size = header & META_SIZE_MASK;
        if meta_size == META_SIZE_MASK {
            // meta_size += reader.read_varuint32() as i64;
            reader.read_varuint32()?;
        }

        // let write_fields_meta = (header & HAS_FIELDS_META_FLAG) != 0;
        // let is_compressed: bool = (header & COMPRESS_META_FLAG) != 0;
        let meta_hash = header >> (64 - NUM_HASH_BITS);

        // let current_meta_size = 0;
        // while current_meta_size < meta_size {}
        let mut meta = Self::from_meta_bytes(reader, type_resolver)?;
        meta.hash = meta_hash;
        Ok(meta)
    }

    pub(crate) fn from_bytes_with_header(
        reader: &mut Reader,
        type_resolver: &TypeResolver,
        header: i64,
    ) -> Result<TypeMeta, Error> {
        let meta_size = header & META_SIZE_MASK;
        if meta_size == META_SIZE_MASK {
            // meta_size += reader.read_varuint32()? as i64;
            reader.read_varuint32()?;
        }

        // let write_fields_meta = (header & HAS_FIELDS_META_FLAG) != 0;
        // let is_compressed: bool = (header & COMPRESS_META_FLAG) != 0;
        let meta_hash = header >> (64 - NUM_HASH_BITS);

        // let current_meta_size = 0;
        // while current_meta_size < meta_size {}
        let mut meta = Self::from_meta_bytes(reader, type_resolver)?;
        meta.hash = meta_hash;
        Ok(meta)
    }

    #[inline(always)]
    pub fn skip_bytes(reader: &mut Reader, header: i64) -> Result<(), Error> {
        let mut meta_size = header & META_SIZE_MASK;
        if meta_size == META_SIZE_MASK {
            meta_size += reader.read_varuint32()? as i64;
        }
        reader.skip(meta_size as usize)
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
                or use compatible mode of Fory by Fory#compatible(true)",
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
        // global_binary_header:| hash:50bits | reserved:4bits | is_compressed:1bit | write_fields_meta:1bit | meta_size:8bits |
        let meta_size = meta_writer.len() as i64;
        let mut header: i64 = min(META_SIZE_MASK, meta_size);
        let write_meta_fields_flag = !self.get_field_infos().is_empty();
        if write_meta_fields_flag {
            header |= HAS_FIELDS_META_FLAG;
        }
        let is_compressed = false;
        if is_compressed {
            header |= COMPRESS_META_FLAG;
        }
        let hash_value = murmurhash3_x64_128(meta_writer.dump().as_slice(), 47).0 as i64;
        let meta_hash_shifted = (hash_value << (64 - NUM_HASH_BITS)).abs();
        let meta_hash = meta_hash_shifted >> (64 - NUM_HASH_BITS);
        header |= meta_hash_shifted;
        result.write_i64(header);
        if meta_size >= META_SIZE_MASK {
            result.write_var_uint32((meta_size - META_SIZE_MASK) as u32);
        }
        result.write_bytes(meta_buffer.as_slice());
        Ok((buffer, meta_hash))
    }
}
