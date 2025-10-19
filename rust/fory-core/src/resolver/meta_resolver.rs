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
use crate::meta::{Encoding, MetaString, TypeMeta, NAMESPACE_DECODER};
use crate::TypeResolver;
use std::collections::HashMap;
use std::sync::Arc;

#[derive(Default)]
pub struct MetaWriterResolver {
    type_defs: Vec<Arc<Vec<u8>>>,
    type_id_index_map: HashMap<std::any::TypeId, usize>,
}

const MAX_PARSED_NUM_TYPE_DEFS: usize = 8192;

#[allow(dead_code)]
impl MetaWriterResolver {
    pub fn push(
        &mut self,
        type_id: std::any::TypeId,
        type_resolver: &TypeResolver,
    ) -> Result<usize, Error> {
        match self.type_id_index_map.get(&type_id) {
            None => {
                let index = self.type_defs.len();
                self.type_defs
                    .push(type_resolver.get_type_info(&type_id)?.get_type_def());
                self.type_id_index_map.insert(type_id, index);
                Ok(index)
            }
            Some(index) => Ok(*index),
        }
    }

    pub fn to_bytes(&self, writer: &mut Writer) {
        writer.write_varuint32(self.type_defs.len() as u32);
        for item in &self.type_defs {
            writer.write_bytes(item);
        }
    }

    pub fn empty(&mut self) -> bool {
        self.type_defs.is_empty()
    }

    pub fn reset(&mut self) {
        self.type_defs.clear();
        self.type_id_index_map.clear();
    }
}

#[derive(Default)]
pub struct MetaReaderResolver {
    pub reading_type_defs: Vec<Arc<TypeMeta>>,
    parsed_type_defs: HashMap<i64, Arc<TypeMeta>>,
}

impl MetaReaderResolver {
    pub fn get(&self, index: usize) -> Option<&Arc<TypeMeta>> {
        self.reading_type_defs.get(index)
    }

    pub fn load(
        &mut self,
        type_resolver: &TypeResolver,
        reader: &mut Reader,
    ) -> Result<usize, Error> {
        let meta_size = reader.read_varuint32()?;
        // self.reading_type_defs.reserve(meta_size as usize);
        for _ in 0..meta_size {
            let meta_header = reader.read_i64()?;
            if let Some(type_meta) = self.parsed_type_defs.get(&meta_header) {
                self.reading_type_defs.push(type_meta.clone());
                TypeMeta::skip_bytes(reader, meta_header)?;
            } else {
                let type_meta = Arc::new(TypeMeta::from_bytes_with_header(
                    reader,
                    type_resolver,
                    meta_header,
                )?);
                if self.parsed_type_defs.len() < MAX_PARSED_NUM_TYPE_DEFS {
                    // avoid malicious type defs to OOM parsed_type_defs
                    self.parsed_type_defs.insert(meta_header, type_meta.clone());
                }
                self.reading_type_defs.push(type_meta);
            }
        }
        Ok(reader.get_cursor())
    }

    pub fn read_metastring(&self, reader: &mut Reader) -> Result<MetaString, Error> {
        let len = reader.read_varuint32()? as usize;
        if len == 0 {
            return Ok(MetaString {
                bytes: vec![],
                encoding: Encoding::Utf8,
                original: String::new(),
                strip_last_char: false,
                special_char1: '\0',
                special_char2: '\0',
            });
        }
        let bytes = reader.read_bytes(len)?;
        let encoding_byte = bytes[0] & 0x07;
        let encoding = match encoding_byte {
            0x00 => Encoding::Utf8,
            0x01 => Encoding::LowerSpecial,
            0x02 => Encoding::LowerUpperDigitSpecial,
            0x03 => Encoding::FirstToLowerSpecial,
            0x04 => Encoding::AllToLowerSpecial,
            _ => Encoding::Utf8,
        };
        NAMESPACE_DECODER.decode(bytes, encoding)
    }

    pub fn reset(&mut self) {
        self.reading_type_defs.clear();
    }
}
