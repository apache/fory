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
use crate::fory::Fory;
use crate::meta::MetaString;
use crate::resolver::meta_resolver::{MetaReaderResolver, MetaWriterResolver};
use crate::resolver::metastring_resolver::{MetaStringReaderResolver, MetaStringWriterResolver};
use crate::resolver::ref_resolver::{RefReader, RefWriter};
use crate::resolver::type_resolver::{TypeInfo, TypeResolver};
use crate::types;
use crate::util::Spinlock;
use std::rc::Rc;

/// Serialization state container used on a single thread at a time.
/// Sharing the same instance across threads simultaneously causes undefined behavior.
pub struct WriteContext {
    // Replicated environment fields (direct access, no Arc indirection for flags)
    type_resolver: TypeResolver,
    compatible: bool,
    share_meta: bool,
    compress_string: bool,
    xlang: bool,
    check_struct_version: bool,

    // Context-specific fields
    pub writer: Writer,
    meta_resolver: MetaWriterResolver,
    meta_string_resolver: MetaStringWriterResolver,
    pub ref_writer: RefWriter,
}

impl WriteContext {
    pub fn new(
        writer: Writer,
        type_resolver: TypeResolver,
        compatible: bool,
        share_meta: bool,
        compress_string: bool,
        xlang: bool,
        check_struct_version: bool,
    ) -> WriteContext {
        WriteContext {
            type_resolver,
            compatible,
            share_meta,
            compress_string,
            xlang,
            check_struct_version,
            writer,
            meta_resolver: MetaWriterResolver::default(),
            meta_string_resolver: MetaStringWriterResolver::default(),
            ref_writer: RefWriter::new(),
        }
    }

    pub fn new_from_fory(writer: Writer, fory: &Fory) -> Result<WriteContext, Error> {
        fory.finalize_registration()?;
        Ok(WriteContext {
            type_resolver: fory.build_type_resolver(),
            compatible: fory.is_compatible(),
            share_meta: fory.is_share_meta(),
            compress_string: fory.is_compress_string(),
            xlang: fory.is_xlang(),
            check_struct_version: fory.is_check_struct_version(),
            writer,
            meta_resolver: MetaWriterResolver::default(),
            meta_string_resolver: MetaStringWriterResolver::default(),
            ref_writer: RefWriter::new(),
        })
    }

    /// Get type resolver
    #[inline(always)]
    pub fn get_type_resolver(&self) -> &TypeResolver {
        &self.type_resolver
    }

    #[inline(always)]
    pub fn get_type_info(&self, type_id: &std::any::TypeId) -> Result<Rc<TypeInfo>, Error> {
        self.type_resolver.get_type_info(type_id)
    }

    /// Check if compatible mode is enabled
    #[inline(always)]
    pub fn is_compatible(&self) -> bool {
        self.compatible
    }

    /// Check if meta sharing is enabled
    #[inline(always)]
    pub fn is_share_meta(&self) -> bool {
        self.share_meta
    }

    /// Check if string compression is enabled
    #[inline(always)]
    pub fn is_compress_string(&self) -> bool {
        self.compress_string
    }

    /// Check if cross-language mode is enabled
    #[inline(always)]
    pub fn is_xlang(&self) -> bool {
        self.xlang
    }

    /// Check if class version checking is enabled
    #[inline(always)]
    pub fn is_check_struct_version(&self) -> bool {
        self.check_struct_version
    }

    #[inline(always)]
    pub fn empty(&mut self) -> bool {
        self.meta_resolver.empty()
    }

    #[inline(always)]
    pub fn push_meta(&mut self, type_id: std::any::TypeId) -> Result<usize, Error> {
        self.meta_resolver.push(type_id, &self.type_resolver)
    }

    #[inline(always)]
    pub fn write_meta(&mut self, offset: usize) {
        self.writer.set_bytes(
            offset,
            &((self.writer.len() - offset - 4) as u32).to_le_bytes(),
        );
        self.meta_resolver.to_bytes(&mut self.writer);
    }

