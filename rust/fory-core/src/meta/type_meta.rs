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

use super::meta_string::MetaStringEncoder;
use crate::buffer::{Reader, Writer};
use crate::error::Error;
use crate::meta::murmurhash3_x64_128;
use crate::meta::{Encoding, MetaStringDecoder};
use crate::types::FieldType;
use anyhow::anyhow;
use std::cmp::min;

#[derive(Debug, PartialEq, Eq)]
pub struct FieldTypeResolver {
    pub type_id: i16,
    generics: Vec<FieldTypeResolver>,
}

impl FieldTypeResolver {
    pub fn new(type_id: i16, generics: Vec<FieldTypeResolver>) -> Self {
        FieldTypeResolver { type_id, generics }
    }

    fn to_bytes(&self, writer: &mut Writer, write_flag: bool) -> Result<(), Error> {
        let mut header: i32 = self.type_id as i32;
        // let ref_tracking = false;
        // todo if "Option<T>" is nullability then T nullability=true
        // let nullability = false;
        if write_flag {
            header <<= 2;
        }
        writer.var_int32(header);

        match self.type_id {
            x if x == FieldType::ARRAY as i16 || x == FieldType::SET as i16 => {
                let generic = self.generics.first().unwrap();
                generic.to_bytes(writer, true)?;
            }
            x if x == FieldType::MAP as i16 => {
                let key_generic = self.generics.first().unwrap();
                let val_generic = self.generics.get(1).unwrap();
                key_generic.to_bytes(writer, true)?;
                val_generic.to_bytes(writer, true)?;
            }
            _ => {
                // for generic in self.generics.iter() {
                //     generic.to_bytes(writer, true)?;
                // }
            }
        }
        Ok(())
    }

    #[allow(clippy::needless_late_init)]
    fn from_bytes(reader: &mut Reader, read_flag: bool) -> Self {
        let header = reader.var_int32();
        let type_id;
        if read_flag {
            type_id = (header >> 2) as i16;
            // let tracking_ref = (header & 1) != 0;
            // todo if T is nullability then "Option<T>"
            // let nullable = (header & 2) != 0;
        } else {
            type_id = header as i16;
        }
        match type_id {
            x if x == FieldType::ARRAY as i16 || x == FieldType::SET as i16 => {
                let generic = Self::from_bytes(reader, true);
                Self {
                    type_id,
                    generics: vec![generic],
                }
            }
            x if x == FieldType::MAP as i16 => {
                let key_generic = Self::from_bytes(reader, true);
                let val_generic = Self::from_bytes(reader, true);
                Self {
                    type_id,
                    generics: vec![key_generic, val_generic],
                }
            }
            _ => Self {
                type_id,
                generics: vec![],
            },
        }
    }
}

static META_SIZE_MASK: u64 = 0xfff;

#[derive(Debug, PartialEq, Eq)]
pub struct FieldInfo {
    pub field_name: String,
    pub field_type: FieldTypeResolver,
}

impl FieldInfo {
    pub fn new(field_name: &str, field_type: FieldTypeResolver) -> FieldInfo {
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
        // println!("read field_header:{:?}", header);
        // let nullability = (header & 0b10) != 0;
        // let ref_tracking = (header & 0b1) != 0;
        // let decoding_idx = (header >> 6) & 0b11;
        // println!("decoding_idx:{:?}", decoding_idx);
        let encoding = Self::u8_to_encoding((header >> 6) & 0b11).unwrap();
        let mut name_size = ((header & 0b0011_1100) >> 2) as usize;
        if name_size == 15 {
            name_size += reader.var_int32() as usize;
        }
        name_size += 1;

        let field_type = FieldTypeResolver::from_bytes(reader, false);

        let field_name_bytes = reader.bytes(name_size);

        let field_name = MetaStringDecoder::new()
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
        let encoding_options: &[Encoding] = &[
            Encoding::Utf8,
            Encoding::AllToLowerSpecial,
            Encoding::LowerUpperDigitSpecial,
        ];
        let meta_string = MetaStringEncoder::new()
            .set_options(Some(encoding_options))
            .encode(&self.field_name)?;
        let name_encoded = meta_string.bytes.as_slice();
        let name_size = name_encoded.len() - 1;
        let mut header: u8 = (min(0b1111, name_size) as u8) << 2;
        let ref_tracking = false;
        let nullability = false;
        if ref_tracking {
            header |= 1;
        }
        if nullability {
            header |= 0b10;
        }
        let encoding_idx = encoding_options
            .iter()
            .position(|x| *x == meta_string.encoding)
            .unwrap() as u8;
        header |= encoding_idx << 6;
        writer.u8(header);
        if name_size >= 15 {
            writer.var_int32((name_size - 15) as i32);
        }
        // write type_info
        self.field_type.to_bytes(&mut writer, false)?;
        // write field_name
        writer.bytes(name_encoded);
        Ok(writer.dump())
    }
}

