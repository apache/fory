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
use crate::meta::{MetaString, TypeMeta};
use crate::resolver::meta_resolver::{MetaReaderResolver, MetaWriterResolver};
use crate::resolver::metastring_resolver::{
    MetaStringBytes, MetaStringReaderResolver, MetaStringWriterResolver,
};
use crate::resolver::ref_resolver::{RefReader, RefWriter};
use crate::resolver::type_resolver::{Harness, TypeResolver};
use std::sync::{Arc, Mutex};

pub struct WriteContext {
    // Replicated environment fields (direct access, no Arc indirection for flags)
    type_resolver: TypeResolver,
    compatible: bool,
    share_meta: bool,
    compress_string: bool,
    xlang: bool,

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
    ) -> WriteContext {
        WriteContext {
            type_resolver,
            compatible,
            share_meta,
            compress_string,
            xlang,
            writer,
            meta_resolver: MetaWriterResolver::default(),
            meta_string_resolver: MetaStringWriterResolver::default(),
            ref_writer: RefWriter::new(),
        }
    }

    pub fn new_from_fory(writer: Writer, fory: &Fory) -> WriteContext {
        WriteContext {
            type_resolver: fory.get_type_resolver().clone(),
            compatible: fory.is_compatible(),
            share_meta: fory.is_share_meta(),
            compress_string: fory.is_compress_string(),
            xlang: fory.is_xlang(),
            writer,
            meta_resolver: MetaWriterResolver::default(),
            meta_string_resolver: MetaStringWriterResolver::default(),
            ref_writer: RefWriter::new(),
        }
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
        concrete_type_id: std::any::TypeId,
    ) -> Result<Arc<Harness>, Error> {
        use crate::types::TypeId as ForyTypeId;

        let type_info = self.type_resolver.get_type_info(concrete_type_id)?;
        let fory_type_id = type_info.get_type_id();

        // Clone out the data we need from `type_info` to break the borrow
        let namespace = type_info.get_namespace().clone();
        let type_name = type_info.get_type_name().clone();
        let registered_by_name = type_info.is_registered_by_name();

        if registered_by_name {
            if fory_type_id & 0xff == ForyTypeId::NAMED_STRUCT as u32 {
                self.writer.write_varuint32(fory_type_id);
                if self.is_share_meta() {
                    let meta_index = self.push_meta(concrete_type_id)? as u32;
                    self.writer.write_varuint32(meta_index);
                } else {
                    namespace.write_to(&mut self.writer);
                    type_name.write_to(&mut self.writer);
                }
            } else if fory_type_id & 0xff == ForyTypeId::NAMED_COMPATIBLE_STRUCT as u32 {
                self.writer.write_varuint32(fory_type_id);
                let meta_index = self.push_meta(concrete_type_id)? as u32;
                self.writer.write_varuint32(meta_index);
            } else {
                self.writer.write_varuint32(u32::MAX);
                namespace.write_to(&mut self.writer);
                type_name.write_to(&mut self.writer);
            }
            self.type_resolver
                .get_name_harness(&namespace, &type_name)
                .ok_or_else(|| Error::TypeError("Name harness not found".into()))
        } else {
            if fory_type_id & 0xff == ForyTypeId::COMPATIBLE_STRUCT as u32 {
                self.writer.write_varuint32(fory_type_id);
                let meta_index = self.push_meta(concrete_type_id)? as u32;
                self.writer.write_varuint32(meta_index);
            } else {
                self.writer.write_varuint32(fory_type_id);
            }
            self.type_resolver
                .get_harness(fory_type_id)
                .ok_or_else(|| Error::TypeError("ID harness not found".into()))
        }
    }

    #[inline(always)]
    pub fn write_meta_string_bytes(&mut self, ms: &MetaString) -> Result<(), Error> {
        self.meta_string_resolver
            .write_meta_string_bytes(&mut self.writer, ms)
    }

    #[inline(always)]
    pub fn reset(&mut self) {
        self.meta_resolver.reset();
        self.ref_writer.reset();
        self.writer.reset();
    }
}

pub struct ReadContext {
    // Replicated environment fields (direct access, no Arc indirection for flags)
    type_resolver: TypeResolver,
    compatible: bool,
    share_meta: bool,
    xlang: bool,
    max_dyn_depth: u32,

    // Context-specific fields
    pub reader: Reader,
    pub meta_resolver: MetaReaderResolver,
    meta_string_resolver: MetaStringReaderResolver,
    pub ref_reader: RefReader,
    current_depth: u32,
}

impl ReadContext {
    pub fn new(
        reader: Reader,
        type_resolver: TypeResolver,
        compatible: bool,
        share_meta: bool,
        xlang: bool,
        max_dyn_depth: u32,
    ) -> ReadContext {
        ReadContext {
            type_resolver,
            compatible,
            share_meta,
            xlang,
            max_dyn_depth,
            reader,
            meta_resolver: MetaReaderResolver::default(),
            meta_string_resolver: MetaStringReaderResolver::default(),
            ref_reader: RefReader::new(),
            current_depth: 0,
        }
    }