    pub fn write_any_typeinfo(
        &mut self,
        fory_type_id: u32,
        concrete_type_id: std::any::TypeId,
    ) -> Result<Rc<TypeInfo>, Error> {
        if types::is_internal_type(fory_type_id) {
            self.writer.write_varuint32(fory_type_id);
            return self
                .type_resolver
                .get_type_info_by_id(fory_type_id)
                .ok_or_else(|| Error::type_error("Type info for internal type not found"));
        }
        let type_info = self.type_resolver.get_type_info(&concrete_type_id)?;
        let fory_type_id = type_info.get_type_id();
        let namespace = type_info.get_namespace();
        let type_name = type_info.get_type_name();
        self.writer.write_varuint32(fory_type_id);
        // should be compiled to jump table generation
        match fory_type_id & 0xff {
            types::NAMED_COMPATIBLE_STRUCT | types::COMPATIBLE_STRUCT => {
                let meta_index =
                    self.meta_resolver
                        .push(concrete_type_id, &self.type_resolver)? as u32;
                self.writer.write_varuint32(meta_index);
            }
            types::NAMED_ENUM | types::NAMED_EXT | types::NAMED_STRUCT => {
                if self.is_share_meta() {
                    let meta_index = self
                        .meta_resolver
                        .push(concrete_type_id, &self.type_resolver)?
                        as u32;
                    self.writer.write_varuint32(meta_index);
                } else {
                    self.write_meta_string_bytes(namespace)?;
                    self.write_meta_string_bytes(type_name)?;
                }
            }
            _ => {
                // default case: do nothing
            }
        }
        Ok(type_info)
    }

    #[inline(always)]
    pub fn write_meta_string_bytes(&mut self, ms: Rc<MetaString>) -> Result<(), Error> {
        self.meta_string_resolver
            .write_meta_string_bytes(&mut self.writer, ms)
    }

    #[inline(always)]
    pub fn reset(&mut self) {
        self.meta_resolver.reset();
        self.meta_string_resolver.reset();
        self.ref_writer.reset();
    }
}

// Safety: WriteContext is only shared across threads via higher-level pooling code that
// ensures single-threaded access while the context is in use. Users must never hold the same
// instance on multiple threads simultaneously; that would violate the invariants and result in
// undefined behavior. Under that assumption, marking it Send/Sync is sound.
unsafe impl Send for WriteContext {}
unsafe impl Sync for WriteContext {}

/// Deserialization state container used on a single thread at a time.
/// Sharing the same instance across threads simultaneously causes undefined behavior.
pub struct ReadContext {
    // Replicated environment fields (direct access, no Arc indirection for flags)
    type_resolver: TypeResolver,
    compatible: bool,
    share_meta: bool,
    xlang: bool,
    max_dyn_depth: u32,
    check_struct_version: bool,

    // Context-specific fields
    pub reader: Reader,
    pub meta_resolver: MetaReaderResolver,
    meta_string_resolver: MetaStringReaderResolver,
    pub ref_reader: RefReader,
    current_depth: u32,
}

// Safety: ReadContext follows the same invariants as WriteContext—external orchestrators ensure
// single-threaded use. Concurrent access to the same instance across threads is forbidden and
// would result in undefined behavior. With exclusive use guaranteed, the Send/Sync markers are safe
// even though Rc is used internally.
unsafe impl Send for ReadContext {}
unsafe impl Sync for ReadContext {}

impl ReadContext {
    pub fn new(
        reader: Reader,
        type_resolver: TypeResolver,
        compatible: bool,
        share_meta: bool,
        xlang: bool,
        max_dyn_depth: u32,
        check_struct_version: bool,
    ) -> ReadContext {
        ReadContext {
            type_resolver,
            compatible,
            share_meta,
            xlang,
            max_dyn_depth,
            check_struct_version,
            reader,
            meta_resolver: MetaReaderResolver::default(),
            meta_string_resolver: MetaStringReaderResolver::default(),
            ref_reader: RefReader::new(),
            current_depth: 0,
        }
    }

    pub fn new_from_fory(reader: Reader, fory: &Fory) -> Result<ReadContext, Error> {
        fory.finalize_registration()?;
        Ok(ReadContext {
            type_resolver: fory.build_type_resolver(),
            compatible: fory.is_compatible(),
            share_meta: fory.is_share_meta(),
            xlang: fory.is_xlang(),
            max_dyn_depth: fory.get_max_dyn_depth(),
            check_struct_version: fory.is_check_struct_version(),
            reader,
            meta_resolver: MetaReaderResolver::default(),
            meta_string_resolver: MetaStringReaderResolver::default(),
            ref_reader: RefReader::new(),
            current_depth: 0,
        })
    }

    /// Get type resolver
    #[inline(always)]
    pub fn get_type_resolver(&self) -> &TypeResolver {
        &self.type_resolver
    }

    /// Check if compatible mode is enabled
    #[inline(always)]
    pub fn is_compatible(&self) -> bool {
        self.compatible
    }

    /// Check if meta sharing is enabled
    #[inline(always)]
    pub fn is_share_meta(&self) -> bool {
        self.share_meta
    }

