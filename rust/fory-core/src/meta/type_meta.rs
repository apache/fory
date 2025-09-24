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
    FIELD_NAME_ENCODER, NAMESPACE_DECODER,
};
use crate::types::TypeId;
use anyhow::anyhow;
use std::clone::Clone;
use std::cmp::min;

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
    pub generics: Vec<FieldType>,
}

#[derive(Debug, Clone)]
pub struct NullableFieldType {
    pub type_id: u32,
    pub generics: Vec<NullableFieldType>,
    pub nullable: bool,
}

impl NullableFieldType {
    pub fn from(node: FieldType) -> Self {
        if node.type_id == TypeId::ForyNullable as u32 {
            let inner = NullableFieldType::from(node.generics.into_iter().next().unwrap());
            NullableFieldType {
                type_id: inner.type_id,
                generics: inner.generics,
                nullable: true,
            }
        } else {
            let generics = node
                .generics
                .into_iter()
                .map(NullableFieldType::from)
                .collect();
            NullableFieldType {
                type_id: node.type_id,
                generics,
                nullable: false,
            }
        }
    }
}

impl PartialEq for NullableFieldType {
    fn eq(&self, other: &Self) -> bool {
        self.type_id == other.type_id && self.generics == other.generics
    }
}

impl Eq for NullableFieldType {}

impl FieldType {
    pub fn new(type_id: u32, generics: Vec<FieldType>) -> Self {
        FieldType { type_id, generics }
    }

    fn to_bytes(&self, writer: &mut Writer, write_flag: bool, nullable: bool) -> Result<(), Error> {
        if self.type_id == TypeId::ForyNullable as u32 {
            self.generics
                .first()
                .unwrap()
                .to_bytes(writer, write_flag, true)?;
            return Ok(());
        }
        let mut header = self.type_id;
        if write_flag {
            header <<= 2;
            // let ref_tracking = false;
            if nullable {
                header |= 2;
            }
        }
        writer.var_uint32(header);
        match self.type_id {
            x if x == TypeId::LIST as u32 || x == TypeId::SET as u32 => {
                let generic = self.generics.first().unwrap();
                generic.to_bytes(writer, true, false)?;
            }
            x if x == TypeId::MAP as u32 => {
                let key_generic = self.generics.first().unwrap();
                let val_generic = self.generics.get(1).unwrap();
                key_generic.to_bytes(writer, true, false)?;
                val_generic.to_bytes(writer, true, false)?;
            }
            _ => {}
        }
        Ok(())
    }

    fn from_bytes(reader: &mut Reader, read_flag: bool, nullable: Option<bool>) -> Self {
        let header = reader.var_uint32();
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
        let field_type = match type_id {
            x if x == TypeId::LIST as u32 || x == TypeId::SET as u32 => {
                let generic = Self::from_bytes(reader, true, None);
                Self {
                    type_id,
                    generics: vec![generic],
                }
            }
            x if x == TypeId::MAP as u32 => {
                let key_generic = Self::from_bytes(reader, true, None);
                let val_generic = Self::from_bytes(reader, true, None);
                Self {
                    type_id,
                    generics: vec![key_generic, val_generic],
                }
            }
            _ => Self {
                type_id,
                generics: vec![],
            },
        };
        if _nullable {
            Self {
                type_id: TypeId::ForyNullable as u32,
                generics: vec![field_type],
            }
        } else {
            field_type
        }
    }
}

#[derive(Debug, PartialEq, Eq, Clone)]
pub struct FieldInfo {
    pub field_name: String,
    pub field_type: FieldType,
}

impl FieldInfo {
    pub fn new(field_name: &str, field_type: FieldType) -> FieldInfo {
        FieldInfo {
            field_name: field_name.to_string(),
            field_type,
        }
    }

    fn u8_to_encoding(value: u8) -> Result<Encoding, Error> {
        match value {
            0x00 => Ok(Encoding::Utf8),
            0x01 => Ok(Encoding::AllToLowerSpecial),
            0x02 => Ok(Encoding::LowerUpperDigitSpecial),
            _ => Err(anyhow!(
                "Unsupported encoding of field name in type meta, value:{value}"
            ))?,
        }
    }

