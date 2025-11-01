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
use crate::types::{TypeId, PRIMITIVE_TYPES};
use std::clone::Clone;
use std::cmp::min;
use std::collections::HashMap;
use std::rc::Rc;

const SMALL_NUM_FIELDS_THRESHOLD: usize = 0b11111;
const REGISTER_BY_NAME_FLAG: u8 = 0b100000;
const FIELD_NAME_SIZE_THRESHOLD: usize = 0b1111;

const BIG_NAME_THRESHOLD: usize = 0b111111;

const META_SIZE_MASK: i64 = 0xfff;
const COMPRESS_META_FLAG: i64 = 0b1 << 13;
const HAS_FIELDS_META_FLAG: i64 = 0b1 << 12;
const NUM_HASH_BITS: i8 = 50;

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
    pub nullable: bool,
    pub generics: Vec<FieldType>,
}

impl FieldType {
    pub fn new(type_id: u32, nullable: bool, generics: Vec<FieldType>) -> Self {
        FieldType {
            type_id,
            nullable,
            generics,
        }
    }

    fn to_bytes(&self, writer: &mut Writer, write_flag: bool, nullable: bool) -> Result<(), Error> {
        let mut header = self.type_id;
        if write_flag {
            header <<= 2;
            // let ref_tracking = false;
            if nullable {
                header |= 2;
            }
        }
        writer.write_varuint32(header);
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
        let header = reader.read_varuint32()?;
        let type_id;
        let _nullable;
        if read_flag {
            type_id = header >> 2;
            // let tracking_ref = (header & 1) != 0;
            _nullable = (header & 2) != 0;
        } else {
            type_id = header;
            _nullable = nullable.unwrap();
        }
        Ok(match type_id {
            x if x == TypeId::LIST as u32 || x == TypeId::SET as u32 => {
                let generic = Self::from_bytes(reader, true, None)?;
                Self {
                    type_id,
                    nullable: _nullable,
                    generics: vec![generic],
                }
            }
            x if x == TypeId::MAP as u32 => {
                let key_generic = Self::from_bytes(reader, true, None)?;
                let val_generic = Self::from_bytes(reader, true, None)?;
                Self {
                    type_id,
                    nullable: _nullable,
                    generics: vec![key_generic, val_generic],
                }
            }
            _ => Self {
                type_id,
                nullable: _nullable,
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
        // let ref_tracking = (header & 1) != 0;
        let encoding = Self::u8_to_encoding((header >> 6) & 0b11)?;
        let mut name_size = ((header >> 2) & FIELD_NAME_SIZE_THRESHOLD as u8) as usize;
        if name_size == FIELD_NAME_SIZE_THRESHOLD {
            name_size += reader.read_varuint32()? as usize;
        }
        name_size += 1;

        let field_type = FieldType::from_bytes(reader, false, Option::from(nullable))?;

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

    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        // field_bytes: | header | type_info | field_name |
        let mut buffer = vec![];
        let mut writer = Writer::from_buffer(&mut buffer);
        // header: | field_name_encoding:2bits | size:4bits | nullability:1bit | ref_tracking:1bit |
        let meta_string =
            FIELD_NAME_ENCODER.encode_with_encodings(&self.field_name, FIELD_NAME_ENCODINGS)?;
        let name_encoded = meta_string.bytes.as_slice();
        let name_size = name_encoded.len() - 1;
        let mut header: u8 = (min(FIELD_NAME_SIZE_THRESHOLD, name_size) as u8) << 2;
        // let ref_tracking = false;
        let nullable = self.field_type.nullable;
        // if ref_tracking {
        //     header |= 1;
        // }
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
            writer.write_varuint32((name_size - FIELD_NAME_SIZE_THRESHOLD) as u32);
        }
        self.field_type.to_bytes(&mut writer, false, nullable)?;
        // write field_name
        writer.write_bytes(name_encoded);
        Ok(buffer)
    }
}

impl PartialEq for FieldType {
    fn eq(&self, other: &Self) -> bool {
        self.type_id == other.type_id && self.generics == other.generics
    }
}

#[derive(Debug)]
pub struct TypeMetaLayer {
    type_id: u32,
    namespace: Rc<MetaString>,
    type_name: Rc<MetaString>,
    register_by_name: bool,
    field_infos: Vec<FieldInfo>,
}

impl TypeMetaLayer {
    pub fn new(
        type_id: u32,
        namespace: MetaString,
        type_name: MetaString,
        register_by_name: bool,
        field_infos: Vec<FieldInfo>,
    ) -> TypeMetaLayer {
        TypeMetaLayer {
            type_id,
            namespace: Rc::from(namespace),
            type_name: Rc::from(type_name),
            register_by_name,
            field_infos,
        }
    }

    pub fn empty() -> TypeMetaLayer {
        TypeMetaLayer {
            type_id: 0,
            namespace: Rc::from(MetaString::get_empty().clone()),
            type_name: Rc::from(MetaString::get_empty().clone()),
            register_by_name: false,
            field_infos: vec![],
        }
    }

    pub fn get_type_id(&self) -> u32 {
        self.type_id
    }

    pub fn get_type_name(&self) -> Rc<MetaString> {
        self.type_name.clone()
    }

    pub fn get_namespace(&self) -> Rc<MetaString> {
        self.namespace.clone()
    }

    pub fn get_field_infos(&self) -> &Vec<FieldInfo> {
        &self.field_infos
    }

    fn write_name(writer: &mut Writer, name: &MetaString, encodings: &[Encoding]) {
        let encoding_idx = encodings.iter().position(|x| *x == name.encoding).unwrap() as u8;
        let bytes = name.bytes.as_slice();
        if bytes.len() >= BIG_NAME_THRESHOLD {
            writer.write_u8((BIG_NAME_THRESHOLD << 2) as u8 | encoding_idx);
            writer.write_varuint32((bytes.len() - BIG_NAME_THRESHOLD) as u32);
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

    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut buffer = vec![];
        // layer_bytes:| meta_header | fields meta |
        let mut writer = Writer::from_buffer(&mut buffer);
        let num_fields = self.field_infos.len();
        let _internal_id = self.type_id & 0xff;
        // meta_header: | unuse:2 bits | is_register_by_id:1 bit | num_fields:4 bits |
        let mut meta_header: u8 = min(num_fields, SMALL_NUM_FIELDS_THRESHOLD) as u8;
        if self.register_by_name {
            meta_header |= REGISTER_BY_NAME_FLAG;
        }
        writer.write_u8(meta_header);
        if num_fields >= SMALL_NUM_FIELDS_THRESHOLD {
            writer.write_varuint32((num_fields - SMALL_NUM_FIELDS_THRESHOLD) as u32);
        }
        if self.register_by_name {
            self.write_namespace(&mut writer);
            self.write_type_name(&mut writer);
        } else {
            writer.write_varuint32(self.type_id);
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
            let type_id = TypeId::try_from(type_id_num as i16).unwrap();
            match type_id {
                TypeId::BOOL => 1,
                TypeId::INT8 => 1,
                TypeId::INT16 => 2,
                TypeId::INT32 => 4,
                TypeId::VAR_INT32 => 4,
                TypeId::INT64 => 8,
                TypeId::VAR_INT64 => 8,
                TypeId::FLOAT16 => 2,
                TypeId::FLOAT32 => 4,
                TypeId::FLOAT64 => 8,
                TypeId::U8 => 1,
                TypeId::U16 => 2,
                TypeId::U32 => 4,
                TypeId::U64 => 8,
                _ => unreachable!(),
            }
        }
        fn is_compress(type_id: u32) -> bool {
            [
                TypeId::INT32 as u32,
                TypeId::INT64 as u32,
                TypeId::VAR_INT32 as u32,
                TypeId::VAR_INT64 as u32,
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
                .then_with(|| a_id.cmp(&b_id)) // when same size: smaller type id first
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

    fn from_bytes(
        reader: &mut Reader,
        type_resolver: &TypeResolver,
    ) -> Result<TypeMetaLayer, Error> {
        let meta_header = reader.read_u8()?;
        let register_by_name = (meta_header & REGISTER_BY_NAME_FLAG) != 0;
        let mut num_fields = meta_header as usize & SMALL_NUM_FIELDS_THRESHOLD;
        if num_fields == SMALL_NUM_FIELDS_THRESHOLD {
            num_fields += reader.read_varuint32()? as usize;
        }
        let type_id;
        let namespace;
        let type_name;
        if register_by_name {
            namespace = Self::read_namespace(reader)?;
            type_name = Self::read_type_name(reader)?;
            type_id = 0;
        } else {
            type_id = reader.read_varuint32()?;
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
                Self::assign_field_ids(&type_info_current, &mut sorted_field_infos);
            }
        } else if let Some(type_info_current) = type_resolver.get_type_info_by_id(type_id) {
            Self::assign_field_ids(&type_info_current, &mut sorted_field_infos);
        }
        // if no type found, keep all fields id as -1 to be skipped.
        Ok(TypeMetaLayer::new(
            type_id,
            namespace,
            type_name,
            register_by_name,
            sorted_field_infos,
        ))
    }

    fn assign_field_ids(type_info_current: &TypeInfo, field_infos: &mut [FieldInfo]) {
        // convert to map: fiend_name -> field_info
        let field_info_map = type_info_current
            .get_type_meta()
            .get_field_infos()
            .iter()
            .map(|field_info| (field_info.field_name.clone(), field_info.clone()))
            .collect::<HashMap<String, FieldInfo>>();
        for field in field_infos.iter_mut() {
            match field_info_map.get(&field.field_name.clone()) {
                Some(local_field_info) => {
                    if field.field_type.type_id != local_field_info.field_type.type_id
                        || field.field_type.generics != local_field_info.field_type.generics
                    {
                        field.field_id = -1;
                    } else {
                        field.field_id = local_field_info.field_id;
                    }
                }
                None => {
                    field.field_id = -1;
                }
            }
        }
    }
}

#[derive(Debug)]
pub struct TypeMeta {
    // assigned valid value and used, only during deserializing
    hash: i64,
    layer: TypeMetaLayer,
}

impl TypeMeta {
    #[inline(always)]
    pub fn get_field_infos(&self) -> &Vec<FieldInfo> {
        self.layer.get_field_infos()
    }

    #[inline(always)]
    pub fn get_type_id(&self) -> u32 {
        self.layer.get_type_id()
    }

    #[inline(always)]
    pub fn get_hash(&self) -> i64 {
        self.hash
    }

    #[inline(always)]
    pub fn get_type_name(&self) -> Rc<MetaString> {
        self.layer.get_type_name()
    }

    #[inline(always)]
    pub fn get_namespace(&self) -> Rc<MetaString> {
        self.layer.get_namespace()
    }

    #[inline(always)]
    pub fn empty() -> TypeMeta {
        TypeMeta {
            hash: 0,
            layer: TypeMetaLayer::empty(),
        }
    }

    pub(crate) fn from_fields(
        type_id: u32,
        namespace: MetaString,
        type_name: MetaString,
        register_by_name: bool,
        field_infos: Vec<FieldInfo>,
    ) -> TypeMeta {
        TypeMeta {
            hash: 0,
            layer: TypeMetaLayer::new(type_id, namespace, type_name, register_by_name, field_infos),
        }
    }

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
        let layer = TypeMetaLayer::from_bytes(reader, type_resolver)?;
        Ok(TypeMeta {
            layer,
            hash: meta_hash,
        })
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
        let layer = TypeMetaLayer::from_bytes(reader, type_resolver)?;
        Ok(TypeMeta {
            layer,
            hash: meta_hash,
        })
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

    pub(crate) fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        // | global_binary_header | layers_bytes |
        let mut buffer = vec![];
        let mut result = Writer::from_buffer(&mut buffer);
        let mut layers_buffer = vec![];
        let mut layers_writer = Writer::from_buffer(&mut layers_buffer);
        // for layer in self.layers.iter() {
        //     layers_writer.bytes(layer.to_bytes()?.as_slice());
        // }
        layers_writer.write_bytes(self.layer.to_bytes()?.as_slice());
        // global_binary_header:| hash:50bits | is_compressed:1bit | write_fields_meta:1bit | meta_size:12bits |
        let meta_size = layers_writer.len() as i64;
        let mut header: i64 = min(META_SIZE_MASK, meta_size);
        let write_meta_fields_flag = !self.get_field_infos().is_empty();
        if write_meta_fields_flag {
            header |= HAS_FIELDS_META_FLAG;
        }
        let is_compressed = false;
        if is_compressed {
            header |= COMPRESS_META_FLAG;
        }
        let meta_hash = murmurhash3_x64_128(layers_writer.dump().as_slice(), 47).0 as i64;
        header |= (meta_hash << (64 - NUM_HASH_BITS)).abs();
        result.write_i64(header);
        if meta_size >= META_SIZE_MASK {
            result.write_varuint32((meta_size - META_SIZE_MASK) as u32);
        }
        result.write_bytes(layers_buffer.as_slice());
        Ok(buffer)
    }
}