    /// Check if cross-language mode is enabled
    #[inline(always)]
    pub fn is_xlang(&self) -> bool {
        self.xlang
    }

    /// Check if class version checking is enabled
    #[inline(always)]
    pub fn is_check_struct_version(&self) -> bool {
        self.check_struct_version
    }

    /// Get maximum dynamic depth
    #[inline(always)]
    pub fn max_dyn_depth(&self) -> u32 {
        self.max_dyn_depth
    }

    #[inline(always)]
    pub fn init(&mut self, bytes: &[u8], max_dyn_depth: u32) {
        self.reader.init(bytes);
        self.max_dyn_depth = max_dyn_depth;
        self.current_depth = 0;
    }

    #[inline(always)]
    pub fn get_type_info_by_index(&self, type_index: usize) -> Result<&Rc<TypeInfo>, Error> {
        self.meta_resolver.get(type_index).ok_or_else(|| {
            Error::type_error(format!("TypeInfo not found for type index: {}", type_index))
        })
    }

    #[inline(always)]
    pub fn load_type_meta(&mut self, offset: usize) -> Result<usize, Error> {
        self.meta_resolver.load(
            &self.type_resolver,
            &mut Reader::new(&self.reader.slice_after_cursor()[offset..]),
        )
    }

    pub fn read_any_typeinfo(&mut self) -> Result<Rc<TypeInfo>, Error> {
        let fory_type_id = self.reader.read_varuint32()?;
        // should be compiled to jump table generation
        match fory_type_id & 0xff {
            types::NAMED_COMPATIBLE_STRUCT | types::COMPATIBLE_STRUCT => {
                let meta_index = self.reader.read_varuint32()? as usize;
                let type_info = self.get_type_info_by_index(meta_index)?.clone();
                Ok(type_info)
            }
            types::NAMED_ENUM | types::NAMED_EXT | types::NAMED_STRUCT => {
                if self.is_share_meta() {
                    let meta_index = self.reader.read_varuint32()? as usize;
                    let type_info = self.get_type_info_by_index(meta_index)?.clone();
                    Ok(type_info)
                } else {
                    let namespace = self.read_meta_string()?.to_owned();
                    let type_name = self.read_meta_string()?.to_owned();
                    let rc_namespace = Rc::from(namespace);
                    let rc_type_name = Rc::from(type_name);
                    self.type_resolver
                        .get_type_info_by_msname(rc_namespace, rc_type_name)
                        .ok_or_else(|| Error::type_error("Name harness not found"))
                }
            }
            _ => self
                .type_resolver
                .get_type_info_by_id(fory_type_id)
                .ok_or_else(|| Error::type_error("ID harness not found")),
        }
    }

    #[inline(always)]
    pub fn get_type_info(&self, type_id: &std::any::TypeId) -> Result<Rc<TypeInfo>, Error> {
        self.type_resolver.get_type_info(type_id)
    }

    #[inline(always)]
    pub fn read_meta_string(&mut self) -> Result<&MetaString, Error> {
        self.meta_string_resolver.read_meta_string(&mut self.reader)
    }

    #[inline(always)]
    pub fn inc_depth(&mut self) -> Result<(), Error> {
        self.current_depth += 1;
        if self.current_depth > self.max_dyn_depth() {
            return Err(Error::depth_exceed(format!(
                "Maximum dynamic object nesting depth ({}) exceeded. Current depth: {}. \
                    This may indicate a circular reference or overly deep object graph. \
                    Consider increasing max_dyn_depth if this is expected.",
                self.max_dyn_depth(),
                self.current_depth
            )));
        }
        Ok(())
    }

    #[inline(always)]
    pub fn dec_depth(&mut self) {
        self.current_depth = self.current_depth.saturating_sub(1);
    }

    #[inline(always)]
    pub fn reset(&mut self) {
        self.meta_resolver.reset();
        self.meta_string_resolver.reset();
        self.ref_reader.reset();
    }
}

pub struct Pool<T> {
    items: Spinlock<Vec<T>>,
    factory: Box<dyn Fn() -> T + Send + Sync>,
}

impl<T> Pool<T> {
    pub fn new<F>(factory: F) -> Self
    where
        F: Fn() -> T + Send + Sync + 'static,
    {
        Pool {
            items: Spinlock::new(vec![]),
            factory: Box::new(factory),
        }
    }

    #[inline(always)]
    pub fn get(&self) -> T {
        self.items.lock().pop().unwrap_or_else(|| (self.factory)())
    }

    // put back manually
    #[inline(always)]
    pub fn put(&self, item: T) {
        self.items.lock().push(item);
    }
}
