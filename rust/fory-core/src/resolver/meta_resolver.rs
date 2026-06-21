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
use crate::config::Config;
use crate::error::Error;
use crate::meta::TypeMeta;
use crate::resolver::type_resolver::NO_USER_TYPE_ID;
use crate::resolver::{TypeInfo, TypeResolver};
use std::collections::HashMap;
use std::rc::Rc;

/// Streaming meta writer that writes TypeMeta inline during serialization.
/// Uses the streaming protocol:
/// - (index << 1) | 0 for new type definition (followed by TypeMeta bytes)
/// - (index << 1) | 1 for reference to previously written type
#[derive(Default)]
pub struct MetaWriterResolver {
    type_id_index_map: HashMap<std::any::TypeId, usize>,
    type_index_index_map: Vec<usize>,
    next_index: usize,
}

const MIN_REMOTE_TYPE_META_LIMIT: usize = 8192;
const NO_WRITTEN_TYPE_INDEX: usize = usize::MAX;

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
                writer.write_var_u32(((index as u32) << 1) | 1);
            }
            None => {
                // New type: index << 1, LSB=0, followed by TypeMeta bytes inline
                let index = self.next_index;
                self.next_index += 1;
                writer.write_var_u32((index as u32) << 1);
                self.type_id_index_map.insert(type_id, index);
                // Write TypeMeta bytes inline
                let type_def = type_resolver.get_type_info(&type_id)?.get_type_def();
                writer.write_bytes(&type_def);
            }
        }
        Ok(())
    }

    /// Write type meta by generated struct type index, avoiding Rust TypeId hash lookup.
    #[inline(always)]
    pub fn write_type_meta_fast(
        &mut self,
        writer: &mut Writer,
        type_id: std::any::TypeId,
        type_index: u32,
        type_resolver: &TypeResolver,
    ) -> Result<(), Error> {
        let type_index = type_index as usize;
        if let Some(&index) = self.type_index_index_map.get(type_index) {
            if index != NO_WRITTEN_TYPE_INDEX {
                writer.write_var_u32(((index as u32) << 1) | 1);
                return Ok(());
            }
        }

        let index = self.next_index;
        self.next_index += 1;
        writer.write_var_u32((index as u32) << 1);
        if type_index >= self.type_index_index_map.len() {
            self.type_index_index_map
                .resize(type_index + 1, NO_WRITTEN_TYPE_INDEX);
        }
        self.type_index_index_map[type_index] = index;
        let type_meta = type_resolver.get_type_meta_by_index_ref(&type_id, type_index as u32)?;
        writer.write_bytes(type_meta.get_bytes());
        Ok(())
    }

    #[inline(always)]
    pub fn reset(&mut self) {
        self.type_id_index_map.clear();
        self.type_index_index_map.clear();
        self.next_index = 0;
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
    remote_schema_versions_by_type: HashMap<String, usize>,
    total_accepted_schema_versions: usize,
    cached_meta_header: i64,
    cached_type_info: Option<Rc<TypeInfo>>,
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
        config: &Config,
    ) -> Result<Rc<TypeInfo>, Error> {
        let index_marker = reader.read_var_u32()?;
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
                .cached_type_info
                .as_ref()
                .filter(|_| self.cached_meta_header == meta_header)
            {
                // Header-cache hits intentionally skip without rehashing. Entries reach this cache
                // only after a successful TypeMeta parse and 52-bit metadata-hash validation. Do
                // not add body/hash/schema-limit/exact-local checks here; the miss path owns them
                // before publish.
                self.reading_type_infos.push(type_info.clone());
                TypeMeta::skip_bytes_for_validated_header(reader, meta_header)?;
                return Ok(type_info.clone());
            }
            if let Some(type_info) = self.parsed_type_infos.get(&meta_header) {
                // Header-cache hits intentionally skip without rehashing. Entries reach this cache
                // only after a successful TypeMeta parse and 52-bit metadata-hash validation. Do
                // not add body/hash/schema-limit/exact-local checks here; the miss path owns them
                // before publish.
                self.cached_meta_header = meta_header;
                self.cached_type_info = Some(type_info.clone());
                self.reading_type_infos.push(type_info.clone());
                TypeMeta::skip_bytes_for_validated_header(reader, meta_header)?;
                Ok(type_info.clone())
            } else {
                let type_def_start = reader.get_cursor() - std::mem::size_of::<i64>();
                self.read_remote_type_meta(
                    reader,
                    type_resolver,
                    config,
                    meta_header,
                    type_def_start,
                )
            }
        }
    }

    #[cold]
    #[inline(never)]
    fn read_remote_type_meta(
        &mut self,
        reader: &mut Reader,
        type_resolver: &TypeResolver,
        config: &Config,
        meta_header: i64,
        type_def_start: usize,
    ) -> Result<Rc<TypeInfo>, Error> {
        let type_meta = Rc::new(TypeMeta::from_bytes_with_header(
            reader,
            type_resolver,
            meta_header,
            config.max_type_fields(),
            config.max_type_meta_bytes(),
        )?);
        let remote_type_def = reader.sub_slice(type_def_start, reader.get_cursor())?;

        let namespace = type_meta.get_namespace();
        let type_name = type_meta.get_type_name();
        let register_by_name = !namespace.original.is_empty() || !type_name.original.is_empty();
        let mut remote_schema_key = None;
        let type_info = if register_by_name {
            if let Some(local_type_info) =
                type_resolver.get_type_info_by_name(&namespace.original, &type_name.original)
            {
                if local_type_info.get_type_meta_ref().get_bytes() == remote_type_def {
                    local_type_info
                } else {
                    remote_schema_key =
                        Some(self.check_remote_type_meta_limit(&type_meta, config)?);
                    Rc::new(TypeInfo::from_remote_meta(
                        type_meta.clone(),
                        Some(local_type_info.get_harness()),
                        Some(local_type_info.get_type_id() as u32),
                        Some(local_type_info.get_user_type_id()),
                    ))
                }
            } else {
                remote_schema_key = Some(self.check_remote_type_meta_limit(&type_meta, config)?);
                Rc::new(TypeInfo::from_remote_meta(
                    type_meta.clone(),
                    None,
                    None,
                    None,
                ))
            }
        } else {
            let type_id = type_meta.get_type_id();
            let user_type_id = type_meta.get_user_type_id();
            let local_type_info = if user_type_id != NO_USER_TYPE_ID {
                type_resolver.get_user_type_info_by_id(user_type_id)
            } else {
                type_resolver.get_type_info_by_id(type_id)
            };
            if let Some(local_type_info) = local_type_info {
                if local_type_info.get_type_meta_ref().get_bytes() == remote_type_def {
                    local_type_info
                } else {
                    remote_schema_key =
                        Some(self.check_remote_type_meta_limit(&type_meta, config)?);
                    Rc::new(TypeInfo::from_remote_meta(
                        type_meta.clone(),
                        Some(local_type_info.get_harness()),
                        Some(local_type_info.get_type_id() as u32),
                        Some(local_type_info.get_user_type_id()),
                    ))
                }
            } else {
                remote_schema_key = Some(self.check_remote_type_meta_limit(&type_meta, config)?);
                Rc::new(TypeInfo::from_remote_meta(
                    type_meta.clone(),
                    None,
                    None,
                    None,
                ))
            }
        };

        self.parsed_type_infos
            .insert(meta_header, type_info.clone());
        self.cached_meta_header = meta_header;
        self.cached_type_info = Some(type_info.clone());
        self.reading_type_infos.push(type_info.clone());
        if let Some(remote_schema_key) = remote_schema_key {
            self.record_remote_type_meta(remote_schema_key);
        }
        Ok(type_info)
    }

    #[cold]
    #[inline(never)]
    fn check_remote_type_meta_limit(
        &self,
        type_meta: &TypeMeta,
        config: &Config,
    ) -> Result<String, Error> {
        let namespace = type_meta.get_namespace();
        let type_name = type_meta.get_type_name();
        let key = if !namespace.original.is_empty() || !type_name.original.is_empty() {
            format!("n{}\0{}", namespace.original, type_name.original)
        } else {
            format!("i{}", type_meta.get_user_type_id())
        };

        let versions_for_type = self
            .remote_schema_versions_by_type
            .get(&key)
            .copied()
            .unwrap_or(0);
        if versions_for_type >= config.max_schema_versions_per_type() {
            return Err(Error::invalid_data(format!(
                "remote schema version limit exceeded for one type. The data may be malicious. If the data is not malicious, please increase max_schema_versions_per_type={}",
                config.max_schema_versions_per_type()
            )));
        }

        let accepted_type_count =
            self.remote_schema_versions_by_type.len() + if versions_for_type == 0 { 1 } else { 0 };
        let global_limit = usize::max(
            MIN_REMOTE_TYPE_META_LIMIT,
            accepted_type_count * config.max_average_schema_versions_per_type(),
        );
        if self.total_accepted_schema_versions >= global_limit {
            return Err(Error::invalid_data(format!(
                "remote schema version limit exceeded globally. The data may be malicious. If the data is not malicious, please increase max_average_schema_versions_per_type={}",
                config.max_average_schema_versions_per_type()
            )));
        }

        Ok(key)
    }

    fn record_remote_type_meta(&mut self, key: String) {
        let versions_for_type = self
            .remote_schema_versions_by_type
            .get(&key)
            .copied()
            .unwrap_or(0);
        self.remote_schema_versions_by_type
            .insert(key, versions_for_type + 1);
        self.total_accepted_schema_versions += 1;
    }

    #[inline(always)]
    pub fn reset(&mut self) {
        self.reading_type_infos.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;
    use crate::context::{ReadContext, WriteContext};
    use crate::meta::{
        FieldInfo, FieldType, MetaString, NAMESPACE_ENCODER, NAMESPACE_ENCODINGS,
        TYPE_NAME_ENCODER, TYPE_NAME_ENCODINGS,
    };
    use crate::serializer::{ForyDefault, Serializer};
    use crate::TypeId;

    struct LocalExt;

    impl ForyDefault for LocalExt {
        fn fory_default() -> Self {
            LocalExt
        }
    }

    impl Serializer for LocalExt {
        fn fory_write_data(&self, _context: &mut WriteContext) -> Result<(), Error> {
            Ok(())
        }

        fn fory_read_data(_context: &mut ReadContext) -> Result<Self, Error> {
            Ok(LocalExt)
        }

        fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<TypeId, Error> {
            Self::fory_get_type_id(type_resolver)
        }

        fn as_any(&self) -> &dyn std::any::Any {
            self
        }
    }

    fn read_type_def(
        resolver: &mut MetaReaderResolver,
        config: &Config,
        type_def: &[u8],
    ) -> Result<Rc<TypeInfo>, Error> {
        let type_resolver = TypeResolver::default();
        read_type_def_with_type_resolver(resolver, config, &type_resolver, type_def)
    }

    fn read_type_def_with_type_resolver(
        resolver: &mut MetaReaderResolver,
        config: &Config,
        type_resolver: &TypeResolver,
        type_def: &[u8],
    ) -> Result<Rc<TypeInfo>, Error> {
        let mut bytes = vec![];
        let mut writer = Writer::from_buffer(&mut bytes);
        writer.write_var_u32(0);
        writer.write_bytes(type_def);
        let mut reader = Reader::new(&bytes);
        resolver.read_type_meta(&mut reader, type_resolver, config)
    }

    #[test]
    fn type_meta_field_limit_rejects_large_struct() {
        let meta = TypeMeta::new(
            TypeId::STRUCT as u32,
            9001,
            MetaString::get_empty().clone(),
            MetaString::get_empty().clone(),
            false,
            vec![
                FieldInfo::new("a", FieldType::new(crate::type_id::INT32, false, vec![])),
                FieldInfo::new("b", FieldType::new(crate::type_id::INT32, false, vec![])),
            ],
        )
        .unwrap();
        let config = Config {
            max_type_fields: 1,
            ..Default::default()
        };
        let err = read_type_def(
            &mut MetaReaderResolver::default(),
            &config,
            meta.get_bytes(),
        )
        .unwrap_err()
        .to_string();
        assert!(err.contains("max_type_fields"));
    }

    #[test]
    fn type_meta_body_limit_rejects_large_metadata() {
        let meta = TypeMeta::new(
            TypeId::STRUCT as u32,
            9001,
            MetaString::get_empty().clone(),
            MetaString::get_empty().clone(),
            false,
            vec![FieldInfo::new(
                "a",
                FieldType::new(crate::type_id::INT32, false, vec![]),
            )],
        )
        .unwrap();
        let config = Config {
            max_type_meta_bytes: 1,
            ..Default::default()
        };
        let err = read_type_def(
            &mut MetaReaderResolver::default(),
            &config,
            meta.get_bytes(),
        )
        .unwrap_err()
        .to_string();
        assert!(err.contains("max_type_meta_bytes"));
    }

    #[test]
    fn schema_limit_tracks_unknown_struct_types_separately() {
        fn type_def(user_type_id: u32, field_name: &str) -> Vec<u8> {
            TypeMeta::new(
                TypeId::STRUCT as u32,
                user_type_id,
                MetaString::get_empty().clone(),
                MetaString::get_empty().clone(),
                false,
                vec![FieldInfo::new(
                    field_name,
                    FieldType::new(crate::type_id::INT32, false, vec![]),
                )],
            )
            .unwrap()
            .get_bytes()
            .to_vec()
        }

        let config = Config {
            max_schema_versions_per_type: 1,
            ..Default::default()
        };

        let mut resolver = MetaReaderResolver::default();
        read_type_def(&mut resolver, &config, &type_def(9001, "a")).unwrap();
        read_type_def(&mut resolver, &config, &type_def(9002, "a")).unwrap();

        let err = read_type_def(&mut resolver, &config, &type_def(9001, "b"))
            .unwrap_err()
            .to_string();
        assert!(err.contains("max_schema_versions_per_type"));
    }

    #[test]
    fn schema_limit_rejects_extra_versions_for_type() {
        let meta = TypeMeta::new(
            TypeId::STRUCT as u32,
            9001,
            MetaString::get_empty().clone(),
            MetaString::get_empty().clone(),
            false,
            vec![FieldInfo::new(
                "a",
                FieldType::new(crate::type_id::INT32, false, vec![]),
            )],
        )
        .unwrap();
        let type_def = meta.get_bytes().to_vec();

        let config = Config {
            max_schema_versions_per_type: 1,
            ..Default::default()
        };
        let mut resolver = MetaReaderResolver::default();
        let mut bytes = vec![];
        let mut writer = Writer::from_buffer(&mut bytes);
        writer.write_var_u32(0);
        writer.write_bytes(&type_def);
        let mut reader = Reader::new(&bytes);
        resolver
            .read_type_meta(&mut reader, &TypeResolver::default(), &config)
            .unwrap();

        let changed = TypeMeta::new(
            TypeId::STRUCT as u32,
            9001,
            MetaString::get_empty().clone(),
            MetaString::get_empty().clone(),
            false,
            vec![FieldInfo::new(
                "b",
                FieldType::new(crate::type_id::INT32, false, vec![]),
            )],
        )
        .unwrap();
        let mut bytes = vec![];
        let mut writer = Writer::from_buffer(&mut bytes);
        writer.write_var_u32(0);
        writer.write_bytes(changed.get_bytes());
        let mut reader = Reader::new(&bytes);
        let err = resolver
            .read_type_meta(&mut reader, &TypeResolver::default(), &config)
            .unwrap_err()
            .to_string();
        assert!(err.contains("max_schema_versions_per_type"));
    }

    #[test]
    fn schema_limit_check_is_not_recorded() {
        let config = Config {
            max_schema_versions_per_type: 1,
            ..Default::default()
        };
        let mut resolver = MetaReaderResolver::default();
        let checked = TypeMeta::new(
            TypeId::STRUCT as u32,
            9001,
            MetaString::get_empty().clone(),
            MetaString::get_empty().clone(),
            false,
            vec![FieldInfo::new(
                "a",
                FieldType::new(crate::type_id::INT32, false, vec![]),
            )],
        )
        .unwrap();
        let accepted = TypeMeta::new(
            TypeId::STRUCT as u32,
            9001,
            MetaString::get_empty().clone(),
            MetaString::get_empty().clone(),
            false,
            vec![FieldInfo::new(
                "b",
                FieldType::new(crate::type_id::INT32, false, vec![]),
            )],
        )
        .unwrap();

        resolver
            .check_remote_type_meta_limit(&checked, &config)
            .unwrap();

        let mut bytes = vec![];
        let mut writer = Writer::from_buffer(&mut bytes);
        writer.write_var_u32(0);
        writer.write_bytes(accepted.get_bytes());
        let mut reader = Reader::new(&bytes);
        resolver
            .read_type_meta(&mut reader, &TypeResolver::default(), &config)
            .unwrap();
    }

    #[test]
    fn non_struct_type_meta_uses_limit() {
        let config = Config {
            max_schema_versions_per_type: 1,
            ..Default::default()
        };
        let mut resolver = MetaReaderResolver::default();
        let namespace = NAMESPACE_ENCODER
            .encode_with_encodings("example", NAMESPACE_ENCODINGS)
            .unwrap();
        let type_name = TYPE_NAME_ENCODER
            .encode_with_encodings("RemoteEnum", TYPE_NAME_ENCODINGS)
            .unwrap();
        let first = TypeMeta::new(
            TypeId::NAMED_ENUM as u32,
            NO_USER_TYPE_ID,
            namespace.clone(),
            type_name.clone(),
            true,
            vec![],
        )
        .unwrap();
        let second = TypeMeta::new(
            TypeId::NAMED_EXT as u32,
            NO_USER_TYPE_ID,
            namespace,
            type_name,
            true,
            vec![],
        )
        .unwrap();

        let key = resolver
            .check_remote_type_meta_limit(&first, &config)
            .unwrap();
        resolver.record_remote_type_meta(key);

        let err = resolver
            .check_remote_type_meta_limit(&second, &config)
            .unwrap_err()
            .to_string();
        assert!(err.contains("max_schema_versions_per_type"));
    }

    #[test]
    fn exact_local_non_struct_type_meta_bypasses_limit() {
        let config = Config {
            max_schema_versions_per_type: 1,
            ..Default::default()
        };
        let mut type_resolver = TypeResolver::default();
        type_resolver
            .register_serializer_by_name::<LocalExt>("example.SharedExt")
            .unwrap();
        let type_resolver = type_resolver.build_final_type_resolver().unwrap();
        let local_info = type_resolver
            .get_type_info_by_name("example", "SharedExt")
            .unwrap();
        let exact = local_info.get_type_meta_ref().get_bytes().to_vec();

        let mut resolver = MetaReaderResolver::default();
        read_type_def_with_type_resolver(&mut resolver, &config, &type_resolver, &exact).unwrap();

        let namespace = NAMESPACE_ENCODER
            .encode_with_encodings("example", NAMESPACE_ENCODINGS)
            .unwrap();
        let type_name = TYPE_NAME_ENCODER
            .encode_with_encodings("SharedExt", TYPE_NAME_ENCODINGS)
            .unwrap();
        let second = TypeMeta::new(
            TypeId::NAMED_ENUM as u32,
            NO_USER_TYPE_ID,
            namespace,
            type_name,
            true,
            vec![],
        )
        .unwrap();
        resolver
            .check_remote_type_meta_limit(&second, &config)
            .unwrap();
    }
}