    pub fn from_bytes(reader: &mut Reader) -> FieldInfo {
        let header = reader.u8();
        let nullable = (header & 2) != 0;
        // let ref_tracking = (header & 1) != 0;
        let encoding = Self::u8_to_encoding((header >> 6) & 0b11).unwrap();
        let mut name_size = ((header >> 2) & FIELD_NAME_SIZE_THRESHOLD as u8) as usize;
        if name_size == FIELD_NAME_SIZE_THRESHOLD {
            name_size += reader.var_uint32() as usize;
        }
        name_size += 1;

        let field_type = FieldType::from_bytes(reader, false, Option::from(nullable));

        let field_name_bytes = reader.bytes(name_size);

        let field_name = FIELD_NAME_DECODER
            .decode(field_name_bytes, encoding)
            .unwrap();
        FieldInfo {
            field_name,
            field_type,
        }
    }

    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        // field_bytes: | header | type_info | field_name |
        let mut writer = Writer::default();
        // header: | field_name_encoding:2bits | size:4bits | nullability:1bit | ref_tracking:1bit |
        let meta_string =
            FIELD_NAME_ENCODER.encode_with_encodings(&self.field_name, FIELD_NAME_ENCODINGS)?;
        let name_encoded = meta_string.bytes.as_slice();
        let name_size = name_encoded.len() - 1;
        let mut header: u8 = (min(FIELD_NAME_SIZE_THRESHOLD, name_size) as u8) << 2;
        // let ref_tracking = false;
        let nullable = self.field_type.type_id == TypeId::ForyNullable as u32;
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
        writer.u8(header);
        if name_size >= FIELD_NAME_SIZE_THRESHOLD {
            writer.var_uint32((name_size - FIELD_NAME_SIZE_THRESHOLD) as u32);
        }
        self.field_type.to_bytes(&mut writer, false, nullable)?;
        // write field_name
        writer.bytes(name_encoded);
        Ok(writer.dump())
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
    namespace: MetaString,
    type_name: MetaString,
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
            namespace,
            type_name,
            register_by_name,
            field_infos,
        }
    }

    pub fn get_type_id(&self) -> u32 {
        self.type_id
    }

    pub fn get_type_name(&self) -> MetaString {
        self.type_name.clone()
    }

    pub fn get_namespace(&self) -> MetaString {
        self.namespace.clone()
    }

    pub fn get_field_infos(&self) -> &Vec<FieldInfo> {
        &self.field_infos
    }

    fn write_name(writer: &mut Writer, name: &MetaString, encodings: &[Encoding]) {
        let encoding_idx = encodings.iter().position(|x| *x == name.encoding).unwrap() as u8;
        let bytes = name.bytes.as_slice();
        if bytes.len() >= BIG_NAME_THRESHOLD {
            writer.u8((BIG_NAME_THRESHOLD << 2) as u8 | encoding_idx);
            writer.var_uint32((bytes.len() - BIG_NAME_THRESHOLD) as u32);
        } else {
            writer.u8((bytes.len() << 2) as u8 | encoding_idx);
        }
        writer.bytes(bytes);
    }

    pub fn write_namespace(&self, writer: &mut Writer) {
        Self::write_name(writer, &self.namespace, NAMESPACE_ENCODINGS);
    }

    pub fn write_type_name(&self, writer: &mut Writer) {
        Self::write_name(writer, &self.type_name, TYPE_NAME_ENCODINGS);
    }

    fn read_name(
        reader: &mut Reader,
        decoder: &MetaStringDecoder,
        encodings: &[Encoding],
    ) -> MetaString {
        let header = reader.u8();
        let encoding_idx = header & 0b11;
        let length = header >> 2;
        let length = if length >= BIG_NAME_THRESHOLD as u8 {
            BIG_NAME_THRESHOLD + reader.var_uint32() as usize
        } else {
            length as usize
        };
        let bytes = reader.bytes(length);
        let encoding = encodings[encoding_idx as usize];
        let name_str = decoder.decode(bytes, encoding).unwrap();
        MetaString::new(
            name_str,
            encoding,
            bytes.to_vec(),
            decoder.special_char1,
            decoder.special_char2,
        )
        .unwrap()
    }

    pub fn read_namespace(reader: &mut Reader) -> MetaString {
        Self::read_name(reader, &NAMESPACE_DECODER, NAMESPACE_ENCODINGS)
    }

    pub fn read_type_name(reader: &mut Reader) -> MetaString {
        Self::read_name(reader, &NAMESPACE_DECODER, TYPE_NAME_ENCODINGS)
    }

    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        // layer_bytes:| meta_header | fields meta |
        let mut writer = Writer::default();
        let num_fields = self.field_infos.len();
        let _internal_id = self.type_id & 0xff;
        // meta_header: | unuse:2 bits | is_register_by_id:1 bit | num_fields:4 bits |
        let mut meta_header: u8 = min(num_fields, SMALL_NUM_FIELDS_THRESHOLD) as u8;
        if self.register_by_name {
            meta_header |= REGISTER_BY_NAME_FLAG;
        }
        writer.u8(meta_header);
        if num_fields >= SMALL_NUM_FIELDS_THRESHOLD {
            writer.var_uint32((num_fields - SMALL_NUM_FIELDS_THRESHOLD) as u32);
        }
        if self.register_by_name {
            self.write_namespace(&mut writer);
            self.write_type_name(&mut writer);
        } else {
            writer.var_uint32(self.type_id);
        }
        for field in self.field_infos.iter() {
            writer.bytes(field.to_bytes()?.as_slice());
        }
        Ok(writer.dump())
    }

    fn from_bytes(reader: &mut Reader) -> TypeMetaLayer {
        let meta_header = reader.u8();
        let register_by_name = (meta_header & REGISTER_BY_NAME_FLAG) != 0;
        let mut num_fields = meta_header as usize & SMALL_NUM_FIELDS_THRESHOLD;
        if num_fields == SMALL_NUM_FIELDS_THRESHOLD {
            num_fields += reader.var_uint32() as usize;
        }
        let type_id;
        let namespace;
        let type_name;
        if register_by_name {
            namespace = Self::read_namespace(reader);
            type_name = Self::read_type_name(reader);
            type_id = TypeId::UNKNOWN as u32;
        } else {
            type_id = reader.var_uint32();
            let empty_name = MetaString::default();
            namespace = empty_name.clone();
            type_name = empty_name;
        }
        let mut field_infos = Vec::with_capacity(num_fields);
        for _ in 0..num_fields {
            field_infos.push(FieldInfo::from_bytes(reader));
        }

        TypeMetaLayer::new(type_id, namespace, type_name, register_by_name, field_infos)
    }
}