    pub fn new_from_fory(reader: Reader, fory: &Fory) -> ReadContext {
        ReadContext {
            type_resolver: fory.get_type_resolver().clone(),
            compatible: fory.is_compatible(),
            share_meta: fory.is_share_meta(),
            xlang: fory.is_xlang(),
            max_dyn_depth: fory.get_max_dyn_depth(),
            reader,
            meta_resolver: MetaReaderResolver::default(),
            meta_string_resolver: MetaStringReaderResolver::default(),
            ref_reader: RefReader::new(),
            current_depth: 0,
        }
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
    pub fn get_meta(&self, type_index: usize) -> &Arc<TypeMeta> {
        self.meta_resolver.get(type_index)
    }

    #[inline(always)]
    pub fn load_meta(&mut self, offset: usize) -> Result<usize, Error> {
        self.meta_resolver.load(
            &self.type_resolver,
            &mut Reader::new(&self.reader.slice_after_cursor()[offset..]),
        )
    }

    pub fn read_any_typeinfo(&mut self) -> Result<Arc<Harness>, Error> {
        use crate::types::TypeId as ForyTypeId;

        let fory_type_id = self.reader.read_varuint32()?;

        if fory_type_id == u32::MAX {
            let namespace = self.meta_resolver.read_metastring(&mut self.reader)?;
            let type_name = self.meta_resolver.read_metastring(&mut self.reader)?;
            self.type_resolver
                .get_name_harness(&namespace, &type_name)
                .ok_or_else(|| Error::TypeError("Name harness not found".into()))
        } else if fory_type_id & 0xff == ForyTypeId::NAMED_STRUCT as u32 {
            if self.is_share_meta() {
                let _meta_index = self.reader.read_varuint32();
            } else {
                let namespace = self.meta_resolver.read_metastring(&mut self.reader)?;
                let type_name = self.meta_resolver.read_metastring(&mut self.reader)?;
                return self
                    .type_resolver
                    .get_name_harness(&namespace, &type_name)
                    .ok_or_else(|| Error::TypeError("Name harness not found".into()));
            }
            self.type_resolver
                .get_harness(fory_type_id)
                .ok_or_else(|| Error::TypeError("ID harness not found".into()))
        } else if fory_type_id & 0xff == ForyTypeId::NAMED_COMPATIBLE_STRUCT as u32
            || fory_type_id & 0xff == ForyTypeId::COMPATIBLE_STRUCT as u32
        {
            let _meta_index = self.reader.read_varuint32();
            self.type_resolver
                .get_harness(fory_type_id)
                .ok_or_else(|| Error::TypeError("ID harness not found".into()))
        } else {
            self.type_resolver
                .get_harness(fory_type_id)
                .ok_or_else(|| Error::TypeError("ID harness not found".into()))
        }
    }

    pub fn read_meta_string_bytes(&mut self) -> Result<MetaStringBytes, Error> {
        self.meta_string_resolver
            .read_meta_string_bytes(&mut self.reader)
    }

    #[inline(always)]
    pub fn inc_depth(&mut self) -> Result<(), Error> {
        self.current_depth += 1;
        if self.current_depth > self.max_dyn_depth() {
            return Err(Error::DepthExceed(
                format!(
                    "Maximum dynamic object nesting depth ({}) exceeded. Current depth: {}. \
                    This may indicate a circular reference or overly deep object graph. \
                    Consider increasing max_dyn_depth if this is expected.",
                    self.max_dyn_depth(),
                    self.current_depth
                )
                .into(),
            ));
        }
        Ok(())
    }

    #[inline(always)]
    pub fn dec_depth(&mut self) {
        self.current_depth = self.current_depth.saturating_sub(1);
    }

    #[inline(always)]
    pub fn reset(&mut self) {
        self.reader.reset();
        self.meta_resolver.reset();
        self.ref_reader.reset();
    }
}

pub struct Pool<T> {
    items: Mutex<Vec<T>>,
    factory: Box<dyn Fn() -> T + Send + Sync>,
}

impl<T> Pool<T> {
    pub fn new<F>(factory: F) -> Self
    where
        F: Fn() -> T + Send + Sync + 'static,
    {
        Pool {
            items: Mutex::new(vec![]),
            factory: Box::new(factory),
        }
    }

    #[inline(always)]
    pub fn get(&self) -> T {
        let item = self
            .items
            .lock()
            .unwrap()
            .pop()
            .unwrap_or_else(|| (self.factory)());
        // println!("Object address: {:p}", &item);
        item
    }

    // put back manually
    #[inline(always)]
    pub fn put(&self, item: T) {
        self.items.lock().unwrap().push(item);
    }
}
