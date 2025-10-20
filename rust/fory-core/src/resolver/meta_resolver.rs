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
use crate::resolver::type_resolver::TypeInfo;
use crate::TypeResolver;
use std::collections::HashMap;
use std::rc::Rc;

#[derive(Default)]
pub struct MetaWriterResolver {
    type_defs: Vec<Rc<Vec<u8>>>,
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
    pub reading_type_infos: Vec<Rc<TypeInfo>>,
    parsed_type_infos: HashMap<i64, Rc<TypeInfo>>,
}

impl MetaReaderResolver {
    pub fn get(&self, index: usize) -> Option<&Rc<TypeInfo>> {
        self.reading_type_infos.get(index)
    }

    pub fn load(
        &mut self,
        type_resolver: &TypeResolver,
        reader: &mut Reader,
    ) -> Result<usize, Error> {
        let meta_size = reader.read_varuint32()?;
        // self.reading_type_infos.reserve(meta_size as usize);
        for _ in 0..meta_size {
            let meta_header = reader.read_i64()?;
            if let Some(type_info) = self.parsed_type_infos.get(&meta_header) {
                self.reading_type_infos.push(type_info.clone());
                TypeMeta::skip_bytes(reader, meta_header)?;
            } else {
                let type_meta = Rc::new(TypeMeta::from_bytes_with_header(
                    reader,
                    type_resolver,
                    meta_header,
                )?);

                // Try to find local type info
                let type_info = if type_meta.get_namespace().original.is_empty() {
                    // Registered by ID
                    let type_id = type_meta.get_type_id();
                    if let Some(local_type_info) = type_resolver.get_type_info_by_id(type_id) {
                        // Use local harness with remote metadata
                        Rc::new(TypeInfo::from_remote_meta(
                            type_meta.clone(),
                            Some(local_type_info.get_harness()),
                        ))
                    } else {
                        // No local type found, use stub harness
                        Rc::new(TypeInfo::from_remote_meta(type_meta.clone(), None))
                    }
                } else {
                    // Registered by name
                    let namespace = &type_meta.get_namespace().original;
                    let type_name = &type_meta.get_type_name().original;
                    if let Some(local_type_info) =
                        type_resolver.get_type_info_by_name(namespace, type_name)
                    {
                        // Use local harness with remote metadata
                        Rc::new(TypeInfo::from_remote_meta(
                            type_meta.clone(),
                            Some(local_type_info.get_harness()),
                        ))
                    } else {
                        // No local type found, use stub harness
                        Rc::new(TypeInfo::from_remote_meta(type_meta.clone(), None))
                    }
                };

                if self.parsed_type_infos.len() < MAX_PARSED_NUM_TYPE_DEFS {
                    // avoid malicious type defs to OOM parsed_type_infos
                    self.parsed_type_infos
                        .insert(meta_header, type_info.clone());
                }
                self.reading_type_infos.push(type_info);
            }
        }
        Ok(reader.get_cursor())
    }

    pub fn read_metastring(&self, reader: &mut Reader) -> Result<MetaString, Error> {
        let len = reader.read_varuint32()? as usize;
        if len == 0 {
            return Ok(MetaString::get_empty().clone());
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
        self.reading_type_infos.clear();
    }
}