#[derive(Debug)]
pub struct TypeMeta {
    // hash: u64,
    layers: Vec<TypeMetaLayer>,
}

impl TypeMeta {
    pub fn get_field_infos(&self) -> &Vec<FieldInfo> {
        self.layers.first().unwrap().get_field_infos()
    }

    pub fn get_type_id(&self) -> u32 {
        self.layers.first().unwrap().get_type_id()
    }

    pub fn get_type_name(&self) -> MetaString {
        self.layers.first().unwrap().get_type_name()
    }

    pub fn get_namespace(&self) -> MetaString {
        self.layers.first().unwrap().get_namespace()
    }

    pub fn from_fields(
        type_id: u32,
        namespace: MetaString,
        type_name: MetaString,
        register_by_name: bool,
        field_infos: Vec<FieldInfo>,
    ) -> TypeMeta {
        TypeMeta {
            // hash: 0,
            layers: vec![TypeMetaLayer::new(
                type_id,
                namespace,
                type_name,
                register_by_name,
                field_infos,
            )],
        }
    }
    #[allow(unused_assignments)]
    pub fn from_bytes(reader: &mut Reader) -> TypeMeta {
        let header = reader.i64();
        let mut meta_size = header & META_SIZE_MASK;
        if meta_size == META_SIZE_MASK {
            meta_size += reader.var_uint32() as i64;
        }

        // let write_fields_meta = (header & HAS_FIELDS_META_FLAG) != 0;
        // let is_compressed: bool = (header & COMPRESS_META_FLAG) != 0;
        // let meta_hash = header >> (64 - NUM_HASH_BITS);

        let mut layers = Vec::new();
        // let current_meta_size = 0;
        // while current_meta_size < meta_size {}
        let layer = TypeMetaLayer::from_bytes(reader);
        layers.push(layer);
        TypeMeta { layers }
    }

    pub fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        // | global_binary_header | layers_bytes |
        let mut result = Writer::default();
        let mut layers_writer = Writer::default();
        // for layer in self.layers.iter() {
        //     layers_writer.bytes(layer.to_bytes()?.as_slice());
        // }
        layers_writer.bytes(self.layers.first().unwrap().to_bytes()?.as_slice());
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
        result.i64(header);
        if meta_size >= META_SIZE_MASK {
            result.var_uint32((meta_size - META_SIZE_MASK) as u32);
        }
        result.bytes(layers_writer.dump().as_slice());
        Ok(result.dump())
    }
}
