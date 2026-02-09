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
use crate::meta::TypeMeta;
use crate::resolver::type_resolver::{TypeInfo, NO_USER_TYPE_ID};
use crate::TypeResolver;
use std::collections::HashMap;
use std::rc::Rc;

/// Streaming meta writer that writes TypeMeta inline during serialization.
/// Uses the streaming protocol:
/// - (index << 1) | 0 for new type definition (followed by TypeMeta bytes)
/// - (index << 1) | 1 for reference to previously written type
#[derive(Default)]
pub struct MetaWriterResolver {
    type_id_index_map: HashMap<std::any::TypeId, usize>,
}

const MAX_PARSED_NUM_TYPE_DEFS: usize = 8192;

#[allow(dead_code)]
impl MetaWriterResolver {
    /// Write type meta inline using streaming protocol.
    /// Returns the index assigned to this type.
    #[inline(always)]
    pub fn write_type_meta(
        &mut self,
        writer: &mut Writer,
        type_id: std::any::TypeId,
        type_resolver: &TypeResolver,
    ) -> Result<(), Error> {
        match self.type_id_index_map.get(&type_id) {
            Some(&index) => {
                // Reference to previously written type: (index << 1) | 1, LSB=1
                writer.write_var_uint32(((index as u32) << 1) | 1);
            }
            None => {
                // New type: index << 1, LSB=0, followed by TypeMeta bytes inline
                let index = self.type_id_index_map.len();
                writer.write_var_uint32((index as u32) << 1);
                self.type_id_index_map.insert(type_id, index);
                // Write TypeMeta bytes inline
                let type_def = type_resolver.get_type_info(&type_id)?.get_type_def();
                writer.write_bytes(&type_def);
            }
        }
        Ok(())
    }

    #[inline(always)]
    pub fn reset(&mut self) {
        self.type_id_index_map.clear();
    }
}

/// Streaming meta reader that reads TypeMeta inline during deserialization.
/// Uses the streaming protocol:
/// - (index << 1) | 0 for new type definition (followed by TypeMeta bytes)
/// - (index << 1) | 1 for reference to previously read type
#[derive(Default)]
pub struct MetaReaderResolver {
    pub reading_type_infos: Vec<Rc<TypeInfo>>,
    parsed_type_infos: HashMap<i64, Rc<TypeInfo>>,
    last_meta_header: i64,
    last_type_info: Option<Rc<TypeInfo>>,
}

impl MetaReaderResolver {
    #[inline(always)]
    pub fn get(&self, index: usize) -> Option<&Rc<TypeInfo>> {
        self.reading_type_infos.get(index)
    }

    /// Read type meta inline using streaming protocol.
    /// Returns the TypeInfo for this type.
    #[inline(always)]
    pub fn read_type_meta(
        &mut self,
        reader: &mut Reader,
        type_resolver: &TypeResolver,
    ) -> Result<Rc<TypeInfo>, Error> {
        let index_marker = reader.read_varuint32()?;
        let is_ref = (index_marker & 1) == 1;
        let index = (index_marker >> 1) as usize;

        if is_ref {
            // Reference to previously read type
            self.reading_type_infos.get(index).cloned().ok_or_else(|| {
                Error::type_error(format!("TypeInfo not found for type index: {}", index))
            })
        } else {
            // New type - read TypeMeta inline
            let meta_header = reader.read_i64()?;
            if let Some(type_info) = self
                .last_type_info
                .as_ref()
                .filter(|_| self.last_meta_header == meta_header)
            {
                self.reading_type_infos.push(type_info.clone());
                TypeMeta::skip_bytes(reader, meta_header)?;
                return Ok(type_info.clone());
            }
            if let Some(type_info) = self.parsed_type_infos.get(&meta_header) {
                self.last_meta_header = meta_header;
                self.last_type_info = Some(type_info.clone());
                self.reading_type_infos.push(type_info.clone());
                TypeMeta::skip_bytes(reader, meta_header)?;
                Ok(type_info.clone())
            } else {
                let type_meta = Rc::new(TypeMeta::from_bytes_with_header(
                    reader,
                    type_resolver,
                    meta_header,
                )?);

                // Try to find local type info
                let namespace = &type_meta.get_namespace().original;
                let type_name = &type_meta.get_type_name().original;
                let register_by_name = !namespace.is_empty() || !type_name.is_empty();
                let type_info = if register_by_name {
                    // Registered by name (namespace can be empty)
                    if let Some(local_type_info) =
                        type_resolver.get_type_info_by_name(namespace, type_name)
                    {
                        // Use local harness with remote metadata
                        Rc::new(TypeInfo::from_remote_meta(
                            type_meta.clone(),
                            Some(local_type_info.get_harness()),
                            Some(local_type_info.get_type_id() as u32),
                            Some(local_type_info.get_user_type_id()),
                        ))
                    } else {
                        // No local type found, use stub harness
                        Rc::new(TypeInfo::from_remote_meta(
                            type_meta.clone(),
                            None,
                            None,
                            None,
                        ))
                    }
                } else {
                    // Registered by ID
                    let type_id = type_meta.get_type_id();
                    let user_type_id = type_meta.get_user_type_id();
                    if user_type_id != NO_USER_TYPE_ID {
                        if let Some(local_type_info) =
                            type_resolver.get_user_type_info_by_id(user_type_id)
                        {
                            // Use local harness with remote metadata
                            Rc::new(TypeInfo::from_remote_meta(
                                type_meta.clone(),
                                Some(local_type_info.get_harness()),
                                Some(local_type_info.get_type_id() as u32),
                                Some(local_type_info.get_user_type_id()),
                            ))
                        } else {
                            // No local type found, use stub harness
                            Rc::new(TypeInfo::from_remote_meta(
                                type_meta.clone(),
                                None,
                                None,
                                None,
                            ))
                        }
                    } else if let Some(local_type_info) = type_resolver.get_type_info_by_id(type_id)
                    {
                        // Use local harness with remote metadata
                        Rc::new(TypeInfo::from_remote_meta(
                            type_meta.clone(),
                            Some(local_type_info.get_harness()),
                            Some(local_type_info.get_type_id() as u32),
                            Some(local_type_info.get_user_type_id()),
                        ))
                    } else {
                        // No local type found, use stub harness
                        Rc::new(TypeInfo::from_remote_meta(
                            type_meta.clone(),
                            None,
                            None,
                            None,
                        ))
                    }
                };

                if self.parsed_type_infos.len() < MAX_PARSED_NUM_TYPE_DEFS {
                    // avoid malicious type defs to OOM parsed_type_infos
                    self.parsed_type_infos
                        .insert(meta_header, type_info.clone());
                }
                self.last_meta_header = meta_header;
                self.last_type_info = Some(type_info.clone());
                self.reading_type_infos.push(type_info.clone());
                Ok(type_info)
            }
        }
    }

    #[inline(always)]
    pub fn reset(&mut self) {
        self.reading_type_infos.clear();
    }
}