#[derive(Debug)]
pub struct TypeMetaLayer {
    type_id: u32,
    field_infos: Vec<FieldInfo>,
}

impl TypeMetaLayer {
    pub fn new(type_id: u32, field_infos: Vec<FieldInfo>) -> TypeMetaLayer {
        TypeMetaLayer {
            type_id,
            field_infos,
        }
    }

    pub fn get_type_id(&self) -> u32 {
        self.type_id
    }

    pub fn get_field_infos(&self) -> &Vec<FieldInfo> {
        &self.field_infos
    }

    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        // layer_bytes:| meta_header | fields meta |
        let mut writer = Writer::default();
        let num_fields = self.field_infos.len() - 1;
        let is_register_by_name = false;
        // meta_header: | unuse:2 bits | is_register_by_id:1 bit | num_fields:4 bits |
        let mut meta_header: u8 = min(num_fields, 0b1111) as u8;
        if is_register_by_name {
            meta_header |= 0b10_0000;
        }
        // println!("write meta_header:{:?}", meta_header);
        writer.u8(meta_header);
        if num_fields >= 0b1_1111 {
            writer.var_int32(num_fields as i32 - 0b1_1111);
        }
        if is_register_by_name {
            todo!()
        } else {
            writer.var_int32(self.type_id as i32);
        }
        for field in self.field_infos.iter() {
            // println!("cur field:{:?}", field);
            writer.bytes(field.to_bytes()?.as_slice());
        }
        Ok(writer.dump())
    }

    fn from_bytes(reader: &mut Reader) -> TypeMetaLayer {
        let meta_header = reader.u8();
        // println!("read meta_header:{:?}", meta_header);
        // let is_register_by_name = (meta_header & 0b10_0000) == 1;
        let is_register_by_name = false;
        let mut num_fields = (meta_header & 0b1111) as i32;
        if num_fields == 15 {
            num_fields += reader.var_int32();
        }
        num_fields += 1;
        let type_id;
        if is_register_by_name {
            todo!()
        } else {
            type_id = reader.var_int32() as u32;
        }
        let mut field_infos = Vec::with_capacity(num_fields as usize);
        for _ in 0..num_fields {
            field_infos.push(FieldInfo::from_bytes(reader));
        }

        TypeMetaLayer::new(type_id, field_infos)
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

    pub fn from_fields(type_id: u32, field_infos: Vec<FieldInfo>) -> TypeMeta {
        TypeMeta {
            // hash: 0,
            layers: vec![TypeMetaLayer::new(type_id, field_infos)],
        }
    }
    #[allow(unused_assignments)]
    pub fn from_bytes(reader: &mut Reader) -> TypeMeta {
        let header = reader.u64();
        let mut meta_size = header & META_SIZE_MASK;
        if meta_size == META_SIZE_MASK {
            meta_size += reader.var_int32() as u64;
        }

        // let write_fields_meta = (header & (1 << 12)) != 0;
        // let is_compressed: bool = (header & (1 << 13)) != 0;
        // let meta_hash = header >> 14;

        let mut layers = Vec::new();
        // let current_meta_size = 0;
        // while current_meta_size < meta_size {}
        let layer = TypeMetaLayer::from_bytes(reader);
        layers.push(layer);
        TypeMeta { layers }
    }

    pub fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        // println!("{:#?}", self);
        // | global_binary_header | layers_bytes |
        let mut result = Writer::default();
        let mut layers_writer = Writer::default();
        // for layer in self.layers.iter() {
        //     layers_writer.bytes(layer.to_bytes()?.as_slice());
        // }
        layers_writer.bytes(self.layers.first().unwrap().to_bytes()?.as_slice());
        // global_binary_header:| hash:50bits | is_compressed:1bit | write_fields_meta:1bit | meta_size:12bits |
        let meta_size = layers_writer.len() as u64;
        let mut header: u64 = min(META_SIZE_MASK, meta_size);
        let write_meta_fields_flag = true;
        if write_meta_fields_flag {
            header |= 1 << 12;
        }
        let is_compressed = false;
        if is_compressed {
            header |= 1 << 13;
        }
        let meta_hash = murmurhash3_x64_128(layers_writer.dump().as_slice(), 47).0;
        header |= meta_hash << 14;
        result.u64(header);
        // extra byte
        if meta_size >= META_SIZE_MASK {
            result.var_int32((meta_size - META_SIZE_MASK) as i32);
        }
        // layers_bytes
        result.bytes(layers_writer.dump().as_slice());
        Ok(result.dump())
    }
}
