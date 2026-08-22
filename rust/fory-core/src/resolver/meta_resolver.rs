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
    // Provider and target indexes share one Rc<TypeInfo>; pointer identity keeps
    // their streaming metadata references in one sequence without probing maps.
    type_info_index_map: HashMap<*const TypeInfo, usize>,
    type_index_index_map: Vec<usize>,
    next_index: usize,
}

const MIN_REMOTE_TYPE_META_VERSIONS: u64 = 8192;
const MAX_REMOTE_TYPE_META_KEYS: usize = 8192;
const NO_WRITTEN_TYPE_INDEX: usize = usize::MAX;

#[allow(dead_code)]
impl MetaWriterResolver {
    /// Write type meta inline using streaming protocol.
    /// Returns the index assigned to this type.
    #[inline(always)]
    pub fn write_type_meta(
        &mut self,
        writer: &mut Writer,
        provider_type_id: std::any::TypeId,
        type_resolver: &TypeResolver,
    ) -> Result<(), Error> {
        let type_info = type_resolver.get_provider_type_info(&provider_type_id)?;
        self.write_resolved_type_meta(writer, &type_info)
    }

    #[inline(always)]
    pub(crate) fn write_resolved_type_meta(
        &mut self,
        writer: &mut Writer,
        type_info: &Rc<TypeInfo>,
    ) -> Result<(), Error> {
        let identity = Rc::as_ptr(type_info);
        match self.type_info_index_map.get(&identity) {
            Some(&index) => {
                // Reference to previously written type: (index << 1) | 1, LSB=1
                writer.write_var_u32(((index as u32) << 1) | 1);
            }
            None => {
                // New type: index << 1, LSB=0, followed by TypeMeta bytes inline
                let index = self.next_index;
                self.next_index += 1;
                writer.write_var_u32((index as u32) << 1);
                self.type_info_index_map.insert(identity, index);
                let type_def = type_info.get_type_def();
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
        self.type_info_index_map.clear();
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
    total_accepted_schema_versions: u64,
    cached_meta_hash: i64,
    cached_type_info: Option<Rc<TypeInfo>>,
}

#[derive(Clone, Copy)]
enum TypeInfoExpectation<'a> {
    Any,
    Exact(&'a Rc<TypeInfo>),
    Structural(&'a Rc<TypeInfo>),
}

impl<'a> TypeInfoExpectation<'a> {
    #[inline(always)]
    fn local(self) -> Option<&'a Rc<TypeInfo>> {
        match self {
            Self::Any => None,
            Self::Exact(type_info) | Self::Structural(type_info) => Some(type_info),
        }
    }

    #[inline(always)]
    fn allows_stub(self) -> bool {
        matches!(self, Self::Structural(_))
    }
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
        self.read_type_meta_with_expected(reader, type_resolver, config, TypeInfoExpectation::Any)
    }

    #[inline(always)]
    pub(crate) fn read_type_meta_for(
        &mut self,
        reader: &mut Reader,
        type_resolver: &TypeResolver,
        config: &Config,
        expected: &Rc<TypeInfo>,
    ) -> Result<Rc<TypeInfo>, Error> {
        self.read_type_meta_with_expected(
            reader,
            type_resolver,
            config,
            TypeInfoExpectation::Exact(expected),
        )
    }

    #[inline(always)]
    pub(crate) fn read_struct_type_meta_for(
        &mut self,
        reader: &mut Reader,
        type_resolver: &TypeResolver,
        config: &Config,
        expected: &Rc<TypeInfo>,
    ) -> Result<Rc<TypeInfo>, Error> {
        self.read_type_meta_with_expected(
            reader,
            type_resolver,
            config,
            TypeInfoExpectation::Structural(expected),
        )
    }

    #[inline(always)]
    fn read_type_meta_with_expected(
        &mut self,
        reader: &mut Reader,
        type_resolver: &TypeResolver,
        config: &Config,
        expected: TypeInfoExpectation<'_>,
    ) -> Result<Rc<TypeInfo>, Error> {
        let index_marker = reader.read_var_u32()?;
        let is_ref = (index_marker & 1) == 1;
        let index = (index_marker >> 1) as usize;

        if is_ref {
            // Reference to previously read type
            let type_info = self.reading_type_infos.get(index).cloned().ok_or_else(|| {
                Error::type_error(format!("TypeInfo not found for type index: {}", index))
            })?;
            Self::check_expected_owner(&type_info, expected)?;
            Ok(type_info)
        } else {
            // New type - read TypeMeta inline
            let meta_header = reader.read_i64()?;
            let meta_hash = TypeMeta::header_hash(meta_header);
            if let Some(type_info) = expected
                .local()
                .filter(|type_info| type_info.get_type_meta_ref().get_hash() == meta_hash)
                .cloned()
            {
                // A statically expected local owner has priority over remote checked-cache hints.
                // The top-52 hash is the schema identity; current low bits only bound this skip.
                TypeMeta::skip_bytes_for_validated_header(reader, meta_header)?;
                self.reading_type_infos.push(type_info.clone());
                return Ok(type_info);
            }
            if let Some(type_info) = self
                .cached_type_info
                .as_ref()
                .filter(|_| self.cached_meta_hash == meta_hash)
                .cloned()
            {
                // The 52-bit header hash is the schema identity. Low header bits describe only
                // this frame, so a checked hit uses them solely to skip its opaque body.
                Self::check_expected_owner(&type_info, expected)?;
                TypeMeta::skip_bytes_for_validated_header(reader, meta_header)?;
                self.reading_type_infos.push(type_info.clone());
                return Ok(type_info);
            }
            if let Some(type_info) = self.parsed_type_infos.get(&meta_hash).cloned() {
                // Entries reach this cache only after successful TypeMeta parse, body-hash
                // validation, policy checks, and publication on the miss path.
                Self::check_expected_owner(&type_info, expected)?;
                TypeMeta::skip_bytes_for_validated_header(reader, meta_header)?;
                self.cached_meta_hash = meta_hash;
                self.cached_type_info = Some(type_info.clone());
                self.reading_type_infos.push(type_info.clone());
                Ok(type_info)
            } else {
                self.read_remote_type_meta(
                    reader,
                    type_resolver,
                    config,
                    meta_header,
                    meta_hash,
                    expected,
                )
            }
        }
    }

    #[inline(always)]
    fn check_expected_owner(
        type_info: &TypeInfo,
        expected: TypeInfoExpectation<'_>,
    ) -> Result<(), Error> {
        let Some(expected_type_info) = expected.local() else {
            return Ok(());
        };
        let expected_target = expected_type_info
            .get_harness()
            .target_type_id()
            .ok_or_else(|| Error::type_error("expected TypeInfo has no concrete target"))?;
        let resolved_target = type_info.get_harness().target_type_id();
        if expected.allows_stub()
            && !crate::type_id::is_struct_type_id(type_info.get_type_meta_ref().get_type_id())
        {
            return Err(Self::type_info_kind_mismatch(
                type_info.get_type_meta_ref().get_type_id(),
            ));
        }
        if resolved_target != Some(expected_target)
            && !(expected.allows_stub() && resolved_target.is_none())
        {
            return Err(Self::type_info_owner_mismatch(
                resolved_target,
                expected_target,
            ));
        }
        Ok(())
    }

    #[cold]
    #[inline(never)]
    fn type_info_owner_mismatch(
        resolved: Option<std::any::TypeId>,
        expected: std::any::TypeId,
    ) -> Error {
        Error::type_error(format!(
            "resolved TypeInfo target {:?} does not match declared target {:?}",
            resolved, expected,
        ))
    }

    #[cold]
    #[inline(never)]
    fn type_info_kind_mismatch(resolved: u32) -> Error {
        Error::type_error(format!(
            "resolved TypeInfo wire kind {} is not structural metadata",
            resolved,
        ))
    }

    #[cold]
    #[inline(never)]
    fn read_remote_type_meta(
        &mut self,
        reader: &mut Reader,
        type_resolver: &TypeResolver,
        config: &Config,
        meta_header: i64,
        meta_hash: i64,
        expected: TypeInfoExpectation<'_>,
    ) -> Result<Rc<TypeInfo>, Error> {
        let type_meta = Rc::new(TypeMeta::from_bytes_with_header(
            reader,
            type_resolver,
            meta_header,
            config.max_type_fields(),
            config.max_type_meta_bytes(),
        )?);

        let namespace = type_meta.get_namespace();
        let type_name = type_meta.get_type_name();
        let register_by_name = !namespace.original.is_empty() || !type_name.original.is_empty();
        let remote_schema_key;
        // The body and its hash are validated above. From this point the top-52 hash alone decides
        // whether a resolved local TypeMeta owns this schema; body bytes are not a second identity.
        // A local owner is root-local reading state, not a remote checked-cache publication.
        let type_info = if register_by_name {
            if let Some(local_type_info) =
                type_resolver.get_type_info_by_name(&namespace.original, &type_name.original)
            {
                if local_type_info.get_type_meta_ref().get_hash() == meta_hash {
                    Self::check_expected_owner(&local_type_info, expected)?;
                    self.reading_type_infos.push(local_type_info.clone());
                    return Ok(local_type_info);
                } else {
                    remote_schema_key = self.check_remote_type_meta_limit(&type_meta, config)?;
                    Rc::new(TypeInfo::from_remote_meta(
                        type_meta.clone(),
                        Some(local_type_info.get_harness()),
                        Some(local_type_info.get_type_id() as u32),
                        Some(local_type_info.get_user_type_id()),
                    ))
                }
            } else {
                remote_schema_key = self.check_remote_type_meta_limit(&type_meta, config)?;
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
                if local_type_info.get_type_meta_ref().get_hash() == meta_hash {
                    Self::check_expected_owner(&local_type_info, expected)?;
                    self.reading_type_infos.push(local_type_info.clone());
                    return Ok(local_type_info);
                } else {
                    remote_schema_key = self.check_remote_type_meta_limit(&type_meta, config)?;
                    Rc::new(TypeInfo::from_remote_meta(
                        type_meta.clone(),
                        Some(local_type_info.get_harness()),
                        Some(local_type_info.get_type_id() as u32),
                        Some(local_type_info.get_user_type_id()),
                    ))
                }
            } else {
                remote_schema_key = self.check_remote_type_meta_limit(&type_meta, config)?;
                Rc::new(TypeInfo::from_remote_meta(
                    type_meta.clone(),
                    None,
                    None,
                    None,
                ))
            }
        };

        // A concrete remote harness must own the declared local target. Compatible structural
        // mapping alone may retain an unregistered stub, and this decision must precede root or
        // persistent checked-cache publication and schema-version accounting.
        Self::check_expected_owner(&type_info, expected)?;
        self.parsed_type_infos.insert(meta_hash, type_info.clone());
        self.cached_meta_hash = meta_hash;
        self.cached_type_info = Some(type_info.clone());
        self.reading_type_infos.push(type_info.clone());
        self.record_remote_type_meta(remote_schema_key);
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
        // Reaching the key cap must not disable schema evolution for keys that were already
        // accepted.
        if versions_for_type == 0
            && self.remote_schema_versions_by_type.len() >= MAX_REMOTE_TYPE_META_KEYS
        {
            return Err(Error::invalid_data(
                "remote logical TypeMeta key limit exceeded. The data may be malicious",
            ));
        }
        if versions_for_type >= config.max_schema_versions_per_type() {
            return Err(Error::invalid_data(format!(
                "remote schema version limit exceeded for one type. The data may be malicious. If the data is not malicious, please increase max_schema_versions_per_type={}",
                config.max_schema_versions_per_type()
            )));
        }

        let accepted_type_count = (self.remote_schema_versions_by_type.len()
            + if versions_for_type == 0 { 1 } else { 0 }) as u64;
        let max_average = config.max_average_schema_versions_per_type() as u64;
        let reached_average_limit = max_average == 0
            || self.total_accepted_schema_versions / max_average >= accepted_type_count;
        if self.total_accepted_schema_versions == u64::MAX
            || (self.total_accepted_schema_versions >= MIN_REMOTE_TYPE_META_VERSIONS
                && reached_average_limit)
        {
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
        // The cold miss check rejects u64::MAX before its caller publishes the TypeInfo and reaches
        // this mutation.
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
    use crate::serializer::{Serializer, StructSerializer};
    use crate::TypeId;

    const LOCAL_COLLISION_FIELD: &str = "!AsA3daaa";
    const REMOTE_COLLISION_FIELD: &str = "!aR5Ocaaaa";

    struct LocalExt;

    impl Serializer for LocalExt {
        type Target = Self;

        fn write_data(_value: &Self, _context: &mut WriteContext) -> Result<(), Error> {
            Ok(())
        }

        fn read_data(_context: &mut ReadContext) -> Result<Self, Error> {
            Ok(LocalExt)
        }
    }

    struct CollisionStruct;

    impl Serializer for CollisionStruct {
        type Target = Self;

        fn write_data(_value: &Self, _context: &mut WriteContext) -> Result<(), Error> {
            Ok(())
        }

        fn read_data(_context: &mut ReadContext) -> Result<Self, Error> {
            Ok(CollisionStruct)
        }

        fn static_type_id() -> TypeId {
            TypeId::STRUCT
        }
    }

    impl StructSerializer for CollisionStruct {
        fn type_index() -> u32 {
            9001
        }

        fn fields_info(_type_resolver: &TypeResolver) -> Result<Vec<FieldInfo>, Error> {
            Ok(vec![FieldInfo::new(
                LOCAL_COLLISION_FIELD,
                FieldType::new(crate::type_id::INT32, false, vec![]),
            )])
        }

        fn variants_fields_info(
            _type_resolver: &TypeResolver,
        ) -> Result<Vec<(String, std::any::TypeId, Vec<FieldInfo>)>, Error> {
            Ok(vec![])
        }

        fn sorted_field_names() -> &'static [&'static str] {
            &[LOCAL_COLLISION_FIELD]
        }

        fn read_compatible(
            _context: &mut ReadContext,
            _type_info: &Rc<TypeInfo>,
        ) -> Result<Self, Error> {
            Ok(CollisionStruct)
        }
    }

    struct ForeignStruct;

    impl Serializer for ForeignStruct {
        type Target = Self;

        fn write_data(_value: &Self, _context: &mut WriteContext) -> Result<(), Error> {
            Ok(())
        }

        fn read_data(_context: &mut ReadContext) -> Result<Self, Error> {
            Ok(ForeignStruct)
        }

        fn static_type_id() -> TypeId {
            TypeId::STRUCT
        }
    }

    impl StructSerializer for ForeignStruct {
        fn type_index() -> u32 {
            9002
        }

        fn fields_info(_type_resolver: &TypeResolver) -> Result<Vec<FieldInfo>, Error> {
            Ok(vec![FieldInfo::new(
                "local_b",
                FieldType::new(crate::type_id::INT32, false, vec![]),
            )])
        }

        fn variants_fields_info(
            _type_resolver: &TypeResolver,
        ) -> Result<Vec<(String, std::any::TypeId, Vec<FieldInfo>)>, Error> {
            Ok(vec![])
        }

        fn sorted_field_names() -> &'static [&'static str] {
            &["local_b"]
        }

        fn read_compatible(
            _context: &mut ReadContext,
            _type_info: &Rc<TypeInfo>,
        ) -> Result<Self, Error> {
            Ok(ForeignStruct)
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

    fn remote_struct_meta(user_type_id: u32, field_name: &str) -> TypeMeta {
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
    }

    fn named_struct_meta(namespace: &str, type_name: &str, field_name: &str) -> TypeMeta {
        named_meta(
            TypeId::NAMED_STRUCT,
            namespace,
            type_name,
            vec![FieldInfo::new(
                field_name,
                FieldType::new(crate::type_id::INT32, false, vec![]),
            )],
        )
    }

    fn named_meta(
        type_id: TypeId,
        namespace: &str,
        type_name: &str,
        fields: Vec<FieldInfo>,
    ) -> TypeMeta {
        TypeMeta::new(
            type_id as u32,
            NO_USER_TYPE_ID,
            NAMESPACE_ENCODER
                .encode_with_encodings(namespace, NAMESPACE_ENCODINGS)
                .unwrap(),
            TYPE_NAME_ENCODER
                .encode_with_encodings(type_name, TYPE_NAME_ENCODINGS)
                .unwrap(),
            true,
            fields,
        )
        .unwrap()
    }

    fn read_type_def_for(
        resolver: &mut MetaReaderResolver,
        config: &Config,
        type_resolver: &TypeResolver,
        expected: &Rc<TypeInfo>,
        type_def: &[u8],
    ) -> Result<Rc<TypeInfo>, Error> {
        let mut bytes = vec![];
        let mut writer = Writer::from_buffer(&mut bytes);
        writer.write_var_u32(0);
        writer.write_bytes(type_def);
        let mut reader = Reader::new(&bytes);
        resolver.read_type_meta_for(&mut reader, type_resolver, config, expected)
    }

    fn read_struct_type_def_for(
        resolver: &mut MetaReaderResolver,
        config: &Config,
        type_resolver: &TypeResolver,
        expected: &Rc<TypeInfo>,
        type_def: &[u8],
    ) -> Result<Rc<TypeInfo>, Error> {
        let mut bytes = vec![];
        let mut writer = Writer::from_buffer(&mut bytes);
        writer.write_var_u32(0);
        writer.write_bytes(type_def);
        let mut reader = Reader::new(&bytes);
        resolver.read_struct_type_meta_for(&mut reader, type_resolver, config, expected)
    }

    fn type_def_frame(meta_hash: i64, flags: i64, body_size: usize, fill: u8) -> Vec<u8> {
        assert_eq!(flags & !0xf00, 0);
        let inline_size = body_size.min(0xff);
        let header = (((meta_hash as u64) << 12) | flags as u64 | inline_size as u64) as i64;
        let mut type_def = vec![];
        let mut writer = Writer::from_buffer(&mut type_def);
        writer.write_i64(header);
        if body_size >= 0xff {
            writer.write_var_u32(u32::try_from(body_size - 0xff).unwrap());
        }
        writer.write_bytes(&vec![fill; body_size]);
        type_def
    }

    fn read_type_def_with_cursor(
        resolver: &mut MetaReaderResolver,
        config: &Config,
        type_def: &[u8],
    ) -> (Result<Rc<TypeInfo>, Error>, usize, usize) {
        let mut bytes = vec![];
        let mut writer = Writer::from_buffer(&mut bytes);
        writer.write_var_u32(0);
        writer.write_bytes(type_def);
        let mut reader = Reader::new(&bytes);
        let result = resolver.read_type_meta(&mut reader, &TypeResolver::default(), config);
        (result, reader.get_cursor(), bytes.len())
    }

    fn fill_remote_schema_keys(resolver: &mut MetaReaderResolver, count: usize, versions: usize) {
        assert!(count <= MAX_REMOTE_TYPE_META_KEYS);
        for user_type_id in 0..count {
            resolver
                .remote_schema_versions_by_type
                .insert(format!("i{user_type_id}"), versions);
        }
        resolver.total_accepted_schema_versions = count as u64 * versions as u64;
    }

    #[test]
    fn logical_type_key_cap() {
        let config = Config::default();
        let mut resolver = MetaReaderResolver::default();
        fill_remote_schema_keys(&mut resolver, MAX_REMOTE_TYPE_META_KEYS - 1, 1);

        let last = remote_struct_meta((MAX_REMOTE_TYPE_META_KEYS - 1) as u32, "a");
        read_type_def(&mut resolver, &config, last.get_bytes()).unwrap();
        assert_eq!(
            resolver.remote_schema_versions_by_type.len(),
            MAX_REMOTE_TYPE_META_KEYS
        );
        assert_eq!(
            resolver.total_accepted_schema_versions,
            MAX_REMOTE_TYPE_META_KEYS as u64
        );

        let parsed_count = resolver.parsed_type_infos.len();
        let reading_count = resolver.reading_type_infos.len();
        let cached_hash = resolver.cached_meta_hash;
        let cached_type_info = resolver.cached_type_info.as_ref().map(Rc::as_ptr);
        let rejected = remote_struct_meta(MAX_REMOTE_TYPE_META_KEYS as u32, "a");
        let err = read_type_def(&mut resolver, &config, rejected.get_bytes())
            .unwrap_err()
            .to_string();

        assert!(err.contains("logical TypeMeta key limit"));
        assert_eq!(
            resolver.remote_schema_versions_by_type.len(),
            MAX_REMOTE_TYPE_META_KEYS
        );
        assert_eq!(
            resolver.total_accepted_schema_versions,
            MAX_REMOTE_TYPE_META_KEYS as u64
        );
        assert_eq!(resolver.parsed_type_infos.len(), parsed_count);
        assert_eq!(resolver.reading_type_infos.len(), reading_count);
        assert_eq!(resolver.cached_meta_hash, cached_hash);
        assert_eq!(
            resolver.cached_type_info.as_ref().map(Rc::as_ptr),
            cached_type_info
        );
    }

    #[test]
    fn existing_key_keeps_limits() {
        let mut per_type_resolver = MetaReaderResolver::default();
        fill_remote_schema_keys(&mut per_type_resolver, MAX_REMOTE_TYPE_META_KEYS, 1);
        let per_type_config = Config {
            max_schema_versions_per_type: 1,
            ..Default::default()
        };
        let changed = remote_struct_meta(0, "b");
        let err = read_type_def(
            &mut per_type_resolver,
            &per_type_config,
            changed.get_bytes(),
        )
        .unwrap_err()
        .to_string();
        assert!(err.contains("max_schema_versions_per_type"));

        let mut average_resolver = MetaReaderResolver::default();
        fill_remote_schema_keys(&mut average_resolver, MAX_REMOTE_TYPE_META_KEYS, 3);
        *average_resolver
            .remote_schema_versions_by_type
            .get_mut("i0")
            .unwrap() = 2;
        average_resolver.total_accepted_schema_versions -= 1;
        let average_config = Config {
            max_schema_versions_per_type: 10,
            max_average_schema_versions_per_type: 3,
            ..Default::default()
        };

        let accepted = remote_struct_meta(0, "b");
        read_type_def(&mut average_resolver, &average_config, accepted.get_bytes()).unwrap();
        assert_eq!(average_resolver.total_accepted_schema_versions, 24_576);

        let rejected = remote_struct_meta(0, "c");
        let err = read_type_def(&mut average_resolver, &average_config, rejected.get_bytes())
            .unwrap_err()
            .to_string();
        assert!(err.contains("max_average_schema_versions_per_type"));
        assert_eq!(average_resolver.total_accepted_schema_versions, 24_576);
    }

    #[test]
    fn schema_total_does_not_wrap() {
        let config = Config {
            max_schema_versions_per_type: u32::MAX,
            max_average_schema_versions_per_type: u32::MAX,
            ..Default::default()
        };
        let mut resolver = MetaReaderResolver::default();
        fill_remote_schema_keys(&mut resolver, 1, 1);
        resolver.total_accepted_schema_versions = u64::MAX;
        let meta = remote_struct_meta(0, "b");

        let err = read_type_def(&mut resolver, &config, meta.get_bytes())
            .unwrap_err()
            .to_string();

        assert!(err.contains("remote schema version limit exceeded globally"));
        assert_eq!(resolver.total_accepted_schema_versions, u64::MAX);
        assert_eq!(resolver.remote_schema_versions_by_type.get("i0"), Some(&1));
        assert!(resolver.parsed_type_infos.is_empty());
        assert!(resolver.cached_type_info.is_none());
        assert!(resolver.reading_type_infos.is_empty());
    }

    #[test]
    fn checked_cache_bypasses_key_cap() {
        let config = Config::default();
        let mut resolver = MetaReaderResolver::default();
        fill_remote_schema_keys(&mut resolver, MAX_REMOTE_TYPE_META_KEYS - 1, 1);
        let meta = remote_struct_meta((MAX_REMOTE_TYPE_META_KEYS - 1) as u32, "a");
        let first = read_type_def(&mut resolver, &config, meta.get_bytes()).unwrap();

        resolver.reset();
        resolver.cached_type_info = None;
        let strict_config = Config {
            max_schema_versions_per_type: 1,
            max_average_schema_versions_per_type: 1,
            ..Default::default()
        };
        let cached = read_type_def(&mut resolver, &strict_config, meta.get_bytes()).unwrap();

        assert!(Rc::ptr_eq(&first, &cached));
        assert_eq!(resolver.reading_type_infos.len(), 1);
        assert_eq!(
            resolver.remote_schema_versions_by_type.len(),
            MAX_REMOTE_TYPE_META_KEYS
        );
        assert_eq!(
            resolver.total_accepted_schema_versions,
            MAX_REMOTE_TYPE_META_KEYS as u64
        );
    }

    #[test]
    fn checked_hash_ignores_frame_length() {
        let mut resolver = MetaReaderResolver::default();
        let meta = remote_struct_meta(9001, "a");
        let first = read_type_def(&mut resolver, &Config::default(), meta.get_bytes()).unwrap();
        let parsed_count = resolver.parsed_type_infos.len();
        let accepted_versions = resolver.total_accepted_schema_versions;
        let strict_config = Config {
            max_type_fields: 0,
            max_type_meta_bytes: 1,
            max_schema_versions_per_type: 0,
            max_average_schema_versions_per_type: 0,
            ..Default::default()
        };

        resolver.reset();
        resolver.cached_type_info = None;
        let inline = type_def_frame(meta.get_hash(), 0b101 << 9, 17, 0xff);
        let inline_header = i64::from_le_bytes(inline[..8].try_into().unwrap());
        assert_eq!(TypeMeta::header_hash(inline_header), meta.get_hash());
        let (inline_result, inline_cursor, inline_len) =
            read_type_def_with_cursor(&mut resolver, &strict_config, &inline);
        assert!(Rc::ptr_eq(&first, &inline_result.unwrap()));
        assert_eq!(inline_cursor, inline_len);

        resolver.reset();
        let extended = type_def_frame(meta.get_hash(), 0b1 << 8, 257, 0xa5);
        let extended_header = i64::from_le_bytes(extended[..8].try_into().unwrap());
        assert_eq!(TypeMeta::header_hash(extended_header), meta.get_hash());
        let (extended_result, extended_cursor, extended_len) =
            read_type_def_with_cursor(&mut resolver, &strict_config, &extended);
        assert!(Rc::ptr_eq(&first, &extended_result.unwrap()));
        assert_eq!(extended_cursor, extended_len);
        assert_eq!(resolver.parsed_type_infos.len(), parsed_count);
        assert_eq!(resolver.total_accepted_schema_versions, accepted_versions);
    }

    #[test]
    fn checked_hash_rejects_truncation() {
        let mut resolver = MetaReaderResolver::default();
        let meta = remote_struct_meta(9001, "a");
        read_type_def(&mut resolver, &Config::default(), meta.get_bytes()).unwrap();
        let parsed_count = resolver.parsed_type_infos.len();

        resolver.reset();
        resolver.cached_type_info = None;
        let mut truncated = meta.get_bytes().to_vec();
        truncated.pop();
        let (result, _, _) =
            read_type_def_with_cursor(&mut resolver, &Config::default(), &truncated);

        assert!(result.is_err());
        assert!(resolver.reading_type_infos.is_empty());
        assert!(resolver.cached_type_info.is_none());
        assert_eq!(resolver.parsed_type_infos.len(), parsed_count);
    }

    #[test]
    fn exact_local_bypasses_key_cap() {
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
        fill_remote_schema_keys(&mut resolver, MAX_REMOTE_TYPE_META_KEYS, 1);
        let strict_config = Config {
            max_schema_versions_per_type: 1,
            max_average_schema_versions_per_type: 1,
            ..Default::default()
        };
        let resolved =
            read_type_def_with_type_resolver(&mut resolver, &strict_config, &type_resolver, &exact)
                .unwrap();

        assert!(Rc::ptr_eq(&local_info, &resolved));
        assert_eq!(
            resolver.remote_schema_versions_by_type.len(),
            MAX_REMOTE_TYPE_META_KEYS
        );
        assert_eq!(
            resolver.total_accepted_schema_versions,
            MAX_REMOTE_TYPE_META_KEYS as u64
        );
    }

    #[test]
    fn local_hash_owns_validated_miss() {
        let mut type_resolver = TypeResolver::default();
        type_resolver
            .register_by_name::<CollisionStruct>("e!.C!")
            .unwrap();
        let type_resolver = type_resolver.build_final_type_resolver().unwrap();
        let local_info = type_resolver.get_type_info_by_name("e!", "C!").unwrap();
        let local_type_def = local_info.get_type_meta_ref().get_bytes();
        let namespace = NAMESPACE_ENCODER
            .encode_with_encodings("e!", NAMESPACE_ENCODINGS)
            .unwrap();
        let type_name = TYPE_NAME_ENCODER
            .encode_with_encodings("C!", TYPE_NAME_ENCODINGS)
            .unwrap();
        // These two field names form a valid top-52 TypeMeta hash collision while producing
        // different body lengths. The miss path must validate the remote frame, then select the
        // local owner by that protocol identity without falling back to full encoded bytes.
        let remote_meta = TypeMeta::new(
            TypeId::NAMED_STRUCT as u32,
            NO_USER_TYPE_ID,
            namespace,
            type_name,
            true,
            vec![FieldInfo::new(
                REMOTE_COLLISION_FIELD,
                FieldType::new(crate::type_id::INT32, false, vec![]),
            )],
        )
        .unwrap();
        let remote_type_def = remote_meta.get_bytes();
        let local_header = i64::from_le_bytes(local_type_def[..8].try_into().unwrap());
        let remote_header = i64::from_le_bytes(remote_type_def[..8].try_into().unwrap());
        assert_ne!(local_header & 0xfff, remote_header & 0xfff);
        assert_ne!(&local_type_def[8..], &remote_type_def[8..]);
        assert_eq!(
            TypeMeta::header_hash(remote_header),
            local_info.get_type_meta_ref().get_hash()
        );

        let mut resolver = MetaReaderResolver::default();
        fill_remote_schema_keys(&mut resolver, MAX_REMOTE_TYPE_META_KEYS, 1);
        let cached_hash = resolver.cached_meta_hash;
        let strict_config = Config {
            max_schema_versions_per_type: 1,
            max_average_schema_versions_per_type: 1,
            ..Default::default()
        };
        let resolved = read_type_def_with_type_resolver(
            &mut resolver,
            &strict_config,
            &type_resolver,
            remote_type_def,
        )
        .unwrap();

        assert!(Rc::ptr_eq(&local_info, &resolved));
        assert_eq!(resolver.reading_type_infos.len(), 1);
        assert!(resolver.parsed_type_infos.is_empty());
        assert_eq!(resolver.cached_meta_hash, cached_hash);
        assert!(resolver.cached_type_info.is_none());
        assert!(!resolver
            .remote_schema_versions_by_type
            .contains_key("ne!\0C!"));
        assert_eq!(
            resolver.total_accepted_schema_versions,
            MAX_REMOTE_TYPE_META_KEYS as u64
        );

        // A checked remote owner under the same top-52 identity must not outrank a statically
        // expected local owner, nor may that local hit replace or increment remote cache state.
        let remote_header_hash = TypeMeta::header_hash(remote_header);
        let mut warmed = MetaReaderResolver::default();
        let remote_owner = read_type_def_with_type_resolver(
            &mut warmed,
            &Config::default(),
            &TypeResolver::default(),
            remote_type_def,
        )
        .unwrap();
        let versions = warmed.total_accepted_schema_versions;
        assert_eq!(warmed.parsed_type_infos.len(), 1);
        assert!(Rc::ptr_eq(
            warmed.parsed_type_infos.get(&remote_header_hash).unwrap(),
            &remote_owner,
        ));

        let mut bytes = vec![];
        let mut writer = Writer::from_buffer(&mut bytes);
        writer.write_var_u32(0);
        writer.write_bytes(local_type_def);
        let mut reader = Reader::new(&bytes);
        let resolved = warmed
            .read_type_meta_for(&mut reader, &type_resolver, &Config::default(), &local_info)
            .unwrap();

        assert!(Rc::ptr_eq(&local_info, &resolved));
        assert_eq!(reader.get_cursor(), bytes.len());
        assert_eq!(warmed.total_accepted_schema_versions, versions);
        assert_eq!(warmed.parsed_type_infos.len(), 1);
        assert!(Rc::ptr_eq(
            warmed.parsed_type_infos.get(&remote_header_hash).unwrap(),
            &remote_owner,
        ));
        assert!(Rc::ptr_eq(
            warmed.cached_type_info.as_ref().unwrap(),
            &remote_owner,
        ));
    }

    #[test]
    fn expected_owner_rejects_foreign() {
        let mut type_resolver = TypeResolver::default();
        type_resolver
            .register_by_name::<CollisionStruct>("owner.StructA")
            .unwrap();
        type_resolver
            .register_by_name::<ForeignStruct>("owner.StructB")
            .unwrap();
        let type_resolver = type_resolver.build_final_type_resolver().unwrap();
        let expected = type_resolver
            .get_type_info_by_name("owner", "StructA")
            .unwrap();
        let foreign = type_resolver
            .get_type_info_by_name("owner", "StructB")
            .unwrap();
        let config = Config::default();

        let unknown = named_struct_meta("owner", "Unknown", "remote");
        let mut unknown_resolver = MetaReaderResolver::default();
        let error = read_type_def_for(
            &mut unknown_resolver,
            &config,
            &type_resolver,
            &expected,
            unknown.get_bytes(),
        )
        .unwrap_err();
        assert!(error.to_string().contains("does not match declared target"));
        assert!(unknown_resolver.reading_type_infos.is_empty());
        assert!(unknown_resolver.parsed_type_infos.is_empty());
        assert!(unknown_resolver.cached_type_info.is_none());
        assert!(unknown_resolver.remote_schema_versions_by_type.is_empty());
        assert_eq!(unknown_resolver.total_accepted_schema_versions, 0);

        let mut structural_stub_resolver = MetaReaderResolver::default();
        let structural_stub = read_struct_type_def_for(
            &mut structural_stub_resolver,
            &config,
            &type_resolver,
            &expected,
            unknown.get_bytes(),
        )
        .unwrap();
        assert!(structural_stub.get_harness().target_type_id().is_none());
        assert_eq!(structural_stub_resolver.reading_type_infos.len(), 1);
        assert_eq!(structural_stub_resolver.parsed_type_infos.len(), 1);
        assert_eq!(structural_stub_resolver.total_accepted_schema_versions, 1);

        let non_struct_meta = [
            (TypeId::NAMED_EXT, "UnknownExt"),
            (TypeId::NAMED_ENUM, "UnknownEnum"),
            (TypeId::NAMED_UNION, "UnknownUnion"),
        ];
        for (type_id, type_name) in non_struct_meta {
            let remote = named_meta(type_id, "remote", type_name, vec![]);
            let mut resolver = MetaReaderResolver::default();
            let error = read_struct_type_def_for(
                &mut resolver,
                &config,
                &type_resolver,
                &expected,
                remote.get_bytes(),
            )
            .unwrap_err();
            assert!(error.to_string().contains("not structural metadata"));
            assert!(resolver.reading_type_infos.is_empty());
            assert!(resolver.parsed_type_infos.is_empty());
            assert!(resolver.cached_type_info.is_none());
            assert!(resolver.remote_schema_versions_by_type.is_empty());
            assert_eq!(resolver.total_accepted_schema_versions, 0);
        }

        let same_name_ext = named_meta(TypeId::NAMED_EXT, "owner", "StructA", vec![]);
        for structural in [false, true] {
            let mut resolver = MetaReaderResolver::default();
            let result = if structural {
                read_struct_type_def_for(
                    &mut resolver,
                    &config,
                    &type_resolver,
                    &expected,
                    same_name_ext.get_bytes(),
                )
            } else {
                read_type_def_with_type_resolver(
                    &mut resolver,
                    &config,
                    &type_resolver,
                    same_name_ext.get_bytes(),
                )
            };
            let error = result.unwrap_err();
            assert!(
                error
                    .to_string()
                    .contains("kind does not match registered type metadata"),
                "{error}"
            );
            assert!(resolver.reading_type_infos.is_empty());
            assert!(resolver.parsed_type_infos.is_empty());
            assert!(resolver.cached_type_info.is_none());
            assert!(resolver.remote_schema_versions_by_type.is_empty());
            assert_eq!(resolver.total_accepted_schema_versions, 0);
        }

        let unknown_ext = named_meta(TypeId::NAMED_EXT, "remote", "CachedExt", vec![]);
        let mut ext_resolver = MetaReaderResolver::default();
        let ext_info = read_type_def_with_type_resolver(
            &mut ext_resolver,
            &config,
            &type_resolver,
            unknown_ext.get_bytes(),
        )
        .unwrap();
        assert!(ext_info.get_harness().target_type_id().is_none());
        let mut ref_bytes = vec![];
        Writer::from_buffer(&mut ref_bytes).write_var_u32(1);
        let mut ref_reader = Reader::new(&ref_bytes);
        let error = ext_resolver
            .read_struct_type_meta_for(&mut ref_reader, &type_resolver, &config, &expected)
            .unwrap_err();
        assert!(error.to_string().contains("not structural metadata"));
        assert_eq!(ext_resolver.reading_type_infos.len(), 1);
        assert_eq!(ext_resolver.parsed_type_infos.len(), 1);
        assert_eq!(ext_resolver.total_accepted_schema_versions, 1);

        ext_resolver.reset();
        let parsed_count = ext_resolver.parsed_type_infos.len();
        let accepted_versions = ext_resolver.total_accepted_schema_versions;
        let cached_hash = ext_resolver.cached_meta_hash;
        let cached_owner = ext_resolver.cached_type_info.as_ref().map(Rc::as_ptr);
        let error = read_struct_type_def_for(
            &mut ext_resolver,
            &config,
            &type_resolver,
            &expected,
            unknown_ext.get_bytes(),
        )
        .unwrap_err();
        assert!(error.to_string().contains("not structural metadata"));
        assert!(ext_resolver.reading_type_infos.is_empty());
        assert_eq!(ext_resolver.parsed_type_infos.len(), parsed_count);
        assert_eq!(
            ext_resolver.total_accepted_schema_versions,
            accepted_versions
        );
        assert_eq!(ext_resolver.cached_meta_hash, cached_hash);
        assert_eq!(
            ext_resolver.cached_type_info.as_ref().map(Rc::as_ptr),
            cached_owner
        );

        let changed_foreign = named_struct_meta("owner", "StructB", "remote_b");
        assert_ne!(
            changed_foreign.get_hash(),
            foreign.get_type_meta_ref().get_hash()
        );
        let mut miss_resolver = MetaReaderResolver::default();
        let error = read_type_def_for(
            &mut miss_resolver,
            &config,
            &type_resolver,
            &expected,
            changed_foreign.get_bytes(),
        )
        .unwrap_err();
        assert!(error.to_string().contains("does not match declared target"));
        assert!(miss_resolver.reading_type_infos.is_empty());
        assert!(miss_resolver.parsed_type_infos.is_empty());
        assert!(miss_resolver.cached_type_info.is_none());
        assert!(miss_resolver.remote_schema_versions_by_type.is_empty());
        assert_eq!(miss_resolver.total_accepted_schema_versions, 0);

        let mut structural_miss_resolver = MetaReaderResolver::default();
        let error = read_struct_type_def_for(
            &mut structural_miss_resolver,
            &config,
            &type_resolver,
            &expected,
            changed_foreign.get_bytes(),
        )
        .unwrap_err();
        assert!(error.to_string().contains("does not match declared target"));
        assert!(structural_miss_resolver.reading_type_infos.is_empty());
        assert!(structural_miss_resolver.parsed_type_infos.is_empty());
        assert!(structural_miss_resolver.cached_type_info.is_none());
        assert!(structural_miss_resolver
            .remote_schema_versions_by_type
            .is_empty());
        assert_eq!(structural_miss_resolver.total_accepted_schema_versions, 0);

        let mut ref_resolver = MetaReaderResolver::default();
        let resolved = read_type_def_with_type_resolver(
            &mut ref_resolver,
            &config,
            &type_resolver,
            foreign.get_type_meta_ref().get_bytes(),
        )
        .unwrap();
        assert!(Rc::ptr_eq(&resolved, &foreign));
        let mut ref_bytes = vec![];
        Writer::from_buffer(&mut ref_bytes).write_var_u32(1);
        let mut ref_reader = Reader::new(&ref_bytes);
        let error = ref_resolver
            .read_type_meta_for(&mut ref_reader, &type_resolver, &config, &expected)
            .unwrap_err();
        assert!(error.to_string().contains("does not match declared target"));
        assert_eq!(ref_resolver.reading_type_infos.len(), 1);
        assert!(ref_resolver.parsed_type_infos.is_empty());
        assert!(ref_resolver.cached_type_info.is_none());
        assert_eq!(ref_resolver.total_accepted_schema_versions, 0);

        let mut cache_resolver = MetaReaderResolver::default();
        let cached = read_type_def_with_type_resolver(
            &mut cache_resolver,
            &config,
            &type_resolver,
            changed_foreign.get_bytes(),
        )
        .unwrap();
        assert_eq!(
            cached.get_harness().target_type_id(),
            foreign.get_harness().target_type_id()
        );
        cache_resolver.reset();
        let parsed_count = cache_resolver.parsed_type_infos.len();
        let accepted_versions = cache_resolver.total_accepted_schema_versions;
        let cached_hash = cache_resolver.cached_meta_hash;
        let cached_owner = cache_resolver.cached_type_info.as_ref().map(Rc::as_ptr);
        let error = read_type_def_for(
            &mut cache_resolver,
            &config,
            &type_resolver,
            &expected,
            changed_foreign.get_bytes(),
        )
        .unwrap_err();
        assert!(error.to_string().contains("does not match declared target"));
        assert!(cache_resolver.reading_type_infos.is_empty());
        assert_eq!(cache_resolver.parsed_type_infos.len(), parsed_count);
        assert_eq!(
            cache_resolver.total_accepted_schema_versions,
            accepted_versions
        );
        assert_eq!(cache_resolver.cached_meta_hash, cached_hash);
        assert_eq!(
            cache_resolver.cached_type_info.as_ref().map(Rc::as_ptr),
            cached_owner
        );
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
